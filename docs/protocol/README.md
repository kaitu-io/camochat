# CamoChat（陈仓）协议概览

本文按**现行代码**概述 CamoChat 的密码学协议，每节标出对应的源码位置。代码是唯一权威：
本文与代码不一致时以代码为准，并欢迎提 Issue 指出。

- 协议核心：`core/src/`（纯 Rust 库，无 I/O、无异步、无平台代码）
- 跨语言 FFI：`bindings/src/facade/` + `bindings/src/chencang.udl`（UniFFI → Swift / Kotlin）

CamoChat 没有自己的消息通道：密文以文本形式经第三方 IM（复制粘贴 / 系统分享）传递。
文字消息全程不联网；语音 / 图片 / 视频加密后上传对象存储，会话里只传一个引用（见 §8）。

## 1. 原语（`core/src/primitives/`）

| 原语 | 用途 | 文件 |
|---|---|---|
| X25519 | DH（握手、DH 棘轮） | `x25519.rs` |
| Ed25519 | 身份签名、SPK 签名 | `ed25519.rs` |
| ML-KEM-768（FIPS 203） | PQ 套件的 KEM（握手、KEM 棘轮） | `ml_kem.rs` |
| ML-DSA-65（FIPS 204） | PQ 套件的签名 | `ml_dsa.rs` |
| XChaCha20-Poly1305 | 消息与媒体 blob 的 AEAD（24 字节 nonce，16 字节 tag） | `aead.rs` |
| BLAKE2b / keyed BLAKE2b / HKDF-BLAKE2b-512 | 哈希、MAC、密钥派生 | `kdf.rs` |
| Argon2id（m=64 MB, t=3, p=4） | 由口令派生 32 字节 KEK | `argon2.rs` |

另外 `core/src/payload/hkdf.rs` 使用 HKDF-Expand-SHA256 派生媒体 blob 的密钥材料（§8）。
KAT 测试位于 `core/tests/`（RFC 7748、RFC 8032、XChaCha20 draft §A.3.1、RFC 7693、FIPS 203/204）。

## 2. 两套算法套件

两套套件都**始终编译**，没有 Cargo feature 开关；套件 ID 写在每条消息的 wire 头里（§6）。

| `suite_id` | 名称 | 握手 | 棘轮 | 常量 |
|---|---|---|---|---|
| `0x01` | 经典套件（默认） | X3DH：X25519 + Ed25519 | DH 棘轮 + 对称棘轮 | `wire::header::SUITE_CLASSICAL_V1` |
| `0x02` | PQ-hybrid 套件 | PQXDH：X25519 + ML-KEM-768，Ed25519 + ML-DSA-65 | 另加 KEM 棘轮 | `wire::header::SUITE_PQ_HYBRID_V1` |

经典套件是当前客户端使用的套件：它的配对包只有约 240 字节，能放进一条聊天消息或一个二维码；
PQ 套件的公钥体积大得多（单个 ML-KEM-768 公钥 1184 字节）。

## 3. 身份与预密钥（`core/src/identity/`、`core/src/prekey/`）

- **身份密钥 IK**（`identity/keypair.rs`）：四把长期密钥 `ik_sig_ed25519`、`ik_sig_mldsa65`、
  `ik_dh_x25519`、`ik_kem_mlkem768`。经典套件的带内包只携带其中 Ed25519 + X25519 两把公钥
  （`handshake/classical_bundle.rs` 的 `ClassicalPublicIdentity`）。
- **身份指纹**（`identity/fingerprint.rs`）：16 字节 BLAKE2b，覆盖全部四把公钥，防止只替换 PQ 部分。
- **签名预密钥 SPK**（`prekey/signed.rs`）：带 `epoch` 的中期密钥。经典 SPK 的 Ed25519 签名覆盖
  `spk_x25519 || epoch`；PQ SPK 由 Ed25519 与 ML-DSA-65 双签。
- **一次性预密钥 OPK**（`prekey/one_time.rs`）：可选，用后即焚；不单独签名，篡改由 transcript 绑定发现。

## 4. 握手（`core/src/handshake/`）

角色约定：**Alice = 发起配对、发出预密钥包的一方（responder）**；**Bob = 收到包后计算会话的一方（initiator）**。

### 4.1 经典 X3DH（`handshake/x3dh.rs`，suite `0x01`）

