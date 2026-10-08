import XCTest
@testable import ChencangShared
import Chencang

/// Runs `@MainActor` so `mintLoopbackSessionPair`'s PQXDH handshake (post-quantum
/// keygen uses large stack buffers) executes on the main thread's big stack rather
/// than the async cooperative pool's small one, which SIGBUSes — same fix as
/// `PairingCoordinatorTests`.
@MainActor
final class SessionStoreTests: XCTestCase {
    func testMissReturnsNil() async throws {
        let store = SessionStore(keychain: FakeKeychain())
        let s = try await store.session(for: "no-such-id")
        XCTAssertNil(s)
    }

    func testDecryptFromBytesAnyEmptyCacheReturnsNil() async {
        let store = SessionStore(keychain: FakeKeychain())
        let result = await store.decryptFromBytesAny(ciphertext: Data([0x00, 0x01, 0x02]))
        XCTAssertNil(result)
    }

    // MARK: - Task 2: cold-process candidate routing + persist-on-advance + cache invalidation

    /// Cold cache + candidates: A encrypts, B's SessionStore starts with only
    /// Keychain state (nothing warmed into the in-memory cache — simulates a
    /// freshly-launched process, e.g. the Action Extension). Passing B's own
    /// contact id for A as a candidate must route to the right session and
    /// return the sender id.
    func testDecryptFromBytesAnyWithCandidatesRoutesFromColdKeychain() async throws {
        let (senderSession, localSession) = try mintLoopbackSessionPair()
        let keychain = FakeKeychain()
        try await SessionStore(keychain: keychain).save(localSession, for: "bob-peer-id")

        let plaintext = Data("hello from A".utf8)
        let ct = try senderSession.encryptToBytes(plaintext: plaintext)

        // Fresh store instance: empty in-memory cache, only Keychain state.
        let coldStore = SessionStore(keychain: keychain)
        let result = await coldStore.decryptFromBytesAny(ciphertext: ct, candidates: ["bob-peer-id"])
        let (pt, senderId) = try XCTUnwrap(result)
        XCTAssertEqual(pt, plaintext)
        XCTAssertEqual(senderId, "bob-peer-id")
    }

    /// Persist-on-advance: after a candidate hit, the advanced receiving-chain
    /// state must be written back to the Keychain immediately (not just kept
    /// in memory) — otherwise the next inbound message can't be routed by a
    /// brand-new SessionStore instance (e.g. the App coming back to
    /// foreground after the Action Extension advanced the ratchet).
    func testDecryptFromBytesAnyWithCandidatesPersistsAdvanceForNextMessage() async throws {
        let (senderSession, localSession) = try mintLoopbackSessionPair()
        let keychain = FakeKeychain()
        try await SessionStore(keychain: keychain).save(localSession, for: "bob-peer-id")

        let ct1 = try senderSession.encryptToBytes(plaintext: Data("message one".utf8))
        let store1 = SessionStore(keychain: keychain)
        let hit1 = await store1.decryptFromBytesAny(ciphertext: ct1, candidates: ["bob-peer-id"])
        XCTAssertNotNil(hit1, "first message must decrypt")

        let stateAfterHit1 = try XCTUnwrap(keychain.items["sess_bob-peer-id"])

        let ct2 = try senderSession.encryptToBytes(plaintext: Data("message two".utf8))

        // Brand-new SessionStore, same Keychain, empty cache: this only works
        // if the advance from message 1 actually made it to the Keychain.
        let store2 = SessionStore(keychain: keychain)
        let hit2 = await store2.decryptFromBytesAny(ciphertext: ct2, candidates: ["bob-peer-id"])
        let (pt2, senderId2) = try XCTUnwrap(hit2)
        XCTAssertEqual(pt2, Data("message two".utf8))
        XCTAssertEqual(senderId2, "bob-peer-id")

        // The second hit must persist yet another advance — proves this isn't
        // a stale write from the first hit being read twice.
        let stateAfterHit2 = try XCTUnwrap(keychain.items["sess_bob-peer-id"])
        XCTAssertNotEqual(stateAfterHit1, stateAfterHit2)
    }

