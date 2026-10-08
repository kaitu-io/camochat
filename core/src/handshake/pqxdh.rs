//! PQXDH-hybrid 数学。Initiator (Bob) 计算 4×DH + 3×KEM；Responder (Alice) 对称计算。
//!
//! 详见协议规范 §7.1 PQXDH-hybrid math。

use rand_core::{CryptoRng, RngCore};

use crate::error::Result;
use crate::handshake::bundle::PreKeyBundle;
use crate::identity::keypair::{PublicIdentity, SecretIdentity};
use crate::prekey::one_time::{OneTimePreKey, SecretOneTimePreKey};
use crate::prekey::signed::{SecretSignedPreKey, SignedPreKey};
use crate::primitives::{kdf, ml_kem, x25519};

/// Session Root Key (32 字节)。
pub type Srk = [u8; 32];

/// Protocol version bound into the handshake transcript (see spec §4.8 / roadmap §4).
const PROTOCOL_VERSION_V1: u8 = 0x01;
/// Cipher suite id (PQXDH-hybrid) bound into the transcript. Matches roadmap §4 `suite_id=0x02`.
const SUITE_ID_V1: u8 = 0x02;
/// HKDF domain-separation label; the transcript hash is appended to it as KDF `info`.
const KDF_INFO_LABEL: &[u8] = b"chencang-v1-pqxdh-srk";

// Convention: 32/64-byte classical fields (ed25519, x25519) are fixed forever →
// pushed raw. PQ key/sig fields (ML-DSA, ML-KEM) are length-prefixed via `push_var`
// even though their current sizes are fixed, for forward-compat with future
// parameter sets and to keep the transcript injective regardless of any size drift.
fn push_var(buf: &mut Vec<u8>, bytes: &[u8]) {
    buf.extend_from_slice(&(u32::try_from(bytes.len()).expect("field < 4GiB")).to_be_bytes());
    buf.extend_from_slice(bytes);
}
fn push_public_identity(buf: &mut Vec<u8>, ik: &PublicIdentity) {
    buf.extend_from_slice(&ik.ik_sig_ed25519.to_bytes()); // 32, fixed
    push_var(buf, &ik.ik_sig_mldsa65.to_bytes()); // variable
    buf.extend_from_slice(&ik.ik_dh_x25519.0); // 32, fixed
    push_var(buf, ik.ik_kem_mlkem768.as_bytes()); // 1184 (length-prefixed for uniformity)
}
fn push_signed_prekey(buf: &mut Vec<u8>, spk: &SignedPreKey) {
    buf.extend_from_slice(&spk.spk_x25519.0); // 32
    push_var(buf, spk.spk_mlkem768.as_bytes()); // 1184
    buf.extend_from_slice(&spk.sig_ed25519); // 64
    push_var(buf, &spk.sig_mldsa65); // variable
    buf.extend_from_slice(&spk.epoch.to_be_bytes()); // 4 BE
}
fn push_one_time_prekey(buf: &mut Vec<u8>, opk: &OneTimePreKey) {
    buf.extend_from_slice(&opk.id.to_be_bytes()); // 4 BE
    buf.extend_from_slice(&opk.opk_x25519.0); // 32
    push_var(buf, opk.opk_mlkem768.as_bytes()); // 1184
}

/// Build the 32-byte `BLAKE2b` transcript hash over every public handshake material,
/// in an injective (length-prefixed) encoding so both sides agree byte-for-byte.
#[allow(clippy::too_many_arguments, clippy::similar_names)]
fn build_transcript(
    ik_a: &PublicIdentity,
    spk_a: &SignedPreKey,
    opk_a: Option<&OneTimePreKey>,
    pairing_nonce: &[u8; 16],
    ik_b: &PublicIdentity,
    ek_x_pub: &x25519::PublicKey32,
    ek_kem_pub: &ml_kem::PublicKey,
    kem_ct_spk: &[u8],
    kem_ct_ik: &[u8],
    kem_ct_opk: Option<&[u8]>,
) -> [u8; 32] {
    let mut t = Vec::new();
    t.push(PROTOCOL_VERSION_V1);
    t.push(SUITE_ID_V1);
    push_public_identity(&mut t, ik_a);
    push_signed_prekey(&mut t, spk_a);
    match opk_a {
        Some(o) => {
            t.push(1);
            push_one_time_prekey(&mut t, o);
        }
        None => t.push(0),
    }
    t.extend_from_slice(pairing_nonce);
    push_public_identity(&mut t, ik_b);
    t.extend_from_slice(&ek_x_pub.0);
    push_var(&mut t, ek_kem_pub.as_bytes());
    push_var(&mut t, kem_ct_spk);
    push_var(&mut t, kem_ct_ik);
    match kem_ct_opk {
        Some(c) => {
            t.push(1);
            push_var(&mut t, c);
        }
        None => t.push(0),
    }
    let h = kdf::blake2b(&t, &[], 32);
    let mut out = [0u8; 32];
    out.copy_from_slice(&h);
    out
}

