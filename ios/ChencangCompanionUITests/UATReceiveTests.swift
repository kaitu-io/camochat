import XCTest

/// 收件侧(brief 第 8 项):把对端 R1 两行文本(`UAT_TEXT`,由发送端经 runner 容器带出的真实分享文本)
/// 键入陈仓线程输入框 → 全选 → 共享 →「陈仓解密」→ 卡片「收到加密…」「打开陈仓查看」→ 进 App 验证气泡。
/// runner 在后台写不了系统剪贴板,所以文本是键入而不是粘贴;Action Extension 收到的仍是这段真实文本。
final class UATReceiveTests: UATCase {

    func testReceiveViaAction() {
        guard let text = env("UAT_TEXT"), let expect = env("UAT_EXPECT"), let kind = env("UAT_KIND") else {
            XCTFail("缺 UAT_TEXT / UAT_EXPECT / UAT_KIND")
            return
        }
        let tag = "item8-\(kind)"
        if kind == "image" { app.resetAuthorizationStatus(for: .photos) }

        // 1–4. 键进陈仓自己线程的输入框 → 全选 → 共享 →「陈仓解密」→ 卡片 →「打开陈仓查看」
        //      (2026-10-01 起不经备忘录:备忘录会同步 iCloud,加密消息不能进机主的数据)
        //      `UAT_COMPOSE_THREAD`:在哪个线程的输入框里键入(默认对端线程)。扩展按发件人身份归位,与键入处无关。
        launchApp()
        openThread(env("UAT_COMPOSE_THREAD") ?? env("UAT_PEER_NAME") ?? "UAT-15")
        _ = deliverToDecryptAction(text, expect: expect, tag: tag)

        // 5. 进 App
        let opened = app.wait(for: .runningForeground, timeout: 15)
        record("\(tag)-open-in-app-worked", "\(opened)")
        shot("\(tag)-after-open-tap")
        XCTAssertTrue(opened, "点「打开陈仓查看」没有切到陈仓(ActionViewController.openHostApp,终审 F1)")
        if !opened {
            // 直达失败时卡片必须留着并提示手动打开,而不是悄悄关掉
            let hint = app.descendants(matching: .any)
                .matching(NSPredicate(format: "label CONTAINS %@", "请手动打开陈仓查看")).firstMatch
            let hintShown = hint.waitForExistence(timeout: 5)
            record("\(tag)-manual-open-hint-shown", "\(hintShown)")
            shot("\(tag)-manual-open-hint")
            XCTAssertTrue(hintShown, "拉不起主 App 时卡片应提示「请手动打开陈仓查看」")
            // 直达失败:按 spec 兜底,用户手动切回陈仓(收件箱已写),进对方线程
            app.activate()
            XCTAssertTrue(app.wait(for: .runningForeground, timeout: 20))
            Thread.sleep(forTimeInterval: 2)
            passOnboardingIfNeeded()
            openThread(env("UAT_PEER_NAME") ?? "UAT-15")
        }
        Thread.sleep(forTimeInterval: 3)
        shot("\(tag)-app-landed")
        switch kind {
        case "voice": verifyVoice(tag)
        case "image": verifyImages(tag)
        case "video": verifyVideo(tag)
        default: XCTFail("未知 kind \(kind)")
        }
    }

    /// 已经经扩展归入收件箱的消息:直接开 App 进对方线程验证(同一条加密消息扩展只能解密一次)。
    func testVerifyInApp() {
        let kind = env("UAT_KIND") ?? "voice"
        let tag = "item8-\(kind)-inapp"
        launchApp()
        openThread(env("UAT_PEER_NAME") ?? "UAT-15")
        Thread.sleep(forTimeInterval: 2)
        shot("\(tag)-thread")
        switch kind {
        case "voice": verifyVoice(tag)
        case "image": verifyImages(tag)
        default: verifyVideo(tag)
        }
    }

    // MARK: - App 内验证

    func lastButton(_ prefix: String) -> XCUIElement {
        let q = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", prefix))
        return q.element(boundBy: max(0, q.count - 1))
    }

