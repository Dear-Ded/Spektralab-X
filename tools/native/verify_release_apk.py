#!/usr/bin/env python3
"""Inspect the packaged release, in cloud. Co-created with GPT (OpenAI)."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import subprocess
import tempfile
import zipfile
import zlib


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def verify_certificate(signature, certificate):
    # Accept scheme-labelled output as well as the older numbered signer.
    count = re.search(r'^Number of signers: (\d+)\s*$', signature, re.MULTILINE)
    digests = re.findall(
        r'^(?:Signer #\d+|V\d+(?:\.\d+)? Signer:)\s+certificate SHA-256 digest: ([0-9a-fA-F]{64})\s*$',
        signature, re.MULTILINE)
    assert count and int(count.group(1)) == 1, 'Expected one APK signer'
    assert {d.lower() for d in digests} == {certificate}, 'APK certificate differs from the published identity'
    assert 'CN=Android Debug' not in signature


def inspect(apk, root):
    version = (root / 'VERSION').read_text().strip()
    code = int((root / 'VERSION_CODE').read_text().strip())
    library_names = {
        'lib/arm64-v8a/libandroidx.graphics.path.so',
        'lib/arm64-v8a/libspektralab_jni.so',
    }
    assets_root = root / 'app/src/main/assets/engine'
    assets = {'assets/engine/' + p.relative_to(assets_root).as_posix(): p
              for p in assets_root.rglob('*') if p.is_file()}
    assert assets, 'Engine assets have not been staged'
    native, dex, shaders = {}, {}, {}
    with zipfile.ZipFile(apk) as package:
        names = package.namelist()
        assert len(names) == len(set(names)), 'Duplicate ZIP entries'
        assert package.testzip() is None, 'Corrupt ZIP entry'
        assert all(not n.startswith('/') and '..' not in n.split('/') for n in names)
        assert {n for n in names if n.startswith('lib/') and n.endswith('.so')} == library_names
        assert {n for n in names if n.startswith('assets/engine/') and not n.endswith('/')} == set(assets)
        for name, path in assets.items():
            assert sha256(package.read(name)) == sha256(path.read_bytes()), name
        for name in sorted(library_names):
            data = package.read(name)
            assert data[:6] == b'\x7fELF\x02\x01', name
            assert struct.unpack_from('<HH', data, 16) == (3, 183), name
            phoff = struct.unpack_from('<Q', data, 32)[0]
            entry_size, count = struct.unpack_from('<HH', data, 54)
            assert entry_size == 56 and phoff + count * entry_size <= len(data)
            loads, relro, stack = [], False, False
            for i in range(count):
                at = phoff + i * entry_size
                kind, flags, offset, address, _, file_size, memory_size, alignment = struct.unpack_from('<IIQQQQQQ', data, at)
                assert offset + file_size <= len(data)
                if kind == 1:
                    assert memory_size >= file_size
                    assert alignment >= 16384 and offset % 16384 == address % 16384
                    assert flags & 3 != 3, 'Writable executable LOAD segment'
                    loads.append(alignment)
                elif kind == 0x6474E552:
                    relro = True
                elif kind == 0x6474E551:
                    stack = True
                    assert not flags & 1, 'Executable stack'
            assert loads and relro and stack, name
            native[name] = {'bytes': len(data), 'sha256': sha256(data), 'load_alignments': loads,
                            'relro': relro, 'non_executable_stack': stack}
        for name in names:
            if re.fullmatch(r'classes\d*\.dex', name):
                data = package.read(name)
                assert data[:4] == b'dex\n' and data[7] == 0 and len(data) >= 112
                assert struct.unpack_from('<I', data, 32)[0] == len(data)
                assert struct.unpack_from('<I', data, 36)[0] == 112
                assert struct.unpack_from('<I', data, 40)[0] == 0x12345678
                assert struct.unpack_from('<I', data, 8)[0] == zlib.adler32(data[12:]) & 0xFFFFFFFF
                assert data[12:32] == hashlib.sha1(data[32:]).digest()
                dex[name] = {'bytes': len(data), 'sha256': sha256(data)}
            elif name.endswith('.spv'):
                data = package.read(name)
                assert len(data) >= 20 and len(data) % 4 == 0
                words = struct.unpack('<' + 'I' * (len(data) // 4), data)
                assert words[0] == 0x07230203 and words[3] > 0 and words[4] == 0
                at = 5
                while at < len(words):
                    size = words[at] >> 16
                    assert size > 0 and at + size <= len(words), name
                    at += size
                assert at == len(words)
                shaders[name] = {'bytes': len(data), 'sha256': sha256(data)}
        assert dex and shaders
        stamp = package.read('assets/engine/stamp.txt').decode().strip()
        jni = package.read('lib/arm64-v8a/libspektralab_jni.so')
    return {
        'package_name': 'com.dearded.spektralab', 'version_name': version, 'version_code': code,
        'apk_bytes': apk.stat().st_size, 'apk_sha256': sha256(apk.read_bytes()),
        'native_libraries': native, 'dex': dex, 'spirv_count': len(shaders), 'spirv': shaders,
        'engine_asset_count': len(assets), 'engine_stamp': stamp,
        'phone_installation': 'not performed', 'installation_warning': 'not verified on a phone',
    }, jni


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument('--android-tools', type=Path, required=True)
    parser.add_argument('--ndk-bin', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    result, jni = inspect(args.apk, args.root)
    for tool, options, log in (
        ('apksigner', ['verify', '--verbose', '--print-certs'], 'signature.log'),
        ('aapt2', ['dump', 'badging'], 'badging.log'),
        ('zipalign', ['-c', '-P', '16', '-v', '4'], 'alignment.log'),
    ):
        text = subprocess.check_output([str(args.android_tools / tool), *options, str(args.apk)], text=True)
        (args.output / log).write_text(text)
    signature = (args.output / 'signature.log').read_text()
    certificate = (args.root / 'scripts/signing-cert.sha256').read_text().replace(':', '').strip().lower()
    verify_certificate(signature, certificate)
    assert 'Verified using v2 scheme (APK Signature Scheme v2): true' in signature
    badging = (args.output / 'badging.log').read_text()
    assert ("package: name='com.dearded.spektralab' versionCode='" + str(result['version_code']) +
            "' versionName='" + result['version_name'] + "'") in badging
    assert "native-code: 'arm64-v8a'" in badging and 'application-debuggable' not in badging
    with tempfile.TemporaryDirectory() as temporary:
        library = Path(temporary) / 'libspektralab_jni.so'
        library.write_bytes(jni)
        symbols = subprocess.check_output([str(args.ndk_bin / 'llvm-nm'), '-D', '--defined-only', str(library)], text=True)
        symbols = {line.split()[-1] for line in symbols.splitlines() if line.split()}
        methods = re.findall(r'external\s+fun\s+(\w+)', (args.root / 'app/src/main/java/com/dearded/spektralab/NativeEngine.kt').read_text())
        assert methods
        for method in methods:
            assert 'Java_com_dearded_spektralab_NativeEngine_' + method in symbols, method
        dynamic = subprocess.check_output([str(args.ndk_bin / 'llvm-readelf'), '-d', str(library)], text=True)
        (args.output / 'jni-dynamic.log').write_text(dynamic)
        dependencies = re.findall(r'\(NEEDED\).*?\[(.*?)\]', dynamic)
        assert 'libomp.so' not in dependencies and 'libc++_shared.so' not in dependencies
    result.update(certificate_sha256=certificate, matches_published_certificate=True,
                  jni_method_count=len(methods), dynamic_dependencies=dependencies,
                  declared_permissions=re.findall(r"uses-permission: name='([^']+)'", badging))
    (args.output / 'verified-package.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps({k: v for k, v in result.items() if k not in ('spirv',)}, indent=2))


if __name__ == '__main__':
    main()
