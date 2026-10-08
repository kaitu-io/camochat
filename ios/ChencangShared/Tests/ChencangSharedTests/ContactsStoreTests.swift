import XCTest
@testable import ChencangShared

@MainActor
final class ContactsStoreTests: XCTestCase {
    private var fakeDefaults: FakeAppGroupDefaults!
    private var sync: AppGroupSync!
    private var store: ContactsStore!

    override func setUp() {
        super.setUp()
        fakeDefaults = FakeAppGroupDefaults()
        sync = AppGroupSync(defaults: fakeDefaults)
        store = ContactsStore(sync: sync)
    }

    func testRemoveAllClearsInMemoryContacts() async throws {
        try await store.add(AppGroupContact(id: "u1", displayName: "Alice", isVerified: true))
        try await store.add(AppGroupContact(id: "u2", displayName: "Bob", isVerified: false))
        XCTAssertEqual(store.contacts.count, 2)

        try await store.removeAll()

        XCTAssertTrue(store.contacts.isEmpty)
    }

    func testRemoveAllClearsAppGroupMirror() async throws {
        try await store.add(AppGroupContact(id: "u1", displayName: "Alice", isVerified: true))

        try await store.removeAll()

        XCTAssertEqual(try sync.readContacts(), [])
    }

    func testRemoveAllOnEmptyStoreIsNoop() async throws {
        try await store.removeAll()
        XCTAssertTrue(store.contacts.isEmpty)
    }

    func testRemoveAllThenFreshStoreLoadsEmpty() async throws {
        try await store.add(AppGroupContact(id: "u1", displayName: "Alice", isVerified: true))
        try await store.removeAll()

        // Simulates a cold reload (e.g. app relaunch after account wipe) —
        // a brand-new ContactsStore reading the same App Group mirror must
        // not resurrect the wiped contact.
        let reloaded = ContactsStore(sync: sync)
        XCTAssertTrue(reloaded.contacts.isEmpty)
    }

    // MARK: - M4 Task 8: remove(contactId:) / rename(contactId:to:)

    func testRemoveFiltersOutOnlyTheMatchingContact() async throws {
        try await store.add(AppGroupContact(id: "u1", displayName: "Alice", isVerified: true))
        try await store.add(AppGroupContact(id: "u2", displayName: "Bob", isVerified: false))

        try await store.remove(contactId: "u1")

        XCTAssertEqual(store.contacts.map(\.id), ["u2"])
        XCTAssertEqual(try sync.readContacts().map(\.id), ["u2"])
    }

    func testRemoveOnUnknownContactIdIsNoop() async throws {
        try await store.add(AppGroupContact(id: "u1", displayName: "Alice", isVerified: true))

        try await store.remove(contactId: "does-not-exist")

        XCTAssertEqual(store.contacts.count, 1)
    }

    func testRenameTrimsAndUpdatesDisplayNameOnlyForTheMatchingContact() async throws {
        try await store.add(AppGroupContact(id: "u1", displayName: "old-name", isVerified: false))
        try await store.add(AppGroupContact(id: "u2", displayName: "untouched", isVerified: false))

        try await store.rename(contactId: "u1", to: "  老王  ")

        XCTAssertEqual(store.contacts.first { $0.id == "u1" }?.displayName, "老王")
        XCTAssertEqual(store.contacts.first { $0.id == "u2" }?.displayName, "untouched")
        XCTAssertEqual(try sync.readContacts().first { $0.id == "u1" }?.displayName, "老王")
    }

    func testRenameAndMarkVerifiedPreserveAcceptedInviteDigest() async throws {
        try await store.add(AppGroupContact(id: "u1", displayName: "old", isVerified: false, acceptedInviteDigest: "d1"))

        try await store.rename(contactId: "u1", to: "新名")
        XCTAssertEqual(store.contacts.first?.acceptedInviteDigest, "d1")
        XCTAssertEqual(try sync.readContacts().first?.acceptedInviteDigest, "d1")

        try await store.markVerified(contactId: "u1")
        XCTAssertEqual(store.contacts.first?.acceptedInviteDigest, "d1")
        XCTAssertEqual(try sync.readContacts().first?.acceptedInviteDigest, "d1")
        XCTAssertEqual(try sync.readContacts().first?.isVerified, true)
    }

