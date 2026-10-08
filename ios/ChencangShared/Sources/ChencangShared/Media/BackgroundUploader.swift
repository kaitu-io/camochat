import Foundation

/// 后台上传会话里的一个任务。生产实现是系统的上传任务(见 `MediaTransport.swift` 里的
/// `BackgroundUploadSession`),单测用假的。
public protocol UploadTaskHandle: AnyObject {
    /// 会话内唯一的任务编号(系统上传任务的 taskIdentifier)。认完成回调靠它,不靠对象身份:
    /// 系统递给委托、`allTasks` 的可能是另一个包装对象。
    var taskIdentifier: Int { get }
    /// 只放 `"<messageId>:<index>"`——不放 secret、blob id、URL(会进系统日志)。
    var taskDescription: String? { get set }
    func resume()
    func cancel()
}

/// 后台上传会话:从文件上传(系统进程替我们传,App 被挂起/被回收也不中断)。
public protocol UploadSession: AnyObject {
    func uploadTask(with request: URLRequest, fromFile file: URL) -> UploadTaskHandle
    /// 会话里还没结束的任务(含系统替上一个进程续传的)。
    func allTasks() async -> [UploadTaskHandle]
}

/// iOS 上传引擎(先分享、后上传 spec §1.1/§1.2/§4)。
///
/// - `enqueue`:对一条已分享消息的每个未上传项(`uploading`/`failed`)先 presign(包在
///   `beginBackgroundTask` 里,切后台也能做完),再在后台会话上从 `.cca` 文件建上传任务。
/// - `handleCompletion`:任务结束 → `MediaSender.completeItem` 落结果;403(URL 过期)前台立即
///   重新 presign、后台留给对账;其它可重试失败按 10 s / 30 s / 60 s 退避(仅前台);
///   连续 5 次可重试失败 → 项标 `failed`(「对方还看不到」),等手动重试或下次对账;
///   不认识的 4xx(403/408/429 以外)→ 项标 `failed` + `uploadFailure = .rejected`(终态)。
/// - `reconcile`:启动与回前台时,把「已分享、仍有 `uploading`/`failed` 项、又没有同名任务在跑」
///   的消息重新交给引擎;连续失败计数清零。带永久失败原因(`uploadFailure`:413 / 被拒)的 `failed` 项
///   是终态,对账与手动重试都跳过(审查 M7、终审 F2)。
/// - `cancel`:删消息/清空会话前取消该消息的全部任务(调用方随后才删文件)。
///
/// **不变量**:永不重新加密——只重传同一份 `.cca`;`.cca` 丢了只标失败。
@MainActor
public final class BackgroundUploader {
    nonisolated public static let identifier = "app.chencang.companion.upload"
    /// 前台可重试失败的退避间隔(第 n 次失败取第 min(n, 3) 个)。
    public static let backoff: [TimeInterval] = [10, 30, 60]
    /// 连续这么多次可重试失败就放弃自动重传,标 `failed`。
    public static let maxConsecutiveFailures = 5

    /// 把一段工作包在 `UIApplication.beginBackgroundTask` 里(App 层注入;单测直接执行)。
    public typealias BackgroundTaskRunner = @MainActor (_ work: @escaping @MainActor () async -> Void) async -> Void

    /// 系统在后台把 App 拉起来交付上传结果时给的回调;全部事件处理完(`handleEventsFinished`)后调用一次。
    /// 会话在 `App.init` 就建好,本批事件可能先于 AppDelegate 递来 handler 就交付完:那时记下
    /// `eventsFinishedPending`,handler 一到立即调用。
    public var backgroundCompletionHandler: (() -> Void)? {
        didSet {
            guard eventsFinishedPending, let handler = backgroundCompletionHandler else { return }
            eventsFinishedPending = false
            backgroundCompletionHandler = nil
            handler()
        }
    }
    private var eventsFinishedPending = false

    private let session: UploadSession
    private let sender: MediaSender
    private let store: ChatStore
    private let files: MediaFiles
    private let activity: MediaActivity
    private let isForeground: @MainActor () -> Bool
    private let backgroundTask: BackgroundTaskRunner
    private let sleep: @MainActor (TimeInterval) async -> Void

    /// 本进程知道的在跑任务(自己建的 + 对账时从会话里认领的),键 = taskDescription。
    private var tasks: [String: UploadTaskHandle] = [:]
    /// 每个任务登记时的序号:对账只清理「查询会话之前就登记、会话里却已没有」的句柄。
    private var taskGeneration: [String: Int] = [:]
    private var generation = 0
    /// 正在 presign 的项(防止同一项并发建两个任务)。
    private var presigning: Set<String> = []
    /// 每项连续可重试失败次数。
    private var failures: [String: Int] = [:]
    /// 等待退避的重排。
    private var retries: [String: Task<Void, Never>] = [:]

