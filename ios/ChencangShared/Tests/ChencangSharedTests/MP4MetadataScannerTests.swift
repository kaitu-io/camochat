import XCTest
@testable import ChencangShared

/// 手工拼的最小 MP4 box 字节,覆盖 spec §4.3 的扫描规则(与 Android `Mp4MetadataScanner` 同规则)。
final class MP4MetadataScannerTests: XCTestCase {
    // MARK: - Box builders

    private func box(_ type: String, _ payload: Data = Data()) -> Data {
        var d = be32(UInt32(8 + payload.count))
        d.append(fourCC(type))
        d.append(payload)
        return d
    }

    /// size == 1 → 16 字节头,后跟 64 位 largesize。
    private func largeBox(_ type: String, _ payload: Data = Data()) -> Data {
        var d = be32(1)
        d.append(fourCC(type))
        d.append(be64(UInt64(16 + payload.count)))
        d.append(payload)
        return d
    }

    /// size == 0 → 一直到所在范围末尾。
    private func toEndBox(_ type: String, _ payload: Data = Data()) -> Data {
        var d = be32(0)
        d.append(fourCC(type))
        d.append(payload)
        return d
    }

    /// ISO `meta` 是 FullBox:4 字节 version/flags 之后才是子 box。
    private func metaBox(_ children: Data...) -> Data {
        box("meta", Data([0, 0, 0, 0]) + children.reduce(Data(), +))
    }

    private func container(_ type: String, _ children: Data...) -> Data {
        box(type, children.reduce(Data(), +))
    }

    private func uuidBox(_ usertype: [UInt8], _ payload: Data = Data()) -> Data {
        box("uuid", Data(usertype) + payload)
    }

    private let xmpUUID: [UInt8] = [0xBE, 0x7A, 0xCF, 0xCB, 0x97, 0xA9, 0x42, 0xE8,
                                    0x9C, 0x71, 0x99, 0x94, 0x91, 0xE3, 0xAF, 0xAC]

    private func fourCC(_ s: String) -> Data {
        // `©` 是 0xA9(Latin-1),不能按 UTF-8 编
        Data(s.unicodeScalars.map { UInt8($0.value) })
    }

    private func be32(_ v: UInt32) -> Data { withUnsafeBytes(of: v.bigEndian) { Data($0) } }
    private func be64(_ v: UInt64) -> Data { withUnsafeBytes(of: v.bigEndian) { Data($0) } }

    private var ftyp: Data { box("ftyp", fourCC("isom") + be32(0x200) + fourCC("isom")) }
    private var cleanTrak: Data {
        container("trak", box("tkhd", Data(count: 84)),
                  container("mdia", box("mdhd", Data(count: 24)),
                            container("minf", container("stbl", box("stsd", Data(count: 8))))))
    }

    // MARK: - Tests

    func testCleanFileHasNoPrivacyBoxes() {
        let file = ftyp + container("moov", box("mvhd", Data(count: 100)), cleanTrak) + box("mdat", Data(count: 32))
        XCTAssertEqual(MP4MetadataScanner.privacyBoxes(in: file), [])
    }

    func testEmptyInputIsClean() {
        XCTAssertEqual(MP4MetadataScanner.privacyBoxes(in: Data()), [])
    }

    func testFindsQuickTimeLocationInMoovUdta() {
        let file = ftyp + container("moov", box("mvhd", Data(count: 100)),
                                    container("udta", box("©xyz", Data("+39.9042+116.4074/".utf8))))
        XCTAssertEqual(MP4MetadataScanner.privacyBoxes(in: file), ["©xyz"])
    }

    func testFindsLociDeepInTrackUdta() {
        let trak = container("trak", box("tkhd", Data(count: 84)), container("udta", box("loci", Data(count: 20))))
        let file = ftyp + container("moov", trak)
        XCTAssertEqual(MP4MetadataScanner.privacyBoxes(in: file), ["loci"])
    }

