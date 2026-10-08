import Foundation

/// 上传一条消息的结果(先分享、后上传 spec §1.1)。
public enum UploadOutcome: Equatable, Sendable {
    /// 所有项都已在桶里(本来就传完的算在内)。
    case done
    /// 暂时性失败:项保持 `uploading`、`.cca` 保留,稍后重传同一份密文。
    case retryable(MediaFailure)
    /// 不会自己好的失败:相关项已标 `failed`(消息已删则什么都不写)。
    case permanent(MediaFailure)
}

/// 上传失败原因。不带任何 secret / blob id / 明文,可以进日志。
public enum MediaFailure: Equatable, Sendable {
    case network
    case rateLimited
    case server
    /// 403:presign 过期(系统推迟了任务),重新 presign 再传。
    case gone
    case tooLarge
    /// 已分享但 `.cca` 不在了:不能重新加密(对方手里的密钥对不上新密文),只能标「文件丢失」。
    case fileMissing
    /// 消息在上传途中被删了。
    case deleted
    /// 消息还没有分享文本(加密阶段没完成),不该上传。
    case notShared
    /// 本机落库失败(磁盘满等)。
    case storage
    /// 同一条消息已有一次 `upload` 在跑。
    case busy
}

public typealias MediaSendOutcome = MediaSender.Outcome

/// 发送编排(spec 2026-09-30「先分享、后上传」):
/// 1. `seal`:落库一条 outgoing 媒体消息(条目 encrypting),写明文 `.bin`;逐条 core 封 `.cca`
///    (生成 blob_secret)并持久化;合成一个 MEDIA_REF 帧 → DR 加密 → R1 两行文本存进 `body`,
///    条目停在 `uploading`,**零网络**;交给注入的 `uploadScheduler`,返回分享文本给 UI 弹分享面板。
/// 2. `upload`:幂等地逐项重新 presign → PUT(`If-None-Match: *`,412 = 已在桶里)→ 项 `sealed`、删 `.cca`。
///
/// **不变量:分享出去之后永不重新加密。** `body` 非空后每一项的 secret/blobId/`.cca` 冻结,
/// `.cca` 在该项上传成功前不删;`.cca` 丢了只能标失败,绝不补一份新密文。
/// 加密阶段失败(没有 `body`)才允许 `retry` 重新加密。
@MainActor
public final class MediaSender {
    public enum Outcome: Equatable {
        case sealed(messageId: String, shareText: String)
        case failed(messageId: String)
        /// 同一条消息已有一次封缄在跑,这次调用被忽略(防连点重复加密、重复推进棘轮)。
        case inProgress(messageId: String)
    }

    public enum UploadError: Error, Equatable {
        case messageGone
        case notShared
        case fileMissing
    }

    private let store: ChatStore
    private let crypto: ThreadCrypto
    private let mediaCrypto: MediaCrypto
    private let transport: MediaTransporting
    private let files: MediaFiles
    private let activity: MediaActivity
    private let uploadScheduler: @MainActor (String) -> Void
    private let now: () -> Date
    private let newId: () -> String
    private let site: () -> String
    /// 正在封缄的消息。
    private var inFlight: Set<String> = []
    /// 正在进程内 `upload` 的消息。
    private var uploading: Set<String> = []
    /// 每条媒体一个上传"世代":`onProgress` 回调可能在 `.upload` 已经返回、
    /// 我们已经清过进度条之后才真正跑到 MainActor 上——世代校验让那种迟到的回调
    /// 变成空操作,不会把已清掉的进度重新点亮(终审 M2)。
    private let progressGate = ProgressGate()

    public init(store: ChatStore,
                crypto: ThreadCrypto,
                mediaCrypto: MediaCrypto,
                transport: MediaTransporting,
                files: MediaFiles,
                activity: MediaActivity,
                uploadScheduler: @escaping @MainActor (String) -> Void,
                now: @escaping () -> Date = Date.init,
                newId: @escaping () -> String = { UUID().uuidString },
                site: @escaping () -> String = { ConfigRepository.shared.current().shareSite }) {
        self.site = site
        self.store = store
        self.crypto = crypto
        self.mediaCrypto = mediaCrypto
        self.transport = transport
        self.files = files
        self.activity = activity
        self.uploadScheduler = uploadScheduler
        self.now = now
        self.newId = newId
    }

