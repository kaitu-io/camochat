import Foundation
import Chencang

/// 向导入口(三 tab 骨架 spec §3.5):发起、接受(可直接打开扫码)、恢复某份邀请、恢复某份回应、带着一份配对码进来。
/// 入口只决定「从哪一幕开始、读哪条记录」;接收幕不分角色,贴什么都按认出的种类走。
public enum WizardEntry: Hashable, Identifiable, Sendable {
    case initiator
    case redeemer
    case resumeInvite(pairingId: String)
    case resumeResponse(fingerprintHex: String)
    /// 从外面带进来的配对码(深链 / 扩展交接):进入即在「填对方的配对码」幕自动提交它——
    /// 对方的配对码 → 直接到「发配对码」(我的配对码);对方发回的配对码且本机有对应的 → 直接到核对。
    case incoming(wire: String)

    public var id: Self { self }
}

/// The current step within the three-act pairing wizard (出示/接收/对印). Which concrete stage maps to which
/// act depends on how the wizard was entered and what was recognised on 接收 (see
/// ``PairingWizardViewModel``); the stage shapes themselves are entry-agnostic. iOS port of Android's
/// `WizardStage` (`PairingWizardState.kt`); field names follow this codebase's own vocabulary (`peerId`,
/// not Android's `peerUsername`) — `peerId` IS the classical fingerprint (see
/// ``PairingCoordinator``'s contact persistence and how `ChatStore`/`SessionStore` key everything).
public enum WizardStage: Equatable, Sendable {
    /// Transient: awaiting a suspend call (e.g. minting the invite bundle).
    case working

    /// 出示幕: a wire to paste/QR/share. `isResponse` distinguishes an invite bundle from the response
    /// header — they render the same shape but different copy/QR content.
    case show(wire: String, isResponse: Bool)

    /// 接收幕: awaiting the peer's pasted/scanned wire. `notice` is non-nil after a
    /// ``PairingWizardViewModel/submitWire(_:)`` attempt that stayed here.
    case receive(notice: ReceiveNotice?)

    /// 对印幕: pairing succeeded cryptographically; awaiting the user's OOB
    /// emoji-fingerprint verification (一致/不一致).
    case confirm(emoji: [String], fingerprintHex: String, peerId: String, placeholderName: String)

    /// Failure after ``PairingWizardViewModel/rejectMismatch()`` or a failed invite mint; the user may
    /// ``PairingWizardViewModel/retry()`` from scratch.
    case failed(String)

    /// 首次配对前问「你的名字」(规则一):发起人出邀请前、或收到别人的邀请时,昵称为空且还没问过才出现。
    /// 回答(``PairingWizardViewModel/submitName(_:)`` / ``PairingWizardViewModel/skipName()``)后恢复被挂起的那一步。
    case askName

    /// 恢复的那份配对已不存在(被删 / 已完成)。终态:只能返回——重试只会回到同一页。
    case gone(String)
}

/// 接收幕上方的一行提示。`isHint` = 分类提示(贴错了东西,不是故障);否则是握手失败。
/// `contactId` 非空 = 「已配对过」,界面据此显示「查看联系人」。
public struct ReceiveNotice: Equatable, Sendable {
    public let text: String
    public let isHint: Bool
    public let contactId: String?
    /// 非空 = 互发邀请:界面据此给「删掉这条邀请」,删的就是这一份(向导手上的那份,或向导没拿邀请时
    /// 对方回执对上的那份)。
    public let deleteInviteId: String?

    public init(text: String, isHint: Bool, contactId: String? = nil, deleteInviteId: String? = nil) {
        self.text = text
        self.isHint = isHint
        self.contactId = contactId
        self.deleteInviteId = deleteInviteId
    }
}

/// Everything the wizard UI needs to render a frame: the stepper header (index + titles) and the stage.
public struct WizardUi: Equatable, Sendable {
    public let stepIndex: Int
    public let stepTitles: [String]
    public let stage: WizardStage
}

