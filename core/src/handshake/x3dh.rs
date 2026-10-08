//! 经典 X3DH 握手（suite 0x01：X25519 + Ed25519，无后量子）。
//!
//! 这是 [`crate::handshake::pqxdh`] 数学**去掉所有 ML-KEM** 的版本：仅 4×DH，
//! 无 KEM encap/decap、无 ML-KEM 临时公钥、transcript 里也无 `kem_ct`。V1 仅用
//! 经典套件（见 spec §4 顶部 2026-06-15 决策），后量子 PQXDH-hybrid (suite 0x02)
//! 推迟到 V1.1。
//!
//! 数学（Initiator = Bob 扫码方）：
//! - DH1 = X25519(`IK_b_dh`, `SPK_a_x`)
//! - DH2 = X25519(`EK_b_x`,  `IK_a_x`)
//! - DH3 = X25519(`EK_b_x`,  `SPK_a_x`)
//! - DH4 = X25519(`EK_b_x`,  `OPK_a_x`)（可选）
//! - IKM = DH1 || DH2 || DH3 || [DH4]
//! - SRK = HKDF-BLAKE2b(salt=[0;32], ikm, info = "chencang-v1-x3dh-srk" || transcript)
//!
//! Responder (Alice) 用自己的私钥 + Bob 的 EK pub 镜像计算同样 4×DH。

use rand_core::{CryptoRng, RngCore};

use crate::error::Result;
use crate::handshake::classical_bundle::ClassicalPreKeyBundle;
use crate::identity::keypair::{PublicIdentity, SecretIdentity};
use crate::prekey::one_time::SecretOneTimePreKey;
use crate::prekey::signed::SecretSignedPreKey;
use crate::primitives::{kdf, x25519};

/// Session Root Key（32 字节）。与 PQXDH 路径同型。
pub type Srk = [u8; 32];

/// 经典协议版本（绑入 transcript）。
const PROTOCOL_VERSION_V1: u8 = 0x01;
/// 经典套件标识（绑入 transcript）。
const SUITE_ID_V1: u8 = 0x01;
/// HKDF 域分隔标签；transcript 哈希拼在它后面作 KDF `info`。
const KDF_INFO_LABEL: &[u8] = b"chencang-v1-x3dh-srk";

/// 构造 32 字节 `BLAKE2b` transcript 哈希，覆盖全部公开握手材料。
///
/// 经典套件字段全为定长（32/64/4B），故用固定字节布局（无长度前缀），两端
/// 按相同顺序拼出逐字节相同的输入。`A_IK` / `B_IK` 以裸 `&[u8; 32]` 形式传入
/// （而非 `PublicIdentity`）——经典带内包不携带 ML-KEM/ML-DSA 公钥，无法从经典
/// 字段重建完整 `PublicIdentity`，故 transcript 只吃 ed25519+x25519 两件套。
///
/// 布局：
/// `version(1)` || `suite(1)`
/// || `A_IK.ed25519(32)` || `A_IK.x25519(32)`
/// || `A_SPK.x25519(32)` || `A_SPK.sig_ed25519(64)` || `A_SPK.epoch(4 BE)`
/// || `opk_present(1)` [|| `opk.id(4 BE)` || `opk.x25519(32)`]
/// || `pairing_nonce(16)`
/// || `B_IK.ed25519(32)` || `B_IK.x25519(32)`
/// || `EK_b_x(32)`
// 10 个入参皆为协议规定的经典握手公开材料（A/B 各 IK 拆 ed/x、SPK 三件、OPK、
// nonce、Bob EK）；聚成 struct 反而割裂协议字段对应，故保留扁平签名。
#[allow(clippy::too_many_arguments, clippy::similar_names)]
fn build_transcript(
    a_ik_ed: &[u8; 32],
    a_ik_x: &[u8; 32],
    a_spk_x: &[u8; 32],
    a_spk_sig: &[u8; 64],
    a_spk_epoch: u32,
    opk: Option<(u32, &[u8; 32])>,
    pairing_nonce: &[u8; 16],
    b_ik_ed: &[u8; 32],
    b_ik_x: &[u8; 32],
    ek_b_x: &[u8; 32],
) -> [u8; 32] {
    // Raw concatenation is injective here (unlike pqxdh's length-prefixed form):
    // every field below is permanently fixed-length (ed25519/x25519 = 32, sig = 64,
    // epoch/opk-id = 4 BE, nonce = 16), the one variable field (`inviter_username`)
    // is deliberately excluded, and the OPK arm is disambiguated by a presence byte.
    let mut t = Vec::new();
    t.push(PROTOCOL_VERSION_V1);
    t.push(SUITE_ID_V1);
    // A_IK
    t.extend_from_slice(a_ik_ed);
    t.extend_from_slice(a_ik_x);
    // A_SPK
    t.extend_from_slice(a_spk_x);
    t.extend_from_slice(a_spk_sig);
    t.extend_from_slice(&a_spk_epoch.to_be_bytes());
    // OPK（presence-byte + 内容）
    match opk {
        Some((id, x)) => {
            t.push(1);
            t.extend_from_slice(&id.to_be_bytes());
            t.extend_from_slice(x);
        }
        None => t.push(0),
    }
    // pairing nonce
    t.extend_from_slice(pairing_nonce);
    // B_IK
    t.extend_from_slice(b_ik_ed);
    t.extend_from_slice(b_ik_x);
    // Bob 临时 EK
    t.extend_from_slice(ek_b_x);

    let h = kdf::blake2b(&t, &[], 32);
    let mut out = [0u8; 32];
    out.copy_from_slice(&h);
    out
}

