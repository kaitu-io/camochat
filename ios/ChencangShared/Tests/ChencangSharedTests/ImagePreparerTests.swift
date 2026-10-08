import XCTest
import CoreGraphics
import ImageIO
import UniformTypeIdentifiers
@testable import ChencangShared

final class ImagePreparerTests: XCTestCase {
    /// 造一张带 GPS 与 Exif 注释的 JPEG(模拟手机相册原图)。
    private func makeJPEG(width: Int, height: Int) -> Data {
        let ctx = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0,
                            space: CGColorSpaceCreateDeviceRGB(),
                            bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
        let gradient = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(),
                                  colors: [CGColor(red: 1, green: 0, blue: 0, alpha: 1),
                                           CGColor(red: 0, green: 0, blue: 1, alpha: 1)] as CFArray,
                                  locations: nil)!
        ctx.drawLinearGradient(gradient, start: .zero, end: CGPoint(x: width, y: height), options: [])
        let out = NSMutableData()
        let dest = CGImageDestinationCreateWithData(out, UTType.jpeg.identifier as CFString, 1, nil)!
        let props: [CFString: Any] = [
            kCGImagePropertyGPSDictionary: [
                kCGImagePropertyGPSLatitude: 31.2, kCGImagePropertyGPSLatitudeRef: "N",
                kCGImagePropertyGPSLongitude: 121.4, kCGImagePropertyGPSLongitudeRef: "E",
            ],
            kCGImagePropertyExifDictionary: [kCGImagePropertyExifUserComment: "secret"],
        ]
        CGImageDestinationAddImage(dest, ctx.makeImage()!, props as CFDictionary)
        CGImageDestinationFinalize(dest)
        return out as Data
    }

    private func pixelSize(_ jpeg: Data) -> (Int, Int) {
        let src = CGImageSourceCreateWithData(jpeg as CFData, nil)!
        let props = CGImageSourceCopyPropertiesAtIndex(src, 0, nil) as! [CFString: Any]
        return (props[kCGImagePropertyPixelWidth] as! Int, props[kCGImagePropertyPixelHeight] as! Int)
    }

    /// 造一张 raw 像素为横图、但 EXIF `Orientation` 标记要求转正为竖图的 JPEG(相机拍竖持横传感器的典型输出),
    /// 同样带 GPS + Exif 注释,验证「摆正在先、去元数据在后」不会让图片方向不对。
    private func makeOrientedJPEG(width: Int, height: Int, orientation: Int) -> Data {
        let ctx = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0,
                            space: CGColorSpaceCreateDeviceRGB(),
                            bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
        let gradient = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(),
                                  colors: [CGColor(red: 1, green: 0, blue: 0, alpha: 1),
                                           CGColor(red: 0, green: 0, blue: 1, alpha: 1)] as CFArray,
                                  locations: nil)!
        ctx.drawLinearGradient(gradient, start: .zero, end: CGPoint(x: width, y: height), options: [])
        let out = NSMutableData()
        let dest = CGImageDestinationCreateWithData(out, UTType.jpeg.identifier as CFString, 1, nil)!
        let props: [CFString: Any] = [
            kCGImagePropertyOrientation: orientation,
            kCGImagePropertyGPSDictionary: [
                kCGImagePropertyGPSLatitude: 31.2, kCGImagePropertyGPSLatitudeRef: "N",
                kCGImagePropertyGPSLongitude: 121.4, kCGImagePropertyGPSLongitudeRef: "E",
            ],
            kCGImagePropertyExifDictionary: [kCGImagePropertyExifUserComment: "secret"],
        ]
        CGImageDestinationAddImage(dest, ctx.makeImage()!, props as CFDictionary)
        CGImageDestinationFinalize(dest)
        return out as Data
    }

    /// 带 GPS + 机型 + 拍摄时间的源 JPEG(逐项覆盖校验器的四个分支)。
    private func makeRichMetadataJPEG(width: Int, height: Int) -> Data {
        let ctx = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0,
                            space: CGColorSpaceCreateDeviceRGB(),
                            bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
        ctx.setFillColor(CGColor(red: 0.2, green: 0.6, blue: 0.4, alpha: 1))
        ctx.fill(CGRect(x: 0, y: 0, width: width, height: height))
        let out = NSMutableData()
        let dest = CGImageDestinationCreateWithData(out, UTType.jpeg.identifier as CFString, 1, nil)!
        let props = richProps(gps: true, make: true, model: true, tiffDate: true, exifDate: true)
        CGImageDestinationAddImage(dest, ctx.makeImage()!, props as CFDictionary)
        CGImageDestinationFinalize(dest)
        return out as Data
    }

    private func richProps(gps: Bool = false, make: Bool = false, model: Bool = false,
                           tiffDate: Bool = false, exifDate: Bool = false) -> [CFString: Any] {
        var props: [CFString: Any] = [:]
        if gps {
            props[kCGImagePropertyGPSDictionary] = [
                kCGImagePropertyGPSLatitude: 31.2, kCGImagePropertyGPSLatitudeRef: "N",
                kCGImagePropertyGPSLongitude: 121.4, kCGImagePropertyGPSLongitudeRef: "E",
            ]
        }
        var tiff: [CFString: Any] = [:]
        if make { tiff[kCGImagePropertyTIFFMake] = "Acme" }
        if model { tiff[kCGImagePropertyTIFFModel] = "Phone 9" }
        if tiffDate {
            tiff[kCGImagePropertyTIFFDateTime] = "2026:09:30 10:00:00"
        }
        if !tiff.isEmpty { props[kCGImagePropertyTIFFDictionary] = tiff }
        if exifDate { props[kCGImagePropertyExifDictionary] = [kCGImagePropertyExifDateTimeOriginal: "2026:09:30 10:00:00"] }
        return props
    }

    private func jpeg(with props: [CFString: Any]) -> Data {
        let ctx = CGContext(data: nil, width: 20, height: 20, bitsPerComponent: 8, bytesPerRow: 0,
                            space: CGColorSpaceCreateDeviceRGB(),
                            bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
        let out = NSMutableData()
        let dest = CGImageDestinationCreateWithData(out, UTType.jpeg.identifier as CFString, 1, nil)!
        CGImageDestinationAddImage(dest, ctx.makeImage()!, props as CFDictionary)
        CGImageDestinationFinalize(dest)
        return out as Data
    }

    func testValidatorFlagsEachFieldIndividually() {
        let cases: [(String, [CFString: Any])] = [
            ("GPS", richProps(gps: true)),
            ("TIFF.Make", richProps(make: true)),
            ("TIFF.Model", richProps(model: true)),
            // ImageIO 写 JPEG 时,TIFF 字典里只有 DateTime 会被整个丢掉,所以必须与 Make 同写
            ("TIFF.Make,TIFF.DateTime", richProps(make: true, tiffDate: true)),
            ("Exif.DateTimeOriginal", richProps(exifDate: true)),
        ]
        for (hit, props) in cases {
            XCTAssertEqual(ImagePreparer.privacyViolations(in: jpeg(with: props)),
                           hit.components(separatedBy: ","), hit)
        }
        XCTAssertEqual(ImagePreparer.privacyViolations(in: jpeg(with: [:])), [])
    }

    func testRichMetadataSourceProducesCleanOutput() throws {
        let source = makeRichMetadataJPEG(width: 600, height: 400)
        XCTAssertEqual(Set(ImagePreparer.privacyViolations(in: source)),
                       ["GPS", "TIFF.Make", "TIFF.Model", "TIFF.DateTime", "Exif.DateTimeOriginal"],
                       "夹具应真的带齐这些属性")
        let prepared = try ImagePreparer.prepare(imageData: source)
        XCTAssertEqual(ImagePreparer.privacyViolations(in: prepared.plaintext), [])
        let src = CGImageSourceCreateWithData(prepared.plaintext as CFData, nil)!
        let props = CGImageSourceCopyPropertiesAtIndex(src, 0, nil) as! [CFString: Any]
        XCTAssertNil(props[kCGImagePropertyGPSDictionary])
        let tiff = props[kCGImagePropertyTIFFDictionary] as? [CFString: Any]
        XCTAssertNil(tiff?[kCGImagePropertyTIFFMake])
        XCTAssertNil(tiff?[kCGImagePropertyTIFFModel])
        XCTAssertNil(tiff?[kCGImagePropertyTIFFDateTime])
        let exif = props[kCGImagePropertyExifDictionary] as? [CFString: Any]
        XCTAssertNil(exif?[kCGImagePropertyExifDateTimeOriginal])
    }

    func testFixtureReallyCarriesExifAndGPS() {
        XCTAssertTrue(JPEGMetadata.markers(makeJPEG(width: 100, height: 80)).contains(0xE1))
    }

    func testDownscalesLongEdgeTo1920AndStripsMetadata() throws {
        let prepared = try ImagePreparer.prepare(imageData: makeJPEG(width: 4000, height: 3000))

        XCTAssertEqual(prepared.kind, .image)
        XCTAssertEqual(prepared.width, 1920)
        XCTAssertEqual(prepared.height, 1440)
        XCTAssertEqual(prepared.durMs, 0)
        XCTAssertEqual(pixelSize(prepared.plaintext).0, 1920)
        XCTAssertEqual(MediaFormat.sniff(prepared.plaintext), .heic)
        XCTAssertLessThanOrEqual(prepared.plaintext.count, 2_097_102)
        let src = CGImageSourceCreateWithData(prepared.plaintext as CFData, nil)!
        let props = CGImageSourceCopyPropertiesAtIndex(src, 0, nil) as! [CFString: Any]
        XCTAssertNil(props[kCGImagePropertyGPSDictionary])
        XCTAssertTrue(ImagePreparer.privacyViolations(in: prepared.plaintext).isEmpty)
    }

    func testValidatorDetectsLeaks() {
        XCTAssertEqual(ImagePreparer.privacyViolations(in: makeJPEG(width: 50, height: 50)), ["GPS"])
        XCTAssertThrowsError(try ImagePreparer.verifyNoPrivacyMetadata(makeJPEG(width: 50, height: 50))) {
            XCTAssertEqual($0 as? MediaPrepError, .imageMetadataLeak(reason: "GPS"))
        }
        XCTAssertEqual(ImagePreparer.privacyViolations(in: Data("x".utf8)), ["unreadable"])
    }

    func testJPEGFallbackPathStillStrips() throws {
        let image = CGImageSourceCreateThumbnailAtIndex(
            CGImageSourceCreateWithData(makeJPEG(width: 200, height: 100) as CFData, nil)!, 0,
            [kCGImageSourceCreateThumbnailFromImageAlways: true] as CFDictionary)!
        let jpeg = try XCTUnwrap(ImagePreparer.encodeJPEG(image, quality: 0.7))
        XCTAssertEqual(MediaFormat.sniff(jpeg), .jpeg)
        XCTAssertTrue(ImagePreparer.privacyViolations(in: jpeg).isEmpty)
        XCTAssertFalse(JPEGMetadata.markers(jpeg).contains(0xE1))
    }

    func testPortraitStaysPortrait() throws {
        let prepared = try ImagePreparer.prepare(imageData: makeJPEG(width: 3000, height: 4000))
        XCTAssertEqual(prepared.width, 1440)
        XCTAssertEqual(prepared.height, 1920)
    }

    func testSmallImageIsNotUpscaled() throws {
        let prepared = try ImagePreparer.prepare(imageData: makeJPEG(width: 800, height: 600))
        XCTAssertEqual(prepared.width, 800)
        XCTAssertEqual(prepared.height, 600)
    }

    /// 4:3 横向像素缓冲(4000x3000)但打了 Orientation=6/8(旋转 90°摆正为竖图)——
    /// 期望输出与 `testPortraitStaysPortrait`(原生竖图 3000x4000)一样落在 1440x1920,
    /// 且方向标记被去掉后不会倒回横图。
    func testAppliesExifOrientationBeforeDownscalingAndStripping() throws {
        for orientation in [6, 8] {
            let jpeg = makeOrientedJPEG(width: 4000, height: 3000, orientation: orientation)

            // 先确认造出来的测试夹具确实带方向标记(不是白测)。
            let rawSrc = CGImageSourceCreateWithData(jpeg as CFData, nil)!
            let rawProps = CGImageSourceCopyPropertiesAtIndex(rawSrc, 0, nil) as! [CFString: Any]
            XCTAssertEqual(rawProps[kCGImagePropertyOrientation] as? Int, orientation,
                           "夹具应带 Orientation=\(orientation)")

            let prepared = try ImagePreparer.prepare(imageData: jpeg)

            XCTAssertEqual(prepared.width, 1440, "orientation=\(orientation) 应摆正为竖图后再按长边 1920 缩放")
            XCTAssertEqual(prepared.height, 1920, "orientation=\(orientation) 应摆正为竖图后再按长边 1920 缩放")
            XCTAssertLessThan(prepared.width, prepared.height, "orientation=\(orientation) 摆正后应是竖图,不能倒回横图")
            let outPixels = pixelSize(prepared.plaintext)
            XCTAssertEqual(outPixels.0, 1440)
            XCTAssertEqual(outPixels.1, 1920)


            let outSrc = CGImageSourceCreateWithData(prepared.plaintext as CFData, nil)!
            let outProps = CGImageSourceCopyPropertiesAtIndex(outSrc, 0, nil) as! [CFString: Any]
            XCTAssertNil(outProps[kCGImagePropertyGPSDictionary], "orientation=\(orientation): 不得含 GPS")
            XCTAssertNil(outProps[kCGImagePropertyExifDictionary], "orientation=\(orientation): 不得含 Exif")
        }
    }

    func testGarbageIsUnreadable() {
        XCTAssertThrowsError(try ImagePreparer.prepare(imageData: Data("not an image".utf8))) {
            XCTAssertEqual($0 as? MediaPrepError, .unreadableImage)
        }
    }

    func testPickQualityStepsDownUntilUnderBudget() {
        var tried: [Double] = []
        let out = ImagePreparer.pickQuality(budget: 2_097_102) { q in
            tried.append(q)
            return Data(count: q > 0.62 ? 3_000_000 : 1_000_000)
        }
        XCTAssertEqual(tried, [0.75, 0.70, 0.65, 0.60])
        XCTAssertEqual(out?.count, 1_000_000)
    }

    func testPickQualityGivesUpAtFloor() {
        var tried: [Double] = []
        XCTAssertNil(ImagePreparer.pickQuality(budget: 10) { tried.append($0); return Data(count: 11) })
        XCTAssertEqual(tried.last, 0.45)
    }

    func testStripRejectsNonJPEG() {
        XCTAssertNil(JPEGMetadata.strip(Data("hello".utf8)))
    }

    func testPrepErrorMessages() {
        XCTAssertEqual(MediaPrepError.videoTooLong.userMessage, L10n.mediaVideoTooLong)
        XCTAssertEqual(MediaPrepError.videoTooLarge.userMessage, L10n.mediaVideoTooBig)
        XCTAssertEqual(MediaNotice.text(for: MediaPrepError.imageTooLarge), L10n.mediaImageTooBig)
    }

    // 终审 6:HEIC 目标能建但编码失败 → 整条走 JPEG 阶梯,不误报「图片太大」。
    func testHEICEncodeFailureFallsBackToJPEG() {
        var jpegTried: [Double] = []
        let out = ImagePreparer.encodePreferringHEIC(budget: 100, heic: { _ in nil },
                                                     jpeg: { jpegTried.append($0); return Data("jpeg".utf8) })
        XCTAssertEqual(out, Data("jpeg".utf8))
        XCTAssertEqual(jpegTried, [0.75])
    }

    func testHEICSuccessDoesNotTouchJPEG() {
        var jpegCalled = false
        let out = ImagePreparer.encodePreferringHEIC(budget: 100, heic: { _ in Data("heic".utf8) },
                                                     jpeg: { _ in jpegCalled = true; return Data() })
        XCTAssertEqual(out, Data("heic".utf8))
        XCTAssertFalse(jpegCalled)
    }

    func testHEICTooLargeAtEveryQualityIsTooLargeWithoutJPEG() {
        var jpegCalled = false
        let out = ImagePreparer.encodePreferringHEIC(budget: 10, heic: { _ in Data(count: 11) },
                                                     jpeg: { _ in jpegCalled = true; return Data(count: 1) })
        XCTAssertNil(out)
        XCTAssertFalse(jpegCalled)
    }

    func testNoHEICEncoderUsesJPEG() {
        let out = ImagePreparer.encodePreferringHEIC(budget: 100, heic: nil, jpeg: { _ in Data("j".utf8) })
        XCTAssertEqual(out, Data("j".utf8))
    }
}
