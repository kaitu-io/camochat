# CamoChat（陈仓）

端到端加密的聊天 App（iOS / Android），**没有自己的消息通道**：消息在本机加密成一段文字，
借微信、QQ 等任何聊天软件（复制粘贴 / 系统分享）送达，对方在 CamoChat 里解密。

- **文字零网络**：配对、加密、解密全部在手机上完成，文字消息不发出任何网络请求，也没有账号和服务器。
- **语音 / 图片 / 视频**：在本机加密后上传到对象存储中转，聊天里只发一个加密的引用；中转只见到密文。
  中转源码在 [`infra/media/`](infra/media/)。
- **协议**：经典套件 X25519 + Ed25519（X3DH + 对称棘轮；DH 棘轮仅在首轮执行一步，详见协议说明 §6）。
  ML-KEM-768 + ML-DSA-65 后量子套件已编译进核心库，但客户端尚未启用。
  概要见 [`docs/protocol/README.md`](docs/protocol/README.md)。

## 目录结构

| 路径 | 内容 |
|---|---|
| `core/` | `chencang-core`：纯 Rust 密码学 / 协议库（无 I/O、无异步、无平台代码） |
| `bindings/` | UniFFI 绑定层，生成 Swift XCFramework 与 Android AAR；`bindings/xtask/` 驱动所有构建 |
| `ios/` | iOS App 与 Action Extension（XcodeGen 由 `ios/project.yml` 生成工程） |
| `android/` | Android App（Gradle 模块 `:app`、`:shared`、`:design`） |
| `design/` | 设计 token（Style Dictionary），生成两端的颜色 / 尺寸 / 字体常量 |
| `infra/media/` | 富媒体密文中转（对象存储 + 上传签名 Lambda + CDN 路由） |
| `release/config/` | 已签名的公开配置文件（Ed25519 签名） |
| `site/` | 静态官网（GitHub Pages） |
| `docs/protocol/` | 协议说明 |

构建链：`core`（Rust）→ `bindings`（UniFFI）→ 客户端（Swift / Kotlin）。

## 构建

常用构建与测试命令：

```sh
cargo test --workspace                      # Rust core + bindings
cargo run -p xtask -- build-ios             # 构建 XCFramework（iOS 前置）
cargo run -p xtask -- build-android         # 构建 Android AAR（需 NDK 27 + cargo-ndk）
cd android && ./scripts/publish-aar-local.sh && ./gradlew assembleDirectDebug
cd ios && xcodegen generate                 # 生成 Xcode 工程
```

## 下载与验证

- Android 安装包：[GitHub Releases](https://github.com/kaitu-io/camochat/releases)
- 验证正版：[kaitu-io.github.io/camochat/verify.html](https://kaitu-io.github.io/camochat/verify.html)
  列出 APK 签名证书的 SHA-256 指纹和配置文件的验签公钥，可自行核对。

## 参与

欢迎提 Issue 报告问题或建议。Pull Request 由维护者审阅后合入。
涉及 `core/` 的改动属于安全敏感代码，需要通过 KAT 测试并经过密码学审阅。

## 许可证

[GNU Affero General Public License v3.0](LICENSE)（AGPL-3.0-or-later）。

---

## English

CamoChat is an end-to-end encrypted messaging app for iOS and Android that **has no channel of its own**.
Messages are encrypted on the device into a short piece of text and carried by any messenger
(WeChat, QQ, …) via copy-paste or the system share sheet; the recipient decrypts them in CamoChat.

- **Text never touches the network.** Pairing, encryption and decryption run entirely on the device —
  no accounts, no servers, no network requests for text.
- **Voice / images / video** are encrypted on the device and uploaded to an object-storage relay; the chat
  carries only an encrypted reference. The relay sees ciphertext only; its source is in `infra/media/`.
- **Protocol:** X25519 + Ed25519 with X3DH and symmetric-key ratchets (the DH ratchet steps only once,
  on the first exchange — see §6 of the protocol notes). An ML-KEM-768 + ML-DSA-65 post-quantum suite is
  compiled into the core library but not yet enabled in the apps. See [`docs/protocol/README.md`](docs/protocol/README.md) (in Chinese).

Build instructions: see the “构建” section above.
Downloads: [GitHub Releases](https://github.com/kaitu-io/camochat/releases).
Verify an APK: [verify.html](https://kaitu-io.github.io/camochat/verify.html) lists the signing-certificate
SHA-256 fingerprint and the config-signing public key.

Issues are welcome; pull requests are reviewed and merged by the maintainers.
Licensed under the [GNU AGPL v3.0](LICENSE) (AGPL-3.0-or-later).