/// Three-act pairing wizard (出示/接收/对印) — the iOS twin of Android's `PairingWizardViewModel` (规 W):
///
///  - **initiator**: 出示(0, the invite) → 接收(1) → 对印(2).
///  - **redeemer**: 接收(0) → 出示(1, the response) → 对印(2).
///  - **resumeInvite**: an unshared invite with text resumes at 出示, anything else at 接收 (step 1).
///  - **resumeResponse**: 出示(the stored response) → 对印, the emoji coming from the saved contact.
///
/// 接收 does not care who is on the other end: every submission goes through
/// ``PairingCoordinator/handleIncoming(_:myDisplayName:)``, which recognises an invite (→ 出示 the response,
/// steps become 接收/出示/对印), a response (→ 对印, 出示/接收/对印) or something that is neither (→ stay,
/// with a hint). The coordinator persists the contact; this VM only mirrors it into the resident
/// ``ContactsStore`` and never overwrites one that already exists (so a re-pasted invite cannot downgrade
/// a verified contact).
///
/// Closing the wizard deletes nothing by itself, but the VM reports through `onInviteDiscardable` the
/// invite it holds that was never handed out (not shared, share sheet never opened, not copied / scanned);
/// the host discards that one on close via ``PairingCoordinator/discardUnsharedInvite(pairingId:)``, which
/// re-reads the record so a shared invite is never deleted. Every coordinator write goes through its
/// process-wide gate, so none of this VM's calls may be made from inside the gate.
@MainActor
public final class PairingWizardViewModel: ObservableObject {

    public let entry: WizardEntry
    private let coordinator: PairingCoordinator
    private let contactsStore: ContactsStore
    private let sessionStore: SessionStore
    /// 每次发邀请 / 回执时现读(App Group 里的「我的名字」),不在向导创建时定格。
    private let myDisplayName: () -> String
    /// 「你的名字」问过了没有(App Group)。
    private let namePromptDone: () -> Bool
    /// 记下名字幕的回答:非 nil = 存昵称,nil = 跳过;两者都标记问过。
    private let saveName: (String?) -> Void
    private let onInviteHeld: (String?) -> Void
    private let onInviteDiscardable: (String?) -> Void
    private let onWipeThread: (String) -> Void
    private let routeSessionMessage: @MainActor (String) async -> IntakeRoute
    private let kindOf: (String) -> PairingTransport.WireKind

    @Published public private(set) var ui: WizardUi

    /// One-shot navigation signal: non-nil once 对印 is confirmed and the wizard should hand off into that
    /// peer's thread. The SwiftUI side consumes this via `.onChange` and resets it to nil.
    @Published public var openThreadPeerId: String?

    /// 与 ``openThreadPeerId`` 同时设置:在接收幕贴进来的是一条加密消息、已解开落库时,要定位的那条消息 id。
    @Published public var openThreadHighlightId: String?

    /// The `pairingId` of the invite this wizard currently holds (mine, unanswered); nil once the current
    /// object is a response or the pairing is done.
    @Published public private(set) var inviteId: String?

    /// 除了正在出示的这份,还有几份「待完成邀请」(``awaitingInvites(_:nowMillis:)``):出示幕据此显示备注框。
    @Published public private(set) var otherAwaitingInvites = 0

    /// The held invite has stored text to show again (false for one migrated from the old single slot).
    @Published public private(set) var canResendInvite = false

    /// The held invite's local-only note (bound to the 出示幕's note field).
    @Published public var note = ""

    /// One-shot: the wizard should close (``deleteInvite(pairingId:)`` finished).
    @Published public var shouldClose = false

    /// Set when ``deleteInvite(pairingId:)`` / the 不一致 delete / the 核对 finish failed; the UI shows it and the
    /// wizard stays where it was.
    @Published public var actionError: String?

    /// 出示幕上的互发邀请提示(可忽略;界面给「删掉这条邀请」与「取消」,删的是向导持有的那份)。
    /// 接收幕上走 ``ReceiveNotice/deleteInviteId``。
    @Published public var mutualInviteNotice: String?

    private var pendingConfirm: WizardStage?
    private var sheetOpen = false
    private var leftWhileSharing = false
    private var shownResponseFp: String?
    private var inviteWire = ""
    private var acceptedNote = ""
    /// The invite id the host keeps for this wizard (so a rebuilt wizard resumes it instead of minting another).
    private var heldId: String?
    /// The invite to discard when the wizard closes: held here and never handed out. Cleared once it is
    /// shared / copied / scanned, its share sheet was opened, or it was deleted. Reported to the host.
    private var unsharedInviteId: String? {
        didSet { if unsharedInviteId != oldValue { onInviteDiscardable(unsharedInviteId) } }
    }
    /// 提交重入保护:上一次提交未返回前,再次提交直接忽略(扫码回调与粘贴可能同时到)。第一个挂起点之前置位。
    private var submitting = false
    private var started = false
    /// `.incoming` 带来的配对码只自动提交一次(失败幕「重试」回到接收幕,不再重贴同一份)。
    private var incomingConsumed = false

