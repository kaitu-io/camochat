import XCTest

/// 富媒体打磨真机 UAT(spec 2026-09-30 §7):分享取消不标 / 完成才标、带 GPS 的图与视频发送、
/// 纯黑看图、转发单张 / 全部、视频全屏黑底 + 关闭。挂在 `UATSendTests` 上复用它的选择器与分享面板助手,
/// 跑法同上:`scripts/uat-run.sh ip15 UATSendTests/testP1TextShareCancelThenComplete UAT_PEER_NAME=…`。
extension UATSendTests {

    var composeField: XCUIElement {
        app.descendants(matching: .any)
            .matching(NSPredicate(format: "placeholderValue == %@ OR label == %@", "写点什么…", "写点什么…"))
            .firstMatch
    }

    var cardShareButton: XCUIElement { app.buttons["分享"].firstMatch }

    func labelCount(_ label: String) -> Int {
        app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", label)).count
    }

    /// 取像素里「最亮通道」,纯黑应接近 0。
    func maxChannel(_ px: [(r: Int, g: Int, b: Int)]) -> Int {
        px.map { max($0.r, $0.g, $0.b) }.max() ?? 255
    }

    // MARK: - P1 文字:点「加密」直接弹分享;取消不标,完成才标

    func testP1TextShareCancelThenComplete() {
        launchApp()
        openThread(peer)
        if app.buttons["切换到键盘"].exists { app.buttons["切换到键盘"].tap() }
        let text = "UAT 打磨 \(Int(Date().timeIntervalSince1970) % 100000)"
        typeInto(composeField, text)
        // 本条之前线程里已有的状态行数(含历史消息),后面只比增量。
        let notSentBefore = labelCount("已加密 · 还没发")
        let sharedBefore = labelCount("已分享")
        record("p1-counts-before", "已加密 · 还没发=\(notSentBefore) 已分享=\(sharedBefore)")
        let encrypt = app.buttons["加密"].firstMatch
        XCTAssertTrue(encrypt.waitForExistence(timeout: 5), "没有「加密」键")
        encrypt.tap()

        // 1) 点「加密」直接弹分享面板(偏好不是「复制」时)→ 取消
        XCTAssertTrue(dismissShareSheet("p1-cancel", timeout: 15), "点「加密」没有直接弹分享面板")
        Thread.sleep(forTimeInterval: 1.5)
        shot("p1-after-cancel")
        let notSent = anyElement(labelContains: "还没发 · 点这里再发")
        XCTAssertTrue(notSent.waitForExistence(timeout: 5), "取消后卡片没显示「还没发 · 点这里再发」")
        XCTAssertTrue(cardShareButton.exists, "取消后卡片不见了")
        XCTAssertEqual(labelCount("已分享"), sharedBefore, "取消分享却标了已分享")
        XCTAssertEqual(labelCount("已加密 · 还没发"), notSentBefore + 1, "取消后这条应停在「已加密 · 还没发」")
        record("p1-counts-after-cancel", "已加密 · 还没发=\(labelCount("已加密 · 还没发")) 已分享=\(labelCount("已分享"))")

        // 2) 再分享 → 面板里「拷贝」(一个会报告完成的活动)
        cardShareButton.tap()
        XCTAssertTrue(copyFromShareSheet("p1-complete", timeout: 15), "再次分享没弹面板")
        let toast = app.descendants(matching: .any)["已分享"].firstMatch
        let toastSeen = toast.waitForExistence(timeout: 3)
        shot("p1-after-complete")
        record("p1-toast-seen", "\(toastSeen)")
        Thread.sleep(forTimeInterval: 2.5)   // 轻提示淡出后再数气泡状态
        shot("p1-after-complete-settled")
        XCTAssertFalse(cardShareButton.exists, "完成后加密卡没收起")
        XCTAssertFalse(anyElement(labelContains: "还没发 · 点这里再发").exists)
        let notSentAfter = labelCount("已加密 · 还没发"), sharedAfter = labelCount("已分享")
        record("p1-counts-after-complete", "已加密 · 还没发=\(notSentAfter) 已分享=\(sharedAfter)")
        XCTAssertEqual(notSentAfter, notSentBefore, "完成后这条没从「已加密 · 还没发」变走")
        XCTAssertEqual(sharedAfter, sharedBefore + 1, "完成后没多一个「已分享」")

        let shared = clipboardViaComposer("p1-text-share")
        let first = shared.components(separatedBy: "\n").first { !$0.isEmpty } ?? ""
        // 文字分享首行 = `card_share_header_text`:「🔒 陈仓加密消息 · 用陈仓解密 〈链接〉」。
        XCTAssertNotNil(first.trimmingCharacters(in: .whitespaces)
                            .range(of: "^🔒 陈仓加密消息 · 用陈仓解密 https://[^/ ]+/m/$", options: .regularExpression),
                        "文字分享首行不符: \(first)")
    }

