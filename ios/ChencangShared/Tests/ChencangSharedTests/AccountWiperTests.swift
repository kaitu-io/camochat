import XCTest
@testable import ChencangShared

@MainActor
final class AccountWiperTests: XCTestCase {
    private var chatDir: URL!

    override func setUp() {
        super.setUp()
        chatDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("cc-wipe-tests-\(UUID().uuidString)", isDirectory: true)
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: chatDir)
    }

    private func makeWiper(
        keychain: FakeKeychain,
        appGroup: FakeAppGroupDefaults,
        clearPairingState: @escaping @MainActor () async throws -> Void = {},
        mediaFiles: MediaFiles? = nil,
        profileDefaults: UserDefaults? = nil
    ) throws -> AccountWiper {
        AccountWiper(
            identityStore: IdentityStore(keychain: keychain),
            contactsStore: ContactsStore(sync: AppGroupSync(defaults: appGroup)),
            chatStore: try ChatStore(directory: chatDir),
            mediaFiles: mediaFiles ?? MediaFiles(root: chatDir.appendingPathComponent("media")),
            inbox: AppGroupInbox(defaults: appGroup),
            sessionStore: SessionStore(keychain: keychain),
            clearPairingState: clearPairingState,
            profileDefaults: profileDefaults
        )
    }

    func testAllStepsSucceedReportsZeroFailures() async throws {
        let wiper = try makeWiper(keychain: FakeKeychain(), appGroup: FakeAppGroupDefaults())

        let result = await wiper.wipeAll()

        XCTAssertEqual(result.failedStepCount, 0)
        XCTAssertTrue(result.allSucceeded)
    }

    /// The scenario the review flagged: if identity deletion fails but wipe
    /// continues silently, the user sees an emptied contact/chat list and
    /// believes the account is gone while the identity secret is still in
    /// the Keychain. This asserts both halves of the best-effort contract:
    /// the failure is counted (not swallowed) AND the later steps still ran.
    func testIdentityDeleteFailureIsCountedAndLaterStepsStillRun() async throws {
        let keychain = FakeKeychain(seed: ["secret_identity_v1": Data([1, 2, 3])])
        keychain.deleteError = NSError(domain: "test.keychain", code: 1)
        let appGroup = FakeAppGroupDefaults()
        let sync = AppGroupSync(defaults: appGroup)
        try sync.writeContacts([AppGroupContact(id: "u1", displayName: "Alice", isVerified: true)])

        let wiper = try makeWiper(keychain: keychain, appGroup: appGroup)
        let result = await wiper.wipeAll()

        XCTAssertEqual(result.failedStepCount, 1)
        XCTAssertFalse(result.allSucceeded)

        // The failed identity delete didn't touch the Keychain item...
        XCTAssertNotNil(keychain.items["secret_identity_v1"], "a failed delete must not silently succeed")
        XCTAssertEqual(keychain.deleteCount, 0)

        // ...but every later step still ran (best-effort, not short-circuited).
        XCTAssertEqual(try sync.readContacts(), [])
    }

    func testWipeCallsClearPairingState() async throws {
        var calls = 0
        let wiper = try makeWiper(keychain: FakeKeychain(), appGroup: FakeAppGroupDefaults(),
                                  clearPairingState: { calls += 1 })

        let result = await wiper.wipeAll()

        XCTAssertEqual(calls, 1)
        XCTAssertTrue(result.allSucceeded)
    }

    /// 配对状态最先清:要等挂起中的配对操作收尾,再删联系人,否则收尾时会把联系人写回来。
    func testClearPairingStateRunsBeforeTheContactsAreWiped() async throws {
        let appGroup = FakeAppGroupDefaults()
        let sync = AppGroupSync(defaults: appGroup)
        try sync.writeContacts([AppGroupContact(id: "u1", displayName: "Alice", isVerified: true)])
        var contactsStillThere: Bool?
        let wiper = try makeWiper(keychain: FakeKeychain(), appGroup: appGroup,
                                  clearPairingState: { contactsStillThere = !((try? sync.readContacts()) ?? []).isEmpty })

        _ = await wiper.wipeAll()

        XCTAssertEqual(contactsStillThere, true)
    }

    func testThrowingClearPairingStateCountsAsFailedStep() async throws {
        let keychain = FakeKeychain()
        let appGroup = FakeAppGroupDefaults()
        let sync = AppGroupSync(defaults: appGroup)
        try sync.writeContacts([AppGroupContact(id: "u1", displayName: "Alice", isVerified: true)])

        let wiper = try makeWiper(keychain: keychain, appGroup: appGroup,
                                  clearPairingState: { throw NSError(domain: "test.pendingPairing", code: 1) })
        let result = await wiper.wipeAll()

        XCTAssertEqual(result.failedStepCount, 1)
        XCTAssertFalse(result.allSucceeded)
        // The earlier contacts step still completed despite the later failure.
        XCTAssertEqual(try sync.readContacts(), [])
    }

    func testWipeDeletesAllMediaFiles() async throws {
        let media = MediaFiles(root: chatDir.appendingPathComponent("media-wipe"))
        try media.write(Data([1, 2]), to: media.binURL(messageId: "m1", index: 0))
        try media.write(Data([3]), to: media.ccaURL(messageId: "m2", index: 0))

        let wiper = try makeWiper(keychain: FakeKeychain(), appGroup: FakeAppGroupDefaults(), mediaFiles: media)
        let result = await wiper.wipeAll()

        XCTAssertTrue(result.allSucceeded)
        XCTAssertFalse(media.exists(media.root))
    }

    func testWipeClearsMyNameAndAvatarKeys() async throws {
        let suite = "cc-wipe-profile-\(UUID().uuidString)"
        let d = UserDefaults(suiteName: suite)!
        defer { d.removePersistentDomain(forName: suite) }
        d.set("阿青", forKey: MyProfileKeys.name)
        d.set("青", forKey: MyProfileKeys.avatarGlyph)
        d.set(2, forKey: MyProfileKeys.avatarColor)
        let wiper = try makeWiper(keychain: FakeKeychain(), appGroup: FakeAppGroupDefaults(), profileDefaults: d)
        let result = await wiper.wipeAll()
        XCTAssertTrue(result.allSucceeded)
        for k in [MyProfileKeys.name, MyProfileKeys.avatarGlyph, MyProfileKeys.avatarColor] {
            XCTAssertNil(d.object(forKey: k), k)
        }
    }

    func testWipeKeepsSummaryPrivacyAndSealAction() async throws {
        let suite = "cc-wipe-keep-\(UUID().uuidString)"
        let d = UserDefaults(suiteName: suite)!
        defer { d.removePersistentDomain(forName: suite) }
        d.set(true, forKey: "cc.summaryPrivacy.v1")
        d.set("share", forKey: "cc.sealAction.v1")
        let wiper = try makeWiper(keychain: FakeKeychain(), appGroup: FakeAppGroupDefaults(), profileDefaults: d)
        await wiper.wipeAll()
        XCTAssertEqual(d.object(forKey: "cc.summaryPrivacy.v1") as? Bool, true)
        XCTAssertEqual(d.string(forKey: "cc.sealAction.v1"), "share")
    }
}
