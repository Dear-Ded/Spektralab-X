#!/usr/bin/env python3
"""Paired production-meter timings; execute on a cloud worker. GPT (OpenAI)."""
import argparse
import csv
import json
from pathlib import Path
import statistics
import subprocess


def main(args):
    args.out.mkdir(parents=True, exist_ok=True)
    rows = []
    # Warm both binaries equally; compare alternating independent processes.
    for binary in (args.baseline, args.candidate):
        subprocess.check_output([str(binary), "--benchmark", "meter", "1", "10"], text=True)
    for pattern in (0, 1, 5, 6, 7, 9):
        for pair in range(7):
            order = [("baseline", args.baseline), ("candidate", args.candidate)]
            if pair % 2:
                order.reverse()
            for side, binary in order:
                text = subprocess.check_output(
                    [str(binary), "--benchmark", "meter", str(pattern), "60"], text=True)
                fields = text.strip().split(",")
                if fields[:4] != ["meter", str(pattern), "65536", "60"]:
                    raise ValueError("unexpected meter benchmark output")
                (args.out / f"{pattern}-{pair}-{side}.csv").write_text(text)
                rows.append(dict(pattern=pattern, pair=pair, side=side,
                                 milliseconds=float(fields[4]) / 60, checksum=fields[5]))
        selected = [row for row in rows if row["pattern"] == pattern]
        if len({row["checksum"] for row in selected}) != 1:
            raise ValueError("meter result bits changed")
    with (args.out / "measurements.csv").open("w", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)
    summary = []
    for pattern in (0, 1, 5, 6, 7, 9):
        selected = [row for row in rows if row["pattern"] == pattern]
        old, new = (statistics.median(row["milliseconds"] for row in selected
                                     if row["side"] == side)
                    for side in ("baseline", "candidate"))
        summary.append(dict(pattern=pattern, baseline_ms=old, candidate_ms=new,
                            time_reduction_percent=100 * (1 - new / old)))
    (args.out / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    print(json.dumps(summary, indent=2), flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--baseline", type=Path, required=True)
    parser.add_argument("--candidate", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    main(parser.parse_args())
