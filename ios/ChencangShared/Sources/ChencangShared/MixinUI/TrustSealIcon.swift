import SwiftUI

/// spec §2:加密状态必须可见——锁、印、指纹 emoji 是仅有的三个仪式符号。
/// 本图标是「验证印」:verified = 已核对表情指纹的会话,unverified = 未核对(警示)。
/// 无显式 `.font(size:)`——尺寸由调用方外层容器的 ambient font/`.imageScale` 决定,
/// 天然继承 Dynamic Type,无需 moyuFont。
public struct TrustSealIcon: View {
    let verified: Bool

    public init(verified: Bool) {
        self.verified = verified
    }

    public var body: some View {
        Image(systemName: verified ? "checkmark.seal.fill" : "seal")
            .foregroundStyle(verified ? Moyu.Palette.accentPrimary : Moyu.Palette.statusWarn)
            .accessibilityLabel(verified ? L10n.verifyStatusVerified : L10n.verifyStatusUnverified)
    }
}
