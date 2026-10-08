//! Double Ratchet 会话状态机。
//!
//! 本模块同时暴露低层 ratchet 子模块（`dh_ratchet` / `kem_ratchet` /
//! `symmetric_ratchet` / `skipped_keys`）和高层 [`Session`] 类型。Session
//! 是 PQXDH 握手结果 + 所有 ratchet 步骤 + wire format 编解码的整合层，
//! 是平台 SDK 唯一会直接接触的会话入口。

pub mod dh_ratchet;
pub mod kem_ratchet;
pub mod skipped_keys;
pub mod state;
pub mod symmetric_ratchet;

pub use state::{now_unix, SessionState};

use rand_core::{CryptoRng, RngCore};

use crate::error::{Error, Result};
use crate::handshake::pqxdh::InitiatorOutput;
use crate::identity::keypair::PublicIdentity;
use crate::primitives::{aead, kdf, ml_kem, x25519};
use crate::wire::aad::build_aad;
use crate::wire::header::{
    Header, KemRatchetData, FLAG_DH_RATCHET, FLAG_KEM_RATCHET, SUITE_CLASSICAL_V1,
    SUITE_PQ_HYBRID_V1, VERSION_V1,
};

/// HKDF info label for deriving the initial chain key from the SRK.
const INFO_INIT_CHAIN: &[u8] = b"chencang-v1-init-chain";

/// 顶层 Session：整合所有 ratchet + wire format 的可消费 API。
///
/// 调用方通过 [`Session::initiator_after_handshake`] 或
/// [`Session::responder_after_handshake`] 在 PQXDH 握手后构造 Session，
/// 然后用 [`Session::encrypt`] / [`Session::decrypt`] 收发消息。
pub struct Session {
    /// 内部状态。暴露为 `pub` 以便高级用例（持久化、调试）访问；
    /// 通常调用方只需要 `encrypt` / `decrypt`。
    pub state: SessionState,
}

impl Session {
    /// Initiator 端 (Bob) 握手后构造 Session：
    /// 用 Alice IK 中的长期 DH/KEM pub 作为初始 recv 方向，并把握手时
    /// 生成的临时 EK 私钥种入 send 方向，使得当 responder 先回复并执行
    /// DH/KEM ratchet 时，initiator 能用对应私钥完成 `step_recv`。
    /// Bob 的首条消息仍走预种的 `send_chain_key`（不触发 `step_send`）。
    #[must_use]
    pub fn initiator_after_handshake(
        srk: [u8; 32],
        sid: [u8; 5],
        alice_ik: &PublicIdentity,
        ek_x25519_secret: x25519::SecretKey,
        ek_mlkem_secret: ml_kem::SecretKey,
    ) -> Self {
        let mut state = SessionState::from_srk(srk, sid);
        // 初始 chain 从 SRK 派生（首条 send 直接使用，不触发 DH ratchet）。
        let initial_chain = kdf::hkdf_blake2b(&srk, &[], INFO_INIT_CHAIN);
        state.send_chain_key = Some(initial_chain);
        state.dh_recv_x25519_pub = Some(alice_ik.ik_dh_x25519);
        state.kem_recv_mlkem_pub = Some(alice_ik.ik_kem_mlkem768.clone());
        // 种入 EK 私钥作为 send 方向：responder 的 recv 方向种的是同一对
        // EK 公钥，因此 responder 先回复时的 DH/KEM ratchet 能在此对称收敛。
        state.dh_send_x25519 = Some(ek_x25519_secret);
        state.kem_send_mlkem = Some(ek_mlkem_secret);
        Session { state }
    }

    /// Responder 端 (Alice) 握手后构造 Session：
    /// 用 Bob 的临时 EK pub 作为初始 recv 方向
    /// （Alice 首条接收消息会带 Bob 的新 DH pub）。
    #[must_use]
    pub fn responder_after_handshake(
        srk: [u8; 32],
        sid: [u8; 5],
        init_out: &InitiatorOutput,
    ) -> Self {
        let mut state = SessionState::from_srk(srk, sid);
        let initial_chain = kdf::hkdf_blake2b(&srk, &[], INFO_INIT_CHAIN);
        state.recv_chain_key = Some(initial_chain);
        state.dh_recv_x25519_pub = Some(init_out.ek_x25519_pub);
        state.kem_recv_mlkem_pub = Some(init_out.ek_mlkem_pub.clone());
        Session { state }
    }

