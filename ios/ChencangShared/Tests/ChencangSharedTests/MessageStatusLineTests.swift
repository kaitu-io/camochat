import XCTest
@testable import ChencangShared

/// 气泡下的状态行只说用户做过的事;收到的消息不显示状态。断言比较 `L10n`,不比较字面量。
final class MessageStatusLineTests: XCTestCase {
    private func message(_ direction: ChatMessage.Direction, _ status: ChatMessage.Status,
                         kind: MessageKind = .text, body: String = "hi") -> ChatMessage {
        ChatMessage(id: "m", peerId: "p", direction: direction, body: body,
                    timestamp: Date(timeIntervalSince1970: 1), status: status, kind: kind,
                    media: kind.isMedia ? [] : nil)
    }

    func testSealed() {
        XCTAssertEqual(MessageStatusLine.text(for: message(.outgoing, .sealed)), L10n.statusEncryptedNotSent)
    }

    func testCopied() {
        XCTAssertEqual(MessageStatusLine.text(for: message(.outgoing, .copied)), L10n.statusCopied)
    }

    func testShared() {
        XCTAssertEqual(MessageStatusLine.text(for: message(.outgoing, .shared)), L10n.statusShared)
    }

    func testIncomingHasNoStatusLine() {
        XCTAssertNil(MessageStatusLine.text(for: message(.incoming, .received)))
    }

    func testOutgoingMediaWithoutShareTextHasNoStatusLine() {
        XCTAssertNil(MessageStatusLine.text(for: message(.outgoing, .sealed, kind: .image, body: "")))
        XCTAssertEqual(MessageStatusLine.text(for: message(.outgoing, .copied, kind: .image, body: "🔒 x\n🔒W")),
                       L10n.statusCopied)
    }
}
