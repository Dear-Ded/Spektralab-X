#!/usr/bin/env python3
# Spektralab-X - https://github.com/Dear-Ded/Spektralab-X
# Copyright (C) 2026 Spektralab-X contributors
# Additional terms under GPL-3.0 section 7 apply; see NOTICE.
# Co-created with GPT (OpenAI), 2026-11-11.
"""Compare actual output CCTFs and the production ROMM ICC with Little CMS.

LCMS is a separately installed test oracle, not an Android dependency. The
profile must be emitted by the production Kotlin IccProfile implementation.
No input photographs or absolute paths are included in the JSON summary.
"""
import argparse
import csv
import ctypes as C
import ctypes.util
import hashlib
import json
import math
import os
import pathlib
import struct


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--icc', required=True)
    parser.add_argument('--samples', required=True)
    parser.add_argument('--lcms')
    args = parser.parse_args()
    library = args.lcms or ctypes.util.find_library('lcms2')
    if not library:
        parser.error('Little CMS 2 shared library required; set --lcms')
    cookie = None
    if os.name == 'nt' and pathlib.Path(library).is_absolute():
        cookie = os.add_dll_directory(str(pathlib.Path(library).parent))
    lib = C.CDLL(library)

    def api(name, result, *params):
        fn = getattr(lib, name)
        fn.restype, fn.argtypes = result, list(params)
        return fn

    ptr, uint, dbl = C.c_void_p, C.c_uint32, C.c_double
    version = api('cmsGetEncodedCMMversion', C.c_int)()
    build_curve = api('cmsBuildParametricToneCurve', ptr, ptr, C.c_int, C.POINTER(dbl))
    eval_curve = api('cmsEvalToneCurveFloat', C.c_float, ptr, C.c_float)
    free_curve = api('cmsFreeToneCurve', None, ptr)
    open_profile = api('cmsOpenProfileFromMem', ptr, ptr, uint)
    close_profile = api('cmsCloseProfile', C.c_int, ptr)
    read_tag = api('cmsReadTag', ptr, ptr, uint)
    create_xyz = api('cmsCreateXYZProfile', ptr)
    transform = api('cmsCreateTransform', ptr, ptr, uint, ptr, uint, uint, uint)
    run = api('cmsDoTransform', None, ptr, ptr, ptr, uint)
    delete_transform = api('cmsDeleteTransform', None, ptr)
    profiles, curves, transforms = [], [], []
    try:
        data = pathlib.Path(args.icc).read_bytes()
        profile_data = C.create_string_buffer(data)
        profile = open_profile(profile_data, len(data))
        assert profile, 'LCMS rejected the production ICC'
        profiles.append(profile)
        named_curves = []
        # LCMS type numbers are ICC parametricCurveType functionType + 1.
        for kind, values in [(4, [2.4, 1 / 1.055, .055 / 1.055, 1 / 12.92, .04045]),
                             (4, [1.8, 1, 0, 1 / 16, 1 / 32]),
                             (1, [563 / 256])]:
            curve = build_curve(None, kind, (dbl * len(values))(*values))
            assert curve
            curves.append(curve)
            named_curves.append(curve)

        errors, encode_errors, domain_rows, extension = [0.] * 3, [0.] * 3, [0] * 3, []
        for row in csv.reader(pathlib.Path(args.samples).open(newline='')):
            mode = int(row[0])
            v, decoded, encoded = map(float, row[1:])
            if 0 <= v <= 1:
                expected = float(eval_curve(named_curves[mode], v))
                errors[mode] = max(errors[mode], abs(decoded - expected))
                domain_rows[mode] += 1
                assert abs(decoded - expected) < 5e-7, (mode, v, decoded, expected)
                # Encode is checked against the inverse of the specified curve,
                # not a LUT or a forward/decode implementation copied here.
                reference = ((12.92 * v if v <= .0031308 else 1.055 * v ** (1 / 2.4) - .055)
                             if mode == 0 else
                             (16 * v if v < 1 / 512 else v ** (1 / 1.8))
                             if mode == 1 else v ** (256 / 563))
                encode_errors[mode] = max(encode_errors[mode], abs(encoded - reference))
                assert abs(encoded - reference) < 5e-7, (mode, v, encoded, reference)
            elif v in (-2., -1., -.1, 1.5, 2.):
                extension.append(dict(mode=mode, value=v, decode_finite=math.isfinite(decoded),
                                      encode_finite=math.isfinite(encoded)))
        assert all(count >= 4097 for count in domain_rows), 'incomplete CCTF sample grid'

        def signature(tag):
            return int.from_bytes(tag.encode('ascii'), 'big')

        tag_count = struct.unpack_from('>I', data, 128)[0]
        tags = {}
        for i in range(tag_count):
            name, offset, length = struct.unpack_from('>4sII', data, 132 + i * 12)
            assert offset <= len(data) - length
            tags[name.decode('ascii')] = data[offset:offset + length]
        matrix = []
        for name in ('rXYZ', 'gXYZ', 'bXYZ'):
            assert tags[name][:4] == b'XYZ '
            matrix.append([v / 65536 for v in struct.unpack_from('>iii', tags[name], 8)])
        original = [[.7976749, .2880402, 0], [.1351917, .7118741, 0], [.0313534, .0000857, .82521]]
        profile_curves = [read_tag(profile, signature(name)) for name in ('rTRC', 'gTRC', 'bTRC')]
        assert all(profile_curves)
        xyz_profile = create_xyz()
        assert xyz_profile
        profiles.append(xyz_profile)
        rgb_double, xyz_double = (1 << 22) | (4 << 16) | (3 << 3), (1 << 22) | (9 << 16) | (3 << 3)
        conversion = transform(profile, rgb_double, xyz_profile, xyz_double, 1, 0x0100)
        assert conversion
        transforms.append(conversion)
        max_xyz, max_trc, count = 0., 0., 0
        values = [0, 1 / 1024, 1 / 32 - 1 / 65535, 1 / 32, 1 / 32 + 1 / 65535, .18, .5, 1]
        for v in values:
            for rgb in ([v, v, v], [v, 0, 0], [0, v, 0], [0, 0, v]):
                src, dst = (dbl * 3)(*rgb), (dbl * 3)()
                run(conversion, src, dst, 1)
                linear = [x / 16 if x < 1 / 32 else x ** 1.8 for x in rgb]
                expected = [sum(original[c][r] * linear[c] for c in range(3)) for r in range(3)]
                max_xyz = max(max_xyz, *(abs(dst[r] - expected[r]) for r in range(3)))
                assert max_xyz < 4e-5, (rgb, list(dst), expected)
                count += 1
        for channel in range(3):
            for i in range(4097):
                v = i / 4096
                decoded = float(eval_curve(profile_curves[channel], v))
                expected = v / 16 if v < 1 / 32 else v ** 1.8
                max_trc = max(max_trc, abs(decoded - expected))
                assert max_trc < 3e-6
        print(json.dumps(dict(status='PASS', lcms_encoded_version=version,
                              profile_sha256=hashlib.sha256(data).hexdigest(),
                              samples_sha256=hashlib.sha256(pathlib.Path(args.samples).read_bytes()).hexdigest(),
                              cctf_domain_rows=domain_rows, cctf_decode_max_abs=errors,
                              cctf_encode_max_abs=encode_errors,
                              romm_cmm_xyz_cases=count, romm_xyz_max_abs=max_xyz,
                              romm_channel_curve_samples=3 * 4097, romm_curve_max_abs=max_trc,
                              colorants_d50_columns=matrix, extension_observations=extension,
                              scope='SDR ICC domain 0..1; extension observations are not ICC conformance claims'),
                         indent=2, allow_nan=False))
    finally:
        for item in transforms:
            delete_transform(item)
        for item in profiles:
            close_profile(item)
        for item in curves:
            free_curve(item)
        if cookie:
            cookie.close()


if __name__ == '__main__':
    main()
