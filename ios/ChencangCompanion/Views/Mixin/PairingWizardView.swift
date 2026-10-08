import SwiftUI
import ChencangShared

/// 「添加联系人」向导(发配对码 / 填对方的配对码 / 核对)。
/// `entry` 决定入口:发起(`.initiator`,「＋」菜单 / 空态 / 联系人 tab)先出示我的配对码;
/// 接收(`.redeemer`,老短码配对链接的入口)先填对方的配对码;带码进来(`.incoming`)进门即提交那份配对码。
/// 恢复入口(`.resumeInvite` / `.resumeResponse`,从联系人 tab 的「配对中」进来)落在对应的一幕。关闭向导时只丢弃
/// 从没交出去的那份我的配对码(没分享、没打开过分享面板、没复制 / 扫码),由 `MixinAppModel.wizard` 收尾。
/// 幕体结构与 Android `PairingWizardScreen` 对齐。
///
/// `onWipeThread` / `routeSessionMessage` need `MixinAppModel`,而
/// environment 值要到 body 求值才可用,拿不到就没法在 `init` 里现场建
/// `@StateObject`——照抄 `ConversationThreadView`/`ThreadContent` 的两层结构:
/// 外层这个 `PairingWizardView` 只读 environment、在 body 里现建 VM,再把它交给
/// 内层 `PairingWizardContent` 持有为 `@StateObject`(SwiftUI 只在首次求值时采用
/// 这份初始值,后续 body 重算不会重建/丢状态)。
struct PairingWizardView: View {
    let entry: WizardEntry
    @EnvironmentObject private var model: MixinAppModel

    var body: some View {
        PairingWizardContent(
            vm: PairingWizardViewModel(
                entry: entry,
                coordinator: model.pairing,
                myDisplayName: { MyProfileKeys.storedName(defaults: UserDefaults(suiteName: SharedAppGroupDefaults.suiteName)) },
                namePromptDone: { MyProfileKeys.isNamePromptDone(defaults: UserDefaults(suiteName: SharedAppGroupDefaults.suiteName)) },
                saveName: { MyProfileKeys.recordNamePromptAnswer($0, defaults: UserDefaults(suiteName: SharedAppGroupDefaults.suiteName)) },
                heldInviteId: model.wizardHeldInviteId,
                onInviteHeld: { model.wizardHeldInviteId = $0 },
                onInviteDiscardable: { model.wizardDiscardableInviteId = $0 },
                onWipeThread: { peerId in
                    Task { try? await model.threadCleaner.clear(peerId: peerId) }
                },
                routeSessionMessage: { await model.intakeRouter.route($0) }
            )
        )
    }
}

private struct PairingWizardContent: View {
    @StateObject private var vm: PairingWizardViewModel
    @EnvironmentObject private var model: MixinAppModel
    @Environment(\.dismiss) private var dismiss
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    @State private var showScanner = false
    @State private var confirmDelete = false

    init(vm: PairingWizardViewModel) {
        _vm = StateObject(wrappedValue: vm)
    }

    /// 标题栏「删除」只在出示 / 接收两幕出现(核对、失败、恢复不到时没有意义)。
    private var deletable: Bool {
        switch vm.ui.stage {
        case .show, .receive: return true
        default: return false
        }
    }

