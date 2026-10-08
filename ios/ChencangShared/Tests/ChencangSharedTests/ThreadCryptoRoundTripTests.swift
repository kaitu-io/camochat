import XCTest
@testable import ChencangShared
import Chencang

/// 真 crypto 双端往返:复用 `SessionV1WireRoundTripTests` / `SessionStoreTests`
/// 的 PQXDH 双端会话构造,验证 `SessionThreadCrypto.sealText`/`openWire` 在一对
/// 真实 `Session` 上可互通(两个方向都要走通,DR 握手后是双向的)。
///
/// `@MainActor` 是有意的:PQXDH keygen 用到的大栈缓冲在异步协作池的小栈上会
/// SIGBUS,同款修复见 `SessionStoreTests`/`PairingCoordinatorTests`。
@MainActor
final class ThreadCryptoRoundTripTests: XCTestCase {

    func testSealTextAndOpenWireRoundTripBothDirections() async throws {
        let (bobSess, aliceSess) = try mintLoopbackSessionPair()

        let storeA = SessionStore(keychain: FakeKeychain())
        let storeB = SessionStore(keychain: FakeKeychain())

        // A(本地)通讯录里的联系人「bob」对应的会话对象是 aliceSess ——
        // A 用它加密出的密文,只有持有 bobSess 的一方(B)能解开;反之亦然。
        try await storeA.save(aliceSess, for: "bob-contact-id")
        try await storeB.save(bobSess, for: "alice-contact-id")

        let cryptoA = SessionThreadCrypto(store: storeA)
        let cryptoB = SessionThreadCrypto(store: storeB)

        // A → B
        let wire1 = try await cryptoA.sealText(peerId: "bob-contact-id", text: "你好,密信")
        XCTAssertTrue(wire1.hasPrefix("🔒"))
        let opened1 = await cryptoB.openWire(wire1, candidates: ["alice-contact-id"])
        XCTAssertEqual(opened1, OpenedWire(peerId: "alice-contact-id", content: .text("你好,密信")))

        // B → A(反向一条)
        let wire2 = try await cryptoB.sealText(peerId: "alice-contact-id", text: "收到,已核对指纹")
        let opened2 = await cryptoA.openWire(wire2, candidates: ["bob-contact-id"])
        XCTAssertEqual(opened2, OpenedWire(peerId: "bob-contact-id", content: .text("收到,已核对指纹")))
    }

    func testOpenWireReturnsNilOnGarbageWire() async throws {
        let (_, aliceSess) = try mintLoopbackSessionPair()
        let storeB = SessionStore(keychain: FakeKeychain())
        try await storeB.save(aliceSess, for: "alice-contact-id")
        let cryptoB = SessionThreadCrypto(store: storeB)

        // 不是 decodeWire 能识别的 wire → 提前 nil,不碰任何会话。
        let opened = await cryptoB.openWire("这只是普通文字", candidates: ["alice-contact-id"])
        XCTAssertNil(opened)
    }

    func testMediaFrameRoundTripsAsPendingMediaItems() async throws {
        let (bobSess, aliceSess) = try mintLoopbackSessionPair()
        let storeA = SessionStore(keychain: FakeKeychain())
        let storeB = SessionStore(keychain: FakeKeychain())
        try await storeA.save(aliceSess, for: "bob-contact-id")
        try await storeB.save(bobSess, for: "alice-contact-id")

        let blob = try encryptMediaBlob(plaintext: Data(repeating: 7, count: 1_000), kind: 2)
        let sent = MediaItem(index: 0, kind: .image, durMs: 0, width: 1920, height: 1080,
                             byteLen: blob.blob.count, blobSecret: blob.blobSecret, blobId: blob.blobId, state: .sealed)
        let wire = try await SessionThreadCrypto(store: storeA).sealMedia(peerId: "bob-contact-id", items: [sent])
        XCTAssertTrue(wire.hasPrefix("🔒"))

        let openedResult = await SessionThreadCrypto(store: storeB).openWire(wire, candidates: ["alice-contact-id"])
        let opened = try XCTUnwrap(openedResult)
        XCTAssertEqual(opened.peerId, "alice-contact-id")
        guard case let .media(kind, items) = opened.content else { return XCTFail("应解出媒体引用") }
        XCTAssertEqual(kind, .image)
        var expected = sent
        expected.state = .pending
        XCTAssertEqual(items, [expected])
    }

    // 一条消息里的 item 必须同 kind(spec §1.1);帧编码本身不禁止混 kind,收件方
    // 不能只信第一个 item——混了就当「不认识的形态」处理,落占位而不是乱猜。
    func testMixedKindMediaFrameBecomesUnsupported() async throws {
        let (bobSess, aliceSess) = try mintLoopbackSessionPair()
        let storeA = SessionStore(keychain: FakeKeychain())
        let storeB = SessionStore(keychain: FakeKeychain())
        try await storeA.save(aliceSess, for: "bob-contact-id")
        try await storeB.save(bobSess, for: "alice-contact-id")

        let imageBlob = try encryptMediaBlob(plaintext: Data(repeating: 1, count: 100), kind: 2)
        let voiceBlob = try encryptMediaBlob(plaintext: Data(repeating: 2, count: 100), kind: 1)
        let imageItem = MediaItem(index: 0, kind: .image, durMs: 0, width: 640, height: 480,
                                  byteLen: imageBlob.blob.count, blobSecret: imageBlob.blobSecret,
                                  blobId: imageBlob.blobId, state: .sealed)
        let voiceItem = MediaItem(index: 1, kind: .voice, durMs: 3_000, width: 0, height: 0,
                                  byteLen: voiceBlob.blob.count, blobSecret: voiceBlob.blobSecret,
                                  blobId: voiceBlob.blobId, state: .sealed)

        let wire = try await SessionThreadCrypto(store: storeA).sealMedia(peerId: "bob-contact-id",
                                                                          items: [imageItem, voiceItem])
        let opened = await SessionThreadCrypto(store: storeB).openWire(wire, candidates: ["alice-contact-id"])
        XCTAssertEqual(opened, OpenedWire(peerId: "alice-contact-id", content: .unsupported))
    }

    func testUndecodableFrameAfterDecryptBecomesUnsupported() async throws {
        let (bobSess, aliceSess) = try mintLoopbackSessionPair()
        let storeA = SessionStore(keychain: FakeKeychain())
        let storeB = SessionStore(keychain: FakeKeychain())
        try await storeA.save(aliceSess, for: "bob-contact-id")
        try await storeB.save(bobSess, for: "alice-contact-id")

        // 合法 L2 头 + 未知 msg_type 0x7F:DR 能解开,decodeFrame 必然报错。
        let ct = try await storeA.encryptToBytesPersisting(plaintext: Data([0xCC, 0x10, 0x7F, 0x00]), for: "bob-contact-id")
        let opened = await SessionThreadCrypto(store: storeB).openWire(encodeWire(ciphertext: ct),
                                                                       candidates: ["alice-contact-id"])
        XCTAssertEqual(opened, OpenedWire(peerId: "alice-contact-id", content: .unsupported))
    }

    /// Run the full PQXDH handshake and produce a (Bob, Alice) Session pair.
    /// Copied from `SessionV1WireRoundTripTests`/`SessionStoreTests` — this exact
    /// handshake boilerplate is already duplicated per-test-file in this target
    /// rather than factored out, so this follows the established convention.
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
