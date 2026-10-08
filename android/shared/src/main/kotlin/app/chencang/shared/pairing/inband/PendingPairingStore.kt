package app.chencang.shared.pairing.inband

import androidx.datastore.core.Serializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.encodeToByteArray
import java.io.InputStream
import java.io.OutputStream

/**
 * On-disk record for a half-finished in-band pairing. The inviter (A) sends a bundle, then
 * must finish when the invitee replies; the app process may be killed in between, so A
 * persists this small marker and reloads it to resume. Records live in [PendingInviteStore].
 *
 * Holds NO secret: only the *public* pairing nonce (already inside the transmitted bundle),
 * a local id, a timestamp, and — since the list store ([PendingInviteStore]) — the invite text
 * itself (public: it is what the user pastes into WeChat), a local-only note and share time.
 *
 * The trailing fields default so that a three-field blob written by an older build still
 * decodes (spec 2026-10-01-three-tab-shell §7.5).
 */
@Serializable
data class PendingPairingRecord(
    val pairingId: String,
    val pairingNonceB64: String,   // PUBLIC pairing nonce, base64 (NO secrets)
    val createdAtMillis: Long,
    /** 邀请暗号全文，供再次分享 / 重新出二维码；从旧单槽迁来的记录为空串。 */
    val inviteWire: String = "",
    /** 仅本机可见的备注（≤ 24 UTF-8 字节，由编排层校验）。 */
    val note: String? = null,
    /** 最近一次点分享或复制的时间；null = 「还没发出去」。 */
    val lastSharedAtMillis: Long? = null,
    /** 预留：日后每份邀请专属预密钥在安全存储里的句柄（不是密钥本身）。本轮恒为 null。 */
    val keyHandle: String? = null,
    /** 第一次弹出分享面板的时间；非 null 的邀请可能已经发出去了（有的面板不回报），不再丢弃、也不再复用。 */
    val sheetPresentedAtMillis: Long? = null,
)

/**
 * DataStore [Serializer] for the single slot older builds kept (file `chencang_pending_pairing.cbor`);
 * retained only so [DataStorePendingInviteStore] can migrate that slot into the list. Mirrors ContactListSerializer's
 * CBOR idiom. Empty input is the cleared/initial sentinel → null; a null value writes empty bytes.
 * A schema-incompatible blob is dropped cleanly (decode failure → null), not migrated.
 */
@OptIn(ExperimentalSerializationApi::class)
object PendingPairingRecordSerializer : Serializer<PendingPairingRecord?> {
    override val defaultValue: PendingPairingRecord? = null

    override suspend fun readFrom(input: InputStream): PendingPairingRecord? {
        val bytes = input.readBytes()
        if (bytes.isEmpty()) return null
        return try {
            Cbor.decodeFromByteArray(PendingPairingRecord.serializer(), bytes)
        } catch (e: SerializationException) {
            null
        }
    }

    override suspend fun writeTo(t: PendingPairingRecord?, output: OutputStream) {
        if (t == null) return
        output.write(Cbor.encodeToByteArray(PendingPairingRecord.serializer(), t))
    }
}
