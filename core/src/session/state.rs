//! Double Ratchet 会话状态。
//!
//! 包含所有 ratchet 进度状态、计数器、乱序密钥缓冲。
//! 各 ratchet 模块（symmetric / dh / kem）会更新此结构。

use std::collections::HashMap;

use crate::error::{Error, Result};
use crate::primitives::{ml_kem, x25519};

/// Magic prefix for [`SessionState::serialize`] output (`0xCC` chencang + `0x02` session-blob v1).
const SESSION_STATE_MAGIC: [u8; 2] = [0xCC, 0x02];

/// Fixed-size portion of the session-state serialization (everything before optional
/// fields and skipped map). 2 magic + 5 sid + 32 root + 4×4 counters + 8 unix
/// + 1 `kem_pending` + 1 `post_quantum`.
const SESSION_STATE_FIXED_HEAD_LEN: usize = 2 + 5 + 32 + 4 + 4 + 4 + 4 + 8 + 1 + 1;

/// 单条 session 的完整状态。
///
/// 字段语义参见协议规范 §8.1。
///
/// `Clone` 是为了支持 `decrypt_from_bytes` 的 tentative-then-commit 路径
/// (#142)：解密前 clone 一份 mutable working copy，AEAD 通过才把它写回 `Session`。
/// `SecretKey` 字段克隆时复制字节；clone 和 original 各自 zeroize-on-drop，
/// 不会有 lifetime 长出原状态的密钥副本。
#[derive(Clone)]
pub struct SessionState {
    /// 永久稳定的 session ID（5 字节 = 40 bit）。
    pub sid: [u8; 5],

    /// Double Ratchet root key（持续推进）。
    pub root_key: [u8; 32],
    /// 当前 send chain key（None 表示尚未建立 send chain）。
    pub send_chain_key: Option<[u8; 32]>,
    /// 当前 recv chain key（None 表示尚未建立 recv chain）。
    pub recv_chain_key: Option<[u8; 32]>,

    /// DH ratchet 代际编号。每次方向切换 +1。
    pub ratchet_gen: u32,
    /// 当前 DH ratchet 发送私钥。
    pub dh_send_x25519: Option<x25519::SecretKey>,
    /// 当前 DH ratchet 接收公钥（对端最新）。
    pub dh_recv_x25519_pub: Option<x25519::PublicKey32>,

    /// 当前 KEM ratchet 发送私钥（搭乘 DH ratchet 时使用）。
    pub kem_send_mlkem: Option<ml_kem::SecretKey>,
    /// 当前 KEM ratchet 接收公钥（对端最新）。
    pub kem_recv_mlkem_pub: Option<ml_kem::PublicKey>,
    /// 自上次 KEM ratchet 后发送的消息数。
    pub kem_messages_since_ratchet: u32,
    /// 上次 KEM ratchet 的 Unix 时间戳（秒）。
    pub kem_ratchet_last_unix: u64,
    /// 是否标记本会话有“待消化的 KEM ratchet”（下次 DH ratchet 时一并执行）。
    pub kem_pending: bool,
    /// 本会话是否走后量子套件（suite `0x02`）。`true` = PQ-hybrid（ML-KEM
    /// 棘轮启用，wire suite `0x02`）；`false` = 经典 X25519/Ed25519
    /// （suite `0x01`，KEM 棘轮永久门控关闭，无任何 ML-KEM 物料）。
    pub post_quantum: bool,

    /// 当前 send chain 内的消息计数器。
    pub send_counter: u32,
    /// 当前 recv chain 内的消息计数器。
    pub recv_counter: u32,

    /// 乱序消息密钥缓冲。键 = (`ratchet_gen`, counter)。
    pub skipped_msg_keys: HashMap<(u32, u32), [u8; 32]>,
}

