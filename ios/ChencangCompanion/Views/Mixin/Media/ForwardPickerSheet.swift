import SwiftUI
import ChencangShared

/// 长按「转发」的联系人选择(R9):选中后由调用方对该联系人重新封缄。
/// 排除当前线程所在的联系人(审查修复 7)——转发给消息本来就在的这个人没有意义。
struct ForwardPickerSheet: View {
    let excludingPeerId: String
    let onPick: (String) -> Void

    @ObservedObject private var contactsStore = ContactsStore.shared
    @Environment(\.dismiss) private var dismiss

    private var contacts: [AppGroupContact] {
        contactsStore.contacts.filter { $0.id != excludingPeerId }
    }

    var body: some View {
        NavigationStack {
            List(contacts, id: \.id) { contact in
                Button {
                    dismiss()
                    onPick(contact.id)
                } label: {
                    HStack(spacing: Moyu.Space.m) {
                        // spec §4.2 组件级字面量:28pt 与线程标题栏头像同尺寸(沿用 ConversationThreadView 的写法)
                        MoyuAvatarView(spec: contactAvatar(displayName: contact.displayName, fingerprintHex: contact.id), size: Moyu.Size.avatarInline)
                        Text(contact.displayName)
                            .font(moyuFont(Moyu.FontSize.body))
                            .foregroundStyle(Moyu.Palette.textPrimary)
                    }
                }
                .listRowBackground(Moyu.Palette.surfaceRaised)
            }
            .scrollContentBackground(.hidden)
            .background(Moyu.Palette.surfaceBase)
            .navigationTitle(L10n.mediaForwardTo)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L10n.commonCancel) { dismiss() }
                }
            }
        }
    }
}
