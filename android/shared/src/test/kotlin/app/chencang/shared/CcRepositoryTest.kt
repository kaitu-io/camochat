package app.chencang.shared

import app.cash.turbine.test
import app.chencang.shared.model.Contact
import app.chencang.shared.model.PairingState
import app.chencang.shared.model.PendingInvite
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

class CcRepositoryTest {

    private fun contact(
        fingerprintHex: String,
        username: String = fingerprintHex,
        displayName: String = username,
        pairedAt: Long = 1000L,
        safetyEmoji: List<String> = emptyList(),
    ) = Contact(
        fingerprintHex = fingerprintHex,
        username = username,
        displayName = displayName,
        pairedAt = pairedAt,
        safetyEmoji = safetyEmoji,
    )

    @Test
    fun `contactList starts empty`() = runTest {
        val repo = CcRepository.forTest()

        repo.contacts.test {
            assertThat(awaitItem()).isEmpty()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `upsertContact appears in flow`() = runTest {
        val repo = CcRepository.forTest()
        val alice = contact(fingerprintHex = "aa11", displayName = "Alice")

        repo.upsertContact(alice)

        repo.contacts.test {
            assertThat(awaitItem()).containsExactly(alice)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `contact carries pairing state and safety emoji`() = runTest {
        val repo = CcRepository.forTest()
        val emoji = listOf("🐉", "🍵", "🏮", "🎋", "🦄", "🔥", "🌊", "🌙")
        val alice = contact(fingerprintHex = "aa11", safetyEmoji = emoji)

        assertThat(alice.pairingState).isEqualTo(PairingState.PAIRED)

        repo.upsertContact(alice)

        repo.contacts.test {
            val list = awaitItem()
            assertThat(list).hasSize(1)
            assertThat(list[0].safetyEmoji).isEqualTo(emoji)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `upsertContact dedupes by fingerprintHex`() = runTest {
        val repo = CcRepository.forTest()
        // Same fingerprint, different cosmetic fields + username — must collapse to one.
        val v1 = contact(fingerprintHex = "aa11", username = "alice", displayName = "Alice", pairedAt = 1000L)
        val v2 = contact(fingerprintHex = "aa11", username = "alice2", displayName = "Alice 2", pairedAt = 2000L)
        repo.upsertContact(v1)
        repo.upsertContact(v2)

        repo.contacts.test {
            val list = awaitItem()
            assertThat(list).hasSize(1)
            assertThat(list[0].displayName).isEqualTo("Alice 2")
            assertThat(list[0].username).isEqualTo("alice2")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `distinct fingerprints coexist`() = runTest {
        val repo = CcRepository.forTest()
        repo.upsertContact(contact(fingerprintHex = "aa11", displayName = "Alice"))
        repo.upsertContact(contact(fingerprintHex = "bb22", displayName = "Bob"))

        repo.contacts.test {
            assertThat(awaitItem()).hasSize(2)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `renameContact updates displayName only, trims, ignores blank, no-ops unknown`() = runTest {
        val repo = CcRepository.forTest()
        repo.upsertContact(
            Contact(
                fingerprintHex = "fp1",
                username = "fp1",
                displayName = "联系人 fp1xxx",
                pairedAt = 1L,
                pairingState = PairingState.PAIRED,
                safetyEmoji = listOf("🐶"),
                deviceId = "fp1",
            ),
        )

        repo.renameContact("fp1", "  老王  ")
        val c = repo.contacts.first().single()
        assertThat(c.displayName).isEqualTo("老王")          // trimmed
        assertThat(c.fingerprintHex).isEqualTo("fp1")        // identity untouched
        assertThat(c.safetyEmoji).isEqualTo(listOf("🐶"))    // other fields untouched
        assertThat(c.deviceId).isEqualTo("fp1")

        repo.renameContact("fp1", "   ")                     // blank ignored
        assertThat(repo.contacts.first().single().displayName).isEqualTo("老王")

        repo.renameContact("unknown", "x")                   // unknown fp: no crash, no-op
        assertThat(repo.contacts.first()).hasSize(1)
        assertThat(repo.contacts.first().single().displayName).isEqualTo("老王")
    }

    @Test
    fun `markVerified toggles verified flag by fingerprint`() = runTest {
        val repo = CcRepository.forTest()
        repo.upsertContact(contact(fingerprintHex = "cc33", displayName = "Carol"))

        repo.markVerified("cc33")

        repo.contacts.test {
            val list = awaitItem()
            assertThat(list).hasSize(1)
            assertThat(list[0].verified).isTrue()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `updateSessionState stores opaque bytes by fingerprint`() = runTest {
        val repo = CcRepository.forTest()
        repo.upsertContact(contact(fingerprintHex = "dd44", displayName = "Dave"))
        val state = byteArrayOf(1, 2, 3, 4, 5)

        repo.updateSessionState("dd44", state)

        repo.contacts.test {
            val list = awaitItem()
            assertThat(list[0].sessionState).isEqualTo(state)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `deviceIdFor resolves by fingerprint`() = runTest {
        val repo = CcRepository.forTest()
        repo.upsertContact(
            contact(fingerprintHex = "ee55", displayName = "Erin").copy(deviceId = "dev-erin"),
        )

        assertThat(repo.deviceIdFor("ee55")).isEqualTo("dev-erin")
        assertThat(repo.deviceIdFor("unknown")).isNull()
    }

    @Test
    fun `pendingInvites round-trip through the store`() = runTest {
        val repo = CcRepository.forTest()
        val invite = PendingInvite(code = "ABC123", createdAt = 100L, expiresAt = 200L)

        repo.addPendingInvite(invite)

        repo.pendingInvites.test {
            assertThat(awaitItem()).containsExactly(invite)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `pendingInvites round-trip the peerLabelForA field`() = runTest {
        val repo = CcRepository.forTest()
        val invite = PendingInvite(
            code = "LBL123",
            createdAt = 100L,
            expiresAt = 200L,
            peerLabelForA = "小静",
        )

        repo.addPendingInvite(invite)

        val list = repo.pendingInvitesSnapshot()
        assertThat(list).hasSize(1)
        assertThat(list[0].peerLabelForA).isEqualTo("小静")
    }

    @Test
    fun `peerLabelForA defaults to null when omitted`() = runTest {
        val repo = CcRepository.forTest()
        repo.addPendingInvite(PendingInvite(code = "NL", createdAt = 1L, expiresAt = 2L))

        assertThat(repo.pendingInvitesSnapshot().single().peerLabelForA).isNull()
    }

    @Test
    fun `findPendingInvite returns the matching code, null otherwise`() = runTest {
        val repo = CcRepository.forTest()
        repo.addPendingInvite(PendingInvite(code = "AAA", createdAt = 1L, expiresAt = 2L, peerLabelForA = "小静"))
        repo.addPendingInvite(PendingInvite(code = "BBB", createdAt = 3L, expiresAt = 4L))

        assertThat(repo.findPendingInvite("AAA")?.peerLabelForA).isEqualTo("小静")
        assertThat(repo.findPendingInvite("BBB")?.peerLabelForA).isNull()
        assertThat(repo.findPendingInvite("MISSING")).isNull()
    }

    @Test
    fun `removePendingInvite drops the matching code`() = runTest {
        val repo = CcRepository.forTest()
        val a = PendingInvite(code = "AAA", createdAt = 1L, expiresAt = 2L)
        val b = PendingInvite(code = "BBB", createdAt = 3L, expiresAt = 4L)
        repo.addPendingInvite(a)
        repo.addPendingInvite(b)

        repo.removePendingInvite("AAA")

        repo.pendingInvites.test {
            assertThat(awaitItem()).containsExactly(b)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `addPendingInvite dedupes by code`() = runTest {
        val repo = CcRepository.forTest()
        repo.addPendingInvite(PendingInvite(code = "DUP", createdAt = 1L, expiresAt = 2L))
        repo.addPendingInvite(PendingInvite(code = "DUP", createdAt = 9L, expiresAt = 10L))

        repo.pendingInvites.test {
            val list = awaitItem()
            assertThat(list).hasSize(1)
            assertThat(list[0].createdAt).isEqualTo(9L)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `clearAll empties contacts and pending invites`() = runTest {
        val repo = CcRepository.forTest()
        repo.upsertContact(contact(fingerprintHex = "aa11", displayName = "Alice"))
        repo.addPendingInvite(PendingInvite(code = "ABC123", createdAt = 1L, expiresAt = 2L))

        repo.clearAll()

        assertThat(repo.contacts.first()).isEmpty()
        assertThat(repo.pendingInvites.first()).isEmpty()
    }

    @Test
    fun `rename and markVerified preserve acceptedInviteDigest`() = runTest {
        val repo = CcRepository.forTest()
        repo.upsertContact(contact(fingerprintHex = "aa11").copy(acceptedInviteDigest = "digest-1"))

        repo.renameContact("aa11", "老周")
        repo.markVerified("aa11")

        val stored = repo.contacts.first().single()
        assertThat(stored.displayName).isEqualTo("老周")
        assertThat(stored.verified).isTrue()
        assertThat(stored.acceptedInviteDigest).isEqualTo("digest-1")
    }

    @Test
    fun `removeContact drops the digest with the contact`() = runTest {
        val repo = CcRepository.forTest()
        repo.upsertContact(contact(fingerprintHex = "aa11").copy(acceptedInviteDigest = "digest-1"))
        repo.upsertContact(contact(fingerprintHex = "bb22").copy(acceptedInviteDigest = "digest-2"))

        repo.removeContact("aa11")

        assertThat(repo.contacts.first().map { it.acceptedInviteDigest }).containsExactly("digest-2")
    }

    @Test
    fun `contacts that differ only in acceptedInviteDigest are not equal`() {
        val a = contact(fingerprintHex = "aa11").copy(acceptedInviteDigest = "digest-1")
        val b = contact(fingerprintHex = "aa11").copy(acceptedInviteDigest = "digest-2")
        val none = contact(fingerprintHex = "aa11")

        assertThat(a).isNotEqualTo(b)
        assertThat(a).isNotEqualTo(none)
        assertThat(a.hashCode()).isNotEqualTo(b.hashCode())
        assertThat(a).isEqualTo(a.copy())
        assertThat(a.hashCode()).isEqualTo(a.copy().hashCode())
    }
}
