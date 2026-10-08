import SwiftUI
import ChencangShared

/// 会话 tab 内容(spec §3.1):每个联系人一行(回应还没发出去的接受方除外),按最新消息时间 / 配对时间倒序;整行点击进会话。
/// 标题与「＋」菜单在 `MainTabView` 的 `TabView` 层。行与空状态由 `ConversationListViewModel` 算好,这里只读。
struct ConversationListView: View {
    @EnvironmentObject private var model: MixinAppModel
    @ObservedObject var vm: ConversationListViewModel

    /// 摘要隐私开关:开时会话列表只显示「已加密」(`conversations_preview_hidden`),不泄露明文摘要(常见于锁屏/
    /// 投屏场景)。键与 App Group 容器约定一致,「我」tab 读写同一个键。
    @AppStorage("cc.summaryPrivacy.v1", store: UserDefaults(suiteName: SharedAppGroupDefaults.suiteName))
    private var summaryPrivacy = false

    var body: some View {
        Group {
            if let empty = vm.emptyState {
                emptyState(empty)
            } else if !vm.rows.isEmpty {
                list
            } else {
                // 数据未加载完:什么都不画,免得冷启动先闪一下错的空状态。
                Color.clear
            }
        }
        .background(Moyu.Palette.surfaceBase)
        .safeAreaInset(edge: .top, spacing: 0) {
            if vm.awaitingCount > 0 { awaitingRow }
        }
    }

