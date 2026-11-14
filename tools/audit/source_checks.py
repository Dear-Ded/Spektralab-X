#!/usr/bin/env python3
"""Check a byte inventory, Python/XML/shell syntax and credential markers in cloud."""
import argparse
import ast
import hashlib
import json
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path)
    parser.add_argument("inventory", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    inventory = json.loads(args.inventory.read_text(encoding="utf-8-sig"))
    results = []
    # Report locations and types only; never print a matched credential.
    credentials = re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----|\b(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{40,}|AKIA[A-Z0-9]{16})\b")
    for item in inventory:
        path = args.root / item["path"]
        data = path.read_bytes()
        record = {"path": item["path"], "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}
        assert record["sha256"] == item["sha256"], item["path"]
        text = data.decode("utf-8-sig")
        assert "\x00" not in text, item["path"]
        assert not credentials.search(text), "Credential marker in " + item["path"]
        if path.suffix == ".py":
            ast.parse(text, filename=item["path"])
            record["syntax"] = "Python AST"
        elif path.suffix == ".xml":
            ET.fromstring(text)
            record["syntax"] = "XML"
        elif path.suffix == ".sh":
            subprocess.run(["bash", "-n", str(path)], check=True, capture_output=True)
            record["syntax"] = "bash -n"
        results.append(record)
    report = {"files": len(results), "bytes": sum(r["bytes"] for r in results),
              "syntax_checked": sum("syntax" in r for r in results),
              "credentials": "No matching credential markers; heuristic only", "results": results}
    args.output.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps({k: v for k, v in report.items() if k != "results"}))


if __name__ == "__main__":
    main()
