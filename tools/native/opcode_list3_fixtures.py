#!/usr/bin/env python3
"""Co-created with GPT (OpenAI). Add synthetic lens opcodes to integer DNGs."""
import argparse
from pathlib import Path
import struct


def opcode(number, data):
    return struct.pack('>4I', number, 0x01030000, 1, len(data)) + data


def lists():
    warp = opcode(1, struct.pack('>I20d', 3,
        .94, -.03, .01, 0, .002, -.001,
        .955, -.03, .01, 0, .002, -.001,
        .97, -.03, .01, 0, .002, -.001, .37, .62))
    vig = opcode(3, struct.pack('>7d', .5, .25, -.1, 0, 0, .37, .62))
    identity = opcode(1, struct.pack('>I8d', 1, 1, 0, 0, 0, 0, 0, .5, .5))
    return {'warp': struct.pack('>I', 1) + warp,
            'vignette': struct.pack('>I', 1) + vig,
            'combined': struct.pack('>I', 2) + warp + vig,
            'identity': struct.pack('>I', 1) + identity,
            'malformed': struct.pack('>I', 2) + warp + vig[:-1]}


def add_list(source, destination, ops, orientation):
    data = bytearray(source.read_bytes())
    if data[:4] != b'II*\0':
        raise ValueError('expected little-endian classic TIFF')
    old = struct.unpack_from('<I', data, 4)[0]
    count = struct.unpack_from('<H', data, old)[0]
    entries = {}
    for i in range(count):
        item = data[old + 2 + i * 12:old + 14 + i * 12]
        entries[struct.unpack_from('<H', item)[0]] = item
    entries[274] = struct.pack('<HHI4s', 274, 3, 1, struct.pack('<H', orientation) + b'\0\0')
    entries[51022] = struct.pack('<HHII', 51022, 7, len(ops), len(data))
    data.extend(ops)
    if len(data) % 2:
        data.append(0)
    struct.pack_into('<I', data, 4, len(data))
    data.extend(struct.pack('<H', len(entries)))
    for _, item in sorted(entries.items()):
        data.extend(item)
    data.extend(b'\0' * 4)
    destination.write_bytes(data)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('source', type=Path)
    parser.add_argument('destination', type=Path)
    args = parser.parse_args()
    args.destination.mkdir(parents=True, exist_ok=True)
    for source in ('integer-bayer', 'integer-linear'):
        for name, ops in lists().items():
            for orientation in (1, 6):
                add_list(args.source / (source + '.dng'),
                         args.destination / f'{source}-{name}-{orientation}.dng', ops, orientation)


if __name__ == '__main__':
    main()
