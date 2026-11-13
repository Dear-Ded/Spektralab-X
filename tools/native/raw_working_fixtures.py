#!/usr/bin/env python3
"""Co-created with GPT (OpenAI). Public integer LinearRaw fixtures, no photos.

Uses the existing synthetic DNG calibration tags and streams RGB16 rows. The
large fixture generator runs separately from every RSS measurement process.
"""
import argparse
from array import array
from pathlib import Path
import struct
import sys

from dng_float_precision_fixtures import FM, tag


def fixture(path, width, height, orientation=1, bayer=False):
    tags = {}
    channels = 1 if bayer else 3
    fields = {254: (4, [0]), 256: (4, [width]), 257: (4, [height]),
              258: (3, [16] * channels), 259: (3, [1]), 262: (3, [32803 if bayer else 34892]),
              273: (4, [0]), 274: (3, [orientation]), 277: (3, [channels]),
              278: (4, [height]), 279: (4, [width * height * channels * 2]),
              284: (3, [1]), 339: (3, [1] * channels),
              50829: (4, [0, 0, height, width]),
              50706: (1, [1, 4, 0, 0]), 50707: (1, [1, 4, 0, 0]),
              50713: (3, [1, 1]), 50714: (5, [0] * channels),
              50717: (4, [65535] * channels),
              50721: (10, [1, 0, 0, 0, 1, 0, 0, 0, 1]),
              50728: (5, [1, 1, 1]), 50778: (3, [23]), 50964: (10, FM),
              271: (2, b'GPT\0'), 272: (2, b'Working conversion fixture\0'),
              50708: (2, b'GPT Working conversion fixture\0')}
    if bayer:
        fields.update({33421: (3, [2, 2]), 33422: (1, [0, 1, 1, 2]),
                       50710: (1, [0, 1, 2]), 50711: (3, [1])})
    for number, (kind, values) in fields.items():
        tag(tags, number, kind, values)
    extra_offset = 8 + 2 + 12 * len(tags) + 4
    extra_size = sum((len(payload) + 1) // 2 * 2 for _, _, payload in tags.values() if len(payload) > 4)
    strip = extra_offset + extra_size
    tag(tags, 273, 4, [strip])
    header = bytearray(struct.pack('<2sHIH', b'II', 42, 8, len(tags)))
    extra = bytearray()
    for number, (kind, count, payload) in sorted(tags.items()):
        pointer = payload.ljust(4, b'\0') if len(payload) <= 4 else struct.pack('<I', extra_offset + len(extra))
        if len(payload) > 4:
            extra.extend(payload)
            if len(extra) % 2:
                extra.append(0)
        header.extend(struct.pack('<HHI4s', number, kind, count, pointer))
    header.extend(struct.pack('<I', 0))
    header.extend(extra)
    assert len(header) == strip
    with path.open('wb') as file:
        file.write(header)
        row = array('H', ((x * 59 + c * 179) % 65536 for x in range(width) for c in range(channels)))
        if sys.byteorder != 'little':
            row.byteswap()
        payload = row.tobytes()
        for _ in range(height):
            file.write(payload)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('folder', type=Path)
    parser.add_argument('--large', action='store_true')
    args = parser.parse_args()
    args.folder.mkdir(parents=True, exist_ok=True)
    for orientation in range(1, 9):
        fixture(args.folder / f'integer-orientation-{orientation}.dng', 97, 73, orientation)
        fixture(args.folder / f'integer-bayer-orientation-{orientation}.dng', 97, 73, orientation, bayer=True)
    if args.large:
        fixture(args.folder / 'integer-linear-24mp.dng', 6000, 4000)
        fixture(args.folder / 'integer-bayer-24mp.dng', 6000, 4000, bayer=True)
    print('Generated public integer LinearRaw/Bayer orientations 1..8' + (' and 24 MP' if args.large else ''))
