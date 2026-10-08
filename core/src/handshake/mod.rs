//! PQXDH-hybrid 握手。

pub mod ack_mac;
pub mod bundle;
pub mod classical_bundle;
pub mod inband_cbor;
pub mod key_confirm;
pub mod pqxdh;
pub mod x3dh;

pub use ack_mac::{compute_ack_mac, verify_ack_mac};
pub use bundle::PreKeyBundle;
pub use classical_bundle::{
    ClassicalOneTimePreKey, ClassicalPreKeyBundle, ClassicalPublicIdentity, ClassicalSignedPreKey,
};
pub use inband_cbor::{
    decode_classical_bundle, decode_classical_header, encode_classical_bundle,
    encode_classical_header, ClassicalInbandHeader,
};
pub use key_confirm::{
    confirm_tag, derive_kc, verify_confirm_tag, CONFIRM_WHO_INITIATOR, CONFIRM_WHO_RESPONDER,
};
pub use x3dh::{
    derive_initiator_classical, derive_responder_classical,
    InitiatorOutput as ClassicalInitiatorOutput, ResponderOutput as ClassicalResponderOutput,
    Srk as ClassicalSrk,
};