    /// 经典 Initiator 端 (Bob) 握手后构造 Session（suite `0x01`）。
    ///
    /// 与 [`Self::initiator_after_handshake`] 同构，但只种入 X25519 DH 棘轮种子，
    /// 不接触任何 ML-KEM 物料：`post_quantum=false`，KEM 棘轮永久门控关闭。
    /// `alice_ik_dh_x25519` 是 Alice 长期身份的 X25519 公钥（初始 recv 方向）；
    /// `ek_x25519_secret` 是经典 X3DH 时生成的临时 EK 私钥（种入 send 方向）。
    #[must_use]
    pub fn initiator_after_handshake_classical(
        srk: [u8; 32],
        sid: [u8; 5],
        alice_ik_dh_x25519: x25519::PublicKey32,
        ek_x25519_secret: x25519::SecretKey,
    ) -> Self {
        let mut state = SessionState::from_srk_classical(srk, sid);
        let initial_chain = kdf::hkdf_blake2b(&srk, &[], INFO_INIT_CHAIN);
        state.send_chain_key = Some(initial_chain);
        state.dh_recv_x25519_pub = Some(alice_ik_dh_x25519);
        state.dh_send_x25519 = Some(ek_x25519_secret);
        Session { state }
    }

    /// 经典 Responder 端 (Alice) 握手后构造 Session（suite `0x01`）。
    ///
    /// 与 [`Self::responder_after_handshake`] 同构，但只种入 X25519 DH 棘轮种子，
    /// 不接触任何 ML-KEM 物料。`bob_ek_x25519_pub` 是 Bob 经典 X3DH 的临时 EK
    /// 公钥（初始 recv 方向；Alice 首条接收消息携带 Bob 的新 DH pub）。
    #[must_use]
    pub fn responder_after_handshake_classical(
        srk: [u8; 32],
        sid: [u8; 5],
        bob_ek_x25519_pub: x25519::PublicKey32,
    ) -> Self {
        let mut state = SessionState::from_srk_classical(srk, sid);
        let initial_chain = kdf::hkdf_blake2b(&srk, &[], INFO_INIT_CHAIN);
        state.recv_chain_key = Some(initial_chain);
        state.dh_recv_x25519_pub = Some(bob_ek_x25519_pub);
        Session { state }
    }

    /// 加密一条消息，返回 z-base32 wire 文本（legacy V0 codec）。
    ///
    /// 新代码请优先使用 [`Session::encrypt_to_bytes`]：V1 wire 协议在
    /// 字节层之上叠加 Base32768 + 🔒 前缀（见 [`crate::payload::encode_wire`]），
    /// z-base32 已退役。本方法保留是为了不破坏现有的 facade 集成测试。
    ///
    /// # Errors
    /// 当 AEAD seal 失败（不该发生）返回 [`Error::Internal`]。
    pub fn encrypt<R: CryptoRng + RngCore>(
        &mut self,
        plaintext: &[u8],
        rng: &mut R,
    ) -> Result<String> {
        let bytes = self.encrypt_to_bytes(plaintext, rng)?;
        Ok(crate::encoding::zbase32::encode(&bytes))
    }

