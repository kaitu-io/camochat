import SwiftUI
import ChencangShared

/// 密信 App 的根 view model:持有共享的 ChatStore/ChatService/收件箱与导航栈。
/// RootView 用一份 `@StateObject` 注入 environment,子屏幕(会话列表/线程/设置)
/// 都通过 `@EnvironmentObject` 取用同一份状态。
@MainActor
final class MixinAppModel: ObservableObject {
    let chatStore: ChatStore
    let chatService: ChatService
    let inbox: AppGroupInbox
    let mediaFiles: MediaFiles
    let threadCleaner: ThreadCleaner
    let mediaActivity: MediaActivity
    /// 粘贴条的可见状态(会话 / 联系人 tab 与对话页共用一份)。
    let pasteBar = PasteBarModel()
    /// 粘贴条的轻提示(不是陈仓的内容 / 读不到);主 tab 与对话页各自用自己的轻提示样式显示。
    @Published var pasteBarNotice: PasteBarNotice?
    let mediaSender: MediaSender
    let uploader: BackgroundUploader
    let mediaDownloader: MediaDownloader
    let voicePlayer: VoicePlayer
    /// 三个 tab 的列表数据(会话 / 联系人):在视图模型里随源数据算好,视图只读,不在 body 里排序重建。
    let conversationList: ConversationListViewModel
    let contactList: ContactListViewModel
    /// 单条导航栈(spec §4.1)。tab 是栈的根,不在栈里。
    @Published var path: [AppRoute] = []
    @Published var selectedTab: AppTab = .chats
    /// 配对向导入口;根上唯一一个 `.sheet(item:)` 据此弹出。向导收起(或换入口)时,上一次持有的邀请 id 一并放掉;
    /// 它报上来的「从没交出去」的邀请(没分享、没打开过分享面板、没复制 / 扫码)交给协调器丢弃——协调器重读记录,
    /// 已分享的绝不删。
    @Published var wizard: WizardEntry? {
        didSet {
            guard wizard != oldValue else { return }
            wizardHeldInviteId = nil
            if let id = wizardDiscardableInviteId {
                wizardDiscardableInviteId = nil
                Task { [pairing] in
                    do {
                        try await pairing.discardUnsharedInvite(pairingId: id)
                    } catch {
                        NSLog("CCCHAT 丢弃没发出去的邀请失败:\(error)")
                    }
                }
            }
        }
    }
    /// 向导当前持有、还从没交出去的邀请 id(不发布);向导关闭时丢弃它。
    var wizardDiscardableInviteId: String?
    /// 发起向导当前持有的邀请 id(不发布):向导视图 / VM 被系统重建时据此回到原来那一份,不再出新邀请攒出孤儿(spec §3.5.4)。
    var wizardHeldInviteId: String?
    /// 生产的配对编排:向导、联系人 tab、联系人详情、收件落库都用这一个(串行闸本就进程内共享,这里只是不重复构造)。
    let pairing: PairingCoordinator
    /// 主 App 内的来件单一入口(粘贴解密 / 向导接收幕贴进来的加密消息 / 扩展交接)。
    let intakeRouter: IntakeRouter
    @Published var highlightMessageId: String?
    /// 来件处理失败(草稿里贴进来的加密消息解不开等):RootView 据此弹一次根级提示。
    @Published var intakeFailure: IntakeFailure?

    /// Onboarding 闸门(2026-08 controller 裁决):RootView 曾经拿
    /// `identityStore.identity == nil` 直接判定,但 identity 在幕 1(品牌幕)
    /// 建号成功那一刻就已非 nil——早于用户看到幕 2(机制幕)、早于「我明白了」
    /// 被点——导致幕 2 在真机上几乎不可能渲染出来(同一次不挂起的异步续体里,
    /// identity 和 act 的 @Published 变更被 SwiftUI 合并进同一次渲染,RootView
    /// 直接跳过 OnboardingView)。改为这份独立状态:「有没有身份」≠「onboarding
    /// 有没有走完」。init 时用 `IdentityStore.hasPersistedIdentity()`(同步、不
    /// 触发加载)甄别老用户 vs 新用户;新用户建号后仍留在 onboarding 里,直到
    /// 幕 2「我明白了」显式翻它。
    @Published var showOnboarding: Bool
    /// 引导期间到达的向导请求(配对链接、扩展交来的配对码)先存在这里,引导走完再弹。
    private var onboardingGate = OnboardingGate()
    private var protectedDataObserver: NSObjectProtocol?

