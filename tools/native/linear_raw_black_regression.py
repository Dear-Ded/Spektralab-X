#!/usr/bin/env python3
"""Co-created with GPT (OpenAI). Uniform RGB black oracle and frozen controls."""
import argparse
import json
from pathlib import Path
import struct
import subprocess

import dng_float_precision_fixtures as storage
from float_dng_linear_fixtures import replace_tag


def fixture(folder, name, black, white, orientation=1, linear=True):
    source = storage.fixture(folder, linear, 2, black=black[0], white=white)
    data = bytearray((folder / (source['name'] + '.dng')).read_bytes())
    channels = 3 if linear else 1
    values = [0, 1, 63, 64, 65, 127, 1024, 2048, 3000, 4030, 4094, 4095]
    pixels = [values[(x + 3*y + c) % len(values)]
              for y in range(storage.HEIGHT) for x in range(storage.WIDTH)
              for c in range(channels)]
    payload = struct.pack('<' + 'H'*len(pixels), *pixels)
    replace_tag(data, 258, 3, [16]*channels)
    replace_tag(data, 339, 3, [1]*channels)
    replace_tag(data, 274, 3, [orientation])
    replace_tag(data, 50714, 5, list(black) if linear else [black[0]])
    replace_tag(data, 279, 4, [len(payload)])
    replace_tag(data, 273, 4, [len(data)])
    data.extend(payload)
    path = folder / (name + '.dng')
    path.write_bytes(data)
    return path, pixels


def oriented(values, orientation):
    w, h = storage.WIDTH, storage.HEIGHT
    ow, oh = (h, w) if orientation >= 5 else (w, h)
    output = [0.0]*(ow*oh*3)
    for y in range(h):
        for x in range(w):
            dx, dy = [(x,y),(w-1-x,y),(w-1-x,h-1-y),(x,h-1-y),
                      (y,x),(h-1-y,x),(h-1-y,w-1-x),(y,w-1-x)][orientation-1]
            output[(dy*ow+dx)*3:(dy*ow+dx+1)*3] = values[(y*w+x)*3:(y*w+x+1)*3]
    return output


def run(probe, source, prefix, budget):
    subprocess.run([str(probe), str(source), str(prefix), str(budget)],
                   check=True, stdout=subprocess.DEVNULL)
    row = json.loads(prefix.with_suffix('.json').read_text())
    blob = prefix.with_suffix('.f32').read_bytes()
    return row, blob, struct.unpack('<' + 'f'*(len(blob)//4), blob)


def check(args):
    folder = args.folder.resolve()
    folder.mkdir(parents=True, exist_ok=True)
    records, controls = [], 0
    for black, white in ((64,4095),(1024,16383),(0,4095)):
        for orientation in range(1,9):
            name = f'uniform-{black}-{white}-{orientation}'
            source, pixels = fixture(folder, name, (black,)*3, white, orientation)
            reference = oriented([max(0, min(1,(v-black)/(white-black))) for v in pixels], orientation)
            for budget in (0,1):
                row, blob, actual = run(args.probe, source, folder/f'{name}-{budget}', budget)
                error = max(abs(a-b) for a,b in zip(actual, reference))
                assert len(actual) == len(reference) and error < 3/65535, (name, row, error)
                assert row['postprocess_maximum'] == white-black, row
                assert row['white'] == white and row['black_per_channel'][:3] == [black]*3, row
                records.append(dict(case=name, budget=budget, maximum_error=error))
                if args.baseline:
                    old, old_blob, _ = run(args.baseline, source, folder/f'{name}-{budget}-old', budget)
                    assert old['black_per_channel'] == row['black_per_channel'], (old,row)
                    if black == 0:
                        assert old_blob == blob, name
                        controls += 1
                    else:
                        assert old['postprocess_maximum'] == white and old_blob != blob, name
    if args.baseline:
        for linear, black in ((True,(64,80,96)),(False,(64,64,64))):
            source, _ = fixture(folder, f'control-{linear}', black, 4095, linear=linear)
            for budget in (0,1):
                row, blob, _ = run(args.probe, source, folder/f'control-{linear}-{budget}', budget)
                old, old_blob, _ = run(args.baseline, source, folder/f'control-{linear}-{budget}-old', budget)
                assert blob == old_blob, (linear, budget)
                for key in row:
                    if key != 'total_ms': assert row[key] == old[key], (key, row[key], old[key])
                controls += 1
    summary = dict(passed=True, oracle_cases=len(records), frozen_controls=controls,
                   maximum_error=max(r['maximum_error'] for r in records))
    (folder/'results.json').write_text(json.dumps(dict(summary=summary,records=records),indent=2)+'\n')
    print(json.dumps(summary))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('folder', type=Path)
    parser.add_argument('--probe', type=Path, required=True)
    parser.add_argument('--baseline', type=Path)
    check(parser.parse_args())
