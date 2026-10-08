import Foundation
import AVFoundation
import VideoToolbox

/// 视频预处理(spec §3.3、R8;打磨 spec 2026-09-30 §4.1/§4.3):先读时长,> 60 秒直接拦下;
/// 转码成 `.mp4`(HEVC 1.8 Mbps,设备不能编 HEVC 时退 H.264 High 2.5 Mbps;短边 ≤ 720;≤ 30 fps;AAC);
/// 转码后 > 视频明文预算(31457230)拦下「视频太大」;输出再过一遍 `MP4MetadataScanner`,
/// 残留任何位置/元数据 box 就判失败,不上传。
///
/// 终审 I4:原来用 `AVAssetExportPreset1280x720`,码率不可控——60 秒 1080p 高运动素材在 Mac 上实测
/// 导出 53.7 MB(视频约 7.4 Mbps),远超 30 MB,用户拍的大部分 30 秒以上视频都会被「视频太大」拦下。
/// 改为 `AVAssetReader` → `AVAssetWriter` 自己转码,码率写死(见 `encodingProfile`)、短边 720
/// (已经 ≤ 720 的源不放大)、方向沿用源的 `preferredTransform`;音频 AAC 64 kbps 单声道。
/// 60 秒 HEVC ≈ 14 MB、H.264 ≈ 19 MB,留足余量。
///
/// 终审 I3:`AVAssetWriter` 只写我们给它的东西——文件级 `metadata` 显式置空,轨道级元数据不复制,
/// 所以相册视频里的 GPS(`com.apple.quicktime.location.ISO6709`)、机型、软件、拍摄时间都不会带出去,
/// 与图片去 EXIF/GPS 的待遇一致。
public enum VideoPreparer {
    /// 容器时长常带几十毫秒取整误差(相机 60 秒上限录出来可能是 60.02 秒),放 0.5 秒余量;
    /// 帧内 dur_ms 另行截到 60000。
    private static let durationTolerance: Double = 0.5
    static let maxShortEdge = 720
    static let maxFrameRate = 30
    static let audioBitRate = 64_000
    static let audioSampleRate = 44_100

    public static func check(durationSeconds: Double) throws {
        if durationSeconds > MediaLimits.maxVideoSeconds + durationTolerance {
            throw MediaPrepError.videoTooLong
        }
    }

    public static func check(byteCount: Int) throws {
        if byteCount > MediaKind.video.plaintextBudget {
            throw MediaPrepError.videoTooLarge
        }
    }

    public static func prepare(sourceURL: URL, workDir: URL) async throws -> PreparedMedia {
        let asset = AVURLAsset(url: sourceURL)
        let duration = try await asset.load(.duration)
        try check(durationSeconds: duration.seconds)

        try FileManager.default.createDirectory(at: workDir, withIntermediateDirectories: true)
        let output = workDir.appendingPathComponent("cc-export-\(UUID().uuidString).mp4")
        defer { try? FileManager.default.removeItem(at: output) }
        try await transcode(asset: asset, to: output)

        let data = try Data(contentsOf: output)
        try check(byteCount: data.count)
        try verifyNoPrivacyBoxes(data)
        let exported = AVURLAsset(url: output)
        let (width, height) = try await displaySize(of: exported)
        let exportedDuration = try await exported.load(.duration)
        let durMs = min(MediaLimits.maxDurationMs, Int((exportedDuration.seconds * 1000).rounded()))
        return PreparedMedia(kind: .video, plaintext: data, durMs: durMs, width: width, height: height)
    }

    /// 兜底校验(spec §4.3):输出里还有 `©xyz`/`loci`/XMP/非空 `ilst` 就判失败——宁可发不出去,
    /// 也不带着位置上传。`reason` 只含 box 名,不含内容。
    static func verifyNoPrivacyBoxes(_ mp4: Data) throws {
        let hits = MP4MetadataScanner.privacyBoxes(in: mp4)
        guard hits.isEmpty else {
            throw MediaPrepError.videoExportFailed(reason: "privacy boxes: \(hits.joined(separator: ","))")
        }
    }

    /// 编码尺寸:按源的编码尺寸(naturalSize,未旋转)等比缩到短边 ≤ 720,只缩不放,取偶数
    /// (4:2:0 要求)。旋转交给 `transform`,不动像素;短边与旋转无关,所以按编码尺寸算即可。
    static func encodedSize(natural: CGSize) -> (width: Int, height: Int) {
        let w = abs(natural.width), h = abs(natural.height)
        guard w > 0, h > 0 else { return (0, 0) }
        let scale = min(1, CGFloat(maxShortEdge) / min(w, h))
        func even(_ v: CGFloat) -> Int { max(2, Int((v * scale).rounded()) / 2 * 2) }
        return (even(w), even(h))
    }

