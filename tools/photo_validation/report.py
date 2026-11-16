#!/usr/bin/env python3
"""Cloud-only aggregation and sRGB contact sheets for the real-photo probe.

Clipping statistics are descriptive, never a film-accuracy score. GPT (OpenAI).
"""
import argparse
import csv
import json
from pathlib import Path

from PIL import Image, ImageCms, ImageDraw, ImageOps

PROOFS = ("neg_portrait", "neg_classic", "neg_night", "slide_velvia",
          "slide_positive", "bw_dynamic", "ph_authentic", "ph_xmage")


def main(args):
    manifest = json.loads((args.photos / "PUBLIC_IMAGES.json").read_text())
    expected = {path.stem for path in args.fixtures.glob("*.json")}
    if len(expected) != 87 or len(manifest) != args.expected_photos:
        raise ValueError("incomplete recipe or public-photo collection")
    if args.allow_partial:
        manifest = [p for p in manifest if (args.results / (p["id"] + ".exit")).is_file()
                    and (args.results / (p["id"] + ".exit")).read_text().strip() == "0"]
    profile = ImageCms.ImageCmsProfile(ImageCms.createProfile("sRGB")).tobytes()
    args.out.mkdir(parents=True, exist_ok=True)
    summary = {"photos": [], "comparisons": 0, "all_exact": True, "partial": args.allow_partial,
               "scope": "Real SDR RGB photos, production JNI and host Vulkan. Preview/main consistency; not an official film-color or physical-display reference.",
               "statistics": "RGB8 clipping is descriptive and includes intended monochrome/contrast effects."}
    warnings = []
    for photo in manifest:
        sid = photo["id"]
        directory = args.results / sid
        if (args.results / (sid + ".exit")).read_text().strip() != "0":
            raise ValueError("failed per-photo process: " + sid)
        rows = list(csv.DictReader((directory / "results.csv").open()))
        keys = {(r["id"], r["output"]) for r in rows}
        if len(rows) != 174 or keys != {(i, s) for i in expected for s in ("sRGB", "Display P3")}:
            raise ValueError("incomplete or duplicated matrix: " + sid)
        for row in rows:
            if row["exact"] != "true" or int(row["bytes"]) != int(row["width"]) * int(row["height"]) * 4:
                raise ValueError("pixel/dimension failure: " + sid)
            count = int(row["width"]) * int(row["height"]) * 3
            row["zero_fraction"] = int(row["zero_channels"]) / count
            row["full_fraction"] = int(row["full_channels"]) / count
            if max(row["zero_fraction"], row["full_fraction"]) > .5:
                warnings.append({"photo": sid, **row})
        grouped = {space: [r for r in rows if r["output"] == space] for space in ("sRGB", "Display P3")}
        item = {"id": sid, "comparisons": len(rows), "distinct_outputs": {
            space: len({r["checksum"] for r in values}) for space, values in grouped.items()},
            "max_zero_fraction": max(r["zero_fraction"] for r in rows),
            "max_full_fraction": max(r["full_fraction"] for r in rows)}
        summary["photos"].append(item)
        summary["comparisons"] += len(rows)
        titles = ("Original sRGB",) + PROOFS
        paths = [args.photos / "thumbnails" / (sid + ".png")] + [directory / (s + ".ppm") for s in PROOFS]
        sheet = Image.new("RGB", (3 * 272, 3 * 292), "#e8e5df")
        draw = ImageDraw.Draw(sheet)
        for index, (title, path) in enumerate(zip(titles, paths)):
            with Image.open(path) as image:
                tile = ImageOps.contain(image.convert("RGB"), (256, 256))
            x, y = (index % 3) * 272, (index // 3) * 292
            sheet.paste(tile, (x + (272 - tile.width) // 2, y + (256 - tile.height) // 2))
            draw.text((x + 8, y + 264), title, fill="#202020")
        sheet.save(args.out / (sid + "-contact.png"), icc_profile=profile)
    (args.out / "SUMMARY.json").write_text(json.dumps(summary, indent=2) + "\n")
    (args.out / "CLIPPING_REVIEW.json").write_text(json.dumps(warnings, indent=2) + "\n")
    print(json.dumps(summary, indent=2))
    print("REVIEW", len(warnings), "rows over 50% zero/full channel clipping; not automatic defects")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--photos", type=Path, required=True)
    parser.add_argument("--fixtures", type=Path, required=True)
    parser.add_argument("--results", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--expected-photos", type=int, default=12)
    parser.add_argument("--allow-partial", action="store_true")
    main(parser.parse_args())
