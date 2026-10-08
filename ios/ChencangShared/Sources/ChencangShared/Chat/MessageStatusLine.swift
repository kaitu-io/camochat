import Foundation

/// 气泡下的状态行:只说用户做过的事(加密了 / 复制了 / 分享了),从不声称对方收到。
/// 收到的消息不显示状态;媒体消息还没产出分享文本(正文为空,还在封)时也不显示——那时由传输状态行说话。
public enum MessageStatusLine {
    public static func text(for message: ChatMessage) -> String? {
        guard message.direction == .outgoing else { return nil }
        if message.kind.isMedia && message.body.isEmpty { return nil }
        switch message.status {
        case .sealed: return L10n.statusEncryptedNotSent
        case .copied: return L10n.statusCopied
        case .shared: return L10n.statusShared
        case .received: return nil
        }
    }

    /// 状态行能不能点(点了在输入区上方重新出加密卡,再分享或复制):自己发出、还没分享
    /// (已加密 / 已复制)、手里有能交出去的文本——文字要存了 wire(老消息没有),媒体要已经产出分享文本。
    public static func isStatusTappable(_ message: ChatMessage) -> Bool {
        guard message.direction == .outgoing, message.status == .sealed || message.status == .copied else {
            return false
        }
        if message.kind == .text { return !(message.wire ?? "").isEmpty }
        if message.kind.isMedia { return !message.body.isEmpty }
        return false
    }
}
