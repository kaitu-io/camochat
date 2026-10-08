package app.chencang.shared.crypto

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import uniffi.chencang.Session

/**
 * Process-wide store of Double Ratchet sessions, keyed by an opaque local label
 * (usually a contact's username, or a UAT-fixture pseudo-name like
 * "uat-alice-recv"). Mirrors the iOS `SessionStore` actor; both modules in this
 * APK — Companion and the IME — share the singleton via [CcServiceLocator].
 *
 * V2 persistence: when a [SessionStateDao] is supplied, every session mutation
 * (put / encrypt / decrypt) is serialized via `Session.serializeState()` and
 * written to `cc-sessions.db`, and the store rehydrates from that DB on
 * construction. This lets paired sessions survive process death (MIUI cleanup /
 * reboot / `am force-stop` / reinstall) instead of forcing a Re-Seed. When the
 * DAO is null the store stays in-memory only (used by pure-Kotlin unit tests
 * that don't care about persistence).
 *
 * iOS parity (file-backed serialize/fromSerializedState on the `SessionStore`
 * actor) is a follow-up; this lands the Android side only.
 *
 * Sessions are MUTABLE — every encrypt or decrypt ratchets internal state.
 * Concurrent send + receive on the same peer must hold the per-store mutex, so
 * callers should go through [encryptToBytes] / [decryptFromBytesAny] rather
 * than reaching into [keys] and calling `Session.encryptToBytes` themselves.
 *
 * The V1 wire codec is raw bytes (DR + AEAD ciphertext). The user-visible
 * "🔒…" envelope is applied / stripped by `encodeWire` / `decodeWire` in
 * `uniffi.chencang`, outside this layer.
 */