    /// SDR BT.709:读出与写入都按它做颜色转换,HDR(HLG/Dolby Vision)的相机视频转成普通 H.264 能正常显示。
    private static let sdrColor: [String: Any] = [
        AVVideoColorPrimariesKey: AVVideoColorPrimaries_ITU_R_709_2,
        AVVideoTransferFunctionKey: AVVideoTransferFunction_ITU_R_709_2,
        AVVideoYCbCrMatrixKey: AVVideoYCbCrMatrix_ITU_R_709_2,
    ]

    /// 编码档位:HEVC Main 1.8 Mbps;不能编 HEVC 时 H.264 High 2.5 Mbps(spec §4.1)。
    struct EncodingProfile: Equatable {
        let codec: AVVideoCodecType
        let bitRate: Int
        let profileLevel: String
    }

    static func encodingProfile(hevcAvailable: Bool) -> EncodingProfile {
        hevcAvailable
            ? EncodingProfile(codec: .hevc, bitRate: 1_800_000,
                              profileLevel: kVTProfileLevel_HEVC_Main_AutoLevel as String)
            : EncodingProfile(codec: .h264, bitRate: 2_500_000,
                              profileLevel: AVVideoProfileLevelH264HighAutoLevel)
    }

    /// 编码器提示用的帧率:源帧率取整,封顶 30(> 30 的源由 `FrameRateLimiter` 真正丢帧);读不到按 30。
    static func expectedFrameRate(nominal: Float) -> Int {
        guard nominal > 0 else { return maxFrameRate }
        return min(maxFrameRate, Int(nominal.rounded()))
    }

    static func videoOutputSettings(profile: EncodingProfile, width: Int, height: Int,
                                    sourceFrameRate: Float) -> [String: Any] {
        [
            AVVideoCodecKey: profile.codec,
            AVVideoWidthKey: width,
            AVVideoHeightKey: height,
            AVVideoScalingModeKey: AVVideoScalingModeResizeAspectFill,
            AVVideoColorPropertiesKey: sdrColor,
            AVVideoCompressionPropertiesKey: [
                AVVideoAverageBitRateKey: profile.bitRate,
                AVVideoProfileLevelKey: profile.profileLevel,
                AVVideoMaxKeyFrameIntervalDurationKey: 2,
                AVVideoExpectedSourceFrameRateKey: expectedFrameRate(nominal: sourceFrameRate),
            ] as [String: Any],
        ]
    }

    /// 先按 `canApply` 选编码;选了 HEVC 却在运行时 writer 失败(`.failed`,比如硬编会话被拒),
    /// 删掉写了一半的输出,用 H.264 重来一次;H.264 也失败才放弃。reader 失败不重试(换编码救不了)。
    ///
    /// 两个参数只给单测用,产品路径全取默认值:`allowHEVC = false` 强制 H.264(证明回退路径真实可达);
    /// `injectHEVCWriterFailure = true` 让 HEVC 那一次按 writer 运行时失败处理(证明重试可达)。
    static func transcode(asset: AVURLAsset, to output: URL, allowHEVC: Bool = true,
                          injectHEVCWriterFailure: Bool = false) async throws {
        do {
            try await transcodeAttempt(asset: asset, to: output, allowHEVC: allowHEVC,
                                       injectWriterFailure: injectHEVCWriterFailure)
        } catch let failure as HEVCWriterFailure {
            try? FileManager.default.removeItem(at: output)   // AVAssetWriter 不肯覆盖已存在的文件
            do {
                try await transcodeAttempt(asset: asset, to: output, allowHEVC: false, injectWriterFailure: false)
            } catch {
                if case MediaPrepError.videoExportFailed(let reason) = error {
                    throw MediaPrepError.videoExportFailed(reason: "hevc: \(failure.reason); h264: \(reason ?? "?")")
                }
                throw error
            }
        }
    }

    /// HEVC 那一次的 writer 运行时失败:由 `transcode` 接住改走 H.264,不外泄。
    private struct HEVCWriterFailure: Error { let reason: String }

