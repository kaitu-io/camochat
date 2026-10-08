import XCTest
import Photos
import AVFoundation
import UIKit
import ImageIO
import CoreLocation
import UniformTypeIdentifiers

/// UAT 测试素材:runner 自己生成 3 张 JPEG、一段 20 秒与一段 70 秒的 H.264 视频写进相册,
/// 不动设备上原有的个人照片。localIdentifier 记到 `Documents/seeded-assets.txt`,
/// 收尾时 `testZZRemoveSeededMedia` 按它删除。
final class UATMediaSeedTests: UATCase {

    func testSeedMedia() throws {
        Self.markSeedStart()
        let status = requestPhotoAccess()
        XCTAssertTrue(status == .authorized || status == .limited, "runner 相册权限: \(status.rawValue)")

        let tmp = FileManager.default.temporaryDirectory
        var ids: [String] = []
        for (i, color) in [UIColor.systemRed, .systemGreen, .systemBlue].enumerated() {
            let data = Self.makeJPEG(color: color, text: "UAT 图 \(i + 1)")
            ids.append(try save {
                let request = PHAssetCreationRequest.forAsset()
                request.addResource(with: .photo, data: data, options: nil)
                return request
            })
        }
        for seconds in [20, 70] {
            let url = tmp.appendingPathComponent("uat-\(seconds)s.mp4")
            try Self.makeVideo(url: url, seconds: seconds)
            ids.append(try save {
                let request = PHAssetCreationRequest.forAsset()
                request.addResource(with: .video, fileURL: url, options: nil)
                return request
            })
        }
        record("seeded-assets", ids.joined(separator: "\n"))
        Self.appendLedger(ids)
    }

    /// 富媒体打磨 UAT(spec 2026-09-30 §4):带 GPS/厂商/拍摄时间的 4032×3024 JPEG 与 1920×1080 30fps
    /// 8 秒 H.264 MOV(QuickTime ISO6709 位置 + make/model)。源文件同时留在 runner `Documents/`
    /// (`uat-gps-src.jpg` / `uat-gps-src.mov`),宿主机取出核对「源里确实有位置」,再对照 App 的明文输出。
    func testSeedGPSMedia() throws {
        Self.markSeedStart()
        let status = requestPhotoAccess()
        XCTAssertTrue(status == .authorized || status == .limited, "runner 相册权限: \(status.rawValue)")
        let location = CLLocation(latitude: 31.2304, longitude: 121.4737)
        var ids: [String] = []

        let jpeg = Self.makeGPSJPEG()
        try jpeg.write(to: Self.documents.appendingPathComponent("uat-gps-src.jpg"))
        ids.append(try save {
            let request = PHAssetCreationRequest.forAsset()
            request.addResource(with: .photo, data: jpeg, options: nil)
            request.location = location
            return request
        })

        let mov = Self.documents.appendingPathComponent("uat-gps-src.mov")
        try Self.makeVideo(url: mov, seconds: 8, width: 1920, height: 1080, fps: 30, fileType: .mov,
                           metadata: Self.locationMetadata())
        ids.append(try save {
            let request = PHAssetCreationRequest.forAsset()
            request.addResource(with: .video, fileURL: mov, options: nil)
            request.location = location
            return request
        })
        record("seeded-assets-gps", ids.joined(separator: "\n"))
        Self.appendLedger(ids)
    }

    /// 先分享、后上传 UAT(spec 2026-09-30 share-before-upload):一段 45 秒 1280×720 30fps 的**噪点**视频
    /// (噪点压不小,转码后仍有几 MB,上传要一会儿,才看得到「分享面板先于上传完成」)+ 2 张纯色图。
    /// 文件名 `uat-sbu-45s.mp4` 同样是 runner 专有标记(`testZYOrphanSeededMedia` 认它)。
    func testSeedShareFirstMedia() throws {
        Self.markSeedStart()
        let status = requestPhotoAccess()
        XCTAssertTrue(status == .authorized || status == .limited, "runner 相册权限: \(status.rawValue)")
        var ids: [String] = []
        for (i, color) in [UIColor.systemTeal, .systemPurple].enumerated() {
            let data = Self.makeJPEG(color: color, text: "UAT SBU \(i + 1)")
            ids.append(try save {
                let request = PHAssetCreationRequest.forAsset()
                request.addResource(with: .photo, data: data, options: nil)
                return request
            })
        }
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("uat-sbu-45s.mp4")
        try Self.makeVideo(url: url, seconds: 45, width: 1280, height: 720, fps: 30, noise: true)
        ids.append(try save {
            let request = PHAssetCreationRequest.forAsset()
            request.addResource(with: .video, fileURL: url, options: nil)
            return request
        })
        record("seeded-assets-sbu", ids.joined(separator: "\n"))
        Self.appendLedger(ids)
    }

