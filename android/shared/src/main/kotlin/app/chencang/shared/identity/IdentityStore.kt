package app.chencang.shared.identity

import androidx.annotation.VisibleForTesting
import uniffi.chencang.SecretIdentity
import uniffi.chencang.generateSecretIdentity
import java.io.File

/**
 * Pair of encrypt + decrypt functions used to envelope identity bytes on disk.
 *
 * - `IdentityFileEnvelope.passthrough` — JVM-only test helper. Bytes round-trip unchanged
 *   so we can validate file format on a plain JUnit JVM (no Android Keystore available).
 * - `IdentityFileEnvelope.keystoreBacked(KeystoreHelper)` — production wiring; AES-256-GCM
 *   key in the Android Keystore.
 */
class IdentityFileEnvelope(
    val encrypt: (ByteArray) -> ByteArray,
    val decrypt: (ByteArray) -> ByteArray,
) {
    companion object {
        @VisibleForTesting
        val passthrough: IdentityFileEnvelope = IdentityFileEnvelope(
            encrypt = { it.copyOf() },
            decrypt = { it.copyOf() },
        )

        fun keystoreBacked(helper: KeystoreHelper): IdentityFileEnvelope = IdentityFileEnvelope(
            encrypt = helper::encrypt,
            decrypt = helper::decrypt,
        )
    }
}

/**
 * On-disk envelope store for serialized identity bytes. Storage format:
 *   `<filesDir>/identity.enc` = envelope.encrypt(serializeForLocalStorage())
 *
 * This class is INTENTIONALLY uniffi-free so it can be unit-tested on a plain JVM
 * (no Android, no native libraries). The uniffi calls live in [IdentityStore].
 */
class IdentityEnvelopeFile(
    private val file: File,
    private val envelope: IdentityFileEnvelope,
) {
    fun exists(): Boolean = file.exists() && file.length() > 0

    fun read(): ByteArray {
        require(exists()) { "No identity persisted at ${file.absolutePath}" }
        return envelope.decrypt(file.readBytes())
    }

    fun write(plaintext: ByteArray) {
        val ciphertext = envelope.encrypt(plaintext)
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.outputStream().use { it.write(ciphertext) }
        require(tmp.renameTo(file)) { "Failed to rename ${tmp.name} -> ${file.name}" }
    }

    /** Account wipe: delete the on-disk envelope. Safe when absent. */
    fun wipe() {
        if (file.exists()) file.delete()
    }

    @VisibleForTesting
    fun clearForTest() = wipe()
}

/**
 * High-level identity API: combines uniffi key-generation/serialization with the on-disk
 * [IdentityEnvelopeFile]. Production callers wire `IdentityStore(filesDir/"identity.enc",
 * IdentityFileEnvelope.keystoreBacked(KeystoreHelper()))`.
 */
class IdentityStore(
    private val envelopeFile: IdentityEnvelopeFile,
) {
    constructor(file: File, envelope: IdentityFileEnvelope) :
        this(IdentityEnvelopeFile(file, envelope))

    fun hasIdentity(): Boolean = envelopeFile.exists()

    /**
     * Generates a new [SecretIdentity] (uniffi → Rust), serializes, persists, and returns
     * a usable handle.
     */
    fun generateAndSave(): SecretIdentity {
        val plaintext = generateSecretIdentity().use { it.serializeForLocalStorage() }
        envelopeFile.write(plaintext)
        return SecretIdentity.Companion.fromLocalStorage(plaintext)
    }

    fun load(): SecretIdentity =
        SecretIdentity.Companion.fromLocalStorage(envelopeFile.read())

    /** Account wipe: delete identity.enc. Keystore key removal is the wiper's job. */
    fun wipe() = envelopeFile.wipe()

    @VisibleForTesting
    fun clearForTest() = envelopeFile.clearForTest()
}
