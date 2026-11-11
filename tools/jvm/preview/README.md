# Preview publication contracts

`../run_preview_publication.ps1` compiles the pure production publication contract
and extracts these EditorViewModel methods verbatim: applyWanted, renderOnce,
renderPooled, retire, repackLoop, loadInspection, previewOverride and clearOverride.
Only Android bitmaps, the native bridge, Main/engine dispatchers and unrelated
editor services are replaced with small test adapters. The dispatcher executes
one queued continuation at a time; tests need no sleeps or Android emulator.

Dependencies are a cached Java 21 runtime, Kotlin compiler/stdlib 2.0.21,
JUnit 4.13.2, Hamcrest 1.3, compiler dependencies and coroutines 1.9.0.
The tool directory defaults to `build/host-jvm/host-tools` under the repository.
Pass `-ToolDir` and `-CoroutinesJar` explicitly when using a shared dependency
cache. The runner does not download dependencies or produce an APK.

```powershell
./tools/jvm/run_preview_publication.ps1 -BuildDir <scratch-directory> `
  -ToolDir <cached-host-tools> -CoroutinesJar <cached-coroutines-1.9.0.jar>
```

Pass `-EditorRef c5666a2` to run the identical suite with baseline editor methods.
The output directory receives the extracted harness, JUnit result and SHA-256
manifest. Compilation checks the extracted methods and pure contract; complete
Android compilation remains a separate integration check. Native packing,
Bitmap ColorSpace behavior and real GPU/display timing require device tests.
