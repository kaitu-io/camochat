#!/usr/bin/env bash
#
# Build the chencang-core-android AAR (Rust .so for 3+ Android ABIs + uniffi-generated
# Kotlin) and publish to the developer's local Maven repository (~/.m2). The Gradle
# build of `chencang-android` then resolves `app.chencang:chencang-core-android:<version>`
# via `mavenLocal()` in `settings.gradle.kts`.
#
# Usage:
#   ./scripts/publish-aar-local.sh
#
# Prereqs:
#   - Rust toolchain with android targets + cargo-ndk
#   - Android NDK 27: ANDROID_NDK_HOME, or $ANDROID_SDK_ROOT / $ANDROID_HOME /ndk/27.1.12297006
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

if [[ ! -d "$REPO_ROOT/bindings/android" ]]; then
    echo "error: $REPO_ROOT/bindings/android not found. Run from inside the chencang monorepo." >&2
    exit 1
fi

if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
    sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
    if [[ -n "$sdk" && -d "$sdk/ndk/27.1.12297006" ]]; then
        export ANDROID_NDK_HOME="$sdk/ndk/27.1.12297006"
    fi
fi

echo "==> Building Android .so files via xtask (cargo-ndk)..."
(cd "$REPO_ROOT" && cargo run -p xtask -- build-android)

echo "==> Publishing chencang-core-android AAR to ~/.m2..."
(cd "$REPO_ROOT/bindings/android" && \
    ./gradlew :chencang-core-android:publishReleasePublicationToMavenLocalRepository)

echo "==> Done."
echo "    Resolve via mavenLocal() in android/settings.gradle.kts."
echo "    Verify: ls ~/.m2/repository/app/chencang/chencang-core-android/"
