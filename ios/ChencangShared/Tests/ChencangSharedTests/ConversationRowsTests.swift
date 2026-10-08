import XCTest
@testable import ChencangShared

final class ConversationRowsTests: XCTestCase {
    private func contact(_ id: String, _ name: String, pairedAt: TimeInterval? = nil, digest: String? = nil) -> AppGroupContact {
        AppGroupContact(id: id, displayName: name, isVerified: false, deviceId: nil,
                        acceptedInviteDigest: digest, pairedAt: pairedAt.map { Date(timeIntervalSince1970: $0) })
    }

    private func message(_ peerId: String, at ts: TimeInterval) -> ChatMessage {
        ChatMessage(id: "m-\(peerId)-\(ts)", peerId: peerId, direction: .incoming,
                    body: "body", timestamp: Date(timeIntervalSince1970: ts), status: .received)
    }

    // 没有消息的联系人也出行(配对完在会话 tab 找不到人会以为丢了、重复加)。
    func testContactsWithoutMessagesAlsoAppear() {
        let a = contact("a", "A"), b = contact("b", "B"), c = contact("c", "C")
        let rows = conversationRows(contacts: [a, b, c], latest: ["a": message("a", at: 1), "c": message("c", at: 2)])
        XCTAssertEqual(Set(rows.map(\.id)), ["a", "b", "c"])
        XCTAssertNil(rows.first { $0.id == "b" }?.last)
    }

    // 摘要种类:接受了对方邀请(有摘要)= 等对方发来第一条;发起方 / 老数据 = 还没有消息。
    func testZeroMessagePreviewKind() {
        let rows = conversationRows(contacts: [contact("acc", "接受方", digest: "d"), contact("ini", "发起方")], latest: [:])
        XCTAssertEqual(rows.first { $0.id == "acc" }?.preview, .waitingPeer)
        XCTAssertEqual(rows.first { $0.id == "ini" }?.preview, .noMessages)
    }

    // 回应还没发出去的接受方只在「配对中」:没有消息时不出行;已有消息照常出行。
    func testExcludedFingerprintsDropOnlyZeroMessageRows() {
        let rows = conversationRows(
            contacts: [contact("x", "X", digest: "d"), contact("y", "Y", digest: "d2"), contact("z", "Z")],
            latest: ["y": message("y", at: 5)], excluding: ["x", "y"])
        XCTAssertEqual(Set(rows.map(\.id)), ["y", "z"])
    }

    // 有消息按最近一条时间、没有消息按配对时间,合在一起倒序;没有配对时间的排最后。
    func testMergedOrderByTimeWithNilPairedAtLast() {
        let rows = conversationRows(
            contacts: [contact("old", "旧", pairedAt: nil), contact("p30", "P", pairedAt: 30),
                       contact("m20", "M", pairedAt: 1), contact("m40", "N", pairedAt: 2), contact("p10", "Q", pairedAt: 10)],
            latest: ["m20": message("m20", at: 20), "m40": message("m40", at: 40)])
        XCTAssertEqual(rows.map(\.id), ["m40", "p30", "m20", "p10", "old"])
        XCTAssertEqual(rows.map(\.time), [40, 30, 20, 10].map { Date(timeIntervalSince1970: $0) } + [nil])
    }

    func testSortedByLatestTimestampDescending() {
        let a = contact("a", "A"), b = contact("b", "B")
        let rows = conversationRows(contacts: [a, b], latest: ["a": message("a", at: 100), "b": message("b", at: 200)])
        XCTAssertEqual(rows.map(\.id), ["b", "a"])
    }

    func testAllExcludedGivesEmpty() {
        XCTAssertTrue(conversationRows(contacts: [contact("a", "A", digest: "d")], latest: [:], excluding: ["a"]).isEmpty)
    }

    private func order(_ names: [String]) -> [String] {
        contactRows(contacts: names.enumerated().map { contact("id\($0.offset)", $0.element) }, locale: Locale(identifier: "zh_CN"))
            .map(\.contact.displayName)
    }

    // spec §7.2:整串 zh_CN、忽略大小写比较,并列按 id。跨类顺序与 Android 真机 ICU 一致:汉字在拉丁字母之前。
    func testContactRowsSortTableS() {
        let names = ["张三", "alice", "老周", "Bob", "阿青", "联系人 a1b2c3"]
        XCTAssertEqual(order(names), ["阿青", "老周", "联系人 a1b2c3", "张三", "alice", "Bob"])
        let tie = contactRows(contacts: [contact("bb", "老周"), contact("aa", "老周")], locale: Locale(identifier: "zh_CN"))
        XCTAssertEqual(tie.map(\.contact.id), ["aa", "bb"])
    }

    func testContactRowsHanBeforeLatinAndPrefixOrder() {
        XCTAssertEqual(order(["老A", "老周"]), ["老周", "老A"])
        XCTAssertEqual(order(["老张1", "老张"]), ["老张", "老张1"])
    }

    func testContactRowsClassOrder() {
        let names = ["张三", "老周", "阿青", "A", "123", "😀笑", "_x", "老A"]
        XCTAssertEqual(order(names), ["_x", "😀笑", "123", "阿青", "老周", "老A", "张三", "A"])
        XCTAssertEqual(order(["b", "a", "B"]).map { $0.lowercased() }, ["a", "b", "b"])
        XCTAssertEqual(order(["张三", ""]).first, "")
    }

    func testContactRowsFollowGivenLocale() {
        let contacts = [contact("a", "汉"), contact("b", "Bob")]
        XCTAssertEqual(contactRows(contacts: contacts, locale: Locale(identifier: "en_US")).map(\.contact.displayName), ["Bob", "汉"])
        XCTAssertEqual(contactRows(contacts: contacts, locale: Locale(identifier: "zh_CN")).map(\.contact.displayName), ["汉", "Bob"])
    }

    func testContactRowsExcludesGivenFingerprints() {
        let rows = contactRows(contacts: [contact("a", "A"), contact("b", "B")], excluding: ["a"])
        XCTAssertEqual(rows.map(\.contact.id), ["b"])
    }

    // 全部有消息:纯按时间倒序。
    func testAllMessagedSortsByTimestampDescending() {
        let a = contact("a", "A")
        let b = contact("b", "B")
        let c = contact("c", "C")

        let latest: [String: ChatMessage] = [
            "a": message("a", at: 10),
            "b": message("b", at: 30),
            "c": message("c", at: 20),
        ]

        let rows = conversationRows(contacts: [a, b, c], latest: latest)

        XCTAssertEqual(rows.map(\.id), ["b", "c", "a"])
    }

    // 空联系人列表 → 空行。
    func testEmptyContactsProducesEmptyRows() {
        XCTAssertTrue(conversationRows(contacts: [], latest: [:]).isEmpty)
    }

    // Row.id 就是 contact.id;last 原样透传。
    func testRowCarriesContactAndLastMessageThrough() {
        let alice = contact("alice", "爱丽丝")
        let msg = message("alice", at: 5)
        let rows = conversationRows(contacts: [alice], latest: ["alice": msg])
        XCTAssertEqual(rows.count, 1)
        XCTAssertEqual(rows[0].id, "alice")
        XCTAssertEqual(rows[0].contact, alice)
        XCTAssertEqual(rows[0].last, msg)
    }
}