```text
DH1 = X25519(IK_b, SPK_a)
DH2 = X25519(EK_b, IK_a)
DH3 = X25519(EK_b, SPK_a)
DH4 = X25519(EK_b, OPK_a)            # 仅当包里有 OPK
IKM = DH1 || DH2 || DH3 || [DH4]
transcript = BLAKE2b-256(version || suite || A_IK(ed,x) || A_SPK(x,sig,epoch)
                         || opk_present [|| opk.id || opk.x] || pairing_nonce
                         || B_IK(ed,x) || EK_b)
SRK = HKDF-BLAKE2b(salt = 0^32, IKM, info = "chencang-v1-x3dh-srk" || transcript)
```

transcript 各字段定长、OPK 由存在标记字节区分，因此直接拼接即为单射。`pairing_nonce`（16 字节）
经 transcript 绑入 SRK。Bob 先用 `ClassicalPreKeyBundle::verify` 验 SPK 签名，再派生。

### 4.2 PQXDH-hybrid（`handshake/pqxdh.rs`，suite `0x02`）

在上面 4×DH 的基础上，Bob 再做 3 次 ML-KEM-768 封装：对 Alice 的 SPK、IK、（可选）OPK 的 KEM 公钥。
`IKM = DH1..DH4 || ss1 || ss2 || [ss3]`；transcript 额外覆盖全部 PQ 公钥、签名与 KEM 密文
（PQ 字段带长度前缀）；`SRK = HKDF-BLAKE2b(..., info = "chencang-v1-pqxdh-srk" || transcript)`。
Bundle 由 `handshake/bundle.rs::PreKeyBundle::verify` 校验 SPK 双签。

### 4.3 密钥确认（`handshake/key_confirm.rs`）

```text
kc  = HKDF-BLAKE2b(salt = SRK, ikm = "", info = "chencang-v1-kc")
tag = keyed-BLAKE2b(kc, transcript || who)    # who: 0x42 = initiator(Bob), 0x41 = responder(Alice)
```

Bob 在第二轮消息里发出 `confirm_b`；Alice 用 `verify_confirm_tag` 常数时间校验。`who` 字节区分方向，
防止 tag 被反射。（`handshake/ack_mac.rs` 另有一个 `ack_mac` 计算，属于早期配对流程。）

## 5. 带内配对

配对不经任何服务器，两轮消息都走不可信的文本通道。

**消息内容**（`handshake/inband_cbor.rs`，整数 key 的确定性 CBOR，两端字节一致）：

| 轮次 | 方向 | 类型 | 主要字段 |
|---|---|---|---|
| Round-1 | Alice → Bob | `ClassicalPreKeyBundle`（约 241 B） | version、suite、IK(ed,x)、SPK(x,sig,epoch)、可选 OPK、`pairing_nonce`、邀请者名 |
| Round-2 | Bob → Alice | `ClassicalInbandHeader`（约 157 B） | version、suite、Bob IK(ed,x)、`EK_b`、5 字节 `session_id`、`confirm_b`、显示名 |

FFI 入口：`bindings/src/facade/x3dh.rs`（`encode/decode_classical_bundle`、`encode/decode_classical_header`、
`derive_initiator_classical`、`derive_responder_classical`、`compute/verify_confirm_tag`）。

**信封与传输形态**（客户端代码：`android/shared/.../pairing/`、`ios/ChencangShared/.../Pairing/`）：

- 信封字节 = `0xCB` || `type`（`0x01` = Round-1，`0x02` = Round-2）|| CBOR。`0xCB` 与会话消息 L2
  magic `0xCC` 区分。
- 以 §7 的 L4 文本形式发送：`🔒` + CJK14(信封字节)。
- 或作为链接：`https://<site>/p/#<base64url(信封字节)>`（无填充 base64url）。配对码在 URL 片段（`#` 之后），
  浏览器不会把片段发给服务器；同一链接可由 iOS Universal Links / Android App Links 直接唤起 App，
  也可做成二维码。
- 或作为自定义 scheme 链接：`camo://pairing/<code>`（仅 iOS 注册了 `camo://` scheme）。

配对完成后，双方各自从 SRK 派生表情指纹（§9）当面或电话核对。

## 6. 会话与棘轮（`core/src/session/`）

会话状态 `SessionState`（`session/state.rs`）可序列化落盘。初始化：

- 初始链密钥 `HKDF-BLAKE2b(SRK, "", "chencang-v1-init-chain")`：Bob（initiator）作为发送链，Alice 作为接收链。
- Bob 的发送方向预置握手临时私钥 `EK_b`；Alice 的接收方向预置 `EK_b` 公钥。

**对称棘轮**（`session/symmetric_ratchet.rs`）：

