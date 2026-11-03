#!/usr/bin/env python3
"""SpektraLab for Android against upstream's own Linux host, pixel by pixel.

The reference is `spektralab-host` (engine/host, built from the pinned
upstream tree with the app's patches): its `export_frame` (tiff16, CAM16 into
the file's space, lightness shoulder off) and `render_view` (the desktop's
screen path, matrix and clip). The Android side is `spk_android`
(spk_android.cpp): the app's opening parameters and the app's own
OutputTransform / Layer 2 code. Both get the same input bytes:

  synth    upstream's parity catalogue picture (engine/tests/parity_metal_vulkan.py
           make_input, linear sRGB), taken to linear ProPhoto and handed to both
           as floats: the host reads an untagged float TIFF as linear ProPhoto
  raw      a CC0 RAW decoded by LibRaw and area-resized as openRaw does
           (spk_android prep), then the same floats to both
  rawfull  the RAW file itself, full size: each side runs its own LibRaw decode
  jpeg     an 8-bit JPEG (sRGB, or Display P3 with its profile) written by the
           host's own write_image: the host converts it through its ICC
           profile, the app hands the codes to the engine as openBitmap does

    python3 host_parity.py render --host H --resources R --android A \
        --up <upstream tree> --raw <dir of CC0 RAWs> --out DIR [--only NAME ...]
    python3 host_parity.py grade DIR [--table out.md] [--where CASE/PAIR]

Grain and glare are off in every case: a pixel comparison of two renders that
differ only in their noise measures the noise (upstream AGENTS trap 1).
"""
import argparse
import json
import os
import struct
import subprocess
import sys
import threading
import time
from pathlib import Path

import numpy as np

OFF = {"grain_active": False, "glare_active": False}
LOOKS = {
    "portra": {"film_stock": "kodak_portra_400", "print_stock": "kodak_supra_endura", "scan_film": False},
    "velvia": {"film_stock": "fujifilm_velvia_100", "print_stock": "kodak_supra_endura", "scan_film": True},
    "cine2383": {"film_stock": "kodak_vision3_250d", "print_stock": "kodak_2383", "scan_film": False},
    "trix": {"film_stock": "kodak_tri_x_400", "print_stock": "ilford_multigrade_iv_rc", "scan_film": False,
             # Multigrade filter 2, as the app sends it for a silver paper (Model.kt putMonoPack)
             "c_filter_neutral": 0.0, "m_filter_neutral": 0.0, "y_filter_neutral": 68.0},
}
# The app's defaults for a photo (Model.kt Edit): auto exposure, balanced.
PHOTO = {"auto_exposure": True, "auto_exposure_method": "balanced"}
# The catalogue picture is exposed by construction, as upstream's own harness renders it.
SYNTH = {"auto_exposure": False, "film_format_mm": 36.0}
OPEN = {"output_color_space": "ProPhoto RGB", "output_cctf_encoding": True,
        "extended_dynamic_range": False, "product_defaults": True, "preview_long_edge": 1024}

RAWS = {
    "lake": "Leica_-_Q_(Typ_116)_-_14bit_(3:2).DNG",
    "fruit": "Canon_-_EOS_R5_-_RAW,_1.6_crop,_dual-pixel_(3:2).CR3",
    "dunes": "Ricoh_-_GR_III_-_14bit_(3:2).DNG",
    "skyline": "Google_-_Pixel_2_XL_-_16bit_(4:3).dng",
    "chart": "Nikon_-_Z_7_-_12bit_12bit_compressed_(3:2).NEF",
    "temple": "Fujifilm_-_X100F_-_14bit_14bit_compressed_(3:2).RAF",
}
RAWFULL = {"iphone": "Apple_-_iPhone_6s_Plus_-_16bit_(4:3).DNG",
           "sl1": "Canon_-_EOS_Rebel_SL1_-_RAW_(3:2).CR2"}

# Layer 2, non-neutral: Look.kt's 50 values and the desktop sidecar's own units.
LOOK_VALUES = [0.0] * 50
for i, v in {0: 20, 1: -10, 2: 0.3, 3: 15, 4: 10, 5: 20, 7: -30, 8: 25, 9: 3, 10: 2, 12: -30, 13: 50,
             14: 40, 15: 20, 16: 10, 17: 220, 18: 30, 19: -10, 20: 120, 21: 10, 22: 0,
             23: 50, 24: 25, 25: 5}.items():
    LOOK_VALUES[i] = float(v)


def zone(h, s, l):
    return {"hue": h, "saturation": s / 100, "luminance": l / 100}


