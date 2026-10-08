package app.chencang.shared

import app.chencang.shared.model.Contact
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** [CcRepository.removeContact] — 联系人删除(不一致核对失败 / 手动删除)。 */
class CcRepositoryRemoveTest {

    private fun contact(fingerprintHex: String, displayName: String = fingerprintHex) = Contact(
        fingerprintHex = fingerprintHex,
        username = fingerprintHex,
        displayName = displayName,
        pairedAt = 1000L,
    )

    @Test
    fun `removeContact drops the matching fingerprint, others survive`() = runTest {
        val repo = CcRepository.forTest()
        repo.upsertContact(contact("aa11", "Alice"))
        repo.upsertContact(contact("bb22", "Bob"))

        repo.removeContact("aa11")

        val list = repo.contacts.first()
        assertThat(list).hasSize(1)
        assertThat(list.single().fingerprintHex).isEqualTo("bb22")
    }

    @Test
    fun `removeContact on unknown fingerprint is a no-op`() = runTest {
        val repo = CcRepository.forTest()
        repo.upsertContact(contact("aa11", "Alice"))

        repo.removeContact("unknown")

        val list = repo.contacts.first()
        assertThat(list).hasSize(1)
        assertThat(list.single().fingerprintHex).isEqualTo("aa11")
    }
}
