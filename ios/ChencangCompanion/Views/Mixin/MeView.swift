import SwiftUI
import ChencangShared

/// 「我」tab(spec §3.4):承接原设置页全部分组,顺序 隐私 → 身份(仅 DEBUG)→ 关于 → 危险区 → 开发者(仅 DEBUG)。
/// 不再是独立路由;标题「我」挂在 `MainTabView` 的 `TabView` 层。顶部是资料卡(昵称 + 印章头像,`ProfileCardSection`)。分组卡片:`List` + `Section`,卡片底 `surfaceRaised`、区头 caption `textSecondary`。
struct MeView: View {
    @EnvironmentObject private var identityStore: IdentityStore
    @EnvironmentObject private var model: MixinAppModel

    /// 摘要隐私开关:与 `ConversationListView` 读写同一个键(切回会话 tab 立即生效)。
    @AppStorage("cc.summaryPrivacy.v1", store: UserDefaults(suiteName: SharedAppGroupDefaults.suiteName))
    private var summaryPrivacy = false

    /// 「我」资料(只在本机,App Group):卡片、头像弹层、改名弹层共用同一份 `@AppStorage`,不另开第二条读取来源。
    @AppStorage(MyProfileKeys.name, store: UserDefaults(suiteName: SharedAppGroupDefaults.suiteName))
    private var myName = ""
    @AppStorage(MyProfileKeys.avatarGlyph, store: UserDefaults(suiteName: SharedAppGroupDefaults.suiteName))
    private var avatarGlyph = ""
    @AppStorage(MyProfileKeys.avatarColor, store: UserDefaults(suiteName: SharedAppGroupDefaults.suiteName))
    private var avatarColor: Int?
    /// 本机指纹只在身份变化时算一次(不放进 body)。
    @State private var fingerprintHex: String?
    @State private var showAvatarEditor = false
    @State private var showNameEditor = false

    @State private var showDeleteConfirm = false
    /// 全清任一步失败时置位:停在「我」tab(不进 onboarding、不重置导航栈),
    /// 让用户能重试——身份若已被删掉而其它状态仍残留,重试路径依然可走。
    @State private var showWipeFailedAlert = false
    @State private var showSource = false

    #if DEBUG
    @State private var uatStatus: String = ""
    @State private var uatBusy = false
    #endif

