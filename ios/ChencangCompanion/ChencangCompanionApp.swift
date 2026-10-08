import SwiftUI
import ChencangShared

@main
struct ChencangCompanionApp: App {
    @StateObject private var identityStore: IdentityStore
    /// 密信 App 根状态:会话存储/发送编排/收件箱/导航栈。单份实例经 environment
    /// 分发给会话列表(Task 6)、线程(Task 7)、设置(Task 9)。也持有 onboarding
    /// 闸门(`showOnboarding`),init 需要读 `identityStore` 判老/新用户,所以两个
    /// `@StateObject` 改走显式 init 按序构造(不能互相引用彼此的默认值表达式)。
    @StateObject private var model: MixinAppModel
    /// 只接后台上传会话的系统回调(见 `AppDelegate`)。
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @Environment(\.scenePhase) private var scenePhase
    /// 开屏动画只在冷启动（进程内界面第一次出现）播一次；从后台切回来不播。装好后第一次是完整版，之后是短版。
    @State private var splashVariant: SplashVariant? = SplashGate.variant()
    /// 由链接唤起时直接淡出。
    @State private var splashSkip = false

    init() {
        let identityStore = IdentityStore()
        _identityStore = StateObject(wrappedValue: identityStore)
        // 立即建 model(不走 StateObject 的惰性初始化):系统为交付后台上传结果在后台拉起 App 时
        // 可能根本不渲染界面,后台上传会话和上传引擎必须在任何委托回调之前就位(先分享、后上传 spec §1.1)。
        let model = MixinAppModel(identityStore: identityStore)
        _model = StateObject(wrappedValue: model)
        AppDelegate.uploader = model.uploader
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .overlay(alignment: .bottom) { PasteBarToast(model: model) }
                .overlay {
                    if let variant = splashVariant {
                        SplashOverlay(variant: variant, skipRequested: splashSkip) { splashVariant = nil }
                    }
                }
                .environmentObject(identityStore)
                .environmentObject(model)
                // ChatStore 单独再注入一份:它自己是 ObservableObject(@Published
                // threads),会话列表/线程屏靠这份直接订阅才能在消息落库后重渲染
                // ——只订阅外层 MixinAppModel 收不到嵌套 store 的 @Published 变化。
                .environmentObject(model.chatStore)
                .environmentObject(model.mediaActivity)
                .environmentObject(model.voicePlayer)
                .task {
                    try? await identityStore.loadOrCreate()
                    LegacyResidueCleaner.runOnce()
                    #if DEBUG
                    UATThreadStress.runIfRequested(model: model)   // 仅 XCUITest 压力用例带环境变量时生效
                    #endif
                }
                .onOpenURL { url in
                    splashSkip = true
                    // camo://thread?peer=<id>&highlight=<messageId> — the
                    // Action Extension (Task 11) already appended the decrypted
                    // wire to AppGroupInbox and bounces us here to land on the
                    // sender's thread. Drain the inbox into ChatStore *before*
                    // routing so the highlighted message is already in the
                    // store when the thread appears. Checked first so it can
                    // never collide with the pairing branch below.
                    if let (peer, highlight) = ThreadOpenURL.parse(url: url) {
                        Task {
                            await model.handleBecameActive()
                            model.openThreadFromOutside(peer, highlight: highlight)
                        }
                        return
                    }
                    // 扩展把用户送回来的 App 内链接(取交接槽 / 添加联系人 / 回首页)。
                    if let link = IntakeOpenURL.parse(url: url) {
                        switch link {
                        case .pendingIntake:
                            if let wire = PendingIntakeStore().take() {
                                model.presentWizard(.incoming(wire: wire))
                            }
                        case .addContact:
                            model.presentWizard(.initiator)
                        case .home:
                            break
                        }
                        return
                    }
                    // 通用链接:卡片二维码里的 https://<站点>/p/#<配对码>,系统相机或浏览器直接唤起 App。
                    // 引导没走完时由 onboardingGate 暂存,走完再弹。
                    if let wire = PairingLink.wires(in: url.absoluteString).last {
                        model.presentWizard(.incoming(wire: wire))
                        return
                    }
                    // 配对链接的形状但码解不出(片段缺失 / 被截断 / 损坏):和粘贴同样的「不完整」提示,不悄悄忽略。
                    if PairingLink.isPairingLinkShape(url) {
                        model.rejectPairingLink()
                        return
                    }
                    // 链接里带着完整配对码(对方的或对方发回的):直达向导并自动提交。
                    if let wire = PairingURLParser.pairingWire(url: url) {
                        model.presentWizard(.incoming(wire: wire))
                        return
                    }
                    // 老的短码配对链接:打开「我收到了配对码」,由用户粘贴配对码。
                    if PairingURLParser.parse(url: url) != nil {
                        model.presentWizard(.redeemer)
                        return
                    }
                }
        }
        // Foregrounding: the Action Extension (Task 11) may have advanced the
        // DR ratchet on disk while we were backgrounded — invalidate the
        // in-memory session cache before any decrypt attempt, then drain
        // whatever it appended to the inbox into ChatStore. Scene-level (not
        // a view modifier) per Apple's documented scenePhase pattern, so it
        // fires once per app-wide transition regardless of which screen is
        // on screen.
        .onChange(of: scenePhase) { newPhase in
            if newPhase == .active {
                Task { await model.handleBecameActive() }
            }
            // 开屏没播完就切到后台：回来时不再接着播。
            if newPhase == .background { splashVariant = nil }
        }
    }
}

