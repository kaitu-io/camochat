import XCTest
import ImageIO
@testable import ChencangShared

final class MediaFormatTests: XCTestCase {
    /// cwebp -q 50 生成的 4x4 有损 WebP(等同 Android WEBP_LOSSY 产物的容器)。
    static let webp = Data([
        0x52, 0x49, 0x46, 0x46, 0x46, 0x00, 0x00, 0x00, 0x57, 0x45, 0x42, 0x50,
        0x56, 0x50, 0x38, 0x20, 0x3a, 0x00, 0x00, 0x00, 0xd0, 0x01, 0x00, 0x9d,
        0x01, 0x2a, 0x04, 0x00, 0x04, 0x00, 0x02, 0xc0, 0x4c, 0x25, 0xb0, 0x02,
        0x74, 0x01, 0x0e, 0xfe, 0x03, 0x8e, 0x00, 0x00, 0xf9, 0x4e, 0x05, 0xff,
        0x6f, 0x2f, 0x8d, 0x14, 0xba, 0x9d, 0x41, 0xed, 0xdd, 0xc8, 0x70, 0x84,
        0xef, 0xab, 0xca, 0x96, 0xd6, 0xd4, 0xe0, 0x51, 0x65, 0x87, 0x71, 0x90,
        0xc4, 0xde, 0x74, 0x00, 0x00, 0x00,
    ])

    private func ftyp(_ brand: String) -> Data {
        Data([0, 0, 0, 0x18]) + Data("ftyp".utf8) + Data(brand.utf8) + Data(count: 12)
    }

    func testSniff() {
        XCTAssertEqual(MediaFormat.sniff(Data([0xFF, 0xD8, 0xFF, 0xE0, 0, 0])), .jpeg)
        XCTAssertEqual(MediaFormat.sniff(Self.webp), .webp)
        for b in ["heic", "heix", "mif1", "msf1", "hevc", "hevx"] {
            XCTAssertEqual(MediaFormat.sniff(ftyp(b)), .heic, b)
        }
        for b in ["isom", "mp42", "avc1"] { XCTAssertEqual(MediaFormat.sniff(ftyp(b)), .mp4, b) }
        XCTAssertEqual(MediaFormat.sniff(Data("OggS".utf8) + Data(count: 20)), .ogg)
        XCTAssertEqual(MediaFormat.sniff(Data()), .unknown)
        XCTAssertEqual(MediaFormat.sniff(Data("RIFF....WAVE".utf8)), .unknown)
        XCTAssertEqual(MediaFormat.sniff(Data([0xFF, 0xD8])), .unknown)
    }

    func testPhotoUTI() {
        XCTAssertEqual(MediaFormat.heic.photoUTI, "public.heic")
        XCTAssertEqual(MediaFormat.webp.photoUTI, "org.webmproject.webp")
        XCTAssertEqual(MediaFormat.jpeg.photoUTI, "public.jpeg")
        XCTAssertNil(MediaFormat.mp4.photoUTI)
    }

    func testDisplayDecoderDecodesWebpSample() throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("\(UUID().uuidString).bin")
        try Self.webp.write(to: url)
        defer { try? FileManager.default.removeItem(at: url) }
        let img = try XCTUnwrap(ImageDecoding.thumbnail(at: url, maxPixel: 1024))
        XCTAssertEqual(img.width, 4)
        XCTAssertEqual(img.height, 4)
    }

    func testPhotoSavePlanJPEGAndHEIC() throws {
        let jpeg = Data([0xFF, 0xD8, 0xFF, 0xE0, 0, 0])
        XCTAssertEqual(PhotoSavePlan.make(for: jpeg),
                       PhotoSavePlan(data: jpeg, uti: "public.jpeg", filename: "chencang.jpg"))
        let heic = Data([0, 0, 0, 0x18]) + Data("ftypheic".utf8) + Data(count: 12)
        XCTAssertEqual(PhotoSavePlan.make(for: heic),
                       PhotoSavePlan(data: heic, uti: "public.heic", filename: "chencang.heic"))
        XCTAssertNil(PhotoSavePlan.make(for: Data("junk".utf8)))
    }

    func testPhotoSavePlanTranscodesWebpToHEIC() throws {
        let plan = try XCTUnwrap(PhotoSavePlan.make(for: Self.webp))
        XCTAssertEqual(plan.uti, "public.heic")
        XCTAssertEqual(plan.filename, "chencang.heic")
        XCTAssertEqual(MediaFormat.sniff(plan.data), .heic)
        let src = try XCTUnwrap(CGImageSourceCreateWithData(plan.data as CFData, nil))
        let img = try XCTUnwrap(CGImageSourceCreateImageAtIndex(src, 0, nil))
        XCTAssertEqual(img.width, 4)
    }
}
