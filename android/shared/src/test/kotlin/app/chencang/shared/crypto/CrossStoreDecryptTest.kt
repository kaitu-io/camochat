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
import uniffi.chencang.Session

/**
 * #201 H1 probe — cross-process Room rehydration.
 *
 * Production layout: Companion's [RatchetSessionStore] writes session blob to
 * cc-sessions.db via [SessionStateDao]. B's IME process then opens its OWN
 * [RatchetSessionStore] over the SAME DB and hydrates the blob into a new
 * `Session.fromSerializedState(...)`. If anything differs between the IME's
 * rehydrated session and what Companion stored, decrypt fails with the
 * `no recv chain key` shape seen in 2026-05-31 UAT.
 *
 * This test simulates that exact split:
 *  1. mint a fresh production-faithful (SPK-only + null secrets) pair
 *  2. write the responder (A's side — "the sender" in the UAT) into store-A
 *     over DAO-1
 *  3. open store-A2 over the SAME DAO (same DB file), let it hydrate
 *  4. encrypt via store-A2's rehydrated session, decrypt via the LIVE
 *     in-memory initiator (B's side) — production-mirror direction
 *
 * Then the symmetric variant: B's initiator goes through the store/rehydrate
 * cycle, A's responder (live) sends the first message.
 *
 * If either FAILS with `no recv chain key`, H1 is SUPPORTED.
 * If both PASS, cross-store rehydration is eliminated as a cause.
 */
@RunWith(RobolectricTestRunner::class)
class CrossStoreDecryptTest {

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
    fun `responder rehydrated cross-store can encrypt to live initiator (R-to-I first)`() = runTest {
        val pair = PqxdhHandshakeFixture.mintProductionFaithfulSessionPair()
        try {
            // Store-A: Companion side — writes responder (A's side in UAT) to DB.
            val storeA = RatchetSessionStore(dao = dao)
            storeA.awaitReady()
            storeA.put("peerFp", pair.responder)
            // pair.responder is now OWNED by storeA — must not be re-used directly.

            // Store-A2: IME side — opens same DB, hydrates from blob.
            val storeA2 = RatchetSessionStore(dao = dao)
            storeA2.awaitReady()
            assertThat(storeA2.keys()).contains("peerFp")

            // A (responder, rehydrated in storeA2) sends first to B (initiator, live).
            val pt = ByteArray(32) { (it + 0x30).toByte() }
            val ct = storeA2.encryptToBytes("peerFp", pt)
            val recovered = pair.initiator.decryptFromBytes(ct)
            assertThat(recovered).isEqualTo(pt)
            println("[H1] R→I after cross-store rehydrate: OK")
        } finally {
            pair.initiator.close()
            // pair.responder is owned by storeA / storeA2 by now; closing it
            // here would double-free.
        }
    }

    @Test
    fun `initiator rehydrated cross-store can decrypt from live responder (R-to-I first)`() = runTest {
        // Production-mirror: B's IME persists initiator state, then A (live
        // responder, never written to disk in this scenario) sends first.
        val pair = PqxdhHandshakeFixture.mintProductionFaithfulSessionPair()
        try {
            val storeB = RatchetSessionStore(dao = dao)
            storeB.awaitReady()
            storeB.put("peerFp", pair.initiator)

            val storeB2 = RatchetSessionStore(dao = dao)
            storeB2.awaitReady()
            assertThat(storeB2.keys()).contains("peerFp")

            // A (responder, live) sends first; B (initiator, rehydrated) decrypts.
            val pt = ByteArray(32) { (it + 0x40).toByte() }
            val ct = pair.responder.encryptToBytes(pt)
            val result = runCatching { storeB2.decryptFromBytesAny(ct) }
            if (result.isFailure) {
                val err = result.exceptionOrNull()!!
                println(
                    "[H1] R→I via cross-store rehydrated initiator FAILED: " +
                        "${err::class.java.simpleName}: ${err.message}",
                )
                if (err is RatchetSessionStore.NoSessionMatched) {
                    println("[H1]   attempts: ${err.renderAttempts()}")
                }
                throw err
            }
            val decrypted = result.getOrThrow()
            assertThat(decrypted.plaintext).isEqualTo(pt)
            println("[H1] R→I via cross-store rehydrated initiator: OK")
        } finally {
            pair.responder.close()
            // pair.initiator owned by storeB after put().
        }
    }
}
