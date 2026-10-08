import Foundation

/// 三个底部 tab(spec §3)。`selectedTab` 不跨冷启动持久化,冷启动恒落 `.chats`。
public enum AppTab: Hashable, Sendable { case chats, contacts, me }

/// 栈上的目的地。设置页不再是路由(各分组并入「我」tab)。
public enum AppRoute: Hashable, Sendable {
    case thread(String)     // peerId
    case contact(String)    // contactId
}

/// 「当前 tab + 单条栈」的导航快照(spec §4.1:单 NavigationStack + tab 根)。
public struct ShellState: Equatable {
    public var tab: AppTab
    public var path: [AppRoute]

    public init(tab: AppTab, path: [AppRoute]) {
        self.tab = tab
        self.path = path
    }
}

/// 各入口对导航状态的写法(spec §4.2)。纯函数,界面层只负责 `apply`。
public enum ShellNav {
    /// 深链 / Action Extension 跳回 / 配对完成:落在会话 tab,栈只有这一条线程。
    public static func openThread(_ peerId: String) -> ShellState {
        ShellState(tab: .chats, path: [.thread(peerId)])
    }

    /// 联系人详情「发消息」(spec §5.3):无论从线程标题栏(同一个人 → 弹回原线程,栈深不增长;
    /// 别人的线程 → 换栈)还是从联系人 tab 进来,结果都是「会话 tab + 该联系人线程」。
    /// `from` 保留在签名里是为了让调用点表达「基于当前状态」,规则本身与来路无关。
    public static func sendMessage(from: ShellState, contactId: String) -> ShellState {
        openThread(contactId)
    }

    /// 删联系人:清栈,**不动 tab**——从哪个 tab 进来就回哪个 tab。
    public static func contactDeleted(from: ShellState) -> ShellState {
        ShellState(tab: from.tab, path: [])
    }

    /// 删账号成功:清栈并落在会话 tab(再次建号后不应落在「我」)。
    public static func accountWiped() -> ShellState {
        ShellState(tab: .chats, path: [])
    }
}
