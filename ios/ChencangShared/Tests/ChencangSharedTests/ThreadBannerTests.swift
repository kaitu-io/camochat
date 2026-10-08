import XCTest
@testable import ChencangShared

@MainActor
final class ThreadBannerTests: XCTestCase {
    private var dir: URL!
    private var store: ChatStore!
    private var defaults: UserDefaults!
    private var suiteName: String!

    override func setUp() async throws {
        dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("cc-banner-tests-\(UUID().uuidString)", isDirectory: true)
        store = try ChatStore(directory: dir)
        suiteName = "cc-banner-\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
    }

    override func tearDown() async throws {
        try? FileManager.default.removeItem(at: dir)
        defaults.removePersistentDomain(forName: suiteName)
    }

    private func makeVM() -> ThreadViewModel {
        let svc = ChatService(store: store, crypto: NoopCrypto(), contactIds: { ["alice"] })
        return ThreadViewModel(peerId: "alice", service: svc, preferences: defaults)
    }

    private struct NoopCrypto: ThreadCrypto {
        func sealText(peerId: String, text: String) async throws -> String { "" }
        func sealMedia(peerId: String, items: [MediaItem]) async throws -> String { "" }
        func openWire(_ wire: String, candidates: [String]) async -> OpenedWire? { nil }
    }

    private func contact(accepted: Bool, verified: Bool = false) -> AppGroupContact {
        AppGroupContact(id: "alice", displayName: "Alice", isVerified: verified,
                        acceptedInviteDigest: accepted ? "digest" : nil)
    }

    private func msg(_ dir: ChatMessage.Direction) -> ChatMessage {
        ChatMessage(id: UUID().uuidString, peerId: "alice", direction: dir, body: "x", timestamp: Date(),
                    status: dir == .incoming ? .received : .shared)
    }

    private func pick(_ vm: ThreadViewModel, _ c: AppGroupContact?, _ msgs: [ChatMessage] = [], loaded: Bool = true) -> ThreadBanner? {
        vm.banner(contact: c, messages: msgs, historyLoaded: loaded)
    }

    func testAcceptorWithoutIncomingWaitsForPeer() {
        XCTAssertEqual(pick(makeVM(), contact(accepted: true)), .waitingPeer)
        XCTAssertEqual(pick(makeVM(), contact(accepted: true), [msg(.outgoing)]), .waitingPeer)
    }

    func testAcceptorWithIncomingFallsToVerifyLater() {
        XCTAssertEqual(pick(makeVM(), contact(accepted: true), [msg(.incoming)]), .verifyLater)
        XCTAssertNil(pick(makeVM(), contact(accepted: true, verified: true), [msg(.incoming)]))
    }

    func testInitiatorWithoutOutgoingSaysHiThenVerifyLater() {
        XCTAssertEqual(pick(makeVM(), contact(accepted: false)), .sayHi)
        XCTAssertEqual(pick(makeVM(), contact(accepted: false), [msg(.outgoing)]), .verifyLater)
        XCTAssertNil(pick(makeVM(), contact(accepted: false, verified: true), [msg(.outgoing)]))
    }

    func testNoContactOrHistoryNotLoadedMeansNoBanner() {
        XCTAssertNil(pick(makeVM(), nil))
        XCTAssertNil(pick(makeVM(), contact(accepted: true), loaded: false))
    }

    func testDismissedWaitingPeerDoesNotFallThroughToVerifyLater() {
        let vm = makeVM(), c = contact(accepted: true)
        vm.dismissBanner(.waitingPeer, contact: c)
        XCTAssertNil(pick(vm, c))
    }

    func testDismissedSayHiFallsThroughToVerifyLater() {
        let vm = makeVM(), c = contact(accepted: false)
        vm.dismissBanner(.sayHi, contact: c)
        XCTAssertEqual(pick(vm, c), .verifyLater)
    }

    func testDismissalPersistsPerContactAndTypeUnderTheDocumentedKey() {
        let vm = makeVM()
        vm.dismissBanner(.verifyLater, contact: contact(accepted: true))
        XCTAssertEqual(defaults.stringArray(forKey: "cc.threadBannerDismissed.v1"), ["alice|verify_later"])
        let reopened = makeVM()
        XCTAssertNil(pick(reopened, contact(accepted: true, verified: false), [msg(.incoming)]))
        let other = AppGroupContact(id: "bob", displayName: "Bob", isVerified: false, acceptedInviteDigest: "d")
        XCTAssertEqual(reopened.banner(contact: other, messages: [msg(.incoming)], historyLoaded: true), .verifyLater)
    }
}
