import XCTest
import AVFoundation
import CoreVideo
import VideoToolbox
@testable import ChencangShared

final class VideoPreparerTests: XCTestCase {
    func testDurationGate() {
        XCTAssertNoThrow(try VideoPreparer.check(durationSeconds: 59.9))
        XCTAssertNoThrow(try VideoPreparer.check(durationSeconds: 60.4), "容器时长取整误差内放行,dur_ms 另行截到 60000")
        XCTAssertThrowsError(try VideoPreparer.check(durationSeconds: 61)) {
            XCTAssertEqual($0 as? MediaPrepError, .videoTooLong)
        }
    }

    func testSizeGateIsVideoPlaintextBudget() {
        XCTAssertNoThrow(try VideoPreparer.check(byteCount: 31_457_230))
        XCTAssertThrowsError(try VideoPreparer.check(byteCount: 31_457_231)) {
            XCTAssertEqual($0 as? MediaPrepError, .videoTooLarge)
        }
    }

    func testMissingSourceFailsCleanly() async {
        let missing = FileManager.default.temporaryDirectory.appendingPathComponent("nope-\(UUID().uuidString).mov")
        do {
            _ = try await VideoPreparer.prepare(sourceURL: missing, workDir: FileManager.default.temporaryDirectory)
            XCTFail("应失败")
        } catch {
            // 读时长失败或导出失败都可以,只要不崩
        }
    }

