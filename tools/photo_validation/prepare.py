#!/usr/bin/env python3
"""Cloud-only preparation of licensed real-photo sRGB regression inputs.

Keep originals and generated images outside Git. Co-created with GPT (OpenAI).
"""
import argparse
import hashlib
import html
import io
import json
import re
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

from PIL import Image, ImageCms, ImageDraw, ImageOps

ROOT = Path(__file__).resolve().parent
DATA_ROOT = "https://raw.githubusercontent.com/scikit-image/scikit-image/v0.25.2/skimage/data/"
DATA_DOC = "https://scikit-image.org/docs/0.25.x/api/skimage.data.html"
MAX_BYTES = 32 << 20


def fetch(url):
    request = urllib.request.Request(url, headers={"User-Agent": "Spektralab-X-validation/1.0"})
    for attempt in range(3):
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                data = response.read(MAX_BYTES + 1)
            break
        except urllib.error.HTTPError as error:
            if error.code != 429 or attempt == 2:
                raise
            delay = error.headers.get("Retry-After", "60")
            delay = int(delay) if delay.isdigit() else 60
            if delay > 120:
                raise
            print("RATE_LIMIT", url, "retry after", delay, "seconds", flush=True)
            time.sleep(max(30, delay))
    if not data or len(data) > MAX_BYTES:
        raise ValueError("empty or oversized public source: " + url)
    return data


def save_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=True, indent=2) + "\n", encoding="utf-8")


def plain(value):
    return html.unescape(re.sub(r"<[^>]*>", "", value))


def resolve(source, metadata):
    if source["provider"] == "scikit-image":
        return DATA_ROOT + source["file"], {
            "source_page": DATA_DOC + "#skimage.data." + Path(source["file"]).stem,
            "source_revision": "scikit-image/v0.25.2", "credit": source["credit"],
            "license": source["license"], "license_evidence": "scikit-image-data-doc.html",
        }
    url = "https://commons.wikimedia.org/w/api.php?" + urllib.parse.urlencode({
        "action": "query", "titles": source["title"], "prop": "imageinfo",
        "iiprop": "url|extmetadata|size|sha1|timestamp", "format": "json",
    })
    evidence = metadata / (source["id"] + ".json")
    raw = evidence.read_bytes() if evidence.exists() else fetch(url)
    evidence.write_bytes(raw)
    pages = list(json.loads(raw)["query"]["pages"].values())
    if len(pages) != 1 or "imageinfo" not in pages[0]:
        raise ValueError("missing Commons original: " + source["title"])
    info = pages[0]["imageinfo"][0]
    meta = info["extmetadata"]
    license_name = meta["LicenseShortName"]["value"]
    if license_name != source["license"] or license_name not in ("CC0", "Public domain"):
        raise ValueError("license changed: " + source["id"] + " / " + license_name)
    if info["size"] > MAX_BYTES:
        raise ValueError("oversized original: " + source["id"])
    return info["url"].split("?")[0], {
        "source_page": info["descriptionurl"], "source_sha1": info["sha1"],
        "source_timestamp": info["timestamp"],
        "credit": plain(meta.get("Artist", {}).get("value", "")),
        "original_credit": plain(meta.get("Credit", {}).get("value", "")),
        "license": license_name, "license_url": meta.get("LicenseUrl", {}).get("value"),
        "restrictions": meta.get("Restrictions", {}).get("value", ""),
        "license_evidence": "metadata/" + source["id"] + ".json",
    }


def prepare(args):
    sources = json.loads(args.manifest.read_text(encoding="utf-8"))["sources"]
    out = args.out
    originals, metadata = out / "originals", out / "metadata"
    for path in (originals, metadata, out / "inputs", out / "thumbnails"):
        path.mkdir(parents=True, exist_ok=True)
    doc = out / "scikit-image-data-doc.html"
    if not doc.exists():
        doc.write_bytes(fetch(DATA_DOC))
    profile = ImageCms.ImageCmsProfile(ImageCms.createProfile("sRGB"))
    records, tiles = [], []
    for source in sources:
        sid = source["id"]
        url, record = resolve(source, metadata)
        original = originals / (sid + Path(urllib.parse.urlparse(url).path).suffix)
        if not original.exists():
            original.write_bytes(fetch(url))
        data = original.read_bytes()
        if "source_sha1" in record and hashlib.sha1(data).hexdigest() != record["source_sha1"]:
            raise ValueError("original differs from Commons metadata: " + sid)
        with Image.open(io.BytesIO(data)) as image:
            size, mode = image.size, image.mode
            orientation = image.getexif().get(274, 1)
            embedded = image.info.get("icc_profile")
            image = ImageOps.exif_transpose(image)
            if embedded:
                input_profile = ImageCms.ImageCmsProfile(io.BytesIO(embedded))
                image = ImageCms.profileToProfile(image, input_profile, profile,
                                                  renderingIntent=1, outputMode="RGB")
                color = "embedded ICC converted to sRGB, relative colorimetric"
            else:
                image = image.convert("RGB")
                color = "untagged RGB assumed sRGB; not an absolute color reference"
            image.thumbnail((args.edge, args.edge), Image.Resampling.LANCZOS)
            image.save(out / "inputs" / (sid + ".ppm"))
            image.save(out / "thumbnails" / (sid + ".png"), icc_profile=profile.tobytes())
            record.update(id=sid, coverage=source["coverage"], url=url,
                          retrieved_utc=datetime.now(timezone.utc).isoformat(),
                          original_file="originals/" + original.name,
                          original_bytes=len(data), original_sha256=hashlib.sha256(data).hexdigest(),
                          original_size=size, original_mode=mode, exif_orientation=orientation,
                          embedded_icc_sha256=hashlib.sha256(embedded).hexdigest() if embedded else None,
                          input_color=color, normalized_space="sRGB", input_size=image.size,
                          normalized_rgb_sha256=hashlib.sha256(image.tobytes()).hexdigest())
            records.append(record)
            tiles.append((sid, image.copy()))
        save_json(out / "PUBLIC_IMAGES.json", records)
        print("PREPARED", sid, image.size, record["license"], color, flush=True)
    sheet = Image.new("RGB", (4 * 272, ((len(tiles) + 3) // 4) * 292), "#e8e5df")
    draw = ImageDraw.Draw(sheet)
    for index, (sid, image) in enumerate(tiles):
        x, y = (index % 4) * 272, (index // 4) * 292
        tile = ImageOps.contain(image, (256, 256))
        sheet.paste(tile, (x + (272 - tile.width) // 2, y + (256 - tile.height) // 2))
        draw.text((x + 8, y + 264), sid, fill="#202020")
    sheet.save(out / "sources-contact.png", icc_profile=profile.tobytes())
    print("PASS", len(records), "public real photographs; originals and rights retained", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path, default=ROOT / "sources.json")
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--edge", type=int, default=256, choices=(192, 256, 384, 768))
    prepare(parser.parse_args())
