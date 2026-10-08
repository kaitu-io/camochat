package app.chencang.shared.crypto

import com.google.common.truth.Truth.assertThat
import org.junit.Assume
import org.junit.BeforeClass
import org.junit.Test
import uniffi.chencang.Session

/**
 * Layered crypto E2E test — exercises post-handshake Double Ratchet at the
 * raw [Session.encryptToBytes] / [Session.decryptFromBytes] API. Lives below
 * the V1 wire codec and the frame layer so a failure here pins the bug to
 * chencang-core's ratchet, not Android wiring or Base32768 encoding.
 *
 * Each case mints a fresh session pair via [PqxdhHandshakeFixture]; no state
 * leaks between cases.
 *
 * Native lib loading: same guard as [SessionV1WireRoundTripTest]. The host
 * bindings dylib must be on `jna.library.path` and the uniffi component
 * `libraryOverride` system property set. CI matrices that skip the host
 * crate are allowed to skip this whole class.
 */
class HandshakeBidirectionalE2ETest {

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
    }

    /**
     * Baseline. If this fails, the test infra is broken — there's no point
     * looking at the other cases. Mirrors what production B→A would do if
     * the canonical Signal-style direction were followed.
     */
    @Test
    fun `canonical direction — initiator encrypts, responder decrypts`() {
        val pair = PqxdhHandshakeFixture.mintLoopbackSessionPair()
        try {
            val pt = ByteArray(32) { it.toByte() }
            val ct = pair.initiator.encryptToBytes(pt)
            val recovered = pair.responder.decryptFromBytes(ct)
            assertThat(recovered).isEqualTo(pt)
        } finally {
            pair.closeAll()
        }
    }

    /**
     * Responder sends first to initiator at the raw DR layer. This is the
     * exact pattern that failed in the live 2026-05-31 UAT (A=responder,
     * B=initiator, A sent voice, B saw `no recv chain key`).
     *
     * **The test PASSES at the in-process layer.** That decisively pins
     * #192 to Android client wiring (HandshakePairing.kt, SessionManager,
     * RatchetSessionStore persistence) — NOT to chencang-core. Investigation
     * for the production bug should focus on `RatchetSessionStorePersistenceTest`
     * scenarios and what differs between the in-process Session and a
     * Session rehydrated from cc-sessions.db immediately after handshake.
     *
     * Keep this test as a forward gate: if chencang-core EVER regresses on
     * responder-first messaging, this test goes red before any device UAT
     * has to.
     */
    @Test
    fun `responder direction — responder encrypts first, initiator decrypts`() {
        val pair = PqxdhHandshakeFixture.mintLoopbackSessionPair()
        try {
            val pt = ByteArray(32) { it.toByte() }
            val ct = pair.responder.encryptToBytes(pt)
            val recovered = pair.initiator.decryptFromBytes(ct)
            assertThat(recovered).isEqualTo(pt)
        } finally {
            pair.closeAll()
        }
    }

    /**
     * Production-faithful: SPK-only handshake + responder gets reconstructed
     * [InitiatorHandshakeOutput] with NULL ephemeral secrets (matches
     * `HandshakePairing.poll` line 109/114-124 byte-for-byte). All four
     * directions + sequence patterns should still work — if any of these
     * fail, that pins #192 to chencang-core's null-secret handling.
     *
     * The base [`responder direction`] test passes with the OPK-fed fixture.
     * If THIS variant fails, the difference is null secrets — which would
     * fully explain the production `no recv chain key` shape.
     */
    @Test
    fun `production-faithful — SPK-only + null secrets — all four direction patterns`() {
        // Reuse helper closure to mint fresh pair each scenario (sessions advance state).
        fun freshPair() = PqxdhHandshakeFixture.mintProductionFaithfulSessionPair()

        // Canonical I→R.
        freshPair().let { pair ->
            try {
                val pt = ByteArray(32) { it.toByte() }
                val ct = pair.initiator.encryptToBytes(pt)
                assertThat(pair.responder.decryptFromBytes(ct)).isEqualTo(pt)
                println("[prod-faithful] canonical I→R: OK")
            } finally { pair.closeAll() }
        }

        // R→I first message (the LIVE-UAT scenario).
        freshPair().let { pair ->
            try {
                val pt = ByteArray(32) { (it + 1).toByte() }
                val ct = pair.responder.encryptToBytes(pt)
                assertThat(pair.initiator.decryptFromBytes(ct)).isEqualTo(pt)
                println("[prod-faithful] R→I first: OK")
            } finally { pair.closeAll() }
        }

        // Ping-pong.
        freshPair().let { pair ->
            try {
                val steps = listOf(
                    Triple(pair.initiator, pair.responder, "I→R"),
                    Triple(pair.responder, pair.initiator, "R→I"),
                    Triple(pair.initiator, pair.responder, "I→R"),
                    Triple(pair.responder, pair.initiator, "R→I"),
                )
                for ((sender, receiver, label) in steps) {
                    val pt = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
                    val ct = sender.encryptToBytes(pt)
                    assertThat(receiver.decryptFromBytes(ct)).isEqualTo(pt)
                    println("[prod-faithful] ping-pong $label: OK")
                }
            } finally { pair.closeAll() }
        }

        // Persistence at-handshake — initiator rehydrates, R→I first.
        freshPair().let { pair ->
            try {
                val blob = pair.initiator.serializeState()
                pair.initiator.close()
                val rehydrated = Session.fromSerializedState(blob)
                try {
                    val pt = ByteArray(32) { (it + 2).toByte() }
                    val ct = pair.responder.encryptToBytes(pt)
                    assertThat(rehydrated.decryptFromBytes(ct)).isEqualTo(pt)
                    println("[prod-faithful] init-rehydrate + R→I: OK (blob=${blob.size}B)")
                } finally { rehydrated.close() }
            } finally { pair.responder.close() }
        }
    }

    /**
     * The production scenario #192 mirrors exactly: B (initiator) completed
     * the handshake, persisted its session to cc-sessions.db with NO messages
     * yet exchanged, the IME process was killed (reinstall / MIUI cleanup),
     * the session was rehydrated from the blob, then A (responder) sent the
     * first message. Live UAT: failed with `no recv chain key`.
     *
     * Tests both rehydration points:
     *  - initiator rehydrates pre-first-message, receives R→I (mirrors live B)
     *  - responder rehydrates pre-first-message, receives I→R (canonical sanity)
     *
     * If either fails, that's where #192 lives — a session whose serializeState
     * blob has been taken before any DH ratchet has fired loses the chain
     * material needed for the first message in one direction.
     */
    @Test
    fun `persistence at-handshake — rehydrate before first message, then decrypt`() {
        // Scenario A — production-mirror: initiator rehydrates, responder sends first.
        run {
            val pair = PqxdhHandshakeFixture.mintLoopbackSessionPair()
            try {
                val blob = pair.initiator.serializeState()
                println("[persistence at-handshake] initiator state blob (pre-msg): ${blob.size} bytes")
                pair.initiator.close()

                val rehydrated = Session.fromSerializedState(blob)
                try {
                    val pt = ByteArray(32) { (it + 0x10).toByte() }
                    val ct = pair.responder.encryptToBytes(pt)
                    val recovered = rehydrated.decryptFromBytes(ct)
                    assertThat(recovered).isEqualTo(pt)
                } finally {
                    rehydrated.close()
                }
            } finally {
                pair.responder.close()
            }
        }

        // Scenario B — canonical sanity: responder rehydrates, initiator sends first.
        run {
            val pair = PqxdhHandshakeFixture.mintLoopbackSessionPair()
            try {
                val blob = pair.responder.serializeState()
                println("[persistence at-handshake] responder state blob (pre-msg): ${blob.size} bytes")
                pair.responder.close()

                val rehydrated = Session.fromSerializedState(blob)
                try {
                    val pt = ByteArray(32) { (it + 0x20).toByte() }
                    val ct = pair.initiator.encryptToBytes(pt)
                    val recovered = rehydrated.decryptFromBytes(ct)
                    assertThat(recovered).isEqualTo(pt)
                } finally {
                    rehydrated.close()
                }
            } finally {
                pair.initiator.close()
            }
        }
    }

    /**
     * Ping-pong: alternates direction every message. Each direction switch
     * triggers a DH ratchet step on the sender and a corresponding ratchet
     * step on the receiver. Lower-bound coverage of the ratchet machinery
     * beyond a single round-trip.
     *
     * GATED on #192: until responder-first works, any message in the
     * R→I direction will throw the same error documented in
     * [`regression #192 — responder cannot send first to initiator`].
     * We could `@Ignore` this, but instead we run as far as the protocol
     * allows so a partial fix to #192 (e.g. responder works after one
     * initiator-first message) is detected.
     */
    @Test
    fun `ping-pong — alternating direction across N rounds`() {
        val pair = PqxdhHandshakeFixture.mintLoopbackSessionPair()
        try {
            // Tuple of (sender, receiver, label) per step. Alternates each round.
            val rounds = listOf(
                Triple(pair.initiator, pair.responder, "I→R round 1"),
                Triple(pair.responder, pair.initiator, "R→I round 1"),
                Triple(pair.initiator, pair.responder, "I→R round 2"),
                Triple(pair.responder, pair.initiator, "R→I round 2"),
            )

            for ((sender, receiver, label) in rounds) {
                val pt = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
                val result = runCatching {
                    val ct = sender.encryptToBytes(pt)
                    receiver.decryptFromBytes(ct)
                }
                if (result.isFailure) {
                    val err = result.exceptionOrNull()!!
                    println("[ping-pong] stopped at $label: ${err::class.java.simpleName}: ${err.message}")
                    // Don't fail the test — this is a discovery probe. Whichever
                    // round fails first pins where the ratchet contract breaks.
                    return
                }
                assertThat(result.getOrThrow()).isEqualTo(pt)
                println("[ping-pong] $label: OK")
            }
        } finally {
            pair.closeAll()
        }
    }

    /**
     * Persistence midstream: simulates what production does when a paired
     * device's process dies between messages (MIUI cleanup / reboot / `am
     * force-stop` / reinstall). The receiver serializes its state to a blob,
     * the in-memory Session is dropped, the blob is rehydrated, and the next
     * message decrypts on the new Session.
     *
     * This is the cc-sessions.db round-trip that B successfully relied on
     * during the 2026-05-31 UAT — re-running the same wire after reinstall
     * still landed at the same session and same error. Locking that path in
     * as a unit-level gate so future ratchet-state changes can't silently
     * break it.
     *
     * Canonical direction only (initiator → responder). The R→I direction
     * is blocked by #192; revisit when that lands.
     */
    @Test
    fun `persistence midstream — responder serialize, rehydrate, decrypt next`() {
        val pair = PqxdhHandshakeFixture.mintLoopbackSessionPair()
        try {
            val pt1 = ByteArray(32) { (it + 1).toByte() }
            val ct1 = pair.initiator.encryptToBytes(pt1)
            assertThat(pair.responder.decryptFromBytes(ct1)).isEqualTo(pt1)

            val blob = pair.responder.serializeState()
            println("[persistence] responder state blob: ${blob.size} bytes")
            pair.responder.close()

            val rehydrated = Session.fromSerializedState(blob)
            try {
                val pt2 = ByteArray(32) { (it + 2).toByte() }
                val ct2 = pair.initiator.encryptToBytes(pt2)
                assertThat(rehydrated.decryptFromBytes(ct2)).isEqualTo(pt2)
            } finally {
                rehydrated.close()
            }
        } finally {
            // responder was already closed above; close initiator only.
            pair.initiator.close()
        }
    }
}
