import Combine
import Foundation

/// 会话 tab 的空状态(spec §3.1)。`nil` = 不画空状态(有行,或数据还没加载完)。
public enum ConversationEmptyState: Equatable, Sendable {
    /// A:没有任何联系人、也没有配对中条目 →「开始配对」。
    case startPairing
    /// B:没有可列的行、但有配对中条目(或只有尚未发出回应的联系人)→「去联系人」。
    case goToContacts
}

/// 空状态判定。`loaded == false` 时一律不画——否则冷启动会先闪出错的那一种(Android 踩过的坑)。
public func conversationEmptyState(loaded: Bool, hasRows: Bool, hasContacts: Bool, hasPending: Bool) -> ConversationEmptyState? {
    guard loaded, !hasRows else { return nil }
    return (hasContacts || hasPending) ? .goToContacts : .startPairing
}

/// 会话 tab 的列表数据:行与空状态在这里随源数据变化时算好,视图只读。
@MainActor
public final class ConversationListViewModel: ObservableObject {
    @Published public private(set) var rows: [ConversationRow] = []
    @Published public private(set) var emptyState: ConversationEmptyState?
    /// 「在等对方回复」的邀请数(``awaitingInvites``);会话 tab 顶部等待行用。
    @Published public private(set) var awaitingCount = 0

    private var bag = Set<AnyCancellable>()

    public init(
        contactsStore: ContactsStore = .shared,
        chatStore: ChatStore,
        inviteStore: PendingInviteStore = .shared,
        responseStore: PairingResponseStore = .shared,
        now: @escaping () -> Date = Date.init
    ) {
        // 用 sink 带来的新值(@Published 先发值、后写属性),不回读 store 属性。
        let sources = Publishers.CombineLatest4(contactsStore.$contacts, chatStore.$threads, inviteStore.$records, responseStore.$records)
        // 第五个源:读不了的会话集合。读回空会话时 `threads` 不变,只有它在变。
        Publishers.CombineLatest(sources, chatStore.$unreadablePeers)
            .sink { [weak self] sources, unreadable in
                let (contacts, threads, invites, responses) = sources
                guard let self else { return }
                let ids = Set(contacts.map(\.id))
                // 与联系人 tab 同一排除口径:回应还没发出去的接受方只在「配对中」。
                let rows = conversationRows(
                    contacts: contacts, latest: threads.compactMapValues { $0.last },
                    excluding: unsentResponseFingerprints(responses: responses, contactIds: ids))
                let pending = pendingItems(
                    invites: invites, responses: responses, contactIds: ids,
                    nowMillis: Int64(now().timeIntervalSince1970 * 1000))
                if self.rows != rows { self.rows = rows }
                let empty = conversationEmptyState(
                    loaded: unreadable.isEmpty, hasRows: !rows.isEmpty,
                    hasContacts: !contacts.isEmpty, hasPending: !pending.isEmpty)
                if self.emptyState != empty { self.emptyState = empty }
                let awaiting = awaitingInvites(invites, nowMillis: Int64(now().timeIntervalSince1970 * 1000)).count
                if self.awaitingCount != awaiting { self.awaitingCount = awaiting }
            }
            .store(in: &bag)
    }
}

/// 「配对中」一行的界面模型(规 U):标题、副标题、颜色语义、头像、行尾动作都在这里算好,视图只读。
public struct PendingRow: Equatable, Identifiable {
    /// 副标题颜色语义:「我的配对码还没发出去」= warn,「等对方发回配对码」= secondary,超 30 天 = tertiary。
    public enum Tone: Equatable { case warn, secondary, tertiary }

    /// 邀请 = pairingId;回应 = 对端指纹。
    public let id: String
    public let kind: PendingKind
    public let isResponse: Bool
    /// 邀请显示备注(没填 = 「没有备注的配对码」);回应显示联系人显示名。
    public let title: String
    /// 标题是占位文案(用 `text-secondary`)。
    public let titleIsPlaceholder: Bool
    public let subtitle: String
    public let tone: Tone
    /// 回应行 = 该联系人的头像;邀请行 = nil(界面画空心印占位)。
    public let avatar: AvatarSpec?
    /// 行尾文字按钮:回应「发回配对码」/ 邀请「再发一次」;没有可重发的文本时为 nil(不显示)。
    public let resendLabel: String?
    /// 只有我发出的邀请可删除;接受方要放弃就去删联系人。
    public let canDelete: Bool

    /// 读屏:「〈备注或“没有备注的配对码”〉,〈状态〉,〈时间〉」。
    public var accessibilityLabel: String { L10n.contactsPendingRowCd(title, subtitle: subtitle) }
}

