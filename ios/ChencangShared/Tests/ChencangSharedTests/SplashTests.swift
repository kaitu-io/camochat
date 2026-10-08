import CoreGraphics
import XCTest
@testable import ChencangShared
#if canImport(AppKit)
import AppKit
import SwiftUI
#endif

/// 开屏动画：时间轴（两个版本）、SVG 路径解析、播放版本闸门，以及系统启动页素材与代码/token 不漂移。
final class SplashTimelineTests: XCTestCase {
    private func assertFrame(_ f: SplashFrame, _ expected: [KeyPath<SplashFrame, Double>: Double],
                             file: StaticString = #filePath, line: UInt = #line) {
        for (key, value) in expected {
            XCTAssertEqual(f[keyPath: key], value, accuracy: 1e-9, "\(key)", file: file, line: line)
        }
    }

    func testFullAtZeroMatchesLaunchScreen() {
        let f = SplashFrame.at(0, variant: .full)
        assertFrame(f, [\.catY: 0, \.clipBottom: 100, \.decoyY: 34, \.decoyAlpha: 0, \.pawsAlpha: 0, \.pawsY: 8,
                        \.wordmarkAlpha: 0, \.wordmarkY: 6, \.hFillAlpha: 1, \.hGhostAlpha: 0, \.lidScaleY: 0,
                        \.overlayAlpha: 1, \.contentScale: 1])
        XCTAssertFalse(f.isDone)
    }

    func testFullAt550CatHiddenBehindDecoy() {
        assertFrame(SplashFrame.at(550, variant: .full),
                    [\.catY: 16, \.clipBottom: 80, \.decoyY: 0, \.decoyAlpha: 1, \.pawsAlpha: 0, \.wordmarkAlpha: 0])
    }

    func testFullAt1200PeekingAndCamouflageHalfway() {
        // h 隐身 ease(0.5) = 0.875
        assertFrame(SplashFrame.at(1200, variant: .full),
                    [\.catY: -4, \.pawsAlpha: 1, \.pawsY: 0, \.wordmarkAlpha: 1, \.wordmarkY: 0,
                     \.hFillAlpha: 0.125, \.hGhostAlpha: 0.875, \.lidScaleY: 0, \.overlayAlpha: 1])
    }

    func testFullAt1475MidBlink() {
        assertFrame(SplashFrame.at(1475, variant: .full),
                    [\.lidScaleY: 0.9, \.hFillAlpha: 0, \.hGhostAlpha: 1, \.overlayAlpha: 1])
    }

    func testFullAt2200Done() {
        let f = SplashFrame.at(2200, variant: .full)
        assertFrame(f, [\.overlayAlpha: 0, \.contentScale: 1.06, \.lidScaleY: 0])
        XCTAssertTrue(f.isDone)
        XCTAssertFalse(SplashFrame.at(2199, variant: .full).isDone)
    }

    func testShortTimeline() {
        assertFrame(SplashFrame.at(0, variant: .short), [\.catY: 0, \.clipBottom: 100, \.decoyAlpha: 0, \.overlayAlpha: 1])
        assertFrame(SplashFrame.at(380, variant: .short), [\.catY: 16, \.clipBottom: 80, \.decoyY: 0, \.decoyAlpha: 1])
        assertFrame(SplashFrame.at(680, variant: .short),
                    [\.catY: -4, \.pawsAlpha: 1, \.pawsY: 0, \.lidScaleY: 0.9, \.overlayAlpha: 1])
        let end = SplashFrame.at(1000, variant: .short)
        assertFrame(end, [\.overlayAlpha: 0, \.contentScale: 1.06])
        XCTAssertTrue(end.isDone)
    }

    func testShortHasNoWordmarkAtAnyTime() {
        for t in stride(from: 0.0, through: 1000, by: 10) {
            XCTAssertEqual(SplashFrame.at(t, variant: .short).wordmarkAlpha, 0)
        }
    }

