//! Cross-language facade. Every type here corresponds to an entry in
//! `chencang.udl`; their public method signatures must match what uniffi
//! expects from the UDL definition.

pub mod blob;
pub mod config;
pub mod error;
pub mod handshake;
pub mod identity;
pub mod payload;
pub mod prekey;
pub mod safety;
pub mod session;
pub mod x3dh;

pub use blob::{decrypt_media_blob, encrypt_media_blob, media_blob_id, MediaBlob};
pub use config::verify_signed_config;
pub use error::ChencangError;
pub use handshake::{
    derive_initiator_handshake, derive_responder_handshake, InitiatorHandshakeOutput,
};
pub use identity::{
    fingerprint_of_public, generate_secret_identity, Fingerprint, PublicIdentity, SecretIdentity,
};
pub use payload::{
    decode_frame, decode_wire, derive_blob_material, encode_media_ref_frame, encode_text_frame,
    encode_wire, BlobMaterial, DecodedMessage, MediaRef,
};
pub use prekey::{
    OneTimePreKeyPublic, PreKeyBundle, SecretOneTimePreKey, SecretSignedPreKey, SignedPreKeyPublic,
};
pub use safety::derive_safety_emoji;
pub use session::Session;
pub use x3dh::{
    compute_confirm_tag, confirm_who_initiator, confirm_who_responder, decode_classical_bundle,
    decode_classical_header, derive_initiator_classical, derive_responder_classical,
    encode_classical_bundle, encode_classical_header, verify_confirm_tag, ClassicalInbandHeader,
    ClassicalInitiatorOutput, ClassicalOneTimePreKey, ClassicalPreKeyBundle,
    ClassicalPublicIdentity, ClassicalResponderOutput, ClassicalSignedPreKey,
};
