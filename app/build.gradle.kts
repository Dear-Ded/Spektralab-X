plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Upstream SpektraLab checkout made by scripts/fetch-upstream.sh.
val upstream: String = System.getenv("SPK_UPSTREAM") ?: rootProject.file("upstream").absolutePath
// VERSION holds "major.minor.patch[-label]" (0.1.3-alpha). Bump the patch for
// every release; the versionCode is derived from it so updates always install.
val appVersion: String = rootProject.file("VERSION").readText().trim()
val appVersionCode: Int = Regex("""^(\d+)\.(\d+)\.(\d+)""").find(appVersion)
    ?.destructured?.let { (a, b, c) -> a.toInt() * 10000 + b.toInt() * 100 + c.toInt() }
    ?: error("VERSION '$appVersion' is not major.minor.patch")
val keystorePath: String? = System.getenv("SPK_KEYSTORE")?.takeIf { file(it).isFile }

android {
    namespace = "com.dearded.spektralab"
    compileSdk = 36
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.dearded.spektralab"
        minSdk = 29
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersion
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake {
                // ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES: NDK r27 lays out its own runtime for 16 KB pages too
                // (r28 does by default); CMakeLists.txt links the library at max-page-size 16384.
                arguments += listOf("-DSPK_UPSTREAM=$upstream", "-DANDROID_STL=c++_static", "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON")
                System.getenv("GLSLANG")?.let { arguments += "-DSPEKTRALAB_GLSLANG=$it" }
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storeType = "pkcs12"
                storePassword = System.getenv("SPK_KEYSTORE_PASSWORD")
                keyAlias = "spektralab"
                keyPassword = System.getenv("SPK_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (keystorePath != null) signingConfigs.getByName("release")
                            else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs { useLegacyPackaging = false }
    }

    // The bundled UI fonts (Theme.kt UiFonts) are mapped in place, not inflated into memory.
    androidResources { noCompress += "ttf" }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.01.00")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.17.0")
    // Android 12+ system splash with a compat path back to API 29 (ui-motion_splash.md 2).
    implementation("androidx.core:core-splashscreen:1.2.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    // HEIF · 全尺寸 (HeifExport.kt): MediaCodec HEVC through the stable 1.1 line.
    implementation("androidx.heifwriter:heifwriter:1.1.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // Installs src/main/baseline-prof.txt on sideloaded installs (no Play to deliver it); see that file.
    implementation("androidx.profileinstaller:profileinstaller:1.4.0")
    testImplementation("junit:junit:4.13.2")
}
