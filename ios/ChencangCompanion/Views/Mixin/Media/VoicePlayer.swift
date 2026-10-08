import AVFoundation
import ChencangShared

/// 语音播放:Ogg 解封装 → AudioToolbox Opus 解码 → AVAudioPlayerNode。走系统默认音频路由(spec §3.4)。
/// 同一时间只放一条;再点正在放的那条 = 停止。
@MainActor
final class VoicePlayer: ObservableObject {
    @Published private(set) var playingKey: String?

    private let engine = AVAudioEngine()
    private let node = AVAudioPlayerNode()
    private let format = AVAudioFormat(standardFormatWithSampleRate: Double(OggOpusFile.sampleRate), channels: 1)!
    private var attached = false
    /// 世代校验:解码放到 detached task 里跑,完成时用户可能已经切到另一条或按了 stop()——
    /// 迟到的解码结果不能把已经换掉的 playingKey 顶掉(审查修复 1)。
    private var generation = 0
    /// 当前这次放音请求的作废令牌(终审复审 1):`stop()` 作废它。与 `generation` 同步,但能在后台
    /// 音频会话队列上读——激活那一块轮到执行时令牌已作废(比如用户已经按下了录音),就不碰会话。
    private var playbackFlag: CancellationFlag?

    /// 这次激活过会话、还没停用:只有真在放过才在 `stop()` 里停用会话——按下录音前也会调
    /// `stop()`(终审 minor 6),那时候不能平白去停一个不是我们激活的会话。
    private var sessionActive = false

    /// 解码整段 Opus(可能长达 60 秒、上千个包)绝不能堵主线程:demux/解码在 `Task.detached` 里跑,
    /// 拿回来的只是 `[Float]`(Sendable);会话激活也在后台串行队列上做(终审 minor 5),
    /// 真正触碰 `AVAudioEngine`/`node` 的部分仍在主 actor 上执行。
    /// 任一包解码失败 → 整条无法播放,调 `onUnplayable`(终审 minor 7),不放一段缺了几截的音频。
    func toggle(url: URL, key: String, onUnplayable: @escaping @MainActor () -> Void) {
        if playingKey == key {
            stop()
            return
        }
        stop()
        generation += 1
        let myGeneration = generation
        let flag = CancellationFlag()
        playbackFlag = flag
        Task.detached(priority: .userInitiated) {
            let samples = try? OggOpusFile.decodePCM(Data(contentsOf: url))
            guard let samples else {
                await MainActor.run { [weak self] in
                    guard let self, self.generation == myGeneration else { return }
                    onUnplayable()
                }
                return
            }
            // 排队前、轮到执行时各查一次令牌:已作废(换了一条/按了停/按下录音)就不把会话切回 .playback,
            // 以免压掉排在它后面的录音 .playAndRecord 激活。
            let activated = await AudioSessionQueue.run(unlessCancelled: flag) {
                try AVAudioSession.sharedInstance().setCategory(.playback, mode: .default)
                try AVAudioSession.sharedInstance().setActive(true)
            }
            await MainActor.run { [weak self] in
                // 过期的激活不记账(复审 1):那之后会话可能已归录音所有,下一次 stop() 不能去停用它。
                guard let self, activated, !flag.isCancelled, self.generation == myGeneration else { return }
                self.sessionActive = true
                self.play(samples: samples, key: key)
            }
        }
    }

    func stop() {
        generation += 1   // 让还在跑的 decode 任务的结果作废
        playbackFlag?.cancel()
        playbackFlag = nil
        if attached { node.stop() }
        engine.stop()
        playingKey = nil
        if sessionActive {
            sessionActive = false
            AudioSessionQueue.deactivate()
        }
    }

    private func play(samples: [Float], key: String) {
        guard let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(samples.count)),
              let channel = buffer.floatChannelData?[0] else { return }
        buffer.frameLength = AVAudioFrameCount(samples.count)
        samples.withUnsafeBufferPointer { channel.update(from: $0.baseAddress!, count: samples.count) }
        do {
            if !attached {
                engine.attach(node)
                engine.connect(node, to: engine.mainMixerNode, format: format)
                attached = true
            }
            try engine.start()
        } catch {
            return
        }
        node.scheduleBuffer(buffer, completionCallbackType: .dataPlayedBack) { [weak self] _ in
            Task { @MainActor in
                if self?.playingKey == key { self?.stop() }
            }
        }
        node.play()
        playingKey = key
    }
}
