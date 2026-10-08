import XCTest
@testable import ChencangShared

final class AppInfoTests: XCTestCase {
    func testVersionTextCombinesShortVersionAndBuild() {
        XCTAssertEqual(AppInfo.versionText(info: ["CFBundleShortVersionString": "1.0", "CFBundleVersion": "4"]), "1.0 (4)")
        XCTAssertEqual(AppInfo.versionText(info: ["CFBundleShortVersionString": "1.0"]), "1.0")
        XCTAssertEqual(AppInfo.versionText(info: nil), "—")
    }

    func testSourceURLIsThePublicSourcePage() {
        XCTAssertEqual(AppInfo.sourceURL(site: "https://site.test/").absoluteString, "https://site.test/source")
    }

    func testPrivacyURLIsTheSitePrivacyPage() {
        XCTAssertEqual(AppInfo.privacyURL(site: "https://site.test/").absoluteString, "https://site.test/privacy.html")
    }
}