    // MARK: - 封缄(零网络)

    /// 加密全部项、成帧、落分享文本,然后交给上传调度器。不发任何网络请求。
    public func seal(to peerId: String, media: [PreparedMedia]) async -> Outcome {
        precondition(!media.isEmpty && media.count <= MediaLimits.maxItemsPerFrame, "一帧 1..9 条媒体")
        let id = newId()
        // 选图/录音层不会产出混 kind 的一批,这里只是兜底这条不变量(终审 M3):
        // 一帧的 kind 只存了 media[0] 一份,混 kind 会让分享文案和收件方 openWire 的
        // "混 kind→unsupported" 判断对不上。
        guard media.allSatisfy({ $0.kind == media[0].kind }) else {
            activity.post(message: L10n.mediaErrorMixedKinds)
            return .failed(messageId: id)
        }
        let items = media.enumerated().map { index, m in
            MediaItem(index: index, kind: m.kind, durMs: m.durMs, width: m.width, height: m.height,
                      byteLen: 0, blobSecret: Data(), blobId: "", state: .encrypting)
        }
        let message = ChatMessage(id: id, peerId: peerId, direction: .outgoing, body: "", timestamp: now(),
                                  status: .sealed, kind: media[0].kind.messageKind, media: items)
        do {
            try store.append(message)
            // 明文最大 30 MB:落盘放到后台(终审 minor 3),不在主 actor 上同步写。
            let files = self.files
            let plaintexts = media.map(\.plaintext)
            try await Task.detached(priority: .userInitiated) {
                for (index, plain) in plaintexts.enumerated() {
                    try files.write(plain, to: files.binURL(messageId: id, index: index))
                }
            }.value
        } catch {
            activity.post(error)
            markUnuploadedFailed(messageId: id, peerId: peerId)
            return .failed(messageId: id)
        }
        // 后台写盘期间用户可能已经删了这条:不留孤儿明文。
        guard exists(id, peerId) else { return abandon(id) }
        return await runOnce(messageId: id, peerId: peerId)
    }

    /// = `seal`(封缄完成即交给上传调度器)。
    public func send(to peerId: String, media: [PreparedMedia]) async -> Outcome {
        await seal(to: peerId, media: media)
    }

    /// 已分享 → 只交给上传调度器(不加密、不改分享文本);未分享(加密阶段失败)→ 重新走封缄。
    public func retry(messageId: String, peerId: String) async -> Outcome {
        if let message = store.message(id: messageId, peerId: peerId), !message.body.isEmpty {
            uploadScheduler(messageId)
            return .sealed(messageId: messageId, shareText: message.body)
        }
        return await runOnce(messageId: messageId, peerId: peerId)
    }

    /// 这条消息是否已经产出分享文本(之后永不重新加密)。
    public func isShared(_ messageId: String) -> Bool {
        !(findMessage(messageId)?.body.isEmpty ?? true)
    }

    public enum ForwardError: Error, Equatable {
        /// 下标不在源消息的条目范围内;此时不读盘、不上传、不建任何消息。
        case indexOutOfRange
    }

