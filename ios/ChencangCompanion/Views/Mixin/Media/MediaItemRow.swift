import SwiftUI
import AVFoundation
import ImageIO
import ChencangShared

/// 媒体消息里单个条目的一行(spec §3.4):多图消息每张一行,共享同一条 ChatMessage。
/// 只负责展示与把手势转成回调;下载/重试/转发/删除的编排在 ConversationThreadView。
struct MediaItemRow: View {
    let message: ChatMessage
    let item: MediaItem
    /// 收到的项「等待对方上传」且 30 分钟轮询窗口已过(气泡改「还没收到文件 · 点击重试」)。
    let awaitingWindowExpired: Bool
    let onRetry: () -> Void
    let onDownload: () -> Void
    let onOpenImage: (URL) -> Void
    let onOpenVideo: (URL) -> Void
    let onCopyWire: () -> Void
    /// `[index]` = 仅这一条;`nil` = 整条消息全部。
    let onForward: ([Int]?) -> Void
    /// 状态行可点(`MessageStatusLine.isStatusTappable`)时由线程页给:重新出加密卡。nil = 不可点。
    var onStatusTap: (() -> Void)? = nil
    let onDelete: () -> Void

    @EnvironmentObject private var model: MixinAppModel
    @EnvironmentObject private var voicePlayer: VoicePlayer

    /// 明文是否已经落盘、视频的可播放硬链接:只在条目状态真的变化时算一次(`.task(id:)`
    /// 随 `item.state` 重跑),不放进 body 里的计算属性——那样每次 SwiftUI 重绘(哪怕只是
    /// 滚动/动画触发的 diff)都会去 stat 文件系统、甚至去建硬链接(审查修复 2)。
    @State private var hasPlaintext = false
    @State private var videoPlaybackURL: URL?

    private var isSelf: Bool { message.direction == .outgoing }
    private var key: String { MediaActivity.key(messageId: message.id, index: item.index) }
    private var binURL: URL { model.mediaFiles.binURL(messageId: message.id, index: item.index) }
    private var isLastItem: Bool { item.index == (message.media?.count ?? 1) - 1 }
    /// 终审 F2c / I5:行本身**不订阅**进度——只把这一项自己的 `MediaProgress` 交给进度环,
    /// 一次进度跳动只重绘那个环。以前每行都订阅共享的 `MediaActivity.progress` 字典,
    /// 任何一项的一次跳动都让整条线程的所有行重算。
    private var progress: MediaProgress? { item.isTransferring ? model.mediaActivity.progress(for: key) : nil }
    /// 发送端整条消息的状态(先分享、后上传 spec §1.3),只在最后一项的行上用。`.cca` 只对失败项查
    /// (极少见),正常路径不碰文件系统。
    private var outgoingStatus: OutgoingMediaStatus? {
        guard isSelf, isLastItem else { return nil }
        return message.outgoingMediaStatus(files: model.mediaFiles)
    }

    var body: some View {
        HStack(alignment: .center, spacing: Moyu.Space.s) {
            if isSelf {
                Spacer(minLength: 0)
                if let status = outgoingStatus, MediaLayout.showsSendFailure(status) { retryButton(status) }
            }
            VStack(alignment: isSelf ? .trailing : .leading, spacing: Moyu.Space.xs) {
                bubble
                    .contentShape(Rectangle())
                    .onTapGesture(perform: tapped)
                    .contextMenu { menu }
                if let status = outgoingStatus { statusLabel(status) }
            }
            if !isSelf {
                if MediaLayout.showsDownloadRetry(direction: message.direction, state: item.state) {
                    downloadRetryButton
                }
                if item.kind == .voice && item.state == .ready && !item.played { unreadDot }
                Spacer(minLength: 0)
            }
        }
        .padding(.horizontal, Moyu.Space.m)
        .task(id: item.state) { refreshFileState() }
        .onChange(of: voicePlayer.playingKey) { newValue in markPlayedIfNowPlaying(newValue) }
    }

