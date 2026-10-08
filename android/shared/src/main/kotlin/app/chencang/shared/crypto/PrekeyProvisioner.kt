package app.chencang.shared.crypto

import android.content.SharedPreferences
import uniffi.chencang.SecretIdentity
import uniffi.chencang.SecretSignedPreKey

/**
 * Manages this device's signed pre-key (SPK) locally. V1 is SPK-only (no OPKs —
 * the binding can't sign OPKs yet). Zero-network: no server upload.
 *
 * The SPK is generated ONCE, its secret form serialized and persisted in
 * Keystore-backed storage ([SignedPreKeyStore]), and reloaded for the responder
 * side of the handshake. This is required because `SecretSignedPreKey(ik, version)`
 * generates a FRESH random keypair on every call — without persistence the
 * responder could never reconstruct the exact signed-prekey it advertised, so A and
 * B would derive different root keys.
 */
class PrekeyProvisioner(
    private val prefs: SharedPreferences,
    private val spkStore: SignedPreKeyStore,
) {
    /**
     * Generate + persist this device's SPK if absent — LOCAL ONLY, no server.
     * Idempotent (a no-op once provisioned). The zero-server in-band pairing path
     * ([app.chencang.shared.pairing.inband.PairingCoordinator]) calls this before
     * [loadActiveSpk] so a brand-new identity — which never ran the server boot
     * path — can still mint an invite. The SPK is derived
     * deterministically from [identity] + [SPK_VERSION] and persisted, so the
     * inviter reconstructs the exact same signed-prekey when it completes. Zero-network
     * replacement for the old server-upload `provisionIfNeeded`.
     */
    suspend fun provisionLocallyIfNeeded(identity: SecretIdentity) {
        // The persisted SPK file is the SOLE source of truth: SecretSignedPreKey()
        // mints a FRESH random keypair each call, so once a file exists we must
        // NEVER regenerate — the inviter advertised that exact SPK's public half in
        // its bundle and must reload the identical secret to complete. (A plain
        // `prefs && file` conjunction would regenerate a *different* SPK in the
        // pref-set-but-file-missing split-brain, silently breaking key confirmation.)
        if (spkStore.exists()) {
            if (!prefs.contains(KEY_SPK_VERSION)) {
                prefs.edit().putInt(KEY_SPK_VERSION, SPK_VERSION).apply()
            }
            return
        }
        SecretSignedPreKey(identity, SPK_VERSION.toUInt()).use { spk ->
            spkStore.save(spk.serialize())
        }
        prefs.edit().putInt(KEY_SPK_VERSION, SPK_VERSION).apply()
    }

    /** Load the persisted secret SPK. Caller OWNS the returned object — must .use{}/.close() it. */
    fun loadActiveSpk(): SecretSignedPreKey {
        val bytes = spkStore.load() ?: error("no persisted SPK — provisionLocallyIfNeeded() must run first")
        return SecretSignedPreKey.fromSerialized(bytes)
    }

    fun activeSpkVersion(): Int = prefs.getInt(KEY_SPK_VERSION, SPK_VERSION)

    companion object {
        const val SPK_VERSION = 0
        private const val KEY_SPK_VERSION = "chencang.spk_version"
    }
}