    func testZZRemoveSeededMedia() throws {
        _ = requestPhotoAccess()
        var ids: [String] = []
        for name in ["seeded-assets.txt", "seeded-assets-gps.txt", "seeded-assets-sbu.txt", Self.ledgerName] {
            let url = Self.documents.appendingPathComponent(name)
            ids += ((try? String(contentsOf: url, encoding: .utf8)) ?? "").split(separator: "\n").map(String.init)
        }
        let assets = PHAsset.fetchAssets(withLocalIdentifiers: Array(Set(ids.filter { !$0.isEmpty })), options: nil)
        guard assets.count > 0 else { return }
        var done = false
        var failure: Error?
        PHPhotoLibrary.shared().performChanges({
            PHAssetChangeRequest.deleteAssets(assets)
        }, completionHandler: { _, error in
            failure = error
            done = true
        })
        let deadline = Date().addingTimeInterval(30)
        while !done && Date() < deadline {
            acceptSystemAlert(timeout: 1, allow: ["删除", "Delete"])
        }
        XCTAssertNil(failure)
        record("removed-assets", "\(assets.count)")
    }

    // MARK: - 台账外的遗留素材

    /// 找标识符已丢失的 runner 素材(2026-09-30 第一批 GPS 图 / GPS 视频)。只认带**只有 runner 才会写**的标记的:
    /// - 图:4032×3024,位置正好是 (31.2304, 121.4737),原图 EXIF `{TIFF}Make == "UATMake"`;
    /// - 视频:原始文件名 `uat-gps-src.mov` / `uat-20s.mp4` / `uat-70s.mp4`,且(GPS 版)位置同上、时长 8 秒。
    /// 默认只列出不删;`UAT_DELETE=1` 时删除列出的这些(相册会弹系统确认框,自动点「删除」)。
    func testZYOrphanSeededMedia() throws {
        _ = requestPhotoAccess()
        let known = Set(["seeded-assets.txt", "seeded-assets-gps.txt", Self.ledgerName].flatMap { name in
            ((try? String(contentsOf: Self.documents.appendingPathComponent(name), encoding: .utf8)) ?? "")
                .split(separator: "\n").map(String.init)
        })
        let options = PHFetchOptions()
        options.predicate = NSPredicate(format: "creationDate > %@", Date().addingTimeInterval(-3 * 86400) as NSDate)
        let all = PHAsset.fetchAssets(with: options)
        var hits: [PHAsset] = []
        var lines: [String] = []
        all.enumerateObjects { asset, _, _ in
            let resources = PHAssetResource.assetResources(for: asset)
            let names = resources.map(\.originalFilename)
            let loc = asset.location?.coordinate
            let atSeedSpot = loc.map { abs($0.latitude - 31.2304) < 1e-6 && abs($0.longitude - 121.4737) < 1e-6 } ?? false
            var marker: String?
            if asset.mediaType == .image, asset.pixelWidth == 4032, asset.pixelHeight == 3024, atSeedSpot,
               let photo = resources.first(where: { $0.type == .photo }),
               Self.exifMake(of: photo) == "UATMake" {
                marker = "gps-photo exif Make=UATMake"
            } else if asset.mediaType == .video, names.contains("uat-gps-src.mov"), atSeedSpot,
                      abs(asset.duration - 8) < 0.5 {
                marker = "gps-video file=uat-gps-src.mov"
            } else if asset.mediaType == .video, names.contains(where: { $0 == "uat-20s.mp4" || $0 == "uat-70s.mp4" || $0 == "uat-sbu-45s.mp4" }) {
                marker = "video file=\(names.joined(separator: ","))"
            }
            guard let marker else { return }
            let tracked = known.contains(asset.localIdentifier)
            lines.append("\(tracked ? "tracked" : "ORPHAN") \(marker) \(asset.pixelWidth)x\(asset.pixelHeight) "
                         + "dur=\(Int(asset.duration)) created=\(asset.creationDate.map { "\($0)" } ?? "-")")
            if !tracked { hits.append(asset) }
        }
        record("orphan-seeded-media", lines.isEmpty ? "<none>" : lines.joined(separator: "\n"))
        guard env("UAT_DELETE") == "1", !hits.isEmpty else { return }
        var done = false
        var failure: Error?
        PHPhotoLibrary.shared().performChanges({
            PHAssetChangeRequest.deleteAssets(hits as NSArray)
        }, completionHandler: { _, error in
            failure = error
            done = true
        })
        let deadline = Date().addingTimeInterval(30)
        while !done && Date() < deadline {
            acceptSystemAlert(timeout: 1, allow: ["删除", "Delete"])
        }
        XCTAssertNil(failure)
        record("orphan-removed", "\(hits.count)")
    }