impl SessionState {
    /// 将 `SessionState` 序列化为字节串，用于本地持久化。
    ///
    /// 调用方必须用应用层 KEK 加密本输出后再写盘 — 本字节流明文包含所有 ratchet 私钥，
    /// 落盘前必须加密。
    ///
    /// 格式（变长）：
    /// `magic[2]` || `sid[5]` || `root_key[32]` ||
    /// `ratchet_gen[4]` || `send_counter[4]` || `recv_counter[4]` ||
    /// `kem_messages_since_ratchet[4]` || `kem_ratchet_last_unix[8]` ||
    /// `kem_pending[1]` || `post_quantum[1]` ||
    /// 6 个 Option 字段（每个 = 1 字节存在标志 + 内容，或单 0 字节缺席） ||
    /// `skipped_count[4]` || N × (`gen[4]` || `counter[4]` || `key[32]`)
    #[must_use]
    #[allow(clippy::redundant_closure_for_method_calls)]
    pub fn serialize(&self) -> Vec<u8> {
        let mut out = Vec::with_capacity(SESSION_STATE_FIXED_HEAD_LEN + 6 + 4);
        out.extend_from_slice(&SESSION_STATE_MAGIC);
        out.extend_from_slice(&self.sid);
        out.extend_from_slice(&self.root_key);
        out.extend_from_slice(&self.ratchet_gen.to_be_bytes());
        out.extend_from_slice(&self.send_counter.to_be_bytes());
        out.extend_from_slice(&self.recv_counter.to_be_bytes());
        out.extend_from_slice(&self.kem_messages_since_ratchet.to_be_bytes());
        out.extend_from_slice(&self.kem_ratchet_last_unix.to_be_bytes());
        out.push(u8::from(self.kem_pending));
        out.push(u8::from(self.post_quantum));

        // Fixed-size arrays' as_slice triggers clippy::redundant_closure_for_method_calls
        // but the closure form is clearer than `<[u8; 32]>::as_slice` here.
        write_opt_array(&mut out, self.send_chain_key.as_ref().map(|k| k.as_slice()));
        write_opt_array(&mut out, self.recv_chain_key.as_ref().map(|k| k.as_slice()));

        let dh_send_bytes = self
            .dh_send_x25519
            .as_ref()
            .map(x25519::SecretKey::to_bytes);
        write_opt_array(&mut out, dh_send_bytes.as_ref().map(|b| b.as_slice()));

        write_opt_array(
            &mut out,
            self.dh_recv_x25519_pub.as_ref().map(|p| p.0.as_slice()),
        );

        let kem_send_bytes = self
            .kem_send_mlkem
            .as_ref()
            .map(ml_kem::SecretKey::to_bytes);
        write_opt_array(&mut out, kem_send_bytes.as_ref().map(|b| b.as_slice()));

        write_opt_array(
            &mut out,
            self.kem_recv_mlkem_pub
                .as_ref()
                .map(|p| p.as_bytes().as_slice()),
        );

        let skipped_count = u32::try_from(self.skipped_msg_keys.len()).unwrap_or(u32::MAX);
        out.extend_from_slice(&skipped_count.to_be_bytes());

        // Stable ordering: sort by (gen, counter) so two equal session states serialize identically.
        let mut entries: Vec<(&(u32, u32), &[u8; 32])> = self.skipped_msg_keys.iter().collect();
        entries.sort_by_key(|((g, c), _)| (*g, *c));
        for ((gen, counter), key) in entries {
            out.extend_from_slice(&gen.to_be_bytes());
            out.extend_from_slice(&counter.to_be_bytes());
            out.extend_from_slice(key);
        }
        out
    }