ADJUST = {"enabled": True, "temperature": 20, "tint": -10, "exposure": 0.3, "contrast": 15, "brightness": 10,
          "saturation": 20, "highlights": -30, "shadows": 25, "blackPoint": 3, "whitePoint": 2,
          "vignette": {"amount": -30, "midpoint": 50},
          "colorBalance": {"master": zone(40, 20, 10), "shadows": zone(220, 30, -10),
                           "midtones": zone(120, 10, 0), "highlights": zone(50, 25, 5)}}

# --- colour ------------------------------------------------------------------

D50 = np.array([0.96422, 1.0, 0.82521])
D65 = np.array([0.95047, 1.0, 1.08883])
PROPHOTO_TO_XYZ_D50 = np.array([[0.7976749, 0.1351917, 0.0313534],
                                [0.2880402, 0.7118741, 0.0000857],
                                [0.0, 0.0, 0.8252100]])
SRGB_TO_XYZ = np.array([[0.4124564, 0.3575761, 0.1804375],
                        [0.2126729, 0.7151522, 0.0721750],
                        [0.0193339, 0.1191920, 0.9503041]])
P3_TO_XYZ = np.array([[0.4865709, 0.2656677, 0.1982173],
                      [0.2289746, 0.6917385, 0.0792869],
                      [0.0, 0.0451134, 1.0439444]])
BRADFORD = np.array([[0.8951, 0.2664, -0.1614], [-0.7502, 1.7135, 0.0367], [0.0389, -0.0685, 1.0296]])


def adapt(src_white, dst_white):
    s, d = BRADFORD @ src_white, BRADFORD @ dst_white
    return np.linalg.inv(BRADFORD) @ np.diag(d / s) @ BRADFORD


D65_TO_D50 = adapt(D65, D50)
SPACE_TO_XYZ_D65 = {"srgb": SRGB_TO_XYZ, "p3": P3_TO_XYZ}


def srgb_decode(v):
    v = np.clip(v, 0.0, 1.0)
    return np.where(v <= 0.04045, v / 12.92, ((v + 0.055) / 1.055) ** 2.4)


def srgb_encode(v):
    v = np.clip(v, 0.0, 1.0)
    return np.where(v <= 0.0031308, 12.92 * v, 1.055 * v ** (1 / 2.4) - 0.055)


def to_lab(enc, space):
    xyz = srgb_decode(enc) @ SPACE_TO_XYZ_D65[space].T / D65
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

# --- files -------------------------------------------------------------------


def write_rgb(path, img, bits):
    h, w, _ = img.shape
    with open(path, "wb") as f:
        f.write(struct.pack("<3I", w, h, bits))
        f.write(np.ascontiguousarray(img, np.uint8 if bits == 8 else "<u2").tobytes())


def read_rgb(path):
    with open(path, "rb") as f:
        w, h, bits = struct.unpack("<3I", f.read(12))
        a = np.frombuffer(f.read(), np.uint8 if bits == 8 else "<u2").reshape(h, w, 3)
    return a.astype(np.float64) / (255.0 if bits == 8 else 65535.0), bits


def write_f32(path, rgb):
    h, w, _ = rgb.shape
    with open(path, "wb") as f:
        f.write(struct.pack("<2I", w, h))
        f.write(np.ascontiguousarray(rgb, "<f4").tobytes())


def read_f32(path):
    with open(path, "rb") as f:
        w, h = struct.unpack("<2I", f.read(8))
        return np.frombuffer(f.read(), "<f4").reshape(h, w, 3)


def write_float_tiff(path, rgb):
    """Untagged 32-bit float RGB, one strip: the host reads it as linear ProPhoto."""
    h, w, _ = rgb.shape
    data = np.ascontiguousarray(rgb, "<f4").tobytes()
    tags = [(256, 4, 1, w), (257, 4, 1, h), (258, 3, 3, None), (259, 3, 1, 1), (262, 3, 1, 2),
            (273, 4, 1, None), (274, 3, 1, 1), (277, 3, 1, 3), (278, 4, 1, h), (279, 4, 1, len(data)),
            (284, 3, 1, 1), (339, 3, 3, None)]
    ifd_at = 8
    extra_at = ifd_at + 2 + 12 * len(tags) + 4
    bps_at, fmt_at = extra_at, extra_at + 6
    pixels_at = (fmt_at + 6 + 15) & ~15
    out = bytearray(b"II*\0" + struct.pack("<I", ifd_at))
    out += struct.pack("<H", len(tags))
    for tag, typ, count, value in tags:
        if tag == 258:
            value = bps_at
        elif tag == 339:
            value = fmt_at
        elif tag == 273:
            value = pixels_at
        if typ == 3 and count == 1:
            out += struct.pack("<HHIHH", tag, typ, count, value, 0)
        else:
            out += struct.pack("<HHII", tag, typ, count, value)
    out += struct.pack("<I", 0)
    out += struct.pack("<3H", 32, 32, 32) + struct.pack("<3H", 3, 3, 3)
    out += b"\0" * (pixels_at - len(out))
    out += data
    Path(path).write_bytes(out)


