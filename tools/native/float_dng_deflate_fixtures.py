#!/usr/bin/env python3
"""Co-created with GPT (OpenAI). Independent predicted-byte encoder and oracle."""
import argparse
import hashlib
import json
from pathlib import Path
import random
import struct
import subprocess
import zlib

import dng_float_linear_fixtures as original
from float_dng_linear_fixtures import entry, replace_tag
from float_dng_streaming_fixtures import wide_fixture
from float_dng_strips_fixtures import tag_values


def check(condition, detail):
    if not condition:
        raise RuntimeError(str(detail))


def predicted(row):
    planes = b''.join(row[n::4] for n in (3, 2, 1, 0))
    # Encode differences from the unchanged input, including plane boundaries.
    return planes[:3] + bytes((planes[i] - planes[i - 3]) & 255
                             for i in range(3, len(planes)))


def repack(source, destination, rows, order='forward', level=6, damage=None, damage_strip=0):
    data = source.read_bytes()
    count = struct.unpack_from('<H', data, 8)[0]
    fields = {}
    sizes = {1: 1, 2: 1, 3: 2, 4: 4, 5: 8, 7: 1, 10: 8}
    for i in range(count):
        at = 10 + i * 12
        tag, kind, length = struct.unpack_from('<HHI', data, at)
        size = length * sizes[kind]
        start = at + 8 if size <= 4 else struct.unpack_from('<I', data, at + 8)[0]
        fields[tag] = (kind, length, data[start:start + size])
    width = struct.unpack('<I', fields[256][2])[0]
    height = struct.unpack('<I', fields[257][2])[0]
    start = struct.unpack('<I', fields[273][2])[0]
    pixels = data[start:start + width * height * 12]
    chunks = []
    for y in range(0, height, rows):
        plain = b''.join(predicted(pixels[r * width * 12:(r + 1) * width * 12])
                         for r in range(y, min(y + rows, height)))
        if damage and y // rows == damage_strip:
            chunks.append(damage(plain))
        else:
            chunks.append(zlib.compress(plain, level))

    def field(tag, kind, values):
        fields[tag] = (kind, len(values), original.payload(kind, values))

    field(259, 3, [8])
    field(317, 3, [3])
    field(278, 4, [rows])
    field(273, 4, [0] * len(chunks))
    field(279, 4, list(map(len, chunks)))
    # Assemble independently from the production TIFF parser, with metadata
    # before all strips and independent, non-monotonic physical placement.
    base = 8 + 2 + 12 * len(fields) + 4
    extra, entries, offsets_at = bytearray(), [], None
    for tag, (kind, length, payload) in sorted(fields.items()):
        if len(payload) <= 4:
            stored = payload.ljust(4, b'\0')
            if tag == 273:
                offsets_at = 10 + len(entries) * 12 + 8
        else:
            stored = struct.pack('<I', base + len(extra))
            if tag == 273:
                offsets_at = base + len(extra)
            extra.extend(payload)
            if len(extra) % 2:
                extra.append(0)
        entries.append(struct.pack('<HHI4s', tag, kind, length, stored))
    result = bytearray(struct.pack('<2sHIH', b'II', 42, 8, len(fields)) +
                       b''.join(entries) + struct.pack('<I', 0) + extra)
    indices = list(range(len(chunks)))
    if order == 'reverse':
        indices.reverse()
    elif order == 'shuffle':
        random.Random(0x44464c).shuffle(indices)
    offsets = [0] * len(chunks)
    for index in indices:
        result.extend(b'gap!' * (1 + index % 3))
        offsets[index] = len(result)
        result.extend(chunks[index])
    struct.pack_into('<' + 'I' * len(offsets), result, offsets_at, *offsets)
    destination.write_bytes(result)
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('folder', type=Path)
    parser.add_argument('--old', type=Path, required=True)
    parser.add_argument('--new', type=Path, required=True)
    parser.add_argument('--dispatcher', type=Path, required=True)
    parser.add_argument('--stream', type=Path, required=True)
    parser.add_argument('--unpack', type=Path, required=True)
    parser.add_argument('--memory', action='store_true')
    args = parser.parse_args()
    args.folder.mkdir(parents=True, exist_ok=True)
    records, malformed, cancellation = [], [], []
    maximum_error = 0
    unpacked_cases = 0

    def independent_unpack(source, path):
        nonlocal unpacked_cases
        data = source.read_bytes()
        start = struct.unpack_from('<I', data, entry(data, 273) + 8)[0]
        width = struct.unpack_from('<I', data, entry(data, 256) + 8)[0]
        height = struct.unpack_from('<I', data, entry(data, 257) + 8)[0]
        output = args.folder / 'unpacked.f32'
        row = json.loads(subprocess.check_output([str(args.unpack), str(path), str(output)], text=True))
        check(row['open'] == row['unpack'] == 0 and row['float'] and
              row['capabilities'] & 64 and output.read_bytes() == data[start:start + width * height * 12],
              ('independent LibRaw sample words', row))
        unpacked_cases += 1

    def run(binary, path, *options):
        output = args.folder / 'output.f32'
        record = json.loads(subprocess.check_output(
            [str(binary), str(path), str(output), *map(str, options)], text=True))
        check(record.get('open_status') == 0, (path, record))
        check(record.get('opened_unchanged', record.get('raw_unchanged')), record)
        check(record['ok'] or record.get('result_unchanged', record.get('unchanged')), record)
        return record, output.read_bytes() if record['ok'] else None

    source, path = args.folder / 'source.dng', args.folder / 'deflate.dng'
    for orientation in range(1, 9):
        case, stored = original.fixture(args.folder, 'source', orientation=orientation,
            black=(.25, .375, .125), white=(4, 3, 2), neutral=(1 / 3, .875, 1))
        for options in ([], ['--wb', '2', '1', '1.5', '1', '--temperature', '6500']):
            old, expected = run(args.old, source, *options)
            check(old['ok'], old)
            if not options:
                values = struct.unpack('<' + 'f' * (len(expected) // 4), expected)
                error = max(abs(a - b) for a, b in zip(values, original.oriented_reference(case, stored)))
                maximum_error = max(maximum_error, error)
                check(error < 2e-6, ('independent oracle', error))
            for rows in (1, 7, 48, 49, 0xffffffff):
                for order in ('forward', 'reverse', 'shuffle'):
                    for level in (0, 6):
                        repack(source, path, rows, order, level)
                        record, blob = run(args.new, path, *options)
                        check(record['ok'] and blob == expected, (orientation, rows, order, level, options, record))
                        for key in ('source_black', 'source_white', 'source_neutral', 'camera_wb',
                                    'source_wb', 'post_wb_matrix', 'width', 'height'):
                            check(record[key] == old[key], (key, record, old))
                        if rows == 7 and order == 'reverse' and level == 6:
                            if not options:
                                independent_unpack(source, path)
                            baseline, _ = run(args.old, path)
                            check(not baseline['ok'] and baseline['support'] == 'UnsupportedFloat', baseline)
                            dispatch, dispatched = run(args.dispatcher, path, *options)
                            check(dispatch['ok'] and dispatch['mode'] == 'float_linear_v1' and
                                  dispatch['exact_pixels'] and dispatch['exact_metadata'] and dispatched == expected,
                                  dispatch)
                        records.append(dict(orientation=orientation, rows=rows, order=order,
                                            level=level, custom_wb=bool(options)))

    # Varied widths exercise byte-plane boundaries, subnormals, signed words,
    # incomplete final strips, and both smaller/larger than 16 KiB input reads.
    for width, height in ((32, 32), (33, 49), (65, 48), (2048, 64), (8192, 32)):
        seed = wide_fixture(args.folder, 'wide-source', width, height)
        _, expected = run(args.old, seed)
        for level in (0, 1, 9):
            repack(seed, path, 7, 'reverse', level)
            record, blob = run(args.new, path)
            check(record['ok'] and blob == expected, ('edge words', width, height, level, record))
            independent_unpack(seed, path)
            records.append(dict(width=width, height=height, level=level))

    # Cancellation includes zlib input refills and the final checksum/trailer.
    seed = wide_fixture(args.folder, 'io-source', 2048, 64)
    repack(seed, path, 7, 'reverse', 0)
    success, _ = run(args.new, path)
    for phase in ('inspect', 'decode'):
        for stop in range(1, success[phase + '_polls'] + 1):
            row, _ = run(args.new, path, '--cancel-' + phase, stop)
            check(row['cancelled'] and row['result_unchanged'] and not row['ok'], (phase, stop, row))
            cancellation.append(dict(phase=phase, stop=stop))
    for mode in ('--short-read', '--replace-source'):
        subprocess.run([str(args.stream), str(path), mode, str(args.folder / 'io-copy.dng')], check=True)

    dispatch, _ = run(args.dispatcher, path)
    dispatch_cancelled = 0
    for stop in range(1, dispatch['polls'] + 1):
        row, _ = run(args.dispatcher, path, '--cancel', stop)
        check(row['cancelled'] and not row['ok'] and row['unchanged'] and
              row['float_metadata_unchanged'] and row['mode'] == 'float_linear_v1', row)
        dispatch_cancelled += 1

    small = wide_fixture(args.folder, 'short-fields-source', 32, 32)
    _, expected = run(args.old, small)
    for offset_kind in (3, 4):
        for count_kind in (3, 4):
            data = repack(small, path, 7, 'reverse')
            offsets, counts = tag_values(data, 273), tag_values(data, 279)
            replace_tag(data, 273, offset_kind, offsets)
            replace_tag(data, 279, count_kind, counts)
            path.write_bytes(data)
            row, blob = run(args.new, path)
            check(row['ok'] and blob == expected, ('SHORT/LONG compressed', row))
            records.append(dict(offset_kind=offset_kind, count_kind=count_kind))
    for height, accepted in ((1024, True), (1025, False)):
        limit_seed = wide_fixture(args.folder, 'limit-source', 64, height)
        repack(limit_seed, path, 1, 'reverse')
        row, blob = run(args.new, path, '--open-source', seed)
        check(row['ok'] == accepted, ('compressed strip cap', height, row))
        if accepted:
            _, expected = run(args.old, limit_seed)
            check(blob == expected, ('compressed cap parity', row))
        else:
            check(row['result_unchanged'] and row['metadata_unchanged'], row)

    def rejection(name, damage=None, mutate=None, support='Supported', damage_strip=0):
        target = args.folder / (name + '.dng')
        data = repack(seed, target, 7, 'reverse', damage=damage, damage_strip=damage_strip)
        if mutate:
            mutate(data)
            target.write_bytes(data)
        row, _ = run(args.new, target, '--open-source', seed)
        check(not row['ok'] and row['support'] == support and row['result_unchanged'], (name, row))
        if support == 'UnsupportedFloat':
            check(row['metadata_unchanged'] and row['decode_polls'] == 0, row)
        else:
            dispatch, _ = run(args.dispatcher, target)
            check(dispatch['mode'] == 'float_linear_v1' and not dispatch['ok'] and
                  dispatch['unchanged'] and dispatch['float_metadata_unchanged'], dispatch)
        malformed.append(dict(case=name, **row))

    rejection('truncated-zlib', damage=lambda p: zlib.compress(p)[:-1])
    rejection('missing-trailer', damage=lambda p: zlib.compress(p)[:-4])
    rejection('bad-checksum', damage=lambda p: zlib.compress(p)[:-1] + bytes([zlib.compress(p)[-1] ^ 1]))
    rejection('bad-header', damage=lambda p: b'xx' + zlib.compress(p)[2:])
    rejection('short-output', damage=lambda p: zlib.compress(p[:-4]))
    rejection('excess-output', damage=lambda p: zlib.compress(p + b'\0'))
    rejection('trailing-byte', damage=lambda p: zlib.compress(p) + b'\0')
    rejection('second-stream', damage=lambda p: zlib.compress(p) + zlib.compress(b''))
    rejection('empty-output', damage=lambda p: zlib.compress(b''))
    compressor = zlib.compressobj(zdict=b'DNG dictionary is unsupported')
    dictionary = compressor.compress(b'\0' * (2048 * 7 * 12)) + compressor.flush()
    rejection('dictionary', damage=lambda p: dictionary)
    rejection('final-strip-checksum', damage=lambda p: zlib.compress(p)[:-1] +
              bytes([zlib.compress(p)[-1] ^ 1]), damage_strip=9)
    rejection('final-strip-excess', damage=lambda p: zlib.compress(p + b'\0'), damage_strip=9)
    rejection('wrong-predictor', mutate=lambda d: replace_tag(d, 317, 3, [2]), support='UnsupportedFloat')
    rejection('long-predictor', mutate=lambda d: replace_tag(d, 317, 4, [3]), support='UnsupportedFloat')
    rejection('zero-compressed-count', mutate=lambda d: replace_tag(d, 279, 4, [0] * 10),
              support='UnsupportedFloat')

    if args.memory:
        large = wide_fixture(args.folder, 'large-source', 4096, 3072)
        repack(large, args.folder / 'large-deflate.dng', 64, 'reverse')
    summary = dict(passed=True, byte_identical_cases=len(records),
                   independent_maximum_absolute_error=maximum_error, absolute_tolerance=2e-6,
                   cancellation_boundaries=len(cancellation), malformed_rejected=len(malformed),
                   dispatcher_cancellation_boundaries=dispatch_cancelled,
                   independent_libraw_word_cases=unpacked_cases,
                   maximum_strips=1024, io_mutations=2, large_fixture_generated=args.memory)
    (args.folder / 'results.json').write_text(json.dumps(dict(summary=summary,
        cases=records, malformed=malformed, cancellation=cancellation), indent=2) + '\n')
    print(json.dumps(summary, sort_keys=True))


if __name__ == '__main__':
    main()
