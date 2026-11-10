#!/usr/bin/env python3
"""Synthetic frozen-unpack/row-stream byte parity and isolated process RSS."""
import argparse
import json
from pathlib import Path
import struct
import subprocess

import dng_float_linear_fixtures as original


def wide_fixture(folder, name, width, height):
    # Retain the same valid metadata, relocating bounded metadata before a
    # streamed pixel payload. Never construct a full-size Python pixel list.
    original.fixture(folder, 'template')
    template = (folder / 'template.dng').read_bytes()
    count = struct.unpack_from('<H', template, 8)[0]
    fields = []
    sizes = {1: 1, 2: 1, 3: 2, 4: 4, 5: 8, 7: 1, 10: 8}
    for index in range(count):
        at = 10 + index * 12
        tag, kind, length = struct.unpack_from('<HHI', template, at)
        size = length * sizes[kind]
        value = template[at + 8:at + 8 + size] if size <= 4 else template[
            struct.unpack_from('<I', template, at + 8)[0]:][:size]
        if tag == 256: value = struct.pack('<I', width)
        elif tag in (257, 278): value = struct.pack('<I', height)
        elif tag == 279: value = struct.pack('<I', width * height * 12)
        elif tag == 50829: value = struct.pack('<IIII', 0, 0, height, width)
        fields.append((tag, kind, length, value))
    base = 8 + 2 + count * 12 + 4
    extra, entries = bytearray(), []
    for tag, kind, length, value in fields:
        if len(value) <= 4:
            stored = value.ljust(4, b'\0')
        else:
            stored = struct.pack('<I', base + len(extra))
            extra.extend(value)
            if len(extra) % 2: extra.append(0)
        entries.append((tag, kind, length, stored))
    pixel_offset = base + len(extra)
    entries = [(tag, kind, length, struct.pack('<I', pixel_offset) if tag == 273 else value)
               for tag, kind, length, value in entries]
    path = folder / (name + '.dng')
    values = [((x // 8) % 12) * .2 - .125 + c * .015 for x in range(width) for c in range(3)]
    # Literal finite edge cases must survive an LE bit read, including signed
    # zero, subnormals, a normal threshold, and adjacent representable samples.
    row = bytearray(struct.pack('<' + 'f' * len(values), *values))
    for index, bits in enumerate((0x80000000, 1, 0x80000001, 0x00800000, 0x3f000001, 0x3f000002)):
        struct.pack_into('<I', row, index * 4, bits)
    with path.open('wb') as file:
        file.write(struct.pack('<2sHIH', b'II', 42, 8, count))
        for entry in entries: file.write(struct.pack('<HHI4s', *entry))
        file.write(struct.pack('<I', 0))
        file.write(extra)
        for _ in range(height): file.write(row)
    return path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('folder', type=Path)
    parser.add_argument('--old', type=Path, required=True)
    parser.add_argument('--new', type=Path, required=True)
    parser.add_argument('--old-stream', type=Path, required=True)
    parser.add_argument('--new-stream', type=Path, required=True)
    parser.add_argument('--memory', action='store_true')
    args = parser.parse_args()
    args.folder.mkdir(parents=True, exist_ok=True)
    names = []
    configurations = [('max2', {}), ('max4', {'maximum': 4}),
        ('fractional', {'black': (.25, .25, .25), 'white': (4, 4, 4)}),
        ('per-plane', {'black': (.25, .375, .125), 'white': (4, 3, 2)}),
        ('rational-neutral', {'neutral': (1 / 3, .875, 1)}),
        ('nonunit-green', {'neutral': (.25, .625, 1)})]
    configurations += [('orientation-' + str(i), {'orientation': i}) for i in range(2, 9)]
    for name, options in configurations:
        original.fixture(args.folder, name, **options)
        names.append(args.folder / (name + '.dng'))
    wide = wide_fixture(args.folder, 'wide-edge-words', 2048, 64)
    names.append(wide)
    comparisons = []
    for path in names:
        # Serialize every public decoded metadata field, independently of the
        # older numerical probe's deliberately smaller JSON diagnostics.
        old_metadata = subprocess.check_output([str(args.old_stream), str(path), '--metadata'])
        new_metadata = subprocess.check_output([str(args.new_stream), str(path), '--metadata'])
        assert old_metadata == new_metadata, (path, old_metadata, new_metadata)
        for options in ([], ['--wb', '2', '1', '1.5', '1', '--temperature', '6500'],
                        ['--wb', '1.25', '1', '.75', '1', '--temperature', '4000']):
            blobs, metadata = [], []
            for label, binary in (('old', args.old), ('new', args.new)):
                output = args.folder / (label + '.f32')
                record = json.loads(subprocess.check_output([str(binary), str(path), str(output), *options], text=True))
                assert record.get('ok') and record.get('support') == 'Supported', (path, options, label, record)
                blobs.append(output.read_bytes())
                metadata.append({k: v for k, v in record.items() if k != 'decode_polls'})
            assert blobs[0] == blobs[1] and metadata[0] == metadata[1], (path, options, metadata)
            comparisons.append(dict(case=path.name, options=options, bytes=len(blobs[0])))
    for mode in ('--short-read', '--replace-source'):
        subprocess.run([str(args.new_stream), str(wide), mode, str(args.folder / 'io-copy.dng')], check=True)
    memory = {}
    if args.memory:
        large = wide_fixture(args.folder, 'large-synthetic', 4096, 3072)
        for label, binary in (('old', args.old_stream), ('new', args.new_stream)):
            memory[label] = json.loads(subprocess.check_output([str(binary), str(large), '--memory'], text=True))
        assert memory['old']['hash64'] == memory['new']['hash64']
        assert memory['old']['width'] == memory['new']['width'] and memory['old']['height'] == memory['new']['height']
        # Keep observed RSS as evidence, rather than asserting a platform-specific delta.
    report = dict(byte_identical_cases=len(comparisons), comparisons=comparisons, memory=memory)
    (args.folder / 'streaming-results.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(dict(byte_identical_cases=len(comparisons), memory=memory)))


if __name__ == '__main__':
    main()
