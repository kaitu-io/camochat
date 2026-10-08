package app.chencang.shared.identity

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Round-trips a real [uniffi.chencang.SecretIdentity] through real Android Keystore
 * encryption + file I/O. Requires:
 *  - An emulator/device with API 26+
 *  - The native chencang_bindings.so for the device ABI (shipped in the AAR jniLibs)
 *
 * If no emulator is available in CI, this test is skipped — controller defers
 * instrumented tests to a later CI run (see plan D.5).
 */
@RunWith(AndroidJUnit4::class)
class IdentityStoreInstrumentedTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val keystoreHelper = KeystoreHelper(alias = "chencang_identity_key_test")
    private val identityFile = ctx.filesDir.resolve("identity_test.enc")

    private fun store() = IdentityStore(
        IdentityEnvelopeFile(identityFile, IdentityFileEnvelope.keystoreBacked(keystoreHelper)),
    )

    @Before
    fun setUp() {
        keystoreHelper.deleteKey()
        if (identityFile.exists()) identityFile.delete()
    }

    @After
    fun tearDown() {
        keystoreHelper.deleteKey()
        if (identityFile.exists()) identityFile.delete()
    }

    @Test
    fun hasIdentity_isFalse_beforeFirstSave() {
        assertThat(store().hasIdentity()).isFalse()
    }

    @Test
    fun generateAndSave_thenLoad_roundTripsSerializedBytes() {
        val s = store()
        s.generateAndSave().close()
        val firstLoad = s.load().use { it.serializeForLocalStorage() }
        val secondLoad = s.load().use { it.serializeForLocalStorage() }
        assertThat(secondLoad).isEqualTo(firstLoad)
    }

    @Test
    fun envelope_decryptsCorrectly_acrossStoreInstances() {
        store().generateAndSave().close()
        val expected = store().load().use { it.serializeForLocalStorage() }

        val s2 = store()
        val actual = s2.load().use { it.serializeForLocalStorage() }

        assertThat(actual).isEqualTo(expected)
    }
}
