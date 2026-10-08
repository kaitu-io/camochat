# chencang-android

CamoChat（陈仓）— an E2E-encrypted chat-style Android app with no communication channel of
its own; text messages travel in-band through system share / `ACTION_PROCESS_TEXT` / paste.
Voice / images / video go through the encrypted-blob relay in `infra/media/`.
See `docs/protocol/README.md` for the protocol overview.

A single APK containing three Gradle modules:

| Module      | Type                                | Role                                                           |
|-------------|--------------------------------------|-----------------------------------------------------------------|
| `:app`      | Android application                 | Compose UI (onboarding, pairing, conversations, contacts, settings) |
| `:design`   | Android library                     | Generated 墨玉 (Moyu) design tokens                              |
| `:shared`   | Android library                     | Repository, DataStore, Room, uniffi bridge, crypto               |

## Build prerequisites

- JDK 17
- Android SDK with platforms 34/35 + build-tools 34.x
- For native binding builds: Rust (stable toolchain) with Android targets (`cargo-zigbuild` auto-installed by xtask)
- Local Maven (`~/.m2`) must contain `app.chencang:chencang-core-android:<version>` before any
  `:shared`/`:app` build can resolve. Use the dev loop below.

## Dev loop — publish the chencang-core AAR

The Rust core is built once via `xtask build-android` and exposed to Gradle through `mavenLocal()`:

```bash
./scripts/publish-aar-local.sh
# Confirm:
ls ~/.m2/repository/app/chencang/chencang-core-android/
```

After the AAR is in place:

```bash
./gradlew :shared:compileDebugKotlin :app:compileDirectDebugKotlin :app:compilePlayDebugKotlin
./gradlew assembleDebug
./gradlew :shared:testDebugUnitTest :app:testDirectDebugUnitTest :app:testPlayDebugUnitTest
```

## CI

There is no Android PR workflow; run the unit tests above locally. Signed releases are built by
`.github/workflows/android-release.yml` on `android-v*` tags.
