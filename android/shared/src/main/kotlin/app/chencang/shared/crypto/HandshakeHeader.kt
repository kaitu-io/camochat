package app.chencang.shared.crypto

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray

/**
 * CBOR handshake header relayed via the server at redeem (B → A). Carries B's
 * public identity + ephemeral pub keys + KEM ciphertexts + the 5-byte session_id
 * (the /status endpoint does NOT return session_id, so A reads it from here) +
 * B's chosen display name. SPK-only (V1): no one-time-prekey fields.
 *
 * NEVER includes the session root key — that stays local on both sides.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class HandshakeHeader(
    val bobIkX25519: ByteArray,
    val bobIkEd25519: ByteArray,
    val bobIkMlkem768: ByteArray,
    val bobIkMldsa65: ByteArray,
    val ekX25519Pub: ByteArray,
    val ekMlkemPub: ByteArray,
    val kemCtToSpk: ByteArray,
    val kemCtToIk: ByteArray,
    val sessionId: ByteArray,           // 5 bytes
    val bobDisplayName: String? = null,
) {
    fun encode(): ByteArray = Cbor.encodeToByteArray(this)

    companion object {
        fun decode(bytes: ByteArray): HandshakeHeader = Cbor.decodeFromByteArray(bytes)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HandshakeHeader) return false
        return bobIkX25519.contentEquals(other.bobIkX25519) &&
            bobIkEd25519.contentEquals(other.bobIkEd25519) &&
            bobIkMlkem768.contentEquals(other.bobIkMlkem768) &&
            bobIkMldsa65.contentEquals(other.bobIkMldsa65) &&
            ekX25519Pub.contentEquals(other.ekX25519Pub) &&
            ekMlkemPub.contentEquals(other.ekMlkemPub) &&
            kemCtToSpk.contentEquals(other.kemCtToSpk) &&
            kemCtToIk.contentEquals(other.kemCtToIk) &&
            sessionId.contentEquals(other.sessionId) &&
            bobDisplayName == other.bobDisplayName
    }

    override fun hashCode(): Int {
        var r = bobIkX25519.contentHashCode()
        r = 31 * r + bobIkEd25519.contentHashCode()
        r = 31 * r + bobIkMlkem768.contentHashCode()
        r = 31 * r + bobIkMldsa65.contentHashCode()
        r = 31 * r + ekX25519Pub.contentHashCode()
        r = 31 * r + ekMlkemPub.contentHashCode()
        r = 31 * r + kemCtToSpk.contentHashCode()
        r = 31 * r + kemCtToIk.contentHashCode()
        r = 31 * r + sessionId.contentHashCode()
        r = 31 * r + (bobDisplayName?.hashCode() ?: 0)
        return r
    }
}