    /// V1 入口：加密一条消息，返回 L3 原始密文字节（不含 z-base32 / Base32768）。
    ///
    /// 字节布局 = `Header::to_bytes()` 拼接 AEAD 输出（密文 + 16-byte Poly1305 tag）。
    /// 调用方负责将这段字节再过 [`crate::payload::encode_wire`] 套上 🔒 + Base32768。
    ///
    /// # Errors
    /// - [`Error::Internal`] 当 AEAD seal 失败（实际不可能发生）或
    ///   `send_chain_key` 在不该缺失的位置缺失。
    pub fn encrypt_to_bytes<R: CryptoRng + RngCore>(
        &mut self,
        plaintext: &[u8],
        rng: &mut R,
    ) -> Result<Vec<u8>> {
        // 1. 如果 send_chain_key 没有，触发 DH ratchet 推进发送方向。
        //    只有真正在本帧执行了 step_send 才需要把新 dh_pub 放进 header；
        //    initiator 用握手种入的 send key 直接发送的首帧不携带 dh_pub
        //    （recv 端已经从 bundle 拿到同一对 EK pub，无需 ratchet）。
        let did_dh_step_send = if self.state.send_chain_key.is_none() {
            dh_ratchet::step_send(rng, &mut self.state);
            true
        } else {
            false
        };

        // 2. KEM ratchet 只搭乘真正执行了 DH step 的帧。DH step 会 bump
        //    ratchet_gen，而接收方的 dh/kem step_recv 都门控在
        //    `header.ratchet_gen > 本地` 之后；若 KEM 在没有 DH step 的帧上
        //    单独触发，gen 不变 → 接收方两条 step_recv 全跳过 → 发送方链
        //    单方面前进 → 永久静默解密失败。因此本帧没有 DH step 可搭时，
        //    保持 kem_pending=true 推迟到下一次 DH ratchet。
        // 经典套件（post_quantum=false）永不触发 KEM 棘轮：经典 SRK 无 ML-KEM
        // 物料可供 bootstrap，short-circuit 在这里确保 want_kem 恒为 false。
        let want_kem = self.state.post_quantum
            && (self.state.kem_pending || kem_ratchet::should_set_pending(&self.state, now_unix()));
        let kem_data = if want_kem && did_dh_step_send {
            let (new_kem_pub, kem_ct) = kem_ratchet::step_send(rng, &mut self.state);
            let mut ct_arr = [0u8; crate::primitives::ml_kem::CIPHERTEXT_LEN];
            ct_arr.copy_from_slice(&kem_ct);
            Some(KemRatchetData {
                new_kem_pub,
                kem_ct: ct_arr,
            })
        } else {
            // 推迟：没有 DH step 可搭车。记住稍后仍要做 KEM ratchet。
            // （kem_ratchet::step_send 在真正执行时会自行清零 kem_pending。）
            self.state.kem_pending = want_kem;
            None
        };

        // 3. 推进 symmetric ratchet：拿 msg_key + 更新 chain。
        let mut chain = self
            .state
            .send_chain_key
            .take()
            .ok_or_else(|| Error::Internal("encrypt: send_chain_key missing".into()))?;
        let (msg_key, next_chain) = symmetric_ratchet::advance(&chain);
        chain = next_chain;
        self.state.send_chain_key = Some(chain);

        // 4. 构造 header。
        let mut nonce = [0u8; 24];
        rng.fill_bytes(&mut nonce);
        // dh_pub 现在 ALWAYS 携带（只要本端的 dh_send_x25519 已被种入）。
        // 历史上只在「本帧真做了 DH step_send」时携带 dh_pub，但这让任意
        // 链中的非首帧没法独立解密：若 send chain 的第一帧丢失，对端永远
        // 拿不到 dh_pub → step_recv 永远不触发 → 静默 `no recv chain key`
        // 失败（#201）。改成总是携带，让 send chain 的任一帧都能 standalone。
        // KEM ratchet 的搭乘条件不变（仍然门控在 did_dh_step_send），因此
        // dh_pub 携带 ≠ ratchet_gen 推进；接收端的 step_recv 门控在
        // ratchet_gen 实际推进或本端 recv_chain_key=None 的 catch-up 场景。
        let dh_pub = self
            .state
            .dh_send_x25519
            .as_ref()
            .map(crate::primitives::x25519::SecretKey::public);
        let mut flags = 0u8;
        if dh_pub.is_some() {
            flags |= FLAG_DH_RATCHET;
        }
        if kem_data.is_some() {
            flags |= FLAG_KEM_RATCHET;
        }

        let suite_id = if self.state.post_quantum {
            SUITE_PQ_HYBRID_V1
        } else {
            SUITE_CLASSICAL_V1
        };
        let header = Header {
            version: VERSION_V1,
            suite_id,
            flags,
            sid: self.state.sid,
            ratchet_gen: self.state.ratchet_gen,
            counter: self.state.send_counter,
            nonce,
            dh_pub,
            kem_data,
        };

        // 5. AAD + AEAD seal。
        let aad = build_aad(&header);
        let ciphertext = aead::seal(&msg_key, &nonce, &aad, plaintext)?;

        // 6. 更新计数器。
        self.state.send_counter = self.state.send_counter.wrapping_add(1);
        self.state.kem_messages_since_ratchet =
            self.state.kem_messages_since_ratchet.saturating_add(1);

        // 7. 拼装 L3 字节：header || (ciphertext + tag)。
        let mut out = header.to_bytes();
        out.extend_from_slice(&ciphertext);
        Ok(out)
    }

    /// 解密一条 z-base32 wire 文本，返回 plaintext（legacy V0 codec）。
    ///
    /// 新代码请优先使用 [`Session::decrypt_from_bytes`]：V1 wire 协议工作
    /// 于纯字节层，z-base32 已退役（见 [`crate::payload::decode_wire`]）。
    /// 本方法保留是为了不破坏现有的 facade 集成测试。
    ///
    /// # Errors
    /// - [`Error::Decoding`] wire 解析失败或 `sid` 不匹配
    /// - [`Error::AeadFailed`] AEAD 鉴权失败（密文损坏 / 篡改 / 密钥不匹配）
    /// - [`Error::Internal`] 状态不一致
    pub fn decrypt(&mut self, wire_text: &str) -> Result<Vec<u8>> {
        let bytes = crate::encoding::zbase32::decode(wire_text)
            .map_err(|e| Error::Decoding(e.to_string()))?;
        self.decrypt_from_bytes(&bytes)
    }

