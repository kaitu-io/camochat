package app.chencang.shared.pairing

import android.content.Context
import app.chencang.shared.crypto.PrekeyProvisioner
import app.chencang.shared.crypto.SignedPreKeyStore
import app.chencang.shared.identity.IdentityFileEnvelope
import app.chencang.shared.identity.KeystoreHelper
import uniffi.chencang.SecretIdentity
import uniffi.chencang.SecretSignedPreKey
import java.io.File

/**
 * androidTest helper: builds a Keystore-backed [SignedPreKeyStore] under a throwaway
 * cache file (one per [name]) using a dedicated key alias, mirroring production wiring
 * without touching real SPK/identity state.
 *
 * When [seedFor] is supplied, the store is pre-populated with that identity's serialized
 * SPK (version 0) so a subsequent [PrekeyProvisioner.provisionIfNeeded] is a genuine no-op.
 */
fun newSpkTestStore(
    ctx: Context,
    name: String,
    seedFor: SecretIdentity? = null,
): SignedPreKeyStore {
    val file = File(ctx.cacheDir, "spk_test_$name.enc")
    if (file.exists()) file.delete()
    val store = SignedPreKeyStore(
        envelope = IdentityFileEnvelope.keystoreBacked(KeystoreHelper("chencang_spk_test_$name")),
        file = file,
    )
    if (seedFor != null) {
        SecretSignedPreKey(seedFor, PrekeyProvisioner.SPK_VERSION.toUInt()).use {
            store.save(it.serialize())
        }
    }
    return store
}
