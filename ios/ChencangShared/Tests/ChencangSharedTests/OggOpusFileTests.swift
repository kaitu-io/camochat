import XCTest
@testable import ChencangShared

final class OggOpusFileTests: XCTestCase {
    /// 大小刻意覆盖 lacing 边界:254、255(需补 0 段)、300(跨 255)。
    private func syntheticPackets(_ n: Int) -> [Data] {
        (0..<n).map { i in
            let sizes = [60, 254, 255, 300, 1]
            return Data(repeating: UInt8(i % 256), count: sizes[i % sizes.count])
        }
    }

    /// 手搭一页原始字节:`OggOpusFile.page` 只会拼「已收尾的整包」(每个包总以 <255 的段结尾),
    /// 没法产出「包还没写完就到页尾」的场景,所以这里绕开它直接按 RFC 3533 的页布局拼字节、
    /// 复用公开的 `crc32` 补 CRC,以便测试续页丢失时的截断检测。
    private func rawPage(headerType: UInt8, granule: Int64, serial: UInt32, sequence: UInt32,
                          lacing: [UInt8], body: Data) -> [UInt8] {
        var page = [UInt8]("OggS".utf8)
        page.append(0)
        page.append(headerType)
        var g = UInt64(bitPattern: granule)
        for _ in 0..<8 { page.append(UInt8(g & 0xFF)); g >>= 8 }
        var s = UInt64(serial)
        for _ in 0..<4 { page.append(UInt8(s & 0xFF)); s >>= 8 }
        var seq = UInt64(sequence)
        for _ in 0..<4 { page.append(UInt8(seq & 0xFF)); seq >>= 8 }
        page.append(contentsOf: [0, 0, 0, 0])   // CRC 占位
        page.append(UInt8(lacing.count))
        page.append(contentsOf: lacing)
        page.append(contentsOf: [UInt8](body))
        let crc = OggOpusFile.crc32(page)
        page[22] = UInt8(crc & 0xFF)
        page[23] = UInt8((crc >> 8) & 0xFF)
        page[24] = UInt8((crc >> 16) & 0xFF)
        page[25] = UInt8((crc >> 24) & 0xFF)
        return page
    }

    func testCRC32MatchesOggPolynomialVectors() {
        XCTAssertEqual(OggOpusFile.crc32(Array("123456789".utf8)), 0x89A1_897F)
        XCTAssertEqual(OggOpusFile.crc32(Array("OggS".utf8)), 0x5FB0_A94F)
        XCTAssertEqual(OggOpusFile.crc32([]), 0)
    }

    func testMuxDemuxRoundTrip() throws {
        let packets = syntheticPackets(120)
        let ogg = OggOpusFile.mux(packets: packets)
        let parsed = try OggOpusFile.demux(ogg)

        XCTAssertEqual(parsed.packets, packets)
        XCTAssertEqual(parsed.channels, 1)
        XCTAssertEqual(parsed.preSkip, 312)
        XCTAssertEqual(parsed.inputSampleRate, 48_000)
        XCTAssertEqual(parsed.finalGranule, 120 * 960)
        XCTAssertEqual(parsed.durationMs, (120 * 960 - 312) / 48)
    }

    func testPageFlagsAndOpusHeadLayout() throws {
        let ogg = [UInt8](OggOpusFile.mux(packets: syntheticPackets(3)))
        XCTAssertEqual(Array(ogg[0..<4]), Array("OggS".utf8))
        XCTAssertEqual(ogg[5], 0x02, "首页 BOS")
        // 首页只有一个 19 字节的 OpusHead 段
        XCTAssertEqual(ogg[26], 1)
        XCTAssertEqual(ogg[27], 19)
        XCTAssertEqual(Array(ogg[28..<36]), Array("OpusHead".utf8))
        XCTAssertEqual(OggOpusFile.opusHead(preSkip: 312).count, 19)
    }

