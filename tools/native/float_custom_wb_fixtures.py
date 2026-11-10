#!/usr/bin/env python3
"""Co-created with GPT (OpenAI). Independent custom-WB mathematics and byte parity.

The historical double matrix/rational oracle remains unchanged. Temperature
controls are checked from literal Robertson CIE 1960 uv knots; XYZ is recovered
directly with Y=1, independently of the production xy conversion. The original
2e-6 absolute tolerance is unchanged for both default and custom WB. Default
output is also compared byte-for-byte to the pre-custom-WB helper when supplied.
"""
import argparse
import json
import math
from pathlib import Path
import struct
import subprocess

import dng_float_linear_fixtures as reference


KNOTS = ((20, .18133, .26846, -.26876), (100, .19032, .29326, -.47888),
         (150, .19962, .30921, -.70471), (175, .20525, .31647, -.84901),
         (250, .22511, .33439, -1.4512), (400, .27218, .35407, -4.3633),
         (575, .32931, .36038, -40.770), (600, .33724, .36051, -116.45))


def control_wb(temperature, tint):
    mired = 1e6 / temperature
    for knot in KNOTS:
        if abs(mired - knot[0]) < 1e-10:
            low = high = knot
            break
    else:
        low = max((k for k in KNOTS if k[0] < mired), key=lambda k: k[0])
        high = min((k for k in KNOTS if k[0] > mired), key=lambda k: k[0])
    fraction = 0 if high == low else (mired - low[0]) / (high[0] - low[0])
    u, v = [(1 - fraction) * low[i] + fraction * high[i] for i in (1, 2)]
    tangent = [(1 - fraction) * component(low[3]) + fraction * component(high[3])
               for component in (lambda slope: 1 / math.hypot(1, slope), lambda slope: slope / math.hypot(1, slope))]
    length = math.hypot(*tangent)
    u -= tint / 3000 * tangent[0] / length
    v -= tint / 3000 * tangent[1] / length
    x = 1.5 * u / v
    z = (6 / v - x - 15) / 3
    if x <= 0 or z <= 0: return None
    return [reference.f32(1 / x), 1., reference.f32(1 / z), 1.]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('folder', type=Path)
    parser.add_argument('--helper', type=Path, required=True)
    parser.add_argument('--dispatcher', type=Path)
    parser.add_argument('--baseline-helper', type=Path)
    args = parser.parse_args()
    args.folder.mkdir(parents=True, exist_ok=True)
    records = []
    cases = [reference.fixture(args.folder, 'orientation-' + str(n), orientation=n) for n in range(1, 9)]
    cases += [reference.fixture(args.folder, 'unequal-white', black=(.25, .375, .125), white=(4, 3, 2)),
              reference.fixture(args.folder, 'unequal-white-turned', black=(.25, .375, .125), white=(4, 3, 2), orientation=6),
              reference.fixture(args.folder, 'permuted-white', black=(.125, .25, .375), white=(2, 4, 3)),
              reference.fixture(args.folder, 'scaled-asn', black=(.25, .375, .125), white=(4, 3, 2), neutral=(.25, .5, .375)),
              reference.fixture(args.folder, 'changed-asn', neutral=(.25, .625, 1)),
              reference.fixture(args.folder, 'rational-asn', black=(.25, .375, .125), white=(4, 3, 2), neutral=(1 / 3, .875, 1))]

    def run(probe, path, *options):
        output = args.folder / 'output.f32'
        row = json.loads(subprocess.check_output([str(probe), str(path), str(output), *map(str, options)], text=True))
        assert row['open_status'] == 0, row
        if probe == args.helper:
            assert row['opened_unchanged'] and (row['ok'] or row['result_unchanged']), row
        elif probe == args.dispatcher:
            assert row['raw_unchanged'] and (row['ok'] or (row['unchanged'] and row['float_metadata_unchanged'])), row
        return row, output.read_bytes() if row['ok'] else None

    def verify(case, stored, row, blob, wb, label):
        assert row['ok'], (label, case, row)
        expected_case = dict(case, neutral=[1 / value for value in wb[:3]])
        expected = reference.oriented_reference(expected_case, stored)
        values = struct.unpack('<' + 'f' * (len(blob) // 4), blob)
        assert len(values) == len(expected)
        errors = [abs(a - b) for a, b in zip(values, expected)]
        bounds = [2e-6] * len(expected)
        assert all(error <= bound for error, bound in zip(errors, bounds)), (label, case['name'], max(errors))
        source_wb = [reference.f32(1 / value) for value in case['neutral']]
        assert row['camera_wb'][:3] == source_wb, (label, row)
        records.append(dict(case=case['name'], label=label, maximum_absolute_error=max(errors),
                            maximum_error_over_bound=max(a / b for a, b in zip(errors, bounds)), **row))

    default_bytes, custom_bytes = {}, {}
    default_baseline_count = 0
    for case, stored in cases:
        path = args.folder / (case['name'] + '.dng')
        default, blob = run(args.helper, path)
        assert default['ok']
        values = struct.unpack('<' + 'f' * (len(blob) // 4), blob)
        assert max(abs(a - b) for a, b in zip(values, reference.oriented_reference(case, stored))) < 2e-6
        default_bytes[case['name']] = blob
        if args.baseline_helper:
            old, old_blob = run(args.baseline_helper, path)
            assert old['ok'] and old_blob == blob, (case, old)
            default_baseline_count += 1
        for label, wb, temperature in (
                ('relative', [2., 1., 1.5, 1.], 6500),
                ('scaled-relative', [4., 2., 3., 2.], 6500),
                ('nonunit-green', [.5, 1., .75, 1.], 0),
                ('explicit-as-shot', [reference.f32(1 / value) for value in case['neutral']] + [reference.f32(1 / case['neutral'][1])], 0)):
            row, output = run(args.helper, path, '--wb', *wb, '--temperature', temperature)
            verify(case, stored, row, output, wb, label)
            if label == 'relative': custom_bytes[case['name']] = output
            if label == 'scaled-relative': assert output == custom_bytes[case['name']]
            if label == 'explicit-as-shot': assert output == blob, case
            if args.dispatcher:
                dispatch, dispatch_blob = run(args.dispatcher, path, '--wb', *wb, '--temperature', temperature)
                assert dispatch['ok'] and dispatch['mode'] == 'float_linear_v1' and not dispatch['note'], dispatch
                assert dispatch['exact_pixels'] and dispatch['exact_metadata'] and dispatch_blob == output, dispatch
    assert custom_bytes['unequal-white'] == custom_bytes['scaled-asn']
    assert custom_bytes['orientation-1'] == custom_bytes['changed-asn']

    temperature_cases = 0
    rejected_controls = []
    for case, stored in (cases[0], cases[8], cases[9], cases[13]):
        path = args.folder / (case['name'] + '.dng')
        for temperature in (1700, 2500, 4000, 6500, 10000, 50000):
            for tint in (-30, 0, 30):
                wb = control_wb(temperature, tint)
                if wb is None:
                    if args.dispatcher:
                        row, _ = run(args.dispatcher, path, '--temperature', temperature, '--tint', tint)
                        assert not row['ok'] and row['control_rejected'] and row['mode'] == 'float_linear_v1', row
                        rejected_controls.append(dict(temperature=temperature, tint=tint, **row))
                    continue
                if args.dispatcher:
                    row, blob = run(args.dispatcher, path, '--temperature', temperature, '--tint', tint)
                    assert row['mode'] == 'float_linear_v1' and row['exact_pixels'] and row['exact_metadata'], row
                    assert max(abs(a - b) for a, b in zip(row['selected_wb'], wb)) < 1e-6, (temperature, tint, wb, row)
                else:
                    row, blob = run(args.helper, path, '--wb', *wb, '--temperature', temperature)
                verify(case, stored, row, blob, wb, 'temperature-' + str(temperature) + '-tint-' + str(tint))
                temperature_cases += 1

    baseline = args.folder / 'orientation-1.dng'
    rejections = []
    for options in (('--temperature', 6500), ('--wb', 0, 1, 1, 1), ('--wb', -1, 1, 1, 1),
                    ('--wb', 'nan', 1, 1, 1), ('--wb', 'inf', 1, 1, 1), ('--wb', 1, 1, 1, 'nan'),
                    ('--wb', .0001, 1, 1, 1), ('--wb', 1, 1, 1, 1, '--temperature', -1),
                    ('--wb', 1, 1, 1, 1, '--temperature', 1699),
                    ('--wb', 1, 1, 1, 1, '--temperature', 50001),
                    ('--wb', 1, 1, 1, 1, '--temperature', 'nan')):
        row, _ = run(args.helper, baseline, *options)
        assert not row['ok'] and not row['cancelled'] and row['result_unchanged'] and row['error'], row
        rejections.append(dict(options=options, **row))
    cancellations = []
    cancellation_probes = [(args.helper, '--cancel-decode', 'decode_polls')]
    if args.dispatcher: cancellation_probes.append((args.dispatcher, '--cancel', 'polls'))
    for probe, option, polls in cancellation_probes:
        options = ('--wb', 2, 1, 1.5, 1, '--temperature', 6500)
        success, _ = run(probe, baseline, *options)
        for stop in (1, success[polls] // 2, success[polls]):
            row, _ = run(probe, baseline, *options, option, stop)
            assert not row['ok'] and row['cancelled'], row
            cancellations.append(dict(probe=probe.name, stop=stop, **row))
    summary = dict(passed=True, default_cases=len(cases), default_byte_equal_to_pre_custom=default_baseline_count,
                   relative_wb_cases=len(cases) * 4, temperature_tint_cases=temperature_cases,
                   production_temperature_controls_verified=bool(args.dispatcher),
                   rejected_outside_camera_controls=len(rejected_controls),
                   rejected_invalid_wb=len(rejections), custom_cancellation_cases=len(cancellations),
                   maximum_absolute_error=max(row['maximum_absolute_error'] for row in records),
                   maximum_error_over_bound=max(row['maximum_error_over_bound'] for row in records),
                   custom_error_bound='unchanged absolute 2e-6 for default and custom WB',
                   source_asn_preserved=True, selected_gain_scale_byte_invariant=True,
                   explicit_as_shot_byte_equal=True, selected_wb_independent_of_source_asn=True,
                   dispatcher_helper_byte_equal=bool(args.dispatcher))
    (args.folder / 'results.json').write_text(json.dumps(dict(summary=summary, records=records,
        rejections=rejections, rejected_controls=rejected_controls, cancellations=cancellations), indent=2) + '\n')
    print(json.dumps(summary))


if __name__ == '__main__':
    main()
