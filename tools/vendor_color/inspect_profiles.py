#!/usr/bin/env python3
"""Offline profile inspection. No manufacturer rendering or application import implied."""

import argparse
import hashlib
import json
import math
from pathlib import Path
import struct
import xml.etree.ElementTree as ET


MAX_FILE = 16 * 1024 * 1024
DCP_TAGS = {
    50708: "UniqueCameraModel", 50721: "ColorMatrix1", 50722: "ColorMatrix2",
    50723: "CameraCalibration1", 50724: "CameraCalibration2",
    50778: "CalibrationIlluminant1", 50779: "CalibrationIlluminant2",
    50931: "CameraCalibrationSig", 50932: "ProfileCalibrationSig",
    50936: "ProfileName", 50937: "ProfileHueSatMapDims",
    50938: "ProfileHueSatMapData1", 50939: "ProfileHueSatMapData2",
    50940: "ProfileToneCurve", 50941: "ProfileEmbedPolicy",
    50942: "ProfileCopyright", 50964: "ForwardMatrix1", 50965: "ForwardMatrix2",
    50981: "ProfileLookTableDims", 50982: "ProfileLookTableData",
    51107: "ProfileHueSatMapEncoding", 51108: "ProfileLookTableEncoding",
    51109: "BaselineExposureOffset", 51110: "DefaultBlackRender",
}
TYPE_INFO = {
    1: (1, "B"), 2: (1, None), 3: (2, "H"), 4: (4, "I"),
    5: (8, "II"), 6: (1, "b"), 7: (1, "B"), 8: (2, "h"),
    9: (4, "i"), 10: (8, "ii"), 11: (4, "f"), 12: (8, "d"),
    13: (4, "I"),
}


def sha(data):
    return hashlib.sha256(data).hexdigest()


def span(data, offset, length):
    if offset < 0 or length < 0 or offset + length > len(data):
        raise ValueError(f"Truncated payload: offset={offset}, length={length}")
    return data[offset:offset + length]


def fingerprint(data):
    if len(data) > MAX_FILE:
        raise ValueError("Profile exceeds the inspection size limit")
    return {"bytes": len(data), "sha256": sha(data)}


def byte_partition(data, ranges):
    """Account for every byte without declaring unknown bytes understood."""
    covered = bytearray(len(data))
    for start, length in ranges:
        span(data, start, length)
        covered[start:start + length] = b"\1" * length
    unknown = []
    i = 0
    while i < len(data):
        if covered[i]:
            i += 1
            continue
        start = i
        while i < len(data) and not covered[i]:
            i += 1
        unknown.append({"offset": start, "bytes": i - start,
                        "sha256": sha(data[start:i])})
    return {"decodedOrFramedBytes": sum(covered), "uninterpretedRanges": unknown}


