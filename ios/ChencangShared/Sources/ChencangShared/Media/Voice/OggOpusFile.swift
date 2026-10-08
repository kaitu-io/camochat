import Foundation

public enum OggOpusError: Error, Equatable {
    case notOgg
    case badCRC(page: Int)
    case missingOpusHead
    case truncated
}

/// 最小 Ogg Opus 封装/解封装(RFC 3533 + RFC 7845):单逻辑流、单声道 48 kHz、每包 20 ms。
/// 封装时每页只放整包(最多 50 包 ≈ 1 秒,且不超过 255 个 lacing 段);解封装支持跨页续包(兼容 Android MediaMuxer 的输出)。
public enum OggOpusFile {
    public static let sampleRate = 48_000
    public static let frameSamples = 960
    public static let defaultPreSkip: UInt16 = 312
    static let packetsPerPage = 50

    public struct Parsed: Equatable {
        public let channels: UInt8
        public let preSkip: UInt16
        public let inputSampleRate: UInt32
        public let packets: [Data]
        public let finalGranule: Int64

        /// 有效时长(毫秒)= (最后 granule − pre-skip) / 48。
        public var durationMs: Int { max(0, Int((finalGranule - Int64(preSkip)) / 48)) }
    }

    public static func mux(packets: [Data], preSkip: UInt16 = defaultPreSkip, serial: UInt32 = 0x4343_0001) -> Data {
        var out = Data()
        var sequence: UInt32 = 0
        out.append(page(headerType: 0x02, granule: 0, serial: serial, sequence: sequence, packets: [opusHead(preSkip: preSkip)]))
        sequence += 1
        out.append(page(headerType: 0x00, granule: 0, serial: serial, sequence: sequence, packets: [opusTags()]))
        sequence += 1
        if packets.isEmpty {
            out.append(page(headerType: 0x04, granule: 0, serial: serial, sequence: sequence, packets: []))
            return out
        }
        // 按 lacing 预算装页:一页最多 255 个 lacing 段、最多 50 包;装不下就先封当前页。
        var granule: Int64 = 0
        var pagePackets: [Data] = []
        var pageSegments = 0
        for packet in packets {
            let segments = packet.count / 255 + 1
            if !pagePackets.isEmpty && (pageSegments + segments > 255 || pagePackets.count == packetsPerPage) {
                out.append(page(headerType: 0x00, granule: granule, serial: serial,
                                sequence: sequence, packets: pagePackets))
                sequence += 1
                pagePackets = []
                pageSegments = 0
            }
            pagePackets.append(packet)
            pageSegments += segments
            granule += Int64(frameSamples)
        }
        out.append(page(headerType: 0x04, granule: granule, serial: serial, sequence: sequence, packets: pagePackets))
        return out
    }

    public static func demux(_ data: Data) throws -> Parsed {
        let bytes = [UInt8](data)
        var pos = 0
        var pageIndex = 0
        var pending = Data()
        var logical: [Data] = []           // 第 0 个是 OpusHead,第 1 个是 OpusTags,其后是音频包
        var finalGranule: Int64 = 0
        while pos < bytes.count {
            guard pos + 27 <= bytes.count, Array(bytes[pos..<(pos + 4)]) == Array("OggS".utf8) else {
                throw OggOpusError.notOgg
            }
            let granule = Int64(bitPattern: readLE64(bytes, pos + 6))
            let segmentCount = Int(bytes[pos + 26])
            guard pos + 27 + segmentCount <= bytes.count else { throw OggOpusError.truncated }
            let lacing = Array(bytes[(pos + 27)..<(pos + 27 + segmentCount)])
            let bodyLength = lacing.reduce(0) { $0 + Int($1) }
            let pageEnd = pos + 27 + segmentCount + bodyLength
            guard pageEnd <= bytes.count else { throw OggOpusError.truncated }

            var check = Array(bytes[pos..<pageEnd])
            let stored = readLE32(check, 22)
            check[22] = 0; check[23] = 0; check[24] = 0; check[25] = 0
            guard crc32(check) == stored else { throw OggOpusError.badCRC(page: pageIndex) }

            var cursor = pos + 27 + segmentCount
            for lace in lacing {
                pending.append(contentsOf: bytes[cursor..<(cursor + Int(lace))])
                cursor += Int(lace)
                if lace < 255 {            // 小于 255 的段结束一个包
                    logical.append(pending)
                    pending = Data()
                }
            }
            if granule >= 0 { finalGranule = max(finalGranule, granule) }
            pos = pageEnd
            pageIndex += 1
        }
        // 文件读完后还有未收尾的包(最后一个 lacing 段是 255,意味着「包还没完」):
        // 说明续页丢了,不能悄悄丢弃这段数据。
        guard pending.isEmpty else { throw OggOpusError.truncated }
        guard let head = logical.first.map({ [UInt8]($0) }), head.count >= 19,
              Array(head[0..<8]) == Array("OpusHead".utf8) else {
            throw OggOpusError.missingOpusHead
        }
        return Parsed(channels: head[9],
                      preSkip: UInt16(head[10]) | UInt16(head[11]) << 8,
                      inputSampleRate: readLE32(head, 12),
                      packets: Array(logical.dropFirst(2)),
                      finalGranule: finalGranule)
    }

