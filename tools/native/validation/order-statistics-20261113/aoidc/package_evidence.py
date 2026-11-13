"""Collect public cloud results; do not execute project binaries again."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tarfile

import yaml


ROOT = Path('/data/work/gpt-rank-selection-20261113')
REPO = ROOT / 'repo'
EVIDENCE = ROOT / 'evidence'
FROZEN = Path('/data/work/gpt-linearraw-move-20261113/upstream')
ARCHIVE = Path('/data/work/gpt-beta2-release-20261109/pinned-source.tar.gz')
PIN_SHA = '52ef254ffa7c70484a411c269f3f837f75d5a215ed7332d5fdab267317408f05'


def sha(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def run(args, output, **kwargs):
    with output.open('a') as log:
        return subprocess.run(args, stdout=log, stderr=subprocess.STDOUT,
                              check=True, text=True, **kwargs)


assert sha(ARCHIVE) == PIN_SHA
(EVIDENCE / 'official-pin.sha256').write_text(f'{PIN_SHA}  {ARCHIVE}\n')
replay = ROOT / 'replay-final3'
assert not replay.exists(), 'Use a fresh replay directory'
for log_name in ['engine-replay-final.log', 'ci-reverse-patch.log', 'toolchains.log']:
    (EVIDENCE / log_name).write_text('')
replay.mkdir()
run(['tar', '-xzf', str(ARCHIVE), '--strip-components=1', '-C', str(replay)],
    EVIDENCE / 'engine-replay-final.log')
patches = sorted((REPO / 'patches').glob('*.patch'))
assert len(patches) == 59
for patch in patches:
    with patch.open() as stream:
        run(['patch', '-d', str(replay), '-p1', '--fuzz=0', '--forward', '--batch'],
            EVIDENCE / 'engine-replay-final.log', stdin=stream)


def tree(path):
    return {str(p.relative_to(path)): sha(p) for p in path.rglob('*')
            if p.is_file() and p.suffix not in ('.orig', '.rej')}


candidate = tree(ROOT / 'upstream/engine/src')
assert tree(replay / 'engine/src') == candidate
last_patch = REPO / 'patches/0059-exact-multiple-order-statistics.patch'
with last_patch.open() as stream:
    run(['patch', '-d', str(replay), '-p1', '--fuzz=0', '--reverse', '--batch'],
        EVIDENCE / 'ci-reverse-patch.log', stdin=stream)
assert tree(replay / 'engine/src') == tree(FROZEN / 'engine/src')
with last_patch.open() as stream:
    run(['patch', '-d', str(replay), '-p1', '--fuzz=0', '--forward', '--batch'],
        EVIDENCE / 'ci-reverse-patch.log', stdin=stream)
assert tree(replay / 'engine/src') == candidate
(EVIDENCE / 'replay-summary.txt').write_text(
    f'PASS verified official engine archive SHA-256\n'
    f'PASS {len(patches)} engine overlays, zero fuzz\n'
    f'PASS {len(candidate)} engine/src files match tested candidate\n'
    f'PASS reversing only 0059 matches frozen 0058 engine/src\n'
    f'PASS reapplying only 0059 restores candidate engine/src\n')

workflow = REPO / '.github/workflows/engine-gpu.yml'
config = yaml.safe_load(workflow.read_text())
runs = 0
for name, job in config['jobs'].items():
    for step in job['steps']:
        if 'run' in step:
            subprocess.run(['bash', '-n'], input=step['run'], text=True, check=True)
            runs += 1
for path in (REPO / 'tools/native').glob('run_order_statistics*.sh'):
    subprocess.run(['bash', '-n', str(path)], check=True)
(EVIDENCE / 'ci-syntax.log').write_text(
    f'PASS YAML; jobs={len(config["jobs"])}; bash_run_blocks={runs}; '
    'two new runner scripts\nNot an online Actions result.\n')

public_inputs = patches + sorted((REPO / 'patches/libraw').glob('*.patch'))
public_inputs += [workflow] + [REPO / f'tools/native/{name}' for name in [
    'order_statistics.cpp', 'order_statistics_gpu.cpp',
    'run_order_statistics.sh', 'run_order_statistics_gpu.sh']]
assert len(public_inputs) == 84
(EVIDENCE / 'inputs.sha256').write_text(''.join(
    f'{sha(path)}  {path.relative_to(REPO)}\n' for path in public_inputs))
(EVIDENCE / 'production-sources.sha256').write_text(''.join(
    f'{digest}  engine/src/{name}\n' for name, digest in sorted(candidate.items())))

for name in ['focused-final', 'release', 'clang']:
    folder = ROOT / name
    assert (folder / 'baseline.csv').read_bytes() == (folder / 'candidate.csv').read_bytes()
    shutil.copy2(folder / 'candidate.csv', EVIDENCE / f'{name}-candidate.csv')
    (EVIDENCE / f'{name}-outputs.sha256').write_text(''.join(
        f'{sha(folder / (side + ".csv"))}  {name}/{side}.csv\n'
        for side in ['baseline', 'candidate']))

gpu = ROOT / 'gpu'
for math in ['auto', 'split']:
    for suffix in ['csv', 'bin']:
        assert (gpu / f'baseline-{math}.{suffix}').read_bytes() == (
            gpu / f'candidate-{math}.{suffix}').read_bytes()
    shutil.copy2(gpu / f'candidate-{math}.csv', EVIDENCE / f'gpu-{math}.csv')
(EVIDENCE / 'gpu-outputs.sha256').write_text(''.join(
    f'{sha(gpu / f"{side}-{math}.{suffix}")}  gpu/{side}-{math}.{suffix}\n'
    for math in ['auto', 'split'] for suffix in ['csv', 'bin']
    for side in ['baseline', 'candidate']))

spirv = {}
for name, folder in [('frozen', ROOT / 'frozen-host'), ('candidate', ROOT / 'host'),
                     ('arm64', ROOT / 'arm64')]:
    files = list(folder.rglob('*.spv'))
    assert len(files) == 56, (name, len(files))
    spirv[name] = {file.name: sha(file) for file in files}
    assert len(spirv[name]) == 56
    (EVIDENCE / f'spirv-{name}.sha256').write_text(''.join(
        f'{digest}  {file}\n' for file, digest in sorted(spirv[name].items())))
assert spirv['candidate'] == spirv['frozen'] == spirv['arm64']
libraries = list((ROOT / 'arm64').rglob('libspektralab_jni.so'))
assert len(libraries) == 1, libraries
(EVIDENCE / 'arm64.sha256').write_text(f'{sha(libraries[0])}  {libraries[0]}\n')
ndk = '/opt/android/ndk/27.2.12479018/toolchains/llvm/prebuilt/linux-x86_64/bin'
run(['g++', '--version'], EVIDENCE / 'toolchains.log')
run([f'{ndk}/clang++', '--version'], EVIDENCE / 'toolchains.log')
run(['cmake', '--version'], EVIDENCE / 'toolchains.log')
run(['ninja', '--version'], EVIDENCE / 'toolchains.log')
run(['uname', '-a'], EVIDENCE / 'toolchains.log')
for name in ['host', 'frozen-host', 'arm64']:
    run(['grep', '-nE', 'CMAKE_BUILD_TYPE:|CMAKE_CXX_COMPILER:|CMAKE_CXX_FLAGS_RELEASE:|'
         'ANDROID_ABI:|ANDROID_PLATFORM:|CMAKE_ANDROID_NDK:', str(ROOT / name / 'CMakeCache.txt')],
        EVIDENCE / 'toolchains.log')
shutil.copy2(ROOT / 'measure.py', EVIDENCE / 'measure.py')
shutil.copy2(ROOT / 'package_evidence.py', EVIDENCE / 'package_evidence.py')
(EVIDENCE / 'final-status.json').write_text(json.dumps({
    'author': 'GPT (OpenAI)', 'date': '2026-11-13', 'cloud': 'AOIDC',
    'baseline': 'pinned engine plus overlays 0001-0058',
    'candidate': 'pinned engine plus overlays 0001-0059',
    'engine_overlays': 59, 'libraw_overlays_unchanged': 20,
    'production_vectors_each': 1460,
    'toolchain_variants': ['GCC 11.4 Release', 'GCC 11.4 ASan/UBSan',
                           'NDK Clang 18.0.3 x86_64-unknown-linux-gnu'],
    'gpu_cases': 48, 'gpu_complete_payloads_identical': True,
    'spirv_files_identical': 56, 'arm64_full_build_steps': 189,
    'release_engine_build_steps_each': 85, 'input_manifest_rows': len(public_inputs),
    'ci_run_blocks': runs, 'online_actions_run': False,
    'phone_test': False, 'private_media_uploaded': False,
    'sanitizer_scope': 'changed production translation units plus test; support archives Release',
    'attempts': 'first ASan link needed support archives; corrected build passes'
}, indent=2) + '\n')
destination = ROOT / 'order-statistics-evidence.tar.gz'
with tarfile.open(destination, 'w:gz') as archive:
    archive.add(EVIDENCE, arcname='aoidc')
print(f'PASS evidence archive {sha(destination)}  {destination}')
print((EVIDENCE / 'replay-summary.txt').read_text(), end='')
print((EVIDENCE / 'ci-syntax.log').read_text(), end='')
