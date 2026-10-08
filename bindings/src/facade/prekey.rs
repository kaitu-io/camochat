//! `PreKey` facade: signed prekey (SPK), one-time prekey (OPK), and the
//! Alice -> Bob bundle that carries both.

use std::sync::Arc;

use parking_lot::Mutex;

use chencang_core::handshake::bundle::PreKeyBundle as CoreBundle;
use chencang_core::prekey::one_time::{OneTimePreKey, SecretOneTimePreKey as CoreOpk};
use chencang_core::prekey::signed::{SecretSignedPreKey as CoreSpk, SignedPreKey};
use chencang_core::primitives::{ml_kem, x25519};

use super::error::{ChencangError, Result};
use super::identity::{PublicIdentity, SecretIdentity};
use crate::rng::os_rng;

/// Mid-term signed prekey (Ed25519 + ML-DSA double-signed).
pub struct SecretSignedPreKey {
    inner: Arc<Mutex<CoreSpk>>,
}

impl SecretSignedPreKey {
    /// Generate a fresh SPK, signed by `ik`. `spk_version` mirrors the
    /// chencang-core epoch counter.
    ///
    /// # Errors
    /// Returns [`ChencangError::Internal`] only on internal signing failure
    /// (not currently reachable).
    pub fn new(ik: Arc<SecretIdentity>, spk_version: u32) -> Result<Self> {
        let mut rng = os_rng();
        let ik_guard = ik.lock();
        let spk = CoreSpk::generate(&mut rng, &ik_guard, spk_version);
        drop(ik_guard);
        Ok(Self {
            inner: Arc::new(Mutex::new(spk)),
        })
    }

    /// Public form suitable for transport in a `PreKeyBundle`.
    #[must_use]
    pub fn public_form(&self) -> SignedPreKeyPublic {
        let g = self.inner.lock();
        SignedPreKeyPublic {
            spk_version: g.public.epoch,
            spk_x25519: g.public.spk_x25519.0.to_vec(),
            spk_mlkem768: g.public.spk_mlkem768.as_bytes().to_vec(),
            ed25519_sig: g.public.sig_ed25519.to_vec(),
            ed25519_sig_classical: g.public.sig_ed25519_classical.to_vec(),
            mldsa65_sig: g.public.sig_mldsa65.clone(),
        }
    }

    /// Serialize the full secret SPK for Keystore-backed local persistence.
    /// NEVER transmit this — it contains the private SPK keys.
    #[must_use]
    pub fn serialize(&self) -> Vec<u8> {
        self.inner.lock().serialize()
    }

    /// Reconstruct a secret SPK from [`serialize`] output.
    ///
    /// # Errors
    /// Returns [`ChencangError`] on malformed input.
    pub fn from_serialized(data: Vec<u8>) -> Result<Self> {
        let spk = CoreSpk::deserialize(&data)?;
        Ok(Self {
            inner: Arc::new(Mutex::new(spk)),
        })
    }

    /// Internal accessor for handshake calls into chencang-core.
    pub(crate) fn lock(&self) -> parking_lot::MutexGuard<'_, CoreSpk> {
        self.inner.lock()
    }
}

/// Public SPK material (no secrets).
#[derive(Clone)]
pub struct SignedPreKeyPublic {
    /// Monotonic epoch counter chosen by the publisher.
    pub spk_version: u32,
    /// X25519 SPK public key (32 bytes).
    pub spk_x25519: Vec<u8>,
    /// ML-KEM-768 SPK public key (1184 bytes).
    pub spk_mlkem768: Vec<u8>,
    /// Ed25519 signature over `(spk_x25519 || spk_mlkem768 || spk_version)`.
    pub ed25519_sig: Vec<u8>,
    /// Classical-only Ed25519 signature over `(spk_x25519 || spk_version)`
    /// (no ML-KEM key in the buffer). This is what the in-band classical
    /// (suite 0x01) bundle carries — see `ClassicalSignedPreKey`.
    pub ed25519_sig_classical: Vec<u8>,
    /// ML-DSA-65 signature over the same buffer.
    pub mldsa65_sig: Vec<u8>,
}

impl SignedPreKeyPublic {
    /// Convert to chencang-core `SignedPreKey`. Validates lengths.
    pub(crate) fn to_core(&self) -> Result<SignedPreKey> {
        let x: [u8; 32] = self.spk_x25519[..].try_into().map_err(|_| {
            ChencangError::InvalidLength(format!(
                "spk_x25519 must be 32 bytes, got {}",
                self.spk_x25519.len()
            ))
        })?;
        let kem_arr: [u8; ml_kem::PUBLIC_KEY_LEN] =
            self.spk_mlkem768[..].try_into().map_err(|_| {
                ChencangError::InvalidLength(format!(
                    "spk_mlkem768 must be {} bytes, got {}",
                    ml_kem::PUBLIC_KEY_LEN,
                    self.spk_mlkem768.len()
                ))
            })?;
        let ed_sig: [u8; 64] = self.ed25519_sig[..].try_into().map_err(|_| {
            ChencangError::InvalidLength(format!(
                "ed25519_sig must be 64 bytes, got {}",
                self.ed25519_sig.len()
            ))
        })?;
        Ok(SignedPreKey {
            spk_x25519: x25519::PublicKey32(x),
            spk_mlkem768: ml_kem::PublicKey::from_bytes(kem_arr),
            sig_ed25519: ed_sig,
            sig_mldsa65: self.mldsa65_sig.clone(),
            epoch: self.spk_version,
            // PQ-suite (0x02) facade reconstruction: the classical-only Ed25519
            // signature is not part of this hybrid wire form and is unused on
            // this path. Left zeroed; the classical (suite 0x01) bundle is
            // assembled via a dedicated path (Task A4), never from here.
            sig_ed25519_classical: [0u8; 64],
        })
    }
}