def parse_dcp(data):
    result = {"format": "DCP/TIFF", **fingerprint(data)}
    span(data, 0, 8)
    if data[:2] not in (b"II", b"MM"):
        raise ValueError("Not a classic TIFF/DCP byte order")
    endian = "<" if data[:2] == b"II" else ">"
    magic, offset = struct.unpack(endian + "HI", data[2:8])
    if magic not in (42, 0x4352):
        raise ValueError("Unsupported classic TIFF/DCP magic")
    result.update(byteOrder=data[:2].decode(), magic=magic)
    entries, framed, seen = [], [(0, 8)], set()
    while offset:
        if offset in seen or len(seen) >= 32:
            raise ValueError("Cyclic or excessive IFD chain")
        seen.add(offset)
        count = struct.unpack(endian + "H", span(data, offset, 2))[0]
        directory = span(data, offset, 2 + 12 * count + 4)
        framed.append((offset, len(directory)))
        tags = set()
        for index in range(count):
            entry_offset = offset + 2 + 12 * index
            tag, typ, n, field = struct.unpack(endian + "HHII", span(data, entry_offset, 12))
            if tag in tags or typ not in TYPE_INFO:
                raise ValueError("Duplicate tag or unsupported TIFF field type")
            tags.add(tag)
            size, fmt = TYPE_INFO[typ]
            length = n * size
            payload_offset = entry_offset + 8 if length <= 4 else field
            raw = span(data, payload_offset, length)
            if typ == 2:
                values = raw.rstrip(b"\0").decode("utf-8", errors="replace")
            else:
                values = list(struct.iter_unpack(endian + fmt, raw))
                if typ in (5, 10):
                    if any(b == 0 for _, b in values):
                        raise ValueError("Zero rational denominator")
                    values = [a / b for a, b in values]
                else:
                    values = [v[0] for v in values]
                if any(not math.isfinite(v) for v in values):
                    raise ValueError("Non-finite TIFF numeric value")
            name = DCP_TAGS.get(tag, f"UnknownTag{tag}")
            entries.append({"tag": tag, "name": name, "type": typ, "count": n,
                            "ifdOffset": offset, "entryOffset": entry_offset,
                            "payloadOffset": payload_offset, "payloadBytes": length,
                            "payloadSha256": sha(raw), "values": values})
            if tag in DCP_TAGS:
                framed.append((payload_offset, length))
        offset = struct.unpack(endian + "I", directory[-4:])[0]
    if not seen:
        raise ValueError("Missing profile directory")
    if len(seen) != 1:
        raise ValueError("Multiple IFDs require a separate semantic contract")
    by_name = {e["name"]: e for e in entries}
    for dim_name, data_names in [
        ("ProfileHueSatMapDims", ["ProfileHueSatMapData1", "ProfileHueSatMapData2"]),
        ("ProfileLookTableDims", ["ProfileLookTableData"]),
    ]:
        dims = by_name.get(dim_name)
        tables = [by_name[k] for k in data_names if k in by_name]
        if tables and dims is None:
            raise ValueError("Table without dimensions")
        if dims:
            h, s, v = table_dimensions(dims["values"])
            if not tables or any(t["type"] != 11 or t["count"] != h * s * v * 3 for t in tables):
                raise ValueError("DCP table shape/type mismatch")
    for name in ["ColorMatrix1", "ColorMatrix2", "ForwardMatrix1", "ForwardMatrix2"]:
        if name in by_name and by_name[name]["count"] != 9:
            raise ValueError("Only three-channel camera matrices are supported")
    tone = by_name.get("ProfileToneCurve")
    if tone:
        values = tone["values"]
        if len(values) < 4 or len(values) % 2 or any(
            values[i] >= values[i + 2] for i in range(0, len(values) - 2, 2)
        ):
            raise ValueError("Invalid tone-curve abscissae")
    result.update(entries=entries, byteAccounting=byte_partition(data, framed),
                  renderingStatus="metadata-only; complete DCP renderer required")
    return result


def table_dimensions(dims):
    if len(dims) != 3 or any(type(x) is not int for x in dims):
        raise ValueError("Expected three integer table dimensions")
    h, s, v = dims
    if h < 1 or s < 2 or v < 1 or h * s * v > MAX_FILE // 12:
        raise ValueError("Unsupported or excessive table dimensions")
    return h, s, v


def sample_hsv_table(dims, values, hue, saturation, value, encoding=0):
    """Return interpolated (hue shift degrees, saturation scale, value scale).

    A table component only: hue is degrees, S/V in [0,1]. The caller owns RGB
    conversion, dual-illuminant blending, applying scales, and rendering order.
    For encoding=1 and V>1 only the *lookup coordinate* uses the sRGB transfer.
    No CUBE tetrahedral interpolation or implicit RGB clipping is involved.
    """
    h, s, v = table_dimensions(dims)
    if len(values) != h * s * v * 3 or encoding not in (0, 1):
        raise ValueError("Invalid table payload or encoding")
    if not all(math.isfinite(x) for x in (hue, saturation, value)):
        raise ValueError("Non-finite sample")
    if not (0 <= saturation <= 1 and 0 <= value <= 1):
        raise ValueError("Table component expects bounded S/V")
    # A negative subnormal can round hue % 360 to exactly 360. Normalize the
    # scaled cyclic coordinate too, so it can never address table row H.
    hs, ss = ((hue % 360) * h / 360) % h, saturation * (s - 1)
    encoded = (12.92 * value if value <= 0.0031308 else 1.055 * value ** (1 / 2.4) - 0.055)
    vs = (encoded if encoding else value) * (v - 1)
    h0, s0, v0 = int(hs), min(int(ss), s - 2), min(int(vs), max(v - 2, 0))
    hf, sf, vf = hs - h0, ss - s0, vs - v0
    out = [0.0, 0.0, 0.0]
    for hi, hw in [(h0, 1 - hf), ((h0 + 1) % h, hf)]:
        for si, sw in [(s0, 1 - sf), (s0 + 1, sf)]:
            for vi, vw in ([(0, 1.0)] if v == 1 else [(v0, 1 - vf), (v0 + 1, vf)]):
                start = ((vi * h + hi) * s + si) * 3
                for channel in range(3):
                    out[channel] += values[start + channel] * hw * sw * vw
    return out


