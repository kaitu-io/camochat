import AVFoundation
import ChencangShared

/// 按住说话的录音器(spec §3.2、R8):
/// - 麦克风权限只在**按下时**申请(冷启动不碰 AVAudioSession);
/// - AVAudioEngine 输入 → 重采样 48 kHz 单声道 → 每 960 采样编一个 Opus 包 → 松手后封 Ogg;
/// - 50 秒起倒计时、60 秒通过 `onAutoStop` 通知界面自动发送。
@MainActor
final class VoiceRecorder: ObservableObject {
    enum StartResult { case started, denied, failed, cancelled }
    /// `.failed`(审查修复 4):编码器中途出错(极罕见的系统编解码器异常),不能悄悄丢帧再假装
    /// 录成了一段时长对不上的音频——整段判失败,不发送。
    enum StopResult { case cancelled, tooShort, failed, recorded(PreparedMedia) }

    static let levelCount = 24

    @Published private(set) var levels: [CGFloat] = Array(repeating: 0, count: VoiceRecorder.levelCount)
    @Published private(set) var phase: VoiceRecordingClock.Phase = .recording
    var onAutoStop: (() -> Void)?
    /// 来电/系统打断(审查修复 1c):AVAudioSession 中断开始时调用,界面据此按「取消」收尾,不发送。
    var onInterrupted: (() -> Void)?

    private var engine: AVAudioEngine?
    private var sink: VoiceCaptureSink?
    private var clock: VoiceRecordingClock?
    private var gate = VoiceSessionGate()
    private var ticker: Task<Void, Never>?
    private var interruptionObserver: NSObjectProtocol?

    /// 手指按下的**同一个同步调用栈里**调用,领本次按压的令牌(不 await)。
    func press() -> Int {
        gate.press()
    }

    /// 异步启动:等权限、建引擎。任何时刻令牌已作废(手指已松开)都不开麦。
    func start(token: Int) async -> StartResult {
        let session = AVAudioSession.sharedInstance()
        switch session.recordPermission {
        case .denied:
            return .denied
        case .undetermined:
            let granted = await withCheckedContinuation { continuation in
                session.requestRecordPermission { continuation.resume(returning: $0) }
            }
            guard granted else { return .denied }
            // 系统权限框弹出时手指已经离开按钮(SwiftUI 不会给这次手势回调 onEnded,
            // 只有 @GestureState 会自动重置)——这次不录,用户再按一次即可(审查修复 1a)。
            return .cancelled
        default:
            break
        }
        guard gate.isCurrent(token) else { return .cancelled }
        do {
            // 会话激活会阻塞,放到后台串行队列(终审 minor 5)
            try await AudioSessionQueue.run {
                let session = AVAudioSession.sharedInstance()
                try session.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker, .allowBluetooth])
                try session.setActive(true)
            }
        } catch {
            teardown()
            return .failed
        }
        // 等激活的这段时间手指可能已经松开
        guard gate.isCurrent(token) else {
            AudioSessionQueue.deactivate()
            return .cancelled
        }
        do {
            let engine = AVAudioEngine()
            let input = engine.inputNode
            let format = input.outputFormat(forBus: 0)
            let sink = try VoiceCaptureSink(inputFormat: format)
            input.installTap(onBus: 0, bufferSize: 1024, format: format) { [weak self] buffer, _ in
                let level = sink.consume(buffer)
                Task { @MainActor in self?.push(level: level) }
            }
            // 真正开麦前最后核对一次:令牌已作废就拆掉,不留运行中的引擎
            guard gate.isCurrent(token) else {
                input.removeTap(onBus: 0)
                AudioSessionQueue.deactivate()
                return .cancelled
            }
            try engine.start()
            self.engine = engine
            self.sink = sink
            clock = VoiceRecordingClock(startedAt: Date())
            phase = .recording
            levels = Array(repeating: 0, count: Self.levelCount)
            startTicker()
            observeInterruptions(session: session)
            return .started
        } catch {
            teardown()
            return .failed
        }
    }

    /// 幂等:没在录(已停/从未启动)时返回 .cancelled。总是先作废令牌,挡住还在路上的启动流程。
    func stop(cancelled: Bool) -> StopResult {
        gate.release()
        guard let clock, let sink else {
            teardown()
            return .cancelled
        }
        let outcome = clock.finish(at: Date(), cancelled: cancelled)
        teardown()                                   // 先停引擎、拆 tap,再收尾编码器
        let allPackets: [Data]
        do {
            allPackets = try sink.finish()
        } catch {
            return .failed
        }
        let packets = Array(allPackets.prefix(MediaLimits.maxDurationMs / 20))
        switch outcome {
        case .cancelled:
            return .cancelled
        case .tooShort:
            return .tooShort
        case .send:
            guard !packets.isEmpty else { return .tooShort }
            let ogg = OggOpusFile.mux(packets: packets, preSkip: sink.preSkip)
            // 按「总采样 − pre-skip」算(终审 minor 8),不把收尾补零/冲前瞻的两帧算进时长
            let durMs = min(MediaLimits.maxDurationMs,
                            OggOpusFile.durationMs(packetCount: packets.count, preSkip: sink.preSkip))
            return .recorded(PreparedMedia(kind: .voice, plaintext: ogg, durMs: durMs, width: 0, height: 0))
        }
    }

    private func teardown() {
        ticker?.cancel()
        ticker = nil
        if let interruptionObserver { NotificationCenter.default.removeObserver(interruptionObserver) }
        interruptionObserver = nil
        engine?.inputNode.removeTap(onBus: 0)
        engine?.stop()
        engine = nil
        sink = nil
        clock = nil
        AudioSessionQueue.deactivate()   // 停用也会阻塞,不在主线程等(终审 minor 5)
    }

    /// 审查修复 1c:来电/Siri/闹钟等打断开始时(`.began`),`.onDisappear`/手势 onEnded 都不会触发——
    /// 只有这个通知能告诉我们「麦克风已经不是我们的了」,界面据此按取消收尾。
    private func observeInterruptions(session: AVAudioSession) {
        interruptionObserver = NotificationCenter.default.addObserver(
            forName: AVAudioSession.interruptionNotification, object: session, queue: .main
        ) { [weak self] note in
            guard let info = note.userInfo,
                  let typeValue = info[AVAudioSessionInterruptionTypeKey] as? UInt,
                  AVAudioSession.InterruptionType(rawValue: typeValue) == .began else { return }
            Task { @MainActor in self?.onInterrupted?() }
        }
    }

    private func startTicker() {
        ticker = Task { [weak self] in
            while !Task.isCancelled {
                // 复用 Moyu.Motion.standard(220ms)作轮询间隔:这里不是过渡动效时长,
                // 只是借一个「够快、不过分」的既有节奏数字,不为轮询单开 token。
                try? await Task.sleep(nanoseconds: UInt64(Moyu.Motion.standard) * 1_000_000)
                guard let self, let clock = self.clock else { return }
                let phase = clock.phase(at: Date())
                self.phase = phase
                if phase == .mustStop {
                    self.onAutoStop?()
                    return
                }
            }
        }
    }

    private func push(level: Float) {
        guard engine != nil else { return }
        levels.removeFirst()
        // 语音 RMS 通常 < 0.25,放大 4 倍再截到 1,波形条才看得出起伏
        levels.append(CGFloat(min(1, level * 4)))
    }
}

