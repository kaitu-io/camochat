import XCTest
@testable import ChencangShared

final class MediaLayoutTests: XCTestCase {
    func testVoiceBubbleWidthGrowsLinearlyBetweenTokens() {
        XCTAssertEqual(MediaLayout.voiceBubbleWidth(durMs: 0), Moyu.Size.voiceBubbleMin)
        XCTAssertEqual(MediaLayout.voiceBubbleWidth(durMs: 60_000), Moyu.Size.voiceBubbleMax)
        XCTAssertEqual(MediaLayout.voiceBubbleWidth(durMs: 30_000),
                       (Moyu.Size.voiceBubbleMin + Moyu.Size.voiceBubbleMax) / 2)
        XCTAssertEqual(MediaLayout.voiceBubbleWidth(durMs: 90_000), Moyu.Size.voiceBubbleMax)
    }

    func testDurationLabels() {
        XCTAssertEqual(MediaLayout.voiceLabel(durMs: 12_000), "12″")
        XCTAssertEqual(MediaLayout.voiceLabel(durMs: 400), "1″")
        XCTAssertEqual(MediaLayout.videoLabel(durMs: 12_000), "0:12")
        XCTAssertEqual(MediaLayout.videoLabel(durMs: 60_000), "1:00")
    }

    func testThumbSizeKeepsAspectWithinTokens() {
        XCTAssertEqual(MediaLayout.thumbSize(width: 1920, height: 1080), CGSize(width: 200, height: 113))
        XCTAssertEqual(MediaLayout.thumbSize(width: 1080, height: 1920), CGSize(width: 113, height: 200))
        XCTAssertEqual(MediaLayout.thumbSize(width: 4000, height: 100), CGSize(width: 200, height: Moyu.Size.mediaThumbMin))
        XCTAssertEqual(MediaLayout.thumbSize(width: 0, height: 0),
                       CGSize(width: Moyu.Size.mediaThumbMax, height: Moyu.Size.mediaThumbMax))
    }

    func testStateTexts() {
        XCTAssertEqual(MediaLayout.stateText(.expired), L10n.mediaFailureExpired)
        XCTAssertEqual(MediaLayout.stateText(.corrupt), L10n.mediaFailureCorrupt)
        XCTAssertNil(MediaLayout.stateText(.ready))
        XCTAssertNil(MediaLayout.stateText(.pending))
    }

    func testAwaitingTexts() {
        XCTAssertEqual(MediaLayout.stateText(.awaiting), L10n.mediaAwaiting)
        XCTAssertEqual(MediaLayout.stateText(.awaiting, awaitingWindowExpired: true), L10n.mediaAwaitingStalled)
        XCTAssertEqual(MediaLayout.stateText(.expired, awaitingWindowExpired: true), L10n.mediaFailureExpired)
        XCTAssertTrue(MediaLayout.showsAwaitingSpinner(state: .awaiting, awaitingWindowExpired: false))
        XCTAssertFalse(MediaLayout.showsAwaitingSpinner(state: .awaiting, awaitingWindowExpired: true))
        XCTAssertFalse(MediaLayout.showsAwaitingSpinner(state: .pending, awaitingWindowExpired: false))
        XCTAssertFalse(MediaLayout.showsDownloadRetry(direction: .incoming, state: .awaiting),
                       "等待中不出红 !(点气泡本身重试)")
    }
}
