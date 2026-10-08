import SwiftUI
import UIKit
import ChencangShared

/// 开屏动画「躲猫猫」：冷启动时盖在 App 上面的一层，App 在下面照常加载。
/// 首帧与系统启动页（`UILaunchScreen`：SplashBackground 底色 + SplashCat 图）完全一致——
/// 猫网格边长 `SplashArt.gridPoints` 点、网格 (50, 50) 在屏幕正中，所以看不出切换。
/// 时间轴见 `SplashFrame`，时钟见 `SplashClock`（按帧推进，启动卡顿时停在当前帧而不是跳到结尾）。
/// 点一下跳到淡出；「减弱动态效果」时直接淡出；播完调用 `onFinish` 由上层拿掉。
struct SplashOverlay: View {
    /// 由链接唤起（配对 / 会话链接）时置 true：直接淡出，不耽误用户。
    let skipRequested: Bool
    let onFinish: () -> Void

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @StateObject private var driver: SplashDriver
    @State private var paths = try? SplashPaths.load()

    init(variant: SplashVariant, skipRequested: Bool, onFinish: @escaping () -> Void) {
        self.skipRequested = skipRequested
        self.onFinish = onFinish
        _driver = StateObject(wrappedValue: SplashDriver(variant: variant))
    }

    var body: some View {
        let frame = driver.clock.frame
        Canvas { context, size in
            draw(frame, in: &context, size: size)
        }
        .opacity(frame.overlayAlpha)
        .ignoresSafeArea()
        .contentShape(Rectangle())
        .onTapGesture { driver.skipToFade() }
        .accessibilityHidden(true)
        .onAppear {
            guard paths != nil else { onFinish(); return }
            SplashGate.didStart(driver.clock.variant)
            if reduceMotion || skipRequested { driver.skipToFade() }
            driver.start(onFinish: onFinish)
        }
        .onDisappear { driver.stop() }
        .onChange(of: skipRequested) { if $0 { driver.skipToFade() } }
    }

