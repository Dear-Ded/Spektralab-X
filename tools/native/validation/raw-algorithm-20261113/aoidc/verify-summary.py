"""Verify native, Android and cached JVM evidence. Co-created with GPT (OpenAI)."""
import json
from pathlib import Path
import xml.etree.ElementTree as ET

root = Path("/data/work/gpt-raw-algorithm-20261113")
summary = json.loads((root / "verified-ab.json").read_text())
for name in ("native", "normal", "sanitized", "output-normal", "output-sanitized", "build", "gradle", "arm64"):
    assert int((root / f"{name}.exit").read_text()) == 0, name
for name in ("normal", "sanitized"):
    assert "12 groups passed" in (root / f"{name}.log").read_text()
for name in ("output-normal", "output-sanitized"):
    assert "RAW_DEVELOPMENT_OUTPUT_PASS cases=96" in (root / f"{name}.log").read_text()
summary["nativeGroupsPerBuild"] = 12
summary["outputCasesPerBuild"] = 96
reports = sorted((root / "results" / "release").glob("TEST-*.xml"))
totals = dict(classes=len(reports), tests=0, failures=0, errors=0, skipped=0)
for report in reports:
    document = ET.parse(report).getroot()
    for field in ("tests", "failures", "errors", "skipped"):
        totals[field] += int(document.get(field, "0"))
assert (totals["classes"], totals["tests"]) == (28, 212)
assert totals["failures"] == totals["errors"] == totals["skipped"] == 0
assert ":app:testReleaseUnitTest UP-TO-DATE" in (root / "gradle.log").read_text()
totals["execution"] = "UP-TO-DATE; Kotlin unchanged; not newly executed"
summary["releaseJvm"] = totals
assert not (root / "arm64-source-check.log").read_text().strip()
summary["uploadedInputs"] = len((root / "source.sha256").read_text().splitlines())
summary["nativeInputs"] = len((root / "native-inputs.sha256").read_text().splitlines())
summary["apkSha256"] = (root / "apk.sha256").read_text().split()[0]
summary["arm64JniSha256"] = (root / "arm64.sha256").read_text().split()[0]
summary["finished"] = (root / "build-finished.txt").read_text().strip()
text = json.dumps(summary, indent=2) + "\n"
(root / "verified-summary.json").write_text(text)
print(text, end="")