    /// V1 入口：解密一段 L3 原始密文字节（[`Session::encrypt_to_bytes`] 的逆操作）。
    ///
    /// # Errors
    /// - [`Error::Decoding`] header 解析失败或 `sid` 不匹配
    /// - [`Error::AeadFailed`] AEAD 鉴权失败（密文损坏 / 篡改 / 密钥不匹配）
    /// - [`Error::Internal`] 状态不一致
    pub fn decrypt_from_bytes(&mut self, bytes: &[u8]) -> Result<Vec<u8>> {
        // 1. 解析 header + 拆出 ciphertext。
        let (header, ciphertext) = Header::from_bytes(bytes)?;

        // 2. sid 必须匹配。
        if header.sid != self.state.sid {
            return Err(Error::Decoding("sid mismatch".into()));
        }

        // Transactional decrypt (#142): clone self.state into a mutable
        // working copy. All ratchet advances + skipped-key consumption happen
        // on `tentative`. Only after AEAD verifies do we commit
        // `tentative` back to `self.state`. On any failure (Header parse,
        // ratchet errors, AEAD AeadFailed) the tentative is dropped — both
        // the in-state SecretKey clones and any derived chain keys are
        // zeroized via their Drop impls. self.state is bit-for-bit untouched.
        //
        // This closes the "unauthenticated header poisons receiver state"
        // class of bugs the #202 catch-up branch otherwise opened: a malicious
        // wire with the right sid but a chosen dh_pub used to permanently
        // overwrite state.dh_recv_x25519_pub even when AEAD failed.
        let mut tentative = self.state.clone();

        // 3. 决定是否推进 recv 方向：
        //    (a) 新代际 (header.ratchet_gen > 本端) — 经典 DH ratchet step。
        //    (b) Catch-up: 本端 recv_chain_key 为空 + header 带 dh_pub +
        //        代际未滞后 — 新 receiver 需要在首次收到任何带 dh_pub 的
        //        wire 时执行 step_recv 建立 recv chain，否则永远卡死。
        //        这覆盖 #201：sender 在 send chain 第二帧之后才把 wire
        //        递给 receiver，sender 那帧没做 step_send（ratchet_gen 不
        //        变）但 header 仍带 dh_pub（fix 之后的总携带语义）。
        //    KEM ratchet 仍然只搭乘真正的代际推进（不在 catch-up 分支触发），
        //    避免对端重复 step_recv → root 漂移。
        if let Some(new_dh_pub) = header.dh_pub {
            let is_new_ratchet_gen = header.ratchet_gen > tentative.ratchet_gen;
            let needs_catchup_step_recv =
                tentative.recv_chain_key.is_none() && header.ratchet_gen >= tentative.ratchet_gen;
            if is_new_ratchet_gen || needs_catchup_step_recv {
                dh_ratchet::step_recv(&mut tentative, new_dh_pub)?;
                tentative.ratchet_gen = header.ratchet_gen;
                if is_new_ratchet_gen {
                    // KEM ratchet 仅在真正代际推进时同步执行；catch-up 路径
                    // sender 端没做 KEM step_send（kem_data 在 fix 之后只
                    // 出现在真正 DH step 帧上），这里也对应跳过。
                    if let Some(kem_data) = &header.kem_data {
                        kem_ratchet::step_recv(
                            &mut tentative,
                            kem_data.new_kem_pub.clone(),
                            &kem_data.kem_ct,
                        )?;
                    }
                }
            }
        }

        // 4. 拿 msg_key：先查 skipped；否则把 recv_chain 推到目标 counter，
        //    把途中跳过的 msg_key 存入 skipped buffer。
        let msg_key = if let Some(k) =
            skipped_keys::take(&mut tentative, header.ratchet_gen, header.counter)
        {
            k
        } else {
            let mut chain = tentative
                .recv_chain_key
                .take()
                .ok_or_else(|| Error::Internal("decrypt: no recv chain key".into()))?;

            // 把所有 counter < header.counter 的密钥派生出来塞进 skipped。
            while tentative.recv_counter < header.counter {
                let (mk, next) = symmetric_ratchet::advance(&chain);
                let skipped_counter = tentative.recv_counter;
                skipped_keys::store(&mut tentative, header.ratchet_gen, skipped_counter, mk);
                chain = next;
                tentative.recv_counter = tentative.recv_counter.wrapping_add(1);
            }
            // 推 chain 一步得到目标 msg_key。
            let (mk, next) = symmetric_ratchet::advance(&chain);
            tentative.recv_chain_key = Some(next);
            tentative.recv_counter = tentative.recv_counter.wrapping_add(1);
            mk
        };

        // 5. AEAD open against tentative-derived msg_key.
        let aad = build_aad(&header);
        let plaintext = aead::open(&msg_key, &header.nonce, &aad, ciphertext)?;

        // 6. AEAD verified — commit tentative state. Any prior dh_pub/kem_ct
        // mutations are now authenticated; we trust them and adopt.
        self.state = tentative;
        Ok(plaintext)
    }
}
