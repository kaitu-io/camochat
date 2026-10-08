import Foundation

public enum MediaTransportError: Error, Equatable, Sendable {
    case rateLimited            // 429
    case tooLarge               // 413
    case gone                   // 下载 403/404 → UI「已过期」
    case network                // 超时 / 断网 / 连接失败
    case server(status: Int)    // 3xx/5xx、签名 403/408、下载其他非 200:换下一台中转
    case rejected(status: Int)  // 签名/上传:403/408/413/429 以外的 4xx——请求本身不被接受,终态,不换中转
}

public protocol MediaTransporting: Sendable {
    func requestUploadUrl(blobId: String, byteLen: Int, kind: MediaKind) async throws -> URL
    func upload(url: URL, body: Data, onProgress: @escaping @Sendable (Double) -> Void) async throws
    func download(blobId: String, onProgress: @escaping @Sendable (Double) -> Void) async throws -> Data
}

/// 全 App 允许联网的两个文件之一(另一个是 `Config/ConfigFetcher.swift`;`NetworkIsolationGuardTests` 强制)。
/// 只搬 `.cca` 密文：密钥不进 URL、不进请求头。ephemeral 会话、无缓存、无 cookie。
public final class MediaTransport: MediaTransporting, @unchecked Sendable {
    private let relays: RelaySelector
    private let configuration: URLSessionConfiguration

    public init(relays: RelaySelector, configuration: URLSessionConfiguration = .ephemeral) {
        self.relays = relays
        let cfg = configuration.copy() as! URLSessionConfiguration
        cfg.urlCache = nil
        cfg.httpCookieStorage = nil
        cfg.httpShouldSetCookies = false
        cfg.httpCookieAcceptPolicy = .never
        cfg.urlCredentialStorage = nil
        cfg.requestCachePolicy = .reloadIgnoringLocalCacheData
        cfg.timeoutIntervalForRequest = 60
        cfg.timeoutIntervalForResource = 600
        self.configuration = cfg
    }

    /// 按 `RelaySelector` 的顺序逐台尝试:`.network` / `.server` 换下一台,其余(限流/超大/已过期/拒收)立即抛出。
    /// 成功即把该台记为 good;全部失败抛最后一个错误。
    private func withRelays<T>(_ op: (String) async throws -> T) async throws -> T {
        var last: MediaTransportError = .network
        for base in relays.ordered() {
            do {
                let result = try await op(base)
                relays.markGood(base)
                return result
            } catch let error as MediaTransportError {
                switch error {
                case .network, .server: last = error
                default: throw error
                }
            }
        }
        throw last
    }

    public func requestUploadUrl(blobId: String, byteLen: Int, kind: MediaKind) async throws -> URL {
        try await withRelays { base in
            guard var comps = URLComponents(string: base + "/api/upload") else { throw MediaTransportError.network }
            comps.queryItems = [
                URLQueryItem(name: "blob_id", value: blobId),
                URLQueryItem(name: "byte_len", value: String(byteLen)),
                URLQueryItem(name: "kind", value: String(kind.rawValue)),
            ]
            guard let url = comps.url else { throw MediaTransportError.network }
            var request = URLRequest(url: url)
            request.httpMethod = "GET"
            let (status, data) = try await perform(request, body: nil, onProgress: { _ in })
            switch status {
            case 200:
                struct Reply: Decodable { let url: String }
                guard let reply = try? JSONDecoder().decode(Reply.self, from: data),
                      let url = URL(string: reply.url) else {
                    throw MediaTransportError.server(status: status)
                }
                return url
            default: throw Self.signerFailure(status)
            }
        }
    }

    /// 签名与 PUT 同一口径(与 Android `signerFailure` 一致,spec §6):429 限流、413 太大;
    /// 403(签名不符/过期)与 408 是服务端问题 → `.server`(换中转 / 可重试);其余 4xx 是请求本身
    /// 不被接受 → `.rejected`(终态);3xx(不跟随重定向)与 5xx → `.server`。
    static func signerFailure(_ status: Int) -> MediaTransportError {
        switch status {
        case 429: return .rateLimited
        case 413: return .tooLarge
        case 403, 408: return .server(status: status)
        case 400..<500: return .rejected(status: status)
        default: return .server(status: status)
        }
    }

    public func upload(url: URL, body: Data, onProgress: @escaping @Sendable (Double) -> Void) async throws {
        var request = URLRequest(url: url)
        request.httpMethod = "PUT"
        request.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
        request.setValue("*", forHTTPHeaderField: "If-None-Match")
        request.setValue(String(body.count), forHTTPHeaderField: "Content-Length")
        let (status, _) = try await perform(request, body: body, onProgress: onProgress)
        switch status {
        case 200, 201, 204, 412:
            // 412 = 同一 blob_id 已在桶里（上次其实已传成功），按成功处理（R6）。
            onProgress(1)
        default: throw Self.signerFailure(status)
        }
    }

