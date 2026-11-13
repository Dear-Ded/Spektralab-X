"""Co-created with GPT (OpenAI). Independent-process public synthetic RAW benchmark."""
import csv
import json
from pathlib import Path
import re
import statistics
import subprocess
import sys

baseline, candidate, fixtures, output = map(Path, sys.argv[1:])
output.mkdir(parents=True, exist_ok=True)
rows = []
for case, fixture, mode in (
    ('linear_working', 'integer-linear-24mp.dng', '--memory-fused'),
    ('bayer_working', 'integer-bayer-24mp.dng', '--memory-fused'),
    ('linear_full', 'integer-linear-24mp.dng', '--memory-full'),
):
    path = fixtures / fixture
    with path.open('rb') as stream:
        while stream.read(8 * 1024 * 1024):
            pass
    for pair in range(3):
        order = [('baseline', baseline), ('candidate', candidate)]
        if pair % 2:
            order.reverse()
        for side, probe in order:
            text = subprocess.check_output([str(probe), mode, str(path)], text=True)
            (output / f'{case}-{pair}-{side}.log').write_text(text)
            match = re.search(r'peak_rss_kib=(\d+),ms=([\d.]+),fnv1a=([0-9a-f]+)', text)
            assert match, text
            rows.append(dict(case=case, pair=pair, side=side, peak_rss_kib=int(match[1]),
                             ms=float(match[2]), fnv1a=match[3]))
    assert len({r['fnv1a'] for r in rows if r['case'] == case}) == 1, case
with (output / 'measurements.csv').open('w', newline='') as stream:
    writer = csv.DictWriter(stream, fieldnames=list(rows[0]))
    writer.writeheader()
    writer.writerows(rows)
summary = {}
for case in dict.fromkeys(r['case'] for r in rows):
    summary[case] = {}
    for side in ('baseline', 'candidate'):
        selected = [r for r in rows if r['case'] == case and r['side'] == side]
        summary[case][side] = {key: statistics.median(r[key] for r in selected)
                               for key in ('peak_rss_kib', 'ms')}
    old, new = summary[case]['baseline'], summary[case]['candidate']
    summary[case]['peak_reduction_percent'] = (1 - new['peak_rss_kib'] / old['peak_rss_kib']) * 100
    summary[case]['time_reduction_percent'] = (1 - new['ms'] / old['ms']) * 100
(output / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
print(json.dumps(summary, indent=2))
