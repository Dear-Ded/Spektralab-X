#!/usr/bin/env python3
"""Collect exact diagnostic contexts for review, co-created with GPT (OpenAI)."""
import json
from pathlib import Path
import plistlib

A = Path('/data/work/gpt-alpha7-audit-20261114')
summary = json.loads((A / 'native-static-summary.json').read_text())
contexts = []
for result in summary['results']:
    plist = A / 'native-static' / (result['report'] + '.plist')
    data = plistlib.loads(plist.read_bytes()) if plist.exists() else {}
    for index, issue in enumerate(result['diagnostics']):
        lines = Path(issue['file']).read_text().splitlines()
        line = issue['line']
        path = data['diagnostics'][index].get('path', [])
        events = [event.get('message') for event in path if event.get('kind') == 'event']
        contexts.append({**issue, 'unit': result['source'], 'report': result['report'],
                         'context': '\n'.join(f'{i + 1}: {lines[i]}' for i in range(max(0, line - 15), min(len(lines), line + 10))),
                         'path_events': events})
(A / 'native-diagnostic-context.json').write_text(json.dumps(contexts, indent=2) + '\n')
