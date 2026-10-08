package app.chencang.shared.pairing.inband

import app.chencang.shared.crypto.EmojiFingerprint
import java.security.MessageDigest
import java.security.SecureRandom
import uniffi.chencang.ClassicalInbandHeader
import uniffi.chencang.ClassicalPreKeyBundle
import uniffi.chencang.ClassicalPublicIdentity
import uniffi.chencang.ClassicalSignedPreKey
import uniffi.chencang.SecretIdentity
import uniffi.chencang.SecretSignedPreKey
import uniffi.chencang.Session
import uniffi.chencang.computeConfirmTag
import uniffi.chencang.confirmWhoInitiator
import uniffi.chencang.decodeClassicalBundle
import uniffi.chencang.decodeClassicalHeader
import uniffi.chencang.deriveInitiatorClassical
import uniffi.chencang.deriveResponderClassical
import uniffi.chencang.encodeClassicalBundle
import uniffi.chencang.encodeClassicalHeader
import uniffi.chencang.verifyConfirmTag

/**
 * Zero-server, in-band classical 2-round pairing engine.
 *
 * Two devices establish a Double-Ratchet session by exchanging exactly two
 * blobs over ANY channel the user already trusts (paste / QR / DM) — no
 * Chencang server, no prekey publishing, no network at all.
 *
 * Role mapping (matches chencang-core's classical semantics, where the
 * comments call the bundle-receiver "Bob" = initiator and the bundle-creator
 * "Alice" = responder):
 *
 *  - **A = inviter = responder.** Builds the invite bundle ([buildInviteBundle],
 *    Round 1) and later runs [complete] on B's response header (Round 2
 *    completion) via `deriveResponderClassical` + `verifyConfirmTag`.
 *  - **B = invitee = initiator.** Receives A's bundle, runs [accept]:
 *    `deriveInitiatorClassical`, builds the response header carrying its key
 *    material + a key-confirmation tag, and constructs its session.
 *
 * **A is stateless between rounds.** The bundle uses no per-pairing ephemeral
 * one-time prekey (`opk = null`); X3DH runs on A's long-lived IK + SPK only.
 * So A retains nothing secret while waiting for B — just the PUBLIC 16-byte
 * pairing nonce (also embedded in the bundle). It reloads its IK/SPK from disk
 * to call [complete], which survives the app process being killed during
 * the wait.
 *
 * The session id is chosen by B (initiator), carried in the header, and read
 * back by A — so both sides build their Session with the same id.
 *
 * Secrets (SRK, ephemeral X25519 secret, transcript, confirm tags) are never
 * logged.
 */
object InbandPairing {

    /** Wire version + suite for the classical in-band path (must match core). */
    private const val VERSION: UByte = 1u
    private const val SUITE_ID: UByte = 1u
    private const val PAIRING_NONCE_LEN = 16
    private const val SESSION_ID_LEN = 5

    private val rng = SecureRandom()

    /**
     * Round-1 output held by the inviter (A) between [buildInviteBundle] and
     * [complete]. Carries only PUBLIC bytes — there is nothing secret to retain
     * or drop, so A survives a process kill while waiting for B. The pairing
     * nonce is also embedded inside [bundleBytes]; it is surfaced here so A can
     * persist it cheaply for the stateless [complete].
     */
    class PendingInvite internal constructor(
        /** Encoded classical bundle to deliver to B (paste / QR). */
        val bundleBytes: ByteArray,
        /** PUBLIC 16-byte pairing nonce (also embedded in [bundleBytes]). */
        val pairingNonce: ByteArray,
    )

    /** Result of B accepting A's invite. */
    data class AcceptResult(
        /** Encoded response header to deliver back to A. */
        val headerBytes: ByteArray,
        /**
         * B's freshly established session. The caller OWNS this native handle and
         * must persist it (e.g. [RatchetSessionStore.put]) or [Session.close] it.
         */
        val session: Session,
        /** 8-emoji safety fingerprint over the session root key. */
        val emoji: List<String>,
        /** Local classical contact id for the peer (A). See [classicalFingerprintHex]. */
        val peerFingerprintHex: String,
        /** Routing/dedup id for the peer; equals [peerFingerprintHex]. */
        val peerDeviceId: String,
    )

    /** Result of A completing the pairing against B's header. */
    data class CompleteResult(
        /**
         * A's freshly established session. The caller OWNS this native handle and
         * must persist it (e.g. [RatchetSessionStore.put]) or [Session.close] it.
         */
        val session: Session,
        /** 8-emoji safety fingerprint over the session root key. */
        val emoji: List<String>,
        /** Local classical contact id for the peer (B). See [classicalFingerprintHex]. */
        val peerFingerprintHex: String,
        /** Routing/dedup id for the peer; equals [peerFingerprintHex]. */
        val peerDeviceId: String,
    )

    /** Thrown when key confirmation fails — the two sides did NOT agree. */
    class PairingFailedException(message: String) : Exception(message)

