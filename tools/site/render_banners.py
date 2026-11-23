"""Render the README banner and the GitHub social preview from banner.html.

Needs headless Edge or Chrome (set BROWSER to override) and Pillow.

    python tools/site/render_banners.py
"""
import os
import shutil
import subprocess
import tempfile
import time
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
SRC = (ROOT / "tools/site/banner.html").as_uri()

CANDIDATES = [
    os.environ.get("BROWSER", ""),
    r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
    r"C:\Program Files\Google\Chrome\Application\chrome.exe",
    "google-chrome", "chromium", "microsoft-edge",
]


def browser():
    for c in CANDIDATES:
        if c and (Path(c).exists() or shutil.which(c)):
            return c
    raise SystemExit("No Edge or Chrome found; set BROWSER")


def shot(query, w, h, out, scale=2):
    with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as tmp:
        png = Path(tmp) / "shot.png"
        subprocess.run([
            browser(), "--headless=new", "--disable-gpu", "--hide-scrollbars",
            "--allow-file-access-from-files", f"--user-data-dir={tmp}/profile",
            f"--window-size={w},{h}", f"--force-device-scale-factor={scale}",
            "--virtual-time-budget=3000", f"--screenshot={png}", f"{SRC}?{query}",
        ], check=True, capture_output=True)
        # Edge on Windows can return before the file is written
        for _ in range(150):
            if png.exists() and png.stat().st_size > 0:
                break
            time.sleep(0.1)
        time.sleep(0.5)
        img = Image.open(png).convert("RGB").crop((0, 0, w * scale, h * scale))
        out.parent.mkdir(parents=True, exist_ok=True)
        if out.suffix == ".png":
            img.save(out, optimize=True)
        else:
            img.save(out, quality=90, optimize=True, progressive=True)
        print(out.relative_to(ROOT), img.size)


if __name__ == "__main__":
    shot("w=1280&h=520&pw=62%25&pad=72&fs=78", 1280, 520, ROOT / "docs/images/readme-banner.jpg")
    shot("w=1280&h=520&pw=62%25&pad=72&fs=78&lang=en", 1280, 520, ROOT / "docs/images/readme-banner-en.jpg")
    shot("w=1280&h=640&pw=60%25&pad=80&fs=84", 1280, 640, ROOT / "docs/images/social-preview.png", scale=1)
