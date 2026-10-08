import Foundation

/// 选择/录制阶段就拦下的错误(spec §5.1「超出大小或时长 → 选择时拦下,不进入加密」)。
public enum MediaPrepError: Error, Equatable, UserFacingMediaError, CustomDebugStringConvertible {
    case unreadableImage
    case imageTooLarge
    case videoTooLong
    case videoTooLarge
    /// `reason` 只供本地调试(断点/日志),取自 `AVAssetExportSession.error?.localizedDescription`;
    /// 从不进入 `userMessage`,不面向用户。
    case videoExportFailed(reason: String? = nil)
    case voiceEncodeFailed
    /// 图片输出里仍检出位置/机型/时间等属性:发送失败而不是放行。`reason` 只供本地调试。
    case imageMetadataLeak(reason: String? = nil)

    public var userMessage: String {
        switch self {
        case .unreadableImage: return L10n.mediaImageUnreadable
        case .imageTooLarge: return L10n.mediaImageTooBig
        case .videoTooLong: return L10n.mediaVideoTooLong
        case .videoTooLarge: return L10n.mediaVideoTooBig
        case .videoExportFailed: return L10n.mediaVideoUnreadable
        case .voiceEncodeFailed: return L10n.mediaVoiceFailed
        case .imageMetadataLeak: return L10n.mediaImageProcessFailed
        }
    }

    /// 仅供本地调试(Console/断点),不经过任何日志管线,不面向用户。
    public var debugDescription: String {
        if case .videoExportFailed(let reason) = self, let reason {
            return "videoExportFailed(\(reason))"
        }
        return "\(self)"
    }
}
