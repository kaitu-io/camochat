import XCTest
@testable import ChencangShared

private final class ClockBox: @unchecked Sendable {
    private let lock = NSLock()
    private var v: Int64
    init(_ v: Int64) { self.v = v }
    var value: Int64 { get { lock.withLock { v } } set { lock.withLock { v = newValue } } }
}

private struct ClosureSource: ConfigSource {
    let body: @Sendable (String) async -> Data?
    func fetch(_ base: String) async -> Data? { await body(base) }
}

final class ConfigRepositoryTests: XCTestCase {
    private typealias T = ConfigTestSupport
    private let factorySource = "https://f.example/c/"
    private var factory: Data { T.env(2, sources: #"["\#(factorySource)"]"#) }
    private var defaults: FakeAppGroupDefaults!
    private var store: ConfigStore!
    private let clock = ClockBox(1_000_000_000)

    override func setUp() {
        super.setUp()
        defaults = FakeAppGroupDefaults()
        store = ConfigStore(defaults: defaults)
    }

    private func repo(overall: TimeInterval = 15, _ body: @escaping @Sendable (String) async -> Data?) -> ConfigRepository {
        let clock = clock
        return ConfigRepository(factoryEnvelope: factory, store: store, source: ClosureSource(body: body),
                                verify: T.fakeVerify, now: { clock.value }, overallTimeout: overall)
    }

    func testStartsFromFactoryWhenNoCache() async {
        let r = repo { _ in nil }
        XCTAssertEqual(r.current().seq, 2)
    }

    func testCacheWithLowerSeqThanFactoryIsIgnored() async {
        store.saveEnvelope(T.env(1))
        XCTAssertEqual(repo { _ in nil }.current().seq, 2)
    }

    func testCacheWithHigherSeqIsUsed() async {
        store.saveEnvelope(T.env(4))
        XCTAssertEqual(repo { _ in nil }.current().seq, 4)
    }

    func testPicksHighestSeqAcrossSources() async {
        store.saveEnvelope(T.env(2, sources: #"["https://x/c/","https://y/c/"]"#))
        let m: [String: Data] = ["https://x/c/": T.env(2), "https://y/c/": T.env(5), factorySource: T.env(3)]
        let r = repo { m[$0] }
        let res = await r.refresh(force: true)
        XCTAssertEqual(res, .updated)
        XCTAssertEqual(r.current().seq, 5)
        XCTAssertEqual(store.envelope(), T.env(5))
    }

    func testNeverRollsBack() async {
        let cached = T.env(5, sources: #"["https://x/c/"]"#)
        store.saveEnvelope(cached)
        let r = repo { _ in T.env(4) }
        let res = await r.refresh(force: true)
        XCTAssertEqual(res, .unchanged)
        XCTAssertEqual(r.current().seq, 5)
        XCTAssertEqual(store.envelope(), cached)
    }

    func testAllSourcesFailKeepsCurrentReturnsFailed() async {
        let r = repo { _ in nil }
        let res = await r.refresh(force: true)
        XCTAssertEqual(res, .failed)
        XCTAssertEqual(r.current().seq, 2)
        XCTAssertEqual(store.lastSuccessAt(), 0)
    }

    func testInvalidResponsesCountAsFailure() async {
        let r = repo { _ in Data("garbage".utf8) }
        let res = await r.refresh(force: true)
        XCTAssertEqual(res, .failed)
    }

    func testThrottledWithin6hUnlessForce() async {
        let r = repo { _ in T.env(2) }
        var res = await r.refresh()
        XCTAssertEqual(res, .unchanged)
        XCTAssertEqual(store.lastSuccessAt(), clock.value)
        clock.value += 21_599_999
        res = await r.refresh()
        XCTAssertEqual(res, .throttled)
        clock.value += 1
        res = await r.refresh()
        XCTAssertEqual(res, .unchanged)
        res = await r.refresh()
        XCTAssertEqual(res, .throttled)
        res = await r.refresh(force: true)
        XCTAssertEqual(res, .unchanged)
    }

    func testSlowSourceDoesNotBlockBeyondBudget() async {
        store.saveEnvelope(T.env(2, sources: #"["https://slow/c/"]"#))
        // 落后者:不响应取消,直到测试放行(模拟不可取消的阻塞读)。
        let gate = Gate()
        let r = repo(overall: 0.3) { u in
            if u == "https://slow/c/" { await gate.wait(); return nil }
            return T.env(3)
        }
        let t0 = Date()
        let res = await r.refresh(force: true)
        XCTAssertEqual(res, .updated)
        XCTAssertLessThan(Date().timeIntervalSince(t0), 5)
        XCTAssertEqual(r.current().seq, 3)
        await gate.open()
    }

    func testDefaultBudgets() {
        XCTAssertEqual(ConfigRepository.overallTimeout, 15)
        XCTAssertEqual(ConfigRepository.throttleMs, 21_600_000)
    }

    func testConcurrentRefreshNeverRollsBack() async {
        store.saveEnvelope(T.env(3, sources: #"["\#(factorySource)"]"#))
        let calls = Counter()
        let r = repo { _ in await calls.next() == 0 ? T.env(5) : T.env(4) }
        async let a = r.refresh(force: true)
        async let b = r.refresh(force: true)
        let (ra, rb) = await (a, b)
        XCTAssertEqual([ra, rb].filter { $0 == .updated }.count, 1)
        XCTAssertEqual([ra, rb].filter { $0 == .unchanged }.count, 1)
        XCTAssertEqual(r.current().seq, 5)
        XCTAssertEqual(store.envelope(), T.env(5))
    }

    func testUnionsCachedAndFactorySources() async {
        store.saveEnvelope(T.env(3, sources: #"["https://x/c/","\#(factorySource)"]"#))
        let seen = Seen()
        let r = repo { await seen.add($0); return nil }
        _ = await r.refresh(force: true)
        let all = await seen.all
        XCTAssertEqual(Set(all), ["https://x/c/", factorySource])
        XCTAssertEqual(all.count, 2)
    }
}

private actor Gate {
    private var conts: [CheckedContinuation<Void, Never>] = []
    private var isOpen = false
    func wait() async {
        if isOpen { return }
        await withCheckedContinuation { conts.append($0) }
    }
    func open() { isOpen = true; conts.forEach { $0.resume() }; conts = [] }
}

private actor Counter {
    private var n = 0
    func next() -> Int { defer { n += 1 }; return n }
}

private actor Seen {
    private(set) var all: [String] = []
    func add(_ s: String) { all.append(s) }
}
