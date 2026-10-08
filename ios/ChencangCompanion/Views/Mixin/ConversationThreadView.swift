import SwiftUI
import ChencangShared
import AVFoundation
import UIKit

/// spec §4.2 组件级字面量:启缄高亮统一 8% accent 底,尚无对应 L3 token;媒体行与文字气泡
/// 两处复用同一个值(审查修复 9)。
private func highlightFill(_ isHighlighted: Bool) -> Color {
    isHighlighted ? Moyu.Palette.accentPrimary.opacity(0.08) : Color.clear
}

/// 会话线程屏(spec §5.2)——密信 App 的核心屏,封缄流全在这里。`AppRoute.thread`
/// 落地到这里,替换 Task 6 的占位。
///
/// environment 值在 `init` 时还不可用,只能在 body 里读到 `MixinAppModel` 后,
/// 用它的 `chatService` 建一份具体的 `ThreadViewModel` 实例交给内层
/// `ThreadContent` 持有为 `@StateObject`(SwiftUI 只在首次求值时采用这份初始
/// 值,后续 body 重算不会重建/丢状态)。
struct ConversationThreadView: View {
    let peerId: String
    @EnvironmentObject private var model: MixinAppModel

    var body: some View {
        ThreadContent(peerId: peerId, vm: ThreadViewModel(peerId: peerId, service: model.chatService),
                      poller: Self.makePoller(model: model, peerId: peerId))
    }

    /// 「等待对方上传」轮询(先分享、后上传 spec §2):单调时钟(墙钟会被校时拨动,UAT O4)+ `Task.sleep`(被取消即返回),
    /// 每次再试走 `MediaDownloader.download`(超过 24 h 直接判已过期、不发请求)。
    @MainActor
    private static func makePoller(model: MixinAppModel, peerId: String) -> AwaitingPoller {
        let downloader = model.mediaDownloader
        let store = model.chatStore
        return AwaitingPoller(
            clock: { ProcessInfo.processInfo.systemUptime },
            sleeper: { try? await Task.sleep(nanoseconds: UInt64($0 * 1_000_000_000)) },
            tryFetch: { messageId, index in
                await downloader.download(messageId: messageId, peerId: peerId, index: index)
            },
            isAwaiting: { messageId, index in
                AwaitingPoller.keepsPolling(store.message(id: messageId, peerId: peerId)?.media?
                    .first(where: { $0.index == index })?.state)
            }
        )
    }
}

private struct ThreadContent: View {
    let peerId: String
    @StateObject private var vm: ThreadViewModel
    /// 收到的媒体「等待对方上传」时的轮询:只在本线程在屏上且 App 在前台时跑(spec §2)。
    @StateObject private var poller: AwaitingPoller
    /// 线程屏是否在屏上(onAppear/onDisappear;推进联系人页、全屏看图都会离屏)。
    @State private var onScreen = false

    @EnvironmentObject private var model: MixinAppModel
    @EnvironmentObject private var chatStore: ChatStore
    @ObservedObject private var contactsStore = ContactsStore.shared

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    /// 切后台/来电全屏/系统面板拉起时 scenePhase 会先离开 .active(审查修复 1c):
    /// 借这个信号取消还在录的语音,`.onDisappear` 只覆盖「离开这个线程」,盖不到「App 还在
    /// 这个线程但被切到后台」。
    @Environment(\.scenePhase) private var scenePhase

    /// 上次用户选的交出方式(卡片按钮或气泡长按分享/复制,由 `ThreadViewModel` 写入),下次封缄卡
    /// 把它排前面(spec §5.2「发过一次后记住偏好,主 action 前置」)。
    @AppStorage(ThreadViewModel.sealActionKey) private var preferredActionRaw = SealCardAction.share.rawValue

    /// 交出后的轻提示(spec 2026-09-30 §5.4):`vm.sharedNotice` 变化弹「已分享」,`vm.copiedNotice` 弹「已复制」。
    @State private var sentToastText = ""
    @State private var sentToastVisible = false
    @State private var sentToastToken = 0
    @EnvironmentObject private var activity: MediaActivity
    @State private var viewerURL: IdentifiedURL?
    @State private var videoURL: IdentifiedURL?
    @State private var forwardSource: ForwardSource?
    /// 看图页点「转发」:先关看图页,`onDismiss` 里再弹选人面板。
    @State private var pendingViewerForward: ForwardSource?
    /// 点了视频气泡触发下载后记下的标记(审查修复 1):这次下载 `await` 完、这一项已经 ready,就自动
    /// 打开播放器一次,打开后清掉;离开线程也清掉(`.onDisappear`),不让用户回来后突然自动播放。
    /// 判定在 `ChencangShared` 的 `VideoAutoPlay.itemToOpen`,读的是存储里的最新状态(终审 F3)。
    @State private var pendingVideoPlay: VideoAutoPlay.Pending?