def read_tiff16(path):
    b = Path(path).read_bytes()
    assert b[:4] == b"II*\0", path
    (ifd,) = struct.unpack_from("<I", b, 4)
    (n,) = struct.unpack_from("<H", b, ifd)
    tags = {}
    for i in range(n):
        tag, typ, count, value = struct.unpack_from("<HHII", b, ifd + 2 + 12 * i)
        size = {3: 2, 4: 4}.get(typ, 1) * count
        if size <= 4:
            vals = struct.unpack_from("<%d%s" % (count, "H" if typ == 3 else "I"), b, ifd + 2 + 12 * i + 8) \
                if typ in (3, 4) else (value,)
        else:
            vals = struct.unpack_from("<%d%s" % (count, "H" if typ == 3 else "I"), b, value) \
                if typ in (3, 4) else (value,)
        tags[tag] = vals
    w, h = tags[256][0], tags[257][0]
    assert tags[258][0] == 16 and tags.get(259, (1,))[0] == 1, "expected uncompressed 16-bit"
    data = b"".join(b[o:o + c] for o, c in zip(tags[273], tags[279]))
    return np.frombuffer(data, "<u2").reshape(h, w, 3)

# --- the host ----------------------------------------------------------------


class Host:
    """upstream engine/tests/host_smoke.py's client: u32 header, u32 payload, JSON, bytes."""

    def __init__(self, cmd, env):
        self.p = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env)
        self.next_id = 1
        self.stderr = []
        threading.Thread(target=self._drain, daemon=True).start()

    def _drain(self):
        for line in self.p.stderr:
            self.stderr.append(line.decode("utf-8", "replace").rstrip())

    def call(self, method, params=None, payload=b""):
        rid = self.next_id
        self.next_id += 1
        head = json.dumps({"id": rid, "method": method, "params": params or {}}).encode()
        self.p.stdin.write(struct.pack("<II", len(head), len(payload)) + head + payload)
        self.p.stdin.flush()
        while True:
            pre = self.p.stdout.read(8)
            if len(pre) < 8:
                raise EOFError("host closed stdout:\n" + "\n".join(self.stderr[-20:]))
            hl, pl = struct.unpack("<II", pre)
            header = json.loads(self.p.stdout.read(hl))
            body = self.p.stdout.read(pl) if pl else b""
            if "event" in header:
                continue
            if header.get("id") == rid:
                if not header.get("ok"):
                    raise RuntimeError(f"{method}: {header.get('error')}")
                return header["result"], body

    def close(self):
        try:
            self.call("shutdown")
        except Exception:  # noqa: BLE001
            pass
        self.p.wait(30)

# --- inputs ------------------------------------------------------------------


def linear_srgb_to_prophoto(rgb):
    m = np.linalg.inv(PROPHOTO_TO_XYZ_D50) @ D65_TO_D50 @ SRGB_TO_XYZ
    return (rgb.astype(np.float64) @ m.T).astype(np.float32)


def encode_for(prophoto, space):
    """Linear ProPhoto -> encoded sRGB / Display P3, 16-bit codes, clipped."""
    m = np.linalg.inv(SPACE_TO_XYZ_D65[space]) @ np.linalg.inv(D65_TO_D50) @ PROPHOTO_TO_XYZ_D50
    lin = prophoto.astype(np.float64) @ m.T
    return np.round(srgb_encode(lin) * 65535).astype("<u2")


