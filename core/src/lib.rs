//! # chencang-core
//!
//! 陈仓加密输入法 V1 协议核心库。
//!
//! 这个 crate 实现了 PQXDH-hybrid 握手 + Double Ratchet（含周期性 PQ 棘轮）+ wire format
//! 编解码。所有密码学操作通过此 crate 暴露，平台代码（iOS / Android）通过 uniffi 调用。
//!
//! ## 主要模块
//!
//! - [`primitives`] — 经过审计的底层密码学原语包装
//! - [`encoding`] — Base32768 (V1) + z-base32 (V0/legacy) text codecs
//! - [`identity`] — 长期身份密钥（IK 四件套）
//! - [`prekey`] — 中期签名预密钥 + 一次性预密钥池
//! - [`handshake`] — PQXDH-hybrid 握手
//! - [`session`] — Double Ratchet 会话状态机
//! - [`wire`] — DR ciphertext binary header + (legacy) string codec
//! - [`blob`] — .cca audio blob format helpers
//! - [`payload`] — V1 wire-protocol L2 frame (tagged union) + L4 string codec
//! - [`safety`] — emoji fingerprint derivation

#![deny(missing_docs)]
#![deny(unsafe_code)]
#![deny(clippy::all)]
#![warn(clippy::pedantic)]

pub mod blob;
pub mod encoding;
pub mod error;
pub mod handshake;
pub mod identity;
pub mod payload;
pub mod prekey;
pub mod primitives;
pub mod safety;
pub mod session;
pub mod wire;

pub use error::{Error, Result};
