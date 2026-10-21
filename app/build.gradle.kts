plugins {
    id("com.android.application") version "8.7.3"
    id("org.jetbrains.kotlin.android") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21"
}

android {
    namespace = "com.winlator"
    compileSdk = 35
    ndkVersion = "24.0.8215888"

    defaultConfig {
        applicationId = "ai.venice.rbxstudio"
        minSdk = 26
        targetSdk = 28
        versionCode = 2
        versionName = "0.2.0"
        ndk { abiFilters += "arm64-v8a" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }

    externalNativeBuild {
        cmake {
            version = "3.22.1"
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    packaging {
        jniLibs { useLegacyPackaging = true }
    }

    lint { checkReleaseBuilds = false }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.preference:preference:1.2.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.github.luben:zstd-jni:1.5.2-3@aar")
    implementation("org.tukaani:xz:1.7")
    implementation("org.apache.commons:commons-compress:1.20")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")

    val bom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(bom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