    public init(session: UploadSession,
                sender: MediaSender,
                store: ChatStore,
                files: MediaFiles,
                activity: MediaActivity,
                isForeground: @escaping @MainActor () -> Bool,
                backgroundTask: @escaping BackgroundTaskRunner,
                sleep: @escaping @MainActor (TimeInterval) async -> Void = { seconds in
                    try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
                }) {
        self.session = session
        self.sender = sender
        self.store = store
        self.files = files
        self.activity = activity
        self.isForeground = isForeground
        self.backgroundTask = backgroundTask
        self.sleep = sleep
    }

    // MARK: - 入队

    /// 把一条已分享消息的所有未上传项交给后台会话。封缄完成(`uploadScheduler`)与用户手动重试都走这里,
    /// 连续失败计数清零。返回的任务在所有 presign 与建任务完成后结束(调用方一般不等)。
    @discardableResult
    public func enqueue(messageId: String) -> Task<Void, Never> {
        start(messageId: messageId, only: nil, resetFailures: true)
    }

    /// 永久失败(`uploadFailure` 非空)的项是终态(spec §1.3):对账与手动重试都跳过。
    private func start(messageId: String, only: Set<Int>?, resetFailures: Bool) -> Task<Void, Never> {
        guard let message = findMessage(messageId), message.direction == .outgoing, !message.body.isEmpty,
              let items = message.media else { return Task {} }
        var picked: [Int] = []
        for item in items where only?.contains(item.index) ?? true {
            // 只有 uploading / failed 需要传;sealed 已在桶里(M6)。
            guard item.state == .uploading || item.state == .failed else { continue }
            if item.state == .failed && item.uploadFailure != nil { continue }
            let key = Self.description(messageId: messageId, index: item.index)
            if resetFailures {
                failures[key] = nil
                retries.removeValue(forKey: key)?.cancel()
            }
            guard tasks[key] == nil, !presigning.contains(key) else { continue }
            guard files.exists(files.ccaURL(messageId: messageId, index: item.index)) else {
                // 已分享但 `.cca` 不在:不能重新加密,标「文件丢失」,也不先闪一下「上传中」。
                markFailed(messageId: messageId, index: item.index)
                continue
            }
            if item.state == .failed {
                // 重新交给引擎的项要显示「上传中」(M6)。
                try? store.updateMediaItem(messageId: messageId, peerId: message.peerId, index: item.index) {
                    guard $0.state == .failed else { return }
                    $0.state = .uploading
                    $0.uploadFailure = nil
                }
            }
            presigning.insert(key)
            picked.append(item.index)
        }
        guard !picked.isEmpty else { return Task {} }
        return Task { @MainActor [weak self] in
            guard let self else { return }
            // 全部项并发 presign、各自一拿到 URL 就建任务,都在同一个后台任务里:9 张图也只要约一次往返,
            // 任务几乎总在分享面板还开着(App 在前台)时建好——后台建的任务系统一律当 discretionary 处理。
            await self.backgroundTask { [weak self] in
                guard let self else { return }
                await withTaskGroup(of: Void.self) { group in
                    for index in picked {
                        group.addTask { await self.presignAndStart(messageId: messageId, index: index) }
                    }
                }
            }
        }
    }

    private func presignAndStart(messageId: String, index: Int) async {
        let key = Self.description(messageId: messageId, index: index)
        // 不用 defer 清标记:失败路径里 `apply` 可能立即重排同一项、重新打上标记,
        // defer 会在那之后把新标记擦掉(审查 M3)。每个出口先清,再做别的。
        let request: URLRequest
        do {
            request = try await sender.presignedRequest(messageId: messageId, index: index)
        } catch MediaSender.UploadError.messageGone, MediaSender.UploadError.notShared {
            presigning.remove(key)
            return
        } catch MediaSender.UploadError.fileMissing {
            presigning.remove(key)
            markFailed(messageId: messageId, index: index)
            return
        } catch {
            presigning.remove(key)
            let (status, failure) = Self.httpEquivalent(of: error)
            let outcome = sender.completeItem(messageId: messageId, index: index, httpStatus: status, error: failure)
            apply(outcome, key: key, messageId: messageId, index: index, httpStatus: status)
            return
        }
        presigning.remove(key)
        // presign 期间用户可能删了消息、别的路径可能已经传完:重新确认再建任务。
        guard sender.isShared(messageId),
              let item = findMessage(messageId)?.media?.first(where: { $0.index == index }),
              item.state == .uploading, tasks[key] == nil else { return }
        let file = files.ccaURL(messageId: messageId, index: index)
        guard files.exists(file) else {
            markFailed(messageId: messageId, index: index)
            return
        }
        let task = session.uploadTask(with: request, fromFile: file)
        task.taskDescription = key
        register(task, key: key)
        task.resume()
    }

