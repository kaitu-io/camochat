//! 带内配对 wire 的确定性 CBOR 编解码（单一真相源，uniffi 导出）。
//!
//! 带内配对经不可信信道（微信文本 / 二维码）传两个 CBOR blob：
//! - Round-1 [`ClassicalPreKeyBundle`]（A→B，~241B；随 `inviter_username` 长度浮动，见 golden 测试）
//! - Round-2 [`ClassicalInbandHeader`]（B→A，~157B；随 `display_name` 浮动，见 golden 测试）
//!
//! **设计决策**：CORE 独占 CBOR 编解码，Android/iOS 经 uniffi 调本模块，故两端
//! 字节完全一致、零手搓 CBOR 漂移。采用**整数 key 的 CBOR map**（语言无关、字段
//! 顺序稳定）；字节数组编为 CBOR byte string（非 int 数组）；可选字段为 `None`
//! 时**省略** key，解码容忍缺失。所有字段均为公钥 / 确认 tag（公开数据），但仍
//! 不向日志倾倒原始字节。

use ciborium::value::{Integer, Value};

use crate::error::{Error, Result};
use crate::handshake::classical_bundle::{
    ClassicalOneTimePreKey, ClassicalPreKeyBundle, ClassicalPublicIdentity, ClassicalSignedPreKey,
};

/// B 发给 A 的 Round-2 经典带内握手 header。
///
/// 复用 A1 的 [`ClassicalPublicIdentity`]（B 的经典身份）。`session_id` 为 5 字节
/// 短会话标识，`confirm_b` 为发起方（B）的 32 字节密钥确认 tag。
#[derive(Clone, PartialEq, Eq, Debug)]
pub struct ClassicalInbandHeader {
    /// 协议版本。
    pub version: u8,
    /// 套件标识。
    pub suite_id: u8,
    /// B 的经典身份公钥（Ed25519 + X25519）。
    pub bob_ik: ClassicalPublicIdentity,
    /// B 的临时 X25519 公钥（X3DH）。
    pub ek_x25519_pub: [u8; 32],
    /// 5 字节短会话标识。
    pub session_id: [u8; 5],
    /// 发起方（B）的 32 字节密钥确认 tag。
    pub confirm_b: [u8; 32],
    /// 可选 B 的展示名（UI 用）。
    pub bob_display_name: Option<String>,
}

// ---- bundle 整数 key 空间 ----
const B_VERSION: i128 = 1;
const B_SUITE: i128 = 2;
const B_IK_ED: i128 = 3;
const B_IK_X: i128 = 4;
const B_SPK_X: i128 = 5;
const B_SPK_SIG: i128 = 6;
const B_SPK_EPOCH: i128 = 7;
const B_OPK_ID: i128 = 8;
const B_OPK_X: i128 = 9;
const B_NONCE: i128 = 10;
const B_INVITER: i128 = 11;

// ---- header 独立整数 key 空间 ----
const H_VERSION: i128 = 1;
const H_SUITE: i128 = 2;
const H_BOB_IK_ED: i128 = 3;
const H_BOB_IK_X: i128 = 4;
const H_EK_X: i128 = 5;
const H_SESSION_ID: i128 = 6;
const H_CONFIRM_B: i128 = 7;
const H_BOB_NAME: i128 = 8;

/// 编码经典 prekey 包为确定性整数 key CBOR。
#[must_use]
pub fn encode_classical_bundle(b: &ClassicalPreKeyBundle) -> Vec<u8> {
    let mut entries: Vec<(Value, Value)> = vec![
        (key(B_VERSION), Value::from(b.version)),
        (key(B_SUITE), Value::from(b.suite_id)),
        (key(B_IK_ED), Value::Bytes(b.ik.ed25519.to_vec())),
        (key(B_IK_X), Value::Bytes(b.ik.x25519.to_vec())),
        (key(B_SPK_X), Value::Bytes(b.spk.x25519.to_vec())),
        (key(B_SPK_SIG), Value::Bytes(b.spk.sig_ed25519.to_vec())),
        (key(B_SPK_EPOCH), Value::from(b.spk.epoch)),
    ];
    if let Some(opk) = &b.opk {
        entries.push((key(B_OPK_ID), Value::from(opk.id)));
        entries.push((key(B_OPK_X), Value::Bytes(opk.x25519.to_vec())));
    }
    entries.push((key(B_NONCE), Value::Bytes(b.pairing_nonce.to_vec())));
    entries.push((key(B_INVITER), Value::Text(b.inviter_username.clone())));
    to_bytes(&Value::Map(entries))
}

