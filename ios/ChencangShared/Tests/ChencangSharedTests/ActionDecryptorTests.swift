import XCTest
@testable import ChencangShared
import Chencang

/// Action Extension 编排逻辑单测。用 `EchoThreadCrypto`(定义于
/// `ChatServiceTests.swift`,同模块内可见)覆盖 open() 的各条分支;末尾再加一条
/// 真 PQXDH 会话回环,证明与 `SessionThreadCrypto` 接线正确(不只是跟 fake 对得上)。
final class ActionDecryptorTests: XCTestCase {
    /// 注入的分类器:`🔒FAKE*` 是会话消息,`🔒PAIRINV`/`🔒PAIRRSP` 是配对码,其余不认识。
    static func fakeKind(_ s: String) -> PairingTransport.WireKind {
        if s.hasPrefix("🔒FAKE") { return .session }
        if s == "🔒PAIRINV" { return .pairingBundle }
        if s == "🔒PAIRRSP" { return .pairingHeader }
        return .unknown
    }

    private let alice = [AppGroupContact(id: "alice", displayName: "爱丽丝", isVerified: true)]

    private func makeDecryptor(
        crypto: ThreadCrypto = EchoThreadCrypto(),
        contacts: [AppGroupContact],
        inbox: InboxWriting,
        awaiting: Bool = false,
        now: Date = Date(timeIntervalSince1970: 42),
        newId: String = "entry-1"
    ) -> ActionDecryptor {
        ActionDecryptor(
            crypto: crypto,
            readContacts: { contacts },
            inbox: inbox,
            hasAwaitingInvites: { awaiting },
            now: { now },
            newId: { newId },
            kindOf: Self.fakeKind
        )
    }

    private func opened(_ result: ActionIntakeResult, file: StaticString = #filePath, line: UInt = #line) throws -> ActionDecryptor.Opened {
        guard case let .opened(o) = result else {
            XCTFail("expected opened, got \(result)", file: file, line: line)
            throw NSError(domain: "ActionDecryptorTests", code: 0)
        }
        return o
    }

    func testUndecryptableWithAwaitingInviteGivesPendingInvite() async {
        let d = makeDecryptor(crypto: EchoThreadCrypto(), contacts: alice, inbox: AppGroupInbox(defaults: FakeAppGroupDefaults()), awaiting: true)
        let result = await d.open("🔒FAKE:bob:x")
        XCTAssertEqual(result, .failed(.pendingInvite))
    }

    func testNoContactsWithAwaitingInviteGivesPendingInvite() async {
        let d = makeDecryptor(contacts: [], inbox: AppGroupInbox(defaults: FakeAppGroupDefaults()), awaiting: true)
        let result = await d.open("🔒FAKE:alice:hi")
        XCTAssertEqual(result, .failed(.pendingInvite))
    }

    // ① 成功路径:写入 inbox 一条,Opened 字段正确。
    func testOpenSuccessWritesInboxEntryAndReturnsOpened() async throws {
        let inbox = AppGroupInbox(defaults: FakeAppGroupDefaults())
        let decryptor = makeDecryptor(contacts: alice, inbox: inbox)

        let opened = try opened(await decryptor.open("🔒FAKE:alice:你好"))
        XCTAssertEqual(opened.peerId, "alice")
        XCTAssertEqual(opened.peerName, "爱丽丝")
        XCTAssertEqual(opened.text, "你好")
        XCTAssertEqual(opened.entryId, "entry-1")

        let drained = inbox.drain()
        XCTAssertEqual(drained.count, 1)
        XCTAssertEqual(drained[0], InboxEntry(id: "entry-1", peerId: "alice", body: "你好", timestamp: Date(timeIntervalSince1970: 42)))
    }

    // ② 聊天软件里选中可能带上前后杂字,从 🔒 起截取仍能成功。
    func testOpenSucceedsWithGarbagePrefixBeforeLock() async throws {
        let decryptor = makeDecryptor(contacts: alice, inbox: AppGroupInbox(defaults: FakeAppGroupDefaults()))

        let opened = try opened(await decryptor.open("abc🔒FAKE:alice:你好"))
        XCTAssertEqual(opened.peerId, "alice")
        XCTAssertEqual(opened.text, "你好")
    }

