import XCTest
@testable import ChencangShared

final class BubbleMenuTests: XCTestCase {
    private func message(_ direction: ChatMessage.Direction, kind: MessageKind = .text,
                         wire: String? = nil) -> ChatMessage {
        ChatMessage(id: UUID().uuidString, peerId: "alice", direction: direction, body: "x", timestamp: Date(),
                    status: direction == .outgoing ? .sealed : .received, kind: kind, wire: wire)
    }

    func testOwnTextWithWire() {
        XCTAssertEqual(BubbleMenu.items(for: message(.outgoing, wire: "🔒W")),
                       [.share, .copyEncrypted, .copyText, .delete])
    }

    func testOwnTextWithoutWire() {
        XCTAssertEqual(BubbleMenu.items(for: message(.outgoing)), [.copyText, .delete])
    }

    func testIncomingText() {
        XCTAssertEqual(BubbleMenu.items(for: message(.incoming, wire: "🔒W")), [.copyText, .delete])
    }

    func testUnsupported() {
        XCTAssertEqual(BubbleMenu.items(for: message(.incoming, kind: .unsupported)), [.delete])
    }

    func testTitles() {
        let own = message(.outgoing, wire: "🔒W")
        let incoming = message(.incoming)
        XCTAssertEqual(BubbleMenu.title(.share, for: own), L10n.commonShare)
        XCTAssertEqual(BubbleMenu.title(.copyEncrypted, for: own), L10n.threadCopyEncrypted)
        XCTAssertEqual(BubbleMenu.title(.copyText, for: own), L10n.threadCopyOriginal)
        XCTAssertEqual(BubbleMenu.title(.copyText, for: incoming), L10n.commonCopy)
        XCTAssertEqual(BubbleMenu.title(.delete, for: incoming), L10n.commonDelete)
    }
}
