import Foundation

/// 对话页顶部横幅的种类;`key` 写进「已关闭」记录,不能改。
public enum ThreadBanner: String, Equatable, Sendable {
    /// 我是接受方,对方还没发来任何消息:等对方完成添加(此时不能核对)。
    case waitingPeer = "waiting_peer"
    /// 我是发起方,还没发过消息:先发一句话试试。
    case sayHi = "say_hi"
    /// 还没核对表情图案:可以和对方一起核对。
    case verifyLater = "verify_later"
}

public enum ThreadBanners {
    /// 「已关闭」记录的元素:联系人 + 横幅类型。
    public static func dismissKey(contactId: String, banner: ThreadBanner) -> String {
        "\(contactId)|\(banner.rawValue)"
    }

    /// 优先级:等对方(接受方且对方没发过消息)> 先说句话(发起方且没发过消息)> 未核对。
    /// 等对方被关掉后也不往下落到「核对」——对方还没加完,现在核对不了;其余被关掉的才落到下一条。
    public static func pick(contact: AppGroupContact?, hasIncoming: Bool, hasOutgoing: Bool,
                            dismissed: Set<String>) -> ThreadBanner? {
        guard let contact else { return nil }
        func open(_ b: ThreadBanner) -> Bool { !dismissed.contains(dismissKey(contactId: contact.id, banner: b)) }
        let iAccepted = contact.acceptedInviteDigest != nil
        if iAccepted && !hasIncoming { return open(.waitingPeer) ? .waitingPeer : nil }
        if !iAccepted && !hasOutgoing && open(.sayHi) { return .sayHi }
        if !contact.isVerified && open(.verifyLater) { return .verifyLater }
        return nil
    }
}
