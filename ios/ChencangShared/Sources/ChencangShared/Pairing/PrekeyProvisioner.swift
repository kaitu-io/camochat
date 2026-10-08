import Foundation
import Chencang

/// Keychain-backed persistence of the serialized secret signed pre-key (SPK).
/// `SecretSignedPreKey(ik:spkVersion:)` generates a FRESH random keypair on every
/// call, so the responder side could never reconstruct the exact SPK it published
/// without persisting it — A and B would then derive different root keys.
public struct SignedPreKeyStore: Sendable {
    private let keychain: KeychainStoreProtocol
    private let key = "spk_active_v1"

    public init(keychain: KeychainStoreProtocol = KeychainStore(service: "app.chencang.identity")) {
        self.keychain = keychain
    }

    public func exists() -> Bool {
        (try? keychain.read(key: key)) ?? nil != nil
    }

    public func save(_ data: Data) throws {
        try keychain.write(key: key, data: data)
    }

    public func load() -> Data? {
        (try? keychain.read(key: key)) ?? nil
    }
}

/// Local-only signed pre-key provisioner. Generates + persists this device's
/// SPK on first call; no server upload. Mirrors chencang-android's
/// `PrekeyProvisioner` (local path).
public struct PrekeyProvisioner: Sendable {
    public static let spkVersion = 0
    private static let versionKey = "chencang.spk_version"

    private let store: SignedPreKeyStore
    private let defaults: UserDefaults

    public init(
        store: SignedPreKeyStore = SignedPreKeyStore(),
        defaults: UserDefaults? = nil
    ) {
        self.store = store
        if let d = defaults {
            self.defaults = d
        } else {
            guard let d = UserDefaults(suiteName: SharedAppGroupDefaults.suiteName) else {
                fatalError("App Group \(SharedAppGroupDefaults.suiteName) not available — check entitlements.")
            }
            self.defaults = d
        }
    }

    /// Generate + persist this device's SPK if absent — LOCAL ONLY, no server.
    /// Idempotent (a no-op once provisioned). The zero-server in-band pairing path
    /// (``PairingCoordinator``) calls this before ``loadActiveSpk()`` so a
    /// brand-new identity — which never ran the server boot path — can still mint
    /// an invite. The SPK is persisted, so the inviter reconstructs the exact same
    /// signed-prekey when it completes. Mirrors chencang-android's
    /// `provisionLocallyIfNeeded`.
    public func provisionLocallyIfNeeded(identity: SecretIdentity) throws {
        // The persisted SPK is the SOLE source of truth: SecretSignedPreKey()
        // mints a FRESH random keypair each call, so once it exists we must NEVER
        // regenerate — the inviter advertised that exact SPK's public half in its
        // bundle and must reload the identical secret to complete. (A plain
        // `flag && exists` conjunction would regenerate a *different* SPK in the
        // flag-set-but-file-missing split-brain, silently breaking confirmation.)
        if store.exists() {
            if defaults.object(forKey: Self.versionKey) == nil {
                defaults.set(Self.spkVersion, forKey: Self.versionKey)
            }
            return
        }
        let spk = try SecretSignedPreKey(ik: identity, spkVersion: UInt32(Self.spkVersion))
        try store.save(spk.serialize())
        defaults.set(Self.spkVersion, forKey: Self.versionKey)
    }

    /// Load the persisted secret SPK (responder half of the handshake reuses the
    /// exact SPK that was published). Throws if `provisionLocallyIfNeeded` never ran.
    public func loadActiveSpk() throws -> SecretSignedPreKey {
        guard let bytes = store.load() else {
            throw PairingError.state("no persisted SPK — provisionLocallyIfNeeded() must run first")
        }
        return try SecretSignedPreKey.fromSerialized(data: bytes)
    }
}
