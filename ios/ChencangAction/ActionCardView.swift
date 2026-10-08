import SwiftUI
import ChencangShared

/// 扩展「陈仓解密」的卡片:解密中 / 解开 / 配对码 / 失败四态。
/// 扩展内存 ≤30MB(spec §7),不做重活。
enum ActionCardState: Equatable {
    case decrypting
    case opened(ActionDecryptor.Opened)
    case pairingCode(PairingCodeKind, wire: String)
    case failed(IntakeFailure)
}

struct ActionCardView: View {
    let state: ActionCardState
    /// 点了拉起主 App 的按钮但没拉起来:卡片不关,补一行手动打开的提示。
    let showManualOpenHint: Bool
    /// 拉起主 App:文字「在陈仓里回复」、媒体「打开陈仓查看」(都开到那条对话)。
    let onOpenThread: () -> Void
    /// 配对码「打开陈仓添加」:控制器先放进交接槽再拉起主 App。
    let onHandOffPairing: () -> Void
    /// 失败卡的下一步(添加联系人 / 打开陈仓)。
    let onNextStep: (IntakeNextStep) -> Void
    let onDone: () -> Void

    init(
        state: ActionCardState,
        showManualOpenHint: Bool = false,
        onOpenThread: @escaping () -> Void = {},
        onHandOffPairing: @escaping () -> Void = {},
        onNextStep: @escaping (IntakeNextStep) -> Void = { _ in },
        onDone: @escaping () -> Void = {}
    ) {
        self.state = state
        self.showManualOpenHint = showManualOpenHint
        self.onOpenThread = onOpenThread
        self.onHandOffPairing = onHandOffPairing
        self.onNextStep = onNextStep
        self.onDone = onDone
    }

    var body: some View {
        VStack(alignment: .leading, spacing: Moyu.Space.m) {
            switch state {
            case .decrypting: decryptingContent
            case let .opened(opened): openedContent(opened)
            case let .pairingCode(kind, _): pairingContent(kind)
            case let .failed(failure): failureContent(failure)
            }
        }
        .padding(Moyu.Space.xl)
        .background(Moyu.Palette.surfaceBase)
    }

    private var decryptingContent: some View {
        HStack(spacing: Moyu.Space.s) {
            ProgressView()
            Text(L10n.actionExtDecrypting)
                .font(moyuFont(Moyu.FontSize.body))
                .foregroundStyle(Moyu.Palette.textSecondary)
        }
        .frame(maxWidth: .infinity)
    }

    private func openedContent(_ opened: ActionDecryptor.Opened) -> some View {
        VStack(alignment: .leading, spacing: Moyu.Space.m) {
            HStack(spacing: Moyu.Space.s) {
                MoyuAvatarView(spec: contactAvatar(displayName: opened.peerName, fingerprintHex: opened.peerId), size: 24)
                Text(L10n.actionExtFrom(opened.peerName))
                    .font(moyuFont(Moyu.FontSize.callout))
                    .foregroundStyle(Moyu.Palette.textSecondary)
            }
            if opened.kind.isMedia {
                mediaSummary(opened)
                actionButton(L10n.actionExtViewInApp, kind: .primary, action: onOpenThread)
                manualOpenHint(L10n.actionExtManualOpenMessage)
                actionButton(L10n.commonDone, kind: .secondary, action: onDone)
            } else {
                // MessageBubbleView 的 peer 气泡样式(左对齐 + hairline 边),
                // 用一条合成的 incoming ChatMessage 复用——不落库,仅供本卡展示。
                MessageBubbleView(message: ChatMessage(
                    id: opened.entryId,
                    peerId: opened.peerId,
                    direction: .incoming,
                    body: opened.text,
                    timestamp: Date(),
                    status: .received,
                    kind: opened.kind
                ))
                actionButton(L10n.commonDone, kind: .primary, action: onDone)
                actionButton(L10n.actionExtReplyInApp, kind: .secondary, action: onOpenThread)
                manualOpenHint(L10n.actionExtManualOpenMessage)
            }
        }
    }

