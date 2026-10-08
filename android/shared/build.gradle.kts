plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "app.chencang.shared"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            consumerProguardFiles("consumer-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions {
        unitTests {
            // Robolectric needs the merged Android resources / manifest to spin
            // up a Context for the Room-backed RatchetSessionStore persistence
            // test (`:shared:testDebugUnitTest`).
            isIncludeAndroidResources = true
        }
    }

    sourceSets {
        getByName("main") {
            kotlin.srcDirs("src/main/kotlin")
            // Factory signed config (chencang-config.json); payload.json/README.md ride along harmlessly.
            assets.srcDir("../../release/config")
        }
        getByName("test") {
            kotlin.srcDirs("src/test/kotlin")
        }
        getByName("androidTest") {
            kotlin.srcDirs("src/androidTest/kotlin")
        }
    }

}

// JVM unit tests in `:shared:testDebugUnitTest` get the host-built
// chencang_bindings dylib on the JNA library path so the V1 wire-codec
// round-trip test can drive the real uniffi binding without standing up
// an Android emulator. The dylib is produced by `cargo build -p
// chencang-bindings --release` (run by `cargo run -p xtask -- build-android`
// indirectly, or directly during local dev). The `uniffi.component.
// chencang.libraryOverride` system property tells the generated Kotlin
// glue to look for `chencang_bindings` instead of `uniffi_chencang`.
// The Cargo `target/` dir is at the monorepo root. When this build runs from
// the main checkout it's two levels up (../../target/<...>); inside a git
// worktree the android dir is nested deeper inside the worktree,
// so `../../target/...` would resolve into the worktree which doesn't have
// a `target/`. We therefore walk up until we find a parent containing the
// expected dylib.
//
// macOS Note: the JDK might be running under Rosetta (x86_64) on an Apple
// Silicon host, in which case JNA can only load an x86_64 dylib. We try the
// architecture-specific subdir first, then fall back to the default release
// dir. Build host dylibs with:
//
//   cargo build -p chencang-bindings --release
//   cargo build -p chencang-bindings --release --target x86_64-apple-darwin
fun resolveCargoTargetRelease(): File? {
    // Prefer the arch-specific subdir matching the JVM, then fall back.
    val arch = System.getProperty("os.arch", "").lowercase()
    val archSpecific = when {
        arch.contains("x86_64") || arch == "amd64" -> "target/x86_64-apple-darwin/release"
        arch.contains("aarch64") || arch.contains("arm64") -> "target/aarch64-apple-darwin/release"
        else -> null
    }
    val candidates = buildList {
        if (archSpecific != null) add(archSpecific)
        add("target/release")
    }
    var current: File? = projectDir
    repeat(8) {
        val dir = current
        if (dir != null) {
            for (sub in candidates) {
                val candidate = File(dir, "$sub/libchencang_bindings.dylib")
                if (candidate.exists()) return candidate.parentFile
            }
            current = dir.parentFile
        }
    }
    return null
}
val hostNativeLibDir = resolveCargoTargetRelease()
tasks.withType<Test>().configureEach {
    if (hostNativeLibDir != null) {
        systemProperty("jna.library.path", hostNativeLibDir.absolutePath)
        systemProperty("uniffi.component.chencang.libraryOverride", "chencang_bindings")
    }
}

dependencies {
    // chencang-core AAR via mavenLocal()
    api(libs.chencang.core.android)
    // uniffi bindings need JNA at runtime
    api(libs.jna) { artifact { type = "aar" } }

    api(libs.kotlinx.coroutines.android)
    api(libs.kotlinx.serialization.cbor)
    api(libs.kotlinx.serialization.json)

    api(libs.androidx.datastore)
    api(libs.androidx.datastore.preferences)

    // Room — chat/session persistence
    api(libs.androidx.room.runtime)
    api(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // 视频转码（VideoPreparer）：media3 Transformer + Presentation/FrameDrop 效果 + InAppMuxer（muxer 模块提供 Muxer.Factory）
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.media3.effect)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.muxer)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    // JNA jar for JVM unit tests — the main classpath has the AAR variant
    // which is Android-only, so the JNA Native.register() call would fail
    // on the host JVM without this.
    testImplementation(libs.jna)
    // Robolectric + AndroidX test-core provide a JVM Context so the Room-backed
    // session-persistence test runs under :shared:testDebugUnitTest.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.okhttp.mockwebserver)
}