    /// 从 [`Self::serialize`] 输出重建 `SessionState`。
    ///
    /// # Errors
    /// - [`Error::Decoding`] — 长度不足 / magic 不匹配 / 内嵌 Option 标志字节非法
    #[allow(clippy::too_many_lines)]
    pub fn deserialize(bytes: &[u8]) -> Result<Self> {
        if bytes.len() < SESSION_STATE_FIXED_HEAD_LEN {
            return Err(Error::Decoding(format!(
                "SessionState::deserialize too short: {} < {SESSION_STATE_FIXED_HEAD_LEN}",
                bytes.len()
            )));
        }
        if bytes[..2] != SESSION_STATE_MAGIC {
            return Err(Error::Decoding("SessionState magic mismatch".into()));
        }

        let mut cur = Cursor::new(bytes, 2);
        let sid: [u8; 5] = cur.read_fixed::<5>()?;
        let root_key: [u8; 32] = cur.read_fixed::<32>()?;
        let ratchet_gen = u32::from_be_bytes(cur.read_fixed::<4>()?);
        let send_counter = u32::from_be_bytes(cur.read_fixed::<4>()?);
        let recv_counter = u32::from_be_bytes(cur.read_fixed::<4>()?);
        let kem_messages_since_ratchet = u32::from_be_bytes(cur.read_fixed::<4>()?);
        let kem_ratchet_last_unix = u64::from_be_bytes(cur.read_fixed::<8>()?);
        let kem_pending = match cur.read_fixed::<1>()?[0] {
            0 => false,
            1 => true,
            other => {
                return Err(Error::Decoding(format!(
                    "kem_pending must be 0 or 1, got {other}"
                )))
            }
        };
        let post_quantum = match cur.read_fixed::<1>()?[0] {
            0 => false,
            1 => true,
            other => {
                return Err(Error::Decoding(format!(
                    "post_quantum must be 0 or 1, got {other}"
                )))
            }
        };

        let send_chain_key = cur.read_opt_fixed::<32>()?;
        let recv_chain_key = cur.read_opt_fixed::<32>()?;
        let dh_send_x25519 = cur
            .read_opt_fixed::<32>()?
            .map(x25519::SecretKey::from_bytes);
        let dh_recv_x25519_pub = cur.read_opt_fixed::<32>()?.map(x25519::PublicKey32);
        let kem_send_mlkem = cur
            .read_opt_fixed::<{ ml_kem::SECRET_KEY_LEN }>()?
            .map(ml_kem::SecretKey::from_bytes);
        let kem_recv_mlkem_pub = cur
            .read_opt_fixed::<{ ml_kem::PUBLIC_KEY_LEN }>()?
            .map(ml_kem::PublicKey::from_bytes);

        let skipped_count = u32::from_be_bytes(cur.read_fixed::<4>()?);
        let mut skipped_msg_keys: HashMap<(u32, u32), [u8; 32]> =
            HashMap::with_capacity(skipped_count as usize);
        for _ in 0..skipped_count {
            let gen = u32::from_be_bytes(cur.read_fixed::<4>()?);
            let counter = u32::from_be_bytes(cur.read_fixed::<4>()?);
            let key = cur.read_fixed::<32>()?;
            skipped_msg_keys.insert((gen, counter), key);
        }

        if !cur.is_at_end() {
            return Err(Error::Decoding(format!(
                "SessionState::deserialize trailing bytes: {} unread",
                cur.remaining()
            )));
        }

        Ok(SessionState {
            sid,
            root_key,
            send_chain_key,
            recv_chain_key,
            ratchet_gen,
            dh_send_x25519,
            dh_recv_x25519_pub,
            kem_send_mlkem,
            kem_recv_mlkem_pub,
            kem_messages_since_ratchet,
            kem_ratchet_last_unix,
            kem_pending,
            post_quantum,
            send_counter,
            recv_counter,
            skipped_msg_keys,
        })
    }

    /// 从 SRK 初始化新会话（后量子套件 `0x02`）。各 ratchet 字段为初始空状态，
    /// 由后续 ratchet 步骤填充。`post_quantum=true`，ML-KEM 棘轮启用。
    #[must_use]
    pub fn from_srk(srk: [u8; 32], sid: [u8; 5]) -> Self {
        Self::from_srk_with_suite(srk, sid, true)
    }

    /// 从经典 X3DH SRK 初始化新会话（经典套件 `0x01`）。
    ///
    /// `post_quantum=false`，KEM 棘轮永久门控关闭：经典 SRK 没有任何 ML-KEM
    /// 物料可供 bootstrap，`kem_send_mlkem`/`kem_recv_mlkem_pub` 永远为 `None`。
    /// DH 棘轮种子（X25519）由调用方在构造后填充，与 PQ 路径一致。
    #[must_use]
    pub fn from_srk_classical(srk: [u8; 32], sid: [u8; 5]) -> Self {
        Self::from_srk_with_suite(srk, sid, false)
    }

    /// Shared constructor for both suites; `post_quantum` selects the suite.
    #[must_use]
    fn from_srk_with_suite(srk: [u8; 32], sid: [u8; 5], post_quantum: bool) -> Self {
        SessionState {
            sid,
            root_key: srk,
            send_chain_key: None,
            recv_chain_key: None,
            ratchet_gen: 0,
            dh_send_x25519: None,
            dh_recv_x25519_pub: None,
            kem_send_mlkem: None,
            kem_recv_mlkem_pub: None,
            kem_messages_since_ratchet: 0,
            kem_ratchet_last_unix: now_unix(),
            kem_pending: false,
            post_quantum,
            send_counter: 0,
            recv_counter: 0,
            skipped_msg_keys: HashMap::new(),
        }
    }
}

/// Helper: 写一个 Option<fixed-size bytes> 到序列化输出。
/// `Some(bytes)` → 字节 `1` 后跟内容；`None` → 单字节 `0`。
fn write_opt_array(out: &mut Vec<u8>, value: Option<&[u8]>) {
    match value {
        Some(bytes) => {
            out.push(1);
            out.extend_from_slice(bytes);
        }
        None => out.push(0),
    }
}

