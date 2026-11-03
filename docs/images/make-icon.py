"""Draw the store icon from the launcher's vector drawables.

  ../../fastlane/metadata/android/en-US/images/icon.png   512 x 512

The geometry and colours are copied from
app/src/main/res/drawable/ic_launcher_background.xml and
ic_launcher_foreground.xml (108 x 108 viewport); change them there first,
then here. The result is the full 108 dp square with rounded corners, which
is also what make-banners.py places on the banners.

Usage: python make-icon.py   (needs Pillow and NumPy)
"""
import os
import numpy as np
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.normpath(os.path.join(HERE, "..", "..", "fastlane", "metadata", "android",
                                    "en-US", "images", "icon.png"))
SIZE = 512
SS = 4                      # supersampling for the hard edges
VIEW = 108.0


def hexrgb(h):
    return np.array([int(h[i:i + 2], 16) for i in (2, 4, 6)], dtype=np.float64)


def ramp(t, stops):
    """Linear interpolation through (offset, colour) stops, t in [0, 1]."""
    t = np.clip(t, 0.0, 1.0)
    offs = np.array([o for o, _ in stops])
    cols = np.array([hexrgb(c) for _, c in stops])
    return np.stack([np.interp(t, offs, cols[:, k]) for k in range(3)], axis=-1)


def grid(n):
    c = (np.arange(n) + 0.5) * VIEW / n
    return np.meshgrid(c, c)


def rrect_mask(n, x0, y0, x1, y1, r):
    """Anti-aliased rounded-rectangle mask in viewport units."""
    big = Image.new("L", (n * SS, n * SS), 0)
    k = n * SS / VIEW
    ImageDraw.Draw(big).rounded_rectangle((x0 * k, y0 * k, x1 * k, y1 * k), r * k, fill=255)
    return np.asarray(big.resize((n, n), Image.LANCZOS), dtype=np.float64) / 255.0


def render(n):
    x, y = grid(n)
    # Background: radial safelight glow from below.
    img = ramp(np.hypot(x - 54, y - 96) / 84, [(0, "FF2A1D12"), (0.55, "FF161412"), (1, "FF0E0D0C")])
    # Film base, edge to edge.
    base = ramp((y - 31) / 46, [(0, "FFC08757"), (1, "FF9C6A42")])
    m = rrect_mask(n, 0, 31, VIEW, 77, 0)[..., None]
    img = img * (1 - m) + base * m
    # Sprocket holes.
    hole = hexrgb("FF0E0D0C")
    for top in (34.5, 68.5):
        for left in range(2, 99, 12):
            m = rrect_mask(n, left, top, left + 7, top + 5, 1)[..., None]
            img = img * (1 - m) + hole * m
    # The frame, lit from behind.
    lit = ramp(np.hypot(x - 51, y - 57) / 24,
               [(0, "FFFFE2AE"), (0.35, "FFF2B565"), (0.8, "FFB5692A"), (1, "FF5A3216")])
    m = rrect_mask(n, 36, 42, 72, 66, 1.5)[..., None]
    img = img * (1 - m) + lit * m
    rgb = Image.fromarray(np.clip(img + 0.5, 0, 255).astype(np.uint8), "RGB")
    # Store icons are shown with rounded corners; bake a gentle radius in.
    alpha = Image.fromarray((rrect_mask(n, 0, 0, VIEW, VIEW, 22) * 255 + 0.5).astype(np.uint8), "L")
    rgb.putalpha(alpha)
    return rgb


if __name__ == "__main__":
    icon = render(SIZE)
    icon.save(OUT, optimize=True)
    print(OUT, icon.size, os.path.getsize(OUT), "bytes")
