import Foundation

/// JPEG 段级元数据清理:删除 SOS 之前的 APP1–APP15(保留 APP2 = ICC 色彩配置)与 COM 段。
/// Exif(含 GPS、机型、时间)与 XMP 都在 APP1,Photoshop/IPTC 在 APP13。
public enum JPEGMetadata {
    public static func strip(_ jpeg: Data) -> Data? {
        let b = [UInt8](jpeg)
        guard b.count > 4, b[0] == 0xFF, b[1] == 0xD8 else { return nil }
        var out: [UInt8] = [0xFF, 0xD8]
        var i = 2
        while i + 4 <= b.count {
            guard b[i] == 0xFF else { return nil }
            let marker = b[i + 1]
            if marker == 0xDA {                       // SOS:其后是压缩数据,原样保留
                out.append(contentsOf: b[i...])
                return Data(out)
            }
            let length = Int(b[i + 2]) << 8 | Int(b[i + 3])
            guard length >= 2, i + 2 + length <= b.count else { return nil }
            let droppable = (marker >= 0xE1 && marker <= 0xEF && marker != 0xE2) || marker == 0xFE
            if !droppable {
                out.append(contentsOf: b[i..<(i + 2 + length)])
            }
            i += 2 + length
        }
        return nil
    }

    /// SOS 之前(含 SOS)出现的段标记,供测试断言。
    public static func markers(_ jpeg: Data) -> [UInt8] {
        let b = [UInt8](jpeg)
        var i = 2
        var markers: [UInt8] = []
        while i + 4 <= b.count, b[i] == 0xFF {
            let marker = b[i + 1]
            markers.append(marker)
            if marker == 0xDA { break }
            i += 2 + (Int(b[i + 2]) << 8 | Int(b[i + 3]))
        }
        return markers
    }
}