    private var stageAnimation: Animation {
        reduceMotion ? .easeOut(duration: 0.09) : .easeOut(duration: Moyu.Motion.standard / 1000)
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                WizardStepper(titles: vm.ui.stepTitles, currentIndex: vm.ui.stepIndex)
                    .padding(.horizontal, Moyu.Space.xl)
                    .padding(.top, Moyu.Space.l)

                Group {
                    switch vm.ui.stage {
                    case .working:
                        WorkingAct()
                    case .askName:
                        NamePromptView(
                            onContinue: { name in Task { await vm.submitName(name) } },
                            onSkip: { Task { await vm.skipName() } })
                    case let .show(wire, isResponse):
                        ShowAct(
                            wire: wire,
                            shareText: vm.shareText ?? wire,
                            isResponse: isResponse,
                            showsNote: !isResponse && vm.inviteId != nil && (vm.otherAwaitingInvites > 0),
                            note: $vm.note,
                            onNoteChange: { text in Task { await vm.setNote(text) } },
                            onShareSheetPresented: { Task { await vm.shareSheetPresented() } },
                            onHandOff: { faceToFace in await vm.handOffDone(faceToFace: faceToFace) },
                            onSceneChange: { bg, active in await vm.sceneChanged(background: bg, active: active) },
                            onShareSheetClosed: { vm.shareSheetClosed() },
                            onReceived: { text in Task { await vm.submitWire(text) } },
                            onClipboardPasted: { model.pasteBar.consume() },
                            onScanRequest: { showScanner = true }
                        )
                    case let .receive(notice):
                        ReceiveAct(
                            notice: notice,
                            isAwaitingPeerCode: vm.isAwaitingPeerCode,
                            canResend: vm.inviteId != nil && vm.canResendInvite,
                            onResend: vm.backToShow,
                            onDeleteInvite: { id in Task { await vm.deleteInvite(pairingId: id) } },
                            onOpenContact: { contactId in
                                // 关闭向导后进入该联系人详情;不切 tab。
                                dismiss()
                                model.path.append(.contact(contactId))
                            },
                            onScanRequest: { showScanner = true },
                            onClipboardPasted: { model.pasteBar.consume() },
                            onSubmit: { text in Task { await vm.submitWire(text) } }
                        )
                    case let .confirm(emoji, _, _, placeholderName):
                        ConfirmAct(
                            emoji: emoji,
                            placeholderName: placeholderName,
                            onConfirmMatch: { name in Task { await vm.confirmMatch(name: name) } },
                            onVerifyLater: { name in Task { await vm.verifyLater(name: name) } },
                            onRejectMismatch: { Task { await vm.rejectMismatch() } }
                        )
                    case let .failed(message):
                        FailedAct(
                            message: message,
                            onRetry: { Task { await vm.retry() } },
                            onBack: { dismiss() }
                        )
                    case let .gone(message):
                        // 终态:重试只会回到同一页,所以只给「返回」。
                        FailedAct(message: message, onRetry: nil, onBack: { dismiss() })
                    }
                }
                .padding(.horizontal, Moyu.Space.xl)
                .transition(.opacity)

                Spacer(minLength: 0)
            }
            .animation(stageAnimation, value: vm.ui.stage)
            .background(Moyu.Palette.surfaceBase)
            .navigationTitle(L10n.pairingAddContact)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button {
                        dismiss()
                    } label: {
                        Image(systemName: "chevron.backward")
                    }
                    .tint(Moyu.Palette.accentPrimary)
                    .accessibilityLabel(L10n.commonBack)
                }
                // 只有「我发出的配对码」可以删;发回的配对码、核对中都没有这个入口。
                ToolbarItem(placement: .navigationBarTrailing) {
                    if vm.inviteId != nil, deletable {
                        Button(L10n.commonDelete, role: .destructive) { confirmDelete = true }
                            .accessibilityIdentifier("pairing-delete")
                    }
                }
            }
            .confirmationDialog(L10n.pairingDeleteTitle, isPresented: $confirmDelete, titleVisibility: .visible) {
                Button(L10n.commonDelete, role: .destructive) {
                    if let id = vm.inviteId { Task { await vm.deleteInvite(pairingId: id) } }
                }
                Button(L10n.commonCancel, role: .cancel) {}
            } message: {
                Text(L10n.pairingDeleteBody)
            }
            .alert(vm.mutualInviteNotice ?? "", isPresented: Binding(
                get: { vm.mutualInviteNotice != nil },
                set: { if !$0 { vm.mutualInviteNotice = nil } }
            )) {
                // 出示幕上的互发提示只在向导握着自己的邀请时出现,删的就是这份。
                Button(L10n.pairingMutualInviteDelete, role: .destructive) {
                    if let id = vm.inviteId { Task { await vm.deleteInvite(pairingId: id) } }
                }
                Button(L10n.commonCancel, role: .cancel) {}
            }
            .alert(vm.actionError ?? "", isPresented: Binding(
                get: { vm.actionError != nil },
                set: { if !$0 { vm.actionError = nil } }
            )) {
                Button(L10n.commonOk) {}
            }
        }
        .fullScreenCover(isPresented: $showScanner) {
            QRScannerView(
                onResult: { text in
                    showScanner = false
                    Task { await vm.submitWire(text) }
                },
                onCancel: { showScanner = false }
            )
        }
        .task {
            await vm.start()
        }
        .onChange(of: vm.shouldClose) { close in
            if close { dismiss() }
        }
        .onChange(of: vm.openThreadPeerId) { peerId in
            guard let peerId else { return }
            let highlight = vm.openThreadHighlightId
            vm.openThreadPeerId = nil
            vm.openThreadHighlightId = nil
            // 收起向导(wizard = nil)、设高亮、落在该会话。
            model.openThreadFromOutside(peerId, highlight: highlight)
        }
    }
}

