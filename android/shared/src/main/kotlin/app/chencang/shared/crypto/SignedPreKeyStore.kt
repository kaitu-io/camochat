package app.chencang.shared.crypto

import app.chencang.shared.identity.IdentityFileEnvelope
import java.io.File

/**
 * Keystore-backed persistence for this device's SERIALIZED secret signed pre-key.
 *
 * The stored bytes are [uniffi.chencang.SecretSignedPreKey.serialize] output, enveloped
 * via an [IdentityFileEnvelope] (AES-256-GCM key in the Android Keystore, mirroring
 * how identity bytes are persisted). NEVER transmit these bytes — only the public
 * form ([uniffi.chencang.SecretSignedPreKey.publicForm]) is published to the server.
 */
class SignedPreKeyStore(
    private val envelope: IdentityFileEnvelope,
    private val file: File,
) {
    fun save(serialized: ByteArray) = file.writeBytes(envelope.encrypt(serialized))

    fun load(): ByteArray? = file.takeIf { it.exists() }?.let { envelope.decrypt(it.readBytes()) }

    fun exists(): Boolean = file.exists()

    /** Account wipe: delete spk.enc. Safe when absent. */
    fun wipe() {
        if (file.exists()) file.delete()
    }
}
