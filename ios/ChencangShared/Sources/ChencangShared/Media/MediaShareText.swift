import Foundation

/// R1 粘贴形态:第一行给人看(聊天软件里可点的落地页链接),第二行是真正的 wire。
/// 媒体首行只含首条 blob_id;文字首行不含任何加密内容或明文(spec 2026-09-30 §5.4)。
/// 首行跟随发件人的系统语言(spec §4.1);收件端 `WireLocator` 从后往前找 wire,与首行语言无关。
/// 分享站点来自配置单(`AppConfig.shareSite`,以 `/` 结尾),不再硬编码域名。
public enum MediaShareText {
    /// 文字分享的首行:没装 App 的人点链接能看到怎么装、怎么解。
    public static func textFirstLine(site: String) -> String { L10n.cardShareHeaderText(site + "m/") }

    public static func compose(kind: MessageKind, items: [MediaItem], wire: String, site: String) -> String {
        let link = site + "m/" + items[0].blobId
        return "\(L10n.cardShareHeaderMedia(kind.displayLabel(count: items.count), link: link))\n\(wire)"
    }

    /// 文字消息的分享/复制文本:固定首行 + 换行 + wire。`WireLocator` 从后往前试,首行解不开自然跳过。
    public static func forText(_ wire: String, site: String) -> String {
        "\(textFirstLine(site: site))\n\(wire)"
    }
}
