import Foundation
import Chencang

/// Observable store that owns the long-lived `SecretIdentity`. Purely local:
/// no server registration, no CC-Sig auth wiring. After `loadOrCreate` the
/// identity is available for in-band pairing and voice encryption.
@MainActor
public final class IdentityStore: ObservableObject {
    @Published public private(set) var identity: SecretIdentity?
    @Published public private(set) var mnemonicPreview: String?

    private let keychain: KeychainStoreProtocol
    private let key = "secret_identity_v1"

    public init(
        keychain: KeychainStoreProtocol = KeychainStore(service: "app.chencang.identity")
    ) {
        self.keychain = keychain
    }

    /// Synchronous, non-mutating existence check — does NOT touch `self.identity`
    /// or `self.mnemonicPreview`. Keychain reads are themselves synchronous OS
    /// calls, so callers that need to know "does a persisted identity already
    /// exist" (e.g. to decide whether onboarding should show) can call this
    /// before ever awaiting `loadOrCreate()`, whose result only lands after an
    /// async hop.
    ///
    /// 此处故意用 do/catch 显式区分,而不是一个 `try?` 把两种情况都吃掉
    /// (与 `SessionStore.decryptFromBytesAny(candidates:)` 里那处 try? 注释
    /// 同风格,同样是「两条错误路径代价不对称,所以拆开处理」):`read` 对
    /// 「确实没这条 Keychain 记录」返回 nil、不抛错——这才是真正的「无身份」,
    /// 归 false。任何其他错误(例如设备未解锁时的 errSecInteractionNotAllowed)
    /// 不代表身份不存在,只代表这次问不出来;此时宁可归 true——误判「有身份」
    /// 最坏后果是新用户被送进空态首页(可逆,后续正常加载会自我纠正);误判
    /// 「无身份」会让老用户在幕 0 看到「创建我的密钥」,一旦点下去就是用新身份
    /// 覆写 Keychain 里已有的那份——不可逆的密钥丢失。
    public func hasPersistedIdentity() -> Bool {
        do {
            return try keychain.read(key: key) != nil
        } catch {
            return true
        }
    }

    /// Loads the identity from Keychain if present, otherwise generates a new
    /// one. Purely local — no server registration, no network calls.
    public func loadOrCreate() async throws {
        if identity != nil { return }
        let id: SecretIdentity
        if let blob = try keychain.read(key: key) {
            id = try SecretIdentity.fromLocalStorage(data: blob)
        } else {
            id = SecretIdentity()
            let blob = id.serializeForLocalStorage()
            try keychain.write(key: key, data: blob)
            self.mnemonicPreview = "(mnemonic export not yet implemented)"
        }
        self.identity = id
    }

    public func deleteIdentity() async throws {
        try keychain.delete(key: key)
        self.identity = nil
        self.mnemonicPreview = nil
    }
}