    /// invalidateCache: after save + invalidate, session(for:) must still
    /// return non-nil by reloading from the Keychain — this is what the main
    /// App calls on foreground return so it never reasons about a stale
    /// in-memory session the Action Extension already advanced.
    func testInvalidateCacheForcesReloadFromKeychain() async throws {
        let (_, localSession) = try mintLoopbackSessionPair()
        let keychain = FakeKeychain()
        let store = SessionStore(keychain: keychain)
        try await store.save(localSession, for: "bob-peer-id")

        await store.invalidateCache()

        let reloaded = try await store.session(for: "bob-peer-id")
        XCTAssertNotNil(reloaded)

        // Discriminating step: if invalidateCache() were a no-op, the still-warm
        // cache would satisfy the lookup above even with the Keychain entry gone.
        // Remove the Keychain entry, invalidate again, and prove resolution now
        // genuinely goes through the Keychain (returns nil) rather than a stale
        // in-memory session.
        try keychain.delete(key: "sess_bob-peer-id")
        await store.invalidateCache()
        let afterDelete = try await store.session(for: "bob-peer-id")
        XCTAssertNil(afterDelete, "cache must have been dropped — nothing left to fall back on but the (now-empty) Keychain")
    }

    // MARK: - encryptToBytesPersisting

    func testEncryptToBytesPersistingWritesAdvancedStateToKeychain() async throws {
        let (senderSession, _) = try mintLoopbackSessionPair()
        let keychain = FakeKeychain()
        let store = SessionStore(keychain: keychain)
        try await store.save(senderSession, for: "peer-id")

        // Captured BEFORE encryptToBytesPersisting — this is the state save()
        // already wrote. If encryptToBytesPersisting's own keychain.write were
        // deleted, this snapshot would equal the post-call bytes below, since
        // nothing else touches the Keychain in between.
        let stateBeforeEncrypt = try XCTUnwrap(keychain.items["sess_peer-id"])

        let ct = try await store.encryptToBytesPersisting(plaintext: Data("hi".utf8), for: "peer-id")
        XCTAssertFalse(ct.isEmpty)

        let stateAfterEncrypt = try XCTUnwrap(keychain.items["sess_peer-id"])
        XCTAssertNotEqual(stateBeforeEncrypt, stateAfterEncrypt, "encryptToBytesPersisting must write the send-chain-advanced state, not leave save()'s stale snapshot")

        // A cold store reading the same Keychain must see the persisted
        // (post-encrypt, chain-advanced) state, not the pre-send snapshot.
        let coldStore = SessionStore(keychain: keychain)
        let reloaded = try await coldStore.session(for: "peer-id")
        XCTAssertNotNil(reloaded)
    }

    // MARK: - M4 Task 8: remove(for:)

    func testRemoveDeletesFromCacheAndKeychain() async throws {
        let (_, localSession) = try mintLoopbackSessionPair()
        let keychain = FakeKeychain()
        let store = SessionStore(keychain: keychain)
        try await store.save(localSession, for: "peer-id")
        XCTAssertNotNil(keychain.items["sess_peer-id"])

        await store.remove(for: "peer-id")

        XCTAssertNil(keychain.items["sess_peer-id"])
        let reloaded = try await store.session(for: "peer-id")
        XCTAssertNil(reloaded, "cache must be cleared too, not just the Keychain — nothing left to fall back on")
    }

    func testRemoveOnUnknownContactIdIsNoop() async throws {
        let store = SessionStore(keychain: FakeKeychain())

        await store.remove(for: "never-existed")  // must not throw/crash

        let result = try await store.session(for: "never-existed")
        XCTAssertNil(result)
    }

    func testEncryptToBytesPersistingThrowsNoSessionWhenMissing() async {
        let store = SessionStore(keychain: FakeKeychain())
        do {
            _ = try await store.encryptToBytesPersisting(plaintext: Data(), for: "missing")
            XCTFail("expected SessionStoreError.noSession")
        } catch SessionStoreError.noSession(let id) {
            XCTAssertEqual(id, "missing")
        } catch {
            XCTFail("unexpected error: \(error)")
        }
    }

    /// Runs the real PQXDH handshake locally and returns a (sender, local)
    /// Session pair: `sender.encryptToBytes` produces ciphertext that
    /// `local.decryptFromBytes` can decrypt. Copied from
    /// `SessionV1WireRoundTripTests.mintLoopbackSessionPair` (and
    /// `UATLoopback.seed`) — this exact handshake boilerplate is already
    /// duplicated per-test-file in this target rather than factored out, so
    /// this follows the established convention instead of reinventing one.
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
