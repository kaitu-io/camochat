import Foundation

/// 会话线程屏(Task 7)的 view model:草稿/封缄/封缄卡状态机/分享面板队列,镜像 Android
/// `ConversationViewModel`。`seal()` 是 fire-and-forget(内部起 `Task`),与
/// Android `viewModelScope.launch` 同款——UI 靠 `@Published` 订阅结果,不等调用返回。
///
/// 状态只说用户做过的事(App 不知道对方收没收到):分享面板报告**完成**才标已分享,点了「复制」标已复制
/// (已分享不降级);取消面板不标,卡片留着显示「还没发 · 点这里再发」。文字与媒体同一套规则。
/// 点「加密」后按记住的交出偏好走:偏好不是「复制」就直接弹分享面板(加密即分享)。
@MainActor
public final class ThreadViewModel: ObservableObject {
    /// 输入区上方的封缄卡:文字与媒体共用。卡片是「最近一条待交出的消息」,新封缄的会顶掉旧的
    /// (旧的仍在列表里,媒体可长按「复制密文」)。
    public struct SealCard: Equatable {
        public enum Content: Equatable {
            case text(charCount: Int)
            case media(kind: MessageKind, count: Int)
        }

        /// ready = 刚封缄还没动;sharing = 分享面板已弹出/在排队;copied = 刚点了复制(停留一拍再收);
        /// notSent = 面板被取消,可再次分享。
        public enum Phase: Equatable {
            case ready, sharing, copied, notSent
        }

        /// 文字摘要最多显示的字符数(卡片本身还有 lineLimit(1) 兜底)。
        public static let summaryLimit = 24

        public let messageId: String
        /// 消息所在会话;转发时是目标会话,不一定是当前线程。
        public let peerId: String
        /// 交出去的文本(分享与复制同一份):文字 = 固定首行 + wire,媒体 = R1 两行。
        public let shareText: String
        public let content: Content

        /// 卡片主行:文字 = 明文首行(截断),媒体 = 类型与数量(「3 张图片」)。只在本机卡片上显示,
        /// 不进分享文本、日志或提示。
        public let summary: String
        public internal(set) var phase: Phase

        /// 卡片副行:取消后「还没发 · 点这里再发」,否则写清发给谁、去哪里粘贴。
        public func caption(recipientName: String) -> String {
            phase == .notSent ? L10n.statusNotSentTapAgain : L10n.cardSendTo(recipientName)
        }

        /// 卡片视图该画的状态。
        public var viewState: SealCardState {
            switch phase {
            case .ready, .sharing: return .sealed
            case .copied: return .copied
            case .notSent: return .notSent
            }
        }

        /// 首个非空行、去首尾空白,超过 `summaryLimit` 截断加「…」。
        public static func textSummary(_ text: String) -> String {
            let line = text.components(separatedBy: .newlines)
                .map { $0.trimmingCharacters(in: .whitespaces) }
                .first { !$0.isEmpty } ?? ""
            guard line.count > summaryLimit else { return line }
            return String(line.prefix(summaryLimit)) + "…"
        }
    }

    /// 一次分享面板请求。`id` 区分每一次弹出(同一条消息取消后再分享是新的一次)。
    public struct ShareRequest: Identifiable, Equatable {
        public let id = UUID()
        public let messageId: String
        public let peerId: String
        public let text: String
    }

    /// 复制动作交给视图的数据:视图写剪贴板、停留一拍后按这里的 id 收尾。
    public struct CopyRequest: Equatable {
        public let messageId: String
        public let peerId: String
        public let text: String
    }

    @Published public var draft: String = ""
    @Published public private(set) var card: SealCard?
    @Published public var sendError: String?
    /// 当前展示的分享面板;`.sheet(item:)` 绑定它,系统收起面板时会把它置 nil。
    @Published public var presentedShare: ShareRequest?
    /// 分享面板报告完成、且本线程自己发出的消息**这次才**变成已分享时 +1,视图据此弹一次「已分享」轻提示。
    /// 与 Android 同规则:转发到别的会话完成不提示,已分享过的不再提示。复制永远不碰它。
    @Published public private(set) var sharedNotice: Int = 0
    /// 每次复制(卡片复制收尾 / 气泡长按复制 / 复制媒体密文,含收到的消息)都 +1,视图弹「已复制」。
    @Published public private(set) var copiedNotice: Int = 0

    /// 封缄卡主 action 偏好(spec §5.2)的存储键;视图用同一个键的 `@AppStorage` 读。
    public static let sealActionKey = "cc.sealAction.v1"

    public let peerId: String
    private let service: ChatService
    private let preferences: UserDefaults
    private let kindOf: (String) -> PairingTransport.WireKind

