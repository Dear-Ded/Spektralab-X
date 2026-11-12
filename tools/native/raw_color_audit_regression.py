#!/usr/bin/env python3
"""Co-created with GPT (OpenAI). Independent pixels/black/geometry audit checks."""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import subprocess

import dng_float_linear_fixtures as original
import dng_float_precision_fixtures as storage
from float_dng_linear_fixtures import replace_tag


def check(probe, folder):
    folder.mkdir(parents=True, exist_ok=True)
    records = []

    def run(path, name, budget=0):
        prefix = folder / name
        subprocess.run([str(probe), str(path), str(prefix), str(budget)], check=True,
                       stdout=subprocess.DEVNULL)
        row = json.loads(prefix.with_suffix('.json').read_text())
        blob = prefix.with_suffix('.f32').read_bytes()
        values = struct.unpack('<' + 'f' * (len(blob) // 4), blob)
        assert len(values) == row['width'] * row['height'] * 3, row
        row['case'] = name
        row['pixel_sha256'] = hashlib.sha256(blob).hexdigest()
        records.append(row)
        return row, values

    for orientation in range(1, 9):
        case, stored = original.fixture(folder, 'float-source-' + str(orientation),
            black=(.25, .375, .125), white=(4, 3, 2), orientation=orientation)
        row, pixels = run(folder / (case['name'] + '.dng'), 'float-output-' + str(orientation))
        reference = original.oriented_reference(case, stored)
        error = max(abs(a - b) for a, b in zip(pixels, reference))
        assert len(pixels) == len(reference) and error < 2e-6, (row, error)
        assert row['mode'] == 'float_linear_v1' and row['float_black'] == list(case['black']), row
        assert row['float_white'] == list(case['white']) and min(pixels) < 0, row
        row['maximum_independent_pixel_error'] = error

    for linear in (False, True):
        case = storage.fixture(folder, linear, 2, black=64, white=4095)
        data = bytearray((folder / (case['name'] + '.dng')).read_bytes())
        channels = 3 if linear else 1
        payload = struct.pack('<' + 'H' * (storage.WIDTH * storage.HEIGHT * channels),
                              *([1024] * (storage.WIDTH * storage.HEIGHT * channels)))
        replace_tag(data, 258, 3, [16] * channels)
        replace_tag(data, 339, 3, [1] * channels)
        replace_tag(data, 279, 4, [len(payload)])
        replace_tag(data, 273, 4, [len(data)])
        data.extend(payload)
        source = folder / ('integer-linear.dng' if linear else 'integer-bayer.dng')
        source.write_bytes(data)
        for budget in (0, 1):
            name = source.stem + ('-full' if budget == 0 else '-half')
            row, pixels = run(source, name, budget)
            assert row['mode'] == 'integer_compatible16' and not row['headroom_enabled'], row
            divisor = 2 if budget and not linear else 1
            assert [row['width'], row['height']] == [storage.WIDTH // divisor, storage.HEIGHT // divisor], row
            # Frozen compatible16 behavior: three-channel LinearRaw retains
            # per-channel black without lowering LibRaw's common maximum.
            # Record its gap to (sample-black)/(white-black), do not present
            # compatible-policy agreement as complete DNG normalization.
            denominator = 4095 if linear else 4095 - 64
            error = max(abs(v - (1024 - 64) / denominator) for v in pixels)
            assert error < 3 / 65535, (row, error)
            pattern = row['black_pattern']
            assert not pattern or all(v == pattern[0] for v in pattern), row
            assert all(v + (pattern[0] if pattern else 0) == 64 for v in row['black_per_channel'][:3]), row
            assert row['white'] == 4095 and row['postprocess_maximum'] == denominator, row
            row['maximum_independent_pixel_error'] = error
            row['maximum_dng_normalization_reference_gap'] = max(
                abs(v - (1024 - 64) / (4095 - 64)) for v in pixels)

    summary = dict(passed=True, float_orientations=8, integer_decodes=4,
        rational_float_metadata_preserved=True, integer_black_and_white_preserved=True,
        bayer_half_size_and_linear_full_size_verified=True,
        maximum_float_error=max(r['maximum_independent_pixel_error'] for r in records[:8]),
        maximum_integer_compatible_error=max(r['maximum_independent_pixel_error'] for r in records[8:]),
        integer_linear_nonzero_black_reference_gap=max(
            r['maximum_dng_normalization_reference_gap'] for r in records[8:] if 'linear' in r['case']),
        complete_dng_normalization_claimed=False)
    (folder / 'results.json').write_text(json.dumps(dict(summary=summary, records=records), indent=2) + '\n')
    print(json.dumps(summary))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('folder', type=Path)
    parser.add_argument('--probe', required=True, type=Path)
    args = parser.parse_args()
    check(args.probe.resolve(), args.folder.resolve())
