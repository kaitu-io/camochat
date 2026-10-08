import Foundation
import CoreGraphics

/// 发送端媒体消息的整体状态(先分享、后上传 spec §1.3),与 Android `OutgoingMediaStatus` 同口径。
public enum OutgoingMediaStatus: Sendable {
    /// 还在压缩/加密(或加密完、分享文本还没落库)。
    case encrypting
    /// 已分享,有项在上传(含排队/退避中)。
    case uploading
    /// 全部项已上传。
    case uploaded
    /// 已分享,有项上传失败且还能重传(`.cca` 在、没有永久原因)。
    case notVisibleToPeer
    /// 已分享,失败的项全都重传不了(过大 / 被拒 / `.cca` 丢失):终态,点击只提示,只能删。
    case permanentlyFailed(OutgoingFailureReason)
    /// 还没分享(加密/封帧阶段失败),点红 ! 重新加密。
    case encryptFailed
}

/// 发送端永久失败的原因(与 Android `OutgoingFailureReason` 同名同义)。
public enum OutgoingFailureReason: Sendable, Equatable {
    /// 413。
    case tooLarge
    /// 其它被拒的 4xx。
    case rejected
    /// 已分享但 `.cca` 不在了(不能重新加密)。
    case fileMissing

    /// 多个失败项原因不同时取哪个:服务器给出的原因优先于「文件丢失」,过大优先于被拒。
    fileprivate var priority: Int {
        switch self {
        case .tooLarge: return 0
        case .rejected: return 1
        case .fileMissing: return 2
        }
    }
}

extension OutgoingMediaStatus: Equatable {}

/// 点状态行 / 红 ! 做什么。
public enum OutgoingStatusTap: Equatable, Sendable {
    /// 重试:未分享 → 重新加密;已分享 → 只交给上传引擎。
    case retry
    /// 终态:只弹这句提示。
    case notice(String)
}

/// 发送端媒体状态(纯函数,两端同口径)。优先级(UAT R1 裁决):encrypting > 可重试的失败 > uploading >
/// 永久失败 > 全部已上传——永久失败用户做不了什么,相册里还有项在传就先显示「上传中」。
/// - 未分享:有 `encrypting`/`uploading` → `encrypting`,否则 → `encryptFailed`
///   (与 `outgoingMediaNeedsRetry` 一致,旧数据里「项全 sealed 但封帧失败」也能重试)。
/// - 已分享:有 `encrypting` → `encrypting`;有可重试的 `failed` 项(没有永久原因且 `.cca` 在)→
///   `notVisibleToPeer`(点了能重传);有 `uploading` → `uploading`;还有 `failed`(全是永久失败)→
///   `permanentlyFailed(原因)`(终态,原因不一时按 过大 > 被拒 > 文件丢失 取);否则 `uploaded`。
/// `ccaExists(index)` 只对没有永久原因的 `failed` 项调用(已上传项的 `.cca` 早删了)。
public func outgoingMediaStatus(items: [MediaItem], shared: Bool,
                                ccaExists: (Int) -> Bool) -> OutgoingMediaStatus {
    if items.contains(where: { $0.state == .encrypting }) { return .encrypting }
    guard shared else {
        return items.contains(where: { $0.state == .uploading }) ? .encrypting : .encryptFailed
    }
    let failed = items.filter { $0.state == .failed }
    var reasons: [OutgoingFailureReason] = []
    for item in failed {
        switch item.uploadFailure {
        case .tooLarge: reasons.append(.tooLarge)
        case .rejected: reasons.append(.rejected)
        case nil:
            if ccaExists(item.index) { return .notVisibleToPeer }
            reasons.append(.fileMissing)
        }
    }
    if items.contains(where: { $0.state == .uploading }) { return .uploading }
    if let reason = reasons.min(by: { $0.priority < $1.priority }) { return .permanentlyFailed(reason) }
    return .uploaded
}

extension ChatMessage {
    /// 自己发出的媒体消息的状态;收到的、文字消息为 nil。
    public func outgoingMediaStatus(ccaExists: (Int) -> Bool) -> OutgoingMediaStatus? {
        guard direction == .outgoing, kind.isMedia, let items = media else { return nil }
        return ChencangShared.outgoingMediaStatus(items: items, shared: !body.isEmpty, ccaExists: ccaExists)
    }

    /// 同上,`.cca` 是否存在问 `files`。
    public func outgoingMediaStatus(files: MediaFiles) -> OutgoingMediaStatus? {
        outgoingMediaStatus { files.exists(files.ccaURL(messageId: id, index: $0)) }
    }
}

/// 媒体气泡的尺寸与文案(spec §3.4、R9、R11),纯函数,全部由 token 推导。
public enum MediaLayout {
    /// 语音气泡宽度随时长线性增长:0 秒 = voiceBubbleMin,60 秒 = voiceBubbleMax。
    public static func voiceBubbleWidth(durMs: Int) -> CGFloat {
        let clamped = CGFloat(min(max(durMs, 0), MediaLimits.maxDurationMs))
        let t = clamped / CGFloat(MediaLimits.maxDurationMs)
        return Moyu.Size.voiceBubbleMin + (Moyu.Size.voiceBubbleMax - Moyu.Size.voiceBubbleMin) * t
    }

