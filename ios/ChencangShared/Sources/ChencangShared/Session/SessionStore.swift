import Foundation
import Chencang

/// Actor-confined cache + Keychain persistence of `Session` objects. The
/// uniffi `Session` is not thread-safe; we serialise all access through
/// this actor (see `bindings/docs/INTEGRATION.md`).
///
/// V1 wire codec: ciphertext is raw bytes (DR + AEAD). The user-visible
/// "🔒…" envelope is applied / stripped by `encodeWire` / `decodeWire` in
/// the binding, outside this layer. The legacy String-based
/// encrypt/decrypt (z-base32 wire) is no longer exposed here — V1 callers
/// go through the bytes API.
public actor SessionStore {
    public static let shared = SessionStore()

    private var cache: [String: Session] = [:]
    private let keychain: KeychainStoreProtocol

    public init(
        keychain: KeychainStoreProtocol = KeychainStore(service: "app.chencang.sessions")
    ) {
        self.keychain = keychain
    }

    public func session(for contactId: String) throws -> Session? {
        if let s = cache[contactId] { return s }
        guard let blob = try keychain.read(key: "sess_\(contactId)") else {
            return nil
        }
        let s = try Session.fromSerializedState(state: blob)
        cache[contactId] = s
        return s
    }

    public func save(_ session: Session, for contactId: String) throws {
        cache[contactId] = session
        try keychain.write(key: "sess_\(contactId)", data: session.serializeState())
    }

    /// Encrypts [plaintext] under the session for [contactId] and returns
    /// the raw L3 ciphertext bytes (DR + AEAD; not yet Base32768-wrapped).
    /// Caller wraps with `encodeWire(...)` to produce the user-visible "🔒…"
    /// envelope. Throws `SessionStoreError.noSession` if no session is cached.
    public func encryptToBytes(plaintext: Data, for contactId: String) throws -> Data {
        guard let s = cache[contactId] else {
            throw SessionStoreError.noSession(contactId)
        }
        return try s.encryptToBytes(plaintext: plaintext)
    }

    /// Walks every cached session attempting to decrypt the raw L3 bytes.
    /// Returns the matched `(plaintext, senderId)` tuple, else nil.
    ///
    /// Each failed attempt is swallowed silently because Double Ratchet
    /// decrypt raises on any mismatch — that's the expected signal that we
    /// tried the wrong session. The caller only sees a failure when no
    /// session matched.
    public func decryptFromBytesAny(ciphertext: Data) -> (Data, String)? {
        for (id, sess) in cache {
            if let pt = try? sess.decryptFromBytes(ciphertext: ciphertext) {
                return (pt, id)
            }
        }
        return nil
    }

    /// 冷进程路由:先把 candidates 逐个从 Keychain 暖进缓存,再走整缓存试解密。
    /// 命中后立即把该会话的推进态写回 Keychain —— 收信推进了 DR 接收链,
    /// 不落盘会导致后续来信解不开。未命中的会话**不**写回,保持其 Keychain
    /// 态为尝试前状态(与 Android 侧"全量落盘"策略有意分叉:iOS 侧重试语义
    /// 更安全,分叉理由记录于 M3 plan Task 2)。
    public func decryptFromBytesAny(ciphertext: Data, candidates: [String]) -> (Data, String)? {
        for id in candidates where cache[id] == nil {
            _ = try? session(for: id)   // 坏/缺条目静默跳过:少一个候选而已
        }
        guard let (pt, id) = decryptFromBytesAny(ciphertext: ciphertext) else { return nil }
        if let s = cache[id] {
            // 此处故意 try?(而非 encryptToBytesPersisting 那样裸 try 抛出):
            // 解密侧此刻明文已在手、message key 已消耗——写失败就抛错=把已经
            // 拿到手的消息强行丢弃,必然丢消息,不可接受。内存缓存仍是暖的,
            // 携带这次推进后的态,下一次任意成功写入(下条消息命中/其他路径)
            // 会把这次的推进态一并补齐落盘,不会永久丢失。加密侧则相反:
            // 密文此时还没离开本机(还没进 wire/发送),写失败可以安全中止
            // 整次发送,所以那边保留裸 try 快速失败。
            try? keychain.write(key: "sess_\(id)", data: s.serializeState())
        }
        return (pt, id)
    }

    /// 加密 + 立即落盘(发送链推进)。文本/后续所有新调用方一律用这个,
    /// 不要再用裸 encryptToBytes(留给既有语音管线,勿改动它)。
    public func encryptToBytesPersisting(plaintext: Data, for contactId: String) throws -> Data {
        guard let s = try session(for: contactId) else {
            throw SessionStoreError.noSession(contactId)
        }
        let ct = try s.encryptToBytes(plaintext: plaintext)
        try keychain.write(key: "sess_\(contactId)", data: s.serializeState())
        return ct
    }

    /// 跨进程一致性:Action Extension 可能在 App 退后台期间推进了棘轮并写回
    /// Keychain;App 回前台必须丢弃内存缓存,下次访问按 Keychain 重载。
    public func invalidateCache() {
        cache.removeAll()
    }

    /// Deletes a session from both the in-memory cache and the Keychain.
    /// Used by the pairing wizard's mismatch-rejection path (M4 Task 8): a
    /// possible MITM means every trace of the pairing — including the DR
    /// session — must be scrubbed before the user retries from scratch.
    /// `try?` mirrors ``ContactsStore/remove(contactId:)``'s best-effort
    /// posture for this same orchestration: a Keychain delete failure here
    /// must not block clearing the in-memory cache or the caller's other
    /// wipe steps (contact, chat thread).
    public func remove(for contactId: String) {
        cache[contactId] = nil
        try? keychain.delete(key: "sess_\(contactId)")
    }

    /// Test-only escape hatch.
    public func _resetForTesting() {
        cache.removeAll()
    }
}

public enum SessionStoreError: Error, Equatable {
    case noSession(String)
}
