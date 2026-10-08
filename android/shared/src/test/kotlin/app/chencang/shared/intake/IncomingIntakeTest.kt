package app.chencang.shared.intake

import app.chencang.shared.chat.ChatMessage
import app.chencang.shared.chat.WireReceiver
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import uniffi.chencang.encodeWire

// Robolectric only so android.util.Log works in the failure paths.
@RunWith(RobolectricTestRunner::class)
class IncomingIntakeTest {
    private class FakeReceiver(private val block: (String) -> ChatMessage?) : WireReceiver {
        var calls = 0
        override suspend fun receiveWireText(wire: String): ChatMessage? {
            calls++
            return block(wire)
        }
    }

    private fun msg(peer: String) = ChatMessage("id-$peer", peer, ChatMessage.DIRECTION_IN, "hi", 1L)

    private fun intake(
        receiver: WireReceiver,
        kind: IntakeKind = IntakeKind.Message("🔒w"),
        stored: ChatMessage? = null,
        hasContacts: Boolean = true,
        hasAwaitingInvites: Boolean = false,
    ) = IncomingIntake(
        receiver = receiver,
        findByWire = { stored },
        hasContacts = { hasContacts },
        hasAwaitingInvites = { hasAwaitingInvites },
        classifier = { kind },
    )

    @Test
    fun `a wire already stored opens its thread without decrypting`() = runTest {
        val r = FakeReceiver { msg("bob") }
        val out = intake(r, stored = msg("alice")).handle("🔒w")
        assertThat(out).isEqualTo(IntakeOutcome.AlreadyInThread("alice"))
        assertThat(r.calls).isEqualTo(0)
    }

    @Test
    fun `decrypted message opens the sender thread`() = runTest {
        val r = FakeReceiver { msg("bob") }
        assertThat(intake(r).handle("🔒w")).isEqualTo(IntakeOutcome.OpenThread("bob"))
        assertThat(r.calls).isEqualTo(1)
    }

    @Test
    fun `undecryptable with no contacts is NO_CONTACTS`() = runTest {
        val out = intake(FakeReceiver { null }, hasContacts = false).handle("🔒w")
        assertThat(out).isEqualTo(IntakeOutcome.Failed(IntakeFailure.NO_CONTACTS))
    }

    @Test
    fun `undecryptable with contacts is CANNOT_DECRYPT`() = runTest {
        val out = intake(FakeReceiver { null }, hasContacts = true).handle("🔒w")
        assertThat(out).isEqualTo(IntakeOutcome.Failed(IntakeFailure.CANNOT_DECRYPT))
    }

    @Test
    fun `undecryptable with an awaiting invite is PENDING_INVITE`() = runTest {
        val out = intake(FakeReceiver { null }, hasContacts = true, hasAwaitingInvites = true).handle("🔒w")
        assertThat(out).isEqualTo(IntakeOutcome.Failed(IntakeFailure.PENDING_INVITE))
        assertThat(IntakeFailure.PENDING_INVITE.actionRes).isNull()
    }

    @Test
    fun `undecryptable with an awaiting invite and no contacts is PENDING_INVITE`() = runTest {
        val out = intake(FakeReceiver { null }, hasContacts = false, hasAwaitingInvites = true).handle("🔒w")
        assertThat(out).isEqualTo(IntakeOutcome.Failed(IntakeFailure.PENDING_INVITE))
    }

    @Test
    fun `a decryptable message is unaffected by awaiting invites`() = runTest {
        val out = intake(FakeReceiver { msg("bob") }, hasAwaitingInvites = true).handle("🔒w")
        assertThat(out).isEqualTo(IntakeOutcome.OpenThread("bob"))
    }

    @Test
    fun `storage failure after decrypt is SAVE_FAILED`() = runTest {
        val out = intake(FakeReceiver { throw IllegalStateException("disk") }).handle("🔒w")
        assertThat(out).isEqualTo(IntakeOutcome.Failed(IntakeFailure.SAVE_FAILED))
    }

    @Test
    fun `truncated wire is never reported as save failed`() = runTest {
        val wire = encodeWire(byteArrayOf(0xCC.toByte(), 0xC8.toByte()) + ByteArray(40) { it.toByte() })
        val truncated = wire.dropLast(3)
        val r = FakeReceiver { null }
        val out = IncomingIntake(
            receiver = r,
            findByWire = { null },
            hasContacts = { true },
            hasAwaitingInvites = { false },
        ).handle(truncated)
        assertThat(out).isInstanceOf(IntakeOutcome.Failed::class.java)
        assertThat((out as IntakeOutcome.Failed).failure).isNotEqualTo(IntakeFailure.SAVE_FAILED)
    }

    @Test
    fun `pairing kinds are handed back with the located wire`() = runTest {
        val r = FakeReceiver { msg("bob") }
        assertThat(intake(r, kind = IntakeKind.PairingInvite("🔒inv")).handle("x\n🔒inv"))
            .isEqualTo(IntakeOutcome.Pairing("🔒inv"))
        assertThat(intake(r, kind = IntakeKind.PairingResponse("🔒res")).handle("🔒res"))
            .isEqualTo(IntakeOutcome.Pairing("🔒res"))
        assertThat(r.calls).isEqualTo(0)
    }

    @Test
    fun `incomplete and not ours map to their failures`() = runTest {
        val r = FakeReceiver { msg("bob") }
        assertThat(intake(r, kind = IntakeKind.Incomplete).handle("🔒"))
            .isEqualTo(IntakeOutcome.Failed(IntakeFailure.INCOMPLETE))
        assertThat(intake(r, kind = IntakeKind.NotOurs).handle("hello"))
            .isEqualTo(IntakeOutcome.Failed(IntakeFailure.NOT_OURS))
        assertThat(r.calls).isEqualTo(0)
    }

    @Test
    fun `link only maps to its own failure that reads as incomplete`() = runTest {
        val r = FakeReceiver { msg("bob") }
        assertThat(intake(r, kind = IntakeKind.LinkOnly).handle("https://x.example/m/"))
            .isEqualTo(IntakeOutcome.Failed(IntakeFailure.LINK_ONLY))
        assertThat(IntakeFailure.LINK_ONLY.messageRes).isEqualTo(IntakeFailure.INCOMPLETE.messageRes)
        assertThat(IntakeFailure.LINK_ONLY.actionRes).isNull()
        assertThat(r.calls).isEqualTo(0)
    }

    @Test
    fun `null or blank input is INCOMPLETE`() = runTest {
        val r = FakeReceiver { msg("bob") }
        val i = IncomingIntake(receiver = r, findByWire = { null }, hasContacts = { true }, hasAwaitingInvites = { false })
        assertThat(i.handle(null)).isEqualTo(IntakeOutcome.Failed(IntakeFailure.INCOMPLETE))
        assertThat(i.handle("  \n ")).isEqualTo(IntakeOutcome.Failed(IntakeFailure.INCOMPLETE))
        assertThat(r.calls).isEqualTo(0)
    }

    @Test
    fun `cancellation is rethrown`() {
        val i = intake(FakeReceiver { throw CancellationException("gone") })
        assertThrows(CancellationException::class.java) {
            kotlinx.coroutines.runBlocking { i.handle("🔒w") }
        }
    }
}