    /// `12″`(四舍五入,最少 1 秒)。
    public static func voiceLabel(durMs: Int) -> String {
        "\(max(1, Int((Double(durMs) / 1000).rounded())))″"
    }

    /// `0:12`。
    public static func videoLabel(durMs: Int) -> String {
        let seconds = Int((Double(durMs) / 1000).rounded())
        return String(format: "%d:%02d", seconds / 60, seconds % 60)
    }

    /// 按帧内 w/h 等比放进 mediaThumbMax 方框,短边不小于 mediaThumbMin;尺寸未知时给正方形占位。
    public static func thumbSize(width: Int, height: Int) -> CGSize {
        let maxSide = Moyu.Size.mediaThumbMax
        let minSide = Moyu.Size.mediaThumbMin
        guard width > 0, height > 0 else { return CGSize(width: maxSide, height: maxSide) }
        let scale = min(maxSide / CGFloat(width), maxSide / CGFloat(height))
        return CGSize(width: max(minSide, (CGFloat(width) * scale).rounded()),
                      height: max(minSide, (CGFloat(height) * scale).rounded()))
    }

    /// 需要替代内容显示的状态文案(spec §5.1;先分享、后上传 spec §2)。`awaiting` 按轮询窗口是否已过
    /// 分两种:轮询中「等待对方上传」,窗口已过「还没收到文件 · 点击重试」(不断言是没传还是过期)。
    public static func stateText(_ state: MediaItem.State, awaitingWindowExpired: Bool = false) -> String? {
        switch state {
        case .expired: return L10n.mediaFailureExpired
        case .corrupt: return MediaNotice.corruptFile
        case .awaiting: return awaitingWindowExpired ? awaitingStalledText : awaitingText
        default: return nil
        }
    }

    public static var awaitingText: String { L10n.mediaAwaiting }
    public static var awaitingStalledText: String { L10n.mediaAwaitingStalled }

    /// 「等待对方上传」期间占位上转圈;窗口过后不转(等用户点)。
    public static func showsAwaitingSpinner(state: MediaItem.State, awaitingWindowExpired: Bool) -> Bool {
        state == .awaiting && !awaitingWindowExpired
    }

    /// 收到的媒体下载失败(网络/落盘,`.failed`)时,气泡旁显示红 ! 供点按重下(终审 I6)——
    /// 与发送失败同一个样式;以前 `.failed` 没有任何可见状态,图片永远是一个灰方块。
    public static func showsDownloadRetry(direction: ChatMessage.Direction, state: MediaItem.State) -> Bool {
        direction == .incoming && state == .failed
    }

    // MARK: - 发送端状态(先分享、后上传 spec §1.3)

    public static var encryptingText: String { L10n.mediaStatusEncrypting }
    public static var encryptFailedText: String { L10n.mediaStatusEncryptFailed }
    public static var uploadingText: String { L10n.mediaStatusUploading }
    public static var notVisibleToPeerText: String { L10n.mediaStatusNotVisible }
    public static var tooLargeText: String { L10n.mediaFailureTooLarge }
    public static var rejectedText: String { L10n.mediaFailureRejected }
    public static var fileLostText: String { L10n.mediaFailureFileMissing }
    public static var unsentPrefix: String { L10n.mediaUnsentPrefix }

    /// 最后一项下方的状态行(与 Android `OutgoingMediaStatus.statusLine` 同文案);nil = 沿用状态行(MessageStatusLine)。
    public static func outgoingStatusText(_ status: OutgoingMediaStatus) -> String? {
        switch status {
        case .encrypting: return encryptingText
        case .encryptFailed: return encryptFailedText
        case .uploading: return uploadingText
        case .notVisibleToPeer: return notVisibleToPeerText
        case .permanentlyFailed(.tooLarge): return tooLargeText
        case .permanentlyFailed(.rejected): return rejectedText
        case .permanentlyFailed(.fileMissing): return fileLostText
        case .uploaded: return nil
        }
    }

    /// 气泡旁红 ! 与错误色状态行:加密失败、对方还看不到、永久失败。
    public static func showsSendFailure(_ status: OutgoingMediaStatus) -> Bool {
        switch status {
        case .notVisibleToPeer, .permanentlyFailed, .encryptFailed: return true
        case .encrypting, .uploading, .uploaded: return false
        }
    }

    /// 点状态行 / 红 !:加密失败、对方还看不到 → 重试;永久失败 → 只提示原因;其它不可点(nil)。
    public static func outgoingStatusTap(_ status: OutgoingMediaStatus) -> OutgoingStatusTap? {
        switch status {
        case .encryptFailed, .notVisibleToPeer: return .retry
        case .permanentlyFailed: return outgoingStatusText(status).map(OutgoingStatusTap.notice)
        case .encrypting, .uploading, .uploaded: return nil
        }
    }

    /// 会话列表预览前缀:最后一条处于「对方还看不到」时加「[未上传] 」。
    public static func listPreviewPrefix(_ status: OutgoingMediaStatus?) -> String? {
        status == .notVisibleToPeer ? unsentPrefix : nil
    }
}