    func testCorruptedPageIsRejected() {
        var bytes = [UInt8](OggOpusFile.mux(packets: syntheticPackets(120)))
        bytes[bytes.count - 1] ^= 0xFF   // 改最后一页(第 5 页,下标 4)的负载
        XCTAssertThrowsError(try OggOpusFile.demux(Data(bytes))) {
            XCTAssertEqual($0 as? OggOpusError, .badCRC(page: 4))
        }
    }

    /// 最后一页的最后一个 lacing 段是 255(=「这个包还没写完,续页里接着」),
    /// 但文件到这就没了:不能把攒了一半的包悄悄丢掉,必须报 `.truncated`。
    func testUnfinishedPacketAtEOFIsTruncated() {
        let serial: UInt32 = 0x4343_0001
        var bytes = OggOpusFile.page(headerType: 0x02, granule: 0, serial: serial, sequence: 0,
                                      packets: [OggOpusFile.opusHead(preSkip: 312)])
        bytes.append(OggOpusFile.page(headerType: 0x00, granule: 0, serial: serial, sequence: 1,
                                       packets: [OggOpusFile.opusTags()]))
        bytes.append(contentsOf: rawPage(headerType: 0x04, granule: 960, serial: serial, sequence: 2,
                                          lacing: [255], body: Data(repeating: 0xAB, count: 255)))
        XCTAssertThrowsError(try OggOpusFile.demux(bytes)) {
            XCTAssertEqual($0 as? OggOpusError, .truncated)
        }
    }

    func testLargePacketsSplitPagesByLacingBudget() throws {
        // 600 B 包占 3 段,1275 B(Opus 单包上限)占 6 段:50 个 1275 B 包 = 300 段,必须拆页而不是崩溃
        for packets in [(0..<100).map { _ in Data(repeating: 0xA5, count: 600) },
                        (0..<60).map { _ in Data(repeating: 0x5A, count: 1_275) }] {
            let ogg = OggOpusFile.mux(packets: packets)
            let parsed = try OggOpusFile.demux(ogg)
            XCTAssertEqual(parsed.packets, packets)
            XCTAssertEqual(parsed.finalGranule, Int64(packets.count * 960))
        }
    }

    func testNotOggIsRejected() {
        XCTAssertThrowsError(try OggOpusFile.demux(Data("RIFF....WAVE".utf8))) {
            XCTAssertEqual($0 as? OggOpusError, .notOgg)
        }
    }

    func testFFprobeAcceptsOurFile() throws {
        let ffprobe = "/opt/homebrew/bin/ffprobe"
        guard FileManager.default.isExecutableFile(atPath: ffprobe) else {
            print("跳过:本机没有 \(ffprobe)")
            throw XCTSkip("ffprobe 不存在")
        }
        let encoder: OpusEncoder
        do { encoder = try OpusEncoder() } catch { throw XCTSkip("本机 AudioToolbox 无 Opus 编码器:\(error)") }
        let packets = try (0..<150).map { f in
            try encoder.encode(frame: (0..<960).map { i in 0.5 * sinf(2 * .pi * 440 * Float(f * 960 + i) / 48_000) })
        }
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("cc-ogg-\(UUID().uuidString).ogg")
        try OggOpusFile.mux(packets: packets).write(to: url)
        defer { try? FileManager.default.removeItem(at: url) }

        let process = Process()
        process.executableURL = URL(fileURLWithPath: ffprobe)
        process.arguments = ["-v", "error", "-show_streams", url.path]
        let pipe = Pipe()
        process.standardOutput = pipe
        process.standardError = pipe
        try process.run()
        process.waitUntilExit()
        let output = String(data: pipe.fileHandleForReading.readDataToEndOfFile(), encoding: .utf8) ?? ""

        XCTAssertEqual(process.terminationStatus, 0, output)
        XCTAssertTrue(output.contains("codec_name=opus"), output)
        XCTAssertTrue(output.contains("sample_rate=48000"), output)
        XCTAssertTrue(output.contains("channels=1"), output)
    }
}
