import XCTest
@testable import ChencangShared

/// Acceptance checks on the packaged localization resources: both languages ship the
/// same keys with real translations, plural entries format a count, and an unsupported
/// system language falls back to English.
///
/// `swift test` on macOS lowercases `zh-Hans.lproj` to `zh-hans.lproj`, so lproj folders
/// are located case-insensitively (see `L10nTests`).
final class L10nResourcesTests: XCTestCase {
    private func lprojURL(_ name: String) throws -> URL {
        let root = try XCTUnwrap(Bundle.module.resourceURL)
        let entries = try FileManager.default.contentsOfDirectory(atPath: root.path)
        let dir = try XCTUnwrap(
            entries.first { $0.lowercased() == "\(name).lproj".lowercased() },
            "\(name).lproj missing from Bundle.module (\(entries))"
        )
        return root.appendingPathComponent(dir)
    }

    private func table(_ loc: String, _ file: String) throws -> [String: Any] {
        let url = try lprojURL(loc).appendingPathComponent(file)
        let dict = NSDictionary(contentsOf: url) as? [String: Any]
        return try XCTUnwrap(dict, "cannot parse \(url.path)")
    }

    func testUnsupportedSystemLanguageFallsBackToEnglish() {
        XCTAssertEqual(
            Bundle.preferredLocalizations(from: ["en", "zh-Hans"], forPreferences: ["ja-JP"]).first,
            "en"
        )
        XCTAssertEqual(
            Bundle.preferredLocalizations(from: ["en", "zh-Hans"], forPreferences: ["zh-Hans-CN"]).first,
            "zh-Hans"
        )
    }

    func testBothLanguagesHaveSameKeysAndNoUntranslatedValue() throws {
        let en = try table("en", "Localizable.strings")
        let zh = try table("zh-Hans", "Localizable.strings")
        XCTAssertFalse(en.isEmpty)
        XCTAssertEqual(Set(en.keys), Set(zh.keys))
        for (k, v) in en { XCTAssertNotEqual(v as? String, k, "en value equals key: \(k)") }
        for (k, v) in zh { XCTAssertNotEqual(v as? String, k, "zh-Hans value equals key: \(k)") }
    }

    func testPluralKeysFormatACount() throws {
        for loc in ["en", "zh-Hans"] {
            let plurals = try table(loc, "Localizable.stringsdict")
            if plurals.isEmpty { throw XCTSkip("no plural keys in \(loc)") }
            let bundle = try XCTUnwrap(Bundle(url: lprojURL(loc)))
            for key in plurals.keys {
                let format = bundle.localizedString(forKey: key, value: nil, table: nil)
                XCTAssertNotEqual(format, key, "\(loc): plural key not resolved: \(key)")
                let text = String(format: format, locale: Locale(identifier: loc == "en" ? "en_US" : "zh_Hans_CN"), 3)
                XCTAssertTrue(text.contains("3"), "\(loc) \(key): \(text)")
            }
        }
    }
}
