import XCTest

/// 交互式真机驱动:宿主机一步一步下命令,runner 执行后回报界面树 + 截图。
///
/// - 输入:宿主机把 `<seq>\t<cmd>\t<arg>` 写进 runner 容器 `Documents/in-cmd.txt`(`devicectl device copy to`)。
///   文字参数(type / deliver)用 base64,避开换行与 emoji 转义。
/// - 输出:`Documents/out-<seq>.txt`(命令结果 + 精简界面树)和 `Documents/shot-<seq>.png`。
/// - `end` 结束;空闲 20 分钟自动结束。
final class UATDriveTests: UATCase {

    /// 聊天软件(微信),跨 App 走配对/收发时用:`wx` 切过去,`tap`/`press` 也会在它里面找元素。
    private let wx = XCUIApplication(bundleIdentifier: "com.tencent.xin")
    private let safari = XCUIApplication(bundleIdentifier: "com.apple.mobilesafari")

    func testDrive() {
        launchInChinese()
        var lastSeq = ""
        while true {
            guard let line = waitMailbox("cmd", timeout: 1200) else { return }
            let parts = line.components(separatedBy: "\t")
            let seq = parts[0]
            guard seq != lastSeq else { continue }
            lastSeq = seq
            let cmd = parts.count > 1 ? parts[1] : ""
            let arg = parts.count > 2 ? parts[2] : ""
            if cmd == "end" { report(seq, "ended"); return }
            let result = run(cmd, arg)
            Thread.sleep(forTimeInterval: 0.8)
            report(seq, result)
        }
    }

