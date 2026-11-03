import SwiftUI
import ChencangShared

/// 我 → 已拉黑的联系人:点一行进联系人页(那里也能解除),行尾「解除拉黑」直接解除。
/// 解除只去掉标记,对话与历史消息原样回到会话 tab。
struct BlockedContactsView: View {
    @EnvironmentObject private var model: MixinAppModel
    @ObservedObject private var contactsStore = ContactsStore.shared

    var body: some View {
        Group {
            if contactsStore.blockedContacts.isEmpty {
                Text(L10n.blockedEmpty)
                    .font(moyuFont(Moyu.FontSize.body))
                    .foregroundStyle(Moyu.Palette.textSecondary)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                List(contactsStore.blockedContacts, id: \.id) { contact in
                    row(contact)
                        .listRowBackground(Moyu.Palette.surfaceRaised)
                }
                .listStyle(.insetGrouped)
                .scrollContentBackground(.hidden)
            }
        }
        .background(Moyu.Palette.surfaceBase)
        .navigationTitle(L10n.settingsBlockedContacts)
        .navigationBarTitleDisplayMode(.inline)
    }

    private func row(_ contact: AppGroupContact) -> some View {
        HStack(spacing: Moyu.Space.m) {
            Button {
                model.path.append(.contact(contact.id))
            } label: {
                HStack(spacing: Moyu.Space.m) {
                    MoyuAvatarView(spec: contactAvatar(displayName: contact.displayName, fingerprintHex: contact.id), size: Moyu.Size.avatarInline)
                    Text(contact.displayName)
                        .font(moyuFont(Moyu.FontSize.body))
                        .foregroundStyle(Moyu.Palette.textPrimary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.borderless)
            Button(L10n.contactsUnblockRow) {
                Task { try? await contactsStore.setBlocked(contactId: contact.id, false) }
            }
            .font(moyuFont(Moyu.FontSize.callout, weight: .medium))
            .foregroundStyle(Moyu.Palette.accentPrimary)
            .buttonStyle(.borderless)
            .accessibilityIdentifier("blocked-unblock-\(contact.id)")
        }
    }
}
