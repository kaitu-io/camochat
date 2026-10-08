import AudioToolbox
import Foundation

public enum OpusCodecError: Error, Equatable {
    case unavailable(OSStatus)
    case encodeFailed(OSStatus)
    case decodeFailed(OSStatus)
}

enum OpusFormats {
    /// 自定义状态码:输入回调「这次的数据喂完了」。AudioConverter 原样把它返回给 FillComplexBuffer,
    /// 但转换器内部状态(编码器前瞻、解码器历史)保留,下次调用继续用。
    static let noMoreInput: OSStatus = 0x6E6D_6F72   // 'nmor'
    /// 单个 Opus 包最长 120 ms = 5760 采样(RFC 6716)。
    static let maxPacketSamples = 5_760

    static var pcm: AudioStreamBasicDescription {
        AudioStreamBasicDescription(
            mSampleRate: 48_000, mFormatID: kAudioFormatLinearPCM,
            mFormatFlags: kAudioFormatFlagIsFloat | kAudioFormatFlagIsPacked,
            mBytesPerPacket: 4, mFramesPerPacket: 1, mBytesPerFrame: 4,
            mChannelsPerFrame: 1, mBitsPerChannel: 32, mReserved: 0)
    }

    /// 编码端写死每包帧数;解码端传 0 = 每包时长可变(Android 可能发 40/60 ms 的包,本机实测 0 才能全解)。
    static func opus(framesPerPacket: UInt32) -> AudioStreamBasicDescription {
        AudioStreamBasicDescription(
            mSampleRate: 48_000, mFormatID: kAudioFormatOpus, mFormatFlags: 0,
            mBytesPerPacket: 0, mFramesPerPacket: framesPerPacket, mBytesPerFrame: 0,
            mChannelsPerFrame: 1, mBitsPerChannel: 0, mReserved: 0)
    }
}

/// PCM Float32 48 kHz 单声道 → Opus(每次 `frameSamples` 采样出一个包;默认 960 = 20 ms)。
public final class OpusEncoder {
    public let frameSamples: Int
    /// 编码器前瞻(RFC 7845 pre-skip),从 `kAudioConverterPrimeInfo` 读;查询失败回退 312。
    public let preSkip: UInt16
    private let converter: AudioConverterRef
    private let maxPacketSize: UInt32

    public init(bitRate: UInt32 = 24_000, frameSamples: Int = OggOpusFile.frameSamples) throws {
        var input = OpusFormats.pcm
        var output = OpusFormats.opus(framesPerPacket: UInt32(frameSamples))
        var conv: AudioConverterRef?
        let status = AudioConverterNew(&input, &output, &conv)
        guard status == noErr, let conv else { throw OpusCodecError.unavailable(status) }
        var rate = bitRate
        AudioConverterSetProperty(conv, kAudioConverterEncodeBitRate, UInt32(MemoryLayout<UInt32>.size), &rate)
        var maxSize: UInt32 = 0
        var size = UInt32(MemoryLayout<UInt32>.size)
        AudioConverterGetProperty(conv, kAudioConverterPropertyMaximumOutputPacketSize, &size, &maxSize)
        var prime = AudioConverterPrimeInfo()
        var primeSize = UInt32(MemoryLayout<AudioConverterPrimeInfo>.size)
        let primeStatus = AudioConverterGetProperty(conv, kAudioConverterPrimeInfo, &primeSize, &prime)
        preSkip = (primeStatus == noErr && prime.leadingFrames > 0 && prime.leadingFrames <= UInt32(UInt16.max))
            ? UInt16(prime.leadingFrames) : OggOpusFile.defaultPreSkip
        converter = conv
        maxPacketSize = max(maxSize, 1_500)
        self.frameSamples = frameSamples
    }

    deinit { AudioConverterDispose(converter) }

    public func encode(frame: [Float]) throws -> Data {
        precondition(frame.count == frameSamples, "Opus 帧长度必须等于 frameSamples")
        let feed = PCMFeed(frame)
        let out = UnsafeMutableRawBufferPointer.allocate(byteCount: Int(maxPacketSize), alignment: 1)
        defer { out.deallocate() }
        var bufferList = AudioBufferList(
            mNumberBuffers: 1,
            mBuffers: AudioBuffer(mNumberChannels: 1, mDataByteSize: maxPacketSize, mData: out.baseAddress))
        var description = AudioStreamPacketDescription()
        var ioPackets: UInt32 = 1
        let status = AudioConverterFillComplexBuffer(
            converter, pcmInputProc, Unmanaged.passUnretained(feed).toOpaque(), &ioPackets, &bufferList, &description)
        guard status == noErr || status == OpusFormats.noMoreInput, ioPackets == 1 else {
            throw OpusCodecError.encodeFailed(status)
        }
        let start = Int(description.mStartOffset)
        return Data(out[start..<(start + Int(description.mDataByteSize))])
    }
}

/// Opus 包 → PCM Float32 48 kHz 单声道。包长可变(20/40/60 ms…最多 120 ms)。
public final class OpusDecoder {
    private let converter: AudioConverterRef

    public init() throws {
        var input = OpusFormats.opus(framesPerPacket: 0)
        var output = OpusFormats.pcm
        var conv: AudioConverterRef?
        let status = AudioConverterNew(&input, &output, &conv)
        guard status == noErr, let conv else { throw OpusCodecError.unavailable(status) }
        converter = conv
    }

