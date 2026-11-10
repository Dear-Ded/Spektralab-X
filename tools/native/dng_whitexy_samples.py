#!/usr/bin/env python3
"""Compare actual production decoders without treating sample appearance as a metric."""
import argparse
import filecmp
import hashlib
import json
import os
from pathlib import Path
import subprocess


def digest(path):
    value = hashlib.sha256()
    with path.open('rb') as file:
        for block in iter(lambda: file.read(1024*1024), b''): value.update(block)
    return value.hexdigest()


def run(probe, source, policy, mode, output):
    env = dict(os.environ, SPK_TEST_RAW_BUDGET='1', SPEKTRALAB_RAW_THREADS='2')
    result = json.loads(subprocess.check_output(
        [str(probe), str(source), policy, mode, str(output)], text=True, env=env))
    if result['ok']:
        assert result['finite'] and result['width']*result['height']*12 == output.stat().st_size
        result['rgb_sha256'] = digest(output)
    else:
        assert result['unchanged'], result
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--old', required=True, type=Path)
    parser.add_argument('--new', required=True, type=Path)
    parser.add_argument('--samples', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    records = []
    names = [
        'Xiaomi_-_MI_MAX_-_10bit_(4:3).dng',
        'Xiaomi_-_M2010J19CG_-_16bit_(4:3).dng',
        'Xiaomi_-_Redmi_Note_7_-_16bit_(4:3).dng',
        'Xiaomi_-_Mi_9_Lite_-_16bit_(4:3).dng',
        'Google_-_Pixel_4a_-_16bit_(4:3).dng',
        'Apple_-_iPhone_12_Pro_-_8bit_(4:3).DNG']
    for name in names:
        source = args.samples/name
        source_hash = digest(source)
        for mode in ('default', 'custom'):
            old_file, new_file = args.output/'old.f32', args.output/'new.f32'
            old = run(args.old, source, 'compatible', mode, old_file)
            new = run(args.new, source, 'compatible', mode, new_file)
            assert old['ok'] and new['ok'], (name, mode, old, new)
            assert filecmp.cmp(old_file, new_file, shallow=False), (name, mode)
            records.append(dict(file=name, source_sha256=source_hash, policy='compatible',
                                mode=mode, byte_equal=True, old=old, new=new))
            old_file.unlink(); new_file.unlink()
    pixel = args.samples/'Google_-_Pixel_8_Pro_-_16bit_(4:3).dng'
    source_hash = digest(pixel)
    for policy in ('compatible', 'headroom'):
        results = []
        for mode in ('default', 'custom'):
            output = args.output/'pixel.f32'
            old = run(args.old, pixel, policy, mode, output)
            new = run(args.new, pixel, policy, mode, output)
            if mode == 'default':
                assert not old['ok'] and 'white balance is required' in old['error'], old
            assert new['ok'] and new['from_xy'] and new['source_valid'] and new['temperature_valid'], new
            assert new['xy'] == [.322046, .326646], new
            results.append(new)
            records.append(dict(file=pixel.name, source_sha256=source_hash, policy=policy,
                                mode=mode, old=old, new=new))
            if output.exists(): output.unlink()
        assert results[0]['source_wb'] == results[1]['source_wb'], results
    (args.output/'results.json').write_text(json.dumps(records, indent=2)+'\n', encoding='utf-8')
    print(json.dumps(dict(unchanged_as_shot_pairs=12, pixel_cases=4,
                         pixel_old_default_rejections=2, passed=True)))