/// 把 transcript 哈希折进 HKDF `info`（接在域分隔标签之后），使 `pairing_nonce`
/// 与所有公开材料密码学上影响 SRK，而 DH IKM 数学保持不变。
fn derive_srk(ikm: &[u8], transcript: &[u8; 32]) -> Srk {
    let mut info = KDF_INFO_LABEL.to_vec();
    info.extend_from_slice(transcript);
    kdf::hkdf_blake2b(&[0u8; 32], ikm, &info)
}

/// Initiator (Bob 扫码方) 一次经典握手产出的、回送 Alice 的材料。
pub struct InitiatorOutput {
    /// Bob 的经典身份公钥（含完整 `PublicIdentity`；Responder 从中读 ed/x）。
    pub bob_ik_pub: PublicIdentity,
    /// Bob 的临时 X25519 公钥（单次使用）。
    pub ek_x25519_pub: x25519::PublicKey32,
    /// Bob 的临时 X25519 私钥——seed 后续 ratchet 的发送链。
    pub ek_x25519_secret: x25519::SecretKey,
    /// 32 字节握手 transcript 哈希。绑入 SRK + 用于算/验 key-confirmation tag。
    pub transcript: [u8; 32],
}

/// Responder (Alice) 经典握手输出：SRK + transcript 哈希（与 initiator 相同）。
pub struct ResponderOutput {
    /// 派生出的 Session Root Key。
    pub srk: Srk,
    /// 32 字节握手 transcript 哈希（与 initiator 逐字节相同）。
    pub transcript: [u8; 32],
}