    init(identityStore: IdentityStore) {
        self.showOnboarding = !identityStore.hasPersistedIdentity()
        let dir = ChatStore.appGroupDirectory()
            ?? FileManager.default.temporaryDirectory.appendingPathComponent("cc-chat-fallback")
        // 容器拿不到只会发生在异常环境(无 App Group 授权的构建);fallback 保证不崩,留痕走日志。
        if ChatStore.appGroupDirectory() == nil { NSLog("CCCHAT App Group 容器不可用,已回退临时目录") }
        let store = (try? ChatStore(directory: dir)) ?? (try! ChatStore(directory: FileManager.default.temporaryDirectory.appendingPathComponent("cc-chat-\(UUID().uuidString)")))
        self.chatStore = store
        let pairing = PairingCoordinator.makeDefault()
        self.pairing = pairing
        self.conversationList = ConversationListViewModel(chatStore: store)
        self.contactList = ContactListViewModel(coordinator: { pairing })
        let files = MediaFiles.production()
        self.mediaFiles = files
        let activity = MediaActivity()
        let transport = MediaTransport(relays: .production())  // 构造不联网;只有发送/下载媒体时才发请求
        let mediaCrypto = CoreMediaCrypto()
        self.mediaActivity = activity
        // 封缄完成即交给后台上传引擎(先分享、后上传 spec §1.1)。闭包弱引用引擎,避免 sender ↔ 引擎循环引用。
        weak var weakUploader: BackgroundUploader?
        let sender = MediaSender(store: store, crypto: SessionThreadCrypto(), mediaCrypto: mediaCrypto,
                                 transport: transport, files: files, activity: activity,
                                 uploadScheduler: { id in weakUploader?.enqueue(messageId: id) })
        self.mediaSender = sender
        let backgroundSession = BackgroundUploadSession.shared
        let uploader = BackgroundUploader(
            session: backgroundSession, sender: sender, store: store, files: files, activity: activity,
            isForeground: { UIApplication.shared.applicationState != .background },
            backgroundTask: MixinAppModel.runInBackgroundTask
        )
        weakUploader = uploader
        self.uploader = uploader
        backgroundSession.attach(.init(
            onComplete: { [weak uploader] identifier, description, status, error in
                uploader?.handleCompletion(taskDescription: description, httpStatus: status, error: error,
                                           taskIdentifier: identifier)
            },
            onProgress: { [weak uploader] description, fraction in
                uploader?.handleProgress(taskDescription: description, fraction: fraction)
            },
            onFinishEvents: { [weak uploader] in uploader?.handleEventsFinished() }
        ))
        self.threadCleaner = ThreadCleaner(chatStore: store, mediaFiles: files,
                                           cancelUploads: { [weak uploader] ids in await uploader?.cancel(messageIds: ids) })
        self.mediaDownloader = MediaDownloader(store: store, mediaCrypto: mediaCrypto, transport: transport,
                                               files: files, activity: activity)
        self.voicePlayer = VoicePlayer()
        // 上次进程在传输途中被杀:没分享的发送项复位成可重试、接收项复位成可再下(R12);
        // 已分享的 uploading 留给上传引擎对账(系统后台会话可能还在传)。
        try? store.resetInterruptedTransfers()
        Task { await uploader.reconcile() }
        self.inbox = AppGroupInbox()
        self.chatService = ChatService(
            store: store,
            crypto: SessionThreadCrypto(),
            contactIds: { ContactsStore.shared.contacts.map(\.id) },
            // 收到对方一条能解开的消息 = 对方已完成配对:清掉为他留着的可重发回应暗号(联系人上的邀请摘要不动)。
            // 走编排层的 `forgetPeer`(闸内串行、没有记录时不写盘),不直接动 store 绕过闸门。
            onIncomingStored: { peerId in
                Task { @MainActor in
                    do { try await pairing.forgetPeer(fingerprintHex: peerId) } catch {
                        NSLog("CCCHAT 收到消息后清回应记录失败:\(error)")
                    }
                }
            }
        )
        self.intakeRouter = IntakeRouter(
            chatService: chatService,
            contactIds: { ContactsStore.shared.contacts.map(\.id) },
            hasAwaitingInvites: { [pairing] in
                !awaitingInvites(pairing.pendingInviteRecords, nowMillis: Int64(Date().timeIntervalSince1970 * 1000)).isEmpty
            }
        )
        // 首次解锁前被系统在后台拉起时,会话文件读不了(ChatStore 标记未加载、拒绝写入);
        // 数据保护一解除就读回来,再对账。
        protectedDataObserver = NotificationCenter.default.addObserver(
            forName: UIApplication.protectedDataDidBecomeAvailableNotification, object: nil, queue: .main
        ) { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self, self.reloadUnreadableThreads() else { return }
                Task { await self.uploader.reconcile() }
            }
        }
    }

    /// 弹「添加联系人」向导的唯一入口(菜单、空状态、来件、链接、交接槽都走这里):
    /// 引导中先暂存,引导结束(`finishOnboarding`)再弹;否则立即弹。
    func presentWizard(_ entry: WizardEntry) {
        if let entry = onboardingGate.offer(entry, onboarding: showOnboarding) {
            wizard = entry
        }
    }

    /// 唤醒进来的配对链接解不出码:收起已开着的向导(根上的提示框叠不到 sheet 上),等收起动画过去再弹「不完整」提示。
    /// 引导期间不打断(此时没有可提示的上下文,用户走完引导自会再点链接)。
    func rejectPairingLink() {
        guard !showOnboarding else { return }
        wizard = nil
        DispatchQueue.main.asyncAfter(deadline: .now() + Moyu.Motion.standard / 1000) { [weak self] in
            self?.intakeFailure = .incomplete
        }
    }

    /// 引导最后一步点完:收起引导;引导期间有暂存的向导请求就接着弹。
    func finishOnboarding() {
        showOnboarding = false
        if let entry = onboardingGate.release() {
            wizard = entry
        }
    }

    /// 把 `ShellNav` 算出的导航快照落到界面状态。
    func apply(_ state: ShellState) {
        selectedTab = state.tab
        path = state.path
    }

    /// 深链 / Action Extension 跳回(spec §4.2、Review Focus 4):先收起向导 sheet,再设高亮,最后落在发件人线程。
    /// 调用方须已在同一个 `Task` 里先 `await handleBecameActive()`(落库先于导航)。
    func openThreadFromOutside(_ peerId: String, highlight: String?) {
        wizard = nil
        highlightMessageId = highlight
        apply(ShellNav.openThread(peerId))
    }

    /// 主 App 内的来件(会话页草稿里贴进来的加密消息 / 配对码):会话消息解开 → 打开那条对话并定位;
    /// 配对码 → 交给「添加联系人」向导;失败 → 根级提示。落库异常 `IntakeRouter` 已归成 `.saveFailed`。
    func handleIntake(_ raw: String) async {
        switch await intakeRouter.route(raw) {
        case let .openThread(peerId, messageId):
            openThreadFromOutside(peerId, highlight: messageId)
        case let .pairing(wire):
            presentWizard(.incoming(wire: wire))
        case let .failed(failure):
            intakeFailure = failure
        }
    }

    /// 粘贴条的「粘贴」(系统 `PasteButton` 回调):先登记已消费,再分流;不是陈仓的内容 / 读不到只给轻提示。
    func handlePasteBar(_ text: String?) async {
        pasteBar.consume()
        guard let text, !text.isEmpty else {
            pasteBarNotice = PasteBarNotice(text: L10n.pasteBarReadFailed)
            return
        }
        switch IntakeClassifier.classify(text) {
        case .notOurs:
            pasteBarNotice = PasteBarNotice(text: L10n.pasteBarNotOurs)
            return
        case .linkOnly:
            // 只复制到了首行链接,🔒 那段没带上:提示重新复制整条。
            pasteBarNotice = PasteBarNotice(text: L10n.pasteBarLinkOnly)
            return
        default:
            break
        }
        await handleIntake(text)
    }

    /// 回前台:先失效会话缓存(扩展可能推进了棘轮),再逐条落库收件箱。
    /// 不整批 `drain()`——那样一上来就清空存储,落库失败或中途被杀就丢消息;
    /// 改成读快照、逐条 ingest、只删成功的 id,失败的原样留给下次回前台重试
    /// (`ChatService.ingest` 按 id 幂等,重放不会产生重复消息)。
    /// 最后取扩展交接槽里的配对码(有则弹向导)。
    func handleBecameActive() async {
        // 配置单刷新(Task 10):不阻塞回前台的其余步骤。
        Task.detached { await ConfigRepository.shared.refresh() }
        // 两个配对记录库只在写时重读磁盘;回前台也重读,让界面看到别处(或首次解锁后才读得到)的记录。
        PendingInviteStore.shared.reload()
        PairingResponseStore.shared.reload()
        reloadUnreadableThreads()
        // 自愈(spec §1.2):已分享但没传完的消息重新交给上传引擎;403 在后台留下的也在这里接上。
        Task { await uploader.reconcile() }
        await SessionStore.shared.invalidateCache()
        let entries = inbox.readAll()
        if !entries.isEmpty {
            var handledIds: Set<String> = []
            for entry in entries {
                do {
                    _ = try chatService.ingest(entry)
                    handledIds.insert(entry.id)
                } catch {
                    NSLog("CCCHAT 收件箱条目落库失败,留待下次回前台重试:\(entry.id)")
                }
            }
            try? inbox.remove(ids: handledIds)
        }
        // 扩展交来的配对码:扩展没能把 App 拉起、用户自己打开 App 时在这里接上。
        if let wire = PendingIntakeStore().take() {
            presentWizard(.incoming(wire: wire))
        }
    }

    /// 把先前因数据保护读不了的会话读回来,并只对这些会话做冷启动复位。读回了任何会话返回 true。
    @discardableResult
    private func reloadUnreadableThreads() -> Bool {
        guard !chatStore.isFullyLoaded, let loaded = try? chatStore.reloadUnreadable(), !loaded.isEmpty else {
            return false
        }
        try? chatStore.resetInterruptedTransfers(peers: loaded)
        return true
    }

    /// presign 这一步包在 `beginBackgroundTask` 里:用户封缄完立刻切去微信,这一步也能做完、把任务交给系统。
    private static func runInBackgroundTask(_ work: @escaping @MainActor () async -> Void) async {
        final class Token { var id: UIBackgroundTaskIdentifier = .invalid }
        let token = Token()
        let end = {
            guard token.id != .invalid else { return }
            UIApplication.shared.endBackgroundTask(token.id)
            token.id = .invalid
        }
        token.id = UIApplication.shared.beginBackgroundTask(withName: "cc.media.presign") { end() }
        await work()
        end()
    }
}
