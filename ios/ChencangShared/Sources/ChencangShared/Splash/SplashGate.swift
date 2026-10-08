import Foundation

/// 冷启动播哪个版本的开屏：装好后第一次是完整版，之后都是短版。
/// 「看过完整版」记在本机偏好里，完整版一开播就记上（中途跳过或被杀也不再重播）。
public enum SplashGate {
    static let fullSeenKey = "splash.fullSeen.v1"

    public static func variant(defaults: UserDefaults = .standard) -> SplashVariant {
        defaults.bool(forKey: fullSeenKey) ? .short : .full
    }

    public static func didStart(_ variant: SplashVariant, defaults: UserDefaults = .standard) {
        if variant == .full { defaults.set(true, forKey: fullSeenKey) }
    }
}
