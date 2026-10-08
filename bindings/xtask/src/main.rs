//! `xtask` — automation for the chencang-bindings crate.
//!
//! Invocation pattern: `cargo run -p xtask -- <subcommand>` from the workspace
//! root. All paths below are resolved relative to the workspace root so the
//! commands are reproducible from CI without any cd-dancing.

use std::path::{Path, PathBuf};

use anyhow::{Context, Result};
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;
use chencang_core::primitives::ed25519::SigningKey;
use clap::Parser;
use duct::cmd;

#[derive(Parser)]
#[command(name = "xtask", about = "Build automation for chencang-bindings")]
enum Cli {
    /// Generate Swift + Kotlin bindings into committed locations
    GenBindings,
    /// Build XCFramework
    BuildIos,
    /// Build Android AAR
    BuildAndroid,
    /// Run all binding tests
    Test,
    /// (Re)generate golden vectors
    GoldenVectors,
    /// Generate a config-signing Ed25519 key (32-byte seed, mode 0600); prints the public key hex
    GenConfigKey {
        /// Output path for the private seed (refuses to overwrite)
        #[arg(long)]
        out: PathBuf,
    },
    /// Sign a config payload JSON file into a `{"p","s"}` envelope
    SignConfig {
        /// Path to the 32-byte private seed
        #[arg(long)]
        key: PathBuf,
        /// Payload JSON file
        payload: PathBuf,
        /// Output envelope path
        #[arg(long)]
        out: PathBuf,
    },
}

fn main() -> Result<()> {
    let cli = Cli::parse();
    match cli {
        Cli::GenBindings => gen_bindings(),
        Cli::BuildIos => build_ios(),
        Cli::BuildAndroid => build_android(),
        Cli::Test => test_all(),
        Cli::GoldenVectors => generate_vectors(),
        Cli::GenConfigKey { out } => gen_config_key(&out),
        Cli::SignConfig { key, payload, out } => sign_config(&key, &payload, &out),
    }
}

/// Domain-separation prefix; must match `CONFIG_DOMAIN` in the bindings facade.
const CONFIG_DOMAIN: &[u8] = b"chencang-config-v1\n";

fn gen_config_key(out: &Path) -> Result<()> {
    use std::io::Write;
    use std::os::unix::fs::OpenOptionsExt;

    let sk = SigningKey::generate(&mut rand::rngs::OsRng);
    let mut f = std::fs::OpenOptions::new()
        .write(true)
        .create_new(true)
        .mode(0o600)
        .open(out)
        .with_context(|| format!("create {} (refuses to overwrite)", out.display()))?;
    f.write_all(&sk.to_bytes())?;
    println!("{}", hex_encode(&sk.verifying_key().0));
    Ok(())
}

fn hex_encode(b: &[u8]) -> String {
    use std::fmt::Write;
    b.iter().fold(String::new(), |mut s, x| {
        let _ = write!(s, "{x:02x}");
        s
    })
}

/// Sign `payload` with the seed and return the envelope JSON `{"p":..,"s":..}`
/// (both base64url, no padding). The signature covers `CONFIG_DOMAIN || payload`.
fn sign_envelope(seed: [u8; 32], payload: &[u8]) -> String {
    let sk = SigningKey::from_bytes(seed);
    let mut msg = CONFIG_DOMAIN.to_vec();
    msg.extend_from_slice(payload);
    let sig = sk.sign(&msg);
    serde_json::json!({
        "p": URL_SAFE_NO_PAD.encode(payload),
        "s": URL_SAFE_NO_PAD.encode(sig),
    })
    .to_string()
}

fn sign_config(key: &Path, payload: &Path, out: &Path) -> Result<()> {
    let seed_bytes = std::fs::read(key).with_context(|| format!("read {}", key.display()))?;
    let seed: [u8; 32] = seed_bytes
        .as_slice()
        .try_into()
        .map_err(|_| anyhow::anyhow!("key file must be exactly 32 bytes"))?;
    let payload_bytes =
        std::fs::read(payload).with_context(|| format!("read {}", payload.display()))?;
    serde_json::from_slice::<serde_json::Value>(&payload_bytes)
        .context("payload is not valid JSON")?;
    std::fs::write(out, sign_envelope(seed, &payload_bytes))?;
    eprintln!("[sign-config] wrote {}", out.display());
    Ok(())
}

