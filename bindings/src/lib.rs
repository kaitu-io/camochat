//! Chencang FFI bindings — exposes chencang-core to Swift and Kotlin via uniffi-rs.
//!
//! Note: `unsafe_code` is allowed because uniffi's generated scaffolding emits
//! `#[unsafe(no_mangle)]` extern "C" functions. Our own facade code MUST NOT
//! contain hand-written unsafe blocks; reviewers should verify this by
//! grepping `unsafe` outside the generated scaffolding under `target/`.
#![warn(clippy::pedantic)]
#![allow(clippy::missing_errors_doc)] // doc'd at the type-level in modules.
#![allow(clippy::needless_pass_by_value)] // uniffi-generated wrappers move values.
#![allow(clippy::large_const_arrays)] // uniffi-generated scaffolding emits a large const metadata array.

mod facade;
mod rng;

pub use facade::{
    compute_confirm_tag, confirm_who_initiator, confirm_who_responder, decode_classical_bundle,
    decode_classical_header, decode_frame, decode_wire, decrypt_media_blob, derive_blob_material,
    derive_initiator_classical, derive_initiator_handshake, derive_responder_classical,
    derive_responder_handshake, derive_safety_emoji, encode_classical_bundle,
    encode_classical_header, encode_media_ref_frame, encode_text_frame, encode_wire,
    encrypt_media_blob, fingerprint_of_public, generate_secret_identity, media_blob_id,
    verify_confirm_tag, verify_signed_config, BlobMaterial, ChencangError, ClassicalInbandHeader,
    ClassicalInitiatorOutput, ClassicalOneTimePreKey, ClassicalPreKeyBundle,
    ClassicalPublicIdentity, ClassicalResponderOutput, ClassicalSignedPreKey, DecodedMessage,
    Fingerprint, InitiatorHandshakeOutput, MediaBlob, MediaRef, OneTimePreKeyPublic, PreKeyBundle,
    PublicIdentity, SecretIdentity, SecretOneTimePreKey, SecretSignedPreKey, Session,
    SignedPreKeyPublic,
};

uniffi::include_scaffolding!("chencang");
