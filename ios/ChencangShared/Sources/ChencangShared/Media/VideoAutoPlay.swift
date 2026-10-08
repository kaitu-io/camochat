import Foundation

/// 视频「点了才下,下完自动播一次」的判定(spec §3.4、R9):点视频气泡触发下载时,
/// 调用方记一个 pending 标记;每次条目状态变化都问一遍这个纯函数「现在该不该自动打开」——
/// 必须精确命中当时点的那条消息/那一项,且这一项现在已经 ready,且这一项确实是视频。
/// 不在这里做「打开后清标记」——那是有副作用的一步,留给调用方。
public enum VideoAutoPlay {
    public struct Pending: Equatable, Sendable {
        public let messageId: String
        public let index: Int

        public init(messageId: String, index: Int) {
            self.messageId = messageId
            self.index = index
        }
    }

    public static func shouldOpen(pending: Pending?, messageId: String, item: MediaItem) -> Bool {
        guard let pending, pending.messageId == messageId, pending.index == item.index else { return false }
        return item.kind == .video && item.state == .ready
    }

    /// 终审 F3:用户点的那次下载 `await` 完之后调用——**从存储重新读**这一项的当前状态再判定。
    /// 以前挂在 `.onChange(of: item.state)` 上,闭包拿的是构建修饰符时捕获的旧 `item`
    /// (状态还是 pending/downloading),判定恒为 false;行在屏幕外(LazyVStack)时更是根本不触发。
    /// 返回命中的最新条目(调用方据此清标记并打开播放器),不命中返回 nil。
    @MainActor
    public static func itemToOpen(pending: Pending?, tapped: Pending, peerId: String, store: ChatStore) -> MediaItem? {
        guard pending == tapped,
              let fresh = store.message(id: tapped.messageId, peerId: peerId)?.media?.first(where: { $0.index == tapped.index }),
              shouldOpen(pending: pending, messageId: tapped.messageId, item: fresh) else { return nil }
        return fresh
    }
}
