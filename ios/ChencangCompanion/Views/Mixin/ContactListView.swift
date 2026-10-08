import SwiftUI
import ChencangShared

/// 联系人 tab 内容(spec §3.2):第一行固定「添加联系人」→ 二选一;其下是全部联系人(按显示名排序),
/// 整行一个点击区,点行进联系人详情。标题「联系人」在 `MainTabView` 的 `TabView` 层。
/// 排序、「配对中」分区(规 U)、角标与「尚未发出回应的联系人只在配对中」都由 `ContactListViewModel` 算好,这里只读。
/// 删除邀请的确认状态在 VM 里(旋转 / 重建后还在)。本屏不订阅聊天消息存储。
struct ContactListView: View {
    @EnvironmentObject private var model: MixinAppModel
    @ObservedObject var vm: ContactListViewModel

    @State private var shareItem: SharePayload?

    var body: some View {
        List {
            addRow
                .listRowBackground(Moyu.Palette.surfaceBase)
                .listRowSeparatorTint(Moyu.Palette.borderHairline)
            if !vm.pendingRows.isEmpty {
                Section {
                    ForEach(vm.pendingRows) { row in
                        PendingRowView(
                            row: row,
                            onOpen: {
                                model.presentWizard(row.isResponse
                                    ? .resumeResponse(fingerprintHex: row.id)
                                    : .resumeInvite(pairingId: row.id))
                            },
                            onResend: {
                                Task {
                                    if let items = await vm.itemsToShare(for: row, render: { PairingCardImage.make($0) }) {
                                        shareItem = SharePayload(items: items)
                                    }
                                }
                            },
                            onDelete: { vm.requestDelete(row.id) }
                        )
                        .listRowBackground(Moyu.Palette.surfaceBase)
                        .listRowSeparatorTint(Moyu.Palette.borderHairline)
                        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                            if row.canDelete {
                                Button(L10n.commonDelete, role: .destructive) { vm.requestDelete(row.id) }
                            }
                        }
                    }
                } header: {
                    Text(L10n.contactsPending)
                        .font(moyuFont(Moyu.FontSize.callout, weight: .medium))
                        .foregroundStyle(Moyu.Palette.textSecondary)
                        .accessibilityIdentifier("contactlist-pending")
                }
            }
            ForEach(vm.rows) { row in
                ContactRowView(contact: row.contact) { model.path.append(.contact(row.contact.id)) }
                    .listRowBackground(Moyu.Palette.surfaceBase)
                    .listRowSeparatorTint(Moyu.Palette.borderHairline)
            }
            if vm.showEmptyHint {
                Text(L10n.contactsEmpty)
                    .font(moyuFont(Moyu.FontSize.callout))
                    .foregroundStyle(Moyu.Palette.textTertiary)
                    .frame(maxWidth: .infinity)
                    .padding(.top, Moyu.Space.l)
                    .listRowBackground(Moyu.Palette.surfaceBase)
                    .listRowSeparator(.hidden)
                    .accessibilityIdentifier("contactlist-empty")
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .background(Moyu.Palette.surfaceBase)
        .accessibilityIdentifier("contactlist")
        // 进入联系人 tab 时按当前时间重算一次;回前台的重算在 `MainTabContent`(tab 内容是惰性创建的,停在别的 tab 时这里收不到)。
        .onAppear { vm.refresh() }
        .sheet(item: $shareItem) { item in
            ActivityView(items: item.items)
        }
        // 弹窗收起会先把 `deleteCandidate` 置空,所以把当时的 id 经 `presenting` 带进按钮闭包。
        .confirmationDialog(
            L10n.pairingDeleteTitle,
            isPresented: Binding(get: { vm.deleteCandidate != nil }, set: { if !$0 { vm.cancelDelete() } }),
            titleVisibility: .visible,
            presenting: vm.deleteCandidate
        ) { id in
            Button(L10n.commonDelete, role: .destructive) { Task { await vm.confirmDelete(id) } }
            Button(L10n.commonCancel, role: .cancel) {}
        } message: { _ in
            Text(L10n.pairingDeleteBody)
        }
        .alert(vm.actionError ?? "", isPresented: Binding(
            get: { vm.actionError != nil },
            set: { if !$0 { vm.actionError = nil } }
        )) {
            Button(L10n.commonOk) {}
        }
    }

    private var addRow: some View {
        Button {
            model.presentWizard(.initiator)
        } label: {
            HStack(spacing: Moyu.Space.m) {
                RoundedRectangle(cornerRadius: Moyu.Radius.input, style: .continuous)
                    .fill(Moyu.Palette.accentPrimary)
                    .frame(width: Moyu.Size.avatarList, height: Moyu.Size.avatarList)
                    .overlay(
                        Image(systemName: "plus")
                            .font(moyuFont(Moyu.FontSize.title, weight: .medium))
                            .foregroundStyle(Moyu.Palette.accentOnPrimary)
                    )
                Text(L10n.pairingAddContact)
                    .font(moyuFont(Moyu.FontSize.body, weight: .medium))
                    .foregroundStyle(Moyu.Palette.textPrimary)
                Spacer(minLength: 0)
            }
            .frame(minHeight: Moyu.Size.avatarList + 2 * Moyu.Space.m)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("contactlist-add")
    }
}

/// 联系人行:头像 + 名字 + 验证印(此处不可单独点击,整行一个点击区)。
/// 合并为一个可访问元素,读作「〈名字〉,已核对 / 未核对」;合并语义下标识显式挂在这个元素上,UI 测试仍按 id 找得到。
private struct ContactRowView: View {
    let contact: AppGroupContact
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: Moyu.Space.m) {
                MoyuAvatarView(spec: contactAvatar(displayName: contact.displayName, fingerprintHex: contact.id), size: Moyu.Size.avatarList)
                Text(contact.displayName)
                    .font(moyuFont(Moyu.FontSize.body, weight: .medium))
                    .foregroundStyle(Moyu.Palette.textPrimary)
                    .lineLimit(1)
                TrustSealIcon(verified: contact.isVerified)
                Spacer(minLength: 0)
            }
            .frame(minHeight: Moyu.Size.avatarList + 2 * Moyu.Space.m)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(L10n.contactsRowCd(contact.displayName, state: contact.isVerified ? L10n.verifyStatusVerified : L10n.verifyStatusUnverified))
        .accessibilityAddTraits(.isButton)
        .accessibilityIdentifier("contactlist-row-\(contact.id)")
    }
}

