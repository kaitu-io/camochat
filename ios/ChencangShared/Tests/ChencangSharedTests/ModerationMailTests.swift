import XCTest
@testable import ChencangShared

final class ModerationMailTests: XCTestCase {
    func testBlockNoticeFields() {
        let mail = ModerationMail.blockNotice(
            displayName: "小明", fingerprintHex: "0123456789abcdef0123456789abcdef",
            now: Date(timeIntervalSince1970: 1_793_000_000.789), appVersion: "1.0.3 (8)")
        XCTAssertEqual(ModerationMail.email, "hi@allnationconnect.com")
        XCTAssertEqual(mail.to, "hi@allnationconnect.com")
        XCTAssertEqual(mail.subject, "CamoChat block notice / 拉黑通知")
        XCTAssertEqual(mail.body, """
        Blocked contact: 小明
        Fingerprint: 0123456789abcdef
        Time (UTC): 2026-10-26T07:33:20Z
        App: CamoChat 1.0.3 (8) iOS

        """)
    }

    func testMailtoURLRoundTripsThroughURLComponents() throws {
        let mail = ModerationMail.Mail(to: "hi@allnationconnect.com",
                                       subject: "CamoChat report / 举报 & more+1",
                                       body: "第一行 a=b&c\n第二行 100% #tag?\n")
        let url = try XCTUnwrap(mail.mailtoURL())
        XCTAssertEqual(url.scheme, "mailto")
        let comps = try XCTUnwrap(URLComponents(url: url, resolvingAgainstBaseURL: false))
        XCTAssertEqual(comps.path, "hi@allnationconnect.com")
        let items = Dictionary(uniqueKeysWithValues: (comps.queryItems ?? []).map { ($0.name, $0.value ?? "") })
        XCTAssertEqual(items["subject"], mail.subject)
        XCTAssertEqual(items["body"], mail.body)
        XCTAssertFalse(url.absoluteString.contains("+"), "plus must be percent-encoded for mail clients")
    }

    func testFallbackTextMatchesAndroidFormat() {
        let mail = ModerationMail.Mail(to: "a@b.c", subject: "S", body: "B\n")
        XCTAssertEqual(mail.fallbackText, "To: a@b.c\nSubject: S\n\nB\n")
    }
}