    deinit { AudioConverterDispose(converter) }

    /// 按 120 ms 的容量循环取输出,直到转换器报告这次的输入已喂完。
    public func decode(packet: Data) throws -> [Float] {
        let feed = PacketFeed(packet)
        var output: [Float] = []
        while true {
            var chunk = [Float](repeating: 0, count: OpusFormats.maxPacketSamples)
            var ioFrames = UInt32(OpusFormats.maxPacketSamples)
            let status: OSStatus = chunk.withUnsafeMutableBytes { raw in
                var bufferList = AudioBufferList(
                    mNumberBuffers: 1,
                    mBuffers: AudioBuffer(mNumberChannels: 1, mDataByteSize: UInt32(raw.count), mData: raw.baseAddress))
                return AudioConverterFillComplexBuffer(
                    converter, packetInputProc, Unmanaged.passUnretained(feed).toOpaque(), &ioFrames, &bufferList, nil)
            }
            guard status == noErr || status == OpusFormats.noMoreInput else {
                throw OpusCodecError.decodeFailed(status)
            }
            output.append(contentsOf: chunk.prefix(Int(ioFrames)))
            if status == OpusFormats.noMoreInput || ioFrames == 0 { break }
        }
        return output
    }
}

/// 输入回调的数据源都放在自己分配的内存里,保证回调返回后指针仍有效。
private final class PCMFeed {
    let samples: UnsafeMutableBufferPointer<Float>
    var consumed = false

    init(_ frame: [Float]) {
        samples = .allocate(capacity: frame.count)
        _ = samples.initialize(from: frame)
    }

    deinit { samples.deallocate() }
}

private final class PacketFeed {
    let bytes: UnsafeMutableRawBufferPointer
    let description: UnsafeMutablePointer<AudioStreamPacketDescription>
    let count: Int
    var consumed = false

    init(_ packet: Data) {
        count = packet.count
        bytes = .allocate(byteCount: max(1, packet.count), alignment: 1)
        bytes.copyBytes(from: packet)
        description = .allocate(capacity: 1)
        description.initialize(to: AudioStreamPacketDescription(
            mStartOffset: 0, mVariableFramesInPacket: 0, mDataByteSize: UInt32(packet.count)))
    }

    deinit {
        bytes.deallocate()
        description.deallocate()
    }
}

private let pcmInputProc: AudioConverterComplexInputDataProc = { _, ioPackets, ioData, _, userData in
    let feed = Unmanaged<PCMFeed>.fromOpaque(userData!).takeUnretainedValue()
    guard !feed.consumed else {
        ioPackets.pointee = 0
        return OpusFormats.noMoreInput
    }
    feed.consumed = true
    ioData.pointee.mNumberBuffers = 1
    ioData.pointee.mBuffers.mNumberChannels = 1
    ioData.pointee.mBuffers.mData = UnsafeMutableRawPointer(feed.samples.baseAddress)
    ioData.pointee.mBuffers.mDataByteSize = UInt32(feed.samples.count * MemoryLayout<Float>.size)
    ioPackets.pointee = UInt32(feed.samples.count)
    return noErr
}

private let packetInputProc: AudioConverterComplexInputDataProc = { _, ioPackets, ioData, outDescription, userData in
    let feed = Unmanaged<PacketFeed>.fromOpaque(userData!).takeUnretainedValue()
    guard !feed.consumed else {
        ioPackets.pointee = 0
        return OpusFormats.noMoreInput
    }
    feed.consumed = true
    ioData.pointee.mNumberBuffers = 1
    ioData.pointee.mBuffers.mNumberChannels = 1
    ioData.pointee.mBuffers.mData = feed.bytes.baseAddress
    ioData.pointee.mBuffers.mDataByteSize = UInt32(feed.count)
    outDescription?.pointee = feed.description
    ioPackets.pointee = 1
    return noErr
}

extension OggOpusFile {
    /// 录音时长(终审 minor 8):解码出的总采样数减去编码器前瞻 pre-skip,换算成毫秒
    /// (48 kHz ⇒ 每毫秒 48 个采样)。不再按「包数 × 20 ms」算——那会把收尾补零的尾帧和
    /// 冲出前瞻的那一帧静音都算进去,5.5 秒能显示成 6″。
    public static func durationMs(packetCount: Int, preSkip: UInt16) -> Int {
        max(0, (packetCount * frameSamples - Int(preSkip)) / (sampleRate / 1000))
    }

    /// 整段 Ogg/Opus → 48 kHz 单声道 PCM(已丢掉 pre-skip)。**任何一个包解码失败都整段抛错**
    /// (终审 minor 7):以前 `try?` 跳过坏包,放出来是一段中间缺几截的音频,还假装正常;
    /// 现在调用方把它当「无法播放」处理。
    public static func decodePCM(_ data: Data) throws -> [Float] {
        let parsed = try demux(data)
        let decoder = try OpusDecoder()
        var samples: [Float] = []
        for packet in parsed.packets {
            samples.append(contentsOf: try decoder.decode(packet: packet))
        }
        samples.removeFirst(min(Int(parsed.preSkip), samples.count))   // RFC 7845:丢掉编码器前瞻
        guard !samples.isEmpty else { throw OggOpusError.truncated }
        return samples
    }
}
