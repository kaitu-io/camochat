//! Wire format encoding.

pub mod aad;
pub mod header;
pub mod parse;

pub use aad::build_aad;
pub use header::{
    Header, KemRatchetData, FIXED_HEADER_LEN, FLAG_DH_RATCHET, FLAG_KEM_RATCHET, MAGIC,
    SUITE_CLASSICAL_V1, SUITE_PQ_HYBRID_V1, VERSION_V1,
};
pub use parse::{decode, encode};
