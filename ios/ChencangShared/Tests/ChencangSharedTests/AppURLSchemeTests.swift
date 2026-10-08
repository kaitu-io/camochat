import XCTest
@testable import ChencangShared

final class AppURLSchemeTests: XCTestCase {
    func testAppURLSchemeIsCamo() {
        XCTAssertEqual(AppURLScheme.name, "camo")
    }
}
