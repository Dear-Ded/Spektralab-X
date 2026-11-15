#!/usr/bin/env python3
"""Verify every archived repository byte and check source syntax. GPT (OpenAI)."""
import argparse
import ast
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import subprocess
import tarfile
import xml.etree.ElementTree as ET


TEXT_SUFFIXES = {".kt", ".kts", ".cpp", ".hpp", ".c", ".h", ".inc", ".comp",
                 ".patch", ".py", ".sh", ".xml", ".json", ".yml", ".yaml", ".md",
                 ".txt", ".properties", ".toml", ".gradle", ".pro", ".map", ".sha256"}
SECRET = re.compile(rb"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----|\b(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{40,}|AKIA[A-Z0-9]{16})\b")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    parser.add_argument("root", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    records, errors, categories = [], [], {}
    with tarfile.open(args.archive, "r:gz") as archive:
        for member in archive:
            path = PurePosixPath(member.name)
            if path.is_absolute() or ".." in path.parts:
                raise ValueError("unsafe archive path")
            if member.isdir():
                continue
            target = args.root / path
            if member.issym():
                expected = member.linkname.encode()
                actual = target.readlink().as_posix().encode()
                kind = "symlink"
            elif member.isfile():
                expected = archive.extractfile(member).read()
                actual = target.read_bytes()
                kind = "file"
            else:
                raise ValueError("unsupported archive entry: " + str(path))
            equal = expected == actual
            record = {"path": str(path), "kind": kind, "bytes": len(actual),
                      "sha256": hashlib.sha256(actual).hexdigest(), "archive_equal": equal}
            if not equal:
                errors.append({"path": str(path), "error": "archive byte mismatch"})
            category = path.parts[0] if len(path.parts) > 1 else "root"
            categories[category] = categories.get(category, 0) + 1
            if kind == "file" and (path.suffix in TEXT_SUFFIXES or path.name in {"CMakeLists.txt", "gradlew", "VERSION", "VERSION_CODE"}):
                try:
                    text = actual.decode("utf-8-sig")
                    if "\x00" in text:
                        raise ValueError("NUL in text")
                    if SECRET.search(actual):
                        raise ValueError("credential marker; content withheld")
                    record["utf8"] = True
                    if path.suffix == ".py":
                        ast.parse(text, filename=str(path))
                        record["syntax"] = "Python AST"
                    elif path.suffix == ".xml":
                        ET.fromstring(text)
                        record["syntax"] = "XML"
                    elif path.suffix == ".json":
                        json.loads(text)
                        record["syntax"] = "JSON"
                    elif path.suffix == ".sh" or path.name == "gradlew":
                        subprocess.run(["bash", "-n", str(target)], check=True, capture_output=True)
                        record["syntax"] = "bash -n"
                except (ValueError, SyntaxError, ET.ParseError, subprocess.CalledProcessError) as error:
                    errors.append({"path": str(path), "error": str(error)[:300]})
            records.append(record)
    result = {"archive_sha256": hashlib.sha256(args.archive.read_bytes()).hexdigest(),
              "files": len(records), "bytes": sum(r["bytes"] for r in records),
              "byte_mismatches": sum(not r["archive_equal"] for r in records),
              "text_files": sum(r.get("utf8", False) for r in records),
              "syntax_checked": sum("syntax" in r for r in records),
              "categories": categories, "errors": errors, "records": records}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps({k: v for k, v in result.items() if k != "records"}), flush=True)
    return bool(errors)


if __name__ == "__main__":
    raise SystemExit(main())
