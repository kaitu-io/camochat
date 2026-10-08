import Foundation
import CryptoKit
import Security
import Chencang

/// Zero-server, in-band classical 2-round pairing engine (iOS port of
/// chencang-android's `InbandPairing`, validated two-device on real hardware).
///
/// Two devices establish a Double-Ratchet session by exchanging exactly two
/// blobs over ANY channel the user already trusts (paste / QR / DM) — no
/// Chencang server, no prekey publishing, no network at all.
///
/// Role mapping (matches chencang-core's classical semantics, where the
/// bundle-receiver "Bob" = initiator and the bundle-creator "Alice" = responder):
///
///  - **A = inviter = responder.** Builds the invite bundle (``buildInviteBundle``,
///    Round 1) and later runs ``complete(aIk:aSpk:pairingNonce:headerBytes:)`` on
///    B's response header (Round 2 completion) via `deriveResponderClassical` +
///    `verifyConfirmTag`.
///  - **B = invitee = initiator.** Receives A's bundle, runs
///    ``accept(bIk:bundleBytes:bDisplayName:)``: `deriveInitiatorClassical`,
///    builds the response header carrying its key material + a key-confirmation
///    tag, and constructs its session.
///
/// **A is stateless between rounds.** The bundle uses no per-pairing ephemeral
/// one-time prekey (`opk = nil`); X3DH runs on A's long-lived IK + SPK only. So A
/// retains nothing secret while waiting for B — just the PUBLIC 16-byte pairing
/// nonce (also embedded in the bundle). It reloads its IK/SPK from disk to call
/// ``complete(aIk:aSpk:pairingNonce:headerBytes:)``, surviving the app
/// process being killed during the wait.
///
/// The session id is chosen by B (initiator), carried in the header, and read
/// back by A — so both sides build their Session with the same id.
///
/// Secrets (SRK, ephemeral X25519 secret, transcript, confirm tags) are never
/// logged.
public enum InbandPairing {

    /// Wire version + suite for the classical in-band path (must match core).
    private static let version: UInt8 = 1
    private static let suiteId: UInt8 = 1
    private static let pairingNonceLen = 16
    private static let sessionIdLen = 5

    /// Round-1 output held by the inviter (A) between ``buildInviteBundle`` and
    /// ``complete(aIk:aSpk:pairingNonce:headerBytes:)``. Carries only PUBLIC
    /// bytes — there is nothing secret to retain or drop, so A survives a process
    /// kill while waiting for B. The pairing nonce is also embedded inside
    /// ``bundleBytes``; it is surfaced here so A can persist it cheaply for the
    /// stateless complete.
    public struct PendingInvite {
        /// Encoded classical bundle to deliver to B (paste / QR).
        public let bundleBytes: Data
        /// PUBLIC 16-byte pairing nonce (also embedded in ``bundleBytes``).
        public let pairingNonce: Data
    }

    /// Result of B accepting A's invite.
    public struct AcceptResult {
        /// Encoded response header to deliver back to A.
        public let headerBytes: Data
        /// B's freshly established session. The caller OWNS this native handle
        /// and must persist it; the underlying Rust handle frees on `deinit`.
        public let session: Session
        /// 8-emoji safety fingerprint over the session root key.
        public let emoji: [String]
        /// Local classical contact id for the peer (A).
        public let peerFingerprintHex: String
        /// Routing/dedup id for the peer; equals ``peerFingerprintHex``.
        public let peerDeviceId: String
    }

    /// Result of A completing the pairing against B's header.
    public struct CompleteResult {
        /// A's freshly established session. The caller OWNS this native handle
        /// and must persist it; the underlying Rust handle frees on `deinit`.
        public let session: Session
        /// 8-emoji safety fingerprint over the session root key.
        public let emoji: [String]
        /// Local classical contact id for the peer (B).
        public let peerFingerprintHex: String
        /// Routing/dedup id for the peer; equals ``peerFingerprintHex``.
        public let peerDeviceId: String
    }

    /// Thrown when key confirmation fails — the two sides did NOT agree.
    public struct PairingFailedError: Error, Equatable {
        public let message: String
        public init(_ message: String) { self.message = message }
    }

    /// Local, deterministic classical contact id for a peer, derived purely from
    /// its classical identity-key bytes. This is NOT the hybrid post-quantum
    /// fingerprint (a classical bundle never carries those four keys) — it is a
    /// LOCAL routing/dedup key. The real cross-device security check is the
    /// SRK-derived 8-emoji safety number, which already matches on both sides.
    static func classicalFingerprintHex(ed25519: Data, x25519: Data) -> String {
        let digest = SHA256.hash(data: ed25519 + x25519)
        return digest.prefix(16).map { String(format: "%02x", $0) }.joined()
    }

    /// 本机身份的联系人指纹(对方记录里「我」的 id 就是这个);复用既有摘要函数。
    public static func localFingerprintHex(_ identity: SecretIdentity) -> String {
        let pub = identity.publicIdentity()
        return classicalFingerprintHex(ed25519: pub.ikSigEd25519, x25519: pub.ikDhX25519)
    }

    /// Cryptographically-secure random bytes (pairing nonce / session id).
    private static func randomBytes(_ count: Int) -> Data {
        var bytes = [UInt8](repeating: 0, count: count)
        let status = SecRandomCopyBytes(kSecRandomDefault, count, &bytes)
        precondition(status == errSecSuccess, "SecRandomCopyBytes failed: \(status)")
        return Data(bytes)
    }

