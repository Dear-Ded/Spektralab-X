#!/usr/bin/env python3
"""Query resolved shipped Maven coordinates in OSV, co-created with GPT (OpenAI)."""
import datetime
import json
from pathlib import Path
import urllib.request

A = Path('/data/work/gpt-alpha7-audit-20261114')
components = json.loads((A / 'runtime-components.json').read_text())
components = [c for c in components if c['group'] != 'Spektralab-X']
results = []
for start in range(0, len(components), 50):
    batch = components[start:start+50]
    payload = {'queries': [{'package': {'ecosystem': 'Maven', 'name': c['group'] + ':' + c['name']},
                            'version': c['version']} for c in batch]}
    request = urllib.request.Request('https://api.osv.dev/v1/querybatch',
                                     data=json.dumps(payload).encode(),
                                     headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(request, timeout=45) as response:
        data = json.load(response)
    if len(data['results']) != len(batch):
        raise RuntimeError('Incomplete OSV response')
    results.extend({**c, **r} for c, r in zip(batch, data['results']))
report = {'queried_utc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
          'components': len(results), 'components_with_advisories': sum(bool(r.get('vulns')) for r in results),
          'results': results}
(A / 'osv-runtime.json').write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps({k:v for k,v in report.items() if k != 'results'}))
for result in results:
    if result.get('vulns'):
        print(json.dumps(result))
