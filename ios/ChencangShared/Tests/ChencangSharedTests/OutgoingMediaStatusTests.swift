import XCTest
@testable import ChencangShared

/// 发送端媒体状态(先分享、后上传 spec §1.3)。六个用例与 Android Task 3 一一对应;
/// 后面几条钉住两端共同的优先级(UAT R1):encrypting > 可重试的失败 > uploading > 永久失败 > 全部已上传。
final class OutgoingMediaStatusTests: XCTestCase {
    private func item(_ index: Int, _ state: MediaItem.State) -> MediaItem {
        MediaItem(index: index, kind: .image, durMs: 0, width: 10, height: 10, byteLen: 1,
                  blobSecret: Data(repeating: 1, count: 32), blobId: "B\(index)", state: state)
    }

    private func status(_ states: [MediaItem.State], shared: Bool, missing: Set<Int> = [],
                        reasons: [Int: MediaItem.UploadFailure] = [:]) -> OutgoingMediaStatus {
        let items = states.enumerated().map { pair -> MediaItem in
            var it = item(pair.offset, pair.element)
            it.uploadFailure = reasons[pair.offset]
            return it
        }
        return outgoingMediaStatus(items: items, shared: shared, ccaExists: { !missing.contains($0) })
    }

    // MARK: - 与 Android 对应的六个用例

    func testEncryptingItemIsEncrypting() {
        XCTAssertEqual(status([.encrypting, .uploading], shared: false), .encrypting)
    }

    func testSharedWithUploadingItemIsUploading() {
        XCTAssertEqual(status([.sealed, .uploading], shared: true), .uploading)
    }

    func testAllSealedIsUploaded() {
        XCTAssertEqual(status([.sealed, .sealed], shared: true), .uploaded)
    }

    func testSharedFailedWithCcaIsNotVisibleToPeer() {
        XCTAssertEqual(status([.sealed, .failed], shared: true), .notVisibleToPeer)
    }

    func testSharedFailedWithoutCcaIsPermanentlyFailedFileMissing() {
        XCTAssertEqual(status([.sealed, .failed], shared: true, missing: [1]), .permanentlyFailed(.fileMissing))
    }

    func testUnsharedFailedIsEncryptFailed() {
        XCTAssertEqual(status([.failed, .failed], shared: false), .encryptFailed)
    }

    // MARK: - 优先级与边界(两端同口径)

    func testFailedWinsOverUploading() {
        XCTAssertEqual(status([.uploading, .failed], shared: true), .notVisibleToPeer,
                       "有项需要用户点重传时,不能被别的项的「上传中」盖住")
    }

    /// UAT R1:永久失败用户做不了什么,相册里还有项在传就显示「上传中」;传完才亮原因。与 Android
    /// `anItemStillUploadingBeatsAPermanentFailure` 对应。
    func testUploadingWinsOverPermanentFailure() {
        XCTAssertEqual(status([.failed, .uploading], shared: true, reasons: [0: .tooLarge]), .uploading)
        XCTAssertEqual(status([.failed, .uploading], shared: true, missing: [0]), .uploading)
        XCTAssertEqual(status([.failed, .sealed], shared: true, reasons: [0: .tooLarge]), .permanentlyFailed(.tooLarge))
    }

    func testRetryableFailureStillWinsOverUploadingAndPermanent() {
        XCTAssertEqual(status([.failed, .uploading, .failed], shared: true, reasons: [0: .tooLarge]), .notVisibleToPeer)
    }

    func testPermanentOnlyWhenNoFailedItemCanStillBeUploaded() {
        XCTAssertEqual(status([.failed, .failed], shared: true, missing: [0]), .notVisibleToPeer,
                       "还有能重传的项:给「点击重新上传」")
        XCTAssertEqual(status([.failed, .failed], shared: true, missing: [0, 1]), .permanentlyFailed(.fileMissing))
    }

    // MARK: - 永久失败是终态(spec §1.3)

    func testTooLargeItemIsPermanentEvenThoughCcaExists() {
        XCTAssertEqual(status([.failed], shared: true, reasons: [0: .tooLarge]), .permanentlyFailed(.tooLarge))
    }

    func testRejectedItemIsPermanent() {
        XCTAssertEqual(status([.sealed, .failed], shared: true, reasons: [1: .rejected]), .permanentlyFailed(.rejected))
    }

    func testRetryableItemWinsOverPermanentOne() {
        XCTAssertEqual(status([.failed, .failed], shared: true, reasons: [0: .tooLarge]), .notVisibleToPeer,
                       "只要还有可重试的失败项,就仍给「点击重新上传」")
    }

    func testPermanentReasonPriority() {
        // 服务器给出的原因优先于「文件丢失」;过大优先于被拒
        XCTAssertEqual(status([.failed, .failed], shared: true, missing: [0], reasons: [1: .rejected]),
                       .permanentlyFailed(.rejected))
        XCTAssertEqual(status([.failed, .failed], shared: true, reasons: [0: .rejected, 1: .tooLarge]),
                       .permanentlyFailed(.tooLarge))
    }

    func testPermanentFailureDoesNotCheckCca() {
        XCTAssertEqual(
            outgoingMediaStatus(items: [{ var it = item(0, .failed); it.uploadFailure = .tooLarge; return it }()],
                                shared: true, ccaExists: { _ in XCTFail("有永久原因的项不查 .cca"); return true }),
            .permanentlyFailed(.tooLarge))
    }

