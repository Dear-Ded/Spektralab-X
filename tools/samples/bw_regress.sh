#!/usr/bin/env bash
# bw_regress.sh -- regression check for upstream SpektraLab #48 (a Xiaomi 13
# Ultra DNG rendered all black with every black-and-white film). The reporter
# closed it as rare and not reproducible; no cause was found. This checks what
# would make it happen, on phone DNGs with a GainMap (the same kind of file):
#
#   1. the decode, at 1, 2 and 4 LibRaw threads, in both modes, is not empty:
#      mean > 1e-3 in every channel, no NaN/Inf, every channel's maximum > 0
#      (one zero channel after the GainMap or the dual-illuminant matrix would
#      turn a B&W film's luminance weighting black);
#   2. every B&W film, printed on Multigrade IV, renders RUNS times with a mean
#      above 3 %, which catches an intermittent failure as well as a steady one.
#
#   tools/samples/bw_regress.sh <workdir> <dng>...
#
#   T=<spk_render> D=<spk_decode> R=<engine resources>   (regress.sh builds spk_decode,
#   docs/SAMPLES.md says how to build spk_render)   RUNS=20   THREADS="1 2 4"
# Needs python3 with Pillow for the render means. A 13 Ultra sample, once there
# is one under CC0, goes into manifest.tsv and onto this command line.
set -uo pipefail
W="${1:?work directory}"; shift
T="${T:?spk_render}"; D="${D:?spk_decode}"; R="${R:?engine resources}"
RUNS="${RUNS:-20}"
THREADS="${THREADS:-1 2 4}"
mkdir -p "$W"
FILMS=(kodak_tri_x_400 ilford_hp5_plus_400 kodak_tmax_100 fujifilm_neopan_acros_100_ii)
fail=0
for f in "$@"; do
    name="$(basename "$f")"
    for t in $THREADS; do
        while read -r mode status rest; do
            if [[ "$status" != ok ]]; then echo "FAIL decode $name t=$t $mode: $rest"; fail=1; continue; fi
            python3 - "$name" "$t" "$mode" "$rest" <<'PY' || fail=1
import sys
name, t, mode, rest = sys.argv[1:]
f = dict(p.split("=", 1) for p in rest.split() if "=" in p)
mean = [float(v) for v in f["mean"].split(",")]
mx = [float(v) for v in f["max"].split(",")]
ok = int(f["nonfinite"]) == 0 and min(mean) > 1e-3 and min(mx) > 0
print(("ok  " if ok else "FAIL") + f" decode {name} t={t} {mode} mean={f['mean']} max={f['max']} nonfinite={f['nonfinite']}")
sys.exit(0 if ok else 1)
PY
        done < <(SPEKTRALAB_RAW_THREADS="$t" "$D" "$f" both 2>&1)
    done
    for film in "${FILMS[@]}"; do
        params="{\"film_stock\":\"$film\",\"print_stock\":\"ilford_multigrade_iv_rc\",\"scan_film\":false}"
        low=""; worst=1
        for ((i = 1; i <= RUNS; i++)); do
            png="$W/bw.png"; rm -f "$png"
            if ! "$T" "$R" "$f" "$png" 1024 "$params" >/dev/null 2>"$W/bw.err"; then
                low="render failed: $(tail -c 200 "$W/bw.err" | tr '\n' ' ')"; break
            fi
            m="$(python3 -c 'import sys
from PIL import Image, ImageStat
print(sum(ImageStat.Stat(Image.open(sys.argv[1]).convert("RGB")).mean) / 3 / 255)' "$png")"
            worst="$(python3 -c "print(min($worst, $m))")"
            python3 -c "import sys; sys.exit(0 if $m > 0.03 else 1)" || { low="run $i mean $m"; break; }
        done
        if [[ -n "$low" ]]; then echo "FAIL render $name $film: $low"; fail=1
        else echo "ok   render $name $film x$RUNS lowest mean $worst"; fi
    done
done
exit "$fail"