```text
msg_key        = HKDF-BLAKE2b(salt = chain_key, ikm = "", info = "chencang-v1-msg-key")
next_chain_key = HKDF-BLAKE2b(salt = chain_key, ikm = "", info = "chencang-v1-next-chain")
```

**DH 棘轮**（`session/dh_ratchet.rs`）：`(root_key, chain_key) = HKDF-BLAKE2b-512(root_key, DH 输出,
"chencang-v1-dh-ratchet")`，并递增 `ratchet_gen`。按现行 `Session::encrypt_to_bytes`，`step_send` 只在本端
**没有发送链**时触发——即 Alice 在会话中第一次发送时；之后双方都沿各自的对称链推进，不再做 DH 步。
接收端在 `ratchet_gen` 前进、或本端尚无接收链（catch-up）时执行 `step_recv`。

**KEM 棘轮**（`session/kem_ratchet.rs`，仅 suite `0x02`）：消息数 ≥ 50（`KEM_RATCHET_THRESHOLD_MSGS`）
或距上次 ≥ 7 天（`KEM_RATCHET_THRESHOLD_SECS`）时置为待执行；**只搭乘真正执行了 DH step 的那一帧**发送
（`"chencang-v1-kem-ratchet"`），没有可搭的 DH 步则保持待执行。经典套件永不触发。

**乱序与跳过**（`session/skipped_keys.rs`）：以 `(ratchet_gen, counter)` 为键缓存跳过的消息密钥，
上限 `MAX_SKIPPED = 100`，超出淘汰最早条目。

**事务式解密**：`decrypt_from_bytes` 在状态副本上推进棘轮，AEAD 校验通过后才提交；
失败时原状态逐位不变（未鉴权的头部无法污染接收状态）。

## 7. Wire 格式与文本编码

### 7.1 分层（`core/src/payload/mod.rs`）

```text
L4 文本   : "🔒"(U+1F512) + CJK14(L3 字节)          payload::encode_wire / decode_wire
L3 密文   : wire 头 || AEAD(L2)                      session::Session::encrypt_to_bytes
L2 应用帧 : [magic 0xCC][version 0x10][msg_type][flags 0x00][body]
            TEXT      (0x10): UTF-8 字节
            MEDIA_REF (0x40): count(1..=9) × [kind 1B][dur_ms u16][w u16][h u16][byte_len u32][blob_secret 32B]
```

`msg_type` 规则（`payload::decode_frame`）：`0x00` 保留；`0x30` 曾用、现保留（按未知类型处理）；
`0x01..=0x7F` 中除 `0x10`、`0x40` 外的类型报 `UnsupportedStandardMsgType`（客户端显示「暂不支持的消息」占位）；`0x80..=0xFF` 为实验区，`decode_frame` 返回 `ExperimentalMsgType` 错误；现行客户端（Android `ChatRepository`、iOS `ThreadCrypto`）对任何帧解码错误一律落一条「暂不支持的消息」占位消息（此时棘轮已推进），而不是静默丢弃。

### 7.2 L3 wire 头（`core/src/wire/header.rs`）

| 偏移 | 长度 | 字段 | 说明 |
|---|---|---|---|
| 0 | 2 | magic | `0xCC 0xC8` |
| 2 | 1 | version | `0x01` |
| 3 | 1 | suite_id | `0x01` 经典 / `0x02` PQ-hybrid |
| 4 | 1 | flags | bit0 携带 `dh_pub`；bit1 携带 KEM 棘轮数据 |
| 5 | 5 | sid | 40 位会话 ID（配对 Round-2 的 `session_id`） |
| 10 | 4 | ratchet_gen | 大端 u32 |
| 14 | 4 | counter | 大端 u32 |
| 18 | 24 | nonce | XChaCha20 nonce（每条消息随机） |
| 42 | 0 / 32 | dh_pub | flags bit0；现行代码只要本端有 DH 发送密钥就总是携带 |
| … | 0 / 1184+1088 | kem_data | flags bit1：新 ML-KEM 公钥 + KEM 密文 |

之后是 AEAD 密文 + 16 字节 tag。

### 7.3 AAD（`core/src/wire/aad.rs`）

`AAD = magic || version || suite_id || flags || sid || ratchet_gen || counter`，即头部前 18 字节。
nonce 不在 AAD 中，但改动 nonce 会使 AEAD 校验失败。可选的 `dh_pub` / `kem_data` 也不在 AAD 中：
若该帧不触发对应的棘轮步骤（按 §6，绝大多数帧都不做 DH 步），接收端不使用该字段，篡改被忽略；
若该字段被使用，篡改会派生出错误的链密钥，导致 AEAD 校验失败（并因事务式解密而不改变状态）。