/// Derive the SRK by folding the transcript hash into the HKDF `info` (after the
/// domain-separation label), so the `pairing_nonce` and every public key/ciphertext
/// cryptographically affect the result while the DH/KEM IKM math stays unchanged.
fn derive_srk(ikm: &[u8], transcript: &[u8; 32]) -> Srk {
    let mut info = KDF_INFO_LABEL.to_vec();
    info.extend_from_slice(transcript);
    kdf::hkdf_blake2b(&[0u8; 32], ikm, &info)
}

/// Initiator (扫码方) 一次握手计算后产出的所有给对端的材料。
pub struct InitiatorOutput {
    /// Bob 的 4 件套公钥。
    pub bob_ik_pub: PublicIdentity,
    /// Bob 的临时 X25519 公钥（单次使用）。
    pub ek_x25519_pub: x25519::PublicKey32,
    /// Bob 的临时 ML-KEM-768 公钥（单次使用）。
    pub ek_mlkem_pub: ml_kem::PublicKey,
    /// ML-KEM encap 到 Alice 的 SPK。
    pub kem_ct_to_spk: Vec<u8>,
    /// ML-KEM encap 到 Alice 的 `IK_kem`。
    pub kem_ct_to_ik: Vec<u8>,
    /// ML-KEM encap 到 Alice 的 OPK（如果有）。
    pub kem_ct_to_opk: Option<Vec<u8>>,
    /// Initiator's ephemeral X25519 secret — seeds `dh_send_x25519`.
    pub ek_x25519_secret: x25519::SecretKey,
    /// Initiator's ephemeral ML-KEM secret — seeds `kem_send_mlkem`.
    pub ek_mlkem_secret: ml_kem::SecretKey,
    /// 32-byte handshake transcript hash (spec §4.8). Bound into the SRK and
    /// used to compute/verify key-confirmation tags.
    pub transcript: [u8; 32],
}

/// Responder (Alice) handshake output: the derived SRK plus the 32-byte
/// transcript hash (for key-confirmation, spec §4.8).
pub struct ResponderOutput {
    /// Derived Session Root Key.
    pub srk: Srk,
    /// 32-byte handshake transcript hash (identical to the initiator's).
    pub transcript: [u8; 32],
}

/// Initiator (Bob) 端：拿 Alice bundle，算 SRK 并产生发回 Alice 的数据。
///
/// 数学：
/// - DH1 = X25519(`IK_b_dh`, `SPK_a_x`)
/// - DH2 = X25519(`EK_b_x`, `IK_a_dh`)
/// - DH3 = X25519(`EK_b_x`, `SPK_a_x`)
/// - DH4 = X25519(`EK_b_x`, `OPK_a_x`)（可选）
/// - (ct1, ss1) = `ML-KEM_encap`(`SPK_a_k`)
/// - (ct2, ss2) = `ML-KEM_encap`(`IK_a_kem`)
/// - (ct3, ss3) = `ML-KEM_encap`(`OPK_a_k`)（可选）
/// - SRK = HKDF-BLAKE2b(salt=0, ikm = DH1||DH2||DH3||[DH4] || ss1||ss2||[ss3],
///   info = "chencang-v1-pqxdh-srk" || `transcript_hash`)
///
/// `transcript_hash` = `BLAKE2b` over (version, `suite_id`, Alice's IK/SPK/OPK,
/// `pairing_nonce`, Bob's IK, Bob's ephemeral X25519+ML-KEM pubs, the three KEM
/// ciphertexts). Folding it into `info` makes the `pairing_nonce` (and every other
/// public material) cryptographically affect the SRK without altering the DH/KEM math.
// `clippy::similar_names`: cryptographic role labels (`ek_x_sk` vs `ek_x_pk`,
// `ss1` vs `ct1`) are protocol-defined; renaming would hurt protocol-spec correspondence.
#[must_use]
#[allow(clippy::similar_names)]
pub fn derive_initiator<R: CryptoRng + RngCore>(
    rng: &mut R,
    bob_ik: &SecretIdentity,
    alice_bundle: &PreKeyBundle,
) -> (Srk, InitiatorOutput) {
    // 1. 生成临时 EK (X25519 + ML-KEM)
    let ek_x_sk = x25519::SecretKey::random(rng);
    let ek_x_pk = ek_x_sk.public();
    let (ek_k_pk, ek_k_sk) = ml_kem::generate_keypair(rng);

    // 2. 经典 4×DH
    let dh1 = bob_ik
        .ik_dh_x25519
        .diffie_hellman(&alice_bundle.spk.spk_x25519);
    let dh2 = ek_x_sk.diffie_hellman(&alice_bundle.ik.ik_dh_x25519);
    let dh3 = ek_x_sk.diffie_hellman(&alice_bundle.spk.spk_x25519);
    let dh4 = alice_bundle
        .opk
        .as_ref()
        .map(|opk| ek_x_sk.diffie_hellman(&opk.opk_x25519));

    // 3. 后量子 3×KEM encap
    let (ct1, ss1) = ml_kem::encapsulate(&alice_bundle.spk.spk_mlkem768, rng);
    let (ct2, ss2) = ml_kem::encapsulate(&alice_bundle.ik.ik_kem_mlkem768, rng);
    let (ct3, ss3) = if let Some(opk) = &alice_bundle.opk {
        let (c, s) = ml_kem::encapsulate(&opk.opk_mlkem768, rng);
        (Some(c), Some(s))
    } else {
        (None, None)
    };

    // 4. 拼接所有共享 secret → KDF
    let mut ikm = Vec::new();
    ikm.extend_from_slice(dh1.as_bytes());
    ikm.extend_from_slice(dh2.as_bytes());
    ikm.extend_from_slice(dh3.as_bytes());
    if let Some(dh4_val) = &dh4 {
        ikm.extend_from_slice(dh4_val.as_bytes());
    }
    ikm.extend_from_slice(ss1.as_bytes());
    ikm.extend_from_slice(ss2.as_bytes());
    if let Some(ss3_val) = &ss3 {
        ikm.extend_from_slice(ss3_val.as_bytes());
    }

    let transcript = build_transcript(
        &alice_bundle.ik,
        &alice_bundle.spk,
        alice_bundle.opk.as_ref(),
        &alice_bundle.pairing_nonce,
        &bob_ik.public(),
        &ek_x_pk,
        &ek_k_pk,
        &ct1,
        &ct2,
        ct3.as_deref(),
    );
    let srk = derive_srk(&ikm, &transcript);

    let output = InitiatorOutput {
        bob_ik_pub: bob_ik.public(),
        ek_x25519_pub: ek_x_pk,
        ek_mlkem_pub: ek_k_pk,
        kem_ct_to_spk: ct1,
        kem_ct_to_ik: ct2,
        kem_ct_to_opk: ct3,
        ek_x25519_secret: ek_x_sk,
        ek_mlkem_secret: ek_k_sk,
        transcript,
    };
    (srk, output)
}

