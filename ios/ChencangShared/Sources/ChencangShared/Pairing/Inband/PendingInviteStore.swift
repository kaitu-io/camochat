import Foundation

/// 「配对中」的邀请列表(发起方)。以 JSON 数组存在 App Group 的单个键里,只含公开内容。
/// 解码逐元素容错;构造时整段解不开当空表。写入失败抛出,不吞。
///
/// 每次写之前先从磁盘重读再改再写(同 `ContactsStore.reload()`):进程可能在重启后首次解锁前被拉起,
/// 那时读不到偏好、缓存是空表;若写时仍整表覆盖,会把磁盘上的记录全抹掉。重读时「键不存在」是空表,
/// 「整段解不开」则保持缓存不动,不当空表。
@MainActor
public final class PendingInviteStore: ObservableObject {
    public static let shared = PendingInviteStore()

    @Published public private(set) var records: [PendingPairingRecord] = []

    private let defaults: AppGroupDefaults
    private let key = "cc.pending_pairings.v2"
    private let legacyKey = "cc.pending_pairing.v1"

    public init(defaults: AppGroupDefaults? = nil) {
        self.defaults = defaults ?? SharedAppGroupDefaults()
        records = Self.read(self.defaults, key: key) ?? []
        migrateLegacySlot()
    }

    /// 从磁盘重读缓存(回前台时调用)。键不存在 = 空表;整段解不开 = 保持缓存。只在值变化时赋给 `@Published`。
    public func reload() {
        if let fresh = Self.read(defaults, key: key), fresh != records { records = fresh }
    }

    public func append(_ record: PendingPairingRecord) throws {
        reload()
        try persist(records + [record])
    }

    public func update(id: String, _ mutate: (inout PendingPairingRecord) -> Void) throws {
        reload()
        var next = records
        guard let i = next.firstIndex(where: { $0.pairingId == id }) else { return }
        mutate(&next[i])
        try persist(next)
    }

    public func remove(id: String) throws {
        reload()
        try persist(records.filter { $0.pairingId != id })
    }

    public func clear() throws {
        reload()
        try persist([])
    }

    public func record(id: String) -> PendingPairingRecord? {
        records.first { $0.pairingId == id }
    }

    private func persist(_ next: [PendingPairingRecord]) throws {
        defaults.set(try JSONEncoder().encode(next), forKey: key)
        if next != records { records = next }
    }

    /// 只读快照:不构造 store、不迁移、不写盘,任意线程 / 进程(含 Action Extension)可调。
    /// 整段解不开按空表(没有缓存可保持)。
    public nonisolated static func readRecords(defaults: AppGroupDefaults? = nil) -> [PendingPairingRecord] {
        read(defaults ?? SharedAppGroupDefaults(), key: "cc.pending_pairings.v2") ?? []
    }

    /// nil = 有数据但整段解不开;`[]` = 键不存在。
    private nonisolated static func read(_ defaults: AppGroupDefaults, key: String) -> [PendingPairingRecord]? {
        guard let d = defaults.data(forKey: key) else { return [] }
        return (try? JSONDecoder().decode(LossyArray<PendingPairingRecord>.self, from: d))?.elements
    }

    /// 旧单槽 → 列表。幂等:列表里已有同 pairingId 就不再追加;写入失败则保留旧键,下次再迁。
    private func migrateLegacySlot() {
        guard let d = defaults.data(forKey: legacyKey) else { return }
        if let old = try? JSONDecoder().decode(PendingPairingRecord.self, from: d),
           !records.contains(where: { $0.pairingId == old.pairingId }) {
            var migrated = old
            // 旧版本里这份邀请必然已出示过,且没有暗号文本可再发。
            migrated.inviteWire = ""
            migrated.lastSharedAtMillis = old.createdAtMillis
            guard (try? persist(records + [migrated])) != nil else { return }
        }
        defaults.set(nil, forKey: legacyKey)
    }
}
