import SwiftUI
import ChencangShared

/// 联系人页(spec §5.6):头像 + 名字(可改) + 验证印大态 + 「重新对印」「清空
/// 消息」「删除联系人」。替代退役的 `FingerprintVerifyView`——路由 `.contact(id)`
/// 目的地(`ChencangCompanionApp.swift`)不变,只是内容全面换血。结构/文案与
/// Android `ContactScreen`(Task 5)逐一对齐。
///
/// 「删除联系人」复用 `PairingWizardViewModel.rejectMismatch()`(Task 8)那套
/// 清除顺序——联系人 / DR 会话(内存+Keychain)/ 本地会话消息。iOS 的
/// `peerId` 就是经典指纹本身(`AppGroupContact.id`),不像 Android 那样要另查
/// username,所以这里不需要一个专门的 ViewModel 去桥接两种 id——直接对
/// `contactId` 操作即可。iOS 也没有 Android「view model 发 `deleted` 事件、nav
/// host 收」的分工,删除编排和 `model.path = []` 都在这个 View 自己的方法里
/// 做完。
///
/// M4 bugfix round 1(2026-09):这里曾经还有第四步——删一个
/// `"session_secret_\(contactId)"` Keychain 键,当时以为验证印表情是从它派生
/// 的。溯源后发现这个键在整个代码库历史上从未被写入过:验证印表情其实是
/// `PairedContact.emoji`,配对成功那一刻就直接存进了 `AppGroupContact.emoji`
/// 字段(`ContactsStore.safetyEmoji(for:)` 现在直接读这个字段)。删掉整条
/// 联系人记录(下面第一步)已经把这份 emoji 一并清掉,不需要再单独删一个从
/// 未存在过的 Keychain 键。
struct ContactView: View {
    let contactId: String

    @EnvironmentObject private var model: MixinAppModel
    @ObservedObject private var contactsStore = ContactsStore.shared
    /// 订阅回应记录:收到对方第一条消息后记录被清掉,「再发一次回应暗号」要随之消失,不能只在进页面时算一次。
    @ObservedObject private var responses = PairingResponseStore.shared

    @State private var emojis: [String] = []
    @State private var renaming = false
    @State private var renameText = ""
    @State private var confirmClear = false
    @State private var confirmDelete = false
    @State private var shareWire: ShareWire?

    private var contact: AppGroupContact? {
        contactsStore.contacts.first { $0.id == contactId }
    }

    var body: some View {
        ScrollView {
            VStack(spacing: Moyu.Space.xl) {
                header
                Button {
                    model.apply(ShellNav.sendMessage(from: ShellState(tab: model.selectedTab, path: model.path), contactId: contactId))
                } label: {
                    Text(L10n.contactsMessage)
                        .font(moyuFont(Moyu.FontSize.body, weight: .medium))
                        .foregroundStyle(Moyu.Palette.accentOnPrimary)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, Moyu.Space.m)
                        .background(Moyu.Palette.accentPrimary, in: RoundedRectangle(cornerRadius: Moyu.Radius.input, style: .continuous))
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("contact-message")
                if let response = resendableResponse(for: contactId, in: responses.records) {
                    resendResponse(response)
                }
                TrustSealCard(
                    emojis: emojis,
                    verified: contact?.isVerified == true,
                    onConfirm: { Task { await markVerified() } }
                )
                ActionsGroup(
                    onClear: { confirmClear = true },
                    onDelete: { confirmDelete = true }
                )
            }
            .padding(Moyu.Space.xl)
        }
        .background(Moyu.Palette.surfaceBase)
        .navigationTitle(L10n.contactsDetailTitle)
        .navigationBarTitleDisplayMode(.inline)
        .task(id: contactId) { await loadEmojis() }
        .alert(L10n.contactsRenameLabel, isPresented: $renaming) {
            TextField(L10n.contactsNameField, text: $renameText)
            Button(L10n.commonSave) { Task { await rename() } }
            Button(L10n.commonCancel, role: .cancel) {}
        }
        .confirmationDialog(L10n.contactsClearTitle, isPresented: $confirmClear, titleVisibility: .visible) {
            Button(L10n.contactsClear, role: .destructive, action: clearMessages)
            Button(L10n.commonCancel, role: .cancel) {}
        } message: {
            Text(L10n.contactsClearBody)
        }
        .confirmationDialog(L10n.contactsDeleteTitle, isPresented: $confirmDelete, titleVisibility: .visible) {
            Button(L10n.commonDelete, role: .destructive) { Task { await deleteContact() } }
            Button(L10n.commonCancel, role: .cancel) {}
        } message: {
            Text(L10n.contactsDeleteBody)
        }
    }

    // MARK: - 再发一次我的配对码