/// The chencang-bindings crate root, used as cwd for `cargo run` invocations.
fn bindings_dir() -> PathBuf {
    // CARGO_MANIFEST_DIR is set by cargo and points at bindings/xtask.
    // The parent is bindings/.
    let manifest = Path::new(env!("CARGO_MANIFEST_DIR"));
    manifest
        .parent()
        .expect("bindings/xtask must have a parent")
        .to_path_buf()
}

fn gen_bindings() -> Result<()> {
    let bindings = bindings_dir();
    let udl = bindings.join("src/chencang.udl");
    let swift_out = bindings.join("swift/Sources/Chencang/");
    let kotlin_out = bindings.join("android/chencang-core-android/src/main/java/");

    std::fs::create_dir_all(&swift_out)?;
    std::fs::create_dir_all(&kotlin_out)?;

    // Build the bindings staticlib first so library-mode + UDL-mode
    // bindgen invocations can both work without rebuilding.
    cmd!("cargo", "build", "-p", "chencang-bindings").run()?;

    cmd!(
        "cargo",
        "run",
        "-p",
        "chencang-bindings",
        "--bin",
        "uniffi-bindgen",
        "--",
        "generate",
        "--language",
        "swift",
        "--out-dir",
        swift_out.to_str().expect("utf-8 path"),
        udl.to_str().expect("utf-8 path"),
    )
    .run()?;

    cmd!(
        "cargo",
        "run",
        "-p",
        "chencang-bindings",
        "--bin",
        "uniffi-bindgen",
        "--",
        "generate",
        "--language",
        "kotlin",
        "--out-dir",
        kotlin_out.to_str().expect("utf-8 path"),
        udl.to_str().expect("utf-8 path"),
    )
    .run()?;

    Ok(())
}

/// Locate a cargo executable served by rustup. Falls back to `cargo` on PATH
/// if the canonical location isn't found, which keeps the helper usable on
/// machines where rustup *is* the default toolchain.
fn which_rustup_cargo() -> String {
    if let Some(home) = std::env::var_os("HOME") {
        let candidate = PathBuf::from(&home).join(".cargo/bin/cargo");
        if candidate.exists() {
            return candidate.to_string_lossy().into_owned();
        }
    }
    "cargo".to_string()
}

/// Locate rustup's `rustc` shim. Returns `None` when rustup isn't installed,
/// in which case we assume the PATH `rustc` is the right one. Used to override
/// the `RUSTC` env var so cross-compilation picks up rustup's iOS stdlibs even
/// when a non-rustup `rustc` (e.g. Homebrew's) appears earlier on PATH.
fn which_rustup_rustc() -> Option<String> {
    if let Some(home) = std::env::var_os("HOME") {
        let candidate = PathBuf::from(&home).join(".cargo/bin/rustc");
        if candidate.exists() {
            return Some(candidate.to_string_lossy().into_owned());
        }
    }
    None
}

