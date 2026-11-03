"""Draw the repository banners from the README sample renders.

  social-preview.png   1280 x 640, GitHub Settings -> Social preview
  ../../fastlane/metadata/android/en-US/images/featureGraphic.png   1024 x 500

The photo is the dunes sample (docs/images/samples/compare_dunes.jpg): the
left half of the frame from the neutral decode, the right half from the
Kodak Portra 400 render, so the banner shows the engine's real output.

Usage: python make-banners.py [FONT_DIR]
FONT_DIR must hold a Latin sans (regular and bold) and a CJK sans; the names
tried are listed in FONTS. Needs Pillow.
"""
import os
import sys
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
SAMPLE = os.path.join(HERE, "samples", "compare_dunes.jpg")
ICON = os.path.join(HERE, "..", "..", "fastlane", "metadata", "android", "en-US", "images", "icon.png")

BG = (18, 18, 18)
FG = (236, 233, 227)
DIM = (158, 154, 147)
AMBER = (232, 162, 74)

# Panel boxes inside compare_*.jpg (left: neutral decode, right: film render).
BEFORE = (40, 40, 992, 676)
AFTER = (1008, 40, 1960, 676)

FONTS = {
    "sans": ["NotoSans-Regular.ttf", "segoeui.ttf", "DejaVuSans.ttf"],
    "bold": ["NotoSans-Bold.ttf", "segoeuib.ttf", "DejaVuSans-Bold.ttf"],
    "cjk": ["NotoSansSC-VF.ttf", "NotoSansCJK-Regular.ttc", "msyh.ttc"],
}


def font(kind, size, dirs):
    for d in dirs:
        for name in FONTS[kind]:
            path = os.path.join(d, name)
            if os.path.exists(path):
                return ImageFont.truetype(path, size)
    sys.exit(f"no {kind} font found in {dirs}; pass a font directory")


def rounded(img, radius):
    mask = Image.new("L", img.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, img.size[0] - 1, img.size[1] - 1), radius, fill=255)
    out = Image.new("RGBA", img.size)
    out.paste(img, (0, 0), mask)
    return out


def split_photo(w, h):
    """The same crop from both panels, before on the left, film on the right."""
    sample = Image.open(SAMPLE).convert("RGB")
    before = sample.crop(BEFORE)
    after = sample.crop(AFTER)
    pw, ph = before.size
    # Largest w:h crop, centred, biased toward the foreground grass.
    if pw / ph > w / h:
        cw, ch = int(ph * w / h), ph
    else:
        cw, ch = pw, int(pw * h / w)
    x0 = (pw - cw) // 2
    y0 = (ph - ch) * 2 // 3
    box = (x0, y0, x0 + cw, y0 + ch)
    b = before.crop(box).resize((w, h), Image.LANCZOS)
    a = after.crop(box).resize((w, h), Image.LANCZOS)
    out = a.copy()
    out.paste(b.crop((0, 0, w // 2, h)), (0, 0))
    d = ImageDraw.Draw(out)
    d.rectangle((w // 2 - 1, 0, w // 2 + 1, h), fill=FG)
    return out


def banner(W, H, dirs, path):
    s = W / 1280
    img = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(img)

    pad = int(56 * s)
    pw = int(600 * s)
    ph = H - 2 * pad
    photo = rounded(split_photo(pw, ph), int(18 * s))
    px = W - pad - pw
    img.paste(photo, (px, pad), photo)
    small = font("sans", int(17 * s), dirs)
    for text, x in (("Before", px + int(16 * s)), ("Kodak Portra 400", px + pw // 2 + int(16 * s))):
        tw = d.textlength(text, font=small)
        y = pad + ph - int(40 * s)
        d.rounded_rectangle((x - int(8 * s), y - int(5 * s), x + tw + int(8 * s), y + int(25 * s)),
                            int(6 * s), fill=(0, 0, 0))
        d.text((x, y), text, font=small, fill=FG)

    x = pad + int(8 * s)
    icon = Image.open(ICON).convert("RGBA").resize((int(88 * s),) * 2, Image.LANCZOS)
    img.paste(icon, (x, pad + int(24 * s)), icon)

    y = pad + int(140 * s)
    d.text((x, y), "SpektraLab", font=font("bold", int(70 * s), dirs), fill=FG)
    y += int(84 * s)
    d.text((x, y), "for Android", font=font("sans", int(36 * s), dirs), fill=DIM)
    y += int(66 * s)
    d.text((x, y), "光谱胶片与相纸模拟", font=font("cjk", int(32 * s), dirs), fill=FG)
    y += int(46 * s)
    d.text((x, y), "Spectral film & print simulation", font=font("sans", int(25 * s), dirs), fill=DIM)
    y += int(58 * s)
    d.rectangle((x, y, x + int(52 * s), y + int(4 * s)), fill=AMBER)
    y += int(20 * s)
    d.text((x, y), "RAW · Vulkan · Xiaomi 10–18 · GPL-3.0", font=font("sans", int(21 * s), dirs), fill=DIM)
    y += int(34 * s)
    d.text((x, y), "Alpha · 快速迭代中", font=font("cjk", int(21 * s), dirs), fill=AMBER)

    img.save(path, optimize=True)
    print(path, img.size, os.path.getsize(path), "bytes")


if __name__ == "__main__":
    dirs = sys.argv[1:] or ["/usr/share/fonts/truetype/noto", "/usr/share/fonts/opentype/noto",
                            "/usr/share/fonts/truetype/dejavu", r"C:\Windows\Fonts"]
    banner(1280, 640, dirs, os.path.join(HERE, "social-preview.png"))
    banner(1024, 500, dirs, os.path.normpath(os.path.join(HERE, "..", "..", "fastlane", "metadata",
                                                           "android", "en-US", "images", "featureGraphic.png")))