    @StateObject private var recorder = VoiceRecorder()
    @State private var voiceMode = false
    @State private var showPlus = false
    @State private var showPhotoPicker = false
    @State private var showCamera = false
    @State private var recordingActive = false
    @State private var inCancelZone = false
    @State private var voiceToast: String?
    @State private var permissionAlert: PermissionPrompt?
    /// 当前这次按压的令牌(审查修复 5):`beginRecording` 里的异步 `start()` 完成时,
    /// 只有这个值仍等于自己领到的令牌才允许去动 `recordingActive`/调用 `endRecording`——
    /// 更新的一次按压已经把它覆盖掉的话,说明这是一次迟到的结果,绝不能碰新按压的状态。
    @State private var pressToken: Int?

    /// 权限被拒后的提示:标题 + 说明,按钮固定「去设置」「取消」。
    private struct PermissionPrompt: Equatable {
        let title: String
        let message: String

        static var microphone: Self { .init(title: L10n.mediaMicNeededTitle, message: L10n.mediaMicNeededBody) }
        static var camera: Self { .init(title: L10n.mediaCameraNeededTitle, message: L10n.mediaCameraNeeded) }
    }

    private struct ForwardSource: Identifiable {
        let id = UUID()
        let messageId: String
        /// `nil` = 整条消息全部。
        let indices: [Int]?
    }

    init(peerId: String, vm: ThreadViewModel, poller: AwaitingPoller) {
        self.peerId = peerId
        _vm = StateObject(wrappedValue: vm)
        _poller = StateObject(wrappedValue: poller)
    }

    private var contact: AppGroupContact? {
        contactsStore.contacts.first { $0.id == peerId }
    }
    private var displayName: String {
        let name = contact?.displayName ?? ""
        return name.isEmpty ? L10n.commonContactFallback : name
    }
    private var isVerified: Bool { contact?.isVerified ?? false }

    private var messages: [ChatMessage] { chatStore.messages(for: peerId) }

    private var preferredAction: SealCardAction {
        SealCardAction(rawValue: preferredActionRaw) ?? .share
    }

    /// reduce-motion 开启时,全部动效统一降为 0.09s crossfade(spec §3.4)。
    private var sealCardAnimation: Animation {
        reduceMotion ? .easeOut(duration: 0.09) : .easeOut(duration: Moyu.Motion.standard / 1000)
    }

