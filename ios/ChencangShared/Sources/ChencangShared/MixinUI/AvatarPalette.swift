import Foundation

/// FNV-1a 64(种子 UTF-8 字节,对色板大小取模):跨进程/跨启动确定,
/// 现在是跨端约定——Android 实现逐字节一致,同一指纹两端取到同一下标
/// (用例表 P 两端各钉一遍)。
/// 不能用 Swift `hashValue`/`Hasher`——两者按进程随机化 seed,同一字符串
/// 跨启动会算出不同值,头像颜色会在每次冷启动后跳变。
public func avatarPaletteIndex(seed: String, paletteSize: Int) -> Int {
    precondition(paletteSize > 0)
    var hash: UInt64 = 0xcbf2_9ce4_8422_2325
    for b in seed.utf8 {
        hash = (hash ^ UInt64(b)) &* 0x0000_0100_0000_01b3
    }
    return Int(hash % UInt64(paletteSize))
}
