//! Classical X3DH facade (suite 0x01: X25519 + Ed25519, no post-quantum).
//!
//! Mirrors the PQ [`super::handshake`] module but for the in-band classical
//! pairing path. Exposes the bundle types, the two halves of the X3DH, and the
//! key-confirmation MAC as free functions (matching the UDL `namespace`).
//!
//! Byte arrays cross the FFI boundary as `Vec<u8>`; the derived
//! `session_root_key` / `ek_x25519_secret` are secrets that flow through the
//! facade exactly like the PQ path — they are NEVER logged.

use std::sync::Arc;

use chencang_core::handshake::classical_bundle::{
    ClassicalOneTimePreKey as CoreClOpk, ClassicalPreKeyBundle as CoreClBundle,
    ClassicalPublicIdentity as CoreClPubId, ClassicalSignedPreKey as CoreClSpk,
};
use chencang_core::handshake::inband_cbor::{self, ClassicalInbandHeader as CoreClHeader};
use chencang_core::handshake::key_confirm::{
    confirm_tag, derive_kc, verify_confirm_tag as core_verify_confirm_tag, CONFIRM_WHO_INITIATOR,
    CONFIRM_WHO_RESPONDER,
};
use chencang_core::handshake::x3dh;
use chencang_core::identity::keypair::PublicIdentity as CorePubId;
use chencang_core::primitives::{ed25519, ml_dsa, ml_kem, x25519};

use super::error::{ChencangError, Result};
use super::identity::{PublicIdentity, SecretIdentity};
use super::prekey::{SecretOneTimePreKey, SecretSignedPreKey};
use crate::rng::os_rng;

/// Classical identity: Ed25519 signing key + X25519 DH key (32 bytes each).
#[derive(Clone)]
pub struct ClassicalPublicIdentity {
    /// Ed25519 verifying key (identity anchor + SPK-signature verifier), 32 bytes.
    pub ed25519: Vec<u8>,
    /// X25519 long-term DH public key (X3DH), 32 bytes.
    pub x25519: Vec<u8>,
}

impl ClassicalPublicIdentity {
    fn to_core(&self) -> Result<CoreClPubId> {
        Ok(CoreClPubId {
            ed25519: to_arr32(&self.ed25519, "classical ik.ed25519")?,
            x25519: to_arr32(&self.x25519, "classical ik.x25519")?,
        })
    }

    fn from_core(c: &CoreClPubId) -> Self {
        Self {
            ed25519: c.ed25519.to_vec(),
            x25519: c.x25519.to_vec(),
        }
    }

    /// Reconstruct a full `PublicIdentity` from the classical two-key set,
    /// zero-filling the post-quantum fields. The classical X3DH responder only
    /// reads `ik_sig_ed25519` + `ik_dh_x25519` (see `x3dh::build_transcript`),
    /// so the zeroed ML-KEM/ML-DSA placeholders are never observed. `from_bytes`
    /// for both PQ types is length-only (no cryptographic validation), so the
    /// zero placeholders construct cleanly.
    fn to_full_core(&self) -> Result<CorePubId> {
        let ed = to_arr32(&self.ed25519, "classical ik.ed25519")?;
        let dh = to_arr32(&self.x25519, "classical ik.x25519")?;
        let dsa = ml_dsa::PublicKey::from_bytes(&[0u8; ml_dsa::PUBLIC_KEY_LEN])?;
        Ok(CorePubId::from_components(
            x25519::PublicKey32(dh),
            ed25519::VerifyingKey(ed),
            ml_kem::PublicKey::from_bytes([0u8; ml_kem::PUBLIC_KEY_LEN]),
            dsa,
        ))
    }
}

/// Classical signed prekey: X25519 public key + classical Ed25519 signature
/// (covers `x25519 || epoch`) + epoch.
#[derive(Clone)]
pub struct ClassicalSignedPreKey {
    /// X25519 SPK public key, 32 bytes.
    pub x25519: Vec<u8>,
    /// Classical Ed25519 signature over `(x25519 || epoch)`, 64 bytes.
    pub sig_ed25519: Vec<u8>,
    /// Rotation epoch counter.
    pub epoch: u32,
}

impl ClassicalSignedPreKey {
    fn to_core(&self) -> Result<CoreClSpk> {
        let sig: [u8; 64] = self.sig_ed25519[..].try_into().map_err(|_| {
            ChencangError::InvalidLength(format!(
                "classical spk.sig_ed25519 must be 64 bytes, got {}",
                self.sig_ed25519.len()
            ))
        })?;
        Ok(CoreClSpk {
            x25519: to_arr32(&self.x25519, "classical spk.x25519")?,
            sig_ed25519: sig,
            epoch: self.epoch,
        })
    }

