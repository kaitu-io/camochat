import SwiftUI

/// 2×4 网格切分;非 8 个(异常/旧数据)退化为单行,由调用方配合空态文案。
/// Pure function — iOS twin of Android's `sealGridRows` (`EmojiSealGrid.kt`).
public func sealGridRows(_ emojis: [String]) -> [[String]] {
    switch emojis.count {
    case 0: return []
    case 8: return [Array(emojis[0..<4]), Array(emojis[4..<8])]
    default: return [emojis]
    }
}

/// 安全码表情网格:8 个表情按 `sealGridRows` 渲染 2×4;非 8 个(legacy/异常数据)
/// 退化单行,空表时不渲染格子——展示与联系人页一致的重新配对提示文案。
public struct EmojiSealGridView: View {
    let emojis: [String]
    let cellSize: CGFloat

    public init(emojis: [String], cellSize: CGFloat = 40) {
        self.emojis = emojis
        self.cellSize = cellSize
    }

    public var body: some View {
        let rows = sealGridRows(emojis)
        if rows.isEmpty {
            Text(L10n.verifyNoCode)
                .font(moyuFont(Moyu.FontSize.callout))
                .foregroundStyle(Moyu.Palette.statusWarn)
        } else {
            VStack(spacing: Moyu.Space.m) {
                ForEach(rows.indices, id: \.self) { rowIdx in
                    HStack(spacing: Moyu.Space.s) {
                        ForEach(rows[rowIdx].indices, id: \.self) { colIdx in
                            Text(rows[rowIdx][colIdx])
                                .font(moyuFont(Moyu.FontSize.display))
                                .frame(width: cellSize, height: cellSize)
                                .background(
                                    Moyu.Palette.surfaceRaised,
                                    in: RoundedRectangle(cornerRadius: Moyu.Radius.card, style: .continuous)
                                )
                                .overlay(
                                    RoundedRectangle(cornerRadius: Moyu.Radius.card, style: .continuous)
                                        .strokeBorder(Moyu.Palette.borderHairline, lineWidth: 1)
                                )
                        }
                    }
                }
            }
        }
    }
}
