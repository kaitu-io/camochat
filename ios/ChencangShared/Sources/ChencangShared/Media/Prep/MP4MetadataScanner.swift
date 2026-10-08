import Foundation

/// 视频输出的隐私兜底校验(spec §4.3):扫 MP4 box 树,找出不该出现的元数据 box。
/// `VideoPreparer` 输出后调用,非空即判失败——发送报错,绝不带着位置上传。
///
/// 规则与 Android `Mp4MetadataScanner.findPrivacyBoxes` 一致:
/// - 只下钻容器 `moov/trak/mdia/minf/stbl/udta/meta`;`meta` 是 FullBox,先跳 4 字节 version/flags
///   (QuickTime 风格的 `meta` 没有 version/flags、紧跟 `hdlr`——识别到就不跳,免得错位漏扫);
/// - box 头支持 size == 1(64 位 largesize)与 size == 0(到所在范围末尾);声明长度超出范围的截到范围末尾;
/// - 命中:`©xyz`、`loci`、QuickTime `udta` 文本原子 `©day`(时间)/`©mak`(厂商)/`©mod`(机型)/
///   `©swr`/`©too`(软件)(各报自己的名字)、`XMP_`(QuickTime 布局的字面 box)、usertype 为 XMP UUID 的 `uuid`(也报成 `XMP_`)、
///   内容非空的 `ilst`;
/// - 失败即拒(fail closed):坏头(size 非 0/1 却 < 8、largesize < 16、size == 1 但放不下 16 字节头)
///   或容器嵌套超过 16 层,报 `malformed`,不静默跳过。一层末尾不足 8 字节的残余忽略(QuickTime `udta`
///   常以 4 字节 0 结尾)。完整规则见 task-6-report.md「Scanner spec」。
public enum MP4MetadataScanner {
    /// XMP 的 uuid box usertype:BE7ACFCB-97A9-42E8-9C71-999491E3AFAC。
    static let xmpUUID: [UInt8] = [0xBE, 0x7A, 0xCF, 0xCB, 0x97, 0xA9, 0x42, 0xE8,
                                   0x9C, 0x71, 0x99, 0x94, 0x91, 0xE3, 0xAF, 0xAC]
    private static let containers: Set<String> = ["moov", "trak", "mdia", "minf", "stbl", "udta", "meta"]
    private static let maxDepth = 16

    /// 按出现顺序返回命中的 box 名;空数组 = 干净。
    public static func privacyBoxes(in data: Data) -> [String] {
        let bytes = [UInt8](data)
        var hits: [String] = []
        walk(bytes, from: 0, to: bytes.count, depth: 0, hits: &hits)
        return hits
    }

    private static func walk(_ b: [UInt8], from start: Int, to end: Int, depth: Int, hits: inout [String]) {
        guard depth <= maxDepth else {
            hits.append("malformed")
            return
        }
        var offset = start
        while end - offset >= 8 {
            var size = Int(be32(b, offset))
            let type = fourCC(b, offset + 4)
            var header = 8
            if size == 1 {
                guard end - offset >= 16 else {
                    hits.append("malformed")
                    return
                }
                let large = be64(b, offset + 8)
                size = large > UInt64(Int.max) ? Int.max : Int(large)
                header = 16
            } else if size == 0 {
                size = end - offset
            }
            guard size >= header else {                     // 坏头:这一层没法再往下走,判失败
                hits.append("malformed")
                return
            }
            let boxEnd = size > end - offset ? end : offset + size
            let payload = offset + header

            switch type {
            case "©xyz", "loci", "©day", "©mak", "©mod", "©swr", "©too", "XMP_":
                hits.append(type)
            case "ilst":
                if boxEnd > payload { hits.append("ilst") }
            case "uuid":
                if boxEnd - payload >= 16, Array(b[payload..<payload + 16]) == xmpUUID { hits.append("XMP_") }
            default:
                if containers.contains(type) {
                    var childStart = payload
                    if type == "meta", !(boxEnd - payload >= 8 && fourCC(b, payload + 4) == "hdlr") {
                        childStart += 4                    // FullBox version/flags
                    }
                    if childStart < boxEnd {
                        walk(b, from: childStart, to: boxEnd, depth: depth + 1, hits: &hits)
                    }
                }
            }
            offset = boxEnd
        }
    }

    private static func be32(_ b: [UInt8], _ i: Int) -> UInt32 {
        b[i..<i + 4].reduce(0) { $0 << 8 | UInt32($1) }
    }

    private static func be64(_ b: [UInt8], _ i: Int) -> UInt64 {
        b[i..<i + 8].reduce(0) { $0 << 8 | UInt64($1) }
    }

    /// box 类型按 Latin-1 解(`©` = 0xA9)。
    private static func fourCC(_ b: [UInt8], _ i: Int) -> String {
        String(b[i..<i + 4].map { Character(Unicode.Scalar($0)) })
    }
}