    public func download(blobId: String, onProgress: @escaping @Sendable (Double) -> Void) async throws -> Data {
        try await withRelays { base in
            guard let url = URL(string: base + "/b/" + blobId) else { throw MediaTransportError.network }
            var request = URLRequest(url: url)
            request.httpMethod = "GET"
            let (status, data) = try await perform(request, body: nil, onProgress: onProgress)
            switch status {
            case 200:
                onProgress(1)
                return data
            case 403, 404: throw MediaTransportError.gone
            case 429: throw MediaTransportError.rateLimited
            default: throw MediaTransportError.server(status: status)
            }
        }
    }

    /// 每个请求一个会话 + 任务级委托：拿得到上传/下载进度，且与 URLProtocol 桩兼容。
    private func perform(_ request: URLRequest, body: Data?,
                         onProgress: @escaping @Sendable (Double) -> Void) async throws -> (Int, Data) {
        let delegate = TransferDelegate(onProgress: onProgress)
        let session = URLSession(configuration: configuration, delegate: delegate, delegateQueue: nil)
        defer { session.finishTasksAndInvalidate() }
        do {
            return try await withCheckedThrowingContinuation { continuation in
                delegate.continuation = continuation
                let task = body.map { session.uploadTask(with: request, from: $0) } ?? session.dataTask(with: request)
                task.resume()
            }
        } catch {
            throw MediaTransportError.network
        }
    }
}

private final class TransferDelegate: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    var continuation: CheckedContinuation<(Int, Data), Error>?
    private let onProgress: @Sendable (Double) -> Void
    private var status = 0
    private var expected: Int64 = -1
    private var lengthCheckable = false
    private var received = Data()

    init(onProgress: @escaping @Sendable (Double) -> Void) {
        self.onProgress = onProgress
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didSendBodyData bytesSent: Int64,
                    totalBytesSent: Int64, totalBytesExpectedToSend: Int64) {
        guard totalBytesExpectedToSend > 0 else { return }
        onProgress(min(1, Double(totalBytesSent) / Double(totalBytesExpectedToSend)))
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse,
                    completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        let http = response as? HTTPURLResponse
        status = http?.statusCode ?? 0
        // 压缩过的响应:Content-Length 是压缩后的长度,收到的是解压后的字节,不能拿来比对。
        let encoding = http?.value(forHTTPHeaderField: "Content-Encoding")?.lowercased() ?? "identity"
        lengthCheckable = encoding == "identity"
        expected = response.expectedContentLength
        completionHandler(.allow)
    }

    /// 绝不跟随重定向：把 3xx 原样交回状态码分支（落到 `.server(status:)`），
    /// 而不是让 URLSession 悄悄换个 host 再发一次请求（可能带着我们的密文 body / 查询串）。
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        received.append(data)
        // 上传任务的响应体进度没有意义，只给下载报；错误响应体（403 的 XML 等）也不报——
        // 进度只代表真的在收 blob（接收端据此把「等待对方上传」切到下载中）。
        if !(dataTask is URLSessionUploadTask), (200..<300).contains(status), expected > 0 {
            onProgress(min(1, Double(received.count) / Double(expected)))
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        if let error {
            continuation?.resume(throwing: error)
        } else if !(task is URLSessionUploadTask), (200..<300).contains(status), lengthCheckable, expected >= 0,
                  Int64(received.count) != expected {
            // 2xx 但收到的字节数与 Content-Length 不符(短读/多读):传输问题,按断网处理(终审 F3)。
            continuation?.resume(throwing: URLError(.networkConnectionLost))
        } else {
            continuation?.resume(returning: (status, received))
        }
        continuation = nil
    }
}

// MARK: - 后台上传会话(先分享、后上传 spec §1.1)

extension URLSessionTask: UploadTaskHandle {}

/// `BackgroundUploader` 的真实会话:系统后台 `URLSession`,App 被挂起/被回收也由系统继续传,
/// 结束后在后台把 App 拉起来交付结果。放在本文件是因为全 App 只有这里可以联网
/// (`NetworkIsolationGuardTests`)。
///
/// - 进程内只能有一个同 identifier 的会话:用 `shared`,并在 App 启动(`App.init`)时就建好,
///   早于任何委托回调,这样系统替上个进程续传的任务能接回来。
/// - 只做从文件上传(后台会话的硬要求);请求头由 `MediaSender.presignedRequest` 放在请求上。
/// - 不跟随重定向(与 `MediaTransport` 同一口径,3xx 原样交回状态码)。
/// - 引擎挂上(`attach`)之前到达的结束/批次完成事件先缓存,挂上后按原顺序交付;进度不缓存。
public final class BackgroundUploadSession: NSObject, UploadSession, URLSessionDataDelegate, @unchecked Sendable {
    public static let shared = BackgroundUploadSession(identifier: BackgroundUploader.identifier)

