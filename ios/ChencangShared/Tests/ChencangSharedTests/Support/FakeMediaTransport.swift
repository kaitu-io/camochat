import Foundation
@testable import ChencangShared

/// 可编排的传输桩:记录请求、按队列注入上传失败、按 blobId 提供下载结果、统计下载并发峰值。
final class FakeMediaTransport: MediaTransporting, @unchecked Sendable {
    private let lock = NSLock()
    private var _requested: [(blobId: String, byteLen: Int, kind: MediaKind)] = []
    private var _uploads: [(blobId: String, body: Data)] = []
    private var _downloadCalls: [String] = []
    private var uploadFailures: [MediaTransportError] = []
    private var requestFailures: [MediaTransportError] = []
    private var requestHook: (@Sendable () async -> Void)?
    private var blobs: [String: Result<Data, MediaTransportError>] = [:]
    private var inFlight = 0
    private var _maxInFlight = 0
    private var uploadHook: (@Sendable () async -> Void)?
    private var downloadHook: (@Sendable () async -> Void)?
    var downloadDelayNanos: UInt64 = 0
    /// 下载途中先报一次这个进度(在 hook 之前),模拟「已经开始收 blob」。
    var downloadProgressMidway: Double?
    /// presign 的模拟往返时延。
    var requestDelayNanos: UInt64 = 0
    private var requestsInFlight = 0
    private var _maxRequestsInFlight = 0
    /// 同时在途的 presign 请求峰值。
    var maxRequestsInFlight: Int { lock.withLock { _maxRequestsInFlight } }
    var uploadDelayNanos: UInt64 = 0

    var requested: [(blobId: String, byteLen: Int, kind: MediaKind)] { lock.withLock { _requested } }
    var uploads: [(blobId: String, body: Data)] { lock.withLock { _uploads } }
    var downloadCalls: [String] { lock.withLock { _downloadCalls } }
    var maxInFlight: Int { lock.withLock { _maxInFlight } }

    func failNextUploads(_ errors: [MediaTransportError]) { lock.withLock { uploadFailures = errors } }
    /// 申请上传地址(presign)依次失败。
    func failNextRequests(_ errors: [MediaTransportError]) { lock.withLock { requestFailures = errors } }
    /// presign 进行到一半时要做的事(例如模拟用户删除消息)。
    func setRequestHook(_ hook: @escaping @Sendable () async -> Void) { lock.withLock { requestHook = hook } }
    func serve(blobId: String, _ result: Result<Data, MediaTransportError>) { lock.withLock { blobs[blobId] = result } }
    /// 上传进行到一半时要做的事(例如模拟用户删除消息)。
    func setUploadHook(_ hook: @escaping @Sendable () async -> Void) { lock.withLock { uploadHook = hook } }
    /// 下载进行到一半时要做的事(例如模拟用户删除消息)。
    func setDownloadHook(_ hook: @escaping @Sendable () async -> Void) { lock.withLock { downloadHook = hook } }

    func requestUploadUrl(blobId: String, byteLen: Int, kind: MediaKind) async throws -> URL {
        lock.withLock {
            _requested.append((blobId, byteLen, kind))
            requestsInFlight += 1
            _maxRequestsInFlight = max(_maxRequestsInFlight, requestsInFlight)
        }
        defer { lock.withLock { requestsInFlight -= 1 } }
        if requestDelayNanos > 0 { try? await Task.sleep(nanoseconds: requestDelayNanos) }
        if let hook = lock.withLock({ requestHook }) { await hook() }
        let failure: MediaTransportError? = lock.withLock {
            requestFailures.isEmpty ? nil : requestFailures.removeFirst()
        }
        if let failure { throw failure }
        return URL(string: "https://upload.test/\(blobId)")!
    }

    func upload(url: URL, body: Data, onProgress: @escaping @Sendable (Double) -> Void) async throws {
        let failure: MediaTransportError? = lock.withLock {
            uploadFailures.isEmpty ? nil : uploadFailures.removeFirst()
        }
        if let failure { throw failure }
        if uploadDelayNanos > 0 { try? await Task.sleep(nanoseconds: uploadDelayNanos) }
        if let hook = lock.withLock({ uploadHook }) { await hook() }
        lock.withLock { _uploads.append((url.lastPathComponent, body)) }
        onProgress(0.5)
        onProgress(1)
    }

    func download(blobId: String, onProgress: @escaping @Sendable (Double) -> Void) async throws -> Data {
        lock.withLock {
            _downloadCalls.append(blobId)
            inFlight += 1
            _maxInFlight = max(_maxInFlight, inFlight)
        }
        if downloadDelayNanos > 0 { try? await Task.sleep(nanoseconds: downloadDelayNanos) }
        if let midway = downloadProgressMidway { onProgress(midway) }
        if let hook = lock.withLock({ downloadHook }) { await hook() }
        let result: Result<Data, MediaTransportError> = lock.withLock {
            inFlight -= 1
            return blobs[blobId] ?? .failure(.gone)
        }
        onProgress(1)
        return try result.get()
    }
}
