import XCTest
@testable import ChencangShared

final class OpusCodecTests: XCTestCase {
    func testEncodeDecodeRoundTripKeepsLengthAndEnergy() throws {
        let encoder: OpusEncoder
        do { encoder = try OpusEncoder() } catch { throw XCTSkip("本机 AudioToolbox 无 Opus 编码器:\(error)") }
        let decoder = try OpusDecoder()

        var total = 0
        var energy: Float = 0
        for f in 0..<50 {
            let frame = (0..<960).map { i in 0.5 * sinf(2 * .pi * 440 * Float(f * 960 + i) / 48_000) }
            let packet = try encoder.encode(frame: frame)
            XCTAssertTrue((1...400).contains(packet.count), "24 kbps 下 20 ms 一包应在几十字节量级,实际 \(packet.count)")
            let pcm = try decoder.decode(packet: packet)
            total += pcm.count
            energy += pcm.reduce(0) { $0 + $1 * $1 }
        }
        XCTAssertTrue((47_000...48_000).contains(total), "解出采样数 \(total)")
        let rms = (energy / Float(total)).squareRoot()
        XCTAssertTrue((0.25...0.45).contains(rms), "0.5 振幅正弦的 RMS 应约 0.354,实际 \(rms)")
    }

    func testPreSkipComesFromEncoderPrimeInfo() throws {
        let encoder: OpusEncoder
        do { encoder = try OpusEncoder() } catch { throw XCTSkip("本机 AudioToolbox 无 Opus 编码器:\(error)") }
        XCTAssertGreaterThan(encoder.preSkip, 0)
        XCTAssertEqual(try OggOpusFile.demux(OggOpusFile.mux(packets: [], preSkip: encoder.preSkip)).preSkip, encoder.preSkip)
    }

    /// Android 端可能发 40 ms(1920 采样)的包:解码器必须整包解出,不能截在 960。
    func testDecodesLongerThan20msPackets() throws {
        let encoder: OpusEncoder
        do { encoder = try OpusEncoder(frameSamples: 1_920) } catch { throw XCTSkip("本机 AudioToolbox 无 Opus 编码器:\(error)") }
        let decoder = try OpusDecoder()
        var total = 0
        for f in 0..<10 {
            let frame = (0..<1_920).map { i in 0.5 * sinf(2 * .pi * 440 * Float(f * 1_920 + i) / 48_000) }
            total += try decoder.decode(packet: try encoder.encode(frame: frame)).count
        }
        XCTAssertTrue((18_500...19_200).contains(total), "10 个 40 ms 包应解出约 19200 采样,实际 \(total)")
    }

    /// 坏包必须报错,不能悄悄解成静音/空输出;同一个解码器随后解正常包仍要能用。
    ///
    /// 本机实测过一圈:AudioToolbox 的 Opus 解码器对内容级乱码异常宽容——哪怕是结构不合法的
    /// code-3 帧表、总时长超 RFC 6716 120 ms 上限的包,它也只会吐空/静音输出并回报「输入喂完了」,
    /// 从不报真错误;唯一能稳定拿到真实 OSStatus 失败(-50 paramErr)的是包长远超真实 Opus 编码
    /// 产出量级(本机实测阈值在 2000~4000 字节之间,这里用 8000 字节留足余量)。这正是原代码在
    /// `ioFrames == 0` 时提前 `break`、把这个真错误吞掉的场景——修复前此测试会失败(未抛错)。
    func testOversizedGarbagePacketThrowsThenValidPacketStillDecodes() throws {
        let encoder: OpusEncoder
        do { encoder = try OpusEncoder() } catch { throw XCTSkip("本机 AudioToolbox 无 Opus 编码器:\(error)") }
        let decoder = try OpusDecoder()

        let garbage = Data((0..<8_000).map { UInt8(($0 * 7 + 3) % 256) })
        XCTAssertThrowsError(try decoder.decode(packet: garbage)) {
            guard case OpusCodecError.decodeFailed = $0 else {
                return XCTFail("应报 decodeFailed,实际 \($0)")
            }
        }

        let frame = (0..<960).map { i in 0.5 * sinf(2 * .pi * 440 * Float(i) / 48_000) }
        let pcm = try decoder.decode(packet: try encoder.encode(frame: frame))
        XCTAssertFalse(pcm.isEmpty, "坏包之后,同一个解码器解正常包仍应正常出声")
    }

    func testRecordedFileRoundTripThroughOgg() throws {
        let encoder: OpusEncoder
        do { encoder = try OpusEncoder() } catch { throw XCTSkip("本机 AudioToolbox 无 Opus 编码器:\(error)") }
        let packets = try (0..<100).map { _ in try encoder.encode(frame: [Float](repeating: 0, count: 960)) }
        let parsed = try OggOpusFile.demux(OggOpusFile.mux(packets: packets))
        let decoder = try OpusDecoder()
        let samples = try parsed.packets.flatMap { try decoder.decode(packet: $0) }
        XCTAssertGreaterThan(samples.count, 95_000)
        XCTAssertEqual(parsed.durationMs, (100 * 960 - 312) / 48)
    }
}

/// 终审 minor 7 / 8:整段解码遇坏包整段失败;时长按「总采样 − pre-skip」算。
final class VoiceFileDecodeTests: XCTestCase {
    func testDurationExcludesPreSkip() {
        XCTAssertEqual(OggOpusFile.durationMs(packetCount: 277, preSkip: 312), (277 * 960 - 312) / 48)
        XCTAssertEqual(OggOpusFile.durationMs(packetCount: 277, preSkip: 312), 5_533, "5.5 秒不再显示成 6″ 那一档")
        XCTAssertEqual(OggOpusFile.durationMs(packetCount: 0, preSkip: 312), 0)
    }

    func testDecodePCMDropsPreSkip() throws {
        let encoder: OpusEncoder
        do { encoder = try OpusEncoder() } catch { throw XCTSkip("本机 AudioToolbox 无 Opus 编码器:\(error)") }
        let packets = try (0..<10).map { _ in try encoder.encode(frame: [Float](repeating: 0.1, count: 960)) }
        let samples = try OggOpusFile.decodePCM(OggOpusFile.mux(packets: packets, preSkip: encoder.preSkip))
        let decoder = try OpusDecoder()
        let raw = try packets.flatMap { try decoder.decode(packet: $0) }
        XCTAssertEqual(samples.count, raw.count - Int(encoder.preSkip))
    }

    func testDecodePCMFailsWholeFileOnOneBadPacket() throws {
        let encoder: OpusEncoder
        do { encoder = try OpusEncoder() } catch { throw XCTSkip("本机 AudioToolbox 无 Opus 编码器:\(error)") }
        var packets = try (0..<10).map { _ in try encoder.encode(frame: [Float](repeating: 0.1, count: 960)) }
        // AudioToolbox 稳定报错的坏包(见 testOversizedGarbagePacketThrowsThenValidPacketStillDecodes)
        packets[5] = Data((0..<8_000).map { UInt8(($0 * 7 + 3) % 256) })
        XCTAssertThrowsError(try OggOpusFile.decodePCM(OggOpusFile.mux(packets: packets, preSkip: encoder.preSkip)),
                             "中间一个包坏了:整段判无法播放,不能放出一段缺了一截的音频")
    }

    func testDecodePCMRejectsNonOgg() {
        XCTAssertThrowsError(try OggOpusFile.decodePCM(Data("not ogg".utf8)))
    }
}
