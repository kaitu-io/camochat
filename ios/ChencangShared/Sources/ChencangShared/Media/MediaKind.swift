import Foundation

/// 媒体种类：数值与 core `MEDIA_KIND_*`、`.cca` 第 6 字节、签名函数 `kind` 参数同一套编号（spec §1.1）。
public enum MediaKind: UInt8, Codable, Sendable, CaseIterable {
    case voice = 1
    case image = 2
    case video = 3

    /// `.cca` 总长上限（含 34 B 头 + 16 B tag），与 core `MEDIA_MAX_BLOB_LEN_*`、Lambda `LIMITS` 同步（R7）。
    public var maxBlobLen: Int {
        switch self {
        case .voice, .image: return 2_097_152
        case .video: return 31_457_280
        }
    }

    /// 明文预算 = 上限 − 50（R7）。
    public var plaintextBudget: Int { maxBlobLen - MediaLimits.ccaOverhead }
}

public enum MediaLimits {
    /// `.cca` 头 34 B + AEAD tag 16 B。
    public static let ccaOverhead = 50
    public static let maxItemsPerFrame = 9
    /// 语音/视频时长上限（帧内 `dur_ms` 的硬上限）。
    public static let maxDurationMs = 60_000
    public static let imageLongEdge = 1920
    public static let maxVideoSeconds: Double = 60
    /// App 内「已过期」判定：消息时间戳 + 24 小时。
    public static let ttl: TimeInterval = 86_400
}