def prepare(a, host, inp):
    """Every input file both sides read. Returns {name: (kind, path)}."""
    inp.mkdir(parents=True, exist_ok=True)
    out = {}
    sys.path.insert(0, str(a.up / "engine" / "tests"))
    import parity_metal_vulkan as up  # noqa: E402  (only make_input; needs numpy)
    synth = linear_srgb_to_prophoto(up.make_input())
    write_f32(inp / "synth.f32", synth)
    write_float_tiff(inp / "synth.tif", synth)
    out["synth"] = ("f32", inp / "synth")
    for name, file in RAWS.items():
        f32 = inp / f"{name}.f32"
        if not f32.exists():
            subprocess.run([str(a.android), "prep", str(a.raw / file), str(a.edge), str(f32)], check=True,
                           env=a.env, stdout=subprocess.DEVNULL)
        write_float_tiff(inp / f"{name}.tif", read_f32(f32))
        out[name] = ("f32", inp / name)
    for name, file in RAWFULL.items():
        out[name] = ("raw", a.raw / file)
    # Phone-style 8-bit JPEGs from three scenes, written by the host itself.
    for scene in ("synth", "fruit", "lake"):
        rgb = read_f32(inp / f"{scene}.f32")
        for space, wire in (("srgb", "srgb"), ("p3", "display-p3")):
            if space == "p3" and scene == "lake":
                continue
            codes = encode_for(rgb, space)
            h, w, _ = codes.shape
            rgba = np.concatenate([codes, np.full((h, w, 1), 65535, "<u2")], -1)
            path = inp / f"{scene}_{space}.jpg"
            host.call("write_image", {"path": str(path), "format": "jpeg", "quality": 95, "color_space": wire,
                                      "width": w, "height": h, "overwrite": True}, rgba.tobytes())
            out[f"jpg_{scene}_{space}"] = (space, path)
    return out


def cases(inputs):
    c = []
    for look in LOOKS:
        c.append(("synth_" + look, "synth", {**SYNTH, **LOOKS[look]}, False))
    for scene in RAWS:
        for look in LOOKS:
            c.append((f"{scene}_{look}", scene, {**PHOTO, **LOOKS[look]}, False))
    for name in RAWFULL:
        c.append((f"rawfull_{name}_portra", name, {**PHOTO, **LOOKS["portra"]}, False))
    for name in inputs:
        if name.startswith("jpg_"):
            for look in ("portra", "velvia"):
                c.append((f"{name}_{look}", name, {**PHOTO, **LOOKS[look]}, False))
    c.append(("layer2_synth_portra", "synth", {**SYNTH, **LOOKS["portra"]}, True))
    c.append(("layer2_dunes_portra", "dunes", {**PHOTO, **LOOKS["portra"]}, True))
    c.append(("layer2_fruit_velvia", "fruit", {**PHOTO, **LOOKS["velvia"]}, True))
    return c


def host_input(kind, path):
    if kind == "f32":
        return str(path) + ".tif"
    return str(path)


def android_input(kind, path):
    if kind == "f32":
        return str(path) + ".f32"
    return str(path)


def render_host(host, d, kind, path, params, layer2):
    o, _ = host.call("open", {"path": host_input(kind, path), "params": {**OPEN, **OFF, **params}})
    sid = o["session"]
    adj = ADJUST if layer2 else {}
    try:
        for space, wire in (("srgb", "srgb"), ("p3", "display-p3")):
            r, px = host.call("render_view", {"session": sid, "tier": "full", "display": wire, "format": "rgba8",
                                              "long_edge": 0, "generation": 1, "adjustments": adj})
            im = r["image"]
            v = np.frombuffer(px, np.uint8).reshape(im["height"], im["width"], 4)[..., :3]
            write_rgb(d / f"host_view_{space}.rgb", v, 8)
            tif = d / f"host_export_{space}.tif"
            host.call("export_frame", {"session": sid, "path": str(tif), "format": "tiff16", "color_space": wire,
                                       "long_edge": 0, "overwrite": True, "adjustments": adj})
            write_rgb(d / f"host_export_{space}.rgb", read_tiff16(tif), 16)
            tif.unlink()
    finally:
        host.call("close", {"session": sid})


def cmd_render(a):
    a.out.mkdir(parents=True, exist_ok=True)
    a.env = {**os.environ, "LP_NUM_THREADS": str(a.threads)}
    host = Host([str(a.host), "--resources", str(a.resources), "--log-level", "error"], a.env)
    try:
        inputs = prepare(a, host, a.out / "inputs")
        log = {}
        for name, scene, params, layer2 in cases(inputs):
            if a.only and not any(s in name for s in a.only):
                continue
            kind, path = inputs[scene]
            d = a.out / name
            d.mkdir(exist_ok=True)
            t = time.time()
            try:
                render_host(host, d, kind, path, params, layer2)
                th = time.time() - t
                look = ",".join(f"{v:g}" for v in LOOK_VALUES) if layer2 else ""
                cmd = [str(a.android), str(a.resources), kind, android_input(kind, path), str(d),
                       json.dumps({**OFF, **params})] + ([look] if look else [])
                p = subprocess.run(cmd, env=a.env, capture_output=True, text=True)
                if p.returncode:
                    raise RuntimeError("spk_android: " + p.stderr[-400:])
                log[name] = {"host_s": round(th, 1), "android_s": round(time.time() - t - th, 1)}
                print(f"{name:<34} host {th:6.1f} s  android {log[name]['android_s']:6.1f} s", flush=True)
            except Exception as ex:  # noqa: BLE001
                log[name] = {"error": str(ex)}
                print(f"{name:<34} ERROR {ex}", flush=True)
        (a.out / "render_log.json").write_text(json.dumps(log, indent=1))
    finally:
        host.close()
    print("RENDER_DONE")
    return 0