    private var shareQueue: [ShareRequest] = []
    /// 正在展示的那次请求——`presentedShare` 在 onDismiss 之前就被 SwiftUI 清掉了,这里留一份。
    private var presenting: ShareRequest?
    /// 别的模态(选图/相机/看图/播放/转发选人/提示框)正在台上:此时不能再请求分享面板——SwiftUI
    /// 不会叠第二个同级模态,绑定会一直非 nil、onDismiss 永远不来,队列就卡死了。由视图维护。
    private var presentationBlocked = false
    /// 最近报告过「完成」的请求(iOS 取消后再选别的 App 会先 false 再 true,只标一次;收起后晚到的
    /// true 也要认)。只留最近几次,够覆盖「晚到」窗口,不随会话时长无限增长。
    private var completedRequests: [UUID] = []
    private static let completedRequestsCap = 8
    var completedRequestCountForTesting: Int { completedRequests.count }

    public init(
        peerId: String,
        service: ChatService,
        preferences: UserDefaults = .standard,
        kindOf: @escaping (String) -> PairingTransport.WireKind = PairingTransport.classify
    ) {
        self.peerId = peerId
        self.service = service
        self.preferences = preferences
        self.kindOf = kindOf
        self.dismissedBanners = Set(preferences.stringArray(forKey: Self.bannerDismissedKey) ?? [])
    }

    /// 上次用户选的交出方式;没选过算「分享」。
    private var preferredSealAction: SealCardAction {
        preferences.string(forKey: Self.sealActionKey).flatMap(SealCardAction.init(rawValue:)) ?? .share
    }

    /// 记住用户最近选的交出方式(卡片按钮与气泡长按同一套,对齐 Android `prefs.setSealAction`)。
    private func rememberSealAction(_ action: SealCardAction) {
        preferences.set(action.rawValue, forKey: Self.sealActionKey)
    }

    // MARK: - 封缄

    /// 加密当前草稿:加密落库(明文 + wire,wire 供卡片被顶掉/取消后从气泡长按再分享)+ 产出加密卡。
    /// 偏好不是「复制」→ 立即请求分享面板(卡片 `.sharing`);是「复制」→ 卡片 `.ready`,等用户点。
    /// 自动弹面板不改偏好(偏好只记用户亲手点的)。
    /// 空草稿(含纯空白)no-op。失败(常见于会话丢失 `SessionStoreError.noSession`)走 `sendError`,
    /// 草稿保留、不落库。
    public func seal() {
        let text = draft
        guard !text.trimmingCharacters(in: .whitespaces).isEmpty else { return }
        Task {
            do {
                let sealed = try await service.sendTextSealed(to: peerId, text: text)
                draft = ""
                let shareText = MediaShareText.forText(sealed.wire, site: ConfigRepository.shared.current().shareSite)
                card = SealCard(messageId: sealed.message.id, peerId: peerId, shareText: shareText,
                                content: .text(charCount: text.count),
                                summary: SealCard.textSummary(text), phase: .ready)
                if preferredSealAction != .copy {
                    request(messageId: sealed.message.id, peerId: peerId, text: shareText)
                }
            } catch {
                sendError = L10n.mediaFailureSessionLost
            }
        }
    }

    /// 媒体封缄成功(上传完成 / 重试成功 / 转发成功):自动弹分享面板(spec §5.3)。
    /// 只有消息属于**当前线程**才出封缄卡;转发到别的会话只弹面板(完成时标目标会话那条),
    /// 绝不顶掉本线程的卡片——那张卡可能是还没交出去的文字,顶掉会让人把 B 的密文贴进 A 的聊天。
    public func mediaSealed(messageId: String, peerId target: String, shareText: String) {
        if target == peerId, let message = service.message(id: messageId, peerId: target), message.kind.isMedia {
            let count = message.media?.count ?? 1
            card = SealCard(messageId: messageId, peerId: target, shareText: shareText,
                            content: .media(kind: message.kind, count: count),
                            summary: message.kind.displayLabel(count: count), phase: .sharing)
        }
        request(messageId: messageId, peerId: target, text: shareText)
    }

    /// 点媒体气泡的红 ! / 「对方还看不到 · 点击重新上传」。
    /// - 已分享过的消息:`MediaSender.retry` 只把它重新交给上传引擎;这里**不**出封缄卡、不弹分享面板、
    ///   不改分享文本(对方手里那份密文永远有效,spec §1.3)。
    /// - 还没分享过(加密阶段失败):重新封缄,成功后与首次发送一样出卡 + 弹面板。
    public func retryMedia(messageId: String, sender: MediaSender) async {
        let wasShared = sender.isShared(messageId)
        let outcome = await sender.retry(messageId: messageId, peerId: peerId)
        guard !wasShared, case let .sealed(id, text) = outcome else { return }
        mediaSealed(messageId: id, peerId: peerId, shareText: text)
    }

