"""Social preview for the repo (1280x640): wordmark on the left, a strip of
negative with three real engine renders of the same frame on the right.
Drawn at 2x and downsampled.  python social_preview.py <films_dunes.jpg> <out.png>"""
import sys
from PIL import Image, ImageDraw, ImageFilter, ImageFont

S = 2
W, H = 1280 * S, 640 * S
BG = (14, 13, 12)
FG = (240, 236, 228)
DIM = (150, 146, 138)
AMBER = (232, 162, 74)
EDGE_INK = (232, 162, 74)
# Aura.kt AuraColors.spectrum (the launcher strip): Gemini blue, teal, green, yellow, red.
SPECTRUM = [(49, 134, 255), (36, 162, 202), (0, 185, 92), (251, 188, 4), (252, 65, 61)]

def font(name, size):
    for n in name:
        try:
            return ImageFont.truetype(n, size * S)
        except OSError:
            pass
    return ImageFont.load_default()

BOLD = ["C:/Windows/Fonts/segoeuib.ttf", "C:/Windows/Fonts/msyhbd.ttc"]
REG = ["C:/Windows/Fonts/segoeui.ttf", "C:/Windows/Fonts/msyh.ttc"]
LIGHT = ["C:/Windows/Fonts/segoeuil.ttf", "C:/Windows/Fonts/msyhl.ttc", "C:/Windows/Fonts/msyh.ttc"]
CJK = ["C:/Windows/Fonts/msyhl.ttc", "C:/Windows/Fonts/msyh.ttc"]
MONO = ["C:/Windows/Fonts/bahnschrift.ttf", "C:/Windows/Fonts/consola.ttf"]

def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))

def spectrum(t):
    n = len(SPECTRUM) - 1
    i = min(int(t * n), n - 1)
    return lerp(SPECTRUM[i], SPECTRUM[i + 1], t * n - i)

def tiles(src):
    im = Image.open(src).convert("RGB")
    tile, gap, pad = 629, 16, 40
    th = round(tile * 1067 / 1600)
    out = {}
    names = ["portra", "ektar", "gold", "cine", "velvia", "trix"]
    for k, n in enumerate(names):
        x = pad + (k % 3) * (tile + gap)
        y = pad + (k // 3) * (th + 64)
        out[n] = im.crop((x + 2, y + 2, x + tile - 2, y + th - 2))
    return out

def main(src, dst):
    img = Image.new("RGB", (W, H), BG)

    # Safelight glow, low and warm, behind the strip.
    glow = Image.new("RGB", (W, H), BG)
    gd = ImageDraw.Draw(glow)
    gd.ellipse((W * 0.40, H * 0.10, W * 1.25, H * 1.30), fill=(70, 38, 14))
    glow = glow.filter(ImageFilter.GaussianBlur(220 * S))
    img = Image.blend(img, glow, 0.55)
    d = ImageDraw.Draw(img)

    # Film strip, slightly tilted, three frames.
    t = tiles(src)
    fw, fh = 300 * S, 200 * S
    gapf = 22 * S
    rebate = 34 * S
    sw = 3 * fw + 4 * gapf + 260 * S
    sh = fh + 2 * rebate
    strip = Image.new("RGBA", (sw, sh), (22, 20, 18, 255))
    sd = ImageDraw.Draw(strip)
    # sprocket holes
    hw, hh, pitch = 16 * S, 11 * S, 30 * S
    x = 10 * S
    while x < sw:
        for y in (11 * S, sh - 11 * S - hh):
            sd.rounded_rectangle((x, y, x + hw, y + hh), radius=3 * S, fill=(8, 8, 7, 255))
        x += pitch
    # frames
    order = [("portra", "PORTRA 400 STYLE"), ("velvia", "VELVIA 100 STYLE"), ("trix", "TRI-X 400 STYLE")]
    edge = font(MONO, 10)
    fx = gapf + 40 * S
    for i, (key, label) in enumerate(order):
        fr = t[key].resize((fw, fh), Image.LANCZOS)
        strip.paste(fr, (fx, rebate))
        sd.text((fx + 4 * S, rebate - 15 * S), f"{label}   {i * 2 + 12}  {i * 2 + 12}A", font=edge, fill=EDGE_INK + (200,))
        fx += fw + gapf
    sd.text((fx + 6 * S, rebate + fh / 2 - 8 * S), "SPEKTRALAB-X", font=font(MONO, 13), fill=EDGE_INK + (160,))
    strip = strip.rotate(-7, resample=Image.BICUBIC, expand=True)
    # drop shadow
    shadow = Image.new("RGBA", strip.size, (0, 0, 0, 0))
    shadow.paste((0, 0, 0, 170), mask=strip.split()[3])
    shadow = shadow.filter(ImageFilter.GaussianBlur(28 * S))
    ox, oy = int(W * 0.47), int(H * 0.18)
    img.paste(shadow, (ox + 14 * S, oy + 26 * S), shadow)
    img.paste(strip, (ox, oy), strip)

    # Left column.
    d = ImageDraw.Draw(img)
    lx = 80 * S
    # icon: spectrum frame
    iy = 92 * S
    for k in range(64 * S):
        d.line((lx + k, iy + 8 * S, lx + k, iy + 30 * S), fill=spectrum(k / (64 * S)))
    for k in range(4):
        hx = lx + 4 * S + k * 16 * S
        d.rectangle((hx, iy, hx + 8 * S, iy + 5 * S), fill=(60, 58, 55))
        d.rectangle((hx, iy + 33 * S, hx + 8 * S, iy + 38 * S), fill=(60, 58, 55))

    d.text((lx - 4 * S, 158 * S), "Spektralab", font=font(BOLD, 74), fill=FG)
    wx = lx - 4 * S + d.textlength("Spektralab", font=font(BOLD, 74))
    d.text((wx, 158 * S), "-X", font=font(BOLD, 74), fill=AMBER)

    d.text((lx, 262 * S), "光谱胶片冲印，装进你的小米", font=font(CJK, 28), fill=FG)
    d.text((lx, 306 * S), "Spectral film & darkroom print, on a phone", font=font(LIGHT, 20), fill=DIM)

    d.rectangle((lx, 362 * S, lx + 44 * S, 364 * S), fill=AMBER)

    tags = ["RAW", "Vulkan", "Xiaomi 10–18", "GPL-3.0"]
    tx = lx
    f = font(REG, 15)
    for tag in tags:
        tw = d.textlength(tag, font=f)
        d.rounded_rectangle((tx, 388 * S, tx + tw + 24 * S, 418 * S), radius=15 * S, outline=(70, 66, 60), width=S)
        d.text((tx + 12 * S, 392 * S), tag, font=f, fill=DIM)
        tx += tw + 34 * S

    d.text((lx, 540 * S), "Free · No ads · No in-app purchase · Online only for updates", font=font(REG, 15), fill=(110, 106, 100))
    d.text((lx, 566 * S), "github.com/Dear-Ded/Spektralab-X", font=font(MONO, 15), fill=AMBER)

    # Fine film grain over everything except nothing; very light.
    out = img.resize((1280, 640), Image.LANCZOS)
    out.save(dst, optimize=True)
    print("ok", out.size)

if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