    public init(
        entry: WizardEntry,
        coordinator: PairingCoordinator,
        contactsStore: ContactsStore = .shared,
        sessionStore: SessionStore = .shared,
        myDisplayName: @escaping () -> String,
        namePromptDone: @escaping () -> Bool,
        saveName: @escaping (String?) -> Void,
        heldInviteId: String? = nil,
        onInviteHeld: @escaping (String?) -> Void = { _ in },
        onInviteDiscardable: @escaping (String?) -> Void = { _ in },
        onWipeThread: @escaping (String) -> Void = { _ in },
        routeSessionMessage: @escaping @MainActor (String) async -> IntakeRoute = { _ in .failed(.cannotOpen) },
        kindOf: @escaping (String) -> PairingTransport.WireKind = PairingTransport.classify
    ) {
        self.entry = entry
        self.coordinator = coordinator
        self.contactsStore = contactsStore
        self.sessionStore = sessionStore
        self.myDisplayName = myDisplayName
        self.namePromptDone = namePromptDone
        self.saveName = saveName
        self.heldId = heldInviteId
        self.onInviteHeld = onInviteHeld
        self.onInviteDiscardable = onInviteDiscardable
        self.onWipeThread = onWipeThread
        self.routeSessionMessage = routeSessionMessage
        self.kindOf = kindOf
        self.ui = WizardUi(stepIndex: 0, stepTitles: Self.initialTitles(for: entry), stage: .working)
    }

    /// Convenience over the production `PairingCoordinator.makeDefault()`.
    public convenience init(
        entry: WizardEntry,
        myDisplayName: @escaping () -> String,
        namePromptDone: @escaping () -> Bool,
        saveName: @escaping (String?) -> Void,
        heldInviteId: String? = nil,
        onInviteHeld: @escaping (String?) -> Void = { _ in },
        onWipeThread: @escaping (String) -> Void = { _ in }
    ) {
        self.init(
            entry: entry, coordinator: .makeDefault(), myDisplayName: myDisplayName,
            namePromptDone: namePromptDone, saveName: saveName,
            heldInviteId: heldInviteId, onInviteHeld: onInviteHeld, onWipeThread: onWipeThread)
    }

    /// Enter the wizard. Idempotent: the scan cover dismissing re-fires the host's `.task`, and a repeat
    /// must not wipe what was already submitted. A failed start / mismatch restarts via ``retry()``.
    public func start() async {
        guard !started else { return }
        started = true
        await begin()
    }

    private func begin() async {
        pendingConfirm = nil
        shownResponseFp = nil
        inviteWire = ""
        inviteId = nil
        canResendInvite = false
        note = ""
        acceptedNote = ""
        switch entry {
        case .initiator:
            setStage(.working, stepIndex: 0, titles: Self.showFirst)
            // 被系统重建的发起向导:宿主还留着上一次持有的邀请 id,就回到那一份,不再出新的(它可能已写了备注,
            // 不满足「空邀请复用」)。记录已被删 / 已完成则按发起入口出新的。
            if let held = heldId, let record = coordinator.pendingInvite(id: held) {
                resume(record)
                return
            }
            if needsName() {
                askName(held: nil)
                return
            }
            await mintInvite()
        case .redeemer:
            setStage(.receive(notice: nil), stepIndex: 0, titles: Self.receiveFirst)
        case let .incoming(wire):
            setStage(.receive(notice: nil), stepIndex: 0, titles: Self.receiveFirst)
            if !incomingConsumed {
                incomingConsumed = true
                await submitWire(wire)
            }
        case let .resumeInvite(pairingId):
            guard let record = coordinator.pendingInvite(id: pairingId) else {
                setStage(.gone(L10n.pairingErrorGone), stepIndex: 0, titles: Self.showFirst)
                return
            }
            resume(record)
        case let .resumeResponse(fingerprintHex):
            contactsStore.reload()
            guard let response = coordinator.pendingResponse(fingerprintHex: fingerprintHex),
                  let contact = contactsStore.contacts.first(where: { $0.id == fingerprintHex }) else {
                setStage(.gone(L10n.pairingErrorGone), stepIndex: 1, titles: Self.receiveFirst)
                return
            }
            shownResponseFp = fingerprintHex
            pendingConfirm = confirmStage(emoji: contact.emoji ?? [], fingerprintHex: fingerprintHex, displayName: contact.displayName)
            setStage(.show(wire: response.responseWire, isResponse: true), stepIndex: 1, titles: Self.receiveFirst)
        }
    }