/// One-time prekey (used once and burned).
pub struct SecretOneTimePreKey {
    inner: Arc<Mutex<CoreOpk>>,
}

impl SecretOneTimePreKey {
    /// Generate a fresh OPK with the given pool index.
    #[must_use]
    pub fn new(opk_index: u32) -> Self {
        let mut rng = os_rng();
        Self {
            inner: Arc::new(Mutex::new(CoreOpk::generate(&mut rng, opk_index))),
        }
    }

    /// Public form suitable for inclusion in a `PreKeyBundle`.
    #[must_use]
    pub fn public_form(&self) -> OneTimePreKeyPublic {
        let g = self.inner.lock();
        OneTimePreKeyPublic {
            opk_index: g.public.id,
            opk_x25519: g.public.opk_x25519.0.to_vec(),
            opk_mlkem768: g.public.opk_mlkem768.as_bytes().to_vec(),
        }
    }

    /// Internal accessor for handshake calls into chencang-core.
    pub(crate) fn lock(&self) -> parking_lot::MutexGuard<'_, CoreOpk> {
        self.inner.lock()
    }
}

/// Public OPK material (no secrets).
#[derive(Clone)]
pub struct OneTimePreKeyPublic {
    /// Pool index assigned at generation.
    pub opk_index: u32,
    /// X25519 OPK public key (32 bytes).
    pub opk_x25519: Vec<u8>,
    /// ML-KEM-768 OPK public key (1184 bytes).
    pub opk_mlkem768: Vec<u8>,
}

impl OneTimePreKeyPublic {
    pub(crate) fn to_core(&self) -> Result<OneTimePreKey> {
        let x: [u8; 32] = self.opk_x25519[..].try_into().map_err(|_| {
            ChencangError::InvalidLength(format!(
                "opk_x25519 must be 32 bytes, got {}",
                self.opk_x25519.len()
            ))
        })?;
        let kem_arr: [u8; ml_kem::PUBLIC_KEY_LEN] =
            self.opk_mlkem768[..].try_into().map_err(|_| {
                ChencangError::InvalidLength(format!(
                    "opk_mlkem768 must be {} bytes, got {}",
                    ml_kem::PUBLIC_KEY_LEN,
                    self.opk_mlkem768.len()
                ))
            })?;
        Ok(OneTimePreKey {
            id: self.opk_index,
            opk_x25519: x25519::PublicKey32(x),
            opk_mlkem768: ml_kem::PublicKey::from_bytes(kem_arr),
        })
    }
}

/// `PreKey` bundle (Alice -> Bob): identity + SPK + optional OPK + pairing
/// metadata.
#[derive(Clone)]
pub struct PreKeyBundle {
    /// Alice's 4-key identity public bundle.
    pub ik: PublicIdentity,
    /// Alice's currently-active signed prekey.
    pub spk: SignedPreKeyPublic,
    /// Optional one-time prekey (improves forward secrecy when present).
    pub opk: Option<OneTimePreKeyPublic>,
    /// Inviter username (for AAD binding + UI display).
    pub inviter_username: String,
    /// 16-byte invite id (matches the URL path).
    pub invite_id: Vec<u8>,
    /// 16-byte pairing nonce.
    pub pairing_nonce: Vec<u8>,
}

impl PreKeyBundle {
    /// Map to chencang-core `PreKeyBundle`. Validates all internal lengths.
    pub(crate) fn to_core(&self) -> Result<CoreBundle> {
        let ik = self.ik.to_core()?;
        let spk = self.spk.to_core()?;
        let opk = self
            .opk
            .as_ref()
            .map(OneTimePreKeyPublic::to_core)
            .transpose()?;

        let invite_id: [u8; 16] = self.invite_id[..].try_into().map_err(|_| {
            ChencangError::InvalidLength(format!(
                "invite_id must be 16 bytes, got {}",
                self.invite_id.len()
            ))
        })?;
        let pairing_nonce: [u8; 16] = self.pairing_nonce[..].try_into().map_err(|_| {
            ChencangError::InvalidLength(format!(
                "pairing_nonce must be 16 bytes, got {}",
                self.pairing_nonce.len()
            ))
        })?;

        Ok(CoreBundle {
            ik,
            spk,
            opk,
            inviter_username: self.inviter_username.clone(),
            invite_id,
            pairing_nonce,
        })
    }
}

#[cfg(test)]
mod tests {
    use std::sync::Arc;

    use super::*;
    use crate::facade::identity::SecretIdentity;

    #[test]
    fn secret_spk_serialize_round_trip() {
        let ik = Arc::new(SecretIdentity::new());
        let spk = SecretSignedPreKey::new(ik, 42).expect("generate SPK");
        let original_pub = spk.public_form();

        let bytes = spk.serialize();
        let restored = SecretSignedPreKey::from_serialized(bytes).expect("deserialize SPK");
        let restored_pub = restored.public_form();

        assert_eq!(original_pub.spk_version, restored_pub.spk_version);
        assert_eq!(original_pub.spk_x25519, restored_pub.spk_x25519);
        assert_eq!(original_pub.spk_mlkem768, restored_pub.spk_mlkem768);
        assert_eq!(original_pub.ed25519_sig, restored_pub.ed25519_sig);
        assert_eq!(original_pub.mldsa65_sig, restored_pub.mldsa65_sig);
    }
}
