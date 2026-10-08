import XCTest
@testable import ChencangShared
import Chencang

/// Sanity-check that the Rust binding works in the SPM test host. If this
/// crashes the entire suite, the macOS slice is mis-linked.
final class BindingProbeTests: XCTestCase {
    func testDirectGenerateSecretIdentity() {
        // Use the free function rather than the convenience init so we know
        // exactly which Rust constructor we are exercising.
        let id = generateSecretIdentity()
        let pub = id.publicIdentity()
        XCTAssertEqual(pub.ikDhX25519.count, 32)
    }

    func testDirectConvenienceInit() {
        let id = SecretIdentity()
        let pub = id.publicIdentity()
        XCTAssertEqual(pub.ikDhX25519.count, 32)
    }
}
