#!/usr/bin/env python3
"""Cloud-only new-photo checks of the ten opt-in style-lab candidates.

Mathematical previews, not Android device or native integration evidence.
Co-created with GPT (OpenAI).
"""
import argparse
import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageCms, ImageDraw, ImageOps

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "style_lab"))
from colour import cube_grid, grade, pro_to_lab, pro_to_srgb_preview, sample_tetra, srgb_to_pro


def main(args):
    styles = json.loads((Path(__file__).resolve().parents[1] / "style_lab/candidates.json").read_text())["styles"]
    photos = []
    for path in sorted((args.photos / "inputs").glob("*.ppm")):
        with Image.open(path) as image:
            rgb = np.asarray(image, dtype=np.float64) / 255
            photos.append((path.stem, image.copy(), srgb_to_pro(rgb)))
    if len(styles) != 10 or len(photos) != 12:
        raise ValueError("complete ten-style/twelve-photo candidate matrix required")
    args.out.mkdir(parents=True, exist_ok=True)
    report = {"scope": "Continuous float64 algorithm versus six-decimal Float32 65-cube; mathematical sRGB previews, not physical device/official film matching",
              "error_unit": "100 * Euclidean Oklab, not CIEDE2000", "cases": [], "max_budget": .75}
    previews = {sid: [("Original", image)] for sid, image, _ in photos}
    for style in styles:
        table = np.round(grade(cube_grid(65), style), 6).reshape(65, 65, 65, 3).astype(np.float32).astype(np.float64)
        for sid, image, source in photos:
            reference = grade(source, style)
            actual = sample_tetra(table, source)
            if not np.isfinite(actual).all():
                raise ValueError("nonfinite candidate: " + style["id"] + "/" + sid)
            error = np.linalg.norm(pro_to_lab(actual) - pro_to_lab(reference), axis=-1) * 100
            row = {"style": style["id"], "photo": sid, "pixels": image.width * image.height,
                   "max": float(error.max()), "p99": float(np.quantile(error, .99)), "mean": float(error.mean())}
            worst = np.unravel_index(np.argmax(error), error.shape)
            row["worst_rgb8"] = list(image.getpixel((int(worst[1]), int(worst[0]))))
            row["worst_encoded_prophoto"] = source[worst].tolist()
            report["cases"].append(row)
            rgb = pro_to_srgb_preview(actual)
            previews[sid].append((style["en"], Image.fromarray(np.rint(np.clip(rgb, 0, 1) * 255).astype(np.uint8))))
        failed = any(row["style"] == style["id"] and row["max"] >= report["max_budget"] for row in report["cases"])
        print("FAIL" if failed else "PASS", style["id"], "12 real photos", flush=True)
    profile = ImageCms.ImageCmsProfile(ImageCms.createProfile("sRGB")).tobytes()
    for sid, tiles in previews.items():
        sheet = Image.new("RGB", (4 * 272, 3 * 292), "#e8e5df")
        draw = ImageDraw.Draw(sheet)
        for index, (title, image) in enumerate(tiles):
            x, y = index % 4 * 272, index // 4 * 292
            tile = ImageOps.contain(image, (256, 256))
            sheet.paste(tile, (x + (272 - tile.width) // 2, y + (256 - tile.height) // 2))
            draw.text((x + 8, y + 264), title, fill="#202020")
        sheet.save(args.out / (sid + ".png"), icc_profile=profile)
    report["max_error"] = max(row["max"] for row in report["cases"])
    report["failed_cases"] = [row for row in report["cases"] if row["max"] >= report["max_budget"]]
    (args.out / "RESULT.json").write_text(json.dumps(report, indent=2) + "\n")
    if report["failed_cases"]:
        raise ValueError("existing candidate interpolation budget: " + json.dumps(report["failed_cases"]))
    print("PASS", len(report["cases"]), "candidate/photo checks; max", report["max_error"], flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--photos", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    main(parser.parse_args())
