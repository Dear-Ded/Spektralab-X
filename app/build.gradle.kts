plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Upstream SpektraLab checkout made by scripts/fetch-upstream.sh.
val upstream: String = System.getenv("SPK_UPSTREAM") ?: rootProject.file("upstream").absolutePath
// Display version and Android update order are independent. Increment
// VERSION_CODE for every release, including changes to the prerelease label.
val appVersion: String = rootProject.file("VERSION").readText().trim()
val appVersionCode: Int = rootProject.file("VERSION_CODE").readText().trim()
    .toIntOrNull()?.takeIf { it in 1..2_100_000_000 }
    ?: error("VERSION_CODE must be an integer in 1..2100000000")
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

    // Screenshots (docs/SCREENSHOTS.md): Robolectric reads the merged resources and
    // assets (the bundled fonts), and Roborazzi writes a PNG only while recording.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.systemProperty("roborazzi.test.record", "true")
                it.maxHeapSize = "2g"
            }
        }
    }
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
    // Execute the real sidecar/style JSON contract on the release JVM test path.
    testImplementation("org.json:json:20240303")
    // Screenshots (app/src/testDebug, docs/SCREENSHOTS.md): debug unit tests only, so the
    // release build and its unit tests never see them.
    testDebugImplementation(composeBom)
    testDebugImplementation("org.robolectric:robolectric:4.17")
    testDebugImplementation("io.github.takahirom.roborazzi:roborazzi:1.77.0")
    testDebugImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.77.0")
    testDebugImplementation("androidx.compose.ui:ui-test-junit4")
    testDebugImplementation("androidx.test:core:1.7.0")
    testDebugImplementation("androidx.test.ext:junit:1.3.0")
    // The empty ComponentActivity createAndroidComposeRule starts; debug APK only.
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// Roborazzi 1.77 is compiled by Kotlin 2.3 (metadata 2.3.0) and this build is on 2.0.21,
// which reads metadata one version ahead at most. Only the unit-test compile reads it.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    if (name.endsWith("UnitTestKotlin")) compilerOptions.freeCompilerArgs.add("-Xskip-metadata-version-check")
}
