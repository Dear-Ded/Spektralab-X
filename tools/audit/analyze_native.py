#!/usr/bin/env python3
"""Analyze every entry in an actual cloud compilation database. GPT (OpenAI)."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
from pathlib import Path
import plistlib
import re
import shlex
import subprocess
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("database", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--workers", type=int, default=2)
    args = parser.parse_args()
    entries = json.loads(args.database.read_text())
    args.output.mkdir(parents=True, exist_ok=True)

    def analyze(index_entry):
        index, entry = index_entry
        original = entry.get("arguments") or shlex.split(entry["command"])
        command, skip = [], False
        for word in original:
            if skip:
                skip = False
            elif word in ("-o", "-MF", "-MT", "-MQ"):
                skip = True
            elif word not in ("-c", "-MD", "-MMD", "-MP"):
                command.append(word)
        name = f'{index:03d}-{Path(entry["file"]).stem}'
        report = args.output / (name + ".plist")
        command += ["--analyze", "-Xclang", "-analyzer-output=plist", "-o", str(report)]
        started = time.monotonic()
        log_path = args.output / (name + ".log")
        with log_path.open("w") as log:
            try:
                code = subprocess.run(command, cwd=entry["directory"], stdout=log,
                                      stderr=subprocess.STDOUT, timeout=180).returncode
            except subprocess.TimeoutExpired:
                code = 124
        diagnostics = []
        if report.exists():
            data = plistlib.loads(report.read_bytes())
            for issue in data.get("diagnostics", []):
                location = issue.get("location", {})
                diagnostics.append({
                    "checker": issue.get("check_name"), "description": issue.get("description"),
                    "file": data["files"][location["file"]] if "file" in location else None,
                    "line": location.get("line"),
                })
        text_warnings = re.findall(r"^.+?:\d+:\d+: warning: .+$", log_path.read_text(), re.MULTILINE)
        result = {"source": entry["file"],
                  "source_sha256": hashlib.sha256(Path(entry["file"]).read_bytes()).hexdigest(),
                  "exit": code, "seconds": round(time.monotonic() - started, 3),
                  "diagnostics": diagnostics, "text_warnings": text_warnings, "report": name}
        (args.output / (name + ".json")).write_text(json.dumps(result, indent=2) + "\n")
        print(json.dumps({"index": index, "exit": code, "warnings": len(text_warnings)}), flush=True)
        return result

    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        results = list(pool.map(analyze, enumerate(entries)))
    summary = {"database_sha256": hashlib.sha256(args.database.read_bytes()).hexdigest(),
               "translation_units": len(results), "completed": sum(r["exit"] == 0 for r in results),
               "plist_diagnostics": sum(len(r["diagnostics"]) for r in results),
               "text_warnings": sum(len(r["text_warnings"]) for r in results), "results": results}
    (args.output / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    print(json.dumps({k: v for k, v in summary.items() if k != "results"}), flush=True)
    if summary["completed"] != len(entries):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
