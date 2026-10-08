package app.chencang.shared.identity

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM key wrapped in the Android Keystore. Hardware-backed on API 28+,
 * software-backed (StrongBox-free) on API 26-27. Caller never holds the raw key —
 * encryption/decryption goes through the Keystore-bound [Cipher].
 *
 * Wire format of [encrypt]/[decrypt]: 12-byte IV prefix + GCM ciphertext.
 */
class KeystoreHelper(private val alias: String = "chencang_identity_key") {

    private val keystore: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun ensureKey() {
        if (keystore.containsAlias(alias)) return
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(spec)
        kg.generateKey()
    }

    private fun key(): SecretKey =
        (keystore.getEntry(alias, null) as KeyStore.SecretKeyEntry).secretKey

    fun encrypt(plaintext: ByteArray): ByteArray {
        ensureKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv // 12 bytes by default
        require(iv.size == GCM_IV_LEN) { "Unexpected IV length ${iv.size}" }
        val ct = cipher.doFinal(plaintext)
        return iv + ct
    }

    fun decrypt(envelope: ByteArray): ByteArray {
        require(envelope.size > GCM_IV_LEN) { "Envelope too short" }
        val iv = envelope.copyOfRange(0, GCM_IV_LEN)
        val ct = envelope.copyOfRange(GCM_IV_LEN, envelope.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ct)
    }

    fun deleteKey() {
        if (keystore.containsAlias(alias)) keystore.deleteEntry(alias)
    }

    companion object {
        const val GCM_IV_LEN = 12
        const val GCM_TAG_BITS = 128
    }
}