    /// Ogg 页校验:多项式 0x04C11DB7,初值 0,不反射,不异或输出。
    public static func crc32(_ bytes: [UInt8]) -> UInt32 {
        var crc: UInt32 = 0
        for byte in bytes {
            crc ^= UInt32(byte) << 24
            for _ in 0..<8 {
                crc = (crc & 0x8000_0000) != 0 ? (crc << 1) ^ 0x04C1_1DB7 : crc << 1
            }
        }
        return crc
    }

    /// OpusHead,19 字节(RFC 7845 §5.1)。
    static func opusHead(preSkip: UInt16) -> Data {
        var d = Data("OpusHead".utf8)
        d.append(1)                                 // version
        d.append(1)                                 // channel count
        appendLE(&d, UInt64(preSkip), bytes: 2)
        appendLE(&d, UInt64(sampleRate), bytes: 4)  // input sample rate
        appendLE(&d, 0, bytes: 2)                   // output gain
        d.append(0)                                 // channel mapping family
        return d
    }

    /// OpusTags(RFC 7845 §5.2),vendor = "chencang",无用户注释。
    static func opusTags() -> Data {
        let vendor = Data("chencang".utf8)
        var d = Data("OpusTags".utf8)
        appendLE(&d, UInt64(vendor.count), bytes: 4)
        d.append(vendor)
        appendLE(&d, 0, bytes: 4)
        return d
    }

    static func page(headerType: UInt8, granule: Int64, serial: UInt32, sequence: UInt32, packets: [Data]) -> Data {
        var lacing: [UInt8] = []
        var body = Data()
        for packet in packets {
            var n = packet.count
            while n >= 255 {
                lacing.append(255)
                n -= 255
            }
            lacing.append(UInt8(n))
            body.append(packet)
        }
        precondition(lacing.count <= 255, "一页最多 255 个 lacing 段")
        var page = Data("OggS".utf8)
        page.append(0)                              // stream structure version
        page.append(headerType)
        appendLE(&page, UInt64(bitPattern: granule), bytes: 8)
        appendLE(&page, UInt64(serial), bytes: 4)
        appendLE(&page, UInt64(sequence), bytes: 4)
        appendLE(&page, 0, bytes: 4)                // CRC 占位
        page.append(UInt8(lacing.count))
        page.append(contentsOf: lacing)
        page.append(body)
        let crc = crc32([UInt8](page))
        page[22] = UInt8(crc & 0xFF)
        page[23] = UInt8((crc >> 8) & 0xFF)
        page[24] = UInt8((crc >> 16) & 0xFF)
        page[25] = UInt8((crc >> 24) & 0xFF)
        return page
    }

    static func appendLE(_ d: inout Data, _ value: UInt64, bytes: Int) {
        for i in 0..<bytes { d.append(UInt8((value >> (8 * UInt64(i))) & 0xFF)) }
    }

    static func readLE32(_ b: [UInt8], _ o: Int) -> UInt32 {
        UInt32(b[o]) | UInt32(b[o + 1]) << 8 | UInt32(b[o + 2]) << 16 | UInt32(b[o + 3]) << 24
    }

    static func readLE64(_ b: [UInt8], _ o: Int) -> UInt64 {
        var v: UInt64 = 0
        for i in 0..<8 { v |= UInt64(b[o + i]) << (8 * UInt64(i)) }
        return v
    }
}
