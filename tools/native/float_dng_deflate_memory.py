#!/usr/bin/env python3
"""Co-created with GPT (OpenAI). Fresh-process full float output parity/RSS."""
import argparse
import json
from pathlib import Path
import subprocess


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('folder', type=Path)
    parser.add_argument('--probe', type=Path, required=True)
    args = parser.parse_args()
    records = []
    for repetition in range(3):
        pair = {}
        names = [('plain', 'large-source.dng'), ('deflate', 'large-deflate.dng')]
        if repetition % 2:
            names.reverse()
        for label, name in names:
            pair[label] = json.loads(subprocess.check_output(
                [str(args.probe), str(args.folder / name), '--memory'], text=True))
        if pair['plain']['hash64'] != pair['deflate']['hash64']:
            raise RuntimeError(('full output differs', pair))
        records.append(pair)
    (args.folder / 'memory.json').write_text(json.dumps(records, indent=2) + '\n')
    print(json.dumps(dict(isolated_process_memory=records), sort_keys=True))


if __name__ == '__main__':
    main()
