import SwiftUI

/// 印章头像:`AvatarSpec`(一个字 + 8 色色板下标)。取色见 `contactAvatar` / `myAvatar`。1:1 会话线程内部
/// 不展示头像(§4.2——头像信息量为零),本组件用于会话列表 / 联系人等场景。
public struct MoyuAvatarView: View {
    let spec: AvatarSpec
    let size: CGFloat

    public init(spec: AvatarSpec, size: CGFloat) {
        self.spec = spec
        self.size = size
    }

    private static let palette: [Color] = [
        Moyu.Palette.avatar1, Moyu.Palette.avatar2, Moyu.Palette.avatar3, Moyu.Palette.avatar4,
        Moyu.Palette.avatar5, Moyu.Palette.avatar6, Moyu.Palette.avatar7, Moyu.Palette.avatar8,
    ]

    public var body: some View {
        Circle()
            .fill(Self.palette[spec.paletteIndex])
            .frame(width: size, height: size)
            .overlay(
                Text(spec.glyph)
                    .font(moyuFont(size * 0.42, weight: .medium))
                    .foregroundStyle(Moyu.Palette.avatarGlyph)
            )
    }
}
