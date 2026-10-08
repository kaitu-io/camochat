package app.chencang.shared.pairing.inband

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.chencang.SecretIdentity
import uniffi.chencang.SecretSignedPreKey

/**
 * ON-DEVICE twin of [app.chencang.shared.pairing.inband.InbandPairingLoopbackTest]
 * (which runs on the JVM host dylib). This instrumented variant drives the SAME
 * two-identity zero-server in-band classical pairing round-trip against the REAL
 * `libchencang_bindings.so` packaged in the APK — i.e. the actual arm64 native
 * code that ships to phones. It is the make-or-break engine proof for the #41
 * gate: the classical X3DH + Double-Ratchet handshake has only ever been
 * exercised on the host before; this confirms it on real hardware.
 *
 * Flow: buildInviteBundle(A) → accept(B, bundle) → complete(A, nonce, header),
 * asserting both sides land on the SAME session root key (matching 8-emoji
 * safety number) and can encrypt/decrypt to each other. A is stateless between
 * rounds (only the PUBLIC pairing nonce survives the wait).
 *
 * No native-lib guard (unlike the host test): on-device the binding loads
 * straight from the APK, exactly as production does.
 */
@RunWith(AndroidJUnit4::class)
class InbandPairingDeviceTest {

    @Test
    fun two_identities_pair_inband_same_srk_emoji_and_confirm() {
        // A = inviter = responder. Holds IK + SPK locally (long-lived, reloadable).
        val aIk = SecretIdentity()
        val aSpk = SecretSignedPreKey(aIk, 1u)
        // B = invitee = initiator. Only needs its own IK.
        val bIk = SecretIdentity()

        // Round 1: A mints the invite bundle (no ephemeral OPK; A keeps only the
        // public pairing nonce).
        val pending = InbandPairing.buildInviteBundle(aIk, aSpk, inviterUsername = "alice")

        // Round 2a: B accepts → derives session + builds the response header.
        val bAccept = InbandPairing.accept(bIk, pending.bundleBytes, bDisplayName = "bob")

        // Round 2b: A completes statelessly with its reloaded IK/SPK + the public
        // nonce + B's header → derives its mirror session.
        val aComplete = InbandPairing.complete(aIk, aSpk, pending.pairingNonce, bAccept.headerBytes)

        // Both sides must agree on the safety fingerprint (i.e. the SRK).
        assertThat(aComplete.emoji).isEqualTo(bAccept.emoji)
        assertThat(aComplete.emoji).hasSize(8)

        // Local classical contact ids are derived per peer and must be present;
        // they are A's view of B and B's view of A — necessarily different.
        assertThat(bAccept.peerFingerprintHex).isNotEmpty()
        assertThat(aComplete.peerFingerprintHex).isNotEmpty()
        assertThat(aComplete.peerFingerprintHex).isNotEqualTo(bAccept.peerFingerprintHex)
        assertThat(bAccept.peerDeviceId).isEqualTo(bAccept.peerFingerprintHex)
        assertThat(aComplete.peerDeviceId).isEqualTo(aComplete.peerFingerprintHex)

        // Sessions must talk to each other in both directions.
        try {
            val ptB = ByteArray(32) { it.toByte() }
            val ctB = bAccept.session.encryptToBytes(ptB)
            assertThat(aComplete.session.decryptFromBytes(ctB)).isEqualTo(ptB)

            val ptA = ByteArray(48) { (it + 7).toByte() }
            val ctA = aComplete.session.encryptToBytes(ptA)
            assertThat(bAccept.session.decryptFromBytes(ctA)).isEqualTo(ptA)
        } finally {
            bAccept.session.close()
            aComplete.session.close()
        }
    }

    @Test
    fun tampered_bundle_diverges() {
        val aIk = SecretIdentity()
        val aSpk = SecretSignedPreKey(aIk, 1u)
        val bIk = SecretIdentity()

        val pending = InbandPairing.buildInviteBundle(aIk, aSpk, inviterUsername = "alice")

        // Flip a byte in the middle of the encoded bundle (likely lands in key
        // material, not the version/suite header which would hard-reject).
        val tampered = pending.bundleBytes.copyOf()
        val mid = tampered.size / 2
        tampered[mid] = (tampered[mid].toInt() xor 0xFF).toByte()

        // Accept may fail at decode/derive (tamper in structural bytes) OR may
        // produce a session — but then A's confirm-tag verification MUST fail.
        val bAccept = try {
            InbandPairing.accept(bIk, tampered, bDisplayName = "bob")
        } catch (e: Exception) {
            // Decode/derive rejected the tamper outright — pairing did not
            // silently succeed. That satisfies the invariant.
            return
        }

        try {
            // B got a session against a tampered bundle. A must NOT confirm it.
            var completed: InbandPairing.CompleteResult? = null
            val threw = try {
                completed = InbandPairing.complete(aIk, aSpk, pending.pairingNonce, bAccept.headerBytes)
                false
            } catch (e: Exception) {
                true
            }
            if (!threw) {
                // If complete somehow returned, emoji MUST diverge — never a
                // silent match.
                assertThat(completed!!.emoji).isNotEqualTo(bAccept.emoji)
                completed.session.close()
            }
        } finally {
            bAccept.session.close()
        }
    }
}
