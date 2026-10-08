import XCTest
@testable import ChencangShared

final class MediaTransportTests: XCTestCase {
    private var transport: MediaTransport!

    override func setUp() {
        super.setUp()
        StubURLProtocol.reset()
        let cfg = URLSessionConfiguration.ephemeral
        cfg.protocolClasses = [StubURLProtocol.self]
        config = cfg
        defaults = UserDefaults(suiteName: "mt-\(UUID().uuidString)")!
        transport = makeTransport(["https://up.test"])
    }

    private var config: URLSessionConfiguration!
    private var defaults: UserDefaults!

    private func makeTransport(_ relays: [String]) -> MediaTransport {
        MediaTransport(relays: RelaySelector(relays: { relays }, defaults: defaults), configuration: config)
    }

    private func hosts() -> [String] { StubURLProtocol.requests.map { $0.request.url?.host ?? "" } }

    override func tearDown() {
        StubURLProtocol.reset()
        super.tearDown()
    }

    private func reply(_ status: Int, _ body: String = "") -> Result<StubURLProtocol.Reply, URLError> {
        .success(.init(status: status, headers: ["Content-Length": "\(body.utf8.count)"],
                       chunks: body.isEmpty ? [] : [Data(body.utf8)]))
    }

    func test_failsOverOn503() async throws {
        transport = makeTransport(["https://r1.test", "https://r2.test"])
        StubURLProtocol.handler = { req, _ in
            req.url?.host == "r1.test" ? self.reply(503) : self.reply(200, #"{"url":"https://s3.test/p"}"#)
        }
        let url = try await transport.requestUploadUrl(blobId: "a", byteLen: 1, kind: .image)
        XCTAssertEqual(url.absoluteString, "https://s3.test/p")
        XCTAssertEqual(hosts(), ["r1.test", "r2.test"])
        // 成功的那台被记住,下次排最前。
        StubURLProtocol.requests = []
        _ = try await transport.requestUploadUrl(blobId: "b", byteLen: 1, kind: .image)
        XCTAssertEqual(hosts(), ["r2.test"])
    }

    func test_failsOverOnNetworkErrorForDownload() async throws {
        transport = makeTransport(["https://r1.test", "https://r2.test"])
        StubURLProtocol.handler = { req, _ in
            req.url?.host == "r1.test" ? .failure(URLError(.cannotConnectToHost)) : self.reply(200, "ab")
        }
        let data = try await transport.download(blobId: "x") { _ in }
        XCTAssertEqual(data, Data("ab".utf8))
        XCTAssertEqual(hosts(), ["r1.test", "r2.test"])
    }

    func test_doesNotFailOverOnGone() async {
        transport = makeTransport(["https://r1.test", "https://r2.test"])
        StubURLProtocol.handler = { _, _ in self.reply(404) }
        do { _ = try await transport.download(blobId: "x") { _ in }; XCTFail("应抛错") }
        catch { XCTAssertEqual(error as? MediaTransportError, .gone) }
        XCTAssertEqual(hosts(), ["r1.test"])
    }

    func test_doesNotFailOverOnRateLimitedOrTooLarge() async {
        transport = makeTransport(["https://r1.test", "https://r2.test"])
        for (status, expected) in [(429, MediaTransportError.rateLimited), (413, .tooLarge)] {
            StubURLProtocol.requests = []
            StubURLProtocol.handler = { _, _ in self.reply(status) }
            do { _ = try await transport.requestUploadUrl(blobId: "a", byteLen: 1, kind: .voice); XCTFail("应抛错") }
            catch { XCTAssertEqual(error as? MediaTransportError, expected) }
            XCTAssertEqual(hosts(), ["r1.test"])
        }
    }

    /// 与 Android `signerFailure` 同口径(spec §6):其他 4xx 是请求本身的问题,换中转也一样 → 终态。
    func test_doesNotFailOverOn400() async {
        transport = makeTransport(["https://r1.test", "https://r2.test"])
        for status in [400, 401, 404, 409, 422] {
            StubURLProtocol.requests = []
            StubURLProtocol.handler = { _, _ in self.reply(status) }
            do { _ = try await transport.requestUploadUrl(blobId: "a", byteLen: 1, kind: .image); XCTFail("应抛错") }
            catch { XCTAssertEqual(error as? MediaTransportError, .rejected(status: status)) }
            XCTAssertEqual(hosts(), ["r1.test"], "状态 \(status) 不应换中转")
        }
    }

    /// 签名的 403(签名不符/过期)、408、3xx、5xx 是服务端问题:换下一台。
    func test_signerFailsOverOn403_408_3xx_5xx() async throws {
        for status in [403, 408, 302, 500] {
            StubURLProtocol.requests = []
            StubURLProtocol.handler = { req, _ in
                req.url?.host == "r1.test" ? self.reply(status) : self.reply(200, #"{"url":"https://s3.test/p"}"#)
            }
            // Fresh selector memory each round, so r1 is tried first again.
            defaults = UserDefaults(suiteName: "mt-\(UUID().uuidString)")!
            transport = makeTransport(["https://r1.test", "https://r2.test"])
            _ = try await transport.requestUploadUrl(blobId: "a", byteLen: 1, kind: .image)
            XCTAssertEqual(hosts(), ["r1.test", "r2.test"], "状态 \(status) 应换中转")
        }
    }

    /// 下载侧与 Android `downloadFailure` 同口径:403/404 已过期、429 限流,其余非 200 都换下一台。
    func test_downloadFailsOverOn400() async throws {
        transport = makeTransport(["https://r1.test", "https://r2.test"])
        StubURLProtocol.handler = { req, _ in req.url?.host == "r1.test" ? self.reply(400) : self.reply(200, "ab") }
        let data = try await transport.download(blobId: "x") { _ in }
        XCTAssertEqual(data, Data("ab".utf8))
        XCTAssertEqual(hosts(), ["r1.test", "r2.test"])
    }

    func test_allRelaysFailThrowsLastError() async {
        transport = makeTransport(["https://r1.test", "https://r2.test"])
        StubURLProtocol.handler = { req, _ in
            req.url?.host == "r1.test" ? .failure(URLError(.timedOut)) : self.reply(502)
        }
        do { _ = try await transport.download(blobId: "x") { _ in }; XCTFail("应抛错") }
        catch { XCTAssertEqual(error as? MediaTransportError, .server(status: 502)) }
        XCTAssertEqual(hosts(), ["r1.test", "r2.test"])
    }

    func testRequestUploadUrlBuildsQueryAndParsesReply() async throws {
        StubURLProtocol.handler = { _, _ in self.reply(200, #"{"url":"https://s3.test/b/x?sig=1","expires_in":300}"#) }

        let url = try await transport.requestUploadUrl(blobId: "AAAAAAAAAAAAAAAAAAAAAA", byteLen: 1234, kind: .image)

        XCTAssertEqual(url.absoluteString, "https://s3.test/b/x?sig=1")
        let req = try XCTUnwrap(StubURLProtocol.requests.first?.request)
        XCTAssertEqual(req.httpMethod, "GET")
        let comps = try XCTUnwrap(URLComponents(url: req.url!, resolvingAgainstBaseURL: false))
        XCTAssertEqual(comps.host, "up.test")
        XCTAssertEqual(comps.path, "/api/upload")
        XCTAssertEqual(Set(comps.queryItems ?? []), [
            URLQueryItem(name: "blob_id", value: "AAAAAAAAAAAAAAAAAAAAAA"),
            URLQueryItem(name: "byte_len", value: "1234"),
            URLQueryItem(name: "kind", value: "2"),
        ])
        XCTAssertNil(req.value(forHTTPHeaderField: "Cookie"))
    }

    func testRequestUploadUrlMapsRateLimitAndTooLarge() async {
        StubURLProtocol.handler = { _, _ in self.reply(429) }
        do { _ = try await transport.requestUploadUrl(blobId: "a", byteLen: 1, kind: .voice); XCTFail("应抛错") }
        catch { XCTAssertEqual(error as? MediaTransportError, .rateLimited) }

        StubURLProtocol.handler = { _, _ in self.reply(413) }
        do { _ = try await transport.requestUploadUrl(blobId: "a", byteLen: 1, kind: .video); XCTFail("应抛错") }
        catch { XCTAssertEqual(error as? MediaTransportError, .tooLarge) }
    }

    func testRequestUploadUrlDoesNotFollowRedirects() async {
        StubURLProtocol.handler = { _, _ in
            .success(.init(status: 302, headers: ["Location": "https://evil.test/api/upload"]))
        }
        do { _ = try await transport.requestUploadUrl(blobId: "a", byteLen: 1, kind: .image); XCTFail("应抛错") }
        catch { XCTAssertEqual(error as? MediaTransportError, .server(status: 302)) }
        XCTAssertEqual(StubURLProtocol.requests.count, 1, "不应跟随重定向再发一次请求")
    }

    func testUploadSendsExactHeadersAndBody() async throws {
        StubURLProtocol.handler = { _, _ in self.reply(200) }
        let body = Data(repeating: 0x5A, count: 10_000)
        let progress = ProgressBox()

        try await transport.upload(url: URL(string: "https://s3.test/put?sig=1")!, body: body) { progress.record($0) }

        let (req, sent) = try XCTUnwrap(StubURLProtocol.requests.first)
        XCTAssertEqual(req.httpMethod, "PUT")
        XCTAssertEqual(req.url?.absoluteString, "https://s3.test/put?sig=1")
        XCTAssertEqual(req.value(forHTTPHeaderField: "Content-Type"), "application/octet-stream")
        XCTAssertEqual(req.value(forHTTPHeaderField: "If-None-Match"), "*")
        XCTAssertEqual(req.value(forHTTPHeaderField: "Content-Length"), "10000")
        XCTAssertEqual(sent, body)
        XCTAssertEqual(progress.values.last, 1)
    }

    func testUpload412CountsAsSuccess() async throws {
        StubURLProtocol.handler = { _, _ in self.reply(412) }
        try await transport.upload(url: URL(string: "https://s3.test/put")!, body: Data([1])) { _ in }
    }

    func testUpload204CountsAsSuccess() async throws {
        StubURLProtocol.handler = { _, _ in self.reply(204) }
        try await transport.upload(url: URL(string: "https://s3.test/put")!, body: Data([1])) { _ in }
    }

    func testUploadErrorsMap() async {
        for (status, expected) in [
            (429, MediaTransportError.rateLimited), (413, .tooLarge), (500, .server(status: 500)),
            (403, .server(status: 403)), (408, .server(status: 408)), (400, .rejected(status: 400)),
        ] {
            StubURLProtocol.handler = { _, _ in self.reply(status) }
            do {
                try await transport.upload(url: URL(string: "https://s3.test/put")!, body: Data([1])) { _ in }
                XCTFail("状态 \(status) 应抛错")
            } catch {
                XCTAssertEqual(error as? MediaTransportError, expected)
            }
        }
    }

    func testUploadDoesNotFollowRedirects() async {
        StubURLProtocol.handler = { _, _ in
            .success(.init(status: 302, headers: ["Location": "https://evil.test/put"]))
        }
        do {
            try await transport.upload(url: URL(string: "https://s3.test/put")!, body: Data([1])) { _ in }
            XCTFail("应抛错")
        } catch {
            XCTAssertEqual(error as? MediaTransportError, .server(status: 302))
        }
        XCTAssertEqual(StubURLProtocol.requests.count, 1, "不应跟随重定向再发一次请求")
    }

    func testDownloadReturnsBodyReportsProgressAndHitsBlobPath() async throws {
        StubURLProtocol.handler = { _, _ in
            .success(.init(status: 200, headers: ["Content-Length": "4"],
                           chunks: [Data([1, 2]), Data([3, 4])]))
        }
        let progress = ProgressBox()

        let data = try await transport.download(blobId: "BBBBBBBBBBBBBBBBBBBBBB") { progress.record($0) }

        XCTAssertEqual(data, Data([1, 2, 3, 4]))
        XCTAssertEqual(StubURLProtocol.requests.first?.request.url?.absoluteString,
                       "https://up.test/b/BBBBBBBBBBBBBBBBBBBBBB")
        XCTAssertEqual(StubURLProtocol.requests.first?.request.httpMethod, "GET")
        XCTAssertEqual(progress.values.last, 1)
    }

    /// 终审 F3:200 但收到的字节数与 Content-Length 对不上(短读/多读)是传输问题 → `.network`,可再取。
    func testDownloadContentLengthMismatchIsNetwork() async {
        for (declared, body) in [("10", Data([1, 2, 3, 4])), ("2", Data([1, 2, 3, 4]))] {
            StubURLProtocol.handler = { _, _ in
                .success(.init(status: 200, headers: ["Content-Length": declared], chunks: [body]))
            }
            do { _ = try await transport.download(blobId: "x") { _ in }; XCTFail("应抛错 Content-Length=\(declared)") }
            catch { XCTAssertEqual(error as? MediaTransportError, .network, "Content-Length=\(declared)") }
        }
    }

    func testDownloadGoneOn403And404() async {
        for status in [403, 404] {
            StubURLProtocol.handler = { _, _ in self.reply(status) }
            do { _ = try await transport.download(blobId: "x") { _ in }; XCTFail("应抛错") }
            catch { XCTAssertEqual(error as? MediaTransportError, .gone) }
        }
    }

    /// 403/404 的错误响应体(CloudFront/S3 的 XML)不报下载进度:进度只代表真的在收 blob,
    /// 接收端据此把「等待对方上传」切到下载中(先分享、后上传 spec §2)。
    func testDownloadErrorBodyReportsNoProgress() async {
        StubURLProtocol.handler = { _, _ in self.reply(403, "<Error><Code>AccessDenied</Code></Error>") }
        let progress = ProgressBox()
        do { _ = try await transport.download(blobId: "x") { progress.record($0) }; XCTFail("应抛错") }
        catch { XCTAssertEqual(error as? MediaTransportError, .gone) }
        XCTAssertEqual(progress.values, [])
    }

    func testTransportFailureMapsToNetwork() async {
        StubURLProtocol.handler = { _, _ in .failure(URLError(.notConnectedToInternet)) }
        do { _ = try await transport.download(blobId: "x") { _ in }; XCTFail("应抛错") }
        catch { XCTAssertEqual(error as? MediaTransportError, .network) }

        StubURLProtocol.handler = { _, _ in .failure(URLError(.timedOut)) }
        do { try await transport.upload(url: URL(string: "https://s3.test/put")!, body: Data([1])) { _ in }; XCTFail("应抛错") }
        catch { XCTAssertEqual(error as? MediaTransportError, .network) }
    }
}
