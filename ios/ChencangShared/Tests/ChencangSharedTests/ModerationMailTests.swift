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

    private func reported(_ kind: MessageKind, body: String, media: [MediaItem]? = nil) -> ChatMessage {
        ChatMessage(id: "m1", peerId: "peer", direction: .incoming, body: body,
                    timestamp: Date(timeIntervalSince1970: 1_793_700_000.999), status: .received,
                    kind: kind, media: media)
    }

    func testReportTextCarriesContent() {
        let mail = ModerationMail.report(
            displayName: "老周", fingerprintHex: "0123456789abcdef0123456789abcdef",
            message: reported(.text, body: "bad\nstuff"), now: Date(), appVersion: "1.0.3")
        XCTAssertEqual(mail.to, "hi@allnationconnect.com")
        XCTAssertEqual(mail.subject, "CamoChat report / 举报")
        XCTAssertEqual(mail.body,
            "Reported contact: 老周\nFingerprint: 0123456789abcdef\nMessage time (UTC): 2026-11-03T10:00:00Z\n" +
            "Type: text\nContent:\nbad\nstuff\nApp: CamoChat 1.0.3 iOS\n")
        XCTAssertFalse(mail.body.contains("Media IDs"))
    }

    private func item(_ i: Int, _ blobId: String) -> MediaItem {
        MediaItem(index: i, kind: .image, durMs: 0, width: 1, height: 1, byteLen: 1,
                  blobSecret: Data(), blobId: blobId, state: .awaiting)
    }

    func testReportMediaListsBlobIdsAndNoContent() {
        let items = [item(0, "a"), item(1, "b")]
        let mail = ModerationMail.report(
            displayName: "A", fingerprintHex: "abcd",
            message: reported(.image, body: "caption", media: items), now: Date(), appVersion: "1.0.3")
        XCTAssertTrue(mail.body.contains("Type: image\n"))
        XCTAssertTrue(mail.body.contains("Media IDs: a,b\n"))
        XCTAssertFalse(mail.body.contains("Content:"))
        XCTAssertEqual(mail.body.split(separator: "\n").last, "App: CamoChat 1.0.3 iOS")
    }
}
