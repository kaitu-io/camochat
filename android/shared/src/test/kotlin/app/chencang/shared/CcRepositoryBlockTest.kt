package app.chencang.shared

import app.chencang.shared.model.Contact
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** [CcRepository.setBlocked] and the lists / lookups derived from it. */
class CcRepositoryBlockTest {

    private fun contact(fp: String, username: String = fp) = Contact(
        fingerprintHex = fp,
        username = username,
        displayName = "name-$fp",
        pairedAt = 1000L,
    )

    @Test
    fun `blocked contact leaves visibleContacts and joins blockedContacts`() = runTest {
        val repo = CcRepository.forTest()
        repo.upsertContact(contact("aa11"))
        repo.upsertContact(contact("bb22"))

        repo.setBlocked("aa11", true)

        assertThat(repo.visibleContacts.first().map { it.fingerprintHex }).containsExactly("bb22")
        assertThat(repo.blockedContacts.first().map { it.fingerprintHex }).containsExactly("aa11")
        // The full list still has both: blocking never deletes.
        assertThat(repo.contacts.first()).hasSize(2)
    }

    @Test
    fun `unblock restores the contact to visibleContacts`() = runTest {
        val repo = CcRepository.forTest()
        repo.upsertContact(contact("aa11"))
        repo.setBlocked("aa11", true)

        repo.setBlocked("aa11", false)

        assertThat(repo.visibleContacts.first().map { it.fingerprintHex }).containsExactly("aa11")
        assertThat(repo.blockedContacts.first()).isEmpty()
    }

    @Test
    fun `upsertContact keeps an existing block`() = runTest {
        val repo = CcRepository.forTest()
        repo.upsertContact(contact("aa11"))
        repo.setBlocked("aa11", true)

        repo.upsertContact(contact("aa11").copy(displayName = "re-paired"))

        val stored = repo.contacts.first().single()
        assertThat(stored.blocked).isTrue()
        assertThat(stored.displayName).isEqualTo("re-paired")
    }

    @Test
    fun `isBlocked matches by fingerprint or username`() = runTest {
        val repo = CcRepository.forTest()
        repo.upsertContact(contact("aa11", username = "alice"))
        repo.upsertContact(contact("bb22"))
        repo.setBlocked("aa11", true)

        assertThat(repo.isBlocked("aa11")).isTrue()
        assertThat(repo.isBlocked("alice")).isTrue()
        assertThat(repo.isBlocked("bb22")).isFalse()
        assertThat(repo.isBlocked("unknown")).isFalse()
    }

    @Test
    fun `setBlocked on unknown fingerprint is a no-op`() = runTest {
        val repo = CcRepository.forTest()
        repo.upsertContact(contact("aa11"))

        repo.setBlocked("zz99", true)

        assertThat(repo.blockedContacts.first()).isEmpty()
    }
}
