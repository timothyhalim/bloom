import java.util.Properties

plugins {
    id("com.android.application") // AGP 9 compiles Kotlin itself
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Release signing: keystore.properties at the project root (gitignored, see docs/RELEASE.md); without it release builds are unsigned.
val keystore = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }

android {
    namespace = "dev.bloom.editor"
    compileSdk {
        version = release(37) { minorApiLevel = 2 } // Compose 1.13 needs 37.1+
    }

    defaultConfig {
        applicationId = "dev.bloom.editor"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    // LibRaw for camera raw files (app/src/main/cpp)
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }

    // fdroid: no proprietary Google libs. play: ML Kit, Play Billing (later milestones).
    flavorDimensions += "store"
    productFlavors {
        create("fdroid") { dimension = "store" }
        create("play") { dimension = "store" }
    }

    signingConfigs {
        if (keystore != null) create("release") {
            storeFile = rootProject.file(keystore.getProperty("storeFile"))
            storePassword = keystore.getProperty("storePassword")
            keyAlias = keystore.getProperty("keyAlias")
            keyPassword = keystore.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.11.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    implementation("androidx.heifwriter:heifwriter:1.2.0") // HEIC/AVIF export
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("ch.acra:acra-core:5.14.2") // crash reports, shared only if the user agrees (BloomApp.kt)
    implementation("ch.acra:acra-dialog:5.14.2")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}
