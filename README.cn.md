# CamoChat（陈仓）

[English](README.md) | 中文

CamoChat（陈仓）是一款端到端加密的聊天 App（iOS / Android），**没有自己的消息通道**。
它看起来和普通聊天软件一样（会话、联系人、加密 / 解密），但消息在本机加密成一段文字，
借微信、QQ 等任何聊天软件（复制粘贴 / 系统分享）送达，对方在 CamoChat 里解密。没有键盘或输入法扩展。

## 工作方式

- **文字零服务器、零网络**：身份、预密钥、配对、加密、解密全部在手机上完成，文字消息不发出任何网络请求，也没有账号。
- **收件**：密文通过 iOS Action Extension、Android `ACTION_PROCESS_TEXT` 菜单、分享或粘贴回到 App。
- **语音 / 图片 / 视频**：在本机加密成 blob 后上传到对象存储中转，聊天里只发一个加密的短引用；中转只见到密文。
  中转源码在 [`infra/media/`](infra/media/)。
- **签名配置**：App 会下载一份公开的、经 Ed25519 签名的配置文件（`chencang-config.json`，源文件在
  [`release/config/`](release/config/)），不含任何个人或设备信息。
- **配对**走带内：双方交换一条带预密钥包的短签名消息（链接、二维码卡片或文字），各自算出相同的会话，
  再核对表情指纹确认。没有服务器参与。

仅有的几处联网：富媒体中转、签名配置下载，以及 Android「direct」版本的更新下载（Play 版和 iOS 只下载配置）。

## 安全概要

完整说明见 [`docs/protocol/README.md`](docs/protocol/README.md)（以代码为准）。

- **经典套件（0x01），默认**：X25519 + Ed25519，带内 X3DH 握手并做密钥确认。
- **后量子套件（0x02）**：ML-KEM-768 + ML-DSA-65 混合。已编译进核心库，但客户端尚未启用
  （后量子公钥太大，配对消息放不进二维码或一次粘贴）。
- **棘轮**：DH 棘轮只转一步，之后消息沿对称链继续，详见协议说明 §6，不要想当然按标准 Double Ratchet 理解。
- **身份核对**：配对后双方比对表情指纹。
- AEAD 为 XChaCha20-Poly1305；核心库是纯 Rust，每个原语都有 KAT 测试。

## 目录结构

| 路径 | 内容 |
|---|---|
| `core/` | `chencang-core`：纯 Rust 密码学 / 协议库（无 I/O、无异步、无平台代码） |
| `bindings/` | UniFFI 绑定层，生成 Swift XCFramework 与 Android AAR；`bindings/xtask/` 驱动所有构建 |
| `ios/` | iOS App 与 Action Extension（工程由 XcodeGen 根据 `ios/project.yml` 生成） |
| `android/` | Android App（Gradle 模块 `:app`、`:shared`、`:design`） |
| `design/` | 设计 token（Style Dictionary），生成两端的颜色 / 尺寸 / 字体常量 |
| `infra/media/` | 富媒体密文中转（对象存储 + 上传签名 Lambda + CDN 路由） |
| `release/config/` | 已签名的公开配置（`payload.json` 为源，`chencang-config.json` 为签名产物） |
| `site/` | 静态官网（GitHub Pages） |
| `docs/protocol/` | 协议说明 |
| `scripts/` | `public-scan.sh`，仓库卫生检查 |

构建链：`core`（Rust）→ `bindings`（UniFFI）→ 客户端（Swift / Kotlin）。客户端不直接依赖 `core`，只使用生成的 XCFramework / AAR。

## 构建与测试

### Rust（core + bindings）

CI 使用 `stable` 工具链（依赖树里有 `edition = "2024"` 的 crate）。`.cargo/config.toml` 全局设置了
`RUST_MIN_STACK = 16777216`，这是必需的：ML-KEM-768 的栈缓冲区在 debug 构建下会撑爆默认的测试线程栈，请勿删除。

```sh
cargo test --workspace
cargo test -p chencang-core                          # 仅 core（单元 + 集成 + KAT）
cargo test -p chencang-core <name>                   # 按名字跑单个测试
cargo test -p chencang-core --test kat_ml_kem_768    # 单个集成测试文件
cargo clippy --all-targets --all-features -- -D warnings   # CI 门禁
cargo fmt --all                                      # CI 门禁
```

