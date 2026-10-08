import XCTest
@testable import ChencangShared

@MainActor
final class PairingResponseStoreTests: XCTestCase {
    private func rec(_ fp: String, digest: String = "d", at: Int64 = 10) -> PairingResponseRecord {
        PairingResponseRecord(fingerprintHex: fp, responseWire: "wire-\(fp)", inviteDigest: digest,
                              createdAtMillis: at, lastSharedAtMillis: nil)
    }

    func testPutThenRecordForFingerprint() throws {
        let s = PairingResponseStore(defaults: FakeAppGroupDefaults())
        try s.put(rec("aa"))
        XCTAssertEqual(s.record(for: "aa")?.responseWire, "wire-aa")
        try s.put(PairingResponseRecord(fingerprintHex: "aa", responseWire: "new", inviteDigest: "d",
                                        createdAtMillis: 11, lastSharedAtMillis: nil))
        XCTAssertEqual(s.records.count, 1)
        XCTAssertEqual(s.record(for: "aa")?.responseWire, "new")
        XCTAssertNil(s.record(for: "zz"))
    }

    func testFindByInviteDigest() throws {
        let s = PairingResponseStore(defaults: FakeAppGroupDefaults())
        try s.put(rec("aa", digest: "d1")); try s.put(rec("bb", digest: "d2"))
        XCTAssertEqual(s.find(inviteDigest: "d2")?.fingerprintHex, "bb")
        XCTAssertNil(s.find(inviteDigest: "d3"))
    }

    func testMarkSharedSetsTimestamp() throws {
        let s = PairingResponseStore(defaults: FakeAppGroupDefaults())
        try s.put(rec("aa"))
        try s.markShared(fingerprintHex: "aa", at: 99)
        XCTAssertEqual(s.record(for: "aa")?.lastSharedAtMillis, 99)
        try s.markShared(fingerprintHex: "nope", at: 1)   // no-op
        XCTAssertEqual(s.records.count, 1)
    }

    func testRemoveAndClear() throws {
        let s = PairingResponseStore(defaults: FakeAppGroupDefaults())
        try s.put(rec("aa")); try s.put(rec("bb"))
        try s.remove(fingerprintHex: "aa")
        XCTAssertEqual(s.records.map(\.fingerprintHex), ["bb"])
        try s.clear()
        XCTAssertTrue(s.records.isEmpty)
    }

    func testSurvivesRebuild() throws {
        let d = FakeAppGroupDefaults()
        let s1 = PairingResponseStore(defaults: d)
        try s1.put(rec("aa")); try s1.markShared(fingerprintHex: "aa", at: 5)
        XCTAssertEqual(PairingResponseStore(defaults: d).records, s1.records)
    }

    func testCorruptElementDroppedAndCorruptBlobEmpty() {
        let d = FakeAppGroupDefaults()
        d.set(Data(#"[{"x":1},{"fingerprintHex":"aa","responseWire":"w","inviteDigest":"d","createdAtMillis":1}]"#.utf8),
              forKey: "cc.pairing_responses.v1")
        XCTAssertEqual(PairingResponseStore(defaults: d).records.map(\.fingerprintHex), ["aa"])
        d.set(Data("junk".utf8), forKey: "cc.pairing_responses.v1")
        XCTAssertTrue(PairingResponseStore(defaults: d).records.isEmpty)
    }

    // U1:同 PendingInviteStore——写前重读,旧缓存不覆盖别的实例写下的记录。
    func testWriteReReadsDiskSoAStaleCacheCannotOverwriteOtherRecords() throws {
        let d = FakeAppGroupDefaults()
        let a = PairingResponseStore(defaults: d)
        let b = PairingResponseStore(defaults: d) // 缓存:空
        try a.put(rec("aa"))
        try b.put(rec("bb"))
        XCTAssertEqual(Set(b.records.map(\.fingerprintHex)), ["aa", "bb"])
        try b.markShared(fingerprintHex: "aa", at: 5)
        try a.remove(fingerprintHex: "bb") // a 的缓存是旧的
        let fresh = PairingResponseStore(defaults: d)
        XCTAssertEqual(fresh.records.map(\.fingerprintHex), ["aa"])
        XCTAssertEqual(fresh.record(for: "aa")?.lastSharedAtMillis, 5)
    }

    func testReloadPicksUpOtherWritersAndKeepsCacheWhenUndecodable() throws {
        let d = FakeAppGroupDefaults()
        let a = PairingResponseStore(defaults: d)
        let b = PairingResponseStore(defaults: d)
        try a.put(rec("aa"))
        b.reload()
        XCTAssertEqual(b.records.map(\.fingerprintHex), ["aa"])
        d.set(Data("not json".utf8), forKey: "cc.pairing_responses.v1")
        b.reload()
        XCTAssertEqual(b.records.map(\.fingerprintHex), ["aa"], "解码失败不当空表")
        d.set(nil, forKey: "cc.pairing_responses.v1")
        b.reload()
        XCTAssertTrue(b.records.isEmpty)
    }
}
