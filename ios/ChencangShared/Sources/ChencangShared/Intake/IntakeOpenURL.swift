import Foundation

/// 扩展把用户送回主 App 用的 App 内链接(无参数):
/// `camo://intake`(取交接槽里的配对码)、`camo://add-contact`、`camo://home`。
/// 与 `ThreadOpenURL`(`camo://thread`)、`PairingURLParser`(`camo://pairing`)互不吞。
public enum IntakeOpenURL: Equatable {
    case pendingIntake
    case addContact
    case home

    private var host: String {
        switch self {
        case .pendingIntake: return "intake"
        case .addContact: return "add-contact"
        case .home: return "home"
        }
    }

    public static func parse(url: URL) -> IntakeOpenURL? {
        guard url.scheme == AppURLScheme.name else { return nil }
        return [IntakeOpenURL.pendingIntake, .addContact, .home].first { $0.host == url.host }
    }

    public static func make(_ link: IntakeOpenURL) -> URL {
        var c = URLComponents()
        c.scheme = AppURLScheme.name
        c.host = link.host
        return c.url!
    }
}