    private static func transcodeAttempt(asset: AVURLAsset, to output: URL, allowHEVC: Bool,
                                         injectWriterFailure: Bool) async throws {
        guard let videoTrack = try await asset.loadTracks(withMediaType: .video).first else {
            throw MediaPrepError.videoExportFailed(reason: "no video track")
        }
        let audioTrack = try await asset.loadTracks(withMediaType: .audio).first
        let (naturalSize, transform, frameRate) = try await videoTrack.load(.naturalSize, .preferredTransform,
                                                                             .nominalFrameRate)
        let size = encodedSize(natural: naturalSize)
        guard size.width > 0 else { throw MediaPrepError.videoExportFailed(reason: "empty video track") }

        let reader: AVAssetReader
        let writer: AVAssetWriter
        do {
            reader = try AVAssetReader(asset: asset)
            writer = try AVAssetWriter(outputURL: output, fileType: .mp4)
        } catch {
            throw MediaPrepError.videoExportFailed(reason: error.localizedDescription)
        }
        writer.shouldOptimizeForNetworkUse = true
        writer.metadata = []            // I3:不带任何文件级元数据(GPS/机型/软件/时间)

        let videoOut = AVAssetReaderTrackOutput(track: videoTrack, outputSettings: [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange,
            AVVideoColorPropertiesKey: sdrColor,
        ])
        videoOut.alwaysCopiesSampleData = false
        func settings(hevc: Bool) -> [String: Any] {
            videoOutputSettings(profile: encodingProfile(hevcAvailable: hevc), width: size.width,
                                height: size.height, sourceFrameRate: frameRate)
        }
        let hevcSettings = settings(hevc: true)
        let useHEVC = allowHEVC && writer.canApply(outputSettings: hevcSettings, forMediaType: .video)
        /// writer 进了 `.failed`:HEVC 这一次交给上层重试,H.264 这一次直接报错。
        func writerFailed(_ reason: String?) -> Error {
            useHEVC ? HEVCWriterFailure(reason: reason ?? "writer failed")
                    : MediaPrepError.videoExportFailed(reason: reason)
        }
        let videoIn = AVAssetWriterInput(mediaType: .video,
                                         outputSettings: useHEVC ? hevcSettings : settings(hevc: false))
        videoIn.transform = transform   // 方向沿用源(竖拍视频仍是竖的)
        videoIn.expectsMediaDataInRealTime = false
        guard reader.canAdd(videoOut), writer.canAdd(videoIn) else {
            throw MediaPrepError.videoExportFailed(reason: "cannot add video track")
        }
        reader.add(videoOut)
        writer.add(videoIn)

        var audioPair: (AVAssetReaderTrackOutput, AVAssetWriterInput)?
        if let audioTrack {
            let audioOut = AVAssetReaderTrackOutput(track: audioTrack, outputSettings: [
                AVFormatIDKey: kAudioFormatLinearPCM,
                AVSampleRateKey: audioSampleRate,
                AVNumberOfChannelsKey: 1,
                AVLinearPCMBitDepthKey: 16,
                AVLinearPCMIsFloatKey: false,
                AVLinearPCMIsBigEndianKey: false,
                AVLinearPCMIsNonInterleaved: false,
            ])
            let audioIn = AVAssetWriterInput(mediaType: .audio, outputSettings: [
                AVFormatIDKey: kAudioFormatMPEG4AAC,
                AVSampleRateKey: audioSampleRate,
                AVNumberOfChannelsKey: 1,
                AVEncoderBitRateKey: audioBitRate,
            ])
            audioIn.expectsMediaDataInRealTime = false
            if reader.canAdd(audioOut), writer.canAdd(audioIn) {
                reader.add(audioOut)
                writer.add(audioIn)
                audioPair = (audioOut, audioIn)
            }
        }

        guard reader.startReading() else {
            throw MediaPrepError.videoExportFailed(reason: reader.error?.localizedDescription)
        }
        guard writer.startWriting() else {
            reader.cancelReading()
            throw writerFailed(writer.error?.localizedDescription)
        }
        writer.startSession(atSourceTime: .zero)

        // 视频、音频各用一个串行队列并行抽取:同一个 reader 的两路输出必须交替消费,
        // 放在一个队列里顺序抽会互相等死。
        // > 30 fps 的源(60/120/240 fps 慢动作)按 30 fps 节拍丢帧;≤ 30 的源一帧不动。
        let limiter = frameRate > Float(maxFrameRate) + 0.5 ? FrameRateLimiter(maxFPS: maxFrameRate) : nil
        var pumps = [TranscodePump(output: videoOut, input: videoIn, label: "cc.video.transcode.v", limiter: limiter)]
        if let (audioOut, audioIn) = audioPair {
            pumps.append(TranscodePump(output: audioOut, input: audioIn, label: "cc.video.transcode.a"))
        }
        // 任一路 append 失败:立刻取消 reader/writer,并让另一路不再等(终审复审 2)——否则另一路可能
        // 永远等不到 isReadyForMoreMediaData,这次选择就一直挂着。
        let session = ReaderWriterPair(reader: reader, writer: writer)
        // 健康检查:writer/reader 在某路等 isReadyForMoreMediaData 时自己失败(编码器会话被系统收回等),
        // 就不会再有任何回调——轮询发现失败即按第一路失败处理,不挂死。
        let ok = await TranscodeCoordinator.runAll(pumps, isHealthy: { session.isHealthy }) { session.cancel() }

        guard ok, reader.status == .completed else {
            let writerDidFail = writer.status == .failed && reader.status != .failed
            let reason = reader.error?.localizedDescription ?? writer.error?.localizedDescription
            session.cancel()
            if writerDidFail { throw writerFailed(reason) }
            // 调用方 prepare() 的 defer 负责删掉写了一半的临时输出
            throw MediaPrepError.videoExportFailed(reason: reason ?? "transcode interrupted")
        }
        await writer.finishWriting()
        if useHEVC, injectWriterFailure { throw writerFailed("injected") }
        guard writer.status == .completed else {
            let reason = writer.error?.localizedDescription
            throw writer.status == .failed ? writerFailed(reason) : MediaPrepError.videoExportFailed(reason: reason)
        }
    }