### 7.4 文本编码（`core/src/encoding/`）

| 编码 | 状态 | 说明 |
|---|---|---|
| `cjk14.rs` | **现行 L4 编码** | 每字符 14 位，主表 U+4E00..=U+8DFF（16384 字），次表 U+8E00..=U+8E3F 仅用于末位补齐；全部为 CJK 统一汉字，NFC/NFKC 稳定，聊天 App 视作普通汉字 |
| `base32768.rs` | 已被取代 | 保留供参考与 golden-vector 工具 |
| `zbase32.rs` | 旧版 | 仅 `Session::encrypt` / `decrypt` 的旧接口与测试使用 |

## 8. 媒体容器与 locator（Media container and locator）

语音 / 图片 / 视频不走文本通道：发送方本地加密成 `.cca` blob 上传对象存储，会话里只发送一条
携带 `MEDIA_REF` 帧的普通加密消息（即 locator）。对象存储只见到密文和一个不可关联的 id。

### 8.1 `.cca` 容器（`core/src/blob/mod.rs`）

```text
magic[4]      = "CCA1"
version[1]    = 0x01
kind[1]       = 0x01 voice (Opus/Ogg) | 0x02 image (JPEG) | 0x03 video (MP4)
nonce[24]     = XChaCha20 nonce
cipher_len[4] = 大端 u32，必须恰好等于剩余字节数
ciphertext[N] = XChaCha20-Poly1305(明文) + 16 字节 tag
AAD           = magic || version || kind（6 字节）
```

`open_cca` 拒绝错误 magic / version、长度不符（含尾随字节）；`decrypt_media_blob` 还要求 blob 的 kind
与 `MEDIA_REF` 声明的 kind 一致。大小上限（`payload::max_blob_len_for_kind`）：语音 / 图片 2 MiB，视频 30 MiB；
时长上限 60 s；一帧最多 9 个引用。

### 8.2 密钥材料与 locator（`core/src/payload/hkdf.rs`、`core/src/payload/mod.rs`）

每个 blob 由 `encrypt_media_blob` 用系统 CSPRNG 生成一次性的 32 字节 `blob_secret`（调用方不能自带，
避免 nonce 重用），再以 HKDF-Expand-SHA256（`blob_secret` 直接作 PRK）派生：

| 输出 | 长度 | info 标签 |
|---|---|---|
| `blob_id` | 16 B | `cc/blob/v1/id` |
| `blob_key` | 32 B | `cc/blob/v1/key` |
| `blob_nonce` | 24 B | `cc/blob/v1/nonce` |

对象存储的 id 为 `media_blob_id` = base64url（无填充）的 `blob_id`，固定 22 个字符。locator 即 L2
`MEDIA_REF` 帧（每个引用 43 字节，含 `kind`、时长、宽高、`byte_len`、`blob_secret`），它与文字消息一样经
会话棘轮（§6）加密、以 `🔒` 文本发送。接收方解密得到 `blob_secret`，本地派生出 id 与密钥，下载并解密 blob。
`MediaRef` 的 `Debug` 输出会隐去 `blob_secret`。

FFI 入口：`bindings/src/facade/blob.rs`（`encrypt_media_blob`、`decrypt_media_blob`）与
`bindings/src/facade/payload.rs`（`encode_media_ref_frame`、`derive_blob_material`）。

## 9. 表情指纹（`core/src/safety/`）

```text
hash = BLAKE2b(session_secret, dkLen = 9)        # 72 位
8 × 9 位分段 → 每段索引 512 项表情字典 EMOJI_DICTIONARY
```

客户端以 SRK 作为 `session_secret`。字典由 `EMOJI_DICTIONARY_HASH`（规范 JSON 的 32 字节 BLAKE2b）锁定，
其他语言实现可据此确认字典逐字节一致。双方读出 8 个表情比对，用于发现配对时的中间人。
FFI 入口：`bindings/src/facade/safety.rs::derive_safety_emoji`。

## 10. 跨语言一致性

- `bindings/golden-vectors/vectors.json`：固定种子生成的跨语言测试向量（`cargo run -p xtask -- golden-vectors`），
  Swift / Kotlin 测试据此校验与 Rust 逐字节一致。
- CBOR、wire、帧编解码全部只在 Rust core 中实现，平台端经 UniFFI 调用，不各自手写。
