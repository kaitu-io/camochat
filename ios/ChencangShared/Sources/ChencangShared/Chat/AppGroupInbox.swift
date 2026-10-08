import Foundation

/// 扩展进程解密成功后的交接件。文字:明文 body;媒体:只有引用(kind + media,state = pending),
/// 扩展从不下载(spec §4.2)。明文/引用经 App Group 中转与「主 App 落 ChatStore」同信任域。
public struct InboxEntry: Codable, Equatable, Sendable {
    public let id: String
    public let peerId: String
    public let body: String
    public let timestamp: Date
    public let kind: MessageKind
    public let media: [MediaItem]?

    public init(id: String, peerId: String, body: String, timestamp: Date,
                kind: MessageKind = .text, media: [MediaItem]? = nil) {
        self.id = id
        self.peerId = peerId
        self.body = body
        self.timestamp = timestamp
        self.kind = kind
        self.media = media
    }

    private enum CodingKeys: String, CodingKey {
        case id, peerId, body, timestamp, kind, media
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        peerId = try c.decode(String.self, forKey: .peerId)
        body = try c.decode(String.self, forKey: .body)
        timestamp = try c.decode(Date.self, forKey: .timestamp)
        let rawKind = try c.decodeIfPresent(String.self, forKey: .kind)
        kind = rawKind.map { MessageKind(rawValue: $0) ?? .unsupported } ?? .text
        media = try c.decodeIfPresent(LossyArray<MediaItem>.self, forKey: .media)?.elements
    }
}

/// 只为了让 `ActionDecryptor` 能在测试里注入一个「写入必然失败」的假实现,验证
/// 收件箱写失败时的降级路径;生产环境唯一实现是 `AppGroupInbox`。
public protocol InboxWriting {
    func append(_ entry: InboxEntry) throws
}

/// 单键 JSON 数组,与 cc.pendingWire.v1 同模式。写者=Action Extension(append),
/// 读者=主 App(drain,读后清)。两进程不会同时活跃在前台,竞态窗口可忽略。
public struct AppGroupInbox: InboxWriting {
    public static let key = "cc.inbox.v1"
    private let defaults: AppGroupDefaults

    public init(defaults: AppGroupDefaults = SharedAppGroupDefaults()) {
        self.defaults = defaults
    }

    public func append(_ entry: InboxEntry) throws {
        var list = readAll()
        list.append(entry)
        let enc = JSONEncoder()
        enc.dateEncodingStrategy = .millisecondsSince1970
        defaults.set(try enc.encode(list), forKey: Self.key)
    }

    public func drain() -> [InboxEntry] {
        let list = readAll()
        clear()
        return list
    }

    public func clear() {
        defaults.set(nil, forKey: Self.key)
    }

    /// 只读快照,不清空——搭配 `remove(ids:)` 做「逐条落库、只删成功的」的安全 drain
    /// (`handleBecameActive` 用,替代整批 `drain()`:落库失败的条目要留到下次回前台重试)。
    public func readAll() -> [InboxEntry] {
        guard let data = defaults.data(forKey: Self.key) else { return [] }
        let dec = JSONDecoder()
        dec.dateDecodingStrategy = .millisecondsSince1970
        return (try? dec.decode(LossyArray<InboxEntry>.self, from: data))?.elements ?? []
    }

    /// 只删掉落库成功的那些 id;其余(落库失败的)原样留在收件箱里。
    public func remove(ids: Set<String>) throws {
        guard !ids.isEmpty else { return }
        let remaining = readAll().filter { !ids.contains($0.id) }
        guard !remaining.isEmpty else {
            clear()
            return
        }
        let enc = JSONEncoder()
        enc.dateEncodingStrategy = .millisecondsSince1970
        defaults.set(try enc.encode(remaining), forKey: Self.key)
    }
}

extension InboxEntry {
    /// 解开的 wire → 收件件。媒体只带引用(pending);占位正文为空,文案在显示时按语言取。
    public init(opened: OpenedWire, id: String, timestamp: Date) {
        switch opened.content {
        case let .text(text):
            self.init(id: id, peerId: opened.peerId, body: text, timestamp: timestamp)
        case let .media(kind, items):
            self.init(id: id, peerId: opened.peerId, body: "", timestamp: timestamp, kind: kind, media: items)
        case .unsupported:
            self.init(id: id, peerId: opened.peerId, body: "", timestamp: timestamp, kind: .unsupported)
        }
    }
}
