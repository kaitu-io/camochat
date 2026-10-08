import XCTest
@testable import ChencangShared

final class ThreadFormatTests: XCTestCase {
    private func msg(at t: TimeInterval) -> ChatMessage {
        ChatMessage(id: UUID().uuidString, peerId: "p", direction: .outgoing, body: "x",
                    timestamp: Date(timeIntervalSince1970: t), status: .sealed)
    }

    func testShowTimeChip_gapUnder10Min_noChip() {
        let prev = msg(at: 1_000)
        let cur = msg(at: 1_000 + 9 * 60 + 59) // 9m59s later
        XCTAssertFalse(ThreadFormat.showTimeChip(prev: prev, cur: cur))
    }

    func testShowTimeChip_gapOver10Min_showsChip() {
        let prev = msg(at: 1_000)
        let cur = msg(at: 1_000 + 10 * 60 + 1) // 10m01s later
        XCTAssertTrue(ThreadFormat.showTimeChip(prev: prev, cur: cur))
    }

    func testShowTimeChip_firstMessageAlwaysShowsChip() {
        let cur = msg(at: 1_000)
        XCTAssertTrue(ThreadFormat.showTimeChip(prev: nil, cur: cur))
    }

    private func dates() -> (now: Date, earlierSameDay: Date, yesterday: Date) {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = .current
        let now = cal.date(from: DateComponents(year: 2026, month: 8, day: 15, hour: 20, minute: 30))!
        let earlier = cal.date(from: DateComponents(year: 2026, month: 8, day: 15, hour: 9, minute: 5))!
        let yesterday = cal.date(from: DateComponents(year: 2026, month: 8, day: 14, hour: 9, minute: 5))!
        return (now, earlier, yesterday)
    }

    func testChipText_sameDay_showsTimeOnly() {
        let d = dates()
        for id in ["zh_CN", "en_US"] {
            let text = ThreadFormat.chipText(d.earlierSameDay, now: d.now, locale: Locale(identifier: id))
            XCTAssertTrue(text.contains("09:05") || text.contains("9:05"), "\(id): \(text)")
            XCTAssertFalse(text.contains("月") || text.contains("Aug"), "\(id): \(text)")
        }
    }

    // 跨日带月日,格式跟随语言(不写死 zh_CN)。
    func testChipTextFollowsLocale() {
        let d = dates()
        let zh = ThreadFormat.chipText(d.yesterday, now: d.now, locale: Locale(identifier: "zh_CN"))
        let en = ThreadFormat.chipText(d.yesterday, now: d.now, locale: Locale(identifier: "en_US"))
        XCTAssertTrue(zh.contains("月") && zh.contains("14"), zh)
        XCTAssertTrue(en.contains("Aug") && en.contains("14"), en)
        XCTAssertTrue(en.contains("09:05") || en.contains("9:05"), en)
    }

    // 时制跟随语言/用户偏好(模板 j),不强制 24 小时:en_US 默认 12 小时制带 AM/PM。
    func testChipTextFollows12HourPreferenceOfLocale() {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = .current
        let now = cal.date(from: DateComponents(year: 2026, month: 8, day: 15, hour: 20, minute: 30))!
        let afternoon = cal.date(from: DateComponents(year: 2026, month: 8, day: 15, hour: 15, minute: 5))!
        let yesterdayAfternoon = cal.date(from: DateComponents(year: 2026, month: 8, day: 14, hour: 15, minute: 5))!
        let en = Locale(identifier: "en_US")
        let sameDay = ThreadFormat.chipText(afternoon, now: now, locale: en)
        let otherDay = ThreadFormat.chipText(yesterdayAfternoon, now: now, locale: en)
        for text in [sameDay, otherDay] {
            XCTAssertTrue(text.contains("3:05") && text.contains("PM"), text)
            XCTAssertFalse(text.contains("15:05"), text)
        }
        XCTAssertTrue(otherDay.contains("Aug") && otherDay.contains("14"), otherDay)
    }
}