    // MARK: - 回调

    /// 后台会话报告一个任务结束(`didCompleteWithError`)。返回因此排出的后续工作(重新 presign / 退避重排),
    /// 没有则 nil。
    ///
    /// `taskIdentifier` 是结束的那个任务的编号:与本项当前登记任务的编号比,判断它是不是当前任务(审查 M4)。
    /// 不比对象身份——系统每次递来的包装对象可能不同。`taskDescription` 只用来定位项、以及重启后从会话里
    /// 认领任务(认领后按编号认它的完成回调)。被取代的旧任务(上个进程遗留、
    /// 或重排前的那个)迟到的结果:失败直接忽略,不碰新任务的句柄;成功说明对象已在桶里——落「已上传」,
    /// 并取消多余的新任务。
    @discardableResult
    public func handleCompletion(taskDescription: String, httpStatus: Int?, error: Error?,
                                 taskIdentifier: Int? = nil) -> Task<Void, Never>? {
        guard let (messageId, index) = Self.parse(taskDescription) else { return nil }
        // 我们自己取消的(删消息)或用户强退 App 时系统取消的:不落任何结果;
        // 消息还在的话,项仍是 uploading,下次对账会重传。
        if let error, Self.isCancellation(error) { return nil }
        if let taskIdentifier, let current = tasks[taskDescription], current.taskIdentifier != taskIdentifier {
            guard error == nil, let status = httpStatus, (200..<300).contains(status) || status == 412 else { return nil }
            current.cancel()
        }
        tasks[taskDescription] = nil
        taskGeneration[taskDescription] = nil
        activity.clearProgress(messageId: messageId, index: index)
        let outcome = sender.completeItem(messageId: messageId, index: index, httpStatus: httpStatus, error: error)
        return apply(outcome, key: taskDescription, messageId: messageId, index: index, httpStatus: httpStatus)
    }

    /// 上传进度(`didSendBodyData`),0…1。
    public func handleProgress(taskDescription: String, fraction: Double) {
        guard let (messageId, index) = Self.parse(taskDescription), tasks[taskDescription] != nil else { return }
        activity.setProgress(fraction, messageId: messageId, index: index)
    }

    /// 后台会话把这一批事件都交付完了(`urlSessionDidFinishEvents`):告诉系统可以再挂起我们了。
    public func handleEventsFinished() {
        guard let handler = backgroundCompletionHandler else {
            eventsFinishedPending = true
            return
        }
        backgroundCompletionHandler = nil
        handler()
    }

    @discardableResult
    private func apply(_ outcome: UploadOutcome, key: String, messageId: String, index: Int,
                       httpStatus: Int?) -> Task<Void, Never>? {
        switch outcome {
        case .done, .permanent(.deleted):
            failures[key] = nil
            return nil
        case .permanent:
            failures[key] = nil
            markFailed(messageId: messageId, index: index)
            return nil
        case .retryable(let reason):
            if let status = httpStatus, Self.isUnretryableClientError(status) {
                // 中转拒收:记下原因,终态(对账与手动重试都不再重传,终审 F2)。
                failures[key] = nil
                markFailed(messageId: messageId, index: index, reason: .rejected)
                return nil
            }
            let count = (failures[key] ?? 0) + 1
            guard count < Self.maxConsecutiveFailures else {
                failures[key] = nil
                markFailed(messageId: messageId, index: index)
                return nil
            }
            failures[key] = count
            if reason == .gone {
                // presign 过期(系统推迟了任务):前台立即重新 presign;后台再 presign 也可能又过期,留给对账。
                guard isForeground() else { return nil }
                return start(messageId: messageId, only: [index], resetFailures: false)
            }
            let delay = Self.backoff[min(count, Self.backoff.count) - 1]
            retries[key]?.cancel()
            let retry = Task { @MainActor [weak self] in
                guard let self else { return }
                await self.sleep(delay)
                guard !Task.isCancelled else { return }
                self.retries[key] = nil
                // 后台不自己重排:回前台的对账会接上。
                guard self.isForeground() else { return }
                await self.start(messageId: messageId, only: [index], resetFailures: false).value
            }
            retries[key] = retry
            return retry
        }
    }

    // MARK: - 对账

