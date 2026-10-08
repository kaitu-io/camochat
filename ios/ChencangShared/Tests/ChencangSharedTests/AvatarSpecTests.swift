import XCTest
@testable import ChencangShared

final class AvatarSpecTests: XCTestCase {
    func testContactAvatarUsesFirstGraphemeAndFingerprintColor() {
        let fp = "3f9c1e7a5b2d4f608192a3b4c5d6e7f8"
        let spec = contactAvatar(displayName: "👍🏽老周", fingerprintHex: fp)
        XCTAssertEqual(spec.glyph, "👍🏽")
        XCTAssertEqual(spec.paletteIndex, avatarPaletteIndex(seed: fp, paletteSize: 8))
    }

    func testMyAvatarTableA() {
        let fp = "3f9c1e7a5b2d4f608192a3b4c5d6e7f8" // 下标 1
        XCTAssertEqual(myAvatar(storedGlyph: nil, storedColor: nil, myName: "", myFingerprintHex: nil),
                       AvatarSpec(glyph: L10n.meAvatarDefaultGlyph, paletteIndex: 7))
        XCTAssertEqual(myAvatar(storedGlyph: nil, storedColor: nil, myName: "阿青", myFingerprintHex: nil).glyph, "阿")
        XCTAssertEqual(myAvatar(storedGlyph: "山", storedColor: nil, myName: "老周", myFingerprintHex: nil).glyph, "山")
        XCTAssertEqual(myAvatar(storedGlyph: nil, storedColor: 3, myName: "", myFingerprintHex: fp).paletteIndex, 3)
        XCTAssertEqual(myAvatar(storedGlyph: nil, storedColor: 9, myName: "", myFingerprintHex: fp).paletteIndex, 1)
        XCTAssertEqual(myAvatar(storedGlyph: nil, storedColor: nil, myName: "", myFingerprintHex: fp).paletteIndex, 1)
        XCTAssertEqual(myAvatar(storedGlyph: "", storedColor: nil, myName: "阿青", myFingerprintHex: nil).glyph, "阿")
    }

    func testContactAvatarLowercasesFingerprint() {
        for fp in ["3f9c1e7a5b2d4f608192a3b4c5d6e7f8", "abcdef0123456789abcdef0123456789", "deadbeefcafe", "fedcba"] {
            XCTAssertEqual(contactAvatar(displayName: "a", fingerprintHex: fp.uppercased()).paletteIndex,
                           avatarPaletteIndex(seed: fp, paletteSize: 8), fp)
        }
    }

    func testMyAvatarEmptyFingerprintFallsBackToMe() {
        XCTAssertEqual(myAvatar(storedGlyph: nil, storedColor: nil, myName: "", myFingerprintHex: "").paletteIndex, 7)
    }
}