    private func run(_ cmd: String, _ arg: String) -> String {
        switch cmd {
        case "launch":
            app.terminate()
            app.launchArguments = []
            switch arg {
            case "en": app.launchArguments = ["-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
            case "ja": app.launchArguments = ["-AppleLanguages", "(ja)", "-AppleLocale", "ja_JP"]
            default: app.launchArguments = ["-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
            }
            app.launch()
            front = app
            return "launched \(arg) state=\(app.state.rawValue)"
        case "activate":
            app.activate()
            front = app
            return "activated"
        case "wx":
            wx.activate()
            front = wx
            return "wx activated state=\(wx.state.rawValue)"
        case "tap", "tapc", "tapn":
            guard let element = find(arg, contains: cmd == "tapc", nth: cmd == "tapn") else { return "NOT FOUND \(arg)" }
            let desc = "\(element.elementType.rawValue) '\(element.label)'"
            element.tap()
            return "tapped \(desc)"
        case "press":
            let bits = arg.components(separatedBy: "|")
            guard let element = find(bits[0], contains: true) else { return "NOT FOUND \(bits[0])" }
            let label = element.label
            element.press(forDuration: Double(bits.count > 1 ? bits[1] : "1.2") ?? 1.2)
            return "pressed '\(label)'"
        case "tapxy":
            let v = arg.split(separator: " ").compactMap { Double($0) }
            guard v.count == 2 else { return "bad xy" }
            frontHost().coordinate(withNormalizedOffset: CGVector(dx: v[0], dy: v[1])).tap()
            return "tapped xy \(v)"
        case "pressxy":
            let v = arg.split(separator: " ").compactMap { Double($0) }
            guard v.count == 3 else { return "bad xy secs" }
            frontHost().coordinate(withNormalizedOffset: CGVector(dx: v[0], dy: v[1])).press(forDuration: v[2])
            return "pressed xy \(v)"
        case "type":
            let text = decode(arg)
            frontHost().typeText(text)
            return "typed \(text.count) chars"
        case "typef":
            let field = composerField()
            field.tap()
            field.typeText(decode(arg))
            return "typed into composer"
        case "clear":
            clearComposer(composerField())
            return "cleared"
        case "alert":
            return "alert: \(acceptSystemAlert(timeout: 5, allow: arg.isEmpty ? UATCase.allowLabels : [arg]) ?? "none")"
        case "sb":
            let element = springboard.descendants(matching: .any).matching(NSPredicate(format: "label == %@", arg)).firstMatch
            guard element.waitForExistence(timeout: 3) else { return "NOT FOUND sb \(arg)" }
            element.tap()
            return "tapped sb \(arg)"
        case "swipe":
            switch arg {
            case "up": app.swipeUp()
            case "down": app.swipeDown()
            case "left": app.swipeLeft()
            default: app.swipeRight()
            }
            return "swiped \(arg)"
        case "sleep":
            Thread.sleep(forTimeInterval: Double(arg) ?? 1)
            return "slept"
        case "clip":
            return "clip=" + captureClipboard("drive-clip")
        case "deliver":
            return deliver(decode(arg))
        case "menu":
            // 输入框编辑菜单项(全选 / 粘贴 / 共享…)
            let field = composerField()
            guard let item = editMenuItem(arg.components(separatedBy: "|"), in: app, field: field) else { return "NOT FOUND menu \(arg)" }
            item.tap()
            return "menu \(arg)"
        case "look":
            return "look"
        case "open":
            // 像系统一样打开网址(通用链接会直接拉起已装的 App)。arg 可为 base64。
            guard let url = URL(string: decode(arg)) else { return "bad url" }
            XCUIDevice.shared.system.open(url)
            Thread.sleep(forTimeInterval: 3)
            front = app.state == .runningForeground ? app : safari
            return "opened app=\(app.state.rawValue) safari=\(safari.state.rawValue)"
        case "kill":
            app.terminate()
            return "killed state=\(app.state.rawValue)"
        case "safari":
            safari.activate()
            front = safari
            return "safari state=\(safari.state.rawValue)"
        default:
            return "unknown \(cmd)"
        }
    }

    /// 宿主机给的文本 → 陈仓输入框 → 全选 →「共享…」→「陈仓解密」。之后停在扩展卡片上,由后续命令看结果。
    private func deliver(_ text: String) -> String {
        let field = composerField()
        guard field.waitForExistence(timeout: 5) else { return "no composer" }
        clearComposer(field)
        field.tap()
        field.typeText(text)
        Thread.sleep(forTimeInterval: 0.6)
        guard let all = editMenuItem(["全选", "Select All"], in: app, field: field) else { return "no select all" }
        all.tap()
        Thread.sleep(forTimeInterval: 0.8)
        guard let share = editMenuItem(["共享…", "Share…", "共享...", "Share..."], in: app, field: field) else { return "no share" }
        share.tap()
        let action = app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", "陈仓解密")).firstMatch
        var found = action.waitForExistence(timeout: 10)
        for _ in 0..<4 where !found || !action.isHittable {
            app.swipeUp()
            found = action.waitForExistence(timeout: 2)
        }
        guard found else { return "陈仓解密 not in sheet" }
        action.tap()
        Thread.sleep(forTimeInterval: 2.5)
        return "delivered"
    }

    private func find(_ query: String, contains: Bool, nth: Bool = false) -> XCUIElement? {
        var q = query
        var index = 0
        if nth, let bar = query.lastIndex(of: "|") {
            index = Int(query[query.index(after: bar)...]) ?? 0
            q = String(query[..<bar])
        }
        let predicate = contains
            ? NSPredicate(format: "identifier == %@ OR label CONTAINS %@", q, q)
            : NSPredicate(format: "identifier == %@ OR label == %@", q, q)
        for host in [frontHost(), springboard] {
            let matches = host.descendants(matching: .any).matching(predicate)
            let hittable = matches.allElementsBoundByIndex.filter { $0.exists && $0.isHittable }
            if hittable.count > index { return hittable[index] }
        }
        return nil
    }

    /// 当前操作的 App:由 launch/activate/wx/safari/open 命令显式切换。XCUIApplication.state 在多 App 间切换时不可靠
    /// (曾把微信会话列表误当成前台写进报告),所以不用它判断。
    private var front: XCUIApplication?

    private func frontHost() -> XCUIApplication {
        front ?? app
    }

    private func decode(_ arg: String) -> String {
        if let data = Data(base64Encoded: arg), let text = String(data: data, encoding: .utf8) { return text }
        return arg
    }

    private func report(_ seq: String, _ result: String) {
        var lines = ["RESULT \(result)", "STATE app=\(app.state.rawValue)"]
        // 微信是用户真实账号:不把它的界面树写进报告,只留截图供核对。
        if frontHost() !== wx, let snap = try? frontHost().snapshot() { walk(snap, into: &lines, depth: 0) }
        let alert = springboard.alerts.firstMatch
        if alert.exists { lines.append("SB-ALERT '\(alert.label)' buttons=\(alert.buttons.allElementsBoundByIndex.map(\.label))") }
        let docs = Self.documents
        try? lines.joined(separator: "\n").write(to: docs.appendingPathComponent("out-\(seq).txt"), atomically: true, encoding: .utf8)
        try? XCUIScreen.main.screenshot().pngRepresentation.write(to: docs.appendingPathComponent("shot-\(seq).png"))
    }

    private func walk(_ s: XCUIElementSnapshot, into lines: inout [String], depth: Int) {
        let label = s.label, id = s.identifier
        let value = (s.value as? String) ?? ""
        if !label.isEmpty || !id.isEmpty || !value.isEmpty {
            let f = s.frame
            lines.append("\(String(repeating: " ", count: min(depth, 12)))\(s.elementType.rawValue) id=\(id) '\(label)' v='\(value.prefix(80))' @\(Int(f.minX)),\(Int(f.minY)),\(Int(f.width))x\(Int(f.height))")
        }
        for c in s.children { walk(c, into: &lines, depth: depth + 1) }
    }
}