    /// 显示尺寸:naturalSize 经 preferredTransform 旋转后取绝对值(竖拍视频宽高互换)。
    static func displaySize(of asset: AVURLAsset) async throws -> (Int, Int) {
        guard let track = try await asset.loadTracks(withMediaType: .video).first else { return (0, 0) }
        let (size, transform) = try await track.load(.naturalSize, .preferredTransform)
        let rect = CGRect(origin: .zero, size: size).applying(transform)
        return (Int(abs(rect.width).rounded()), Int(abs(rect.height).rounded()))
    }
}

/// 转码的一路(视频或音频)。抽出协议是为了能在单测里注入失败(终审复审 2)。
protocol TranscodeStage: AnyObject, Sendable {
    /// 开始搬运;读完或出错时回调一次(`true` = 成功)。
    func start(_ done: @escaping @Sendable (Bool) -> Void)
    /// 别的一路失败了:停止搬运,不再碰 writer 输入(reader/writer 已被取消)。之后它自己的回调会被忽略。
    func abort()
}

/// 等所有路都结束;**第一路失败时**先调 `onFirstFailure`(取消 reader/writer),再把其余还没结束的
/// 路直接判失败收掉——不等它们(被取消的 writer 输入可能再也不会回调 isReadyForMoreMediaData)。
///
/// `isHealthy`:每 `healthPollInterval` 秒问一次;一旦为 `false`(reader/writer 已失败),视同还没结束的
/// 所有路一起失败——先 `onFirstFailure`,再把它们全部 abort 收掉。
enum TranscodeCoordinator {
    static func runAll(_ stages: [TranscodeStage], healthPollInterval: Double = 0.25,
                       isHealthy: (@Sendable () -> Bool)? = nil,
                       onFirstFailure: @escaping @Sendable () -> Void) async -> Bool {
        await withCheckedContinuation { (continuation: CheckedContinuation<Bool, Never>) in
            let state = CoordinatorState(count: stages.count)
            let group = DispatchGroup()
            for _ in stages { group.enter() }
            @Sendable func report(_ index: Int, _ ok: Bool) {
                guard let outcome = state.record(index, ok) else { return }   // 这一路已经报过
                group.leave()
                guard outcome == .firstFailure else { return }
                onFirstFailure()
                for (other, stage) in stages.enumerated() where other != index {
                    if state.isPending(other) {
                        stage.abort()
                        report(other, false)
                    }
                }
            }
            var timer: DispatchSourceTimer?
            if let isHealthy {
                let t = DispatchSource.makeTimerSource(queue: .global(qos: .userInitiated))
                t.schedule(deadline: .now() + healthPollInterval, repeating: healthPollInterval)
                t.setEventHandler {
                    guard !isHealthy() else { return }
                    t.cancel()
                    // 还没结束的路逐个按失败上报:第一条触发取消并顺带收掉其余的;
                    // 逐个查而不是只报第一条,免得它恰好同时成功、失败被忽略、剩下的路仍挂着
                    for index in stages.indices where state.isPending(index) {
                        stages[index].abort()
                        report(index, false)
                    }
                }
                t.resume()
                timer = t
            }
            for (index, stage) in stages.enumerated() {
                stage.start { ok in report(index, ok) }
            }
            group.notify(queue: .global(qos: .userInitiated)) { [timer] in
                timer?.cancel()
                continuation.resume(returning: state.allOK)
            }
        }
    }
}

