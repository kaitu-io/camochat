import Foundation

/// 设置 → 关于(R10、spec §2.4)。
public enum AppInfo {
    /// 中转服务全部源码(只有用户点击才打开;由系统 Safari 组件加载,不经 App 的网络代码)。
    public static func sourceURL(site: String) -> URL { URL(string: site + "source")! }

    /// 隐私政策页(官网静态页,与 `sourceURL` 同一站点;同样只在用户点击时由 Safari 组件加载)。
    public static func privacyURL(site: String) -> URL { URL(string: site + "privacy.html")! }

    public static func versionText(info: [String: Any]?) -> String {
        let short = info?["CFBundleShortVersionString"] as? String ?? "—"
        guard let build = info?["CFBundleVersion"] as? String else { return short }
        return "\(short) (\(build))"
    }
}
