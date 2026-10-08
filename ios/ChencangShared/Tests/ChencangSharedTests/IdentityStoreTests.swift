import XCTest
@testable import ChencangShared

@MainActor
final class IdentityStoreTests: XCTestCase {
    func testCreatesOnFirstCall() async throws {
        let kc = FakeKeychain()
        let store = IdentityStore(keychain: kc)
        try await store.loadOrCreate()
        XCTAssertNotNil(store.identity)
        XCTAssertEqual(kc.writeCount, 1)
    }

    func testReturnsSameIdentityOnSecondCall() async throws {
        let kc = FakeKeychain()
        let store = IdentityStore(keychain: kc)
        try await store.loadOrCreate()
        let firstFingerprint = store.identity?.publicIdentity().ikDhX25519
        try await store.loadOrCreate()
        let secondFingerprint = store.identity?.publicIdentity().ikDhX25519
        XCTAssertEqual(firstFingerprint, secondFingerprint)
        XCTAssertEqual(kc.writeCount, 1, "loadOrCreate must be idempotent")
    }

    func testDeleteClearsKeychain() async throws {
        let kc = FakeKeychain()
        let store = IdentityStore(keychain: kc)
        try await store.loadOrCreate()
        XCTAssertFalse(kc.items.isEmpty)
        try await store.deleteIdentity()
        XCTAssertNil(store.identity)
        XCTAssertEqual(kc.deleteCount, 1)
    }

    func testHasPersistedIdentityFalseOnEmptyKeychain() {
        let store = IdentityStore(keychain: FakeKeychain())
        XCTAssertFalse(store.hasPersistedIdentity())
        XCTAssertNil(store.identity, "existence check must not load the identity")
    }

    func testHasPersistedIdentityTrueWhenKeychainAlreadySeeded() async throws {
        // Seed a real persisted blob via one store, then probe with a second
        // (fresh) instance sharing the same backing keychain — this is the
        // "returning user" shape at app launch, before `loadOrCreate()` runs.
        let kc = FakeKeychain()
        let seeder = IdentityStore(keychain: kc)
        try await seeder.loadOrCreate()

        let freshStore = IdentityStore(keychain: kc)
        XCTAssertTrue(freshStore.hasPersistedIdentity())
        XCTAssertNil(freshStore.identity, "existence check must not load the identity")
    }

    /// A real Keychain error that ISN'T "item not found" — e.g.
    /// `errSecInteractionNotAllowed` while the device is locked — must NOT be
    /// read as "no identity". `hasPersistedIdentity()` seeds
    /// `MixinAppModel.showOnboarding = !hasPersistedIdentity()`; a false
    /// negative here would send a returning user into onboarding, where
    /// tapping "创建我的密钥" overwrites their existing identity — unrecoverable.
    /// So this path deliberately fails toward `true` (assume an identity
    /// exists), not `false`.
    func testHasPersistedIdentityTrueOnKeychainErrorOtherThanNotFound() {
        struct DummyKeychainError: Error {}
        let kc = FakeKeychain()
        kc.readError = DummyKeychainError()
        let store = IdentityStore(keychain: kc)

        XCTAssertTrue(store.hasPersistedIdentity(), "non-not-found errors must fail toward \"has identity\", not \"no identity\"")
        XCTAssertNil(store.identity, "existence check must not load the identity")
    }
}