    /// 启动与回前台调用:认领系统续着传的任务,把「已分享、有 `uploading`/`failed` 项、又没有同名任务在跑」
    /// 的项重新入队。连续失败计数清零。等所有 presign 与建任务完成后返回。
    public func reconcile() async {
        let snapshot = generation
        let live = await session.allTasks()
        var liveKeys = Set<String>()
        for task in live {
            guard let key = task.taskDescription, Self.parse(key) != nil else { continue }
            liveKeys.insert(key)
            if tasks[key] == nil { register(task, key: key) }
        }
        // 会话已经不认识、又是查询之前就登记的句柄:结束回调丢了(比如进程在交付途中被杀),清掉以便重传。
        for key in Array(tasks.keys) where !liveKeys.contains(key) && (taskGeneration[key] ?? 0) <= snapshot {
            tasks[key] = nil
            taskGeneration[key] = nil
        }
        failures.removeAll()
        retries.values.forEach { $0.cancel() }
        retries.removeAll()

        var started: [Task<Void, Never>] = []
        for list in store.threads.values {
            for message in list where message.direction == .outgoing && !message.body.isEmpty {
                guard message.media?.contains(where: {
                    $0.state == .uploading || ($0.state == .failed && $0.uploadFailure == nil)
                }) == true else { continue }
                started.append(start(messageId: message.id, only: nil, resetFailures: true))
            }
        }
        for task in started { await task.value }
    }

    // MARK: - 取消

    /// 取消这条消息的全部上传任务(含系统替上个进程续传的)。删消息在**删文件之前**调用。
    public func cancel(messageId: String) async {
        await cancel(messageIds: [messageId])
    }

    /// 批量取消(清空会话):本进程登记的立即取消,再**只问一次**后台会话,取消遗留任务。
    public func cancel(messageIds: [String]) async {
        let ids = Set(messageIds)
        guard !ids.isEmpty else { return }
        func belongs(_ key: String) -> Bool { Self.parse(key).map { ids.contains($0.messageId) } ?? false }
        for (key, task) in tasks where belongs(key) {
            task.cancel()
            tasks[key] = nil
            taskGeneration[key] = nil
        }
        for (key, retry) in retries where belongs(key) {
            retry.cancel()
            retries[key] = nil
        }
        for key in Array(failures.keys) where belongs(key) { failures[key] = nil }
        for task in await session.allTasks() where task.taskDescription.map(belongs) == true {
            task.cancel()
        }
    }

    /// 取消全部上传(删除账号)。
    public func cancelAll() async {
        tasks.values.forEach { $0.cancel() }
        tasks.removeAll()
        taskGeneration.removeAll()
        retries.values.forEach { $0.cancel() }
        retries.removeAll()
        failures.removeAll()
        for task in await session.allTasks() { task.cancel() }
    }

    // MARK: - 私有

    private func register(_ task: UploadTaskHandle, key: String) {
        generation += 1
        tasks[key] = task
        taskGeneration[key] = generation
    }

    private func findMessage(_ messageId: String) -> ChatMessage? {
        for list in store.threads.values {
            if let message = list.first(where: { $0.id == messageId }) { return message }
        }
        return nil
    }

    private func markFailed(messageId: String, index: Int, reason: MediaItem.UploadFailure? = nil) {
        guard let message = findMessage(messageId) else { return }
        try? store.updateMediaItem(messageId: messageId, peerId: message.peerId, index: index) {
            guard $0.state != .sealed else { return }
            $0.state = .failed
            if let reason { $0.uploadFailure = reason }
        }
    }

    static func description(messageId: String, index: Int) -> String { "\(messageId):\(index)" }

    static func parse(_ description: String) -> (messageId: String, index: Int)? {
        guard let colon = description.lastIndex(of: ":"),
              let index = Int(description[description.index(after: colon)...]) else { return nil }
        let messageId = String(description[..<colon])
        return messageId.isEmpty ? nil : (messageId, index)
    }

    /// 403(URL 过期,重新 presign)、408(超时)、429(限流)之外的 4xx 不会自己好:立即放弃。
    static func isUnretryableClientError(_ status: Int) -> Bool {
        (400..<500).contains(status) && ![403, 408, 429].contains(status)
    }

    private static func isCancellation(_ error: Error) -> Bool {
        if let error = error as? URLError { return error.code == .cancelled }
        let ns = error as NSError
        return ns.domain == NSURLErrorDomain && ns.code == NSURLErrorCancelled
    }

    /// presign 的错误换算成与上传回调同一套 (HTTP 状态, 错误),交给 `completeItem` 统一判定。
    private static func httpEquivalent(of error: Error) -> (Int?, Error?) {
        guard let error = error as? MediaTransportError else { return (nil, error) }
        switch error {
        case .rateLimited: return (429, nil)
        case .tooLarge: return (413, nil)
        case .gone: return (403, nil)
        case .server(let status), .rejected(let status): return (status, nil)
        case .network: return (nil, error)
        }
    }
}
