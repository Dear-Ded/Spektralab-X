#!/usr/bin/env bash
# Co-created with GPT (OpenAI), 2026-11-12. See NOTICE.
# Use only on a disposable cloud emulator; record readiness for each case.
set -euo pipefail
: "${ANDROID_HOME:?SDK required}" "${BUILD_DIR:?build_feedback_android.sh output required}"
: "${RESULTS:?new isolated host results directory required}"
: "${ADB_SERIAL:?explicit disposable emulator serial required}"
case ${ANDROID_CASES:-all} in all|codec|pipeline) ;; *) printf 'ANDROID_CASES must be all, codec or pipeline\n' >&2; exit 1;; esac
adb=("$ANDROID_HOME/platform-tools/adb" -s "$ADB_SERIAL")
[[ $ADB_SERIAL == emulator-* ]] || { printf 'Cloud emulator required\n' >&2; exit 1; }
boot_complete=$("${adb[@]}" shell getprop sys.boot_completed | tr -d '\r')
[[ $boot_complete == 1 || ${ALLOW_BOOTING_AVD:-0} == 1 ]] || {
  printf 'Emulator must finish booting first; ALLOW_BOOTING_AVD=1 labels an unstable-runtime diagnostic\n' >&2
  exit 1
}
mkdir -p "$RESULTS"
device_dir="/data/local/tmp/spk-feedback-${RUN_ID:-20261112}"
"${adb[@]}" shell mkdir -p "$device_dir"
"${adb[@]}" push "$BUILD_DIR/native/libspektralab_jni.so" "$BUILD_DIR/feedback.jar" \
  "$BUILD_DIR/libfeedback_fixture.so" "$device_dir/"
"${adb[@]}" push "$BUILD_DIR/native/engine/resources" "$device_dir/"
printf 'initial_boot_completed=%s\nallow_booting_avd=%s\n' "$boot_complete" "${ALLOW_BOOTING_AVD:-0}" \
  > "$RESULTS/readiness.txt"
"${adb[@]}" shell 'getprop ro.build.fingerprint; getprop ro.build.version.sdk; getprop dalvik.vm.heapsize; getprop dalvik.vm.heapgrowthlimit; getconf PAGESIZE; cat /proc/meminfo' \
  > "$RESULTS/android-environment.txt"
cp "$BUILD_DIR/build-inputs.txt" "$RESULTS/build-inputs.txt"
printf 'case,width,height,exit_code,boot_completed_before,boot_completed_after\n' > "$RESULTS/cases.csv"
run_case() {
  local name=$1 width=$2 height=$3 mode=$4
  local ready_before ready_after
  ready_before=$("${adb[@]}" shell getprop sys.boot_completed | tr -d '\r')
  set +e
  timeout --kill-after=15s 900s "${adb[@]}" shell \
    "SPEKTRAFILM_VULKAN_FORCE_SPLIT=1 CLASSPATH=$device_dir/feedback.jar app_process -Xmx512m -Djava.library.path=$device_dir /data/local/tmp com.dearded.spektralab.FeedbackAndroid $device_dir/libspektralab_jni.so $device_dir/resources $device_dir/$name $width $height $mode" \
    > "$RESULTS/$name.log" 2>&1
  local status=$?
  set -e
  ready_after=$("${adb[@]}" shell getprop sys.boot_completed | tr -d '\r')
  printf '%s,%s,%s,%s,%s,%s\n' "$name" "$width" "$height" "$status" "$ready_before" "$ready_after" >> "$RESULTS/cases.csv"
  "${adb[@]}" pull "$device_dir/$name" "$RESULTS/" >> "$RESULTS/pull.log" 2>&1 || true
  tail -12 "$RESULTS/$name.log"
  return "$status"
}
if [[ ${ANDROID_CASES:-all} != pipeline ]]; then
  run_case codec-full 6709 4475 codec || true
fi
if [[ ${ANDROID_CASES:-all} != codec ]]; then
  if run_case pipeline-smoke 1281 855 pipeline; then
    run_case pipeline-full 6709 4475 pipeline || true
  else
    printf 'pipeline-full,6709,4475,skipped-prerequisite,,\n' >> "$RESULTS/cases.csv"
  fi
fi
"${adb[@]}" logcat -d -t 300 > "$RESULTS/logcat-tail.txt"
cat "$RESULTS/cases.csv"