    /// 媒体只在 App 内下载解密(spec §4.2),扩展里只给图标 + 种类标签。
    private func mediaSummary(_ opened: ActionDecryptor.Opened) -> some View {
        HStack(spacing: Moyu.Space.s) {
            Image(systemName: mediaIcon(opened.kind))
                .font(moyuFont(Moyu.FontSize.title))
                .foregroundStyle(Moyu.Palette.accentPrimary)
            Text(opened.kind.displayLabel(count: opened.mediaCount))
                .font(moyuFont(Moyu.FontSize.body))
                .foregroundStyle(Moyu.Palette.textPrimary)
        }
        .padding(Moyu.Space.m)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Moyu.Palette.surfaceRaised, in: RoundedRectangle(cornerRadius: Moyu.Radius.card, style: .continuous))
    }

    private func mediaIcon(_ kind: MessageKind) -> String {
        switch kind {
        case .voice: return "waveform"
        case .video: return "video"
        default: return "photo"
        }
    }

    private func pairingContent(_ kind: PairingCodeKind) -> some View {
        VStack(alignment: .leading, spacing: Moyu.Space.m) {
            Text(L10n.actionExtPairingCodeTitle)
                .font(moyuFont(Moyu.FontSize.title, weight: .semibold))
                .foregroundStyle(Moyu.Palette.textPrimary)
            Text(kind == .invite ? L10n.actionExtPairingInviteBody : L10n.actionExtPairingResponseBody)
                .font(moyuFont(Moyu.FontSize.body))
                .foregroundStyle(Moyu.Palette.textSecondary)
            actionButton(L10n.actionExtOpenAppToAdd, kind: .primary, action: onHandOffPairing)
            manualOpenHint(L10n.actionExtManualOpenPairing)
            actionButton(L10n.commonDone, kind: .secondary, action: onDone)
        }
    }

    private func failureContent(_ failure: IntakeFailure) -> some View {
        VStack(alignment: .leading, spacing: Moyu.Space.m) {
            HStack(alignment: .top, spacing: Moyu.Space.s) {
                Image(systemName: "exclamationmark.triangle.fill")
                    .font(moyuFont(Moyu.FontSize.body))
                    .foregroundStyle(Moyu.Palette.statusDanger)
                Text(failure.message)
                    .font(moyuFont(Moyu.FontSize.body))
                    .foregroundStyle(Moyu.Palette.textPrimary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if let next = failure.nextStep {
                // 失败卡没有写入任何东西,拉不起主 App 时没有「已存进/已交给」可说,不补提示。
                actionButton(nextStepTitle(next), kind: .primary) { onNextStep(next) }
            }
            actionButton(L10n.commonDone, kind: failure.nextStep == nil ? .primary : .secondary, action: onDone)
        }
    }

    private func nextStepTitle(_ step: IntakeNextStep) -> String {
        switch step {
        case .addContact: return L10n.pairingAddContact
        case .openApp: return L10n.intakeActionOpenApp
        }
    }

    @ViewBuilder
    private func manualOpenHint(_ text: String) -> some View {
        if showManualOpenHint {
            Text(text)
                .font(moyuFont(Moyu.FontSize.callout))
                .foregroundStyle(Moyu.Palette.statusWarn)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private enum ButtonKind { case primary, secondary }

    @ViewBuilder
    private func actionButton(_ title: String, kind: ButtonKind, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(moyuFont(Moyu.FontSize.callout, weight: .medium))
                .foregroundStyle(kind == .primary ? Moyu.Palette.accentOnPrimary : Moyu.Palette.textSecondary)
                .multilineTextAlignment(.center)
                .frame(maxWidth: .infinity)
                .padding(.vertical, Moyu.Space.s)
                .background(
                    kind == .primary ? Moyu.Palette.accentPrimary : Moyu.Palette.surfaceRaised,
                    in: RoundedRectangle(cornerRadius: Moyu.Radius.input, style: .continuous)
                )
                .overlay {
                    if kind == .secondary {
                        RoundedRectangle(cornerRadius: Moyu.Radius.input, style: .continuous)
                            .strokeBorder(Moyu.Palette.borderHairline, lineWidth: 1)
                    }
                }
        }
        .buttonStyle(.plain)
    }
}
