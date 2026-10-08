import Foundation

/// 收件下载编排(spec §3.6、R9):
/// - 打开线程自动下载 pending 的语音与图片;视频点击后才下载;
/// - 同一时间最多 2 个下载;
/// - 消息时间戳(本地收到时间)+ 24 小时及以后判「已过期」,**不发请求**;服务器 403/404 按
///   `classifyGone` 区分:不满 24 h →「等待对方上传」(`awaiting`,可再取),否则「已过期」;
/// - `awaiting` 项再取时状态保持 `awaiting`(403 轮询时气泡不闪),真开始收 blob(首个进度回调)才切到
///   `downloading` 出进度环;这次再取遇断网/5xx/429(含下到一半失败)→ 回到/保持 `awaiting`、不提示,
///   交给下一轮轮询;首次下载遇同样错误仍是 `failed`。同一项同时只发一个请求;
/// - 下载长度 ≠ 帧内 byte_len、解密失败、kind 不符 → 「文件已损坏」;
/// - 成功:密文先落 `.cca`,解密写 `.bin`,再删 `.cca`(R5)。
@MainActor
public final class MediaDownloader {
    private let store: ChatStore
    private let mediaCrypto: MediaCrypto
    private let transport: MediaTransporting
    private let files: MediaFiles
    private let activity: MediaActivity
    private let now: () -> Date
    private let limiter: AsyncLimiter
    /// 正在取的项:`awaiting` 再取时不立刻切到 `.downloading`,靠它挡住同一项的并发请求(轮询 + 点击)。
    private var inFlight: Set<TransferKey> = []

    private struct TransferKey: Hashable {
        let messageId: String
        let index: Int
    }

    public init(store: ChatStore,
                mediaCrypto: MediaCrypto,
                transport: MediaTransporting,
                files: MediaFiles,
                activity: MediaActivity,
                now: @escaping () -> Date = Date.init,
                maxConcurrent: Int = 2) {
        self.store = store
        self.mediaCrypto = mediaCrypto
        self.transport = transport
        self.files = files
        self.activity = activity
        self.now = now
        self.limiter = AsyncLimiter(limit: maxConcurrent)
    }

    public func isExpired(_ message: ChatMessage) -> Bool {
        now().timeIntervalSince(message.timestamp) >= MediaLimits.ttl
    }

    /// 自动下载语音与图片(视频点了才下,R9)。`retryFailed` = 进线程那一次(终审 I6):
    /// 上次因网络失败停在 `.failed` 的也顺手再试一次;线程里新到消息触发的那几次只下 `.pending`,
    /// 免得断网时每来一条消息就把失败的全部重打一遍。
    public func autoDownload(peerId: String, retryFailed: Bool = false) async {
        let targets: [(id: String, index: Int)] = store.messages(for: peerId).flatMap { message -> [(id: String, index: Int)] in
            guard message.direction == .incoming, let items = message.media else { return [] }
            return items
                .filter { ($0.kind == .voice || $0.kind == .image)
                    && ($0.state == .pending || (retryFailed && $0.state == .failed)) }
                .map { (message.id, $0.index) }
        }
        await withTaskGroup(of: Void.self) { group in
            for target in targets {
                group.addTask { await self.download(messageId: target.id, peerId: peerId, index: target.index) }
            }
        }
    }