    // ③ 非 🔒 文字 → notOurs。
    func testPlainTextIsNotOurs() async {
        let decryptor = makeDecryptor(contacts: alice, inbox: AppGroupInbox(defaults: FakeAppGroupDefaults()))
        let result = await decryptor.open("这只是普通文字")
        XCTAssertEqual(result, .failed(.notOurs))
    }

    func testEmptySelectionIsIncomplete() async {
        let decryptor = makeDecryptor(contacts: alice, inbox: AppGroupInbox(defaults: FakeAppGroupDefaults()))
        let result = await decryptor.open("")
        XCTAssertEqual(result, .failed(.incomplete))
    }

    func testLockGarbageIsIncomplete() async {
        let decryptor = makeDecryptor(contacts: alice, inbox: AppGroupInbox(defaults: FakeAppGroupDefaults()))
        let result = await decryptor.open("🔒zz")
        XCTAssertEqual(result, .failed(.incomplete))
    }

    func testNoContactsGivesNoContacts() async {
        let inbox = AppGroupInbox(defaults: FakeAppGroupDefaults())
        let decryptor = makeDecryptor(crypto: NeverCalledCrypto(), contacts: [], inbox: inbox)
        let result = await decryptor.open("🔒FAKE:alice:hi")
        XCTAssertEqual(result, .failed(.noContacts))
        XCTAssertTrue(inbox.drain().isEmpty)
    }

    // ④ 是会话消息但候选联系人对不上 → cannotOpen,且 inbox 不写。
    func testNoSessionMatchGivesCannotOpen() async {
        let inbox = AppGroupInbox(defaults: FakeAppGroupDefaults())
        // "bob" 不在候选联系人里 → EchoThreadCrypto.openWire 返回 nil。
        let contacts = [AppGroupContact(id: "someone-else", displayName: "别人", isVerified: true)]
        let decryptor = makeDecryptor(contacts: contacts, inbox: inbox)

        let result = await decryptor.open("🔒FAKE:bob:你好")
        XCTAssertEqual(result, .failed(.cannotOpen))
        XCTAssertTrue(inbox.drain().isEmpty)
    }

    // 配对码:原样交还,不碰 crypto、不写收件箱(扩展不握手)。
    func testInviteIsHandedBackWithoutHandshake() async {
        let inbox = AppGroupInbox(defaults: FakeAppGroupDefaults())
        let decryptor = makeDecryptor(crypto: NeverCalledCrypto(), contacts: alice, inbox: inbox)
        let result = await decryptor.open("🔒 陈仓配对码 · 复制整条消息后打开陈仓 https://site.test/\n🔒PAIRINV")
        XCTAssertEqual(result, .pairingCode(.invite, wire: "🔒PAIRINV"))
        XCTAssertTrue(inbox.drain().isEmpty)
    }

    func testResponseIsHandedBack() async {
        let inbox = AppGroupInbox(defaults: FakeAppGroupDefaults())
        let decryptor = makeDecryptor(crypto: NeverCalledCrypto(), contacts: [], inbox: inbox)
        let result = await decryptor.open("🔒PAIRRSP")
        XCTAssertEqual(result, .pairingCode(.response, wire: "🔒PAIRRSP"))
        XCTAssertTrue(inbox.drain().isEmpty)
    }

    func testUnknownPeerNameFallsBackToL10n() async throws {
        let decryptor = makeDecryptor(crypto: GhostPeerCrypto(), contacts: alice,
                                      inbox: AppGroupInbox(defaults: FakeAppGroupDefaults()))
        let opened = try opened(await decryptor.open("🔒FAKE:ghost:hi"))
        XCTAssertEqual(opened.peerId, "ghost")
        XCTAssertEqual(opened.peerName, L10n.commonContactFallback)
    }

