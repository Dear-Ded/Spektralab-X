#!/usr/bin/env bash
# release-notes.sh VERSION DIST_DIR REPO SHA -- the release body: the version's
# CHANGELOG section, the checksums, the signing certificate and how to verify.
# Fails rather than publish a release without notes or checksums.
set -euo pipefail
version="$1"; dist="$2"; repo="$3"; sha="$4"
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# Literal match on the heading (index(), not a regex: '.' in the version is not a wildcard).
notes="$(awk -v h="## $version" 'index($0, h) == 1 && (length($0) == length(h) || substr($0, length(h) + 1, 1) == " ") {f=1; next} /^## / {f=0} f' "$here/../CHANGELOG.md")"
[[ -n "${notes//[[:space:]]/}" ]] || { echo "release-notes: no CHANGELOG section for $version" >&2; exit 1; }
[[ -s "$dist/SHA256SUMS" ]] || { echo "release-notes: $dist/SHA256SUMS is missing" >&2; exit 1; }
sums="$(cat "$dist/SHA256SUMS")"
cert="$(tr -d '[:space:]' < "$here/signing-cert.sha256")"
cat <<EOF
$notes

### 校验 / Verify

\`\`\`
$sums
\`\`\`

签名证书 SHA-256 / Signing certificate: \`$cert\`

\`gh attestation verify <apk> -R $repo\` · commit \`$sha\`
EOF
