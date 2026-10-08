package uniffi.chencang

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase A3.8 Task 23 — Kotlin side of the cross-platform equivalence gate
 * (spec §16.2). Mirrors `GoldenVectorsTests.swift`: load Rust-generated
 * `vectors.json`, reconstruct Alice's `Session` from `alice_session_state_hex`,
 * decrypt the bundled wire blobs, and assert byte-exact match with the
 * recorded plaintexts.
 *
 * If this test ever disagrees with the Rust or Swift suite the protocol is
 * broken — do NOT paper over with skipped assertions.
 */
@RunWith(AndroidJUnit4::class)
class GoldenVectorsInstrumentedTest {
    @Test
    fun decryptsCoreGeneratedWire() {
        val ctx = InstrumentationRegistry.getInstrumentation().context
        val raw = ctx.assets.open("vectors.json").bufferedReader().use { it.readText() }
        val root = JSONObject(raw)

        assertEquals("1.0.0", root.getString("spec_version"))
        assertEquals("0909090909", root.getString("session_id_hex"))

        val aliceState = hexDecode(root.getString("alice_session_state_hex"))
        val aliceSess = Session.`fromSerializedState`(aliceState)

        val messages = root.getJSONArray("messages")
        assertEquals("generator must emit two messages", 2, messages.length())

        for (i in 0 until messages.length()) {
            val msg = messages.getJSONObject(i)
            val plaintext = hexDecode(msg.getString("plaintext_hex"))
            val wire = msg.getString("wire")
            val decrypted = aliceSess.`decrypt`(wire)
            assertArrayEquals(
                "message #$i Kotlin decrypt must match Rust-generated plaintext",
                plaintext,
                decrypted,
            )
        }
    }

    @Test
    fun aliceFingerprintIsDeterministic() {
        val ctx = InstrumentationRegistry.getInstrumentation().context
        val raw = ctx.assets.open("vectors.json").bufferedReader().use { it.readText() }
        val root = JSONObject(raw)
        val aliceJson = root.getJSONObject("alice")

        val alicePub = PublicIdentity(
            `ikDhX25519` = hexDecode(aliceJson.getString("ik_dh_x25519_hex")),
            `ikSigEd25519` = hexDecode(aliceJson.getString("ik_sig_ed25519_hex")),
            `ikKemMlkem768` = hexDecode(aliceJson.getString("ik_kem_mlkem768_hex")),
            `ikSigMldsa65` = hexDecode(aliceJson.getString("ik_sig_mldsa65_hex")),
        )
        val fp1 = `fingerprintOfPublic`(alicePub)
        val fp2 = `fingerprintOfPublic`(alicePub)
        assertEquals(fp1.`hex`, fp2.`hex`)
        assertEquals(16, fp1.`value`.size)
        assertEquals(32, fp1.`hex`.length)
    }

    private fun hexDecode(s: String): ByteArray {
        require(s.length % 2 == 0) { "odd-length hex string" }
        return ByteArray(s.length / 2) { i ->
            ((Character.digit(s[i * 2], 16) shl 4) or
                Character.digit(s[i * 2 + 1], 16)).toByte()
        }
    }
}