    func testFindsBoxesUnderStbl() {
        let trak = container("trak", container("mdia", container("minf", container("stbl", box("loci", Data(count: 4))))))
        XCTAssertEqual(MP4MetadataScanner.privacyBoxes(in: container("moov", trak)), ["loci"])
    }

    func testMetaIsFullBoxAndNonEmptyIlstIsReported() {
        let ilst = box("ilst", box("©day", Data(count: 16)))
        let meta = metaBox(box("hdlr", Data(count: 25)), ilst)
        let file = ftyp + container("moov", container("udta", meta))
        XCTAssertEqual(MP4MetadataScanner.privacyBoxes(in: file), ["ilst"])
    }

    func testEmptyIlstIsNotReported() {
        let meta = metaBox(box("hdlr", Data(count: 25)), box("ilst"))
        XCTAssertEqual(MP4MetadataScanner.privacyBoxes(in: container("moov", container("udta", meta))), [])
    }

    func testLocationInsideMetaIsFoundAfterSkippingVersionFlags() {
        let meta = metaBox(box("hdlr", Data(count: 25)), box("loci", Data(count: 8)))
        XCTAssertEqual(MP4MetadataScanner.privacyBoxes(in: container("moov", meta)), ["loci"])
    }

    /// QuickTime 风格(`moov/meta` + `keys`/`ilst`,AVFoundation 写 .mov 用的)没有 version/flags:
    /// 紧跟头部就是 `hdlr`。按 FullBox 硬跳 4 字节会错位、漏掉 ilst——识别出来就不跳。
    func testQuickTimeStyleMetaWithoutVersionFlagsIsAlsoWalked() {
        let meta = box("meta", box("hdlr", Data(count: 25)) + box("keys", Data(count: 8))
                       + box("ilst", box("data", Data(count: 4))))
        XCTAssertEqual(MP4MetadataScanner.privacyBoxes(in: container("moov", meta)), ["ilst"])
    }

    func testFindsXMPUUIDAtTopLevelAndIgnoresOtherUUIDs() {
        var other = xmpUUID
        other[15] ^= 0xFF
        let file = ftyp + uuidBox(xmpUUID, Data("<x:xmpmeta/>".utf8)) + uuidBox(other, Data(count: 4))
        XCTAssertEqual(MP4MetadataScanner.privacyBoxes(in: file), ["XMP_"])
    }

    func testFindsXMPUUIDInsideUdta() {
        let file = container("moov", container("udta", uuidBox(xmpUUID, Data(count: 4))))
        XCTAssertEqual(MP4MetadataScanner.privacyBoxes(in: file), ["XMP_"])
    }

    func testLargeSizeBoxesAreWalked() {
        // 64 位 size 的 mdat 在前,后面的 moov 仍要被扫到;moov 本身也用 64 位 size
        let file = ftyp + largeBox("mdat", Data(count: 40))
            + largeBox("moov", container("udta", box("©xyz", Data(count: 8))))
        XCTAssertEqual(MP4MetadataScanner.privacyBoxes(in: file), ["©xyz"])
    }

    func testSizeZeroRunsToEnd() {
        let file = ftyp + toEndBox("moov", container("udta", box("loci", Data(count: 8))))
        XCTAssertEqual(MP4MetadataScanner.privacyBoxes(in: file), ["loci"])
    }

    func testPrivacyTypeOutsideContainersIsNotMisreadFromMdatPayload() {
        // mdat 不是容器:里面恰好出现 "loci" 字节也不算
        let file = ftyp + box("mdat", box("loci", Data(count: 8)))
        XCTAssertEqual(MP4MetadataScanner.privacyBoxes(in: file), [])
    }

