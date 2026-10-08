import Foundation

public enum RefreshResult: Equatable, Sendable { case updated, unchanged, failed, throttled }

/// 持有当前 `AppConfig`:出厂内置配置,或更新的缓存;永不回滚(候选 `seq` 必须严格更大才替换)。
/// `current()` 同步可读(锁保护的快照),Action Extension 与分享文案只用它,从不 `refresh`。
public actor ConfigRepository {
    public static let overallTimeout: TimeInterval = 15
    public static let throttleMs: Int64 = 21_600_000

    public static let shared: ConfigRepository = {
        guard let url = Bundle.module.url(forResource: "chencang-config", withExtension: "json"),
              let data = try? Data(contentsOf: url) else {
            fatalError("chencang-config.json missing from bundle")
        }
        return ConfigRepository(factoryEnvelope: data,
                                store: ConfigStore(defaults: SharedAppGroupDefaults()),
                                source: ConfigFetcher())
    }()

    private final class Snapshot: @unchecked Sendable {
        private let lock = NSLock()
        private var value: AppConfig
        init(_ v: AppConfig) { value = v }
        func get() -> AppConfig { lock.withLock { value } }
        func set(_ v: AppConfig) { lock.withLock { value = v } }
    }

    private let factory: AppConfig
    private let store: ConfigStore
    private let source: ConfigSource
    private let verify: (Data, Data) -> Bool
    private let now: @Sendable () -> Int64
    private let overall: TimeInterval
    private let snapshot: Snapshot
    private var tail: Task<RefreshResult, Never>?

    public init(factoryEnvelope: Data, store: ConfigStore, source: ConfigSource,
                verify: @escaping (Data, Data) -> Bool = SignedConfigCodec.productionVerify,
                now: @escaping @Sendable () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) },
                overallTimeout: TimeInterval = ConfigRepository.overallTimeout) {
        guard let f = SignedConfigCodec.decode(factoryEnvelope, verify: verify) else {
            fatalError("factory config failed verification")
        }
        factory = f
        self.store = store
        self.source = source
        self.verify = verify
        self.now = now
        overall = overallTimeout
        let cached = store.envelope().flatMap { SignedConfigCodec.decode($0, verify: verify) }
        snapshot = Snapshot(cached.flatMap { $0.seq >= f.seq ? $0 : nil } ?? f)
    }

    public nonisolated func current() -> AppConfig { snapshot.get() }

    /// 串行化:后来的调用排在前一个之后,再按最新状态重判节流与 seq。
    public func refresh(force: Bool = false) async -> RefreshResult {
        let prev = tail
        let task = Task { () -> RefreshResult in
            _ = await prev?.value
            return await self.refreshSerialized(force)
        }
        tail = task
        return await task.value
    }

    private func refreshSerialized(_ force: Bool) async -> RefreshResult {
        if !force && now() - store.lastSuccessAt() < Self.throttleMs { return .throttled }
        let urls = Array(NSOrderedSet(array: snapshot.get().sources + factory.sources)) as! [String]
        let bodies = await gather(urls)
        let verify = self.verify
        let best = bodies.compactMap { body in
            SignedConfigCodec.decode(body, verify: verify).map { ($0, body) }
        }.max { $0.0.seq < $1.0.seq }
        guard let best else { return .failed }

        // actor 在上面的 await 处可重入:提交前重读实时 seq(防回滚)。
        if best.0.seq > snapshot.get().seq {
            store.saveEnvelope(best.1)
            snapshot.set(best.0)
            store.markSuccess(at: now())
            return .updated
        }
        store.markSuccess(at: now())
        return .unchanged
    }

    /// 并发取所有源;整体预算到点就用已到的结果返回,不等落后者(并取消它们)。
    private func gather(_ urls: [String]) async -> [Data] {
        if urls.isEmpty { return [] }
        let collector = Collector(total: urls.count)
        let source = self.source
        let budget = overall
        return await withCheckedContinuation { (cont: CheckedContinuation<[Data], Never>) in
            collector.install(cont)
            var tasks: [Task<Void, Never>] = []
            for u in urls {
                tasks.append(Task { collector.add(await source.fetch(u)) })
            }
            let timer = Task {
                try? await Task.sleep(nanoseconds: UInt64(budget * 1_000_000_000))
                collector.expire()
            }
            collector.onFinish { tasks.forEach { $0.cancel() }; timer.cancel() }
        }
    }
}

private final class Collector: @unchecked Sendable {
    private let lock = NSLock()
    private var remaining: Int
    private var bodies: [Data] = []
    private var cont: CheckedContinuation<[Data], Never>?
    private var done = false
    private var finish: (() -> Void)?

    init(total: Int) { remaining = total }

    func install(_ c: CheckedContinuation<[Data], Never>) { lock.withLock { cont = c } }

    func onFinish(_ f: @escaping () -> Void) {
        let runNow = lock.withLock { () -> Bool in
            if done { return true }
            finish = f
            return false
        }
        if runNow { f() }
    }

    func add(_ body: Data?) {
        let out: (CheckedContinuation<[Data], Never>, [Data], (() -> Void)?)? = lock.withLock {
            if done { return nil }
            if let body { bodies.append(body) }
            remaining -= 1
            return remaining == 0 ? complete() : nil
        }
        if let (c, b, f) = out { f?(); c.resume(returning: b) }
    }

    func expire() {
        let out = lock.withLock { done ? nil : complete() }
        if let (c, b, f) = out { f?(); c.resume(returning: b) }
    }

    /// 持锁调用。
    private func complete() -> (CheckedContinuation<[Data], Never>, [Data], (() -> Void)?)? {
        done = true
        guard let c = cont else { return nil }
        return (c, bodies, finish)
    }
}
