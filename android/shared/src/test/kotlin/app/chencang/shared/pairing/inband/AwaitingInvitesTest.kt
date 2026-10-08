package app.chencang.shared.pairing.inband

import org.junit.Assert.assertEquals
import org.junit.Test

class AwaitingInvitesTest {
    private val now = 1_000_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun rec(id: String, created: Long = now - day, shared: Long? = null, sheet: Long? = null) =
        PendingPairingRecord(
            pairingId = id, pairingNonceB64 = "n", createdAtMillis = created,
            lastSharedAtMillis = shared, sheetPresentedAtMillis = sheet,
        )

    @Test fun `neither shared nor sheet opened does not count`() {
        assertEquals(emptyList<String>(), awaitingInvites(listOf(rec("a")), now).map { it.pairingId })
    }

    @Test fun `shared counts`() {
        assertEquals(listOf("a"), awaitingInvites(listOf(rec("a", shared = now)), now).map { it.pairingId })
    }

    @Test fun `only sheet opened counts`() {
        assertEquals(listOf("a"), awaitingInvites(listOf(rec("a", sheet = now)), now).map { it.pairingId })
    }

    @Test fun `eight days old does not count, six days does`() {
        val old = rec("old", created = now - 8 * day, shared = now - 8 * day)
        val fresh = rec("fresh", created = now - 6 * day, shared = now - 6 * day)
        assertEquals(listOf("fresh"), awaitingInvites(listOf(old, fresh), now).map { it.pairingId })
    }
}