    var body: some View {
        VStack(spacing: 0) {
            if let banner = vm.banner(contact: contact, messages: messages, historyLoaded: !chatStore.unreadablePeers.contains(peerId)) {
                ThreadBannerBar(
                    banner: banner,
                    // 动作进真正的核对页(联系人页,与标题栏同一条路由);横幅本身从不标记已核对。
                    onAction: { model.path.append(.contact(peerId)) },
                    onDismiss: { if let contact { vm.dismissBanner(banner, contact: contact) } })
            }
            messageList
                .overlay(alignment: .bottom) {
                    if sentToastVisible { sentToast }
                }
                .animation(sealCardAnimation, value: sentToastVisible)
            PasteBarHost(state: model.pasteBar)
            composeBar
        }
        .onAppear { model.pasteBar.recompute() }
        .overlay {
            if recordingActive || voiceToast != nil {
                ZStack {
                    Moyu.Palette.mediaScrim.ignoresSafeArea()
                    VoiceRecordOverlay(levels: recorder.levels, inCancelZone: inCancelZone,
                                       phase: recorder.phase, toast: voiceToast)
                }
                .allowsHitTesting(false)
            }
        }
        .sheet(isPresented: $showPhotoPicker, onDismiss: unblockShareIfIdle) {
            PhotoPicker(
                onPicked: handlePicked,
                onFailed: { model.mediaActivity.post(message: L10n.mediaPickerUnreadable) },
                onDone: { showPhotoPicker = false }
            )
            .ignoresSafeArea()
        }
        .fullScreenCover(isPresented: $showCamera, onDismiss: unblockShareIfIdle) {
            CameraPicker(onPicked: { handlePicked([$0]) }, onDone: { showCamera = false })
                .ignoresSafeArea()
        }
        .background(Moyu.Palette.surfaceBase)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) { titleBar }
        }
        // 分享面板一次一个,队列与「已分享」判定都在 ThreadViewModel(spec 2026-09-30 §5):
        // 面板报告完成才标记(取消后再选别的 App 会先 false 再 true,任一 true 即标、只标一次);
        // 收起时没完成过 → 卡片转「还没发 · 点这里再发」,再弹队列里的下一个。
        .sheet(item: $vm.presentedShare, onDismiss: { vm.shareSheetDismissed() }) { request in
            ActivityView(items: [request.text]) { completed in
                vm.shareReported(request, completed: completed)
            }
        }
        .onChange(of: vm.card?.messageId) { newValue in
            guard newValue != nil else { return }
            UINotificationFeedbackGenerator().notificationOccurred(.success)
        }
        .onChange(of: vm.sharedNotice) { _ in showSentToast(L10n.statusSharedToast) }
        .onChange(of: vm.copiedNotice) { _ in showSentToast(L10n.statusCopiedToast) }
        // 用户把整条加密消息或配对码贴进了输入框:交给来件入口(解开落库 / 进添加联系人向导 / 报失败)。
        .onChange(of: vm.draft) { _ in
            guard let raw = vm.takeIntakeFromDraft() else { return }
            Task { await model.handleIntake(raw) }
        }
        // 别的模态一上台就挡住分享面板;覆盖式模态在各自 onDismiss(收起动画结束)里解除,
        // 提示框没有 onDismiss,等它的收起动画(Motion.standard)过去再解除。
        .onChange(of: otherModalActive) { active in
            if active { vm.setPresentationBlocked(true) }
        }
        .onChange(of: alertActive) { active in
            guard !active else { return }
            DispatchQueue.main.asyncAfter(deadline: .now() + Moyu.Motion.standard / 1000) { unblockShareIfIdle() }
        }
        .onChange(of: scenePhase) { newPhase in
            // 权限框弹出的一瞬间 scenePhase 也会先变成 .inactive:这里的取消跟
            // HoldToTalkBar 手势侧的取消会重复触发,但 endRecording 本身是幂等的,无妨。
            if newPhase != .active, recordingActive {
                endRecording(cancelled: true)
            }
            // 回前台:可见的等待项立即再试并重开窗口;离开前台:停止轮询。
            if newPhase == .active { model.pasteBar.recompute(); resumePolling() } else { poller.onHidden() }
        }
        .alert(
            L10n.mediaStatusEncryptFailed,
            isPresented: Binding(
                get: { vm.sendError != nil },
                set: { isPresented in if !isPresented { vm.consumeSendError() } }
            )
        ) {
            Button(L10n.threadReaddContact) {
                vm.consumeSendError()
                // 等提示框收起动画过去再弹向导(同一时刻叠两个模态,SwiftUI 会丢掉后一个)。
                DispatchQueue.main.asyncAfter(deadline: .now() + Moyu.Motion.standard / 1000) {
                    model.presentWizard(.initiator)
                }
            }
            Button(L10n.commonOk, role: .cancel) { vm.consumeSendError() }
        } message: {
            Text(vm.sendError ?? "")
        }
    }

    /// 标题栏:整体可点 → 联系人页(ContactView,经 AppRoute.contact);未核对时名字下加一行「未核对 · 点这里核对」。
    private var titleBar: some View {
        Button {
            model.path.append(.contact(peerId))
        } label: {
            HStack(spacing: Moyu.Space.xs) {
                MoyuAvatarView(spec: contactAvatar(displayName: displayName, fingerprintHex: peerId), size: Moyu.Size.avatarInline)
                VStack(alignment: .leading, spacing: 0) {
                    HStack(spacing: Moyu.Space.xs) {
                        Text(displayName)
                            .font(moyuFont(Moyu.FontSize.body, weight: .medium))
                            .foregroundStyle(Moyu.Palette.textPrimary)
                        TrustSealIcon(verified: isVerified)
                    }
                    if !isVerified {
                        Text(L10n.threadUnverifiedSubtitle)
                            .font(moyuFont(Moyu.FontSize.caption))
                            .foregroundStyle(Moyu.Palette.statusWarn)
                    }
                }
            }
        }
        .buttonStyle(.plain)
    }

    /// 终审 F2(iPhone 12 松手后主线程在 LazyVStack 布局里空转 3 分钟,见 spindump):
    /// (a) `ForEach` 每个元素恰好一个视图——时间胶囊折进这一行的 VStack,不再给行另挂一个与 ForEach
    ///     id 相同的 `.id`;(b) 滚到底不带动画,并推迟到插入这次事务提交之后(下一轮 run loop);
    /// (c) 进度只让各自的进度环重绘(见 `MediaItemRow.progress`)。
    private var messageList: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: Moyu.Space.s) {
                    ForEach(ThreadFormat.rows(messages)) { row in
                        threadRow(row)
                    }
                }
                .padding(.vertical, Moyu.Space.s)
            }
            .background(Moyu.Palette.surfaceSunken)
            .overlay {
                if messages.isEmpty { emptyState }
            }
            .onAppear { scrollToBottom(proxy) }
            .onChange(of: messages.count) { _ in
                Task {
                    await model.mediaDownloader.autoDownload(peerId: peerId)
                    trackAwaiting()
                }
                scrollToBottom(proxy)
            }
        }
        // 打开线程自动下载语音与图片(pending 且未过期);视频点了才下(R9)。进线程这一次顺带把上次
        // 网络失败的再试一次(终审 I6)。得到「等待对方上传」的项随后进入轮询(spec §2)。
        .task {
            await model.mediaDownloader.autoDownload(peerId: peerId, retryFailed: true)
            trackAwaiting()
        }
        .onAppear {
            onScreen = true
            resumePolling()
        }
        .onDisappear {
            onScreen = false
            poller.onHidden()
            model.voicePlayer.stop()
            pendingVideoPlay = nil
        }
        .fullScreenCover(item: $viewerURL, onDismiss: {
            if let next = pendingViewerForward {
                pendingViewerForward = nil
                forwardSource = next
            }
            unblockShareIfIdle()
        }) { item in
            ImageViewer(url: item.url, onForward: item.forward.map { request in
                {
                    pendingViewerForward = ForwardSource(messageId: request.messageId, indices: request.indices)
                    viewerURL = nil
                }
            })
        }
        .fullScreenCover(item: $videoURL, onDismiss: unblockShareIfIdle) { item in
            VideoPlayerSheet(url: item.url)
        }
        .sheet(item: $forwardSource, onDismiss: unblockShareIfIdle) { source in
            ForwardPickerSheet(excludingPeerId: peerId) { target in
                Task {
                    let outcome = try? await model.mediaSender.forward(messageId: source.messageId, fromPeer: peerId,
                                                                       indices: source.indices, toPeer: target)
                    if let outcome {
                        handleMediaOutcome(outcome, peerId: target)
                    } else {
                        activity.post(message: MediaNotice.forwardFailed)
                    }
                }
            }
        }
        // 提示本身就是标题(没有单独的「提示」二字)。
        .alert(activity.notice ?? "", isPresented: Binding(
            get: { activity.notice != nil },
            set: { if !$0 { activity.consumeNotice() } }
        )) {
            Button(L10n.commonOk) { activity.consumeNotice() }
        }
    }

    /// 空线程:发件句 + 收件句(与 Android 空态同文案)。
    private var emptyState: some View {
        VStack(spacing: Moyu.Space.xs) {
            Text(L10n.threadEmptyTitle)
                .font(moyuFont(Moyu.FontSize.body))
                .foregroundStyle(Moyu.Palette.textSecondary)
            Text(L10n.threadEmptySend)
                .font(moyuFont(Moyu.FontSize.caption))
                .foregroundStyle(Moyu.Palette.textTertiary)
            Text(L10n.threadEmptyReceive)
                .font(moyuFont(Moyu.FontSize.caption))
                .foregroundStyle(Moyu.Palette.textTertiary)
        }
        .multilineTextAlignment(.center)
        .padding(Moyu.Space.xl)
        .allowsHitTesting(false)
    }

    /// 一条消息 = 一个视图:可选的时间胶囊 + 气泡(终审 F2a)。
    private func threadRow(_ row: ThreadRowModel) -> some View {
        VStack(spacing: Moyu.Space.s) {
            if row.showsTimeChip {
                Text(ThreadFormat.chipText(row.message.timestamp, now: Date()))
                    .font(moyuFont(Moyu.FontSize.caption))
                    .foregroundStyle(Moyu.Palette.textTertiary)
                    .frame(maxWidth: .infinity, alignment: .center)
            }
            messageContent(row.message)
        }
    }

    @ViewBuilder
    private func messageContent(_ message: ChatMessage) -> some View {
        if message.kind.isMedia, let items = message.media {
            VStack(spacing: Moyu.Space.s) {
                ForEach(items, id: \.index) { item in
                    mediaRow(message, item)
                }
            }
            // 与 MessageRow 同一套启缄高亮:命中时 8% accent 底,出现后清位
            .background(
                RoundedRectangle(cornerRadius: Moyu.Radius.bubble, style: .continuous)
                    .fill(highlightFill(message.id == model.highlightMessageId))
            )
            .onAppear { settleHighlight(message.id) }
        } else {
            MessageRow(
                message: message,
                isHighlighted: message.id == model.highlightMessageId,
                reduceMotion: reduceMotion,
                onHighlightSettled: { model.highlightMessageId = nil },
                actions: bubbleActions(message),
                onStatusTap: MessageStatusLine.isStatusTappable(message) ? { vm.reopenCard(for: message) } : nil
            )
        }
    }

    /// 文字气泡长按菜单(项目由 `BubbleMenu.items` 决定):分享 / 复制加密消息走 VM 的交出规则;
    /// 复制原文只写剪贴板、不改状态;删除与媒体同一条路(先收卡撤面板,再删记录)。
    private func bubbleActions(_ message: ChatMessage) -> BubbleActions {
        BubbleActions(
            onShare: { vm.shareMessage(message) },
            onCopyEncrypted: {
                if let text = vm.copyMessage(message) { AppPasteboard.write(text) }
            },
            onCopyText: {
                AppPasteboard.write(message.body)
                showSentToast(L10n.commonCopied)
            },
            onDelete: {
                vm.messageDeleted(message.id)
                Task { try? await model.threadCleaner.deleteMessage(id: message.id, peerId: peerId) }
            }
        )
    }

    /// 不带动画、推迟到下一轮 run loop 再滚(终审 F2b):插入新行的这次事务先提交、布局先落定,
    /// 再定位到 ForEach 的 id;不在同一个事务里叠一个动画滚动。
    private func scrollToBottom(_ proxy: ScrollViewProxy) {
        DispatchQueue.main.async {
            guard let last = messages.last else { return }
            proxy.scrollTo(last.id, anchor: .bottom)
        }
    }

    private func mediaRow(_ message: ChatMessage, _ item: MediaItem) -> some View {
        MediaItemRow(
            message: message,
            item: item,
            awaitingWindowExpired: poller.windowExpired(message.id, item.index),
            onRetry: {
                // 已分享的只重排上传(不出卡、不弹面板、不改分享文本);未分享的重新封缄后照常出卡 + 弹面板。
                Task { await vm.retryMedia(messageId: message.id, sender: model.mediaSender) }
            },
            onDownload: {
                // 等待对方上传(含「还没收到文件 · 点击重试」):重开 30 分钟窗口并立即再试(spec §2)。
                if item.state == .awaiting {
                    poller.onVisible([(message.id, item.index)])
                    return
                }
                // 视频没有自动下载(R9):这次点击本身就是「打开」的意图,记下来,
                // 下载完就自动播一次(审查修复 1)。
                let tapped = VideoAutoPlay.Pending(messageId: message.id, index: item.index)
                if item.kind == .video { pendingVideoPlay = tapped }
                Task {
                    await model.mediaDownloader.download(messageId: message.id, peerId: peerId, index: item.index)
                    if item.kind == .video { openVideoIfStillPending(tapped) }
                    // 点了得到「等待对方上传」(视频点了才下):同样进入轮询。
                    trackAwaiting()
                }
            },
            onOpenImage: {
                viewerURL = IdentifiedURL(url: $0, forward: ThreadForwardRequest(messageId: message.id,
                                                                                indices: [item.index]))
            },
            onOpenVideo: { videoURL = IdentifiedURL(url: $0) },
            onCopyWire: {
                // R1 两行整体。复制即标已复制(已分享的不降级),每次都提示「已复制」;若输入区上方正是这条的封缄卡,
                // 顺带收起;并记住「复制」偏好(与 Android 长按复制一致)。
                if let text = vm.copyMediaWire(message) { AppPasteboard.write(text) }
            },
            onForward: { indices in forwardSource = ForwardSource(messageId: message.id, indices: indices) },
            onStatusTap: MessageStatusLine.isStatusTappable(message) ? { vm.reopenCard(for: message) } : nil,
            onDelete: {
                // 删掉的这条消息如果正在放语音,先停(审查修复 6)——不然消息/文件都没了,
                // 播放器还在放着一段已经不属于任何消息的音频。
                if let key = model.voicePlayer.playingKey, key.hasPrefix("\(message.id)#") {
                    model.voicePlayer.stop()
                }
                // 封缄卡正是这条就收起、撤掉它排队的面板(UAT B1)。
                vm.messageDeleted(message.id)
                // 记录立刻删(界面即时消失);随后取消这条的后台上传,最后才删文件。
                Task { try? await model.threadCleaner.deleteMessage(id: message.id, peerId: peerId) }
            }
        )
    }

    /// 本线程在屏上且 App 在前台:可见的等待项立即再试一次并重开 30 分钟窗口(spec §2)。
    private func resumePolling() {
        guard onScreen, scenePhase == .active else { return }
        poller.onVisible(AwaitingPoller.targets(in: messages))
    }

    /// 刚请求过、刚进入等待的项从现在起按间隔轮询(不可见时 `track` 自己忽略)。
    private func trackAwaiting() {
        poller.track(AwaitingPoller.targets(in: messages))
    }

    /// 视频下载完成后是否该自动打开播放一次(spec §3.4、R9)。终审 F3:以前挂在
    /// `.onChange(of: item.state)` 上,拿的是闭包捕获的旧 `item`(状态恒为 pending/downloading),
    /// 永远打不开;行在屏幕外时更是不触发。现在由用户点的那次下载 `await` 完直接调用,
    /// 从存储读最新状态判定(`VideoAutoPlay.itemToOpen`),这里只负责清标记 + 真正打开。
    private func openVideoIfStillPending(_ tapped: VideoAutoPlay.Pending) {
        guard let fresh = VideoAutoPlay.itemToOpen(pending: pendingVideoPlay, tapped: tapped,
                                                   peerId: peerId, store: chatStore) else { return }
        pendingVideoPlay = nil
        guard let url = try? model.mediaFiles.playableURL(messageId: tapped.messageId, index: fresh.index,
                                                          ext: "mp4") else { return }
        videoURL = IdentifiedURL(url: url)
    }

    /// 媒体封缄成功(加密完即返回,上传在后台引擎里另跑——先分享、后上传 spec §1)→ 输入区上方出媒体封缄卡
    /// + 自动弹分享面板(spec 2026-09-30 §5.3)。「已分享」在面板
    /// 报告完成、点卡片「复制」或长按「复制密文」时才标记,取消不标记,排队等待的分享不提前标记。
    /// 失败/进行中什么都不做(气泡上有红 ! 或进度环)。重试不走这里,见 `ThreadViewModel.retryMedia`。
    private func handleMediaOutcome(_ outcome: MediaSender.Outcome, peerId target: String) {
        guard case let .sealed(messageId, text) = outcome else { return }
        vm.mediaSealed(messageId: messageId, peerId: target, shareText: text)
    }

    /// 与 MessageRow.onAppear 相同:reduce-motion 直接清,否则 Motion.seal 淡出。
    private func settleHighlight(_ messageId: String) {
        guard messageId == model.highlightMessageId else { return }
        if reduceMotion {
            model.highlightMessageId = nil
        } else {
            withAnimation(.easeOut(duration: Moyu.Motion.seal / 1000)) {
                model.highlightMessageId = nil
            }
        }
    }

    /// 除分享面板外的覆盖式模态(sheet / fullScreenCover)。它们在台上时 SwiftUI 不会再叠分享面板。
    private var otherModalActive: Bool {
        showPhotoPicker || showCamera || viewerURL != nil || videoURL != nil
            || forwardSource != nil || pendingViewerForward != nil || alertActive
    }

    private var alertActive: Bool {
        permissionAlert != nil || activity.notice != nil || vm.sendError != nil
    }

    /// 别的模态都已收起才放行排队中的分享面板(见 `ThreadViewModel.setPresentationBlocked`)。
    private func unblockShareIfIdle() {
        if !otherModalActive { vm.setPresentationBlocked(false) }
    }

    /// 「已分享」/「已复制」轻提示:停留两拍 Motion.seal 后淡出;连续触发以最后一次为准。
    private func showSentToast(_ text: String) {
        sentToastToken += 1
        let token = sentToastToken
        sentToastText = text
        sentToastVisible = true
        UIAccessibility.post(notification: .announcement, argument: text)
        Task {
            try? await Task.sleep(nanoseconds: UInt64(Moyu.Motion.seal) * 2 * 1_000_000)
            if sentToastToken == token { sentToastVisible = false }
        }
    }

    private var sentToast: some View {
        Label(sentToastText, systemImage: "checkmark.circle.fill")
            .font(moyuFont(Moyu.FontSize.callout, weight: .medium))
            .foregroundStyle(Moyu.Palette.textPrimary)
            .padding(.horizontal, Moyu.Space.m)
            .padding(.vertical, Moyu.Space.s)
            .background(Moyu.Palette.surfaceRaised, in: Capsule())
            .overlay(Capsule().strokeBorder(Moyu.Palette.borderHairline, lineWidth: 1))
            .padding(.bottom, Moyu.Space.s)
            .transition(.opacity)
            .allowsHitTesting(false)
    }

    private var composeBar: some View {
        VStack(spacing: 0) {
            Rectangle()
                .fill(Moyu.Palette.borderHairline)
                .frame(height: 1)

            if let card = vm.card {
                SealCardView(
                    state: card.viewState,
                    summary: card.summary,
                    caption: card.caption(recipientName: displayName),
                    primaryAction: preferredAction,
                    onShare: handleShare,
                    onCopy: handleCopy,
                    onClose: { vm.dismissSealed() }
                )
                .padding(.horizontal, Moyu.Space.m)
                .padding(.top, Moyu.Space.s)
                .transition(.scale(scale: 0.92).combined(with: .opacity))
            }

            HStack(alignment: .bottom, spacing: Moyu.Space.s) {
                modeToggle
                if voiceMode {
                    HoldToTalkBar(
                        isPressing: recordingActive,
                        onBegin: beginRecording,
                        onCancelZoneChange: { inCancelZone = $0 },
                        onEnd: { endRecording(cancelled: $0) }
                    )
                } else {
                    TextField(L10n.composerPlaceholder, text: $vm.draft, axis: .vertical)
                        .lineLimit(1...5)
                        .padding(.horizontal, Moyu.Space.m)
                        .padding(.vertical, Moyu.Space.s)
                        .background(Moyu.Palette.surfaceSunken, in: RoundedRectangle(cornerRadius: Moyu.Radius.input, style: .continuous))
                }
                if !voiceMode && !vm.draft.trimmingCharacters(in: .whitespaces).isEmpty {
                    sealButton
                } else {
                    plusButton
                }
            }
            .padding(Moyu.Space.s)

            if showPlus {
                PlusPanel(
                    onAlbum: {
                        showPlus = false
                        showPhotoPicker = true
                    },
                    onCamera: {
                        showPlus = false
                        Task { await openCamera() }
                    }
                )
                .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .background(Moyu.Palette.surfaceBase)
        .animation(sealCardAnimation, value: vm.card)
        .animation(sealCardAnimation, value: showPlus)
        .onDisappear {
            if recordingActive { endRecording(cancelled: true) }
        }
        .alert(permissionAlert?.title ?? "", isPresented: Binding(
            get: { permissionAlert != nil },
            set: { if !$0 { permissionAlert = nil } }
        )) {
            Button(L10n.mediaGoToSettings) {
                if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                permissionAlert = nil
            }
            Button(L10n.commonCancel, role: .cancel) { permissionAlert = nil }
        } message: {
            Text(permissionAlert?.message ?? "")
        }
    }

    private var modeToggle: some View {
        Button {
            voiceMode.toggle()
            showPlus = false
        } label: {
            Image(systemName: voiceMode ? "keyboard" : "mic.circle")
                .font(moyuFont(Moyu.FontSize.title))
                .foregroundStyle(Moyu.Palette.textSecondary)
                .padding(Moyu.Space.s)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(voiceMode ? L10n.composerToKeyboard : L10n.composerToVoice)
    }

    private var plusButton: some View {
        Button {
            showPlus.toggle()
        } label: {
            Image(systemName: "plus.circle")
                .font(moyuFont(Moyu.FontSize.title))
                .foregroundStyle(Moyu.Palette.textSecondary)
                .padding(Moyu.Space.s)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(L10n.composerMore)
    }

    // MARK: - 媒体发送编排

    private func beginRecording() {
        // 正在放的语音先停(终审 minor 6):不然外放的声音会被录进去,录音引擎也会碰上路由变化。
        model.voicePlayer.stop()
        // 令牌在按下的同步调用栈里领取,同时记进 pressToken 标记「这是当前这次按压」;
        // 松手(endRecording)会立刻作废录音器里的令牌,即使下面的异步启动还没跑到,
        // 也不会在松手后开麦。
        let token = recorder.press()
        pressToken = token
        recordingActive = true
        inCancelZone = false
        recorder.onAutoStop = { autoStopRecording() }              // 60 秒自动结束并发送
        recorder.onInterrupted = { endRecording(cancelled: true) } // 来电/系统打断:按取消收尾,不发送
        Task {
            let result = await recorder.start(token: token)
            // 更新的一次按压已经把 pressToken 覆盖掉:这是一次迟到的结果,不能碰新按压的状态
            // (审查修复 5)。
            guard pressToken == token else { return }
            switch result {
            case .started:
                break
            case .cancelled:
                // 权限框弹出时手指已经离开(系统取消了这次手势,onEnded 不会被调用),
                // 直接按取消收尾,用户再按一次即可(审查修复 1a)。
                endRecording(cancelled: true)
            case .denied:
                endRecording(cancelled: true)
                permissionAlert = .microphone
            case .failed:
                endRecording(cancelled: true)
                model.mediaActivity.post(message: MediaPrepError.voiceEncodeFailed.userMessage)
            }
        }
    }

    /// 手指松开(或被判定为取消)。录音器总是被叫停(幂等),界面仍处于录音态时才处理结果。
    private func endRecording(cancelled: Bool) {
        let wasRecording = recordingActive
        recordingActive = false
        inCancelZone = false
        pressToken = nil
        let result = recorder.stop(cancelled: cancelled)
        if wasRecording { deliver(result) }
    }

    /// 60 秒到点:总是叫停录音器;界面仍在录音态才发送(之后手指松开走 endRecording,拿到 .cancelled 不再重复)。
    private func autoStopRecording() {
        let wasRecording = recordingActive
        recordingActive = false
        inCancelZone = false
        pressToken = nil
        let result = recorder.stop(cancelled: false)
        if wasRecording { deliver(result) }
    }

    private func deliver(_ result: VoiceRecorder.StopResult) {
        switch result {
        case .cancelled:
            break
        case .tooShort:
            showVoiceToast(L10n.mediaVoiceTooShort)
        case .failed:
            model.mediaActivity.post(message: MediaPrepError.voiceEncodeFailed.userMessage)
        case let .recorded(prepared):
            Task { await sendMedia([prepared]) }
        }
    }

    private func showVoiceToast(_ text: String) {
        voiceToast = text
        Task {
            // 借封缄动效的时长(Motion.seal)乘 2:够读完「说话时间太短」这几个字,
            // 不为「提示停留时长」另开一个 token。
            try? await Task.sleep(nanoseconds: UInt64(Moyu.Motion.seal) * 2 * 1_000_000)
            voiceToast = nil
        }
    }

    private func sendMedia(_ media: [PreparedMedia]) async {
        let outcome = await model.mediaSender.send(to: peerId, media: media)
        handleMediaOutcome(outcome, peerId: peerId)
    }

    private func handlePicked(_ picks: [PickedMedia]) {
        let batch = PickedMedia.batches(picks)
        Task {
            var images: [PreparedMedia] = []
            for data in batch.images {
                do {
                    images.append(try await Task.detached { try ImagePreparer.prepare(imageData: data) }.value)
                } catch {
                    model.mediaActivity.post(error)
                }
            }
            if !images.isEmpty { await sendMedia(images) }
            for url in batch.videos {
                defer { try? FileManager.default.removeItem(at: url) }
                do {
                    let prepared = try await VideoPreparer.prepare(sourceURL: url,
                                                                   workDir: FileManager.default.temporaryDirectory)
                    await sendMedia([prepared])
                } catch {
                    model.mediaActivity.post(error)       // 「视频不能超过 60 秒」「视频太大」
                }
            }
        }
    }

    /// 相机权限只在点「拍摄」时申请(R8)。
    private func openCamera() async {
        guard UIImagePickerController.isSourceTypeAvailable(.camera) else {
            model.mediaActivity.post(message: L10n.mediaNoCamera)
            return
        }
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            showCamera = true
        case .notDetermined:
            if await AVCaptureDevice.requestAccess(for: .video) {
                showCamera = true
            } else {
                permissionAlert = .camera
            }
        default:
            permissionAlert = .camera
        }
    }

    /// 带字的「加密」键(取代原来的锁图标圆键):文字可换行,高度不低于 44 的触控下限(spec §5.2 组件级字面量)。
    private var sealButton: some View {
        let isEmpty = vm.draft.trimmingCharacters(in: .whitespaces).isEmpty
        return Button {
            vm.seal()
        } label: {
            Text(L10n.commonEncrypt)
                .font(moyuFont(Moyu.FontSize.callout, weight: .semibold))
                .foregroundStyle(Moyu.Palette.accentOnPrimary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, Moyu.Space.m)
                .frame(minHeight: 44)
                .background(Moyu.Palette.accentPrimary,
                            in: RoundedRectangle(cornerRadius: Moyu.Radius.input, style: .continuous))
        }
        .buttonStyle(.plain)
        .opacity(isEmpty ? 0.4 : 1)
        .disabled(isEmpty)
        .accessibilityLabel(L10n.commonEncrypt)
    }

    /// 「分享」:只弹分享面板,不标记——面板报告完成才算已分享(spec 2026-09-30 §5.1)。
    /// 走 VM 的队列:要是这时正好有一个媒体分享面板在展示,不会把它顶掉(审查修复 8)。
    private func handleShare() {
        vm.shareCard()      // 偏好由 VM 记
    }

    /// 「复制」:拷贝与分享同一份文本(首行 + 加密消息),卡片先切「已复制」态停留一拍(Motion.seal
    /// 600ms,与封缄动效同一预算),再标记已复制收起——让用户看清「已复制」这个反馈。
    ///
    /// messageId/peerId 由 `copyCard()` 在调度延迟 Task **之前**同步捕获:600ms 窗口期内用户完全
    /// 可能已经封缄了下一条,到期时再读「当下」的卡片会误标新消息。`markCopied(messageId:peerId:)`
    /// 按 id 落库,且只在当前卡片仍是这一条时才收卡。
    private func handleCopy() {
        guard let copy = vm.copyCard() else { return }
        AppPasteboard.write(copy.text)      // 偏好由 copyCard() 记
        Task {
            try? await Task.sleep(nanoseconds: UInt64(Moyu.Motion.seal * 1_000_000))
            vm.markCopied(messageId: copy.messageId, peerId: copy.peerId)
        }
    }
}

/// 单条气泡行:承担启缄高亮(spec §5.4)——`model.highlightMessageId` 命中时
/// 加一层 8% accent 底,`onAppear` 后(reduce-motion 直接清、否则 600ms 淡出)清位。
private struct MessageRow: View {
    let message: ChatMessage
    let isHighlighted: Bool
    let reduceMotion: Bool
    let onHighlightSettled: () -> Void
    /// 长按菜单动作(项目见 `BubbleMenu`)与状态行点击(nil = 不可点)。
    var actions: BubbleActions = .none
    var onStatusTap: (() -> Void)? = nil

    var body: some View {
        MessageBubbleView(message: message, actions: actions, onStatusTap: onStatusTap)
            .padding(.horizontal, Moyu.Space.m)
            .background(
                RoundedRectangle(cornerRadius: Moyu.Radius.bubble, style: .continuous)
                    .fill(highlightFill(isHighlighted))
            )
            .onAppear {
                guard isHighlighted else { return }
                if reduceMotion {
                    onHighlightSettled()
                } else {
                    withAnimation(.easeOut(duration: Moyu.Motion.seal / 1000)) {
                        onHighlightSettled()
                    }
                }
            }
    }
}
