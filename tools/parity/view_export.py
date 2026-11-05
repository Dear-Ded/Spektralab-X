#!/usr/bin/env python3
"""view_export.py -- does the editor's screen show the colours the export writes?

Snapseed 4.x users report exports more saturated than the preview. This checks
the same thing here, inside the app's own code (no upstream host involved):

  screen  what the editor shows: the `live` tier (preview_long_edge 1024, while
          a slider moves) and the `preview` tier (the sharp frame it settles on),
          packed by pack_rgba8 with the display transform (matrix and clip), 8-bit
  export  the file: the `full` tier, packed by pack_rgba16 with the export
          transform (CAM16 gamut compression), 16-bit

all rendered by spk_android from the same input and parameters, in sRGB and in
Display P3, and graded in CIEDE2000 in their common space after an area resize
in linear light. Gated at mean < 1: the full tier's view against its export
(same pixels, only the packing differs); each screen tier against the export
at 128 px on the long edge, where differences in sharpening and resampling drop
out and a colour or saturation shift stays; a 90 degree turn
(`geometry_quarter_turns` 1) against the plain export turned in numpy; and a
centre crop against the same region of the plain export, at 128 px. The
full-size screen and crop pairs are gated too: mean < 1, and never worse than
their recorded mean in view_export_baseline.json by more than 0.3. The pairs
whose baseline is itself 1 or more (the `live` tier on `synth` and `lake`, the
`lake` crop) are known differences in detail, not colour, and are held to the
baseline only. The geometry cases fix the exposure, so a crop that re-meters does not
count as a colour change.

    view_export.py --android spk_android --resources R --inputs DIR --out DIR [--check] [--record]

`--inputs` holds the `<scene>.f32` files host_parity.py prepares (scripts/
parity.sh leaves them in <work>/run/inputs). `--check` exits 1 on any failed
pair; `--record` rewrites the baseline (docs/PARITY.md section 8).
"""
import argparse
import json
import os
import subprocess
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from host_parity import LOOKS, OFF, PHOTO, ciede2000, read_rgb, srgb_decode, srgb_encode, to_lab  # noqa: E402

SCENES = ("synth", "lake", "skyline", "fruit")
CASE_LOOKS = ("portra", "velvia", "trix")
GEOMETRY_SCENES = ("lake", "skyline")
FIXED = {"auto_exposure": False}
TURN = {"geometry_quarter_turns": 1}
CROP = {"geometry_crop_x": 0.25, "geometry_crop_y": 0.25, "geometry_crop_w": 0.5, "geometry_crop_h": 0.5}
BAR_MEAN = 1.0
# The full-size pairs differ in detail between tiers by design; they may not get worse.
BASELINE = Path(__file__).resolve().with_name("view_export_baseline.json")
BASELINE_SLACK = 0.3


def area_matrix(n_in, n_out):
    """Rows of exact area weights taking n_in samples to n_out."""
    m = np.zeros((n_out, n_in))
    s = n_in / n_out
    for o in range(n_out):
        a, b = o * s, (o + 1) * s
        for i in range(int(np.floor(a)), min(n_in, int(np.ceil(b)))):
            m[o, i] = min(b, i + 1) - max(a, i)
        m[o] /= m[o].sum()
    return m


def resize(enc, h, w):
    """Encoded RGB -> area-resized in linear light -> encoded."""
    if enc.shape[:2] == (h, w):
        return enc
    ry, rx = area_matrix(enc.shape[0], h), area_matrix(enc.shape[1], w)
    lin = srgb_decode(enc)
    out = np.einsum("oh,hwc->owc", ry, lin)
    out = np.einsum("pw,owc->opc", rx, out)
    return srgb_encode(out)


def delta(a, b, space):
    e = ciede2000(to_lab(a.reshape(-1, 3), space), to_lab(b.reshape(-1, 3), space))
    return {"mean": round(float(e.mean()), 4), "p99": round(float(np.percentile(e, 99)), 4),
            "max": round(float(e.max()), 4)}


