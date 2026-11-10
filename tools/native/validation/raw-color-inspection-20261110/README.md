# Android integration and output labels

Co-created with GPT (OpenAI), 2026-11-10. Source-only validation on AOIDC.

With the existing patched upstream, Java 17, Android SDK/NDK and two Gradle
workers:

```sh
bash ./gradlew --no-daemon --max-workers=2 \
  :app:testReleaseUnitTest :app:compileReleaseKotlin :app:externalNativeBuildRelease
bash ./gradlew --no-daemon --max-workers=2 \
  :app:testDebugUnitTest --tests com.dearded.spektralab.OutputColorTagTest
```

Both commands passed. The first validates release JVM tests plus Kotlin and
ARM64 JNI compilation; it does not assemble an APK. The second executes the
three new bitmap tests using Robolectric SDK 34 native graphics. They verify
that explicit sRGB/P3 labels leave the packed bytes intact and an unsupported
target leaves the existing label/bytes unchanged. Robolectric's notice about
Java 21 for SDK 36/37 does not affect the explicitly selected SDK 34 tests.

The logs and JUnit XML are copied directly from the cloud run. `source.sha256`
records the compiled source files checked against the local workspace. Native
helper normal/sanitizer results from both clouds are archived separately in
[raw-lifecycle](../raw-lifecycle-20261110/README.md).

This is not a native GPU runtime or physical Android device test. The new JNI
inspection entry point, actual ViewModel races, final JPEG appearance and
screen color-management behavior still require end-to-end device validation.