    fn from_core(c: &CoreClSpk) -> Self {
        Self {
            x25519: c.x25519.to_vec(),
            sig_ed25519: c.sig_ed25519.to_vec(),
            epoch: c.epoch,
        }
    }
}

/// Classical one-time prekey: X25519 + id (not separately signed; tamper is
/// caught via transcript binding).
#[derive(Clone)]
pub struct ClassicalOneTimePreKey {
    /// OPK id.
    pub id: u32,
    /// X25519 one-time prekey public key, 32 bytes.
    pub x25519: Vec<u8>,
}

impl ClassicalOneTimePreKey {
    fn to_core(&self) -> Result<CoreClOpk> {
        Ok(CoreClOpk {
            id: self.id,
            x25519: to_arr32(&self.x25519, "classical opk.x25519")?,
        })
    }

    fn from_core(c: &CoreClOpk) -> Self {
        Self {
            id: c.id,
            x25519: c.x25519.to_vec(),
        }
    }
}

/// Classical in-band prekey bundle (Alice -> Bob).
#[derive(Clone)]
pub struct ClassicalPreKeyBundle {
    /// Protocol version (must be 0x01).
    pub version: u8,
    /// Suite id (must be 0x01).
    pub suite_id: u8,
    /// Alice's classical identity public keys.
    pub ik: ClassicalPublicIdentity,
    /// Alice's classical signed prekey.
    pub spk: ClassicalSignedPreKey,
    /// Optional one-time prekey.
    pub opk: Option<ClassicalOneTimePreKey>,
    /// 16-byte pairing nonce.
    pub pairing_nonce: Vec<u8>,
    /// Inviter username (AAD binding + UI display).
    pub inviter_username: String,
}

impl ClassicalPreKeyBundle {
    fn to_core(&self) -> Result<CoreClBundle> {
        let nonce: [u8; 16] = self.pairing_nonce[..].try_into().map_err(|_| {
            ChencangError::InvalidLength(format!(
                "classical pairing_nonce must be 16 bytes, got {}",
                self.pairing_nonce.len()
            ))
        })?;
        Ok(CoreClBundle {
            version: self.version,
            suite_id: self.suite_id,
            ik: self.ik.to_core()?,
            spk: self.spk.to_core()?,
            opk: self
                .opk
                .as_ref()
                .map(ClassicalOneTimePreKey::to_core)
                .transpose()?,
            pairing_nonce: nonce,
            inviter_username: self.inviter_username.clone(),
        })
    }

    fn from_core(c: &CoreClBundle) -> Self {
        Self {
            version: c.version,
            suite_id: c.suite_id,
            ik: ClassicalPublicIdentity::from_core(&c.ik),
            spk: ClassicalSignedPreKey::from_core(&c.spk),
            opk: c.opk.as_ref().map(ClassicalOneTimePreKey::from_core),
            pairing_nonce: c.pairing_nonce.to_vec(),
            inviter_username: c.inviter_username.clone(),
        }
    }
}

/// Classical in-band handshake header (Bob -> Alice, Round-2).
#[derive(Clone)]
pub struct ClassicalInbandHeader {
    /// Protocol version (must be 0x01).
    pub version: u8,
    /// Suite id (must be 0x01).
    pub suite_id: u8,
    /// Bob's classical identity public keys.
    pub bob_ik: ClassicalPublicIdentity,
    /// Bob's ephemeral X25519 public key (32 bytes).
    pub ek_x25519_pub: Vec<u8>,
    /// 5-byte short session id.
    pub session_id: Vec<u8>,
    /// Bob's (initiator's) 32-byte key-confirmation tag.
    pub confirm_b: Vec<u8>,
    /// Optional Bob display name (UI).
    pub bob_display_name: Option<String>,
}

impl ClassicalInbandHeader {
    fn to_core(&self) -> Result<CoreClHeader> {
        let session_id: [u8; 5] = self.session_id[..].try_into().map_err(|_| {
            ChencangError::InvalidLength(format!(
                "session_id must be 5 bytes, got {}",
                self.session_id.len()
            ))
        })?;
        let confirm_b: [u8; 32] = self.confirm_b[..].try_into().map_err(|_| {
            ChencangError::InvalidLength(format!(
                "confirm_b must be 32 bytes, got {}",
                self.confirm_b.len()
            ))
        })?;
        Ok(CoreClHeader {
            version: self.version,
            suite_id: self.suite_id,
            bob_ik: self.bob_ik.to_core()?,
            ek_x25519_pub: to_arr32(&self.ek_x25519_pub, "ek_x25519_pub")?,
            session_id,
            confirm_b,
            bob_display_name: self.bob_display_name.clone(),
        })
    }