private struct RootView: View {
    @EnvironmentObject var identityStore: IdentityStore
    @EnvironmentObject var model: MixinAppModel

    var body: some View {
        // `model.showOnboarding` (not `identityStore.identity == nil`) is the
        // gate: identity already exists partway through onboarding (幕 1 建号
        // 成功那一刻),so gating on identity directly skips 幕 2. See
        // `MixinAppModel.showOnboarding` doc comment.
        Group {
            if model.showOnboarding {
                OnboardingView(viewModel: OnboardingViewModel(
                    identityStore: identityStore,
                    namePromptDone: { MyProfileKeys.isNamePromptDone(defaults: UserDefaults(suiteName: SharedAppGroupDefaults.suiteName)) },
                    saveName: { MyProfileKeys.recordNamePromptAnswer($0, defaults: UserDefaults(suiteName: SharedAppGroupDefaults.suiteName)) }))
            } else {
                // 单条 NavigationStack + tab 根(spec §4.1):`model.path` 的语义不变,tab 栏随整屏 push 一起滑走。
                NavigationStack(path: $model.path) {
                    MainTabView()
                        .navigationDestination(for: AppRoute.self) { route in
                            switch route {
                            case let .thread(peerId): ConversationThreadView(peerId: peerId)
                            case let .contact(id): ContactView(contactId: id)
                            }
                        }
                }
            }
        }
        // 根上唯一的向导 sheet:会话「＋」、联系人「添加联系人」、空状态按钮、配对链接、交接槽都经 `model.presentWizard` 设 `model.wizard`。
        // `.environmentObject` 在 `.sheet` 之外的修饰符不会传进被弹出的内容(SwiftUI 环境作用域),
        // 向导读 `@EnvironmentObject MixinAppModel`,所以这里显式注入。
        .sheet(item: $model.wizard) { entry in
            // `.id(entry)`:向导已开着时又来一个入口(比如用户配对到一半、点开了别人发来的链接),
            // 换入口 = 换一个全新的向导(新 VM),不会让旧向导的状态吞掉新入口;旧向导里没交出去的邀请由 `model.wizard` 的 didSet 丢弃。
            PairingWizardView(entry: entry)
                .id(entry)
                .environmentObject(model)
        }
        // 来件失败的根级提示:说清原因;还没加过发件人时给「添加联系人」。`.openApp`(去陈仓里看看)
        // 在 App 内没有意义,不出按钮。
        .alert(
            model.intakeFailure?.message ?? "",
            isPresented: Binding(
                get: { model.intakeFailure != nil },
                set: { if !$0 { model.intakeFailure = nil } }
            ),
            presenting: model.intakeFailure
        ) { failure in
            if failure.nextStep == .addContact {
                Button(L10n.pairingAddContact) {
                    model.intakeFailure = nil
                    // 等提示框收起动画过去再弹向导(同一时刻叠两个模态,SwiftUI 会丢掉后一个)。
                    DispatchQueue.main.asyncAfter(deadline: .now() + Moyu.Motion.standard / 1000) {
                        model.presentWizard(.initiator)
                    }
                }
            }
            Button(L10n.commonOk, role: .cancel) { model.intakeFailure = nil }
        }
    }
}
