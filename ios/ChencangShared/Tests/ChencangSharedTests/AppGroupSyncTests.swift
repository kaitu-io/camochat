import XCTest
@testable import ChencangShared

final class AppGroupSyncTests: XCTestCase {
    private var fake: FakeAppGroupDefaults!
    private var sync: AppGroupSync!

    override func setUp() {
        super.setUp()
        fake = FakeAppGroupDefaults()
        sync = AppGroupSync(defaults: fake)
    }

    func testContactsRoundTrip() throws {
        let contacts = [
            AppGroupContact(id: "u1", displayName: "Alice", isVerified: true),
            AppGroupContact(id: "u2", displayName: "Bob", isVerified: false),
        ]
        try sync.writeContacts(contacts)
        let read = try sync.readContacts()
        XCTAssertEqual(read, contacts)
    }

    func testEmptyOnFirstRead() throws {
        let read = try sync.readContacts()
        XCTAssertEqual(read, [])
    }

    func testContactJSONWithoutDigestDecodesWithNilDigest() throws {
        let legacy = #"[{"id":"u1","displayName":"Alice","isVerified":true,"deviceId":"d","emoji":["🌊"]}]"#
        fake.set(Data(legacy.utf8), forKey: "cc.contacts.v1")
        let read = try sync.readContacts()
        XCTAssertEqual(read.count, 1)
        XCTAssertNil(read[0].acceptedInviteDigest)
        XCTAssertEqual(read[0].id, "u1")
        XCTAssertEqual(read[0].displayName, "Alice")
        XCTAssertTrue(read[0].isVerified)
        XCTAssertEqual(read[0].deviceId, "d")
        XCTAssertEqual(read[0].emoji, ["🌊"])
        XCTAssertNil(read[0].pairedAt, "老数据没有配对时间")
    }

    func testContactPairedAtRoundTrips() throws {
        let c = AppGroupContact(id: "u1", displayName: "Alice", isVerified: false, pairedAt: Date(timeIntervalSince1970: 1_700_000_000))
        try sync.writeContacts([c])
        XCTAssertEqual(try sync.readContacts().first?.pairedAt, Date(timeIntervalSince1970: 1_700_000_000))
    }

    func testContactDigestRoundTrips() throws {
        let c = AppGroupContact(id: "u1", displayName: "Alice", isVerified: false, acceptedInviteDigest: "abc")
        try sync.writeContacts([c])
        XCTAssertEqual(try sync.readContacts(), [c])
        XCTAssertEqual(try sync.readContacts().first?.acceptedInviteDigest, "abc")
    }
}
