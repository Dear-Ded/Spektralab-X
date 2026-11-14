#!/usr/bin/env bash
# Reproduce the production shader toolchain in cloud. Co-created with GPT.
set -euo pipefail
E=/data/work/gpt-alpha8-release-20261115
G=/data/work/gpt-composable-styles-20261113/gradle-repo
F=/data/work/gpt-alpha7-final-r3-20261114/upstream
A="$E/package"
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
exec > >(tee "$A/toolchain-reproduction.log") 2>&1
trap 'printf "%s\n" "$?" > "$A/toolchain-reproduction.exit"' EXIT
python3 - "$G" "$A" <<'PY'
import hashlib, json, pathlib, sys, zipfile
root, package = map(pathlib.Path, sys.argv[1:])
assets = root / 'app/src/main/assets/engine'
with zipfile.ZipFile(package / 'Spektralab-X-0.2.00-alpha-8-arm64-v8a.apk') as apk:
    differences = []
    for source in sorted(assets.rglob('*')):
        if not source.is_file():
            continue
        name = source.relative_to(assets).as_posix()
        expected, actual = source.read_bytes(), apk.read('assets/engine/' + name)
        if expected != actual:
            differences.append({'path': name, 'aoidc_sha256': hashlib.sha256(expected).hexdigest(),
                                'release_sha256': hashlib.sha256(actual).hexdigest()})
    report = {'initial_verifier': 'rejected different-toolchain generated assets',
              'aoidc_glslang': '11.8.0', 'production_glslang': '15.1.0',
              'non_shader_resources_match': all(x['path'] == 'stamp.txt' or x['path'].endswith('.spv') for x in differences),
              'differences': differences}
    (package / 'initial-asset-mismatch.json').write_text(json.dumps(report, indent=2) + '\n')
    assert report['non_shader_resources_match']
PY
mkdir -p "$E/glslang-production"
cd "$E/glslang-production"
if ! test -s source.tar.gz; then
    curl --fail --location --retry 2 --max-time 120 https://github.com/KhronosGroup/glslang/archive/refs/tags/15.1.0.tar.gz -o source.tar.gz
fi
sha256sum source.tar.gz > source.sha256
tar -xzf source.tar.gz
cmake_tool="$E/toolchain-python/cmake/data/bin/cmake"
if ! test -x "$cmake_tool"; then
    python3 -m pip install --no-cache-dir --target "$E/toolchain-python" cmake==3.31.6
fi
"$cmake_tool" -S glslang-15.1.0 -B build -G Ninja -DCMAKE_BUILD_TYPE=Release -DENABLE_OPT=OFF -DBUILD_TESTING=OFF -DGLSLANG_TESTS=OFF -DENABLE_HLSL=OFF -DENABLE_SPVREMAPPER=OFF
"$cmake_tool" --build build --parallel 3
compiler="$E/glslang-production/build/StandAlone/glslang"
test -x "$compiler"
"$compiler" --version | tee "$A/reproduced-glslang-version.txt"
GLSLANG="$compiler" bash "$G/scripts/stage-assets.sh" "$F"
python3 "$G/tools/native/verify_release_apk.py" "$A/Spektralab-X-0.2.00-alpha-8-arm64-v8a.apk" --root "$G" --android-tools /opt/android/build-tools/36.0.0 --ndk-bin /opt/android/ndk/27.2.12479018/toolchains/llvm/prebuilt/linux-x86_64/bin --output "$A/inspection"