    func testCcaIsOnlyCheckedForFailedItems() {
        // 已上传的项 `.cca` 早就删了,不能因此判「文件丢失」
        XCTAssertEqual(status([.sealed, .uploading], shared: true, missing: [0, 1]), .uploading)
        XCTAssertEqual(status([.sealed], shared: true, missing: [0]), .uploaded)
    }

    func testUnsharedUploadingIsStillEncrypting() {
        // 加密完、分享文本还没落库的一瞬间
        XCTAssertEqual(status([.uploading], shared: false), .encrypting)
    }

    func testUnsharedWithNothingInFlightIsEncryptFailed() {
        // 与 `outgoingMediaNeedsRetry` 同口径:封帧失败(旧数据里项全 sealed)也要能重试
        XCTAssertEqual(status([.sealed, .sealed], shared: false), .encryptFailed)
    }

    // MARK: - 消息级便捷入口

    func testMessageStatusOnlyForOutgoingMedia() {
        let items = [item(0, .failed)]
        let outgoing = ChatMessage(id: "m", peerId: "a", direction: .outgoing, body: "🔒", timestamp: Date(),
                                   status: .sealed, kind: .image, media: items)
        let incoming = ChatMessage(id: "m", peerId: "a", direction: .incoming, body: "", timestamp: Date(),
                                   status: .received, kind: .image, media: items)
        let text = ChatMessage(id: "t", peerId: "a", direction: .outgoing, body: "hi", timestamp: Date(),
                               status: .sealed)
        XCTAssertEqual(outgoing.outgoingMediaStatus(ccaExists: { _ in true }), .notVisibleToPeer)
        XCTAssertNil(incoming.outgoingMediaStatus(ccaExists: { _ in true }))
        XCTAssertNil(text.outgoingMediaStatus(ccaExists: { _ in true }))
    }

    // MARK: - 文案

    func testStatusLineCopy() {
        XCTAssertEqual(MediaLayout.outgoingStatusText(.uploading), L10n.mediaStatusUploading)
        XCTAssertEqual(MediaLayout.outgoingStatusText(.notVisibleToPeer), L10n.mediaStatusNotVisible)
        XCTAssertEqual(MediaLayout.outgoingStatusText(.permanentlyFailed(.fileMissing)), L10n.mediaFailureFileMissing)
        XCTAssertEqual(MediaLayout.outgoingStatusText(.permanentlyFailed(.tooLarge)), L10n.mediaFailureTooLarge)
        XCTAssertEqual(MediaLayout.outgoingStatusText(.permanentlyFailed(.rejected)), L10n.mediaFailureRejected)
        XCTAssertNil(MediaLayout.outgoingStatusText(.uploaded), "沿用封缄印/已送出")
        XCTAssertEqual(MediaLayout.outgoingStatusText(.encrypting), L10n.mediaStatusEncrypting, "与 Android 同口径")
        XCTAssertEqual(MediaLayout.outgoingStatusText(.encryptFailed), L10n.mediaStatusEncryptFailed, "与 Android 同口径")
    }

    func testStatusLineTapBehaviour() {
        XCTAssertEqual(MediaLayout.outgoingStatusTap(.notVisibleToPeer), .retry)
        XCTAssertEqual(MediaLayout.outgoingStatusTap(.encryptFailed), .retry, "未分享:走重新加密的重试路径")
        for reason: OutgoingFailureReason in [.tooLarge, .rejected, .fileMissing] {
            XCTAssertEqual(MediaLayout.outgoingStatusTap(.permanentlyFailed(reason)),
                           .notice(MediaLayout.outgoingStatusText(.permanentlyFailed(reason))!), "终态:点击只提示")
        }
        for other: OutgoingMediaStatus in [.encrypting, .uploading, .uploaded] {
            XCTAssertNil(MediaLayout.outgoingStatusTap(other))
        }
    }

    func testRedBangStates() {
        XCTAssertTrue(MediaLayout.showsSendFailure(.notVisibleToPeer))
        XCTAssertTrue(MediaLayout.showsSendFailure(.permanentlyFailed(.fileMissing)))
        XCTAssertTrue(MediaLayout.showsSendFailure(.permanentlyFailed(.tooLarge)))
        XCTAssertTrue(MediaLayout.showsSendFailure(.permanentlyFailed(.rejected)))
        XCTAssertTrue(MediaLayout.showsSendFailure(.encryptFailed))
        XCTAssertFalse(MediaLayout.showsSendFailure(.uploading))
        XCTAssertFalse(MediaLayout.showsSendFailure(.uploaded))
        XCTAssertFalse(MediaLayout.showsSendFailure(.encrypting))
    }

    func testListPreviewPrefix() {
        XCTAssertEqual(MediaLayout.listPreviewPrefix(.notVisibleToPeer), L10n.mediaUnsentPrefix)
        for other: OutgoingMediaStatus? in [
            nil, .encrypting, .uploading, .uploaded, .encryptFailed,
            .permanentlyFailed(.fileMissing), .permanentlyFailed(.tooLarge), .permanentlyFailed(.rejected),
        ] {
            XCTAssertNil(MediaLayout.listPreviewPrefix(other), "\(String(describing: other))")
        }
    }
}
