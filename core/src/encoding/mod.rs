//! 文本编码 / 解码。
//!
//! - [`base32768`] — superseded wire encoding (qntm alphabet); retained for
//!   reference and golden-vector tooling.
//! - [`cjk14`] — V1 wire L4 outer text encoding (CJK-only 14-bit).
//! - [`zbase32`] — legacy V0 wire text encoding; retained because
//!   `wire::parse::encode/decode` still expose a z-base32 path for
//!   non-V1 tests and golden-vector tooling, but the V1 voice pipeline
//!   no longer uses it.

pub mod base32768;
pub mod cjk14;
pub mod zbase32;
