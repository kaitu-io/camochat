import Foundation

/// 开屏动画「躲猫猫」的时间轴：纯函数 t(毫秒) → 当帧状态，界面只管照着画。
/// 两个版本共用一套公式，只是关键帧时刻不同：
/// - `.full`（2.2 秒）：装好后第一次启动，带字标 camochat 与 h 隐身成 camo·cat；
/// - `.short`（1.0 秒）：之后每次冷启动，只有猫躲进气泡、探头、眨眼、淡出。
public enum SplashVariant: Equatable {
    case full
    case short

    /// 关键帧表（毫秒）。字段含义见 `SplashFrame.at`。
    public struct Keyframes: Equatable {
        public let duckStart: Double
        public let peekStart: Double
        public let peekEnd: Double
        public let decoyFadeInEnd: Double
        public let pawsStart: Double
        public let pawsFadeInEnd: Double
        public let pawsEnd: Double
        /// 字标浮现区间；短版没有字标。
        public let wordmark: ClosedRange<Double>?
        /// h 褪成虚线的区间；短版没有。
        public let camouflage: ClosedRange<Double>?
        public let blinkStart: Double
        public let blinkEnd: Double
        public let fadeStart: Double
        public let end: Double
    }

    public var keyframes: Keyframes {
        switch self {
        case .full:
            return Keyframes(duckStart: 200, peekStart: 550, peekEnd: 900, decoyFadeInEnd: 320,
                             pawsStart: 650, pawsFadeInEnd: 720, pawsEnd: 850,
                             wordmark: 550...850, camouflage: 1050...1350,
                             blinkStart: 1400, blinkEnd: 1550, fadeStart: 1900, end: 2200)
        case .short:
            return Keyframes(duckStart: 120, peekStart: 380, peekEnd: 620, decoyFadeInEnd: 200,
                             pawsStart: 450, pawsFadeInEnd: 500, pawsEnd: 600,
                             wordmark: nil, camouflage: nil,
                             blinkStart: 620, blinkEnd: 740, fadeStart: 750, end: 1000)
        }
    }

    /// 点一下跳过 / 减弱动态效果：直接从淡出开始。
    public var skipTo: Double { keyframes.fadeStart }
    public var duration: Double { keyframes.end }
}

/// 某一时刻的画面状态。坐标单位是 100 格的猫网格（与 brand/camochat-glyph.svg 相同）。
public struct SplashFrame: Equatable {
    /// 猫整体的竖向位移（正 = 往下缩）。
    public var catY: Double
    /// 猫的裁切下沿：y 大于它的部分不画（静止 100，气泡升起后 80）。
    public var clipBottom: Double
    public var decoyY: Double
    public var decoyAlpha: Double
    public var pawsY: Double
    public var pawsAlpha: Double
    public var wordmarkAlpha: Double
    /// 字标相对静止位置的竖向偏移（网格单位）。
    public var wordmarkY: Double
    public var hFillAlpha: Double
    public var hGhostAlpha: Double
    /// 眨眼时眼皮的竖向缩放（0 = 睁眼）。
    public var lidScaleY: Double
    public var overlayAlpha: Double
    public var contentScale: Double
    public var isDone: Bool

    public static func at(_ t: Double, variant: SplashVariant) -> SplashFrame {
        let k = variant.keyframes
        let rise = ease(seg(t, k.duckStart, k.peekStart))
        let w = k.wordmark.map { ease(seg(t, $0.lowerBound, $0.upperBound)) } ?? 0
        let g = k.camouflage.map { ease(seg(t, $0.lowerBound, $0.upperBound)) } ?? 0
        let b = seg(t, k.blinkStart, k.blinkEnd)
        let f = ease(seg(t, k.fadeStart, k.end))
        return SplashFrame(
            catY: rise * 16 - back(seg(t, k.peekStart, k.peekEnd)) * 20,
            clipBottom: 100 - 20 * rise,
            decoyY: (1 - rise) * 34,
            decoyAlpha: seg(t, k.duckStart, k.decoyFadeInEnd),
            pawsY: (1 - back(seg(t, k.pawsStart, k.pawsEnd))) * 8,
            pawsAlpha: seg(t, k.pawsStart, k.pawsFadeInEnd),
            wordmarkAlpha: w,
            wordmarkY: (1 - w) * 6,
            hFillAlpha: 1 - g,
            hGhostAlpha: g,
            lidScaleY: 0.9 * sin(.pi * b),
            overlayAlpha: 1 - f,
            contentScale: 1 + 0.06 * f,
            isDone: t >= k.end
        )
    }

    static func seg(_ t: Double, _ a: Double, _ b: Double) -> Double {
        min(1, max(0, (t - a) / (b - a)))
    }

    /// easeOutCubic。
    static func ease(_ t: Double) -> Double { 1 - pow(1 - t, 3) }

    /// easeOutBack，c = 1.7（略冲过头再回弹）。
    static func back(_ t: Double) -> Double {
        let c = 1.7
        return 1 + (c + 1) * pow(t - 1, 3) + c * pow(t - 1, 2)
    }
}

/// 动画时钟：按「实际画出来的帧」推进，而不是按墙上时间。
/// 冷启动时主线程常会被 App 自己的初始化卡住几百毫秒；按墙上时间算，卡完动画已经跳到结尾。
/// 这里每帧最多前进 `maxStepMs`，卡顿期间画面停在当前帧（首帧 = 系统启动页），卡完接着播。
public struct SplashClock: Equatable {
    public let variant: SplashVariant
    public private(set) var t: Double = 0
    public static let maxStepMs: Double = 1000.0 / 30

    public init(variant: SplashVariant) { self.variant = variant }

    public mutating func advance(byMs dt: Double) {
        t = min(variant.duration, t + min(max(dt, 0), Self.maxStepMs))
    }

    /// 点一下 / 链接唤起 / 减弱动态效果：跳到淡出起点（已过则不动）。
    public mutating func skipToFade() {
        t = max(t, variant.skipTo)
    }

    public var isDone: Bool { t >= variant.duration }
    public var frame: SplashFrame { SplashFrame.at(t, variant: variant) }
}