    private func draw(_ frame: SplashFrame, in context: inout GraphicsContext, size: CGSize) {
        context.fill(Path(CGRect(origin: .zero, size: size)), with: .color(Moyu.Palette.splashBackground))
        guard let paths else { return }
        let mark = GraphicsContext.Shading.color(Moyu.Palette.splashMark)

        // 网格 → 屏幕：网格 (50, 50) 落在正中，100 格 = gridPoints 点；淡出时绕 (50, 60) 放大。
        let unit = SplashArt.gridPoints / 100
        let scale = unit * frame.contentScale
        let pivot = SplashArt.scalePivot
        let pivotOnScreen = CGPoint(x: size.width / 2 + (pivot.x - SplashArt.gridCenter.x) * unit,
                                    y: size.height / 2 + (pivot.y - SplashArt.gridCenter.y) * unit)
        context.translateBy(x: pivotOnScreen.x, y: pivotOnScreen.y)
        context.scaleBy(x: scale, y: scale)
        context.translateBy(x: -pivot.x, y: -pivot.y)

        // 猫：只画裁切下沿以上的部分（气泡升起后，猫不能从气泡下半截露出来）。
        context.drawLayer { cat in
            cat.clip(to: Path(CGRect(x: -20, y: -40, width: 140, height: frame.clipBottom + 40)))
            cat.translateBy(x: 0, y: frame.catY)
            for ear in [paths.earLeft, paths.earRight] {
                cat.fill(Path(ear), with: mark)
                cat.stroke(Path(ear), with: mark, style: StrokeStyle(lineWidth: SplashArt.earStrokeWidth, lineJoin: .round))
            }
            cat.fill(Path(paths.tail), with: mark)
            cat.stroke(Path(paths.tail), with: mark, style: StrokeStyle(lineWidth: SplashArt.tailStrokeWidth, lineJoin: .round))
            cat.fill(Path(paths.bodyWithEyeHoles), with: mark, style: FillStyle(eoFill: true))
            cat.fill(Path(paths.pupils), with: mark)
            if frame.lidScaleY > 0 {
                let lid = CGAffineTransform(translationX: 0, y: SplashArt.lidPivotY)
                    .scaledBy(x: 1, y: frame.lidScaleY)
                    .translatedBy(x: 0, y: -SplashArt.lidPivotY)
                cat.fill(Path(paths.lids).applying(lid), with: mark)
            }
        }

        // 诱饵气泡：别人家聊天软件的灰气泡，从下方升起。
        if frame.decoyAlpha > 0 {
            group(context, opacity: frame.decoyAlpha) { decoy in
                decoy.translateBy(x: 0, y: frame.decoyY)
                decoy.fill(Path(roundedRect: SplashArt.decoyRect, cornerRadius: SplashArt.decoyRadius),
                           with: .color(Moyu.Palette.splashDecoy))
                for line in SplashArt.decoyLines {
                    decoy.fill(Path(roundedRect: line, cornerRadius: SplashArt.decoyLineRadius),
                               with: .color(Moyu.Palette.splashMark.opacity(SplashArt.decoyLineAlpha)))
                }
            }
        }

        // 字标 camochat，h 褪成虚线 → camo·cat（只有完整版）。
        if frame.wordmarkAlpha > 0 {
            group(context, opacity: frame.wordmarkAlpha) { word in
                word.translateBy(x: SplashArt.wordmarkOrigin.x, y: SplashArt.wordmarkOrigin.y + frame.wordmarkY)
                word.scaleBy(x: SplashArt.wordmarkScale, y: SplashArt.wordmarkScale)
                word.fill(Path(paths.wordmarkRest), with: mark)
                if frame.hFillAlpha > 0 {
                    var h = word
                    h.opacity = frame.hFillAlpha
                    h.fill(Path(paths.wordmarkH), with: mark)
                }
                if frame.hGhostAlpha > 0 {
                    var ghost = word
                    ghost.opacity = frame.hGhostAlpha
                    ghost.stroke(Path(paths.wordmarkH), with: mark,
                                 style: StrokeStyle(lineWidth: SplashArt.hGhostStrokeWidth, lineJoin: .round,
                                                    dash: SplashArt.hGhostDash))
                }
            }
        }

        // 两只爪子搭在气泡边上。
        if frame.pawsAlpha > 0 {
            group(context, opacity: frame.pawsAlpha) { paws in
                paws.translateBy(x: 0, y: frame.pawsY)
                for paw in SplashArt.paws {
                    paws.fill(Path(roundedRect: paw, cornerRadius: SplashArt.pawRadius), with: mark)
                }
            }
        }
    }

    /// 整组半透明（和网页的 <g opacity> 一样先合成再降透明度，组内重叠处不会透出叠影）。
    private func group(_ context: GraphicsContext, opacity: Double, _ content: (inout GraphicsContext) -> Void) {
        var outer = context
        outer.opacity = opacity
        outer.drawLayer(content: content)
    }
}

/// 用 CADisplayLink 按屏幕刷新推进 `SplashClock`。
@MainActor
private final class SplashDriver: ObservableObject {
    @Published private(set) var clock: SplashClock
    private var link: CADisplayLink?
    private var lastTimestamp: CFTimeInterval?
    private var onFinish: (() -> Void)?

    init(variant: SplashVariant) {
        clock = SplashClock(variant: variant)
    }

    func start(onFinish: @escaping () -> Void) {
        guard link == nil else { return }
        self.onFinish = onFinish
        let link = CADisplayLink(target: self, selector: #selector(tick(_:)))
        link.add(to: .main, forMode: .common)
        self.link = link
    }

    func skipToFade() {
        clock.skipToFade()
    }

    /// CADisplayLink 强持有 target，必须显式停掉。
    func stop() {
        link?.invalidate()
        link = nil
    }

    @objc private func tick(_ link: CADisplayLink) {
        if let last = lastTimestamp {
            clock.advance(byMs: (link.timestamp - last) * 1000)
        }
        lastTimestamp = link.timestamp
        if clock.isDone {
            stop()
            onFinish?()
            onFinish = nil
        }
    }
}