    /// 真实素材路径:1080x1920(9:16 竖版)约 1 秒的 H.264 mp4 喂给 `VideoPreparer.prepare`,
    /// 验证导出后仍是竖版、短边缩到 720、时长与体积都在预期范围。
    func testExportsRealPortraitVideoTo720ShortEdgeWithinAspectDurationAndBudget() async throws {
        let source = try await makePortraitFixture()
        defer { try? FileManager.default.removeItem(at: source) }
        let workDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("cc-videoprep-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: workDir) }

        let prepared = try await VideoPreparer.prepare(sourceURL: source, workDir: workDir)

        XCTAssertEqual(prepared.kind, .video)
        XCTAssertGreaterThan(prepared.plaintext.count, 0, "应产出非空 mp4 数据")
        XCTAssertLessThanOrEqual(prepared.plaintext.count, MediaKind.video.plaintextBudget)
        XCTAssertGreaterThan(prepared.height, prepared.width, "竖版源视频导出后仍应竖版")
        XCTAssertEqual(prepared.width, 720, "短边缩到 720")
        XCTAssertEqual(prepared.height, 1280, "1080x1920 等比缩到短边 720 → 1280")
        XCTAssertGreaterThanOrEqual(prepared.durMs, 900)
        XCTAssertLessThanOrEqual(prepared.durMs, 2000)
    }

    /// 旋转靠 `preferredTransform` 而不是改像素(终审 I4「保持方向」):编码 1920x1080、transform 转 90° 的
    /// 竖拍素材(iPhone 竖拍就是这样存的),导出后显示尺寸仍是竖的。
    func testRotatedSourceKeepsOrientationViaTransform() async throws {
        let source = try await writeFixture(width: 1920, height: 1080, frameCount: 15,
                                            transform: CGAffineTransform(rotationAngle: .pi / 2))
        defer { try? FileManager.default.removeItem(at: source) }
        let prepared = try await VideoPreparer.prepare(sourceURL: source, workDir: tempDir())
        XCTAssertEqual(prepared.width, 720)
        XCTAssertEqual(prepared.height, 1280)
    }

    /// 短边已经 ≤ 720 的源不放大(终审 I4)。
    func testSmallSourceIsNeverUpscaled() async throws {
        let source = try await writeFixture(width: 640, height: 360, frameCount: 15)
        defer { try? FileManager.default.removeItem(at: source) }
        let prepared = try await VideoPreparer.prepare(sourceURL: source, workDir: tempDir())
        XCTAssertEqual(prepared.width, 640)
        XCTAssertEqual(prepared.height, 360)
    }

    func testEncodedSizeMathShortEdge720() {
        XCTAssertTrue(VideoPreparer.encodedSize(natural: CGSize(width: 3840, height: 2160)) == (1280, 720))
        XCTAssertTrue(VideoPreparer.encodedSize(natural: CGSize(width: 1920, height: 1080)) == (1280, 720))
        XCTAssertTrue(VideoPreparer.encodedSize(natural: CGSize(width: 1080, height: 1920)) == (720, 1280))
        XCTAssertTrue(VideoPreparer.encodedSize(natural: CGSize(width: 1280, height: 720)) == (1280, 720))
        XCTAssertTrue(VideoPreparer.encodedSize(natural: CGSize(width: 720, height: 1280)) == (720, 1280))
        XCTAssertTrue(VideoPreparer.encodedSize(natural: CGSize(width: 480, height: 360)) == (480, 360))
        XCTAssertTrue(VideoPreparer.encodedSize(natural: CGSize(width: 721, height: 1281)) == (720, 1278),
                      "1281·720/721 = 1279.2 → 1279 → 取偶 1278")
        XCTAssertTrue(VideoPreparer.encodedSize(natural: CGSize(width: 641, height: 361)) == (640, 360),
                      "短边 < 720 不缩,奇数取偶")
        XCTAssertTrue(VideoPreparer.encodedSize(natural: CGSize(width: 1440, height: 1440)) == (720, 720))
        XCTAssertTrue(VideoPreparer.encodedSize(natural: .zero) == (0, 0))
    }

    // MARK: - 编码参数(纯函数)

    func testEncodingProfilePrefersHEVCAt1_8Mbps() {
        let p = VideoPreparer.encodingProfile(hevcAvailable: true)
        XCTAssertEqual(p.codec, .hevc)
        XCTAssertEqual(p.bitRate, 1_800_000)
        XCTAssertEqual(p.profileLevel, kVTProfileLevel_HEVC_Main_AutoLevel as String)
    }

    func testEncodingProfileFallsBackToH264At2_5Mbps() {
        let p = VideoPreparer.encodingProfile(hevcAvailable: false)
        XCTAssertEqual(p.codec, .h264)
        XCTAssertEqual(p.bitRate, 2_500_000)
        XCTAssertEqual(p.profileLevel, AVVideoProfileLevelH264HighAutoLevel)
    }

    func testOutputSettingsCarryProfileSizeAndCappedFrameRate() {
        let s = VideoPreparer.videoOutputSettings(profile: VideoPreparer.encodingProfile(hevcAvailable: true),
                                                  width: 720, height: 1280, sourceFrameRate: 60)
        XCTAssertEqual(s[AVVideoCodecKey] as? AVVideoCodecType, .hevc)
        XCTAssertEqual(s[AVVideoWidthKey] as? Int, 720)
        XCTAssertEqual(s[AVVideoHeightKey] as? Int, 1280)
        let c = s[AVVideoCompressionPropertiesKey] as? [String: Any]
        XCTAssertEqual(c?[AVVideoAverageBitRateKey] as? Int, 1_800_000)
        XCTAssertEqual(c?[AVVideoExpectedSourceFrameRateKey] as? Int, 30)
        XCTAssertEqual(c?[AVVideoProfileLevelKey] as? String, kVTProfileLevel_HEVC_Main_AutoLevel as String)
    }

    func testExpectedFrameRateIsCappedAt30() {
        XCTAssertEqual(VideoPreparer.expectedFrameRate(nominal: 60), 30)
        XCTAssertEqual(VideoPreparer.expectedFrameRate(nominal: 240), 30)
        XCTAssertEqual(VideoPreparer.expectedFrameRate(nominal: 29.97), 30)
        XCTAssertEqual(VideoPreparer.expectedFrameRate(nominal: 24), 24)
        XCTAssertEqual(VideoPreparer.expectedFrameRate(nominal: 0), 30)
    }

    // MARK: - 丢帧到 ≤ 30 fps(纯函数)

    private func keptCount(fps: Double, seconds: Double = 1) -> Int {
        var limiter = FrameRateLimiter(maxFPS: 30)
        let frames = Int((fps * seconds).rounded())
        return (0..<frames).filter { limiter.shouldKeep(seconds: Double($0) / fps) }.count
    }

    func testFrameLimiterHalves60fps() { XCTAssertEqual(keptCount(fps: 60), 30) }
    func testFrameLimiterQuarters120fps() { XCTAssertEqual(keptCount(fps: 120), 30) }
    func testFrameLimiterKeeps30fpsIntact() { XCTAssertEqual(keptCount(fps: 30), 30) }
    func testFrameLimiterKeeps24fpsIntact() { XCTAssertEqual(keptCount(fps: 24), 24) }
    func testFrameLimiterBrings50fpsTo30() { XCTAssertEqual(keptCount(fps: 50), 30) }

    func testFrameLimiterDoesNotBurstAfterGap() {
        var limiter = FrameRateLimiter(maxFPS: 30)
        XCTAssertTrue(limiter.shouldKeep(seconds: 0))
        XCTAssertTrue(limiter.shouldKeep(seconds: 1.0), "长间隔后的第一帧保留")
        XCTAssertFalse(limiter.shouldKeep(seconds: 1.0 + 1.0 / 60), "紧跟其后的帧不能因为落后的节拍而连发")
        XCTAssertTrue(limiter.shouldKeep(seconds: 1.0 + 2.0 / 60))
    }

    // MARK: - 编码器选择与真实输出

    /// Mac 上有 HEVC 编码器:输出必须是 hvc1/hev1。
    func testOutputIsHEVCWhereAvailable() async throws {
        let source = try await writeFixture(width: 1280, height: 720, frameCount: 15)
        defer { try? FileManager.default.removeItem(at: source) }
        let prepared = try await VideoPreparer.prepare(sourceURL: source, workDir: tempDir())
        let codec = try await codecFourCC(of: prepared.plaintext)
        XCTAssertTrue([kCMVideoCodecType_HEVC, fourCC("hev1")].contains(codec),
                      "期望 HEVC,实际 \(fourCCString(codec))")
    }

    /// H.264 回退路径真实可达(Review Focus 2):强制不用 HEVC 也能转出合格的 avc1 视频,且同样干净。
    func testForcedH264FallbackProducesAVC1() async throws {
        let source = try await writeFixture(width: 1920, height: 1080, frameCount: 15)
        defer { try? FileManager.default.removeItem(at: source) }
        let out = tempDir().appendingPathComponent("h264.mp4")
        try FileManager.default.createDirectory(at: out.deletingLastPathComponent(), withIntermediateDirectories: true)
        try await VideoPreparer.transcode(asset: AVURLAsset(url: source), to: out, allowHEVC: false)
        let data = try Data(contentsOf: out)
        let codec = try await codecFourCC(of: data)
        XCTAssertEqual(codec, kCMVideoCodecType_H264)
        XCTAssertEqual(MP4MetadataScanner.privacyBoxes(in: data), [])
        let (w, h) = try await VideoPreparer.displaySize(of: AVURLAsset(url: out))
        XCTAssertEqual(w, 1280)
        XCTAssertEqual(h, 720)
    }

    /// HEVC 过了 canApply 但 writer 运行时失败:删掉半成品,用 H.264 重来一次并成功(Review Focus 2)。
    /// 这台 Mac 有 HEVC,不注入时输出是 hvc1——注入后变 avc1,说明确实先走了 HEVC 再回退。
    func testHEVCWriterRuntimeFailureRetriesWithH264() async throws {
        let source = try await writeFixture(width: 1280, height: 720, frameCount: 15)
        defer { try? FileManager.default.removeItem(at: source) }
        let out = tempDir().appendingPathComponent("retry.mp4")
        try FileManager.default.createDirectory(at: out.deletingLastPathComponent(), withIntermediateDirectories: true)
        try await VideoPreparer.transcode(asset: AVURLAsset(url: source), to: out, injectHEVCWriterFailure: true)
        let data = try Data(contentsOf: out)
        let codec = try await codecFourCC(of: data)
        XCTAssertEqual(codec, kCMVideoCodecType_H264, "实际 \(fourCCString(codec))")
        XCTAssertEqual(MP4MetadataScanner.privacyBoxes(in: data), [])
    }

    /// 60 fps 源 → 输出 ≤ 30 fps(帧数减半、时长不变)。
    func testSixtyFpsSourceIsDroppedToThirty() async throws {
        let source = try await writeFixture(width: 640, height: 360, frameCount: 60, fps: 60)
        defer { try? FileManager.default.removeItem(at: source) }
        let prepared = try await VideoPreparer.prepare(sourceURL: source, workDir: tempDir())
        let url = tempDir().appendingPathComponent("fps.mp4")
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try prepared.plaintext.write(to: url)
        let tracks = try await AVURLAsset(url: url).loadTracks(withMediaType: .video)
        let track = try XCTUnwrap(tracks.first)
        let fps = try await track.load(.nominalFrameRate)
        XCTAssertLessThanOrEqual(fps, 30.5, "输出帧率 \(fps)")
        XCTAssertGreaterThanOrEqual(fps, 25)
        XCTAssertGreaterThanOrEqual(prepared.durMs, 900)
    }

    func testPrivacyCheckThrowsWhenBoxesRemain() {
        var loci = Data([0, 0, 0, 16]) + Data("loci".utf8) + Data(count: 8)
        loci = Data([0, 0, 0, UInt8(8 + loci.count)]) + Data("udta".utf8) + loci
        let moov = Data([0, 0, 0, UInt8(8 + loci.count)]) + Data("moov".utf8) + loci
        XCTAssertThrowsError(try VideoPreparer.verifyNoPrivacyBoxes(moov)) {
            XCTAssertEqual(($0 as? MediaPrepError)?.userMessage, MediaPrepError.videoExportFailed().userMessage)
        }
        XCTAssertNoThrow(try VideoPreparer.verifyNoPrivacyBoxes(Data()))
    }

    /// 终审 I3:相册视频带 GPS(`com.apple.quicktime.location.ISO6709`)与机型等元数据,导出后必须一条不剩。
    func testStripsLocationAndIdentifyingMetadata() async throws {
        let location = AVMutableMetadataItem()
        location.identifier = .quickTimeMetadataLocationISO6709
        location.value = "+39.9042+116.4074+043.000/" as NSString
        location.dataType = kCMMetadataBaseDataType_UTF8 as String
        let model = AVMutableMetadataItem()
        model.identifier = .quickTimeMetadataModel
        model.value = "iPhone 15" as NSString
        model.dataType = kCMMetadataBaseDataType_UTF8 as String
        let source = try await writeFixture(width: 640, height: 360, frameCount: 15, fileType: .mov,
                                            metadata: [location, model])
        defer { try? FileManager.default.removeItem(at: source) }

        // 先证明素材里确实有位置(否则这条测试是空转)
        let sourceMeta = try await AVURLAsset(url: source).load(.metadata)
        XCTAssertTrue(sourceMeta.contains { $0.identifier == .quickTimeMetadataLocationISO6709 }, "素材应带位置元数据")

        let workDir = tempDir()
        let prepared = try await VideoPreparer.prepare(sourceURL: source, workDir: workDir)
        let out = workDir.appendingPathComponent("out.mp4")
        try prepared.plaintext.write(to: out)
        let exported = AVURLAsset(url: out)
        let meta = try await exported.load(.metadata)
        let common = try await exported.load(.commonMetadata)
        var trackMeta: [AVMetadataItem] = []
        for track in try await exported.load(.tracks) {
            trackMeta += try await track.load(.metadata)
        }
        let all = meta + common + trackMeta
        XCTAssertFalse(all.contains { $0.identifier == .quickTimeMetadataLocationISO6709 }, "导出后仍带 GPS")
        XCTAssertFalse(all.contains { $0.commonKey == .commonKeyLocation }, "导出后仍带位置")
        XCTAssertFalse(all.contains { $0.identifier == .quickTimeMetadataModel }, "导出后仍带机型")
        XCTAssertTrue(meta.isEmpty, "文件级元数据应为空,实际:\(meta.map { $0.identifier?.rawValue ?? "?" })")
        XCTAssertEqual(MP4MetadataScanner.privacyBoxes(in: prepared.plaintext), [], "box 级扫描也必须干净")
    }

    /// 终审 I4 的常驻代理:4 秒 1080p 纯噪声(最难压的画面)导出后平均码率 ≤ 3.5 Mbps——
    /// 30 MB / 60 秒的预算折合 4.2 Mbps,只要码率被写死的这条转码路径还在,60 秒就装得下。
    /// 实测(2026-09-29,Mac):本路径 3.10 Mbps;同一素材走旧的 `AVAssetExportPreset1280x720` 是 11.8 Mbps,会挂。
    /// 2026-09-30 改 HEVC 1.8 Mbps、短边 720(1280x720)后实测 2.94 Mbps。强制 H.264 2.5 Mbps 同尺寸是
    /// 10.5 Mbps——纯噪声把 H.264 顶到 QP 上限、码率只随像素数走,真实画面不会;且 iOS 16+ 真机全有 HEVC 硬编,
    /// H.264 只是兜底,超预算时照常报「视频太大」,所以这里不为 H.264 设同样的噪声门槛。
    func testHighMotionBitrateStaysUnderSixtySecondBudget() async throws {
        let source = try await writeFixture(width: 1920, height: 1080, frameCount: 120, noise: true)
        defer { try? FileManager.default.removeItem(at: source) }
        let prepared = try await VideoPreparer.prepare(sourceURL: source, workDir: tempDir())
        let seconds = Double(prepared.durMs) / 1000
        let mbps = Double(prepared.plaintext.count * 8) / seconds / 1_000_000
        print("CCMEASURE noise1080p \(String(format: "%.2f", mbps)) Mbps")
        XCTAssertLessThanOrEqual(mbps, 3.5, "噪声素材导出码率 \(mbps) Mbps,60 秒会超 30 MB")
    }

    /// 实测用(默认跳过):`CC_VIDEO_BUDGET_SOURCE=<60 秒 1080p 素材>` 时跑一遍真实转码并打印体积。
    func testMeasureSixtySecondSourceIfProvided() async throws {
        guard let path = ProcessInfo.processInfo.environment["CC_VIDEO_BUDGET_SOURCE"] else {
            throw XCTSkip("未设置 CC_VIDEO_BUDGET_SOURCE")
        }
        let start = Date()
        let prepared = try await VideoPreparer.prepare(sourceURL: URL(fileURLWithPath: path), workDir: tempDir())
        let mb = Double(prepared.plaintext.count) / 1_048_576
        print("CCMEASURE bytes=\(prepared.plaintext.count) (\(String(format: "%.2f", mb)) MB) "
              + "size=\(prepared.width)x\(prepared.height) durMs=\(prepared.durMs) "
              + "elapsed=\(String(format: "%.1f", Date().timeIntervalSince(start)))s")
        XCTAssertLessThanOrEqual(prepared.plaintext.count, MediaKind.video.plaintextBudget)
    }

    private func codecFourCC(of mp4: Data) async throws -> FourCharCode {
        let url = tempDir().appendingPathComponent("codec-\(UUID().uuidString).mp4")
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try mp4.write(to: url)
        let tracks = try await AVURLAsset(url: url).loadTracks(withMediaType: .video)
        let track = try XCTUnwrap(tracks.first)
        let descs = try await track.load(.formatDescriptions)
        let desc = try XCTUnwrap(descs.first)
        return CMFormatDescriptionGetMediaSubType(desc)
    }

    private func fourCC(_ s: String) -> FourCharCode { s.utf8.reduce(0) { $0 << 8 | FourCharCode($1) } }

    private func fourCCString(_ c: FourCharCode) -> String {
        String(bytes: [24, 16, 8, 0].map { UInt8((c >> $0) & 0xFF) }, encoding: .isoLatin1) ?? "?"
    }

    private func tempDir() -> URL {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("cc-videoprep-\(UUID().uuidString)")
        addTeardownBlock { try? FileManager.default.removeItem(at: dir) }
        return dir
    }

    // MARK: - Fixture generation

    /// 现造一段约 1 秒、竖版(默认 1080x1920)的 H.264 mp4。优先用 `AVAssetWriter` 现写 30 帧 @30fps;
    /// 若这台 Mac 的 AVAssetWriter 不支持 H.264 编码,退回 `ffmpeg` 生成同样时长的竖版素材;
    /// 两条路都不通(ffmpeg 也没装)就跳过——那是这台机器的编解码器可用性问题,不是 `VideoPreparer` 的 bug。
    private func makePortraitFixture(width: Int = 1080, height: Int = 1920,
                                      frameCount: Int = 30, fps: Int32 = 30) async throws -> URL {
        if let url = try? await writePortraitAssetWriterFixture(width: width, height: height,
                                                                 frameCount: frameCount, fps: fps) {
            return url
        }
        let ffmpegPath = "/opt/homebrew/bin/ffmpeg"
        guard FileManager.default.fileExists(atPath: ffmpegPath) else {
            throw XCTSkip("AVAssetWriter H.264 编码不可用,且未找到 ffmpeg(\(ffmpegPath)),跳过真实视频素材测试")
        }
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("cc-fixture-\(UUID().uuidString).mp4")
        let seconds = Double(frameCount) / Double(fps)
        let process = Process()
        process.executableURL = URL(fileURLWithPath: ffmpegPath)
        process.arguments = [
            "-y", "-f", "lavfi", "-i", "color=c=blue:s=\(width)x\(height):d=\(seconds)",
            "-c:v", "libx264", "-pix_fmt", "yuv420p", url.path,
        ]
        try process.run()
        process.waitUntilExit()
        guard process.terminationStatus == 0, FileManager.default.fileExists(atPath: url.path) else {
            throw MediaPrepError.videoExportFailed(reason: "ffmpeg exited \(process.terminationStatus)")
        }
        return url
    }

    private func writePortraitAssetWriterFixture(width: Int, height: Int,
                                                  frameCount: Int, fps: Int32) async throws -> URL {
        try await writeFixture(width: width, height: height, frameCount: frameCount, fps: fps)
    }

    /// 用 `AVAssetWriter` 现写一段 H.264 素材。`noise` = 每帧随机像素(最难压缩);
    /// `transform` 写进轨道(模拟竖拍);`metadata` 写进文件级元数据(模拟相册视频的 GPS/机型)。
    private func writeFixture(width: Int, height: Int, frameCount: Int, fps: Int32 = 30,
                              fileType: AVFileType = .mp4, noise: Bool = false,
                              transform: CGAffineTransform = .identity,
                              metadata: [AVMetadataItem] = []) async throws -> URL {
        let ext = fileType == .mov ? "mov" : "mp4"
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("cc-fixture-\(UUID().uuidString).\(ext)")
        let writer = try AVAssetWriter(outputURL: url, fileType: fileType)
        writer.metadata = metadata
        var settings: [String: Any] = [
            AVVideoCodecKey: AVVideoCodecType.h264,
            AVVideoWidthKey: width,
            AVVideoHeightKey: height,
        ]
        if noise {
            // 素材本身按相机级码率写,免得素材先被压糊、测不出转码的码率控制
            settings[AVVideoCompressionPropertiesKey] = [AVVideoAverageBitRateKey: 20_000_000]
        }
        let input = AVAssetWriterInput(mediaType: .video, outputSettings: settings)
        input.transform = transform
        let pixelBufferAttrs: [String: Any] = [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32ARGB,
            kCVPixelBufferWidthKey as String: width,
            kCVPixelBufferHeightKey as String: height,
        ]
        let adaptor = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: input,
                                                            sourcePixelBufferAttributes: pixelBufferAttrs)
        guard writer.canAdd(input) else {
            throw MediaPrepError.videoExportFailed(reason: "canAdd(input) == false")
        }
        writer.add(input)
        guard writer.startWriting() else {
            throw MediaPrepError.videoExportFailed(reason: writer.error?.localizedDescription ?? "startWriting failed")
        }
        writer.startSession(atSourceTime: .zero)

        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            var frameIndex = 0
            input.requestMediaDataWhenReady(on: DispatchQueue(label: "cc.videopreparer.fixture")) {
                while input.isReadyForMoreMediaData {
                    if frameIndex >= frameCount {
                        input.markAsFinished()
                        continuation.resume()
                        return
                    }
                    guard let pool = adaptor.pixelBufferPool else {
                        input.markAsFinished()
                        continuation.resume(throwing: MediaPrepError.videoExportFailed(reason: "no pixelBufferPool"))
                        return
                    }
                    var pixelBuffer: CVPixelBuffer?
                    CVPixelBufferPoolCreatePixelBuffer(nil, pool, &pixelBuffer)
                    guard let buffer = pixelBuffer else {
                        // 原来这里 `continue` 会在同一个回调里死转;拿不到缓冲就判失败
                        input.markAsFinished()
                        continuation.resume(throwing: MediaPrepError.videoExportFailed(reason: "no pixel buffer"))
                        return
                    }
                    CVPixelBufferLockBaseAddress(buffer, [])
                    if let base = CVPixelBufferGetBaseAddress(buffer) {
                        let bytesPerRow = CVPixelBufferGetBytesPerRow(buffer)
                        if noise {
                            arc4random_buf(base, bytesPerRow * height)
                        } else {
                            memset(base, Int32(frameIndex % 256), bytesPerRow * height)
                        }
                    }
                    CVPixelBufferUnlockBaseAddress(buffer, [])
                    let pts = CMTime(value: CMTimeValue(frameIndex), timescale: fps)
                    // append 失败(writer 已 .failed)后 isReadyForMoreMediaData 不会再变 true,回调也不会再来——
                    // 不在这里收掉,continuation 就永远不 resume(并行跑时偶发挂死的原因之一)
                    guard adaptor.append(buffer, withPresentationTime: pts) else {
                        let reason = writer.error?.localizedDescription ?? "fixture append failed"
                        continuation.resume(throwing: MediaPrepError.videoExportFailed(reason: reason))
                        return
                    }
                    frameIndex += 1
                }
            }
        }

        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            writer.finishWriting { continuation.resume() }
        }
        guard writer.status == .completed else {
            let reason = writer.error?.localizedDescription ?? "writer status \(writer.status.rawValue)"
            throw MediaPrepError.videoExportFailed(reason: reason)
        }
        return url
    }
}
