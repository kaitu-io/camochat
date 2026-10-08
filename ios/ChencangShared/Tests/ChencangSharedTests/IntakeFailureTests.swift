import XCTest
@testable import ChencangShared

final class IntakeFailureTests: XCTestCase {
    func testMessagesUseLocalizedStrings() {
        XCTAssertEqual(IntakeFailure.incomplete.message, L10n.intakeErrorIncomplete)
        XCTAssertEqual(IntakeFailure.notOurs.message, L10n.intakeErrorNotOurs)
        XCTAssertEqual(IntakeFailure.noContacts.message, L10n.intakeErrorNoContacts)
        XCTAssertEqual(IntakeFailure.cannotOpen.message, L10n.intakeErrorCannotDecrypt)
        XCTAssertEqual(IntakeFailure.saveFailed.message, L10n.intakeErrorSaveFailed)
        XCTAssertEqual(IntakeFailure.pendingInvite.message, L10n.intakeErrorPendingInvite)
    }

    func testNextSteps() {
        XCTAssertEqual(IntakeFailure.noContacts.nextStep, .addContact)
        XCTAssertEqual(IntakeFailure.cannotOpen.nextStep, .openApp)
        XCTAssertNil(IntakeFailure.incomplete.nextStep)
        XCTAssertNil(IntakeFailure.pendingInvite.nextStep, "无动作按钮")
        XCTAssertNil(IntakeFailure.notOurs.nextStep)
        XCTAssertNil(IntakeFailure.saveFailed.nextStep)
        XCTAssertNil(IntakeFailure.clipboardEmpty.nextStep)
    }

    // 「粘贴解密」时剪贴板空 / 只有空白:直接提示「剪贴板里没有文字」,不再当成「不是完整的加密消息」。
    func testBlankClipboardIsClipboardEmpty() {
        XCTAssertEqual(IntakeFailure.clipboardEmpty.message, L10n.intakeErrorClipboardEmpty)
        XCTAssertEqual(IntakeFailure.forClipboard(nil), .clipboardEmpty)
        XCTAssertEqual(IntakeFailure.forClipboard(""), .clipboardEmpty)
        XCTAssertEqual(IntakeFailure.forClipboard(" \n\t "), .clipboardEmpty)
        XCTAssertNil(IntakeFailure.forClipboard("🔒x"))
    }
}
