package app.chencang.shared.pairing.inband

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PendingItemsTest {
    private val now = 1_800_000_000_000L
    private val minute = 60_000L
    private val day = 86_400_000L

    private fun invite(id: String, createdAt: Long, sharedAt: Long? = null, wire: String = "🔒wire-$id") =
        PendingPairingRecord(
            pairingId = id,
            pairingNonceB64 = "nonce-$id",
            createdAtMillis = createdAt,
            inviteWire = wire,
            note = "note-$id",
            lastSharedAtMillis = sharedAt,
        )

    private fun response(fp: String, createdAt: Long, sharedAt: Long? = null) = PairingResponseRecord(
        fingerprintHex = fp,
        responseWire = "🔒response-$fp",
        inviteDigest = "digest-$fp",
        createdAtMillis = createdAt,
        lastSharedAtMillis = sharedAt,
    )

    // 表 K（配对中只列发出去的配对码）
    private val inviteA = invite("A", now - 5 * minute)                                  // 没发过：不列、不计
    private val inviteB = invite("B", now - 3 * day, sharedAt = now - 3 * day)
    private val inviteC = invite("C", now - 31 * day, sharedAt = now - 31 * day)
    private val responseR = response("f0r1", now - minute)
    private val responseR2 = response("f0r2", now - 2 * minute, sharedAt = now - minute)
    private val responseR3 = response("f0r3", now - minute)
    private val contacts = setOf("f0r1", "f0r2")   // R3 的联系人已不在

    private fun tableK(invites: List<PendingPairingRecord> = listOf(inviteC, inviteB, inviteA)) =
        pendingItems(invites, listOf(responseR3, responseR2, responseR), contacts, now)

    @Test
    fun `table K`() {
        val items = tableK()

        assertThat(items.map { it.id }).containsExactly("f0r1", "B", "C").inOrder()
        assertThat(items.map { it.kind }).containsExactly(
            PendingKind.ResponseUnsent,
            PendingKind.InviteAwaiting,
            PendingKind.InviteAwaiting,
        ).inOrder()
        assertThat(items.map { it.isStale }).containsExactly(false, false, true).inOrder()
        assertThat(items.map { it.needsMyAction }).containsExactly(true, false, false).inOrder()
        assertThat(items.map { it.canResend }).containsExactly(true, true, true).inOrder()
        assertThat(items.map { it.createdAtMillis }).containsExactly(
            responseR.createdAtMillis, inviteB.createdAtMillis, inviteC.createdAtMillis,
        ).inOrder()
        // 邀请带自己的备注；回应没有备注（界面显示联系人名）。
        assertThat(items.map { it.note }).containsExactly(null, "note-B", "note-C").inOrder()

        assertThat(pendingBadgeCount(items)).isEqualTo(1)
    }

    @Test
    fun `never shared invite is neither listed nor counted`() {
        val items = pendingItems(listOf(invite("A", now - minute, sharedAt = null)), emptyList(), emptySet(), now)

        assertThat(items).isEmpty()
        assertThat(pendingBadgeCount(items)).isEqualTo(0)
    }

    @Test
    fun `sheet-presented-only invite is listed as awaiting and agrees with awaitingInvites`() {
        val rec = invite("S", now - minute).copy(sheetPresentedAtMillis = now - minute)
        val items = pendingItems(listOf(rec), emptyList(), emptySet(), now)

        assertThat(items.map { it.id }).containsExactly("S")
        assertThat(items.single().kind).isEqualTo(PendingKind.InviteAwaiting)
        assertThat(pendingBadgeCount(items)).isEqualTo(0)
        assertThat(items.size).isAtLeast(awaitingInvites(listOf(rec), now).size)
    }

    @Test
    fun `shared invite is listed as awaiting and not counted`() {
        val items = pendingItems(listOf(invite("A", now - minute, sharedAt = now)), emptyList(), emptySet(), now)

        assertThat(items.single().kind).isEqualTo(PendingKind.InviteAwaiting)
        assertThat(pendingBadgeCount(items)).isEqualTo(0)
    }

    @Test
    fun `unsent response is listed and counted`() {
        val items = pendingItems(emptyList(), listOf(responseR), contacts, now)

        assertThat(items.single().kind).isEqualTo(PendingKind.ResponseUnsent)
        assertThat(pendingBadgeCount(items)).isEqualTo(1)
    }

    @Test
    fun `marking an invite shared moves it into the list without a badge`() {
        val before = tableK(listOf(inviteA))
        assertThat(before.map { it.id }).containsExactly("f0r1").inOrder()

        val after = tableK(listOf(inviteA.copy(lastSharedAtMillis = now)))
        assertThat(after.map { it.id }).containsExactly("f0r1", "A").inOrder()
        assertThat(pendingBadgeCount(after)).isEqualTo(1)
    }

    @Test
    fun `exactly 30 days is not stale`() {
        val items = pendingItems(
            invites = listOf(
                invite("edge", now - 30 * day, sharedAt = now),
                invite("over", now - 30 * day - 1, sharedAt = now),
            ),
            responses = listOf(response("f0r1", now - 30 * day - 2)),
            contactFingerprints = contacts,
            nowMillis = now,
        )

        assertThat(items.single { it.id == "edge" }.isStale).isFalse()
        assertThat(items.single { it.id == "edge" }.needsMyAction).isFalse()
        assertThat(items.single { it.id == "over" }.isStale).isTrue()
        // 超期的回应仍列出，但不计角标。
        assertThat(items.single { it.id == "f0r1" }.isStale).isTrue()
        assertThat(items.map { it.id }).containsExactly("edge", "over", "f0r1").inOrder()
        assertThat(pendingBadgeCount(items)).isEqualTo(0)
    }

    @Test
    fun `migrated invite cannot resend`() {
        // 旧单槽迁来的记录：暗号文本为空，最近分享时间 = 创建时间。
        val migrated = PendingPairingRecord("old", "b2xk", now - day, inviteWire = "", lastSharedAtMillis = now - day)
        val item = pendingItems(listOf(migrated), emptyList(), emptySet(), now).single()

        assertThat(item.kind).isEqualTo(PendingKind.InviteAwaiting)
        assertThat(item.canResend).isFalse()
        assertThat(item.needsMyAction).isFalse()
        assertThat(item.note).isNull()
    }

    @Test
    fun unsentResponseFingerprints() {
        val responses = listOf(responseR, responseR2, responseR3)

        assertThat(unsentResponseFingerprints(responses, contacts)).containsExactly("f0r1")
        assertThat(unsentResponseFingerprints(responses, emptySet())).isEmpty()
        assertThat(unsentResponseFingerprints(emptyList(), contacts)).isEmpty()
    }

    @Test
    fun `same-millisecond rows order by id ascending as the third key`() {
        val items = pendingItems(
            invites = listOf(invite("b", now - minute, now), invite("c", now - minute, now), invite("a", now - minute, now)),
            responses = emptyList(),
            contactFingerprints = emptySet(),
            nowMillis = now,
        )
        assertThat(items.map { it.id }).containsExactly("a", "b", "c").inOrder()
        // 输入顺序不影响结果。
        val reversed = pendingItems(
            invites = listOf(invite("a", now - minute, now), invite("c", now - minute, now), invite("b", now - minute, now)),
            responses = emptyList(),
            contactFingerprints = emptySet(),
            nowMillis = now,
        )
        assertThat(reversed.map { it.id }).containsExactly("a", "b", "c").inOrder()
    }
}