/// Initiator (Bob) 端：拿 Alice 的经典 bundle，算 SRK 并产生回送数据。
///
/// 见模块文档的数学。`pairing_nonce` 经 transcript 绑入 SRK：传错 nonce 会静默
/// 派生出不同 SRK（无错误，两端单纯无法一致）。
// DH 角色标签（`ek_x_sk`/`ek_x_pk` 等）是协议约定，重命名会损害规范对应性。
#[must_use]
#[allow(clippy::similar_names)]
pub fn derive_initiator_classical<R: CryptoRng + RngCore>(
    rng: &mut R,
    bob_ik: &SecretIdentity,
    alice_bundle: &ClassicalPreKeyBundle,
) -> (Srk, InitiatorOutput) {
    // 1. 生成临时 EK（仅 X25519，无 ML-KEM）。
    let ek_x_sk = x25519::SecretKey::random(rng);
    let ek_x_pk = ek_x_sk.public();

    // 2. 经典 4×DH（与 pqxdh 同序，去掉 KEM）。
    let spk_a_x = x25519::PublicKey32(alice_bundle.spk.x25519);
    let ik_a_x = x25519::PublicKey32(alice_bundle.ik.x25519);
    let dh1 = bob_ik.ik_dh_x25519.diffie_hellman(&spk_a_x);
    let dh2 = ek_x_sk.diffie_hellman(&ik_a_x);
    let dh3 = ek_x_sk.diffie_hellman(&spk_a_x);
    let dh4 = alice_bundle
        .opk
        .as_ref()
        .map(|opk| ek_x_sk.diffie_hellman(&x25519::PublicKey32(opk.x25519)));

    // 3. 拼接共享 secret → IKM。
    let mut ikm = Vec::new();
    ikm.extend_from_slice(dh1.as_bytes());
    ikm.extend_from_slice(dh2.as_bytes());
    ikm.extend_from_slice(dh3.as_bytes());
    if let Some(dh4_val) = &dh4 {
        ikm.extend_from_slice(dh4_val.as_bytes());
    }

    // 4. transcript（A_IK/B_IK 用裸字节，见 build_transcript 文档）。
    let bob_pub = bob_ik.public();
    let opk_t = alice_bundle.opk.as_ref().map(|o| (o.id, &o.x25519));
    let transcript = build_transcript(
        &alice_bundle.ik.ed25519,
        &alice_bundle.ik.x25519,
        &alice_bundle.spk.x25519,
        &alice_bundle.spk.sig_ed25519,
        alice_bundle.spk.epoch,
        opk_t,
        &alice_bundle.pairing_nonce,
        &bob_pub.ik_sig_ed25519.to_bytes(),
        &bob_pub.ik_dh_x25519.0,
        &ek_x_pk.0,
    );

    let srk = derive_srk(&ikm, &transcript);

    let output = InitiatorOutput {
        bob_ik_pub: bob_pub,
        ek_x25519_pub: ek_x_pk,
        ek_x25519_secret: ek_x_sk,
        transcript,
    };
    (srk, output)
}