    /// 点气泡下的状态行(`MessageStatusLine.isStatusTappable`):在输入区上方重新出这条的加密卡(`.ready`,
    /// 不自动弹面板),用户再选分享或复制。不满足条件的消息不做任何事。
    public func reopenCard(for message: ChatMessage) {
        guard MessageStatusLine.isStatusTappable(message) else { return }
        if message.kind == .text {
            guard let text = Self.handOffText(message) else { return }
            card = SealCard(messageId: message.id, peerId: message.peerId, shareText: text,
                            content: .text(charCount: message.body.count),
                            summary: SealCard.textSummary(message.body), phase: .ready)
        } else {
            let count = message.media?.count ?? 1
            card = SealCard(messageId: message.id, peerId: message.peerId, shareText: message.body,
                            content: .media(kind: message.kind, count: count),
                            summary: message.kind.displayLabel(count: count), phase: .ready)
        }
    }

    // MARK: - 草稿里贴进来的来件

    /// 用户把一整条加密消息或配对码贴进了输入框:整段草稿能认成会话消息或配对码 → 清空草稿、返回原文,
    /// 交给来件入口处理;普通文字(含不完整的 🔒 内容)返回 nil,草稿不动。只看草稿,不读剪贴板。
    public func takeIntakeFromDraft() -> String? {
        let raw = draft
        switch IntakeClassifier.classify(raw, kindOf: kindOf) {
        case .sessionMessage, .pairingInvite, .pairingResponse:
            draft = ""
            return raw
        case .empty, .notOurs, .incomplete, .linkOnly:
            return nil
        }
    }

    // MARK: - 分享面板

    /// 卡片「分享」:只弹面板,不标记。同一条已在展示/排队就不重复入队。
    public func shareCard() {
        guard let current = card else { return }
        rememberSealAction(.share)
        request(messageId: current.messageId, peerId: current.peerId, text: current.shareText)
    }

    /// 自己发出的文字气泡长按「分享」:与卡片同一条队列、同一套完成才标的规则。
    /// 没存 wire 的旧消息(本功能之前发出的)返回 false,视图不给这个菜单。
    @discardableResult
    public func shareMessage(_ message: ChatMessage) -> Bool {
        guard let text = Self.handOffText(message) else { return false }
        rememberSealAction(.share)
        request(messageId: message.id, peerId: message.peerId, text: text)
        return true
    }

    /// 自己发出的文字气泡长按「复制加密消息」:返回要写进剪贴板的两行文本,并立即标已复制(已分享的保持已分享)。
    public func copyMessage(_ message: ChatMessage) -> String? {
        guard let text = Self.handOffText(message) else { return nil }
        rememberSealAction(.copy)
        markCopied(messageId: message.id, peerId: message.peerId)
        return text
    }

    /// 媒体气泡长按「复制加密消息」:返回 R1 两行(消息正文)。自己发出的记偏好、立即标已复制;
    /// 收到的只提示「已复制」。正文为空(还没封缄完)返回 nil。
    public func copyMediaWire(_ message: ChatMessage) -> String? {
        guard !message.body.isEmpty else { return nil }
        if message.direction == .outgoing { rememberSealAction(.copy) }
        markCopied(messageId: message.id, peerId: message.peerId)
        return message.body
    }

    /// 可从气泡再交出的文字:自己发出、文字、存了 wire。
    public static func handOffText(_ message: ChatMessage) -> String? {
        guard message.direction == .outgoing, message.kind == .text, let wire = message.wire, !wire.isEmpty
        else { return nil }
        return MediaShareText.forText(wire, site: ConfigRepository.shared.current().shareSite)
    }

    /// 面板 `completionWithItemsHandler` 的每次回报。任一 `true` 即标已分享,且只标一次;
    /// 收起后才晚到的 `true` 也照标。`false` 本身不做事——是否「未发出」等面板收起时再定。
    public func shareReported(_ request: ShareRequest, completed: Bool) {
        guard completed, !completedRequests.contains(request.id) else { return }
        completedRequests.append(request.id)
        if completedRequests.count > Self.completedRequestsCap {
            completedRequests.removeFirst(completedRequests.count - Self.completedRequestsCap)
        }
        handOff(messageId: request.messageId, peerId: request.peerId, byCopy: false)
    }

    /// 面板收起(`.sheet` 的 onDismiss):没报过完成 → 卡片转「还没发 · 点这里再发」;再弹队列里的下一个。
    public func shareSheetDismissed() {
        if let done = presenting {
            presenting = nil
            if !completedRequests.contains(done.id), var current = card,
               current.messageId == done.messageId, current.phase == .sharing {
                current.phase = .notSent
                card = current
            }
        }
        presentNext()
    }

