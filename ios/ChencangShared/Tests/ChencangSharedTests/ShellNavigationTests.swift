import XCTest
@testable import ChencangShared

final class ShellNavigationTests: XCTestCase {
    func testSendMessageFromSamePeersThreadPopsBack() {
        let from = ShellState(tab: .chats, path: [.thread("a"), .contact("a")])
        let next = ShellNav.sendMessage(from: from, contactId: "a")
        XCTAssertEqual(next, ShellState(tab: .chats, path: [.thread("a")]))
    }

    func testSendMessageFromContactsTabSwitchesToChats() {
        let from = ShellState(tab: .contacts, path: [.contact("a")])
        let next = ShellNav.sendMessage(from: from, contactId: "a")
        XCTAssertEqual(next, ShellState(tab: .chats, path: [.thread("a")]))
    }

    func testSendMessageFromOtherPeersThreadReplacesStack() {
        let from = ShellState(tab: .chats, path: [.thread("b"), .contact("a")])
        let next = ShellNav.sendMessage(from: from, contactId: "a")
        XCTAssertEqual(next, ShellState(tab: .chats, path: [.thread("a")]))
    }

    func testContactDeletedKeepsTab() {
        let from = ShellState(tab: .contacts, path: [.contact("a")])
        XCTAssertEqual(ShellNav.contactDeleted(from: from), ShellState(tab: .contacts, path: []))
        let fromChats = ShellState(tab: .chats, path: [.thread("a"), .contact("a")])
        XCTAssertEqual(ShellNav.contactDeleted(from: fromChats), ShellState(tab: .chats, path: []))
    }

    func testAccountWipedLandsOnChats() {
        XCTAssertEqual(ShellNav.accountWiped(), ShellState(tab: .chats, path: []))
    }

    func testOpenThreadAlwaysLandsOnChats() {
        XCTAssertEqual(ShellNav.openThread("x"), ShellState(tab: .chats, path: [.thread("x")]))
    }
}
