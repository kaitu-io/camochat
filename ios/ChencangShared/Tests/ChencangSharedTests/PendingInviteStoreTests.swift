import XCTest
@testable import ChencangShared

@MainActor
final class PendingInviteStoreTests: XCTestCase {
    private let listKey = "cc.pending_pairings.v2"
    private let legacyKey = "cc.pending_pairing.v1"

    private func rec(_ id: String, at: Int64 = 100, wire: String = "w") -> PendingPairingRecord {
        PendingPairingRecord(pairingId: id, pairingNonceB64: "n-\(id)", createdAtMillis: at, inviteWire: wire)
    }

    func testAppendTwoKeepsBoth() throws {
        let store = PendingInviteStore(defaults: FakeAppGroupDefaults())
        try store.append(rec("a")); try store.append(rec("b"))
        XCTAssertEqual(store.records.map(\.pairingId), ["a", "b"])
    }

    func testUpdateAndRemoveById() throws {
        let store = PendingInviteStore(defaults: FakeAppGroupDefaults())
        try store.append(rec("a")); try store.append(rec("b"))
        try store.update(id: "a") { $0.note = "老周"; $0.lastSharedAtMillis = 7 }
        XCTAssertEqual(store.record(id: "a")?.note, "老周")
        XCTAssertEqual(store.record(id: "a")?.lastSharedAtMillis, 7)
        try store.remove(id: "b")
        XCTAssertEqual(store.records.map(\.pairingId), ["a"])
        XCTAssertNil(store.record(id: "b"))
    }

    func testSurvivesRebuild() throws {
        let d = FakeAppGroupDefaults()
        let s1 = PendingInviteStore(defaults: d)
        try s1.append(rec("a")); try s1.update(id: "a") { $0.note = "x" }
        let s2 = PendingInviteStore(defaults: d)
        XCTAssertEqual(s2.records, s1.records)
    }

    func testLegacySlotMigratesOnceAndDeletesOldKey() throws {
        let d = FakeAppGroupDefaults()
        let old = #"{"pairingId":"old","pairingNonceB64":"nn","createdAtMillis":555}"#
        d.set(Data(old.utf8), forKey: legacyKey)
        let store = PendingInviteStore(defaults: d)
        XCTAssertEqual(store.records.count, 1)
        let r = store.records[0]
        XCTAssertEqual(r.pairingId, "old")
        XCTAssertEqual(r.inviteWire, "")
        XCTAssertEqual(r.lastSharedAtMillis, 555)
        XCTAssertNil(d.data(forKey: legacyKey))
        XCTAssertEqual(PendingInviteStore(defaults: d).records.count, 1)
    }

    func testMigrationIsIdempotentWhenOldKeyStillPresent() throws {
        let d = FakeAppGroupDefaults()
        let s1 = PendingInviteStore(defaults: d)
        try s1.append(rec("old", at: 555))
        let old = #"{"pairingId":"old","pairingNonceB64":"nn","createdAtMillis":555}"#
        d.set(Data(old.utf8), forKey: legacyKey)
        let s2 = PendingInviteStore(defaults: d)
        XCTAssertEqual(s2.records.map(\.pairingId), ["old"])
        XCTAssertNil(d.data(forKey: legacyKey))
    }

    func testCorruptElementIsDroppedOthersKept() throws {
        let d = FakeAppGroupDefaults()
        let json = #"[{"pairingId":"a","pairingNonceB64":"n","createdAtMillis":1},{"bogus":true},{"pairingId":"c","pairingNonceB64":"n","createdAtMillis":3}]"#
        d.set(Data(json.utf8), forKey: listKey)
        let store = PendingInviteStore(defaults: d)
        XCTAssertEqual(store.records.map(\.pairingId), ["a", "c"])
    }

    func testCorruptBlobGivesEmptyListNoCrash() throws {
        let d = FakeAppGroupDefaults()
        d.set(Data("not json".utf8), forKey: listKey)
        let store = PendingInviteStore(defaults: d)
        XCTAssertTrue(store.records.isEmpty)
        try store.append(rec("a"))
        XCTAssertEqual(store.records.count, 1)
    }

    func testClearEmptiesList() throws {
        let d = FakeAppGroupDefaults()
        let store = PendingInviteStore(defaults: d)
        try store.append(rec("a")); try store.clear()
        XCTAssertTrue(store.records.isEmpty)
        XCTAssertTrue(PendingInviteStore(defaults: d).records.isEmpty)
    }

    // U1:两个实例共用同一份磁盘;B 的缓存是旧的(比如进程在首次解锁前被拉起时读到空),它再写,A 的记录不丢。
    func testWriteReReadsDiskSoAStaleCacheCannotOverwriteOtherRecords() throws {
        let d = FakeAppGroupDefaults()
        let a = PendingInviteStore(defaults: d)
        let b = PendingInviteStore(defaults: d) // 缓存:空
        try a.append(rec("a"))
        try b.append(rec("b"))
        XCTAssertEqual(b.records.map(\.pairingId), ["a", "b"])
        XCTAssertEqual(PendingInviteStore(defaults: d).records.map(\.pairingId), ["a", "b"])
        try b.update(id: "a") { $0.note = "x" }
        try a.remove(id: "b") // a 的缓存是旧的,但 remove 前先重读
        XCTAssertEqual(PendingInviteStore(defaults: d).records.map(\.pairingId), ["a"])
        XCTAssertEqual(PendingInviteStore(defaults: d).records.first?.note, "x")
    }

    func testReloadPicksUpOtherWritersAndKeepsCacheWhenUndecodable() throws {
        let d = FakeAppGroupDefaults()
        let a = PendingInviteStore(defaults: d)
        let b = PendingInviteStore(defaults: d)
        try a.append(rec("a"))
        b.reload()
        XCTAssertEqual(b.records.map(\.pairingId), ["a"])
        d.set(Data("not json".utf8), forKey: listKey)
        b.reload()
        XCTAssertEqual(b.records.map(\.pairingId), ["a"], "解码失败不当空表")
        d.set(nil, forKey: listKey)
        b.reload()
        XCTAssertTrue(b.records.isEmpty, "键不存在才是空表")
    }
}
