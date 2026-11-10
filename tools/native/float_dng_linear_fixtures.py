#!/usr/bin/env python3
"""Co-created with GPT (OpenAI). Production-helper tests, synthetic inputs only.

Reuse the historical writer and unchanged independent matrix/orientation oracle;
do not derive expected pixels from the helper's prepared matrix or normalization.
"""
import argparse
import json
from pathlib import Path
import struct
import subprocess

import dng_float_linear_fixtures as original
import dng_float_precision_fixtures as storage


def entry(data, number):
    count = struct.unpack_from('<H', data, 8)[0]
    return next(10 + i * 12 for i in range(count)
                if struct.unpack_from('<H', data, 10 + i * 12)[0] == number)


def replace_tag(data, number, kind, values):
    at = entry(data, number)
    encoded = original.payload(kind, values)
    count = len(encoded) // {1: 1, 2: 1, 3: 2, 4: 4, 5: 8, 7: 1, 10: 8}[kind]
    if len(encoded) <= 4:
        value = encoded.ljust(4, b'\0')
    else:
        value = struct.pack('<I', len(data))
        data.extend(encoded)
    data[at:at + 12] = struct.pack('<HHI4s', number, kind, count, value)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('folder', type=Path)
    parser.add_argument('--probe', required=True, type=Path)
    args = parser.parse_args()
    args.folder.mkdir(parents=True, exist_ok=True)
    cases = [original.fixture(args.folder, 'max' + str(n), maximum=n) for n in (2, 4)]
    cases += [original.fixture(args.folder, 'fractional', black=(.25, .25, .25), white=(4, 4, 4)),
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
    cases += [original.fixture(args.folder, 'orientation-' + str(n), orientation=n) for n in range(2, 9)]
    cases += [original.fixture(args.folder, 'unsupported-' + name, invalid=name)
              for name in ('crop', 'opcode', 'profile', 'no-fm', 'repeat', 'white',
                           'nonfinite', 'missing-neutral', 'zero-neutral')]
    records, outputs = [], {}
    baseline = args.folder / 'max2.dng'

    def run(path, *options):
        output = args.folder / 'output.f32'
        row = json.loads(subprocess.check_output(
            [str(args.probe), str(path), str(output), *map(str, options)], text=True))
        assert row.get('open_status') == 0, (path, options, row)
        assert row['opened_unchanged'], (path, row)
        assert row['ok'] or row['result_unchanged'], (path, row)
        pixels = output.read_bytes() if row['ok'] else None
        return row, pixels

    for case, stored in cases:
        path = args.folder / (case['name'] + '.dng')
        # A valid opened float decoder also permits parser-rejection tests whose
        # corrupt metadata LibRaw itself rejects before our dispatcher can run.
        row, blob = run(path, '--open-source', baseline)
        assert row['ok'] == (case['invalid'] is None), (case, row)
        if row['ok']:
            values = struct.unpack('<' + 'f' * (len(blob) // 4), blob)
            expected = original.oriented_reference(case, stored)
            assert len(values) == len(expected)
            error = max(abs(a - b) for a, b in zip(values, expected))
            assert error < 2e-6, (case, error)
            assert row['source_black'] == list(case['black']), (case, row)
            assert row['source_white'] == list(case['white']), (case, row)
            assert row['source_neutral'] == case['neutral'], (case, row)
            row['max_absolute_error'] = error
            outputs[case['name']] = blob
        elif row['support'] == 'UnsupportedFloat':
            assert row['metadata_unchanged'] and row['error'], row
        records.append(dict(case=case['name'], **row))
    assert outputs['scaled-neutral-white'] == outputs['per-plane-white']
    unchanged = [i for y in range(original.HEIGHT) for x in range(56)
                 for i in range((y * original.WIDTH + x) * 3, (y * original.WIDTH + x) * 3 + 3)]
    assert all(outputs['max2'][i * 4:i * 4 + 4] == outputs['max4'][i * 4:i * 4 + 4] for i in unchanged)
    for name in ('max2', 'fractional', 'per-plane-black'):
        a, b = [(24 * original.WIDTH + x) * 12 for x in (20, 28)]
        assert outputs[name][a:a + 12] != outputs[name][b:b + 12], name
    signed = struct.unpack('<' + 'f' * (len(outputs['max2']) // 4), outputs['max2'])
    assert min(signed) < 0 and max(signed) > 1

    malformed = []
    def rejection(name, change, expected_support='UnsupportedFloat'):
        data = bytearray(baseline.read_bytes())
        change(data)
        path = args.folder / (name + '.dng')
        path.write_bytes(data)
        row, _ = run(path, '--open-source', baseline)
        assert not row['ok'] and row['support'] == expected_support and row['error'], (name, row)
        if expected_support == 'UnsupportedFloat': assert row['metadata_unchanged'], row
        malformed.append(dict(case=name, **row))

    rejection('wrong-photometric-type', lambda d: replace_tag(d, 262, 4, [34892]))
    rejection('wrong-activearea-type', lambda d: replace_tag(d, 50829, 3, [0, 0, storage.HEIGHT, storage.WIDTH]))
    rejection('future-backward', lambda d: replace_tag(d, 50707, 1, [1, 5, 0, 0]))
    rejection('truncated-strip', lambda d: replace_tag(d, 279, 4, [storage.WIDTH * storage.HEIGHT * 12 - 4]))
    rejection('outside-strip', lambda d: replace_tag(d, 273, 4, [len(d)]))
    rejection('overlapping-strip', lambda d: replace_tag(d, 273, 4, [8]))
    rejection('multiple-strips', lambda d: replace_tag(d, 273, 4, [400, 800]))
    rejection('multiple-ifds', lambda d: struct.pack_into('<I', d, 10 + 12 * struct.unpack_from('<H', d, 8)[0], 8))
    rejection('invalid-orientation', lambda d: replace_tag(d, 274, 3, [9]))
    rejection('oversize-metadata', lambda d: struct.pack_into('<I', d, entry(d, 50721) + 4, 4097))
    for number in (50714, 50728, 50721, 50964):
        rejection('zero-denominator-' + str(number),
                  lambda d, tag=number: struct.pack_into('<I', d, struct.unpack_from('<I', d, entry(d, tag) + 8)[0] + 4, 0))
    for name, value in (('positive-infinity', float('inf')), ('negative-infinity', -float('inf')),
                        ('output-overflow', original.f32(3.4028234663852886e38))):
        rejection(name, lambda d, v=value: struct.pack_into('<f', d, struct.unpack_from('<I', d, entry(d, 273) + 8)[0], v),
                  'Supported')

    cfa = storage.fixture(args.folder, False, 2)
    row, _ = run(args.folder / (cfa['name'] + '.dng'))
    assert row['support'] == 'UnsupportedFloat' and row['metadata_unchanged'] and 'camera layout' in row['error'], row
    malformed.append(dict(case='float-bayer-dispatch', **row))
    data = bytearray(baseline.read_bytes())
    replace_tag(data, 258, 3, [16, 16, 16])
    replace_tag(data, 339, 3, [1, 1, 1])
    replace_tag(data, 279, 4, [storage.WIDTH * storage.HEIGHT * 6])
    integer = args.folder / 'integer-linear.dng'
    integer.write_bytes(data)
    integer_row, _ = run(args.folder / 'deliberately-absent.dng', '--open-source', integer,
                         '--max-pixels', 0, '--max-bytes', 0, '--cancel-inspect', 1)
    assert integer_row['support'] == 'NotFloat' and integer_row['metadata_unchanged'] and integer_row['result_unchanged'], integer_row
    assert integer_row['inspect_polls'] == integer_row['decode_polls'] == 0 and integer_row['error'] == 'untouched', integer_row

    limits = []
    for options in (('--max-pixels', 0), ('--max-bytes', 0),
                    ('--max-pixels', storage.WIDTH * storage.HEIGHT - 1),
                    ('--max-bytes', storage.WIDTH * storage.HEIGHT * 24 - 1)):
        row, _ = run(baseline, *options)
        assert row['support'] == 'UnsupportedFloat' and row['metadata_unchanged'] and row['decode_polls'] == 0, row
        limits.append(dict(options=options, **row))
    success, _ = run(baseline, '--max-pixels', storage.WIDTH * storage.HEIGHT,
                     '--max-bytes', storage.WIDTH * storage.HEIGHT * 24)
    assert success['ok'], success
    cancellations = []
    for phase in ('inspect', 'decode'):
        count = success[phase + '_polls']
        assert count > 0
        for stop in range(1, count + 1):
            row, _ = run(baseline, '--cancel-' + phase, stop)
            assert row['cancelled'] and not row['ok'] and row['result_unchanged'], (phase, stop, row)
            if phase == 'inspect': assert row['metadata_unchanged'], row
            cancellations.append(dict(phase=phase, stop=stop, **row))
        row, _ = run(baseline, '--cancel-' + phase, 1, '--throw-cancel')
        assert row['cancelled'] and row['result_unchanged'], row
        cancellations.append(dict(phase=phase, callback_throws=True, **row))
    summary = dict(passed=True, historical_cases=len(cases), historical_accepted=len(outputs),
                   malformed_cases=len(malformed), limit_cases=len(limits), cancellation_cases=len(cancellations),
                   maximum_absolute_error=max(row.get('max_absolute_error', 0) for row in records),
                   independent_absolute_tolerance=2e-6, unchanged_components=len(unchanged),
                   distant_maximum_byte_equal=True, scaled_AsShotNeutral_byte_equal=True,
                   adjacent_float_preserved=True, signed_and_headroom_preserved=True,
                   integer_no_read_no_cancel_no_mutation=True, atomic_failures=True,
                   input_plus_output_payload_bytes_per_pixel=24)
    (args.folder / 'results.json').write_text(json.dumps(dict(summary=summary, cases=records,
        malformed=malformed, limits=limits, cancellations=cancellations, integer=integer_row), indent=2) + '\n')
    print(json.dumps(summary))


if __name__ == '__main__':
    main()
