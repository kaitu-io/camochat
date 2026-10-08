import SwiftUI
import ChencangShared

/// ➕ 面板(R8):相册、拍摄两项。
struct PlusPanel: View {
    let onAlbum: () -> Void
    let onCamera: () -> Void

    var body: some View {
        HStack(spacing: Moyu.Space.xl) {
            tile(title: L10n.composerPanelAlbum, icon: "photo.on.rectangle", action: onAlbum)
            tile(title: L10n.composerPanelCamera, icon: "camera", action: onCamera)
            Spacer()
        }
        .padding(Moyu.Space.l)
        .background(Moyu.Palette.surfaceBase)
    }

    private func tile(title: String, icon: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: Moyu.Space.xs) {
                Image(systemName: icon)
                    .font(moyuFont(Moyu.FontSize.display))
                    .foregroundStyle(Moyu.Palette.textPrimary)
                    // 借媒体缩略图的最小边长(mediaThumbMin)当方形图标底的尺寸,
                    // 不为「➕ 面板 tile」另开一个 token。
                    .frame(width: Moyu.Size.mediaThumbMin, height: Moyu.Size.mediaThumbMin)
                    .background(Moyu.Palette.surfaceRaised,
                                in: RoundedRectangle(cornerRadius: Moyu.Radius.card, style: .continuous))
                Text(title)
                    .font(moyuFont(Moyu.FontSize.caption))
                    .foregroundStyle(Moyu.Palette.textSecondary)
            }
        }
        .buttonStyle(.plain)
    }
}
