import Foundation
import CoreGraphics
import ImageIO
import UniformTypeIdentifiers

/// 图片预处理(spec §3.3、R8):按长边 1920 缩放(小图不放大)、按 EXIF 方向摆正、
/// HEIC 质量从 0.75 逐步降到 0.45 直到 ≤ 图片明文预算(2097102);HEIC 编码器不可用或编码失败时退 JPEG 同参数。
/// 编码只传像素与压缩质量、不带任何源属性,输出后再由校验器确认无 GPS/机型/时间,违规即发送失败。
public enum ImagePreparer {
    public static let qualities: [Double] = [0.75, 0.70, 0.65, 0.60, 0.55, 0.50, 0.45]

    /// 当前设备能否创建 HEIC 编码目标(极旧设备为 false,此时退 JPEG)。
    static var heicAvailable: Bool {
        CGImageDestinationCreateWithData(NSMutableData(), UTType.heic.identifier as CFString, 1, nil) != nil
    }

    public static func prepare(imageData: Data) throws -> PreparedMedia {
        guard let source = CGImageSourceCreateWithData(imageData as CFData, nil),
              let props = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let pixelWidth = props[kCGImagePropertyPixelWidth] as? Int,
              let pixelHeight = props[kCGImagePropertyPixelHeight] as? Int else {
            throw MediaPrepError.unreadableImage
        }
        let maxEdge = min(max(pixelWidth, pixelHeight), MediaLimits.imageLongEdge)
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,     // 按 EXIF 方向摆正
            kCGImageSourceThumbnailMaxPixelSize: maxEdge,
            kCGImageSourceShouldCacheImmediately: true,
        ]
        guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else {
            throw MediaPrepError.unreadableImage
        }
        guard let encoded = encodePreferringHEIC(
            budget: MediaKind.image.plaintextBudget,
            heic: heicAvailable ? { encodeHEIC(image, quality: $0) } : nil,
            jpeg: { encodeJPEG(image, quality: $0) }
        ) else {
            throw MediaPrepError.imageTooLarge
        }
        try verifyNoPrivacyMetadata(encoded)
        return PreparedMedia(kind: .image, plaintext: encoded, durMs: 0, width: image.width, height: image.height)
    }

    /// 先走 HEIC 质量阶梯;HEIC 编码目标能建、但某一档编码/finalize 失败(部分模拟器与旧硬件编码器)
    /// → 整条改走 JPEG 阶梯(`encodeJPEG` 自带 strip,之后同样过校验器),而不是误报「图片太大」。
    /// HEIC 每档都编出来了只是超预算 → nil(JPEG 只会更大,不必再试)。`heic == nil` = 编码器不可用。
    static func encodePreferringHEIC(budget: Int, heic: ((Double) -> Data?)?,
                                     jpeg: (Double) -> Data?) -> Data? {
        if let heic {
            var encoderFailed = false
            let picked = pickQuality(budget: budget) { quality in
                guard let data = heic(quality) else {
                    encoderFailed = true
                    return nil
                }
                return data
            }
            if let picked { return picked }
            if !encoderFailed { return nil }
        }
        return pickQuality(budget: budget, encode: jpeg)
    }

    public static func pickQuality(budget: Int, encode: (Double) -> Data?) -> Data? {
        for quality in qualities {
            if let data = encode(quality), data.count <= budget { return data }
        }
        return nil
    }

    static func encodeHEIC(_ image: CGImage, quality: Double) -> Data? {
        let out = NSMutableData()
        guard let dest = CGImageDestinationCreateWithData(out, UTType.heic.identifier as CFString, 1, nil) else {
            return nil
        }
        CGImageDestinationAddImage(dest, image, [kCGImageDestinationLossyCompressionQuality: quality] as CFDictionary)
        guard CGImageDestinationFinalize(dest) else { return nil }
        return out as Data
    }

    static func encodeJPEG(_ image: CGImage, quality: Double) -> Data? {
        let out = NSMutableData()
        guard let dest = CGImageDestinationCreateWithData(out, UTType.jpeg.identifier as CFString, 1, nil) else {
            return nil
        }
        CGImageDestinationAddImage(dest, image, [kCGImageDestinationLossyCompressionQuality: quality] as CFDictionary)
        guard CGImageDestinationFinalize(dest) else { return nil }
        return JPEGMetadata.strip(out as Data)
    }

    /// 输出的隐私属性违规项(空 = 干净):GPS 字典、TIFF Make/Model/DateTime、Exif DateTimeOriginal。
    static func privacyViolations(in data: Data) -> [String] {
        guard let src = CGImageSourceCreateWithData(data as CFData, nil),
              let props = CGImageSourceCopyPropertiesAtIndex(src, 0, nil) as? [CFString: Any] else {
            return ["unreadable"]
        }
        var hits: [String] = []
        if props[kCGImagePropertyGPSDictionary] != nil { hits.append("GPS") }
        if let tiff = props[kCGImagePropertyTIFFDictionary] as? [CFString: Any] {
            if tiff[kCGImagePropertyTIFFMake] != nil { hits.append("TIFF.Make") }
            if tiff[kCGImagePropertyTIFFModel] != nil { hits.append("TIFF.Model") }
            if tiff[kCGImagePropertyTIFFDateTime] != nil { hits.append("TIFF.DateTime") }
        }
        if let exif = props[kCGImagePropertyExifDictionary] as? [CFString: Any],
           exif[kCGImagePropertyExifDateTimeOriginal] != nil {
            hits.append("Exif.DateTimeOriginal")
        }
        return hits
    }

    static func verifyNoPrivacyMetadata(_ data: Data) throws {
        let hits = privacyViolations(in: data)
        if !hits.isEmpty {
            throw MediaPrepError.imageMetadataLeak(reason: hits.joined(separator: ","))
        }
    }
}
