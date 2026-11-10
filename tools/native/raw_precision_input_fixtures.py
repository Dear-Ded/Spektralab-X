#!/usr/bin/env python3
"""Co-created with GPT (OpenAI). Real dispatcher, unchanged legacy IO, synthetic RAW.

Float pixels are also checked against the original independent rational/matrix
oracle. Byte equality to a second decode only checks dispatcher routing/parity.
"""
import argparse
import json
from pathlib import Path
import struct
import subprocess

import dng_float_linear_fixtures as original
import dng_float_precision_fixtures as storage
from float_dng_linear_fixtures import entry, replace_tag


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('folder', type=Path)
    parser.add_argument('--probe', required=True, type=Path)
    parser.add_argument('--integer', action='append', default=[], type=Path)
    args = parser.parse_args()
    args.folder.mkdir(parents=True, exist_ok=True)
    records = []

    def run(path, *options):
        output = args.folder / 'output.f32'
        row = json.loads(subprocess.check_output(
            [str(args.probe), str(path), str(output), *map(str, options)], text=True))
        assert row.get('open_status') == 0 and row['raw_unchanged'], (path, options, row)
        assert row['ok'] or (row['unchanged'] and row['float_metadata_unchanged']), row
        if not options or options == ('--custom',):
            assert row['exact_pixels'] and row['exact_metadata'], (path, row)
        records.append(dict(case=path.name, options=options, **row))
        return row, output.read_bytes() if row['ok'] else None

    float_cases = [original.fixture(args.folder, 'max' + str(n), maximum=n) for n in (2, 4)]
    float_cases += [original.fixture(args.folder, 'fractional', black=(.25, .25, .25), white=(4, 4, 4)),
                   original.fixture(args.folder, 'per-plane-black', black=(.25, .375, .125), white=(4, 4, 4)),
                   original.fixture(args.folder, 'per-plane-white', black=(.25, .375, .125), white=(4, 3, 2)),
                   original.fixture(args.folder, 'per-plane-white-orientation6', black=(.25, .375, .125),
                                    white=(4, 3, 2), orientation=6),
                   original.fixture(args.folder, 'per-plane-white-permuted', black=(.125, .25, .375), white=(2, 4, 3)),
                   original.fixture(args.folder, 'nonunit-green-neutral', neutral=(.25, .625, 1)),
                   original.fixture(args.folder, 'scaled-neutral-white', black=(.25, .375, .125),
                                    white=(4, 3, 2), neutral=(.25, .5, .375)),
                   original.fixture(args.folder, 'rational-neutral-white', black=(.25, .375, .125),
                                    white=(4, 3, 2), neutral=(1 / 3, .875, 1))]
    float_cases += [original.fixture(args.folder, 'orientation-' + str(n), orientation=n) for n in range(2, 9)]
    errors, output_bytes = [], {}
    flips = [0, 1, 3, 2, 4, 6, 7, 5]
    for case, stored in float_cases:
        row, blob = run(args.folder / (case['name'] + '.dng'))
        assert row['ok'] and row['mode'] == 'float_linear_v1' and not row['note'], row
        assert row['raw_flip'] == flips[case['orientation'] - 1], row
        expected = original.oriented_reference(case, stored)
        values = struct.unpack('<' + 'f' * (len(blob) // 4), blob)
        error = max(abs(a - b) for a, b in zip(values, expected))
        assert len(values) == len(expected) and error < 2e-6, (case, error)
        assert row['float_black'] == list(case['black']) and row['float_white'] == list(case['white']), row
        errors.append(error)
        output_bytes[case['name']] = blob
    assert output_bytes['per-plane-white'] == output_bytes['scaled-neutral-white']
    unchanged = [i for y in range(original.HEIGHT) for x in range(56)
                 for i in range((y * original.WIDTH + x) * 3, (y * original.WIDTH + x) * 3 + 3)]
    assert all(output_bytes['max2'][i * 4:i * 4 + 4] == output_bytes['max4'][i * 4:i * 4 + 4] for i in unchanged)

    fallback_count = 0
    for invalid in ('crop', 'opcode', 'profile', 'repeat'):
        case, _ = original.fixture(args.folder, 'fallback-' + invalid, invalid=invalid)
        row, _ = run(args.folder / (case['name'] + '.dng'))
        assert row['ok'] and row['mode'] == 'float_legacy_quantized' and row['note'], row
        fallback_count += 1
    cfa = storage.fixture(args.folder, False, 2)
    row, _ = run(args.folder / (cfa['name'] + '.dng'))
    assert row['ok'] and row['mode'] == 'float_legacy_quantized' and 'camera layout' in row['note'], row
    fallback_count += 1
    for name in ('max2', 'per-plane-white', 'orientation-8'):
        row, _ = run(args.folder / (name + '.dng'), '--custom')
        assert row['ok'] and row['mode'] == 'float_legacy_quantized' and 'custom white balance' in row['note'], row
        fallback_count += 1

    bad, _ = original.fixture(args.folder, 'supported-bad-sample', invalid='nonfinite')
    row, _ = run(args.folder / (bad['name'] + '.dng'))
    assert row['mode'] == 'float_linear_v1' and not row['ok'] and 'nonfinite' in row['error'], row

    integer_cases = []
    for linear in (False, True):
        case = storage.fixture(args.folder, linear, 2)
        path = args.folder / ('integer-' + ('linear' if linear else 'bayer') + '.dng')
        data = bytearray((args.folder / (case['name'] + '.dng')).read_bytes())
        channels = 3 if linear else 1
        count = storage.WIDTH * storage.HEIGHT * channels
        strip = struct.unpack_from('<I', data, entry(data, 273) + 8)[0]
        values = [min(4095, max(0, (x * 59 + y * 13 + channel * 179)))
                  for y in range(storage.HEIGHT) for x in range(storage.WIDTH) for channel in range(channels)]
        struct.pack_into('<' + 'H' * count, data, strip, *values)
        replace_tag(data, 258, 3, [16] * channels)
        replace_tag(data, 339, 3, [1] * channels)
        replace_tag(data, 279, 4, [count * 2])
        replace_tag(data, 50717, 4, [4095] * channels)
        path.write_bytes(data)
        integer_cases.append(path)
    integer_cases += args.integer
    for path in integer_cases:
        for options in ((), ('--custom',)):
            row, _ = run(path, *options)
            assert row['ok'] and row['mode'] == 'integer_compatible16' and not row['note'], row

    baseline = args.folder / 'max2.dng'
    success, _ = run(baseline)
    cancellation_count = success['polls']
    for stop in range(1, cancellation_count + 1):
        row, _ = run(baseline, '--cancel', stop)
        assert row['cancelled'] and not row['ok'] and row['unchanged'] and row['float_metadata_unchanged'], (stop, row)
    for path in (integer_cases[0], args.folder / 'fallback-crop.dng'):
        success, _ = run(path)
        assert success['polls'] == 2, success
        for stop in (1, 2):
            row, _ = run(path, '--cancel', stop)
            assert row['cancelled'] and not row['ok'] and row['unchanged'] and row['float_metadata_unchanged'], row
            cancellation_count += 1
    for option in ('--bad-plan', '--wrong-wb'):
        row, _ = run(baseline, option)
        assert not row['ok'] and not row['cancelled'] and row['error'], row
    summary = dict(passed=True, float_cases=len(float_cases), fallback_cases=fallback_count,
                   integer_cases=len(integer_cases) * 2, cancellation_cases=cancellation_count,
                   maximum_absolute_error=max(errors), independent_absolute_tolerance=2e-6,
                   helper_pixels_byte_equal=True, legacy_pixels_byte_equal=True, legacy_metadata_equal=True,
                   all_eight_orientations_match_libraw_flip=True, float_metadata_not_integer_truncated=True,
                   supported_bad_sample_does_not_fallback=True, atomic_failures=True,
                   distant_maximum_byte_equal=True, scaled_neutral_byte_equal=True)
    (args.folder / 'results.json').write_text(json.dumps(dict(summary=summary, records=records), indent=2) + '\n')
    print(json.dumps(summary))


if __name__ == '__main__':
    main()