/// 把 `pendingItems` 的推导结果变成可画的行。`relativeTime` 把创建时间格式化成「5 分钟前」一类(由调用方绑定「现在」)。
public func buildPendingRows(
    items: [PendingItem], contacts: [AppGroupContact], relativeTime: (Int64) -> String
) -> [PendingRow] {
    let byId = Dictionary(contacts.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
    return items.map { item in
        let isResponse = item.kind == .responseUnsent
        let contact = isResponse ? byId[item.id] : nil
        let note = item.note ?? ""
        let title = isResponse ? (contact?.displayName ?? "") : (note.isEmpty ? L10n.contactsInviteNoNote : note)
        let relative = relativeTime(item.createdAtMillis)
        let status: String
        switch item.kind {
        case .responseUnsent: status = L10n.contactsPendingResponseUnsent
        case .inviteAwaiting: status = L10n.contactsPendingAwaiting
        }
        let tone: PendingRow.Tone
        if item.isStale {
            tone = .tertiary
        } else if item.kind == .inviteAwaiting {
            tone = .secondary
        } else {
            tone = .warn
        }
        return PendingRow(
            id: item.id, kind: item.kind, isResponse: isResponse,
            title: title, titleIsPlaceholder: !isResponse && note.isEmpty,
            subtitle: item.isStale ? L10n.contactsPendingStale(relative) : L10n.contactsPendingSubtitle(status, time: relative),
            tone: tone,
            avatar: contact.map { contactAvatar(displayName: $0.displayName, fingerprintHex: $0.id) },
            resendLabel: item.canResend ? (isResponse ? L10n.contactsSendBack : L10n.contactsResend) : nil,
            canDelete: !isResponse)
    }
}

/// 联系人详情「再发一次我的配对码」的依据:该联系人的回应记录**已分享过**。没分享过的列在「配对中」,详情页不重复;
/// 收到对方第一条消息后记录被 `forgetPeer` 清掉,这一行随之消失(详情页订阅 `PairingResponseStore` 即可)。
public func resendableResponse(for contactId: String, in responses: [PairingResponseRecord]) -> PairingResponseRecord? {
    responses.first { $0.fingerprintHex == contactId && $0.lastSharedAtMillis != nil }
}

/// 「5 分钟前」一类的相对时间(`RelativeDateTimeFormatter`,跟随传入 locale,默认系统语言)。一分钟内说「刚刚」。
public func defaultPendingRelativeTime(createdAtMillis: Int64, nowMillis: Int64, locale: Locale = .current) -> String {
    if nowMillis - createdAtMillis < 60_000 { return L10n.contactsJustNow }
    let formatter = RelativeDateTimeFormatter()
    formatter.locale = locale
    formatter.unitsStyle = .full
    formatter.dateTimeStyle = .numeric
    return formatter.localizedString(
        for: Date(timeIntervalSince1970: Double(createdAtMillis) / 1000),
        relativeTo: Date(timeIntervalSince1970: Double(nowMillis) / 1000))
}

/// 联系人 tab 的列表数据:排序、「尚未发出回应」的联系人排除、「配对中」分区、角标、删除确认,都在这里算好。
/// 不订阅聊天消息存储(Global Constraints)。「可能已失效」依赖当前时间:进入联系人 tab / 回到前台时调 ``refresh()`` 重算一次即可。
/// 配对记录的写(标记已分享、删除邀请)一律走 `PairingCoordinator` 的入口(闸内串行),这里不直接动 store。
@MainActor
public final class ContactListViewModel: ObservableObject {
    @Published public private(set) var rows: [ContactRow] = []
    /// 有配对中条目:此时不显示「还没有联系人。」。
    @Published public private(set) var hasPending = false
    @Published public private(set) var showEmptyHint = false
    /// 「配对中」分区的行(已按表 K 排好序);空 = 整个分区不显示。
    @Published public private(set) var pendingRows: [PendingRow] = []
    /// 联系人 tab 图标上的角标 = 需要我动手的条目数(0 不显示)。
    @Published public private(set) var badgeCount = 0
    /// 待确认删除的邀请 id。确认弹窗的状态放这里,视图旋转 / 重建后还在。
    @Published public private(set) var deleteCandidate: String?
    /// 上一次操作(删除邀请)失败的提示;界面显示后置空。
    @Published public var actionError: String?

    private var bag = Set<AnyCancellable>()
    private let now: () -> Date
    private let locale: Locale
    private let makeCoordinator: @MainActor () -> PairingCoordinator
    private let relativeTime: (Int64, Int64) -> String
    private var coordinatorCache: PairingCoordinator?
    private var latestContacts: [AppGroupContact] = []
    private var latestInvites: [PendingPairingRecord] = []
    private var latestResponses: [PairingResponseRecord] = []

    public init(
        contactsStore: ContactsStore = .shared,
        inviteStore: PendingInviteStore = .shared,
        responseStore: PairingResponseStore = .shared,
        now: @escaping () -> Date = Date.init,
        locale: Locale = .current,
        coordinator: @escaping @MainActor () -> PairingCoordinator = { .makeDefault() },
        relativeTime: @escaping (_ createdAtMillis: Int64, _ nowMillis: Int64) -> String = { defaultPendingRelativeTime(createdAtMillis: $0, nowMillis: $1) }
    ) {
        self.now = now
        self.locale = locale
        self.makeCoordinator = coordinator
        self.relativeTime = relativeTime
        // 用 sink 带来的新值(@Published 先发值、后写属性),不回读 store 属性。
        Publishers.CombineLatest3(contactsStore.$contacts, inviteStore.$records, responseStore.$records)
            .sink { [weak self] contacts, invites, responses in
                guard let self else { return }
                self.latestContacts = contacts
                self.latestInvites = invites
                self.latestResponses = responses
                self.recompute()
            }
            .store(in: &bag)
    }

    /// 用当前时间重算一次(进入联系人 tab、回到前台时调):30 天的「可能已失效」与角标只随时间变化,没有数据事件可订阅。
    public func refresh() { recompute() }

    private func recompute() {
        let ids = Set(latestContacts.map(\.id))
        let nowMillis = Int64(now().timeIntervalSince1970 * 1000)
        let rows = contactRows(
            contacts: latestContacts, excluding: unsentResponseFingerprints(responses: latestResponses, contactIds: ids), locale: locale)
        let items = pendingItems(invites: latestInvites, responses: latestResponses, contactIds: ids, nowMillis: nowMillis)
        let pending = buildPendingRows(items: items, contacts: latestContacts, relativeTime: { [relativeTime] in relativeTime($0, nowMillis) })
        if self.rows != rows { self.rows = rows }
        if self.pendingRows != pending { self.pendingRows = pending }
        let badge = pendingBadgeCount(items)
        if self.badgeCount != badge { self.badgeCount = badge }
        let hasPending = !items.isEmpty
        if self.hasPending != hasPending { self.hasPending = hasPending }
        let hint = rows.isEmpty && items.isEmpty
        if self.showEmptyHint != hint { self.showEmptyHint = hint }
    }

    private var coordinator: PairingCoordinator {
        if let coordinatorCache { return coordinatorCache }
        let made = makeCoordinator()
        coordinatorCache = made
        return made
    }

    // MARK: 删除邀请(左滑「删除」→ 确认)

    /// 只有我发出的邀请可删;别的 id 忽略。
    public func requestDelete(_ id: String) {
        guard pendingRows.contains(where: { $0.id == id && $0.canDelete }) else { return }
        deleteCandidate = id
    }

    public func cancelDelete() { deleteCandidate = nil }

    /// 确认弹窗的「删除」。失败要让用户看得到(记录原样留着)。
    /// 界面把弹窗当时的 id 传进来:弹窗收起会把 `deleteCandidate` 先置空,而本方法在稍后的 `Task` 里才跑。
    public func confirmDelete(_ id: String? = nil) async {
        guard let id = id ?? deleteCandidate else { return }
        deleteCandidate = nil
        do {
            try await coordinator.deleteInvite(pairingId: id)
        } catch {
            NSLog("CCCHAT 删除邀请失败:\(error)")
            actionError = L10n.commonDeleteFailed
        }
    }

    // MARK: 行尾「再发一次 / 发回配对码」

    /// 要交给系统分享面板的**同一份**配对码:`render` 先把它画成卡片图(失败返回 nil → 退回文字版,按钮不会没反应),
    /// 有了可发的东西才标记已分享并返回。没有可发的内容(迁移来的邀请、记录已没)返回 nil。
    /// 标记失败不拦分享——最多是「还没发出去」的标签晚一点翻。
    public func itemsToShare(for row: PendingRow, render: (PairingShare) -> Any?) async -> [Any]? {
        guard row.resendLabel != nil else { return nil }
        let share: PairingShare
        if row.isResponse {
            guard let record = coordinator.pendingResponse(fingerprintHex: row.id) else { return nil }
            share = PairingShare(wire: record.responseWire, isResponse: true)
            let item = render(share) ?? share.text
            do { try await coordinator.markResponseShared(fingerprintHex: row.id) } catch { logMarkFailure(error) }
            return [item]
        }
        guard let record = coordinator.pendingInvite(id: row.id), !record.inviteWire.isEmpty else { return nil }
        share = PairingShare(wire: record.inviteWire, isResponse: false)
        let item = render(share) ?? share.text
        do { try await coordinator.markInviteShared(pairingId: row.id) } catch { logMarkFailure(error) }
        return [item]
    }

    private func logMarkFailure(_ error: Error) {
        NSLog("CCCHAT 标记已分享失败:\(error)")
    }
}
