"""Compose README sample images from the spk_render outputs in out/.

  compare_<scene>.jpg  neutral decode | film render, side by side
  films_<scene>.jpg    the same frame through six stocks, 3 x 2
"""
import sys
from PIL import Image, ImageDraw, ImageFont

OUT = "out"
DST = "final"
BG = (18, 18, 18)
FG = (236, 236, 236)
DIM = (150, 150, 150)
AMBER = (232, 162, 74)
SANS = "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"
BOLD = "/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc"

NAMES = {
    "none": "Neutral decode",
    "portra400": "Kodak Portra 400",
    "ektar100": "Kodak Ektar 100",
    "gold200": "Kodak Gold 200",
    "cinestill800t": "CineStill 800T",
    "velvia100": "Fujifilm Velvia 100",
    "trix400": "Kodak Tri-X 400",
}
CAMERA = {
    "skyline": "Google Pixel 2 XL · DNG",
    "lake": "Leica Q (Typ 116) · DNG",
    "dunes": "Ricoh GR III · DNG",
    "temple": "Fujifilm X100F · RAF",
    "fruit": "Canon EOS R5 · CR3",
    "flowers": "Nikon Z fc · NEF",
}


def font(path, size):
    try:
        return ImageFont.truetype(path, size)
    except OSError:
        return ImageFont.load_default()


def fit(im, w):
    return im.resize((w, round(im.height * w / im.width)), Image.LANCZOS)


def label(d, x, y, title, sub, size):
    d.text((x, y), title, font=font(BOLD, size), fill=FG)
    if sub:
        d.text((x, y + round(size * 1.45)), sub, font=font(SANS, round(size * 0.78)), fill=DIM)


def compare(scene, look, width=2000):
    pad, gap = 40, 16
    tile = (width - 2 * pad - gap) // 2
    a = fit(Image.open(f"{OUT}/{scene}_none.png").convert("RGB"), tile)
    b = fit(Image.open(f"{OUT}/{scene}_{look}.png").convert("RGB"), tile)
    cap = 96
    im = Image.new("RGB", (width, pad + a.height + cap), BG)
    im.paste(a, (pad, pad))
    im.paste(b, (pad + tile + gap, pad))
    d = ImageDraw.Draw(im)
    y = pad + a.height + 22
    label(d, pad, y, "Before", "Neutral decode, ProPhoto → sRGB, metered to middle grey", 26)
    label(d, pad + tile + gap, y, NAMES[look], CAMERA[scene] + " · SpektraLab engine, default settings", 26)
    d.rectangle([pad + tile + gap, y - 10, pad + tile + gap + 56, y - 7], fill=AMBER)
    im.save(f"{DST}/compare_{scene}.jpg", quality=88, optimize=True, progressive=True)


def films(scene, looks, width=2000):
    pad, gap, cols = 40, 16, 3
    tile = (width - 2 * pad - (cols - 1) * gap) // cols
    ims = [fit(Image.open(f"{OUT}/{scene}_{l}.png").convert("RGB"), tile) for l in looks]
    th, cap = ims[0].height, 64
    rows = (len(ims) + cols - 1) // cols
    im = Image.new("RGB", (width, pad + rows * (th + cap) + pad // 2), BG)
    d = ImageDraw.Draw(im)
    for k, (l, t) in enumerate(zip(looks, ims)):
        x = pad + (k % cols) * (tile + gap)
        y = pad + (k // cols) * (th + cap)
        im.paste(t, (x, y))
        d.text((x, y + th + 14), NAMES[l], font=font(BOLD, 24), fill=FG)
    im.save(f"{DST}/films_{scene}.jpg", quality=88, optimize=True, progressive=True)


def detail(scene, looks, box, width=2000):
    """100% crops of the same region, to show grain and halation at pixel level."""
    pad, gap = 40, 16
    n = len(looks)
    tile = (width - 2 * pad - (n - 1) * gap) // n
    srcs = [Image.open(f"{OUT}/{scene}_{l}.png").convert("RGB") for l in looks]
    W, H = srcs[0].size
    x0, y0, x1, y1 = (round(box[0] * W), round(box[1] * H), round(box[2] * W), round(box[3] * H))
    ims = [fit(s.crop((x0, y0, x1, y1)), tile) for s in srcs]
    th, cap = ims[0].height, 64
    im = Image.new("RGB", (width, pad + th + cap), BG)
    d = ImageDraw.Draw(im)
    for k, (l, t) in enumerate(zip(looks, ims)):
        x = pad + k * (tile + gap)
        im.paste(t, (x, pad))
        d.text((x, pad + th + 14), NAMES[l], font=font(BOLD, 24), fill=FG)
    im.save(f"{DST}/detail_{scene}.jpg", quality=90, optimize=True, progressive=True)


if __name__ == "__main__":
    import os
    os.makedirs(DST, exist_ok=True)
    for arg in sys.argv[1:]:
        kind, scene, *rest = arg.split(":")
        if kind == "compare":
            compare(scene, rest[0])
        elif kind == "detail":
            detail(scene, rest[0].split(","), [float(v) for v in rest[1].split(",")])
        else:
            films(scene, rest[0].split(","))
        print("made", arg)
