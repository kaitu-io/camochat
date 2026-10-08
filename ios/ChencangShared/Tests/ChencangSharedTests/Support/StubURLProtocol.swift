import Foundation

/// 本地 HTTP 桩：注册到 URLSessionConfiguration.protocolClasses，按 handler 返回响应，记录请求与请求体。
final class StubURLProtocol: URLProtocol {
    struct Reply {
        var status: Int
        var headers: [String: String] = [:]
        var chunks: [Data] = []
    }

    static var handler: ((URLRequest, Data) -> Result<Reply, URLError>)?
    static var requests: [(request: URLRequest, body: Data)] = []

    static func reset() {
        handler = nil
        requests = []
    }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        let body = readBody()
        Self.requests.append((request, body))
        switch Self.handler?(request, body) ?? .failure(URLError(.badServerResponse)) {
        case let .success(reply):
            let response = HTTPURLResponse(url: request.url!, statusCode: reply.status,
                                           httpVersion: "HTTP/1.1", headerFields: reply.headers)!
            // 3xx + Location：把它交给 URLProtocolClient 的 wasRedirectedTo，这样 URLSession
            // 才会真的去问 delegate 的 willPerformHTTPRedirection——否则跑的是我们自己模拟的
            // 「响应」，delegate 是否声明拒绝重定向根本没被考验到。
            if (300...399).contains(reply.status),
               let location = reply.headers.first(where: { $0.key.caseInsensitiveCompare("Location") == .orderedSame })?.value,
               let redirectURL = URL(string: location) {
                var newRequest = request
                newRequest.url = redirectURL
                client?.urlProtocol(self, wasRedirectedTo: newRequest, redirectResponse: response)
            }
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            for chunk in reply.chunks { client?.urlProtocol(self, didLoad: chunk) }
            client?.urlProtocolDidFinishLoading(self)
        case let .failure(error):
            client?.urlProtocol(self, didFailWithError: error)
        }
    }

    override func stopLoading() {}

    private func readBody() -> Data {
        if let body = request.httpBody { return body }
        guard let stream = request.httpBodyStream else { return Data() }
        stream.open()
        defer { stream.close() }
        var data = Data()
        var buffer = [UInt8](repeating: 0, count: 16_384)
        while stream.hasBytesAvailable {
            let n = stream.read(&buffer, maxLength: buffer.count)
            if n <= 0 { break }
            data.append(buffer, count: n)
        }
        return data
    }
}

/// 线程安全地收集进度回调。
final class ProgressBox: @unchecked Sendable {
    private let lock = NSLock()
    private var _values: [Double] = []
    var values: [Double] { lock.withLock { _values } }
    func record(_ value: Double) { lock.withLock { _values.append(value) } }
}