    func testSkipJumpsToFadeStart() {
        XCTAssertEqual(SplashVariant.full.skipTo, 1900)
        XCTAssertEqual(SplashVariant.full.duration, 2200)
        XCTAssertEqual(SplashVariant.short.skipTo, 750)
        XCTAssertEqual(SplashVariant.short.duration, 1000)
        XCTAssertEqual(SplashFrame.at(SplashVariant.short.skipTo, variant: .short).overlayAlpha, 1)
    }

    func testGateFullOnceThenShort() throws {
        let suite = "SplashGateTests-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        XCTAssertEqual(SplashGate.variant(defaults: defaults), .full)
        SplashGate.didStart(.full, defaults: defaults)
        XCTAssertEqual(SplashGate.variant(defaults: defaults), .short)
        SplashGate.didStart(.short, defaults: defaults)
        XCTAssertEqual(SplashGate.variant(defaults: defaults), .short)
    }
}

final class SVGPathTests: XCTestCase {
    private func assertRect(_ r: CGRect, _ x: CGFloat, _ y: CGFloat, _ w: CGFloat, _ h: CGFloat,
                            file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertEqual(r.minX, x, accuracy: 1e-6, file: file, line: line)
        XCTAssertEqual(r.minY, y, accuracy: 1e-6, file: file, line: line)
        XCTAssertEqual(r.width, w, accuracy: 1e-6, file: file, line: line)
        XCTAssertEqual(r.height, h, accuracy: 1e-6, file: file, line: line)
    }

    func testBodyWithArcsHasExactBounds() throws {
        let body = try SVGPath.parse(SplashArt.bodyWithEyeHoles)
        assertRect(body.boundingBoxOfPath, 12, 28, 76, 56)
    }

    func testArcFollowsTheCircle() throws {
        // 右上角圆弧：圆心 (64, 52)，半径 24。45° 方向圆内一点在、圆外一点不在。
        let body = try SVGPath.parse(SplashArt.bodyWithEyeHoles)
        let d = 24 / CGFloat(2).squareRoot()
        XCTAssertTrue(body.contains(CGPoint(x: 64 + d - 0.3, y: 52 - d + 0.3), using: .evenOdd))
        XCTAssertFalse(body.contains(CGPoint(x: 64 + d + 0.3, y: 52 - d - 0.3), using: .evenOdd))
    }

    func testEyesAreHolesUnderEvenOdd() throws {
        let body = try SVGPath.parse(SplashArt.bodyWithEyeHoles)
        XCTAssertTrue(body.contains(CGPoint(x: 50, y: 40), using: .evenOdd))
        XCTAssertFalse(body.contains(CGPoint(x: 36, y: 55), using: .evenOdd))
        XCTAssertFalse(body.contains(CGPoint(x: 64, y: 55), using: .evenOdd))
    }

    func testImplicitLinetoAfterMoveto() throws {
        let p = try SVGPath.parse("M0 0 10 0 10 10Z")
        assertRect(p.boundingBoxOfPath, 0, 0, 10, 10)
    }

    func testHVAndCompactSeparators() throws {
        let p = try SVGPath.parse("M1-2H5V-6ZM0,0L-1e1 3")
        assertRect(p.boundingBoxOfPath, -10, -6, 15, 9)
    }

    func testRejectsUnsupportedAndTruncated() {
        XCTAssertThrowsError(try SVGPath.parse("M0 0 S1 1 2 2")) { error in
            XCTAssertEqual(error as? SVGPath.ParseError, .unsupportedCommand("S"))
        }
        XCTAssertThrowsError(try SVGPath.parse("M0")) { error in
            XCTAssertEqual(error as? SVGPath.ParseError, .missingNumber(command: "M"))
        }
        XCTAssertThrowsError(try SVGPath.parse("0 0"))
    }

    func testAllSplashPathsParseAndWordmarkSpansItsWidth() throws {
        let paths = try SplashPaths.load()
        let rest = paths.wordmarkRest.boundingBoxOfPath
        let h = paths.wordmarkH.boundingBoxOfPath
        let all = rest.union(h)
        XCTAssertGreaterThan(all.minX, 0)
        XCTAssertLessThan(all.maxX, SplashArt.wordmarkWidth)
        XCTAssertEqual(h.minY, -70, accuracy: 1e-6)          // h 的升部
        XCTAssertGreaterThan(SplashArt.wordmarkWidth * SplashArt.wordmarkScale, 119.999)
        // h 夹在 c-a-m-o 与 c-a-t 之间
        XCTAssertGreaterThan(h.minX, 280)
        XCTAssertLessThan(h.maxX, 340)
    }
}

