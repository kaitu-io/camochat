import XCTest

/// 先分享、后上传(spec 2026-09-30 share-before-upload)真机 UAT。
///
/// 素材先跑 `UATMediaSeedTests/testSeedShareFirstMedia`(45 秒噪点视频 + 2 张纯色图)。
/// 对端联系人名经 `UAT_PEER_NAME` 传入。宿主机据 `UATRECORD` 行做同步(例如看到 `sf-shared` 立刻
/// `devicectl` 杀进程),分享文本也经 `UATRECORD` 带出,只存本地证据,不进文档。
final class UATShareFirstTests: UATCase {

    var peer: String { env("UAT_PEER_NAME") ?? "K40" }

    static let statusWords = ["加密中", "上传中", "传输中", "对方还看不到", "文件太大", "文件无法发送", "文件丢失",
                              "已分享", "已复制 · 去粘贴", "已加密 · 还没发", "还没发 · 点这里再发", "发送失败", "等待对方上传", "还没收到文件", "已过期", "未上传"]

    /// 线程/列表里所有带状态关键字的标签(去重,保持顺序)。
    /// 只按谓词查带关键字的元素(2026-10-01 真机:分享面板在台上时枚举整棵树会 60 s 快照超时)。
    func statusLabels() -> [String] {
        let predicate = NSCompoundPredicate(orPredicateWithSubpredicates:
            Self.statusWords.map { NSPredicate(format: "label CONTAINS %@", $0) })
        var seen = Set<String>()
        return app.descendants(matching: .any).matching(predicate).allElementsBoundByIndex.compactMap { element -> String? in
            let label = element.label
            guard !label.isEmpty, !seen.contains(label) else { return nil }
            seen.insert(label)
            return label
        }
    }

    func openPlus(_ tile: String) {
        if app.buttons["切换到键盘"].exists { app.buttons["切换到键盘"].tap() }
        app.buttons["更多"].tap()
        let button = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", tile)).firstMatch
        XCTAssertTrue(button.waitForExistence(timeout: 5), "➕ 面板没有「\(tile)」")
        button.tap()
    }

    /// 同 UATSendTests.pickerCells:只点本批(seeded-at 之后)造的素材,否则整批拒绝。
    func freshPickerCell(_ prefix: String) -> XCUIElement? {
        let query = app.images.matching(NSPredicate(format: "label BEGINSWITH %@ AND identifier == %@", prefix, "PXGGridLayout-Info"))
        _ = query.firstMatch.waitForExistence(timeout: 10)
        guard query.count > 0 else { return nil }
        let cell = query.element(boundBy: 0)
        record("picker-cell", cell.label)
        guard let raw = try? String(contentsOf: Self.documents.appendingPathComponent("seeded-at.txt"), encoding: .utf8),
              let seededAt = TimeInterval(raw.trimmingCharacters(in: .whitespacesAndNewlines)),
              let date = UATSendTests.pickerDate(cell.label) else {
            XCTFail("没有 seeded-at 或解析不出格子时刻,拒绝点选")
            return nil
        }
        let floor = Date(timeIntervalSince1970: seededAt - seededAt.truncatingRemainder(dividingBy: 60))
        guard date >= floor, date <= Date().addingTimeInterval(60) else {
            XCTFail("格子不是本批素材,拒绝点选: \(cell.label)")
            return nil
        }
        return cell
    }

    func tapPickerAdd() {
        for label in ["添加", "Add", "完成", "Done"] {
            let button = app.buttons[label].firstMatch
            if button.exists && button.isEnabled { button.tap(); return }
        }
        XCTFail("PHPicker 找不到「添加」")
    }

