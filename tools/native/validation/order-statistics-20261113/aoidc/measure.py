"""Co-created with GPT (OpenAI). Paired independent-process stage measurements."""
import csv
import json
from pathlib import Path
import statistics
import subprocess
import sys

baseline, candidate, folder = map(Path, sys.argv[1:])
folder.mkdir(parents=True, exist_ok=True)
rows = []
for mode in ('median', 'meter', 'scene'):
    for pattern in (0, 1, 5):
        for pair in range(3):
            order = [('baseline', baseline), ('candidate', candidate)]
            if pair % 2:
                order.reverse()
            for side, probe in order:
                text = subprocess.check_output([str(probe), '--benchmark', mode, str(pattern), '30'], text=True)
                (folder / f'{mode}-{pattern}-{pair}-{side}.csv').write_text(text)
                fields = text.strip().split(',')
                assert fields[:4] == [mode, str(pattern), '65536', '30'], text
                rows.append(dict(mode=mode, pattern=pattern, pair=pair, side=side,
                                 milliseconds=float(fields[4]) / 30, checksum=fields[5]))
        assert len({r['checksum'] for r in rows if r['mode'] == mode and r['pattern'] == pattern}) == 1
with (folder / 'measurements.csv').open('w', newline='') as stream:
    writer = csv.DictWriter(stream, fieldnames=list(rows[0]))
    writer.writeheader()
    writer.writerows(rows)
summary = []
for mode in ('median', 'meter', 'scene'):
    for pattern in (0, 1, 5):
        selected = [r for r in rows if r['mode'] == mode and r['pattern'] == pattern]
        old, new = (statistics.median(r['milliseconds'] for r in selected if r['side'] == side)
                    for side in ('baseline', 'candidate'))
        summary.append(dict(mode=mode, pattern=pattern, baseline_ms=old, candidate_ms=new,
                            time_reduction_percent=100 * (1 - new / old)))
(folder / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
print(json.dumps(summary, indent=2))