    // ⑥ R1 两行媒体形态:只落引用(pending),不下载;Opened 带种类与张数。
    func testOpenMediaTwoLineFormWritesReferenceEntry() async throws {
        let inbox = AppGroupInbox(defaults: FakeAppGroupDefaults())
        let decryptor = makeDecryptor(contacts: alice, inbox: inbox)

        let text = "🔒 陈仓加密3 张图片 · 24小时内有效 https://site.test/m/fakeblob0\n🔒FAKEMEDIA:alice:2:3"
        let opened = try opened(await decryptor.open(text))

        XCTAssertEqual(opened.kind, .image)
        XCTAssertEqual(opened.mediaCount, 3)
        let entry = try XCTUnwrap(inbox.drain().first)
        XCTAssertEqual(entry.kind, .image)
        XCTAssertEqual(entry.media?.count, 3)
        XCTAssertEqual(entry.media?.map(\.state), [.pending, .pending, .pending])
        XCTAssertEqual(entry.body, "")
    }

    // ⑦ 解得开但帧不认识 → 仍写一条占位收件(R3),绝不丢。
    func testOpenUnsupportedWritesPlaceholderEntry() async throws {
        let inbox = AppGroupInbox(defaults: FakeAppGroupDefaults())
        let decryptor = makeDecryptor(contacts: alice, inbox: inbox)

        let opened = try opened(await decryptor.open("🔒FAKEUNSUP:alice"))

        XCTAssertEqual(opened.kind, .unsupported)
        XCTAssertEqual(opened.text, "", "占位文案在显示时按语言取")
        XCTAssertEqual(inbox.drain().first?.kind, .unsupported)
    }

    // ⑧ 收件箱写入失败——文字/占位仍然把已解出的内容给用户看(棘轮已经推进,这条
    // 加密消息不会有第二次机会),不因为存不进收件箱就藏起来。
    func testOpenTextStillSucceedsWhenInboxAppendFails() async throws {
        let decryptor = makeDecryptor(contacts: alice, inbox: FailingInbox())
        let opened = try opened(await decryptor.open("🔒FAKE:alice:你好"))
        XCTAssertEqual(opened.text, "你好")
    }

    // ⑨ 收件箱写入失败 + 媒体引用——收件箱是它唯一的落脚点,写不进去就是真丢了,
    // 必须给用户一个能感知、能重试的失败,而不是伪装成功。
    func testOpenMediaReturnsSaveFailedWhenInboxAppendFails() async {
        let decryptor = makeDecryptor(contacts: alice, inbox: FailingInbox())
        let result = await decryptor.open("🔒FAKEMEDIA:alice:2:1")
        XCTAssertEqual(result, .failed(.saveFailed))
    }

    // ⑤ 真回环:两端真实 PQXDH Session 走 SessionThreadCrypto,证明与生产实现接线正确。
    // `@MainActor` 是有意的:PQXDH keygen 用到的大栈缓冲在异步协作池的小栈上会
    // SIGBUS,同款修复见 SessionStoreTests / PairingCoordinatorTests / ThreadCryptoRoundTripTests。
    @MainActor
    func testOpenWithRealSessionThreadCryptoRoundTrips() async throws {
        let (bobSess, aliceSess) = try mintLoopbackSessionPair()

        let storeA = SessionStore(keychain: FakeKeychain())
        let storeB = SessionStore(keychain: FakeKeychain())
        try await storeA.save(aliceSess, for: "bob-contact-id")
        try await storeB.save(bobSess, for: "alice-contact-id")

        let cryptoA = SessionThreadCrypto(store: storeA)
        let wire = try await cryptoA.sealText(peerId: "bob-contact-id", text: "在密信中查看我")

        let inbox = AppGroupInbox(defaults: FakeAppGroupDefaults())
        let contacts = [AppGroupContact(id: "alice-contact-id", displayName: "爱丽丝", isVerified: true)]
        let decryptor = ActionDecryptor(
            crypto: SessionThreadCrypto(store: storeB),
            readContacts: { contacts },
            inbox: inbox,
            hasAwaitingInvites: { false }
        )

        let opened = try opened(await decryptor.open(wire))
        XCTAssertEqual(opened.peerId, "alice-contact-id")
        XCTAssertEqual(opened.peerName, "爱丽丝")
        XCTAssertEqual(opened.text, "在密信中查看我")
        XCTAssertEqual(inbox.drain().count, 1)
    }