    /// 只在这条真的开始播放时才标记已读(审查修复 5)。语音解码是异步的(`VoicePlayer`
    /// 在 detached task 里解码——审查修复 4),`toggle()` 调用完不代表已经在放,所以不能在
    /// `tapped()` 里同步判断;改成订阅 `playingKey` 的变化,它变成这一条的 key 才算真正播放
    /// 开始(`toggle` 因解码失败 bail 掉的话,`playingKey` 永远不会变成 `key`,也就永远不会
    /// 误标已读)。
    private func markPlayedIfNowPlaying(_ playingKey: String?) {
        guard !isSelf, item.kind == .voice, !item.played, playingKey == key else { return }
        try? model.chatStore.updateMediaItem(messageId: message.id, peerId: message.peerId,
                                             index: item.index) { $0.played = true }
    }

    private func refreshFileState() {
        hasPlaintext = model.mediaFiles.exists(binURL)
        guard item.kind == .video, hasPlaintext else {
            videoPlaybackURL = nil
            return
        }
        videoPlaybackURL = try? model.mediaFiles.playableURL(messageId: message.id, index: item.index, ext: "mp4")
    }

    @ViewBuilder private var bubble: some View {
        switch item.kind {
        case .voice:
            VoiceBubble(item: item, isSelf: isSelf, isPlaying: voicePlayer.playingKey == key, progress: progress,
                        awaitingWindowExpired: awaitingWindowExpired)
        case .image:
            ImageBubble(item: item, fileURL: binURL, hasFile: hasPlaintext, progress: progress,
                        awaitingWindowExpired: awaitingWindowExpired)
        case .video:
            VideoBubble(item: item, posterURL: videoPlaybackURL, progress: progress,
                        awaitingWindowExpired: awaitingWindowExpired)
        }
    }

    private func tapped() {
        switch item.kind {
        case .voice:
            guard hasPlaintext else {
                if !isSelf { onDownload() }
                return
            }
            // 标已读见 markPlayedIfNowPlaying;解不开(坏包)整条判无法播放,提示而不是放出一段残缺音频
            voicePlayer.toggle(url: binURL, key: key) { [activity = model.mediaActivity] in
                activity.post(message: MediaNotice.corruptFile)
            }
        case .image:
            if hasPlaintext { onOpenImage(binURL) } else if !isSelf { onDownload() }
        case .video:
            if let url = videoPlaybackURL { onOpenVideo(url) } else if !isSelf { onDownload() }
        }
    }

    @ViewBuilder private var menu: some View {
        if isSelf && !message.body.isEmpty {
            Button(action: onCopyWire) { Label(L10n.threadCopyEncrypted, systemImage: "doc.on.doc") }
        }
        // 转发重发本机明文:这一项有明文才给「转发」;相册每一张都有明文才给「转发全部」(终审 10)。
        // 别的条目下载完会改写 message,行随之重算,菜单跟着更新。
        let forward = ForwardMenu.options(message: message, index: item.index) { index in
            index == item.index ? hasPlaintext
                : model.mediaFiles.exists(model.mediaFiles.binURL(messageId: message.id, index: index))
        }
        if forward.single {
            Button { onForward([item.index]) } label: { Label(L10n.commonForward, systemImage: "arrowshape.turn.up.right") }
        }
        if let count = forward.allCount {
            Button { onForward(nil) } label: {
                Label(L10n.mediaForwardAll(count), systemImage: "arrowshape.turn.up.right.2")
            }
        }
        Button(role: .destructive, action: onDelete) { Label(L10n.commonDelete, systemImage: "trash") }
    }

