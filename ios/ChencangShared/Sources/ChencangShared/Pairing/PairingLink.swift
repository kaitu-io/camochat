import Foundation
import Chencang

/// 配对链接:`{site}p/#{base64url(信封字节)}`。信封字节 = `decodeWire(🔒wire)`(`0xCB, type, CBOR…`)。
/// 二维码、卡片、分享文字里的链接是同一个网址;`#` 后的部分浏览器不会发给服务器。
public enum PairingLink {
    /// `site` 由配置单保证以 `/` 结尾。wire 解不出来时片段为空(此时识别端会按「只复制了链接」处理)。
    public static func make(site: String, wire: String) -> String {
        site + "p/#" + base64url((try? decodeWire(s: wire)) ?? Data())
    }

    /// 文本里所有合法配对链接,转回标准 🔒 wire,顺序同原文。
    /// scheme 不区分大小写,路径 `/p/` 区分大小写;解码后必须以 `CB 01` 或 `CB 02` 开头。
    public static func wires(in text: String) -> [String] {
        let range = NSRange(text.startIndex..., in: text)
        return pattern.matches(in: text, range: range).compactMap { match in
            guard let r = Range(match.range(at: 1), in: text),
                  let bytes = decode(String(text[r])),
                  bytes.count >= 3, bytes[0] == 0xCB, bytes[1] == 0x01 || bytes[1] == 0x02
            else { return nil }
            return encodeWire(ciphertext: bytes)
        }
    }

    /// 系统唤醒时交来的 URL 是不是「配对链接的形状」(`https://<主机>/p/…`):是但 `wires(in:)` 解不出码
    /// (片段缺失 / 被截断 / 损坏)时,宿主要给出和粘贴同样的「不完整」提示,不能悄悄忽略。
    public static func isPairingLinkShape(_ url: URL) -> Bool {
        guard url.scheme?.lowercased() == "https", url.host?.isEmpty == false else { return false }
        return url.path.hasPrefix("/p/") || url.path == "/p"
    }

    // swiftlint:disable:next force_try
    private static let pattern = try! NSRegularExpression(
        pattern: "(?i:https)://[^\\s/?#]+/p/[^\\s#]*#([A-Za-z0-9_-]+)")

    static func base64url(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    /// 无填充 base64url;长度不合法(余 1)或解不出来 → nil。
    static func decode(_ s: String) -> Data? {
        guard s.count % 4 != 1 else { return nil }
        var b = s.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        b += String(repeating: "=", count: (4 - b.count % 4) % 4)
        return Data(base64Encoded: b)
    }
}