// MARK: - 步骤条

/// 三段 Capsule + 标题。当前及已过步骤 accent 填色,未到步骤 borderHairline
/// 填色——iOS 原生进度条惯例(与 Android 的三点样式不同,是本 task 有意的平台
/// 适配,见 brief Step 4)。
private struct WizardStepper: View {
    let titles: [String]
    let currentIndex: Int

    var body: some View {
        VStack(spacing: Moyu.Space.xs) {
            HStack(spacing: Moyu.Space.xs) {
                ForEach(titles.indices, id: \.self) { index in
                    Capsule()
                        .fill(index <= currentIndex ? Moyu.Palette.accentPrimary : Moyu.Palette.borderHairline)
                        .frame(height: 4)
                }
            }
            HStack(spacing: 0) {
                ForEach(titles.indices, id: \.self) { index in
                    Text(titles[index])
                        .font(moyuFont(Moyu.FontSize.caption))
                        .foregroundStyle(index <= currentIndex ? Moyu.Palette.accentPrimary : Moyu.Palette.textTertiary)
                        .frame(maxWidth: .infinity)
                }
            }
        }
        .padding(.bottom, Moyu.Space.xl)
    }
}

// MARK: - Working 幕

/// 正在建立加密会话:印章呼吸(scale 0.96↔1.04 + opacity 0.7↔1.0 循环),
/// reduce-motion 开启则常亮不呼吸。
private struct WorkingAct: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var breathing = false

    var body: some View {
        VStack(spacing: Moyu.Space.l) {
            Image(systemName: "seal")
                .font(moyuFont(64))
                .foregroundStyle(Moyu.Palette.accentPrimary)
                .scaleEffect(breathing ? 1.04 : 0.96)
                .opacity(breathing ? 1.0 : 0.7)
            Text(L10n.pairingWorking)
                .font(moyuFont(Moyu.FontSize.callout))
                .foregroundStyle(Moyu.Palette.textSecondary)
        }
        .padding(.top, Moyu.Space.xxl)
        .onAppear(perform: syncBreath)
    }

    private func syncBreath() {
        guard !reduceMotion else {
            breathing = false
            return
        }
        withAnimation(.easeInOut(duration: Moyu.Motion.gentle * 2 / 1000).repeatForever(autoreverses: true)) {
            breathing = true
        }
    }
}

// MARK: - 出示幕(发配对码)

