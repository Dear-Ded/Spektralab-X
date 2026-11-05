#!/usr/bin/env bash
# regress.sh -- the RAW sample regression set (docs/SAMPLES.md), in one go.
#
#   tools/samples/regress.sh <workdir> [record]
#
# For every file in tools/samples/manifest.tsv: download it from raw.pixls.us
# (CC0), check its SHA-256, decode it with spk_decode in both modes, compare
# with tools/samples/expected.tsv, and delete the file again (the box has
# little disk). `record` writes a new expected.tsv instead of comparing.
#
# Needs an upstream tree with patches/ applied and a build of spk_image_io
# (scripts/parity.sh leaves both in its work directory):
#   UP=<upstream tree> B=<build dir with engine/libspk_image_io.a>
# Environment:
#   ONLY="a b"   only manifest ids containing one of these
#   KEEP=1       keep the downloaded files in <workdir>/raw
#   SAMPLES_DIR= a directory already holding the files (names with _ for spaces)
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
W="${1:?work directory}"
mode="${2:-compare}"
UP="${UP:?upstream tree}"
B="${B:?upstream build directory}"
mkdir -p "$W/raw"
W="$(cd "$W" && pwd)"
log() { echo "== $*" >&2; }

# --- spk_decode ---------------------------------------------------------------
T="$W/spk_decode"
if [[ ! -x "$T" || "$here/spk_decode.cpp" -nt "$T" ]]; then
    log "spk_decode"
    E="$B/engine"
    # The LibRaw tree the build used: patches/libraw changes libraw.h, so the
    # pristine copy in $UP/build/deps would not match the library.
    lib="$(grep '^SPEKTRALAB_LIBRAW_SOURCE:' "$B/CMakeCache.txt" | cut -d= -f2-)"
    [[ -f "$lib/libraw/libraw.h" ]] || { echo "regress.sh: no LibRaw source in $B/CMakeCache.txt" >&2; exit 2; }
    c++ -O2 -std=c++20 -fopenmp "$here/spk_decode.cpp" -I"$UP/engine/src" -I"$lib" \
        -DLIBRAW_NODLL -o "$T" "$E/libspk_image_io.a" "$E/libspk_libraw.a" -lpthread
fi

sum() { sha256sum "$1" | cut -d' ' -f1; }
fetch() {  # fetch <sha> <rpu id> <name> -> path
    local sha="$1" id="$2" name="$3" file="$W/raw/${3// /_}"
    if [[ -f "$file" && "$(sum "$file")" == "$sha" ]]; then echo "$file"; return; fi
    local local_copy="${SAMPLES_DIR:-}/${name// /_}"
    if [[ -n "${SAMPLES_DIR:-}" && -f "$local_copy" && "$(sum "$local_copy")" == "$sha" ]]; then
        echo "$local_copy"; return
    fi
    local url="https://raw.pixls.us/getfile.php/$id/nice/$name"
    for attempt in 1 2 3 4 5; do
        curl -fsSL --retry 3 -C - -o "$file.part" "${url// /%20}" || true
        [[ -f "$file.part" && "$(sum "$file.part")" == "$sha" ]] && break
    done
    [[ -f "$file.part" && "$(sum "$file.part")" == "$sha" ]] || { rm -f "$file.part"; return 1; }
    mv "$file.part" "$file"
    echo "$file"
}

out="$W/results.tsv"
: > "$out"
fail=0
while IFS=$'\t' read -r key sha id name; do
    [[ -z "$key" || "$key" == \#* ]] && continue
    if [[ -n "${ONLY:-}" ]]; then
        hit=0; for o in $ONLY; do [[ "$key" == *"$o"* ]] && hit=1; done
        (( hit )) || continue
    fi
    if ! file="$(fetch "$sha" "$id" "$name")"; then
        printf '%s\t%s\tdownload-failed\n' "$key" all >> "$out"; fail=1; continue
    fi
    "$T" "$file" both 2>/dev/null | while read -r m status rest; do
        printf '%s\t%s\t%s %s\n' "$key" "$m" "$status" "$rest"
    done >> "$out" || true
    log "$key"
    [[ "${KEEP:-0}" == 1 || "$file" != "$W/raw/"* ]] || rm -f "$file"
done < "$here/manifest.tsv"

if [[ "$mode" == record ]]; then
    { echo "# key	mode	result (spk_decode, $(c++ --version | head -n1), $(uname -m))"; cat "$out"; } > "$here/expected.tsv"
    log "recorded $(wc -l < "$out") lines into tools/samples/expected.tsv"
    exit "$fail"
fi

# Exact hash first; when it differs, the means within 0.5 % still pass as
# "close" (another compiler or CPU), anything else fails.
python3 - "$here/expected.tsv" "$out" <<'PY' || fail=1
import sys
def load(p):
    d = {}
    for line in open(p, encoding="utf-8"):
        if line.startswith("#") or not line.strip(): continue
        k, m, r = line.rstrip("\n").split("\t", 2)
        d[(k, m)] = r
    return d
def fields(r):
    parts = r.split()
    f = {"status": parts[0]}
    for p in parts[1:]:
        if "=" in p: a, b = p.split("=", 1); f[a] = b
        elif "x" in p and p[0].isdigit(): f["size"] = p
    return f
exp, got = load(sys.argv[1]), load(sys.argv[2])
bad = 0
for key in sorted(got):
    g = fields(got[key]); e = fields(exp[key]) if key in exp else None
    if e is None: verdict = "new"
    elif g["status"] != e["status"] or g.get("size") != e.get("size"): verdict = "FAIL"
    elif g.get("sha256") == e.get("sha256"): verdict = "same"
    else:
        gm = [float(v) for v in g.get("mean", "0,0,0").split(",")]
        em = [float(v) for v in e.get("mean", "0,0,0").split(",")]
        close = all(abs(a - b) <= 0.005 * max(abs(b), 1e-6) for a, b in zip(gm, em))
        verdict = "close" if close else "FAIL"
    bad += verdict == "FAIL"
    print(f"{verdict:6} {key[0]:28} {key[1]:10} {got[key][:90]}")
missing = sorted(set(exp) - set(got))
for key in missing: print(f"absent {key[0]:28} {key[1]}")
print(f"{len(got)} results, {bad} failed")
sys.exit(1 if bad else 0)
PY
exit "$fail"
