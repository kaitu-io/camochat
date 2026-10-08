import Foundation
import Chencang

/// Self-loopback fixture for UAT. Runs the real PQXDH handshake locally between
/// two ephemeral identities (Bob = sender, Alice = recipient), then persists
/// both DR sessions and a contact mirror so a single device can encrypt-to-self
/// and decrypt-from-self.
///
/// V1 wire codec: `encodeTextFrame` (L2 text frame) → `SessionStore.encryptToBytes`
/// → raw L3 bytes. No blob layer, no server round-trip.
///
/// NOT for production: short-circuits real pairing. The contact ID and keys
/// are stable across calls only insofar as the App Group + Keychain outlive
/// the process — re-seeding overwrites prior state.
@MainActor
public enum UATLoopback {
    public static let contactId = "uat-alice"
    public static let displayName = "UAT 自测"
    public static let recvSessionId = "uat-alice-recv"
    /// Synthetic device-id for UAT contacts (server-pairing is gone; the field
    /// is retained in AppGroupContact for persistence compat only).
    private static let syntheticDeviceId = "uat-local"

    /// End-to-end loopback test: encode a text frame, encrypt it under Bob's
    /// session, decrypt it under Alice's, and assert the round-tripped text
    /// matches. Both sides run purely on-device — no network, no blob layer.
    public static func runE2E() async throws -> String {
        guard try AppGroupSync.shared.readContacts().contains(where: { $0.id == contactId }) else {
            return "❌ Run Seed first."
        }
        // Warm both sessions into the actor's cache from Keychain before the
        // bytes-domain encrypt/decrypt calls, which require an already-cached
        // session.
        _ = try await SessionStore.shared.session(for: contactId)
        _ = try await SessionStore.shared.session(for: recvSessionId)

        let text = "UAT 自测 \(Date())"
        let frame = encodeTextFrame(text: text)
        let ct = try await SessionStore.shared.encryptToBytes(plaintext: frame, for: contactId)
        guard let (pt, senderId) = await SessionStore.shared.decryptFromBytesAny(
            ciphertext: ct, candidates: [recvSessionId]
        ) else {
            return "❌ E2E: no session matched"
        }
        guard case let .text(value) = try decodeFrame(bytes: pt), value == text else {
            return "❌ E2E: decoded text mismatch"
        }
        return "✅ E2E OK — \(value) · sender=\(senderId)"
    }

    /// Seed a single-device loopback dogfood. Writes:
    ///
    ///  - the "UAT 自测" contact into the App Group mirror, marked verified,
    ///    with `deviceId` set to a synthetic local id;
    ///  - two Double Ratchet sessions into the Keychain — Bob keyed by the
    ///    contact's id (send path) and Alice keyed by a distinct label so
    ///    `SessionStore.decryptFromBytesAny` can find it on the receive path.
    ///
    /// Calling seed again rotates both ephemeral identities and overwrites
    /// the persisted sessions.
    public static func seed() async throws {
        let deviceId = syntheticDeviceId
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

        try await SessionStore.shared.save(bobSess, for: contactId)
        try await SessionStore.shared.save(aliceSess, for: recvSessionId)

        let contact = AppGroupContact(
            id: contactId,
            displayName: displayName,
            isVerified: true,
            deviceId: deviceId
        )
        try await ContactsStore.shared.add(contact)
    }
}
