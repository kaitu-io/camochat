# Host-app integration guide

Production concerns that go beyond [QUICKSTART.md](./QUICKSTART.md): binary distribution, threading, error mapping, and the release tagging procedure.

## iOS — SwiftPM with a binary XCFramework

The local `Package.swift` consumes `ChencangCore.xcframework` from the same directory. For consumers outside this monorepo we ship a tag-attached zip and a SwiftPM checksum.

### Local development (against the in-tree XCFramework)

```sh
# From workspace root:
cargo run -p xtask -- build-ios       # produces swift/ChencangCore.xcframework
cd bindings/swift
swift test                            # XCTest target proves the FFI works
```

Consumers depend on the local package path:

```swift
.package(path: "../bindings/swift")
```

### Release distribution

The `bindings-release.yml` workflow zips the XCFramework as `ChencangCore-<tag>.xcframework.zip` and attaches it to the GitHub Release. Consumers reference it as a remote binary target:

```swift
.binaryTarget(
    name: "ChencangCore",
    url: "https://github.com/<owner>/<repo>/releases/download/bindings-v0.1.0-rc1/ChencangCore-bindings-v0.1.0-rc1.xcframework.zip",
    checksum: "<paste the sha256 from the release notes>"
)
```

The SHA-256 of the zip is logged in both the workflow output and the release notes table; verify it with `swift package compute-checksum ChencangCore-*.xcframework.zip` before pinning.

### Module layout

`ChencangCore.xcframework` exposes a clang module named `chencangFFI` (declared by the modulemap inside each slice). The thin Swift target `Chencang` re-exports that as the `Chencang` module and is the only thing app code should import. **Do not** `import chencangFFI` directly — the C-typed FFI is not API.

## Android — Gradle + Maven

### Local development (Maven Local)

```sh
# From workspace root, one-time per facade change:
cargo run -p xtask -- build-android   # builds .so for 3 ABIs + Kotlin + AAR

cd bindings/android
./gradlew :chencang-core-android:publishReleasePublicationToMavenLocalRepository
```

Consumer `build.gradle.kts`:

```kotlin
repositories {
    mavenLocal()           // dev
    mavenCentral()         // for jna
}
dependencies {
    implementation("app.chencang:chencang-core-android:0.1.0-rc1")
    implementation("net.java.dev.jna:jna:5.14.0@aar") // uniffi runtime
}
```

### Release publishing (GitHub Packages, dry-run today)

`bindings-release.yml` runs the AAR build on `bindings-v*` tag push and logs the publish command in dry-run form. To enable real publishing:

1. Confirm packaging coordinates (`groupId`, `artifactId`) with the release manager.
2. Replace the dry-run `cat <<EOF` step in `.github/workflows/bindings-release.yml` with the actual gradle invocation:
   ```sh
   ./gradlew :chencang-core-android:publishReleasePublicationToGitHubPackagesRepository \
     -PgprUser="$GITHUB_ACTOR" \
     -PgprKey="$GITHUB_TOKEN"
   ```
3. Add the corresponding `repositories { maven { url = "https://maven.pkg.github.com/<owner>/<repo>"; credentials { ... } } }` block to `chencang-core-android/build.gradle.kts`.

### ABIs

The AAR ships three: `arm64-v8a` (all modern phones), `armeabi-v7a` (pre-2019 budget devices), `x86_64` (emulators + Chromebooks). Dropping `armeabi-v7a` would save ≈2MB; the V1 release keeps it for emulator-free CI lanes that test on Android Studio's default x86_64 emulator and for the long tail of 32-bit Tencent devices still in the field.

## Threading model

- All facade functions are **synchronous and CPU-bound**. The expensive ones — key generation (`SecretIdentity()` also generates the ML-KEM-768 / ML-DSA-65 keys), `deriveInitiatorClassical` / `deriveResponderClassical`, and media-blob encryption — should run off the main thread to avoid jank. Wrap calls in `Task.detached { ... }` (Swift) or `withContext(Dispatchers.Default) { ... }` (Kotlin).
- `Session.encryptToBytes` and `Session.decryptFromBytes` are fast (≈6 µs and ≈4 µs p50 per the `encrypt_throughput` bench on Apple Silicon Rust release builds) but still hit OS-level RNG for the per-message nonce on encrypt. Treat them as cheap-but-blocking and keep them off the UI thread when batching.
- A single `Session` instance is **not thread-safe**. Each session must be confined to one queue/dispatcher. If you must call from multiple threads, serialize through an actor (Swift) or a single-threaded coroutine context (Kotlin).
- `SecretIdentity`, `SecretSignedPreKey`, `SecretOneTimePreKey` are read-only after construction and safe to share.

## Error mapping

The Rust enum `ChencangError` maps directly to platform errors via uniffi. Catch the platform error type — never inspect message strings, they are not stable.

