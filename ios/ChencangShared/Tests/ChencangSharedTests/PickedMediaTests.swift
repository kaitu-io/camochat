import XCTest
@testable import ChencangShared

final class PickedMediaTests: XCTestCase {
    func testImagesGroupIntoOneMessageVideosSeparately() {
        let v1 = URL(fileURLWithPath: "/tmp/a.mov")
        let v2 = URL(fileURLWithPath: "/tmp/b.mov")
        let batch = PickedMedia.batches([.image(Data([1])), .video(v1), .image(Data([2])), .video(v2)])
        XCTAssertEqual(batch.images, [Data([1]), Data([2])])
        XCTAssertEqual(batch.videos, [v1, v2])
    }

    func testAtMostNineImagesPerMessage() {
        let picks = (0..<12).map { PickedMedia.image(Data([UInt8($0)])) }
        XCTAssertEqual(PickedMedia.batches(picks).images.count, 9)
    }
}