def parse_np3(data):
    result = {"format": "Nikon NP3 Flexible Color 0310", **fingerprint(data)}
    span(data, 0, 392)
    if data[:4] != b"NCP\0" or data[12:16] != b"0310":
        raise ValueError("Unsupported NP3 variant; retain the original bytes")
    ranges = [(0, 4), (12, 4), (24, 19), (0x185, 3)]
    decoded = {"name": data[24:43].split(b"\0", 1)[0].decode("latin1")}
    for name, offset, scale in [
        ("sharpning", 0x52, 4), ("midRangeSharpning", 0xf2, 4), ("clarity", 0x5c, 4),
        ("contrast", 0x110, 1), ("highlights", 0x11a, 1), ("shadows", 0x124, 1),
        ("whiteLevel", 0x12e, 1), ("blackLevel", 0x138, 1), ("saturation", 0x142, 1),
    ]:
        decoded[name] = (data[offset] - 128) / scale
        ranges.append((offset, 1))
    decoded["colorBlender"] = {}
    for index, name in enumerate(["red", "orange", "yellow", "green", "cyan", "blue", "purple", "magenta"]):
        offset = 0x14c + index * 3
        decoded["colorBlender"][name] = dict(zip(["hue", "chroma", "brightness"], [b - 128 for b in data[offset:offset + 3]]))
        ranges.append((offset, 3))
    grading = {}
    for index, name in enumerate(["highlights", "midTone", "shadows"]):
        offset = 0x170 + index * 4
        grading[name] = {"hue": ((data[offset] & 15) << 8) + data[offset + 1],
                         "chroma": data[offset + 2] - 128, "brightness": data[offset + 3] - 128}
        # The high nibble is retained, not assigned a rendering meaning.
        ranges.append((offset + 1, 3))
    grading.update(blending=data[0x180] - 128, balance=data[0x182] - 128)
    ranges.extend([(0x180, 1), (0x182, 1)])
    decoded["colorGrading"] = grading
    tone_start = None
    if data[0x185:0x187] == b"\1\1":
        length = struct.unpack(">I", span(data, 392, 4))[0]
        if length == 0 or length % 2:
            raise ValueError("Invalid NP3 comment length")
        payload = span(data, 396, length)
        end = payload.find(b"\0")
        if end < 0 or any(payload[end:]):
            raise ValueError("Invalid NP3 comment terminator/padding")
        decoded["comment"] = payload[:end].decode("utf-8", errors="replace")
        next_type = struct.unpack(">I", span(data, 396 + length, 4))[0]
        if next_type not in (0, 2):
            raise ValueError("Unsupported NP3 extension type")
        ranges.append((392, length + 8))
        if next_type == 2:
            tone_start = length + 400
    elif data[0x187] == 2:
        tone_start = 392
    if tone_start is not None:
        span(data, tone_start, 586)
        point_offset, raw_offset = tone_start + 12, tone_start + 68
        count = data[point_offset]
        if count > 27:
            raise ValueError("NP3 points overlap raw curve")
        points = [{"x": data[point_offset + 1 + i * 2], "y": data[point_offset + 2 + i * 2]} for i in range(count)]
        raw = list(struct.unpack(">257H", span(data, raw_offset, 514)))
        if any(x > 32767 for x in raw):
            raise ValueError("NP3 raw curve exceeds its 15-bit range")
        decoded["toneCurve"] = {"raw": raw, "points": points}
        for name in ["contrast", "highlights", "shadows", "whiteLevel", "blackLevel"]:
            decoded[name] = 0
        ranges.extend([(point_offset, 1 + count * 2), (raw_offset, 514)])
    result.update(decoded=decoded, byteAccounting=byte_partition(data, ranges),
                  renderingStatus="controls decoded; vendor base renderer and control math unverified")
    return result