    /// Round 1 (A side). Mint an invite bundle. No per-pairing ephemeral OPK is
    /// generated (X3DH uses A's long-lived IK + SPK only), so the returned
    /// ``PendingInvite`` holds only public bytes.
    public static func buildInviteBundle(
        aIk: SecretIdentity,
        aSpk: SecretSignedPreKey,
        inviterUsername: String
    ) throws -> PendingInvite {
        let pairingNonce = randomBytes(pairingNonceLen)

        let ikPub = aIk.publicIdentity()
        let spkPub = aSpk.publicForm()

        let bundle = ClassicalPreKeyBundle(
            version: version,
            suiteId: suiteId,
            ik: ClassicalPublicIdentity(
                ed25519: ikPub.ikSigEd25519,
                x25519: ikPub.ikDhX25519
            ),
            spk: ClassicalSignedPreKey(
                x25519: spkPub.spkX25519,
                sigEd25519: spkPub.ed25519SigClassical,
                epoch: spkPub.spkVersion
            ),
            opk: nil,
            pairingNonce: pairingNonce,
            inviterUsername: inviterUsername
        )

        return PendingInvite(
            bundleBytes: try encodeClassicalBundle(bundle: bundle),
            pairingNonce: pairingNonce
        )
    }

    /// Round 2a (B side). Accept A's bundle: derive the session root key, build
    /// the response header (with a key-confirmation tag), and construct B's
    /// session. B picks the session id.
    public static func accept(
        bIk: SecretIdentity,
        bundleBytes: Data,
        bDisplayName: String?
    ) throws -> AcceptResult {
        let bundle = try decodeClassicalBundle(bytes: bundleBytes)
        guard bundle.version == version else {
            throw PairingError.malformed("unsupported bundle version \(bundle.version)")
        }
        guard bundle.suiteId == suiteId else {
            throw PairingError.malformed("unsupported suite \(bundle.suiteId)")
        }

        let initOut = try deriveInitiatorClassical(bobIk: bIk, aliceBundle: bundle)

        let sessionId = randomBytes(sessionIdLen)
        let confirmB = try computeConfirmTag(
            srk: initOut.sessionRootKey,
            transcript: initOut.transcript,
            who: confirmWhoInitiator()
        )

        // initOut.bobIk is the hybrid PublicIdentity of B; project to classical.
        let bClassicalIk = ClassicalPublicIdentity(
            ed25519: initOut.bobIk.ikSigEd25519,
            x25519: initOut.bobIk.ikDhX25519
        )
        let header = ClassicalInbandHeader(
            version: version,
            suiteId: suiteId,
            bobIk: bClassicalIk,
            ekX25519Pub: initOut.ekX25519Pub,
            sessionId: sessionId,
            confirmB: confirmB,
            bobDisplayName: bDisplayName
        )
        let headerBytes = try encodeClassicalHeader(header: header)

        let session = try Session.initiatorAfterHandshakeClassical(
            sessionRootKey: initOut.sessionRootKey,
            sessionId: sessionId,
            aliceIkDhX25519: bundle.ik.x25519, // A's (inviter) classical IK x25519
            ekX25519Secret: initOut.ekX25519Secret
        )

        // Peer is A: derive its local contact id from the bundle's classical IK.
        let peerFp = classicalFingerprintHex(ed25519: bundle.ik.ed25519, x25519: bundle.ik.x25519)

        return AcceptResult(
            headerBytes: headerBytes,
            session: session,
            emoji: try deriveSafetyEmoji(sessionSecret: initOut.sessionRootKey),
            peerFingerprintHex: peerFp,
            peerDeviceId: peerFp
        )
    }

    /// Round 2b (A side). Complete the pairing against B's response header:
    /// re-derive the session root key from A's reloaded IK/SPK + the public
    /// pairing nonce, verify B's key-confirmation tag, and construct A's session.
    /// Stateless — nothing secret was retained across the wait.
    ///
    /// - Throws: ``PairingFailedError`` if the confirmation tag does not verify
    ///   (tamper / wrong peer).
    public static func complete(
        aIk: SecretIdentity,
        aSpk: SecretSignedPreKey,
        pairingNonce: Data,
        headerBytes: Data
    ) throws -> CompleteResult {
        let header = try decodeClassicalHeader(bytes: headerBytes)
        guard header.version == version else {
            throw PairingError.malformed("unsupported header version \(header.version)")
        }
        guard header.suiteId == suiteId else {
            throw PairingError.malformed("unsupported suite \(header.suiteId)")
        }

        let respOut = try deriveResponderClassical(
            aliceIk: aIk,
            aliceSpk: aSpk,
            aliceOpk: nil, // no per-pairing ephemeral OPK
            bobIk: header.bobIk,
            bobEkX25519Pub: header.ekX25519Pub,
            opkId: nil,
            pairingNonce: pairingNonce
        )

        let ok = try verifyConfirmTag(
            srk: respOut.sessionRootKey,
            transcript: respOut.transcript,
            who: confirmWhoInitiator(),
            tag: header.confirmB
        )
        guard ok else {
            throw PairingFailedError("key confirmation failed — peers did not agree")
        }

        let session = try Session.responderAfterHandshakeClassical(
            sessionRootKey: respOut.sessionRootKey,
            sessionId: header.sessionId,
            bobEkX25519Pub: header.ekX25519Pub
        )

        // Peer is B: derive its local contact id from the header's classical IK.
        let peerFp = classicalFingerprintHex(ed25519: header.bobIk.ed25519, x25519: header.bobIk.x25519)

        return CompleteResult(
            session: session,
            emoji: try deriveSafetyEmoji(sessionSecret: respOut.sessionRootKey),
            peerFingerprintHex: peerFp,
            peerDeviceId: peerFp
        )
    }
}
