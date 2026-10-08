import XCTest
@testable import ChencangShared

/// ``AppGroupContactPersister`` is the only production `PairedContactPersisting`
/// — the coordinator/wizard unit tests exercise a `NoopContactPersister`/
/// `FakeContactStore` double instead, so this is the sole test coverage of the
/// real App-Group-mirroring path `PairingCoordinator.makeDefault()` wires up.
///
/// This was the root cause of the "验证印显示 8 个占位符" defect (M4 Task 9
/// review, bugfix round 1): `upsert(_:)` rebuilt a fresh `AppGroupContact`
/// from the coordinator's `PairedContact` without carrying `emoji` across, so
/// the real pairing-time safety emoji never reached the App Group mirror
/// `ContactsStore.safetyEmoji(for:)` reads from.
final class AppGroupContactPersisterTests: XCTestCase {
    func testUpsertCarriesThePairingTimeEmojiIntoTheAppGroupMirror() async throws {
        let sync = AppGroupSync(defaults: FakeAppGroupDefaults())
        let persister = AppGroupContactPersister(sync: sync)
        let emoji = ["🌊", "🌙", "🍀", "🔥", "🐝", "🎯", "🧭", "🪁"]

        try await persister.upsert(
            PairedContact(
                fingerprintHex: "abc123",
                displayName: "联系人 abc123",
                emoji: emoji,
                deviceId: "abc123",
                pairedAtMillis: 0
            )
        )

        let mirrored = try sync.readContacts()
        XCTAssertEqual(mirrored.first?.emoji, emoji)
    }

    func testUpsertCarriesAcceptedInviteDigestAndFindWorks() async throws {
        let sync = AppGroupSync(defaults: FakeAppGroupDefaults())
        let persister = AppGroupContactPersister(sync: sync)
        try await persister.upsert(
            PairedContact(fingerprintHex: "fp1", displayName: "x", emoji: ["🌊"], deviceId: "fp1",
                          pairedAtMillis: 0, acceptedInviteDigest: "dig")
        )
        XCTAssertEqual(try sync.readContacts().first?.acceptedInviteDigest, "dig")
        let byFp = await persister.find(fingerprintHex: "fp1")
        XCTAssertEqual(byFp?.acceptedInviteDigest, "dig")
        let byDigest = await persister.find(acceptedInviteDigest: "dig")
        XCTAssertEqual(byDigest?.fingerprintHex, "fp1")
        let none = await persister.find(acceptedInviteDigest: "other")
        XCTAssertNil(none)
        let noFp = await persister.find(fingerprintHex: "nope")
        XCTAssertNil(noFp)
    }

    func testUpsertWritesPairedAtAndFindReadsItBack() async throws {
        let sync = AppGroupSync(defaults: FakeAppGroupDefaults())
        let persister = AppGroupContactPersister(sync: sync)
        try await persister.upsert(
            PairedContact(fingerprintHex: "fp1", displayName: "x", emoji: [], deviceId: "fp1", pairedAtMillis: 1_700_000_000_000)
        )
        XCTAssertEqual(try sync.readContacts().first?.pairedAt, Date(timeIntervalSince1970: 1_700_000_000))
        let found = await persister.find(fingerprintHex: "fp1")
        XCTAssertEqual(found?.pairedAtMillis, 1_700_000_000_000)
    }
}