fn build_ios() -> Result<()> {
    let bindings = bindings_dir();
    let workspace = bindings
        .parent()
        .expect("chencang-bindings must have a workspace root parent");
    let target_dir = workspace.join("target");

    let ios_device_target = "aarch64-apple-ios";
    let ios_sim_targets = ["aarch64-apple-ios-sim", "x86_64-apple-ios"];
    let macos_targets = ["aarch64-apple-darwin", "x86_64-apple-darwin"];

    // Build per-arch static libs. Sequential to keep memory usage in check on
    // macOS; the targets compile a lot of cryptography code.
    let all_targets: Vec<&str> = std::iter::once(ios_device_target)
        .chain(ios_sim_targets.iter().copied())
        .chain(macos_targets.iter().copied())
        .collect();
    // Cross-compilation must go through a toolchain that ships the iOS/macOS
    // std libs. On this developer machine the `cargo`/`rustc` on PATH are
    // Homebrew rustc 1.95 with no preinstalled iOS std; rustup-managed
    // `stable` does. We therefore force rustup's cargo *and* rustc here.
    let rustup_cargo = which_rustup_cargo();
    let rustup_rustc = which_rustup_rustc();
    for target in &all_targets {
        eprintln!("[build-ios] cargo build --release --target {target}");
        let mut expr = cmd!(
            &rustup_cargo,
            "build",
            "--release",
            "--target",
            target,
            "-p",
            "chencang-bindings",
        );
        if let Some(rustc) = rustup_rustc.as_deref() {
            expr = expr.env("RUSTC", rustc);
            expr = expr.env("RUSTUP_TOOLCHAIN", "stable");
        }
        expr.run()?;
    }

    let staticlib_name = "libchencang_bindings.a";

    let ios_sim_universal = target_dir.join("libchencang_bindings_ios_sim_universal.a");
    let macos_universal = target_dir.join("libchencang_bindings_macos_universal.a");

    // Lipo simulator arches into a single fat static lib.
    let _ = std::fs::remove_file(&ios_sim_universal);
    cmd!(
        "lipo",
        "-create",
        target_dir
            .join(ios_sim_targets[0])
            .join("release")
            .join(staticlib_name),
        target_dir
            .join(ios_sim_targets[1])
            .join("release")
            .join(staticlib_name),
        "-output",
        &ios_sim_universal,
    )
    .run()?;

    // Lipo macOS arches into a universal binary.
    let _ = std::fs::remove_file(&macos_universal);
    cmd!(
        "lipo",
        "-create",
        target_dir
            .join(macos_targets[0])
            .join("release")
            .join(staticlib_name),
        target_dir
            .join(macos_targets[1])
            .join("release")
            .join(staticlib_name),
        "-output",
        &macos_universal,
    )
    .run()?;

    // Generate Swift bindings (sources + FFI header + modulemap) into build/headers/.
    // uniffi 0.31 emits chencang.swift, chencangFFI.h, chencangFFI.modulemap together.
    let headers_dir = bindings.join("build/headers");
    let _ = std::fs::remove_dir_all(&headers_dir);
    std::fs::create_dir_all(&headers_dir)?;

    let udl = bindings.join("src/chencang.udl");
    cmd!(
        "cargo",
        "run",
        "-p",
        "chencang-bindings",
        "--bin",
        "uniffi-bindgen",
        "--",
        "generate",
        "--language",
        "swift",
        "--out-dir",
        headers_dir.to_str().expect("utf-8 path"),
        udl.to_str().expect("utf-8 path"),
    )
    .run()?;

    // For an XCFramework with library + headers form, we need a directory that
    // contains *only* the C header(s) plus a `module.modulemap`. uniffi emits
    // the modulemap as `chencangFFI.modulemap` — xcodebuild wants exactly
    // `module.modulemap`, so rename. Also remove the generated `.swift` so it
    // doesn't get bundled as a header (it lives in swift/Sources/Chencang/
    // instead).
    let ffi_modulemap = headers_dir.join("chencangFFI.modulemap");
    let module_modulemap = headers_dir.join("module.modulemap");
    if ffi_modulemap.exists() {
        std::fs::rename(&ffi_modulemap, &module_modulemap)?;
    }
    let stray_swift = headers_dir.join("chencang.swift");
    if stray_swift.exists() {
        std::fs::remove_file(&stray_swift)?;
    }

    // Also refresh the committed Swift bridge so swift/Sources/Chencang/chencang.swift
    // stays in sync with the staticlib we just built. Re-run bindgen targeted
    // at the swift sources directory; the FFI header & modulemap that land
    // there are harmless extras (the Package.swift target only picks up
    // chencang.swift).
    let swift_sources = bindings.join("swift/Sources/Chencang");
    std::fs::create_dir_all(&swift_sources)?;
    cmd!(
        "cargo",
        "run",
        "-p",
        "chencang-bindings",
        "--bin",
        "uniffi-bindgen",
        "--",
        "generate",
        "--language",
        "swift",
        "--out-dir",
        swift_sources.to_str().expect("utf-8 path"),
        udl.to_str().expect("utf-8 path"),
    )
    .run()?;

    // Build XCFramework — one slice per platform variant.
    let xcframework_path = bindings.join("swift/ChencangCore.xcframework");
    let _ = std::fs::remove_dir_all(&xcframework_path);

    cmd!(
        "xcodebuild",
        "-create-xcframework",
        "-library",
        target_dir
            .join(ios_device_target)
            .join("release")
            .join(staticlib_name),
        "-headers",
        &headers_dir,
        "-library",
        &ios_sim_universal,
        "-headers",
        &headers_dir,
        "-library",
        &macos_universal,
        "-headers",
        &headers_dir,
        "-output",
        &xcframework_path,
    )
    .run()?;

    eprintln!(
        "[build-ios] XCFramework built: {}",
        xcframework_path.display()
    );
    Ok(())
}

