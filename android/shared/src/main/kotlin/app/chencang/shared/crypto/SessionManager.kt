package app.chencang.shared.crypto

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Wraps per-peer Double Ratchet sessions for the V1 wire codec. Each peer
 * username gets its own mutex so concurrent send + receive don't race the
 * ratchet state.
 *
 * V1 ciphertext is raw bytes (no z-base32 wrap). The user-visible "🔒…"
 * envelope is handled outside this layer via `encodeWire` / `decodeWire`
 * from `uniffi.chencang`. See docs/protocol/README.md §6–§7.
 */
interface SessionCrypto {
    /** Encrypts [plaintext] under the session for [peerUsername] and returns
     *  the raw L3 ciphertext bytes (DR + AEAD; not yet Base32768-wrapped). */
    suspend fun encryptToBytes(peerUsername: String, plaintext: ByteArray): ByteArray

    /** Decrypts raw L3 ciphertext bytes for [peerUsername]. */
    suspend fun decryptFromBytes(peerUsername: String, ciphertext: ByteArray): ByteArray

    /**
     * Try every cached session and return the first that decrypts. Used by
     * the IME's receive path so loopback (and future multi-peer flows) don't
     * need to know which key encrypted the wire. Returns null when this
     * backend doesn't support multi-session lookup.
     */
    suspend fun decryptFromBytesAny(ciphertext: ByteArray): DecryptedAnyBytes? = null

    data class DecryptedAnyBytes(val senderKey: String, val plaintext: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is DecryptedAnyBytes) return false
            return senderKey == other.senderKey && plaintext.contentEquals(other.plaintext)
        }
        override fun hashCode(): Int = senderKey.hashCode() * 31 + plaintext.contentHashCode()
    }
}

class SessionManager(
    private val crypto: SessionCrypto,
) {
    private val mutexes = mutableMapOf<String, Mutex>()
    private val mutexesLock = Mutex()

    private suspend fun mutexFor(peer: String): Mutex = mutexesLock.withLock {
        mutexes.getOrPut(peer) { Mutex() }
    }

    suspend fun encryptToBytes(peerUsername: String, plaintext: ByteArray): ByteArray =
        mutexFor(peerUsername).withLock { crypto.encryptToBytes(peerUsername, plaintext) }

    suspend fun decryptFromBytes(peerUsername: String, ciphertext: ByteArray): ByteArray =
        mutexFor(peerUsername).withLock { crypto.decryptFromBytes(peerUsername, ciphertext) }

    suspend fun decryptFromBytesAny(ciphertext: ByteArray): SessionCrypto.DecryptedAnyBytes? =
        crypto.decryptFromBytesAny(ciphertext)
}

/**
 * Real SessionCrypto backed by a [RatchetSessionStore] — every call delegates
 * to a stored chencang-core `Session`, so `encryptToBytes` / `decryptFromBytes`
 * produce the same wire bytes the Rust core (and iOS via uniffi) produce. The
 * IME and Companion share the same singleton through [CcServiceLocator].
 *
 * Pairing or UAT-seeding populates the store before the first encrypt.
 */
class RatchetSessionCrypto(
    private val store: RatchetSessionStore,
) : SessionCrypto {
    override suspend fun encryptToBytes(peerUsername: String, plaintext: ByteArray): ByteArray =
        store.encryptToBytes(peerUsername, plaintext)

    override suspend fun decryptFromBytes(
        peerUsername: String,
        ciphertext: ByteArray,
    ): ByteArray {
        val any = store.decryptFromBytesAny(ciphertext)
        return any.plaintext
    }

    override suspend fun decryptFromBytesAny(
        ciphertext: ByteArray,
    ): SessionCrypto.DecryptedAnyBytes? {
        // Note: we deliberately do NOT runCatching here. The store throws a
        // rich `NoSessionMatched(attempts)` exception whose per-session error
        // reasons (AeadFailed / Decoding / ...) are critical for diagnosing
        // wire-layer bugs. ReceivePipeline catches and surfaces them.
        val d = store.decryptFromBytesAny(ciphertext)
        return SessionCrypto.DecryptedAnyBytes(senderKey = d.senderKey, plaintext = d.plaintext)
    }
}
