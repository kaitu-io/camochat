import XCTest
import UIKit

/// 两台 iPhone 互相配对。发起方(iPhone 15)先跑 `testPairInitiator`,出示配对码写到
/// `Documents/invite.txt`,然后停在接收幕轮询 `in-response.txt`;宿主机把配对码经
/// `TEST_RUNNER_UAT_INVITE` 交给接收方(iPhone 12)的 `testPairRedeemer`,它在
/// 模拟器上写剪贴板后点会话 tab 粘贴条的系统粘贴按钮,真机(runner 后台写不了剪贴板)走 ➕ → 出示幕「对方已扫码 · 下一步」
/// → 接收幕键入;把我的配对码写到 `Documents/response.txt`,宿主机再投进发起方信箱。
final class UATPairingTests: UATCase {

    /// 出示幕里完整配对码的那个文本(标签以 🔒 开头)。
    private func wireText() -> XCUIElement {
        app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "🔒")).firstMatch
    }

    private func emojiSummary() -> String {
        // 核对安全码的图案网格的每个表情是一个 staticText;只收集单字符(表情)标签
        let labels = app.staticTexts.allElementsBoundByIndex.map(\.label)
        return labels.filter { $0.count <= 2 && $0.unicodeScalars.contains { $0.properties.isEmojiPresentation } }
            .joined(separator: " ")
    }

    private func finishConfirm(name: String, tag: String) {
        XCTAssertTrue(app.buttons["pairing-verify-match"].waitForExistence(timeout: 30), "没进核对幕")
        record("\(tag)-emoji", emojiSummary())
        shot("\(tag)-confirm")
        let field = app.textFields["pairing-verify-name"]
        if field.exists {
            field.tap()
            field.typeText(name)
        }
        app.buttons["pairing-verify-match"].tap()
        XCTAssertTrue(app.buttons["更多"].waitForExistence(timeout: 10), "确认后没进线程")
        shot("\(tag)-thread")
    }

    func testPairInitiator() {
        launchApp()
        backToList()
        app.buttons["convlist-add"].tap()
        let wire = wireText()
        XCTAssertTrue(wire.waitForExistence(timeout: 30), "没出现我的配对码")
        record("invite", wire.label)
        shot("pair-initiator-show")
        app.buttons["pairing-next-after-scan"].tap()

        guard let response = waitMailbox("response", timeout: 900) else {
            XCTFail("15 分钟内没收到对方的配对码")
            return
        }
        // runner 在后台写不了系统剪贴板(PBErrorDomain 10/11),改为键入;认出配对码即自动提交
        typeInto(app.textFields["pairing-input"], response)
        shot("pair-initiator-pasted")
        finishConfirm(name: env("UAT_PEER_NAME") ?? "UAT-12", tag: "pair-initiator")
    }

    func testPairRedeemer() {
        guard let invite = env("UAT_INVITE") else {
            XCTFail("缺 UAT_INVITE")
            return
        }
        launchApp()
        backToList()
        #if targetEnvironment(simulator)
        // 入口收拢:对方的邀请在会话 tab 的粘贴条里粘贴(runner 写剪贴板 → 点系统粘贴按钮)。
        pasteViaPasteBar(invite)
        #else
        // 真机 runner 在后台写不了剪贴板:走 ＋ → 出示幕 →「对方已扫码 · 下一步」到接收幕,键入;认出邀请即自动提交。
        app.buttons["convlist-add"].tap()
        XCTAssertTrue(app.buttons["pairing-next-after-scan"].waitForExistence(timeout: 30), "没进出示幕")
        app.buttons["pairing-next-after-scan"].tap()
        typeInto(app.textFields["pairing-input"], invite)
        #endif
        shot("pair-redeemer-pasted")
        let wire = wireText()
        XCTAssertTrue(wire.waitForExistence(timeout: 30), "没出现回应的配对码")
        record("response", wire.label)
        shot("pair-redeemer-show")
        app.buttons["pairing-next-after-scan"].tap()
        finishConfirm(name: env("UAT_PEER_NAME") ?? "UAT-15", tag: "pair-redeemer")
    }
}
