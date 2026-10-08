import XCTest
@testable import ChencangShared

final class AppGroupInboxTests: XCTestCase {
    var inbox: AppGroupInbox!
    var fakeDefaults: FakeAppGroupDefaults!

    override func setUp() {
        super.setUp()
        fakeDefaults = FakeAppGroupDefaults()
        inbox = AppGroupInbox(defaults: fakeDefaults)
    }

    // MARK: - Basic append/drain cycle

    func testAppendTwoThenDrainReturnsTwo() throws {
        let entry1 = InboxEntry(id: "1", peerId: "peer1", body: "Hello", timestamp: Date(timeIntervalSince1970: 1000))
        let entry2 = InboxEntry(id: "2", peerId: "peer2", body: "World", timestamp: Date(timeIntervalSince1970: 2000))

        try inbox.append(entry1)
        try inbox.append(entry2)

        let drained = inbox.drain()
        XCTAssertEqual(drained.count, 2)
        XCTAssertEqual(drained[0], entry1)
        XCTAssertEqual(drained[1], entry2)
    }

    func testDrainClearsTheStorage() throws {
        let entry = InboxEntry(id: "1", peerId: "peer1", body: "Test", timestamp: Date())
        try inbox.append(entry)

        _ = inbox.drain()
        let secondDrain = inbox.drain()

        XCTAssertEqual(secondDrain.count, 0)
    }

    // MARK: - Empty drain

    func testEmptyDrainReturnsEmptyArray() {
        let drained = inbox.drain()
        XCTAssertEqual(drained.count, 0)
    }

    // MARK: - Corrupt data handling

    func testCorruptBytesReturnEmptyArrayNoCrash() {
        // Store invalid JSON bytes
        fakeDefaults.set("invalid json {{{".data(using: .utf8), forKey: AppGroupInbox.key)

        let drained = inbox.drain()
        XCTAssertEqual(drained.count, 0)
    }

    // MARK: - Clear

    func testClearRemovesAllEntries() throws {
        let entry = InboxEntry(id: "1", peerId: "peer1", body: "Test", timestamp: Date())
        try inbox.append(entry)

        inbox.clear()

        let drained = inbox.drain()
        XCTAssertEqual(drained.count, 0)
    }

    // MARK: - readAll (non-destructive) + remove(ids:)

    func testReadAllDoesNotClearStorage() throws {
        let entry = InboxEntry(id: "1", peerId: "peer1", body: "Test", timestamp: Date())
        try inbox.append(entry)

        XCTAssertEqual(inbox.readAll().count, 1)
        XCTAssertEqual(inbox.readAll().count, 1)
    }

    func testRemoveIdsDeletesOnlyMatchingEntries() throws {
        let e1 = InboxEntry(id: "1", peerId: "peer1", body: "a", timestamp: Date(timeIntervalSince1970: 1))
        let e2 = InboxEntry(id: "2", peerId: "peer2", body: "b", timestamp: Date(timeIntervalSince1970: 2))
        try inbox.append(e1)
        try inbox.append(e2)

        try inbox.remove(ids: ["1"])

        XCTAssertEqual(inbox.readAll().map(\.id), ["2"])
    }

    func testRemoveAllIdsClearsStorage() throws {
        let entry = InboxEntry(id: "1", peerId: "peer1", body: "a", timestamp: Date(timeIntervalSince1970: 1))
        try inbox.append(entry)

        try inbox.remove(ids: ["1"])

        XCTAssertTrue(inbox.readAll().isEmpty)
    }

    func testRemoveWithEmptyIdsIsNoOp() throws {
        let entry = InboxEntry(id: "1", peerId: "peer1", body: "a", timestamp: Date(timeIntervalSince1970: 1))
        try inbox.append(entry)

        try inbox.remove(ids: [])

        XCTAssertEqual(inbox.readAll().map(\.id), ["1"])
    }
}
