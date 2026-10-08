import XCTest
import UIKit

/// 富媒体真机 UAT(Plan 3 Task 12)的公共设施。
///
/// 跨设备/跨次运行的字符串交换走两条路:
/// - 输入:`xcodebuild test` 的 `TEST_RUNNER_UAT_*` 环境变量(值用 base64,避开换行/emoji 转义);
///   配对这种「对端要在运行中途收到对方的配对码」的场景,改为轮询 runner 自己容器的 `Documents/in-<name>.txt`,
///   由宿主机用 `devicectl device copy to` 投进来。
/// - 输出:写 runner 容器 `Documents/<name>.txt`(宿主机 `devicectl device copy from` 取)+ 文本附件。
class UATCase: XCTestCase {
    let app = XCUIApplication()
    let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")

    override func setUp() {
        super.setUp()
        continueAfterFailure = true
    }

    /// 开跑前的通话闸门(2026-09-30:上一轮在机主视频通话时把陈仓切到了前台)。设备正在通话
    /// (来电界面在前台,或状态栏 / 灵动岛出现通话类标签)就直接让用例失败、一步都不动。
    override func setUpWithError() throws {
        try super.setUpWithError()
        let incall = XCUIApplication(bundleIdentifier: "com.apple.InCallService")
        let facetime = XCUIApplication(bundleIdentifier: "com.apple.facetime")
        let words = ["通话", "呼叫", "Call", "FaceTime", "LINE", "来电", "视频"]
        let barLabels = springboard.statusBars.firstMatch.exists
            ? springboard.statusBars.firstMatch.descendants(matching: .any).allElementsBoundByIndex.map(\.label) : []
        let barHit = barLabels.filter { label in words.contains { label.contains($0) } }
        let fg = [incall, facetime].filter { $0.state == .runningForeground }.map { $0.description }
        record("precheck-call", "incallFg=\(fg) statusBarHits=\(barHit) statusBar=\(barLabels)")
        shot("precheck")
        if !fg.isEmpty || !barHit.isEmpty {
            throw NSError(domain: "uat", code: 99, userInfo: [NSLocalizedDescriptionKey: "设备似乎在通话中,停跑: \(fg) \(barHit)"])
        }
    }

    // MARK: - 证据

    func shot(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    func record(_ name: String, _ text: String) {
        let attachment = XCTAttachment(string: text)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
        try? text.write(to: Self.documents.appendingPathComponent("\(name).txt"), atomically: true, encoding: .utf8)
        print("UATRECORD \(name)=\(Data(text.utf8).base64EncodedString())")
    }

    /// 截一张屏并在归一化坐标处取像素(0–255 RGB),用于「纯黑底」这类判定。截图同时存为附件。
    func samplePixels(_ name: String, at points: [CGVector]) -> [(r: Int, g: Int, b: Int)] {
        let screenshot = XCUIScreen.main.screenshot()
        let attachment = XCTAttachment(screenshot: screenshot)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
        guard let cg = screenshot.image.cgImage else { return [] }
        let w = cg.width, h = cg.height
        var pixel = [UInt8](repeating: 0, count: 4)
        return points.map { p in
            let x = min(w - 1, max(0, Int(p.dx * CGFloat(w)))), y = min(h - 1, max(0, Int(p.dy * CGFloat(h))))
            guard let crop = cg.cropping(to: CGRect(x: x, y: y, width: 1, height: 1)),
                  let ctx = CGContext(data: &pixel, width: 1, height: 1, bitsPerComponent: 8, bytesPerRow: 4,
                                      space: CGColorSpaceCreateDeviceRGB(),
                                      bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return (-1, -1, -1) }
            ctx.draw(crop, in: CGRect(x: 0, y: 0, width: 1, height: 1))
            return (Int(pixel[0]), Int(pixel[1]), Int(pixel[2]))
        }
    }

    func dumpTree(_ name: String, _ element: XCUIElement? = nil) {
        let attachment = XCTAttachment(string: (element ?? app).debugDescription)
        attachment.name = "tree-\(name)"
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    static var documents: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    }

    // MARK: - 输入

    func env(_ key: String) -> String? {
        guard let raw = ProcessInfo.processInfo.environment[key], !raw.isEmpty else { return nil }
        if let data = Data(base64Encoded: raw), let text = String(data: data, encoding: .utf8) { return text }
        return raw
    }

    /// 轮询 `Documents/in-<name>.txt`(宿主机投递)。
    func waitMailbox(_ name: String, timeout: TimeInterval) -> String? {
        let url = Self.documents.appendingPathComponent("in-\(name).txt")
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if let text = try? String(contentsOf: url, encoding: .utf8), !text.isEmpty {
                try? FileManager.default.removeItem(at: url)
                return text.trimmingCharacters(in: .whitespacesAndNewlines)
            }
            // 保持与 App 的连接活着,顺手处理可能冒出的系统框
            _ = app.state
            Thread.sleep(forTimeInterval: 2)
        }
        return nil
    }