/// 音频线程上的采集下游:重采样 → 分帧 → Opus。与主线程只经锁交换编码结果。
final class VoiceCaptureSink: @unchecked Sendable {
    private let lock = NSLock()
    private let converter: AVAudioConverter
    private let targetFormat: AVAudioFormat
    private let encoder: OpusEncoder
    private var frames = VoiceFrameBuffer()
    private var packets: [Data] = []
    /// 编码器出过错就记下来,不再继续编(徒劳),`finish()` 据此把整段录音判失败——
    /// 不能悄悄丢帧,那样只会留下有缺口、时长对不上的音频(审查修复 4)。
    private var encodeError: Error?

    /// 写进 OpusHead 的 pre-skip,取自编码器自报的前瞻。
    var preSkip: UInt16 { encoder.preSkip }

    init(inputFormat: AVAudioFormat) throws {
        guard let target = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: Double(OggOpusFile.sampleRate),
                                         channels: 1, interleaved: false),
              let converter = AVAudioConverter(from: inputFormat, to: target) else {
            throw MediaPrepError.voiceEncodeFailed
        }
        self.targetFormat = target
        self.converter = converter
        self.encoder = try OpusEncoder()
    }

    /// 返回这段音频的 RMS(0…1),供波形显示。
    func consume(_ buffer: AVAudioPCMBuffer) -> Float {
        let ratio = targetFormat.sampleRate / buffer.format.sampleRate
        let capacity = AVAudioFrameCount(Double(buffer.frameLength) * ratio) + 1024
        guard let out = AVAudioPCMBuffer(pcmFormat: targetFormat, frameCapacity: capacity) else { return 0 }
        var fed = false
        var error: NSError?
        converter.convert(to: out, error: &error) { _, status in
            if fed {
                status.pointee = .noDataNow
                return nil
            }
            fed = true
            status.pointee = .haveData
            return buffer
        }
        guard error == nil, let channel = out.floatChannelData?[0], out.frameLength > 0 else { return 0 }
        let samples = Array(UnsafeBufferPointer(start: channel, count: Int(out.frameLength)))
        lock.withLock {
            guard encodeError == nil else { return }   // 已经出过错,不再徒劳编码,等 finish() 报失败
            for frame in frames.append(samples) {
                do {
                    packets.append(try encoder.encode(frame: frame))
                } catch {
                    encodeError = error
                    return
                }
            }
        }
        let sum = samples.reduce(Float(0)) { $0 + $1 * $1 }
        return (sum / Float(samples.count)).squareRoot()
    }

    /// 收尾:尾巴补零成一帧,再多喂一帧静音把编码器前瞻里的最后一段声音冲出来。
    /// 任何一步编码出错都抛出(审查修复 4)——调用方(`VoiceRecorder.stop`)据此判 `.failed`,不发送。
    func finish() throws -> [Data] {
        try lock.withLock {
            if encodeError == nil, let tail = frames.flush() {
                do {
                    packets.append(try encoder.encode(frame: tail))
                } catch {
                    encodeError = error
                }
            }
            if encodeError == nil {
                do {
                    packets.append(try encoder.encode(frame: [Float](repeating: 0, count: encoder.frameSamples)))
                } catch {
                    encodeError = error
                }
            }
            if let encodeError { throw encodeError }
            return packets
        }
    }
}
