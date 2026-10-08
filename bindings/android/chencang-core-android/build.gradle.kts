plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    `maven-publish`
}

android {
    // Namespace must match the generated uniffi Kotlin package: `uniffi.chencang`.
    // (The plan's `app.chencang.core` is the publish coordinate, not the namespace.)
    namespace = "uniffi.chencang"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    sourceSets {
        getByName("main") {
            // Generated Kotlin lives under src/main/java/uniffi/chencang/.
            java.srcDirs("src/main/java")
            // JNI .so files copied by xtask build_android.
            jniLibs.srcDirs("src/main/jniLibs")
        }
        getByName("androidTest") {
            java.srcDirs("src/androidTest/kotlin")
            assets.srcDirs("src/androidTest/assets")
        }
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // uniffi-rs 0.31 generates JNA-based bindings.
    implementation("net.java.dev.jna:jna:5.14.0@aar")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "app.chencang"
            artifactId = "chencang-core-android"
            version = "0.1.0-rc1"

            afterEvaluate { from(components["release"]) }
        }
    }
    repositories {
        mavenLocal()
    }
}