def parse_pf(data):
    result = {"format": "Canon Picture Style envelope", **fingerprint(data)}
    header = span(data, 0, 12)
    version, signature, body_size = struct.unpack(">I4sI", header)
    if signature != b"PSP\0" or version not in (0x2000, 0x2001, 0x3000):
        raise ValueError("Unsupported Canon Picture Style envelope")
    if body_size + 12 != len(data):
        raise ValueError("Canon envelope length mismatch")
    result.update(version=f"0x{version:08x}", declaredBodyBytes=body_size,
                  bodySha256=sha(data[12:]), byteAccounting=byte_partition(data, [(0, 12)]),
                  renderingStatus="opaque body; no decoded Canon look or redistribution permission inferred")
    return result


def inspect_file(path):
    with path.open("rb") as stream:
        data = stream.read(MAX_FILE + 1)
    fingerprint(data)
    if data[:4] == b"NCP\0":
        return parse_np3(data)
    if data[4:8] == b"PSP\0":
        return parse_pf(data)
    return parse_dcp(data)


def lens_inventory(paths):
    files, cameras, lenses, models, makers = [], 0, 0, {}, {}
    for path in sorted(paths):
        data = path.read_bytes()
        fingerprint(data)
        # ElementTree does not load this external DTD. Allow the actual corpus
        # declaration while rejecting internal entity definitions/other DTDs.
        without_dtd = data.replace(b'<!DOCTYPE lensdatabase SYSTEM "lensfun-database.dtd">', b"")
        if b"<!DOCTYPE" in without_dtd.upper() or b"<!ENTITY" in data.upper():
            raise ValueError("Unsupported Lensfun DTD/entity declaration")
        root = ET.fromstring(data)
        if root.tag != "lensdatabase":
            raise ValueError("Unexpected Lensfun XML root")
        camera_count, lens_count = len(root.findall("camera")), len(root.findall("lens"))
        cameras += camera_count
        lenses += lens_count
        for lens in root.findall("lens"):
            maker = lens.findtext("maker", "unknown")
            makers[maker] = makers.get(maker, 0) + 1
            for calibration in lens.findall("calibration"):
                for element in calibration:
                    key = element.tag + ":" + element.get("model", "none")
                    models[key] = models.get(key, 0) + 1
        files.append({"path": path.name, **fingerprint(data), "cameras": camera_count, "lenses": lens_count})
    return {"files": files, "cameraEntries": cameras, "lensEntries": lenses,
            "lensMakers": makers, "calibrationRecordsByTypeAndModel": models,
            "colourTransmissionData": False,
            "note": "Database entries, not deduplicated physical models; geometry/TCA/shading do not specify coating spectral transmission."}


def compact(result):
    copy = dict(result)
    if "entries" in copy:
        copy["entries"] = [dict(e, values=e["values"][:18], valuesTruncated=True)
                           if isinstance(e["values"], list) and len(e["values"]) > 18 else e
                           for e in copy["entries"]]
    return copy


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("files", nargs="+", type=Path)
    parser.add_argument("--full", action="store_true", help="Include complete numeric payloads")
    args = parser.parse_args()
    results = [{"file": str(p), **inspect_file(p)} for p in args.files]
    print(json.dumps(results if args.full else [compact(r) for r in results], indent=2, allow_nan=False))


if __name__ == "__main__":
    main()