    // MARK: - P7 分享面板:completionWithItemsHandler 与 .sheet onDismiss 的先后

    /// 陈仓不打日志,这两个回调的顺序只能从 UI 间接看:ThreadViewModel 里 onDismiss 先到且此前没收到
    /// completed=true,卡片会先转「还没发 · 点这里再发」,等 true 晚到才标「已分享」、卡片收起。所以点「拷贝」后
    /// 高频轮询:见过「还没发」→ onDismiss 先于 completion(true);卡片直接收起、从未见「还没发」→ completion 先到
    /// (或两者落在同一帧,轮询分辨不出)。轮询粒度是一次无障碍快照(约 0.1–0.3 秒)。
    func testP7ShareCallbackOrder() {
        launchApp()
        openThread(peer)
        var observations: [String] = []
        for round in 0..<3 {
            if app.buttons["切换到键盘"].exists { app.buttons["切换到键盘"].tap() }
            typeInto(composeField, "UAT 顺序 \(round) \(Int(Date().timeIntervalSince1970) % 100000)")
            app.buttons["加密"].firstMatch.tap()   // 点「加密」直接弹分享面板
            guard let copy = shareSheetCopyButton(timeout: 15) else {
                XCTFail("没弹分享面板")
                return
            }
            copy.tap()
            let t0 = Date()
            var seq: [String] = []
            while Date().timeIntervalSince(t0) < 4 {
                let notSent = anyElement(labelContains: "还没发 · 点这里再发").exists
                let card = cardShareButton.exists
                let sheet = shareSheetCopyButton(timeout: 0) != nil
                let state = "sheet=\(sheet ? 1 : 0) card=\(card ? 1 : 0) notSent=\(notSent ? 1 : 0)"
                if seq.last?.hasSuffix(state) != true {
                    seq.append(String(format: "+%.2fs ", Date().timeIntervalSince(t0)) + state)
                }
                if !card && !sheet { break }
            }
            shot("p7-round\(round)-settled")
            let sawNotSent = seq.contains { $0.hasSuffix("notSent=1") }
            observations.append("round\(round) sawNotSentTransient=\(sawNotSent) | " + seq.joined(separator: " → "))
            XCTAssertFalse(cardShareButton.waitForExistence(timeout: 3), "拷贝后卡片最终没收起")
            Thread.sleep(forTimeInterval: 2)
        }
        record("p7-callback-order", observations.joined(separator: "\n"))
    }

    // MARK: - P2/P3 带 GPS 的图片 / 视频

    func testP2SendGPSPhoto() {
        launchApp()
        openThread(peer)
        let before = imageCount
        openPlus("相册")
        Thread.sleep(forTimeInterval: 3)
        shot("p2-picker")
        let photo = pickerCells(matching: "Photo,", count: 1).first
        XCTAssertNotNil(photo, "PHPicker 没有照片")
        photo?.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        tapPickerAdd()
        XCTAssertTrue(copyFromShareSheet("p2", timeout: 120), "GPS 照片加密后没弹分享面板")
        Thread.sleep(forTimeInterval: 2)
        shot("p2-thread")
        record("p2-image-count", "\(before) -> \(imageCount)")   // 懒加载列表,只作记录
        record("p2-sent-at", ISO8601DateFormatter().string(from: Date()))
    }

