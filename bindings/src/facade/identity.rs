//! Identity facade: `SecretIdentity`, `PublicIdentity`, `Fingerprint`.

use std::sync::Arc;

use parking_lot::Mutex;

use chencang_core::identity::fingerprint::Fingerprint as CoreFingerprint;
use chencang_core::identity::keypair::{
    PublicIdentity as CorePubId, SecretIdentity as CoreSecretId,
};
use chencang_core::primitives::{ed25519, ml_dsa, ml_kem, x25519};

use super::error::{ChencangError, Result};
use crate::rng::os_rng;

/// Long-term identity keypair (4-key bundle). Held behind an `Arc<Mutex<_>>` so
/// the FFI boundary can clone references freely.
pub struct SecretIdentity {
    inner: Arc<Mutex<CoreSecretId>>,
}

impl SecretIdentity {
    /// Generate a fresh identity using the platform CSPRNG.
    ///
    /// At the FFI boundary uniffi-rs wraps the returned `Self` in `Arc`
    /// automatically; we return the owned value so that the manual export
    /// (`#[uniffi::export_for_udl]`) and the UDL `interface` stay in sync.
    #[must_use]
    pub fn new() -> Self {
        let mut rng = os_rng();
        Self {
            inner: Arc::new(Mutex::new(CoreSecretId::generate(&mut rng))),
        }
    }

    /// Reconstruct from local-storage serialization.
    ///
    /// # Errors
    /// Returns [`ChencangError::Decoding`] when the serialized bytes are
    /// malformed.
    pub fn from_local_storage(data: Vec<u8>) -> Result<Self> {
        let parsed = CoreSecretId::deserialize(&data)?;
        Ok(Self {
            inner: Arc::new(Mutex::new(parsed)),
        })
    }

    /// Project the public half (4 public keys).
    #[must_use]
    pub fn public_identity(&self) -> PublicIdentity {
        let guard = self.inner.lock();
        let pubid = guard.public();
        PublicIdentity {
            ik_dh_x25519: pubid.ik_dh_x25519.0.to_vec(),
            ik_sig_ed25519: pubid.ik_sig_ed25519.to_bytes().to_vec(),
            ik_kem_mlkem768: pubid.ik_kem_mlkem768.as_bytes().to_vec(),
            ik_sig_mldsa65: pubid.ik_sig_mldsa65.to_bytes().to_vec(),
        }
    }

    /// Serialize the full secret identity for local (encrypted-at-rest) storage.
    #[must_use]
    pub fn serialize_for_local_storage(&self) -> Vec<u8> {
        self.inner.lock().serialize()
    }

    /// Sign `message` with the Ed25519 IK private key. Returns the 64-byte
    /// signature.
    ///
    /// General-purpose identity signature; the caller defines the message
    /// format and any domain separation. The current apps do not call it
    /// (pairing signs the SPK inside the core, not via this function).
    #[must_use]
    pub fn sign_ed25519_ik(&self, message: Vec<u8>) -> Vec<u8> {
        self.inner.lock().ik_sig_ed25519.sign(&message).to_vec()
    }

    /// Internal accessor for other facade modules that need to call into
    /// chencang-core with the underlying `SecretIdentity`.
    pub(crate) fn lock(&self) -> parking_lot::MutexGuard<'_, CoreSecretId> {
        self.inner.lock()
    }
}

impl Default for SecretIdentity {
    fn default() -> Self {
        Self::new()
    }
}

/// 4-key public identity bundle.
#[derive(Clone)]
pub struct PublicIdentity {
    /// X25519 DH public key, 32 bytes.
    pub ik_dh_x25519: Vec<u8>,
    /// Ed25519 verifying key, 32 bytes.
    pub ik_sig_ed25519: Vec<u8>,
    /// ML-KEM-768 public key, 1184 bytes.
    pub ik_kem_mlkem768: Vec<u8>,
    /// ML-DSA-65 verifying key, 1952 bytes.
    pub ik_sig_mldsa65: Vec<u8>,
}

impl PublicIdentity {
    /// Convert to chencang-core `PublicIdentity`. Validates lengths.
    ///
    /// # Errors
    /// Returns [`ChencangError::InvalidLength`] when any field has the wrong
    /// length, or [`ChencangError::Decoding`] when ML-DSA decoding fails.
    pub fn to_core(&self) -> Result<CorePubId> {
        let dh: [u8; 32] = self.ik_dh_x25519[..].try_into().map_err(|_| {
            ChencangError::InvalidLength(format!(
                "ik_dh_x25519 must be 32 bytes, got {}",
                self.ik_dh_x25519.len()
            ))
        })?;
        let ed: [u8; 32] = self.ik_sig_ed25519[..].try_into().map_err(|_| {
            ChencangError::InvalidLength(format!(
                "ik_sig_ed25519 must be 32 bytes, got {}",
                self.ik_sig_ed25519.len()
            ))
        })?;
        let kem_arr: [u8; ml_kem::PUBLIC_KEY_LEN] =
            self.ik_kem_mlkem768[..].try_into().map_err(|_| {
                ChencangError::InvalidLength(format!(
                    "ik_kem_mlkem768 must be {} bytes, got {}",
                    ml_kem::PUBLIC_KEY_LEN,
                    self.ik_kem_mlkem768.len()
                ))
            })?;
        let dsa = ml_dsa::PublicKey::from_bytes(&self.ik_sig_mldsa65)?;
        Ok(CorePubId::from_components(
            x25519::PublicKey32(dh),
            ed25519::VerifyingKey(ed),
            ml_kem::PublicKey::from_bytes(kem_arr),
            dsa,
        ))
    }
}

/// 16-byte identity fingerprint + cached hex form.
pub struct Fingerprint {
    /// Raw 16-byte fingerprint.
    pub value: Vec<u8>,
    /// Hex (32 lowercase chars).
    pub hex: String,
}

/// Compute the fingerprint of a 4-key public identity.
///
/// Invalid `identity` (wrong-length fields) returns a zero-fingerprint —
/// callers should validate inputs upstream. Returning a zero fingerprint
/// rather than `Result` keeps the UDL signature parameter-less.
#[must_use]
pub fn fingerprint_of_public(identity: PublicIdentity) -> Fingerprint {
    match identity.to_core() {
        Ok(core_pub) => {
            let fp = CoreFingerprint::of(&core_pub);
            Fingerprint {
                value: fp.0.to_vec(),
                hex: hex::encode(fp.0),
            }
        }
        Err(_) => Fingerprint {
            value: vec![0u8; 16],
            hex: "00000000000000000000000000000000".to_string(),
        },
    }
}

/// Top-level helper exposed in the UDL `namespace chencang`.
#[must_use]
pub fn generate_secret_identity() -> Arc<SecretIdentity> {
    Arc::new(SecretIdentity::new())
}
