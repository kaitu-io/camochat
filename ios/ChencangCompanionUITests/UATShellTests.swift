import XCTest

/// 三 tab 骨架的界面用例(spec 2026-10-01-three-tab-shell §6 / §10.2)。不依赖聊天软件、不依赖网络,
/// 联系人用「我」tab 开发者区的「Seed test contact」造(DEBUG 构建才有),所以只能跑 Debug 构建:
/// `xcodebuild test -project ChencangiOS.xcodeproj -scheme ChencangCompanion -destination 'platform=iOS Simulator,name=<机型>' -only-testing:ChencangCompanionUITests/UATShellTests`
///
/// 注意(2026-10-02 在 iPad 模拟器上实测):`CODE_SIGNING_ALLOWED=NO` 的模拟器构建能启动,但没有 Keychain / App Group
/// 授权,「Seed test contact」(状态行「❌ Self-test failed」)与向导出配对码(「配对失败,请重试」)都会失败。
/// 所以除 `testThreeTabsExistAndSwitch` 外,这些用例须在真机(带签名的构建)上执行。
final class UATShellTests: UATCase {
    private let seedName = "UAT 自测"

    // MARK: - 辅助

    /// 「我」tab → 开发者区「Seed test contact」(幂等)。可重复调用:状态行是保留的(上一次的「Seed done」还在屏幕上),
    /// 所以点击后先等状态行离开「Running…」再判定,不拿点击前的旧文字当结果。
    /// (点击处理里同步把状态置为「运行中…」,点击返回时它已是新状态或已完成。)
    private func seedContact() {
        selectTab("我")
        let seed = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "Seed test contact")).firstMatch
        for _ in 0..<6 where !seed.exists { swipeContentUp() }
        XCTAssertTrue(seed.waitForExistence(timeout: 5), "「我」tab 里没有「Seed test contact」(非 Debug 构建?)")
        seed.tap()
        let status = app.staticTexts.matching(NSPredicate(
            format: "label CONTAINS %@ OR label CONTAINS %@ OR label CONTAINS %@", "Seed done", "Self-test failed", "Running")).firstMatch
        _ = status.waitForExistence(timeout: 10)
        if !status.exists { swipeContentUp() }   // 状态行可能在屏外:找不到就上滑一次再找
        _ = status.waitForExistence(timeout: 5)
        let deadline = Date().addingTimeInterval(60)
        while status.exists && status.label.contains("Running") && Date() < deadline {
            Thread.sleep(forTimeInterval: 0.5)
        }
        XCTAssertTrue(status.exists && status.label.contains("Seed done"),
                      "Seed 没有完成,状态行:\(status.exists ? status.label : "(无)")")
    }

    private func contactRow(_ name: String) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "\(name)，")).firstMatch
    }

    /// 联系人行标识 `contactlist-row-<fingerprintHex>` 里的指纹(深链 `peer=` 用)。
    private func contactId(_ name: String) -> String? {
        let id = contactRow(name).identifier
        return id.hasPrefix("contactlist-row-") ? String(id.dropFirst("contactlist-row-".count)) : nil
    }

    private func isSelected(_ label: String) -> Bool { tabButton(label).isSelected }

    private func tapNavBack() {
        let back = app.navigationBars.buttons.element(boundBy: 0)
        XCTAssertTrue(back.waitForExistence(timeout: 5), "没有返回按钮")
        back.tap()
        Thread.sleep(forTimeInterval: 0.8)
    }

    /// 联系人 tab → 种子联系人 → 「发消息」→ 线程。
    private func openSeedThread() {
        selectTab("联系人")
        XCTAssertTrue(contactRow(seedName).waitForExistence(timeout: 10), "联系人 tab 里没有 \(seedName)")
        contactRow(seedName).tap()
        let message = app.buttons["发消息"]
        XCTAssertTrue(message.waitForExistence(timeout: 10))
        message.tap()
        XCTAssertTrue(app.buttons["更多"].waitForExistence(timeout: 10) || app.buttons["切换到语音"].exists, "没进线程")
    }

    // MARK: - 骨架

    func testThreeTabsExistAndSwitch() {
        launchApp()
        for label in ["会话", "联系人", "我"] {
            XCTAssertTrue(tabButton(label).waitForExistence(timeout: 10), "缺 tab「\(label)」")
        }
        for (tab, title) in [("会话", "陈仓"), ("联系人", "联系人"), ("我", "我")] {
            selectTab(tab)
            XCTAssertTrue(isSelected(tab), "「\(tab)」没被选中")
            XCTAssertTrue(app.navigationBars[title].waitForExistence(timeout: 5), "「\(tab)」tab 的标题不是「\(title)」")
        }
        shot("shell-three-tabs")
    }

    func testTabBarHiddenInsideThread() {
        launchApp()
        seedContact()
        openSeedThread()
        let chats = tabButton("会话")
        XCTAssertTrue(!chats.exists || !chats.isHittable, "线程里 tab 栏仍可点")
        tapNavBack()
        XCTAssertTrue(tabButton("会话").waitForExistence(timeout: 5) && tabButton("会话").isHittable, "返回后 tab 栏没回来")
    }

    func testSendMessageFromContactLandsOnChatsTab() {
        launchApp()
        seedContact()
        openSeedThread()
        tapNavBack()
        XCTAssertTrue(isSelected("会话"), "「发消息」进线程再返回,应落在「会话」tab")
        XCTAssertTrue(app.navigationBars["陈仓"].waitForExistence(timeout: 5))
    }

    func testThreadTitleToDetailToMessageDoesNotStack() {
        launchApp()
        seedContact()
        openSeedThread()
        // 线程标题栏整体是一个按钮 → 联系人详情
        let title = app.navigationBars.buttons.matching(NSPredicate(format: "label CONTAINS %@", seedName)).firstMatch
        XCTAssertTrue(title.waitForExistence(timeout: 5), "线程标题栏里没有 \(seedName)")
        title.tap()
        let message = app.buttons["发消息"]
        XCTAssertTrue(message.waitForExistence(timeout: 10), "没进联系人详情")
        message.tap()
        XCTAssertTrue(app.buttons["更多"].waitForExistence(timeout: 10) || app.buttons["切换到语音"].exists, "没回到线程")
        tapNavBack()   // 只返回一次就应到会话 tab 根,栈深不增长
        XCTAssertTrue(app.navigationBars["陈仓"].waitForExistence(timeout: 5), "返回一次没回到会话 tab 根(多叠了一层?)")
        XCTAssertTrue(isSelected("会话"))
    }

    func testDeleteContactReturnsToOriginTab() {
        launchApp()
        seedContact()
        // 从联系人 tab 进详情删除 → 回联系人 tab,该行消失
        selectTab("联系人")
        XCTAssertTrue(contactRow(seedName).waitForExistence(timeout: 10))
        contactRow(seedName).tap()
        deleteFromDetail()
        XCTAssertTrue(app.navigationBars["联系人"].waitForExistence(timeout: 5), "删除后没回联系人 tab")
        XCTAssertTrue(isSelected("联系人"))
        XCTAssertFalse(contactRow(seedName).exists, "删除后联系人行还在")

        // 从线程 → 标题 → 详情删除 → 回会话 tab
        seedContact()
        openSeedThread()
        let titleButton = app.navigationBars.buttons.matching(NSPredicate(format: "label CONTAINS %@", seedName)).firstMatch
        XCTAssertTrue(titleButton.waitForExistence(timeout: 5), "线程标题栏里没有 \(seedName)")
        titleButton.tap()
        deleteFromDetail()
        XCTAssertTrue(app.navigationBars["陈仓"].waitForExistence(timeout: 5), "从线程进详情删除后没回会话 tab")
        XCTAssertTrue(isSelected("会话"))
        selectTab("联系人")
        XCTAssertFalse(contactRow(seedName).exists, "删除后联系人行还在")
    }

    private func deleteFromDetail() {
        let delete = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "删除联系人")).firstMatch
        // 详情页是 ScrollView(不是列表):对它自己滑,免得滑到被盖住的下层列表上。
        for _ in 0..<4 where !delete.exists {
            let detail = app.scrollViews.firstMatch
            if detail.exists { detail.swipeUp() } else { app.swipeUp() }
        }
        XCTAssertTrue(delete.waitForExistence(timeout: 5), "详情里没有「删除联系人」")
        delete.tap()
        let confirm = app.buttons["删除"].firstMatch
        XCTAssertTrue(confirm.waitForExistence(timeout: 5), "没有删除确认")
        confirm.tap()
        Thread.sleep(forTimeInterval: 1.5)
    }

    // MARK: - 配对中角标

    /// 「配对中」与角标的新规则(三 tab 外壳 spec §3.5.3 + 大白话改版):
    /// - 发起方的配对码**分享过**才进「配对中」(行标识 `contactlist-pending-row-<pairingId>`);没分享过的不出现。
    /// - 角标只数「球在我手里」的行(接受方「我的配对码还没发回去」);等对方回配对码的行不计角标。
    /// 所以本用例:出一份配对码并写独有备注 → 经分享面板「拷贝」交出去(向导自动前进)→ 退出向导 →
    /// 联系人 tab 的「配对中」里出现这条(备注对得上),角标与出码前相同;再点行尾「再发一次」弹面板,行仍在、角标仍不变。
    /// 断言取相对值,不假设设备上「配对中」是空的(跑过配对用例的手机会有「待发回配对码」行)。
    func testBadgeOnlyForActionablePending() {
        launchApp()
        backToList()
        let badgeBefore = contactsBadgeCount()
        // 纯 ASCII:真机中文键盘会吞字。
        let note = "badge\(Int.random(in: 1000...9999))"
        app.buttons["convlist-add"].tap()
        let wire = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "🔒")).firstMatch
        XCTAssertTrue(wire.waitForExistence(timeout: 30), "没出现我的配对码")
        typeInto(app.textFields["pairing-note"], note)
        app.keyboards.buttons.matching(NSPredicate(format: "label IN %@", ["完成", "Done", "收起键盘", "return", "换行"])).firstMatch.tapIfExists()

        // 分享即前进:面板里点「拷贝」(会报告完成),向导自动进到等对方的那一幕。
        app.buttons["pairing-share"].tap()
        guard let copy = shareSheetCopyButton(timeout: 15) else { return XCTFail("点「分享给对方」没弹分享面板") }
        copy.tap()
        Thread.sleep(forTimeInterval: 1.5)
        XCTAssertTrue(app.descendants(matching: .any)["pairing-waiting"].waitForExistence(timeout: 10), "分享后向导没前进到等对方那一幕")
        app.navigationBars.buttons["返回"].tap()
        XCTAssertTrue(app.navigationBars["陈仓"].waitForExistence(timeout: 5))

        XCTAssertEqual(contactsBadgeCount(), badgeBefore, "等对方回配对码的行不应计入角标")
        shot("shell-badge-unchanged")
        selectTab("联系人")
        XCTAssertTrue(app.descendants(matching: .any)["contactlist-pending"].waitForExistence(timeout: 5), "「配对中」分区没出现")
        let row = app.buttons.matching(NSPredicate(
            format: "identifier BEGINSWITH %@ AND label BEGINSWITH %@", "contactlist-pending-row-", note)).firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 10), "「配对中」里找不到备注为 \(note) 的配对码")
        let pairingId = String(row.identifier.dropFirst("contactlist-pending-row-".count))

        // 再发一次:弹分享面板,行仍留在「配对中」,角标仍不变
        let resend = app.buttons["contactlist-pending-resend-\(pairingId)"]
        XCTAssertTrue(resend.waitForExistence(timeout: 5), "没有这条配对码的「再发一次」")
        resend.tap()
        XCTAssertTrue(shareSheetPresent() || shareSheetCopyButton(timeout: 5) != nil, "没弹出分享面板")
        dismissShareSheet()
        XCTAssertEqual(contactsBadgeCount(), badgeBefore, "再发一次后角标不应变化")
        XCTAssertTrue(app.descendants(matching: .any)["contactlist-pending"].exists, "分享过的配对码仍应留在「配对中」")
        shot("shell-badge-after-resend")

        // 收尾:删掉本轮造的这条配对码(左滑「删除」→ 确认),不给设备留垃圾。找不到就算了。
        if row.exists {
            row.swipeLeft()
            let del = app.buttons["删除"].firstMatch
            if del.waitForExistence(timeout: 3) {
                del.tap()
                // 先等确认标题出现,再点确认里的「删除」(否则点到的还是左滑的那个按钮)。
                // 标题 = `pairing_delete_title` 的中文值(UI 测试 target 不链接 ChencangShared,逐字写)。
                if app.staticTexts["删除这份配对码？"].firstMatch.waitForExistence(timeout: 3) {
                    let confirm = app.buttons["删除"].firstMatch
                    if confirm.waitForExistence(timeout: 3) { confirm.tap() }
                }
            }
        }
    }

    private func dismissShareSheet() {
        for label in ["关闭", "Close"] {
            let close = app.buttons[label].firstMatch
            if close.waitForExistence(timeout: 2) { close.tap(); break }
        }
        if shareSheetPresent() { app.swipeDown(velocity: .fast) }   // 兜底:下拉收起
        Thread.sleep(forTimeInterval: 1)
    }

    // MARK: - 深链收起向导

    func testThreadDeepLinkDismissesOpenWizard() {
        launchApp()
        seedContact()
        selectTab("联系人")
        XCTAssertTrue(contactRow(seedName).waitForExistence(timeout: 10))
        guard let peer = contactId(seedName) else { return XCTFail("取不到联系人指纹(行标识不是 contactlist-row-<id>)") }

        // 开着向导 sheet
        app.buttons["contactlist-add"].tap()
        XCTAssertTrue(app.navigationBars["添加联系人"].waitForExistence(timeout: 10), "向导没打开")

        // 深链:`xcrun simctl openurl` 在这里的等价物
        XCUIDevice.shared.system.open(URL(string: "camo://thread?peer=\(peer)")!)
        acceptSystemAlert(timeout: 5, allow: ["打开", "Open"])
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 15))
        XCTAssertTrue(app.buttons["更多"].waitForExistence(timeout: 15) || app.buttons["切换到语音"].exists, "深链没落在线程")
        XCTAssertFalse(app.navigationBars["添加联系人"].exists, "向导 sheet 没被收起")
        tapNavBack()
        XCTAssertTrue(isSelected("会话"), "深链进线程,返回应到会话 tab 根")
    }
}
