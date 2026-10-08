package app.chencang.shared.crypto

import com.google.common.truth.Truth.assertThat
import org.junit.Assume
import org.junit.BeforeClass
import org.junit.Test
import uniffi.chencang.InitiatorHandshakeOutput
import uniffi.chencang.PreKeyBundle
import uniffi.chencang.SecretIdentity
import uniffi.chencang.SecretSignedPreKey
import uniffi.chencang.Session
import uniffi.chencang.deriveInitiatorHandshake
import uniffi.chencang.deriveResponderHandshake

/**
 * #201 H2 probe — SPK regeneration race.
 *
 * A's `loadActiveSpk()` must return the SAME secret SPK whose public was on the
 * server when B fetched the bundle. If A re-provisioned the SPK between
 * B-accept and A-poll, the SRK on each side diverges silently (ML-KEM with a
 * wrong sk returns a wrong shared secret, NO decap error). A's first message
 * is then encrypted with root_A; B tries to decrypt with root_B; the error
 * shape we'd expect is the same `no recv chain key` we saw in UAT 2026-05-31.
 *
 * Test design:
 *  1. Build PreKeyBundle from SPK_v1.public — this is what B fetched.
 *  2. Initiator derives root_B from SPK_v1.public.
 *  3. Responder derives root_A from a DIFFERENT secret SPK (SPK_v2) —
 *     simulating A having silently rotated its SPK between B's redeem and A's
 *     poll.
 *  4. Build both sessions; A (responder) encrypts; B (initiator) tries to
 *     decrypt → record the failure shape.
 *
 * Additionally we sanity-check that with matching SPKs the same code path
 * succeeds — to prove the divergence is the only variable.
 */
class SpkRegenScenarioTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun assumeNativeBindingsAvailable() {
            val arch = System.getProperty("os.arch", "")
            val override = System.getProperty(
                "uniffi.component.chencang.libraryOverride",
            )
            val jnaPath = System.getProperty("jna.library.path")
            Assume.assumeTrue(
                "host bindings not configured (jna.library.path / libraryOverride absent; arch=$arch)",
                override != null && jnaPath != null,
            )
        }
    }

    @Test
    fun `divergent SPK on responder produces decrypt failure on first R-to-I message`() {
        val initiatorIk = SecretIdentity()
        val responderIk = SecretIdentity()

        // SPK_v1: was on the server when B fetched bundle.
        val spkV1 = SecretSignedPreKey(responderIk, 1u)

        val bundleFromV1 = PreKeyBundle(
            ik = responderIk.publicIdentity(),
            spk = spkV1.publicForm(),
            opk = null,
            inviterUsername = "responder",
            inviteId = ByteArray(16) { 0x11.toByte() },
            pairingNonce = ByteArray(16) { 0x22.toByte() },
        )

        // B (initiator) derives session against SPK_v1.public.
        val initOut = deriveInitiatorHandshake(initiatorIk, bundleFromV1)

        // A's IME meanwhile generated a fresh SPK and stored it — A polls with
        // SPK_v2 (DIFFERENT secret).
        val spkV2 = SecretSignedPreKey(responderIk, 2u)

        val rootResponderDivergent = deriveResponderHandshake(
            responderIk,
            spkV2,           // <-- different SPK secret
            null,
            initOut.bobIdentityPublic,
            initOut.ekX25519Pub,
            initOut.ekMlkemPub,
            initOut.kemCtToSpk,
            initOut.kemCtToIk,
            null,
            ByteArray(16) { 0x22.toByte() },
        )

        val sid = byteArrayOf(9, 9, 9, 9, 9)
        val initiatorSession = Session.initiatorAfterHandshake(
            initOut.sessionRootKey,
            sid,
            responderIk.publicIdentity(),
            initOut.ekX25519Secret!!,
            initOut.ekMlkemSecret!!,
        )
        val responderReconstructedOutput = InitiatorHandshakeOutput(
            sessionRootKey = rootResponderDivergent,
            bobIdentityPublic = initOut.bobIdentityPublic,
            ekX25519Pub = initOut.ekX25519Pub,
            ekMlkemPub = initOut.ekMlkemPub,
            kemCtToSpk = initOut.kemCtToSpk,
            kemCtToIk = initOut.kemCtToIk,
            kemCtToOpk = null,
            ekX25519Secret = null,
            ekMlkemSecret = null,
        )
        val responderSession = Session.responderAfterHandshake(
            rootResponderDivergent,
            sid,
            responderReconstructedOutput,
        )

        try {
            // First sanity: roots must actually be different.
            assertThat(initOut.sessionRootKey).isNotEqualTo(rootResponderDivergent)
            println(
                "[H2] root divergence confirmed: " +
                    "initiator_root.size=${initOut.sessionRootKey.size}, " +
                    "responder_root.size=${rootResponderDivergent.size}, equal=false",
            )

            // A (responder, with divergent root) encrypts first; B tries decrypt.
            val pt = ByteArray(32) { (it + 0x50).toByte() }
            val ct = responderSession.encryptToBytes(pt)
            val result = runCatching { initiatorSession.decryptFromBytes(ct) }
            assertThat(result.isFailure).isTrue()
            val err = result.exceptionOrNull()!!
            println(
                "[H2] R→I decrypt failure under SPK divergence: " +
                    "${err::class.java.simpleName}: ${err.message}",
            )
        } finally {
            spkV1.close()
            spkV2.close()
            initiatorSession.close()
            responderSession.close()
        }
    }

    @Test
    fun `control — matching SPK round-trip works (proves only SPK is the variable)`() {
        // Same setup but responder uses SPK_v1 (matches what B saw).
        val pair = PqxdhHandshakeFixture.mintProductionFaithfulSessionPair()
        try {
            val pt = ByteArray(32) { (it + 0x60).toByte() }
            val ct = pair.responder.encryptToBytes(pt)
            val recovered = pair.initiator.decryptFromBytes(ct)
            assertThat(recovered).isEqualTo(pt)
            println("[H2 control] R→I with matching SPK: OK")
        } finally {
            pair.closeAll()
        }
    }
}
