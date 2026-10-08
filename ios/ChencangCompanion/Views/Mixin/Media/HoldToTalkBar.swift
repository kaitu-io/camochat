import SwiftUI
import ChencangShared

/// 「按住 说话」长条(spec §3.1–§3.2):按下开始,上滑超过阈值进入取消区,松手结束。
struct HoldToTalkBar: View {
    let isPressing: Bool
    let onBegin: () -> Void
    let onCancelZoneChange: (Bool) -> Void
    let onEnd: (_ cancelled: Bool) -> Void

    /// 只用来侦测「手势被系统取消」(权限框弹出、来电、控制中心/通知中心下拉、切后台……):
    /// `@GestureState` 在手势被打断时会自动重置回 `.idle`,而 `DragGesture.onEnded` 在那种情况下
    /// 不会被调用——这是 SwiftUI 的已知行为(审查修复 1b)。
    private enum PressPhase: Equatable { case idle, pressing }
    @GestureState private var pressPhase: PressPhase = .idle
    @State private var began = false
    @State private var inCancelZone = false

    var body: some View {
        Text(isPressing ? L10n.composerReleaseToSend : L10n.composerHoldToTalk)
            .font(moyuFont(Moyu.FontSize.body, weight: .medium))
            .foregroundStyle(Moyu.Palette.textPrimary)
            .frame(maxWidth: .infinity)
            .padding(.vertical, Moyu.Space.s)
            .background(isPressing ? Moyu.Palette.surfaceSunken : Moyu.Palette.surfaceRaised,
                        in: RoundedRectangle(cornerRadius: Moyu.Radius.input, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: Moyu.Radius.input, style: .continuous)
                    // 1pt 描边:沿用 xs(4)的 1/4,不新开一个「hairline 宽度」token
                    // (其余气泡/卡片同款描边目前仍是字面量 1,不在本次范围内)。
                    .strokeBorder(Moyu.Palette.borderHairline, lineWidth: Moyu.Space.xs / 4)
            )
            .gesture(
                DragGesture(minimumDistance: 0)
                    .updating($pressPhase) { _, state, _ in
                        state = .pressing
                    }
                    .onChanged { value in
                        if !began {
                            began = true
                            onBegin()
                        }
                        let cancel = VoiceRecordingClock.isCancelSlide(translationHeight: value.translation.height)
                        if cancel != inCancelZone {
                            inCancelZone = cancel
                            onCancelZoneChange(cancel)
                        }
                    }
                    .onEnded { _ in
                        let cancelled = inCancelZone
                        began = false
                        inCancelZone = false
                        onEnd(cancelled)
                    }
            )
            .onChange(of: pressPhase) { phase in
                // `began` 仍是 true 说明这次回到 .idle 不是 onEnded 正常松手引发的(onEnded 会先把
                // began 置 false),而是手势被系统取消——补一次「取消收尾」,不让录音停在无手指状态。
                if phase == .idle, began {
                    began = false
                    inCancelZone = false
                    onEnd(true)
                }
            }
            .accessibilityLabel(L10n.composerHoldToTalk)
    }
}

/// 屏幕中部的录音浮层:实时振幅条 +「松开 发送 / 上滑 取消」,取消区变红;50 秒起倒计时;太短时短暂提示。
struct VoiceRecordOverlay: View {
    let levels: [CGFloat]
    let inCancelZone: Bool
    let phase: VoiceRecordingClock.Phase
    let toast: String?

    // 取消区背景换成 statusDanger(红)后,文字/图标复用 accentOnPrimary——它是「实心强调色上的前景色」,
    // 并不是恒为浅色:浅色模式是白字(#FFFFFF 压 #D64545,约 4.6:1),深色模式是近黑字(#0B0D0E 压
    // 深色版 statusDanger #E0605A,约 5.6:1)。两种模式下 statusDanger 与 accentPrimary 的明暗走向一致,
    // 所以借它当红底上的前景都够清楚,不必再开一个 token(终审 minor 10 更正了原注释)。
    private var foreground: Color { inCancelZone ? Moyu.Palette.accentOnPrimary : Moyu.Palette.textPrimary }

    var body: some View {
        VStack(spacing: Moyu.Space.m) {
            if let toast {
                Image(systemName: "exclamationmark.circle")
                    .font(moyuFont(Moyu.FontSize.display))
                Text(toast)
            } else {
                // 波形条间距复用 xs(4)的一半:比最小间距 token 更紧凑,凑出密集波形的视觉效果。
                HStack(alignment: .center, spacing: Moyu.Space.xs / 2) {
                    ForEach(Array(levels.enumerated()), id: \.offset) { _, level in
                        Capsule()
                            .fill(inCancelZone ? Moyu.Palette.accentOnPrimary : Moyu.Palette.accentPrimary)
                            // 波形条最大高度复用浮层尺寸(voiceOverlay)的一半:留出上下 padding 和文案的空间,
                            // 不为「波形区高度」另开一个 token。
                            .frame(width: Moyu.Space.xs,
                                   height: max(Moyu.Space.xs, level * Moyu.Size.voiceOverlay / 2))
                    }
                }
                .frame(height: Moyu.Size.voiceOverlay / 2)
                if case let .countdown(secondsLeft) = phase {
                    Text(L10n.mediaVoiceSecondsLeft(secondsLeft))
                }
                Text(inCancelZone ? L10n.mediaVoiceReleaseCancel : L10n.mediaVoiceReleaseSendOrCancel)
            }
        }
        .font(moyuFont(Moyu.FontSize.callout))
        .foregroundStyle(foreground)
        .padding(Moyu.Space.m)
        .frame(width: Moyu.Size.voiceOverlay, height: Moyu.Size.voiceOverlay)
        .background(inCancelZone ? Moyu.Palette.statusDanger : Moyu.Palette.surfaceRaised,
                    in: RoundedRectangle(cornerRadius: Moyu.Radius.card, style: .continuous))
        .accessibilityElement(children: .combine)
    }
}
