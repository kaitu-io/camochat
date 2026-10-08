import Foundation

/// 把任意长度的 PCM 片段攒成 960 采样(20 ms)的整帧;结束时尾巴补零成一帧。
public struct VoiceFrameBuffer: Sendable {
    private var pending: [Float] = []

    public init() {}

    public mutating func append(_ samples: [Float]) -> [[Float]] {
        pending.append(contentsOf: samples)
        var frames: [[Float]] = []
        while pending.count >= OggOpusFile.frameSamples {
            frames.append(Array(pending[0..<OggOpusFile.frameSamples]))
            pending.removeFirst(OggOpusFile.frameSamples)
        }
        return frames
    }

    public mutating func flush() -> [Float]? {
        guard !pending.isEmpty else { return nil }
        let frame = pending + [Float](repeating: 0, count: OggOpusFile.frameSamples - pending.count)
        pending = []
        return frame
    }
}
