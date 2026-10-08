import Foundation

/// 签名配置单(spec 2026-10-07 §4)。只由 `SignedConfigCodec.decode` 构造。
/// iOS 忽略 `android` 段与任何未知键。
public struct AppConfig: Codable, Equatable, Sendable {
    public let schema: Int
    public let seq: Int64
    public let sources: [String]
    public let relays: [String]
    public let shareSite: String

    public init(schema: Int, seq: Int64, sources: [String], relays: [String], shareSite: String) {
        self.schema = schema
        self.seq = seq
        self.sources = sources
        self.relays = relays
        self.shareSite = shareSite
    }
}
