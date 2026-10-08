//! Session facade. Wraps `chencang_core::session::Session` with shared-state
//! semantics so multiple FFI handles point at the same underlying ratchet.

use std::sync::Arc;

use parking_lot::Mutex;

use chencang_core::handshake::pqxdh::InitiatorOutput as CoreInitOut;
use chencang_core::primitives::{ml_kem, x25519};
use chencang_core::session::{Session as CoreSession, SessionState};

use super::error::{ChencangError, Result};
use super::handshake::InitiatorHandshakeOutput;
use super::identity::PublicIdentity;
use crate::rng::os_rng;

/// One Double-Ratchet session.
pub struct Session {
    inner: Arc<Mutex<CoreSession>>,
}

impl Session {
    /// Initiator (Bob) constructs Session after `derive_initiator_handshake`.
    ///
    /// `session_id` is the 5-byte stable session ID; `alice_ik_public` is the
    /// peer's identity bundle. `ek_x25519_secret` (32 bytes) and
    /// `ek_mlkem_secret` (2400 bytes) are the ephemeral secret keys generated
    /// during the handshake; they seed the send-direction ratchet slots so that
    /// the first responder→initiator ratchet step can complete.
    ///
    /// # Errors
    /// Returns [`ChencangError::InvalidLength`] on wrong-size inputs, and
    /// [`ChencangError::Decoding`] for malformed peer identity bundles.
    pub fn initiator_after_handshake(
        session_root_key: Vec<u8>,
        session_id: Vec<u8>,
        alice_ik_public: PublicIdentity,
        ek_x25519_secret: Vec<u8>,
        ek_mlkem_secret: Vec<u8>,
    ) -> Result<Self> {
        let srk: [u8; 32] = session_root_key.try_into().map_err(|v: Vec<u8>| {
            ChencangError::InvalidLength(format!("srk must be 32 bytes, got {}", v.len()))
        })?;
        let sid: [u8; 5] = session_id.try_into().map_err(|v: Vec<u8>| {
            ChencangError::InvalidLength(format!("session_id must be 5 bytes, got {}", v.len()))
        })?;
        let alice_pub = alice_ik_public.to_core()?;
        let ek_x_arr: [u8; 32] = ek_x25519_secret.try_into().map_err(|v: Vec<u8>| {
            ChencangError::InvalidLength(format!(
                "ek_x25519_secret must be 32 bytes, got {}",
                v.len()
            ))
        })?;
        let ek_kem_arr: [u8; ml_kem::SECRET_KEY_LEN] =
            ek_mlkem_secret.try_into().map_err(|v: Vec<u8>| {
                ChencangError::InvalidLength(format!(
                    "ek_mlkem_secret must be {} bytes, got {}",
                    ml_kem::SECRET_KEY_LEN,
                    v.len()
                ))
            })?;
        let ek_x_sk = x25519::SecretKey::from_bytes(ek_x_arr);
        let ek_kem_sk = ml_kem::SecretKey::from_bytes(ek_kem_arr);
        let sess = CoreSession::initiator_after_handshake(srk, sid, &alice_pub, ek_x_sk, ek_kem_sk);
        Ok(Self {
            inner: Arc::new(Mutex::new(sess)),
        })
    }

    /// Responder (Alice) constructs Session.
    ///
    /// # Errors
    /// Same length / decoding errors as
    /// [`Session::initiator_after_handshake`].
    pub fn responder_after_handshake(
        session_root_key: Vec<u8>,
        session_id: Vec<u8>,
        initiator_output: InitiatorHandshakeOutput,
    ) -> Result<Self> {
        let srk: [u8; 32] = session_root_key.try_into().map_err(|v: Vec<u8>| {
            ChencangError::InvalidLength(format!("srk must be 32 bytes, got {}", v.len()))
        })?;
        let sid: [u8; 5] = session_id.try_into().map_err(|v: Vec<u8>| {
            ChencangError::InvalidLength(format!("session_id must be 5 bytes, got {}", v.len()))
        })?;
        let core_io = io_to_core(&initiator_output)?;
        let sess = CoreSession::responder_after_handshake(srk, sid, &core_io);
        Ok(Self {
            inner: Arc::new(Mutex::new(sess)),
        })
    }