    /// 选中素材 → 点「添加」起计时,等分享面板;期间见到「加密中」就截一张。返回 (面板出现耗时, 面板上方看到的状态)。
    func pickAndTimeShareSheet(_ tag: String, prefix: String) -> TimeInterval? {
        openPlus("相册")
        Thread.sleep(forTimeInterval: 3)
        guard let cell = freshPickerCell(prefix) else { return nil }
        cell.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        let t0 = Date()
        tapPickerAdd()
        var encryptingShot = false
        let deadline = t0.addingTimeInterval(240)
        while Date() < deadline {
            if shareSheetCopyButton(timeout: 0.3) != nil {
                let elapsed = Date().timeIntervalSince(t0)
                record("\(tag)-sheet-seconds", String(format: "%.1f", elapsed))
                // 不截分享面板:面板里有机主的联系人头像
                return elapsed
            }
            if !encryptingShot, app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", "加密中")).firstMatch.exists {
                encryptingShot = true
                shot("\(tag)-encrypting")
                record("\(tag)-encrypting-seen-at", String(format: "%.1f", Date().timeIntervalSince(t0)))
            }
        }
        shot("\(tag)-no-sheet")
        XCTFail("240 秒内没弹分享面板")
        return nil
    }

    func cancelShareSheet(_ tag: String) {
        for attempt in 0..<4 {
            let close = app.buttons.matching(NSPredicate(format: "label IN %@", ["关闭", "Close"])).firstMatch
            if attempt < 2, close.exists {
                close.tap()
            } else {
                app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.15))
                    .press(forDuration: 0.1, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.95)))
            }
            Thread.sleep(forTimeInterval: 0.8)
            if shareSheetCopyButton(timeout: 0.3) == nil { return }
        }
        shot("\(tag)-sheet-stuck")
    }

    /// 最底下(最新)那个媒体气泡。
    func newestBubble(_ prefix: String) -> XCUIElement? {
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", prefix)).allElementsBoundByIndex
            .filter { $0.frame.height > 40 }.max { $0.frame.maxY < $1.frame.maxY }
    }

    /// 长按最新气泡 →「复制加密消息」→ 经陈仓输入框带出两行分享文本(记录为 `<tag>-share`,存 runner 容器 + 附件)。
    func copyShareText(_ tag: String, prefix: String) -> String? {
        guard let bubble = newestBubble(prefix) else { XCTFail("找不到 \(prefix) 气泡"); return nil }
        bubble.press(forDuration: 1.2)
        let copy = app.buttons["复制加密消息"].firstMatch
        guard copy.waitForExistence(timeout: 5) else { shot("\(tag)-no-menu"); XCTFail("没有长按菜单"); return nil }
        copy.tap()
        return captureClipboard("\(tag)-share")
    }

    // MARK: - 场景 1:45 秒视频,面板先于上传弹出;取消 → 立刻回桌面 → 等 → 回前台

    func testSF1VideoShareBeforeUpload() {
        launchApp()
        openThread(peer)
        guard pickAndTimeShareSheet("sf1", prefix: "Video, forty-five seconds") != nil else { return }
        cancelShareSheet("sf1")
        record("sf1-status-after-cancel", statusLabels().joined(separator: " | "))
        shot("sf1-after-cancel")
        XCUIDevice.shared.press(.home)
        let bgStart = Date()
        record("sf-shared", "backgrounded")
        let wait = TimeInterval(env("UAT_BG_SECONDS") ?? "") ?? 90
        while Date().timeIntervalSince(bgStart) < wait { Thread.sleep(forTimeInterval: 5) }
        app.activate()
        _ = app.wait(for: .runningForeground, timeout: 15)
        Thread.sleep(forTimeInterval: 2)
        shot("sf1-foreground")
        record("sf1-status-after-foreground", statusLabels().joined(separator: " | "))
        // 回前台那一刻还在「上传中」不直接判失败:记下它还要多久才消失(后台传得慢 / 回前台才对账,都如实记)。
        let fg = Date()
        let uploading = app.descendants(matching: .any)["上传中"]
        let stillAtForeground = uploading.exists
        while uploading.exists, Date().timeIntervalSince(fg) < 180 { Thread.sleep(forTimeInterval: 1) }
        record("sf1-uploading-at-foreground", "\(stillAtForeground) cleared-after=\(String(format: "%.0f", Date().timeIntervalSince(fg)))s")
        shot("sf1-settled")
        XCTAssertFalse(uploading.exists, "回前台 180 秒后还在上传中")
        _ = copyShareText("sf1", prefix: "视频 ")
    }

    // MARK: - 场景 4:杀进程——宿主机看到 `sf-shared` 就用 devicectl 杀陈仓

    /// 发 45 秒视频,面板出来就取消、写 `sf-shared`,然后停 `UAT_HOLD_SECONDS` 秒让宿主机动手(杀进程)。
    func testSF4SendThenHold() {
        launchApp()
        openThread(peer)
        guard pickAndTimeShareSheet("sf4", prefix: "Video, forty-five seconds") != nil else { return }
        cancelShareSheet("sf4")
        record("sf-shared", "ready-to-kill")
        let hold = TimeInterval(env("UAT_HOLD_SECONDS") ?? "") ?? 20
        Thread.sleep(forTimeInterval: hold)
        record("sf4-app-state-after-hold", "\(app.state.rawValue)")
    }

    /// 杀进程之后重新启动陈仓,看这条视频的状态(上传完成 / 对账续传)。
    func testSF4Relaunch() {
        launchApp()
        openThread(peer)
        Thread.sleep(forTimeInterval: 3)
        shot("sf4-relaunch")
        record("sf4-status-relaunch", statusLabels().joined(separator: " | "))
        let wait = TimeInterval(env("UAT_WAIT_SECONDS") ?? "") ?? 120
        let deadline = Date().addingTimeInterval(wait)
        while Date() < deadline, app.descendants(matching: .any)["上传中"].exists { Thread.sleep(forTimeInterval: 3) }
        shot("sf4-relaunch-settled")
        record("sf4-status-settled", statusLabels().joined(separator: " | "))
        _ = copyShareText("sf4", prefix: "视频 ")
    }

    // MARK: - 场景 5:上传中删消息

    /// 把一段无害的哨兵文本经输入框「拷贝」放进剪贴板(B1:之后若剪贴板变成分享文本,就是卡片复制了已删消息)。
    func putSentinelOnClipboard(_ sentinel: String) {
        let field = composerField()
        clearComposer(field)
        field.tap()
        field.typeText(sentinel)
        if let all = editMenuItem(["全选", "Select All"], in: app, field: field) { all.tap() }
        Thread.sleep(forTimeInterval: 0.6)
        record("sentinel-menu", app.menuItems.allElementsBoundByIndex.map(\.label).joined(separator: " | "))
        if let copy = editMenuItem(["拷贝", "Copy"], in: app, field: field) { copy.tap() } else { XCTFail("找不到拷贝") }
        Thread.sleep(forTimeInterval: 1.0)
        shot("sentinel-after-copy")
        clearComposer(field)
    }

    /// 场景 5 + B1:45 秒视频,面板取消(卡片停在「还没发 · 点这里再发」),趁「上传中」删除这条。
    /// 断言:卡片收起(没有「分享」/「复制」);若卡片还在就点「复制」,剪贴板不得变成分享文本;
    /// 不崩溃;等 `UAT_WAIT_SECONDS` 再冷启动,消息不复活。
    func testSF5DeleteMidUpload() {
        launchApp()
        openThread(peer)
        let sentinel = "uat-b1-sentinel-\(Int(Date().timeIntervalSince1970))"
        putSentinelOnClipboard(sentinel)
        // 收起键盘再发:键盘开着时新气泡会被卡片挡在屏幕外,长按会落到上一条消息上(2026-10-01 iPhone 12 轮踩过)。
        backToList()
        openThread(peer)
        guard pickAndTimeShareSheet("sf5", prefix: env("UAT_SF5_PREFIX") ?? "Video, forty-five seconds") != nil else { return }
        cancelShareSheet("sf5")
        let bubblePrefix = env("UAT_SF5_BUBBLE") ?? "视频 "
        let before = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", bubblePrefix)).allElementsBoundByIndex
            .filter { $0.frame.height > 40 }.count
        let cardShare = app.buttons["分享"].firstMatch
        record("sf5-card-before-delete", "share=\(cardShare.exists) status=\(statusLabels().joined(separator: " | "))")
        shot("sf5-before-delete")
        guard let bubble = newestBubble(bubblePrefix), bubble.isHittable else { XCTFail("找不到(可点的)新气泡"); return }
        // 只删「上传中」的那条:状态行不在就不动手,免得删错消息。
        guard app.descendants(matching: .any)["上传中"].exists else {
            record("sf5-skip", "no 上传中 before delete"); XCTFail("删除前已经不在上传中"); return
        }
        bubble.press(forDuration: 1.2)
        let delete = app.buttons["删除"].firstMatch
        XCTAssertTrue(delete.waitForExistence(timeout: 5), "长按菜单没有删除")
        delete.tap()
        let confirm = app.buttons["删除"].firstMatch
        if confirm.waitForExistence(timeout: 2) { confirm.tap() }
        let deletedAt = Date()
        Thread.sleep(forTimeInterval: 2)
        shot("sf5-after-delete")
        let after = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", bubblePrefix)).allElementsBoundByIndex
            .filter { $0.frame.height > 40 }.count
        let cardShareAfter = app.buttons["分享"].firstMatch.exists
        let cardCopyAfter = app.buttons.matching(NSPredicate(format: "label IN %@", ["复制", "已复制"])).firstMatch
        record("sf5-video-count", "\(before) -> \(after)")
        record("sf5-card-after-delete", "share=\(cardShareAfter) copy=\(cardCopyAfter.exists) status=\(statusLabels().joined(separator: " | "))")
        XCTAssertFalse(cardShareAfter, "B1:删掉卡片上的消息后加密卡还在")
        if cardCopyAfter.exists {
            cardCopyAfter.tap()
            Thread.sleep(forTimeInterval: 1)
        }
        let clip = captureClipboard("sf5-clipboard-after-delete")
        let leaked = clip != sentinel && clip.contains("陈仓加密")
        record("sf5-b1-clipboard", leaked ? "LEAKED-share-text" : (clip == sentinel ? "sentinel-intact" : "other(\(clip.count) chars)"))
        XCTAssertFalse(leaked, "B1:「复制」复制出了已删消息的分享文本")
        XCTAssertEqual(app.state, .runningForeground, "删除后 App 不在前台(崩溃?)")
        // 等一会儿(上传回调若回写已删的消息就会「复活」),再冷启动核对
        let wait = TimeInterval(env("UAT_WAIT_SECONDS") ?? "") ?? 45
        while Date().timeIntervalSince(deletedAt) < wait { Thread.sleep(forTimeInterval: 3) }
        shot("sf5-after-wait")
        let afterWait = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", bubblePrefix)).allElementsBoundByIndex
            .filter { $0.frame.height > 40 }.count
        app.terminate()
        launchApp()
        openThread(peer)
        Thread.sleep(forTimeInterval: 3)
        shot("sf5-relaunch")
        let relaunch = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", bubblePrefix)).allElementsBoundByIndex
            .filter { $0.frame.height > 40 }.count
        record("sf5-video-count-wait-relaunch", "\(afterWait) -> \(relaunch)")
        record("sf5-status-relaunch", statusLabels().joined(separator: " | "))
        XCTAssertLessThan(after, before, "删除后气泡还在")
        XCTAssertEqual(relaunch, after, "冷启动后气泡数变了(复活?)")
    }

    // MARK: - 设施自检:输入框带出剪贴板 / 输入框共享里有「陈仓解密」(都用无害文本,不点扩展)

    func testHarnessProbe() {
        launchApp()
        openThread(peer)
        // `UAT_PROBE_PAD`:把探针文本垫长到这么多字(测长文本键入;加密消息一行约 110 字)。
        let pad = Int(env("UAT_PROBE_PAD") ?? "") ?? 0
        let probe = "uat-harness-probe-\(Int(Date().timeIntervalSince1970))" + String(repeating: "垫", count: pad)
        let t0 = Date()
        putSentinelOnClipboard(probe)
        record("probe-sentinel-seconds", String(format: "%.0f", Date().timeIntervalSince(t0)))
        let back = captureClipboard("probe-clipboard")
        record("probe-clipboard-roundtrip", back == probe ? "ok" : "mismatch(\(back.count) chars)")
        XCTAssertTrue(back == probe, "剪贴板往返不一致")
        let field = composerField()
        field.tap()
        field.typeText(probe)
        if let all = editMenuItem(["全选", "Select All"], in: app, field: field) { all.tap() }
        Thread.sleep(forTimeInterval: 0.6)
        guard let share = editMenuItem(["共享…", "Share…", "共享...", "Share..."], in: app, field: field) else {
            record("probe-share-menu", "missing"); XCTFail("输入框编辑菜单没有共享"); clearComposer(field); return
        }
        share.tap()
        let action = app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", "陈仓解密")).firstMatch
        var found = action.waitForExistence(timeout: 10)
        for _ in 0..<3 where !found { app.swipeUp(); found = action.waitForExistence(timeout: 2) }
        record("probe-decrypt-action-in-own-share-sheet", "\(found)")
        cancelShareSheet("probe")
        clearComposer(composerField())
        XCTAssertTrue(found, "陈仓自己的分享面板里没有「陈仓解密」")
    }

    // MARK: - 图片:面板先于上传(给场景 3 反方向当发送端用)

    /// 素材前缀 `UAT_SF6_PREFIX`(默认图片;给「Video, forty-five seconds」就是发视频)。面板里点「拷贝」,
    /// 分享文本经输入框带出(`sf6-share`)。给了 `UAT_BG_SECONDS` 就随后回桌面待这么久再回前台记状态——
    /// 无线调试下没法开飞行模式时,靠大文件上传慢,让对端有时间先打开。
    func testSF6ImageShareCopy() {
        launchApp()
        openThread(peer)
        guard pickAndTimeShareSheet("sf6", prefix: env("UAT_SF6_PREFIX") ?? "Photo,") != nil else { return }
        guard let copy = shareSheetCopyButton(timeout: 5) else { return }
        copy.tap()
        // 面板收起的动画里别读状态行(元素在变,快照会抛错把用例打断)
        Thread.sleep(forTimeInterval: 2)
        captureClipboard("sf6-share")
        record("sf-shared", "copied")
        guard let bg = TimeInterval(env("UAT_BG_SECONDS") ?? "") else { return }
        XCUIDevice.shared.press(.home)
        let start = Date()
        while Date().timeIntervalSince(start) < bg { Thread.sleep(forTimeInterval: 5) }
        app.activate()
        _ = app.wait(for: .runningForeground, timeout: 15)
        Thread.sleep(forTimeInterval: 2)
        shot("sf6-foreground")
        record("sf6-status-after-foreground", statusLabels().joined(separator: " | "))
    }

    // MARK: - 场景 3:收件方先打开 → 等待对方上传 → 对端传完后自动出现

    /// `UAT_TEXT` 是对端(K40)的两行分享文本;`UAT_EXPECT` 例如「图片」;`UAT_WAIT_SECONDS` 最长等多久。
    /// 看到「等待对方上传」后写 `sf3-awaiting-seen`,宿主机据此放开对端上传(关飞行模式)。
    func testSF3ReceiveAwaitingThenAppears() {
        guard let text = env("UAT_TEXT"), let expect = env("UAT_EXPECT") else { XCTFail("缺 UAT_TEXT / UAT_EXPECT"); return }
        launchApp()
        openThread(peer)
        guard deliverToDecryptAction(text, expect: expect, tag: "sf3") else { return }
        let t0 = Date()
        let awaiting = app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", "等待对方上传")).firstMatch
        let seen = awaiting.waitForExistence(timeout: 30)
        shot("sf3-awaiting")
        record("sf3-awaiting-seen", "\(seen) status=\(statusLabels().joined(separator: " | "))")
        XCTAssertTrue(seen, "没显示「等待对方上传」")
        let wait = TimeInterval(env("UAT_WAIT_SECONDS") ?? "") ?? 600
        var lastStatus = ""
        while Date().timeIntervalSince(t0) < wait {
            let status = statusLabels().joined(separator: " | ")
            if status != lastStatus {
                record("sf3-status-t\(Int(Date().timeIntervalSince(t0)))", status)
                lastStatus = status
            }
            if !awaiting.exists && !app.descendants(matching: .any)["传输中"].exists { break }
            Thread.sleep(forTimeInterval: 2)
        }
        let appeared = !awaiting.exists
        record("sf3-appeared", "\(appeared) after=\(Int(Date().timeIntervalSince(t0)))s")
        Thread.sleep(forTimeInterval: 3)
        shot("sf3-appeared")
        XCTAssertTrue(appeared, "对端传完后没自动出现")
    }

    // MARK: - 飞行模式(只在 USB 连接时用:无线调试下开飞行模式会断连且无法远程恢复)

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
        shot("airplane-\(on ? "on" : "off")")
        record("airplane-\(on ? "on" : "off")-value", "\(toggle.value ?? "nil")")
        springboard.swipeUp()
        app.activate()
        _ = app.wait(for: .runningForeground, timeout: 10)
    }

    /// `UAT_AIRPLANE=1|0`。关掉之后可选停在对端线程 `UAT_WAIT_SECONDS` 秒,记录状态变化(看自己传完)。
    func testSFAirplaneSet() {
        let on = env("UAT_AIRPLANE") == "1"
        launchApp()
        if !on, env("UAT_PEER_NAME") != nil { openThread(peer) }
        setAirplane(on)
        guard !on else { return }
        let t0 = Date()
        let wait = TimeInterval(env("UAT_WAIT_SECONDS") ?? "") ?? 0
        var last = ""
        while Date().timeIntervalSince(t0) < wait {
            let status = statusLabels().joined(separator: " | ")
            if status != last { record("sfair-status-t\(Int(Date().timeIntervalSince(t0)))", status); last = status }
            if !app.descendants(matching: .any)["上传中"].exists && Date().timeIntervalSince(t0) > 5 { break }
            Thread.sleep(forTimeInterval: 2)
        }
        shot("sfair-after")
        record("sfair-final", statusLabels().joined(separator: " | "))
    }

    /// 场景 2:飞行模式下发图 → 面板照弹 → 拷贝 → 状态停在「上传中」;分享文本经陈仓输入框带出。飞行模式保持开着。
    func testSF2OfflineImage() {
        launchApp()
        openThread(peer)
        setAirplane(true)
        guard pickAndTimeShareSheet("sf2", prefix: "Photo,") != nil else { return }
        guard let copy = shareSheetCopyButton(timeout: 5) else { return }
        copy.tap()
        Thread.sleep(forTimeInterval: 1.5)
        shot("sf2-after-copy")
        record("sf2-status-after-copy", statusLabels().joined(separator: " | "))
        captureClipboard("sf2-share")
        backToList()
        Thread.sleep(forTimeInterval: 1)
        shot("sf2-list")
        record("sf2-list-status", statusLabels().joined(separator: " | "))
    }

    // MARK: - 开跑前只看不动:机主是否在用手机

    /// 不启动陈仓、不点任何东西:setUp 的通话闸门 + precheck 截图之外,再逐个查 App 是否在前台。
    /// `UAT_BUNDLES` = 逗号分隔的 bundle id(宿主机从 `devicectl device info apps --include-all-apps` 取)。
    /// 桌面(springboard)永远报 runningForeground,不算。宿主机据 `sf-precheck-foreground` 判定:
    /// 空 = 在桌面/锁屏,可以跑;陈仓以外的 App 在前台 = 机主在用,停手。
    /// precheck 截图可能拍到机主屏幕,宿主机看完即删。
    func testSFPrecheck() {
        let ids = (env("UAT_BUNDLES") ?? "").split(separator: ",").map(String.init)
            .filter { $0 != "com.apple.springboard" && !$0.hasSuffix(".xctrunner") }
        let fg = ids.filter { XCUIApplication(bundleIdentifier: $0).state == .runningForeground }
        record("sf-precheck-foreground", fg.isEmpty ? "<none>" : fg.joined(separator: ","))
        record("sf-precheck-checked", "\(ids.count)")
    }

    // MARK: - 收尾:删掉 runner 容器里的分享文本记录

    /// `record` 会把分享文本写进 runner 自己容器的 `Documents/<tag>.txt`。一轮跑完删掉这些(素材台账、
    /// seeded-at 不动),加密消息不留在手机上。不启动陈仓、不点任何东西。
    func testZZPurgeShareRecords() {
        let keep: Set<String> = ["seeded-at.txt", "seeded-assets.txt", "seeded-assets-gps.txt", "seeded-assets-sbu.txt", UATMediaSeedTests.ledgerName]
        let files = (try? FileManager.default.contentsOfDirectory(atPath: Self.documents.path)) ?? []
        var removed = 0
        for name in files where name.hasSuffix(".txt") && !keep.contains(name) {
            if name.hasSuffix("-share.txt") || name.contains("clipboard") || name.hasPrefix("in-") {
                try? FileManager.default.removeItem(at: Self.documents.appendingPathComponent(name)); removed += 1
            }
        }
        print("UATPURGE removed=\(removed) of=\(files.count)")
    }

    // MARK: - 只读:线程与列表状态截图

    func testSFPeek() {
        launchApp()
        backToList()
        Thread.sleep(forTimeInterval: 2)
        shot("sf-peek-list")
        record("sf-peek-list", statusLabels().joined(separator: " | "))
        openThread(peer)
        Thread.sleep(forTimeInterval: 3)
        shot("sf-peek-thread")
        record("sf-peek-thread", statusLabels().joined(separator: " | "))
    }
}
