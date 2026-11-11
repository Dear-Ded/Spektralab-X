#!/usr/bin/env bash
# Co-created with GPT (OpenAI), 2026-11-11. Read-only; no SDK download or VM boot.
set -u
printf '%s\n' '== clock / kernel / identity =='
date -u
uname -srm
id
printf '%s\n' '== CPU / memory / disk =='
lscpu
free -h
df -h / /tmp
printf '%s\n' '== nested virtualization / graphics =='
if [[ -e /dev/kvm ]]; then
    ls -l /dev/kvm
    [[ -r /dev/kvm && -w /dev/kvm ]] && printf '%s\n' 'KVM file readable/writable; ioctl usability still unproven'
else
    printf '%s\n' '/dev/kvm absent'
fi
printf 'vmx/svm CPU entries: '
grep -Ec '(^| )(vmx|svm)( |$)' /proc/cpuinfo || true
if [[ -d /dev/dri ]]; then ls -l /dev/dri; else printf '%s\n' '/dev/dri absent'; fi
for f in /sys/module/kvm_intel/parameters/nested /sys/module/kvm_amd/parameters/nested; do
    if [[ -r "$f" ]]; then printf '%s: ' "$f"; head -c 32 "$f"; fi
done
printf '%s\n' '== SDK / Java / QEMU =='
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/android}}"
printf 'SDK candidate: %s\n' "$sdk"
java -version 2>&1 || true
for p in emulator adb sdkmanager avdmanager qemu-system-x86_64 qemu-aarch64; do command -v "$p" || true; done
if [[ -d "$sdk" ]]; then
    du -sh "$sdk"
    find "$sdk" -maxdepth 2 -type d -print
    if [[ -x "$sdk/emulator/emulator" ]]; then "$sdk/emulator/emulator" -accel-check; fi
fi
printf '%s\n' 'No ADB daemon started; no environment secrets printed; no network request made.'
