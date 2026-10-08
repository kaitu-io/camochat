import Foundation

/// 接受方生成的回应暗号记录(公开内容),按对端指纹一条,用于重发与重复粘贴识别。
public struct PairingResponseRecord: Codable, Equatable, Sendable {
    public var fingerprintHex: String
    public var responseWire: String
    /// 邀请负载字节的 SHA-256 小写十六进制(见 PairingCoordinator)。
    public var inviteDigest: String
    public var createdAtMillis: Int64
    /// nil = 回暗号还没发出去。
    public var lastSharedAtMillis: Int64?

    public init(fingerprintHex: String, responseWire: String, inviteDigest: String,
                createdAtMillis: Int64, lastSharedAtMillis: Int64?) {
        self.fingerprintHex = fingerprintHex
        self.responseWire = responseWire
        self.inviteDigest = inviteDigest
        self.createdAtMillis = createdAtMillis
        self.lastSharedAtMillis = lastSharedAtMillis
    }
}

/// 回应记录列表,JSON 数组存在 App Group 单个键里。容错与写入语义同 `PendingInviteStore`:
/// 每次写之前先从磁盘重读再改再写,`reload()` 供回前台调用。
@MainActor
public final class PairingResponseStore: ObservableObject {
    public static let shared = PairingResponseStore()

    @Published public private(set) var records: [PairingResponseRecord] = []

    private let defaults: AppGroupDefaults
    private let key = "cc.pairing_responses.v1"

    public init(defaults: AppGroupDefaults? = nil) {
        self.defaults = defaults ?? SharedAppGroupDefaults()
        records = Self.read(self.defaults, key: key) ?? []
    }

    /// 从磁盘重读缓存。键不存在 = 空表;整段解不开 = 保持缓存。只在值变化时赋给 `@Published`。
    public func reload() {
        if let fresh = Self.read(defaults, key: key), fresh != records { records = fresh }
    }

    /// 同指纹覆盖。
    public func put(_ record: PairingResponseRecord) throws {
        reload()
        try persist(records.filter { $0.fingerprintHex != record.fingerprintHex } + [record])
    }

    public func record(for fingerprintHex: String) -> PairingResponseRecord? {
        records.first { $0.fingerprintHex == fingerprintHex }
    }

    public func find(inviteDigest: String) -> PairingResponseRecord? {
        records.first { $0.inviteDigest == inviteDigest }
    }

    /// 没有该指纹的记录时什么都不做。
    public func markShared(fingerprintHex: String, at: Int64) throws {
        reload()
        guard records.contains(where: { $0.fingerprintHex == fingerprintHex }) else { return }
        try persist(records.map {
            var r = $0
            if r.fingerprintHex == fingerprintHex { r.lastSharedAtMillis = at }
            return r
        })
    }

    /// 只删这条可重发的回应文本;联系人上的邀请摘要不归这里管。
    public func remove(fingerprintHex: String) throws {
        reload()
        try persist(records.filter { $0.fingerprintHex != fingerprintHex })
    }

    public func clear() throws {
        reload()
        try persist([])
    }

    /// nil = 有数据但整段解不开;`[]` = 键不存在。
    private static func read(_ defaults: AppGroupDefaults, key: String) -> [PairingResponseRecord]? {
        guard let d = defaults.data(forKey: key) else { return [] }
        return (try? JSONDecoder().decode(LossyArray<PairingResponseRecord>.self, from: d))?.elements
    }

    private func persist(_ next: [PairingResponseRecord]) throws {
        defaults.set(try JSONEncoder().encode(next), forKey: key)
        if next != records { records = next }
    }
}
