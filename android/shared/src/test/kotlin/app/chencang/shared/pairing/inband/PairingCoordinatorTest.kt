package app.chencang.shared.pairing.inband

import androidx.test.core.app.ApplicationProvider
import app.chencang.shared.CcRepository
import app.chencang.shared.contacts.ContactsStore
import app.chencang.shared.contacts.InMemoryContactsStore
import app.chencang.shared.crypto.PrekeyProvisioner
import app.chencang.shared.crypto.RatchetSessionStore
import app.chencang.shared.crypto.SessionStateDao
import app.chencang.shared.crypto.SessionStateEntity
import app.chencang.shared.crypto.SignedPreKeyStore
import app.chencang.shared.identity.IdentityFileEnvelope
import app.chencang.shared.identity.IdentityStore
import app.chencang.shared.model.ContactListSnapshot
import app.chencang.shared.model.PairingState
import app.chencang.shared.R
import app.chencang.shared.pairing.PairingCopy
import app.chencang.shared.pairing.PairingLink
import app.chencang.shared.pairing.PairingShareText
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assume
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import uniffi.chencang.SecretIdentity
import uniffi.chencang.SecretSignedPreKey
import uniffi.chencang.encodeWire
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * End-to-end proof for [PairingCoordinator] against the REAL handshake engine: coordinators for
 * two (or three) identities pair with each other. Covers the cross-platform use-case table B
 * (B1–B21, plan 2026-10-01-three-tab-shell) — match-by-response, role-agnostic intake, repeated
 * paste, the key-material exit — plus process-death resumption.
 *
 * Security assertions to keep an eye on: the trial computation writes nothing ([TestEnv.sessionWrites]
 * stays put), a non-matching response removes no record, one response completes at most one invite.
 *
 * Native lib loading: same guard as [InbandPairingLoopbackTest] — the host
 * bindings dylib must be on `jna.library.path` and the uniffi component
 * `libraryOverride` system property set. CI matrices that skip the host crate
 * skip this class. Robolectric supplies a JVM Context for SharedPreferences.
 */