/// Helper: 反序列化游标，固定长度读取 + Option<固定长度> 读取。
struct Cursor<'a> {
    bytes: &'a [u8],
    pos: usize,
}

impl<'a> Cursor<'a> {
    fn new(bytes: &'a [u8], start: usize) -> Self {
        Cursor { bytes, pos: start }
    }

    fn read_fixed<const N: usize>(&mut self) -> Result<[u8; N]> {
        if self.pos + N > self.bytes.len() {
            return Err(Error::Decoding(format!(
                "cursor underrun: need {N} at pos {}, total {}",
                self.pos,
                self.bytes.len()
            )));
        }
        let mut out = [0u8; N];
        out.copy_from_slice(&self.bytes[self.pos..self.pos + N]);
        self.pos += N;
        Ok(out)
    }

    fn read_opt_fixed<const N: usize>(&mut self) -> Result<Option<[u8; N]>> {
        let flag = self.read_fixed::<1>()?[0];
        match flag {
            0 => Ok(None),
            1 => Ok(Some(self.read_fixed::<N>()?)),
            other => Err(Error::Decoding(format!(
                "Option flag must be 0 or 1, got {other}"
            ))),
        }
    }

    fn is_at_end(&self) -> bool {
        self.pos == self.bytes.len()
    }

    fn remaining(&self) -> usize {
        self.bytes.len().saturating_sub(self.pos)
    }
}

