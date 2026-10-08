package app.chencang.shared.crypto

import app.chencang.shared.identity.IdentityFileEnvelope
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SignedPreKeyStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `wipe deletes spk file and is safe when absent`() {
        val file = tmp.newFolder().resolve("spk.enc")
        val store = SignedPreKeyStore(IdentityFileEnvelope.passthrough, file)
        store.save(byteArrayOf(9, 9, 9))
        assertThat(store.exists()).isTrue()
        store.wipe()
        assertThat(store.exists()).isFalse()
        store.wipe() // second call must not throw
    }
}
