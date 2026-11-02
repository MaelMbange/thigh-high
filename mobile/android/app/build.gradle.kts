plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.maelmbange.thighhigh"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.maelmbange.thighhigh"
        // Android 10+: MediaStore exposes RELATIVE_PATH, used to find Music/thigh-high.
        minSdk = 29
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"

        ndk {
            // yt-dlp ships Python and ffmpeg per CPU type (~30 MB each): keep
            // only 64-bit ARM, which every recent phone uses.
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // No release key yet: sign with the debug key so the APK installs.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        // Native libraries must be unpacked on install for youtubedl-android.
        jniLibs.useLegacyPackaging = true
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

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

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)

    implementation(libs.youtubedl.library)
    implementation(libs.youtubedl.ffmpeg)
}