    /// Classical Initiator (Bob) constructs Session after
    /// `derive_initiator_classical` (suite 0x01). Takes only the X25519
    /// material: `alice_ik_dh_x25519` (peer's long-term DH public, 32 bytes;
    /// initial recv direction) and `ek_x25519_secret` (the ephemeral EK secret
    /// from the classical X3DH, 32 bytes; seeds the send ratchet). No ML-KEM.
    ///
    /// # Errors
    /// Returns [`ChencangError::InvalidLength`] on wrong-size inputs.
    pub fn initiator_after_handshake_classical(
        session_root_key: Vec<u8>,
        session_id: Vec<u8>,
        alice_ik_dh_x25519: Vec<u8>,
        ek_x25519_secret: Vec<u8>,
    ) -> Result<Self> {
        let srk: [u8; 32] = session_root_key.try_into().map_err(|v: Vec<u8>| {
            ChencangError::InvalidLength(format!("srk must be 32 bytes, got {}", v.len()))
        })?;
        let sid: [u8; 5] = session_id.try_into().map_err(|v: Vec<u8>| {
            ChencangError::InvalidLength(format!("session_id must be 5 bytes, got {}", v.len()))
        })?;
        let alice_dh: [u8; 32] = alice_ik_dh_x25519.try_into().map_err(|v: Vec<u8>| {
            ChencangError::InvalidLength(format!(
                "alice_ik_dh_x25519 must be 32 bytes, got {}",
                v.len()
            ))
        })?;
        let ek_x: [u8; 32] = ek_x25519_secret.try_into().map_err(|v: Vec<u8>| {
            ChencangError::InvalidLength(format!(
                "ek_x25519_secret must be 32 bytes, got {}",
                v.len()
            ))
        })?;
        let sess = CoreSession::initiator_after_handshake_classical(
            srk,
            sid,
            x25519::PublicKey32(alice_dh),
            x25519::SecretKey::from_bytes(ek_x),
        );
        Ok(Self {
            inner: Arc::new(Mutex::new(sess)),
        })
    }

    /// Classical Responder (Alice) constructs Session (suite 0x01). Takes only
    /// `bob_ek_x25519_pub` (Bob's ephemeral EK public from the classical X3DH,
    /// 32 bytes; initial recv direction). No ML-KEM.
    ///
    /// # Errors
    /// Returns [`ChencangError::InvalidLength`] on wrong-size inputs.
    pub fn responder_after_handshake_classical(
        session_root_key: Vec<u8>,
        session_id: Vec<u8>,
        bob_ek_x25519_pub: Vec<u8>,
    ) -> Result<Self> {
        let srk: [u8; 32] = session_root_key.try_into().map_err(|v: Vec<u8>| {
            ChencangError::InvalidLength(format!("srk must be 32 bytes, got {}", v.len()))
        })?;
        let sid: [u8; 5] = session_id.try_into().map_err(|v: Vec<u8>| {
            ChencangError::InvalidLength(format!("session_id must be 5 bytes, got {}", v.len()))
        })?;
        let bob_ek: [u8; 32] = bob_ek_x25519_pub.try_into().map_err(|v: Vec<u8>| {
            ChencangError::InvalidLength(format!(
                "bob_ek_x25519_pub must be 32 bytes, got {}",
                v.len()
            ))
        })?;
        let sess =
            CoreSession::responder_after_handshake_classical(srk, sid, x25519::PublicKey32(bob_ek));
        Ok(Self {
            inner: Arc::new(Mutex::new(sess)),
        })
    }

    /// V1 entry point: encrypt plaintext, return raw L3 ciphertext bytes.
    ///
    /// Callers wrap the returned bytes in the L4 wire string by passing
    /// them to [`super::payload::encode_wire`].
    ///
    /// # Errors
    /// Returns [`ChencangError::Internal`] only if the underlying AEAD seal
    /// fails (should not occur with correct inputs).
    pub fn encrypt_to_bytes(&self, plaintext: Vec<u8>) -> Result<Vec<u8>> {
        let mut rng = os_rng();
        let mut g = self.inner.lock();
        g.encrypt_to_bytes(&plaintext, &mut rng).map_err(Into::into)
    }

