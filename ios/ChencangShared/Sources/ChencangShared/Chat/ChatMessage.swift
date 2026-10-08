import Foundation

/// 消息种类（R4），存字符串。未知字符串（更新版本写入的新种类）按 `.unsupported` 读出，不让整个线程文件解码失败。
public enum MessageKind: String, Codable, Sendable {
    case text, voice, image, video, unsupported

    public var isMedia: Bool { self == .voice || self == .image || self == .video }

    /// 种类的显示词:分享首行 `card_share_header_media` 的 `{kind}`、封缄卡摘要、扩展里的媒体摘要。
    /// 多张图片用复数键。
    public func displayLabel(count: Int) -> String {
        switch self {
        case .voice: return L10n.mediaKindVoice
        case .image: return count > 1 ? L10n.mediaPhotoCount(count) : L10n.mediaKindImage
        case .video: return L10n.mediaKindVideo
        case .text, .unsupported: return L10n.mediaKindMessage
        }
    }
}

/// 与 Android `ChatMessage`(Room 实体)字段对齐。status 只说用户做过的事(App 永远不知道对方收没收到):
/// outgoing:sealed = 已加密还没交出,copied = 点了复制,shared = 分享面板选了目标;incoming:received。
/// 媒体消息(R4):`kind` 为 voice/image/video,`media` 存 1..9 个条目;发送方封缄成功后
/// `body` 存 R1 两行分享文本(供「复制密文」),此前为空串;接收方媒体消息 `body` 为空串。
public struct ChatMessage: Codable, Equatable, Identifiable, Hashable, Sendable {
    public enum Direction: String, Codable, Sendable {
        case incoming = "in"
        case outgoing = "out"
    }
    /// 旧版本写的 `"sent"` 在 `ChatMessage.init(from:)` 里按方向迁移(outgoing → shared,incoming → received),
    /// 认不出的值不丢消息(outgoing → sealed,incoming → received)。编码只写新值。
    public enum Status: String, Codable, Sendable {
        case sealed
        case copied
        case shared
        case received
    }

    public let id: String
    public let peerId: String
    public let direction: Direction
    public var body: String
    public let timestamp: Date
    public var status: Status
    public let kind: MessageKind
    public var media: [MediaItem]?
    /// 自己发出的文字消息的 wire(密文,非密钥)。封缄卡被顶掉/取消后,从气泡长按仍能再分享/复制。
    /// 本字段加入之前落库的消息、收到的消息、媒体消息都是 nil(旧 JSON 没有这个键,照常可读)。
    public var wire: String?

    public init(id: String, peerId: String, direction: Direction, body: String, timestamp: Date, status: Status,
                kind: MessageKind = .text, media: [MediaItem]? = nil, wire: String? = nil) {
        self.id = id
        self.peerId = peerId
        self.direction = direction
        self.body = body
        self.timestamp = timestamp
        self.status = status
        self.kind = kind
        self.media = media
        self.wire = wire
    }

    private enum CodingKeys: String, CodingKey {
        case id, peerId, direction, body, timestamp, status, kind, media, wire
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        peerId = try c.decode(String.self, forKey: .peerId)
        direction = try c.decode(Direction.self, forKey: .direction)
        body = try c.decode(String.self, forKey: .body)
        timestamp = try c.decode(Date.self, forKey: .timestamp)
        // 状态按方向解:旧 "sent" 与未知值都不能让这条消息被 LossyArray 丢掉。
        let rawStatus = try c.decode(String.self, forKey: .status)
        status = Self.migratedStatus(rawStatus, direction: direction)
        // 旧版本 JSON 没有 kind → 文字;更新版本写入的未知 kind → 占位。
        let rawKind = try c.decodeIfPresent(String.self, forKey: .kind)
        kind = rawKind.map { MessageKind(rawValue: $0) ?? .unsupported } ?? .text
        media = try c.decodeIfPresent(LossyArray<MediaItem>.self, forKey: .media)?.elements
        wire = try c.decodeIfPresent(String.self, forKey: .wire)
    }

    private static func migratedStatus(_ raw: String, direction: Direction) -> Status {
        switch (Status(rawValue: raw), direction) {
        case (.some(let known), _): return known
        case (nil, .incoming): return .received
        case (nil, .outgoing): return raw == "sent" ? .shared : .sealed
        }
    }

    /// 会话列表摘要。占位消息不读 body(老数据里存的是写死的中文),显示时按当前语言取。
    public var summary: String {
        switch kind {
        case .text: return body
        case .unsupported: return L10n.threadUnsupportedMessage
        case .voice: return L10n.mediaPreviewVoice
        case .image:
            let count = media?.count ?? 1
            return count > 1 ? L10n.mediaPreviewImages(count) : L10n.mediaPreviewImage
        case .video: return L10n.mediaPreviewVideo
        }
    }

    /// 发送方媒体消息还没产出分享文本、且没有条目正在传输 → 气泡旁显示红 `!`,点击重试。
    public var outgoingMediaNeedsRetry: Bool {
        guard direction == .outgoing, kind.isMedia, body.isEmpty, let items = media else { return false }
        return !items.contains { $0.isTransferring }
    }
}