    /// 有邀请在等对方回复:点一下切到联系人 tab(那里有「配对中」分区)。
    private var awaitingRow: some View {
        Button { model.selectedTab = .contacts } label: {
            HStack(spacing: Moyu.Space.s) {
                Image(systemName: "hourglass")
                    .foregroundStyle(Moyu.Palette.textSecondary)
                    .accessibilityHidden(true)
                Text(L10n.chatsPendingInvites(vm.awaitingCount))
                    .font(moyuFont(Moyu.FontSize.callout))
                    .foregroundStyle(Moyu.Palette.textPrimary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Image(systemName: "chevron.right")
                    .foregroundStyle(Moyu.Palette.textTertiary)
                    .accessibilityHidden(true)
            }
            .padding(.horizontal, Moyu.Space.l)
            .frame(minHeight: Moyu.Size.touchMin)
            .background(Moyu.Palette.surfaceRaised)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("convlist-awaiting")
    }

    private var list: some View {
        List(vm.rows) { row in
            ConversationRowView(
                row: row,
                summaryPrivacy: summaryPrivacy,
                unsentPrefix: row.last.flatMap { MediaLayout.listPreviewPrefix($0.outgoingMediaStatus(files: model.mediaFiles)) },
                onSelectThread: { model.path.append(.thread(row.contact.id)) }
            )
            .listRowBackground(Moyu.Palette.surfaceBase)
            .listRowSeparatorTint(Moyu.Palette.borderHairline)
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .accessibilityIdentifier("convlist")
    }

    /// 空状态的按钮文字:允许换行,不定死宽度。
    private func emptyButtonLabel(_ title: String) -> some View {
        Text(title)
            .font(moyuFont(Moyu.FontSize.body, weight: .medium))
            .multilineTextAlignment(.center)
            .frame(maxWidth: .infinity)
            .padding(.vertical, Moyu.Space.s)
    }

    /// A(无联系人、无配对中):「添加联系人」(主)+ 一行「收到了邀请?复制整条消息,回来点粘贴」。
    private var startPairingActions: some View {
        let add = Button { model.presentWizard(.initiator) } label: { emptyButtonLabel(L10n.pairingAddContact) }
            .buttonStyle(.borderedProminent)
            .tint(Moyu.Palette.accentPrimary)
            .accessibilityIdentifier("convlist-empty-add")
        return VStack(spacing: Moyu.Space.m) {
            add
            Text(L10n.conversationsEmptyReceivedHint)
                .font(moyuFont(Moyu.FontSize.callout))
                .foregroundStyle(Moyu.Palette.textSecondary)
                .multilineTextAlignment(.center)
                .accessibilityIdentifier("convlist-empty-received-hint")
        }
    }

    /// B(有联系人或配对中、没有消息):「去联系人」。
    private var goToContactsAction: some View {
        Button { model.selectedTab = .contacts } label: { emptyButtonLabel(L10n.conversationsGoToContacts) }
            .buttonStyle(.borderedProminent)
            .tint(Moyu.Palette.accentPrimary)
            .accessibilityIdentifier("convlist-empty-contacts")
    }

    private func emptyState(_ kind: ConversationEmptyState) -> some View {
        VStack(spacing: Moyu.Space.l) {
            Spacer()
            Image(systemName: "seal")
                .font(moyuFont(64))
                .foregroundStyle(Moyu.Palette.accentPrimary)
                .accessibilityHidden(true)
            Text(L10n.conversationsEmptyTitle)
                .font(moyuFont(Moyu.FontSize.display, weight: .semibold))
                .foregroundStyle(Moyu.Palette.textPrimary)
                .multilineTextAlignment(.center)
            Text(kind == .startPairing ? L10n.conversationsEmptyNoContacts : L10n.conversationsEmptyHasContacts)
                .font(moyuFont(Moyu.FontSize.callout))
                .foregroundStyle(Moyu.Palette.textSecondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, Moyu.Space.xl)
            Group {
                switch kind {
                case .startPairing: startPairingActions
                case .goToContacts: goToContactsAction
                }
            }
            .padding(.horizontal, Moyu.Space.xxl)
            Spacer()
            Spacer()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(.top, Moyu.Space.xxl)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("convlist-empty")
    }
}

/// 单行渲染:头像 + 名字/验证印(未核对时后跟「未核对」)+ 摘要 + 相对时间。整行一个点击目标,进会话。
private struct ConversationRowView: View {
    let row: ConversationRow
    let summaryPrivacy: Bool
    /// 最后一条处于「对方还看不到」时的「[未上传] 」前缀(错误色),否则 nil。
    let unsentPrefix: String?
    let onSelectThread: () -> Void

    /// 相对时间跟随系统语言(`Locale.current`),不写死区域。
    private static let relativeFormatter: RelativeDateTimeFormatter = {
        let f = RelativeDateTimeFormatter()
        f.locale = Locale.current
        return f
    }()

    /// 没有时间(老数据里还没有消息的联系人)时为 nil,行尾不显示。
    private var relativeTime: String? {
        row.time.map { Self.relativeFormatter.localizedString(for: $0, relativeTo: Date()) }
    }

    private var summaryText: String {
        switch row.preview {
        case let .message(last): return summaryPrivacy ? L10n.conversationsPreviewHidden : last.summary
        case .waitingPeer: return L10n.conversationsPreviewWaitingPeer
        case .noMessages: return L10n.conversationsPreviewNoMessages
        }
    }

    /// 摘要一行:可选的「[未上传] 」前缀 + 摘要,两段各自着色。用 `AttributedString` 拼,
    /// 不再用已废弃的 `Text.foregroundColor` + `Text` 拼接(iOS 16 上 `Text.foregroundStyle` 不返回 `Text`)。
    private func previewText(_ summary: String) -> AttributedString {
        var prefix = AttributedString(unsentPrefix ?? "")
        // 有意复用:前缀用气泡红 ! 同一个错误色 statusDanger
        prefix.foregroundColor = Moyu.Palette.statusDanger
        var body = AttributedString(summary)
        body.foregroundColor = Moyu.Palette.textSecondary
        return prefix + body
    }

    var body: some View {
        Button(action: onSelectThread) {
            HStack(spacing: Moyu.Space.m) {
                MoyuAvatarView(spec: contactAvatar(displayName: row.contact.displayName, fingerprintHex: row.contact.id), size: Moyu.Size.avatarList)

                VStack(alignment: .leading, spacing: Moyu.Space.xs) {
                    HStack(spacing: Moyu.Space.xs) {
                        Text(row.contact.displayName)
                            .font(moyuFont(Moyu.FontSize.body, weight: .medium))
                            .foregroundStyle(Moyu.Palette.textPrimary)
                            .lineLimit(1)

                        TrustSealIcon(verified: row.contact.isVerified)

                        if !row.contact.isVerified {
                            Text(L10n.verifyStatusUnverified)
                                .font(moyuFont(Moyu.FontSize.caption))
                                .foregroundStyle(Moyu.Palette.textTertiary)
                                .lineLimit(1)
                                // 验证印的读屏标签已是「未核对」,文字不再重复读。
                                .accessibilityHidden(true)
                        }
                    }

                    Text(previewText(summaryText))
                        .font(moyuFont(Moyu.FontSize.callout))
                        .lineLimit(1)
                }

                Spacer(minLength: Moyu.Space.s)

                if let relativeTime {
                    Text(relativeTime)
                        .font(moyuFont(Moyu.FontSize.caption))
                        .foregroundStyle(Moyu.Palette.textTertiary)
                }
            }
            .padding(.vertical, Moyu.Space.xs)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}