    fn from_core(c: &CoreClHeader) -> Self {
        Self {
            version: c.version,
            suite_id: c.suite_id,
            bob_ik: ClassicalPublicIdentity::from_core(&c.bob_ik),
            ek_x25519_pub: c.ek_x25519_pub.to_vec(),
            session_id: c.session_id.to_vec(),
            confirm_b: c.confirm_b.to_vec(),
            bob_display_name: c.bob_display_name.clone(),
        }
    }
}

/// Encode a classical prekey bundle to deterministic integer-key CBOR
/// (Round-1, Alice -> Bob over the untrusted channel).
///
/// # Errors
/// Returns [`ChencangError::InvalidLength`] if any fixed-length field of the
/// facade `bundle` (keys, nonce) has the wrong length.
pub fn encode_classical_bundle(bundle: ClassicalPreKeyBundle) -> Result<Vec<u8>> {
    let core = bundle.to_core()?;
    Ok(inband_cbor::encode_classical_bundle(&core))
}

/// Decode a classical prekey bundle from integer-key CBOR.
///
/// # Errors
/// Returns [`ChencangError::Decoding`] / [`ChencangError::InvalidLength`] on
/// malformed CBOR or wrong-length fields.
pub fn decode_classical_bundle(bytes: Vec<u8>) -> Result<ClassicalPreKeyBundle> {
    let core = inband_cbor::decode_classical_bundle(&bytes)?;
    Ok(ClassicalPreKeyBundle::from_core(&core))
}

/// Encode a classical in-band header to deterministic integer-key CBOR
/// (Round-2, Bob -> Alice).
///
/// # Errors
/// Returns [`ChencangError::InvalidLength`] if `session_id`/`confirm_b`/
/// `ek_x25519_pub`/`bob_ik` fields have the wrong length.
pub fn encode_classical_header(header: ClassicalInbandHeader) -> Result<Vec<u8>> {
    let core = header.to_core()?;
    Ok(inband_cbor::encode_classical_header(&core))
}

/// Decode a classical in-band header from integer-key CBOR.
///
/// # Errors
/// Returns [`ChencangError::Decoding`] / [`ChencangError::InvalidLength`] on
/// malformed CBOR or wrong-length fields.
pub fn decode_classical_header(bytes: Vec<u8>) -> Result<ClassicalInbandHeader> {
    let core = inband_cbor::decode_classical_header(&bytes)?;
    Ok(ClassicalInbandHeader::from_core(&core))
}

/// Output of the initiator (Bob) side of a classical X3DH — everything the
/// initiator keeps locally plus the material echoed to the responder.
#[derive(Clone)]
pub struct ClassicalInitiatorOutput {
    /// 32-byte session root key (kept locally; do *not* transmit).
    pub session_root_key: Vec<u8>,
    /// The initiator's own 4-key public identity (echoed to the responder; the
    /// responder reads only the classical ed25519+x25519 subset).
    pub bob_ik: PublicIdentity,
    /// Ephemeral X25519 public key (32 bytes) — sent to the responder.
    pub ek_x25519_pub: Vec<u8>,
    /// Ephemeral X25519 secret key (32 bytes) — kept local; seeds the send
    /// ratchet via `Session::initiator_after_handshake_classical`.
    pub ek_x25519_secret: Vec<u8>,
    /// 32-byte handshake transcript hash (bound into SRK + used for key
    /// confirmation).
    pub transcript: Vec<u8>,
}

/// Output of the responder (Alice) side — both the derived SRK and the
/// transcript (so the client can compute/verify the confirmation tags).
#[derive(Clone)]
pub struct ClassicalResponderOutput {
    /// 32-byte session root key.
    pub session_root_key: Vec<u8>,
    /// 32-byte handshake transcript hash (byte-identical to the initiator's).
    pub transcript: Vec<u8>,
}

/// Initiator (Bob) side. Returns the derived SRK plus all material to send.
///
/// # Errors
/// Returns [`ChencangError::InvalidLength`] if any `alice_bundle` field has the
/// wrong length.
pub fn derive_initiator_classical(
    bob_ik: Arc<SecretIdentity>,
    alice_bundle: ClassicalPreKeyBundle,
) -> Result<ClassicalInitiatorOutput> {
    let mut rng = os_rng();
    let bundle = alice_bundle.to_core()?;
    let bob_guard = bob_ik.lock();
    let (srk, out) = x3dh::derive_initiator_classical(&mut rng, &bob_guard, &bundle);
    drop(bob_guard);
    Ok(ClassicalInitiatorOutput {
        session_root_key: srk.to_vec(),
        bob_ik: super::handshake::pub_to_facade(&out.bob_ik_pub),
        ek_x25519_pub: out.ek_x25519_pub.0.to_vec(),
        ek_x25519_secret: out.ek_x25519_secret.to_bytes().to_vec(),
        transcript: out.transcript.to_vec(),
    })
}

