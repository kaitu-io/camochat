import Foundation

/// On-disk record for one half-finished in-band pairing (an invite I sent out).
/// The inviter (A) sends a bundle, then must finish when the invitee replies; the
/// app process may be killed in between, so A persists this small record (see
/// ``PendingInviteStore``) and reloads it to resume.
///
/// Holds NO secret: only the *public* pairing nonce (already inside the
/// transmitted bundle), a local id, and a timestamp. Mirrors chencang-android's
/// `PendingPairingRecord`.
public struct PendingPairingRecord: Codable, Equatable, Sendable {
    public let pairingId: String
    /// PUBLIC pairing nonce, base64 (NO secrets).
    public let pairingNonceB64: String
    public let createdAtMillis: Int64
    /// 邀请暗号全文(公开内容)。从旧单槽迁来的记录为空串:没有文本可再发。
    public var inviteWire: String
    /// 用户给这份邀请写的备注(只存本机)。
    public var note: String?
    /// 最近一次分享/复制的时间;nil = 从未分享。
    public var lastSharedAtMillis: Int64?
    /// 预留给「作废密钥材料」出口;本轮恒为 nil。
    public var keyHandle: String?
    /// 第一次弹出分享面板的时间;非 nil 的邀请可能已经发出去了(有的面板不回报完成),不再丢弃、也不再复用。
    public var sheetPresentedAtMillis: Int64?

    public init(
        pairingId: String,
        pairingNonceB64: String,
        createdAtMillis: Int64,
        inviteWire: String = "",
        note: String? = nil,
        lastSharedAtMillis: Int64? = nil,
        keyHandle: String? = nil,
        sheetPresentedAtMillis: Int64? = nil
    ) {
        self.pairingId = pairingId
        self.pairingNonceB64 = pairingNonceB64
        self.createdAtMillis = createdAtMillis
        self.inviteWire = inviteWire
        self.note = note
        self.lastSharedAtMillis = lastSharedAtMillis
        self.keyHandle = keyHandle
        self.sheetPresentedAtMillis = sheetPresentedAtMillis
    }

    // 旧单槽 JSON 没有新字段,缺失时取默认值。
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        pairingId = try c.decode(String.self, forKey: .pairingId)
        pairingNonceB64 = try c.decode(String.self, forKey: .pairingNonceB64)
        createdAtMillis = try c.decode(Int64.self, forKey: .createdAtMillis)
        inviteWire = try c.decodeIfPresent(String.self, forKey: .inviteWire) ?? ""
        note = try c.decodeIfPresent(String.self, forKey: .note)
        lastSharedAtMillis = try c.decodeIfPresent(Int64.self, forKey: .lastSharedAtMillis)
        keyHandle = try c.decodeIfPresent(String.self, forKey: .keyHandle)
        sheetPresentedAtMillis = try c.decodeIfPresent(Int64.self, forKey: .sheetPresentedAtMillis)
    }
}