    public struct Handlers {
        /// `taskIdentifier` 是结束任务的 `URLSessionTask.taskIdentifier`(上传引擎按它认当前任务)。
        public var onComplete: @MainActor (_ taskIdentifier: Int, _ taskDescription: String,
                                           _ httpStatus: Int?, _ error: Error?) -> Void
        public var onProgress: @MainActor (_ taskDescription: String, _ fraction: Double) -> Void
        public var onFinishEvents: @MainActor () -> Void

        public init(onComplete: @escaping @MainActor (Int, String, Int?, Error?) -> Void,
                    onProgress: @escaping @MainActor (String, Double) -> Void,
                    onFinishEvents: @escaping @MainActor () -> Void) {
            self.onComplete = onComplete
            self.onProgress = onProgress
            self.onFinishEvents = onFinishEvents
        }
    }

    private enum Event {
        case complete(Int, String, Int?, Error?)
        case progress(String, Double)
        case finished
    }

    private let lock = NSLock()
    private var handlers: Handlers?
    private var buffered: [Event] = []
    private var throttles: [Int: ProgressThrottle] = [:]
    private var session: URLSession!

    private init(identifier: String) {
        super.init()
        let configuration = URLSessionConfiguration.background(withIdentifier: identifier)
        configuration.isDiscretionary = false
        #if os(iOS)
        configuration.sessionSendsLaunchEvents = true
        #endif
        configuration.urlCache = nil
        configuration.httpCookieStorage = nil
        configuration.httpShouldSetCookies = false
        configuration.httpCookieAcceptPolicy = .never
        configuration.urlCredentialStorage = nil
        let queue = OperationQueue()
        queue.maxConcurrentOperationCount = 1
        session = URLSession(configuration: configuration, delegate: self, delegateQueue: queue)
    }

    /// 把事件接到上传引擎。之前缓存的事件随即按顺序交付。
    public func attach(_ handlers: Handlers) {
        let pending: [Event] = lock.withLock {
            self.handlers = handlers
            defer { buffered.removeAll() }
            return buffered
        }
        for event in pending { dispatch(event, to: handlers) }
    }

    public func uploadTask(with request: URLRequest, fromFile file: URL) -> UploadTaskHandle {
        session.uploadTask(with: request, fromFile: file)
    }

    public func allTasks() async -> [UploadTaskHandle] {
        await session.allTasks
    }

    // MARK: 委托(在串行委托队列上)

    public func urlSession(_ session: URLSession, task: URLSessionTask, didSendBodyData bytesSent: Int64,
                           totalBytesSent: Int64, totalBytesExpectedToSend: Int64) {
        guard let description = task.taskDescription, totalBytesExpectedToSend > 0 else { return }
        let fraction = min(1, Double(totalBytesSent) / Double(totalBytesExpectedToSend))
        let throttle: ProgressThrottle = lock.withLock {
            if let existing = throttles[task.taskIdentifier] { return existing }
            let created = ProgressThrottle()
            throttles[task.taskIdentifier] = created
            return created
        }
        guard throttle.shouldEmit(fraction) else { return }
        deliver(.progress(description, fraction))
    }

    public func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                           newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }

    public func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        lock.withLock { _ = throttles.removeValue(forKey: task.taskIdentifier) }
        guard let description = task.taskDescription else { return }
        let status = (task.response as? HTTPURLResponse)?.statusCode
        deliver(.complete(task.taskIdentifier, description, status, error))
    }

    #if os(iOS)
    public func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        deliver(.finished)
    }
    #endif

    private func deliver(_ event: Event) {
        let target: Handlers? = lock.withLock {
            guard let handlers else {
                if case .progress = event { return nil }
                buffered.append(event)
                return nil
            }
            return handlers
        }
        if let target { dispatch(event, to: target) }
    }

    /// 主队列 FIFO:保证「任务结束」先于「本批事件交付完」被处理(之后才调系统 completion handler)。
    private func dispatch(_ event: Event, to handlers: Handlers) {
        DispatchQueue.main.async {
            MainActor.assumeIsolated {
                switch event {
                case let .complete(identifier, description, status, error):
                    handlers.onComplete(identifier, description, status, error)
                case let .progress(description, fraction): handlers.onProgress(description, fraction)
                case .finished: handlers.onFinishEvents()
                }
            }
        }
    }
}