    /// 我的配对码已分享过、对方还没来过消息时保留的重发入口(spec §3.5.6):点击 → 系统分享面板,发同一份回应。
    private func resendResponse(_ response: PairingResponseRecord) -> some View {
        VStack(spacing: Moyu.Space.xs) {
            Button {
                let share = PairingShare(wire: response.responseWire, isResponse: true)
                shareWire = ShareWire(items: [PairingCardImage.make(share) ?? share.text])
            } label: {
                Text(L10n.pairingResend)
                    .font(moyuFont(Moyu.FontSize.body, weight: .medium))
                    .foregroundStyle(Moyu.Palette.accentPrimary)
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("contact-resend-response")
            Text(L10n.contactsResendCodeHint)
                .font(moyuFont(Moyu.FontSize.callout))
                .foregroundStyle(Moyu.Palette.textSecondary)
                .multilineTextAlignment(.center)
        }
        .sheet(item: $shareWire) { item in
            ActivityView(items: item.items)
        }
    }

    // MARK: - 头部

    private var header: some View {
        VStack(spacing: Moyu.Space.m) {
            MoyuAvatarView(spec: contactAvatar(displayName: contact?.displayName ?? "…", fingerprintHex: contact?.id ?? ""), size: Moyu.Size.avatarProfile)

            HStack(spacing: Moyu.Space.xs) {
                Text(contact?.displayName ?? "…")
                    .font(moyuFont(Moyu.FontSize.title, weight: .semibold))
                    .foregroundStyle(Moyu.Palette.textPrimary)

                Button {
                    renameText = contact?.displayName ?? ""
                    renaming = true
                } label: {
                    Image(systemName: "pencil")
                        .foregroundStyle(Moyu.Palette.textSecondary)
                }
                .accessibilityLabel(L10n.contactsRenameLabel)
            }
        }
    }

    // MARK: - 动作

    private func loadEmojis() async {
        emojis = (try? await contactsStore.safetyEmoji(for: contactId)) ?? []
    }

    private func rename() async {
        try? await contactsStore.rename(contactId: contactId, to: renameText)
    }

    private func markVerified() async {
        try? await contactsStore.markVerified(contactId: contactId)
    }

    private func clearMessages() {
        Task { try? await model.threadCleaner.clear(peerId: contactId) }
    }

    private func deleteContact() async {
        try? await contactsStore.remove(contactId: contactId)
        await SessionStore.shared.remove(for: contactId)
        // 联系人没了,为他留着的可重发回应暗号不应再出现在「配对中」。走编排层(闸内串行),不直接动 store。
        do { try await model.pairing.forgetPeer(fingerprintHex: contactId) } catch {
            NSLog("CCCHAT 删除联系人后清回应记录失败:\(error)")
        }
        try? await model.threadCleaner.clear(peerId: contactId)
        model.apply(ShellNav.contactDeleted(from: ShellState(tab: model.selectedTab, path: model.path)))
    }
}

private struct ShareWire: Identifiable {
    let id = UUID()
    let items: [Any]
}

/// 验证印大态卡:说明两行 + [EmojiSealGridView] + 状态行(已核对/未核对);未核对时才有「我核对过了,一致」。
private struct TrustSealCard: View {
    let emojis: [String]
    let verified: Bool
    let onConfirm: () -> Void

    var body: some View {
        VStack(spacing: Moyu.Space.l) {
            VStack(spacing: Moyu.Space.xs) {
                Text(L10n.verifyExplainCompare)
                    .font(moyuFont(Moyu.FontSize.callout))
                    .foregroundStyle(Moyu.Palette.textPrimary)
                Text(L10n.verifyExplainMeaning)
                    .font(moyuFont(Moyu.FontSize.callout))
                    .foregroundStyle(Moyu.Palette.textSecondary)
            }
            .multilineTextAlignment(.center)

            EmojiSealGridView(emojis: emojis)

            HStack(spacing: Moyu.Space.xs) {
                Image(systemName: verified ? "checkmark.seal.fill" : "shield")
                    .foregroundStyle(verified ? Moyu.Palette.accentPrimary : Moyu.Palette.statusWarn)
                Text(verified ? L10n.verifyStatusVerified : L10n.verifyStatusUnverified)
                    .font(moyuFont(Moyu.FontSize.callout, weight: .medium))
                    .foregroundStyle(verified ? Moyu.Palette.accentPrimary : Moyu.Palette.statusWarn)
            }

            if !verified {
                Button(action: onConfirm) {
                    Text(L10n.verifyConfirmDone).frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .tint(Moyu.Palette.accentPrimary)
            }
        }
        .padding(Moyu.Space.xl)
        .frame(maxWidth: .infinity)
        .background(Moyu.Palette.surfaceRaised, in: RoundedRectangle(cornerRadius: Moyu.Radius.card, style: .continuous))
    }
}

/// 操作组:清空消息 / 删除联系人(danger)。
private struct ActionsGroup: View {
    let onClear: () -> Void
    let onDelete: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            actionRow(
                title: L10n.contactsClearRow,
                subtitle: L10n.contactsClearRowHint,
                titleColor: Moyu.Palette.textPrimary,
                action: onClear
            )
            Rectangle()
                .fill(Moyu.Palette.borderHairline)
                .frame(height: 1)
            actionRow(
                title: L10n.contactsDeleteRow,
                subtitle: L10n.contactsDeleteRowHint,
                titleColor: Moyu.Palette.statusDanger,
                action: onDelete
            )
        }
        .background(Moyu.Palette.surfaceRaised, in: RoundedRectangle(cornerRadius: Moyu.Radius.card, style: .continuous))
    }

    private func actionRow(
        title: String,
        subtitle: String,
        titleColor: Color,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: Moyu.Space.xs) {
                Text(title)
                    .font(moyuFont(Moyu.FontSize.body))
                    .foregroundStyle(titleColor)
                Text(subtitle)
                    .font(moyuFont(Moyu.FontSize.callout))
                    .foregroundStyle(Moyu.Palette.textSecondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(Moyu.Space.m)
        }
        .buttonStyle(.plain)
    }
}