/// 说明 + 分享(卡片图)/ 复制(文字版)+ 备注 + 卡片预览(当面给对方扫)+ 低调的「对方已扫码 · 下一步」。
/// 分享面板报告完成、点了复制、或点了「对方已扫码」都算交出去了:`onHandOff` 标已分享并前进。
private struct ShowAct: View {
    let wire: String
    /// 「复制」写进剪贴板的文字版(首行说明 + 带配对码的链接);分享的是卡片图,卡片二维码编码同一个链接。
    let shareText: String
    let isResponse: Bool
    /// 备注栏只在出示我的配对码时出现。
    let showsNote: Bool
    @Binding var note: String
    let onNoteChange: (String) -> Void
    /// 分享面板弹出(不论之后是否回报完成):此后关闭向导不再丢弃这份配对码。
    let onShareSheetPresented: () -> Void
    /// `faceToFace`:当面(扫码 / 手动「下一步」)= true,远程发回(分享完成 / 复制 / 离开 App 再回来)= false。
    let onHandOff: (Bool) async -> Void
    let onSceneChange: (_ background: Bool, _ active: Bool) async -> Void
    let onShareSheetClosed: () -> Void
    /// 对方先发来的(粘贴)/ 扫到的:交给向导的 `submitWire`(出示幕上只接收我自己邀请的这一幕)。
    let onReceived: (String) -> Void
    /// 向导里的系统粘贴按钮读了剪贴板:不论结果都算处理过,共享粘贴条记已消费并收起(spec 4.2)。
    let onClipboardPasted: () -> Void
    let onScanRequest: () -> Void

    @State private var shareItem: ShareCardItem?
    /// 出示幕上的卡片图(预览与分享是同一张);进幕时画一次。
    @State private var cardImage: UIImage?
    @Environment(\.scenePhase) private var scenePhase
    /// 分享面板开着时 App 进过后台(去别的 App 发,或锁屏 / 回桌面 / 切 App)也算发出去了——按设计,镜像 Android 对
    /// 「不回报完成」的 OEM 兜底;回执仍可从联系人页重发。逻辑在 VM(`sceneChanged`),这里只转发。

