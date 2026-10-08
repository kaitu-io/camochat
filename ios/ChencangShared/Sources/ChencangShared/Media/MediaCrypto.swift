import Foundation
import Chencang

/// 已压缩好的一条待发媒体(明文)。
public struct PreparedMedia: Equatable, Sendable {
    public let kind: MediaKind
    public let plaintext: Data
    public let durMs: Int
    public let width: Int
    public let height: Int

    public init(kind: MediaKind, plaintext: Data, durMs: Int, width: Int, height: Int) {
        self.kind = kind
        self.plaintext = plaintext
        self.durMs = durMs
        self.width = width
        self.height = height
    }
}

public struct SealedMediaBlob: Equatable, Sendable {
    public let secret: Data
    public let blob: Data
    public let blobId: String

    public init(secret: Data, blob: Data, blobId: String) {
        self.secret = secret
        self.blob = blob
        self.blobId = blobId
    }
}

/// 打日志/断点打印这个值时绝不能带出 `secret`(终审 M5)——`blobId` 本来就是公开信息
/// (进分享文本第一行的落地页链接),打出来没问题;`secret` 一旦落进日志就等于泄了密钥。
extension SealedMediaBlob: CustomStringConvertible, CustomDebugStringConvertible {
    public var description: String {
        "SealedMediaBlob(blobId: \(blobId), blob: \(blob.count) bytes, secret: <redacted \(secret.count) bytes>)"
    }

    public var debugDescription: String { description }
}

/// `.cca` 加解密抽象:生产走 core(uniffi),测试包一层计数。
public protocol MediaCrypto: Sendable {
    func seal(_ plaintext: Data, kind: MediaKind) throws -> SealedMediaBlob
    func open(_ blob: Data, secret: Data, kind: MediaKind) throws -> Data
}

public struct CoreMediaCrypto: MediaCrypto {
    public init() {}

    /// blob_secret 由 core 内部 CSPRNG 生成(spec §1.3 终审 F2),调用方不能自带密钥。
    public func seal(_ plaintext: Data, kind: MediaKind) throws -> SealedMediaBlob {
        let sealed = try encryptMediaBlob(plaintext: plaintext, kind: kind.rawValue)
        return SealedMediaBlob(secret: sealed.blobSecret, blob: sealed.blob, blobId: sealed.blobId)
    }

    /// kind 与帧内声明不一致、密文被篡改 → 抛错(UI「文件已损坏」)。
    public func open(_ blob: Data, secret: Data, kind: MediaKind) throws -> Data {
        try decryptMediaBlob(blob: blob, blobSecret: secret, expectedKind: kind.rawValue)
    }
}