/// Responder (Alice) 端：用自己的私钥 + Bob 的 EK pub 重算 SRK。
///
/// `pairing_nonce` is bound into the SRK via the handshake transcript hash
/// (spec §4.8). Supplying the wrong nonce yields a silently different SRK —
/// there is no error; the two sides simply fail to agree. Callers MUST pass
/// the nonce from the original pairing invite.
///
/// # Errors
/// 当任一 ML-KEM decap 失败（不正确的密文长度等）返回 Err。
// 9 inputs are protocol-mandated PQXDH-hybrid materials; names mirror the PQXDH spec.
#[allow(clippy::too_many_arguments, clippy::similar_names)]
pub fn derive_responder(
    alice_ik: &SecretIdentity,
    alice_spk: &SecretSignedPreKey,
    alice_opk: Option<&SecretOneTimePreKey>,
    bob_ik_pub: &PublicIdentity,
    bob_ek_x: &x25519::PublicKey32,
    bob_ek_k: &ml_kem::PublicKey,
    kem_ct_to_spk: &[u8],
    kem_ct_to_ik: &[u8],
    kem_ct_to_opk: Option<&[u8]>,
    pairing_nonce: &[u8; 16],
) -> Result<ResponderOutput> {
    // 镜像计算 DH
    let dh1 = alice_spk
        .spk_x25519
        .diffie_hellman(&bob_ik_pub.ik_dh_x25519);
    let dh2 = alice_ik.ik_dh_x25519.diffie_hellman(bob_ek_x);
    let dh3 = alice_spk.spk_x25519.diffie_hellman(bob_ek_x);
    let dh4 = alice_opk.map(|opk| opk.opk_x25519.diffie_hellman(bob_ek_x));

    // ML-KEM decap
    let ss1 = ml_kem::decapsulate(&alice_spk.spk_mlkem768, kem_ct_to_spk)?;
    let ss2 = ml_kem::decapsulate(&alice_ik.ik_kem_mlkem768_sk, kem_ct_to_ik)?;
    let ss3 = match (alice_opk, kem_ct_to_opk) {
        (Some(opk), Some(ct)) => Some(ml_kem::decapsulate(&opk.opk_mlkem768, ct)?),
        _ => None,
    };

    let mut ikm = Vec::new();
    ikm.extend_from_slice(dh1.as_bytes());
    ikm.extend_from_slice(dh2.as_bytes());
    ikm.extend_from_slice(dh3.as_bytes());
    if let Some(dh4_val) = &dh4 {
        ikm.extend_from_slice(dh4_val.as_bytes());
    }
    ikm.extend_from_slice(ss1.as_bytes());
    ikm.extend_from_slice(ss2.as_bytes());
    if let Some(ss3_val) = &ss3 {
        ikm.extend_from_slice(ss3_val.as_bytes());
    }

    let ik_a = alice_ik.public();
    let opk_a = alice_opk.map(|o| &o.public);
    let transcript = build_transcript(
        &ik_a,
        &alice_spk.public,
        opk_a,
        pairing_nonce,
        bob_ik_pub,
        bob_ek_x,
        bob_ek_k,
        kem_ct_to_spk,
        kem_ct_to_ik,
        kem_ct_to_opk,
    );
    let srk = derive_srk(&ikm, &transcript);
    Ok(ResponderOutput { srk, transcript })
}
