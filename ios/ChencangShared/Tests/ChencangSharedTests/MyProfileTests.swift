import XCTest
@testable import ChencangShared

final class MyProfileTests: XCTestCase {
    func testNormalizeMyNameTableN() {
        let ok: [(String, String)] = [
            ("  阿青\n", "阿青"), ("", ""), ("   ", ""), ("e\u{301}", "é"),
            ("一二三四五六七八", "一二三四五六七八"),
            (String(repeating: "a", count: 24), String(repeating: "a", count: 24)),
        ]
        for (raw, want) in ok { XCTAssertEqual(normalizeMyName(raw), want, "raw \(raw.debugDescription)") }
        XCTAssertEqual(normalizeMyName("e\u{301}")?.utf8.count, 2)
        let rejected = ["阿\n青", "阿\t青", "a\u{0}b", "一二三四五六七八九",
                        String(repeating: "a", count: 25), "一二三四五六七😀"]
        for raw in rejected { XCTAssertNil(normalizeMyName(raw), "raw \(raw.debugDescription)") }
    }

    func testClampMyNameInputTableC() {
        XCTAssertEqual(clampMyNameInput("一二三四五六七八九"), "一二三四五六七八")
        XCTAssertEqual(clampMyNameInput("一二三四五六七😀"), "一二三四五六七")
        XCTAssertEqual(clampMyNameInput(String(repeating: "a", count: 22) + "👍🏽"), String(repeating: "a", count: 22))
        XCTAssertEqual(clampMyNameInput(String(repeating: "字", count: 100)), String(repeating: "字", count: 8))
    }

    func testNormalizeAvatarGlyphTableG() {
        for g in ["青", "A", "7", "😀", "👍🏽", "🇨🇳"] { XCTAssertEqual(normalizeAvatarGlyph(g), .ok(g), g) }
        XCTAssertEqual(normalizeAvatarGlyph("e\u{301}"), .ok("é"))
        XCTAssertEqual(normalizeAvatarGlyph("👨‍👩‍👧‍👦"), .tooComplex)
        for g in ["青山", " ", "\n"] { XCTAssertEqual(normalizeAvatarGlyph(g), .invalid, g.debugDescription) }
        XCTAssertEqual(normalizeAvatarGlyph(""), .empty)
        XCTAssertEqual(normalizeAvatarGlyph("\u{20}\u{301}"), .invalid) // 含任一空白标量即非法
        XCTAssertEqual(lastGrapheme("青山"), "山")
        XCTAssertEqual(firstGrapheme("👍🏽老周"), "👍🏽")
        XCTAssertNil(firstGrapheme(""))
        XCTAssertNil(lastGrapheme(""))
    }

    func testClearRemovesThreeKeys() {
        let suite = "cc-myprofile-\(UUID().uuidString)"
        let d = UserDefaults(suiteName: suite)!
        defer { d.removePersistentDomain(forName: suite) }
        d.set("阿青", forKey: MyProfileKeys.name)
        d.set("青", forKey: MyProfileKeys.avatarGlyph)
        d.set(3, forKey: MyProfileKeys.avatarColor)
        d.set(true, forKey: "cc.summaryPrivacy.v1")
        MyProfileKeys.clear(defaults: d)
        XCTAssertNil(d.object(forKey: MyProfileKeys.name))
        XCTAssertNil(d.object(forKey: MyProfileKeys.avatarGlyph))
        XCTAssertNil(d.object(forKey: MyProfileKeys.avatarColor))
        XCTAssertNotNil(d.object(forKey: "cc.summaryPrivacy.v1"))
        MyProfileKeys.clear(defaults: nil) // 不崩
    }

    func testNamePromptAnswerSavesNameAndMarksDone() {
        let suite = "cc-myprofile-\(UUID().uuidString)"
        let d = UserDefaults(suiteName: suite)!
        defer { d.removePersistentDomain(forName: suite) }
        XCTAssertFalse(MyProfileKeys.isNamePromptDone(defaults: d))
        MyProfileKeys.recordNamePromptAnswer("阿青", defaults: d)
        XCTAssertEqual(MyProfileKeys.storedName(defaults: d), "阿青")
        XCTAssertTrue(MyProfileKeys.isNamePromptDone(defaults: d))
    }

    func testNamePromptSkipOnlyMarksDoneAndKeepsExistingName() {
        let suite = "cc-myprofile-\(UUID().uuidString)"
        let d = UserDefaults(suiteName: suite)!
        defer { d.removePersistentDomain(forName: suite) }
        d.set("老周", forKey: MyProfileKeys.name)
        MyProfileKeys.recordNamePromptAnswer(nil, defaults: d)
        XCTAssertEqual(MyProfileKeys.storedName(defaults: d), "老周")
        XCTAssertTrue(MyProfileKeys.isNamePromptDone(defaults: d))
        MyProfileKeys.clear(defaults: d)
        XCTAssertFalse(MyProfileKeys.isNamePromptDone(defaults: d), "wipe resets the answered flag")
        XCTAssertFalse(MyProfileKeys.isNamePromptDone(defaults: nil))
    }
}