    func testReportsAllHits() {
        let udta = container("udta", box("©xyz", Data(count: 8)), box("loci", Data(count: 8)),
                             metaBox(box("hdlr", Data(count: 25)), box("ilst", box("data", Data(count: 4)))))
        let file = ftyp + container("moov", udta) + uuidBox(xmpUUID)
        XCTAssertEqual(MP4MetadataScanner.privacyBoxes(in: file), ["©xyz", "loci", "ilst", "XMP_"])
    }

    func testTruncatedBoxIsClampedAndStillScanned() {
        var file = container("moov", container("udta", box("©xyz", Data(count: 8))))
        file.removeLast(4)                                  // moov/udta/©xyz 声明长度都超出实际
        XCTAssertEqual(MP4MetadataScanner.privacyBoxes(in: file), ["©xyz"], "超长声明截到末尾,不算坏头")
        XCTAssertEqual(MP4MetadataScanner.privacyBoxes(in: Data([0, 0, 0, 1, 0x6D])), [],
                       "不足 8 字节的残余忽略")
    }

    func testTrailingZeroTerminatorInUdtaIsIgnored() {
        var payload = box("free", Data(count: 4))
        payload.append(Data(count: 4))                      // QuickTime udta 的 4 字节 0 结尾
        XCTAssertEqual(MP4MetadataScanner.privacyBoxes(in: container("moov", box("udta", payload))), [])
    }

    func testLiteralXMPBoxInUdtaIsReported() {
        let file = container("moov", container("udta", box("XMP_", Data("<x:xmpmeta/>".utf8))))
        XCTAssertEqual(MP4MetadataScanner.privacyBoxes(in: file), ["XMP_"])
    }

    func testSmallSizeHeaderIsMalformed() {
        var bad = be32(4)                                   // size 非 0/1 却 < 8
        bad.append(fourCC("free"))
        let file = container("moov", box("udta", bad + box("©xyz", Data(count: 8))))
        XCTAssertEqual(MP4MetadataScanner.privacyBoxes(in: file), ["malformed"])
    }

    func testLargeSizeBelowHeaderIsMalformed() {
        var bad = be32(1)
        bad.append(fourCC("moov"))
        bad.append(be64(12))                                // largesize < 16
        XCTAssertEqual(MP4MetadataScanner.privacyBoxes(in: ftyp + bad + Data(count: 8)), ["malformed"])
    }

    func testLargeSizeHeaderThatDoesNotFitIsMalformed() {
        var bad = be32(1)
        bad.append(fourCC("moov"))
        bad.append(Data([0, 0, 0, 0]))                      // 只剩 12 字节,放不下 16 字节头
        XCTAssertEqual(MP4MetadataScanner.privacyBoxes(in: bad), ["malformed"])
    }

    func testNestingBeyondDepthLimitIsMalformed() {
        var nested = box("free")
        for _ in 0..<17 { nested = container("udta", nested) }   // 17 层容器:第 17 层的内容在深度 17
        XCTAssertEqual(MP4MetadataScanner.privacyBoxes(in: nested), ["malformed"])
        var ok = box("free")
        for _ in 0..<16 { ok = container("udta", ok) }
        XCTAssertEqual(MP4MetadataScanner.privacyBoxes(in: ok), [])
    }

    // 终审 9:QuickTime udta 文本原子(时间/厂商/机型/软件)各按自己的名字命中(与 Android 同表)。
    func testQuickTimeTextAtomsInMoovUdtaAreReported() {
        for atom in ["©day", "©mak", "©mod", "©swr", "©too"] {
            let file = ftyp + container("moov", box("mvhd", Data(count: 100)),
                                        container("udta", box(atom, Data("x".utf8))))
            XCTAssertEqual(MP4MetadataScanner.privacyBoxes(in: file), [atom], atom)
        }
    }

    func testOtherQuickTimeTextAtomsAreNotReported() {
        let file = container("moov", container("udta", box("©nam", Data("title".utf8))))
        XCTAssertEqual(MP4MetadataScanner.privacyBoxes(in: file), [])
    }
}
