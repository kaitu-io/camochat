import CoreGraphics
import XCTest
@testable import ChencangShared

/// 品牌猫的取景框要正好包住整只猫（含描边），否则小尺寸下会被裁掉或偏心。
final class BrandCatTests: XCTestCase {
    func testGridBoundsTightlyContainTheCat() throws {
        let paths = try SplashPaths.load()
        func stroked(_ p: CGPath, _ w: CGFloat) -> CGRect {
            p.copy(strokingWithWidth: w, lineCap: .butt, lineJoin: .round, miterLimit: 10).boundingBoxOfPath
        }
        let all = [
            stroked(paths.earLeft, SplashArt.earStrokeWidth),
            stroked(paths.earRight, SplashArt.earStrokeWidth),
            stroked(paths.tail, SplashArt.tailStrokeWidth),
            paths.bodyWithEyeHoles.boundingBoxOfPath,
            paths.pupils.boundingBoxOfPath,
        ].reduce(CGRect.null) { $0.union($1) }
        let b = BrandCat.gridBounds
        for (got, want) in [(all.minX, b.minX), (all.minY, b.minY), (all.maxX, b.maxX), (all.maxY, b.maxY)] {
            XCTAssertEqual(got, want, accuracy: 1.0)
        }
    }
}
