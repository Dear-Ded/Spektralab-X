#!/usr/bin/env python3
"""Co-created with GPT (OpenAI). Frozen/candidate pixels and every metadata field."""
import argparse
import json
from pathlib import Path
import subprocess


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('folder', type=Path)
    parser.add_argument('--baseline', required=True, type=Path)
    parser.add_argument('--candidate', required=True, type=Path)
    parser.add_argument('--fixtures', required=True, nargs='+', type=Path)
    args = parser.parse_args()
    args.folder.mkdir(parents=True, exist_ok=True)
    records = []
    for fixture in args.fixtures:
        for options in ((), ('--custom',), ('--half-budget', '1'), ('--custom', '--half-budget', '1')):
            rows, pixels, metadata = [], [], []
            for side, probe in (('baseline', args.baseline), ('candidate', args.candidate)):
                output = args.folder / f'{len(records)}-{side}.f32'
                row = json.loads(subprocess.check_output([str(probe), str(fixture), str(output), *options], text=True))
                rows.append(row)
                pixels.append(output.read_bytes() if row.get('ok') else None)
                metadata.append(Path(str(output) + '.metadata').read_bytes() if row.get('ok') else None)
            baseline, candidate = rows
            poll_delta = candidate.get('polls', 0) - baseline.get('polls', 0)
            assert {k: v for k, v in baseline.items() if k != 'polls'} == {
                k: v for k, v in candidate.items() if k != 'polls'}, (fixture, options, rows)
            assert pixels[0] == pixels[1] and metadata[0] == metadata[1], (fixture, options)
            records.append(dict(fixture=fixture.name, options=options, mode=baseline.get('mode'),
                                ok=baseline.get('ok'), pixel_bytes=len(pixels[0] or b''),
                                metadata_bytes=len(metadata[0] or b''), poll_delta=poll_delta))
    result = dict(passed=True, cases=len(records), successful=sum(row['ok'] for row in records),
                  all_pixel_bytes_equal=True, all_metadata_fields_equal=True, records=records)
    (args.folder / 'results.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps({k: v for k, v in result.items() if k != 'records'}))


if __name__ == '__main__':
    main()
