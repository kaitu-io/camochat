import XCTest
@testable import ChencangShared

/// 表 E(头像编辑模型)。纯值模型,不碰存储;持久化由界面层的 @AppStorage 绑定承担。
final class AvatarEditorModelTests: XCTestCase {
    private let fp = "3f9c1e7a5b2d4f608192a3b4c5d6e7f8"

    private func model(glyph: String? = nil, color: Int? = nil, name: String = "") -> AvatarEditorModel {
        AvatarEditorModel(storedGlyph: glyph, storedColor: color, myName: name, myFingerprintHex: fp)
    }

    func testE1MultipleGraphemesKeepOnlyLast() {
        var m = model()
        m.inputGlyph("青山")
        XCTAssertEqual(m.storedGlyph, "山")
        XCTAssertEqual(m.preview.glyph, "山")
        XCTAssertNil(m.error)
    }

    func testE2EmptyInputFollowsName() {
        var m = model(glyph: "山", name: "阿青")
        m.inputGlyph("")
        XCTAssertNil(m.storedGlyph)
        XCTAssertEqual(m.preview.glyph, "阿")
        XCTAssertEqual(m.placeholderGlyph, "阿")
        XCTAssertNil(m.error)
    }

    func testE3TooComplexKeepsStoredAndSetsErrorThenClears() {
        var m = model(glyph: "山")
        m.inputGlyph("👨‍👩‍👧‍👦")
        XCTAssertEqual(m.storedGlyph, "山")
        XCTAssertEqual(m.error, L10n.meAvatarTooComplex)
        m.inputGlyph("青")
        XCTAssertNil(m.error)
        XCTAssertEqual(m.storedGlyph, "青")
    }

    func testWhitespaceOnlyInputClears() {
        var m = model(glyph: "山", name: "阿青")
        m.inputGlyph(" \n")
        XCTAssertNil(m.storedGlyph)
        XCTAssertEqual(m.preview.glyph, "阿")
        XCTAssertNil(m.error)
    }

    func testTrailingWhitespaceStrippedBeforeLastGrapheme() {
        var m = model()
        m.inputGlyph("山青\n")
        XCTAssertEqual(m.storedGlyph, "青")
        m.inputGlyph("青 \t")
        XCTAssertEqual(m.storedGlyph, "青")
    }

    func testComposingDoesNotNormalizeOrStore() {
        var m = model(glyph: "山")
        m.inputGlyph("q", isComposing: true)
        m.inputGlyph("qi", isComposing: true)
        XCTAssertEqual(m.storedGlyph, "山")
        XCTAssertNil(m.error)
        m.inputGlyph("气", isComposing: false)
        XCTAssertEqual(m.storedGlyph, "气")
    }

    func testComposingTooComplexDoesNotSetError() {
        var m = model()
        m.inputGlyph("👨‍👩‍👧‍👦", isComposing: true)
        XCTAssertNil(m.error)
        XCTAssertNil(m.storedGlyph)
    }

    func testSkinToneEmojiAccepted() {
        var m = model()
        m.inputGlyph("👍🏽")
        XCTAssertEqual(m.storedGlyph, "👍🏽")
        XCTAssertNil(m.error)
    }

    func testFlagAccepted() {
        var m = model()
        m.inputGlyph("🇨🇳")
        XCTAssertEqual(m.storedGlyph, "🇨🇳")
    }

    func testVariationSelectorEmojiAccepted() {
        var m = model()
        m.inputGlyph("❤️") // U+2764 U+FE0F
        XCTAssertEqual(m.storedGlyph, "❤️")
        XCTAssertNil(m.error)
    }

    func testDecomposedInputIsNormalizedToNFC() {
        var m = model()
        m.inputGlyph("e\u{301}")
        XCTAssertEqual(m.storedGlyph, "\u{E9}")
        XCTAssertEqual(m.storedGlyph?.unicodeScalars.count, 1)
    }

    func testNameClampSkippedWhileComposing() {
        let long = String(repeating: "a", count: 25)
        XCTAssertNil(rewrittenNameInput(long, isComposing: true))
        XCTAssertEqual(rewrittenNameInput(long, isComposing: false), String(repeating: "a", count: 24))
        XCTAssertNil(rewrittenNameInput("阿青", isComposing: false))
    }

    func testE4PickColorAndAuto() {
        var m = model()
        m.pickColor(3)
        XCTAssertEqual(m.storedColor, 3)
        XCTAssertEqual(m.preview.paletteIndex, 3)
        m.pickColor(nil)
        XCTAssertNil(m.storedColor)
        XCTAssertEqual(m.preview.paletteIndex, avatarPaletteIndex(seed: fp, paletteSize: 8))
        XCTAssertEqual(m.preview.paletteIndex, 1)
    }

    func testPickColorOutOfRangeIgnored() {
        var m = model(color: 2)
        m.pickColor(9)
        XCTAssertEqual(m.storedColor, 2)
    }

    func testE5StoredGlyphSurvivesNameChange() {
        let m = model(glyph: "山", name: "老周")
        XCTAssertEqual(m.preview.glyph, "山")
        XCTAssertEqual(m.placeholderGlyph, "老")
    }

    func testDefaultsWithoutAnything() {
        let m = AvatarEditorModel(storedGlyph: nil, storedColor: nil, myName: "", myFingerprintHex: nil)
        XCTAssertEqual(m.preview, AvatarSpec(glyph: L10n.meAvatarDefaultGlyph, paletteIndex: 7))
        XCTAssertEqual(m.placeholderGlyph, L10n.meAvatarDefaultGlyph)
    }
}