    /// 读原图资源字节里的 `{TIFF}Make`。
    static func exifMake(of resource: PHAssetResource) -> String? {
        var data = Data()
        var done = false
        let options = PHAssetResourceRequestOptions()
        options.isNetworkAccessAllowed = false
        PHAssetResourceManager.default().requestData(for: resource, options: options,
                                                     dataReceivedHandler: { data.append($0) },
                                                     completionHandler: { _ in done = true })
        let deadline = Date().addingTimeInterval(20)
        while !done && Date() < deadline { RunLoop.current.run(until: Date().addingTimeInterval(0.05)) }
        guard let source = CGImageSourceCreateWithData(data as CFData, nil),
              let props = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let tiff = props[kCGImagePropertyTIFFDictionary] as? [CFString: Any] else { return nil }
        return tiff[kCGImagePropertyTIFFMake] as? String
    }

    // MARK: - 台账

    /// 每次造素材都追加进这份台账(`seeded-assets.txt` / `-gps.txt` 每次被覆盖,重造一次就丢了上一批的标识符,
    /// 2026-09-30 第一批 GPS 素材就是这样没删掉的)。开造时写 `seeded-at.txt`(秒级时间戳),
    /// `UATSendTests.pickerCells` 据此拒绝挑中早于本批素材的照片(机主本人的照片)。
    static let ledgerName = "seeded-assets-ledger.txt"

    static func appendLedger(_ ids: [String]) {
        let url = documents.appendingPathComponent(ledgerName)
        let old = (try? String(contentsOf: url, encoding: .utf8)) ?? ""
        try? (old + ids.map { $0 + "\n" }.joined()).write(to: url, atomically: true, encoding: .utf8)
    }

    /// 本批素材开始造的时刻(早于其中任何一张的 creationDate)。
    static func markSeedStart() {
        try? "\(Int(Date().timeIntervalSince1970))".write(to: documents.appendingPathComponent("seeded-at.txt"),
                                                         atomically: true, encoding: .utf8)
    }

    // MARK: - helpers

    private func requestPhotoAccess() -> PHAuthorizationStatus {
        var result: PHAuthorizationStatus?
        PHPhotoLibrary.requestAuthorization(for: .readWrite) { result = $0 }
        let deadline = Date().addingTimeInterval(30)
        while result == nil && Date() < deadline {
            acceptSystemAlert(timeout: 1, allow: ["允许完全访问", "Allow Full Access", "允许访问所有照片", "允许"])
        }
        return result ?? .notDetermined
    }

    private func save(_ change: @escaping () -> PHAssetCreationRequest) throws -> String {
        var identifier: String?
        try PHPhotoLibrary.shared().performChangesAndWait {
            let request = change()
            request.creationDate = Date()
            identifier = request.placeholderForCreatedAsset?.localIdentifier
        }
        return identifier ?? ""
    }

    static func makeJPEG(color: UIColor, text: String) -> Data {
        let size = CGSize(width: 1200, height: 900)
        let image = UIGraphicsImageRenderer(size: size).image { ctx in
            color.setFill()
            ctx.fill(CGRect(origin: .zero, size: size))
            (text as NSString).draw(at: CGPoint(x: 80, y: 380), withAttributes: [
                .font: UIFont.boldSystemFont(ofSize: 120), .foregroundColor: UIColor.white,
            ])
        }
        return image.jpegData(compressionQuality: 0.8)!
    }

    /// 4032×3024 JPEG,EXIF 带 GPS(上海)、TIFF Make/Model、DateTimeOriginal。
    static func makeGPSJPEG() -> Data {
        let size = CGSize(width: 4032, height: 3024)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let image = UIGraphicsImageRenderer(size: size, format: format).image { ctx in
            UIColor.systemOrange.setFill()
            ctx.fill(CGRect(origin: .zero, size: size))
            ("UAT GPS" as NSString).draw(at: CGPoint(x: 400, y: 1300), withAttributes: [
                .font: UIFont.boldSystemFont(ofSize: 400), .foregroundColor: UIColor.white,
            ])
        }
        let out = NSMutableData()
        let dest = CGImageDestinationCreateWithData(out, UTType.jpeg.identifier as CFString, 1, nil)!
        let props: [CFString: Any] = [
            kCGImageDestinationLossyCompressionQuality: 0.85,
            kCGImagePropertyGPSDictionary: [
                kCGImagePropertyGPSLatitude: 31.2304, kCGImagePropertyGPSLatitudeRef: "N",
                kCGImagePropertyGPSLongitude: 121.4737, kCGImagePropertyGPSLongitudeRef: "E",
                kCGImagePropertyGPSAltitude: 10.0,
            ],
            kCGImagePropertyTIFFDictionary: [
                kCGImagePropertyTIFFMake: "UATMake", kCGImagePropertyTIFFModel: "UATModel",
                kCGImagePropertyTIFFSoftware: "UATSoftware",
            ],
            kCGImagePropertyExifDictionary: [
                kCGImagePropertyExifDateTimeOriginal: "2026:09:30 12:00:00",
            ],
        ]
        CGImageDestinationAddImage(dest, image.cgImage!, props as CFDictionary)
        CGImageDestinationFinalize(dest)
        return out as Data
    }

