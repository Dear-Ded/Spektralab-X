#!/usr/bin/env python3
"""Build reproducible, opt-in bilingual .spklook candidates and public previews.

Run on cloud only. Python 3.10+, numpy, Pillow. No application/private inputs.
Co-created with GPT (OpenAI), 2026-11-15; see project LICENSE and NOTICE.
"""
import argparse
import hashlib
import io
import json
import struct
import urllib.request
import zipfile
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageOps

from colour import (cube_grid, desired_lab, gamut_map, grade, lab_to_pro_linear,
                    pro_encode, pro_to_lab, pro_to_srgb_preview, sample_tetra, srgb_to_pro)

ROOT = Path(__file__).resolve().parent
SOURCES = {
    "astronaut": "NASA / public domain, Eileen Collins portrait",
    "coffee": "Rachel Michetti / CC0, scikit-image data",
    "chelsea": "Stefan van der Walt / CC0, scikit-image data",
    "rocket": "SpaceX / public domain, scikit-image data",
}
DATA_BASE = "https://raw.githubusercontent.com/scikit-image/scikit-image/v0.25.2/skimage/data/"
DATA_DOC = "https://scikit-image.org/docs/0.25.x/api/skimage.data.html"


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def zip_entry(archive, name, data):
    info = zipfile.ZipInfo(name, (2026, 11, 15, 0, 0, 0))
    info.compress_type = zipfile.ZIP_DEFLATED
    info.external_attr = 0o644 << 16
    archive.writestr(info, data, compresslevel=9)


def chart():
    """Synthetic sRGB ramps, neutral patches and hue/saturation stress rows."""
    import colorsys
    out = np.ones((320, 512, 3))
    ramp = np.linspace(0, 1, 512)
    out[:64] = ramp[None, :, None]
    for row, saturation in enumerate((.12, .4, .7, 1)):
        rgb = np.array([colorsys.hsv_to_rgb(x, saturation, .85) for x in ramp])
        out[64 + row * 64:128 + row * 64] = rgb
    return out


def image8(values):
    return Image.fromarray(np.rint(np.clip(values, 0, 1) * 255).astype(np.uint8))


def fetch_photos(directory):
    directory.mkdir(parents=True, exist_ok=True)
    images, provenance = {}, []
    for name, credit in SOURCES.items():
        filename = name + (".jpg" if name == "rocket" else ".png")
        url = DATA_BASE + filename
        path = directory / filename
        if not path.exists():
            request = urllib.request.Request(url, headers={"User-Agent": "Spektralab-Style-Lab/1"})
            with urllib.request.urlopen(request, timeout=60) as response:
                data = response.read(8 << 20)
            path.write_bytes(data)
        data = path.read_bytes()
        image = Image.open(io.BytesIO(data)).convert("RGB")
        image.thumbnail((512, 512), Image.Resampling.LANCZOS)
        images[name] = np.asarray(image, dtype=np.float64) / 255
        provenance.append({"file": path.name, "url": url, "credit_license": credit,
                           "license_source": DATA_DOC, "sha256": hashlib.sha256(data).hexdigest(),
                           "preview": "embedded RGB treated as sRGB; not a RAW camera-profile test"})
    return images, provenance


def error_stats(reference, actual):
    error = np.linalg.norm(pro_to_lab(reference) - pro_to_lab(actual), axis=-1) * 100
    return {"mean": float(error.mean()), "p95": float(np.percentile(error, 95)),
            "p99": float(np.percentile(error, 99)), "max": float(error.max())}