    // MARK: - 系统弹窗

    static let allowLabels = ["允许", "允许粘贴", "好", "Allow", "Allow Paste", "OK", "允许访问", "使用 App 时允许"]

    /// 等系统权限框出现并点「允许」一类按钮;返回弹框标题(没出现返回 nil)。
    @discardableResult
    func acceptSystemAlert(timeout: TimeInterval, allow: [String] = UATCase.allowLabels) -> String? {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            for host in [springboard, app] {
                let alert = host.alerts.firstMatch
                if alert.exists {
                    let title = alert.label
                    for label in allow where alert.buttons[label].exists {
                        alert.buttons[label].tap()
                        return title
                    }
                }
            }
            Thread.sleep(forTimeInterval: 0.3)
        }
        return nil
    }

    func systemAlertPresent() -> Bool {
        springboard.alerts.firstMatch.exists
    }

    // MARK: - 导航

    /// 界面文案断言一律按简体中文写:固定中文启动,不依赖机器当前语言。所有启动都走这里(含不过引导的冷启动用例)。
    func launchInChinese() {
        let args = ["-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        if app.launchArguments.first != args.first { app.launchArguments += args }
        app.launch()
    }

    func launchApp() {
        launchInChinese()
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 20))
        passOnboardingIfNeeded()
    }

    func passOnboardingIfNeeded() {
        let create = app.buttons["创建我的密钥"]
        if create.waitForExistence(timeout: 3) {
            create.tap()
            // 名字幕:跳过(问过名字的重装不会出现)。
            let skip = app.buttons["跳过"]
            if skip.waitForExistence(timeout: 30) { skip.tap() }
            let done = app.buttons["我明白了"]
            if done.waitForExistence(timeout: 30) { done.tap() }
        }
    }

    // MARK: - tab(三 tab 骨架)

    /// tab 栏按钮。一律按 label 查:`main-tab-*` 的标识挂在 tabItem 的 Label 上,不保证透传给 tab 按钮;
    /// 联系人 tab 带角标时读屏 label 会变成「联系人,n项配对待处理」,所以用前缀匹配而不是整串相等。
    func tabButton(_ label: String) -> XCUIElement {
        app.tabBars.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", label)).firstMatch
    }

    func selectTab(_ label: String) {
        let button = tabButton(label)
        XCTAssertTrue(button.waitForExistence(timeout: 10), "找不到 tab「\(label)」")
        if !button.isSelected { button.tap() }
        Thread.sleep(forTimeInterval: 0.4)
    }

    /// 联系人 tab 角标的数字(没有角标 = 0)。读 tab 按钮的 label 与 value 里出现的数字。
    func contactsBadgeCount() -> Int {
        let button = tabButton("联系人")
        guard button.exists else { return 0 }
        let text = "\(button.label) \(button.value as? String ?? "")"
        let digits = text.components(separatedBy: CharacterSet.decimalDigits.inverted).filter { !$0.isEmpty }
        return digits.compactMap(Int.init).first ?? 0
    }

    /// 在当前屏的滚动内容上上滑。优先对第一个列表 / 滚动视图滑,而不是整个 App 元素:
    /// 在 iPad 模拟器上 iPhone App 以兼容窗口运行,按整个屏幕坐标滑会落到窗口外。
    func swipeContentUp() {
        let list = app.collectionViews.firstMatch
        if list.exists { list.swipeUp() } else { app.swipeUp() }
    }

    // MARK: - 导航

    /// 回到根并停在「会话」tab(标题「陈仓」)。在线程 / 详情 / 向导里就先退出来。
    func backToList() {
        for _ in 0..<5 {
            // 根上(tab 栏可点)就切到会话 tab;标题「陈仓」只在会话 tab 出现。
            let chats = tabButton("会话")
            if chats.exists && chats.isHittable {
                if !chats.isSelected { chats.tap() }
                _ = app.navigationBars["陈仓"].waitForExistence(timeout: 5)
                return
            }
            if app.navigationBars["陈仓"].exists || app.staticTexts["陈仓"].exists { return }
            let back = app.navigationBars.buttons.element(boundBy: 0)
            if back.exists { back.tap() } else { return }
            Thread.sleep(forTimeInterval: 0.6)
        }
    }

    /// 会话 tab 里找得到就点;3 秒内找不到(没有消息的联系人不在会话 tab)就走联系人 tab →「发消息」。
    func openThread(_ name: String) {
        backToList()
        let row = app.staticTexts[name].firstMatch
        if row.waitForExistence(timeout: 3) {
            row.tap()
            XCTAssertTrue(app.buttons["更多"].waitForExistence(timeout: 10) || app.buttons["切换到语音"].exists)
        } else {
            openThreadViaContacts(name)
        }
    }

    /// 联系人 tab → 联系人行 → 详情页「发消息」→ 线程。
    func openThreadViaContacts(_ name: String) {
        selectTab("联系人")
        let row = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "\(name)，")).firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 10), "联系人 tab 里找不到 \(name)")
        row.tap()
        let message = app.buttons["发消息"]
        XCTAssertTrue(message.waitForExistence(timeout: 10), "联系人详情里没有「发消息」")
        message.tap()
        XCTAssertTrue(app.buttons["更多"].waitForExistence(timeout: 10) || app.buttons["切换到语音"].exists, "没进线程")
    }

    // MARK: - 分享面板

    /// 分享面板的「拷贝」按钮。面板在 App 进程里呈现,但也兜底查 springboard。
    func shareSheetCopyButton(timeout: TimeInterval) -> XCUIElement? {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            for host in [app, springboard] {
                for label in ["拷贝", "Copy"] {
                    let button = host.buttons[label].firstMatch
                    if button.exists { return button }
                    let cell = host.cells[label].firstMatch
                    if cell.exists { return cell }
                }
            }
            Thread.sleep(forTimeInterval: 0.3)
        }
        return nil
    }

    func shareSheetPresent() -> Bool {
        app.otherElements["ActivityListView"].exists || shareSheetCopyButton(timeout: 0.5) != nil
    }

    /// 清空并键入(runner 在后台写不了系统剪贴板,只能走键盘合成输入)。
    func typeInto(_ field: XCUIElement, _ text: String) {
        XCTAssertTrue(field.waitForExistence(timeout: 10))
        field.tap()
        if let current = field.value as? String, !current.isEmpty, current != field.placeholderValue {
            field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: current.count))
        }
        field.typeText(text)
    }

    // MARK: - 剪贴板

    /// runner 读系统剪贴板(跨 App 读会弹「允许粘贴」,自动允许)。
    func readPasteboard() -> String? {
        var result: String?
        let reader = Thread {
            result = UIPasteboard.general.string
        }
        reader.start()
        let deadline = Date().addingTimeInterval(15)
        while !reader.isFinished && Date() < deadline {
            acceptSystemAlert(timeout: 0.5)
        }
        return result
    }

    /// runner 写系统剪贴板(模拟器可用;真机后台写会失败,真机用例仍走键入)。
    func writePasteboard(_ text: String) {
        let writer = Thread { UIPasteboard.general.string = text }
        writer.start()
        let deadline = Date().addingTimeInterval(15)
        while !writer.isFinished && Date() < deadline {
            acceptSystemAlert(timeout: 0.5)
        }
    }

    /// 把 `text` 放上剪贴板,切 tab 触发粘贴条重算,点粘贴条里的系统粘贴按钮(应用内的收件入口)。
    func pasteViaPasteBar(_ text: String) {
        writePasteboard(text)
        selectTab("联系人")
        selectTab("会话")
        let paste = app.buttons["paste-bar-paste"]
        XCTAssertTrue(paste.waitForExistence(timeout: 10), "没出现粘贴条")
        paste.tap()
        acceptSystemAlert(timeout: 2)
    }

    // MARK: - 编辑菜单

    /// 编辑菜单项(iOS 26 上可能是 menuItem 也可能是 button)。
    func menuItem(in host: XCUIApplication, _ labels: [String]) -> XCUIElement {
        let predicate = NSPredicate(format: "label IN %@", labels)
        let item = host.menuItems.matching(predicate).firstMatch
        if item.waitForExistence(timeout: 2) { return item }
        return host.buttons.matching(predicate).firstMatch
    }

    /// 编辑菜单里找一项;找不到就翻「更多」再找,再不行重新长按 `field` 唤出菜单。
    func editMenuItem(_ labels: [String], in host: XCUIApplication, field: XCUIElement) -> XCUIElement? {
        let predicate = NSPredicate(format: "label IN %@", labels)
        for attempt in 0..<3 {
            let item = host.menuItems.matching(predicate).firstMatch
            if item.waitForExistence(timeout: 2) { return item }
            let button = host.buttons.matching(predicate).firstMatch
            if button.exists { return button }
            record("editmenu-miss-\(labels[0])-\(attempt)", host.menuItems.allElementsBoundByIndex.map(\.label).joined(separator: " | "))
            let chevron = host.buttons.matching(
                NSPredicate(format: "label IN %@", ["Forward", "显示更多项目", "Show more items", "向前"])).firstMatch
            if chevron.exists {
                chevron.tap()
                if item.waitForExistence(timeout: 2) { return item }
                let anyItem = host.descendants(matching: .any).matching(predicate).firstMatch
                if anyItem.exists { return anyItem }
            }
            if attempt == 0 {
                field.tap()
                Thread.sleep(forTimeInterval: 0.6)
                field.tap()
            } else {
                field.press(forDuration: 1.0)
            }
        }
        return nil
    }

    // MARK: - 分享文本进出手机:只经陈仓自己的输入框 + runner 容器/附件
    //
    // 2026-10-01 起不再用备忘录(或任何别的 App)中转分享文本:备忘录会同步 iCloud,加密消息不能进机主的数据。
    // - 带出:剪贴板贴进陈仓线程的输入框(`ThreadViewModel.draft` 只在内存里,不落盘、不发送),读出后清空,
    //   经 `record` 写进 runner 自己容器 `Documents/<tag>.txt` + xcresult 文本附件(宿主机 `devicectl device
    //   copy from` 或导出附件取,用完即删)。
    // - 带入:宿主机给的文本键进同一个输入框 → 全选 →「共享…」→「陈仓解密」(Action Extension),
    //   读完同样清空输入框。全程不点「加密」。

    /// 当前线程的输入框(必须已在某个线程里、键盘模式)。
    func composerField() -> XCUIElement {
        if app.buttons["切换到键盘"].exists { app.buttons["切换到键盘"].tap() }
        // 线程里只有这一个 TextField;键入后 placeholderValue 会消失,所以不能按占位符查。
        let field = app.textFields.firstMatch
        _ = field.waitForExistence(timeout: 5)
        return field
    }

    func composerText(_ field: XCUIElement) -> String {
        guard let value = field.value as? String, value != field.placeholderValue, value != "写点什么…" else { return "" }
        return value
    }

    /// 清空输入框:全选 + 删除,不行就逐字删。
    func clearComposer(_ field: XCUIElement) {
        guard !composerText(field).isEmpty else { return }
        if let all = editMenuItem(["全选", "Select All"], in: app, field: field) {
            all.tap()
            Thread.sleep(forTimeInterval: 0.4)
            field.typeText(XCUIKeyboardKey.delete.rawValue)
        }
        for _ in 0..<3 where !composerText(field).isEmpty {
            field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: composerText(field).count + 2))
        }
        XCTAssertTrue(composerText(field).isEmpty, "输入框没清空")
    }

    /// 剪贴板 → 陈仓输入框 → 读出 → 清空 → 记为 `<tag>`(runner 容器 + 附件)。调用前必须在某个线程里。
    @discardableResult
    func captureClipboard(_ tag: String) -> String {
        let field = composerField()
        XCTAssertTrue(field.waitForExistence(timeout: 5), "找不到输入框")
        clearComposer(field)
        field.tap()
        Thread.sleep(forTimeInterval: 0.6)
        if let paste = editMenuItem(["粘贴", "Paste"], in: app, field: field) { paste.tap() } else { dumpTree("composer-no-paste") }
        acceptSystemAlert(timeout: 2)
        Thread.sleep(forTimeInterval: 1.0)
        var text = composerText(field)
        clearComposer(field)
        // 剪贴板里要是机主自己的内容(不是本轮造的分享文本/哨兵),一个字都不记,只记长度。
        if !(text.contains("陈仓加密") || text.hasPrefix("uat-") || text.isEmpty) {
            record("\(tag)-foreign", "\(text.count) chars")
            text = "<foreign>"
        }
        record(tag, text)
        app.keyboards.buttons.matching(NSPredicate(format: "label IN %@", ["完成", "Done", "收起键盘"])).firstMatch.tapIfExists()
        return text
    }

    /// 宿主机给的分享文本 → 陈仓输入框 → 全选 →「共享…」→「陈仓解密」→ 卡片「收到加密<expect>」→「打开陈仓查看」。
    /// 调用前必须在某个线程里。返回是否成功回到陈仓。
    @discardableResult
    func deliverToDecryptAction(_ text: String, expect: String, tag: String) -> Bool {
        let field = composerField()
        XCTAssertTrue(field.waitForExistence(timeout: 5), "找不到输入框")
        clearComposer(field)
        field.tap()
        // `UAT_WIRE_ONLY=1`:只键入加密消息那一行。拼音键盘下 XCTest 键不出首行链接里的某些字符(键入会卡住),
        // 而扩展本来就是逐行找能解码的那一行(WireLocator.extract),首行不参与解密。
        let typed = env("UAT_WIRE_ONLY") == "1"
            ? (text.components(separatedBy: .newlines).last { !$0.trimmingCharacters(in: .whitespaces).isEmpty } ?? text) : text
        field.typeText(typed)
        Thread.sleep(forTimeInterval: 0.6)
        guard let all = editMenuItem(["全选", "Select All"], in: app, field: field) else { XCTFail("找不到全选"); clearComposer(field); return false }
        all.tap()
        Thread.sleep(forTimeInterval: 0.8)
        guard let share = editMenuItem(["共享…", "Share…", "共享...", "Share..."], in: app, field: field) else {
            dumpTree("\(tag)-no-share"); XCTFail("找不到共享"); clearComposer(field); return false
        }
        share.tap()
        let action = app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", "陈仓解密")).firstMatch
        var found = action.waitForExistence(timeout: 10)
        for _ in 0..<4 where !found || !action.isHittable {
            app.swipeUp()
            found = action.waitForExistence(timeout: 2)
        }
        guard found else { record("\(tag)-no-action", "陈仓解密 不在分享面板"); XCTFail("分享面板里没有「陈仓解密」"); return false }
        action.tap()
        // 扩展卡片上的类型标签(媒体)= `MessageKind.displayLabel`:语音 / 图片 / 视频 / 「3 张图片」,整串相等。
        let card = app.staticTexts.matching(NSPredicate(format: "label == %@", expect)).firstMatch
        XCTAssertTrue(card.waitForExistence(timeout: 20), "卡片没显示类型「\(expect)」")
        shot("\(tag)-card")
        let open = app.buttons["打开陈仓查看"].firstMatch
        guard open.waitForExistence(timeout: 5) else { XCTFail("没有「打开陈仓查看」"); return false }
        open.tap()
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 15), "没回到陈仓")
        Thread.sleep(forTimeInterval: 1.5)
        // 输入框里的加密消息清掉(draft 只在内存,但别留在屏幕上)
        if app.buttons["更多"].exists || app.buttons["加密"].exists {
            let f = composerField()
            if f.exists { clearComposer(f) }
        }
        return true
    }
}

extension XCUIElement {
    func tapIfExists() { if exists && isHittable { tap() } }
}
