#!/usr/bin/env bash
# Independently inspect the signed CI candidate. Co-created with GPT (OpenAI).
set -euo pipefail
A=/data/work/gpt-alpha8-candidate-20261114
tools=/opt/android/build-tools/36.0.0
apk=Spektralab-X-0.2.00-alpha-8-arm64-v8a.apk
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
cd "$A"
trap 'printf "%s\n" "$?" > verification.exit' EXIT
sha256sum --check SHA256SUMS > checksum.log
unzip -tq "$apk" > archive.log
"$tools/apksigner" verify --verbose --print-certs "$apk" > signature.log
grep -F 'Signer #1 certificate SHA-256 digest: cdc5e3d0ac6fc90b53e88c57e0409336725ae3c26fd19d1a7e585170945f6de3' signature.log
"$tools/aapt2" dump badging "$apk" > badging.log
grep -F "package: name='com.dearded.spektralab' versionCode='128' versionName='0.2.00-alpha-8'" badging.log
grep -F "native-code: 'arm64-v8a'" badging.log
! grep -F 'application-debuggable' badging.log
"$tools/zipalign" -c -P 16 -v 4 "$apk" > alignment.log
mkdir -p extracted
unzip -q -o "$apk" 'lib/*' 'assets/engine/*' -d extracted
for so in extracted/lib/*/*.so; do
    readelf -lW "$so" >> elf.log
    min=$(readelf -lW "$so" | awk '$1 == "LOAD" { print $NF }' | sort -u | while read -r a; do printf '%d\n' "$a"; done | sort -n | head -1)
    test -n "$min" && test "$min" -ge 16384
done
count=0
for shader in extracted/assets/engine/vulkan/*.spv; do
    spirv-val --target-env vulkan1.0 "$shader" >> spirv.log 2>&1
    count=$((count+1))
done
test "$count" = 56
(cd extracted/assets/engine && find . -type f ! -name stamp.txt -print0 | LC_ALL=C sort -z | xargs -0 sha256sum | sha256sum | cut -c1-32) > computed-stamp.txt
cmp computed-stamp.txt extracted/assets/engine/stamp.txt
sha256sum "$apk" extracted/lib/*/*.so extracted/assets/engine/vulkan/*.spv > verified.sha256
printf 'PASS alpha-8 candidate: checksum, CRC, package/version, published certificate, ARM64, 16KiB alignment, asset stamp and %s validated shaders\n' "$count" > result.txt
cat result.txt