@RunWith(RobolectricTestRunner::class)
class PairingCoordinatorTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun assumeNativeBindingsAvailable() {
            val override = System.getProperty("uniffi.component.chencang.libraryOverride")
            val jnaPath = System.getProperty("jna.library.path")
            Assume.assumeTrue(
                "host bindings not configured (jna.library.path / libraryOverride absent)",
                override != null && jnaPath != null,
            )
        }
    }

    /** Counts every session row written — "did the session store see a write" is a security assertion here. */
    private class RecordingSessionDao : SessionStateDao {
        val upserts = mutableListOf<String>()

        /** 非空时,`upsert` 先通知 [entered],再挂起到 [gate] 放行(模拟提交中途的挂起点)。 */
        var gate: CompletableDeferred<Unit>? = null
        val entered = CompletableDeferred<Unit>()
        override suspend fun all(): List<SessionStateEntity> = emptyList()
        override suspend fun upsert(row: SessionStateEntity) {
            gate?.let {
                entered.complete(Unit)
                it.await()
            }
            upserts += row.peerLabel
        }

        override suspend fun delete(label: String) = Unit
        override suspend fun clear() = Unit
    }

    /** Logs record removals into [log] so a test can order them against key-material invalidation. */
    private class LoggingInviteStore(
        private val delegate: PendingInviteStore,
        private val log: MutableList<String>,
    ) : PendingInviteStore {
        override val records: Flow<List<PendingPairingRecord>> get() = delegate.records
        override suspend fun all() = delegate.all()
        override suspend fun append(record: PendingPairingRecord) = delegate.append(record)
        override suspend fun update(id: String, transform: (PendingPairingRecord) -> PendingPairingRecord) =
            delegate.update(id, transform)

        override suspend fun remove(id: String) {
            log += "remove:$id"
            delegate.remove(id)
        }

        override suspend fun clear() {
            log += "clear"
            delegate.clear()
        }
    }

    /** A contacts store whose writes can be made to fail, to model a failed DataStore write. */
    private class FlakyContactsStore : ContactsStore {
        private val delegate = InMemoryContactsStore()
        var failWrites = false
        override val data: Flow<ContactListSnapshot> get() = delegate.data
        override suspend fun updateData(
            transform: suspend (ContactListSnapshot) -> ContactListSnapshot,
        ): ContactListSnapshot {
            if (failWrites) throw IOException("contacts store unavailable")
            return delegate.updateData(transform)
        }
    }

    /**
     * Per-identity bundle of on-disk-equivalent stores the test controls. The
     * identity + SPK live in temp files (survive a "process kill"); the pending
     * stores, session store, and repository are in-memory stand-ins for the
     * production DataStore/Room and are kept across [reopen] to model a restart.
     */
    private class TestEnv private constructor(
        val tag: String,
        val identityFile: File,
        val spkFile: File,
        val pending: PendingInviteStore,
        val responses: PairingResponseStore,
        val sessionDao: RecordingSessionDao,
        val sessionStore: RatchetSessionStore,
        val repository: CcRepository,
        val keyMaterial: InviteKeyMaterialInvalidator,
        val defaultContactName: (String) -> String = { "Contact $it" },
    ) {
        var nowMillis = 1_000_000L
        private var nextId = 0
        val coord: PairingCoordinator = build()

        private fun build(): PairingCoordinator {
            val identityStore = IdentityStore(identityFile, IdentityFileEnvelope.passthrough)
            val provisioner = PrekeyProvisioner(
                // Per-identity prefs (keyed on the unique temp dir) so tests don't
                // contaminate each other's SPK-version marker — but STABLE across
                // reopen() so a "restart" sees the same persisted state. Sharing one
                // name made fresh_identities… pass via a stale marker, not the real
                // both-absent path.
                prefs = ApplicationProvider.getApplicationContext<android.content.Context>()
                    .getSharedPreferences("pairing-coordinator-test-${identityFile.parentFile!!.name}", 0),
                spkStore = SignedPreKeyStore(IdentityFileEnvelope.passthrough, spkFile),
            )
            return PairingCoordinator(
                identityStore = identityStore,
                prekeyProvisioner = provisioner,
                sessionStore = sessionStore,
                repository = repository,
                pending = pending,
                responses = responses,
                defaultContactName = defaultContactName,
                keyMaterial = keyMaterial,
                now = { nowMillis },
                newPairingId = { "$tag-invite-${++nextId}" },
            )
        }

        /** Models a process restart: a NEW coordinator over the SAME backing stores. */
        fun reopen(): TestEnv = with()

        /** Same identity and data, with one collaborator swapped. */
        fun with(
            pending: PendingInviteStore = this.pending,
            keyMaterial: InviteKeyMaterialInvalidator = this.keyMaterial,
        ): TestEnv = TestEnv(
            "$tag'", identityFile, spkFile, pending, responses, sessionDao, sessionStore, repository, keyMaterial,
            defaultContactName,
        )

        /**
         * The same person on a device with no data: same identity + SPK, empty stores. Lets a
         * test obtain a response from someone whose own contact list would (rightly) refuse to
         * produce one.
         */
        fun sameIdentityNoData(): TestEnv {
            val dao = RecordingSessionDao()
            return TestEnv(
                "$tag-blank", identityFile, spkFile, InMemoryPendingInviteStore(), InMemoryPairingResponseStore(),
                dao, RatchetSessionStore(dao = dao), CcRepository.forTest(), NoInviteKeyMaterial, defaultContactName,
            )
        }

        suspend fun contacts() = repository.contacts.first()

        /** How many times the session store was written. */
        val sessionWrites: Int get() = sessionDao.upserts.size

        companion object {
            /**
             * @param seedSpk when false, only the identity is seeded on "disk" — NOT
             *   the SPK. Models a brand-new identity that has never run the (now
             *   removed) server boot path; the coordinator must provision the SPK
             *   locally before it can mint an invite.
             */
            fun create(
                tag: String,
                seedSpk: Boolean = true,
                contactsStore: ContactsStore = InMemoryContactsStore(),
                sessionDao: RecordingSessionDao = RecordingSessionDao(),
                defaultContactName: (String) -> String = { "Contact $it" },
            ): TestEnv {
                val dir = Files.createTempDirectory("pairing-$tag").toFile()
                val identityFile = File(dir, "identity.enc")
                val spkFile = File(dir, "spk.enc")
                // Seed identity (+ optionally the active SPK) on "disk".
                val identityStore = IdentityStore(identityFile, IdentityFileEnvelope.passthrough)
                val ik = identityStore.generateAndSave()
                if (seedSpk) {
                    ik.use { live ->
                        SecretSignedPreKey(live, PrekeyProvisioner.SPK_VERSION.toUInt()).use { spk ->
                            SignedPreKeyStore(IdentityFileEnvelope.passthrough, spkFile).save(spk.serialize())
                        }
                    }
                } else {
                    ik.close()
                }
                return TestEnv(
                    tag = tag,
                    identityFile = identityFile,
                    spkFile = spkFile,
                    pending = InMemoryPendingInviteStore(),
                    responses = InMemoryPairingResponseStore(),
                    sessionDao = sessionDao,
                    sessionStore = RatchetSessionStore(dao = sessionDao),
                    repository = CcRepository(contactsStore),
                    keyMaterial = NoInviteKeyMaterial,
                    defaultContactName = defaultContactName,
                )
            }
        }
    }

    // Robolectric needs a Context before any @Test body touches SharedPreferences.
    @Before fun warmContext() {
        ApplicationProvider.getApplicationContext<android.content.Context>()
    }

    /** [from] seals to [to] and [to] opens it, then the other way round — the two sessions really agree. */
    private suspend fun assertSessionsTalk(from: TestEnv, fromPeerFp: String, to: TestEnv, toPeerFp: String) {
        val ping = ByteArray(24) { it.toByte() }
        assertThat(to.sessionStore.decryptFromBytesAny(from.sessionStore.encryptToBytes(fromPeerFp, ping)).plaintext)
            .isEqualTo(ping)
        val pong = ByteArray(24) { (it + 100).toByte() }
        assertThat(from.sessionStore.decryptFromBytesAny(to.sessionStore.encryptToBytes(toPeerFp, pong)).plaintext)
            .isEqualTo(pong)
    }

    private suspend inline fun <reified T : Throwable> assertThrows(block: () -> Unit): T {
        try {
            block()
        } catch (t: Throwable) {
            if (t is T) return t
            throw AssertionError("expected ${T::class.java.simpleName} but got $t", t)
        }
        throw AssertionError("expected ${T::class.java.simpleName} but nothing was thrown")
    }

    /**
     * [inviter]'s environment as an upgrade from the old build would see it: a real DataStore pair
     * whose legacy single slot holds [minted] in the three-field shape the old build wrote (no
     * invite text). The returned stores must be closed by the caller.
     */
    private suspend fun upgradedFromSingleSlot(
        inviter: TestEnv,
        minted: PendingPairingRecord,
    ): Pair<TestEnv, TestDataStores> {
        val stores = TestDataStores(Files.createTempDirectory("pairing-legacy").toFile())
        val legacy = stores.open("chencang_pending_pairing.cbor", PendingPairingRecordSerializer)
        legacy.updateData {
            PendingPairingRecord(minted.pairingId, minted.pairingNonceB64, minted.createdAtMillis)
        }
        val list = stores.open("chencang_pending_pairings.cbor", PendingInviteListSerializer)
        return inviter.with(pending = DataStorePendingInviteStore(list, legacy)) to stores
    }

    // ── Pre-existing cases, on the new signatures ────────────────────────────────────────────

    @Test
    fun full_pairing_through_coordinator_survives_process_death() = runBlocking<Unit> {
        val a = TestEnv.create("alice")
        val b = TestEnv.create("bob")

        val bundleWire = a.coord.startInvite(myDisplayName = "alice").inviteWire
        assertThat(a.pending.all()).hasSize(1)
        assertThat(a.coord.classifyIncoming(bundleWire))
            .isEqualTo(PairingTransport.WireKind.PAIRING_BUNDLE)

        val accept = b.coord.acceptIncoming(bundleWire, myDisplayName = "bob")
        assertThat(b.contacts().single().pairingState).isEqualTo(PairingState.PAIRED)

        // *** simulate A's app process being killed: rebuild A's coordinator
        // from the same on-disk stores ***
        val a2 = a.reopen()
        assertThat(a2.pending.all()).hasSize(1) // pending survived the "kill"

        val complete = a2.coord.completeIncoming(accept.headerWire)
        assertThat(complete.emoji).isEqualTo(accept.emoji)
        assertThat(complete.emoji).hasSize(8)
        assertThat(a2.pending.all()).isEmpty() // removed on success
        assertThat(a2.contacts().single().safetyEmoji).isEqualTo(accept.emoji)

        // sessions actually talk: B encrypts → A (reopened) decrypts
        val msg = ByteArray(24) { it.toByte() }
        val ct = b.sessionStore.encryptToBytes(accept.contact.fingerprintHex, msg)
        val dec = a2.sessionStore.decryptFromBytesAny(ct)
        assertThat(dec.plaintext).isEqualTo(msg)
    }

    @Test
    fun fresh_identities_pair_without_pre_seeded_spk() = runBlocking<Unit> {
        // Neither side has a pre-provisioned SPK — brand-new identities that never
        // ran the (removed) server boot path. The coordinator must provision A's
        // SPK LOCALLY (no server upload) before minting the invite, or the whole
        // zero-server flow is dead on a fresh install. This is the gap the
        // real-device UAT surfaced ("no persisted SPK — provisionLocallyIfNeeded must run").
        val a = TestEnv.create("alice-fresh", seedSpk = false)
        val b = TestEnv.create("bob-fresh", seedSpk = false)

        val bundleWire = a.coord.startInvite(myDisplayName = "alice").inviteWire
        assertThat(bundleWire).isNotEmpty()

        val accept = b.coord.acceptIncoming(bundleWire, myDisplayName = "bob")
        val complete = a.coord.completeIncoming(accept.headerWire)

        assertThat(complete.emoji).isEqualTo(accept.emoji)
        assertThat(complete.emoji).hasSize(8)
    }

    @Test
    fun completeIncoming_without_pending_throws() = runBlocking<Unit> {
        val b = TestEnv.create("bob")
        val a = TestEnv.create("alice")
        // B builds a real header so the wire classifies as PAIRING_HEADER, but A
        // has NO pending record → completeIncoming must reject.
        val bundleWire = a.coord.startInvite(myDisplayName = "alice").inviteWire
        val accept = b.coord.acceptIncoming(bundleWire, myDisplayName = "bob")
        a.pending.clear()
        val fresh = a.reopen()

        assertThrows<NoMatchingInviteException> { fresh.coord.completeIncoming(accept.headerWire) }

        assertThat(fresh.contacts()).isEmpty()
        assertThat(fresh.sessionWrites).isEqualTo(0)
    }

    @Test
    fun acceptIncoming_on_non_bundle_wire_throws() = runBlocking<Unit> {
        val a = TestEnv.create("alice")
        // A clearly-non-pairing string does NOT classify as PAIRING_BUNDLE.
        val junk = "🔒garbage-not-a-real-wire"
        assertThat(a.coord.classifyIncoming(junk))
            .isNotEqualTo(PairingTransport.WireKind.PAIRING_BUNDLE)

        assertThrows<IllegalArgumentException> { a.coord.acceptIncoming(junk, myDisplayName = "alice") }
    }

    // ── Table B ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun b01_two_invites_coexist() = runBlocking<Unit> {
        val a = TestEnv.create("alice")

        val first = a.coord.startInvite("我")
        a.coord.markInviteShared(first.pairingId)
        val second = a.coord.startInvite("我")

        assertThat(a.pending.all().map { it.pairingId }).containsExactly(first.pairingId, second.pairingId).inOrder()
        assertThat(second.inviteWire).isNotEqualTo(first.inviteWire)
        assertThat(second.pairingNonceB64).isNotEqualTo(first.pairingNonceB64)
    }

    @Test
    fun b02_response_completes_only_its_own_invite() = runBlocking<Unit> {
        val a = TestEnv.create("alice")
        val b = TestEnv.create("bob")
        val first = a.coord.startInvite("我")
        a.coord.markInviteShared(first.pairingId)
        val second = a.coord.startInvite("我")

        val accept = b.coord.acceptIncoming(second.inviteWire, "我")
        val complete = a.coord.completeIncoming(accept.headerWire)

        assertThat(a.pending.all().map { it.pairingId }).containsExactly(first.pairingId)
        assertThat(a.contacts()).hasSize(1)
        assertThat(complete.emoji).isEqualTo(accept.emoji)
        assertThat(a.sessionWrites).isEqualTo(1)
    }

    @Test
    fun b03_response_to_someone_elses_invite_matches_nothing() = runBlocking<Unit> {
        val a = TestEnv.create("alice")
        val b = TestEnv.create("bob")
        val c = TestEnv.create("carol")
        val first = a.coord.startInvite("我")
        a.coord.markInviteShared(first.pairingId)
        a.coord.startInvite("我")
        // B answers C's invite; that response then reaches A.
        val strayResponse = b.coord.acceptIncoming(c.coord.startInvite("我").inviteWire, "我").headerWire
        val before = a.pending.all()

        val outcome = a.coord.handleIncoming(strayResponse, "我")
        assertThat(outcome).isEqualTo(IncomingOutcome.Rejected(IncomingRejection.NoMatchingInvite))
        assertThat((outcome as IncomingOutcome.Rejected).reason.messageRes)
            .isEqualTo(R.string.pairing_error_no_matching_invite)
        assertThrows<NoMatchingInviteException> { a.coord.completeIncoming(strayResponse) }

        assertThat(a.pending.all()).isEqualTo(before) // no record removed, none altered
        assertThat(a.contacts()).isEmpty()
        assertThat(a.sessionWrites).isEqualTo(0) // the trial computation wrote nothing
    }

    @Test
    fun b04_same_response_cannot_complete_twice() = runBlocking<Unit> {
        val a = TestEnv.create("alice")
        val b = TestEnv.create("bob")
        val first = a.coord.startInvite("我")
        a.coord.markInviteShared(first.pairingId)
        val second = a.coord.startInvite("我")
        val response = b.coord.acceptIncoming(second.inviteWire, "我").headerWire
        a.coord.completeIncoming(response)

        assertThat(a.coord.handleIncoming(response, "我"))
            .isEqualTo(IncomingOutcome.Rejected(IncomingRejection.NoMatchingInvite))

        // The other invite is untouched; nothing was written a second time.
        assertThat(a.pending.all().map { it.pairingId }).containsExactly(first.pairingId)
        assertThat(a.contacts()).hasSize(1)
        assertThat(a.sessionWrites).isEqualTo(1)
    }

    @Test
    fun b05_unused_invite_is_reused() = runBlocking<Unit> {
        val a = TestEnv.create("alice")

        val first = a.coord.startInvite("我")
        val again = a.coord.startInvite("我")
        assertThat(again).isEqualTo(first)
        assertThat(a.pending.all()).hasSize(1)

        // A note makes it "used" …
        a.coord.updateNote(first.pairingId, "发给老周的")
        val second = a.coord.startInvite("我")
        assertThat(second.pairingId).isNotEqualTo(first.pairingId)
        assertThat(a.pending.all()).hasSize(2)

        // … and so does having been shared.
        a.coord.markInviteShared(second.pairingId)
        val third = a.coord.startInvite("我")
        assertThat(a.pending.all().map { it.pairingId })
            .containsExactly(first.pairingId, second.pairingId, third.pairingId).inOrder()
    }

    @Test
    fun b06_invite_migrated_from_single_slot_still_completes() = runBlocking<Unit> {
        val a = TestEnv.create("alice")
        val b = TestEnv.create("bob")
        val minted = a.coord.startInvite("我") // a real invite; the old build kept only its nonce
        val (upgraded, stores) = upgradedFromSingleSlot(a.with(pending = InMemoryPendingInviteStore()), minted)
        try {
            val migrated = upgraded.pending.all().single()
            assertThat(migrated.inviteWire).isEmpty()
            assertThat(migrated.pairingNonceB64).isEqualTo(minted.pairingNonceB64)

            val accept = b.coord.acceptIncoming(minted.inviteWire, "我")
            val complete = upgraded.coord.completeIncoming(accept.headerWire)

            assertThat(complete.emoji).isEqualTo(accept.emoji)
            assertThat(upgraded.pending.all()).isEmpty()
            assertSessionsTalk(b, accept.contact.fingerprintHex, upgraded, complete.contact.fingerprintHex)
        } finally {
            stores.close()
        }
    }

    @Test
    fun b07_note_becomes_the_new_contacts_name() = runBlocking<Unit> {
        val a = TestEnv.create("alice")
        val b = TestEnv.create("bob")
        val c = TestEnv.create("carol")

        val noted = a.coord.startInvite("我")
        a.coord.updateNote(noted.pairingId, "发给老周的")
        val withNote = a.coord.completeIncoming(b.coord.acceptIncoming(noted.inviteWire, "我").headerWire)
        assertThat(withNote.contact.displayName).isEqualTo("发给老周的")

        val plain = a.coord.startInvite("我")
        val withoutNote = a.coord.completeIncoming(c.coord.acceptIncoming(plain.inviteWire, "").headerWire)
        assertThat(withoutNote.contact.displayName).startsWith("Contact ")

        assertThat(a.contacts().map { it.displayName })
            .containsExactly("发给老周的", withoutNote.contact.displayName)
    }

    @Test
    fun b08_completes_after_stores_and_coordinator_are_rebuilt() = runBlocking<Unit> {
        val a = TestEnv.create("alice")
        val b = TestEnv.create("bob")
        val invite = a.coord.startInvite("我")
        a.coord.updateNote(invite.pairingId, "老周")
        a.coord.markInviteShared(invite.pairingId)
        val accept = b.coord.acceptIncoming(invite.inviteWire, "我")

        val restarted = a.reopen()
        assertThat(restarted.coord.pendingInvite(invite.pairingId)!!.note).isEqualTo("老周")
        val complete = restarted.coord.completeIncoming(accept.headerWire)

        assertThat(complete.emoji).isEqualTo(accept.emoji)
        assertThat(complete.contact.displayName).isEqualTo("老周")
        assertThat(restarted.pending.all()).isEmpty()
        assertSessionsTalk(b, accept.contact.fingerprintHex, restarted, complete.contact.fingerprintHex)
    }

    @Test
    fun b09_key_material_is_invalidated_before_the_record_goes() = runBlocking<Unit> {
        val log = mutableListOf<String>()
        val spy = InviteKeyMaterialInvalidator { log += "invalidate:${it.pairingId}" }
        val base = TestEnv.create("alice")
        val a = base.with(pending = LoggingInviteStore(base.pending, log), keyMaterial = spy)
        val b = TestEnv.create("bob")

        // delete
        val deleted = a.coord.startInvite("我")
        a.coord.deleteInvite(deleted.pairingId)
        assertThat(log).containsExactly("invalidate:${deleted.pairingId}", "remove:${deleted.pairingId}").inOrder()
        assertThat(a.pending.all()).isEmpty()

        // completion
        log.clear()
        val completed = a.coord.startInvite("我")
        a.coord.completeIncoming(b.coord.acceptIncoming(completed.inviteWire, "我").headerWire)
        assertThat(log).containsExactly("invalidate:${completed.pairingId}", "remove:${completed.pairingId}").inOrder()

        // account wipe
        log.clear()
        val one = a.coord.startInvite("我")
        a.coord.markInviteShared(one.pairingId)
        val two = a.coord.startInvite("我")
        a.coord.clearAllPending()
        assertThat(log).containsExactly(
            "invalidate:${one.pairingId}", "remove:${one.pairingId}",
            "invalidate:${two.pairingId}", "remove:${two.pairingId}",
        ).inOrder()
        assertThat(a.pending.all()).isEmpty()
    }

    @Test
    fun b09_failed_invalidation_keeps_the_record() = runBlocking<Unit> {
        val base = TestEnv.create("alice")
        val a = base.with(keyMaterial = { throw IOException("secure storage unavailable") })
        val b = TestEnv.create("bob")
        val invite = a.coord.startInvite("我")
        val response = b.coord.acceptIncoming(invite.inviteWire, "我").headerWire

        assertThrows<IOException> { a.coord.deleteInvite(invite.pairingId) }
        assertThat(a.pending.all()).containsExactly(invite)

        // Completion: nothing may be persisted when the invite's key material could not be destroyed.
        assertThrows<IOException> { a.coord.completeIncoming(response) }
        assertThat(a.pending.all()).containsExactly(invite)
        assertThat(a.contacts()).isEmpty()
        assertThat(a.sessionWrites).isEqualTo(0)

        assertThrows<IOException> { a.coord.clearAllPending() }
        assertThat(a.pending.all()).containsExactly(invite)

        // Once invalidation works again the same response still completes — it was a retryable failure.
        val complete = base.reopen().coord.completeIncoming(response)
        assertThat(complete.contact.fingerprintHex).isNotEmpty()
        assertThat(base.pending.all()).isEmpty()
    }

    @Test
    fun b10_accept_stores_the_response_as_unsent() = runBlocking<Unit> {
        val a = TestEnv.create("alice")
        val b = TestEnv.create("bob")
        val invite = a.coord.startInvite("我")
        b.nowMillis = 5_000L

        val accept = b.coord.acceptIncoming(invite.inviteWire, "我")

        val stored = b.coord.pendingResponse(accept.contact.fingerprintHex)!!
        assertThat(stored.responseWire).isEqualTo(accept.headerWire)
        assertThat(stored.lastSharedAtMillis).isNull()
        assertThat(stored.createdAtMillis).isEqualTo(5_000L)
        assertThat(stored.inviteDigest).isEqualTo(PairingTransport.inviteDigest(invite.inviteWire))

        b.nowMillis = 6_000L
        b.coord.markResponseShared(accept.contact.fingerprintHex)
        assertThat(b.coord.pendingResponse(accept.contact.fingerprintHex)!!.lastSharedAtMillis).isEqualTo(6_000L)
    }

    @Test
    fun b11_pasting_the_same_invite_again_returns_the_stored_response() = runBlocking<Unit> {
        val a = TestEnv.create("alice")
        val b = TestEnv.create("bob")
        val invite = a.coord.startInvite("我")
        val first = b.coord.acceptIncoming(invite.inviteWire, "我")
        assertThat(b.sessionWrites).isEqualTo(1)

        val second = b.coord.acceptIncoming(invite.inviteWire, "我")
        val viaIntake = b.coord.handleIncoming(invite.inviteWire, "我")

        assertThat(second.headerWire).isEqualTo(first.headerWire)
        assertThat(second.emoji).isEqualTo(first.emoji)
        assertThat(second.contact).isEqualTo(first.contact)
        assertThat(viaIntake).isEqualTo(IncomingOutcome.Accepted(second))
        assertThat(b.sessionWrites).isEqualTo(1) // no second handshake: the session was not overwritten
        assertThat(b.contacts()).containsExactly(first.contact)

        val complete = a.coord.completeIncoming(second.headerWire)
        assertThat(complete.emoji).isEqualTo(first.emoji)
        assertSessionsTalk(b, first.contact.fingerprintHex, a, complete.contact.fingerprintHex)
    }

    @Test
    fun b12_after_deleting_the_contact_the_invite_handshakes_afresh() = runBlocking<Unit> {
        val a = TestEnv.create("alice")
        val b = TestEnv.create("bob")
        val invite = a.coord.startInvite("我")
        val first = b.coord.acceptIncoming(invite.inviteWire, "我")
        val fpA = first.contact.fingerprintHex

        // Delete the contact the way the contact page does — but WITHOUT forgetPeer, the harder
        // case: a response record left behind must not be handed out for a contact that is gone.
        b.repository.removeContact(fpA)
        b.sessionStore.remove(fpA)

        val second = b.coord.acceptIncoming(invite.inviteWire, "我")

        assertThat(second.headerWire).isNotEqualTo(first.headerWire)
        assertThat(b.sessionWrites).isEqualTo(2)
        assertThat(b.contacts().single().acceptedInviteDigest).isEqualTo(PairingTransport.inviteDigest(invite.inviteWire))
        assertThat(b.coord.pendingResponse(fpA)!!.responseWire).isEqualTo(second.headerWire)
        // A completes with the NEW response and the two sessions agree.
        val complete = a.coord.completeIncoming(second.headerWire)
        assertSessionsTalk(b, fpA, a, complete.contact.fingerprintHex)
    }

    @Test
    fun b13_intake_accepts_an_invite_and_completes_a_response() = runBlocking<Unit> {
        val a = TestEnv.create("alice")
        val b = TestEnv.create("bob")
        val invite = a.coord.startInvite("我")

        val accepted = b.coord.handleIncoming(invite.inviteWire, "我")
        assertThat(accepted).isInstanceOf(IncomingOutcome.Accepted::class.java)
        val accept = (accepted as IncomingOutcome.Accepted).outcome

        val completed = a.coord.handleIncoming(accept.headerWire, "我")
        assertThat(completed).isInstanceOf(IncomingOutcome.Completed::class.java)
        val complete = (completed as IncomingOutcome.Completed).outcome

        assertThat(complete.emoji).isEqualTo(accept.emoji)
        assertSessionsTalk(b, accept.contact.fingerprintHex, a, complete.contact.fingerprintHex)
    }

    @Test
    fun b14_intake_rejections_change_nothing() = runBlocking<Unit> {
        val a = TestEnv.create("alice")
        val b = TestEnv.create("bob")
        // A already has one contact and one pending invite, so "unchanged" is not vacuous.
        val earlier = a.coord.startInvite("我")
        a.coord.completeIncoming(b.coord.acceptIncoming(earlier.inviteWire, "我").headerWire)
        val own = a.coord.startInvite("我")
        val records = a.pending.all()
        val contacts = a.contacts()
        val writes = a.sessionWrites
        val sessionCiphertext = encodeWire(byteArrayOf(0xCC.toByte(), 0xC8.toByte(), 0x00, 0x00))

        val cases = listOf(
            own.inviteWire to (IncomingRejection.OwnInvite to R.string.pairing_error_own_code),
            sessionCiphertext to (IncomingRejection.SessionCiphertext to R.string.pairing_error_is_message),
            "hello" to (IncomingRejection.NotPairingWire to R.string.pairing_error_not_pairing),
            "" to (IncomingRejection.NotPairingWire to R.string.pairing_error_not_pairing),
        )
        for ((raw, expected) in cases) {
            val (reason, message) = expected
            val outcome = a.coord.handleIncoming(raw, "我")

            assertThat(outcome).isEqualTo(IncomingOutcome.Rejected(reason))
            assertThat(reason.messageRes).isEqualTo(message)
            assertThat(reason.contactFingerprintHex).isNull()
            assertThat(a.pending.all()).isEqualTo(records)
            assertThat(a.contacts()).isEqualTo(contacts)
            assertThat(a.sessionWrites).isEqualTo(writes)
        }
    }

    @Test
    fun b15_intake_finds_the_wire_inside_pasted_text() = runBlocking<Unit> {
        val a = TestEnv.create("alice")
        val b = TestEnv.create("bob")
        val invite = a.coord.startInvite("我")

        val accepted = b.coord.handleIncoming("他发来的：\n" + invite.inviteWire + "  \n", "我")
        assertThat(accepted).isInstanceOf(IncomingOutcome.Accepted::class.java)
        val accept = (accepted as IncomingOutcome.Accepted).outcome
        // Same result as the bare wire: the stored digest is the bare invite's.
        assertThat(accept.contact.acceptedInviteDigest).isEqualTo(PairingTransport.inviteDigest(invite.inviteWire))
        assertThat(b.coord.handleIncoming(invite.inviteWire, "我")).isEqualTo(accepted)

        val completed = a.coord.handleIncoming("  他回的：" + accept.headerWire + "\n\n", "我")
        assertThat(completed).isInstanceOf(IncomingOutcome.Completed::class.java)
        assertThat((completed as IncomingOutcome.Completed).outcome.emoji).isEqualTo(accept.emoji)
    }

    @Test
    fun b16_forgetPeer_drops_the_response_record() = runBlocking<Unit> {
        val a = TestEnv.create("alice")
        val b = TestEnv.create("bob")
        val c = TestEnv.create("carol")
        val fpA = b.coord.acceptIncoming(a.coord.startInvite("我").inviteWire, "我").contact.fingerprintHex
        val fpC = b.coord.acceptIncoming(c.coord.startInvite("我").inviteWire, "我").contact.fingerprintHex

        b.coord.forgetPeer(fpA)
        b.coord.forgetPeer("no-such-peer") // unknown peer: no-op

        assertThat(b.coord.pendingResponse(fpA)).isNull()
        assertThat(b.coord.pendingResponse(fpC)).isNotNull()
        assertThat(b.responses.records.first().map { it.fingerprintHex }).containsExactly(fpC)
    }

    @Test
    fun b17_clearAllPending_empties_both_tables() = runBlocking<Unit> {
        val a = TestEnv.create("alice")
        val b = TestEnv.create("bob")
        b.coord.acceptIncoming(a.coord.startInvite("我").inviteWire, "我")
        val shared = b.coord.startInvite("我")
        b.coord.markInviteShared(shared.pairingId)
        b.coord.startInvite("我")
        assertThat(b.pending.all()).hasSize(2)
        assertThat(b.responses.records.first()).hasSize(1)

        b.coord.clearAllPending()

        assertThat(b.pending.all()).isEmpty()
        assertThat(b.responses.records.first()).isEmpty()
    }

    @Test
    fun b18_only_the_accepting_side_records_the_invite_digest() = runBlocking<Unit> {
        val a = TestEnv.create("alice")
        val b = TestEnv.create("bob")
        val invite = a.coord.startInvite("我")

        val accept = b.coord.acceptIncoming(invite.inviteWire, "我")
        val complete = a.coord.completeIncoming(accept.headerWire)

        val digest = PairingTransport.inviteDigest(invite.inviteWire)
        assertThat(digest).matches("[0-9a-f]{64}")
        assertThat(b.contacts().single().acceptedInviteDigest).isEqualTo(digest)
        assertThat(accept.contact.acceptedInviteDigest).isEqualTo(digest)
        assertThat(a.contacts().single().acceptedInviteDigest).isNull()
        assertThat(complete.contact.acceptedInviteDigest).isNull()
    }

    @Test
    fun b19_invite_of_an_in_use_contact_is_refused_without_a_handshake() = runBlocking<Unit> {
        val a = TestEnv.create("alice")
        val b = TestEnv.create("bob")
        val invite = a.coord.startInvite("我")
        val accept = b.coord.acceptIncoming(invite.inviteWire, "我")
        val fpA = accept.contact.fingerprintHex
        val complete = a.coord.completeIncoming(accept.headerWire)
        b.coord.forgetPeer(fpA) // A's first message arrived
        val contactsBefore = b.contacts()

        val outcome = b.coord.handleIncoming(invite.inviteWire, "我")

        assertThat(outcome).isEqualTo(IncomingOutcome.Rejected(IncomingRejection.AlreadyPaired(fpA)))
        val reason = (outcome as IncomingOutcome.Rejected).reason
        assertThat(reason.messageRes).isEqualTo(R.string.pairing_error_already_paired)
        assertThat(reason.contactFingerprintHex).isEqualTo(fpA)
        assertThat(assertThrows<AlreadyPairedException> { b.coord.acceptIncoming(invite.inviteWire, "我") }.fingerprintHex)
            .isEqualTo(fpA)

        assertThat(b.sessionWrites).isEqualTo(1) // the in-use session was not overwritten
        assertThat(b.contacts()).isEqualTo(contactsBefore)
        assertThat(b.contacts().single().acceptedInviteDigest).isEqualTo(PairingTransport.inviteDigest(invite.inviteWire))
        assertThat(b.coord.pendingResponse(fpA)).isNull() // and no response record was conjured up
        assertSessionsTalk(b, fpA, a, complete.contact.fingerprintHex)
    }

    @Test
    fun b20_rename_and_verify_keep_the_digest() = runBlocking<Unit> {
        val a = TestEnv.create("alice")
        val b = TestEnv.create("bob")
        val invite = a.coord.startInvite("我")
        val fpA = b.coord.acceptIncoming(invite.inviteWire, "我").contact.fingerprintHex

        b.repository.renameContact(fpA, "老周")
        b.repository.markVerified(fpA)
        b.coord.forgetPeer(fpA)

        assertThat(b.coord.handleIncoming(invite.inviteWire, "我"))
            .isEqualTo(IncomingOutcome.Rejected(IncomingRejection.AlreadyPaired(fpA)))
        assertThat(b.sessionWrites).isEqualTo(1)
        val contact = b.contacts().single()
        assertThat(contact.displayName).isEqualTo("老周")
        assertThat(contact.verified).isTrue()
    }

    @Test
    fun b21_migrated_own_invite_recognised_by_nonce() = runBlocking<Unit> {
        val a = TestEnv.create("alice")
        val minted = a.coord.startInvite("我")
        val (upgraded, stores) = upgradedFromSingleSlot(a.with(pending = InMemoryPendingInviteStore()), minted)
        try {
            val before = upgraded.pending.all()
            assertThat(before.single().inviteWire).isEmpty() // no text to digest: only the nonce can identify it

            assertThat(upgraded.coord.handleIncoming(minted.inviteWire, "我"))
                .isEqualTo(IncomingOutcome.Rejected(IncomingRejection.OwnInvite))

            assertThat(upgraded.pending.all()).isEqualTo(before)
            assertThat(upgraded.contacts()).isEmpty()
            assertThat(upgraded.sessionWrites).isEqualTo(0)
        } finally {
            stores.close()
        }
    }

    // ── A peer who is already a contact is never handshaken with again (review round 1, ruling C) ──

    @Test
    fun newer_invite_from_an_existing_contact_is_refused_and_the_old_invite_stays_blocked() = runBlocking<Unit> {
        val a = TestEnv.create("alice")
        val b = TestEnv.create("bob")
        val invite1 = a.coord.startInvite("我")
        val accept = b.coord.acceptIncoming(invite1.inviteWire, "我")
        val fpA = accept.contact.fingerprintHex
        val complete = a.coord.completeIncoming(accept.headerWire)
        b.repository.renameContact(fpA, "老周")
        b.repository.markVerified(fpA)
        b.coord.forgetPeer(fpA) // A's first message arrived: an in-use contact
        val contactBefore = b.contacts().single()
        val writesBefore = b.sessionWrites
        // A — on a device that no longer knows B — mints a second invite and sends it.
        val invite2 = a.sameIdentityNoData().coord.startInvite("我")
        assertThat(invite2.inviteWire).isNotEqualTo(invite1.inviteWire)

        // Invite 2: a different digest, but the inviter is already a contact.
        assertThat(b.coord.handleIncoming(invite2.inviteWire, "我"))
            .isEqualTo(IncomingOutcome.Rejected(IncomingRejection.AlreadyPaired(fpA)))
        assertThat(assertThrows<AlreadyPairedException> { b.coord.acceptIncoming(invite2.inviteWire, "我") }.fingerprintHex)
            .isEqualTo(fpA)
        // Invite 1 pasted again by accident: still blocked — its digest was never replaced.
        assertThat(b.coord.handleIncoming(invite1.inviteWire, "我"))
            .isEqualTo(IncomingOutcome.Rejected(IncomingRejection.AlreadyPaired(fpA)))

        assertThat(b.sessionWrites).isEqualTo(writesBefore)
        assertThat(b.contacts()).containsExactly(contactBefore)
        assertThat(contactBefore.displayName).isEqualTo("老周")
        assertThat(contactBefore.verified).isTrue()
        assertThat(contactBefore.acceptedInviteDigest).isEqualTo(PairingTransport.inviteDigest(invite1.inviteWire))
        assertThat(b.coord.pendingResponse(fpA)).isNull()
        assertThat(b.pending.all()).isEmpty()
        assertSessionsTalk(b, fpA, a, complete.contact.fingerprintHex) // the original session is intact
    }

    @Test
    fun response_from_an_existing_contact_is_refused_and_the_invite_stays_pending() = runBlocking<Unit> {
        val log = mutableListOf<String>()
        val a = TestEnv.create("alice")
        val base = TestEnv.create("bob")
        val b = base.with(
            pending = LoggingInviteStore(base.pending, log),
            keyMaterial = { log += "invalidate:${it.pairingId}" },
        )
        val invite = a.coord.startInvite("我")
        val accept = b.coord.acceptIncoming(invite.inviteWire, "我")
        val fpA = accept.contact.fingerprintHex
        val complete = a.coord.completeIncoming(accept.headerWire)
        b.repository.renameContact(fpA, "老周")
        b.repository.markVerified(fpA)
        b.coord.forgetPeer(fpA)
        // B has an invite of its own out, and A (from a device that does not know B) answers it.
        val myInvite = b.coord.startInvite("我")
        val responseFromA = a.sameIdentityNoData().coord.acceptIncoming(myInvite.inviteWire, "我").headerWire
        val contactBefore = b.contacts().single()
        val writesBefore = b.sessionWrites

        // B accepted A's invite earlier, so this is a mutual invite: the outcome names B's matched invite.
        assertThat(b.coord.handleIncoming(responseFromA, "我"))
            .isEqualTo(IncomingOutcome.Rejected(IncomingRejection.AlreadyPaired(fpA, matchedPairingId = myInvite.pairingId)))
        assertThat(assertThrows<AlreadyPairedException> { b.coord.completeIncoming(responseFromA) }.fingerprintHex)
            .isEqualTo(fpA)

        // The invite is neither completed nor removed, and its key material was not invalidated.
        assertThat(b.pending.all()).containsExactly(myInvite)
        assertThat(log).isEmpty()
        assertThat(b.sessionWrites).isEqualTo(writesBefore)
        // The contact — digest included — is exactly as it was …
        assertThat(b.contacts()).containsExactly(contactBefore)
        assertThat(contactBefore.acceptedInviteDigest).isEqualTo(PairingTransport.inviteDigest(invite.inviteWire))
        assertThat(contactBefore.verified).isTrue()
        assertThat(contactBefore.displayName).isEqualTo("老周")
        // … so A's old invite is still blocked, and the original session still works.
        assertThat(b.coord.handleIncoming(invite.inviteWire, "我"))
            .isEqualTo(IncomingOutcome.Rejected(IncomingRejection.AlreadyPaired(fpA)))
        assertThat(b.sessionWrites).isEqualTo(writesBefore)
        assertSessionsTalk(b, fpA, a, complete.contact.fingerprintHex)
    }

    @Test
    fun response_from_a_contact_whose_invite_i_did_not_accept_names_no_invite() = runBlocking<Unit> {
        val a = TestEnv.create("alice")
        val b = TestEnv.create("bob")
        // B invited A and completed: B's contact for A carries no accepted-invite digest.
        val fpA = b.coord.completeIncoming(a.coord.acceptIncoming(b.coord.startInvite("我").inviteWire, "我").headerWire)
            .contact.fingerprintHex
        val myInvite = b.coord.startInvite("我")
        val responseFromA = a.sameIdentityNoData().coord.acceptIncoming(myInvite.inviteWire, "我").headerWire

        assertThat(b.coord.handleIncoming(responseFromA, "我"))
            .isEqualTo(IncomingOutcome.Rejected(IncomingRejection.AlreadyPaired(fpA)))
        assertThat(b.pending.all()).containsExactly(myInvite)
    }

    @Test
    fun after_deleting_the_contact_the_same_response_completes() = runBlocking<Unit> {
        val a = TestEnv.create("alice")
        val b = TestEnv.create("bob")
        val fpA = b.coord.acceptIncoming(a.coord.startInvite("我").inviteWire, "我").contact.fingerprintHex
        val myInvite = b.coord.startInvite("我")
        val aElsewhere = a.sameIdentityNoData()
        val acceptByA = aElsewhere.coord.acceptIncoming(myInvite.inviteWire, "我")
        assertThrows<AlreadyPairedException> { b.coord.completeIncoming(acceptByA.headerWire) }

        // Deleting the contact is the one way to re-pair. Done here WITHOUT forgetPeer, so the
        // response B stored for A is left behind …
        b.repository.removeContact(fpA)
        b.sessionStore.remove(fpA)
        assertThat(b.coord.pendingResponse(fpA)).isNotNull()

        val complete = b.coord.completeIncoming(acceptByA.headerWire)

        assertThat(complete.contact.fingerprintHex).isEqualTo(fpA)
        assertThat(b.pending.all()).isEmpty()
        assertThat(b.contacts().single().acceptedInviteDigest).isNull()
        // … and must not outlive its session: no 「回暗号还没发出去」 row for the new contact.
        assertThat(b.coord.pendingResponse(fpA)).isNull()
        assertThat(
            pendingItems(b.pending.all(), b.responses.records.first(), setOf(fpA), b.nowMillis),
        ).isEmpty()
        assertSessionsTalk(aElsewhere, acceptByA.contact.fingerprintHex, b, fpA)
    }

    @Test
    fun failed_contact_write_leaves_an_ignorable_orphan_and_a_repaste_handshakes_afresh() = runBlocking<Unit> {
        val a = TestEnv.create("alice")
        val contactsStore = FlakyContactsStore()
        val b = TestEnv.create("bob", contactsStore = contactsStore)
        val invite = a.coord.startInvite("我")

        contactsStore.failWrites = true
        assertThrows<IOException> { b.coord.acceptIncoming(invite.inviteWire, "我") }

        // What is left: a session and a response record, no contact. The record is invisible
        // without its contact, and nothing blocks the invite.
        assertThat(b.contacts()).isEmpty()
        val orphan = b.responses.records.first().single()
        assertThat(b.sessionWrites).isEqualTo(1)
        assertThat(pendingItems(b.pending.all(), listOf(orphan), emptySet(), b.nowMillis)).isEmpty()
        assertThat(unsentResponseFingerprints(listOf(orphan), emptySet())).isEmpty()

        contactsStore.failWrites = false
        val retry = b.coord.acceptIncoming(invite.inviteWire, "我")

        assertThat(retry.headerWire).isNotEqualTo(orphan.responseWire) // a fresh handshake, not the orphan
        assertThat(b.sessionWrites).isEqualTo(2)
        assertThat(b.contacts()).containsExactly(retry.contact)
        assertThat(b.responses.records.first().single().responseWire).isEqualTo(retry.headerWire)
        val complete = a.coord.completeIncoming(retry.headerWire)
        assertSessionsTalk(b, retry.contact.fingerprintHex, a, complete.contact.fingerprintHex)
    }

    @Test
    fun damaged_record_is_skipped_and_a_later_record_still_matches() = runBlocking<Unit> {
        val damaged = PendingPairingRecord(
            pairingId = "damaged",
            pairingNonceB64 = "!!not base64!!",
            createdAtMillis = 1L,
            inviteWire = "🔒not-a-decodable-invite",
            lastSharedAtMillis = 1L, // already shared, so startInvite below mints a new one instead of reusing it
        )
        val a = TestEnv.create("alice").with(pending = InMemoryPendingInviteStore(listOf(damaged)))
        val b = TestEnv.create("bob")
        val c = TestEnv.create("carol")
        val invite = a.coord.startInvite("我")
        assertThat(a.pending.all().map { it.pairingId }).containsExactly("damaged", invite.pairingId).inOrder()

        // isOwnInvite: the damaged record neither matches nor aborts the comparison.
        assertThat(a.coord.handleIncoming(invite.inviteWire, "我"))
            .isEqualTo(IncomingOutcome.Rejected(IncomingRejection.OwnInvite))
        assertThat(a.coord.handleIncoming(c.coord.startInvite("我").inviteWire, "我"))
            .isInstanceOf(IncomingOutcome.Accepted::class.java)

        // tryComplete: skipped, and the record after it completes.
        val accept = b.coord.acceptIncoming(invite.inviteWire, "我")
        val complete = a.coord.completeIncoming(accept.headerWire)

        assertThat(complete.emoji).isEqualTo(accept.emoji)
        assertThat(a.pending.all()).containsExactly(damaged)
    }

    // M4:自己发出的邀请,记录已不在(完成或已删)而文本还在剪贴板:按「自己的邀请」拒绝,零写入。
    @Test
    fun m4_own_invite_no_longer_pending_is_refused_as_own() = runBlocking<Unit> {
        val a = TestEnv.create("alice")
        val invite = a.coord.startInvite("我")
        a.coord.deleteInvite(invite.pairingId)
        assertThat(a.pending.all()).isEmpty()

        assertThat(a.coord.handleIncoming(invite.inviteWire, "我"))
            .isEqualTo(IncomingOutcome.Rejected(IncomingRejection.OwnInvite))

        assertThat(a.sessionWrites).isEqualTo(0)
        assertThat(a.contacts()).isEmpty()
        assertThat(a.responses.records.first()).isEmpty()
        assertThat(a.pending.all()).isEmpty()
    }

    // M5:一条会让引擎抛非 PairingFailed 异常的坏记录(随机数是合法 base64 但长度不对)排在真邀请前面:
    // 视为不匹配,真邀请照常完成,坏记录不被删。
    @Test
    fun m5_record_that_makes_the_engine_throw_is_skipped() = runBlocking<Unit> {
        val bad = PendingPairingRecord(
            pairingId = "bad-len", pairingNonceB64 = "AAAA", createdAtMillis = 1L, inviteWire = "", lastSharedAtMillis = 1L,
        )
        val a = TestEnv.create("alice").with(pending = InMemoryPendingInviteStore(listOf(bad)))
        val b = TestEnv.create("bob")
        val invite = a.coord.startInvite("我")
        val accept = b.coord.acceptIncoming(invite.inviteWire, "我")

        val complete = a.coord.completeIncoming(accept.headerWire)

        assertThat(complete.emoji).isEqualTo(accept.emoji)
        assertThat(a.pending.all()).containsExactly(bad)
    }

    // M1:提交段(完成一路)在写会话处挂起时调用方被取消,提交仍要完整走完。
    @Test
    fun m1_complete_commit_survives_caller_cancellation() = runBlocking<Unit> {
        val dao = RecordingSessionDao()
        val a = TestEnv.create("alice", sessionDao = dao)
        val b = TestEnv.create("bob")
        val invite = a.coord.startInvite("我")
        val accept = b.coord.acceptIncoming(invite.inviteWire, "我")
        dao.gate = CompletableDeferred()

        val job = launch { a.coord.completeIncoming(accept.headerWire) }
        dao.entered.await()
        job.cancel()
        dao.gate!!.complete(Unit)
        job.join()

        assertThat(a.pending.all()).isEmpty() // 邀请记录已删
        assertThat(a.contacts()).hasSize(1)
        assertThat(a.sessionWrites).isEqualTo(1)
    }

    // M1:接受一路同理——写会话、回应记录、联系人三件都在。
    @Test
    fun m1_accept_commit_survives_caller_cancellation() = runBlocking<Unit> {
        val dao = RecordingSessionDao()
        val a = TestEnv.create("alice")
        val b = TestEnv.create("bob", sessionDao = dao)
        val invite = a.coord.startInvite("我")
        dao.gate = CompletableDeferred()

        val job = launch { b.coord.acceptIncoming(invite.inviteWire, "我") }
        dao.entered.await()
        job.cancel()
        dao.gate!!.complete(Unit)
        job.join()

        assertThat(b.sessionWrites).isEqualTo(1)
        assertThat(b.responses.records.first()).hasSize(1)
        assertThat(b.contacts()).hasSize(1)
    }

    @Test
    fun completeIncoming_without_the_spk_throws_and_writes_nothing() = runBlocking<Unit> {
        val a = TestEnv.create("alice")
        val b = TestEnv.create("bob")
        val invite = a.coord.startInvite("我")
        val response = b.coord.acceptIncoming(invite.inviteWire, "我").headerWire
        assertThat(a.spkFile.delete()).isTrue()

        // Not provisioned afresh (a new SPK could never match the invite) — a genuine failure.
        val thrown = assertThrows<IllegalStateException> { a.coord.handleIncoming(response, "我") }

        assertThat(thrown).hasMessageThat().contains("no persisted SPK")
        assertThat(a.spkFile.exists()).isFalse()
        assertThat(a.pending.all()).containsExactly(invite)
        assertThat(a.contacts()).isEmpty()
        assertThat(a.sessionWrites).isEqualTo(0)
    }

    // ── Beyond table B ───────────────────────────────────────────────────────────────────────

    @Test
    fun undecodable_response_is_a_handshake_failure_not_a_missing_invite() = runBlocking<Unit> {
        val a = TestEnv.create("alice")
        val garbled = PairingTransport.headerToWire(byteArrayOf(0x01, 0x02, 0x03))
        assertThat(a.coord.classifyIncoming(garbled)).isEqualTo(PairingTransport.WireKind.PAIRING_HEADER)

        // With no invite on record …
        val noInvite = assertThrows<Exception> { a.coord.handleIncoming(garbled, "我") }
        assertThat(noInvite).isNotInstanceOf(NoMatchingInviteException::class.java)

        // … and with one: thrown either way, and the invite survives.
        val invite = a.coord.startInvite("我")
        val withInvite = assertThrows<Exception> { a.coord.handleIncoming(garbled, "我") }
        assertThat(withInvite).isNotInstanceOf(NoMatchingInviteException::class.java)
        assertThat(a.pending.all()).containsExactly(invite)
        assertThat(a.contacts()).isEmpty()
        assertThat(a.sessionWrites).isEqualTo(0)
    }

    @Test
    fun updateNote_rejects_an_over_long_note_and_stores_nothing() = runBlocking<Unit> {
        val a = TestEnv.create("alice")
        val invite = a.coord.startInvite("我")

        assertThrows<IllegalArgumentException> { a.coord.updateNote(invite.pairingId, "一二三四五六七八九") } // 27 bytes
        assertThat(a.coord.pendingInvite(invite.pairingId)!!.note).isNull()

        a.coord.updateNote(invite.pairingId, "  老周\n")
        assertThat(a.coord.pendingInvite(invite.pairingId)!!.note).isEqualTo("老周")
        a.coord.updateNote(invite.pairingId, "   ") // cleared → the invite counts as un-noted again
        assertThat(a.coord.pendingInvite(invite.pairingId)!!.note).isNull()
        assertThat(a.coord.pendingInvite("no-such-id")).isNull()
    }

    @Test
    fun `rejection messages are resource ids`() {
        assertThat(IncomingRejection.OwnInvite.messageRes).isEqualTo(R.string.pairing_error_own_code)
        assertThat(IncomingRejection.NoMatchingInvite.messageRes).isEqualTo(R.string.pairing_error_no_matching_invite)
        assertThat(IncomingRejection.SessionCiphertext.messageRes).isEqualTo(R.string.pairing_error_is_message)
        assertThat(IncomingRejection.NotPairingWire.messageRes).isEqualTo(R.string.pairing_error_not_pairing)
        assertThat(IncomingRejection.AlreadyPaired("fp").messageRes).isEqualTo(R.string.pairing_error_already_paired)
        assertThat(PairingCopy.OWN_INVITE).isEqualTo(R.string.pairing_error_own_code)
    }

    @Test
    fun `handleIncoming accepts an invite wrapped in the share header and a nickname line`() = runBlocking<Unit> {
        val a = TestEnv.create("alice")
        val b = TestEnv.create("bob")
        val invite = a.coord.startInvite("alice").inviteWire
        val raw = "Alice:\n" + PairingShareText.compose("🔒 CamoChat pairing code · copy it\n" + PairingLink.make("https://site.test/", invite)) + "\n"

        val outcome = b.coord.handleIncoming(raw, "bob")

        assertThat(outcome).isInstanceOf(IncomingOutcome.Accepted::class.java)
        assertThat(b.contacts()).hasSize(1)
    }

    @Test
    fun `default contact name comes from the injected provider`() = runBlocking<Unit> {
        val a = TestEnv.create("alice", defaultContactName = { "A-$it" })
        val b = TestEnv.create("bob", defaultContactName = { "C-$it" })
        val invite = a.coord.startInvite("")
        val accept = b.coord.acceptIncoming(invite.inviteWire, "")
        assertThat(accept.contact.displayName).isEqualTo("C-" + accept.contact.fingerprintHex.take(6))

        val complete = a.coord.completeIncoming(accept.headerWire)
        assertThat(complete.contact.displayName).isEqualTo("A-" + complete.contact.fingerprintHex.take(6))
    }

    @Test
    fun `accepting an invite names the contact after the inviter's nickname`() = runBlocking<Unit> {
        val a = TestEnv.create("alice")
        val b = TestEnv.create("bob")
        val invite = a.coord.startInvite("老王\u202E")
        val accept = b.coord.acceptIncoming(invite.inviteWire, "bob")
        assertThat(accept.contact.displayName).isEqualTo("老王")
    }

    @Test
    fun `accepting an invite with a blank nickname falls back to the default name`() = runBlocking<Unit> {
        val a = TestEnv.create("alice")
        val b = TestEnv.create("bob")
        val invite = a.coord.startInvite("")
        val accept = b.coord.acceptIncoming(invite.inviteWire, "bob")
        assertThat(accept.contact.displayName).isEqualTo("Contact " + accept.contact.fingerprintHex.take(6))
    }

    @Test
    fun `completing names the contact note then response nickname then default`() = runBlocking<Unit> {
        val a = TestEnv.create("alice")
        val b = TestEnv.create("bob")
        val c = TestEnv.create("carol")
        val d = TestEnv.create("dave")

        val noted = a.coord.startInvite("我")
        a.coord.updateNote(noted.pairingId, "发给老周的")
        val withNote = a.coord.completeIncoming(b.coord.acceptIncoming(noted.inviteWire, "小明").headerWire)
        assertThat(withNote.contact.displayName).isEqualTo("发给老周的")

        val plain = a.coord.startInvite("我")
        val withNick = a.coord.completeIncoming(c.coord.acceptIncoming(plain.inviteWire, "小红\u202E").headerWire)
        assertThat(withNick.contact.displayName).isEqualTo("小红")

        val third = a.coord.startInvite("我")
        val withNothing = a.coord.completeIncoming(d.coord.acceptIncoming(third.inviteWire, "").headerWire)
        assertThat(withNothing.contact.displayName).isEqualTo("Contact " + withNothing.contact.fingerprintHex.take(6))
    }

    @Test
    fun `discardUnsharedInvite removes a never shared invite`() = runBlocking<Unit> {
        val log = mutableListOf<String>()
        val base = TestEnv.create("alice")
        val a = base.with(keyMaterial = { log += "invalidate:${it.pairingId}" })
        val invite = a.coord.startInvite("alice")

        a.coord.discardUnsharedInvite(invite.pairingId)

        assertThat(a.pending.all()).isEmpty()
        assertThat(log).containsExactly("invalidate:${invite.pairingId}")
        a.coord.discardUnsharedInvite("no-such-id") // missing: nothing happens
    }

    @Test
    fun `discardUnsharedInvite keeps an invite that was shared`() = runBlocking<Unit> {
        val a = TestEnv.create("alice")
        val invite = a.coord.startInvite("alice")
        a.coord.markInviteShared(invite.pairingId)

        a.coord.discardUnsharedInvite(invite.pairingId)

        assertThat(a.pending.all().map { it.pairingId }).containsExactly(invite.pairingId)
    }

    @Test
    fun `an invite whose share sheet was opened is neither discarded nor reused`() = runBlocking<Unit> {
        val a = TestEnv.create("alice")
        val invite = a.coord.startInvite("alice")
        a.coord.markInvitePresented(invite.pairingId)

        a.coord.discardUnsharedInvite(invite.pairingId)
        val next = a.coord.startInvite("alice")

        assertThat(a.pending.all().map { it.pairingId }).containsExactly(invite.pairingId, next.pairingId).inOrder()
        assertThat(a.pending.all().first().lastSharedAtMillis).isNull() // still out of 「配对中」
    }

    /** Parks inside [invalidate] — which the coordinator calls inside its lock — until released. */
    private class GateInvalidator : InviteKeyMaterialInvalidator {
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        override suspend fun invalidate(record: PendingPairingRecord) {
            entered.complete(Unit)
            release.await()
        }
    }

    /**
     * Every entrypoint that writes pairing state shares the one lock: while a completion is
     * suspended mid-commit, a share mark / note edit / forget cannot interleave with it.
     */
    @Test
    fun record_mutations_wait_for_an_in_flight_completion() = runBlocking<Unit> {
        val a = TestEnv.create("alice")
        val b = TestEnv.create("bob")
        val invite = a.coord.startInvite("我")
        val accept = b.coord.acceptIncoming(invite.inviteWire, "我")
        val gate = GateInvalidator()
        val g = a.with(keyMaterial = gate) // one coordinator (one lock) for every call below

        val completing = async { g.coord.completeIncoming(accept.headerWire) }
        gate.entered.await()
        val others = listOf(
            async { g.coord.markInviteShared(invite.pairingId) },
            async { g.coord.updateNote(invite.pairingId, "老周") },
            async { g.coord.markResponseShared("fp-x") },
            async { g.coord.forgetPeer("fp-x") },
        )

        assertThat(withTimeoutOrNull(300) { others.forEach { it.await() } }).isNull()

        gate.release.complete(Unit)
        completing.await()
        others.forEach { it.await() }
        assertThat(a.pending.all()).isEmpty()
        assertThat(a.contacts()).hasSize(1)
    }
}