/// 系统启动页的素材（App 的 Assets.xcassets）不在本包里，按源码相对路径读进来核对：
/// 底色 = splashBackground token，猫 = splashMark token、同样的路径、边长 = gridPoints。
final class SplashLaunchAssetTests: XCTestCase {
    private var assets: URL {
        URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("ChencangCompanion/Assets.xcassets")
    }

    #if canImport(AppKit)
    private func rgb(_ color: SwiftUI.Color) throws -> (Int, Int, Int) {
        let c = try XCTUnwrap(NSColor(color).usingColorSpace(.sRGB))
        return (Int((c.redComponent * 255).rounded()), Int((c.greenComponent * 255).rounded()),
                Int((c.blueComponent * 255).rounded()))
    }

    func testLaunchBackgroundColorMatchesToken() throws {
        let data = try Data(contentsOf: assets.appendingPathComponent("SplashBackground.colorset/Contents.json"))
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        let colors = try XCTUnwrap(json["colors"] as? [[String: Any]])
        XCTAssertEqual(colors.count, 1, "启动页底色不随暗色模式变化，只能有一个 universal 值")
        let comps = try XCTUnwrap((colors[0]["color"] as? [String: Any])?["components"] as? [String: String])
        func hex(_ k: String) throws -> Int { try XCTUnwrap(Int(XCTUnwrap(comps[k]).dropFirst(2), radix: 16)) }
        let token = try rgb(Moyu.Palette.splashBackground)
        XCTAssertEqual(try hex("red"), token.0)
        XCTAssertEqual(try hex("green"), token.1)
        XCTAssertEqual(try hex("blue"), token.2)
    }

    func testLaunchCatMatchesOverlay() throws {
        let svg = try String(contentsOf: assets.appendingPathComponent("SplashCat.imageset/SplashCat.svg"), encoding: .utf8)
        let size = Int(SplashArt.gridPoints)
        XCTAssertTrue(svg.contains("width=\"\(size)\" height=\"\(size)\" viewBox=\"0 0 100 100\""))
        let mark = try rgb(Moyu.Palette.splashMark)
        let fill = String(format: "#%02X%02X%02X", mark.0, mark.1, mark.2)
        XCTAssertTrue(svg.contains("fill=\"\(fill)\" stroke=\"\(fill)\""))
        for d in [SplashArt.earLeft, SplashArt.earRight, SplashArt.tail, SplashArt.bodyWithEyeHoles, SplashArt.pupils] {
            XCTAssertTrue(svg.contains("d=\"\(d)\""), d)
        }
    }
    #endif
}

final class SplashClockTests: XCTestCase {
    func testStallDoesNotSkipTheAnimation() {
        var clock = SplashClock(variant: .full)
        clock.advance(byMs: 1500)                     // 主线程卡了 1.5 秒
        XCTAssertEqual(clock.t, SplashClock.maxStepMs, accuracy: 1e-9)
        XCTAssertFalse(clock.isDone)
    }

    func testRunsToEndAndClamps() {
        var clock = SplashClock(variant: .short)
        for _ in 0..<200 { clock.advance(byMs: 16) }
        XCTAssertEqual(clock.t, 1000)
        XCTAssertTrue(clock.isDone)
        XCTAssertEqual(clock.frame.overlayAlpha, 0, accuracy: 1e-9)
    }

    func testSkipJumpsOnceAndNeverRewinds() {
        var clock = SplashClock(variant: .full)
        clock.advance(byMs: 16)
        clock.skipToFade()
        XCTAssertEqual(clock.t, 1900)
        clock.advance(byMs: 20)
        clock.skipToFade()
        XCTAssertEqual(clock.t, 1920)
        clock.advance(byMs: -5)
        XCTAssertEqual(clock.t, 1920)
    }
}