def contact_sheet(rows, path, label):
    width, height, columns = 384, 300, 3
    sheet = Image.new("RGB", (columns * width, ((len(rows) + columns - 1) // columns) * height + 40), "#eeeae2")
    draw = ImageDraw.Draw(sheet)
    font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 15)
    draw.text((12, 10), label + " | sRGB previews, 100% candidate strength", font=font, fill="#242424")
    for i, (title, image) in enumerate(rows):
        x, y = (i % columns) * width, (i // columns) * height + 40
        tile = ImageOps.contain(image, (width - 16, height - 36), Image.Resampling.LANCZOS)
        sheet.paste(tile, (x + (width - tile.width) // 2, y))
        draw.text((x + 10, y + height - 28), title, font=font, fill="#242424")
    sheet.save(path)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--photos", type=Path, required=True)
    args = parser.parse_args()
    out = args.out
    out.mkdir(parents=True, exist_ok=True)
    styles = json.loads((ROOT / "candidates.json").read_text(encoding="utf-8"))["styles"]
    photos, provenance = fetch_photos(args.photos)
    write_json(out / "PUBLIC_IMAGES.json", provenance)
    samples = np.random.default_rng(20261115).random((32768, 3))
    public = np.concatenate([srgb_to_pro(p).reshape(-1, 3)[::13] for p in photos.values()])
    checks = {"prophoto_uniform": samples, "srgb_uniform": srgb_to_pro(samples), "public_photo": public}
    previews = {name: [("Original", image8(photo))] for name, photo in photos.items()}
    previews["chart"] = [("Original", image8(chart()))]
    metrics = {"algorithm": json.loads((ROOT / "candidates.json").read_text(encoding="utf-8"))["algorithm"], "seed": 20261115,
               "error_units": "100 * Euclidean Oklab; not CIEDE2000 or a quality rating",
               "scope": "Photographic SDR candidates, not a general ProPhoto color transform",
               "styles": []}
    package_manifest = []
    for style in styles:
        sid = style["id"]
        print("building " + sid, flush=True)
        table = grade(cube_grid(65), style).reshape(65, 65, 65, 3)
        # Match the actual six-decimal text package and Float32 application table.
        cube = ('TITLE "' + style["en"] + ' / encoded ProPhoto"\nLUT_3D_SIZE 65\n' +
                "DOMAIN_MIN 0 0 0\nDOMAIN_MAX 1 1 1\n" +
                "\n".join("%.6f %.6f %.6f" % tuple(row) for row in table.reshape(-1, 3)) + "\n").encode("ascii")
        assert len(cube) < 12 << 20
        quantized = np.round(table, 6).astype(np.float32).astype(np.float64)
        result = {"id": sid, "interpolation": {}, "out_of_gamut_before_mapping": {}}
        for name, pixels in checks.items():
            desired = desired_lab(pixels, style)
            raw_rgb = lab_to_pro_linear(desired)
            mapped = gamut_map(desired)
            contained = lab_to_pro_linear(mapped)
            # Existing app matrices are rounded to seven decimal places. The
            # neutral D65 white inverse has a ~2.03e-7 endpoint residual.
            assert np.isfinite(contained).all() and contained.min() >= -3e-7 and contained.max() <= 1 + 3e-7
            result["out_of_gamut_before_mapping"][name] = int(((raw_rgb < 0) | (raw_rgb > 1)).any(axis=-1).sum())
            result["interpolation"][name] = {"count": len(pixels), **error_stats(grade(pixels, style), sample_tetra(quantized, pixels))}
        result["comparison_33_srgb"] = error_stats(grade(checks["srgb_uniform"], style),
            sample_tetra(np.round(grade(cube_grid(33), style), 6).reshape(33, 33, 33, 3), checks["srgb_uniform"]))
        assert result["interpolation"]["public_photo"]["max"] < .75, sid + " public photo interpolation budget"
        assert result["interpolation"]["prophoto_uniform"]["max"] < 5, sid + " extreme cube interpolation budget"
        for name, photo in {**photos, "chart": chart()}.items():
            rgb = pro_to_srgb_preview(sample_tetra(quantized, srgb_to_pro(photo)))
            previews[name].append((style["en"], image8(rgb)))
        thumb = io.BytesIO()
        previews["chart"][-1][1].resize((256, 160)).save(thumb, "JPEG", quality=85)
        for language in ("zh", "en"):
            destination = out / language / (sid + ".spklook")
            destination.parent.mkdir(parents=True, exist_ok=True)
            look = {"format": "spklook", "version": 2, "name": style[language],
                    "author": "Spektralab-X contributors / GPT (OpenAI)", "license": "GPL-3.0; project NOTICE applies",
                    "category": "下版候选" if language == "zh" else "Next version candidates",
                    "scope": "filter", "layer2": {},
                    "lut": {"file": "lut.cube", "strength": 1.0, "space": "prophoto"},
                    "source": {"kind": "original-style-lab", "file": sid, "note": style["use_" + language]}}
            # Category must fit the production parser's 20-character boundary.
            if language == "en":
                look["category"] = "Candidate styles"
            with zipfile.ZipFile(destination, "w") as archive:
                zip_entry(archive, "look.json", (json.dumps(look, ensure_ascii=False, indent=2) + "\n").encode("utf-8"))
                zip_entry(archive, "lut.cube", cube)
                zip_entry(archive, "thumb.jpg", thumb.getvalue())
            package_manifest.append({"file": str(destination.relative_to(out)), "bytes": destination.stat().st_size,
                                     "sha256": hashlib.sha256(destination.read_bytes()).hexdigest()})
        # Existing production C++ harness reads this independent sorted-simplex fixture.
        fixture = out / (sid + ".bin")
        inputs = samples[:16384].astype(np.float32)
        expected = sample_tetra(quantized, inputs).astype(np.float32)
        with fixture.open("wb") as stream:
            stream.write(struct.pack("<II", 65, len(inputs)))
            stream.write(quantized.astype("<f4").tobytes())
            stream.write(np.concatenate((inputs, expected), axis=1).astype("<f4").tobytes())
        metrics["styles"].append(result)
    # Quantify a hard-clip baseline using the same desired perceptual colors.
    lab = np.column_stack((np.full(720, .65), .45 * np.cos(np.linspace(-np.pi, np.pi, 720)),
                           .45 * np.sin(np.linspace(-np.pi, np.pi, 720))))
    clipped = pro_to_lab(pro_encode(np.clip(lab_to_pro_linear(lab), 0, 1)))
    mapped = gamut_map(lab)
    def hue_error(output):
        angle = np.arctan2(output[:, 2], output[:, 1]) - np.arctan2(lab[:, 2], lab[:, 1])
        return np.abs(np.rad2deg(np.arctan2(np.sin(angle), np.cos(angle))))
    metrics["boundary_stress_720"] = {
        "hard_clip_hue_error_mean_deg": float(hue_error(clipped).mean()),
        "hard_clip_hue_error_max_deg": float(hue_error(clipped).max()),
        "soft_map_hue_error_mean_deg": float(hue_error(mapped).mean()),
        "soft_map_hue_error_max_deg": float(hue_error(mapped).max()),
        "hard_clip_lightness_error_mean": float(np.abs(clipped[:, 0] - lab[:, 0]).mean()),
        "hard_clip_lightness_error_max": float(np.abs(clipped[:, 0] - lab[:, 0]).max()),
        "soft_map_lightness_error_mean": float(np.abs(mapped[:, 0] - lab[:, 0]).mean()),
        "soft_map_lightness_error_max": float(np.abs(mapped[:, 0] - lab[:, 0]).max())}
    write_json(out / "metrics.json", metrics)
    write_json(out / "PACKAGES.json", package_manifest)
    for name, rows in previews.items():
        contact_sheet(rows, out / ("contact_" + name + ".jpg"), name.title())
    print(json.dumps(metrics["boundary_stress_720"]), flush=True)


if __name__ == "__main__":
    main()
