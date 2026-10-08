import XCTest

/// 发送侧清单(brief Step 3 第 0–7、9–11 项)。每条用例独立 launch;对端联系人名经
/// `UAT_PEER_NAME` 传入(iPhone 15 上是 UAT-12,iPhone 12 上是 UAT-15)。
final class UATSendTests: UATCase {

    var peer: String { env("UAT_PEER_NAME") ?? "UAT-12" }

    // MARK: - 元素

    var holdBar: XCUIElement { app.descendants(matching: .any)["按住 说话"].firstMatch }

    func elements(labelBeginsWith prefix: String) -> XCUIElementQuery {
        app.descendants(matching: .any).matching(NSPredicate(format: "label BEGINSWITH %@", prefix))
    }

    func anyElement(labelContains text: String) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", text)).firstMatch
    }

    // 气泡带 .isButton trait,在树里是 Button;只数 Button,避免同一气泡被 Other/StaticText 重复计数
    var voiceCount: Int { app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "语音 ")).count }
    var imageCount: Int { app.buttons.matching(NSPredicate(format: "label == %@", "图片")).count }
    // 视频气泡里右下角时长胶囊也会冒出一个同名小 Button,按高度过滤掉
    var videoCount: Int {
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "视频 ")).allElementsBoundByIndex
            .filter { $0.frame.height > 40 }.count
    }

    func enterVoiceMode() {
        if app.buttons["切换到语音"].waitForExistence(timeout: 5) { app.buttons["切换到语音"].tap() }
        XCTAssertTrue(holdBar.waitForExistence(timeout: 5), "没有「按住 说话」条")
    }

    func openPlus(_ tile: String) {
        if app.buttons["切换到键盘"].exists { app.buttons["切换到键盘"].tap() }
        app.buttons["更多"].tap()
        let button = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", tile)).firstMatch
        XCTAssertTrue(button.waitForExistence(timeout: 5), "➕ 面板没有「\(tile)」")
        button.tap()
    }

    /// 等分享面板,点「拷贝」;返回是否出现过面板。
    @discardableResult
    func copyFromShareSheet(_ tag: String, timeout: TimeInterval = 120) -> Bool {
        guard let copy = shareSheetCopyButton(timeout: timeout) else {
            shot("\(tag)-no-sharesheet")
            dumpTree("\(tag)-no-sharesheet")
            return false
        }
        shot("\(tag)-sharesheet")
        copy.tap()
        Thread.sleep(forTimeInterval: 1.5)
        return true
    }

    func dismissShareSheet(_ tag: String, timeout: TimeInterval = 120) -> Bool {
        guard shareSheetCopyButton(timeout: timeout) != nil else { return false }
        shot("\(tag)-sharesheet")
        for attempt in 0..<4 {
            let close = app.buttons.matching(NSPredicate(format: "label IN %@", ["关闭", "Close"])).firstMatch
            if attempt < 2, close.exists {
                close.tap()
            } else {
                app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.15))
                    .press(forDuration: 0.1, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.95)))
            }
            Thread.sleep(forTimeInterval: 2)
            if shareSheetCopyButton(timeout: 0.5) == nil { return true }
        }
        shot("\(tag)-sharesheet-stuck")
        return true
    }

    /// 剪贴板经陈仓自己的输入框带出(不经备忘录,见 UATCase.captureClipboard)。调用时须在线程里。
    func clipboardViaComposer(_ tag: String) -> String {
        captureClipboard(tag)
    }

    func assertR1(_ text: String, label: String, file: StaticString = #filePath, line: UInt = #line) {
        let lines = text.components(separatedBy: "\n").filter { !$0.isEmpty }
        XCTAssertEqual(lines.count, 2, "不是两行: \(text)", file: file, line: line)
        guard lines.count >= 2 else { return }
        let first = lines[0].trimmingCharacters(in: .whitespaces)
        let pattern = "^🔒 陈仓加密\(label) · 24 小时内有效 https://[^/ ]+/m/[A-Za-z0-9_-]{22}$"
        XCTAssertNotNil(first.range(of: pattern, options: .regularExpression), "第一行不符: \(first)", file: file, line: line)
        XCTAssertTrue(lines[1].hasPrefix("🔒"), "第二行不是 🔒 开头", file: file, line: line)
    }

    // MARK: - 0 冷启动

    func test00ColdLaunchNoPrompt() {
        app.resetAuthorizationStatus(for: .microphone)
        app.resetAuthorizationStatus(for: .camera)
        app.resetAuthorizationStatus(for: .photos)
        launchInChinese()
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 20))
        Thread.sleep(forTimeInterval: 5)
        shot("item0-coldlaunch")
        XCTAssertFalse(systemAlertPresent(), "冷启动出现系统弹窗: \(springboard.alerts.firstMatch.label)")
        openThread(peer)
        Thread.sleep(forTimeInterval: 2)
        XCTAssertFalse(systemAlertPresent(), "进线程出现系统弹窗")
        shot("item0-thread")
    }

    // MARK: - 1/2 按住说话

    func test01VoiceFirstPressAndSend() {
        app.resetAuthorizationStatus(for: .microphone)
        launchApp()
        openThread(peer)
        enterVoiceMode()
        Thread.sleep(forTimeInterval: 1)
        XCTAssertFalse(systemAlertPresent(), "未按下就出现了权限弹窗")
        shot("item1-before-press")
        let before = voiceCount

        holdBar.press(forDuration: 1.5)
        let title = acceptSystemAlert(timeout: 10)
        record("item1-mic-alert", title ?? "<none>")
        XCTAssertNotNil(title, "首次按下没有弹麦克风权限")
        XCTAssertTrue((title ?? "").contains("麦克风") || (title ?? "").lowercased().contains("microphone"))
        Thread.sleep(forTimeInterval: 2)
        XCTAssertEqual(voiceCount, before, "授权那一次不应发出语音")

        holdBar.press(forDuration: 5.5)
        let bubble = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "语音 ")).element(boundBy: max(0, voiceCount - 1))
        XCTAssertTrue(anyElement(labelContains: "语音").waitForExistence(timeout: 5))
        shot("item1-after-release")
        XCTAssertEqual(voiceCount, before + 1, "松手后没有新语音气泡")
        record("item1-voice-label", bubble.label)
        let ringSeen = app.descendants(matching: .any)["传输中"].exists
        record("item1-progress-ring-seen", "\(ringSeen)")

        XCTAssertTrue(copyFromShareSheet("item1"), "加密后没有自动弹分享面板")
        // 状态行只说用户做过的事:点「拷贝」报告完成 → 「已分享」;没完成则停在「已加密 · 还没发」。
        let sealed = anyElement(labelContains: "已分享").exists || anyElement(labelContains: "已加密 · 还没发").exists
        shot("item1-after-copy")
        record("item1-status-label-seen", "\(sealed)")

        let text = clipboardViaComposer("item2-voice-share")
        assertR1(text, label: "语音")
    }

    // MARK: - 3 太短 / 上滑取消 / 倒计时 / 60 秒

    func test03VoiceEdgeCases() {
        launchApp()
        openThread(peer)
        enterVoiceMode()
        let before = voiceCount

        holdBar.press(forDuration: 0.5)
        let tooShort = anyElement(labelContains: "说话时间太短")
        let tooShortSeen = tooShort.waitForExistence(timeout: 2)
        shot("item3-too-short")
        record("item3-too-short-seen", "\(tooShortSeen)")
        XCTAssertTrue(tooShortSeen, "0.5 秒没提示「说话时间太短」")
        Thread.sleep(forTimeInterval: 2)
        XCTAssertEqual(voiceCount, before, "太短的语音不应发出")

        let start = holdBar.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
        let up = start.withOffset(CGVector(dx: 0, dy: -300))
        start.press(forDuration: 2, thenDragTo: up, withVelocity: .slow, thenHoldForDuration: 2)
        Thread.sleep(forTimeInterval: 2)
        shot("item3-after-cancel")
        XCTAssertEqual(voiceCount, before, "上滑取消后不应发出")

        // 按住 64 秒:52 秒起应有「还可以说 N 秒」,60 秒自动发送(过程看系统录屏)
        let t0 = Date()
        holdBar.press(forDuration: 64)
        record("item3-long-press-seconds", String(format: "%.1f", Date().timeIntervalSince(t0)))
        Thread.sleep(forTimeInterval: 2)
        shot("item3-after-60s")
        // 自动发送后分享面板盖在线程上,气泡要等面板收起后再数(面板在台上时线程树不全)
        XCTAssertTrue(dismissShareSheet("item3-60s"), "60 秒自动发送后没弹分享面板")
        Thread.sleep(forTimeInterval: 1.5)
        // LazyVStack 只把屏幕上的行放进树:语音气泡占满一屏后计数不再增长,改判最后一条是 60″
        let last = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "语音 ")).element(boundBy: max(0, voiceCount - 1))
        record("item3-60s-label", last.label)
        XCTAssertTrue(voiceCount == before + 1 || last.label.hasPrefix("语音 60") || last.label.hasPrefix("语音 59"),
                      "60 秒没有自动发送出一条语音(最后一条: \(last.label))")
    }

    // MARK: - 4 三张图

    func test04ThreeImages() {
        launchApp()
        openThread(peer)
        let before = imageCount
        openPlus("相册")
        Thread.sleep(forTimeInterval: 3)
        shot("item4-picker")
        dumpTree("item4-picker")
        let photos = pickerCells(matching: "Photo,", count: 3)
        XCTAssertEqual(photos.count, 3, "PHPicker 里找不到 3 张照片")
        photos.forEach { $0.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap() }
        shot("item4-picked")
        tapPickerAdd()
        XCTAssertTrue(copyFromShareSheet("item4"), "3 张图加密后没弹分享面板")
        shot("item4-thread")
        // LazyVStack 只把屏幕上的行放进树,大图气泡一屏只放得下约 3 个:不再比「前后差 3」,R1 首行「3 张图片」为准
        record("item4-image-count", "\(before) -> \(imageCount)")
        XCTAssertGreaterThanOrEqual(imageCount, 3, "没出现 3 个图片气泡")
        let text = clipboardViaComposer("item4-image-share")
        assertR1(text, label: "3 张图片")
    }

    // MARK: - 5 视频

    func test05Videos() {
        launchApp()
        openThread(peer)
        let before = videoCount
        openPlus("相册")
        Thread.sleep(forTimeInterval: 3)
        let long = pickerCells(matching: "Video, one minute, ten seconds", count: 1).first
        XCTAssertNotNil(long, "找不到 70 秒视频")
        long?.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        tapPickerAdd()
        let alert = app.alerts.firstMatch
        XCTAssertTrue(alert.waitForExistence(timeout: 60), "超长视频没有提示")
        let alertText = alert.staticTexts.allElementsBoundByIndex.map(\.label).joined(separator: " | ")
        record("item5-long-alert", alertText)
        shot("item5-long-alert")
        XCTAssertTrue(alertText.contains("视频不能超过 60 秒"))
        alert.buttons.firstMatch.tap()
        Thread.sleep(forTimeInterval: 2)
        XCTAssertEqual(videoCount, before, "超长视频不应产生消息")

        openPlus("相册")
        Thread.sleep(forTimeInterval: 3)
        let short = pickerCells(matching: "Video, twenty seconds", count: 1).first
        XCTAssertNotNil(short, "找不到 20 秒视频")
        short?.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        tapPickerAdd()
        XCTAssertTrue(copyFromShareSheet("item5", timeout: 180), "20 秒视频没弹分享面板")
        shot("item5-thread")
        XCTAssertEqual(videoCount, before + 1)
        record("item5-video-label", app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "视频 ")).element(boundBy: max(0, videoCount - 1)).label)
        let text = clipboardViaComposer("item5-video-share")
        assertR1(text, label: "视频")
    }

    /// PHPicker 在 App 的元素树里(iPhone 15 系统语言为英文):网格格子是 Image,标签形如
    /// 「Photo, September 25, 21:44」「Video, one minute, ten seconds, …」。最新的素材在最前。
    ///
    /// 只返回「本批 runner 素材」:格子标签里的拍摄时刻(`September 30, 17:18`)必须不早于
    /// `Documents/seeded-at.txt`(`UATMediaSeedTests` 开造时写)所在的那一分钟,也不晚于现在。
    /// 前 `count` 个里只要有一个不新鲜就整批拒绝(返回空)——宁可用例失败,也不误选机主的个人照片。
    func pickerCells(matching text: String, count: Int) -> [XCUIElement] {
        let query = app.images.matching(NSPredicate(format: "label BEGINSWITH %@ AND identifier == %@",
                                                    text, "PXGGridLayout-Info"))
        _ = query.firstMatch.waitForExistence(timeout: 10)
        let cells = Array(query.allElementsBoundByIndex.prefix(count))
        let labels = cells.map(\.label)
        record("picker-cells", labels.joined(separator: "\n"))
        guard let raw = try? String(contentsOf: Self.documents.appendingPathComponent("seeded-at.txt"), encoding: .utf8),
              let seededAt = TimeInterval(raw.trimmingCharacters(in: .whitespacesAndNewlines)) else {
            XCTFail("没有 seeded-at.txt:先跑 UATMediaSeedTests 造素材再选图")
            return []
        }
        let floor = Date(timeIntervalSince1970: seededAt - seededAt.truncatingRemainder(dividingBy: 60))
        let stale = labels.filter { label in
            guard let date = Self.pickerDate(label) else { return true }
            return date < floor || date > Date().addingTimeInterval(60)
        }
        guard stale.isEmpty else {
            XCTFail("选图格子不是本批素材(早于 seeded-at),拒绝点选: \(stale)")
            return []
        }
        return cells
    }

    /// 从「Photo, September 30, 17:18」里解析出今天年份下的时刻(本地时区)。
    static func pickerDate(_ label: String) -> Date? {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        let year = Calendar.current.component(.year, from: Date())
        // 两种地区写法:「October 1, 5:00」与「01 October, 05:00」(iPhone 12 的地区设置是日在前)
        for (pattern, format) in [("[A-Z][a-z]+ [0-9]{1,2}, [0-9]{1,2}:[0-9]{2}", "yyyy MMMM d, H:mm"),
                                  ("[0-9]{1,2} [A-Z][a-z]+, [0-9]{1,2}:[0-9]{2}", "yyyy d MMMM, H:mm")] {
            guard let range = label.range(of: pattern, options: .regularExpression) else { continue }
            formatter.dateFormat = format
            if let date = formatter.date(from: "\(year) \(label[range])") { return date }
        }
        return nil
    }

    func tapPickerAdd() {
        for label in ["添加", "Add", "完成", "Done"] {
            let button = app.buttons[label].firstMatch
            if button.exists && button.isEnabled {
                button.tap()
                return
            }
        }
        dumpTree("picker-no-add")
        XCTFail("PHPicker 找不到「添加」")
    }

    // MARK: - 6 拍摄

    func test06Camera() {
        app.resetAuthorizationStatus(for: .camera)
        launchApp()
        openThread(peer)
        let imagesBefore = imageCount
        let videosBefore = videoCount
        Thread.sleep(forTimeInterval: 1)
        XCTAssertFalse(systemAlertPresent())
        openPlus("拍摄")
        let title = acceptSystemAlert(timeout: 10)
        record("item6-camera-alert", title ?? "<none>")
        XCTAssertNotNil(title, "首次点拍摄没弹相机权限")
        Thread.sleep(forTimeInterval: 3)
        shot("item6-camera")
        dumpTree("item6-camera")
        tapFirst(["拍照", "Take Picture", "PhotoCapture"])
        Thread.sleep(forTimeInterval: 3)
        shot("item6-photo-review")
        tapFirst(["使用照片", "Use Photo", "Done", "完成"])
        XCTAssertTrue(dismissShareSheet("item6-photo", timeout: 120), "拍照后没弹分享面板")
        // LazyVStack 只把屏上的行放进树(同 test04):前后计数只记录,不做判定;以分享面板出现 + 截图为准
        record("item6-image-count", "\(imagesBefore) -> \(imageCount)")
        XCTAssertGreaterThanOrEqual(imageCount, 1)

        openPlus("拍摄")
        Thread.sleep(forTimeInterval: 3)
        let modeVideo = app.descendants(matching: .any).matching(NSPredicate(format: "label IN %@", ["视频", "Video", "VIDEO"])).firstMatch
        if modeVideo.exists { modeVideo.tap() } else { app.swipeRight() }
        Thread.sleep(forTimeInterval: 2)
        shot("item6-video-mode")
        dumpTree("item6-video-mode")
        tapFirst(["录制视频", "开始录制视频", "Record Video", "Start Recording", "VideoCapture"])
        Thread.sleep(forTimeInterval: 10)
        tapFirst(["停止录制视频", "停止录制", "Stop Recording", "Stop Recording Video", "VideoCapture"])
        Thread.sleep(forTimeInterval: 3)
        shot("item6-video-review")
        tapFirst(["使用视频", "Use Video", "Done", "完成"])
        XCTAssertTrue(dismissShareSheet("item6-video", timeout: 180), "录像后没弹分享面板")
        record("item6-video-count", "\(videosBefore) -> \(videoCount)")
        // 最新一条在最底下:按 frame 取最靠下的视频气泡(树里的顺序不保证是时间顺序)
        let newest = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "视频 ")).allElementsBoundByIndex
            .filter { $0.frame.height > 40 }.max { $0.frame.maxY < $1.frame.maxY }
        record("item6-video-label", newest?.label ?? "<none>")
        XCTAssertNotNil(newest, "录像后线程底部没有视频气泡")
    }

    func tapFirst(_ labels: [String], timeout: TimeInterval = 8) {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            for label in labels {
                let byLabel = app.buttons[label].firstMatch
                if byLabel.exists && byLabel.isHittable {
                    byLabel.tap()
                    return
                }
            }
            Thread.sleep(forTimeInterval: 0.3)
        }
        dumpTree("missing-\(labels.first ?? "")")
        XCTFail("找不到按钮 \(labels)")
    }

    // MARK: - 7 断网重试

    func setAirplane(_ on: Bool) {
        springboard.activate()
        let top = springboard.coordinate(withNormalizedOffset: CGVector(dx: 0.9, dy: 0.005))
        top.press(forDuration: 0.1, thenDragTo: springboard.coordinate(withNormalizedOffset: CGVector(dx: 0.9, dy: 0.7)))
        let toggle = springboard.buttons.matching(NSPredicate(format: "label IN %@ OR identifier == %@", ["飞行模式", "Airplane Mode"],
                                                              "airplane-mode-button")).firstMatch
        XCTAssertTrue(toggle.waitForExistence(timeout: 5), "控制中心找不到飞行模式")
        let current = (toggle.value as? String) == "1" || (toggle.value as? Int) == 1
        if current != on { toggle.tap() }
        Thread.sleep(forTimeInterval: 1)
        shot("item7-airplane-\(on ? "on" : "off")")
        record("item7-airplane-\(on ? "on" : "off")-value", "\(toggle.value ?? "nil")")
        springboard.swipeUp()
        app.activate()
        _ = app.wait(for: .runningForeground, timeout: 10)
    }

    func test07aOfflineSendFails() {
        launchApp()
        openThread(peer)
        let before = imageCount
        setAirplane(true)
        openPlus("相册")
        Thread.sleep(forTimeInterval: 3)
        pickerCells(matching: "Photo,", count: 1).first?.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        tapPickerAdd()
        let retry = app.buttons["发送失败，请重试"].firstMatch
        let failed = retry.waitForExistence(timeout: 120)
        shot("item7-failed")
        XCTAssertTrue(failed, "断网发图没有出现红 !")
        XCTAssertEqual(imageCount, before + 1)
        XCTAssertFalse(shareSheetPresent(), "断网时不应弹分享面板")
        setAirplane(false)
    }

    func test07bRetrySucceeds() {
        launchApp()
        openThread(peer)
        // 等 Wi-Fi 重连
        Thread.sleep(forTimeInterval: 10)
        let retry = app.buttons["发送失败，请重试"].firstMatch
        XCTAssertTrue(retry.waitForExistence(timeout: 10), "重启后红 ! 不见了")
        shot("item7-before-retry")
        retry.tap()
        XCTAssertTrue(copyFromShareSheet("item7-retry", timeout: 120), "重试后没弹分享面板")
        XCTAssertFalse(app.buttons["发送失败，请重试"].exists)
        let text = clipboardViaComposer("item7-retry-share")
        assertR1(text, label: env("UAT_RETRY_LABEL") ?? "图片")
    }

    // MARK: - 9 长按

    func test09LongPressMenu() {
        launchApp()
        openThread(peer)
        let image = app.buttons.matching(NSPredicate(format: "label == %@", "图片"))
        let count = image.count
        XCTAssertGreaterThan(count, 0)
        let last = image.element(boundBy: count - 1)

        last.press(forDuration: 1.2)
        shot("item9-menu")
        app.buttons["复制加密消息"].tap()
        let text = clipboardViaComposer("item9-copy-wire")
        let lines = text.components(separatedBy: "\n").filter { !$0.isEmpty }
        XCTAssertEqual(lines.count, 2, "复制加密消息不是两行")

        let forwardTarget = env("UAT_FORWARD_TO") ?? "联系人 9a44de"
        if forwardTarget == "-" {
            record("item9-forward", "skipped: 本机只有一个联系人,无转发对象")
        } else {
        last.press(forDuration: 1.2)
        app.buttons["转发"].tap()
        let target = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", forwardTarget)).firstMatch
        XCTAssertTrue(target.waitForExistence(timeout: 5), "转发列表里没有 \(forwardTarget)")
        shot("item9-forward-picker")
        target.tap()
        XCTAssertTrue(copyFromShareSheet("item9-forward", timeout: 120), "转发后没弹分享面板")
        let fwd = clipboardViaComposer("item9-forward-share")
        assertR1(fwd, label: "图片")
        XCTAssertNotEqual(fwd.components(separatedBy: "\n").first, lines.first, "转发应是新 blob")
        }

        let before = image.count
        let delete = app.buttons["删除"].firstMatch
        for _ in 0..<3 where !delete.exists {
            image.element(boundBy: image.count - 1).press(forDuration: 1.5)
            _ = delete.waitForExistence(timeout: 4)
        }
        shot("item9-delete-menu")
        delete.tap()
        Thread.sleep(forTimeInterval: 2)
        shot("item9-after-delete")
        XCTAssertLessThan(image.count, before, "删除后气泡还在")
        record("item9-image-count", "\(before) -> \(image.count)")
    }

    // MARK: - 10 关于

    func test10About() {
        launchApp()
        backToList()
        let version = app.staticTexts["1.0 (4)"]
        selectTab("我")      // 设置已并入「我」tab,不再有会话页的齿轮
        for _ in 0..<5 where !version.exists { swipeContentUp() }
        XCTAssertTrue(version.waitForExistence(timeout: 5), "版本号行不是 1.0 (4)")
        shot("item10-about")
        app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "语音图片怎么传的")).firstMatch.tap()
        Thread.sleep(forTimeInterval: 6)
        shot("item10-safari")
        let url = app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@ OR value CONTAINS %@",
                                                                       "cloudfront.net", "cloudfront.net")).firstMatch
        XCTAssertTrue(url.waitForExistence(timeout: 10), "Safari 视图里看不到分享站点域名(出厂配置单 cloudfront.net)")
        record("item10-safari-url", "\(url.label) \(url.value ?? "")")
        dumpTree("item10-safari")
    }

    // MARK: - 只读查看一个线程(清空前先看里面有什么)

    func testZPeekThread() {
        launchApp()
        openThread(peer)
        Thread.sleep(forTimeInterval: 2)
        for i in 0..<12 {
            shot("peek-\(i)")
            let labels = app.buttons.allElementsBoundByIndex.map(\.label).filter { !$0.isEmpty }
            record("peek-\(i)", labels.joined(separator: " | "))
            app.scrollViews.firstMatch.swipeDown()
            Thread.sleep(forTimeInterval: 0.8)
        }
    }

    // MARK: - 11 清空消息

    func test11ClearMessages() {
        launchApp()
        openThread(peer)
        app.navigationBars.buttons.matching(NSPredicate(format: "label CONTAINS %@", peer)).firstMatch.tap()
        let clear = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "清空消息")).firstMatch
        for _ in 0..<4 where !clear.isHittable { app.swipeUp() }
        clear.tap()
        let confirm = app.buttons["清空"].firstMatch
        XCTAssertTrue(confirm.waitForExistence(timeout: 5))
        confirm.tap()
        Thread.sleep(forTimeInterval: 2)
        shot("item11-cleared")
        app.navigationBars.buttons.element(boundBy: 0).tap()
        Thread.sleep(forTimeInterval: 1)
        shot("item11-thread-empty")
        XCTAssertEqual(voiceCount + imageCount + videoCount, 0, "清空后线程里还有媒体气泡")
    }

    // MARK: - 终审 F2:背靠背连发语音

    /// iPhone 12 首跑时松手后主线程在线程列表布局里空转约 3 分钟(F2)。在已经有 40+ 条消息的
    /// UAT 线程里连发 `UAT_BURST`(默认 10)条语音:每条按 2 秒 → 松手 → 必须在 60 秒内弹分享面板并拷贝,
    /// 之后「更多」按钮 10 秒内可用;整轮平均每条 ≤ 30 秒。任何一次主线程卡住,XCUITest 拿不到快照,
    /// 这里就会超时失败。建议照 UAT 那次加 spindump 抓证据(uat-run.sh 保留系统附件)。
    func test12VoiceBurstBackToBack() {
        let count = Int(env("UAT_BURST") ?? "") ?? 10
        launchApp()
        openThread(peer)
        enterVoiceMode()
        let start = Date()
        var perNote: [String] = []
        for i in 0..<count {
            let t = Date()
            holdBar.press(forDuration: 2.0)
            let shared = copyFromShareSheet("item12-\(i)", timeout: 60)
            perNote.append(String(format: "%.1f", Date().timeIntervalSince(t)))
            XCTAssertTrue(shared, "第 \(i + 1) 条语音松手后 60 秒内没弹分享面板(主线程卡住?)")
            guard shared else { break }
            XCTAssertTrue(holdBar.waitForExistence(timeout: 10), "第 \(i + 1) 条之后输入栏不可用")
        }
        let total = Date().timeIntervalSince(start)
        record("item12-burst", "count=\(count) total=\(String(format: "%.1f", total))s perNote=\(perNote.joined(separator: ","))")
        shot("item12-after-burst")
        XCTAssertLessThan(total, Double(count) * 30, "连发 \(count) 条用了 \(total) 秒")
    }
}
