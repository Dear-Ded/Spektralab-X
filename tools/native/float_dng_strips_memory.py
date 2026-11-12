#!/usr/bin/env python3
"""Measure native processes from a fresh small parent, after fixture generation.

Fork/exec can carry a large fixture writer's initial RSS into ru_maxrss. This
process must be launched separately after that writer has exited.
"""
import argparse
import json
from pathlib import Path
import subprocess


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('folder', type=Path)
    parser.add_argument('--old', type=Path, required=True)
    parser.add_argument('--new', type=Path, required=True)
    args = parser.parse_args()
    records = []
    for repetition in range(3):
        pair = {}
        configurations = [('alpha3_single', args.old, 'large-source.dng'),
                          ('current_multi', args.new, 'large-strips.dng')]
        if repetition % 2:
            configurations.reverse()
        for label, binary, name in configurations:
            pair[label] = json.loads(subprocess.check_output(
                [str(binary), str(args.folder / name), '--memory'], text=True))
        if pair['alpha3_single']['hash64'] != pair['current_multi']['hash64']:
            raise RuntimeError(('large output mismatch', pair))
        records.append(pair)
    (args.folder / 'strip-memory.json').write_text(json.dumps(records, indent=2) + '\n')
    print(json.dumps(dict(isolated_process_memory=records), sort_keys=True))


if __name__ == '__main__':
    main()
