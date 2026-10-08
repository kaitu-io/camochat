package app.chencang.shared.contacts

import app.chencang.shared.model.Contact
import app.chencang.shared.model.ContactListSnapshot
import app.chencang.shared.model.PairingState
import app.chencang.shared.model.PendingInvite
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.encodeToByteArray
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Test

@OptIn(ExperimentalSerializationApi::class)
class ContactListSerializerTest {

    @Test
    fun `round-trips a snapshot with contacts and pending invites`() = runTest {
        val snap = ContactListSnapshot(
            contacts = listOf(
                Contact(
                    fingerprintHex = "aa11",
                    username = "alice",
                    displayName = "Alice",
                    pairedAt = 1000L,
                    safetyEmoji = listOf("🐉", "🍵"),
                ),
            ),
            pendingInvites = listOf(PendingInvite(code = "ABC", createdAt = 1L, expiresAt = 2L)),
        )

        val out = ByteArrayOutputStream()
        ContactListSerializer.writeTo(snap, out)
        val restored = ContactListSerializer.readFrom(ByteArrayInputStream(out.toByteArray()))

        assertThat(restored).isEqualTo(snap)
    }

    @Test
    fun `empty input yields the default snapshot`() = runTest {
        val restored = ContactListSerializer.readFrom(ByteArrayInputStream(ByteArray(0)))
        assertThat(restored).isEqualTo(ContactListSnapshot())
    }

    @Test
    fun `garbage bytes fall back to empty snapshot`() = runTest {
        // Non-decodable bytes (corruption, truncation) must not crash the store.
        val garbage = byteArrayOf(0x42, 0x13, 0x37, -0x80, 0x01, 0x02)
        val restored = ContactListSerializer.readFrom(ByteArrayInputStream(garbage))
        assertThat(restored).isEqualTo(ContactListSnapshot())
    }

    @Test
    fun `genuine pre-migration username-keyed blob falls back to empty snapshot`() = runTest {
        // The real regression: a contact persisted BEFORE the fingerprint
        // re-key had no `fingerprint_hex` field. Build a faithful old-schema
        // CBOR blob from a mirror struct lacking that now-required field, then
        // feed it through the real serializer. Decoding into the current schema
        // raises MissingFieldException (a SerializationException subclass), which
        // the serializer catches — dropping the blob cleanly per the V1 decision.
        val legacyBytes = Cbor.encodeToByteArray(
            LegacyContactListSnapshot.serializer(),
            LegacyContactListSnapshot(
                contacts = listOf(
                    LegacyContact(
                        username = "alice",
                        displayName = "Alice",
                        pairedAt = 1L,
                        verified = false,
                    ),
                ),
            ),
        )

        val restored = ContactListSerializer.readFrom(ByteArrayInputStream(legacyBytes))

        assertThat(restored).isEqualTo(ContactListSnapshot())
    }

    @Test
    fun `snapshot written without accepted_invite_digest decodes with null digest`() = runTest {
        // A contact stored by a build that predates the field: every field of today's schema
        // except `accepted_invite_digest`. It must survive the upgrade intact, digest = null.
        val oldBytes = Cbor.encodeToByteArray(
            PreDigestContactListSnapshot.serializer(),
            PreDigestContactListSnapshot(
                contacts = listOf(
                    PreDigestContact(
                        fingerprintHex = "aa11",
                        username = "aa11",
                        displayName = "老周",
                        pairedAt = 1000L,
                        pairingState = PairingState.PAIRED,
                        safetyEmoji = listOf("🐉", "🍵"),
                        verified = true,
                        deviceId = "aa11",
                    ),
                ),
            ),
        )

        val restored = ContactListSerializer.readFrom(ByteArrayInputStream(oldBytes))

        assertThat(restored.contacts).containsExactly(
            Contact(
                fingerprintHex = "aa11",
                username = "aa11",
                displayName = "老周",
                pairedAt = 1000L,
                pairingState = PairingState.PAIRED,
                safetyEmoji = listOf("🐉", "🍵"),
                verified = true,
                deviceId = "aa11",
                acceptedInviteDigest = null,
            ),
        )
        assertThat(restored.contacts.single().acceptedInviteDigest).isNull()
    }

    @Test
    fun `digest round-trips`() = runTest {
        val snap = ContactListSnapshot(
            contacts = listOf(
                Contact(
                    fingerprintHex = "aa11",
                    username = "aa11",
                    displayName = "老周",
                    pairedAt = 1000L,
                    acceptedInviteDigest = "0f".repeat(32),
                ),
            ),
        )

        val out = ByteArrayOutputStream()
        ContactListSerializer.writeTo(snap, out)
        val restored = ContactListSerializer.readFrom(ByteArrayInputStream(out.toByteArray()))

        assertThat(restored.contacts.single().acceptedInviteDigest).isEqualTo("0f".repeat(32))
    }
}

/**
 * Mirror of the on-disk contact schema just before `accepted_invite_digest` was added; field
 * names/@SerialName must match the production [Contact] for the CBOR to be a genuine old blob.
 */
@Serializable
private data class PreDigestContact(
    @SerialName("fingerprint_hex") val fingerprintHex: String,
    val username: String,
    val displayName: String,
    @SerialName("paired_at") val pairedAt: Long,
    @SerialName("pairing_state") val pairingState: PairingState,
    @SerialName("safety_emoji") val safetyEmoji: List<String>,
    val verified: Boolean,
    @SerialName("session_state") val sessionState: ByteArray? = null,
    @SerialName("device_id") val deviceId: String?,
)

@Serializable
private data class PreDigestContactListSnapshot(
    val contacts: List<PreDigestContact> = emptyList(),
)

/**
 * Mirror of the pre-migration on-disk schema: a contact WITHOUT the now-required
 * `fingerprint_hex` field. Used solely to produce a faithful old persisted blob
 * for the discard-on-load test above; field names/@SerialName must match the
 * production [Contact] for the CBOR shape to be a genuine old blob.
 */
@Serializable
private data class LegacyContact(
    val username: String,
    val displayName: String,
    @SerialName("paired_at") val pairedAt: Long,
    val verified: Boolean = false,
)

@Serializable
private data class LegacyContactListSnapshot(
    val contacts: List<LegacyContact> = emptyList(),
)
