//! XChaCha20-Poly1305 AEAD wrapper.

use chacha20poly1305::{
    aead::{Aead, KeyInit, Payload},
    XChaCha20Poly1305, XNonce,
};

use crate::error::{Error, Result};

/// Key 长度（32 字节）。
pub const KEY_LEN: usize = 32;
/// Nonce 长度（24 字节 — `XChaCha20` 扩展版）。
pub const NONCE_LEN: usize = 24;
/// Poly1305 tag 长度（16 字节）。
pub const TAG_LEN: usize = 16;

/// 加密。返回 ciphertext 含末尾 16 字节 tag。
///
/// # Errors
/// 当 `RustCrypto` 内部出错时返回 `Error::Internal`（应当不会发生）。
pub fn seal(
    key: &[u8; KEY_LEN],
    nonce: &[u8; NONCE_LEN],
    aad: &[u8],
    plaintext: &[u8],
) -> Result<Vec<u8>> {
    let cipher = XChaCha20Poly1305::new(key.into());
    let xnonce = XNonce::from_slice(nonce);
    cipher
        .encrypt(
            xnonce,
            Payload {
                msg: plaintext,
                aad,
            },
        )
        .map_err(|_| Error::Internal("AEAD seal failed".into()))
}

/// 解密。输入 ciphertext 含末尾 16 字节 tag。
///
/// # Errors
/// 当 AEAD 鉴权失败时返回 `Error::AeadFailed`。
pub fn open(
    key: &[u8; KEY_LEN],
    nonce: &[u8; NONCE_LEN],
    aad: &[u8],
    ciphertext: &[u8],
) -> Result<Vec<u8>> {
    let cipher = XChaCha20Poly1305::new(key.into());
    let xnonce = XNonce::from_slice(nonce);
    cipher
        .decrypt(
            xnonce,
            Payload {
                msg: ciphertext,
                aad,
            },
        )
        .map_err(|_| Error::AeadFailed)
}
