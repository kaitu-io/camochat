import Foundation
import Chencang

/// Production identity provider for in-band pairing: loads the long-lived
/// `SecretIdentity` from the Keychain (same slot ``IdentityStore`` writes). The
/// `SecretIdentity.fromLocalStorage` deserialize runs post-quantum keygen with
/// large stack buffers, so it must execute on a big stack — guaranteed because
/// ``PairingCoordinator`` is `@MainActor`.
public struct KeychainInbandIdentityProvider: InbandIdentityProviding {
    private let keychain: KeychainStoreProtocol
    private let key = "secret_identity_v1"

    public init(keychain: KeychainStoreProtocol = KeychainStore(service: "app.chencang.identity")) {
        self.keychain = keychain
    }

    @MainActor public func loadIdentity() throws -> SecretIdentity {
        guard let blob = try keychain.read(key: key) else {
            throw PairingError.noIdentity
        }
        return try SecretIdentity.fromLocalStorage(data: blob)
    }
}

/// Persists a paired session into the shared ``SessionStore`` actor.
public struct SessionStoreSessionPersister: PairedSessionPersisting {
    private let store: SessionStore

    public init(store: SessionStore = .shared) { self.store = store }

    public func put(_ session: Session, peerId: String) async throws {
        try await store.save(session, for: peerId)
    }

    public func remove(peerId: String) async {
        await store.remove(for: peerId)
    }
}

/// Upserts a paired contact into the App Group mirror (keyed by fingerprint, so
/// re-pairing the same peer replaces the row rather than duplicating it).
public struct AppGroupContactPersister: PairedContactPersisting {
    private let sync: AppGroupSync

    public init(sync: AppGroupSync = .shared) { self.sync = sync }

    /// `@MainActor`:读-改-写与主线程上的 `ContactsStore` 同一线程,二者各自是一段不挂起的同步代码,
    /// 因而互相串行,不会丢更新(原先 nonisolated async 可能落在后台线程与之交错)。
    @MainActor
    public func upsert(_ contact: PairedContact) async throws {
        var all = (try? sync.readContacts()) ?? []
        all.removeAll { $0.id == contact.fingerprintHex }
        all.append(
            AppGroupContact(
                id: contact.fingerprintHex,
                displayName: contact.displayName,
                isVerified: false,
                deviceId: contact.deviceId,
                emoji: contact.emoji,
                acceptedInviteDigest: contact.acceptedInviteDigest,
                pairedAt: contact.pairedAt
            )
        )
        try sync.writeContacts(all)
    }

    public func find(fingerprintHex: String) async -> PairedContact? {
        ((try? sync.readContacts()) ?? []).first { $0.id == fingerprintHex }.map(Self.paired)
    }

    public func find(acceptedInviteDigest: String) async -> PairedContact? {
        ((try? sync.readContacts()) ?? []).first { $0.acceptedInviteDigest == acceptedInviteDigest }.map(Self.paired)
    }

    /// 老数据镜像里没有配对时间,取 0(调用方只用指纹、emoji、摘要)。
    private static func paired(_ c: AppGroupContact) -> PairedContact {
        PairedContact(
            fingerprintHex: c.id, displayName: c.displayName, emoji: c.emoji ?? [],
            deviceId: c.deviceId ?? c.id, pairedAtMillis: c.pairedAt.map { Int64($0.timeIntervalSince1970 * 1000) } ?? 0, acceptedInviteDigest: c.acceptedInviteDigest
        )
    }
}

public extension PairingCoordinator {
    /// Build a production coordinator wired to the Keychain identity, local
    /// (zero-server) SPK provisioning, the shared session store, the App Group
    /// contact mirror, and the App Group pending-invite / response stores (the
    /// shared instances, which hold an in-memory cache the UI also observes).
    @MainActor
    static func makeDefault() -> PairingCoordinator {
        PairingCoordinator(
            identity: KeychainInbandIdentityProvider(),
            prekeyProvisioner: PrekeyProvisioner(),
            sessionStore: SessionStoreSessionPersister(),
            contactStore: AppGroupContactPersister(),
            pending: .shared,
            responses: .shared
        )
    }
}
