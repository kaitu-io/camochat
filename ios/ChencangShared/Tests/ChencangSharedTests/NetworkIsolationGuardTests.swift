import XCTest

/// spec §2.5 / R6 + 2026-10-07 §6：全 App 只有 MediaTransport.swift 与 Config/ConfigFetcher.swift 可以联网；
/// iOS 源码与测试里不得再出现旧域名。
final class NetworkIsolationGuardTests: XCTestCase {
    /// 相对 `ChencangShared/Sources/ChencangShared/` 的路径后缀。
    private let allowedSuffixes = ["Media/MediaTransport.swift", "Config/ConfigFetcher.swift"]
    /// 旧域名,拆开写,免得守卫文件自己命中。
    private let legacyNeedle = "52j" + "." + "me"
    /// 能自己发起网络连接的 API(终审 minor 1):URLSession 家族、老的 NSURLConnection、Network.framework、
    /// WebKit 网页视图、CFNetwork / CFStream / Stream 套接字。
    static let networkPattern = #"URLSession|NSURLConnection|CFNetwork|NWConnection|NWPathMonitor|NWListener|"#
        + #"import\s+Network\b|WKWebView|import\s+WebKit\b|CFStream|CFReadStream|CFWriteStream|getStreamsToHost"#
    /// App 内 Safari 视图只许出现在「设置 → 关于 → 服务器源码」那一处(R10,只有点击才联网)。
    static let safariPattern = #"SFSafariViewController|import\s+SafariServices\b"#
    private let safariAllowedFile = "SafariView.swift"
    /// 后台上传会话的系统回调名(UIKit 规定的方法名,只接收事件、不发起连接)只许出现在 AppDelegate.swift
    /// (先分享、后上传 spec §1.1);会话本身仍在 MediaTransport.swift。
    static let backgroundEventsCallback = "handleEventsForBackgroundURLSession"
    private let backgroundEventsAllowedFile = "AppDelegate.swift"

    /// 本文件位于 ios/ChencangShared/Tests/ChencangSharedTests/，上溯 4 级 = ios/
    private var iosRoot: URL {
        var url = URL(fileURLWithPath: #filePath)
        for _ in 0..<4 { url.deleteLastPathComponent() }
        return url
    }

    private func swiftFiles(under relative: String) -> [URL] {
        let dir = iosRoot.appendingPathComponent(relative)
        guard let e = FileManager.default.enumerator(at: dir, includingPropertiesForKeys: nil) else { return [] }
        return e.compactMap { $0 as? URL }.filter { $0.pathExtension == "swift" }
    }

    func testOnlyMediaTransportTouchesTheNetwork() throws {
        let files = ["ChencangShared/Sources", "ChencangCompanion", "ChencangAction"].flatMap(swiftFiles(under:))
        XCTAssertGreaterThan(files.count, 20, "扫描范围不对：\(iosRoot.path)")
        let forbidden = try NSRegularExpression(pattern: Self.networkPattern)
        let safari = try NSRegularExpression(pattern: Self.safariPattern)
        var offenders: [String] = []
        for file in files where !allowedSuffixes.contains(where: { file.path.hasSuffix("/" + $0) }) {
            var text = try String(contentsOf: file, encoding: .utf8)
            if file.lastPathComponent == backgroundEventsAllowedFile {
                text = text.replacingOccurrences(of: Self.backgroundEventsCallback, with: "")
            }
            let range = NSRange(text.startIndex..., in: text)
            if forbidden.firstMatch(in: text, range: range) != nil {
                offenders.append(file.lastPathComponent)
            }
            if file.lastPathComponent != safariAllowedFile, safari.firstMatch(in: text, range: range) != nil {
                offenders.append("\(file.lastPathComponent)(SFSafariViewController)")
            }
        }
        XCTAssertEqual(offenders, [], "只有 MediaTransport.swift / ConfigFetcher.swift 可以联网（spec §2.5）")
    }

    private func filesContainingLegacyDomain(_ files: [URL]) throws -> [String] {
        try files.filter { try String(contentsOf: $0, encoding: .utf8).contains(legacyNeedle) }.map(\.lastPathComponent)
    }

    private func allIOSSourcesAndTests() -> [URL] {
        ["ChencangShared/Sources", "ChencangShared/Tests", "ChencangCompanion", "ChencangAction", "ChencangCompanionUITests"]
            .flatMap(swiftFiles(under:))
    }

    /// 本任务(Task 10)已清干净的文件:现在就强制零旧域名。
    func testTaskTenFilesHaveNoLegacyDomain() throws {
        let t = "ChencangShared/Tests/ChencangSharedTests/"
        let files = (swiftFiles(under: "ChencangShared/Sources/ChencangShared/Config")
            + ["ChencangShared/Sources/ChencangShared/Media/MediaTransport.swift", t + "NetworkIsolationGuardTests.swift",
               t + "MediaTransportTests.swift", t + "SignedConfigCodecTests.swift", t + "ConfigRepositoryTests.swift",
               t + "ConfigFetcherTests.swift", t + "RelaySelectorTests.swift", t + "FactoryConfigTests.swift",
               t + "Support/ConfigTestSupport.swift"].map { iosRoot.appendingPathComponent($0) })
        XCTAssertGreaterThan(files.count, 12)
        XCTAssertEqual(try filesContainingLegacyDomain(files), [])
        let sample = FileManager.default.temporaryDirectory.appendingPathComponent("legacy-\(UUID().uuidString).swift")
        try "let u = \"https://cc.\(legacyNeedle)/\"".write(to: sample, atomically: true, encoding: .utf8)
        defer { try? FileManager.default.removeItem(at: sample) }
        XCTAssertEqual(try filesContainingLegacyDomain([sample]).count, 1, "检测器空转")
    }

    /// 全量断言:iOS 源码与测试里不得再出现旧域名。
    func testNoLegacyDomainAnywhereInIOS() throws {
        XCTAssertEqual(try filesContainingLegacyDomain(allIOSSourcesAndTests()), [])
    }

    /// 守卫自己不能是空转:每一类都得真的能抓到。
    func testPatternsCatchEveryForbiddenAPI() throws {
        let forbidden = try NSRegularExpression(pattern: Self.networkPattern)
        let samples = [
            "let s = URLSession.shared", "NSURLSession *s", "NSURLConnection(request: r, delegate: nil)",
            "CFNetworkCopySystemProxySettings()", "let c = NWConnection(host: h, port: p, using: .tcp)",
            "import Network", "let m = NWPathMonitor()", "let w = WKWebView()", "import WebKit",
            "CFStreamCreatePairWithSocketToHost", "CFReadStreamOpen(s)", "Stream.getStreamsToHost(withName:)",
            // 豁免只在 AppDelegate.swift 里剥掉这个回调名;别处出现照抓。
            "func application(_ a: UIApplication, handleEventsForBackgroundURLSession id: String)",
        ]
        for sample in samples {
            XCTAssertNotNil(forbidden.firstMatch(in: sample, range: NSRange(sample.startIndex..., in: sample)),
                            "没抓到:\(sample)")
        }
        let benign = ["import NetworkExtensionHelpers", "let networkError = 1", "// 网络层回调"]
        for sample in benign {
            XCTAssertNil(forbidden.firstMatch(in: sample, range: NSRange(sample.startIndex..., in: sample)),
                         "误报:\(sample)")
        }
        let safari = try NSRegularExpression(pattern: Self.safariPattern)
        for sample in ["SFSafariViewController(url: u)", "import SafariServices"] {
            XCTAssertNotNil(safari.firstMatch(in: sample, range: NSRange(sample.startIndex..., in: sample)))
        }
    }
}