/// 当前 Unix 时间戳（秒）。Wall-clock 偏差用相对时间在协议层补偿。
#[must_use]
pub fn now_unix() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn from_srk_initializes_root_and_sid() {
        let srk = [42u8; 32];
        let sid = [1, 2, 3, 4, 5];
        let state = SessionState::from_srk(srk, sid);
        assert_eq!(state.root_key, srk);
        assert_eq!(state.sid, sid);
    }

    #[test]
    fn from_srk_initializes_counters_to_zero() {
        let state = SessionState::from_srk([0; 32], [0; 5]);
        assert_eq!(state.ratchet_gen, 0);
        assert_eq!(state.send_counter, 0);
        assert_eq!(state.recv_counter, 0);
        assert_eq!(state.kem_messages_since_ratchet, 0);
        assert!(!state.kem_pending);
    }

    #[test]
    fn from_srk_initializes_chains_empty() {
        let state = SessionState::from_srk([0; 32], [0; 5]);
        assert!(state.send_chain_key.is_none());
        assert!(state.recv_chain_key.is_none());
        assert!(state.dh_send_x25519.is_none());
        assert!(state.dh_recv_x25519_pub.is_none());
        assert!(state.kem_send_mlkem.is_none());
        assert!(state.kem_recv_mlkem_pub.is_none());
    }

    #[test]
    fn from_srk_initializes_skipped_buffer_empty() {
        let state = SessionState::from_srk([0; 32], [0; 5]);
        assert!(state.skipped_msg_keys.is_empty());
    }

    #[test]
    fn from_srk_sets_kem_ratchet_last_unix() {
        let before = now_unix();
        let state = SessionState::from_srk([0; 32], [0; 5]);
        let after = now_unix();
        assert!(state.kem_ratchet_last_unix >= before);
        assert!(state.kem_ratchet_last_unix <= after);
    }

    #[test]
    fn serialize_deserialize_empty_state() {
        let original = SessionState::from_srk([7u8; 32], [1, 2, 3, 4, 5]);
        let bytes = original.serialize();
        let restored = SessionState::deserialize(&bytes).expect("round-trip");
        assert_eq!(restored.sid, original.sid);
        assert_eq!(restored.root_key, original.root_key);
        assert_eq!(restored.ratchet_gen, original.ratchet_gen);
        assert_eq!(restored.send_counter, original.send_counter);
        assert_eq!(restored.recv_counter, original.recv_counter);
        assert!(restored.send_chain_key.is_none());
        assert!(restored.recv_chain_key.is_none());
        assert!(restored.dh_send_x25519.is_none());
        assert!(restored.dh_recv_x25519_pub.is_none());
        assert!(restored.skipped_msg_keys.is_empty());
    }

    #[test]
    fn serialize_deserialize_populated_state() {
        let mut rng = rand::thread_rng();
        let mut original = SessionState::from_srk([42u8; 32], [9; 5]);
        original.ratchet_gen = 5;
        original.send_counter = 100;
        original.recv_counter = 95;
        original.kem_messages_since_ratchet = 30;
        original.kem_pending = true;
        // Flip off the `from_srk` default (true) so the round-trip actually
        // exercises the `post_quantum` byte rather than its default value.
        original.post_quantum = false;
        original.send_chain_key = Some([0xAAu8; 32]);
        original.recv_chain_key = Some([0xBBu8; 32]);
        original.dh_send_x25519 = Some(x25519::SecretKey::random(&mut rng));
        let dh_pub_bytes: [u8; 32] = original.dh_send_x25519.as_ref().unwrap().public().0;
        original.dh_recv_x25519_pub = Some(x25519::PublicKey32(dh_pub_bytes));
        let (kem_pub, kem_sk) = ml_kem::generate_keypair(&mut rng);
        original.kem_send_mlkem = Some(kem_sk);
        original.kem_recv_mlkem_pub = Some(kem_pub.clone());
        original.skipped_msg_keys.insert((1, 2), [0xCC; 32]);
        original.skipped_msg_keys.insert((1, 3), [0xDD; 32]);
        original.skipped_msg_keys.insert((2, 0), [0xEE; 32]);

        let bytes = original.serialize();
        let restored = SessionState::deserialize(&bytes).expect("round-trip");

        assert_eq!(restored.sid, original.sid);
        assert_eq!(restored.root_key, original.root_key);
        assert_eq!(restored.ratchet_gen, original.ratchet_gen);
        assert_eq!(restored.send_counter, original.send_counter);
        assert_eq!(restored.recv_counter, original.recv_counter);
        assert_eq!(
            restored.kem_messages_since_ratchet,
            original.kem_messages_since_ratchet
        );
        assert_eq!(restored.kem_pending, original.kem_pending);
        assert_eq!(restored.post_quantum, original.post_quantum);
        assert_eq!(restored.send_chain_key, original.send_chain_key);
        assert_eq!(restored.recv_chain_key, original.recv_chain_key);
        assert_eq!(
            restored
                .dh_send_x25519
                .as_ref()
                .map(x25519::SecretKey::to_bytes),
            original
                .dh_send_x25519
                .as_ref()
                .map(x25519::SecretKey::to_bytes)
        );
        assert_eq!(
            restored.dh_recv_x25519_pub.as_ref().map(|p| p.0),
            original.dh_recv_x25519_pub.as_ref().map(|p| p.0)
        );
        assert_eq!(
            restored
                .kem_send_mlkem
                .as_ref()
                .map(ml_kem::SecretKey::to_bytes),
            original
                .kem_send_mlkem
                .as_ref()
                .map(ml_kem::SecretKey::to_bytes)
        );
        assert_eq!(
            restored
                .kem_recv_mlkem_pub
                .as_ref()
                .map(|p| p.as_bytes().to_vec()),
            original
                .kem_recv_mlkem_pub
                .as_ref()
                .map(|p| p.as_bytes().to_vec())
        );
        assert_eq!(restored.skipped_msg_keys, original.skipped_msg_keys);
    }

    #[test]
    fn deserialize_rejects_wrong_magic() {
        let original = SessionState::from_srk([0u8; 32], [0u8; 5]);
        let mut bytes = original.serialize();
        bytes[0] = 0xFF;
        let result = SessionState::deserialize(&bytes);
        assert!(matches!(result, Err(crate::error::Error::Decoding(_))));
    }

    #[test]
    fn deserialize_rejects_trailing_bytes() {
        let original = SessionState::from_srk([0u8; 32], [0u8; 5]);
        let mut bytes = original.serialize();
        bytes.push(0); // unexpected trailing byte
        let result = SessionState::deserialize(&bytes);
        assert!(matches!(result, Err(crate::error::Error::Decoding(_))));
    }

    #[test]
    fn serialize_is_stable_under_map_ordering() {
        // HashMap iteration order is nondeterministic. Serialize() must produce
        // identical bytes regardless of insertion order, otherwise byte-exact
        // cross-platform vectors break.
        let mut a = SessionState::from_srk([0u8; 32], [0u8; 5]);
        a.skipped_msg_keys.insert((1, 2), [0xAA; 32]);
        a.skipped_msg_keys.insert((1, 3), [0xBB; 32]);
        a.skipped_msg_keys.insert((2, 0), [0xCC; 32]);

        let mut b = SessionState::from_srk([0u8; 32], [0u8; 5]);
        b.skipped_msg_keys.insert((2, 0), [0xCC; 32]);
        b.skipped_msg_keys.insert((1, 3), [0xBB; 32]);
        b.skipped_msg_keys.insert((1, 2), [0xAA; 32]);

        assert_eq!(a.serialize(), b.serialize());
    }
}
