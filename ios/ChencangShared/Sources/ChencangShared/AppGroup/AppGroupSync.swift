import Foundation

/// A serialisable contact stored in the App Group container so the Action
/// Extension can read it without crossing process boundaries via Keychain.
///
/// `deviceId` is a synthetic value (= fingerprint hex) set by in-band pairing
/// (`PairingCoordinator`). Retained as a field to avoid migrating persisted
/// contacts; no server endpoint consumes it. Historical note: pre-Plan-5 this
/// held a server-assigned device_id used for `GET /v1/blobs/...`.
///
/// `emoji` is the 8-emoji safety fingerprint shown at pairing time
/// (`PairedContact.emoji`, `InbandPairing`'s `deriveSafetyEmoji` output) —
/// carried through by both production upsert paths
/// (`AppGroupContactPersister.upsert`, `PairingWizardViewModel
/// .persistLocally`) so `ContactsStore.safetyEmoji(for:)` can show the exact
/// same emoji later on the contact page, without re-deriving anything from a
/// secret. `nil` for contacts paired before this field existed —
/// `Codable`'s synthesized decode treats a missing "emoji" key in
/// old-format JSON as nil, no custom migration needed.
public struct AppGroupContact: Codable, Equatable, Identifiable, Hashable, Sendable {
    public let id: String
    public let displayName: String
    public let isVerified: Bool
    public let deviceId: String?
    public let emoji: [String]?
    /// 接受(对方的)邀请时那份邀请的摘要(SHA-256 小写十六进制);发起方建的联系人为 nil。
    /// 老数据缺此键解为 nil,不做迁移;改名、对印重建记录时必须原样带上,只随联系人删除而消失。
    public let acceptedInviteDigest: String?
    /// 配对完成的时间;会话 tab 里还没有消息的联系人按它排序、显示时间。
    /// 老数据缺此键解为 nil(排在最旧、不显示时间),不做迁移;改名、对印重建记录时必须原样带上。
    public let pairedAt: Date?

    public init(
        id: String, displayName: String, isVerified: Bool, deviceId: String? = nil, emoji: [String]? = nil,
        acceptedInviteDigest: String? = nil, pairedAt: Date? = nil
    ) {
        self.id = id
        self.displayName = displayName
        self.isVerified = isVerified
        self.deviceId = deviceId
        self.emoji = emoji
        self.acceptedInviteDigest = acceptedInviteDigest
        self.pairedAt = pairedAt
    }
}

/// Tiny protocol so we can swap UserDefaults for an in-memory fake in tests.
public protocol AppGroupDefaults: AnyObject, Sendable {
    func data(forKey: String) -> Data?
    func set(_ value: Data?, forKey: String)
}

/// Production implementation backed by `UserDefaults(suiteName:)`. Crashes early
/// if the App Group entitlement is missing so we surface configuration errors
/// during dogfood rather than silently no-op-ing.
public final class SharedAppGroupDefaults: AppGroupDefaults, @unchecked Sendable {
    public static let suiteName = "group.app.chencang.shared"
    private let defaults: UserDefaults

    public init(suiteName: String = SharedAppGroupDefaults.suiteName) {
        guard let d = UserDefaults(suiteName: suiteName) else {
            fatalError("App Group \(suiteName) not available — check entitlements.")
        }
        self.defaults = d
    }

    public func data(forKey: String) -> Data? {
        defaults.data(forKey: forKey)
    }

    public func set(_ value: Data?, forKey: String) {
        defaults.set(value, forKey: forKey)
    }
}

/// Reads/writes the small mirror of contacts that the Action Extension needs.
/// Identity material never lives here — the Keychain (or KeychainStoreProtocol
/// fake) owns secrets.
public final class AppGroupSync: @unchecked Sendable {
    public static let shared = AppGroupSync()

    private let defaults: AppGroupDefaults
    private let contactsKey = "cc.contacts.v1"

    public init(defaults: AppGroupDefaults? = nil) {
        if let d = defaults {
            self.defaults = d
        } else {
            self.defaults = SharedAppGroupDefaults()
        }
    }

    public func writeContacts(_ contacts: [AppGroupContact]) throws {
        let data = try JSONEncoder().encode(contacts)
        defaults.set(data, forKey: contactsKey)
    }

    public func readContacts() throws -> [AppGroupContact] {
        guard let d = defaults.data(forKey: contactsKey) else { return [] }
        return try JSONDecoder().decode([AppGroupContact].self, from: d)
    }
}
