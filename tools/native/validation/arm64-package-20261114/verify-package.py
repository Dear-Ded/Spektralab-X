#!/usr/bin/env python3
"""Inspect the real cloud-built candidate. Co-created with GPT (OpenAI)."""
import hashlib
import json
from pathlib import Path
import re
import struct
import subprocess
import zipfile


A = Path('/data/work/gpt-arm64-package-20261114')
G = Path('/data/work/gpt-composable-styles-20261113/gradle-repo')
APK = A / 'Spektralab-X-alpha-6-arm64-raw-51be487.apk'
NDK_BIN = Path('/opt/android/ndk/27.2.12479018/toolchains/llvm/prebuilt/linux-x86_64/bin')


def digest(data):
    return hashlib.sha256(data).hexdigest()


assert (A / 'build.exit').read_text().strip() == '0'
inputs = (A / 'build-inputs.sha256').read_text().splitlines()
for line in inputs:
    expected, relative = line.split('  ', 1)
    assert digest((G / relative).read_bytes()) == expected, relative
for name in ('input-check-before.log', 'input-check-after.log'):
    checks = (A / name).read_text().splitlines()
    assert len(checks) == len(inputs) and all(x.endswith(': OK') for x in checks)

metadata = json.loads((A / 'output-metadata.json').read_text())
assert metadata['variantName'] == 'release'
assert metadata['applicationId'] == 'com.dearded.spektralab'
version = (G / 'VERSION').read_text().strip()
version_code = int((G / 'VERSION_CODE').read_text().strip())
assert metadata['elements'][0]['versionName'] == version
assert metadata['elements'][0]['versionCode'] == version_code
badging = (A / 'badging.log').read_text()
assert "native-code: 'arm64-v8a'" in badging
assert 'application-debuggable' not in badging
assert 'Verification successful' in (A / 'alignment.log').read_text()
assert 'Verified using v2 scheme (APK Signature Scheme v2): true' in (A / 'signature.log').read_text()

with zipfile.ZipFile(APK) as package:
    names = package.namelist()
    assert len(names) == len(set(names))
    assert package.testzip() is None
    native = [x for x in names if x.startswith('lib/') and x.endswith('.so')]
    assert set(native) == {
        'lib/arm64-v8a/libandroidx.graphics.path.so',
        'lib/arm64-v8a/libspektralab_jni.so',
    }, native
    libraries = {name: package.read(name) for name in native}
    stripped_root = G / 'app/build/intermediates/stripped_native_libs/release/stripReleaseDebugSymbols/out'
    for name, data in libraries.items():
        assert digest(data) == digest((stripped_root / name).read_bytes()), name
    library = libraries['lib/arm64-v8a/libspektralab_jni.so']
    stripped = stripped_root / 'lib/arm64-v8a/libspektralab_jni.so'
    engine_files = sorted(p for p in (G / 'app/src/main/assets/engine').rglob('*') if p.is_file())
    expected_assets = {'assets/engine/' + p.relative_to(G / 'app/src/main/assets/engine').as_posix() for p in engine_files}
    assert {x for x in names if x.startswith('assets/engine/') and not x.endswith('/')} == expected_assets
    for p in engine_files:
        entry = 'assets/engine/' + p.relative_to(G / 'app/src/main/assets/engine').as_posix()
        assert digest(package.read(entry)) == digest(p.read_bytes()), entry
    stamp = package.read('assets/engine/stamp.txt').decode().strip()

native_details = {}
for name, data in libraries.items():
    assert data[:6] == b'\x7fELF\x02\x01', name
    assert struct.unpack_from('<HH', data, 16) == (3, 183), name
    phoff = struct.unpack_from('<Q', data, 32)[0]
    phentsize, phnum = struct.unpack_from('<HH', data, 54)
    load_alignments = []
    for i in range(phnum):
        offset = phoff + i * phentsize
        if struct.unpack_from('<I', data, offset)[0] == 1:
            file_offset, vaddr = struct.unpack_from('<QQ', data, offset + 8)
            alignment = struct.unpack_from('<Q', data, offset + 48)[0]
            assert alignment >= 16384 and file_offset % 16384 == vaddr % 16384, name
            load_alignments.append(alignment)
    assert load_alignments, name
    native_details[name] = {'sha256': digest(data), 'elf_load_alignments': load_alignments}

symbols = subprocess.check_output([str(NDK_BIN / 'llvm-nm'), '-D', '--defined-only', str(stripped)], text=True)
symbols = {line.split()[-1] for line in symbols.splitlines() if line.split()}
methods = re.findall(r'external\s+fun\s+(\w+)', (G / 'app/src/main/java/com/dearded/spektralab/NativeEngine.kt').read_text())
assert methods
for method in methods:
    assert 'Java_com_dearded_spektralab_NativeEngine_' + method in symbols, method
dynamic = subprocess.check_output([str(NDK_BIN / 'llvm-readelf'), '-d', str(stripped)], text=True)
dependencies = re.findall(r'\(NEEDED\).*?\[(.*?)\]', dynamic)
assert 'libomp.so' not in dependencies and 'libc++_shared.so' not in dependencies

commands = json.loads((A / 'compile_commands.json').read_text())
raw_commands = [x for x in commands if Path(x['file']).name == 'raw_development.cpp']
assert len(raw_commands) == 1
command = raw_commands[0]['command']
assert '-O2' in command or '-O3' in command
assert 'aarch64' in command and '-DNDEBUG' in command
(A / 'raw-compile-command.txt').write_text(command + '\n')
summary = {
    'source_commit': '51be487613f2bc5a57a04aead9f4ff62210b21ab',
    'build_input_count': len(inputs),
    'variant': 'release', 'abi': 'arm64-v8a',
    'version_name': version, 'version_code': version_code,
    'apk_bytes': APK.stat().st_size, 'apk_sha256': digest(APK.read_bytes()),
    'packaged_jni_sha256': digest(library),
    'jni_method_count': len(methods), 'native_libraries': native_details,
    'dynamic_dependencies': dependencies,
    'engine_asset_count': len(engine_files), 'engine_asset_stamp': stamp,
    'signing': 'cloud debug certificate; research candidate',
    'device_installation': 'not performed; adb detected no connected device',
}
(A / 'verified-package.json').write_text(json.dumps(summary, indent=2) + '\n')
print(json.dumps(summary, indent=2))
