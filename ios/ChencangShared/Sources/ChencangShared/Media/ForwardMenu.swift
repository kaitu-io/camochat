import Foundation

/// 媒体气泡长按菜单里的转发入口(终审 10):转发重发的是本机明文,没下载的条目转不出去。
/// - 「转发」:只在这一项本机有明文时给;
/// - 「转发全部 N 张」:只在多图相册、且**每一张**本机都有明文时给——有一张没下载就不给,
///   免得点了才报一句笼统的「转发失败」。
public enum ForwardMenu {
    public struct Options: Equatable {
        public let single: Bool
        /// 非 nil = 给「转发全部 N 张」,值为 N。
        public let allCount: Int?

        public init(single: Bool, allCount: Int?) {
            self.single = single
            self.allCount = allCount
        }
    }

    public static func options(message: ChatMessage, index: Int, hasPlaintext: (Int) -> Bool) -> Options {
        let items = message.media ?? []
        let single = hasPlaintext(index)
        let isAlbum = message.kind == .image && items.count > 1
        let allReady = isAlbum && items.allSatisfy { hasPlaintext($0.index) }
        return Options(single: single, allCount: allReady ? items.count : nil)
    }
}
