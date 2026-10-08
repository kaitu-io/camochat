import XCTest
@testable import ChencangShared

@MainActor
final class IntakeRouterTests: XCTestCase {
    private var dir: URL!
    private var store: ChatStore!
    private var idCounter = 0

    private let kindOf: (String) -> PairingTransport.WireKind = {
        $0.hasPrefix("🔒FAKE") ? .session : ($0.hasPrefix("🔒PAIRINV") ? .pairingBundle : .unknown)
    }

    override func setUp() async throws {
        dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("cc-intakerouter-tests-\(UUID().uuidString)", isDirectory: true)
        store = try ChatStore(directory: dir)
        idCounter = 0
    }

    override func tearDown() async throws {
        try? FileManager.default.removeItem(at: dir)
    }

    private func makeRouter(contacts: [String], awaiting: Bool = false) -> IntakeRouter {
        let service = ChatService(
            store: store,
            crypto: EchoThreadCrypto(),
            contactIds: { contacts },
            now: { Date(timeIntervalSince1970: 42) },
            newId: { [weak self] in
                self!.idCounter += 1
                return "intake-id-\(self!.idCounter)"
            },
            isWire: { $0.hasPrefix("🔒FAKE") }
        )
        return IntakeRouter(chatService: service, contactIds: { contacts }, hasAwaitingInvites: { awaiting }, kindOf: kindOf)
    }

    func testSessionMessageDecryptsAndStores() async {
        let route = await makeRouter(contacts: ["alice"]).route("🔒FAKE:alice:你好")
        let thread = store.messages(for: "alice")
        XCTAssertEqual(thread.count, 1)
        XCTAssertEqual(thread.first?.status, .received)
        XCTAssertEqual(thread.first?.body, "你好")
        XCTAssertEqual(route, .openThread(peerId: "alice", messageId: thread.first!.id))
    }

    func testSessionMessageWithHeaderLineAndNicknamePrefix() async {
        let raw = "🔒 header line · open it\n小明：🔒FAKE:alice:hi"
        let route = await makeRouter(contacts: ["alice"]).route(raw)
        XCTAssertEqual(route, .openThread(peerId: "alice", messageId: "intake-id-1"))
    }

    func testNoContactsGivesNoContacts() async {
        let route = await makeRouter(contacts: []).route("🔒FAKE:alice:你好")
        XCTAssertEqual(route, .failed(.noContacts))
        XCTAssertTrue(store.messages(for: "alice").isEmpty)
    }

    func testUndecryptableWithContactsGivesCannotOpen() async {
        let route = await makeRouter(contacts: ["alice"]).route("🔒FAKE:bob:x")
        XCTAssertEqual(route, .failed(.cannotOpen))
        XCTAssertTrue(store.messages(for: "bob").isEmpty)
    }

    func testUndecryptableWithAwaitingInviteGivesPendingInvite() async {
        let route = await makeRouter(contacts: ["alice"], awaiting: true).route("🔒FAKE:bob:x")
        XCTAssertEqual(route, .failed(.pendingInvite))
    }

    func testNoContactsWithAwaitingInviteGivesPendingInvite() async {
        let route = await makeRouter(contacts: [], awaiting: true).route("🔒FAKE:alice:你好")
        XCTAssertEqual(route, .failed(.pendingInvite))
    }

    func testDecryptableMessageIgnoresAwaitingInvite() async {
        let route = await makeRouter(contacts: ["alice"], awaiting: true).route("🔒FAKE:alice:你好")
        XCTAssertEqual(route, .openThread(peerId: "alice", messageId: "intake-id-1"))
    }

    func testSaveFailureGivesSaveFailed() async throws {
        try FileManager.default.createDirectory(at: dir.appendingPathComponent("alice.json"),
                                                 withIntermediateDirectories: true)
        let route = await makeRouter(contacts: ["alice"]).route("🔒FAKE:alice:你好")
        XCTAssertEqual(route, .failed(.saveFailed))
    }

    func testPairingCodeIsHandedToWizardWithoutDecrypt() async {
        let route = await makeRouter(contacts: ["alice"]).route("🔒PAIRINV")
        XCTAssertEqual(route, .pairing(wire: "🔒PAIRINV"))
        XCTAssertTrue(store.messages(for: "alice").isEmpty)
        XCTAssertEqual(idCounter, 0)
    }

    func testPairingCodeRoutesEvenWithoutContacts() async {
        let route = await makeRouter(contacts: []).route("🔒PAIRINV")
        XCTAssertEqual(route, .pairing(wire: "🔒PAIRINV"))
    }

    func testPlainTextAndEmpty() async {
        let router = makeRouter(contacts: ["alice"])
        let plain = await router.route("hi")
        let empty = await router.route("")
        let broken = await router.route("🔒garbage")
        XCTAssertEqual(plain, .failed(.notOurs))
        XCTAssertEqual(empty, .failed(.incomplete))
        XCTAssertEqual(broken, .failed(.incomplete))
    }
}
