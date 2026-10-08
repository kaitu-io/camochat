import SwiftUI

/// spec §3.2:Dynamic Type 全面支持。Moyu.FontSize 是设计基准值,
/// 经 UIFontMetrics 随系统字体档缩放;Mac(swift test)无 UIKit 回退原值。
///
/// 全 plan 字号唯一入口——任何 `.font(.system(size:))` 一律改走本 helper,
/// 否则固定字号不随系统「字体大小」设置缩放,违反 spec §3.2。
public func moyuFont(_ size: CGFloat, weight: Font.Weight = .regular, design: Font.Design = .default) -> Font {
    #if canImport(UIKit)
    return .system(size: UIFontMetrics.default.scaledValue(for: size), weight: weight, design: design)
    #else
    return .system(size: size, weight: weight, design: design)
    #endif
}