    /// 转发(R9):用本机明文重新 `encryptMediaBlob` 得新 secret → 新帧,发给另一联系人,走同一 `seal` + 上传引擎。
    /// `indices == nil` 转发全部;否则只转发指定下标(去重、升序),任一下标越界或 `indices` 为空数组都抛 `indexOutOfRange`。
    /// 源消息/明文缺失返回 nil。原会话、原消息不受影响。
    public func forward(messageId: String, fromPeer: String, indices: [Int]?, toPeer: String) async throws -> Outcome? {
        guard let source = store.message(id: messageId, peerId: fromPeer), let items = source.media else { return nil }
        let wanted: [Int]
        if let indices {
            let valid = Set(items.map(\.index))
            guard !indices.isEmpty, indices.allSatisfy(valid.contains) else { throw ForwardError.indexOutOfRange }
            wanted = Array(Set(indices)).sorted()
        } else {
            wanted = items.map(\.index).sorted()
        }
        var prepared: [PreparedMedia] = []
        for item in items.filter({ wanted.contains($0.index) }).sorted(by: { $0.index < $1.index }) {
            let index = item.index
            let binURL = files.binURL(messageId: messageId, index: index)
            guard let plain = await Task.detached(priority: .userInitiated, operation: { try? Data(contentsOf: binURL) }).value
            else { return nil }
            prepared.append(PreparedMedia(kind: item.kind, plaintext: plain, durMs: item.durMs,
                                          width: item.width, height: item.height))
        }
        return await seal(to: toPeer, media: prepared)
    }

    private func runOnce(messageId: String, peerId: String) async -> Outcome {
        guard !inFlight.contains(messageId) else { return .inProgress(messageId: messageId) }
        inFlight.insert(messageId)
        defer { inFlight.remove(messageId) }
        return await encryptAndSeal(messageId: messageId, peerId: peerId)
    }

    /// 每个 await 之后都可能发生「用户长按删除了这条消息」:所有落库都走局部更新,
    /// 并在落库前确认消息仍在;不在就放弃(`abandon`),绝不把删掉的消息写回来。
    private func encryptAndSeal(messageId: String, peerId: String) async -> Outcome {
        guard let message = store.message(id: messageId, peerId: peerId), var items = message.media else {
            return .failed(messageId: messageId)
        }
        if !message.body.isEmpty {
            return .sealed(messageId: messageId, shareText: message.body)
        }
        // 还没分享:允许(重新)加密。`sealed` 的项是旧版流程里已经传完的,secret 已有,原样保留。
        for i in items.indices where items[i].state != .sealed {
            let index = items[i].index
            let ccaURL = files.ccaURL(messageId: messageId, index: index)
            do {
                if items[i].blobSecret.isEmpty || !files.exists(ccaURL) {
                    // 封 `.cca` 并持久化(之后上传只重传这份密文)。
                    // 也兜底「有 secret 但 `.cca` 不见了」的畸形态:还没分享,可以重新加密,
                    // 而且绝不复用旧 secret——core 每次都发一把新的
                    // (nonce 安全:同一把 secret 配两份不同密文是硬红线)。
                    items[i].state = .encrypting
                    if case .messageGone = try save(items[i], messageId, peerId) { return abandon(messageId) }
                    let binURL = files.binURL(messageId: messageId, index: index)
                    let kind = items[i].kind
                    let mediaCrypto = self.mediaCrypto
                    let files = self.files
                    // 读明文、封 `.cca`、落盘都在后台(终审 minor 3):视频明文/密文各 ~30 MB。
                    // 落盘在「确认消息仍在」之前发生也无妨——消息没了就走 abandon 整目录删除。
                    let sealed = try await Task.detached(priority: .userInitiated) { () throws -> SealedMediaBlob in
                        let plain = try Data(contentsOf: binURL)
                        let sealed = try mediaCrypto.seal(plain, kind: kind)
                        try files.write(sealed.blob, to: ccaURL)
                        return sealed
                    }.value
                    guard exists(messageId, peerId) else { return abandon(messageId) }
                    items[i].blobSecret = sealed.secret
                    items[i].blobId = sealed.blobId
                    items[i].byteLen = sealed.blob.count
                }
                items[i].state = .uploading
                if case .messageGone = try save(items[i], messageId, peerId) { return abandon(messageId) }
            } catch {
                return failSealing(messageId: messageId, peerId: peerId, index: index, items: &items[i], error: error)
            }
        }
        guard let current = store.message(id: messageId, peerId: peerId) else { return abandon(messageId) }
        do {
            // 封帧会推进 DR 发送链:先拿到 wire,再确认消息没被删,才真正落 body
            // (终审 M1)——消息已经没了就不要再往它身上写东西。
            let wire = try await crypto.sealMedia(peerId: peerId, items: items)
            guard exists(messageId, peerId) else { return abandon(messageId) }
            let text = MediaShareText.compose(kind: current.kind, items: items, wire: wire, site: site())
            try store.setBody(messageId: messageId, peerId: peerId, body: text)
            uploadScheduler(messageId)
            return .sealed(messageId: messageId, shareText: text)
        } catch {
            // 没产出分享文本:条目不能停在 uploading(气泡既不能重试也不会真的传),一起标 failed 给红 !。
            // `setBody` 落盘失败时自己会把内存滚回空 body;这里再兜底一次,确保「body 非空 = 已分享」不被伪造。
            if let current = store.message(id: messageId, peerId: peerId), !current.body.isEmpty {
                try? store.setBody(messageId: messageId, peerId: peerId, body: "")
            }
            markUnuploadedFailed(messageId: messageId, peerId: peerId)
            activity.post(error)
            return .failed(messageId: messageId)
        }
    }

