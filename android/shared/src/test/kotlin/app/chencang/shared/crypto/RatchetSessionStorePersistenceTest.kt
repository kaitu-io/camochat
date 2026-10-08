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
import uniffi.chencang.PreKeyBundle
import uniffi.chencang.SecretIdentity
import uniffi.chencang.SecretOneTimePreKey
import uniffi.chencang.SecretSignedPreKey
import uniffi.chencang.Session
import uniffi.chencang.deriveInitiatorHandshake
import uniffi.chencang.deriveResponderHandshake

/**
 * Persistence round-trip for [RatchetSessionStore] backed by an in-memory Room
 * DB ([SessionStateDatabase]'s [SessionStateDao]). Verifies sessions survive a
 * simulated process restart (build a fresh store over the same DAO) and that
 * every session touched during [RatchetSessionStore.decryptFromBytesAny] is
 * persisted — including a decoy whose decrypt fails (DR may advance state on a
 * failed AEAD attempt, issue #142).
 *
 * Host-bindings gate: minting a real [Session] requires the host-built
 * `libchencang_bindings.dylib` on the JNA path (same mechanism as
 * [SessionV1WireRoundTripTest]). On lanes that don't build the host crate the
 * whole class is skipped via [Assume]; the rest of the unit suite still runs.
 */
@RunWith(RobolectricTestRunner::class)
class RatchetSessionStorePersistenceTest {

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
    fun `hydrate restores a previously put session`() = runTest {
        val (alice, _) = mintLoopbackSessionPair()

        val store1 = RatchetSessionStore(dao = dao)
        store1.awaitReady()
        store1.put("alice", alice)

        // Simulate process restart: a brand-new store over the same DB rows.
        val store2 = RatchetSessionStore(dao = dao)
        store2.awaitReady()
        assertThat(store2.keys()).contains("alice")
    }

    @Test
    fun `encryptToBytes persists chain state across a restart`() = runTest {
        val (sender, _) = mintLoopbackSessionPair()

        val store1 = RatchetSessionStore(dao = dao)
        store1.awaitReady()
        store1.put("bob", sender)
        // First encrypt advances + persists the ratchet.
        store1.encryptToBytes("bob", "hello".toByteArray())

        // Restart: rehydrate from the persisted (advanced) state.
        val store2 = RatchetSessionStore(dao = dao)
        store2.awaitReady()
        assertThat(store2.keys()).contains("bob")
        // The point: the session is back, so a second encrypt does NOT throw
        // NoSessionForPeer.
        val ct = store2.encryptToBytes("bob", "world".toByteArray())
        assertThat(ct).isNotEmpty()
    }

    @Test
    fun `decryptFromBytesAny persists every touched session`() = runTest {
        // Pair A: realSender encrypts the wire, realRecv decrypts it.
        val (realSender, realRecv) = mintLoopbackSessionPair()
        // Pair B: an unrelated session used as a decoy — its decrypt will fail
        // and (per #142) may advance state, so it must still be persisted.
        val (decoy, _) = mintLoopbackSessionPair()

        val store1 = RatchetSessionStore(dao = dao)
        store1.awaitReady()
        store1.put("real", realRecv)
        store1.put("decoy", decoy)

        val wire = realSender.encryptToBytes("ping".toByteArray())
        val decrypted = store1.decryptFromBytesAny(wire)
        assertThat(decrypted.plaintext).isEqualTo("ping".toByteArray())

        // Both labels must have been persisted: the matcher AND the decoy.
        assertThat(dao.all().map { it.peerLabel }).containsExactly("real", "decoy")

        // Restart: both sessions come back.
        val store2 = RatchetSessionStore(dao = dao)
        store2.awaitReady()
        assertThat(store2.keys()).containsExactly("real", "decoy")
    }

    @Test
    fun `corrupted blob is skipped silently on hydrate`() = runTest {
        val (good, _) = mintLoopbackSessionPair()

        // A valid row plus a row whose blob is garbage.
        val store0 = RatchetSessionStore(dao = dao)
        store0.awaitReady()
        store0.put("good", good)

        dao.upsert(
            SessionStateEntity(
                peerLabel = "corrupt",
                peerAlias = "corrupt",
                stateBlob = byteArrayOf(0xff.toByte(), 0x00, 0x13, 0x37),
                updatedAt = 0L,
            ),
        )

        // Hydrate must not throw; the corrupt label is dropped, the good one stays.
        val store = RatchetSessionStore(dao = dao)
        store.awaitReady()
        val keys = store.keys()
        assertThat(keys).contains("good")
        assertThat(keys).doesNotContain("corrupt")
    }

    @Test
    fun `clear drops in-memory sessions aliases and db rows`() = runTest {
        val (alice, _) = mintLoopbackSessionPair()

        val store = RatchetSessionStore(dao = dao)
        store.awaitReady()
        store.put("alice", alice)

        store.clear()

        assertThat(store.keys()).isEmpty()
        assertThat(dao.all()).isEmpty()

        // Decrypting anything against an emptied store must report "store is empty",
        // not silently no-op or match a stale session. `kotlin-test` isn't on this
        // module's classpath, so a plain try/catch stands in for assertFailsWith.
        var caught: RatchetSessionStore.NoSessionMatched? = null
        try {
            store.decryptFromBytesAny(byteArrayOf(0))
        } catch (e: RatchetSessionStore.NoSessionMatched) {
            caught = e
        }
        assertThat(caught).isNotNull()
    }

    @Test
    fun `remove drops one session, the other survives, in-memory only`() = runTest {
        val (alice, _) = mintLoopbackSessionPair()
        val (bob, _) = mintLoopbackSessionPair()

        val store = RatchetSessionStore(dao = null)
        store.awaitReady()
        store.put("alice", alice)
        store.put("bob", bob)

        store.remove("alice")

        assertThat(store.keys()).containsExactly("bob")
    }

    @Test
    fun `remove deletes the persisted db row too`() = runTest {
        val (alice, _) = mintLoopbackSessionPair()
        val (bob, _) = mintLoopbackSessionPair()

        val store = RatchetSessionStore(dao = dao)
        store.awaitReady()
        store.put("alice", alice)
        store.put("bob", bob)
        assertThat(dao.all().map { it.peerLabel }).containsExactly("alice", "bob")

        store.remove("alice")

        assertThat(store.keys()).containsExactly("bob")
        assertThat(dao.all().map { it.peerLabel }).containsExactly("bob")

        // Restart: the removed session must not come back from disk.
        val store2 = RatchetSessionStore(dao = dao)
        store2.awaitReady()
        assertThat(store2.keys()).containsExactly("bob")
    }

    /**
     * Run the full PQXDH handshake and produce a (Bob/sender, Alice/recv)
     * Session pair. Copied from [SessionV1WireRoundTripTest.mintLoopbackSessionPair]
     * so this test doesn't reach into the `uat` package (Android-only deps).
     */
    private fun mintLoopbackSessionPair(): Pair<Session, Session> {
        val bobIk = SecretIdentity()
        val aliceIk = SecretIdentity()
        val aliceSpk = SecretSignedPreKey(aliceIk, 1u)
        val aliceOpk = SecretOneTimePreKey(7u)

        val bundle = PreKeyBundle(
            ik = aliceIk.publicIdentity(),
            spk = aliceSpk.publicForm(),
            opk = aliceOpk.publicForm(),
            inviterUsername = "alice",
            inviteId = ByteArray(16) { 0x11.toByte() },
            pairingNonce = ByteArray(16) { 0x22.toByte() },
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
            ByteArray(16) { 0x22.toByte() },
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
}
