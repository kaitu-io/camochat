//! PQXDH handshake facade. Exposes the two halves of the handshake as
//! free functions (matching the UDL `namespace chencang { ... }` block).

use std::sync::Arc;

use chencang_core::handshake::pqxdh;
use chencang_core::identity::keypair::PublicIdentity as CorePubId;
use chencang_core::primitives::{ml_kem, x25519};

use super::error::{ChencangError, Result};
use super::identity::{PublicIdentity, SecretIdentity};
use super::prekey::{PreKeyBundle, SecretOneTimePreKey, SecretSignedPreKey};
use crate::rng::os_rng;

/// Output of the initiator (Bob) side of a PQXDH-hybrid handshake — everything
/// the initiator sends to the responder, plus the derived session root key.
#[derive(Clone)]
pub struct InitiatorHandshakeOutput {
    /// 32-byte session root key (kept locally; do *not* transmit).
    pub session_root_key: Vec<u8>,
    /// The initiator's own 4-key public identity (echoed for the responder).
    pub bob_identity_public: PublicIdentity,
    /// Ephemeral X25519 public key (32 bytes).
    pub ek_x25519_pub: Vec<u8>,
    /// Ephemeral ML-KEM-768 public key (1184 bytes).
    pub ek_mlkem_pub: Vec<u8>,
    /// ML-KEM ciphertext encapsulating to responder's SPK (1088 bytes).
    pub kem_ct_to_spk: Vec<u8>,
    /// ML-KEM ciphertext encapsulating to responder's IK (1088 bytes).
    pub kem_ct_to_ik: Vec<u8>,
    /// ML-KEM ciphertext encapsulating to responder's OPK, if one was used.
    pub kem_ct_to_opk: Option<Vec<u8>>,
    /// Ephemeral X25519 secret key (32 bytes). Populated by the initiator;
    /// null when this record is reconstructed on the responder side.
    pub ek_x25519_secret: Option<Vec<u8>>,
    /// Ephemeral ML-KEM-768 secret key (2400 bytes). Populated by the
    /// initiator; null when reconstructed on the responder side.
    pub ek_mlkem_secret: Option<Vec<u8>>,
}

/// Initiator (Bob) side. Returns the derived SRK plus all material to send.
///
/// # Errors
/// Returns [`ChencangError::InvalidLength`] / [`ChencangError::Decoding`] if
/// the inbound `alice_bundle` fields don't decode.
pub fn derive_initiator_handshake(
    bob_ik: Arc<SecretIdentity>,
    alice_bundle: PreKeyBundle,
) -> Result<InitiatorHandshakeOutput> {
    let mut rng = os_rng();
    let bundle = alice_bundle.to_core()?;
    let bob_guard = bob_ik.lock();
    let (srk, out) = pqxdh::derive_initiator(&mut rng, &bob_guard, &bundle);
    drop(bob_guard);
    // Extract secret key bytes before moving out of `out`.
    let ek_x25519_secret_bytes = out.ek_x25519_secret.to_bytes().to_vec();
    let ek_mlkem_secret_bytes = out.ek_mlkem_secret.to_bytes().to_vec();
    Ok(InitiatorHandshakeOutput {
        session_root_key: srk.to_vec(),
        bob_identity_public: pub_to_facade(&out.bob_ik_pub),
        ek_x25519_pub: out.ek_x25519_pub.0.to_vec(),
        ek_mlkem_pub: out.ek_mlkem_pub.as_bytes().to_vec(),
        kem_ct_to_spk: out.kem_ct_to_spk,
        kem_ct_to_ik: out.kem_ct_to_ik,
        kem_ct_to_opk: out.kem_ct_to_opk,
        ek_x25519_secret: Some(ek_x25519_secret_bytes),
        ek_mlkem_secret: Some(ek_mlkem_secret_bytes),
    })
}

/// Responder (Alice) side. Returns the derived SRK as raw bytes.
///
/// # Errors
/// Returns [`ChencangError::InvalidLength`] on wrong-length inputs, or
/// [`ChencangError::Decoding`] on KEM decapsulation failure.
// `clippy::similar_names`: cryptographic role labels are protocol-defined.
// `clippy::too_many_arguments`: the responder API mirrors the PQXDH-hybrid
// message shape; merging into a struct would just hide the wire layout.
#[allow(clippy::too_many_arguments, clippy::similar_names)]
pub fn derive_responder_handshake(
    alice_ik: Arc<SecretIdentity>,
    alice_spk: Arc<SecretSignedPreKey>,
    alice_opk: Option<Arc<SecretOneTimePreKey>>,
    bob_ik_pub: PublicIdentity,
    bob_ek_x_pub: Vec<u8>,
    bob_ek_kem_pub: Vec<u8>,
    kem_ct_to_spk: Vec<u8>,
    kem_ct_to_ik: Vec<u8>,
    kem_ct_to_opk: Option<Vec<u8>>,
    pairing_nonce: Vec<u8>,
) -> Result<Vec<u8>> {
    let bob_pub = bob_ik_pub.to_core()?;

    let pairing_nonce: [u8; 16] = pairing_nonce[..].try_into().map_err(|_| {
        ChencangError::InvalidLength(format!(
            "pairing_nonce must be 16 bytes, got {}",
            pairing_nonce.len()
        ))
    })?;

    let ek_x: [u8; 32] = bob_ek_x_pub[..].try_into().map_err(|_| {
        ChencangError::InvalidLength(format!(
            "bob_ek_x_pub must be 32 bytes, got {}",
            bob_ek_x_pub.len()
        ))
    })?;
    let ek_kem_arr: [u8; ml_kem::PUBLIC_KEY_LEN] = bob_ek_kem_pub[..].try_into().map_err(|_| {
        ChencangError::InvalidLength(format!(
            "bob_ek_kem_pub must be {} bytes, got {}",
            ml_kem::PUBLIC_KEY_LEN,
            bob_ek_kem_pub.len()
        ))
    })?;
    let ek_kem = ml_kem::PublicKey::from_bytes(ek_kem_arr);
    let ek_x = x25519::PublicKey32(ek_x);

    let ik_guard = alice_ik.lock();
    let spk_guard = alice_spk.lock();
    let opk_guard = alice_opk.as_ref().map(|o| o.lock());
    let opk_ref = opk_guard.as_deref();

    let out = pqxdh::derive_responder(
        &ik_guard,
        &spk_guard,
        opk_ref,
        &bob_pub,
        &ek_x,
        &ek_kem,
        &kem_ct_to_spk,
        &kem_ct_to_ik,
        kem_ct_to_opk.as_deref(),
        &pairing_nonce,
    )?;

    Ok(out.srk.to_vec())
}

pub(crate) fn pub_to_facade(p: &CorePubId) -> PublicIdentity {
    PublicIdentity {
        ik_dh_x25519: p.ik_dh_x25519.0.to_vec(),
        ik_sig_ed25519: p.ik_sig_ed25519.to_bytes().to_vec(),
        ik_kem_mlkem768: p.ik_kem_mlkem768.as_bytes().to_vec(),
        ik_sig_mldsa65: p.ik_sig_mldsa65.to_bytes().to_vec(),
    }
}
