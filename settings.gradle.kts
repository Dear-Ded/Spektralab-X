pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Robolectric's android-all runtimes (150-210 MB each, app/build.gradle.kts) come
        // from Google's Maven Central mirror: Maven Central answers HTTP 429 to CI runners
        // for them. Same files; Gradle checks them against the mirrored checksums. Only
        // that one module is taken from here, and it is never looked up anywhere else.
        exclusiveContent {
            forRepository {
                maven("https://maven-central.storage-download.googleapis.com/maven2/") {
                    name = "MavenCentralGoogleMirror"
                }
            }
            filter { includeModule("org.robolectric", "android-all-instrumented") }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "Spektralab-X"
include(":app")
