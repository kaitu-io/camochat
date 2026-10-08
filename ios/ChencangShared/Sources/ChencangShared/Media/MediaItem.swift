import Foundation

/// 一条媒体消息里的一个媒体（R4）。多图消息 1..9 个 item，共享同一条 `ChatMessage`。
/// 文件位置由 `MediaFiles` 按 (messageId, index) 推导，不单独存路径。
public struct MediaItem: Codable, Equatable, Hashable, Sendable {
    public enum State: String, Codable, Sendable {
        // 发送方：encrypting → uploading → sealed，失败 failed（点 ! 重试）
        case encrypting, uploading, sealed, failed
        // 接收方：pending → downloading → ready；过期 expired；解密失败 corrupt；网络失败 failed（可再下）
        case pending, downloading, ready, expired, corrupt
        // 接收方：中转 403/404 且收到不满 24 h → awaiting（等待对方上传，非终态，可再取；先分享、后上传 spec §2）
        case awaiting
    }

    /// 发送方上传「注定失败、重传也没用」的原因(审查 M7;终审 F2)。有它的 `failed` 项是终态:
    /// 对账与手动重试都跳过,气泡状态行显示原因(先分享、后上传 spec §1.3)。
    public enum UploadFailure: String, Codable, Sendable {
        /// 413:对象超过中转上限。
        case tooLarge
        /// 403/408/429/413 之外的 4xx:中转拒收,重传也一样。
        case rejected
    }

    public let index: Int
    public let kind: MediaKind
    public let durMs: Int
    public let width: Int
    public let height: Int
    /// `.cca` 总长（帧内 byte_len），接收方用它校验下载结果。
    public var byteLen: Int
    /// 32 B，由 core `encryptMediaBlob` 生成；发送方加密前为空。
    public var blobSecret: Data
    /// 22 字符 base64url；发送方加密前为空串。
    public var blobId: String
    public var state: State
    /// 接收方语音是否已播放（气泡红点，R9）。
    public var played: Bool
    /// 追加字段:旧记录没有它 → nil;更新版本写入的未知原因也读成 nil(退回「可自动重传」)。
    public var uploadFailure: UploadFailure?

    public init(index: Int, kind: MediaKind, durMs: Int, width: Int, height: Int, byteLen: Int,
                blobSecret: Data, blobId: String, state: State, played: Bool = false) {
        self.index = index
        self.kind = kind
        self.durMs = durMs
        self.width = width
        self.height = height
        self.byteLen = byteLen
        self.blobSecret = blobSecret
        self.blobId = blobId
        self.state = state
        self.played = played
        self.uploadFailure = nil
    }

    public var isTransferring: Bool {
        state == .encrypting || state == .uploading || state == .downloading
    }

    private enum CodingKeys: String, CodingKey {
        case index, kind, durMs, width, height, byteLen, blobSecret, blobId, state, played, uploadFailure
    }

    /// 容错解码:收件箱与线程文件里的记录可能来自别的版本。未知 kind 只让这一条失败(上层 LossyArray 跳过它);
    /// 未知 state 读成 failed(用户可重试/再下);缺 played 读成 false。
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        index = try c.decode(Int.self, forKey: .index)
        kind = try c.decode(MediaKind.self, forKey: .kind)
        durMs = try c.decode(Int.self, forKey: .durMs)
        width = try c.decode(Int.self, forKey: .width)
        height = try c.decode(Int.self, forKey: .height)
        byteLen = try c.decode(Int.self, forKey: .byteLen)
        blobSecret = try c.decode(Data.self, forKey: .blobSecret)
        blobId = try c.decode(String.self, forKey: .blobId)
        let rawState = try c.decode(String.self, forKey: .state)
        state = State(rawValue: rawState) ?? .failed
        played = try c.decodeIfPresent(Bool.self, forKey: .played) ?? false
        // 认不出的原因(降级 / 将来新增)按「被拒」:仍是永久失败「文件无法发送」,与 Android `fromStored` 同口径(UAT N2)。
        uploadFailure = (try? c.decodeIfPresent(String.self, forKey: .uploadFailure))
            .flatMap { $0 }.map { UploadFailure(rawValue: $0) ?? .rejected }
    }
}

extension MediaKind {
    public var messageKind: MessageKind {
        switch self {
        case .voice: return .voice
        case .image: return .image
        case .video: return .video
        }
    }
}