    func testP3SendGPSVideo() {
        launchApp()
        openThread(peer)
        let before = videoCount
        openPlus("相册")
        Thread.sleep(forTimeInterval: 3)
        dumpTree("p3-picker")
        let video = pickerCells(matching: env("UAT_VIDEO_LABEL") ?? "Video, eight seconds", count: 1).first
        XCTAssertNotNil(video, "PHPicker 里找不到 8 秒 GPS 视频")
        video?.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        tapPickerAdd()
        XCTAssertTrue(copyFromShareSheet("p3", timeout: 240), "GPS 视频加密后没弹分享面板")
        Thread.sleep(forTimeInterval: 2)
        shot("p3-thread")
        record("p3-video-count", "\(before) -> \(videoCount)")   // 懒加载列表,只作记录
        let last = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "视频 "))
            .allElementsBoundByIndex.filter { $0.frame.height > 40 }.last
        record("p3-video-label", last?.label ?? "<none>")
    }

    // MARK: - P4 看图纯黑

    func testP4ImageViewerBlack() {
        launchApp()
        openThread(peer)
        let images = app.buttons.matching(NSPredicate(format: "label == %@", "图片"))
        XCTAssertGreaterThan(images.count, 0, "线程里没有图片")
        images.element(boundBy: images.count - 1).tap()
        let close = app.buttons["关闭"].firstMatch
        XCTAssertTrue(close.waitForExistence(timeout: 10), "看图页没有「关闭」")
        XCTAssertTrue(app.buttons["转发"].firstMatch.exists, "看图页顶栏没有「转发」")
        XCTAssertTrue(app.buttons["保存到相册"].firstMatch.exists, "看图页没有「保存到相册」")
        Thread.sleep(forTimeInterval: 2)
        // 图是 4:3 横图,竖屏下上下两块留白应为背景;另取导航栏中部
        let points = [CGVector(dx: 0.5, dy: 0.2), CGVector(dx: 0.5, dy: 0.8), CGVector(dx: 0.5, dy: 0.95),
                      CGVector(dx: 0.05, dy: 0.25), CGVector(dx: 0.5, dy: 0.08)]
        let px = samplePixels("p4-viewer", at: points)
        record("p4-viewer-pixels", zip(points, px).map { "(\($0.0.dx),\($0.0.dy))=\($0.1.r),\($0.1.g),\($0.1.b)" }
            .joined(separator: " "))
        XCTAssertLessThanOrEqual(maxChannel(Array(px.prefix(4))), 8, "看图背景不是纯黑")
        dumpTree("p4-viewer")

        // 看图页「转发」→ 弹「转发给」选人面板 → 取消
        app.buttons["转发"].firstMatch.tap()
        let title = app.descendants(matching: .any)["转发给"].firstMatch
        let pickerShown = title.waitForExistence(timeout: 8)
        shot("p4-viewer-forward-picker")
        XCTAssertTrue(pickerShown, "看图页「转发」没弹选人面板")
        if app.buttons["取消"].firstMatch.exists { app.buttons["取消"].firstMatch.tap() }
        Thread.sleep(forTimeInterval: 1.5)
        XCTAssertTrue(app.buttons["更多"].waitForExistence(timeout: 5), "取消转发后没回到线程")
    }

    // MARK: - P5 转发单张 / 全部

    /// 前置:线程最后一条图片消息是 3 张相册(先跑 test04ThreeImages)。转发对象 `UAT_FORWARD_TO`,
    /// 缺省取选人面板里第一个联系人。
    func testP5ForwardSingleAndAll() {
        launchApp()
        openThread(peer)
        let images = app.buttons.matching(NSPredicate(format: "label == %@", "图片"))
        XCTAssertGreaterThanOrEqual(images.count, 3, "线程里不足 3 张图")
        let last = images.element(boundBy: images.count - 1)

        last.press(forDuration: 1.2)
        let single = app.buttons["转发"].firstMatch
        let all = app.buttons["转发全部 3 张"].firstMatch
        XCTAssertTrue(single.waitForExistence(timeout: 5), "长按相册图没有「转发」")
        XCTAssertTrue(all.exists, "长按 3 张相册里的图没有「转发全部 3 张」")
        shot("p5-menu")
        record("p5-menu-items", app.buttons.allElementsBoundByIndex.map(\.label)
            .filter { $0.hasPrefix("转发") || $0 == "删除" || $0 == "复制加密消息" }.joined(separator: " | "))

        single.tap()
        pickForwardTarget("p5-single")
        XCTAssertTrue(copyFromShareSheet("p5-single", timeout: 120), "转发单张后没弹分享面板")
        let one = clipboardViaComposer("p5-single-share")
        assertR1(one, label: "图片")

        openThread(peer)
        let images2 = app.buttons.matching(NSPredicate(format: "label == %@", "图片"))
        images2.element(boundBy: images2.count - 1).press(forDuration: 1.2)
        let all2 = app.buttons["转发全部 3 张"].firstMatch
        XCTAssertTrue(all2.waitForExistence(timeout: 5))
        all2.tap()
        pickForwardTarget("p5-all")
        XCTAssertTrue(copyFromShareSheet("p5-all", timeout: 180), "转发全部后没弹分享面板")
        let three = clipboardViaComposer("p5-all-share")
        assertR1(three, label: "3 张图片")
        XCTAssertNotEqual(one.components(separatedBy: "\n").first, three.components(separatedBy: "\n").first)

        // 单张图消息的长按菜单:只有「转发」
        if let target = env("UAT_SINGLE_CHECK_PEER") {
            openThread(target)
            let imgs = app.buttons.matching(NSPredicate(format: "label == %@", "图片"))
            if imgs.count > 3 {
                imgs.element(boundBy: imgs.count - 4).press(forDuration: 1.2)   // 转发出来的单张(倒数第 4)
                Thread.sleep(forTimeInterval: 1)
                shot("p5-single-image-menu")
                record("p5-single-image-menu", app.buttons.allElementsBoundByIndex.map(\.label)
                    .filter { $0.hasPrefix("转发") }.joined(separator: " | "))
                XCTAssertFalse(app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "转发全部")).firstMatch.exists,
                               "单张图消息不该有「转发全部」")
            }
        }
    }

    func pickForwardTarget(_ tag: String) {
        let title = app.descendants(matching: .any)["转发给"].firstMatch
        XCTAssertTrue(title.waitForExistence(timeout: 8), "没弹「转发给」")
        shot("\(tag)-picker")
        let target: XCUIElement
        if let name = env("UAT_FORWARD_TO") {
            target = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", name)).firstMatch
        } else {
            target = app.cells.firstMatch.exists ? app.cells.firstMatch : app.collectionViews.buttons.firstMatch
        }
        XCTAssertTrue(target.waitForExistence(timeout: 5), "转发列表里没有可选联系人")
        record("\(tag)-target", target.label)
        target.tap()
    }

    // MARK: - P6 视频全屏黑底 + 关闭

    func testP6VideoFullScreen() {
        launchApp()
        openThread(peer)
        let videos = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "视频 "))
            .allElementsBoundByIndex.filter { $0.frame.height > 40 }
        XCTAssertFalse(videos.isEmpty, "线程里没有视频")
        videos.last?.tap()
        let close = app.buttons["关闭"].firstMatch
        XCTAssertTrue(close.waitForExistence(timeout: 10), "视频播放页没有「关闭」")
        Thread.sleep(forTimeInterval: 1.5)
        let hiddenThread = !app.buttons["更多"].isHittable
        let frame = app.windows.firstMatch.frame
        record("p6-cover", "threadHidden=\(hiddenThread) closeFrame=\(close.frame) window=\(frame)")
        XCTAssertTrue(hiddenThread, "播放页不是全屏覆盖(线程输入栏仍可点)")
        Thread.sleep(forTimeInterval: 4)   // 等播放控件自动隐藏
        let points = [CGVector(dx: 0.5, dy: 0.22), CGVector(dx: 0.5, dy: 0.78), CGVector(dx: 0.9, dy: 0.3),
                      CGVector(dx: 0.1, dy: 0.7)]
        let px = samplePixels("p6-player", at: points)
        record("p6-player-pixels", zip(points, px).map { "(\($0.0.dx),\($0.0.dy))=\($0.1.r),\($0.1.g),\($0.1.b)" }
            .joined(separator: " "))
        XCTAssertLessThanOrEqual(maxChannel(px), 8, "播放页背景不是纯黑")
        dumpTree("p6-player")
        close.tap()
        XCTAssertTrue(app.buttons["更多"].waitForExistence(timeout: 5), "关闭后没回到线程")
        Thread.sleep(forTimeInterval: 1)
        XCTAssertTrue(app.buttons["更多"].isHittable, "关闭后线程不可用")
        shot("p6-after-close")
    }
}