    var body: some View {
        ScrollView {
            VStack(spacing: Moyu.Space.m) {
                Text(isResponse ? L10n.pairingShowHintResponse : L10n.pairingShowHintInvite)
                    .font(moyuFont(Moyu.FontSize.callout))
                    .foregroundStyle(Moyu.Palette.textSecondary)
                    .multilineTextAlignment(.center)

                Button {
                    // 卡片没画出来就退回分享文字版,按钮不会没反应。
                    shareItem = ShareCardItem(items: [cardImage ?? shareText])
                    onShareSheetPresented()
                } label: {
                    Text(L10n.pairingShareToPeer).frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .tint(Moyu.Palette.accentPrimary)
                .accessibilityIdentifier("pairing-share")

                Button {
                    AppPasteboard.write(shareText)
                    Task { await onHandOff(false) }
                } label: {
                    Text(L10n.commonCopy).frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .accessibilityIdentifier("pairing-copy")

                if !isResponse {
                    receivedEntry
                }

                if showsNote {
                    VStack(alignment: .leading, spacing: Moyu.Space.xs) {
                        TextField(L10n.pairingNotePlaceholder, text: $note)
                            .padding(.horizontal, Moyu.Space.m)
                            .padding(.vertical, Moyu.Space.s)
                            .background(Moyu.Palette.surfaceSunken, in: RoundedRectangle(cornerRadius: Moyu.Radius.input, style: .continuous))
                            .onChange(of: note) { onNoteChange($0) }
                            .accessibilityIdentifier("pairing-note")
                        Text(L10n.pairingNoteHint)
                            .font(moyuFont(Moyu.FontSize.caption))
                            .foregroundStyle(Moyu.Palette.textTertiary)
                    }
                    .padding(.top, Moyu.Space.s)
                }

                if let cardImage {
                    Image(uiImage: cardImage)
                        .resizable()
                        .scaledToFit()
                        .frame(width: Moyu.Size.shareCardPreview)
                        .clipShape(RoundedRectangle(cornerRadius: Moyu.Radius.card, style: .continuous))
                        // 浅色模式下卡片底色与页面相近，描一圈细边让它看起来是一张图。
                        .overlay(
                            RoundedRectangle(cornerRadius: Moyu.Radius.card, style: .continuous)
                                .stroke(Moyu.Palette.borderHairline, lineWidth: 1)
                        )
                        .accessibilityLabel(L10n.shareCardCd)
                        .accessibilityIdentifier("pairing-card")
                        .padding(.top, Moyu.Space.s)
                }

                // 邀请幕:当面扫码后的唯一前进方式;回执幕:当面核对的手动入口(远程发回会自动直达对话)。
                Button(isResponse ? L10n.pairingSentNext : L10n.pairingNextAfterScan) { Task { await onHandOff(true) } }
                    .font(moyuFont(Moyu.FontSize.callout))
                    .tint(Moyu.Palette.textSecondary)
                    .accessibilityIdentifier("pairing-next-after-scan")
            }
            .padding(.vertical, Moyu.Space.s)
        }
        .task(id: wire) { cardImage = PairingCardImage.make(PairingShare(wire: wire, isResponse: isResponse)) }
        .onChange(of: scenePhase) { phase in
            Task {
                await onSceneChange(phase == .background, phase == .active)
                if phase == .active { shareItem = nil }
            }
        }
        .sheet(item: $shareItem, onDismiss: onShareSheetClosed) { item in
            ActivityView(items: item.items, onComplete: { completed in
                // 取消不算交出去;面板可能先报 false 再报 true,VM 只在仍停在出示幕时前进一次。
                guard completed else { return }
                shareItem = nil
                Task { await onHandOff(false) }
            })
        }
    }
}

extension ShowAct {
    /// 「对方先发给我了?」:系统粘贴按钮(点了才读剪贴板,不弹授权)+ 扫一扫。
    fileprivate var receivedEntry: some View {
        VStack(spacing: Moyu.Space.s) {
            Text(L10n.addContactReceivedPrompt)
                .font(moyuFont(Moyu.FontSize.callout))
                .foregroundStyle(Moyu.Palette.textSecondary)
            HStack(spacing: Moyu.Space.m) {
                PasteButton(payloadType: String.self) { strings in
                    let text = strings.first
                    Task { @MainActor in
                        onClipboardPasted()
                        if let text { onReceived(text) }
                    }
                }
                .tint(Moyu.Palette.accentPrimary)
                .accessibilityIdentifier("pairing-show-received-paste")

                Button(action: onScanRequest) {
                    Text(L10n.pairingScan)
                }
                .buttonStyle(.bordered)
                .accessibilityIdentifier("pairing-show-received-scan")
            }
        }
        .padding(.top, Moyu.Space.s)
    }
}

private struct ShareCardItem: Identifiable {
    let id = UUID()
    let items: [Any]
}

// MARK: - 接收幕(填对方的配对码)

/// 系统粘贴按钮(用户点了才读剪贴板)/ 扫码 / 手动输入;认出是配对码或加密消息就自动提交。
/// 进屏不读剪贴板、不预填。
private struct ReceiveAct: View {
    let notice: ReceiveNotice?
    /// 持有我发出、对方还没发回的配对码:提示可以先离开。
    let isAwaitingPeerCode: Bool
    /// 当前对象是可重发的我的配对码:显示「再发一次配对码」。
    let canResend: Bool
    let onResend: () -> Void
    /// 互发邀请提示上的「删掉这条邀请」:删提示指名的那一份(``ReceiveNotice/deleteInviteId``)。
    let onDeleteInvite: (String) -> Void
    let onOpenContact: (String) -> Void
    let onScanRequest: () -> Void
    /// 系统粘贴按钮读了剪贴板:不论结果都算处理过,共享粘贴条记已消费并收起(spec 4.2)。
    let onClipboardPasted: () -> Void
    let onSubmit: (String) -> Void

    @State private var wireInput = ""

    /// 认得出是配对码或加密消息:输入一变就自动提交。
    static func autoSubmits(_ text: String) -> Bool {
        switch IntakeClassifier.classify(text) {
        case .pairingInvite, .pairingResponse, .sessionMessage: return true
        case .empty, .notOurs, .incomplete, .linkOnly: return false
        }
    }

    var body: some View {
        VStack(spacing: Moyu.Space.m) {
            if isAwaitingPeerCode {
                Text(L10n.pairingWaitingForPeer)
                    .font(moyuFont(Moyu.FontSize.callout))
                    .foregroundStyle(Moyu.Palette.textPrimary)
                    .multilineTextAlignment(.center)
                    .accessibilityIdentifier("pairing-waiting")
            }

            Text(L10n.pairingEnterHint)
                .font(moyuFont(Moyu.FontSize.callout))
                .foregroundStyle(Moyu.Palette.textSecondary)
                .multilineTextAlignment(.center)

            // 系统粘贴按钮:标签由系统按语言给出(即「粘贴」/「Paste」),点它不会弹「允许粘贴」。
            PasteButton(payloadType: String.self) { strings in
                let first = strings.first
                Task { @MainActor in
                    onClipboardPasted()
                    guard let text = first else { return }
                    wireInput = text
                    // 认得出的内容由下面输入框的 onChange 提交(只提交一次);认不出的也提交,让用户看到提示。
                    if !Self.autoSubmits(text) { onSubmit(text) }
                }
            }
            .tint(Moyu.Palette.accentPrimary)
            .accessibilityIdentifier("pairing-paste")

            Button(action: onScanRequest) {
                Text(L10n.pairingScanQr).frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)

            TextField("🔒…", text: $wireInput, axis: .vertical) // l10n-ignore: 配对码前缀符号,不是文字
                .lineLimit(1...4)
                .font(moyuFont(Moyu.FontSize.mono, design: .monospaced))
                .padding(.horizontal, Moyu.Space.m)
                .padding(.vertical, Moyu.Space.s)
                .background(Moyu.Palette.surfaceSunken, in: RoundedRectangle(cornerRadius: Moyu.Radius.input, style: .continuous))
                .accessibilityIdentifier("pairing-input")
                .onChange(of: wireInput) { text in
                    // 用户贴进 / 打完了一整段能认出的内容才提交;半截的不打扰。
                    if Self.autoSubmits(text) { onSubmit(text) }
                }

            if let notice {
                // 分类提示不是故障:用次要色;握手真失败才用危险色。
                Text(notice.text)
                    .font(moyuFont(Moyu.FontSize.caption))
                    .foregroundStyle(notice.isHint ? Moyu.Palette.textSecondary : Moyu.Palette.statusDanger)
                    .multilineTextAlignment(.center)
                    .accessibilityIdentifier(notice.isHint ? "pairing-receive-hint" : "pairing-wizard-receive-error")
                if let deleteId = notice.deleteInviteId {
                    Button(L10n.pairingMutualInviteDelete, role: .destructive) { onDeleteInvite(deleteId) }
                        .accessibilityIdentifier("pairing-receive-delete-invite")
                }
                if let contactId = notice.contactId {
                    Button(L10n.pairingOpenContact) { onOpenContact(contactId) }
                        .accessibilityIdentifier("pairing-receive-open-contact")
                }
            }

            if canResend {
                Button(L10n.pairingResend, action: onResend)
                    .accessibilityIdentifier("pairing-resend")
            }
        }
    }
}

// MARK: - 核对幕

/// 核对安全码:说明 + 8 个图案 + 起名 + 一致 / 以后再核对 / 不一致。
private struct ConfirmAct: View {
    let emoji: [String]
    let placeholderName: String
    let onConfirmMatch: (String) -> Void
    let onVerifyLater: (String) -> Void
    let onRejectMismatch: () -> Void

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var stampVisible = false
    @State private var showMismatchDialog = false
    @State private var name = ""

    var body: some View {
        ScrollView {
            VStack(spacing: Moyu.Space.l) {
                Text(L10n.verifyTitle)
                    .font(moyuFont(Moyu.FontSize.title, weight: .semibold))
                    .foregroundStyle(Moyu.Palette.textPrimary)
                    .multilineTextAlignment(.center)

                VStack(spacing: Moyu.Space.xs) {
                    Text(L10n.verifyExplainCompare)
                        .font(moyuFont(Moyu.FontSize.callout))
                        .foregroundStyle(Moyu.Palette.textPrimary)
                    Text(L10n.verifyExplainMeaning)
                        .font(moyuFont(Moyu.FontSize.callout))
                        .foregroundStyle(Moyu.Palette.textSecondary)
                }
                .multilineTextAlignment(.center)

                EmojiSealGridView(emojis: emoji)
                    .scaleEffect(stampVisible ? 1.0 : 1.4)
                    .opacity(stampVisible ? 1.0 : 0.0)

                // 起名(只有自己看得到);留空保留默认名。不单独「保存」,随按钮一起生效。
                VStack(alignment: .leading, spacing: Moyu.Space.s) {
                    Text(L10n.verifyNameLabel)
                        .font(moyuFont(Moyu.FontSize.callout))
                        .foregroundStyle(Moyu.Palette.textSecondary)
                    TextField(placeholderName, text: $name)
                        .padding(.horizontal, Moyu.Space.m)
                        .padding(.vertical, Moyu.Space.s)
                        .background(Moyu.Palette.surfaceSunken, in: RoundedRectangle(cornerRadius: Moyu.Radius.input, style: .continuous))
                        .accessibilityIdentifier("pairing-verify-name")
                }

                Button {
                    #if canImport(UIKit)
                    UINotificationFeedbackGenerator().notificationOccurred(.success)
                    #endif
                    onConfirmMatch(name)
                } label: {
                    Text(L10n.verifyConfirmDone).frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .tint(Moyu.Palette.accentPrimary)
                .accessibilityIdentifier("pairing-verify-match")

                Button {
                    onVerifyLater(name)
                } label: {
                    Text(L10n.verifyLater).frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .accessibilityIdentifier("pairing-verify-later")

                Button {
                    showMismatchDialog = true
                } label: {
                    Text(L10n.verifyMismatch).foregroundStyle(Moyu.Palette.statusDanger)
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("pairing-verify-mismatch")
            }
            .padding(.vertical, Moyu.Space.l)
        }
        .onAppear {
            guard !reduceMotion else {
                stampVisible = true
                return
            }
            withAnimation(.easeOut(duration: Moyu.Motion.standard / 1000)) {
                stampVisible = true
            }
        }
        .confirmationDialog(L10n.verifyMismatchTitle, isPresented: $showMismatchDialog, titleVisibility: .visible) {
            Button(L10n.verifyMismatchConfirm, role: .destructive, action: onRejectMismatch)
            Button(L10n.verifyMismatchDismiss, role: .cancel) {}
        } message: {
            Text(L10n.verifyMismatchBody)
        }
    }
}

// MARK: - 失败幕

/// 说明 + 重试/返回。`onRetry == nil`(恢复的配对已不存在)只给「返回」——重试只会回到同一页。
private struct FailedAct: View {
    let message: String
    let onRetry: (() -> Void)?
    let onBack: () -> Void

    var body: some View {
        VStack(spacing: Moyu.Space.xl) {
            Text(message)
                .font(moyuFont(Moyu.FontSize.callout))
                // 失败用危险色;「这份配对码已不存在」(无重试)不是故障,用中性次要色。
                .foregroundStyle(onRetry == nil ? Moyu.Palette.textSecondary : Moyu.Palette.statusDanger)
                .multilineTextAlignment(.center)

            HStack(spacing: Moyu.Space.s) {
                if let onRetry {
                    Button(action: onRetry) {
                        Text(L10n.commonRetry).frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(Moyu.Palette.accentPrimary)
                }

                Button(action: onBack) {
                    Text(L10n.commonBack).frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
            }
        }
        .padding(.top, Moyu.Space.xxl)
    }
}
