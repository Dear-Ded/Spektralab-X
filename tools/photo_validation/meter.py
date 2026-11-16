#!/usr/bin/env python3
"""Independent scene-meter checks on public photos. Cloud only. GPT (OpenAI)."""
import argparse
import json
from pathlib import Path

import numpy as np
from PIL import Image


def expected(rgb, prophoto=None, colorants=None):
    h, w, _ = rgb.shape
    encoded = rgb.astype(np.float64) / 255
    linear = np.where(encoded <= .04045, encoded / 12.92, ((encoded + .055) / 1.055) ** 2.4)
    # Keep full source conversion and the meter's linear-input boundary explicit.
    if prophoto is None:
        if colorants is None:
            y = linear @ np.array([.2225045, .7168786, .0606169])
        else:
            srgb_xyz = np.array([[.4360747, .3850649, .1430804], [.2225045, .7168786, .0606169],
                                 [.0139322, .0971045, .7141733]])
            prophoto_xyz = np.array([[.7976749, .1351917, .0313534], [.2880402, .7118741, .0000857],
                                     [0., 0., .82521]])
            y = (linear @ srgb_xyz.T @ np.linalg.inv(prophoto_xyz).T) @ colorants
    else:
        if prophoto.shape != rgb.shape or not np.isfinite(prophoto).all():
            raise ValueError("invalid metered ProPhoto source")
        coefficients = colorants if colorants is not None else [.288040237862310, .711874097235, .00008566490269]
        y = prophoto.astype(np.float64) @ np.asarray(coefficients)
    y = np.maximum(y, .184 / 4096)
    ordered = np.sort(y.ravel())
    p1, p5, p99, p995 = (ordered[q * (len(ordered) - 1) // 1000] for q in (10, 50, 990, 995))
    trim = (y >= p1) & (y <= p99)
    balanced = -np.log2(np.exp(np.log(y[trim]).mean()) / .184)
    xx = (np.arange(w) / w - .5) * w / max(h, w)
    yy = (np.arange(h) / h - .5) * h / max(h, w)
    weight = np.exp(-(xx[None, :] ** 2 + yy[:, None] ** 2) / (2 * .2 ** 2))[trim]
    center = -np.log2(np.exp(np.sum(np.log(y[trim]) * weight) / weight.sum()) / .184)
    return {"balanced": float(balanced), "center": float(center),
            "protect_highlights": float(np.clip(np.log2(.184 * 2 ** 2.5 / p995), balanced - 3, balanced)),
            "protect_shadows": float(np.clip(np.log2(.184 * 2 ** -3.5 / p5), balanced, balanced + 2))}


def main(args):
    rows = []
    colorants = json.loads(args.colorants.read_text())["ProPhoto_RGB_Y"] if args.colorants else None
    if colorants is not None and (np.shape(colorants) != (3,) or not np.isfinite(colorants).all()):
        raise ValueError("meter colorants")
    for path in sorted(args.photos.glob("*.ppm")):
        with Image.open(path) as image:
            rgb = np.asarray(image.convert("RGB"))
        prophoto = None
        if args.input == "linear-prophoto":
            prophoto = np.fromfile(args.actual / path.stem / "input.f32", dtype="<f4").reshape(rgb.shape)
        reference = expected(rgb, prophoto, colorants)
        measured = json.loads((args.actual / path.stem / "meter.json").read_text())
        actual = measured["exposure_ev_by_method"]
        errors = {key: abs(reference[key] - actual[key]) for key in reference}
        if abs(measured["solved_params"]["exposure_compensation_ev"] - actual["balanced"]) > 1e-12:
            raise ValueError("selected meter differs from solve: " + path.stem)
        row = {"id": path.stem, "reference": reference, "actual": actual, "error_ev": errors,
               "balanced_gain": 2 ** actual["balanced"]}
        rows.append(row)
        print(json.dumps(row), flush=True)
    report = {"reference": "float64 luminance and independent rank/trim/log/center/clamp equations",
              "input": args.input,
              "meter_colorants": colorants if colorants is not None else "unrounded reference luminance",
              "scope": "twelve normalized SDR images; numerical meter agreement, not aesthetic acceptance",
              "budget_ev": .0001, "photos": rows,
              "max_error_ev": max(max(row["error_ev"].values()) for row in rows)}
    args.out.write_text(json.dumps(report, indent=2) + "\n")
    if len(rows) != 12 or report["max_error_ev"] > report["budget_ev"]:
        raise ValueError("independent real-photo meter budget")
    print("PASS 12 photos x 4 independent meter intents", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--photos", type=Path, required=True)
    parser.add_argument("--actual", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--input", choices=("srgb", "linear-prophoto"), default="srgb")
    parser.add_argument("--colorants", type=Path)
    main(parser.parse_args())
