"""Grade spk_parity dumps in CIEDE2000.

  python3 grade.py <dir> [<dir> ...]

Each <dir> holds old.f32, export.f32, display.f32 and exact.f32 (spk_parity).
Every comparison is against export.f32, upstream's own export path, except
display-vs-exact, which isolates the display tables' error. Values are encoded
sRGB; they are decoded, taken to XYZ (D65) and CIELAB, then compared per pixel.
"""
import sys
import numpy as np


def load(path):
    with open(path, "rb") as f:
        w, h = np.frombuffer(f.read(8), dtype=np.uint32)
        return np.frombuffer(f.read(), dtype=np.float32).reshape(int(h), int(w), 3).astype(np.float64)


def to_lab(srgb):
    c = np.clip(srgb, 0.0, 1.0)
    lin = np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)
    m = np.array([[0.4124564, 0.3575761, 0.1804375],
                  [0.2126729, 0.7151522, 0.0721750],
                  [0.0193339, 0.1191920, 0.9503041]])
    xyz = lin @ m.T / np.array([0.95047, 1.0, 1.08883])
    f = np.where(xyz > (6 / 29) ** 3, np.cbrt(xyz), xyz / (3 * (6 / 29) ** 2) + 4 / 29)
    return np.stack([116 * f[..., 1] - 16, 500 * (f[..., 0] - f[..., 1]), 200 * (f[..., 1] - f[..., 2])], -1)


def ciede2000(lab1, lab2):
    L1, a1, b1 = lab1[..., 0], lab1[..., 1], lab1[..., 2]
    L2, a2, b2 = lab2[..., 0], lab2[..., 1], lab2[..., 2]
    C1, C2 = np.hypot(a1, b1), np.hypot(a2, b2)
    Cm = (C1 + C2) / 2
    G = 0.5 * (1 - np.sqrt(Cm ** 7 / (Cm ** 7 + 25 ** 7)))
    a1p, a2p = (1 + G) * a1, (1 + G) * a2
    C1p, C2p = np.hypot(a1p, b1), np.hypot(a2p, b2)
    h1p = np.degrees(np.arctan2(b1, a1p)) % 360
    h2p = np.degrees(np.arctan2(b2, a2p)) % 360
    dLp, dCp = L2 - L1, C2p - C1p
    dh = h2p - h1p
    dh = np.where(np.abs(dh) > 180, dh - 360 * np.sign(dh), dh)
    dh = np.where(C1p * C2p == 0, 0, dh)
    dHp = 2 * np.sqrt(C1p * C2p) * np.sin(np.radians(dh / 2))
    Lpm, Cpm = (L1 + L2) / 2, (C1p + C2p) / 2
    hs = h1p + h2p
    hpm = np.where(np.abs(h1p - h2p) > 180, np.where(hs < 360, hs + 360, hs - 360) / 2, hs / 2)
    hpm = np.where(C1p * C2p == 0, hs, hpm)
    T = (1 - 0.17 * np.cos(np.radians(hpm - 30)) + 0.24 * np.cos(np.radians(2 * hpm))
         + 0.32 * np.cos(np.radians(3 * hpm + 6)) - 0.20 * np.cos(np.radians(4 * hpm - 63)))
    dth = 30 * np.exp(-(((hpm - 275) / 25) ** 2))
    Rc = 2 * np.sqrt(Cpm ** 7 / (Cpm ** 7 + 25 ** 7))
    Sl = 1 + 0.015 * (Lpm - 50) ** 2 / np.sqrt(20 + (Lpm - 50) ** 2)
    Sc, Sh = 1 + 0.045 * Cpm, 1 + 0.015 * Cpm * T
    Rt = -np.sin(np.radians(2 * dth)) * Rc
    return np.sqrt((dLp / Sl) ** 2 + (dCp / Sc) ** 2 + (dHp / Sh) ** 2 + Rt * (dCp / Sc) * (dHp / Sh))


def stats(a, b):
    d = ciede2000(to_lab(a), to_lab(b)).ravel()
    codes = np.abs(np.round(a * 255) - np.round(b * 255)).max()
    return (f"mean {d.mean():.3f}  p95 {np.percentile(d, 95):.3f}  p99.9 {np.percentile(d, 99.9):.3f}  "
            f"max {d.max():.3f}  max8bit {int(codes)}")


PAIRS = [("old", "export", "old Android path vs upstream export"),
         ("display", "export", "new screen path  vs upstream export"),
         ("exact", "export", "screen, exact curves vs export"),
         ("display", "exact", "display tables   vs exact curves")]

for d in sys.argv[1:]:
    print(d)
    img = {k: load(f"{d}/{k}.f32") for k in ("old", "export", "display", "exact")}
    for a, b, label in PAIRS:
        print(f"  {label:38s} {stats(img[a], img[b])}")
