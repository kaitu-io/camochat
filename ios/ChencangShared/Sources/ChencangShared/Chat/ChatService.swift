import Foundation

/// iOS 版 ChatRepository:封缄发送 / 收 wire 入库 / 收件箱明文入库。
@MainActor
public final class ChatService {
    public struct SealedText: Equatable {
        public let message: ChatMessage
        public let wire: String
    }

    private let store: ChatStore
    private let crypto: ThreadCrypto
    private let contactIds: () -> [String]
    private let now: () -> Date
    private let newId: () -> String
    private let isWire: (String) -> Bool
    private let onIncomingStored: (String) -> Void

    public init(
        store: ChatStore,
        crypto: ThreadCrypto,
        contactIds: @escaping () -> [String],
        now: @escaping () -> Date = Date.init,
        newId: @escaping () -> String = { UUID().uuidString },
        isWire: @escaping (String) -> Bool = WireLocator.decodes,
        onIncomingStored: @escaping (String) -> Void = { _ in }
    ) {
        self.store = store
        self.crypto = crypto
        self.contactIds = contactIds
        self.now = now
        self.newId = newId
        self.isWire = isWire
        self.onIncomingStored = onIncomingStored
    }

    public func sendTextSealed(to peerId: String, text: String) async throws -> SealedText {
        let wire = try await crypto.sealText(peerId: peerId, text: text)
        let msg = ChatMessage(id: newId(), peerId: peerId, direction: .outgoing,
                              body: text, timestamp: now(), status: .sealed, wire: wire)
        try store.append(msg)
        return SealedText(message: msg, wire: wire)
    }

    /// 交出结果(对齐 Android `ChatRepository.MarkResult`):`marked` = 这次状态真的往前走了一步;
    /// `unchanged` = 已经是这个状态或更靠后(已分享不会被复制降级);`notOwned` = 不是这个会话里自己发出的消息
    /// (收到的/不存在)。
    public enum HandOffResult: Equatable {
        case marked, unchanged, notOwned
    }

    /// 点了复制:已加密 → 已复制;已复制、已分享保持不变。
    @discardableResult
    public func markCopied(messageId: String, peerId: String) -> HandOffResult {
        advance(messageId: messageId, peerId: peerId, to: .copied, from: [.sealed])
    }

    /// 分享面板选了目标:已加密 / 已复制 → 已分享;已分享保持不变。
    @discardableResult
    public func markShared(messageId: String, peerId: String) -> HandOffResult {
        advance(messageId: messageId, peerId: peerId, to: .shared, from: [.sealed, .copied])
    }

    /// 读状态与写状态在同一次同步调用里完成:本类与 `ChatStore` 都是 `@MainActor`,这里没有 `await`,
    /// 检查和写入之间不会插进另一次 mark——复制收尾与分享面板回调无论谁先到,已分享都不会被降级。
    /// 落盘失败沿用原先的吞掉(`setStatus` 已把内存滚回),结果仍报 `marked`:界面照常收卡、提示。
    private func advance(messageId: String, peerId: String, to target: ChatMessage.Status,
                         from allowed: Set<ChatMessage.Status>) -> HandOffResult {
        guard let message = store.message(id: messageId, peerId: peerId), message.direction == .outgoing else {
            return .notOwned
        }
        guard allowed.contains(message.status) else { return .unchanged }
        try? store.setStatus(id: messageId, peerId: peerId, target)
        return .marked
    }

    public func message(id: String, peerId: String) -> ChatMessage? {
        store.message(id: id, peerId: peerId)
    }

    /// 粘贴/收件进来的 wire。R2:先过 `WireLocator.extract` 定位真正的密文行(容忍
    /// R1 两行分享形态混在普通聊天文本里粘贴进来),非本会话可解 → nil(调用方给
    /// 「无法启缄」)。落库失败(磁盘/编码错误)不再用 `try?` 吞掉——棘轮已经推进,
    /// 这条密文再解不出第二次,调用方需要知道到底是「解不开」还是「落库失败」。
    @discardableResult
    public func receiveWireText(_ raw: String) async throws -> ChatMessage? {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        guard let wire = WireLocator.extract(trimmed, isWire: isWire) else { return nil }
        guard let opened = await crypto.openWire(wire, candidates: contactIds()) else { return nil }
        return try ingest(InboxEntry(opened: opened, id: newId(), timestamp: now()))
    }

    /// 收件箱 drain 用:扩展进程已解好的内容直接入库(不再碰棘轮)。文字、媒体引用、占位都走这里。
    /// 按 id 幂等:这条 id 已经落库过(上次回前台成功过,只是收件箱清理失败又重投),
    /// 直接返回已有记录,不重复 append——调用方(`handleBecameActive`)靠这个安全重放。
    @discardableResult
    public func ingest(_ entry: InboxEntry) throws -> ChatMessage {
        if let existing = store.message(id: entry.id, peerId: entry.peerId) {
            return existing
        }
        let msg = ChatMessage(id: entry.id, peerId: entry.peerId, direction: .incoming,
                              body: entry.body, timestamp: entry.timestamp, status: .received,
                              kind: entry.kind, media: entry.media)
        try store.append(msg)
        // 真正落库了一条 incoming(同 id 重放在上面已提前返回,不会走到这里)。
        onIncomingStored(entry.peerId)
        return msg
    }

    @discardableResult
    public func ingest(peerId: String, text: String, timestamp: Date, id: String) throws -> ChatMessage {
        try ingest(InboxEntry(id: id, peerId: peerId, body: text, timestamp: timestamp))
    }
}