    private func mintInvite() async {
        do {
            let record = try await coordinator.startInvite(myDisplayName: myDisplayName())
            hold(record)
            setStage(.show(wire: record.inviteWire, isResponse: false), stepIndex: 0, titles: Self.showFirst)
        } catch {
            setStage(.failed(L10n.commonActionFailed), stepIndex: 0, titles: Self.showFirst)
        }
    }

    // MARK: - 问名字

    /// 昵称为空、且名字幕还没回答过。
    private func needsName() -> Bool { !namePromptDone() && myDisplayName().isEmpty }

    /// 名字幕挂起的那一步:`wire` = 等着处理的别人的邀请;nil = 等着出我自己的邀请。`stage`/`stepIndex`/`titles`
    /// 是被打断的那一幕,回答后回到那里(拒绝提示照常落在原幕上)。不持久化:进程重建后向导重来,会再问。
    private struct HeldForName {
        let wire: String?
        let stage: WizardStage
        let stepIndex: Int
        let titles: [String]
    }
    private var heldForName: HeldForName?

    private func askName(held wire: String?) {
        heldForName = HeldForName(wire: wire, stage: ui.stage, stepIndex: ui.stepIndex, titles: ui.stepTitles)
        setStage(.askName, stepIndex: ui.stepIndex, titles: ui.stepTitles)
    }

    /// 名字幕「继续」:规范化后为空 = 跳过。
    public func submitName(_ text: String) async {
        let name = normalizeMyName(clampMyNameInput(text)).flatMap { $0.isEmpty ? nil : $0 }
        await finishAskName(name)
    }

    /// 名字幕「跳过」:只记问过了。
    public func skipName() async { await finishAskName(nil) }

    private func finishAskName(_ name: String?) async {
        guard ui.stage == .askName, let held = heldForName else { return }
        heldForName = nil
        saveName(name)
        guard let wire = held.wire else {
            setStage(.working, stepIndex: held.stepIndex, titles: held.titles)
            await mintInvite()
            return
        }
        setStage(held.stage, stepIndex: held.stepIndex, titles: held.titles)
        submitting = true
        defer { submitting = false }
        await submitPairing(wire)
    }

    /// 恢复规则:未分享且有暗号文本 → 出示幕;否则接收幕(步骤条停在第 2 步)。
    private func resume(_ record: PendingPairingRecord) {
        hold(record)
        if record.lastSharedAtMillis == nil && !record.inviteWire.isEmpty {
            setStage(.show(wire: record.inviteWire, isResponse: false), stepIndex: 0, titles: Self.showFirst)
        } else {
            setStage(.receive(notice: nil), stepIndex: 1, titles: Self.showFirst)
        }
    }

    private func hold(_ record: PendingPairingRecord) {
        inviteWire = record.inviteWire
        inviteId = record.pairingId
        canResendInvite = !record.inviteWire.isEmpty
        note = record.note ?? ""
        acceptedNote = note
        heldId = record.pairingId
        onInviteHeld(record.pairingId)
        unsharedInviteId = record.lastSharedAtMillis == nil && record.sheetPresentedAtMillis == nil ? record.pairingId : nil
    }