    var body: some View {
        List {
            ProfileCardSection(
                avatar: myAvatar(storedGlyph: avatarGlyph, storedColor: avatarColor, myName: myName, myFingerprintHex: fingerprintHex),
                myName: myName,
                onEditAvatar: { showAvatarEditor = true },
                onEditName: { showNameEditor = true }
            )
            privacySection
            #if DEBUG
            identitySection
            #endif
            aboutSection
            dangerSection
            #if DEBUG
            developerSection
            #endif
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(Moyu.Palette.surfaceBase)
        .confirmationDialog(
            L10n.settingsEraseConfirmTitle,
            isPresented: $showDeleteConfirm,
            titleVisibility: .visible
        ) {
            Button(L10n.settingsEraseConfirm, role: .destructive) {
                Task { await wipeAll() }
            }
            Button(L10n.commonCancel, role: .cancel) {}
        } message: {
            Text(L10n.settingsEraseConfirmBody)
        }
        .alert(L10n.meEraseIncompleteTitle, isPresented: $showWipeFailedAlert) {
            Button(L10n.commonOk) {}
        } message: {
            Text(L10n.meEraseIncompleteBody)
        }
        .sheet(isPresented: $showSource) {
            SafariView(url: AppInfo.sourceURL(site: ConfigRepository.shared.current().shareSite)).ignoresSafeArea()
        }
        .sheet(isPresented: $showAvatarEditor) {
            AvatarEditorSheet(glyph: $avatarGlyph, color: $avatarColor, myName: myName, fingerprintHex: fingerprintHex)
        }
        .sheet(isPresented: $showNameEditor) {
            NameEditorSheet(name: $myName)
        }
        .onReceive(identityStore.$identity) { identity in
            let next = identity.map(InbandPairing.localFingerprintHex)
            if fingerprintHex != next { fingerprintHex = next }
        }
    }

    // MARK: - 隐私

    private var privacySection: some View {
        Section {
            Toggle(isOn: $summaryPrivacy) {
                settingsRow(title: L10n.settingsHideSummary, subtitle: L10n.settingsHideSummaryHint)
            }
            .tint(Moyu.Palette.accentPrimary)
        } header: {
            sectionHeader(L10n.settingsPrivacy)
        }
        .listRowBackground(Moyu.Palette.surfaceRaised)
    }

    #if DEBUG
    // MARK: - 身份(仅 DEBUG;助记词导出/恢复的真实现是独立的 core plan,
    // release 不露占位——与 Android 同策略)

    private var identitySection: some View {
        Section {
            NavigationLink {
                ExportMnemonicView()
            } label: {
                settingsRow(title: "Export recovery phrase", subtitle: "Restore your identity on a new device")
            }
            NavigationLink {
                ImportMnemonicView()
            } label: {
                settingsRow(title: "Restore identity", subtitle: "From a recovery phrase")
            }
        } header: {
            sectionHeader("Identity")
        }
        .listRowBackground(Moyu.Palette.surfaceRaised)
    }
    #endif

    // MARK: - 关于

    private var aboutSection: some View {
        Section {
            HStack {
                settingsRow(title: L10n.settingsVersion)
                Spacer()
                Text(AppInfo.versionText(info: Bundle.main.infoDictionary))
                    .font(moyuFont(Moyu.FontSize.callout))
                    .foregroundStyle(Moyu.Palette.textSecondary)
            }
            Button {
                showSource = true
            } label: {
                settingsRow(title: L10n.settingsMediaRelay, subtitle: L10n.settingsMediaRelayHint)
            }
        } header: {
            sectionHeader(L10n.settingsAbout)
        }
        .listRowBackground(Moyu.Palette.surfaceRaised)
    }

    // MARK: - 危险区

    private var dangerSection: some View {
        Section {
            Button(role: .destructive) {
                showDeleteConfirm = true
            } label: {
                settingsRow(
                    title: L10n.meEraseAll,
                    titleColor: Moyu.Palette.statusDanger,
                    subtitle: L10n.settingsEraseHint
                )
            }
        } header: {
            sectionHeader(L10n.settingsDanger)
        }
        .listRowBackground(Moyu.Palette.surfaceRaised)
    }

    #if DEBUG
    // MARK: - 开发者(仅 DEBUG;原 ContactListView 工具栏「🔨」菜单迁移至此)

    private var developerSection: some View {
        Section {
            Button {
                runUat {
                    try await UATLoopback.seed()
                    return "✅ Seed done (UAT contact + DR session created)"
                }
            } label: {
                settingsRow(title: "Seed test contact", subtitle: "Writes a UAT contact + DR session")
            }
            .disabled(uatBusy)

            Button {
                runUat { try await UATLoopback.runE2E() }
            } label: {
                settingsRow(title: "Run E2E encrypt/decrypt", subtitle: "Loopback encrypt → decrypt on this device")
            }
            .disabled(uatBusy)

            if !uatStatus.isEmpty {
                Text(uatStatus)
                    .font(moyuFont(Moyu.FontSize.caption))
                    .foregroundStyle(Moyu.Palette.textSecondary)
            }
        } header: {
            sectionHeader("Developer")
        }
        .listRowBackground(Moyu.Palette.surfaceRaised)
    }

    private func runUat(_ block: @escaping () async throws -> String) {
        uatBusy = true
        uatStatus = "Running…"
        Task { @MainActor in
            do {
                uatStatus = try await block()
            } catch {
                uatStatus = "❌ Self-test failed, try again"
            }
            uatBusy = false
        }
    }
    #endif

    // MARK: - 行 / 区头样式

    private func sectionHeader(_ title: String) -> some View {
        Text(title)
            .font(moyuFont(Moyu.FontSize.caption))
            .foregroundStyle(Moyu.Palette.textSecondary)
    }

    private func settingsRow(
        title: String,
        titleColor: Color = Moyu.Palette.textPrimary,
        subtitle: String? = nil
    ) -> some View {
        VStack(alignment: .leading, spacing: Moyu.Space.xs) {
            Text(title)
                .font(moyuFont(Moyu.FontSize.body))
                .foregroundStyle(titleColor)
            if let subtitle {
                Text(subtitle)
                    .font(moyuFont(Moyu.FontSize.callout))
                    .foregroundStyle(Moyu.Palette.textSecondary)
            }
        }
        .padding(.vertical, Moyu.Space.xs)
    }

    // MARK: - 危险区编排:全清账号

    /// 全清编排(危险区「删除账号」):清身份、联系人、消息、收件箱、会话内存
    /// 缓存,以及 App Group 里待完成的配对状态。实际的 best-effort 步骤序列
    /// 在 `AccountWiper`(ChencangShared,可测)里——
    /// 这里只负责按结果决定 UI 反应:
    ///
    /// - 全部成功:清空导航栈并把用户送回 onboarding。身份已被删除,若不显式
    ///   翻 `model.showOnboarding`,用户会停在一个没有身份的空会话列表上——
    ///   `RootView` 的闸门是 `showOnboarding`,不是 `identity == nil`
    ///   (Task 8 review 定案,见 `MixinAppModel` 文档注释)。
    /// - 任一步失败:**不**进 onboarding、**不**重置导航栈——用户留在「我」tab,
    ///   弹出「抹除未完全成功」提示,可以重试(review 裁决:此前每步 `try?`
    ///   吞错、无条件当作成功处理,会出现「身份删除失败但联系人/消息已清」
    ///   却仍把用户送进 onboarding 的假成功场景)。
    ///
    /// Keychain 里的 `sess_*` 条目(不存在 `session_secret_*` 这个键,早前的
    /// 说法已核实为误传):逐联系人删除走 `SessionStore.remove(for:)`,已在
    /// 联系人页「删除联系人」路径(`ContactView.deleteContact`,M4 Task 8/9)
    /// 落地;这里全清账号调的是 `sessionStore.invalidateCache()`,只失效内存
    /// 缓存——身份和联系人已经一起被删掉,不会再有用户可达路径引用这些残留
    /// 条目。完整的清除步骤序列以 `AccountWiper.wipeAll()` 的 8 步为准。
    private func wipeAll() async {
        let wiper = AccountWiper(
            identityStore: identityStore,
            contactsStore: ContactsStore.shared,
            chatStore: model.chatStore,
            mediaFiles: model.mediaFiles,
            inbox: model.inbox,
            sessionStore: SessionStore.shared,
            clearPairingState: { try await PairingCoordinator.makeDefault().clearAllPending() },
            profileDefaults: UserDefaults(suiteName: SharedAppGroupDefaults.suiteName)
        )
        // 先取消全部后台上传:别让系统还在读随后被删的 `.cca`。
        await model.uploader.cancelAll()
        let result = await wiper.wipeAll()
        if result.allSucceeded {
            model.apply(ShellNav.accountWiped())
            model.showOnboarding = true
        } else {
            showWipeFailedAlert = true
        }
    }
}

/// 「我」资料卡(spec §3.4):印章头像 + 昵称两个独立点击区。纯展示,值与弹层由 `MeView` 持有;本屏不订阅 `ChatStore`。
struct ProfileCardSection: View {
    let avatar: AvatarSpec
    let myName: String
    let onEditAvatar: () -> Void
    let onEditName: () -> Void

