#!/usr/bin/env python3
"""Compare production real-photo input RGB with independent LittleCMS floats.

The supplied ProPhoto ICC is exported by Kotlin IccProfile, not the JNI matrix.
Cloud only. Co-created with GPT (OpenAI).
"""
import argparse
import ctypes as C
import ctypes.util
import json
from pathlib import Path

import numpy as np
from PIL import Image, ImageCms


def main(args):
    lib = C.CDLL(ctypes.util.find_library("lcms2"))
    lib.cmsOpenProfileFromMem.argtypes = [C.c_void_p, C.c_uint32]
    lib.cmsOpenProfileFromMem.restype = C.c_void_p
    lib.cmsCreateTransform.argtypes = [C.c_void_p, C.c_uint32, C.c_void_p, C.c_uint32, C.c_uint32, C.c_uint32]
    lib.cmsCreateTransform.restype = C.c_void_p
    lib.cmsDoTransform.argtypes = [C.c_void_p, C.c_void_p, C.c_void_p, C.c_uint32]
    lib.cmsDeleteTransform.argtypes = [C.c_void_p]
    lib.cmsCloseProfile.argtypes = [C.c_void_p]
    profiles = [ImageCms.ImageCmsProfile(ImageCms.createProfile("sRGB")).tobytes(), args.profile.read_bytes()]
    buffers = [C.create_string_buffer(data) for data in profiles]
    handles = [lib.cmsOpenProfileFromMem(buffer, len(data)) for buffer, data in zip(buffers, profiles)]
    if not all(handles):
        raise ValueError("independent CMM profiles")
    if args.source_colorants == "published-d50":
        # ICC's published legacy sRGB D50 colorants, also the app's host contract.
        # LittleCMS's built-in profile uses a slightly different D50 adaptation.
        class XYZ(C.Structure):
            _fields_ = [("x", C.c_double), ("y", C.c_double), ("z", C.c_double)]
        lib.cmsWriteTag.argtypes = [C.c_void_p, C.c_uint32, C.c_void_p]
        lib.cmsWriteTag.restype = C.c_int
        for tag, values in ((b"rXYZ", (.4360747, .2225045, .0139322)),
                            (b"gXYZ", (.3850649, .7168786, .0971045)),
                            (b"bXYZ", (.1430804, .0606169, .7141733))):
            column = XYZ(*values)
            if not lib.cmsWriteTag(handles[0], int.from_bytes(tag, "big"), C.byref(column)):
                raise ValueError("reference source colorant tag")
    rgb_float = (1 << 22) | (4 << 16) | (3 << 3) | 4
    transform = lib.cmsCreateTransform(handles[0], rgb_float, handles[1], rgb_float, 1, 0x0100 | 0x0040)
    if not transform:
        raise ValueError("independent float CMM transform")
    rows = []
    try:
        for path in sorted((args.photos / "inputs").glob("*.ppm")):
            with Image.open(path) as image:
                source = np.ascontiguousarray(np.asarray(image, dtype=np.float32).reshape(-1, 3) / 255)
            encoded = np.empty_like(source)
            lib.cmsDoTransform(transform, source.ctypes.data, encoded.ctypes.data, len(source))
            values = encoded.astype(np.float64)
            reference = np.where(values < 1 / 32, values / 16, np.maximum(values, 0) ** 1.8)
            actual = np.fromfile(args.actual / (path.stem + ".f32"), dtype="<f4").reshape(-1, 3)
            if actual.shape != reference.shape or not np.isfinite(actual).all():
                raise ValueError("input dimensions or nonfinite values: " + path.stem)
            difference = np.abs(actual - reference)
            row = {"id": path.stem, "pixels": len(source), "max_linear_error": float(difference.max()),
                   "mean_linear_error": float(difference.mean()), "p99_linear_error": float(np.quantile(difference, .99))}
            rows.append(row)
            print(json.dumps(row), flush=True)
        report = {"reference": "LittleCMS relative-colorimetric float, no optimization/cache; Kotlin ProPhoto ICC",
                  "source_colorants": args.source_colorants,
                  "scope": "SDR normalized sRGB photograph import only; no film matching or HDR claim",
                  "photos": rows, "pixels": sum(r["pixels"] for r in rows),
                  "max_linear_error": max(r["max_linear_error"] for r in rows),
                  "budget": .0001}
        args.out.write_text(json.dumps(report, indent=2) + "\n")
        if len(rows) != 12 or report["max_linear_error"] > report["budget"]:
            raise ValueError("real-photo independent input color budget")
        print("PASS", report["pixels"], "real-photo pixels against independent CMM", flush=True)
    finally:
        lib.cmsDeleteTransform(transform)
        for handle in handles:
            lib.cmsCloseProfile(handle)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--photos", type=Path, required=True)
    parser.add_argument("--actual", type=Path, required=True)
    parser.add_argument("--profile", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--source-colorants", choices=("lcms-default", "published-d50"), default="lcms-default")
    main(parser.parse_args())
