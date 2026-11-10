"""Co-created with GPT. Measure child executable RSS, excluding launcher exec history."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import time

binary, resources, source, output = sys.argv[1:]
binary = Path(binary).resolve()
output = Path(output)
output.mkdir(parents=True, exist_ok=True)
records = []
for mode in ("full", "rows"):
    target = output / f"{mode}-measured.jpg"
    env = dict(os.environ, SPEKTRAFILM_VULKAN_STRICT_LIMITS="0", SPEKTRALAB_RAW_THREADS="2")
    started = time.monotonic()
    with (output / f"{mode}-measured.log").open("w") as log:
        child = subprocess.Popen([str(binary), resources, "memory", mode, source, str(target)],
                                 stdout=log, stderr=subprocess.STDOUT, env=env)
        maximum_rss = maximum_hwm = samples = 0
        while child.poll() is None:
            try:
                proc = Path(f"/proc/{child.pid}")
                if (proc / "exe").resolve() == binary:
                    status = dict(line.split(":", 1) for line in (proc / "status").read_text().splitlines() if ":" in line)
                    maximum_rss = max(maximum_rss, int(status.get("VmRSS", "0 kB").split()[0]))
                    maximum_hwm = max(maximum_hwm, int(status.get("VmHWM", "0 kB").split()[0]))
                    samples += 1
            except (OSError, ProcessLookupError):
                pass
            time.sleep(.005)
        if child.wait():
            raise RuntimeError(f"{mode} failed; inspect {log.name}")
    records.append({"mode": mode, "sampled_peak_rss_kib": maximum_rss,
                    "observed_kernel_vm_hwm_kib": maximum_hwm, "samples": samples,
                    "elapsed_seconds": time.monotonic() - started,
                    "jpeg_sha256": hashlib.sha256(target.read_bytes()).hexdigest()})
assert records[0]["jpeg_sha256"] == records[1]["jpeg_sha256"]
result = {"method": "5ms /proc child VmRSS and VmHWM polling after exe identity check; no inherited ru_maxrss.",
          "cases": records, "rss_reduction_kib": records[0]["sampled_peak_rss_kib"] - records[1]["sampled_peak_rss_kib"]}
(output / "measured-memory.json").write_text(json.dumps(result, indent=2) + "\n")
print(json.dumps(result, indent=2))