/// 解码经典 prekey 包；校验长度与 version/suite 字段存在性。
///
/// # Errors
/// [`Error::Decoding`] / [`Error::InvalidLength`] 当 CBOR 结构非法或字节长度错。
pub fn decode_classical_bundle(bytes: &[u8]) -> Result<ClassicalPreKeyBundle> {
    let map = read_map(bytes)?;
    let opk = match (find(&map, B_OPK_ID), find(&map, B_OPK_X)) {
        (Some(id), Some(x)) => Some(ClassicalOneTimePreKey {
            id: as_u32(id, "opk.id")?,
            x25519: as_arr(x, "opk.x25519")?,
        }),
        (None, None) => None,
        _ => {
            return Err(Error::Decoding(
                "opk.id/opk.x25519 must both be present or absent".into(),
            ))
        }
    };
    Ok(ClassicalPreKeyBundle {
        version: as_u8(req(&map, B_VERSION, "version")?, "version")?,
        suite_id: as_u8(req(&map, B_SUITE, "suite_id")?, "suite_id")?,
        ik: ClassicalPublicIdentity {
            ed25519: as_arr(req(&map, B_IK_ED, "ik.ed25519")?, "ik.ed25519")?,
            x25519: as_arr(req(&map, B_IK_X, "ik.x25519")?, "ik.x25519")?,
        },
        spk: ClassicalSignedPreKey {
            x25519: as_arr(req(&map, B_SPK_X, "spk.x25519")?, "spk.x25519")?,
            sig_ed25519: as_arr(req(&map, B_SPK_SIG, "spk.sig_ed25519")?, "spk.sig_ed25519")?,
            epoch: as_u32(req(&map, B_SPK_EPOCH, "spk.epoch")?, "spk.epoch")?,
        },
        opk,
        pairing_nonce: as_arr(req(&map, B_NONCE, "pairing_nonce")?, "pairing_nonce")?,
        inviter_username: as_text(
            req(&map, B_INVITER, "inviter_username")?,
            "inviter_username",
        )?,
    })
}

/// 编码经典带内 header 为确定性整数 key CBOR。
#[must_use]
pub fn encode_classical_header(h: &ClassicalInbandHeader) -> Vec<u8> {
    let mut entries: Vec<(Value, Value)> = vec![
        (key(H_VERSION), Value::from(h.version)),
        (key(H_SUITE), Value::from(h.suite_id)),
        (key(H_BOB_IK_ED), Value::Bytes(h.bob_ik.ed25519.to_vec())),
        (key(H_BOB_IK_X), Value::Bytes(h.bob_ik.x25519.to_vec())),
        (key(H_EK_X), Value::Bytes(h.ek_x25519_pub.to_vec())),
        (key(H_SESSION_ID), Value::Bytes(h.session_id.to_vec())),
        (key(H_CONFIRM_B), Value::Bytes(h.confirm_b.to_vec())),
    ];
    if let Some(name) = &h.bob_display_name {
        entries.push((key(H_BOB_NAME), Value::Text(name.clone())));
    }
    to_bytes(&Value::Map(entries))
}

/// 解码经典带内 header；校验长度与必填字段。
///
/// # Errors
/// [`Error::Decoding`] / [`Error::InvalidLength`] 当 CBOR 结构非法或字节长度错。
pub fn decode_classical_header(bytes: &[u8]) -> Result<ClassicalInbandHeader> {
    let map = read_map(bytes)?;
    Ok(ClassicalInbandHeader {
        version: as_u8(req(&map, H_VERSION, "version")?, "version")?,
        suite_id: as_u8(req(&map, H_SUITE, "suite_id")?, "suite_id")?,
        bob_ik: ClassicalPublicIdentity {
            ed25519: as_arr(req(&map, H_BOB_IK_ED, "bob_ik.ed25519")?, "bob_ik.ed25519")?,
            x25519: as_arr(req(&map, H_BOB_IK_X, "bob_ik.x25519")?, "bob_ik.x25519")?,
        },
        ek_x25519_pub: as_arr(req(&map, H_EK_X, "ek_x25519_pub")?, "ek_x25519_pub")?,
        session_id: as_arr(req(&map, H_SESSION_ID, "session_id")?, "session_id")?,
        confirm_b: as_arr(req(&map, H_CONFIRM_B, "confirm_b")?, "confirm_b")?,
        bob_display_name: find(&map, H_BOB_NAME)
            .map(|v| as_text(v, "bob_display_name"))
            .transpose()?,
    })
}

// ---- 内部 helper ----

fn key(k: i128) -> Value {
    Value::Integer(Integer::try_from(k).expect("integer key fits"))
}

fn to_bytes(v: &Value) -> Vec<u8> {
    let mut out = Vec::new();
    ciborium::into_writer(v, &mut out).expect("writing to Vec never fails");
    out
}

fn read_map(bytes: &[u8]) -> Result<Vec<(Value, Value)>> {
    let v: Value = ciborium::from_reader(bytes)
        .map_err(|e| Error::Decoding(format!("CBOR parse failed: {e}")))?;
    match v {
        Value::Map(m) => Ok(m),
        _ => Err(Error::Decoding("expected CBOR map".into())),
    }
}

