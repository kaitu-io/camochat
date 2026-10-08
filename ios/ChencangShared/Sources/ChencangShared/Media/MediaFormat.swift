import Foundation
import CoreGraphics
import ImageIO
import UniformTypeIdentifiers

/// 按字节魔数嗅探媒体格式(不信扩展名)。规则与 Android `MediaFormat.sniff` 完全一致。
public enum MediaFormat: Equatable {
    case jpeg, heic, webp, mp4, ogg, unknown

    private static let heicBrands: Set<String> = ["heic", "heix", "mif1", "msf1", "hevc", "hevx"]

    public static func sniff(_ data: Data) -> MediaFormat {
        let b = [UInt8](data.prefix(12))
        func ascii(_ r: Range<Int>) -> String? {
            r.upperBound <= b.count ? String(bytes: b[r], encoding: .isoLatin1) : nil
        }
        if b.count >= 3, b[0] == 0xFF, b[1] == 0xD8, b[2] == 0xFF { return .jpeg }
        if ascii(0..<4) == "RIFF", ascii(8..<12) == "WEBP" { return .webp }
        if ascii(4..<8) == "ftyp", let brand = ascii(8..<12) {
            return heicBrands.contains(brand) ? .heic : .mp4
        }
        if ascii(0..<4) == "OggS" { return .ogg }
        return .unknown
    }

    /// 存相册用的 UTI(仅图片格式有值)。
    public var photoUTI: String? {
        switch self {
        case .jpeg: return "public.jpeg"
        case .heic: return "public.heic"
        case .webp: return "org.webmproject.webp"
        default: return nil
        }
    }
}

/// 接收图片的缩略图 / 看图解码(Companion 的 MediaThumbnailer 调用此处),ImageIO 按内容识别格式,
/// 因此 HEIC / WebP / 旧 JPEG 一视同仁。
public enum ImageDecoding {
    public static func thumbnail(at url: URL, maxPixel: CGFloat) -> CGImage? {
        guard let source = CGImageSourceCreateWithURL(url as CFURL, nil) else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixel,
            // 在后台任务里就把像素解出来,而不是等第一次上屏时在主线程懒解码
            kCGImageSourceShouldCacheImmediately: true,
        ]
        return CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary)
    }
}

/// 存相册的决策(纯函数):按嗅探定 (数据, UTI, 文件名)。Photos 不一定收 WebP,
/// 所以 WebP 先经 ImageIO 转 HEIC(只带像素、无任何属性)再存;无法识别的格式返回 nil。
public struct PhotoSavePlan: Equatable {
    public let data: Data
    public let uti: String
    public let filename: String

    public static func make(for data: Data) -> PhotoSavePlan? {
        switch MediaFormat.sniff(data) {
        case .jpeg: return PhotoSavePlan(data: data, uti: "public.jpeg", filename: "chencang.jpg")
        case .heic: return PhotoSavePlan(data: data, uti: "public.heic", filename: "chencang.heic")
        case .webp:
            guard let heic = transcodeToHEIC(data) else { return nil }
            return PhotoSavePlan(data: heic, uti: "public.heic", filename: "chencang.heic")
        default: return nil
        }
    }

    static func transcodeToHEIC(_ data: Data) -> Data? {
        guard let src = CGImageSourceCreateWithData(data as CFData, nil),
              let image = CGImageSourceCreateImageAtIndex(src, 0, nil) else { return nil }
        let out = NSMutableData()
        guard let dest = CGImageDestinationCreateWithData(out, UTType.heic.identifier as CFString, 1, nil) else { return nil }
        CGImageDestinationAddImage(dest, image, [kCGImageDestinationLossyCompressionQuality: 0.9] as CFDictionary)
        return CGImageDestinationFinalize(dest) ? out as Data : nil
    }
}
