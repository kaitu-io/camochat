package app.chencang.shared.uat

import app.chencang.shared.CcRepository
import app.chencang.shared.crypto.RatchetSessionStore
import app.chencang.shared.model.Contact
import uniffi.chencang.DecodedMessage
import uniffi.chencang.PreKeyBundle
import uniffi.chencang.SecretIdentity
import uniffi.chencang.SecretOneTimePreKey
import uniffi.chencang.SecretSignedPreKey
import uniffi.chencang.Session
import uniffi.chencang.decodeFrame
import uniffi.chencang.decodeWire
import uniffi.chencang.deriveInitiatorHandshake
import uniffi.chencang.deriveResponderHandshake
import uniffi.chencang.encodeTextFrame
import uniffi.chencang.encodeWire

/**
 * Self-loopback fixture for UAT. Mirrors iOS `UATLoopback`: runs the real
 * PQXDH handshake locally between two ephemeral identities (Bob = sender,
 * Alice = recipient), then either:
 *
 *   - drives [runE2E] entirely in-call (transient sessions, asserts round-trip
 *     parity for the standalone "loopback test" button), or
 *   - persists state via [seed] so a "UAT self-test" contact can do the same
 *     loopback live from the chat UI: type → 🔒… → paste → decrypt → read.
 *
 * V1 wire codec (in-band text): TEXT frame (L1) + L2 header →
 * `Session.encryptToBytes` → `encodeWire` → "🔒" + Base32768. This loopback
 * round-trips a synthetic text message through the wire codec to assert
 * round-trip parity.
 *
 * NOT for production: shortcuts real pairing. Identities are ephemeral;
 * sessions live only for the current process lifetime (V1 in-memory store).
 */
object UatLoopback {

    const val CONTACT_USERNAME = "uat-alice"
    const val DISPLAY_NAME = "UAT self-test"
    const val RECV_SESSION_KEY = "uat-alice-recv"

    /**
     * Synthetic device_id for the single-device loopback. Any stable local
     * string works — the seed needs no server-issued id, keeping it fully
     * offline/serverless.
     */
    const val LOCAL_DEVICE_ID = "uat-local-device"

    private const val LOOPBACK_TEXT = "UAT self-test"

    /**
     * Push a synthetic text message through the in-band send + receive wire
     * codec (transient sessions), assert round-trip parity. Doesn't touch the
     * locator's session store. Runs fully offline.
     */
    suspend fun runE2E(): String {
        val (bobSess, aliceSess) = mintLoopbackSessionPair()
        val wire = sendV1(
            text = LOOPBACK_TEXT,
            sendSession = bobSess,
        )
        val recovered = receiveV1(
            wireText = wire,
            recvSession = aliceSess,
        )
        bobSess.close()
        aliceSess.close()
        if (recovered != LOOPBACK_TEXT) {
            return "❌ E2E mismatch: sent \"$LOOPBACK_TEXT\", recovered \"$recovered\""
        }
        val wirePrefix = wire.take(48)
        return "✅ E2E OK — wire=$wirePrefix… (${wire.length} chars), recovered \"$recovered\""
    }

    /**
     * Seed for a single-device loopback dogfood. Writes:
     *
     *  - the "UAT self-test" [Contact] into the repository, marked verified,
     *    with `deviceId` set to the local device's id (single-device
     *    loopback);
     *  - two Double Ratchet sessions into [store] — Bob keyed by the
     *    contact's username (used by the send pipeline) and Alice keyed by a
     *    distinct label so [RatchetSessionStore.decryptFromBytesAny] can
     *    find it on the receive path.
     *
     * Calling seed again rotates both ephemeral identities and overwrites
     * the cached sessions — handy when ratchet state has drifted.
     */
    suspend fun seed(
        repository: CcRepository,
        store: RatchetSessionStore,
        deviceId: String = LOCAL_DEVICE_ID,
    ): String {
        val (bobSess, aliceSess) = mintLoopbackSessionPair()
        store.put(CONTACT_USERNAME, bobSess)
        // Alice's recv session lives under a distinct label so it doesn't
        // collide with Bob's send session, but peerAlias points back to
        // CONTACT_USERNAME so the receive pipeline can look up the contact's
        // device_id and display name without knowing about the loopback hack.
        store.put(RECV_SESSION_KEY, aliceSess, peerAlias = CONTACT_USERNAME)
        repository.upsertContact(
            Contact(
                // Loopback uses the username as the stable key so the existing
                // username-keyed session/history plumbing keeps working.
                fingerprintHex = CONTACT_USERNAME,
                username = CONTACT_USERNAME,
                displayName = DISPLAY_NAME,
                pairedAt = System.currentTimeMillis(),
                verified = true,
                deviceId = deviceId,
            ),
        )
        return "✅ Seeded $DISPLAY_NAME"
    }

    // ---- internals ----------------------------------------------------------

    /**
     * Run the full PQXDH handshake and produce a (Bob, Alice) Session pair.
     * The shape mirrors `SessionInstrumentedTest.basicRoundTripWithOpk` so the
     * binding's own coverage doubles as our protocol-correctness check.
     *
     * Exposed `internal` so the in-process round-trip test can reuse it
     * without copying the handshake plumbing.
     */
    internal fun mintLoopbackSessionPair(): Pair<Session, Session> {
        val bobIk = SecretIdentity()
        val aliceIk = SecretIdentity()
        val aliceSpk = SecretSignedPreKey(aliceIk, 1u)
        val aliceOpk = SecretOneTimePreKey(7u)

        val pairingNonce = ByteArray(16) { 0x22.toByte() }
        val bundle = PreKeyBundle(
            ik = aliceIk.publicIdentity(),
            spk = aliceSpk.publicForm(),
            opk = aliceOpk.publicForm(),
            inviterUsername = "alice",
            inviteId = ByteArray(16) { 0x11.toByte() },
            pairingNonce = pairingNonce,
        )

        val initOut = deriveInitiatorHandshake(bobIk, bundle)
        val srkAlice = deriveResponderHandshake(
            aliceIk,
            aliceSpk,
            aliceOpk,
            initOut.bobIdentityPublic,
            initOut.ekX25519Pub,
            initOut.ekMlkemPub,
            initOut.kemCtToSpk,
            initOut.kemCtToIk,
            initOut.kemCtToOpk,
            pairingNonce,
        )

        val sid = byteArrayOf(9, 9, 9, 9, 9)
        val bobSess = Session.initiatorAfterHandshake(
            initOut.sessionRootKey,
            sid,
            aliceIk.publicIdentity(),
            initOut.ekX25519Secret!!,
            initOut.ekMlkemSecret!!,
        )
        val aliceSess = Session.responderAfterHandshake(srkAlice, sid, initOut)
        return bobSess to aliceSess
    }

    private suspend fun sendV1(
        text: String,
        sendSession: Session,
    ): String {
        val frame = encodeTextFrame(text)
        val ct = sendSession.encryptToBytes(frame)
        return encodeWire(ct)
    }

    private suspend fun receiveV1(
        wireText: String,
        recvSession: Session,
    ): String {
        check(wireText.startsWith("🔒")) { "wire text missing 🔒 prefix" }
        val ct = decodeWire(wireText)
        val frame = recvSession.decryptFromBytes(ct)
        val msg = decodeFrame(frame)
        check(msg is DecodedMessage.Text) {
            "expected DecodedMessage.Text, got ${msg::class.simpleName}"
        }
        return msg.value
    }
}