/// 在整数 key map 中查找一个 key 的值（按整数语义匹配）。
fn find(map: &[(Value, Value)], k: i128) -> Option<&Value> {
    map.iter().find_map(|(mk, mv)| match mk {
        Value::Integer(i) if i128::from(*i) == k => Some(mv),
        _ => None,
    })
}

fn req<'m>(map: &'m [(Value, Value)], k: i128, field: &str) -> Result<&'m Value> {
    find(map, k).ok_or_else(|| Error::Decoding(format!("missing field {field} (key {k})")))
}

fn as_u8(v: &Value, field: &str) -> Result<u8> {
    let n = v
        .as_integer()
        .and_then(|i| u8::try_from(i).ok())
        .ok_or_else(|| Error::Decoding(format!("{field} not a u8")))?;
    Ok(n)
}

fn as_u32(v: &Value, field: &str) -> Result<u32> {
    let n = v
        .as_integer()
        .and_then(|i| u32::try_from(i).ok())
        .ok_or_else(|| Error::Decoding(format!("{field} not a u32")))?;
    Ok(n)
}

fn as_text(v: &Value, field: &str) -> Result<String> {
    match v {
        Value::Text(s) => Ok(s.clone()),
        _ => Err(Error::Decoding(format!("{field} not text"))),
    }
}

/// 取 CBOR byte string 并 `try_into` 固定长度数组（长度错 → `InvalidLength`）。
fn as_arr<const N: usize>(v: &Value, field: &str) -> Result<[u8; N]> {
    let Value::Bytes(bytes) = v else {
        return Err(Error::Decoding(format!("{field} not a byte string")));
    };
    bytes
        .as_slice()
        .try_into()
        .map_err(|_| Error::InvalidLength {
            expected: N,
            got: bytes.len(),
        })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample_bundle(with_opk: bool) -> ClassicalPreKeyBundle {
        ClassicalPreKeyBundle {
            version: 0x01,
            suite_id: 0x01,
            ik: ClassicalPublicIdentity {
                ed25519: [1u8; 32],
                x25519: [2u8; 32],
            },
            spk: ClassicalSignedPreKey {
                x25519: [2u8; 32],
                sig_ed25519: [3u8; 64],
                epoch: 7,
            },
            opk: with_opk.then_some(ClassicalOneTimePreKey {
                id: 9,
                x25519: [4u8; 32],
            }),
            pairing_nonce: [5u8; 16],
            inviter_username: "alice".into(),
        }
    }

    fn sample_header(with_name: bool) -> ClassicalInbandHeader {
        ClassicalInbandHeader {
            version: 0x01,
            suite_id: 0x01,
            bob_ik: ClassicalPublicIdentity {
                ed25519: [1u8; 32],
                x25519: [2u8; 32],
            },
            ek_x25519_pub: [6u8; 32],
            session_id: [7u8; 5],
            confirm_b: [8u8; 32],
            bob_display_name: with_name.then(|| "bob".to_string()),
        }
    }

    #[test]
    fn bundle_roundtrip() {
        for with_opk in [true, false] {
            let b = sample_bundle(with_opk);
            let bytes = encode_classical_bundle(&b);
            let back = decode_classical_bundle(&bytes).expect("decode");
            assert_eq!(b, back);
        }
    }

    #[test]
    fn header_roundtrip() {
        for with_name in [true, false] {
            let h = sample_header(with_name);
            let bytes = encode_classical_header(&h);
            let back = decode_classical_header(&bytes).expect("decode");
            assert_eq!(h, back);
        }
    }

    #[test]
    fn bundle_golden() {
        let bytes = encode_classical_bundle(&sample_bundle(true));
        assert_eq!(
            hex::encode(&bytes),
            "ab01010201035820010101010101010101010101010101010101010101010101010101010101010104582002020202020202020202020202020202020202020202020202020202020202020558200202020202020202020202020202020202020202020202020202020202020202065840030303030303030303030303030303030303030303030303030303030303030303030303030303030303030303030303030303030303030303030303030303030707080909582004040404040404040404040404040404040404040404040404040404040404040a50050505050505050505050505050505050b65616c696365"
        );
        assert!(
            bytes.len() < 400,
            "bundle should fit one QR/message, got {}",
            bytes.len()
        );
    }

    #[test]
    fn header_golden() {
        let bytes = encode_classical_header(&sample_header(true));
        assert_eq!(
            hex::encode(&bytes),
            "a8010102010358200101010101010101010101010101010101010101010101010101010101010101045820020202020202020202020202020202020202020202020202020202020202020205582006060606060606060606060606060606060606060606060606060606060606060645070707070707582008080808080808080808080808080808080808080808080808080808080808080863626f62"
        );
        assert!(
            bytes.len() < 220,
            "header should fit one message, got {}",
            bytes.len()
        );
    }
}
