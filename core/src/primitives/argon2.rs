//! Argon2id wrapper for KEK derivation.
//!
//! 参数：m=64MB, t=3, p=4（针对移动设备的"中等"配置）。
//! 输出固定 32 字节。
//!
//! **不要降级参数** — 用户口令是攻击者唯一的暴力面，KDF 慢正是其防御价值所在。

use argon2::{Algorithm, Argon2, Params, Version};

/// 从密码和盐派生 KEK（32 字节）。
///
/// # Panics
/// 不会 panic — 参数固定且有效。
#[must_use]
pub fn derive_kek(password: &[u8], salt: &[u8; 32]) -> [u8; 32] {
    let params = Params::new(
        64 * 1024, // m = 64 MiB
        3,         // t = 3 iterations
        4,         // p = 4 parallelism
        Some(32),  // output 32 bytes
    )
    .expect("valid Argon2 params");
    let argon2 = Argon2::new(Algorithm::Argon2id, Version::V0x13, params);
    let mut out = [0u8; 32];
    argon2
        .hash_password_into(password, salt, &mut out)
        .expect("Argon2 hash_password_into");
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn deterministic_same_input() {
        let password = b"correct horse battery staple";
        let salt = [7u8; 32];
        let kek1 = derive_kek(password, &salt);
        let kek2 = derive_kek(password, &salt);
        assert_eq!(kek1, kek2);
    }

    #[test]
    fn different_salt_different_output() {
        let password = b"correct horse battery staple";
        let kek1 = derive_kek(password, &[1u8; 32]);
        let kek2 = derive_kek(password, &[2u8; 32]);
        assert_ne!(kek1, kek2);
    }

    #[test]
    fn different_password_different_output() {
        let salt = [9u8; 32];
        let kek1 = derive_kek(b"password1", &salt);
        let kek2 = derive_kek(b"password2", &salt);
        assert_ne!(kek1, kek2);
    }
}
