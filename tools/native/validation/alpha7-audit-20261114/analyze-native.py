#!/usr/bin/env python3
"""Analyze the complete actual ARM64 database in cloud. GPT (OpenAI) co-creation."""
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
from pathlib import Path
import plistlib
import shlex
import subprocess
import time

A = Path('/data/work/gpt-alpha7-audit-20261114')
DATABASE = Path('/data/work/gpt-composable-styles-20261113/gradle-repo/app/.cxx/RelWithDebInfo/4x4c4s6b/arm64-v8a/compile_commands.json')
entries = json.loads(DATABASE.read_text())
reports = A / 'native-static'
reports.mkdir(parents=True, exist_ok=True)


def analyze(index_and_entry):
    index, entry = index_and_entry
    original = shlex.split(entry['command'])
    args = []
    skip = False
    for word in original:
        if skip:
            skip = False
            continue
        if word in ('-o', '-MF', '-MT', '-MQ'):
            skip = True
        elif word not in ('-c', '-MD', '-MMD', '-MP'):
            args.append(word)
    name = f'{index:03d}-{Path(entry["file"]).stem}'
    report = reports / (name + '.plist')
    args += ['--analyze', '-Xclang', '-analyzer-output=plist', '-o', str(report)]
    start = time.monotonic()
    timed_out = False
    with (reports / (name + '.log')).open('w') as log:
        try:
            process = subprocess.run(args, cwd=entry['directory'], stdout=log,
                                     stderr=subprocess.STDOUT, timeout=180)
            rc = process.returncode
        except subprocess.TimeoutExpired:
            timed_out = True
            rc = 124
    diagnostics = []
    if report.exists():
        data = plistlib.loads(report.read_bytes())
        for issue in data.get('diagnostics', []):
            location = issue.get('location', {})
            file_index = location.get('file')
            diagnostics.append({
                'checker': issue.get('check_name'), 'type': issue.get('type'),
                'description': issue.get('description'),
                'file': data['files'][file_index] if file_index is not None else None,
                'line': location.get('line'), 'column': location.get('col'),
            })
    result = {
        'source': entry['file'], 'source_sha256': hashlib.sha256(Path(entry['file']).read_bytes()).hexdigest(),
        'exit': rc, 'timed_out': timed_out, 'seconds': round(time.monotonic() - start, 3),
        'diagnostics': diagnostics, 'report': name,
    }
    (reports / (name + '.json')).write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps({'index': index, 'source': entry['file'], 'exit': rc,
                      'diagnostic_count': len(diagnostics)}), flush=True)
    return result


with ThreadPoolExecutor(max_workers=2) as pool:
    results = list(pool.map(analyze, enumerate(entries)))
summary = {
    'database': str(DATABASE), 'translation_units': len(results),
    'completed': sum(r['exit'] == 0 for r in results),
    'diagnostic_count': sum(len(r['diagnostics']) for r in results), 'results': results,
}
(A / 'native-static-summary.json').write_text(json.dumps(summary, indent=2) + '\n')
print(json.dumps({k: v for k, v in summary.items() if k != 'results'}), flush=True)