private struct SharePayload: Identifiable {
    let id = UUID()
    let items: [Any]
}

/// 「配对中」的一行。左侧整块(头像 + 两行字)是一个可访问元素,读作「〈标题〉,〈状态〉,〈时间〉」,点击 = 以恢复入口打开向导,
/// 邀请行另有「删除」自定义操作;行尾文字按钮是独立的控件(直接弹系统分享面板,发同一份暗号)。
/// 合并语义下 label 与标识显式写在同一个元素上。
private struct PendingRowView: View {
    let row: PendingRow
    let onOpen: () -> Void
    let onResend: () -> Void
    let onDelete: () -> Void

    var body: some View {
        HStack(spacing: Moyu.Space.s) {
            Button(action: onOpen) {
                HStack(spacing: Moyu.Space.m) {
                    leading
                    VStack(alignment: .leading, spacing: Moyu.Space.xs) {
                        Text(row.title)
                            .font(moyuFont(Moyu.FontSize.body, weight: .medium))
                            .foregroundStyle(row.titleIsPlaceholder ? Moyu.Palette.textSecondary : Moyu.Palette.textPrimary)
                            .lineLimit(1)
                        Text(row.subtitle)
                            .font(moyuFont(Moyu.FontSize.callout))
                            .foregroundStyle(subtitleColor)
                            .lineLimit(1)
                    }
                    Spacer(minLength: 0)
                }
                .frame(minHeight: Moyu.Size.avatarList + 2 * Moyu.Space.m)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(row.accessibilityLabel)
            .accessibilityAddTraits(.isButton)
            .accessibilityIdentifier(rowIdentifier)
            .modifier(DeleteActionModifier(enabled: row.canDelete, onDelete: onDelete))

            if let label = row.resendLabel {
                Button(label, action: onResend)
                    .font(moyuFont(Moyu.FontSize.callout, weight: .medium))
                    .foregroundStyle(Moyu.Palette.accentPrimary)
                    .buttonStyle(.plain)
                    .accessibilityIdentifier(resendIdentifier)
            }
        }
    }

    @ViewBuilder private var leading: some View {
        if let avatar = row.avatar {
            MoyuAvatarView(spec: avatar, size: Moyu.Size.avatarList)
        } else {
            // 空心印占位:还不是联系人,不给实心头像。
            Circle()
                .strokeBorder(Moyu.Palette.borderHairline, lineWidth: Moyu.Space.xs / 4) // 有意复用:发丝线宽
                .frame(width: Moyu.Size.avatarList, height: Moyu.Size.avatarList)
                .overlay(
                    Image(systemName: "seal")
                        .font(moyuFont(Moyu.FontSize.title))
                        .foregroundStyle(Moyu.Palette.textTertiary)
                )
        }
    }

    private var subtitleColor: Color {
        switch row.tone {
        case .warn: return Moyu.Palette.statusWarn
        case .secondary: return Moyu.Palette.textSecondary
        case .tertiary: return Moyu.Palette.textTertiary
        }
    }

    private var rowIdentifier: String {
        row.isResponse ? "contactlist-pending-response-\(row.id)" : "contactlist-pending-row-\(row.id)"
    }

    private var resendIdentifier: String {
        row.isResponse ? "contactlist-pending-sendback-\(row.id)" : "contactlist-pending-resend-\(row.id)"
    }
}

/// 读屏走自定义操作「删除」(只有邀请行有)。
private struct DeleteActionModifier: ViewModifier {
    let enabled: Bool
    let onDelete: () -> Void

    func body(content: Content) -> some View {
        if enabled {
            content.accessibilityAction(named: L10n.commonDelete, onDelete)
        } else {
            content
        }
    }
}