    /// 接收幕、或出示幕(我的邀请——对方可能先发了他的)提交。Classified first: an encrypted message is
    /// handed to `routeSessionMessage` (decrypt + open its thread, or a hint saying why not); everything
    /// else funnels through ``PairingCoordinator/handleIncoming(_:myDisplayName:)``. A blank paste is the
    /// "not a pairing code" hint without touching the coordinator; a handshake failure is marked as a
    /// failure; a second call before the first returns is ignored. On 接收 a refusal is a notice; on 出示
    /// it is a one-shot ``actionError`` and the stage stays as it is.
    public func submitWire(_ wireText: String) async {
        guard accepts(ui.stage), !submitting else { return }
        let kind = IntakeClassifier.classify(wireText, kindOf: kindOf)
        if kind == .empty {
            reject(ReceiveNotice(text: L10n.pairingErrorNotPairing, isHint: true))
            return
        }
        submitting = true
        defer { submitting = false }
        if case .sessionMessage = kind {
            switch await routeSessionMessage(wireText) {
            case let .openThread(peerId, messageId):
                openThreadHighlightId = messageId
                openThreadPeerId = peerId
            case let .failed(failure):
                reject(ReceiveNotice(text: failure.message, isHint: true))
            case .pairing:
                // 分类器已认定是会话消息,分流不会再给配对码;万一给了也只提示,不在这里握手。
                reject(ReceiveNotice(text: L10n.pairingErrorNotPairing, isHint: true))
            }
            return
        }
        // 只有收到「邀请」才可能问名字;回执不问。问的时候这条码原样挂着,回答后恰好处理一次。
        if case .pairingInvite = kind, needsName() {
            askName(held: wireText)
            return
        }
        await submitPairing(wireText)
    }

    /// 接收幕,或出示幕上我自己的邀请(不是发回去的回执)。
    private func accepts(_ stage: WizardStage) -> Bool {
        switch stage {
        case .receive: return true
        case let .show(_, isResponse): return !isResponse
        default: return false
        }
    }

    /// 一条认出的配对码 / 回执:协调器接受、完成或拒绝。
    private func submitPairing(_ wireText: String) async {
        do {
            switch try await coordinator.handleIncoming(wireText, myDisplayName: myDisplayName()) {
            case let .accepted(out):
                // 他们的邀请顶掉了出示幕:我手里那份从没交出去的邀请与关闭向导同规则,当场丢弃。
                await discardHeldUnsharedInvite()
                inviteId = nil
                canResendInvite = false
                // 在发起入口里贴了别人的邀请走到接受:原邀请留在「配对中」,但向导不再持有它——
                // 向导被重建应回到这份回应,不是旧邀请。
                if heldId != nil {
                    heldId = nil
                    onInviteHeld(nil)
                }
                shownResponseFp = out.contact.fingerprintHex
                await mirrorIntoContactsStore(out.contact)
                pendingConfirm = confirmStage(emoji: out.emoji, contact: out.contact)
                setStage(.show(wire: out.headerWire, isResponse: true), stepIndex: 1, titles: Self.receiveFirst)
            case let .completed(out):
                await discardHeldUnsharedInvite()
                inviteId = nil
                canResendInvite = false
                // 完成的是哪一份邀请不重要;宿主留着的那份若已不在,就放掉。
                if let held = heldId, coordinator.pendingInvite(id: held) == nil {
                    heldId = nil
                    onInviteHeld(nil)
                }
                await mirrorIntoContactsStore(out.contact)
                setStage(confirmStage(emoji: out.emoji, contact: out.contact), stepIndex: 2, titles: Self.showFirst)
            case let .rejected(rejection):
                reject(rejectionNotice(rejection))
            }
        } catch {
            reject(ReceiveNotice(text: L10n.pairingErrorSubmit, isHint: false))
        }
    }

    /// 「已是联系人」的细分 = 互发邀请(对方就是我接受过其邀请的人,联系人带邀请摘要),给「删掉这条邀请」
    /// (只删邀请,不动联系人 / 会话):向导手握自己的邀请时删这份;没握(从粘贴条进来)时删对方回执对上的
    /// 那份(协调器报的 `matchedPairingId`)。其余保持原提示。
    private func rejectionNotice(_ rejection: IncomingRejection) -> ReceiveNotice {
        if case let .alreadyPaired(fp, matched) = rejection {
            if let held = inviteId {
                contactsStore.reload()
                if contactsStore.contacts.first(where: { $0.id == fp })?.acceptedInviteDigest != nil {
                    return ReceiveNotice(text: L10n.pairingMutualInvite, isHint: true, deleteInviteId: held)
                }
            } else if let matched {
                return ReceiveNotice(text: L10n.pairingMutualInvite, isHint: true, deleteInviteId: matched)
            }
        }
        return ReceiveNotice(text: rejection.message, isHint: true, contactId: rejection.contactId)
    }

