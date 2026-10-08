package app.chencang.shared.crypto

import com.google.common.truth.Truth.assertThat
import org.junit.Assume
import org.junit.BeforeClass
import org.junit.Test
import uniffi.chencang.InitiatorHandshakeOutput
import uniffi.chencang.PreKeyBundle
import uniffi.chencang.SecretIdentity
import uniffi.chencang.SecretSignedPreKey
import uniffi.chencang.Session
import uniffi.chencang.deriveInitiatorHandshake
import uniffi.chencang.deriveResponderHandshake

/**
 * #201 cross-process invalidation probe.
 *
 * Production trace from chencang-core (mod.rs:229-264) says `no recv chain key`
 * fires ONLY when the sender skipped `step_send` AND the receiver lacks both a
 * `recv_chain_key` and a skipped key for (gen, counter). A is the responder
 * whose `send_chain_key` starts as None, so the first encrypt MUST trigger
 * step_send, MUST bump ratchet_gen, MUST attach dh_pub to the header. The only
 * way to skip step_send is if A's session was initiator-shaped at encrypt time.
 *
 * Hypothesis 1: A's IME has hydrated a STALE initiator-shaped session (from a
 * prior pairing where A was redeemer) under peerFp(B), and that stale row was
 * NOT overwritten by Companion's new responder session before A's first send.
 * Companion writes to cc-sessions.db, but the IME holds a process-local
 * `Map<peerFp, Session>` cache that was hydrated at IME startup and isn't
 * invalidated when Companion writes new rows.
 *
 * This test characterizes what failure shape that produces. We do NOT test
 * cross-store rehydration here — CrossStoreDecryptTest already proved that
 * works. We test what happens when A's IME ignores the new row and keeps
 * using a stale initiator-shaped session.
 */
class CrossProcessInvalidationTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun assumeNativeBindingsAvailable() {
            val arch = System.getProperty("os.arch", "")
            val override = System.getProperty(
                "uniffi.component.chencang.libraryOverride",
            )
            val jnaPath = System.getProperty("jna.library.path")
            Assume.assumeTrue(
                "host bindings not configured (jna.library.path / libraryOverride absent; arch=$arch)",
                override != null && jnaPath != null,
            )
        }

        /**
         * Inspect role of a session by reading byte 64 (send_chain_key Option
         * flag) of its serialized state blob.
         *  - byte 64 == 1 → send_chain_key=Some → INITIATOR-shaped
         *  - byte 64 == 0 → send_chain_key=None → RESPONDER-shaped (pre-step_send)
         *
         * Blob format: core/src/session/state.rs:65-71
         *   [0..2]  magic 0xCC 0x02
         *   [2..7]  sid (5 bytes)
         *   [7..39] root_key (32 bytes)
         *   [39..43] ratchet_gen u32 BE
         *   [43..47] send_counter u32 BE
         *   [47..51] recv_counter u32 BE
         *   [51..55] kem_messages_since_ratchet u32 BE
         *   [55..63] kem_ratchet_last_unix u64 BE
         *   [63]    kem_pending flag
         *   [64]    send_chain_key Option flag (0=None, 1=Some)
         *   [65...] if flag=1, the 32-byte key; otherwise next field starts.
         */
        fun inspectRole(blob: ByteArray): String {
            require(blob.size >= 67) { "blob too small (${blob.size}B)" }
            require(blob[0] == 0xCC.toByte() && blob[1] == 0x02.toByte()) {
                "blob magic mismatch: ${blob[0].toUByte()} ${blob[1].toUByte()}"
            }
            // send_chain_key flag sits at offset 65 (after kem_pending[63] +
            // post_quantum[64], which precede it in SessionState.serialize).
            val sendFlag = blob[65].toInt() and 0xFF
            // For diagnostics: locate the recv_chain_key flag (after optional send key).
            val recvFlagOffset = 66 + (if (sendFlag == 1) 32 else 0)
            val recvFlag = if (recvFlagOffset < blob.size) blob[recvFlagOffset].toInt() and 0xFF else -1
            return when {
                sendFlag == 1 && recvFlag == 0 -> "INITIATOR (send=Some, recv=None)"
                sendFlag == 0 && recvFlag == 1 -> "RESPONDER (send=None, recv=Some)"
                sendFlag == 1 && recvFlag == 1 -> "MID-STREAM (send=Some, recv=Some)"
                sendFlag == 0 && recvFlag == 0 -> "EMPTY (send=None, recv=None)"
                else -> "UNKNOWN (sendFlag=$sendFlag, recvFlag=$recvFlag at $recvFlagOffset)"
            }
        }

        fun extractSid(blob: ByteArray): ByteArray {
            require(blob.size >= 7)
            return blob.copyOfRange(2, 7)
        }

        fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
    }

    // --- Scenario A — Different SIDs (realistic) -----------------------------

    /**
     * Models: A's IME hydrated a stale session under peerFp(B) from an OLD
     * pairing where A acted as redeemer (initiator). A new pairing happened in
     * Companion making A responder, but the IME's in-proc cache still points at
     * the OLD initiator-shaped session. A's send uses that old session.
     *
     * Different SIDs (since the pairings are independent). Expected: B can't
     * even find the session by SID — the wire decodec or session lookup fails
     * before reaching the "no recv chain key" path. This is the realistic case.
     */
    @Test
    fun `scenario A — A's IME uses stale initiator session, different SIDs from B's session`() {
        val pair1 = PqxdhHandshakeFixture.mintProductionFaithfulSessionPair()
        val oldInitiatorBlob = pair1.initiator.serializeState()
        val oldRole = inspectRole(oldInitiatorBlob)
        val oldSid = extractSid(oldInitiatorBlob)
        println("[scenA] pair1 oldInitiator role=$oldRole sid=${hex(oldSid)}")
        pair1.closeAll()

        // NEW pairing: A is now responder; B is the new initiator.
        val pair2 = PqxdhHandshakeFixture.mintProductionFaithfulSessionPair()
        val newResponderBlob = pair2.responder.serializeState()
        val newInitiatorBlob = pair2.initiator.serializeState()
        val newRole = inspectRole(newResponderBlob)
        val newSid = extractSid(newResponderBlob)
        val bSid = extractSid(newInitiatorBlob)
        println("[scenA] pair2 newResponder (Companion wrote) role=$newRole sid=${hex(newSid)}")
        println("[scenA] pair2 newInitiator (B's session)    role=${inspectRole(newInitiatorBlob)} sid=${hex(bSid)}")

        // Same SID across pair2 (intentional): both sides of pair2 share SID.
        assertThat(newSid).isEqualTo(bSid)
        // Different from pair1 — extremely likely since pair1 used fresh fixture.
        val sidMatch = oldSid.contentEquals(bSid)
        println("[scenA] A's stale (IME) SID matches B's session SID? $sidMatch")

        // A's IME loads the stale initiator blob (skipping Companion's responder row).
        val aImeSession = Session.fromSerializedState(oldInitiatorBlob)
        val aImeRoleAtSendTime = inspectRole(aImeSession.serializeState())
        println("[scenA] A's IME session role at encrypt time: $aImeRoleAtSendTime")

        val pt = ByteArray(32) { (it + 0x90).toByte() }
        val ct = aImeSession.encryptToBytes(pt)
        println("[scenA] A encrypted ${ct.size}B with stale initiator")

        val newInitiator = Session.fromSerializedState(newInitiatorBlob)
        val result = runCatching { newInitiator.decryptFromBytes(ct) }
        if (result.isSuccess) {
            println("[scenA] UNEXPECTED: B decrypted ciphertext from A's stale session!")
        } else {
            val err = result.exceptionOrNull()!!
            println("[scenA] B decrypt error: ${err::class.java.simpleName}: ${err.message}")
        }

        aImeSession.close()
        newInitiator.close()
        pair2.responder.close()
        pair2.initiator.close()
    }

    // --- Scenario B — Same SID forced ---------------------------------------

    /**
     * Synthetic: force pair1 and pair2 to share the same 5-byte SID, so B's
     * SID-lookup happily finds A's stale initiator's wire. Now the failure
     * lands inside DR state-machine logic instead of session lookup.
     *
     * Has to inline the pair-minting logic since the fixture hardcodes its own
     * SID. We use SID = [7,7,7,7,7] for pair1 and [7,7,7,7,7] for pair2.
     */
    @Test
    fun `scenario B — same SID forced, stale initiator vs fresh initiator`() {
        val forcedSid = byteArrayOf(7, 7, 7, 7, 7)

        fun mintPairWithSid(sid: ByteArray): PqxdhHandshakeFixture.SessionPair {
            val initiatorIk = SecretIdentity()
            val responderIk = SecretIdentity()
            val responderSpk = SecretSignedPreKey(responderIk, 1u)
            try {
                val bundle = PreKeyBundle(
                    ik = responderIk.publicIdentity(),
                    spk = responderSpk.publicForm(),
                    opk = null,
                    inviterUsername = "responder",
                    inviteId = ByteArray(16) { 0x11.toByte() },
                    pairingNonce = ByteArray(16) { 0x22.toByte() },
                )
                val initOut = deriveInitiatorHandshake(initiatorIk, bundle)
                val responderRoot = deriveResponderHandshake(
                    responderIk,
                    responderSpk,
                    null,
                    initOut.bobIdentityPublic,
                    initOut.ekX25519Pub,
                    initOut.ekMlkemPub,
                    initOut.kemCtToSpk,
                    initOut.kemCtToIk,
                    null,
                    ByteArray(16) { 0x22.toByte() },
                )
                val initiatorSession = Session.initiatorAfterHandshake(
                    initOut.sessionRootKey,
                    sid,
                    responderIk.publicIdentity(),
                    initOut.ekX25519Secret!!,
                    initOut.ekMlkemSecret!!,
                )
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
                return PqxdhHandshakeFixture.SessionPair(
                    initiator = initiatorSession,
                    responder = responderSession,
                )
            } finally {
                responderSpk.close()
            }
        }

        val pair1 = mintPairWithSid(forcedSid)
        val oldInitiatorBlob = pair1.initiator.serializeState()
        println("[scenB] pair1 oldInitiator role=${inspectRole(oldInitiatorBlob)} sid=${hex(extractSid(oldInitiatorBlob))}")
        pair1.closeAll()

        val pair2 = mintPairWithSid(forcedSid)
        val newInitiatorBlob = pair2.initiator.serializeState()
        println("[scenB] pair2 newInitiator role=${inspectRole(newInitiatorBlob)} sid=${hex(extractSid(newInitiatorBlob))}")

        val sidA = extractSid(oldInitiatorBlob)
        val sidB = extractSid(newInitiatorBlob)
        assertThat(sidA).isEqualTo(sidB)
        println("[scenB] SIDs match: ${hex(sidA)}")

        val aImeSession = Session.fromSerializedState(oldInitiatorBlob)
        val aImeRoleAtSendTime = inspectRole(aImeSession.serializeState())
        println("[scenB] A's IME session role at encrypt: $aImeRoleAtSendTime")

        val pt = ByteArray(32) { (it + 0xA0).toByte() }
        val ct = aImeSession.encryptToBytes(pt)
        println("[scenB] A encrypted ${ct.size}B with stale initiator (forced same SID)")

        val newInitiator = Session.fromSerializedState(newInitiatorBlob)
        val result = runCatching { newInitiator.decryptFromBytes(ct) }
        if (result.isSuccess) {
            println("[scenB] UNEXPECTED SUCCESS — divergent roots somehow round-tripped!")
        } else {
            val err = result.exceptionOrNull()!!
            println("[scenB] B decrypt error: ${err::class.java.simpleName}: ${err.message}")
        }

        aImeSession.close()
        newInitiator.close()
        pair2.responder.close()
        pair2.initiator.close()
    }

    // --- Scenario B' — A's session is responder-shaped but SOMEHOW initiator-flagged ---

    /**
     * What if cross-process invalidation isn't about A loading a stale row, but
     * about A's responder-shaped session getting its send_chain_key flag flipped
     * somehow during persist/rehydrate? Direct test: take a fresh
     * production-faithful pair, take ONLY the responder blob, hand it to a
     * fresh Session via fromSerializedState, and verify role is preserved.
     */
    @Test
    fun `scenario B prime — responder role preservation through round-trip`() {
        val pair = PqxdhHandshakeFixture.mintProductionFaithfulSessionPair()
        try {
            val blob = pair.responder.serializeState()
            println("[scenB'] before rehydrate: ${inspectRole(blob)}")

            val rehydrated = Session.fromSerializedState(blob)
            try {
                val reblob = rehydrated.serializeState()
                println("[scenB'] after rehydrate:  ${inspectRole(reblob)}")
                // Bytes should match exactly (deterministic serialization).
                val same = blob.contentEquals(reblob)
                println("[scenB'] round-trip byte-identical: $same")

                // Sanity: rehydrated responder can still R→I encrypt.
                val pt = ByteArray(16) { (it + 0xB0).toByte() }
                val ct = rehydrated.encryptToBytes(pt)
                val recovered = pair.initiator.decryptFromBytes(ct)
                val ok = recovered.contentEquals(pt)
                println("[scenB'] R→I after rehydrate: ${if (ok) "PASS" else "FAIL"}")
                assertThat(ok).isTrue()
            } finally {
                rehydrated.close()
            }
        } finally {
            pair.initiator.close()
            // pair.responder is the source of blob — its state is unchanged
            // until we called encryptToBytes on rehydrated (separate handle).
            pair.responder.close()
        }
    }

    // --- Scenario C — Direct manipulation, single Session pair ---------------

    /**
     * Inspect the EXACT byte layout of a fresh-handshake responder session,
     * and confirm the send_chain_key Option flag is 0. NB the flag sits at
     * offset 65: core's SessionState serializes `kem_pending[1] || post_quantum[1]`
     * right before send_chain_key, so the post_quantum byte occupies offset 64
     * and the send_chain_key flag follows at 65.
     * Also dump the equivalent initiator-side bytes for comparison so the test
     * log is self-documenting.
     */
    @Test
    fun `scenario C — byte-level role inspection of fresh sessions`() {
        val pair = PqxdhHandshakeFixture.mintProductionFaithfulSessionPair()
        try {
            val initBlob = pair.initiator.serializeState()
            val respBlob = pair.responder.serializeState()

            println("[scenC] initiator blob (${initBlob.size}B):")
            println("[scenC]   byte 0..1 (magic):   ${"%02x %02x".format(initBlob[0], initBlob[1])}")
            println("[scenC]   byte 65 (sendFlag):  ${initBlob[65].toInt() and 0xFF}  (1=Some=initiator)")
            val initRecvFlagOff = 66 + (if ((initBlob[65].toInt() and 0xFF) == 1) 32 else 0)
            println("[scenC]   byte $initRecvFlagOff (recvFlag): ${initBlob[initRecvFlagOff].toInt() and 0xFF}")
            println("[scenC]   role: ${inspectRole(initBlob)}")

            println("[scenC] responder blob (${respBlob.size}B):")
            println("[scenC]   byte 0..1 (magic):   ${"%02x %02x".format(respBlob[0], respBlob[1])}")
            println("[scenC]   byte 65 (sendFlag):  ${respBlob[65].toInt() and 0xFF}  (0=None=responder)")
            val respRecvFlagOff = 66 + (if ((respBlob[65].toInt() and 0xFF) == 1) 32 else 0)
            println("[scenC]   byte $respRecvFlagOff (recvFlag): ${respBlob[respRecvFlagOff].toInt() and 0xFF}")
            println("[scenC]   role: ${inspectRole(respBlob)}")

            // Hard assertion: a fresh production-faithful responder MUST have
            // sendFlag=0 (send_chain_key=None). If this ever fails, chencang-core
            // regressed. Flag is at offset 65 (after the post_quantum byte at 64).
            assertThat(respBlob[65].toInt() and 0xFF).isEqualTo(0)
            assertThat(initBlob[65].toInt() and 0xFF).isEqualTo(1)
        } finally {
            pair.closeAll()
        }
    }

    // --- Scenario A' — TRULY different SIDs (random) -------------------------

    /**
     * The default fixture hardcodes SID=[9,9,9,9,9], so Scenario A's two
     * sessions accidentally share SID and we can't tell from that test whether
     * mismatching SIDs route to a different error shape. This variant gives
     * pair1 SID=[1,1,1,1,1] and pair2 SID=[2,2,2,2,2] using the same inline
     * mint helper as Scenario B.
     */
    @Test
    fun `scenario A prime — truly different SIDs, stale initiator vs fresh B`() {
        fun mintPairWithSid(sid: ByteArray): PqxdhHandshakeFixture.SessionPair {
            val initiatorIk = SecretIdentity()
            val responderIk = SecretIdentity()
            val responderSpk = SecretSignedPreKey(responderIk, 1u)
            try {
                val bundle = PreKeyBundle(
                    ik = responderIk.publicIdentity(),
                    spk = responderSpk.publicForm(),
                    opk = null,
                    inviterUsername = "responder",
                    inviteId = ByteArray(16) { 0x11.toByte() },
                    pairingNonce = ByteArray(16) { 0x22.toByte() },
                )
                val initOut = deriveInitiatorHandshake(initiatorIk, bundle)
                val responderRoot = deriveResponderHandshake(
                    responderIk, responderSpk, null,
                    initOut.bobIdentityPublic, initOut.ekX25519Pub, initOut.ekMlkemPub,
                    initOut.kemCtToSpk, initOut.kemCtToIk, null,
                    ByteArray(16) { 0x22.toByte() },
                )
                val initiatorSession = Session.initiatorAfterHandshake(
                    initOut.sessionRootKey, sid, responderIk.publicIdentity(),
                    initOut.ekX25519Secret!!, initOut.ekMlkemSecret!!,
                )
                val responderOutput = InitiatorHandshakeOutput(
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
                val responderSession = Session.responderAfterHandshake(responderRoot, sid, responderOutput)
                return PqxdhHandshakeFixture.SessionPair(initiatorSession, responderSession)
            } finally {
                responderSpk.close()
            }
        }

        val pair1 = mintPairWithSid(byteArrayOf(1, 1, 1, 1, 1))
        val oldInitiatorBlob = pair1.initiator.serializeState()
        println("[scenA'] pair1 oldInitiator role=${inspectRole(oldInitiatorBlob)} sid=${hex(extractSid(oldInitiatorBlob))}")
        pair1.closeAll()

        val pair2 = mintPairWithSid(byteArrayOf(2, 2, 2, 2, 2))
        val newInitiatorBlob = pair2.initiator.serializeState()
        println("[scenA'] pair2 newInitiator role=${inspectRole(newInitiatorBlob)} sid=${hex(extractSid(newInitiatorBlob))}")

        val sidA = extractSid(oldInitiatorBlob)
        val sidB = extractSid(newInitiatorBlob)
        println("[scenA'] SIDs equal? ${sidA.contentEquals(sidB)}")

        val aImeSession = Session.fromSerializedState(oldInitiatorBlob)
        println("[scenA'] A's IME session role at encrypt: ${inspectRole(aImeSession.serializeState())}")

        val pt = ByteArray(32) { (it + 0xD0).toByte() }
        val ct = aImeSession.encryptToBytes(pt)

        val freshB = Session.fromSerializedState(newInitiatorBlob)
        val result = runCatching { freshB.decryptFromBytes(ct) }
        val err = result.exceptionOrNull()
        if (err != null) {
            println("[scenA'] B decrypt error: ${err::class.java.simpleName}: ${err.message}")
            println("[scenA'] Matches 'no recv chain key'? ${(err.message ?: "").contains("no recv chain key")}")
            println("[scenA'] Matches 'sid' / 'session id'? ${(err.message ?: "").lowercase().contains("sid") || (err.message ?: "").lowercase().contains("session id")}")
        } else {
            println("[scenA'] UNEXPECTED SUCCESS")
        }

        aImeSession.close()
        freshB.close()
        pair2.responder.close()
        pair2.initiator.close()
    }

    // --- Scenario E — #202 production-shape: same responder, msg-1 lost ------

    /**
     * #202 fix verification.
     *
     * Production shape: A (responder) encrypts msg-1 → step_send fires (send
     * chain was None), header carries dh_pub, ratchet_gen=1. Wire-1 LOST.
     * A encrypts msg-2 from the SAME session → step_send SKIPPED (send chain
     * Some now). Pre-fix msg-2's header had NO dh_pub. Fresh B receives wire-2
     * and fails with `Internal("no recv chain key")`.
     *
     * Post-fix: msg-2's header carries dh_pub (always-include semantics),
     * and B's catch-up step_recv fires off it even though ratchet_gen did
     * not advance. B decrypts wire-2 successfully.
     *
     * This is the EXACT scenario that bug #201 reported in the field.
     */
    @Test
    fun `scenario E — #202 fix — same responder, msg-1 lost, msg-2 delivers to fresh B`() {
        // Mint a fresh production-faithful pair: A is the responder, B is the
        // initiator. Both start with recv_chain_key=None (B's case) /
        // send_chain_key=None (A's case until first send).
        val pair = PqxdhHandshakeFixture.mintProductionFaithfulSessionPair()

        // A encrypts msg-1 — triggers step_send (send chain was None).
        // We DROP wire-1 to simulate it never reaching B.
        val droppedWire1 = pair.responder.encryptToBytes("lost message".toByteArray())
        println("[scenE] A msg-1 wire (DROPPED): ${droppedWire1.size}B")

        // A encrypts msg-2 from the SAME session. step_send is now skipped
        // because send_chain_key is Some. Pre-fix this wire would have NO
        // dh_pub in its header.
        val pt2 = "msg-2 must decrypt".toByteArray()
        val wire2 = pair.responder.encryptToBytes(pt2)
        println("[scenE] A msg-2 wire (delivered): ${wire2.size}B")

        // B (fresh initiator from the same pair) decrypts wire-2. Pre-fix
        // this returned Internal("no recv chain key"). Post-fix the catch-up
        // step_recv path runs and decrypt succeeds.
        val result = runCatching { pair.initiator.decryptFromBytes(wire2) }
        if (result.isFailure) {
            val err = result.exceptionOrNull()!!
            println("[scenE] FAIL — B decrypt error: ${err::class.java.simpleName}: ${err.message}")
        } else {
            println("[scenE] PASS — B decrypted msg-2 standalone")
        }
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isEqualTo(pt2)

        pair.closeAll()
    }

    // --- Scenario D — Initiator stale + sends first ZERO ratchet step --------

    /**
     * What does an initiator's wire look like on its FIRST send? An initiator
     * already has send_chain_key=Some, so step_send is skipped on the first
     * encrypt — meaning the wire header has NO dh_pub and ratchet_gen=0. This
     * is the wire that B receives in our scenario A "stale initiator" case.
     *
     * For comparison, a fresh responder's first send DOES run step_send →
     * header has dh_pub and ratchet_gen=1. This characterization confirms WHY
     * Scenario A's wire would be diagnosed differently by B.
     *
     * If B receives an initiator-shaped first wire (no dh_pub, gen=0), B's
     * decrypt path looks for skipped[(0,0)] then recv_chain_key — initiator's
     * fresh state has recv_chain_key=None, so the error is exactly
     * "no recv chain key". THIS reproduces the production error shape.
     */
    @Test
    fun `scenario D — stale initiator first-send IS production no-recv-chain-key shape`() {
        // pair1: stale A (was redeemer / initiator).
        val pair1 = PqxdhHandshakeFixture.mintProductionFaithfulSessionPair()
        val oldInitiatorBlob = pair1.initiator.serializeState()
        pair1.closeAll()

        // pair2: new pairing where A is now responder and B is initiator.
        val pair2 = PqxdhHandshakeFixture.mintProductionFaithfulSessionPair()
        val newInitiatorBlob = pair2.initiator.serializeState()
        pair2.responder.close()
        pair2.initiator.close()

        val staleA = Session.fromSerializedState(oldInitiatorBlob)
        val freshB = Session.fromSerializedState(newInitiatorBlob)

        println("[scenD] A's IME session: ${inspectRole(staleA.serializeState())}")
        println("[scenD] B's IME session: ${inspectRole(freshB.serializeState())}")
        println("[scenD] A SID: ${hex(extractSid(oldInitiatorBlob))}")
        println("[scenD] B SID: ${hex(extractSid(newInitiatorBlob))}")

        // A sends from its stale initiator state. Since send_chain_key was
        // already Some (from old pair1's initiator construction), step_send is
        // skipped → wire header has no dh_pub, ratchet_gen unchanged.
        val pt = ByteArray(32) { (it + 0xC0).toByte() }
        val ct = staleA.encryptToBytes(pt)
        println("[scenD] A wire size: ${ct.size}B")

        val result = runCatching { freshB.decryptFromBytes(ct) }
        assertThat(result.isFailure).isTrue()
        val err = result.exceptionOrNull()!!
        val type = err::class.java.simpleName
        val msg = err.message ?: ""
        println("[scenD] B decrypt error: $type: $msg")
        println("[scenD] Matches production 'no recv chain key' shape? ${msg.contains("no recv chain key")}")

        staleA.close()
        freshB.close()
    }
}
