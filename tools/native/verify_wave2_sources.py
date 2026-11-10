#!/usr/bin/env python3
"""Replay patches with zero fuzz and compare every patched production file."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def replay(patches, pristine, candidate, output, archive=False):
    scratch = Path(tempfile.mkdtemp(prefix='replay-', dir=output))
    if archive:
        subprocess.run(['tar', '-xzf', str(pristine), '--strip-components=1', '-C', str(scratch)], check=True)
    else:
        shutil.copytree(pristine, scratch, dirs_exist_ok=True)
        # Match Android's CRX-only source newline normalization.
        subprocess.run(['sed', '-i', 's/\r$//', str(scratch/'src/decoders/crx.cpp')], check=True)
    paths = set()
    log = output/('engine-replay.log' if archive else 'libraw-replay.log')
    with log.open('w') as stream:
        for patch in sorted(patches):
            stream.write(patch.name+'\n'); stream.flush()
            subprocess.run(['patch', '-d', str(scratch), '-p1', '--forward', '--batch', '--fuzz=0',
                            '-i', str(patch)], stdout=stream, stderr=subprocess.STDOUT, check=True)
            paths.update(re.findall(r'^\+\+\+ b/(\S+)', patch.read_text(encoding='utf-8'), re.M))
    records = []
    for name in sorted(paths):
        expected, actual = sha(scratch/name), sha(candidate/name)
        assert actual == expected, (name, expected, actual)
        records.append(dict(path=name, sha256=actual))
    return dict(patches=len(patches), files=len(records), zero_fuzz=True, sources=records)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--repo', required=True, type=Path)
    parser.add_argument('--pin', required=True, type=Path)
    parser.add_argument('--upstream', required=True, type=Path)
    parser.add_argument('--libraw', required=True, type=Path)
    parser.add_argument('--android-libraw', required=True, type=Path)
    parser.add_argument('--junit', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    engine = replay(list((args.repo/'patches').glob('*.patch')), args.pin,
                    args.upstream, args.output, True)
    libraw = replay(list((args.repo/'patches/libraw').glob('*.patch')),
                    args.upstream/'build/deps/LibRaw-0.22.2', args.libraw, args.output)
    for item in libraw['sources']:
        assert sha(args.android_libraw/item['path']) == item['sha256'], item['path']
    junit = dict(suites=0, tests=0, failures=0, errors=0, skipped=0, task_state='UP-TO-DATE')
    for file in sorted(args.junit.glob('TEST-*.xml')):
        xml = ET.parse(file).getroot()
        junit['suites'] += 1
        for name in ('tests', 'failures', 'errors', 'skipped'):
            junit[name] += int(xml.attrib.get(name, '0'))
    assert junit['suites'] and junit['tests'] and not (junit['failures'] or junit['errors']), junit
    result = dict(engine=engine, libraw=libraw, android_libraw_sources_identical=True,
                  archive_sha256=sha(args.pin), junit=junit)
    (args.output/'result.json').write_text(json.dumps(result, indent=2)+'\n', encoding='utf-8')
    print(json.dumps(dict(engine_patches=engine['patches'],engine_files=engine['files'],
                         libraw_patches=libraw['patches'],libraw_files=libraw['files'],junit=junit)))