    /// 没收下的东西:接收幕上是一行提示;出示幕上是一次性提示(出示幕保持原样)。
    private func reject(_ notice: ReceiveNotice) {
        if case .receive = ui.stage {
            stay(with: notice)
        } else if notice.deleteInviteId != nil {
            mutualInviteNotice = notice.text
        } else {
            actionError = notice.text
        }
    }

    /// 与关闭向导同规则:持有的邀请从没分享、分享面板也没弹出过,就丢掉。协调器在闸门内重读记录,
    /// 期间被标过已分享 / 已弹面板的不会被删。失败只记日志。
    private func discardHeldUnsharedInvite() async {
        guard let id = unsharedInviteId else { return }
        unsharedInviteId = nil
        do {
            try await coordinator.discardUnsharedInvite(pairingId: id)
        } catch {
            NSLog("CCCHAT 丢弃未分享邀请失败:\(error)")
        }
    }

    private func stay(with notice: ReceiveNotice) {
        setStage(.receive(notice: notice), stepIndex: ui.stepIndex, titles: ui.stepTitles)
    }

    /// 出示幕上要复制的整段文本(首行说明 + 带配对码的链接);不在出示幕时为 nil。卡片图的二维码编码同一个链接。
    public var shareText: String? {
        guard case let .show(wire, isResponse) = ui.stage else { return nil }
        return PairingShareText.compose(wire: wire, site: ConfigRepository.shared.current().shareSite, isResponse: isResponse)
    }

    /// 接收幕且向导持有我发出、对方还没发回的配对码:界面据此显示「等对方把配对码发回来」。
    public var isAwaitingPeerCode: Bool {
        guard case .receive = ui.stage else { return false }
        return inviteId != nil
    }

    /// 出示幕的东西交到对方手里了(分享面板报告完成、点了复制、分享面板开过后 App 回到前台、或点了下一步按钮):
    /// 先把正在出示的配对码(我的 / 发回的)标为已分享,再前进——
    ///  - 我的配对码 → 接收幕(第 2 步);
    ///  - 发回的配对码(回执)且 `faceToFace` → 核对(当面扫码 / 手动「已发出 · 下一步」);
    ///  - 发回的配对码且不是当面(远程发回)→ 不进核对,发「打开会话」信号一次:联系人此时已落库,
    ///    核对延后到对话页横幅,**不会**把联系人标成已核对。
    /// 回执幕的 `pendingConfirm` 先于标记检查、交出信号后清空,所以同一幕的第二次触发什么都不做。
    /// 标记失败最多让列表上留一条过时的「还没发」,只记日志。不在出示幕时什么都不做(面板可能先报 false 再报 true)。
    public func handOffDone(faceToFace: Bool) async {
        guard case let .show(_, isResponse) = ui.stage else { return }
        if isResponse, pendingConfirm == nil { return }
        if !isResponse { unsharedInviteId = nil }
        do {
            if isResponse {
                if let fp = shownResponseFp { try await coordinator.markResponseShared(fingerprintHex: fp) }
            } else if let id = inviteId {
                try await coordinator.markInviteShared(pairingId: id)
            }
        } catch {
            NSLog("CCCHAT 标记已分享失败:\(error)")
        }
        // 标记期间挂起过:只在仍停在同一出示幕时前进。
        guard case .show(_, isResponse) = ui.stage else { return }
        if !isResponse {
            setStage(.receive(notice: nil), stepIndex: 1, titles: Self.showFirst)
        } else {
            guard let confirm = pendingConfirm else { return }
            if faceToFace {
                setStage(confirm, stepIndex: 2, titles: ui.stepTitles)
            } else if case let .confirm(_, _, peerId, _) = confirm {
                pendingConfirm = nil
                openThreadPeerId = peerId
            }
        }
    }

    /// 出示幕的分享面板弹出来了:从此关闭向导也不丢弃这份配对码——有的面板不回报完成,用户却可能已经发出去,
    /// 丢了对方发回的配对码就对不上。记录上也记一笔(``PairingCoordinator/markInvitePresented(pairingId:)``),
    /// 之后重开或重建的向导既不丢弃也不复用它。这里不标已分享,它照旧不进「配对中」,直到面板回报完成(或复制 / 对方已扫码)。
    public func shareSheetPresented() async {
        sheetOpen = true
        guard case let .show(_, isResponse) = ui.stage, !isResponse else { return }
        unsharedInviteId = nil
        guard let id = inviteId else { return }
        do {
            try await coordinator.markInvitePresented(pairingId: id)
        } catch {
            NSLog("CCCHAT 标记已弹分享面板失败:\(error)")
        }
    }

