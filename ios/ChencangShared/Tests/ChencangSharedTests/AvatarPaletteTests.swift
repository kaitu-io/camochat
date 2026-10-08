import XCTest
@testable import ChencangShared

/// avatarPaletteIndex 用 FNV-1a 64 做确定性哈希(不能用 Swift `hashValue`——
/// 进程随机化,同一 seed 跨启动会变)。这里锁住算法本身:同 seed 稳定、
/// 两个已知种子钉死具体值(回归锁,算法一旦跑偏测试立刻炸)、空串合法、
/// 值域受 paletteSize 约束。
final class AvatarPaletteTests: XCTestCase {
    func testSameSeedIsStableAcrossCalls() {
        let a = avatarPaletteIndex(seed: "alice", paletteSize: 8)
        let b = avatarPaletteIndex(seed: "alice", paletteSize: 8)
        XCTAssertEqual(a, b)
    }

    func testKnownSeedPinnedValue_alice() {
        // FNV-1a 64("alice") = 5803779529149266183 % 8 = 7 — 算法回归锁。
        XCTAssertEqual(avatarPaletteIndex(seed: "alice", paletteSize: 8), 7)
    }

    func testKnownSeedPinnedValue_bob() {
        // FNV-1a 64("bob") = 21748447695211092 % 8 = 4 — 算法回归锁。
        XCTAssertEqual(avatarPaletteIndex(seed: "bob", paletteSize: 8), 4)
    }

    func testEmptySeedIsLegal() {
        // 空 seed 不能 crash;FNV-1a 64("") = offset basis % 8 = 5。
        XCTAssertEqual(avatarPaletteIndex(seed: "", paletteSize: 8), 5)
    }

    func testResultAlwaysWithinPaletteRange() {
        var generator = SystemRandomNumberGenerator()
        for _ in 0..<100 {
            let length = Int.random(in: 0...24, using: &generator)
            let seed = String((0..<length).map { _ in
                "abcdefghijklmnopqrstuvwxyz0123456789陈仓密信".randomElement(using: &generator)!
            })
            let index = avatarPaletteIndex(seed: seed, paletteSize: 8)
            XCTAssertTrue((0..<8).contains(index), "index \(index) out of range for seed \(seed)")
        }
    }
}

extension AvatarPaletteTests {
    func testPaletteIndexTableP() {
        let table: [(String, Int)] = [
            ("a", 4), ("me", 7), ("", 5),
            ("0123456789abcdef0123456789abcdef", 5),
            ("3f9c1e7a5b2d4f608192a3b4c5d6e7f8", 1),
        ]
        for (seed, want) in table {
            XCTAssertEqual(avatarPaletteIndex(seed: seed, paletteSize: 8), want, "seed \(seed)")
        }
    }
}
