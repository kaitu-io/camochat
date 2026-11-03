import XCTest
@testable import ChencangShared

final class TermsAcceptanceTests: XCTestCase {
    private func makeDefaults() -> UserDefaults {
        let name = "terms-test-\(UUID().uuidString)"
        let d = UserDefaults(suiteName: name)!
        d.removePersistentDomain(forName: name)
        return d
    }

    func testDefaultsToZeroAcceptRecordsCurrentVersionClearResets() {
        let d = makeDefaults()
        XCTAssertEqual(MyProfileKeys.termsAcceptedVersion(defaults: d), 0)
        XCTAssertFalse(MyProfileKeys.isTermsAccepted(defaults: d))
        MyProfileKeys.recordTermsAccepted(defaults: d)
        XCTAssertEqual(MyProfileKeys.termsAcceptedVersion(defaults: d), Terms.currentVersion)
        XCTAssertTrue(MyProfileKeys.isTermsAccepted(defaults: d))
        MyProfileKeys.clear(defaults: d)
        XCTAssertEqual(MyProfileKeys.termsAcceptedVersion(defaults: d), 0)
    }

    func testOlderVersionIsNotAccepted() {
        let d = makeDefaults()
        d.set(Terms.currentVersion - 1, forKey: MyProfileKeys.termsAcceptedVersion)
        XCTAssertFalse(MyProfileKeys.isTermsAccepted(defaults: d))
    }

    func testTermsURL() {
        XCTAssertEqual(AppInfo.termsURL(site: "https://x.example/").absoluteString, "https://x.example/terms.html")
    }

    func testOnboardingGateHeldUntilTermsAccepted() {
        var gate = OnboardingGate()
        XCTAssertNil(gate.offer(.initiator, onboarding: true))
        XCTAssertNotNil(gate.release())
    }
}
