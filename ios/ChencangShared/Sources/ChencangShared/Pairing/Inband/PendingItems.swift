import Foundation

/// 「配对中」一行在等什么(spec 2026-10-01-three-tab-shell §3.5.3)。
public enum PendingKind: Equatable, Sendable {
    /// 接受方:我的配对码还没发出去。
    case responseUnsent
    /// 发起方:已发出(分享过,或分享面板至少弹出过)我的配对码,等对方发回配对码。两者都没有的邀请不出现在「配对中」。
    case inviteAwaiting
}

public struct PendingItem: Equatable, Identifiable, Sendable {
    /// 邀请 = pairingId;回应 = 对端指纹。
    public let id: String
    public let kind: PendingKind
    public let note: String?
    public let createdAtMillis: Int64
    /// 创建超过 30 天:显示「可能已失效」,不计角标。
    public let isStale: Bool
    /// 有存好的配对码文本可再发(从旧单槽迁来的邀请为 false)。
    public let canResend: Bool

    /// 球在我手里:回应未发出且不是 stale。角标就数这个。
    public var needsMyAction: Bool { !isStale && kind == .responseUnsent }

    public init(id: String, kind: PendingKind, note: String?, createdAtMillis: Int64, isStale: Bool, canResend: Bool) {
        self.id = id
        self.kind = kind
        self.note = note
        self.createdAtMillis = createdAtMillis
        self.isStale = isStale
        self.canResend = canResend
    }
}

/// 「可能已失效」阈值:创建超过 30 天(恰好 30 天不算)。
private let staleAfterMillis: Int64 = 2_592_000_000

/// 推导「配对中」条目。邀请只在「发出过」(分享过或分享面板弹出过,与 awaitingInvites 同一判定,列表 ⊇ 计数)时出现,过期的照列并标 stale;回应只在「从未分享且联系人仍在」时出现。
/// 排序:需要我动手的在前,其余在后;各自按创建时间倒序,时间相同按 id 升序。
public func pendingItems(
    invites: [PendingPairingRecord],
    responses: [PairingResponseRecord],
    contactIds: Set<String>,
    nowMillis: Int64
) -> [PendingItem] {
    func stale(_ created: Int64) -> Bool { nowMillis - created > staleAfterMillis }

    let responseItems = responses
        .filter { $0.lastSharedAtMillis == nil && contactIds.contains($0.fingerprintHex) }
        .map {
            PendingItem(id: $0.fingerprintHex, kind: .responseUnsent, note: nil,
                        createdAtMillis: $0.createdAtMillis, isStale: stale($0.createdAtMillis),
                        canResend: !$0.responseWire.isEmpty)
        }
    let inviteItems = invites.filter { $0.lastSharedAtMillis != nil || $0.sheetPresentedAtMillis != nil }.map { r in
        PendingItem(
            id: r.pairingId, kind: .inviteAwaiting,
            note: r.note, createdAtMillis: r.createdAtMillis,
            isStale: stale(r.createdAtMillis),
            // 没有配对码文本(旧单槽迁来)的邀请无可再发,只能等对方。
            canResend: !r.inviteWire.isEmpty)
    }
    return (responseItems + inviteItems).sorted {
        if $0.needsMyAction != $1.needsMyAction { return $0.needsMyAction }
        if $0.createdAtMillis != $1.createdAtMillis { return $0.createdAtMillis > $1.createdAtMillis }
        // 排序不保证稳定:时间相同时按 id 定序,界面顺序才可复现。
        return $0.id < $1.id
    }
}

/// 联系人 tab 角标:需要我动手的条数。
public func pendingBadgeCount(_ items: [PendingItem]) -> Int {
    items.filter(\.needsMyAction).count
}

/// 回应还没发出去的联系人指纹:只出现在「配对中」,联系人列表里不重复。
public func unsentResponseFingerprints(responses: [PairingResponseRecord], contactIds: Set<String>) -> Set<String> {
    Set(responses.filter { $0.lastSharedAtMillis == nil && contactIds.contains($0.fingerprintHex) }
        .map(\.fingerprintHex))
}
