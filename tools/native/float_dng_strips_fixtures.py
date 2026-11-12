#!/usr/bin/env python3
"""Co-created with GPT (OpenAI). Independent TIFF strip layout and float parity.

TIFF 6.0, section 8: strips are indexed in image order; their file offsets need
not be consecutive or increasing. Repackage identical words without deriving
expected pixels from the production strip reader.
"""
import argparse
import json
from pathlib import Path
import random
import struct
import subprocess

import dng_float_linear_fixtures as original
from float_dng_linear_fixtures import entry, replace_tag
from float_dng_streaming_fixtures import wide_fixture


def check(condition, detail):
    if not condition:
        raise RuntimeError(str(detail))


def tag_values(data, number):
    at = entry(data, number)
    kind, count = struct.unpack_from('<HI', data, at + 2)
    check(kind == 4, (number, kind))
    offset = at + 8 if count == 1 else struct.unpack_from('<I', data, at + 8)[0]
    return list(struct.unpack_from('<' + 'I' * count, data, offset))


def store_values(data, number, values):
    at = entry(data, number)
    count = struct.unpack_from('<I', data, at + 4)[0]
    check(count == len(values), (number, count, values))
    offset = at + 8 if count == 1 else struct.unpack_from('<I', data, at + 8)[0]
    struct.pack_into('<' + 'I' * count, data, offset, *values)