    private func failSealing(messageId: String, peerId: String, index: Int,
                             items item: inout MediaItem, error: Error) -> Outcome {
        item.state = .failed
        if let outcome = try? save(item, messageId, peerId), case .messageGone = outcome {
            return abandon(messageId)
        }
        // 落库本身失败(磁盘满等)时,上面这次 save 也会抛、被 try? 吞掉——
        // 尽力而为,不再假装成功;不管落没落成,都要把结果吐给调用方。
        // 连同其它还没完成的条目一起标 failed:不然后面几张图停在 encrypting,
        // 气泡既不转圈也点不出红 !(终审 I1)。
        markUnuploadedFailed(messageId: messageId, peerId: peerId)
        activity.post(error)
        return .failed(messageId: messageId)
    }

    // MARK: - 上传(幂等)

    /// 进程内逐项上传所有未上传的项。可反复调用:已上传的项跳过,412 视为成功。
    /// 后台上传引擎不走这里,而是复用 `presignedRequest` + `completeItem`。
    public func upload(messageId: String) async -> UploadOutcome {
        guard let message = findMessage(messageId), let initial = message.media else { return .permanent(.deleted) }
        guard !message.body.isEmpty else { return .permanent(.notShared) }
        guard !uploading.contains(messageId) else { return .retryable(.busy) }
        uploading.insert(messageId)
        defer { uploading.remove(messageId) }
        let peerId = message.peerId

        var permanent: MediaFailure?
        for index in initial.map(\.index) {
            guard let item = store.message(id: messageId, peerId: peerId)?.media?.first(where: { $0.index == index })
            else { return deleted(messageId) }
            guard item.state != .sealed else { continue }
            let ccaURL = files.ccaURL(messageId: messageId, index: index)
            guard files.exists(ccaURL) else {
                // 先求值再合并:`??` 右侧是自动闭包,已有失败时会被跳过,后面缺文件的项就不会标 failed。
                let missing = markFileMissing(messageId: messageId, peerId: peerId, index: index)
                permanent = permanent ?? missing
                continue
            }
            if item.state != .uploading {
                do {
                    try store.updateMediaItem(messageId: messageId, peerId: peerId, index: index) { $0.state = .uploading }
                } catch {
                    return .retryable(.storage)
                }
            }
            guard let blob = await Task.detached(priority: .userInitiated, operation: { try? Data(contentsOf: ccaURL) }).value
            else {
                guard exists(messageId, peerId) else { return deleted(messageId) }
                // 先求值再合并:`??` 右侧是自动闭包,已有失败时会被跳过,后面缺文件的项就不会标 failed。
                let missing = markFileMissing(messageId: messageId, peerId: peerId, index: index)
                permanent = permanent ?? missing
                continue
            }

            var status: Int?
            var failure: Error?
            let key = MediaActivity.key(messageId: messageId, index: index)
            let epoch = progressGate.begin(key)
            do {
                let url = try await transport.requestUploadUrl(blobId: item.blobId, byteLen: blob.count, kind: item.kind)
                let activity = self.activity
                let gate = progressGate
                let throttle = ProgressThrottle()
                try await transport.upload(url: url, body: blob) { value in
                    // 先在回调线程上节流(≤ 10 Hz),放行的才跳主 actor(终审 F2c / I5)。
                    guard throttle.shouldEmit(value) else { return }
                    Task { @MainActor in
                        // 世代校验在 MainActor 上、真正执行的那一刻才做:此时如果 upload()
                        // 早就返回并已经 `end(key)` 过,这次回调就是迟到的,直接吞掉,
                        // 不会在 clearProgress 之后又把进度点回去。
                        guard gate.isCurrent(key, epoch) else { return }
                        activity.setProgress(value, messageId: messageId, index: index)
                    }
                }
                status = 200
            } catch let error as MediaTransportError {
                (status, failure) = Self.httpEquivalent(of: error)
            } catch {
                failure = error
            }
            progressGate.end(key)
            activity.clearProgress(messageId: messageId, index: index)

            switch completeItem(messageId: messageId, index: index, httpStatus: status, error: failure) {
            case .done:
                continue
            case .retryable(let reason):
                return .retryable(reason)
            case .permanent(.deleted):
                return deleted(messageId)
            case .permanent(let reason):
                permanent = permanent ?? reason
            }
        }
        return permanent.map(UploadOutcome.permanent) ?? .done
    }

