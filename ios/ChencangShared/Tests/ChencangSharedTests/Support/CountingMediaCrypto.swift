import Foundation
@testable import ChencangShared

/// 包一层真 CoreMediaCrypto,统计 seal/open 调用次数(重试不得重加密)。
final class CountingMediaCrypto: MediaCrypto, @unchecked Sendable {
    private let inner = CoreMediaCrypto()
    private let lock = NSLock()
    private var _seals = 0
    private var _opens = 0
    /// 第几次 seal 调用(从 1 数)要抛错;用来模拟「加密阶段失败、还没分享」。
    private var failingSealCalls: Set<Int> = []

    var sealCount: Int { lock.withLock { _seals } }
    var openCount: Int { lock.withLock { _opens } }

    func failSeal(onCall call: Int) { lock.withLock { _ = failingSealCalls.insert(call) } }

    func seal(_ plaintext: Data, kind: MediaKind) throws -> SealedMediaBlob {
        let shouldFail: Bool = lock.withLock {
            _seals += 1
            return failingSealCalls.remove(_seals) != nil
        }
        if shouldFail { throw CocoaError(.fileWriteUnknown) }
        return try inner.seal(plaintext, kind: kind)
    }

    func open(_ blob: Data, secret: Data, kind: MediaKind) throws -> Data {
        lock.withLock { _opens += 1 }
        return try inner.open(blob, secret: secret, kind: kind)
    }
}
