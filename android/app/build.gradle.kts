import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

// Release signing is driven by an untracked `keystore.properties` at the
// android/ root (see .gitignore). When it is absent — e.g. on CI — the release
// build falls back to debug signing so builds still succeed.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) FileInputStream(keystorePropsFile).use { load(it) }
}

android {
    namespace = "app.chencang.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.chencang.android"
        minSdk = 29
        targetSdk = 36
        versionCode = 5
        versionName = "1.0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "channel"
    productFlavors {
        create("direct")
        create("play")
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Sign with the real release keystore when keystore.properties is
            // present; otherwise (CI) fall back to debug signing.
            signingConfig = if (keystorePropsFile.exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get()
    }
    testOptions {
        unitTests {
            // Robolectric needs the merged Android resources / manifest to spin up a
            // Context-less shadow graphics stack for the PairingQr Bitmap codec test.
            isIncludeAndroidResources = true
        }
    }
    packaging {
        resources {
            excludes += listOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
            )
        }
    }

    sourceSets {
        getByName("main") {
            kotlin.srcDirs("src/main/kotlin")
        }
        getByName("test") {
            kotlin.srcDirs("src/test/kotlin")
        }
        getByName("direct") {
            kotlin.srcDirs("src/direct/kotlin")
        }
        getByName("play") {
            kotlin.srcDirs("src/play/kotlin")
        }
        getByName("testDirect") {
            kotlin.srcDirs("src/testDirect/kotlin")
        }
        getByName("testPlay") {
            kotlin.srcDirs("src/testPlay/kotlin")
        }
        getByName("androidTest") {
            kotlin.srcDirs("src/androidTest/kotlin")
        }
    }
}

// :app:testDebugUnitTest exercises real `uniffi.chencang` FFI calls (via
// ChatRepository → encodeTextFrame/encodeWire in ConversationViewModelTest),
// so it needs the same host-built native lib on the JNA library path that
// :shared's build.gradle.kts wires up for its own Test tasks — that config
// only applies to :shared's tasks, not :app's. See :shared/build.gradle.kts
// for the full rationale (Rosetta arch fallback, worktree path walk-up).
fun resolveCargoTargetRelease(): File? {
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
    implementation(project(":shared"))
    implementation(project(":design"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.core.splashscreen)

    // Face-to-face pairing QR: generate (zxing-core) + camera scan (zxing-embedded).
    implementation(libs.zxing.core)
    implementation(libs.zxing.embedded)

    // 设置 → 关于 → 服务器源码（Custom Tab 打开签名配置里 shareSite 的 /source）
    implementation(libs.androidx.browser)

    // 媒体后台上传引擎（UploadScheduler / UploadWorker）
    implementation(libs.androidx.work.runtime.ktx)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    // Robolectric supplies a JVM-side android.graphics.Bitmap for the PairingQr codec test.
    testImplementation(libs.robolectric)
    // ApplicationProvider — JVM Context for the Room-backed ConversationViewModel test.
    testImplementation(libs.androidx.test.core)
    // :shared exposes JNA only as the Android AAR variant (api(libs.jna){artifact{type="aar"}}),
    // which has no desktop native dispatch lib. ConversationViewModelTest makes real
    // uniffi.chencang FFI calls, so — mirroring :shared's own JVM-test setup — pull in
    // the plain jar variant with the host-platform jnidispatch native.
    testImplementation(libs.jna)
    // ConversationViewModel 媒体发送测试：本地桩代替中转（仅测试依赖）
    testImplementation(libs.okhttp.mockwebserver)
    // SettingsScreen「关于」分组的 Robolectric Compose 测试
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    // UploadWorker / UploadScheduler：TestListenableWorkerBuilder + WorkManagerTestInitHelper
    testImplementation(libs.androidx.work.testing)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.truth)
}