    static func locationMetadata() -> [AVMetadataItem] {
        func item(_ id: AVMetadataIdentifier, _ value: String) -> AVMetadataItem {
            let m = AVMutableMetadataItem()
            m.identifier = id
            m.value = value as NSString
            m.dataType = kCMMetadataBaseDataType_UTF8 as String
            return m
        }
        return [
            item(.quickTimeMetadataLocationISO6709, "+31.2304+121.4737+010.000/"),
            item(.quickTimeMetadataMake, "UATMake"),
            item(.quickTimeMetadataModel, "UATModel"),
            item(.quickTimeMetadataSoftware, "UATSoftware"),
        ]
    }

    static func makeVideo(url: URL, seconds: Int, width: Int = 640, height: Int = 360, fps: Int32 = 5,
                          fileType: AVFileType = .mp4, metadata: [AVMetadataItem] = [], noise: Bool = false) throws {
        try? FileManager.default.removeItem(at: url)
        let writer = try AVAssetWriter(outputURL: url, fileType: fileType)
        writer.metadata = metadata
        let input = AVAssetWriterInput(mediaType: .video, outputSettings: [
            AVVideoCodecKey: AVVideoCodecType.h264, AVVideoWidthKey: width, AVVideoHeightKey: height,
        ])
        let adaptor = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: input, sourcePixelBufferAttributes: [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
            kCVPixelBufferWidthKey as String: width, kCVPixelBufferHeightKey as String: height,
        ])
        writer.add(input)
        writer.startWriting()
        writer.startSession(atSourceTime: .zero)
        let frames = seconds * Int(fps)
        for frame in 0..<frames {
            while !input.isReadyForMoreMediaData { Thread.sleep(forTimeInterval: 0.01) }
            guard let pool = adaptor.pixelBufferPool else { break }
            var buffer: CVPixelBuffer?
            CVPixelBufferPoolCreatePixelBuffer(nil, pool, &buffer)
            guard let pb = buffer else { break }
            CVPixelBufferLockBaseAddress(pb, [])
            let ctx = CGContext(data: CVPixelBufferGetBaseAddress(pb), width: width, height: height, bitsPerComponent: 8,
                                bytesPerRow: CVPixelBufferGetBytesPerRow(pb), space: CGColorSpaceCreateDeviceRGB(),
                                bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue)!
            let hue = CGFloat(frame % 50) / 50
            ctx.setFillColor(UIColor(hue: hue, saturation: 0.8, brightness: 0.8, alpha: 1).cgColor)
            ctx.fill(CGRect(x: 0, y: 0, width: width, height: height))
            if noise {
                // 每帧随机噪点(隔 8 行填一条,够压不小又不至于太慢)
                let base = CVPixelBufferGetBaseAddress(pb)!.assumingMemoryBound(to: UInt8.self)
                let bpr = CVPixelBufferGetBytesPerRow(pb)
                for row in stride(from: frame % 2, to: height, by: 2) { arc4random_buf(base + row * bpr, width * 4) }
            }
            UIGraphicsPushContext(ctx)
            ctx.translateBy(x: 0, y: CGFloat(height))
            ctx.scaleBy(x: 1, y: -1)
            ("UAT \(seconds)s  t=\(frame / Int(fps))" as NSString).draw(at: CGPoint(x: 40, y: height * 150 / 360), withAttributes: [
                .font: UIFont.boldSystemFont(ofSize: CGFloat(48 * height / 360)), .foregroundColor: UIColor.white,
            ])
            UIGraphicsPopContext()
            CVPixelBufferUnlockBaseAddress(pb, [])
            adaptor.append(pb, withPresentationTime: CMTime(value: CMTimeValue(frame), timescale: fps))
        }
        input.markAsFinished()
        var finished = false
        writer.finishWriting { finished = true }
        while !finished { Thread.sleep(forTimeInterval: 0.05) }
        if writer.status != .completed { throw writer.error ?? NSError(domain: "uat", code: 1) }
    }
}