/// Responder (Alice) side. Returns the derived SRK + transcript.
///
/// # Errors
/// Returns [`ChencangError::InvalidLength`] on wrong-length inputs.
// `clippy::too_many_arguments`: the responder API mirrors the classical X3DH
// message shape; merging into a struct would just hide the protocol fields.
// `clippy::similar_names`: `bob_ik` / `bob_ek` are protocol-defined role labels.
#[allow(clippy::too_many_arguments, clippy::similar_names)]
pub fn derive_responder_classical(
    alice_ik: Arc<SecretIdentity>,
    alice_spk: Arc<SecretSignedPreKey>,
    alice_opk: Option<Arc<SecretOneTimePreKey>>,
    bob_ik: ClassicalPublicIdentity,
    bob_ek_x25519_pub: Vec<u8>,
    opk_id: Option<u32>,
    pairing_nonce: Vec<u8>,
) -> Result<ClassicalResponderOutput> {
    let bob_full = bob_ik.to_full_core()?;
    let bob_ek = x25519::PublicKey32(to_arr32(&bob_ek_x25519_pub, "bob_ek_x25519_pub")?);
    let nonce: [u8; 16] = pairing_nonce[..].try_into().map_err(|_| {
        ChencangError::InvalidLength(format!(
            "pairing_nonce must be 16 bytes, got {}",
            pairing_nonce.len()
        ))
    })?;

    let ik_guard = alice_ik.lock();
    let spk_guard = alice_spk.lock();
    let opk_guard = alice_opk.as_ref().map(|o| o.lock());
    let opk_ref = opk_guard.as_deref();

    let out = x3dh::derive_responder_classical(
        &ik_guard, &spk_guard, opk_ref, &bob_full, &bob_ek, opk_id, &nonce,
    )?;
    drop(opk_guard);
    drop(spk_guard);
    drop(ik_guard);

    Ok(ClassicalResponderOutput {
        session_root_key: out.srk.to_vec(),
        transcript: out.transcript.to_vec(),
    })
}

/// Compute a key-confirmation tag: `confirm_tag(derive_kc(srk), transcript, who)`.
///
/// # Errors
/// Returns [`ChencangError::InvalidLength`] if `srk` or `transcript` are not 32
/// bytes.
pub fn compute_confirm_tag(srk: Vec<u8>, transcript: Vec<u8>, who: u8) -> Result<Vec<u8>> {
    let srk = to_arr32(&srk, "srk")?;
    let transcript = to_arr32(&transcript, "transcript")?;
    let kc = derive_kc(&srk);
    Ok(confirm_tag(&kc, &transcript, who).to_vec())
}

/// Constant-time verify a key-confirmation tag against `(srk, transcript, who)`.
///
/// # Errors
/// Returns [`ChencangError::InvalidLength`] if `srk`/`transcript`/`tag` are not
/// 32 bytes.
pub fn verify_confirm_tag(
    srk: Vec<u8>,
    transcript: Vec<u8>,
    who: u8,
    tag: Vec<u8>,
) -> Result<bool> {
    let srk = to_arr32(&srk, "srk")?;
    let transcript = to_arr32(&transcript, "transcript")?;
    let tag = to_arr32(&tag, "tag")?;
    let kc = derive_kc(&srk);
    Ok(core_verify_confirm_tag(&kc, &transcript, who, &tag))
}

/// The `who` byte for the INITIATOR's (Bob's) confirmation tag (`confirm_b`).
#[must_use]
pub fn confirm_who_initiator() -> u8 {
    CONFIRM_WHO_INITIATOR
}

/// The `who` byte for the RESPONDER's (Alice's) confirmation tag (`confirm_a`).
#[must_use]
pub fn confirm_who_responder() -> u8 {
    CONFIRM_WHO_RESPONDER
}

/// Convert a byte slice into a `[u8; 32]`, mapping length mismatch to a
/// descriptive [`ChencangError::InvalidLength`].
fn to_arr32(bytes: &[u8], field: &str) -> Result<[u8; 32]> {
    bytes.try_into().map_err(|_| {
        ChencangError::InvalidLength(format!("{} must be 32 bytes, got {}", field, bytes.len()))
    })
}