    func testRenameAndMarkVerifiedPreservePairedAt() async throws {
        let t = Date(timeIntervalSince1970: 1_700_000_000)
        try await store.add(AppGroupContact(id: "u1", displayName: "old", isVerified: false, pairedAt: t))
        try await store.rename(contactId: "u1", to: "新名")
        try await store.markVerified(contactId: "u1")
        XCTAssertEqual(store.contacts.first?.pairedAt, t)
        XCTAssertEqual(try sync.readContacts().first?.pairedAt, t)
    }

    func testRemoveDropsContactAndItsDigest() async throws {
        try await store.add(AppGroupContact(id: "u1", displayName: "A", isVerified: false, acceptedInviteDigest: "d1"))
        try await store.remove(contactId: "u1")
        XCTAssertTrue(try sync.readContacts().isEmpty)
        XCTAssertFalse(try sync.readContacts().contains { $0.acceptedInviteDigest == "d1" })
    }

    /// 编排层经 persister 直接写镜像;常驻的 ContactsStore 缓存之后的任何回写都不能把新联系人冲掉。
    func testMutationAfterAnOutOfBandMirrorWriteKeepsTheNewContact() async throws {
        try await store.add(AppGroupContact(id: "u1", displayName: "Alice", isVerified: false))
        // what AppGroupContactPersister.upsert does during a pairing
        try sync.writeContacts(try sync.readContacts() + [AppGroupContact(id: "u2", displayName: "Bob", isVerified: false)])

        try await store.rename(contactId: "u1", to: "Alice2")

        XCTAssertEqual(Set(try sync.readContacts().map(\.id)), ["u1", "u2"])
        XCTAssertEqual(Set(store.contacts.map(\.id)), ["u1", "u2"])
    }

    func testRenameWithBlankNameIsNoop() async throws {
        try await store.add(AppGroupContact(id: "u1", displayName: "old-name", isVerified: false))

        try await store.rename(contactId: "u1", to: "   ")

        XCTAssertEqual(store.contacts.first?.displayName, "old-name")
    }

    // MARK: - M4 bugfix round 1: safetyEmoji reads the persisted pairing-time
    // emoji off the contact record — it no longer re-derives from a
    // `session_secret_*` Keychain key that no production path ever wrote
    // (root cause of the "验证印显示 8 个占位符" defect Task 9 review flagged).

    func testSafetyEmojiReturnsThePersistedEmojiFromTheContactRecord() async throws {
        let realEmoji = ["🌊", "🌙", "🍀", "🔥", "🐝", "🎯", "🧭", "🪁"]
        try await store.add(AppGroupContact(id: "u1", displayName: "Alice", isVerified: false, emoji: realEmoji))

        let result = try await store.safetyEmoji(for: "u1")

        XCTAssertEqual(result, realEmoji)
    }

    func testSafetyEmojiReturnsEmptyForAContactWithNoPersistedEmoji() async throws {
        // Models a contact paired before the `emoji` field existed (or any
        // other legacy/missing-data case) — `AppGroupContact.emoji` decodes
        // to nil, not a fabricated placeholder. `EmojiSealGridView` renders
        // an empty array as its own "暂无安全码，请重新配对以生成" message.
        try await store.add(AppGroupContact(id: "u1", displayName: "Alice", isVerified: false))

        let result = try await store.safetyEmoji(for: "u1")

        XCTAssertEqual(result, [])
    }

    func testMarkVerifiedPreservesThePersistedEmoji() async throws {
        let realEmoji = ["🌊", "🌙", "🍀", "🔥", "🐝", "🎯", "🧭", "🪁"]
        try await store.add(AppGroupContact(id: "u1", displayName: "Alice", isVerified: false, emoji: realEmoji))

        try await store.markVerified(contactId: "u1")

        XCTAssertEqual(store.contacts.first?.emoji, realEmoji)
        XCTAssertEqual(try sync.readContacts().first?.emoji, realEmoji)
    }

    func testRenamePreservesThePersistedEmoji() async throws {
        let realEmoji = ["🌊", "🌙", "🍀", "🔥", "🐝", "🎯", "🧭", "🪁"]
        try await store.add(AppGroupContact(id: "u1", displayName: "old-name", isVerified: false, emoji: realEmoji))

        try await store.rename(contactId: "u1", to: "老王")

        XCTAssertEqual(store.contacts.first?.emoji, realEmoji)
        XCTAssertEqual(try sync.readContacts().first?.emoji, realEmoji)
    }
}