| Rust variant       | Swift                                | Kotlin                                       | When you'll see it |
|---|---|---|---|
| `InvalidLength`    | `ChencangError.InvalidLength`        | `ChencangException.InvalidLength`            | Caller passed a wrong-sized key/MAC/ciphertext. Almost always a programmer error. |
| `UnsupportedSuite` | `ChencangError.UnsupportedSuite`     | `ChencangException.UnsupportedSuite`         | Wire byte declares a suite id we don't implement. Peer is on a newer/incompatible build. |
| `UnsupportedVersion` | `ChencangError.UnsupportedVersion` | `ChencangException.UnsupportedVersion`       | Wire byte declares a protocol version we don't speak. Peer is on V2+. |
| `AeadFailed`       | `ChencangError.AeadFailed`           | `ChencangException.AeadFailed`               | XChaCha20-Poly1305 verify failed — tampered ciphertext, wrong key, or out-of-window message. |
| `SignatureFailed`  | `ChencangError.SignatureFailed`      | `ChencangException.SignatureFailed`          | Ed25519 or ML-DSA-65 signature did not verify — bundle was tampered with, or you have a stale SPK. |
| `Decoding`         | `ChencangError.Decoding`             | `ChencangException.Decoding`                 | Wire text is not valid `🔒` + CJK14, has the wrong framing, or claims an impossible length. |
| `Internal`         | `ChencangError.Internal`             | `ChencangException.Internal`                 | Should never fire in production. File a bug. |

Recovery guidance:
- **Decoding / AeadFailed on first decrypt**: not your fault — message was corrupted in transit. Ask the user to re-paste.
- **SignatureFailed on pairing**: the pasted invite bundle was corrupted or tampered with in transit (SPK signature did not verify). Ask the inviter to send a fresh invite.
- **UnsupportedSuite / UnsupportedVersion**: surface a "your app is too old, please update" message.
- **InvalidLength / Internal**: report as a crash / bug — keys live in the keychain so the only failure mode is a code bug.

## Persisting state across launches

```swift
// On app launch
if let blob = keychain.read("chencang_ik") {
    let ik = try SecretIdentity(data: blob)
} else {
    let ik = SecretIdentity()
    keychain.write("chencang_ik", ik.serializeForLocalStorage())
}
```

```kotlin
// On app launch
val ik = encryptedPrefs.getByteArray("chencang_ik")?.let { SecretIdentity.fromLocalStorage(it) }
    ?: SecretIdentity().also { encryptedPrefs.putByteArray("chencang_ik", it.serializeForLocalStorage()) }
```

Identity blobs are ≈3.2 KiB (X25519 + Ed25519 + ML-KEM-768 + ML-DSA-65 secret material). Session state blobs grow with the skipped-key window; expect 1–8 KiB.

## Release tagging procedure

Releases are cut from `main` after CI is green. **Do not** publish a tag from a feature branch.

```sh
# 1. Confirm CI on main is green
gh run list --workflow=bindings-ci.yml --branch=main --limit=1

# 2. Bump versions in lockstep
#    - bindings/Cargo.toml           (version = "0.1.0")
#    - bindings/android/chencang-core-android/build.gradle.kts (version = "0.1.0")
#    - bindings/docs/* references to the version (search-replace)

# 3. Tag (annotated, signed)
git tag -s -a bindings-v0.1.0 -m "chencang-bindings v0.1.0"

# 4. Push
git push origin bindings-v0.1.0
```

The push triggers `.github/workflows/bindings-release.yml`, which:

1. Builds the XCFramework on `macos-14`.
2. Builds the AAR on `ubuntu-24.04` (Android SDK + NDK 27, cargo-ndk).
3. Computes SHA-256 of both artifacts.
4. Logs the GitHub Packages publish command (dry-run; flip to real per the section above).
5. Creates a GitHub Release with both artifacts, their `.sha256` sidecar files, and a release-notes table containing the checksums.

Pre-release tags (`-rc`, `-beta`, `-alpha`) are auto-marked as `prerelease: true` and won't be the default download.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `import Chencang` not found in Xcode | Stale Derived Data after rebuilding the XCFramework | Product → Clean Build Folder, then re-resolve packages |
| `java.lang.UnsatisfiedLinkError: libuniffi_chencang.so` | AAR was built without one of the ABIs | Re-run `cargo run -p xtask -- build-android`; verify all three ABIs under `src/main/jniLibs/` |
| `cargo run -p xtask -- build-android` fails with `linker: command not found` | `ANDROID_NDK_HOME` unset or pointing at an older NDK | Install NDK 27.1.12297006; export `ANDROID_NDK_HOME=$ANDROID_SDK_ROOT/ndk/27.1.12297006` |
| Swift tests pass on macOS but fail on iOS device | XCFramework rebuilt without the device slice | Run `cargo run -p xtask -- build-ios` (it always rebuilds all 5 slices) |
| Kotlin instrumented tests time out in CI | We deliberately skip them — emulator is too slow for PR CI | Run locally against an API 35 emulator |
