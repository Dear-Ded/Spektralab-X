#!/usr/bin/env bash
set -euo pipefail
# Co-created with GPT (OpenAI). Inspect a GitHub Actions candidate on AOIDC.
A=/data/work/gpt-release-signing-20261114
TOOLS=/opt/android/build-tools/36.0.0
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
cd "$A"
trap 'printf "%s\n" "$?" > verification.exit' EXIT
APK=Spektralab-X-0.2.00-alpha-6-arm64-v8a.apk
sha256sum --check SHA256SUMS > checksum.log
"$TOOLS/apksigner" verify --verbose --print-certs "$APK" > signature.log
"$TOOLS/zipalign" -c -P 16 -v 4 "$APK" > alignment.log
"$TOOLS/aapt2" dump badging "$APK" > badging.log
python3 - <<'PY'
import hashlib
import json
from pathlib import Path
import re
import struct
import zipfile

apk = Path('Spektralab-X-0.2.00-alpha-6-arm64-v8a.apk')
certificate = Path('signing-cert.sha256').read_text().replace(':', '').strip().lower()
signature = Path('signature.log').read_text()
actual = re.search(r'Signer #1 certificate SHA-256 digest: ([0-9a-f]+)', signature).group(1)
assert actual == certificate, 'Certificate differs from the published project identity'
assert 'CN=Android Debug' not in signature
assert 'Verified using v2 scheme (APK Signature Scheme v2): true' in signature
badging = Path('badging.log').read_text()
assert "package: name='com.dearded.spektralab' versionCode='126' versionName='0.2.00-alpha-6'" in badging
assert "native-code: 'arm64-v8a'" in badging
assert 'application-debuggable' not in badging
native_details = {}
with zipfile.ZipFile(apk) as package:
    names = package.namelist()
    assert len(names) == len(set(names)) and package.testzip() is None
    native = sorted(n for n in names if n.startswith('lib/') and n.endswith('.so'))
    assert native == ['lib/arm64-v8a/libandroidx.graphics.path.so', 'lib/arm64-v8a/libspektralab_jni.so']
    for name in native:
        data = package.read(name)
        assert data[:6] == b'\x7fELF\x02\x01'
        assert struct.unpack_from('<HH', data, 16) == (3, 183)
        phoff = struct.unpack_from('<Q', data, 32)[0]
        entry_size, entry_count = struct.unpack_from('<HH', data, 54)
        alignments = []
        for i in range(entry_count):
            offset = phoff + i * entry_size
            if struct.unpack_from('<I', data, offset)[0] == 1:
                file_offset, address = struct.unpack_from('<QQ', data, offset + 8)
                alignment = struct.unpack_from('<Q', data, offset + 48)[0]
                assert alignment >= 16384 and file_offset % 16384 == address % 16384
                alignments.append(alignment)
        assert alignments
        native_details[name] = {'sha256': hashlib.sha256(data).hexdigest(), 'load_alignments': alignments}
    assets = [n for n in names if n.startswith('assets/engine/') and not n.endswith('/')]
    assert len(assets) == 113
    stamp = package.read('assets/engine/stamp.txt').decode().strip()
summary = {
    'workflow_run': 43466329167,
    'source_commit': 'fee70ee9fea4d6477776d32b5261bc2ce02f0df8',
    'package_name': 'com.dearded.spektralab', 'version_name': '0.2.00-alpha-6',
    'version_code': 126, 'abi': 'arm64-v8a', 'debuggable': False,
    'apk_bytes': apk.stat().st_size, 'apk_sha256': hashlib.sha256(apk.read_bytes()).hexdigest(),
    'certificate_sha256': actual, 'matches_published_certificate': True,
    'native_libraries': native_details, 'engine_asset_count': len(assets), 'engine_stamp': stamp,
    'phone_installation': 'not performed; no ADB device connected',
    'warning_resolution': 'unconfirmed; original installer warning text was not provided',
}
Path('verified-package.json').write_text(json.dumps(summary, indent=2) + '\n')
print(json.dumps(summary, indent=2))
PY