    var body: some View {
        Section {
            HStack(spacing: Moyu.Space.l) {
                Button(action: onEditAvatar) {
                    MoyuAvatarView(spec: avatar, size: Moyu.Size.avatarProfile)
                }
                .buttonStyle(.plain)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(L10n.meAvatarCd)
                .accessibilityAddTraits(.isButton)
                .accessibilityIdentifier("me-avatar")

                Button(action: onEditName) {
                    VStack(alignment: .leading, spacing: Moyu.Space.xs) {
                        HStack(spacing: Moyu.Space.s) {
                            Text(myName.isEmpty ? L10n.meNameUnset : myName)
                                .font(moyuFont(Moyu.FontSize.title, weight: .medium))
                                .foregroundStyle(myName.isEmpty ? Moyu.Palette.textTertiary : Moyu.Palette.textPrimary)
                                .lineLimit(1)
                            Image(systemName: "pencil")
                                .font(moyuFont(Moyu.FontSize.callout))
                                .foregroundStyle(Moyu.Palette.textTertiary)
                        }
                        Text(L10n.meNameSubtitle)
                            .font(moyuFont(Moyu.FontSize.callout))
                            .foregroundStyle(Moyu.Palette.textSecondary)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(myName.isEmpty ? L10n.meNameUnsetShort : L10n.meNameCd(myName))
                .accessibilityAddTraits(.isButton)
                .accessibilityIdentifier("me-name")
            }
            .padding(Moyu.Space.l)
            .listRowInsets(EdgeInsets())
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("me-profile")
        }
        .listRowBackground(Moyu.Palette.surfaceRaised)
    }
}
