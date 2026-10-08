import XCTest
@testable import ChencangShared

/// 终审 10:转发入口只在本机有明文时给;「转发全部」要求相册每一张都有明文。
final class ForwardMenuTests: XCTestCase {
    private func album(_ count: Int, kind: MessageKind = .image) -> ChatMessage {
        let items = (0..<count).map {
            MediaItem(index: $0, kind: kind == .image ? .image : .video, durMs: 0, width: 10, height: 10, byteLen: 1,
                      blobSecret: Data(repeating: 1, count: 32), blobId: "B\($0)", state: .ready)
        }
        return ChatMessage(id: "m", peerId: "alice", direction: .incoming, body: "", timestamp: Date(),
                           status: .received, kind: kind, media: items)
    }

    func testAllDownloadedAlbumOffersBoth() {
        let o = ForwardMenu.options(message: album(3), index: 1) { _ in true }
        XCTAssertEqual(o, .init(single: true, allCount: 3))
    }

    func testOneMissingItemHidesForwardAll() {
        let o = ForwardMenu.options(message: album(3), index: 0) { $0 != 2 }
        XCTAssertEqual(o, .init(single: true, allCount: nil))
    }

    func testThisItemMissingHidesSingleButAllStillNeedsEveryItem() {
        XCTAssertEqual(ForwardMenu.options(message: album(3), index: 2) { $0 != 2 },
                       .init(single: false, allCount: nil))
    }

    func testSingleImageHasNoForwardAll() {
        XCTAssertEqual(ForwardMenu.options(message: album(1), index: 0) { _ in true },
                       .init(single: true, allCount: nil))
    }

    func testNonImageNeverOffersForwardAll() {
        XCTAssertEqual(ForwardMenu.options(message: album(2, kind: .video), index: 0) { _ in true },
                       .init(single: true, allCount: nil))
    }

    func testNothingDownloadedOffersNothing() {
        XCTAssertEqual(ForwardMenu.options(message: album(2), index: 0) { _ in false },
                       .init(single: false, allCount: nil))
    }
}
