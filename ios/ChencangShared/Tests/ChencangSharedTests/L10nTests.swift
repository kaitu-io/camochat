import XCTest
@testable import ChencangShared

/// Runtime check that the generated resources (design/strings → Resources/*.lproj)
/// are packaged into the SwiftPM resource bundle and that `L10n` reads them.
/// Explicit per-language lookups keep the test independent of this machine's
/// language settings.
///
/// Note: command-line SwiftPM (`swift test`) lowercases localization folders when it
/// copies them (`zh-Hans.lproj` → `zh-hans.lproj`), so Foundation does not list
/// zh-Hans in `Bundle.module.localizations` here. Xcode's build keeps the case
/// (verified in the built .app/.appex), so this only affects the macOS test host.
/// The lookup below therefore matches the folder name case-insensitively.
final class L10nTests: XCTestCase {
    private func lproj(_ name: String) throws -> Bundle {
        let root = try XCTUnwrap(Bundle.module.resourceURL)
        let entries = try FileManager.default.contentsOfDirectory(atPath: root.path)
        let dir = try XCTUnwrap(
            entries.first { $0.lowercased() == "\(name).lproj".lowercased() },
            "\(name).lproj missing from Bundle.module (\(entries))"
        )
        return try XCTUnwrap(Bundle(url: root.appendingPathComponent(dir)))
    }

    func testBundleShipsEnglishAndSimplifiedChinese() throws {
        XCTAssertNoThrow(try lproj("en"))
        XCTAssertNoThrow(try lproj("zh-Hans"))
        XCTAssertEqual(Bundle.module.developmentLocalization, "en")
    }

    func testEnglishAndChineseLookups() throws {
        XCTAssertEqual(try lproj("en").localizedString(forKey: "status_shared", value: nil, table: nil), "Shared")
        XCTAssertEqual(try lproj("zh-Hans").localizedString(forKey: "status_shared", value: nil, table: nil), "已分享")
    }

    func testL10nFollowsBundlePreferredLocalization() throws {
        let preferred = try XCTUnwrap(Bundle.module.preferredLocalizations.first)
        let expected = try lproj(preferred).localizedString(forKey: "status_shared", value: nil, table: nil)
        XCTAssertEqual(L10n.statusShared, expected)
        XCTAssertTrue(["Shared", "已分享"].contains(L10n.statusShared))
        XCTAssertNotEqual(L10n.statusShared, "status_shared", "key fell through: resource missing")
    }

    func testEnglishPluralForms() throws {
        let format = try lproj("en").localizedString(forKey: "media_photo_count", value: nil, table: nil)
        let en = Locale(identifier: "en_US")
        XCTAssertEqual(String(format: format, locale: en, 1), "1 photo")
        XCTAssertEqual(String(format: format, locale: en, 3), "3 photos")
    }

    func testChinesePluralForm() throws {
        let format = try lproj("zh-Hans").localizedString(forKey: "media_photo_count", value: nil, table: nil)
        XCTAssertEqual(String(format: format, locale: Locale(identifier: "zh_Hans_CN"), 1), "1 张图片")
    }

    func testL10nPluralAccessor() {
        XCTAssertTrue(["1 photo", "1 张图片"].contains(L10n.mediaPhotoCount(1)), L10n.mediaPhotoCount(1))
    }
}