private final class CoordinatorState: @unchecked Sendable {
    enum Outcome { case recorded, firstFailure }
    private let lock = NSLock()
    private var reported: [Bool]
    private var ok = true

    init(count: Int) { reported = Array(repeating: false, count: count) }

    func record(_ index: Int, _ value: Bool) -> Outcome? {
        lock.withLock {
            guard !reported[index] else { return nil }
            reported[index] = true
            let first = ok && !value
            ok = ok && value
            return first ? .firstFailure : .recorded
        }
    }

    func isPending(_ index: Int) -> Bool { lock.withLock { !reported[index] } }
    var allOK: Bool { lock.withLock { ok } }
}

/// reader/writer 只取消一次(失败回调与收尾都可能调)。
private final class ReaderWriterPair: @unchecked Sendable {
    private let reader: AVAssetReader
    private let writer: AVAssetWriter
    private let lock = NSLock()
    private var cancelled = false

    init(reader: AVAssetReader, writer: AVAssetWriter) {
        self.reader = reader
        self.writer = writer
    }

    /// reader/writer 都没进入 `.failed`。被我们自己 `cancel()` 的不算不健康(那条路已经在收尾)。
    var isHealthy: Bool { reader.status != .failed && writer.status != .failed }

    func cancel() {
        let first: Bool = lock.withLock {
            defer { cancelled = true }
            return !cancelled
        }
        guard first else { return }
        reader.cancelReading()
        writer.cancelWriting()
    }
}

/// 一路「reader 输出 → writer 输入」的搬运。AVFoundation 的这两个类型不是 `Sendable`,但各自只在
/// 自己的串行队列上被碰,所以包成 `@unchecked Sendable` 交给 `requestMediaDataWhenReady` 的回调。
private final class TranscodePump: TranscodeStage, @unchecked Sendable {
    private let output: AVAssetReaderOutput
    private let input: AVAssetWriterInput
    private let queue: DispatchQueue
    private let lock = NSLock()
    private var finished = false
    private var aborted = false
    /// 只在 `queue` 上读写。
    private var limiter: FrameRateLimiter?

    init(output: AVAssetReaderOutput, input: AVAssetWriterInput, label: String, limiter: FrameRateLimiter? = nil) {
        self.output = output
        self.input = input
        self.queue = DispatchQueue(label: label)
        self.limiter = limiter
    }

    func start(_ done: @escaping @Sendable (Bool) -> Void) {
        input.requestMediaDataWhenReady(on: queue) { [self] in
            while !isAborted, input.isReadyForMoreMediaData {
                guard let sample = output.copyNextSampleBuffer() else {
                    finish(ok: !isAborted, done)
                    return
                }
                if limiter?.shouldKeep(seconds: CMSampleBufferGetPresentationTimeStamp(sample).seconds) == false {
                    continue                               // 丢掉超出 30 fps 节拍的帧
                }
                if !input.append(sample) {
                    finish(ok: false, done)
                    return
                }
            }
        }
    }

    func abort() {
        lock.withLock { aborted = true }
    }

    private var isAborted: Bool { lock.withLock { aborted } }

    /// 只报一次;writer 已被取消(aborted)就不再 markAsFinished。
    private func finish(ok: Bool, _ done: @Sendable (Bool) -> Void) {
        let (first, wasAborted): (Bool, Bool) = lock.withLock {
            defer { finished = true }
            return (!finished, aborted)
        }
        guard first else { return }
        if !wasAborted { input.markAsFinished() }
        done(ok)
    }
}

/// 按固定节拍把帧率降到 `maxFPS` 以内:节拍落在 `next`,到点(含 1 ms 容差)的第一帧保留,节拍前进一格;
/// 若节拍已落后于这一帧(源里有长间隔),从这一帧重新起拍——不会在间隔之后连发。
/// 输入是解码后按显示顺序出来的帧,时间单调递增。
struct FrameRateLimiter {
    private let interval: Double
    private var next: Double?
    private static let tolerance = 0.001

    init(maxFPS: Int) { interval = 1 / Double(maxFPS) }

    mutating func shouldKeep(seconds pts: Double) -> Bool {
        guard pts.isFinite else { return true }
        if let next, pts < next - Self.tolerance { return false }
        let advanced = (next ?? pts) + interval
        next = advanced > pts + Self.tolerance ? advanced : pts + interval
        return true
    }
}
