"""Verify cloud Gradle results. Co-created with GPT (OpenAI)."""

import json
from pathlib import Path
import xml.etree.ElementTree as ET


root = Path("/data/work/gpt-save-preview-20261113")
summary = {"baseline": "2b32845299a264b219dd5163f9e24f7a13509f62", "suites": {}}
for name in ("release", "debug", "input-check"):
    code = int((root / f"{name}.exit").read_text().strip())
    assert code == 0, (name, code)

for suite, expected in (("release", (28, 212)), ("debug", (3, 26))):
    reports = sorted((root / "results" / suite).glob("TEST-*.xml"))
    totals = dict(classes=len(reports), tests=0, failures=0, errors=0, skipped=0)
    for report in reports:
        document = ET.parse(report).getroot()
        for field in ("tests", "failures", "errors", "skipped"):
            totals[field] += int(document.get(field, "0"))
    assert (totals["classes"], totals["tests"]) == expected, (suite, totals)
    assert totals["failures"] == totals["errors"] == totals["skipped"] == 0, totals
    summary["suites"][suite] = totals

summary["changedInputs"] = len((root / "source.sha256").read_text().splitlines())
assert summary["changedInputs"] == 11
summary["debugApkSha256"] = (root / "apk.sha256").read_text().split()[0]
summary["finished"] = (root / "finished.txt").read_text().strip()
text = json.dumps(summary, indent=2) + "\n"
(root / "verified-summary.json").write_text(text)
print(text, end="")
