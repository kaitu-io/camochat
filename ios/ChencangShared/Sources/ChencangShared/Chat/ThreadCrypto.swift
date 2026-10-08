import Foundation
import Chencang

/// 解开一条 wire 得到的内容。
public enum OpenedContent: Equatable, Sendable {
    case text(String)
    case media(kind: MessageKind, items: [MediaItem])
    /// 解密成功但帧不认识(新版本的消息类型)——R3:落占位,绝不丢。
    case unsupported
}

public struct OpenedWire: Equatable, Sendable {
    public let peerId: String
    public let content: OpenedContent

    public init(peerId: String, content: OpenedContent) {
        self.peerId = peerId
        self.content = content
    }
}

/// 封缄/启缄抽象。fake 注入用于单测;真实现走 SessionStore + bindings 帧 API。
public protocol ThreadCrypto: Sendable {
    /// 明文 → 「🔒…」wire。无会话抛 SessionStoreError.noSession。
    func sealText(peerId: String, text: String) async throws -> String
    /// 已上传条目 → MEDIA_REF 帧 → DR 加密 → wire。
    func sealMedia(peerId: String, items: [MediaItem]) async throws -> String
    /// wire → (发件人, 内容)。非本会话/解不开 → nil。
    func openWire(_ wire: String, candidates: [String]) async -> OpenedWire?
}

public struct SessionThreadCrypto: ThreadCrypto {
    private let store: SessionStore
    public init(store: SessionStore = .shared) { self.store = store }

    public func sealText(peerId: String, text: String) async throws -> String {
        let frame = encodeTextFrame(text: text)
        let ct = try await store.encryptToBytesPersisting(plaintext: frame, for: peerId)
        return encodeWire(ciphertext: ct)
    }

    public func sealMedia(peerId: String, items: [MediaItem]) async throws -> String {
        let frame = try encodeMediaRefFrame(refs: items.map(\.mediaRef))
        let ct = try await store.encryptToBytesPersisting(plaintext: frame, for: peerId)
        return encodeWire(ciphertext: ct)
    }

    public func openWire(_ wire: String, candidates: [String]) async -> OpenedWire? {
        guard let ct = try? decodeWire(s: wire) else { return nil }
        guard let (pt, id) = await store.decryptFromBytesAny(ciphertext: ct, candidates: candidates) else { return nil }
        // 走到这里 = DR 棘轮已推进、这条密文再也解不开第二次。此后任何解析失败
        // (含 decodeFrame 抛 ChencangError.Decoding)都必须落占位消息(R3),不能返回 nil。
        guard let decoded = try? decodeFrame(bytes: pt) else {
            return OpenedWire(peerId: id, content: .unsupported)
        }
        switch decoded {
        case let .text(value):
            return OpenedWire(peerId: id, content: .text(value))
        case let .media(refs):
            // 一条媒体消息里的多个 item 共享同一个 kind(spec §1.1);混 kind 只可能是
            // 帧被篡改或未来版本的新形态,当前版本不认识,同样落占位。
            guard let items = MediaItem.received(from: refs), let first = items.first,
                  items.allSatisfy({ $0.kind == first.kind }) else {
                return OpenedWire(peerId: id, content: .unsupported)
            }
            return OpenedWire(peerId: id, content: .media(kind: first.kind.messageKind, items: items))
        }
    }
}
