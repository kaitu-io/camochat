package app.chencang.shared.contacts

import app.chencang.shared.model.Contact
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.encodeToByteArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

@OptIn(ExperimentalSerializationApi::class)
class ContactsDataStoreLegacyTest {
    /** Pre-teardown snapshot shape: carries the retired `sticky_recipient` key. */
    @Serializable
    private data class LegacySnapshot(
        val contacts: List<Contact> = emptyList(),
        @SerialName("sticky_recipient") val stickyRecipient: String? = null,
        @SerialName("pending_invites") val pendingInvites: List<String> = emptyList(),
    )

    @Test
    fun `snapshot written by keyboard-era build still decodes`() = runBlocking {
        val legacyBytes = Cbor.encodeToByteArray(
            LegacySnapshot.serializer(),
            LegacySnapshot(
                contacts = listOf(
                    Contact(
                        fingerprintHex = "aa11",
                        username = "alice",
                        displayName = "alice",
                        pairedAt = 1000L,
                    ),
                ),
                stickyRecipient = "aa11",
            ),
        )
        // Prove the fixture actually carries the retired key — otherwise this
        // test would pass trivially regardless of ignoreUnknownKeys.
        assertTrue(String(legacyBytes, Charsets.ISO_8859_1).contains("sticky_recipient"))
        val decoded = ContactListSerializer.readFrom(ByteArrayInputStream(legacyBytes))
        assertEquals(listOf("alice"), decoded.contacts.map { it.username })
    }
}
