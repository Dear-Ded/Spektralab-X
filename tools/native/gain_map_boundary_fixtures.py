#!/usr/bin/env python3
"""Co-created with GPT (OpenAI). File-level synthetic OpcodeList2 boundary cases."""
import argparse
from pathlib import Path
import struct
import subprocess


def opcode(number, payload=b''):
    return struct.pack('>4I', number, 0x01030000, 1, len(payload)) + payload


def gain(left=0, row_pitch=1, col_pitch=1):
    payload = struct.pack('>10I4dIf', 0, left, 48, 64, 0, 1, row_pitch, col_pitch, 1, 1,
                          1, 1, 0, 0, 1, 2)
    return opcode(9, payload)


def write_dng(path, ops, repeat=(1, 1)):
    tags = {}

    def tag(number, kind, values):
        if kind in (1, 2, 7):
            payload = bytes(values)
        elif kind in (3, 4):
            payload = struct.pack('<' + ('H' if kind == 3 else 'I') * len(values), *values)
        else:
            payload = b''.join(struct.pack('<ii' if kind == 10 else '<II', n, 1) for n in values)
        tags[number] = (kind, len(payload) // {1: 1, 2: 1, 3: 2, 4: 4, 5: 8, 7: 1, 10: 8}[kind], payload)

    for number, kind, values in [
        (254, 4, [0]), (256, 4, [64]), (257, 4, [48]), (258, 3, [16]),
        (259, 3, [1]), (262, 3, [32803]), (273, 4, [0]), (277, 3, [1]),
        (278, 4, [48]), (279, 4, [64 * 48 * 2]), (284, 3, [1]), (339, 3, [1]),
        (33421, 3, [2, 2]), (33422, 1, [0, 1, 1, 2]),
        (50706, 1, [1, 4, 0, 0]), (50707, 1, [1, 3, 0, 0]),
        (50710, 1, [0, 1, 2]), (50711, 3, [1]), (50713, 3, repeat),
        (50714, 5, [100]), (50717, 4, [4095]),
        (50721, 10, [1, 0, 0, 0, 1, 0, 0, 0, 1]), (50728, 5, [1, 1, 1]),
        (50778, 3, [23]), (50829, 4, [0, 0, 48, 64]),
        (271, 2, b'GPT\0'), (272, 2, b'GainMap boundary synthetic\0'),
        (50708, 2, b'GPT GainMap boundary synthetic\0'), (51009, 7, ops),
    ]:
        tag(number, kind, values)
    strip = 8 + 2 + 12 * len(tags) + 4
    tag(273, 4, [strip])
    result = bytearray(struct.pack('<2sHIH', b'II', 42, 8, len(tags)))
    extra = bytearray(struct.pack('<' + 'H' * (64 * 48), *([1000] * (64 * 48))))
    for number, (kind, count, payload) in sorted(tags.items()):
        if len(payload) <= 4:
            pointer = payload.ljust(4, b'\0')
        else:
            pointer = struct.pack('<I', strip + len(extra))
            extra.extend(payload)
            if len(extra) % 2:
                extra.append(0)
        result.extend(struct.pack('<HHI4s', number, kind, count, pointer))
    result.extend(struct.pack('<I', 0))
    result.extend(extra)
    path.write_bytes(result)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('folder', type=Path)
    parser.add_argument('--probe', required=True, type=Path)
    parser.add_argument('--observe', action='store_true')
    args = parser.parse_args()
    args.folder.mkdir(parents=True, exist_ok=True)
    prefix = gain() + opcode(0x7ffffffe) * 1023
    cases = [
        ('valid-single', struct.pack('>I', 1) + gain(), (1, 1), 64 * 48),
        ('missing-1025-header', struct.pack('>I', 1025) + prefix, (1, 1), 0),
        ('truncated-1025-payload', struct.pack('>I', 1025) + prefix + gain()[:17], (1, 1), 0),
        ('max-count', struct.pack('>I', 0xffffffff) + prefix, (1, 1), 0),
        ('max-short-repeat', struct.pack('>I', 1) + gain(), (65535, 65535), 64 * 48),
        ('large-column-pitch', struct.pack('>I', 1) + gain(left=1, col_pitch=0xffffffff), (1, 1), 48),
        ('large-row-pitch', struct.pack('>I', 1) + gain(row_pitch=0xffffffff), (1, 1), 64),
    ]
    failures = 0
    for name, ops, repeat, expected_changed in cases:
        path = args.folder / (name + '.dng')
        write_dng(path, ops, repeat)
        output = subprocess.check_output([str(args.probe), '--file', str(path)], text=True)
        count = int(output.split('changed_pixels=')[1].split()[0])
        okay = count == expected_changed
        if repeat != (1, 1):
            okay = okay and 'black-dim=1x1' in output
        failures += not okay
        print(name + ': ' + ('PASS ' if okay else 'FAIL ') + output.strip())
    print('file boundary failures=' + str(failures))
    if failures and not args.observe:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
