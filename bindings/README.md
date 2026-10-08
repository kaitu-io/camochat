# chencang-bindings

Cross-platform bindings for [`chencang-core`](../core/) — the CamoChat（陈仓）protocol core (classical X3DH + symmetric ratchets by default; see [`docs/protocol/README.md`](../docs/protocol/README.md)). The same Rust facade is exposed to Swift (iOS/macOS) and Kotlin (Android) via [uniffi](https://github.com/mozilla/uniffi-rs) 0.31, so all three platforms produce byte-identical wire output for identical inputs (proven by the `golden-vectors` test fixture).

> Status: **v0.1.0-rc1**, internal preview. Versions move in lockstep with `chencang-core`.

## Supported platforms

| Platform | Distribution | Minimum OS |
|---|---|---|
| iOS    | Swift Package Manager, binary XCFramework target | iOS 16, macOS 12 |
| Android | AAR (Maven Local for dev, GitHub Packages for release) | API 26 (Android 8.0) |

ABIs shipped in the AAR: `arm64-v8a`, `armeabi-v7a`, `x86_64`. The XCFramework includes device + simulator slices for `aarch64-apple-ios`, `aarch64-apple-ios-sim`, `x86_64-apple-ios`, plus macOS slices `aarch64-apple-darwin` and `x86_64-apple-darwin`.

## Install

### Swift Package Manager

In Xcode → File → Add Packages → enter the repository URL, select the desired `bindings-v*` version, and add the `Chencang` library product to your target. For local development against an in-tree XCFramework, depend on the local path:

```swift
.package(path: "../bindings/swift")
```

See [docs/INTEGRATION.md](./docs/INTEGRATION.md) for production setup including binary distribution and SwiftPM `.binaryTarget` checksumming.

### Gradle (Android)

Add the Maven Local repository while iterating, then publish into it from this repo with:

```sh
cd bindings/android
./gradlew :chencang-core-android:publishReleasePublicationToMavenLocalRepository
```

Consumer `build.gradle.kts`:

```kotlin
repositories {
    mavenLocal()
    mavenCentral()
}
dependencies {
    implementation("app.chencang:chencang-core-android:0.1.0-rc1")
    implementation("net.java.dev.jna:jna:5.14.0@aar") // uniffi runtime
}
```

GitHub Packages publishing is wired up in `bindings-release.yml` (dry-run today). See [docs/INTEGRATION.md](./docs/INTEGRATION.md) for the Android section.

## Quickstart

A short "hello world" lives in [docs/QUICKSTART.md](./docs/QUICKSTART.md). It covers identity generation, the two-round in-band classical pairing with key confirmation, and a full encrypt → wire → decrypt round trip.

## Public API

The uniffi facade is defined in [`src/chencang.udl`](./src/chencang.udl). Main groups:

- Interfaces: `SecretIdentity`, `SecretSignedPreKey`, `SecretOneTimePreKey`, `Session`.
- Classical (suite `0x01`) pairing — what the apps use: `derive_initiator_classical`, `derive_responder_classical`,
  `compute_confirm_tag` / `verify_confirm_tag`, `encode/decode_classical_bundle`, `encode/decode_classical_header`,
  and the `Session.*_after_handshake_classical` constructors.
- Message wire: `encode_text_frame`, `encode_media_ref_frame`, `decode_frame`, `encode_wire` / `decode_wire`;
  `Session.encrypt_to_bytes` / `decrypt_from_bytes`.
- Media blobs: `encrypt_media_blob`, `decrypt_media_blob`, `derive_blob_material`.
- Safety fingerprint: `derive_safety_emoji`. Signed config: `verify_signed_config`.
- PQ-hybrid (suite `0x02`) handshake: `derive_initiator_handshake` / `derive_responder_handshake` — compiled in, not used by the apps yet.

All fallible operations throw `ChencangError` (Swift) / `ChencangException` (Kotlin) with one of: `InvalidLength`, `UnsupportedSuite`, `UnsupportedVersion`, `AeadFailed`, `SignatureFailed`, `Decoding`, `Internal`. Error mapping is documented in [docs/INTEGRATION.md](./docs/INTEGRATION.md#error-mapping).

## Regenerating bindings after a facade change

The Swift `.swift` file and Kotlin `.kt` file are committed; CI fails on drift. After editing `src/chencang.udl` or any `#[uniffi::export]`-touching Rust code, regenerate from the workspace root:

```sh
cargo run -p xtask -- gen-bindings        # Swift + Kotlin source
cargo run -p xtask -- build-ios           # XCFramework (macOS only)
cargo run -p xtask -- build-android       # AAR (requires Android NDK 27)
cargo run -p xtask -- golden-vectors      # regenerates fixture & copies to Android assets
```

## Testing

```sh
# Rust unit + integration
cargo test --workspace

# Swift (requires XCFramework already built)
cd bindings/swift && swift test

# Kotlin instrumented (requires Android API 35 emulator running)
cd bindings/android && ./gradlew :chencang-core-android:connectedAndroidTest

# Encrypt throughput bench
cargo bench -p chencang-bindings --bench encrypt_throughput
```

The cross-platform equivalence gate lives in `golden-vectors/vectors.json`. Rust generates it, both Swift and Kotlin tests load it, and the CI workflow asserts that two consecutive regenerations produce byte-identical output.

## Repository layout

```
bindings/
├── src/                              # Rust facade crate
│   ├── chencang.udl                  # uniffi public surface
│   ├── lib.rs                        # facade glue around chencang-core
│   └── ...
├── swift/                            # Swift Package
│   ├── Package.swift
│   ├── ChencangCore.xcframework/     # built artifact (gitignored)
│   └── Sources/Chencang/chencang.swift   # uniffi-generated
├── android/                          # Gradle project producing the AAR
│   └── chencang-core-android/
├── benches/encrypt_throughput.rs     # criterion bench
├── golden-vectors/vectors.json       # cross-platform fixture
├── xtask/                            # build automation (cargo run -p xtask -- ...)
└── docs/
    ├── QUICKSTART.md
    └── INTEGRATION.md
```

## License

AGPL-3.0-or-later, same as `chencang-core`. See the top-level [`LICENSE`](../LICENSE).