    /// 视图报告「有别的模态在台上」。解除时(别的模态**已经收起**之后才调)补弹排队中的分享面板。
    public func setPresentationBlocked(_ blocked: Bool) {
        presentationBlocked = blocked
        if !blocked { presentNext() }
    }

    private func request(messageId: String, peerId target: String, text: String) {
        if var current = card, current.messageId == messageId {
            current.phase = .sharing
            card = current
        }
        let pending = (presenting.map { [$0] } ?? []) + shareQueue
        guard !pending.contains(where: { $0.messageId == messageId }) else { return }
        shareQueue.append(ShareRequest(messageId: messageId, peerId: target, text: text))
        presentNext()
    }

    private func presentNext() {
        guard presenting == nil, !presentationBlocked, !shareQueue.isEmpty else { return }
        let next = shareQueue.removeFirst()
        presenting = next
        presentedShare = next
    }

    /// 已交出/已放弃的消息不该再弹面板:把它还在排队(未展示)的请求撤掉。
    private func dropQueued(messageId: String) {
        shareQueue.removeAll { $0.messageId == messageId }
    }

    // MARK: - 复制 / 标记

    /// 卡片「复制」:卡片进「已复制」态,撤掉这条还在排队的分享,返回要写进剪贴板的文本和收尾用的 id。
    /// 视图停留一拍后调 `markCopied(messageId:peerId:)`——id 必须此刻捕获,
    /// 窗口期内用户可能已经封缄了下一条。
    public func copyCard() -> CopyRequest? {
        guard var current = card else { return nil }
        rememberSealAction(.copy)
        current.phase = .copied
        card = current
        dropQueued(messageId: current.messageId)
        return CopyRequest(messageId: current.messageId, peerId: current.peerId, text: current.shareText)
    }

    /// 复制交出(卡片「复制」停留一拍后 / 长按复制):按 id 标已复制(已分享的不降级),每次都提示「已复制」。
    public func markCopied(messageId: String, peerId target: String? = nil) {
        handOff(messageId: messageId, peerId: target ?? peerId, byCopy: true)
    }

    /// 按 id 落库总是对的;当前卡片正是这一条时顺带收卡;这条还在排队的分享撤掉。
    /// 「已分享」提示只给分享面板完成、且本线程里这次才变成已分享的消息;转发到别的会话、重复分享已分享的
    /// 不提示——与 Android `watchHandOffs` 只盯本线程状态变化一致。
    private func handOff(messageId: String, peerId target: String, byCopy: Bool) {
        let result = byCopy
            ? service.markCopied(messageId: messageId, peerId: target)
            : service.markShared(messageId: messageId, peerId: target)
        dropQueued(messageId: messageId)
        if card?.messageId == messageId {
            card = nil
        }
        if byCopy {
            copiedNotice += 1
        } else if result == .marked && target == peerId {
            sharedNotice += 1
        }
    }

    /// 收起卡片(不标记);这条还在排队的分享一并撤掉。
    public func dismissSealed() {
        if let id = card?.messageId { dropQueued(messageId: id) }
        card = nil
    }

    /// 长按删除了一条消息(UAT B1):封缄卡正是它就收起、它排队的面板撤掉(同 `dismissSealed`)——
    /// 不然卡上「复制」「分享」还能把已删消息的分享文本交出去。与 Android `delete` 同口径。
    public func messageDeleted(_ messageId: String) {
        dropQueued(messageId: messageId)
        if card?.messageId == messageId { card = nil }
    }

    public func consumeSendError() { sendError = nil }

    // MARK: - 顶部状态横幅(同 Android `ThreadBanners.pick`)

    /// 「已关闭」记录的存储键:字符串数组,元素 = 联系人 id + "|" + 横幅类型。
    public static let bannerDismissedKey = "cc.threadBannerDismissed.v1"

    /// 已关闭的横幅(`@Published`:关闭后视图立即重算)。
    @Published public private(set) var dismissedBanners: Set<String> = []

    /// 现在该显示哪条横幅(至多一条)。历史没读完时一律 nil,免得空列表闪出「还没发 / 还没收到」。
    public func banner(contact: AppGroupContact?, messages: [ChatMessage], historyLoaded: Bool) -> ThreadBanner? {
        guard historyLoaded else { return nil }
        return ThreadBanners.pick(
            contact: contact,
            hasIncoming: messages.contains { $0.direction == .incoming },
            hasOutgoing: messages.contains { $0.direction == .outgoing },
            dismissed: dismissedBanners)
    }

    /// `contact.id` 与 `banner(contact:…)` 里 pick 读的是同一个 id 来源。
    public func dismissBanner(_ banner: ThreadBanner, contact: AppGroupContact) {
        dismissedBanners.insert(ThreadBanners.dismissKey(contactId: contact.id, banner: banner))
        preferences.set(dismissedBanners.sorted(), forKey: Self.bannerDismissedKey)
    }
}
