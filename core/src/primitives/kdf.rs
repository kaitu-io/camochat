//! `BLAKE2b` + HKDF-`BLAKE2b` wrapper.
//!
//! - `blake2b(input, key, output_len)` — variable-output `BLAKE2b`. Empty key → standard
//!   hash. Non-empty key → keyed `BLAKE2b` (RFC 7693 §2.5, the `BLAKE2b`-native HMAC form).
//! - `hmac_blake2b_32` — convenience for 32-byte MAC output.
//! - `hkdf_blake2b` / `hkdf_blake2b_64` — HKDF over `BLAKE2b`-512.
//!
//! 注：`Blake2b512` 在 `blake2` 0.10 中是 lazy 类型（variable-output core 包装），
//! 不能直接作为 `Hkdf` 的默认 `Hmac<H>` 后端使用。改用 `SimpleHkdf<Blake2b512>`，
//! 它内部使用 `SimpleHmac<H>` 支持非 eager 哈希。

use blake2::{
    digest::{Mac, Update, VariableOutput},
    Blake2bMac512, Blake2bVar,
};
use hkdf::SimpleHkdf;

/// `BLAKE2b` 哈希。
///
/// 当 `key` 为空 → 标准 `BLAKE2b` 哈希（variable output 1..=64）。
/// 当 `key` 非空 → `BLAKE2b` keyed mode（RFC 7693 §2.5），输出按 `output_len` 截断到 1..=32。
///
/// # Panics
/// `output_len` 超出 1..=64（unkey）/ 1..=32（keyed）范围会 panic。
#[must_use]
pub fn blake2b(input: &[u8], key: &[u8], output_len: usize) -> Vec<u8> {
    if key.is_empty() {
        let mut hasher = Blake2bVar::new(output_len).expect("output_len 1..=64");
        Update::update(&mut hasher, input);
        let mut out = vec![0u8; output_len];
        hasher.finalize_variable(&mut out).expect("finalize");
        out
    } else {
        let mut mac = <Blake2bMac512 as Mac>::new_from_slice(key).expect("key length 1..=64 bytes");
        Mac::update(&mut mac, input);
        let result = mac.finalize().into_bytes();
        result[..output_len].to_vec()
    }
}

/// HMAC-`BLAKE2b`（`BLAKE2b` 的本机 keyed 模式，等价于 HMAC 用途）。
/// 输出固定 32 字节。
///
/// # Panics
/// `key` 长度超出 1..=64 字节时 panic。
#[must_use]
pub fn hmac_blake2b_32(key: &[u8], message: &[u8]) -> [u8; 32] {
    let mut mac = <Blake2bMac512 as Mac>::new_from_slice(key).expect("key length 1..=64 bytes");
    Mac::update(&mut mac, message);
    let result = mac.finalize().into_bytes();
    let mut out = [0u8; 32];
    out.copy_from_slice(&result[..32]);
    out
}

/// HKDF-`BLAKE2b`-512 → 32 字节输出。
///
/// # Panics
/// 实际不会 panic：32 字节远小于 HKDF 单次 expand 上限（255 × `HashLen`）。
#[must_use]
pub fn hkdf_blake2b(salt: &[u8], ikm: &[u8], info: &[u8]) -> [u8; 32] {
    let hk = SimpleHkdf::<blake2::Blake2b512>::new(Some(salt), ikm);
    let mut okm = [0u8; 32];
    hk.expand(info, &mut okm).expect("HKDF expand 32 bytes");
    okm
}

/// HKDF-`BLAKE2b`-512 → 64 字节输出（用于 `root_key` + `chain_key` 同步派生）。
///
/// # Panics
/// 实际不会 panic：64 字节远小于 HKDF 单次 expand 上限（255 × `HashLen`）。
#[must_use]
pub fn hkdf_blake2b_64(salt: &[u8], ikm: &[u8], info: &[u8]) -> [u8; 64] {
    let hk = SimpleHkdf::<blake2::Blake2b512>::new(Some(salt), ikm);
    let mut okm = [0u8; 64];
    hk.expand(info, &mut okm).expect("HKDF expand 64 bytes");
    okm
}
