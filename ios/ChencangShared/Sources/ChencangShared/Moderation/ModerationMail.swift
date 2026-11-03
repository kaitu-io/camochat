import Foundation

/// 用户可以选择发给开发者的举报 / 拉黑邮件。App 自己从不发送:这里只拼草稿,交给用户自己的邮件 App。
/// 格式与 Android `ModerationMail` 一致,平台写 `iOS`。
public enum ModerationMail {
    public static let email = "hi@allnationconnect.com"

    public struct Mail: Equatable, Sendable {
        public let to: String
        public let subject: String
        public let body: String

        public init(to: String, subject: String, body: String) {
            self.to = to
            self.subject = subject
            self.body = body
        }

        /// `mailto:<to>?subject=…&body=…`,值按 RFC 3986 未保留字符之外一律百分号编码(含 `+`、`&`、换行)。
        public func mailtoURL() -> URL? {
            URL(string: "mailto:\(Self.encode(to))?subject=\(Self.encode(subject))&body=\(Self.encode(body))")
        }

        /// 没有邮件 App 时放进剪贴板的全文(同 Android `MailLauncher.fallbackText`)。
        public var fallbackText: String {
            "To: \(to)\nSubject: \(subject)\n\n" + body
        }

        private static let unreserved = CharacterSet(charactersIn:
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")

        private static func encode(_ s: String) -> String {
            s.addingPercentEncoding(withAllowedCharacters: unreserved) ?? ""
        }
    }

    /// 指纹只带前 16 位:够辨认,不是整把公钥。
    private static let fingerprintChars = 16

    /// 「我拉黑了这个联系人」。`now` 写成 ISO-8601 UTC,精确到秒。
    public static func blockNotice(displayName: String, fingerprintHex: String, now: Date, appVersion: String) -> Mail {
        Mail(
            to: email,
            subject: "CamoChat block notice / 拉黑通知",
            body: [
                "Blocked contact: \(displayName)",
                "Fingerprint: \(fingerprintHex.prefix(fingerprintChars))",
                "Time (UTC): \(isoSeconds(now))",
                "App: CamoChat \(appVersion) iOS",
            ].joined(separator: "\n") + "\n"
        )
    }

    static func isoSeconds(_ date: Date) -> String {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime]
        f.timeZone = TimeZone(identifier: "UTC")
        return f.string(from: date)
    }
}
