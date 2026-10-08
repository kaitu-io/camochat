//! 统一错误类型。所有公开 API 返回 `Result<T> = std::result::Result<T, Error>`.

use thiserror::Error;

/// chencang-core 的统一错误类型。
#[derive(Debug, Error)]
pub enum Error {
    /// 输入字节长度不正确
    #[error("invalid input length: expected {expected}, got {got}")]
    InvalidLength {
        /// 预期长度（字节）
        expected: usize,
        /// 实际长度（字节）
        got: usize,
    },

    /// 算法套件不支持
    #[error("unsupported suite_id: 0x{0:02x}")]
    UnsupportedSuite(u8),

    /// 协议版本不支持
    #[error("unsupported protocol version: 0x{0:02x}")]
    UnsupportedVersion(u8),

    /// AEAD 验证失败
    #[error("AEAD authentication failed (tampered or wrong key)")]
    AeadFailed,

    /// 签名验证失败
    #[error("signature verification failed")]
    SignatureFailed,

    /// 解码失败
    #[error("decoding error: {0}")]
    Decoding(String),

    /// 内部一致性错误（不该发生）
    #[error("internal error: {0}")]
    Internal(String),
}

/// `Result` 类型别名。
pub type Result<T> = std::result::Result<T, Error>;