    /// `AppGroupInbox.append` 的 encode 实际上不会失败(见 `ActionDecryptor.open` 里
    /// 的注释),所以「写入失败」只能靠注入一个假 `InboxWriting` 来复现。
    private struct FailingInbox: InboxWriting {
        func append(_ entry: InboxEntry) throws {
            throw NSError(domain: "ActionDecryptorTests.FailingInbox", code: 1)
        }
    }

    /// 配对码 / 无联系人路径绝不能走到解密。
    private struct NeverCalledCrypto: ThreadCrypto {
        func sealText(peerId: String, text: String) async throws -> String { XCTFail("sealText called"); return "" }
        func sealMedia(peerId: String, items: [MediaItem]) async throws -> String { XCTFail("sealMedia called"); return "" }
        func openWire(_ wire: String, candidates: [String]) async -> OpenedWire? { XCTFail("openWire called"); return nil }
    }

    /// 解出的发件人不在联系人表里(名字查不到),用来验证名字兜底。
    private struct GhostPeerCrypto: ThreadCrypto {
        func sealText(peerId: String, text: String) async throws -> String { "" }
        func sealMedia(peerId: String, items: [MediaItem]) async throws -> String { "" }
        func openWire(_ wire: String, candidates: [String]) async -> OpenedWire? {
            OpenedWire(peerId: "ghost", content: .text("hi"))
        }
    }

    /// Run the full PQXDH handshake and produce a (Bob, Alice) Session pair.
    /// Copied from `ThreadCryptoRoundTripTests`/`SessionV1WireRoundTripTests` — this
    /// exact handshake boilerplate is already duplicated per-test-file in this
    /// target rather than factored out, so this follows the established convention.
    private func mintLoopbackSessionPair() throws -> (Session, Session) {
        let bobIk = SecretIdentity()
        let aliceIk = SecretIdentity()
        let aliceSpk = try SecretSignedPreKey(ik: aliceIk, spkVersion: 1)
        let aliceOpk = SecretOneTimePreKey(opkIndex: 7)

        let bundle = PreKeyBundle(
            ik: aliceIk.publicIdentity(),
            spk: aliceSpk.publicForm(),
            opk: aliceOpk.publicForm(),
            inviterUsername: "alice",
            inviteId: Data(repeating: 0x11, count: 16),
            pairingNonce: Data(repeating: 0x22, count: 16)
        )

        let initOut = try deriveInitiatorHandshake(bobIk: bobIk, aliceBundle: bundle)
        let srkAlice = try deriveResponderHandshake(
            aliceIk: aliceIk,
            aliceSpk: aliceSpk,
            aliceOpk: aliceOpk,
            bobIkPub: initOut.bobIdentityPublic,
            bobEkXPub: initOut.ekX25519Pub,
            bobEkKemPub: initOut.ekMlkemPub,
            kemCtToSpk: initOut.kemCtToSpk,
            kemCtToIk: initOut.kemCtToIk,
            kemCtToOpk: initOut.kemCtToOpk,
            pairingNonce: Data(repeating: 0x22, count: 16)
        )

        let sid = Data([9, 9, 9, 9, 9])
        let bobSess = try Session.initiatorAfterHandshake(
            sessionRootKey: initOut.sessionRootKey,
            sessionId: sid,
            aliceIkPublic: aliceIk.publicIdentity(),
            ekX25519Secret: initOut.ekX25519Secret!,
            ekMlkemSecret: initOut.ekMlkemSecret!
        )
        let aliceSess = try Session.responderAfterHandshake(
            sessionRootKey: srkAlice,
            sessionId: sid,
            initiatorOutput: initOut
        )
        return (bobSess, aliceSess)
    }
}