fn build_android() -> Result<()> {
    let bindings = bindings_dir();
    let android_libs_root = bindings.join("android/chencang-core-android/src/main/jniLibs");

    // Plan deviation: cargo-zigbuild 0.22.3 + zig 0.16 cannot resolve Android
    // -ldl/-llog/-lm system stubs without NDK paths anyway, and its target
    // suffix syntax (`aarch64-linux-android.21`) is rejected. cargo-ndk is the
    // de-facto replacement: it points clang/lld at the real NDK
    // sysroot+toolchain. We require `ANDROID_NDK_HOME` to point at NDK 27.
    let ndk_home = std::env::var("ANDROID_NDK_HOME").map_err(|_| {
        anyhow::anyhow!(
            "ANDROID_NDK_HOME is not set; point it at an Android NDK 27 install, e.g. \
             export ANDROID_NDK_HOME=$ANDROID_SDK_ROOT/ndk/27.1.12297006"
        )
    })?;
    // Cross-compilation must go through a toolchain that ships Android std
    // libs. The same dance the iOS builder does — force rustup-managed
    // `stable` so the homebrew rustc on PATH doesn't get picked up.
    let rustup_cargo = which_rustup_cargo();
    let rustup_rustc = which_rustup_rustc();

    let android_abis = ["arm64-v8a", "armeabi-v7a", "x86_64"];

    eprintln!(
        "[build-android] cargo ndk -t {} -P 26 -o {} build --release",
        android_abis.join(" -t "),
        android_libs_root.display()
    );
    let mut expr = cmd!(
        &rustup_cargo,
        "ndk",
        "-t",
        android_abis[0],
        "-t",
        android_abis[1],
        "-t",
        android_abis[2],
        "-P",
        "26",
        "-o",
        android_libs_root.to_str().expect("utf-8 path"),
        "build",
        "--release",
        "-p",
        "chencang-bindings",
    )
    .env("ANDROID_NDK_HOME", &ndk_home);
    if let Some(rustc) = rustup_rustc.as_deref() {
        expr = expr.env("RUSTC", rustc);
        expr = expr.env("RUSTUP_TOOLCHAIN", "stable");
    }
    expr.run()?;

    // cargo-ndk drops the Rust crate's cdylib as `libchencang_bindings.so`,
    // but the uniffi-generated Kotlin loads `System.loadLibrary("uniffi_chencang")`
    // (see findLibraryName() in the generated chencang.kt). Rename the file
    // in each ABI directory so the JNA / JNI linker can find it.
    for abi in &android_abis {
        let abi_dir = android_libs_root.join(abi);
        let src = abi_dir.join("libchencang_bindings.so");
        let dst = abi_dir.join("libuniffi_chencang.so");
        if src.exists() {
            std::fs::rename(&src, &dst)?;
        } else if !dst.exists() {
            anyhow::bail!(
                "expected JNI lib not found for ABI {abi}: neither {} nor {}",
                src.display(),
                dst.display()
            );
        }
    }

    // Re-generate Kotlin bindings into the committed location.
    let kotlin_out = bindings.join("android/chencang-core-android/src/main/java/");
    std::fs::create_dir_all(&kotlin_out)?;
    let udl = bindings.join("src/chencang.udl");
    cmd!(
        "cargo",
        "run",
        "-p",
        "chencang-bindings",
        "--bin",
        "uniffi-bindgen",
        "--",
        "generate",
        "--language",
        "kotlin",
        "--out-dir",
        kotlin_out.to_str().expect("utf-8 path"),
        udl.to_str().expect("utf-8 path"),
    )
    .run()?;

    // Build AAR via Gradle wrapper.
    // NOTE: pass the wrapper by ABSOLUTE path. duct resolves a relative
    // program path (`./gradlew`) against the *parent* process cwd, not the
    // `.dir()` we set — from the workspace root that yields ENOENT. `.dir()`
    // is still required so Gradle finds settings.gradle.kts in android/.
    let android_dir = bindings.join("android");
    cmd!(
        android_dir.join("gradlew"),
        ":chencang-core-android:assembleRelease"
    )
    .dir(&android_dir)
    .run()?;

    eprintln!(
        "[build-android] AAR: {}/chencang-core-android/build/outputs/aar/",
        android_dir.display()
    );
    Ok(())
}