def strips(source, destination, rows, order):
    data = bytearray(source.read_bytes())
    width, height = tag_values(data, 256)[0], tag_values(data, 257)[0]
    offset = tag_values(data, 273)[0]
    pixels = bytes(data[offset:offset + width * height * 12])
    chunks = [pixels[y * width * 12:min(height, y + rows) * width * 12]
              for y in range(0, height, rows)]
    replace_tag(data, 278, 4, [rows])
    replace_tag(data, 273, 4, [0] * len(chunks))
    replace_tag(data, 279, 4, [len(chunk) for chunk in chunks])
    physical_order = list(range(len(chunks)))
    if order == 'reverse':
        physical_order.reverse()
    elif order == 'shuffle':
        random.Random(0x53504b).shuffle(physical_order)
    offsets = [0] * len(chunks)
    for index in physical_order:
        # Leave holes to catch readers assuming adjacent strips.
        data.extend(b'gap!' * (1 + index % 3))
        offsets[index] = len(data)
        data.extend(chunks[index])
    store_values(data, 273, offsets)
    destination.write_bytes(data)
    return data


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('folder', type=Path)
    parser.add_argument('--old', type=Path, required=True)
    parser.add_argument('--new', type=Path, required=True)
    parser.add_argument('--stream', type=Path, required=True)
    parser.add_argument('--memory', action='store_true')
    args = parser.parse_args()
    args.folder.mkdir(parents=True, exist_ok=True)
    records, malformed, cancellations = [], [], []

    def run(binary, path, *options):
        output = args.folder / 'output.f32'
        record = json.loads(subprocess.check_output(
            [str(binary), str(path), str(output), *map(str, options)], text=True))
        check(record.get('open_status') == 0, (path, record))
        check(record['opened_unchanged'], (path, record))
        check(record['ok'] or record['result_unchanged'], (path, record))
        return record, output.read_bytes() if record['ok'] else None

    source = args.folder / 'source.dng'
    for orientation in range(1, 9):
        case, stored = original.fixture(args.folder, 'source', orientation=orientation,
            black=(.25, .375, .125), white=(4, 3, 2), neutral=(1 / 3, .875, 1))
        for options in ([], ['--wb', '2', '1', '1.5', '1', '--temperature', '6500']):
            reference, expected = run(args.old, source, *options)
            current, blob = run(args.new, source, *options)
            check(reference['ok'] and current['ok'] and blob == expected, (orientation, options))
            if not options:
                values = struct.unpack('<' + 'f' * (len(blob) // 4), blob)
                error = max(abs(a - b) for a, b in zip(values, original.oriented_reference(case, stored)))
                check(error < 2e-6, ('independent matrix/orientation reference', error))
            for rows in (1, 7, 16, 48, 49, 0xffffffff):
                for order in ('forward', 'reverse', 'shuffle'):
                    path = args.folder / 'strips.dng'
                    strips(source, path, rows, order)
                    record, blob = run(args.new, path, *options)
                    check(record['ok'] and blob == expected, (orientation, rows, order, options, record))
                    for key in ('source_black', 'source_white', 'source_neutral', 'camera_wb',
                                'source_wb', 'post_wb_matrix', 'width', 'height'):
                        check(record[key] == reference[key], (key, orientation, rows, order))
                    if rows < original.HEIGHT and orientation == 1 and not options:
                        old, _ = run(args.old, path)
                        check(not old['ok'] and old['support'] == 'UnsupportedFloat', ('baseline', old))
                    records.append(dict(orientation=orientation, rows=rows, order=order,
                                        custom_wb=bool(options), bytes=len(blob)))

    # An entire row of distinct float words, with subnormals and adjacent values.
    wide = wide_fixture(args.folder, 'wide', 2048, 64)
    reference, expected = run(args.old, wide)
    io_paths = [wide]
    for order in ('forward', 'reverse', 'shuffle'):
        path = args.folder / ('wide-' + order + '.dng')
        strips(wide, path, 7, order)
        record, blob = run(args.new, path)
        check(record['ok'] and blob == expected, ('edge words', order, record))
        io_paths.append(path)
    for path in io_paths:
        for mode in ('--short-read', '--replace-source'):
            subprocess.run([str(args.stream), str(path), mode, str(args.folder / 'io-copy.dng')], check=True)

    # SHORT/LONG offsets/counts are independent TIFF choices. Keep a small
    # fixture so all actual offsets fit a SHORT without truncation.
    seed = wide_fixture(args.folder, 'short-source', 32, 32)
    reference, expected = run(args.old, seed)
    short_layouts = 0
    for offset_kind in (3, 4):
        for count_kind in (3, 4):
            for row_kind in (3, 4):
                path = args.folder / 'short-fields.dng'
                data = strips(seed, path, 7, 'reverse')
                offsets, counts = tag_values(data, 273), tag_values(data, 279)
                replace_tag(data, 273, offset_kind, offsets)
                replace_tag(data, 279, count_kind, counts)
                replace_tag(data, 278, row_kind, [7])
                path.write_bytes(data)
                record, blob = run(args.new, path)
                check(record['ok'] and blob == expected, ('SHORT/LONG fields', record))
                short_layouts += 1

    # Retain the 1024-strip ceiling even when SHORT arrays occupy fewer bytes.
    for height, accepted in ((1024, True), (1025, False)):
        seed = wide_fixture(args.folder, 'limit-source', 64, height)
        path = args.folder / ('strips-limit-' + str(height) + '.dng')
        strips(seed, path, 1, 'reverse')
        record, blob = run(args.new, path, '--open-source', wide)
        check(record['ok'] == accepted, ('metadata cap', height, record))
        if accepted:
            _, baseline = run(args.old, seed)
            check(blob == baseline, ('1024 strips parity', record))
        else:
            check(record['metadata_unchanged'] and record['result_unchanged'], record)

    good = args.folder / 'wide-reverse.dng'
    success, _ = run(args.new, good)

    def rejection(name, change, support='UnsupportedFloat'):
        data = bytearray(good.read_bytes())
        change(data)
        path = args.folder / (name + '.dng')
        path.write_bytes(data)
        row, _ = run(args.new, path, '--open-source', wide)
        check(not row['ok'] and row['support'] == support and row['result_unchanged'], (name, row))
        if support == 'UnsupportedFloat':
            check(row['metadata_unchanged'] and row['decode_polls'] == 0, (name, row))
        malformed.append(dict(case=name, **row))

    rejection('zero-rows', lambda d: replace_tag(d, 278, 4, [0]))
    rejection('rational-rows', lambda d: replace_tag(d, 278, 5, [7]))
    rejection('rational-offsets', lambda d: replace_tag(d, 273, 5, [1] * len(tag_values(d, 273))))
    rejection('rational-counts', lambda d: replace_tag(d, 279, 5, [1] * len(tag_values(d, 279))))
    rejection('missing-strip', lambda d: replace_tag(d, 273, 4, tag_values(d, 273)[:-1]))
    rejection('extra-strip', lambda d: replace_tag(d, 273, 4, tag_values(d, 273) + [0]))
    rejection('missing-count', lambda d: replace_tag(d, 279, 4, tag_values(d, 279)[:-1]))
    rejection('short-last-count', lambda d: store_values(d, 279, tag_values(d, 279)[:-1] + [4]))
    rejection('outside-last', lambda d: store_values(d, 273, tag_values(d, 273)[:-1] + [len(d)]))
    rejection('metadata-overlap', lambda d: store_values(d, 273, [8] + tag_values(d, 273)[1:]))
    rejection('strip-array-overlap', lambda d: store_values(d, 273,
        [struct.unpack_from('<I', d, entry(d, 273) + 8)[0]] + tag_values(d, 273)[1:]))
    rejection('duplicate-strips', lambda d: store_values(d, 273, [tag_values(d, 273)[1]] + tag_values(d, 273)[1:]))
    rejection('partial-strip-overlap', lambda d: store_values(d, 273,
        [tag_values(d, 273)[1] + 4] + tag_values(d, 273)[1:]))
    rejection('truncated-file', lambda d: d.__delitem__(slice(len(d) - 4, None)))
    rejection('last-row-nan', lambda d: struct.pack_into('<f', d,
        tag_values(d, 273)[-1] + tag_values(d, 279)[-1] - 4, float('nan')), 'Supported')

    for phase in ('inspect', 'decode'):
        for stop in range(1, success[phase + '_polls'] + 1):
            row, _ = run(args.new, good, '--cancel-' + phase, stop)
            check(row['cancelled'] and row['result_unchanged'] and not row['ok'], (phase, stop, row))
            if phase == 'inspect':
                check(row['metadata_unchanged'], row)
            cancellations.append(dict(phase=phase, stop=stop))
        row, _ = run(args.new, good, '--cancel-' + phase, 1, '--throw-cancel')
        check(row['cancelled'] and row['result_unchanged'], row)

    if args.memory:
        seed = wide_fixture(args.folder, 'large-source', 4096, 3072)
        large = args.folder / 'large-strips.dng'
        strips(seed, large, 64, 'reverse')

    report = dict(byte_identical_cases=len(records) + 3 + 1 + short_layouts,
                  short_long_layout_cases=short_layouts,
                  compatibility_records=records, malformed=malformed,
                  cancellation_boundaries=len(cancellations),
                  io_mutations=2 * len(io_paths), independent_absolute_tolerance=2e-6,
                  maximum_strips=1024, float_headroom_and_signed_words_preserved=True,
                  large_fixture_generated=args.memory)
    (args.folder / 'strip-results.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({k: v for k, v in report.items() if k not in ('compatibility_records', 'malformed')}, sort_keys=True))
    print('malformed_rejected=' + str(len(malformed)))


if __name__ == '__main__':
    main()