    /// V1 entry point: decrypt raw L3 ciphertext bytes.
    ///
    /// # Errors
    /// [`ChencangError::Decoding`] on parse failure,
    /// [`ChencangError::AeadFailed`] on tamper / wrong key.
    pub fn decrypt_from_bytes(&self, ciphertext: Vec<u8>) -> Result<Vec<u8>> {
        let mut g = self.inner.lock();
        g.decrypt_from_bytes(&ciphertext).map_err(Into::into)
    }

    /// Legacy V0 entry point: encrypt plaintext, return the z-base32 wire
    /// string. Retained only so the facade round-trip integration test
    /// keeps compiling; **new code MUST use [`Self::encrypt_to_bytes`]**.
    ///
    /// # Errors
    /// Returns [`ChencangError::Internal`] only if the underlying AEAD seal
    /// fails (should not occur with correct inputs).
    pub fn encrypt(&self, plaintext: Vec<u8>) -> Result<String> {
        let mut rng = os_rng();
        let mut g = self.inner.lock();
        g.encrypt(&plaintext, &mut rng).map_err(Into::into)
    }

    /// Legacy V0 entry point: decrypt a z-base32 wire string. Retained
    /// only for the facade round-trip integration test; **new code MUST
    /// use [`Self::decrypt_from_bytes`]**.
    ///
    /// # Errors
    /// [`ChencangError::Decoding`] on parse failure,
    /// [`ChencangError::AeadFailed`] on tamper / wrong key.
    pub fn decrypt(&self, wire_text: String) -> Result<Vec<u8>> {
        let mut g = self.inner.lock();
        g.decrypt(&wire_text).map_err(Into::into)
    }

    /// Serialize ratchet state for local (encrypted-at-rest) persistence.
    #[must_use]
    pub fn serialize_state(&self) -> Vec<u8> {
        self.inner.lock().state.serialize()
    }

    /// Reconstruct a Session from `serialize_state()` output.
    ///
    /// # Errors
    /// Returns [`ChencangError::Decoding`] when the state blob is malformed.
    pub fn from_serialized_state(state: Vec<u8>) -> Result<Self> {
        let parsed = SessionState::deserialize(&state)?;
        Ok(Self {
            inner: Arc::new(Mutex::new(CoreSession { state: parsed })),
        })
    }
}

fn io_to_core(io: &InitiatorHandshakeOutput) -> Result<CoreInitOut> {
    let bob_ik_pub = io.bob_identity_public.to_core()?;

    let ek_x: [u8; 32] = io.ek_x25519_pub[..].try_into().map_err(|_| {
        ChencangError::InvalidLength(format!(
            "ek_x25519_pub must be 32 bytes, got {}",
            io.ek_x25519_pub.len()
        ))
    })?;
    let ek_kem_arr: [u8; ml_kem::PUBLIC_KEY_LEN] =
        io.ek_mlkem_pub[..].try_into().map_err(|_| {
            ChencangError::InvalidLength(format!(
                "ek_mlkem_pub must be {} bytes, got {}",
                ml_kem::PUBLIC_KEY_LEN,
                io.ek_mlkem_pub.len()
            ))
        })?;
    // The secret keys are only needed on the initiator side (via
    // `initiator_after_handshake`).  The responder reconstructs this record
    // from wire data and never reads ek_*_secret; provide zeroed placeholders
    // so the struct is complete without transmitting the secrets. The
    // `transcript` field is likewise initiator-only handshake-derivation
    // state — `responder_after_handshake` only reads the ek pubkeys — so a
    // zeroed placeholder is correct on this reconstruction path.
    Ok(CoreInitOut {
        bob_ik_pub,
        ek_x25519_pub: x25519::PublicKey32(ek_x),
        ek_mlkem_pub: ml_kem::PublicKey::from_bytes(ek_kem_arr),
        kem_ct_to_spk: io.kem_ct_to_spk.clone(),
        kem_ct_to_ik: io.kem_ct_to_ik.clone(),
        kem_ct_to_opk: io.kem_ct_to_opk.clone(),
        ek_x25519_secret: x25519::SecretKey::from_bytes([0u8; 32]),
        ek_mlkem_secret: ml_kem::SecretKey::from_bytes([0u8; ml_kem::SECRET_KEY_LEN]),
        transcript: [0u8; 32],
    })
}
