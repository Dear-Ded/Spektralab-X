#!/usr/bin/env python3
# Spektralab-X - https://github.com/Dear-Ded/Spektralab-X
# Copyright (C) 2026 Spektralab-X contributors
# Additional terms under GPL-3.0 section 7 apply; see NOTICE.
# Synthetic ASN calibration-signature and finite-calibration regressions.
# Co-created with GPT (OpenAI), 2026-11-11.
"""Synthetic ASN calibration-signature audit.

Uses Adobe SDK dng_color_spec.cpp signature selection and SetWhiteXY algebra;
does not claim SDK full-render or camera appearance equivalence.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import subprocess

import dng_whitexy_fixtures as fixture


def expected_output(case, row, calibrated, dual, identity):
    kelvin = 6500 if row['mode'] == 'custom' else row['kelvin']
    weight = min(1, max(0, (1/kelvin-1/6500)/(1/2850-1/6500))) if dual else 1
    cc = [weight*a+(1-weight)*b for a,b in zip(*fixture.CC)] if calibrated and not identity else fixture.IDENTITY
    individual = fixture.inverse(fixture.multiply(fixture.diagonal(fixture.ANALOG), cc))
    wb = row['selected_wb'] if row['mode'] == 'custom' else row['source_wb']
    neutral = [1/w for w in wb[:3]]
    neutral = [v/max(neutral) for v in neutral]
    reference_neutral = fixture.vector(individual, neutral)
    fm = [round(v*1000000)/1000000 for v in fixture.FM]
    for r in range(3):
        total = sum(fm[3*r:3*r+3])
        for c in range(3):
            fm[3*r+c] *= fixture.PCS_WHITE[r]/total
    pcs = fixture.multiply(fixture.multiply(fm,
        fixture.diagonal([1/v for v in reference_neutral])), individual)
    return fixture.multiply(fixture.multiply(fixture.inverse(fixture.PROPHOTO_XYZ), pcs),
                            fixture.diagonal(neutral))


def run(folder, probe):
    folder.mkdir(parents=True, exist_ok=True)
    records = []
    for dual, identity in ((False, False), (True, False), (False, True), (True, True)):
        for signature in ('missing', 'match', 'different', 'camera_only', 'profile_only'):
            name = ('identity-' if identity else 'calibrated-')+('dual-' if dual else 'single-')+signature
            original_cc = fixture.CC
            if identity:
                fixture.CC = [fixture.IDENTITY, fixture.IDENTITY]
            case = fixture.fixture(folder, name, dual=dual, source='neutral', signature=signature)
            fixture.CC = original_cc
            calibrated = signature in ('missing', 'match')
            for policy in ('compatible', 'headroom'):
                for mode in ('default', 'custom'):
                    pixels = folder/(name+'-'+policy+'-'+mode+'.f32')
                    row = json.loads(subprocess.check_output(
                        [str(probe), str(folder/(name+'.dng')), policy, mode, str(pixels)], text=True))
                    row.update(case=name, policy=policy, mode=mode)
                    assert row['ok'] and row['finite'], row
                    expected = expected_output(case, row, calibrated, dual, identity)
                    actual = [row['camera_to_output'][4*r+c] for r in range(3) for c in range(3)]
                    row['matrix_error'] = max(abs(a-b) for a,b in zip(actual, expected))
                    row['matrix_pass'] = row['matrix_error'] < 1e-5
                    row['temperature_pass'] = mode != 'custom' or abs(row['kelvin']-6500) < 1e-8
                    row['pixel_sha256'] = hashlib.sha256(pixels.read_bytes()).hexdigest()
                    records.append(row)
    (folder/'asn-signature-results.json').write_text(json.dumps(records, indent=2)+'\n')
    return dict(cases=20, decodes=len(records), passes=sum(r['matrix_pass'] and r['temperature_pass'] for r in records),
                maximum_matrix_error=max(r['matrix_error'] for r in records))


def no_forward_controls(folder, probe):
    records = []
    for dual in (False, True):
        for signature in ('missing', 'match', 'different', 'camera_only', 'profile_only'):
            name = 'no-fm-'+('dual-' if dual else 'single-')+signature
            fixture.fixture(folder, name, dual=dual, source='neutral', signature=signature, forward=False)
            for policy in ('compatible', 'headroom'):
                for mode in ('default', 'custom'):
                    pixels = folder/(name+'-'+policy+'-'+mode+'.f32')
                    row = json.loads(subprocess.check_output(
                        [str(probe), str(folder/(name+'.dng')), policy, mode, str(pixels)], text=True))
                    assert row['ok'] and row['finite'], row
                    records.append(dict(case=name, policy=policy, mode=mode,
                        pixel_sha256=hashlib.sha256(pixels.read_bytes()).hexdigest()))
    (folder/'asn-no-forward-controls.json').write_text(json.dumps(records, indent=2)+'\n')
    return records


def replace_calibration(path, matrices):
    # DOUBLE is intentional malformed-metadata input for non-finite CC. It also
    # preserves near-unit values below the fixture writer's rational precision.
    data = bytearray(path.read_bytes())
    offset = struct.unpack_from('<I', data, 4)[0]
    count = struct.unpack_from('<H', data, offset)[0]
    replaced = 0
    for index in range(count):
        entry = offset + 2 + 12*index
        number = struct.unpack_from('<H', data, entry)[0]
        if number in (50723, 50724):
            values = matrices[number-50723]
            payload = struct.pack('<9d', *values)
            struct.pack_into('<HHII', data, entry, number, 12, 9, len(data))
            data.extend(payload)
            replaced += 1
    assert replaced in (1, 2)
    path.write_bytes(data)


def boundary_controls(folder, probe, gate_probe=None):
    records = []
    controls = [('nan-match', float('nan'), 'match', 'neutral'),
                ('nan-different', float('nan'), 'different', 'neutral'),
                ('inf-match', float('inf'), 'match', 'neutral'),
                ('inf-different', float('inf'), 'different', 'neutral'),
                ('near-different', 1e-8, 'different', 'neutral'),
                ('near-camera-only', 1e-8, 'camera_only', 'neutral'),
                ('invalid-signature', None, 'long', 'neutral'),
                ('non-asn', None, 'different', 'missing')]
    for dual in (False, True):
        for label, value, signature, source in controls:
            name = 'boundary-'+('dual-' if dual else 'single-')+label
            fixture.fixture(folder, name, dual=dual, source=source, signature=signature)
            gate_file = folder/(name+'.dng')
            if value is not None:
                gate_file = folder/(name+'.gate.dng')
                gate_file.write_bytes((folder/(name+'.dng')).read_bytes())
                matrices = [fixture.IDENTITY.copy(), fixture.IDENTITY.copy()]
                for matrix in matrices:
                    matrix[1] = value
                replace_calibration(folder/(name+'.dng'), matrices)
            gate = None
            if gate_probe:
                command = [str(gate_probe), str(gate_file)]
                if value is not None:
                    command.append(label.split('-')[0])
                gate = json.loads(subprocess.check_output(command, text=True))
                assert gate['open'] == 0
                assert bool(gate['finite_cc']) == (value is None or label.startswith('near-')), gate
            for policy in ('compatible', 'headroom'):
                for mode in ('default', 'custom'):
                    pixels = folder/(name+'-'+policy+'-'+mode+'.f32')
                    process = subprocess.run([str(probe), str(folder/(name+'.dng')), policy,
                        mode, str(pixels)], text=True, capture_output=True)
                    assert process.returncode == 0, (name, process.returncode, process.stderr)
                    # Existing fallback diagnostics may print non-JSON IEEE tokens.
                    payload = re.sub(r'(?<![\w"])-?(?:nan|inf)(?![\w"])', 'null', process.stdout)
                    row = json.loads(payload)
                    row.update(case=name, policy=policy, mode=mode,
                        pixel_sha256=hashlib.sha256(pixels.read_bytes()).hexdigest() if row['ok'] else None)
                    if gate is not None:
                        row['explicit_wb_gate'] = gate
                    records.append(row)
    (folder/'asn-boundary-controls.json').write_text(json.dumps(records, indent=2)+'\n')
    return records


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('folder', type=Path)
    parser.add_argument('--probe', required=True, type=Path)
    parser.add_argument('--gate-probe', type=Path)
    parser.add_argument('--require-pass', action='store_true')
    parser.add_argument('--compare-baseline', type=Path)
    args = parser.parse_args()
    result = run(args.folder, args.probe)
    no_forward = no_forward_controls(args.folder, args.probe)
    boundary = boundary_controls(args.folder, args.probe, args.gate_probe)
    if args.compare_baseline:
        old = json.loads((args.compare_baseline/'asn-signature-results.json').read_text())
        new = json.loads((args.folder/'asn-signature-results.json').read_text())
        identity = [(a,b) for a,b in zip(old,new) if a['case'].startswith('identity-')]
        assert len(identity) == 40 and all(a['pixel_sha256']==b['pixel_sha256'] for a,b in identity)
        old_no_forward = json.loads((args.compare_baseline/'asn-no-forward-controls.json').read_text())
        assert len(no_forward) == 40 and old_no_forward == no_forward
        old_boundary = json.loads((args.compare_baseline/'asn-boundary-controls.json').read_text())
        assert len(boundary) == 64 and old_boundary == boundary
        result.update(identity_byte_equal_pairs=40, no_forward_byte_equal_pairs=40,
                      boundary_equal_pairs=len(boundary))
    print(json.dumps(result))
    if args.require_pass:
        assert result['passes'] == result['decodes'], result