    /// 红 !:加密失败 → 重新加密;对方还看不到 → 只重排上传;永久失败 → 只提示原因(不重传,可删除)。
    private func retryButton(_ status: OutgoingMediaStatus) -> some View {
        Button { failureTapped(status) } label: {
            Image(systemName: "exclamationmark.circle.fill")
                .font(moyuFont(Moyu.FontSize.title))
                .foregroundStyle(Moyu.Palette.statusDanger)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(failureAccessibilityLabel(status))
    }

    private func failureTapped(_ status: OutgoingMediaStatus) {
        switch MediaLayout.outgoingStatusTap(status) {
        case .retry: onRetry()
        case .notice(let text): model.mediaActivity.post(message: text)
        case nil: break
        }
    }

    private func failureAccessibilityLabel(_ status: OutgoingMediaStatus) -> String {
        switch status {
        case .notVisibleToPeer: return L10n.mediaStatusNotVisible
        case .permanentlyFailed: return MediaLayout.outgoingStatusText(status) ?? ""
        default: return L10n.threadSendFailed
        }
    }

    /// 收到的媒体下载失败(终审 I6):与发送失败同一个红 !,点按重下。
    private var downloadRetryButton: some View {
        Button(action: onDownload) {
            Image(systemName: "exclamationmark.circle.fill")
                .font(moyuFont(Moyu.FontSize.title))
                .foregroundStyle(Moyu.Palette.statusDanger)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(L10n.threadDownloadFailed)
    }

    private var unreadDot: some View {
        // spec §4.2 组件级字面量:未播放红点直径复用 Space.s(8pt),尚无对应 L3 token
        Circle()
            .fill(Moyu.Palette.statusDanger)
            .frame(width: Moyu.Space.s, height: Moyu.Space.s)
            .accessibilityLabel(L10n.mediaVoiceUnplayed)
    }

    /// 最后一项下方的状态行(与 Android `OutStatus` 同口径):加密中 / 上传中 灰字;发送失败 /
    /// 对方还看不到 · 点击重新上传 / 永久失败原因 错误色、可点;全部已上传沿用状态行(MessageStatusLine)。
    @ViewBuilder private func statusLabel(_ status: OutgoingMediaStatus) -> some View {
        switch status {
        case .encrypting, .uploading:
            Text(MediaLayout.outgoingStatusText(status) ?? "")
                .font(moyuFont(Moyu.FontSize.caption))
                // 有意复用:与「已分享」同一档次要文字色
                .foregroundStyle(Moyu.Palette.textTertiary)
        case .encryptFailed, .notVisibleToPeer, .permanentlyFailed:
            Button { failureTapped(status) } label: {
                Text(MediaLayout.outgoingStatusText(status) ?? "")
                    .font(moyuFont(Moyu.FontSize.caption))
                    // 有意复用:与气泡旁红 ! 同一个错误色
                    .foregroundStyle(Moyu.Palette.statusDanger)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(failureAccessibilityLabel(status))
        case .uploaded:
            handOffLabel
        }
    }

    /// 全部已上传:沿用文字气泡的状态行;还没分享(已加密 / 已复制)时可点,重新出加密卡。
    @ViewBuilder private var handOffLabel: some View {
        if let text = MessageStatusLine.text(for: message) {
            if let onStatusTap {
                Button(action: onStatusTap) {
                    MessageStatusLabel(status: message.status, text: text)
                }
                .buttonStyle(.plain)
            } else {
                MessageStatusLabel(status: message.status, text: text)
            }
        }
    }
}

/// 订阅单个条目进度的进度环:只有它随进度重绘(终审 F2c / I5)。
private struct LiveProgressRing: View {
    @ObservedObject var progress: MediaProgress

    var body: some View {
        ProgressRing(progress: progress.value ?? 0)
    }
}

struct ProgressRing: View {
    let progress: Double

    var body: some View {
        ZStack {
            Circle().stroke(Moyu.Palette.mediaScrim, lineWidth: Moyu.Size.progressStroke)
            Circle()
                .trim(from: 0, to: max(0.02, min(1, progress)))
                .stroke(Moyu.Palette.accentPrimary,
                        style: StrokeStyle(lineWidth: Moyu.Size.progressStroke, lineCap: .round))
                .rotationEffect(.degrees(-90))
        }
        .frame(width: Moyu.Size.progressRing, height: Moyu.Size.progressRing)
        .accessibilityLabel(L10n.mediaTransferring)
    }
}

/// 「等待对方上传」占位上的转圈(spec §2)。
private struct AwaitingSpinner: View {
    var body: some View {
        ProgressView()
            // 有意复用:与占位文案同一档次要文字色
            .tint(Moyu.Palette.textTertiary)
            .accessibilityHidden(true)
    }
}

/// 媒体占位上的状态文案(已过期 / 文件已损坏 / 等待对方上传 / 还没收到文件 · 点击重试),
/// 等待对方上传时文案上方转圈。
private struct PlaceholderStatus: View {
    let text: String
    let spinning: Bool

    var body: some View {
        VStack(spacing: Moyu.Space.xs) {
            if spinning { AwaitingSpinner() }
            Text(text)
                .font(moyuFont(Moyu.FontSize.caption))
                .foregroundStyle(Moyu.Palette.textTertiary)
                .multilineTextAlignment(.center)
        }
        .padding(Moyu.Space.xs)
    }
}

private struct VoiceBubble: View {
    let item: MediaItem
    let isSelf: Bool
    let isPlaying: Bool
    let progress: MediaProgress?
    let awaitingWindowExpired: Bool

    var body: some View {
        HStack(spacing: Moyu.Space.s) {
            if isSelf {
                Spacer(minLength: 0)
                label
                icon
            } else {
                icon
                if MediaLayout.showsAwaitingSpinner(state: item.state, awaitingWindowExpired: awaitingWindowExpired) {
                    AwaitingSpinner()
                }
                label
                Spacer(minLength: 0)
            }
        }
        .padding(.horizontal, Moyu.Space.m)
        .padding(.vertical, Moyu.Space.s)
        .frame(minWidth: MediaLayout.voiceBubbleWidth(durMs: item.durMs))
        .background(isSelf ? Moyu.Palette.bubbleSelf : Moyu.Palette.bubblePeer,
                    in: RoundedRectangle(cornerRadius: Moyu.Radius.bubble, style: .continuous))
        .overlay {
            if !isSelf {
                RoundedRectangle(cornerRadius: Moyu.Radius.bubble, style: .continuous)
                    // 1pt 描边:与 HoldToTalkBar 同款,沿用 xs(4)的 1/4,不新开「hairline 宽度」token
                    // (终审 minor 11)。
                    .strokeBorder(Moyu.Palette.borderHairline, lineWidth: Moyu.Space.xs / 4)
            }
        }
        .overlay { if let progress { LiveProgressRing(progress: progress) } }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibilityText)
        .accessibilityAddTraits(.isButton)
    }

    private var icon: some View {
        Image(systemName: isPlaying ? "speaker.wave.3.fill" : "speaker.wave.2")
            .font(moyuFont(Moyu.FontSize.body))
            .foregroundStyle(Moyu.Palette.textPrimary)
            .scaleEffect(x: isSelf ? -1 : 1, y: 1)
    }

    private var accessibilityText: String {
        let base = L10n.mediaVoiceCd(MediaLayout.voiceLabel(durMs: item.durMs))
        guard item.state == .awaiting,
              let text = MediaLayout.stateText(item.state, awaitingWindowExpired: awaitingWindowExpired) else { return base }
        return L10n.mediaCdWithState(base, state: text)
    }

    private var label: some View {
        let stateText = MediaLayout.stateText(item.state, awaitingWindowExpired: awaitingWindowExpired)
        return Text(stateText ?? MediaLayout.voiceLabel(durMs: item.durMs))
            .font(moyuFont(Moyu.FontSize.body))
            .foregroundStyle(stateText == nil ? Moyu.Palette.textPrimary : Moyu.Palette.textTertiary)
            // 短语音气泡只有 ~20pt 给文字：不许折行，放不下时撑宽气泡（上面是 minWidth）
            .lineLimit(1)
            .fixedSize()
    }
}

private struct ImageBubble: View {
    let item: MediaItem
    let fileURL: URL
    let hasFile: Bool
    let progress: MediaProgress?
    let awaitingWindowExpired: Bool

    @Environment(\.displayScale) private var displayScale
    @State private var thumbnail: UIImage?

    var body: some View {
        let size = MediaLayout.thumbSize(width: item.width, height: item.height)
        ZStack {
            Moyu.Palette.surfaceSunken
            if let thumbnail {
                Image(uiImage: thumbnail).resizable().scaledToFill()
            }
            if let text = MediaLayout.stateText(item.state, awaitingWindowExpired: awaitingWindowExpired) {
                PlaceholderStatus(text: text, spinning: MediaLayout.showsAwaitingSpinner(
                    state: item.state, awaitingWindowExpired: awaitingWindowExpired))
            }
            if let progress { LiveProgressRing(progress: progress) }
        }
        .frame(width: size.width, height: size.height)
        .clipShape(RoundedRectangle(cornerRadius: Moyu.Radius.bubble, style: .continuous))
        .task(id: hasFile) {
            thumbnail = hasFile
                ? await MediaThumbnailer.image(at: fileURL, maxPixel: max(size.width, size.height) * displayScale)
                : nil
        }
        .accessibilityLabel(item.state == .awaiting
            ? L10n.mediaCdWithState(L10n.mediaImageCd,
                                    state: MediaLayout.stateText(item.state, awaitingWindowExpired: awaitingWindowExpired) ?? "")
            : L10n.mediaImageCd)
        .accessibilityAddTraits(.isButton)
    }
}

private struct VideoBubble: View {
    let item: MediaItem
    let posterURL: URL?
    let progress: MediaProgress?
    let awaitingWindowExpired: Bool

    @Environment(\.displayScale) private var displayScale
    @State private var poster: UIImage?

    var body: some View {
        let size = MediaLayout.thumbSize(width: item.width, height: item.height)
        ZStack {
            Moyu.Palette.surfaceSunken
            if let poster {
                Image(uiImage: poster).resizable().scaledToFill()
            }
            if let text = MediaLayout.stateText(item.state, awaitingWindowExpired: awaitingWindowExpired) {
                PlaceholderStatus(text: text, spinning: MediaLayout.showsAwaitingSpinner(
                    state: item.state, awaitingWindowExpired: awaitingWindowExpired))
            } else if let progress {
                LiveProgressRing(progress: progress)
            } else {
                Circle()
                    .fill(Moyu.Palette.surfaceRaised)
                    .frame(width: Moyu.Size.playBadge, height: Moyu.Size.playBadge)
                    .overlay(
                        Image(systemName: "play.fill")
                            .font(moyuFont(Moyu.FontSize.title))
                            .foregroundStyle(Moyu.Palette.accentPrimary)
                    )
            }
        }
        .frame(width: size.width, height: size.height)
        .overlay(alignment: .bottomTrailing) {
            Text(MediaLayout.videoLabel(durMs: item.durMs))
                .font(moyuFont(Moyu.FontSize.caption))
                .foregroundStyle(Moyu.Palette.textPrimary)
                .padding(.horizontal, Moyu.Space.xs)
                .background(Moyu.Palette.surfaceRaised, in: Capsule())
                .padding(Moyu.Space.s)
        }
        .clipShape(RoundedRectangle(cornerRadius: Moyu.Radius.bubble, style: .continuous))
        .task(id: posterURL) {
            if let posterURL {
                poster = await MediaThumbnailer.videoPoster(at: posterURL,
                                                            maxPixel: max(size.width, size.height) * displayScale)
            } else {
                poster = nil
            }
        }
        .accessibilityLabel(L10n.mediaVideoCd(MediaLayout.videoLabel(durMs: item.durMs)))
        .accessibilityAddTraits(.isButton)
    }
}

/// 缩略图 / 视频首帧都在本地从解密后的明文生成(帧内不带缩略图,spec §3.4)。
enum MediaThumbnailer {
    static func image(at url: URL, maxPixel: CGFloat) async -> UIImage? {
        await Task.detached(priority: .userInitiated) { () -> UIImage? in
            guard let cgImage = ImageDecoding.thumbnail(at: url, maxPixel: maxPixel) else { return nil }
            return UIImage(cgImage: cgImage)
        }.value
    }

    static func videoPoster(at url: URL, maxPixel: CGFloat) async -> UIImage? {
        await Task.detached(priority: .userInitiated) { () -> UIImage? in
            let generator = AVAssetImageGenerator(asset: AVURLAsset(url: url))
            generator.appliesPreferredTrackTransform = true
            generator.maximumSize = CGSize(width: maxPixel, height: maxPixel)
            guard let cgImage = try? generator.copyCGImage(at: .zero, actualTime: nil) else { return nil }
            return UIImage(cgImage: cgImage)
        }.value
    }
}