def render(a, scene, params, tier, d):
    d.mkdir(parents=True, exist_ok=True)
    env = {**os.environ, "SPK_TIER": tier, "LP_NUM_THREADS": str(a.threads)}
    cmd = [str(a.android), str(a.resources), "f32", str(a.inputs / f"{scene}.f32"), str(d),
           json.dumps({**OFF, **params})]
    p = subprocess.run(cmd, env=env, capture_output=True, text=True)
    if p.returncode:
        raise RuntimeError(f"spk_android {scene} {tier}: {p.stderr[-300:]}")
    out = {f.stem: read_rgb(f)[0] for f in d.glob("*.rgb") if f.stem != "print"}
    for f in d.glob("*.rgb"):
        f.unlink()
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--android", type=Path, required=True)
    ap.add_argument("--resources", type=Path, required=True)
    ap.add_argument("--inputs", type=Path, required=True)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--threads", type=int, default=3)
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--record", action="store_true", help="write the full-size baseline instead of checking it")
    ap.add_argument("--part", choices=("all", "tiers", "geometry"), default="all")
    a = ap.parse_args()
    a.out.mkdir(parents=True, exist_ok=True)
    baseline = json.loads(BASELINE.read_text()) if BASELINE.exists() and not a.record else {}
    rows = []

    def row(case, label, space, g, gate):
        """gate "bar": mean < BAR_MEAN. gate "baseline" (the full-size pairs): mean < BAR_MEAN
        too, unless the recorded baseline is itself at or above it (a known difference in
        detail, docs/PARITY.md section 8), and never worse than baseline + BASELINE_SLACK."""
        key = f"{case}|{label}|{space}"
        if gate == "bar":
            ok, note = g["mean"] < BAR_MEAN, f"bar {BAR_MEAN}"
        elif key in baseline:
            known = baseline[key] >= BAR_MEAN
            ok = g["mean"] <= baseline[key] + BASELINE_SLACK and (known or g["mean"] < BAR_MEAN)
            note = f"base {baseline[key]:.3f}" + (", known" if known else f", bar {BAR_MEAN}")
        else:
            ok, note = g["mean"] < BAR_MEAN, f"bar {BAR_MEAN}, no base"
        rows.append({"case": case, "pair": label, "space": space, **g, "gate": gate, "key": key, "pass": ok})
        print(f"{case:<20} {label:<30} {space:<5} mean {g['mean']:.3f}  p99 {g['p99']:.3f}  "
              f"max {g['max']:.2f}  {'ok' if ok else 'FAIL'} ({note})", flush=True)

    def broad(img):
        """128 px on the long edge: colour over areas, without edges and grain-scale detail."""
        h, w = img.shape[:2]
        s = 128 / max(h, w)
        return resize(img, max(1, round(h * s)), max(1, round(w * s)))

    for scene in SCENES if a.part in ("all", "tiers") else ():
        for look in CASE_LOOKS:
            case = f"{scene}_{look}"
            params = {**PHOTO, **LOOKS[look]}
            full = render(a, scene, params, "full", a.out / case / "full")
            # live: what the editor shows while a slider moves; preview: the
            # sharp frame it settles on once the finger lifts (EditorViewModel).
            screens = {t: render(a, scene, params, t, a.out / case / t) for t in ("live", "preview")}
            for space in ("srgb", "p3"):
                ex, fv = full[f"export_{space}"], full[f"view_{space}"]
                row(case, "full view vs export", space, delta(fv, ex, space), "bar")
                for tier, out in screens.items():
                    sv = out[f"view_{space}"]
                    # The colour question: at 128 px a difference in sharpening or
                    # resampling between the tiers drops out, a saturation shift stays.
                    row(case, f"{tier} vs export, 128 px", space, delta(broad(sv), broad(ex), space), "bar")
                    # Full size: the tiers differ in detail; held to the recorded baseline.
                    row(case, f"{tier} vs export", space, delta(sv, resize(ex, *sv.shape[:2]), space), "baseline")

    for scene in GEOMETRY_SCENES if a.part in ("all", "geometry") else ():
        case = f"{scene}_geometry"
        params = {**FIXED, **LOOKS["portra"]}
        plain = render(a, scene, params, "full", a.out / case / "plain")["export_srgb"]
        turned = render(a, scene, {**params, **TURN}, "full", a.out / case / "turn")["export_srgb"]
        if turned.shape[:2] != plain.shape[1::-1]:
            raise RuntimeError(f"{case}: a quarter turn gave {turned.shape[:2]} from {plain.shape[:2]}")
        # Whichever direction the engine turns, the colours must come along.
        g, k = min(((delta(turned, np.rot90(plain, k), "srgb"), k) for k in (1, 3)), key=lambda t: t[0]["mean"])
        row(case, f"turn 90 ({'ccw' if k == 1 else 'cw'}) vs plain", "srgb", g, "bar")
        crop = render(a, scene, {**params, **CROP}, "full", a.out / case / "crop")["export_srgb"]
        h, w = plain.shape[:2]
        region = resize(plain[int(round(h * 0.25)):int(round(h * 0.75)), int(round(w * 0.25)):int(round(w * 0.75))],
                        *crop.shape[:2])
        row(case, "centre crop vs plain, 128 px", "srgb", delta(broad(crop), broad(region), "srgb"), "bar")
        row(case, "centre crop vs plain", "srgb", delta(crop, region, "srgb"), "baseline")

    (a.out / "view_export.json").write_text(json.dumps(rows, indent=1))
    if a.record:
        base = {r["key"]: r["mean"] for r in rows if r["gate"] == "baseline"}
        BASELINE.write_text(json.dumps(base, indent=1, sort_keys=True) + chr(10))
        print(f"recorded {len(base)} baseline means into {BASELINE.name}")
    failed = [r for r in rows if not r["pass"]]
    print(f"{len(rows)} pairs, {len(failed)} failed (bar: mean < {BAR_MEAN}; "
          f"baseline pairs: mean <= baseline + {BASELINE_SLACK})")
    if a.check and failed:
        sys.exit(1)


if __name__ == "__main__":
    main()
