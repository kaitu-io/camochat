package app.chencang.shared.crypto

import uniffi.chencang.InitiatorHandshakeOutput
import uniffi.chencang.PreKeyBundle
import uniffi.chencang.SecretIdentity
import uniffi.chencang.SecretOneTimePreKey
import uniffi.chencang.SecretSignedPreKey
import uniffi.chencang.Session
import uniffi.chencang.deriveInitiatorHandshake
import uniffi.chencang.deriveResponderHandshake

/**
 * Reusable PQXDH handshake harness for JVM unit tests. Mints a fresh
 * initiator/responder Session pair via the same uniffi API the production
 * IME/Companion uses, with no Android dependencies.
 *
 * Modeled after the production [HandshakePairing] flow:
 *  - "initiator" = the side that ran [deriveInitiatorHandshake] (= the
 *    redeemer in the spec; "Bob" in the original chencang-core comments)
 *  - "responder" = the side that ran [deriveResponderHandshake] (= the
 *    inviter; "Alice" in chencang-core comments)
 *
 * Returns sessions in [SessionPair.initiator] / [SessionPair.responder]
 * order so test names can read naturally (`initiator.encrypt(...) →
 * responder.decrypt(...)` for the canonical direction).
 *
 * Native lib loading: caller (`@BeforeClass`) must verify the host bindings
 * dylib is on `jna.library.path` and the uniffi `libraryOverride` system
 * property is set. See [SessionV1WireRoundTripTest.assumeNativeBindingsAvailable].
 */
internal object PqxdhHandshakeFixture {

    data class SessionPair(val initiator: Session, val responder: Session) {
        fun closeAll() {
            initiator.close()
            responder.close()
        }
    }

    fun mintLoopbackSessionPair(): SessionPair {
        val initiatorIk = SecretIdentity()
        val responderIk = SecretIdentity()
        val responderSpk = SecretSignedPreKey(responderIk, 1u)
        val responderOpk = SecretOneTimePreKey(7u)

        val pairingNonce = ByteArray(16) { 0x22.toByte() }
        val bundle = PreKeyBundle(
            ik = responderIk.publicIdentity(),
            spk = responderSpk.publicForm(),
            opk = responderOpk.publicForm(),
            inviterUsername = "responder",
            inviteId = ByteArray(16) { 0x11.toByte() },
            pairingNonce = pairingNonce,
        )

        val initOut = deriveInitiatorHandshake(initiatorIk, bundle)
        val responderRoot = deriveResponderHandshake(
            responderIk,
            responderSpk,
            responderOpk,
            initOut.bobIdentityPublic,
            initOut.ekX25519Pub,
            initOut.ekMlkemPub,
            initOut.kemCtToSpk,
            initOut.kemCtToIk,
            initOut.kemCtToOpk,
            pairingNonce,
        )

        val sid = byteArrayOf(9, 9, 9, 9, 9)
        val initiatorSession = Session.initiatorAfterHandshake(
            initOut.sessionRootKey,
            sid,
            responderIk.publicIdentity(),
            initOut.ekX25519Secret!!,
            initOut.ekMlkemSecret!!,
        )
        val responderSession = Session.responderAfterHandshake(responderRoot, sid, initOut)
        return SessionPair(initiator = initiatorSession, responder = responderSession)
    }

    /**
     * Production-faithful variant: SPK-only (no OPK) and the responder
     * reconstructs [InitiatorHandshakeOutput] with `null` ephemeral secrets
     * (because the responder doesn't have access to the initiator's
     * ephemeral secrets — only the public material and KEM ciphertexts that
     * arrived in the handshake header).
     *
     * Mirrors [HandshakePairing.accept] (B's side) and [HandshakePairing.poll]
     * (A's side) byte-for-byte: same SPK-only deriveResponderHandshake call,
     * same null-secret InitiatorHandshakeOutput reconstruction.
     *
     * If [mintLoopbackSessionPair] passes a test that this variant fails,
     * the bug is in chencang-core's handling of null ephemeral secrets in
     * the responder's session — which would explain the production
     * `no recv chain key` failure.
     */
    fun mintProductionFaithfulSessionPair(): SessionPair {
        val initiatorIk = SecretIdentity()
        val responderIk = SecretIdentity()
        val responderSpk = SecretSignedPreKey(responderIk, 1u)

        // Production redeem passes an OPK over the wire as the spec mandates,
        // but pollers (responder side) load it back as `null` per HandshakePairing.poll
        // line 109 (`// SPK-only: opk = null`). Mirror that asymmetry by giving the
        // initiator an OPK to redeem against, then null on the responder side.
        // (V1 production is genuinely SPK-only — the inviter doesn't publish an OPK
        // — so we use null on BOTH sides to faithfully replay the live setup.)
        val pairingNonce = ByteArray(16) { 0x22.toByte() }
        val bundle = PreKeyBundle(
            ik = responderIk.publicIdentity(),
            spk = responderSpk.publicForm(),
            opk = null,
            inviterUsername = "responder",
            inviteId = ByteArray(16) { 0x11.toByte() },
            pairingNonce = pairingNonce,
        )

        val initOut = deriveInitiatorHandshake(initiatorIk, bundle)
        val responderRoot = deriveResponderHandshake(
            responderIk,
            responderSpk,
            null, // SPK-only — matches HandshakePairing.poll line 109
            initOut.bobIdentityPublic,
            initOut.ekX25519Pub,
            initOut.ekMlkemPub,
            initOut.kemCtToSpk,
            initOut.kemCtToIk,
            null, // SPK-only
            pairingNonce,
        )

        val sid = byteArrayOf(9, 9, 9, 9, 9)
        val initiatorSession = Session.initiatorAfterHandshake(
            initOut.sessionRootKey,
            sid,
            responderIk.publicIdentity(),
            initOut.ekX25519Secret!!,
            initOut.ekMlkemSecret!!,
        )

        // KEY DIFFERENCE — production responder reconstructs InitiatorHandshakeOutput
        // with NULL ephemeral secrets, because the responder doesn't have them.
        // See HandshakePairing.kt:114-124.
        val responderReconstructedOutput = InitiatorHandshakeOutput(
            sessionRootKey = responderRoot,
            bobIdentityPublic = initOut.bobIdentityPublic,
            ekX25519Pub = initOut.ekX25519Pub,
            ekMlkemPub = initOut.ekMlkemPub,
            kemCtToSpk = initOut.kemCtToSpk,
            kemCtToIk = initOut.kemCtToIk,
            kemCtToOpk = null,
            ekX25519Secret = null,
            ekMlkemSecret = null,
        )
        val responderSession = Session.responderAfterHandshake(
            responderRoot,
            sid,
            responderReconstructedOutput,
        )
        return SessionPair(initiator = initiatorSession, responder = responderSession)
    }
}