    /// 分享面板收起(取消或完成):不再等回前台。
    public func shareSheetClosed() { sheetOpen = false; leftWhileSharing = false }

    /// 场景切换(视图转发 scenePhase)。分享面板开着时 App 进了后台(去别的 App 发、或锁屏 / 回桌面 / 切 App,
    /// 按设计也算发出,镜像 Android 对「不回报完成」的 OEM 兜底;回执仍可从联系人页重发),
    /// 回到前台就按远程交接处理一次。
    public func sceneChanged(background: Bool, active: Bool) async {
        if background, sheetOpen { leftWhileSharing = true }
        if active, leftWhileSharing {
            leftWhileSharing = false
            sheetOpen = false
            await handOffDone(faceToFace: false)
        }
    }

    /// 接收幕「再发一次配对码」: back to 出示 for the same invite.
    public func backToShow() {
        guard case .receive = ui.stage, inviteId != nil, canResendInvite else { return }
        setStage(.show(wire: inviteWire, isResponse: false), stepIndex: 0, titles: Self.showFirst)
    }

    /// 备注输入: clamped to the 24-byte limit by grapheme while typing, then persisted. Input the
    /// coordinator would refuse (control characters) is ignored — nothing is stored and the field goes back
    /// to the last accepted text.
    public func setNote(_ text: String) async {
        guard let id = inviteId else { return }
        let clamped = clampMyNameInput(text)
        guard normalizeMyName(clamped) != nil else {
            if note != acceptedNote { note = acceptedNote }
            return
        }
        if note != clamped { note = clamped }
        guard clamped != acceptedNote else { return }
        acceptedNote = clamped
        do {
            try await coordinator.updateNote(pairingId: id, note: clamped)
        } catch {
            NSLog("CCCHAT 保存邀请备注失败:\(error)")
        }
    }

    /// 标题栏「删除」(向导持有的那份)或互发邀请的「删掉这条邀请」(持有的那份,或回执对上的那份):
    /// 删掉 `pairingId` 这份邀请并关闭向导。删除失败时记录与向导都留着,``actionError`` 告诉用户。
    public func deleteInvite(pairingId id: String) async {
        do {
            try await coordinator.deleteInvite(pairingId: id)
            if unsharedInviteId == id { unsharedInviteId = nil }
            if inviteId == id {
                inviteId = nil
                canResendInvite = false
            }
            if heldId == id {
                heldId = nil
                onInviteHeld(nil)
            }
            shouldClose = true
        } catch {
            NSLog("CCCHAT 删除邀请失败:\(error)")
            actionError = L10n.commonDeleteFailed
        }
    }

    /// 「一致」: rename (when a name was typed), mark the contact OOB-verified, hand off to its thread.
    public func confirmMatch(name: String) async {
        await finishVerify(name: name, verified: true)
    }

    /// 「以后再核对」: rename (when a name was typed), leave it unverified, hand off to its thread.
    public func verifyLater(name: String) async {
        await finishVerify(name: name, verified: false)
    }

    /// 起名框裁空白后非空才改名(空 = 保留默认名)。写失败时提示并留在核对幕,可再点。
    private func finishVerify(name: String, verified: Bool) async {
        guard case let .confirm(_, fingerprintHex, peerId, _) = ui.stage else { return }
        do {
            let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
            if !trimmed.isEmpty { try await contactsStore.rename(contactId: fingerprintHex, to: trimmed) }
            if verified { try await contactsStore.markVerified(contactId: fingerprintHex) }
        } catch {
            NSLog("CCCHAT 核对收尾写联系人失败:\(error)")
            actionError = L10n.commonActionFailed
            return
        }
        openThreadPeerId = peerId
    }