# --- grading -----------------------------------------------------------------

# (ours, reference, space, label). The export and view rows are the claim;
# the last two say how far upstream's own screen is from its own file.
PAIRS = [("export_srgb", "host_export_srgb", "srgb", "export sRGB"),
         ("export_p3", "host_export_p3", "p3", "export P3"),
         ("view_srgb", "host_view_srgb", "srgb", "view sRGB"),
         ("view_p3", "host_view_p3", "p3", "view P3"),
         ("host_view_srgb", "host_export_srgb", "srgb", "host view vs host export"),
         ("view_srgb", "export_srgb", "srgb", "app view vs app export")]


def grade_pair(d, a, b, space):
    x, bx = read_rgb(d / f"{a}.rgb")
    y, by = read_rgb(d / f"{b}.rgb")
    if x.shape != y.shape:
        return {"error": f"shape {x.shape} vs {y.shape}"}
    e = ciede2000(to_lab(x, space), to_lab(y, space))
    codes = np.abs(x - y) * 255
    return {"mean": float(e.mean()), "p95": float(np.percentile(e, 95)), "p999": float(np.percentile(e, 99.9)),
            "max": float(e.max()), "code_max": float(codes.max()), "code_mean": float(codes.mean()),
            "argmax": [int(v) for v in np.unravel_index(int(e.argmax()), e.shape)], "e": e}


def cmd_grade(a):
    rows = []
    for d in sorted(p for p in a.dir.iterdir() if p.is_dir() and (p / "host_export_srgb.rgb").exists()):
        if not (d / "export_srgb.rgb").exists():
            continue
        for ours, ref, space, label in PAIRS:
            g = grade_pair(d, ours, ref, space)
            if "error" in g:
                print(f"{d.name:<34} {label:<26} {g['error']}")
                continue
            if a.where and a.where == f"{d.name}/{ours}":
                where(d, ours, ref, space, g)
            g.pop("e")
            rows.append({"case": d.name, "pair": label, **g})
            print(f"{d.name:<34} {label:<26} mean {g['mean']:.3f}  p95 {g['p95']:.3f}  p99.9 {g['p999']:.3f}  "
                  f"max {g['max']:.3f}  |d| max {g['code_max']:.2f} mean {g['code_mean']:.4f} code", flush=True)
    (a.dir / "grade.json").write_text(json.dumps(rows, indent=1))
    return 0


def where(d, ours, ref, space, g):
    """The worst pixels of one pair, with the engine's ProPhoto print under them."""
    e = g["e"]
    x, _ = read_rgb(d / f"{ours}.rgb")
    y, _ = read_rgb(d / f"{ref}.rgb")
    pr = None
    for name in ("print",):
        if (d / f"{name}.rgb").exists():
            pr, _ = read_rgb(d / f"{name}.rgb")
    idx = np.argsort(e.ravel())[::-1][:12]
    print(f"worst pixels of {d.name}/{ours} vs {ref}  (pixels > 5: {int((e > 5).sum())}, > 2: {int((e > 2).sum())})")
    for i in idx:
        r, c = divmod(int(i), e.shape[1])
        p = "" if pr is None else " print " + " ".join(f"{v:.4f}" for v in pr[r, c])
        print(f"  ({c:4d},{r:4d}) dE {e[r, c]:6.2f}  ours {np.round(x[r, c] * 255, 1)}  ref {np.round(y[r, c] * 255, 1)}{p}")


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    r = sub.add_parser("render")
    r.add_argument("--host", type=Path, required=True)
    r.add_argument("--resources", type=Path, required=True)
    r.add_argument("--android", type=Path, required=True)
    r.add_argument("--up", type=Path, required=True)
    r.add_argument("--raw", type=Path, required=True)
    r.add_argument("--out", type=Path, required=True)
    r.add_argument("--edge", type=int, default=1536)
    r.add_argument("--threads", type=int, default=3)
    r.add_argument("--only", nargs="*")
    g = sub.add_parser("grade")
    g.add_argument("dir", type=Path)
    g.add_argument("--where")
    a = ap.parse_args()
    return cmd_render(a) if a.cmd == "render" else cmd_grade(a)


if __name__ == "__main__":
    sys.exit(main())
