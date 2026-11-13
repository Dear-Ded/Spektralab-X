"""Compare actual old/new production outputs and alternating host timings."""

from array import array
import json
import math
from pathlib import Path
import statistics
import struct


root = Path("/data/work/gpt-raw-algorithm-20261113")
summary = {"baseline": "12573927f6d9f4562843dc8673ebcb94ffa20353", "pixels": 2048 * 1536}
for name in ("native", "normal", "sanitized", "output-normal", "output-sanitized"):
    assert int((root / f"{name}.exit").read_text()) == 0, name

timings = {}
for variant in ("baseline", "candidate"):
    rounds = []
    for number in range(1, 6):
        rounds.append(dict(line.strip().split(",", 1) for line in
                           (root / "performance" / f"{variant}-{number}.csv").read_text().splitlines()))
    timings[variant] = {
        stage: [float(row[stage]) for row in rounds]
        for stage in ("statistics_ms", "sigmoid_ms", "embedded_ms")
    }

summary["timings"] = {}
for stage in timings["baseline"]:
    old = statistics.median(timings["baseline"][stage])
    new = statistics.median(timings["candidate"][stage])
    summary["timings"][stage] = dict(baseline=timings["baseline"][stage], candidate=timings["candidate"][stage],
                                      baselineMedian=old, candidateMedian=new, speedup=old/new,
                                      reductionPercent=(1-new/old)*100)

before = (root / "baseline.bin").read_bytes()
after = (root / "candidate.bin").read_bytes()
header = 4096 * 8 + 8
channels = summary["pixels"] * 3
length = channels * 4
assert len(before) == len(after) == header + length * 2
assert before[:header] == after[:header], "histogram or trimmed log mean changed"
summary["statisticsBitExact"] = True
summary["outputParity"] = {}


def code16(value):
    # Mirror the float multiply at the engine's UNORM16 boundary.
    product = struct.unpack("f", struct.pack("f", value * 65535))[0]
    return math.floor(product + 0.5)


for index, name in enumerate(("sigmoid", "embedded")):
    start = header + index * length
    old_bytes, new_bytes = before[start:start+length], after[start:start+length]
    changes, max_error, max_code = 0, 0.0, 0
    if old_bytes != new_bytes:
        old, new = array("f"), array("f")
        old.frombytes(old_bytes)
        new.frombytes(new_bytes)
        for a, b in zip(old, new):
            assert math.isfinite(a) and math.isfinite(b) and 0 <= b <= 1
            if a != b:
                changes += 1
                max_error = max(max_error, abs(a-b))
                max_code = max(max_code, abs(code16(a)-code16(b)))
    assert max_error <= 6e-8 and max_code <= 1, (name, max_error, max_code)
    summary["outputParity"][name] = dict(channels=channels, changedChannels=changes,
                                           maxAbsoluteError=max_error, maxUnorm16Difference=max_code)

assert summary["timings"]["statistics_ms"]["reductionPercent"] > 5, "statistics improvement not established"
assert summary["timings"]["sigmoid_ms"]["reductionPercent"] > 2, "sigmoid improvement not established"
assert summary["timings"]["embedded_ms"]["reductionPercent"] > -10, "embedded curve regression"
text = json.dumps(summary, indent=2) + "\n"
(root / "verified-ab.json").write_text(text)
print(text, end="")
