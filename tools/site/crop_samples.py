"""Cut the composed sample sheets in docs/images/samples/ into single frames
for the GitHub Pages site (docs/site/img/). The sheets are real engine renders
(docs/SAMPLES.md); this only crops and re-encodes them.

    python tools/site/crop_samples.py
"""
from pathlib import Path
import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "docs/images/samples"
OUT = ROOT / "docs/site/img"

SHEETS = {
    "compare_dunes.jpg": ["dunes-neutral", "dunes-portra400"],
    "compare_lake.jpg": ["lake-neutral", "lake-ektar100"],
    "compare_fruit.jpg": ["fruit-neutral", "fruit-velvia100"],
    "compare_skyline.jpg": ["skyline-neutral", "skyline-ektar100"],
    "films_dunes.jpg": ["film-portra400", "film-ektar100", "film-gold200",
                        "film-cinestill800t", "film-velvia100", "film-trix400"],
    "detail_temple.jpg": ["temple-neutral", "temple-portra400", "temple-cinestill800t"],
}


def runs(mask, min_len):
    out, start = [], None
    for i, v in enumerate(list(mask) + [False]):
        if v and start is None:
            start = i
        elif not v and start is not None:
            if i - start >= min_len:
                out.append((start, i))
            start = None
    return out


def frames(img):
    a = np.asarray(img.convert("RGB")).astype(int)
    bg = a[2, 2]
    diff = np.abs(a - bg).sum(axis=2) > 24
    rows = runs(diff.mean(axis=1) > 0.25, 120)  # photo bands, not caption lines
    boxes = []
    for y0, y1 in rows:
        cols = runs(diff[y0:y1].mean(axis=0) > 0.5, 120)
        for x0, x1 in cols:
            sub = diff[y0:y1, x0:x1].mean(axis=1) > 0.5
            ys = np.where(sub)[0]
            boxes.append((x0, y0 + ys[0], x1, y0 + ys[-1] + 1))
    return boxes


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    for sheet, names in SHEETS.items():
        img = Image.open(SRC / sheet)
        boxes = frames(img)
        if len(names) == 2:
            # A film render can have near-black edges that the detector reads
            # as margin; the sheets are symmetric, so mirror the neutral frame.
            x0, y0, x1, y1 = boxes[0]
            boxes = [boxes[0], (img.width - x1, y0, img.width - x0, y1)]
        assert len(boxes) == len(names), (sheet, boxes)
        for name, box in zip(names, boxes):
            # trim 2 px of anti-aliased edge on every side
            x0, y0, x1, y1 = box
            crop = img.crop((x0 + 2, y0 + 2, x1 - 2, y1 - 2)).convert("RGB")
            crop.save(OUT / f"{name}.webp", quality=88, method=6)
            print(name, crop.size)


if __name__ == "__main__":
    main()
