#!/usr/bin/env bash
set -euo pipefail
A=/data/work/gpt-raw-algorithm-20261113
trap 'printf "%s\n" "$?" > "$A/native.exit"' EXIT
G=/data/work/gpt-composable-styles-20261113/gradle-repo
mkdir -p "$A/baseline" "$A/source" "$A/normal" "$A/sanitized" "$A/performance"
tar -xzf "$A/baseline.tgz" -C "$A/baseline"
tar -xzf "$A/source.tgz" -C "$A/source"
tar -xzf "$A/source.tgz" -C "$G"
cd "$A/source"
find app tools -type f -print0 | sort -z | xargs -0 sha256sum > "$A/source.sha256"
cd "$G"
sha256sum --check "$A/source.sha256" > "$A/input-check.log"
{
  date -Iseconds
  uname -sr
  nproc
  g++ --version | head -1
  clang++-14 --version | head -1
  lscpu | grep -E 'Model name|CPU\(s\)|Thread|Core|Socket'
} > "$A/environment.log"
app="$G/app/src/main/cpp"
for variant in baseline candidate; do
  implementation="$app/raw_development.cpp"
  if [[ "$variant" == baseline ]]; then implementation="$A/baseline/app/src/main/cpp/raw_development.cpp"; fi
  g++ -std=c++20 -O2 -Wall -Wextra -Werror -ffp-contract=off -pthread -I"$app" \
    tools/native/raw_development_benchmark.cpp "$implementation" -o "$A/$variant-benchmark"
done
# Alternate complete processes, with internal warmup, while no build/test is running.
for round in 1 2 3 4 5; do
  for variant in baseline candidate; do
    if [[ "$round" == 1 ]]; then
      "$A/$variant-benchmark" "$A/$variant.bin" > "$A/performance/$variant-$round.csv"
    else
      "$A/$variant-benchmark" > "$A/performance/$variant-$round.csv"
    fi
  done
done
BUILD_DIR="$A/normal" CXX=g++ bash tools/native/run_raw_development.sh > "$A/normal.log" 2>&1
printf '0\n' > "$A/normal.exit"
ASAN_OPTIONS=detect_leaks=0:halt_on_error=1 UBSAN_OPTIONS=halt_on_error=1:print_stacktrace=1 \
  BUILD_DIR="$A/sanitized" CXX=clang++-14 SANITIZE=1 \
  bash tools/native/run_raw_development.sh > "$A/sanitized.log" 2>&1
printf '0\n' > "$A/sanitized.exit"
export UP=/data/work/gpt-rank-selection-20261113/upstream B=/data/work/gpt-rank-selection-20261113/host
export MATH_DIR=/data/work/gpt-lut-gate-20261111/aoidc LP_NUM_THREADS=2
BUILD_DIR="$A/output-normal" CXX=g++ bash tools/native/run_raw_development_output.sh > "$A/output-normal.log" 2>&1
printf '0\n' > "$A/output-normal.exit"
ASAN_OPTIONS=detect_leaks=0:halt_on_error=1 UBSAN_OPTIONS=halt_on_error=1:print_stacktrace=1 \
  BUILD_DIR="$A/output-sanitized" CXX=clang++-14 SANITIZE=1 \
  bash tools/native/run_raw_development_output.sh > "$A/output-sanitized.log" 2>&1
printf '0\n' > "$A/output-sanitized.exit"
sha256sum --check "$A/source.sha256" > "$A/input-check-final.log"
date -Iseconds > "$A/native-finished.txt"
