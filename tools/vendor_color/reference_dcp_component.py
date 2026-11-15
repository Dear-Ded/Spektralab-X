#!/usr/bin/env python3
"""Compile a pinned external DNG-reference interpolation block on a cloud host.

The extracted block is (c) 2015-2016 Anders Torger, from GPL-3.0 dcamprof.
No full DCP RGB renderer or manufacturer JPEG comparison is performed here.
"""

import argparse
import ctypes as C
import hashlib
import json
from pathlib import Path
import random
import subprocess
import tempfile

from inspect_profiles import parse_dcp, sample_hsv_table

REFERENCE_SHA256 = "75554d70128e740428b260ea90df56fc223828c4563d609444e9775fb6e9d07a"


def run(reference, profiles, output):
    raw = reference.read_bytes()
    if hashlib.sha256(raw).hexdigest() != REFERENCE_SHA256:
        raise ValueError("Unexpected external reference; refusing to compile")
    source = raw.decode()
    start = source.index("        uint32_t hueDivisions = hsmdims[0];")
    end = source.index("        bool did_clip = false;", start)
    block = source[start:end]
    prefix = r"""
/* (c) 2015-2016 Anders Torger; dcamprof GPL-3.0 reference block follows.
 * The harness exposes interpolated parameters before RGB application/clipping.
 */
#include <stdint.h>
#include <stdbool.h>
#include <math.h>
typedef struct { double v[3]; } v3;
static double srgb_gamma_forward(double x) {
    return x <= 0.0031308 ? 12.92*x : 1.055*pow(x, 1.0/2.4)-0.055;
}
void sample(const double *flat, const uint32_t hsmdims[3], int encoding,
            double hue, double sat, double value, double out[3]) {
    const v3 *hsm = (const v3 *)flat;
    const bool srgb_gamma = encoding != 0;
    const v3 hsv = {{hue / 60.0, sat, value}};
"""
    suffix = "\n out[0]=hueShift; out[1]=satScale; out[2]=valScale;\n}\n"
    generated = prefix + block + suffix
    datasets = []
    rng = random.Random(15720261115)
    for dims in [[1, 2, 1], [7, 5, 1], [7, 5, 4]]:
        values = [v for _ in range(dims[0] * dims[1] * dims[2])
                  for v in [rng.uniform(-45, 45), rng.uniform(0.5, 1.5), rng.uniform(0.5, 1.5)]]
        datasets.append(("synthetic-" + "x".join(map(str, dims)), dims, values))
    for path in profiles:
        entries = {e["name"]: e["values"] for e in parse_dcp(path.read_bytes())["entries"]}
        for key, dim_key in [("ProfileHueSatMapData1", "ProfileHueSatMapDims"),
                             ("ProfileHueSatMapData2", "ProfileHueSatMapDims"),
                             ("ProfileLookTableData", "ProfileLookTableDims")]:
            if key in entries:
                datasets.append((path.name + ":" + key, entries[dim_key], entries[key]))
    rows = []
    with tempfile.TemporaryDirectory(prefix="dcp-reference-") as temporary:
        directory = Path(temporary)
        (directory / "reference.c").write_text(generated)
        subprocess.run(["cc", "-shared", "-fPIC", "-O2", "-std=c99", str(directory / "reference.c"),
                        "-lm", "-o", str(directory / "reference.so")], check=True)
        library = C.CDLL(str(directory / "reference.so"))
        library.sample.argtypes = [C.POINTER(C.c_double), C.POINTER(C.c_uint32), C.c_int,
                                   C.c_double, C.c_double, C.c_double, C.POINTER(C.c_double)]
        library.sample.restype = None
        for name, dims, values in datasets:
            table = (C.c_double * len(values))(*values)
            dimensions = (C.c_uint32 * 3)(*dims)
            actual = (C.c_double * 3)()
            maximum = 0.0
            for index in range(4096):
                hue = rng.random() * 360
                sat, val = rng.random(), rng.random()
                if index < 8:
                    hue, sat, val = [0, 359.999999999][index % 2], (index // 2) % 2, index // 4
                encoding = index % 2
                library.sample(table, dimensions, encoding, hue, sat, val, actual)
                expected = sample_hsv_table(dims, values, hue, sat, val, encoding)
                maximum = max(maximum, *(abs(a - b) for a, b in zip(actual, expected)))
            if maximum > 2e-10:
                raise AssertionError(f"External C reference disagreement: {name}: {maximum}")
            rows.append({"dataset": name, "dimensions": dims, "samples": 4096,
                         "maximumAbsoluteComponentError": maximum})
    result = {"referenceSha256": REFERENCE_SHA256,
              "extractedBlockSha256": hashlib.sha256(block.encode()).hexdigest(),
              "generatedHarnessSha256": hashlib.sha256(generated.encode()).hexdigest(),
              "testedScriptSha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
              "compiler": subprocess.check_output(["cc", "--version"], text=True).splitlines()[0],
              "rows": rows, "totalSamples": sum(r["samples"] for r in rows),
              "scope": "table parameters only; both coordinate encodings tested, not full DCP RGB application or camera looks"}
    output.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--reference", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("profiles", nargs="*", type=Path)
    args = parser.parse_args()
    run(args.reference, args.profiles, args.output)