    /// 为某一项重新申请上传地址(URL 只活 300 s,每次尝试都要新的),返回可直接交给
    /// 后台上传任务的 PUT 请求:`Content-Type: application/octet-stream`、`If-None-Match: *`。
    /// 不读 `.cca` 内容、不加密;body 由调用方用 `.cca` 文件本身提供。
    public func presignedRequest(messageId: String, index: Int) async throws -> URLRequest {
        guard let message = findMessage(messageId),
              let item = message.media?.first(where: { $0.index == index }) else { throw UploadError.messageGone }
        guard !message.body.isEmpty else { throw UploadError.notShared }
        guard files.exists(files.ccaURL(messageId: messageId, index: index)) else { throw UploadError.fileMissing }
        let url = try await transport.requestUploadUrl(blobId: item.blobId, byteLen: item.byteLen, kind: item.kind)
        var request = URLRequest(url: url)
        request.httpMethod = "PUT"
        request.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
        request.setValue("*", forHTTPHeaderField: "If-None-Match")
        return request
    }

    /// 一次上传尝试结束后落结果。2xx/412 → 项 `sealed`、删 `.cca`;网络错误/5xx/429 → 可重试,
    /// 项与 `.cca` 原样;403 → `.retryable(.gone)`(需重新 presign);413 → 项 `failed`。
    public func completeItem(messageId: String, index: Int, httpStatus: Int?, error: Error?) -> UploadOutcome {
        guard let message = findMessage(messageId),
              let item = message.media?.first(where: { $0.index == index }) else { return .permanent(.deleted) }
        let peerId = message.peerId
        if error != nil { return .retryable(.network) }
        guard let status = httpStatus else { return .retryable(.network) }
        switch status {
        case 200..<300, 412:
            if item.state != .sealed {
                do {
                    try store.updateMediaItem(messageId: messageId, peerId: peerId, index: index) { $0.state = .sealed }
                } catch {
                    // 没落成「已上传」就别删 `.cca`:下次重传会得 412,再落一次。
                    return .retryable(.storage)
                }
            }
            // 先落「已上传」,再删 `.cca`:反过来的话,落库失败或进程被杀死在两步之间
            // 会留下「uploading + 没有 `.cca`」,已分享的消息就再也传不上去了。
            files.remove(files.ccaURL(messageId: messageId, index: index))
            return .done
        case 403:
            return .retryable(.gone)
        case 413:
            // 另一条完成路径可能已经把这项落成 sealed:绝不降级。
            guard item.state != .sealed else { return .done }
            // 记下原因:对账不再自动重传它(否则每次回前台都重传、都再弹一次「文件太大」,审查 M7)。
            try? store.updateMediaItem(messageId: messageId, peerId: peerId, index: index) {
                guard $0.state != .sealed else { return }
                $0.state = .failed
                $0.uploadFailure = .tooLarge
            }
            activity.post(MediaTransportError.tooLarge)
            return .permanent(.tooLarge)
        case 429:
            return .retryable(.rateLimited)
        default:
            return .retryable(.server)
        }
    }

