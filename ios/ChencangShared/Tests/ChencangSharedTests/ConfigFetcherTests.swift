import XCTest
@testable import ChencangShared

final class ConfigFetcherTests: XCTestCase {
    private func fetcher() -> ConfigFetcher {
        StubURLProtocol.reset()
        let cfg = URLSessionConfiguration.ephemeral
        cfg.protocolClasses = [StubURLProtocol.self]
        return ConfigFetcher(configuration: cfg)
    }

    override func tearDown() { StubURLProtocol.reset(); super.tearDown() }

    func testPlainGetNoQueryNoCustomHeaders() async throws {
        let f = fetcher()
        StubURLProtocol.handler = { _, _ in .success(.init(status: 200, chunks: [Data("hi".utf8)])) }
        let d = await f.fetch("https://a.test/c/")
        XCTAssertEqual(d, Data("hi".utf8))
        let req = try XCTUnwrap(StubURLProtocol.requests.first?.request)
        XCTAssertEqual(req.url?.absoluteString, "https://a.test/c/chencang-config.json")
        XCTAssertEqual(req.httpMethod, "GET")
        XCTAssertNil(req.value(forHTTPHeaderField: "Cookie"))
        XCTAssertNil(req.value(forHTTPHeaderField: "Authorization"))
    }

    func testNon200IsNil() async {
        let f = fetcher()
        StubURLProtocol.handler = { _, _ in .success(.init(status: 404)) }
        let d = await f.fetch("https://a.test/c/")
        XCTAssertNil(d)
    }

    func testRedirectNotFollowed() async {
        let f = fetcher()
        StubURLProtocol.handler = { _, _ in .success(.init(status: 302, headers: ["Location": "https://evil.test/x"])) }
        let d = await f.fetch("https://a.test/c/")
        XCTAssertNil(d)
        XCTAssertEqual(StubURLProtocol.requests.count, 1)
    }

    func testOverCapIsNil() async {
        let f = fetcher()
        StubURLProtocol.handler = { _, _ in
            .success(.init(status: 200, chunks: [Data(count: ConfigFetcher.maxBytes + 1)]))
        }
        let d = await f.fetch("https://a.test/c/")
        XCTAssertNil(d)
    }

    func testAtCapIsAccepted() async {
        let f = fetcher()
        StubURLProtocol.handler = { _, _ in
            .success(.init(status: 200, chunks: [Data(count: ConfigFetcher.maxBytes)]))
        }
        let d = await f.fetch("https://a.test/c/")
        XCTAssertEqual(d?.count, ConfigFetcher.maxBytes)
    }

    func testNetworkErrorIsNil() async {
        let f = fetcher()
        StubURLProtocol.handler = { _, _ in .failure(URLError(.notConnectedToInternet)) }
        let d = await f.fetch("https://a.test/c/")
        XCTAssertNil(d)
    }

    func testHangingResponseHitsTotalDeadline() async {
        StubURLProtocol.reset()
        let cfg = URLSessionConfiguration.ephemeral
        cfg.protocolClasses = [StubURLProtocol.self]
        let f = ConfigFetcher(configuration: cfg, totalTimeout: 0.5)
        StubURLProtocol.handler = { _, _ in
            Thread.sleep(forTimeInterval: 4)
            return .success(.init(status: 200, chunks: [Data("late".utf8)]))
        }
        let t0 = Date()
        let d = await f.fetch("https://a.test/c/")
        XCTAssertNil(d)
        XCTAssertLessThan(Date().timeIntervalSince(t0), 3)
    }
}