fn test_all() -> Result<()> {
    cmd!("cargo", "test", "--workspace").run()?;
    Ok(())
}

fn generate_vectors() -> Result<()> {
    let bindings = bindings_dir();
    let workspace = bindings
        .parent()
        .expect("chencang-bindings must have a workspace root parent");
    let core_dir = workspace.join("core");
    // The regenerator is `#[ignore]` (keeps it out of routine `cargo test` and
    // off the default debug stack) — `--ignored` is required or it is skipped
    // and nothing gets written.
    cmd!(
        "cargo",
        "test",
        "--release",
        "--test",
        "generate_golden_vectors",
        "--",
        "--ignored",
        "--nocapture",
    )
    .dir(&core_dir)
    .run()?;

    // Copy vectors.json into the platform test locations so Swift/Kotlin load
    // the freshest data without manual copy steps.
    let vectors = bindings.join("golden-vectors/vectors.json");
    let swift_dst = bindings.join("swift/Tests/ChencangTests/vectors.json");
    let android_assets = bindings.join("android/chencang-core-android/src/androidTest/assets");
    std::fs::create_dir_all(&android_assets)?;
    let android_dst = android_assets.join("vectors.json");
    if vectors.exists() {
        std::fs::copy(&vectors, &swift_dst)?;
        eprintln!("[generate-vectors] copied to {}", swift_dst.display());
        std::fs::copy(&vectors, &android_dst)?;
        eprintln!("[generate-vectors] copied to {}", android_dst.display());
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use chencang_core::primitives::ed25519::{verify, VerifyingKey};

    #[test]
    fn sign_envelope_roundtrips() {
        let seed = [9u8; 32];
        let payload = br#"{"schema":1,"seq":1}"#;
        let env: serde_json::Value = serde_json::from_str(&sign_envelope(seed, payload)).unwrap();
        let p = URL_SAFE_NO_PAD.decode(env["p"].as_str().unwrap()).unwrap();
        let s = URL_SAFE_NO_PAD.decode(env["s"].as_str().unwrap()).unwrap();
        assert_eq!(p, payload);
        let sig: [u8; 64] = s.as_slice().try_into().unwrap();
        let vk = VerifyingKey(SigningKey::from_bytes(seed).verifying_key().0);
        let mut msg = CONFIG_DOMAIN.to_vec();
        msg.extend_from_slice(&p);
        verify(&vk, &msg, &sig).unwrap();
    }
}
