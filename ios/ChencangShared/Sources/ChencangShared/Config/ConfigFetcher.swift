import Foundation

public protocol ConfigSource: Sendable {
    func fetch(_ base: String) async -> Data?
}

/// 取 `<source>chencang-config.json`。全 App 少数允许联网的文件之一(`NetworkIsolationGuardTests`):
/// 纯 GET、无查询串、无自定义请求头、无 cookie、不跟随重定向、64 KiB 上限、整体 8 s。
/// 任何非 200 或异常都返回 nil。
public struct ConfigFetcher: ConfigSource {
    public static let fileName = "chencang-config.json"
    public static let maxBytes = 64 * 1024

    private let configuration: URLSessionConfiguration
    private let totalTimeout: TimeInterval

    public init(configuration: URLSessionConfiguration = .ephemeral, totalTimeout: TimeInterval = 8) {
        let cfg = configuration.copy() as! URLSessionConfiguration
        cfg.urlCache = nil
        cfg.httpCookieStorage = nil
        cfg.httpShouldSetCookies = false
        cfg.httpCookieAcceptPolicy = .never
        cfg.urlCredentialStorage = nil
        cfg.requestCachePolicy = .reloadIgnoringLocalCacheData
        cfg.timeoutIntervalForRequest = totalTimeout
        cfg.timeoutIntervalForResource = totalTimeout
        self.configuration = cfg
        self.totalTimeout = totalTimeout
    }

    public func fetch(_ base: String) async -> Data? {
        guard let url = URL(string: base + Self.fileName) else { return nil }
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.timeoutInterval = totalTimeout
        let session = URLSession(configuration: configuration, delegate: NoRedirectDelegate(), delegateQueue: nil)
        defer { session.finishTasksAndInvalidate() }
        return await withTaskGroup(of: Data?.self) { group in
            group.addTask { await Self.read(session, request) }
            group.addTask {
                try? await Task.sleep(nanoseconds: UInt64(totalTimeout * 1_000_000_000))
                return nil
            }
            let first = await group.next() ?? nil
            group.cancelAll()
            return first
        }
    }

    private static func read(_ session: URLSession, _ request: URLRequest) async -> Data? {
        do {
            let (bytes, response) = try await session.bytes(for: request)
            guard (response as? HTTPURLResponse)?.statusCode == 200 else { return nil }
            var out = Data()
            for try await b in bytes {
                if out.count >= maxBytes { return nil }
                out.append(b)
            }
            return out
        } catch {
            return nil
        }
    }
}

private final class NoRedirectDelegate: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}
