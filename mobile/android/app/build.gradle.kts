plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Where the Rust core is built (see cargoBuild below).
val repoRoot: File = rootDir.resolve("../..").canonicalFile
val rustJniLibs = layout.buildDirectory.dir("rustJniLibs")

android {
    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Robolectric downloads its Android jars from Maven Central; a mirror can
        // be set with ROBOLECTRIC_REPO when Central is unreachable.
        unitTests.all { test ->
            System.getenv("ROBOLECTRIC_REPO")?.let { test.systemProperty("robolectric.dependency.repo.url", it) }
        }
    }
    namespace = "io.github.maelmbange.thighhigh"
    compileSdk = 36
    // Used to cross-compile the Rust core; Android Studio installs it if missing.
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "io.github.maelmbange.thighhigh"
        // Android 10+: MediaStore exposes RELATIVE_PATH, used to find Music/thigh-high.
        minSdk = 29
        targetSdk = 36
        versionCode = 17
        versionName = "0.8.4"

        ndk {
            // yt-dlp ships Python and ffmpeg per CPU type (~30 MB each): keep
            // only 64-bit ARM, which every recent phone uses.
            abiFilters += "arm64-v8a"
        }
    }

    // Release key, given by environment variables (GitHub Actions secrets in
    // .github/workflows/release.yml). Without them the release APK is signed
    // with the local debug key, as before.
    val releaseKeystore = System.getenv("THIGH_HIGH_KEYSTORE")?.takeIf { it.isNotBlank() }
    val releaseSigning = releaseKeystore?.let {
        signingConfigs.create("release") {
            storeFile = file(it)
            storePassword = System.getenv("THIGH_HIGH_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("THIGH_HIGH_KEY_ALIAS")
            keyPassword = System.getenv("THIGH_HIGH_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = releaseSigning ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        // Native libraries must be unpacked on install for youtubedl-android.
        jniLibs.useLegacyPackaging = true
        // Zip archives named .so so Android unpacks them: nothing to strip.
        jniLibs.keepDebugSymbols += setOf("**/libpython.zip.so", "**/libffmpeg.zip.so")
    }

    sourceSets["main"].jniLibs.srcDir(rustJniLibs)

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// The Rust core (crates/core) through its JNI bridge (crates/android), built
// for the phone with cargo-ndk. Needs, once: Rust, `cargo install cargo-ndk`
// and `rustup target add aarch64-linux-android`. Cargo only rebuilds what changed.

val cargoBuild by tasks.registering(Exec::class) {
    description = "Builds the Rust core for Android."
    workingDir = repoRoot
    inputs.dir(repoRoot.resolve("crates/core/src"))
    inputs.dir(repoRoot.resolve("crates/android/src"))
    inputs.dir(repoRoot.resolve("assets"))
    inputs.files(repoRoot.resolve("Cargo.lock"), repoRoot.resolve("crates/core/Cargo.toml"), repoRoot.resolve("crates/android/Cargo.toml"))
    outputs.dir(rustJniLibs)
    doFirst {
        environment("ANDROID_NDK_HOME", android.ndkDirectory.absolutePath)
    }
    commandLine(
        "cargo", "ndk",
        "-t", "arm64-v8a",
        "--platform", "29",
        "-o", rustJniLibs.get().asFile.absolutePath,
        "build", "--release", "-p", "thigh-high-android",
    )
}

tasks.named("preBuild") { dependsOn(cargoBuild) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Gesture tests of the Compose screens, run on the JVM with Robolectric.
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.robolectric)
    testImplementation(libs.junit)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)

    implementation(libs.youtubedl.library)
    implementation(libs.youtubedl.ffmpeg)
}