    private static func httpEquivalent(of error: MediaTransportError) -> (Int?, Error?) {
        switch error {
        case .rateLimited: return (429, nil)
        case .tooLarge: return (413, nil)
        case .gone: return (403, nil)
        case .server(let status), .rejected(let status): return (status, nil)
        case .network: return (nil, error)
        }
    }

    /// `.cca` 不在了:项标 failed。但如果在我们等待期间另一条完成路径已经把它传完(sealed 后删 `.cca`),
    /// 这不是「文件丢失」,原样保留、返回 nil。
    private func markFileMissing(messageId: String, peerId: String, index: Int) -> MediaFailure? {
        let current = store.message(id: messageId, peerId: peerId)?.media?.first { $0.index == index }
        guard let current, current.state != .sealed else { return nil }
        try? store.updateMediaItem(messageId: messageId, peerId: peerId, index: index) {
            if $0.state != .sealed { $0.state = .failed }
        }
        return .fileMissing
    }

    private func deleted(_ messageId: String) -> UploadOutcome {
        _ = abandon(messageId)
        return .permanent(.deleted)
    }

    // MARK: - 私有

    private func findMessage(_ messageId: String) -> ChatMessage? {
        for list in store.threads.values {
            if let message = list.first(where: { $0.id == messageId }) { return message }
        }
        return nil
    }

    private func exists(_ messageId: String, _ peerId: String) -> Bool {
        store.message(id: messageId, peerId: peerId) != nil
    }

    private enum SaveOutcome {
        case saved
        case messageGone
    }

    /// 局部写回一个条目。消息已被删 → `.messageGone`(调用方应 `abandon`);
    /// 消息还在但落盘本身失败(磁盘满等)→ **抛出**该错误,不再吞掉(终审 I3):
    /// 调用方靠这个抛出走到统一的 catch,报 `.failed` 并弹出对应提示,而不是
    /// 误以为落库成功、接着还去跑加密。
    private func save(_ item: MediaItem, _ messageId: String, _ peerId: String) throws -> SaveOutcome {
        guard exists(messageId, peerId) else { return .messageGone }
        try store.updateMediaItem(messageId: messageId, peerId: peerId, index: item.index) { $0 = item }
        return .saved
    }

    /// 消息在途中被删:清掉删除之后才落盘的文件(如刚写好的 `.cca`),什么记录都不写。
    private func abandon(_ messageId: String) -> Outcome {
        files.delete(messageId: messageId)
        return .failed(messageId: messageId)
    }

    /// 仅用于**分享之前**:把还没传完的条目标 failed(消息红 !,点重试重新封缄)。
    private func markUnuploadedFailed(messageId: String, peerId: String) {
        guard let items = store.message(id: messageId, peerId: peerId)?.media else { return }
        for item in items where item.state != .sealed {
            try? store.updateMediaItem(messageId: messageId, peerId: peerId, index: item.index) { $0.state = .failed }
        }
    }
}

/// 给上传进度回调用的世代校验器:线程安全、`Sendable`,不需要在 `@Sendable` 闭包里
/// 捕获 `MediaSender` 自己(终审 M2)。
private final class ProgressGate: @unchecked Sendable {
    private let lock = NSLock()
    private var epochs: [String: Int] = [:]

    func begin(_ key: String) -> Int {
        lock.withLock {
            let next = (epochs[key] ?? 0) + 1
            epochs[key] = next
            return next
        }
    }

    func end(_ key: String) {
        lock.withLock { epochs[key] = (epochs[key] ?? 0) + 1 }
    }

    func isCurrent(_ key: String, _ epoch: Int) -> Bool {
        lock.withLock { epochs[key] == epoch }
    }
}
