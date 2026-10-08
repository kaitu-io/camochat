import XCTest
@testable import ChencangShared

final class VideoAutoPlayTests: XCTestCase {
    private func item(index: Int = 0, kind: MediaKind = .video, state: MediaItem.State = .ready) -> MediaItem {
        MediaItem(index: index, kind: kind, durMs: 1_000, width: 100, height: 100, byteLen: 0,
                  blobSecret: Data(), blobId: "", state: state)
    }

    func testNoPendingNeverOpens() {
        XCTAssertFalse(VideoAutoPlay.shouldOpen(pending: nil, messageId: "m1", item: item()))
    }

    func testOpensWhenPendingMatchesAndItemIsReady() {
        let pending = VideoAutoPlay.Pending(messageId: "m1", index: 0)
        XCTAssertTrue(VideoAutoPlay.shouldOpen(pending: pending, messageId: "m1", item: item(index: 0, state: .ready)))
    }

    func testDoesNotOpenForADifferentMessage() {
        let pending = VideoAutoPlay.Pending(messageId: "m1", index: 0)
        XCTAssertFalse(VideoAutoPlay.shouldOpen(pending: pending, messageId: "m2", item: item(index: 0, state: .ready)))
    }

    func testDoesNotOpenForADifferentIndex() {
        let pending = VideoAutoPlay.Pending(messageId: "m1", index: 0)
        XCTAssertFalse(VideoAutoPlay.shouldOpen(pending: pending, messageId: "m1", item: item(index: 1, state: .ready)))
    }

    func testDoesNotOpenBeforeItemIsReady() {
        let pending = VideoAutoPlay.Pending(messageId: "m1", index: 0)
        for state: MediaItem.State in [.pending, .downloading, .expired, .corrupt] {
            XCTAssertFalse(VideoAutoPlay.shouldOpen(pending: pending, messageId: "m1", item: item(index: 0, state: state)),
                           "should not open for state \(state)")
        }
    }

    func testDoesNotOpenForNonVideoKind() {
        let pending = VideoAutoPlay.Pending(messageId: "m1", index: 0)
        XCTAssertFalse(VideoAutoPlay.shouldOpen(pending: pending, messageId: "m1",
                                                item: item(index: 0, kind: .image, state: .ready)))
    }
}