`core/tests/` 含 KAT 测试（X25519、Ed25519、XChaCha20-Poly1305、BLAKE2b、ML-KEM-768、ML-DSA-65）以及端到端 / wire / blob 往返测试。
黄金向量生成器带 `#[ignore]`（会写文件）；用 `cargo run -p xtask -- golden-vectors` 重新生成跨语言夹具
（固定种子、逐字节一致；CI 会检查 `bindings/golden-vectors/vectors.json` 没有变化）。

### 绑定与客户端产物（xtask）

```sh
cargo run -p xtask -- gen-bindings   # 重新生成 Swift + Kotlin FFI（已提交；有漂移 CI 会失败）
cargo run -p xtask -- build-ios      # -> bindings/swift/ChencangCore.xcframework（已 gitignore）
ANDROID_NDK_HOME=/path/to/ndk cargo run -p xtask -- build-android   # 需要 Android NDK 27 + cargo-ndk
```

生成的 Swift（`bindings/swift/Sources/Chencang/`）和 Kotlin（`bindings/android/.../src/main/java/`）代码已提交。
修改 `chencang.udl` 或 facade 后，需要重新生成并一并提交。

### iOS

没有 `.xcworkspace`，要先生成工程。Rust 核心以本地 SwiftPM 包的方式使用（`../bindings/swift`，产品名 `Chencang`）。

```sh
cargo run -p xtask -- build-ios      # 先构建 XCFramework（必需）
cd ios && xcodegen generate
xcodebuild -project ios/ChencangiOS.xcodeproj -scheme ChencangCompanion \
  -destination 'generic/platform=iOS Simulator' -configuration Debug CODE_SIGNING_ALLOWED=NO build
cd ios/ChencangShared && swift test --parallel   # 单元测试通过 SwiftPM 在 macOS 上跑
```

`ChencangCompanion` 内嵌 `ChencangAction`（Action Extension）；共享逻辑在 `ChencangShared` 包里。

### Android

跑 Gradle 之前，`~/.m2` 里必须已有核心 AAR（通过 `mavenLocal()` 引用，版本固定在 `android/gradle/libs.versions.toml`）。

```sh
cd android
./scripts/publish-aar-local.sh       # 执行 xtask build-android（需要 ANDROID_NDK_HOME）并发布 AAR 到本地 Maven
./gradlew assembleDirectDebug assemblePlayDebug   # 两个渠道：direct（自更新）/ play
./gradlew :shared:testDebugUnitTest :app:testDirectDebugUnitTest :app:testPlayDebugUnitTest
```

`:shared` 的 JVM 测试通过 `jna.library.path` 与 `uniffi.component.chencang.libraryOverride` 加载本机构建的原生库。
真机 / 模拟器测试需要 API 35 模拟器。发布签名读取未入库的 `android/keystore.properties`，没有则回落到 debug 签名。

### 设计 token

```sh
cd design && npm run tokens   # 重新生成 Moyu.swift / Moyu.kt / moyu_colors.xml
```

重新生成的文件要和 token 改动一起提交；`design-tokens` CI 任务发现漂移会失败。

### 公开仓库扫描

```sh
scripts/public-scan.sh        # CI 中也会与 gitleaks 一起运行
```

## 下载与验证

- Android 安装包：[GitHub Releases](https://github.com/kaitu-io/camochat/releases)
- 项目主页：[kaitu-io.github.io/camochat](https://kaitu-io.github.io/camochat/)
- 验证正版：[verify.html](https://kaitu-io.github.io/camochat/verify.html)
  列出 APK 签名证书的 SHA-256 指纹和配置文件的验签公钥，可自行核对。

## 参与贡献

欢迎提 Issue 报告问题或建议。Pull Request 由维护者审阅后合入。主要工程规则：

- **文字零网络**：不要在文字路径上加入服务器、外发请求或云依赖。
- **不写死界面数值**：颜色、尺寸、字体、时长都来自 `design/tokens/*.json`，改 token 后重新生成。
- **`core/` 是安全敏感代码**：改动（尤其是 `primitives/`、`handshake/`、`session/`、`wire/`）需要通过 KAT 验证，并经懂密码学的人审阅。
- **绑定要同步**：改了 `chencang.udl` 或 facade 之后，重新生成并提交 Swift / Kotlin 绑定。
- **保留** `.cargo/config.toml` 里的 `RUST_MIN_STACK`。
- **推送前运行 `scripts/public-scan.sh`**。不要提交密钥、签名文件、keystore、内部主机名、账号 ID 或个人信息。

## 许可证

[GNU Affero General Public License v3.0](LICENSE)（AGPL-3.0-or-later）。