    func verifyVoice(_ tag: String) {
        let bubble = lastButton("语音 ")
        XCTAssertTrue(bubble.waitForExistence(timeout: 10), "线程里没有语音气泡")
        let dot = app.descendants(matching: .any)["未播放"].firstMatch
        let dotSeen = dot.waitForExistence(timeout: 60)
        shot("\(tag)-downloaded")
        record("\(tag)-unread-dot-seen", "\(dotSeen) label=\(bubble.label)")
        XCTAssertTrue(dotSeen, "自动下载后没有红点")
        bubble.tap()
        Thread.sleep(forTimeInterval: 2)
        shot("\(tag)-playing")
        XCTAssertFalse(app.descendants(matching: .any)["未播放"].exists, "播放后红点没消失")
    }

    func verifyImages(_ tag: String) {
        let images = app.buttons.matching(NSPredicate(format: "label == %@", "图片"))
        XCTAssertTrue(images.firstMatch.waitForExistence(timeout: 10))
        Thread.sleep(forTimeInterval: 20)   // 自动下载 + 缩略图
        shot("\(tag)-thumbnails")
        record("\(tag)-image-count", "\(images.count)")
        let last = images.element(boundBy: images.count - 1)
        last.tap()
        let save = app.buttons["保存到相册"]
        XCTAssertTrue(save.waitForExistence(timeout: 10), "没打开全屏看图(还没下载完?)")
        shot("\(tag)-viewer")
        app.windows.firstMatch.pinch(withScale: 2.5, velocity: 2)
        Thread.sleep(forTimeInterval: 1)
        shot("\(tag)-viewer-zoomed")
        save.tap()
        let title = acceptSystemAlert(timeout: 10)
        record("\(tag)-photo-add-alert", title ?? "<none>")
        XCTAssertNotNil(title, "首次保存没有弹相册写入权限")
        let saved = app.alerts.firstMatch
        XCTAssertTrue(saved.waitForExistence(timeout: 10))
        record("\(tag)-save-result", saved.label)
        shot("\(tag)-saved")
        XCTAssertTrue(saved.label.contains("已保存到相册"))
        saved.buttons.firstMatch.tap()
        // 第二次保存不应再弹权限
        save.tap()
        Thread.sleep(forTimeInterval: 2)
        XCTAssertFalse(systemAlertPresent(), "第二次保存又弹了权限")
        if app.alerts.firstMatch.exists { app.alerts.firstMatch.buttons.firstMatch.tap() }
        app.buttons["关闭"].tap()
    }

    func verifyVideo(_ tag: String) {
        let bubble = lastButton("视频 ")
        XCTAssertTrue(bubble.waitForExistence(timeout: 10))
        app.scrollViews.firstMatch.swipeUp()
        Thread.sleep(forTimeInterval: 3)
        shot("\(tag)-before-tap")   // 视频不自动下载
        tapBubble(bubble)
        Thread.sleep(forTimeInterval: 20)  // 下载;下载完应自动打开一次播放器(VideoAutoPlay.itemToOpen)
        shot("\(tag)-after-tap")
        let autoOpened = playerPresent()
        record("\(tag)-auto-opened-after-download", "\(autoOpened)")
        XCTAssertTrue(autoOpened, "点视频下载完应自动打开播放器一次(R9,终审 F3)")
        if !autoOpened {
            tapBubble(lastButton("视频 "))   // 已下载:点击全屏播放
            Thread.sleep(forTimeInterval: 4)
        }
        shot("\(tag)-player")
        dumpTree("\(tag)-player")
        let played = playerPresent()
        record("\(tag)-player-seen", "\(played)")
        XCTAssertTrue(played, "点击已下载的视频没有打开播放器")
        app.swipeDown(velocity: .fast)
        Thread.sleep(forTimeInterval: 2)
        shot("\(tag)-poster")
    }

    /// 视频气泡的可访问元素只剩右下角时长胶囊那一小块,点它的左上方(气泡主体)。
    func tapBubble(_ bubble: XCUIElement) {
        bubble.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).withOffset(CGVector(dx: -60, dy: -40)).tap()
    }

    /// VideoPlayerSheet 是 AVPlayerViewController:出现「完成/Done/关闭」或播放控件即视为打开。
    func playerPresent() -> Bool {
        let labels = ["Done", "完成", "Close", "关闭", "Play", "Pause", "播放", "暂停", "Close video", "Video"]
        return app.buttons.matching(NSPredicate(format: "label IN %@", labels)).firstMatch.exists
            || app.otherElements.matching(NSPredicate(format: "identifier CONTAINS[c] %@", "AVPlayer")).firstMatch.exists
    }
}