/// Responder (Alice) 端：用自己的私钥 + Bob 的 EK pub 镜像重算 SRK。
///
/// `opk_id` 告知用了哪把 OPK（仅绑入 transcript；DH4 用 `alice_opk` 私钥）。当
/// `alice_opk` 为 `Some` 时 `opk_id` 应一并为 `Some`，两端 OPK 段才一致。
///
/// `pairing_nonce` 经 transcript 绑入 SRK（同 initiator）；传错会静默派生不同
/// SRK——调用方必须用原始邀请里的 nonce。
///
/// # Errors
/// 当前经典路径无 KEM decap，故不会因密码学失败返回 Err；保留 `Result` 以与
/// PQXDH 路径签名一致、便于上层统一处理。
#[allow(clippy::similar_names)]
pub fn derive_responder_classical(
    alice_ik: &SecretIdentity,
    alice_spk: &SecretSignedPreKey,
    alice_opk: Option<&SecretOneTimePreKey>,
    bob_ik_pub: &PublicIdentity,
    bob_ek_x: &x25519::PublicKey32,
    opk_id: Option<u32>,
    pairing_nonce: &[u8; 16],
) -> Result<ResponderOutput> {
    // `alice_opk` (drives the DH4 leg) and `opk_id` (drives the transcript OPK arm)
    // MUST agree, or the responder would mix the OPK into the SRK while omitting it
    // from the transcript — a silent SRK fork. Make caller misuse loud in tests.
    debug_assert_eq!(
        alice_opk.is_some(),
        opk_id.is_some(),
        "alice_opk and opk_id must both be Some or both None"
    );

    // 镜像 4×DH。
    let dh1 = alice_spk
        .spk_x25519
        .diffie_hellman(&bob_ik_pub.ik_dh_x25519);
    let dh2 = alice_ik.ik_dh_x25519.diffie_hellman(bob_ek_x);
    let dh3 = alice_spk.spk_x25519.diffie_hellman(bob_ek_x);
    let dh4 = alice_opk.map(|opk| opk.opk_x25519.diffie_hellman(bob_ek_x));

    let mut ikm = Vec::new();
    ikm.extend_from_slice(dh1.as_bytes());
    ikm.extend_from_slice(dh2.as_bytes());
    ikm.extend_from_slice(dh3.as_bytes());
    if let Some(dh4_val) = &dh4 {
        ikm.extend_from_slice(dh4_val.as_bytes());
    }

    let alice_pub = alice_ik.public();
    let opk_t = match (alice_opk, opk_id) {
        (Some(opk), Some(id)) => Some((id, &opk.public.opk_x25519.0)),
        _ => None,
    };
    let transcript = build_transcript(
        &alice_pub.ik_sig_ed25519.to_bytes(),
        &alice_pub.ik_dh_x25519.0,
        &alice_spk.public.spk_x25519.0,
        &alice_spk.public.sig_ed25519_classical,
        alice_spk.public.epoch,
        opk_t,
        pairing_nonce,
        &bob_ik_pub.ik_sig_ed25519.to_bytes(),
        &bob_ik_pub.ik_dh_x25519.0,
        &bob_ek_x.0,
    );

    let srk = derive_srk(&ikm, &transcript);
    Ok(ResponderOutput { srk, transcript })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::handshake::classical_bundle::*;
    use crate::handshake::key_confirm::{
        confirm_tag, derive_kc, verify_confirm_tag, CONFIRM_WHO_INITIATOR,
    };
    use crate::identity::keypair::SecretIdentity;
    use crate::prekey::one_time::SecretOneTimePreKey;
    use crate::prekey::signed::SecretSignedPreKey;

    // `ik`/`spk`/`opk` are standard PQXDH prekey abbreviations; the short
    // suffixes read as "too similar" to clippy::pedantic but are the domain
    // convention here.
    #[allow(clippy::similar_names)]
    fn run_pair(nonce: [u8; 16]) -> ([u8; 32], [u8; 32], [u8; 32], [u8; 32]) {
        let mut rng = rand::thread_rng();
        let alice_ik = SecretIdentity::generate(&mut rng);
        let alice_spk = SecretSignedPreKey::generate(&mut rng, &alice_ik, 1);
        let alice_opk = SecretOneTimePreKey::generate(&mut rng, 7);
        let bob_ik = SecretIdentity::generate(&mut rng);

        let bundle = ClassicalPreKeyBundle {
            version: 0x01,
            suite_id: 0x01,
            ik: ClassicalPublicIdentity::from_full(&alice_ik.public()),
            spk: ClassicalSignedPreKey::from_full(&alice_spk.public),
            opk: Some(ClassicalOneTimePreKey {
                id: 7,
                x25519: alice_opk.public.opk_x25519.0,
            }),
            pairing_nonce: nonce,
            inviter_username: "alice".into(),
        };

        let (srk_i, out) = derive_initiator_classical(&mut rng, &bob_ik, &bundle);
        let resp = derive_responder_classical(
            &alice_ik,
            &alice_spk,
            Some(&alice_opk),
            &out.bob_ik_pub,
            &out.ek_x25519_pub,
            Some(7),
            &nonce,
        )
        .expect("responder derives");
        (srk_i, resp.srk, out.transcript, resp.transcript)
    }

    #[test]
    fn both_sides_agree_on_srk_and_transcript() {
        let (si, sr, ti, tr) = run_pair([0x22; 16]);
        assert_eq!(si, sr, "SRK must match");
        assert_eq!(ti, tr, "transcript must match");
    }
    #[test]
    fn different_nonce_forks_srk() {
        let (si, _, _, _) = run_pair([0x22; 16]);
        let (sj, _, _, _) = run_pair([0x33; 16]);
        assert_ne!(si, sj, "pairing_nonce must bind into SRK");
    }
    #[test]
    fn key_confirmation_roundtrips() {
        let (si, sr, ti, _tr) = run_pair([0x22; 16]);
        let kc_i = derive_kc(&si);
        let confirm_b = confirm_tag(&kc_i, &ti, CONFIRM_WHO_INITIATOR);
        let kc_r = derive_kc(&sr);
        assert!(verify_confirm_tag(
            &kc_r,
            &ti,
            CONFIRM_WHO_INITIATOR,
            &confirm_b
        ));
    }
}
