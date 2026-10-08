package app.chencang.shared.crypto

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assume
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import uniffi.chencang.InitiatorHandshakeOutput
import uniffi.chencang.PreKeyBundle
import uniffi.chencang.SecretIdentity
import uniffi.chencang.SecretSignedPreKey
import uniffi.chencang.Session
import uniffi.chencang.deriveInitiatorHandshake
import uniffi.chencang.deriveResponderHandshake

/**
 * #201 H3 probe — multi-invite overwrite.
 *
 * `RatchetSessionStore.put()` at RatchetSessionStore.kt:104-116 unconditionally
 * overwrites `sessions[name]` (keyed by peerFp). If A creates multiple invites
 * for the same B, and multiple get redeemed, A's last successful poll wins.
 *
 * Failure mode under investigation: A's session contains root_last; B has
 * sessions for BOTH redeems (or just the first if B didn't replay the second
 * accept) → decrypt fails when A→B uses root_last but B kept root_first.
 *
 * Setup:
 *  - A (responder) makes 2 invites against the SAME SPK (V1 production uses
 *    a single long-lived SPK — invites differ only in inviteId/nonce).
 *  - B (initiator) redeems both with two separate Bs (Bob1, Bob2) — or one
 *    Bob redeeming twice.  V1 production only stores one session per peerFp,
 *    so we model B as the SAME initiator identity redeeming twice (each
 *    deriving its own ephemeral EK → different rootR1, rootR2).
 *  - A polls both → put(peerFp, session) twice → store keeps last session.
 *  - Check: A's stored session matches WHICH of B's two sessions?
 *
 * Verdict logic:
 *  - If A's stored session = root from second redeem AND B kept the first
 *    redeem session, A→B fails. The "redeem twice" scenario is plausible if
 *    B re-clicks the same invite link, or A creates a fresh invite after
 *    forgetting B is already paired.
 */
@RunWith(RobolectricTestRunner::class)
class MultiInvitePollTest {

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

    private lateinit var db: SessionStateDatabase
    private lateinit var dao: SessionStateDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            SessionStateDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.sessionStateDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `A polls two redeems for same peerFp - last write wins, first-session decrypt fails`() = runTest {
        // Shared A identity + persistent SPK (V1: SPK is provisioned ONCE).
        val responderIk = SecretIdentity()
        val responderSpk = SecretSignedPreKey(responderIk, 1u)
        val responderPub = responderIk.publicIdentity()

        // Helper: run one full accept (B side) + poll (A side) and return the
        // resulting (B_initiator_session, A_responder_session, peerFp).
        fun oneRedeemCycle(
            initiatorIk: SecretIdentity,
            inviteIdByte: Byte,
        ): Triple<Session, Session, String> {
            val bundle = PreKeyBundle(
                ik = responderPub,
                spk = responderSpk.publicForm(),
                opk = null,
                inviterUsername = "responder",
                inviteId = ByteArray(16) { inviteIdByte },
                pairingNonce = ByteArray(16) { (inviteIdByte + 1).toByte() },
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
                ByteArray(16) { (inviteIdByte + 1).toByte() },
            )
            val sid = byteArrayOf(inviteIdByte, inviteIdByte, inviteIdByte, inviteIdByte, inviteIdByte)
            val initSess = Session.initiatorAfterHandshake(
                initOut.sessionRootKey,
                sid,
                responderPub,
                initOut.ekX25519Secret!!,
                initOut.ekMlkemSecret!!,
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
            val responderSess = Session.responderAfterHandshake(responderRoot, sid, responderOutput)
            // peerFp: deterministic from initiator's IK (same on both invites since
            // the same B redeems both — this is the heart of the overwrite scenario).
            val peerFp = "b-fixed-fp"
            return Triple(initSess, responderSess, peerFp)
        }

        // SAME B identity redeems twice.
        val initiatorIk = SecretIdentity()

        val (bSess1, aSess1, peerFp) = oneRedeemCycle(initiatorIk, 0x11.toByte())
        val (bSess2, aSess2, _) = oneRedeemCycle(initiatorIk, 0x22.toByte())

        // A's store: poll first invite → put, then poll second invite → put
        // (OVERWRITE). This mirrors RatchetSessionStore.kt:104-116.
        val storeA = RatchetSessionStore(dao = dao)
        storeA.awaitReady()
        storeA.put(peerFp, aSess1) // first poll
        storeA.put(peerFp, aSess2) // second poll — overwrites; aSess1 is closed by store.

        // B's reality at this moment in the cross-device scenario:
        //  - "first-session retained": B somehow kept bSess1 (e.g. UI showed pair
        //    success after redeem 1; redeem 2 was a silent retry that never
        //    overwrote B's local session because of a guard).
        //  - "second-session retained": B's last redeem also overwrote B's local.
        // Production code at HandshakePairing.accept calls sessionStore.put(peerFp,...)
        // unconditionally → B ALSO last-write-wins. So aSess2 ↔ bSess2 should
        // round-trip. We test BOTH retention scenarios to characterize the
        // failure shape.

        // Sanity: A's current stored session matches B's bSess2 (last redeem).
        run {
            val pt = ByteArray(16) { (it + 0x70).toByte() }
            val ct = storeA.encryptToBytes(peerFp, pt)
            val recovered = runCatching { bSess2.decryptFromBytes(ct) }
            println(
                "[H3] A_stored ↔ B_last (both second redeem): " +
                    if (recovered.isSuccess) "OK (roots match)"
                    else "FAIL ${recovered.exceptionOrNull()!!::class.java.simpleName}: " +
                        "${recovered.exceptionOrNull()!!.message}",
            )
            assertThat(recovered.isSuccess).isTrue()
            assertThat(recovered.getOrThrow()).isEqualTo(pt)
        }

        // Now: A → B with B holding the FIRST redeem session (the overwrite-victim
        // scenario). We must mint a fresh A→B ciphertext because the previous
        // encrypt advanced aSess2's chain.
        run {
            val pt = ByteArray(16) { (it + 0x80).toByte() }
            val ct = storeA.encryptToBytes(peerFp, pt)
            val recovered = runCatching { bSess1.decryptFromBytes(ct) }
            if (recovered.isSuccess) {
                println("[H3] UNEXPECTED: bSess1 decrypted ciphertext from aSess2!")
            } else {
                val err = recovered.exceptionOrNull()!!
                println(
                    "[H3] A_stored (last) → B_first (orphan): " +
                        "${err::class.java.simpleName}: ${err.message}",
                )
            }
            // Document the failure shape rather than asserting on it — the test
            // is a probe: we want the error string in the build log either way.
        }

        // Cleanup: aSess1 was closed by storeA when overwritten; storeA owns aSess2.
        bSess1.close()
        bSess2.close()
        responderSpk.close()
    }
}
