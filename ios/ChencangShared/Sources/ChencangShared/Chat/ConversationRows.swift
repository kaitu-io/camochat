import Foundation

/// 会话列表一行:联系人 + 摘要(最近一条消息,或还没有消息时的状态说明)。
public struct ConversationRow: Equatable, Identifiable {
    /// 摘要来源。没有消息的行不吃「[未上传]」前缀,也不受摘要隐私开关影响(本来就没有明文)。
    public enum Preview: Equatable {
        case message(ChatMessage)
        /// 我接受了对方的邀请、回执已发出,在等对方的第一条消息。
        case waitingPeer
        /// 我发起的配对已完成(或老数据),还没有消息。
        case noMessages
    }

    public let contact: AppGroupContact
    public let preview: Preview
    public var id: String { contact.id }

    /// 最近一条消息;没有消息的行为 nil。
    public var last: ChatMessage? {
        if case let .message(m) = preview { return m }
        return nil
    }

    /// 行尾时间与排序依据:有消息 = 最近一条的时间;没有消息 = 配对时间(老数据没有 → nil,不显示时间)。
    public var time: Date? { last?.timestamp ?? contact.pairedAt }

    public init(contact: AppGroupContact, preview: Preview) {
        self.contact = contact
        self.preview = preview
    }

    public init(contact: AppGroupContact, last: ChatMessage) {
        self.init(contact: contact, preview: .message(last))
    }
}

/// 会话列表:所有联系人各一行,按 ``ConversationRow/time`` 倒序(没有时间的排最后,并列按 id)。
/// 没有消息的联系人也出行(配对完找不到人会以为丢了、重复加);`excluding` 里的指纹(尚未发出回应的
/// 接受方,只在联系人 tab「配对中」)在没有消息时不出行。纯函数,无副作用。
public func conversationRows(
    contacts: [AppGroupContact], latest: [String: ChatMessage], excluding: Set<String> = []
) -> [ConversationRow] {
    contacts
        .compactMap { c -> ConversationRow? in
            if let m = latest[c.id] { return ConversationRow(contact: c, last: m) }
            guard !excluding.contains(c.id) else { return nil }
            return ConversationRow(contact: c, preview: c.acceptedInviteDigest != nil ? .waitingPeer : .noMessages)
        }
        .sorted { a, b in
            let ta = a.time ?? .distantPast, tb = b.time ?? .distantPast
            if ta != tb { return ta > tb }
            // 排序不保证稳定:时间相同(含都没有时间)时按 id 定序,界面顺序才可复现。
            return a.id < b.id
        }
}

/// 联系人 tab 一行。
public struct ContactRow: Equatable, Identifiable {
    public let contact: AppGroupContact
    public var id: String { contact.id }

    public init(contact: AppGroupContact) {
        self.contact = contact
    }
}

/// 联系人 tab 排序(spec §7.2):显示名整串按传入 locale(默认系统语言)、忽略大小写比较,
/// 并列再按 id 升序。zh 下标点/符号/数字 < 汉字按拼音 < 拉丁字母;en 下拉丁字母在汉字之前。
/// `excluding` 里的指纹不出行(尚未发出回应的接受方联系人只在「配对中」)。
public func contactRows(contacts: [AppGroupContact], excluding: Set<String> = [], locale: Locale = .current) -> [ContactRow] {
    return contacts
        .filter { !excluding.contains($0.id) }
        .sorted { a, b in
            let order = a.displayName.compare(b.displayName, options: .caseInsensitive, range: nil, locale: locale)
            if order != .orderedSame { return order == .orderedAscending }
            return a.id < b.id
        }
        .map { ContactRow(contact: $0) }
}
