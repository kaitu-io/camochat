import SwiftUI

/// 「气泡猫」品牌标（brand/camochat-glyph.svg），轮廓与开屏动画同一份 `SplashArt`。
/// 眼睛是奇偶填充的洞，透出底色；整只猫只用一种颜色。纯装饰，读屏跳过。
/// 尺寸由外部 `.frame` 决定：猫的实际轮廓等比放进宽高中较小那一边的正方形，居中。
public struct BrandCat: View {
    let color: Color

    public init(color: Color) {
        self.color = color
    }

    public var body: some View {
        Canvas { context, size in
            guard let paths = BrandCat.paths else { return }
            let side = min(size.width, size.height)
            let k = side / BrandCat.gridBounds.height
            let w = BrandCat.gridBounds.width * k
            context.translateBy(x: (size.width - w) / 2 - BrandCat.gridBounds.minX * k,
                                y: (size.height - side) / 2 - BrandCat.gridBounds.minY * k)
            context.scaleBy(x: k, y: k)
            BrandCat.drawInGrid(&context, paths: paths, shading: .color(color))
        }
        .accessibilityHidden(true)
    }

    /// 猫在 100 格网格里实际占的范围（含耳朵、尾巴的描边）。
    public static let gridBounds = CGRect(x: 6, y: 9.5, width: 82, height: 87)

    /// 分享卡片顶栏里的猫边长 / 字号：大写字高约 0.7 em，猫取它的 1.6 倍。
    public static let markToFont: CGFloat = 0.7 * 1.6

    private static let paths = try? SplashPaths.load()

    /// 在 100 格网格坐标里画整只猫（不含眨眼的眼皮）。
    public static func drawInGrid(_ context: inout GraphicsContext, paths: SplashPaths, shading: GraphicsContext.Shading) {
        for ear in [paths.earLeft, paths.earRight] {
            context.fill(Path(ear), with: shading)
            context.stroke(Path(ear), with: shading, style: StrokeStyle(lineWidth: SplashArt.earStrokeWidth, lineJoin: .round))
        }
        context.fill(Path(paths.tail), with: shading)
        context.stroke(Path(paths.tail), with: shading, style: StrokeStyle(lineWidth: SplashArt.tailStrokeWidth, lineJoin: .round))
        context.fill(Path(paths.bodyWithEyeHoles), with: shading, style: FillStyle(eoFill: true))
        context.fill(Path(paths.pupils), with: shading)
    }
}
