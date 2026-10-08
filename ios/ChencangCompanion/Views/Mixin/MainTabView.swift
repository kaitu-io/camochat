import SwiftUI
import UIKit
import ChencangShared

/// 三 tab 根(spec §3、§4.1):系统 `TabView` + `.tabItem`,作为唯一 `NavigationStack` 的根。
/// 标题与顶栏按钮挂在 `TabView` 这一层、随 `selectedTab` 切换,三屏一律 `.inline`;
/// 不把 `navigationTitle` 留在各 tab 内容里靠偏好冒泡。「＋」只在会话 tab 出现。
struct MainTabView: View {
    @EnvironmentObject private var model: MixinAppModel

    init() {
        _ = Self.configureAppearance
    }

    var body: some View {
        // 角标要订阅联系人列表 VM(`model.contactList` 是 `let`,不会触发本视图刷新),所以内容放进持有 `@ObservedObject` 的子视图。
        MainTabContent(contactList: model.contactList)
    }

    /// tab 栏外观:底 `surfaceRaised`、上沿 `borderHairline`、选中 `accentPrimary`、未选 `textSecondary`;
    /// 待办角标 = `accentPrimary` 底、`accentOnPrimary` 字(不用系统红,spec §3.5.5)。
    /// 只配一次(UIKit appearance 代理对之后创建的 tab 栏生效)。
    private static let configureAppearance: Void = {
        let appearance = UITabBarAppearance()
        appearance.configureWithOpaqueBackground()
        appearance.backgroundColor = UIColor(Moyu.Palette.surfaceRaised)
        appearance.shadowColor = UIColor(Moyu.Palette.borderHairline)
        let normal = UIColor(Moyu.Palette.textSecondary)
        let selected = UIColor(Moyu.Palette.accentPrimary)
        let badgeFill = UIColor(Moyu.Palette.accentPrimary)
        let badgeText = UIColor(Moyu.Palette.accentOnPrimary)
        for item in [appearance.stackedLayoutAppearance, appearance.inlineLayoutAppearance, appearance.compactInlineLayoutAppearance] {
            item.normal.iconColor = normal
            item.normal.titleTextAttributes = [.foregroundColor: normal]
            item.selected.iconColor = selected
            item.selected.titleTextAttributes = [.foregroundColor: selected]
            // 三种布局的 normal 与 selected 都要设,否则某个布局 / 选中态会落回系统红。
            for state in [item.normal, item.selected] {
                state.badgeBackgroundColor = badgeFill
                state.badgeTextAttributes = [.foregroundColor: badgeText]
            }
        }
        UITabBar.appearance().standardAppearance = appearance
        UITabBar.appearance().scrollEdgeAppearance = appearance
    }()
}

private struct MainTabContent: View {
    @EnvironmentObject private var model: MixinAppModel
    @ObservedObject var contactList: ContactListViewModel
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        TabView(selection: $model.selectedTab) {
            ConversationListView(vm: model.conversationList)
                .pasteBarAboveTabBar(model.pasteBar)
                .tabItem { tabLabel(L10n.conversationsTab, system: "message", id: "main-tab-chats") }
                .tag(AppTab.chats)
            ContactListView(vm: contactList)
                .pasteBarAboveTabBar(model.pasteBar)
                .tabItem { contactsTabLabel }
                .tag(AppTab.contacts)
                .badge(contactList.badgeCount)
            MeView()
                .tabItem { tabLabel(L10n.meTab, system: "person", id: "main-tab-me") }
                .tag(AppTab.me)
        }
        .tint(Moyu.Palette.accentPrimary)
        .onAppear { model.pasteBar.recompute() }
        .onChange(of: model.selectedTab) { _ in model.pasteBar.recompute() }
        // 回前台按当前时间重算「可能已失效(> 30 天)」与角标。放在 tab 根而不是联系人 tab 内容里:
        // 内容惰性创建,用户停在会话 / 我 tab 回前台时联系人 tab 的视图不存在,角标就不会刷新。
        .onChange(of: scenePhase) { phase in
            if phase == .active {
                contactList.refresh()
                model.pasteBar.recompute()
            }
        }
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                if model.selectedTab == .chats { addButton }
            }
        }
    }

    private var title: String {
        switch model.selectedTab {
        case .chats: return L10n.appName
        case .contacts: return L10n.contactsTab
        case .me: return L10n.meTab
        }
    }

    /// 选中态的 fill 图标由系统 TabView 自动套用(spec §8.3 的 message.fill / person.2.fill / person.fill 与之一致),不手动切换。
    private func tabLabel(_ title: String, system: String, id: String) -> some View {
        Label(title, systemImage: system)
            .accessibilityIdentifier(id)
    }

    /// 联系人 tab:带角标时读作「联系人,〈n〉项待处理」,不读成「n 条未读」(spec §9.1)。
    /// 标识恒为 `main-tab-contacts`;角标的数字 UI 测试读 tab 按钮的 value(tab 按钮上的 identifier 不可靠,不依赖它)。
    @ViewBuilder private var contactsTabLabel: some View {
        let label = tabLabel(L10n.contactsTab, system: "person.2", id: "main-tab-contacts")
        if contactList.badgeCount > 0 {
            label.accessibilityLabel(L10n.contactsBadgeCd(contactList.badgeCount))
        } else {
            label
        }
    }

    /// 「＋」直达添加联系人;对方先发来的,在出示幕上直接收,不再有二级菜单。
    private var addButton: some View {
        Button {
            model.presentWizard(.initiator)
        } label: {
            Image(systemName: "plus")
        }
        .accessibilityLabel(L10n.conversationsAddCd)
        .accessibilityIdentifier("convlist-add")
    }
}
