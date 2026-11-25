#!/usr/bin/env python3
"""Make the project site's images from engine renders.

    python3 tools/site/images.py <renders dir>

<renders dir> holds <scene>_<look>.png as tools/samples/hires.sh writes them
(look "none" is the neutral decode). Writes WebP files to docs/site/img/:

  hero-dunes-portra400{,-1280}.webp        the opening print
  strip-dunes-<look>.webp                  the six frames of the 3D strip, 1200 x 800
  full-<scene>-<look>{,-1280}.webp         full-bleed photos, 2560 / 1280 px wide
  loupe-temple-<look>.webp                 the loupe's detail, native size

Only resizing and WebP encoding happen here; nothing is retouched.
"""
import os
import sys
from PIL import Image

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "docs", "site", "img")
FILMS = ["portra400", "ektar100", "gold200", "cinestill800t", "velvia100", "trix400"]
FULL = {"lake": ["none", "ektar100"], "fruit": ["none", "velvia100"], "skyline": ["none", "ektar100"],
        "temple": ["cinestill800t", "portra400"]}


def load(src, scene, look):
    return Image.open(os.path.join(src, f"{scene}_{look}.png")).convert("RGB")


def save(im, name, width, q=84):
    if im.width > width:
        im = im.resize((width, round(im.height * width / im.width)), Image.LANCZOS)
    path = os.path.join(OUT, name)
    im.save(path, "WEBP", quality=q, method=6)
    print(f"{name:40s} {im.width}x{im.height} {os.path.getsize(path) // 1024} KB")


def cover(im, w, h):
    s = max(w / im.width, h / im.height)
    r = im.resize((round(im.width * s), round(im.height * s)), Image.LANCZOS)
    x, y = (r.width - w) // 2, (r.height - h) // 2
    return r.crop((x, y, x + w, y + h))


def main(src):
    os.makedirs(OUT, exist_ok=True)
    hero = load(src, "dunes", "portra400")
    save(hero, "hero-dunes-portra400.webp", 2560, 82)
    save(hero, "hero-dunes-portra400-1280.webp", 1280, 82)
    for f in FILMS:
        save(cover(load(src, "dunes", f), 1200, 800), f"strip-dunes-{f}.webp", 1200, 86)
    for scene, looks in FULL.items():
        for look in looks:
            im = load(src, scene, look)
            save(im, f"full-{scene}-{look}.webp", 2560, 82)
            save(im, f"full-{scene}-{look}-1280.webp", 1280, 82)
    for look in FULL["temple"]:
        save(load(src, "temple", look), f"loupe-temple-{look}.webp", 4000, 88)


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    main(sys.argv[1])
