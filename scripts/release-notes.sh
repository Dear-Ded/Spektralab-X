#!/usr/bin/env bash
# release-notes.sh VERSION DIST_DIR REPO SHA -- the release body: the version's
# CHANGELOG section, the checksums, the signing certificate and how to verify.
set -euo pipefail
version="$1"; dist="$2"; repo="$3"; sha="$4"
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
notes="$(awk -v v="$version" '$0 ~ "^## "v"( |$)" {f=1; next} /^## / {f=0} f' "$here/../CHANGELOG.md")"
cert="CD:C5:E3:D0:AC:6F:C9:0B:53:E8:8C:57:E0:40:93:36:72:5A:E3:C2:6F:D1:9D:1A:7E:58:51:70:94:5F:6D:E3"
cat <<EOF
${notes:-Commit $sha}

### 校验 / Verify

\`\`\`
$(cat "$dist/SHA256SUMS")
\`\`\`

签名证书 SHA-256 / Signing certificate: \`$cert\`

\`gh attestation verify <apk> -R $repo\`
EOF
