#!/usr/bin/env python3
"""Fetch pinned public research inputs into an external, non-distributed cache."""

import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import hashlib
import json
from pathlib import Path
import urllib.error
import urllib.parse
import urllib.request

PINS = {
    "ssssota/nikon-flexible-color-picture-control": "ed0397fbf1e4d4f2bc11ab3070522c613667d359",
    "RawTherapee/RawTherapee": "64c2d90a3248c7ad41842d9162368858b61aac6a",
    "lensfun/lensfun": "52cf1a7b0e82e07e0ecaa3391ee5106a6dd1ee54",
    "Beep6581/dcamprof": "bffb659d6a0af82f7b6b2e6d0006d1824ac4d398",
}
CANON = ["WW01_NOSTALGIA", "WW02_CLEAR", "WW03_TWILIGHT", "WW04_EMERALD",
         "WW05_AUTUMN_HUES", "WW06_P-STUDIO", "WW07_P-SNAPSHOT", "WW08_VIDEO-X"]


def download(url, limit=16 * 1024 * 1024):
    request = urllib.request.Request(url, headers={"User-Agent": "Spektralab-profile-research/1.0"})
    with urllib.request.urlopen(request, timeout=20) as response:
        data = response.read(limit + 1)
    if len(data) > limit:
        raise ValueError("Source exceeds download limit")
    return data


def wanted(repo, path):
    if repo.startswith("ssssota/"):
        return (path.startswith("src/") and path.endswith(".ts") or
                path.startswith("samples/") and path.endswith(".NP3") or
                path in ("LICENSE", "test/samples.test.ts", "package.json"))
    if repo.startswith("RawTherapee/"):
        return (path.startswith("rtdata/dcpprofiles/") and path.endswith(".dcp") or
                path in ("LICENSE", "rtengine/dcp.cc", "rtengine/dcp.h"))
    if repo.startswith("lensfun/"):
        return (path.startswith("data/db/") and path.endswith(".xml") or
                path in ("data/COPYING.CC_BY-SA_3.0", "COPYING.LESSER"))
    return path in ("src/dngref.c", "src/dngref.h", "LICENSE.txt", "README.md")


def fetch_all(root, output):
    jobs = []
    for repo, commit in PINS.items():
        tree_url = f"https://api.github.com/repos/{repo}/git/trees/{commit}?recursive=1"
        tree_path = root / (repo.replace("/", "_") + "-tree.json")
        if not tree_path.exists():
            tree_path.write_bytes(download(tree_url))
        tree = json.loads(tree_path.read_text())
        if tree.get("truncated"):
            raise ValueError(f"Truncated source tree: {repo}")
        for entry in tree["tree"]:
            rel = entry["path"]
            if entry["type"] == "blob" and wanted(repo, rel):
                if ".." in Path(rel).parts or rel.startswith("/"):
                    raise ValueError("Unsafe upstream path")
                url = f"https://raw.githubusercontent.com/{repo}/{commit}/" + urllib.parse.quote(rel)
                jobs.append(({"repo": repo, "commit": commit, "path": rel,
                              "url": url, "gitBlobSha1": entry["sha"]}, root / repo / rel))
    for name in CANON:
        rel = name + ".pf2"
        jobs.append(({"repo": "Canon official downloads", "path": rel,
                      "url": "https://global.canon/en/imaging/picturestyle/file/wnpic/" + rel},
                     root / "canon-official" / rel))

    def fetch(job):
        metadata, path = job
        metadata = dict(metadata)
        for attempt in range(3):
            try:
                data = path.read_bytes() if path.exists() else download(metadata["url"])
                blob = hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest()
                if "gitBlobSha1" in metadata and blob != metadata["gitBlobSha1"]:
                    raise ValueError("Pinned Git blob mismatch; inspect the external cache")
                path.parent.mkdir(parents=True, exist_ok=True)
                if not path.exists():
                    path.write_bytes(data)
                metadata.update(bytes=len(data), sha256=hashlib.sha256(data).hexdigest())
                return metadata
            except (OSError, ValueError, urllib.error.URLError) as error:
                if attempt == 2:
                    metadata["error"] = str(error)
        return metadata

    rows = []
    with ThreadPoolExecutor(max_workers=6) as pool:
        for future in as_completed([pool.submit(fetch, job) for job in jobs]):
            rows.append(future.result())
    rows.sort(key=lambda r: (r["repo"], r["path"]))
    output.write_text(json.dumps(rows, indent=2) + "\n")
    print(json.dumps({"requested": len(rows), "failed": sum("error" in r for r in rows)}))
    if any("error" in r for r in rows):
        raise SystemExit(1)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("cache", type=Path)
    parser.add_argument("manifest", type=Path)
    args = parser.parse_args()
    args.cache.mkdir(parents=True, exist_ok=True)
    fetch_all(args.cache, args.manifest)
