package uniffi.chencang

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase A3.7 Task 19 — Kotlin instrumented round-trip + fingerprint tests.
 *
 * Mirrors `tests/facade_round_trip.rs::alice_bob_round_trip_with_opk` so
 * a green run here proves the JNA-wrapped AAR works end-to-end on Android.
 */
@RunWith(AndroidJUnit4::class)
class SessionInstrumentedTest {
    @Test
    fun basicRoundTripWithOpk() {
        // Alice is the receiver (publishes a PreKeyBundle); Bob is the initiator.
        val bobIk = SecretIdentity()
        val aliceIk = SecretIdentity()
        val aliceSpk = SecretSignedPreKey(aliceIk, 1u)
        val aliceOpk = SecretOneTimePreKey(7u)

        val bundle = PreKeyBundle(
            `ik` = aliceIk.`publicIdentity`(),
            `spk` = aliceSpk.`publicForm`(),
            `opk` = aliceOpk.`publicForm`(),
            `inviterUsername` = "alice",
            `inviteId` = ByteArray(16) { 0x11.toByte() },
            `pairingNonce` = ByteArray(16) { 0x22.toByte() },
        )

        val initOut = `deriveInitiatorHandshake`(bobIk, bundle)
        val srkAlice = `deriveResponderHandshake`(
            aliceIk,
            aliceSpk,
            aliceOpk,
            initOut.`bobIdentityPublic`,
            initOut.`ekX25519Pub`,
            initOut.`ekMlkemPub`,
            initOut.`kemCtToSpk`,
            initOut.`kemCtToIk`,
            initOut.`kemCtToOpk`,
        )
        assertArrayEquals(
            "responder must derive identical SRK",
            initOut.`sessionRootKey`,
            srkAlice,
        )

        val sid = byteArrayOf(9, 9, 9, 9, 9)
        val bobSess = Session.`initiatorAfterHandshake`(
            initOut.`sessionRootKey`,
            sid,
            aliceIk.`publicIdentity`(),
            initOut.`ekX25519Secret`!!,
            initOut.`ekMlkemSecret`!!,
        )
        val aliceSess = Session.`responderAfterHandshake`(
            srkAlice,
            sid,
            initOut,
        )

        val plain = "hello chencang".toByteArray(Charsets.UTF_8)
        val wire = bobSess.`encrypt`(plain)
        // Wire format must NOT be a URL — it is a z-base32 encoded blob.
        assertFalse("wire must not contain ://", wire.contains("://"))
        assertTrue("wire must be non-empty", wire.isNotEmpty())
        val decrypted = aliceSess.`decrypt`(wire)
        assertArrayEquals("decrypted plaintext", plain, decrypted)
    }

    @Test
    fun fingerprintConsistency() {
        val id = SecretIdentity()
        val fp1 = `fingerprintOfPublic`(id.`publicIdentity`())
        val fp2 = `fingerprintOfPublic`(id.`publicIdentity`())
        assertEquals(fp1.`hex`, fp2.`hex`)
        assertEquals(16, fp1.`value`.size)
        assertEquals(32, fp1.`hex`.length)
    }

    @Test
    fun secretIdentityRoundTripsThroughLocalStorage() {
        val original = SecretIdentity()
        val pubBefore = original.`publicIdentity`()
        val serialized = original.`serializeForLocalStorage`()
        val restored = SecretIdentity.`fromLocalStorage`(serialized)
        val pubAfter = restored.`publicIdentity`()
        assertArrayEquals(pubBefore.`ikDhX25519`, pubAfter.`ikDhX25519`)
        assertArrayEquals(pubBefore.`ikSigEd25519`, pubAfter.`ikSigEd25519`)
        assertArrayEquals(pubBefore.`ikKemMlkem768`, pubAfter.`ikKemMlkem768`)
        assertArrayEquals(pubBefore.`ikSigMldsa65`, pubAfter.`ikSigMldsa65`)
    }
}