    /// 「不一致」(post-confirmation-dialog): a possible MITM — scrub every trace of this pairing (contact,
    /// DR session, chat thread, and the response record kept for resending) and drop into a terminal
    /// failure the user must `retry()` from scratch.
    ///
    /// The Keychain `session_secret_*` key the safety emoji was once believed to derive from was never
    /// written by any production path (M4 bugfix round 1): the real emoji is `AppGroupContact.emoji`,
    /// removed with the contact record. `sessionStore.remove(for:)` scrubs the one secret this path needs
    /// to — the DR session. The coordinator's `forgetPeer` goes last (it is gated, async): without it the
    /// response record would outlive the contact it belonged to.
    public func rejectMismatch() async {
        guard case let .confirm(_, fingerprintHex, peerId, _) = ui.stage else { return }
        // The contact goes first and must really go: if the delete fails nothing else is touched (no
        // half-wipe where the session is gone but the contact survives), the user is told, and the
        // button can be tapped again.
        do {
            try await contactsStore.remove(contactId: fingerprintHex)
        } catch {
            NSLog("CCCHAT 对印拒绝时删除联系人失败:\(error)")
            actionError = L10n.commonDeleteFailed
            return
        }
        await sessionStore.remove(for: peerId)
        onWipeThread(peerId)
        do {
            try await coordinator.forgetPeer(fingerprintHex: fingerprintHex)
        } catch {
            NSLog("CCCHAT 对印拒绝后清回应记录失败:\(error)")
        }
        setStage(.failed(L10n.pairingMismatchDeleted), stepIndex: ui.stepIndex, titles: ui.stepTitles)
    }

    /// From a 接收 notice: clear it and stay on the same act. From a ``WizardStage/failed(_:)``: restart the
    /// whole wizard from its entry. ``WizardStage/gone(_:)`` is terminal — a retry could only land on the
    /// same page — so nothing happens.
    public func retry() async {
        switch ui.stage {
        case .receive(let notice) where notice != nil:
            setStage(.receive(notice: nil), stepIndex: ui.stepIndex, titles: ui.stepTitles)
        case .failed:
            await begin()
        default:
            break
        }
    }

    /// Bridges a freshly-paired contact into `ContactsStore`'s resident cache. The coordinator's own
    /// `PairedContactPersisting` (production: `AppGroupContactPersister`) already wrote it into the App
    /// Group mirror behind that cache's back, so re-read the mirror first. A contact that already exists is
    /// left exactly as it is — its verified flag in particular: pasting an invite again returns the stored
    /// response without a new handshake, and must not turn a verified contact back into an unverified one.
    private func mirrorIntoContactsStore(_ contact: PairedContact) async {
        contactsStore.reload()
        guard !contactsStore.contacts.contains(where: { $0.id == contact.fingerprintHex }) else { return }
        try? await contactsStore.add(
            AppGroupContact(
                id: contact.fingerprintHex,
                displayName: contact.displayName,
                isVerified: false,
                deviceId: contact.deviceId,
                emoji: contact.emoji,
                acceptedInviteDigest: contact.acceptedInviteDigest,
                pairedAt: contact.pairedAt
            )
        )
    }

    private func confirmStage(emoji: [String], contact: PairedContact) -> WizardStage {
        confirmStage(emoji: emoji, fingerprintHex: contact.fingerprintHex, displayName: contact.displayName)
    }

    private func confirmStage(emoji: [String], fingerprintHex: String, displayName: String) -> WizardStage {
        .confirm(emoji: emoji, fingerprintHex: fingerprintHex, peerId: fingerprintHex, placeholderName: displayName)
    }

    private func setStage(_ stage: WizardStage, stepIndex: Int, titles: [String]) {
        ui = WizardUi(stepIndex: stepIndex, stepTitles: titles, stage: stage)
        refreshOtherAwaitingInvites()
    }

    private func refreshOtherAwaitingInvites() {
        let nowMillis = Int64(Date().timeIntervalSince1970 * 1000)
        let count = awaitingInvites(coordinator.pendingInviteRecords, nowMillis: nowMillis)
            .filter { $0.pairingId != inviteId }.count
        if otherAwaitingInvites != count { otherAwaitingInvites = count }
    }

    /// 发起:发配对码 / 填对方的配对码 / 核对。
    private static var showFirst: [String] { [L10n.pairingStepSend, L10n.pairingStepEnter, L10n.pairingStepVerify] }
    /// 接受:填对方的配对码 / 发配对码 / 核对。
    private static var receiveFirst: [String] { [L10n.pairingStepEnter, L10n.pairingStepSend, L10n.pairingStepVerify] }

    private static func initialTitles(for entry: WizardEntry) -> [String] {
        switch entry {
        case .redeemer, .resumeResponse, .incoming: return receiveFirst
        case .initiator, .resumeInvite: return showFirst
        }
    }
}