    public func download(messageId: String, peerId: String, index: Int) async {
        guard let message = store.message(id: messageId, peerId: peerId),
              let item = message.media?.first(where: { $0.index == index }),
              item.state == .pending || item.state == .failed || item.state == .awaiting else { return }
        let key = TransferKey(messageId: messageId, index: index)
        // 「等待对方上传」语境:轮询/点重试的再取。它的暂时性失败不打成 failed(spec §4「传完自动出现」)。
        let wasAwaiting = item.state == .awaiting
        guard !inFlight.contains(key) else { return }
        inFlight.insert(key)
        defer { inFlight.remove(key) }
        if isExpired(message) {
            set(.expired, messageId, peerId, index)
            return
        }
        // 帧内 byte_len 超过这类媒体的 `.cca` 上限(终审 minor 14):合法发送方不可能产出,
        // 直接判损坏,不去拉一个可能超大的响应体进内存。
        guard item.byteLen > 0, item.byteLen <= item.kind.maxBlobLen else {
            set(.corrupt, messageId, peerId, index)
            return
        }
        if item.state != .awaiting { set(.downloading, messageId, peerId, index) }
        await limiter.acquire()
        defer { limiter.release() }

        let activity = self.activity
        let throttle = ProgressThrottle()
        let blob: Data
        do {
            blob = try await transport.download(blobId: item.blobId) { value in
                // 回调线程上先节流(≤ 10 Hz),放行的才跳主 actor(终审 F2c / I5)。
                guard throttle.shouldEmit(value) else { return }
                Task { @MainActor [weak self] in
                    activity.setProgress(value, messageId: messageId, index: index)
                    if wasAwaiting { self?.markDownloading(key, peerId: peerId) }
                }
            }
            // 200 但长度与帧内 byte_len 对不上(截断 / 中间盒 / CDN 异常)是传输问题,不是密文坏了:
            // 与断网同样处理(可再取、轮询中不打断),「文件已损坏」只留给解密失败与 byte_len 预检(终审 F3)。
            guard blob.count == item.byteLen else { throw MediaTransportError.network }
        } catch MediaTransportError.gone {
            activity.clearProgress(messageId: messageId, index: index)
            set(classifyGone(receivedAt: message.timestamp, now: now()), messageId, peerId, index)
            return
        } catch let error as MediaTransportError where wasAwaiting && Self.isTransient(error) {
            // 轮询中的暂时性失败:保持(或从下载中退回)awaiting,不提示,下一轮再试;
            // 一直不通由 30 分钟窗口收尾(「还没收到文件 · 点击重试」)。
            activity.clearProgress(messageId: messageId, index: index)
            set(.awaiting, messageId, peerId, index)
            return
        } catch {
            activity.clearProgress(messageId: messageId, index: index)
            set(.failed, messageId, peerId, index)
            activity.post(error)
            return
        }
        activity.clearProgress(messageId: messageId, index: index)

        // 下载期间消息可能被删(长按删除/清空线程):不写任何文件、不复活记录,
        // 把下载途中落下的东西(整个消息目录)清掉就地放弃(呼应 MediaSender.abandon)。
        guard exists(messageId, peerId) else {
            files.delete(messageId: messageId)
            return
        }

        let cca = files.ccaURL(messageId: messageId, index: index)
        let disk = files
        do {
            // 密文落盘 + 解密 + 明文落盘全在后台(终审 minor 3):视频两份各 ~30 MB。
            let mediaCrypto = self.mediaCrypto
            let secret = item.blobSecret
            let kind = item.kind
            let plain: Data
            do {
                plain = try await Task.detached(priority: .userInitiated) { () throws -> Data in
                    try disk.write(blob, to: cca)
                    do {
                        return try mediaCrypto.open(blob, secret: secret, kind: kind)
                    } catch {
                        throw DecryptFailure()
                    }
                }.value
            } catch is DecryptFailure {
                files.remove(cca)
                set(.corrupt, messageId, peerId, index)
                return
            }
            // 解密这段 await 期间消息也可能被删——再查一次,免得把明文 `.bin` 写进一个
            // 已经不存在的消息的目录里。
            guard exists(messageId, peerId) else {
                files.delete(messageId: messageId)
                return
            }
            let binURL = files.binURL(messageId: messageId, index: index)
            try await Task.detached(priority: .userInitiated) {
                try disk.write(plain, to: binURL)
                disk.remove(cca)
            }.value
            set(.ready, messageId, peerId, index)
            // `set` 内部的 `updateMediaItem` 在消息已不存在时本就是空操作,但 `.bin` 已经
            // 落盘了——如果消息恰好在写盘这一刻被删,补一次目录清理,不留孤儿文件。
            if !exists(messageId, peerId) {
                files.delete(messageId: messageId)
            }
        } catch {
            // 落盘失败(多半是存储空间不足):可再下,并提示。
            files.remove(cca)
            set(.failed, messageId, peerId, index)
            activity.post(error)
        }
    }

    /// 断网 / 3xx / 408 / 429 / 5xx:过一会儿再试可能就好了(与上传侧重试口径一致,spec §1.1.1;终审 F4)。
    private static func isTransient(_ error: MediaTransportError) -> Bool {
        switch error {
        case .network, .rateLimited: return true
        case .server(let status): return (300..<400).contains(status) || status == 408 || status >= 500
        case .gone, .tooLarge, .rejected: return false
        }
    }

    /// awaiting 再取真开始收 blob:切到下载中出进度环。只在这次请求还在途且仍是 awaiting 时切——
    /// 迟到的进度任务不能把已经 ready / 退回 awaiting 的项改回去。
    private func markDownloading(_ key: TransferKey, peerId: String) {
        guard inFlight.contains(key),
              store.message(id: key.messageId, peerId: peerId)?.media?
                .first(where: { $0.index == key.index })?.state == .awaiting else { return }
        set(.downloading, key.messageId, peerId, key.index)
    }

    private func exists(_ messageId: String, _ peerId: String) -> Bool {
        store.message(id: messageId, peerId: peerId) != nil
    }

    /// 区分「解密失败 → 已损坏」与「落盘失败 → 可再下」。
    private struct DecryptFailure: Error {}

    private func set(_ state: MediaItem.State, _ messageId: String, _ peerId: String, _ index: Int) {
        try? store.updateMediaItem(messageId: messageId, peerId: peerId, index: index) { $0.state = state }
    }
}

/// 中转对这条引用答 403/404(「还没上传」与「已过期」在中转上都是 403)时的判定(spec §2):
/// 本地收到不满 24 h → `awaiting`(等待对方上传),否则 `expired`。与 Android `classifyGone` 同口径。
public func classifyGone(receivedAt: Date, now: Date) -> MediaItem.State {
    now.timeIntervalSince(receivedAt) < MediaLimits.ttl ? .awaiting : .expired
}

/// 主线程上的计数信号量:超过上限的调用排队,释放时直接把名额交给队首。
@MainActor
final class AsyncLimiter {
    private let limit: Int
    private var running = 0
    private var waiters: [CheckedContinuation<Void, Never>] = []

    init(limit: Int) {
        self.limit = max(1, limit)
    }

    func acquire() async {
        if running < limit {
            running += 1
            return
        }
        await withCheckedContinuation { waiters.append($0) }
    }

    func release() {
        if waiters.isEmpty {
            running -= 1
        } else {
            waiters.removeFirst().resume()
        }
    }
}