    /**
     * Local, deterministic classical contact id for a peer, derived purely from
     * its classical identity-key bytes. This is NOT the hybrid
     * `fingerprintOfPublic` (which hashes all four post-quantum keys a classical
     * bundle never carries) — it is a LOCAL routing/dedup key. The real
     * cross-device security check is the SRK-derived 8-emoji safety number,
     * which already matches on both sides.
     */
    internal fun classicalFingerprintHex(ed25519: ByteArray, x25519: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(ed25519 + x25519)
            .take(16).joinToString("") { "%02x".format(it) }

    /**
     * This device's own classical contact id — the same value a peer derives for
     * us as its `peerFingerprintHex` (lowercase hex). Used as the seed for the
     * "auto" avatar colour so both phones compute the same colour for one person.
     */
    fun localFingerprintHex(identity: SecretIdentity): String =
        identity.publicIdentity().let { classicalFingerprintHex(it.ikSigEd25519, it.ikDhX25519) }

    /**
     * Round 1 (A side). Mint an invite bundle. No per-pairing ephemeral OPK is
     * generated (X3DH uses A's long-lived IK + SPK only), so the returned
     * [PendingInvite] holds only public bytes.
     */
    fun buildInviteBundle(
        aIk: SecretIdentity,
        aSpk: SecretSignedPreKey,
        inviterUsername: String,
    ): PendingInvite {
        val pairingNonce = ByteArray(PAIRING_NONCE_LEN).also { rng.nextBytes(it) }

        val ikPub = aIk.publicIdentity()
        val spkPub = aSpk.publicForm()

        val bundle = ClassicalPreKeyBundle(
            version = VERSION,
            suiteId = SUITE_ID,
            ik = ClassicalPublicIdentity(
                ed25519 = ikPub.ikSigEd25519,
                x25519 = ikPub.ikDhX25519,
            ),
            spk = ClassicalSignedPreKey(
                x25519 = spkPub.spkX25519,
                sigEd25519 = spkPub.ed25519SigClassical,
                epoch = spkPub.spkVersion,
            ),
            opk = null,
            pairingNonce = pairingNonce,
            inviterUsername = inviterUsername,
        )

        return PendingInvite(
            bundleBytes = encodeClassicalBundle(bundle),
            pairingNonce = pairingNonce,
        )
    }

    /**
     * Round 2a (B side). Accept A's bundle: derive the session root key, build
     * the response header (with a key-confirmation tag), and construct B's
     * session. B picks the session id.
     */
    fun accept(
        bIk: SecretIdentity,
        bundleBytes: ByteArray,
        bDisplayName: String?,
    ): AcceptResult {
        val bundle = decodeClassicalBundle(bundleBytes)
        require(bundle.version == VERSION) { "unsupported bundle version ${bundle.version}" }
        require(bundle.suiteId == SUITE_ID) { "unsupported suite ${bundle.suiteId}" }

        val initOut = deriveInitiatorClassical(bIk, bundle)

        val sessionId = ByteArray(SESSION_ID_LEN).also { rng.nextBytes(it) }
        val confirmB = computeConfirmTag(
            initOut.sessionRootKey,
            initOut.transcript,
            confirmWhoInitiator(),
        )

        // initOut.bobIk is the hybrid PublicIdentity of B; project to classical.
        val bClassicalIk = ClassicalPublicIdentity(
            ed25519 = initOut.bobIk.ikSigEd25519,
            x25519 = initOut.bobIk.ikDhX25519,
        )
        val header = ClassicalInbandHeader(
            version = VERSION,
            suiteId = SUITE_ID,
            bobIk = bClassicalIk,
            ekX25519Pub = initOut.ekX25519Pub,
            sessionId = sessionId,
            confirmB = confirmB,
            bobDisplayName = bDisplayName,
        )
        val headerBytes = encodeClassicalHeader(header)

        val session = Session.initiatorAfterHandshakeClassical(
            initOut.sessionRootKey,
            sessionId,
            bundle.ik.x25519, // A's (inviter) classical IK x25519
            initOut.ekX25519Secret,
        )

        // Peer is A: derive its local contact id from the bundle's classical IK.
        val peerFp = classicalFingerprintHex(bundle.ik.ed25519, bundle.ik.x25519)

        return AcceptResult(
            headerBytes = headerBytes,
            session = session,
            emoji = EmojiFingerprint.derive(initOut.sessionRootKey),
            peerFingerprintHex = peerFp,
            peerDeviceId = peerFp,
        )
    }

    /**
     * Round 2b (A side). Complete the pairing against B's response header:
     * re-derive the session root key from A's reloaded IK/SPK + the public
     * pairing nonce, verify B's key-confirmation tag, and construct A's session.
     * Stateless — nothing secret was retained across the wait.
     *
     * @throws PairingFailedException if the confirmation tag does not verify
     *   (tamper / wrong peer).
     */
    fun complete(
        aIk: SecretIdentity,
        aSpk: SecretSignedPreKey,
        pairingNonce: ByteArray,
        headerBytes: ByteArray,
    ): CompleteResult {
        val header = decodeClassicalHeader(headerBytes)
        require(header.version == VERSION) { "unsupported header version ${header.version}" }
        require(header.suiteId == SUITE_ID) { "unsupported suite ${header.suiteId}" }

        val respOut = deriveResponderClassical(
            aIk,
            aSpk,
            null, // no per-pairing ephemeral OPK
            header.bobIk,
            header.ekX25519Pub,
            null, // opkId
            pairingNonce,
        )

        val ok = verifyConfirmTag(
            respOut.sessionRootKey,
            respOut.transcript,
            confirmWhoInitiator(),
            header.confirmB,
        )
        if (!ok) {
            throw PairingFailedException("key confirmation failed — peers did not agree")
        }

        val session = Session.responderAfterHandshakeClassical(
            respOut.sessionRootKey,
            header.sessionId,
            header.ekX25519Pub,
        )

        // Peer is B: derive its local contact id from the header's classical IK.
        val peerFp = classicalFingerprintHex(header.bobIk.ed25519, header.bobIk.x25519)

        return CompleteResult(
            session = session,
            emoji = EmojiFingerprint.derive(respOut.sessionRootKey),
            peerFingerprintHex = peerFp,
            peerDeviceId = peerFp,
        )
    }
}