class RatchetSessionStore(
    private val dao: SessionStateDao? = null,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val initScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    private val mutex = Mutex()
    private val sessions = mutableMapOf<String, Session>()

    /**
     * Maps an internal session label → the peer username that downstream
     * consumers (device_id lookup, contact display) expect. In production
     * the two are identical so [put] defaults peerAlias to name. The UAT
     * loopback fixture stores Alice's recv session under "uat-alice-recv"
     * (to avoid colliding with Bob's send session "uat-alice") but the
     * actual contact is "uat-alice" — peerAlias bridges that gap so the
     * receive pipeline doesn't see a synthetic label.
     */
    private val peerAliases = mutableMapOf<String, String>()

    /**
     * Gate that all public suspend methods await before touching [sessions].
     * Completed immediately when there's no DAO, otherwise once [hydrate] has
     * finished (success or failure) — see the `finally` in [hydrate].
     */
    private val ready = CompletableDeferred<Unit>()

    init {
        if (dao == null) {
            ready.complete(Unit)
        } else {
            initScope.launch { hydrate() }
        }
    }

    /**
     * Rebuild the in-memory session map from persisted state on construction.
     * Each row is rehydrated independently: a single corrupted blob is a
     * survivable condition (we log + skip it), not a reason to kill the
     * process or abandon the other sessions. The [ready] gate is always
     * completed in `finally` so a hydration crash can't deadlock callers.
     */
    private suspend fun hydrate() {
        try {
            val rows = dao?.all().orEmpty()
            for (row in rows) {
                try {
                    val session = Session.fromSerializedState(row.stateBlob)
                    mutex.withLock {
                        sessions[row.peerLabel]?.close()
                        sessions[row.peerLabel] = session
                        peerAliases[row.peerLabel] = row.peerAlias
                    }
                } catch (t: Throwable) {
                    android.util.Log.w(
                        "RatchetSessionStore",
                        "skipping corrupted session row '${row.peerLabel}': " +
                            "${t::class.java.simpleName}(${t.message ?: "(no msg)"})",
                    )
                }
            }
        } finally {
            ready.complete(Unit)
        }
    }

    suspend fun put(
        name: String,
        session: Session,
        peerAlias: String = name,
    ) {
        ready.await()
        mutex.withLock {
            sessions[name]?.close()
            sessions[name] = session
            peerAliases[name] = peerAlias
            persistLocked(name)
        }
    }

    suspend fun keys(): Set<String> {
        ready.await()
        return mutex.withLock { sessions.keys.toSet() }
    }

    suspend fun encryptToBytes(name: String, plaintext: ByteArray): ByteArray {
        ready.await()
        return mutex.withLock {
            val s = sessions[name] ?: throw NoSessionForPeer(name)
            val ct = s.encryptToBytes(plaintext)
            persistLocked(name)
            ct
        }
    }

    /**
     * Walk every cached session and return the first one whose
     * `decryptFromBytes` succeeds. Used by the IME's receive path so loopback
     * (and, in the future, group fingerprints) doesn't have to know which key
     * encrypted the wire.
     *
     * Each failed attempt is swallowed silently because Double Ratchet decrypt
     * raises on any mismatch — that's the expected signal that we tried the
     * wrong session. The caller only sees a failure when no session matched.
     *
     * Persistence: DR may advance chain state even on a FAILED AEAD attempt
     * (open issue #142), so we persist EVERY session we invoked
     * `decryptFromBytes` on — tracked in [touched] — not just the one that
     * decrypted. Persisting happens while [mutex] is held so disk matches
     * memory.
     */
    suspend fun decryptFromBytesAny(ciphertext: ByteArray): Decrypted {
        ready.await()
        return mutex.withLock {
            if (sessions.isEmpty()) {
                throw NoSessionMatched(emptyList(), "store is empty (no sessions seeded)")
            }
            val attempts = mutableListOf<Attempt>()
            val touched = mutableListOf<String>()
            var matched: Decrypted? = null
            for ((name, session) in sessions) {
                touched += name
                try {
                    val plain = session.decryptFromBytes(ciphertext)
                    val alias = peerAliases[name] ?: name
                    matched = Decrypted(senderKey = alias, plaintext = plain)
                    break
                } catch (t: Throwable) {
                    attempts += Attempt(name, t::class.java.simpleName, t.message ?: "(no msg)")
                }
            }
            // Persist every session we touched, even on AEAD failure (#142),
            // before returning or throwing.
            for (n in touched) {
                persistLocked(n)
            }
            matched ?: throw NoSessionMatched(
                attempts.toList(),
                "all ${attempts.size} sessions rejected ciphertext",
            )
        }
    }

    /**
     * Serialize the current state of session [name] and upsert it into the DB.
     * No-op when there's no DAO. MUST be called while [mutex] is held so the
     * persisted blob matches the in-memory ratchet state exactly.
     */
    private suspend fun persistLocked(name: String) {
        val dao = dao ?: return
        val s = sessions[name] ?: return
        val blob = s.serializeState()
        dao.upsert(
            SessionStateEntity(
                peerLabel = name,
                peerAlias = peerAliases[name] ?: name,
                stateBlob = blob,
                updatedAt = clock(),
            ),
        )
    }

    /** Test-only: suspend until hydration has completed. */
    suspend fun awaitReady() {
        ready.await()
    }

    /** Account wipe: close and drop every cached session, alias, and DB row. */
    suspend fun clear() {
        ready.await()
        mutex.withLock {
            for (s in sessions.values) s.close()
            sessions.clear()
            peerAliases.clear()
            dao?.clear()
        }
    }

    /**
     * 联系人删除的一部分:关闭并丢弃单个 peer 的会话(内存 + alias + 持久化行),
     * 其余会话不受影响。[name] 未知时静默 no-op(session close 是幂等收尾,不是
     * 契约检查点)。
     */
    suspend fun remove(name: String) {
        ready.await()
        mutex.withLock {
            sessions.remove(name)?.close()
            peerAliases.remove(name)
            dao?.delete(name)
        }
    }

    data class Decrypted(val senderKey: String, val plaintext: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Decrypted) return false
            return senderKey == other.senderKey && plaintext.contentEquals(other.plaintext)
        }
        override fun hashCode(): Int = senderKey.hashCode() * 31 + plaintext.contentHashCode()
    }

    /** Per-session decrypt attempt, for diagnostics when no session matches. */
    data class Attempt(val sessionKey: String, val errorType: String, val errorMessage: String)

    class NoSessionForPeer(val name: String) :
        IllegalStateException("no session cached for peer '$name'")

    /**
     * Thrown when no cached session could decrypt the given ciphertext. Carries
     * the per-session failure reasons so the IME can surface what each session
     * actually said (AeadFailed, Decoding, etc.) instead of a flat "no match".
     */
    class NoSessionMatched(
        val attempts: List<Attempt>,
        summary: String,
    ) : IllegalStateException(summary) {
        fun renderAttempts(): String =
            if (attempts.isEmpty()) "(no sessions)"
            else attempts.joinToString("; ") { "${it.sessionKey}: ${it.errorType}(${it.errorMessage})" }
    }
}
