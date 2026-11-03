import Foundation

/// 文字气泡长按菜单的一项。
public enum BubbleMenuItem: Hashable {
    /// 再分享一次加密消息(自己发出、存了 wire)。
    case share
    /// 复制加密消息(首行 + wire),标已复制。
    case copyEncrypted
    /// 复制明文,不改状态。
    case copyText
    /// 举报收到的消息(只给对方发来的)。
    case report
    case delete
}

/// 文字气泡(含「新类型消息」占位)长按菜单:哪些项、叫什么。媒体气泡有自己的菜单,不走这里。
public enum BubbleMenu {
    public static func items(for message: ChatMessage) -> [BubbleMenuItem] {
        let report: [BubbleMenuItem] = message.direction == .incoming ? [.report] : []
        guard message.kind == .text else { return report + [.delete] }
        if message.direction == .outgoing, !(message.wire ?? "").isEmpty {
            return [.share, .copyEncrypted, .copyText, .delete]
        }
        return [.copyText] + report + [.delete]
    }

    public static func title(_ item: BubbleMenuItem, for message: ChatMessage) -> String {
        switch item {
        case .share: return L10n.commonShare
        case .copyEncrypted: return L10n.threadCopyEncrypted
        case .copyText: return message.direction == .outgoing ? L10n.threadCopyOriginal : L10n.commonCopy
        case .report: return L10n.threadReport
        case .delete: return L10n.commonDelete
        }
    }

    public static func systemImage(_ item: BubbleMenuItem) -> String {
        switch item {
        case .share: return "square.and.arrow.up"
        case .copyEncrypted: return "lock.doc"
        case .copyText: return "doc.on.doc"
        case .report: return "exclamationmark.bubble"
        case .delete: return "trash"
        }
    }
}
