import XCTest
@testable import ChencangShared

final class OnboardingGateTests: XCTestCase {
    func testPassesThroughWhenNotOnboarding() {
        var gate = OnboardingGate()
        XCTAssertEqual(gate.offer(.initiator, onboarding: false), .initiator)
        XCTAssertNil(gate.release())
    }

    func testHoldsDuringOnboardingAndReleasesOnce() {
        var gate = OnboardingGate()
        XCTAssertNil(gate.offer(.incoming(wire: "w"), onboarding: true))
        XCTAssertEqual(gate.release(), .incoming(wire: "w"))
        XCTAssertNil(gate.release())
    }

    func testLaterOfferReplacesEarlier() {
        var gate = OnboardingGate()
        XCTAssertNil(gate.offer(.incoming(wire: "a"), onboarding: true))
        XCTAssertNil(gate.offer(.initiator, onboarding: true))
        XCTAssertEqual(gate.release(), .initiator)
        XCTAssertNil(gate.release())
    }
}
