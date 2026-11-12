#!/usr/bin/env python3
"""Co-created with GPT (OpenAI). Local ICC-managed RAW/JPEG diagnostics.

Output belongs beside the user's inputs, outside a public checkout. A camera
JPEG is a developed reference, not sensor truth or a real-film calibration.
Exposure fitting below is for comparison images only; source f32 is read-only.
Requires NumPy, Pillow (LittleCMS) and OpenCV with SIFT.
"""
import argparse
import io
import json
from pathlib import Path

import cv2
import numpy as np
from PIL import Image, ImageCms, ImageDraw, ImageOps

# Linear ProPhoto D50 -> Bradford D65 -> linear sRGB. Keep signed values until
# creating the explicitly labelled display preview, where sRGB clips them.
PROPHOTO_XYZ = np.array([[.7976749, .1351917, .0313534],
                         [.2880402, .7118741, .0000857], [0, 0, .8252100]])
BRADFORD_D50_D65 = np.array([[.9555766, -.0230393, .0631636],
                             [-.0282895, 1.0099416, .0210077],
                             [.0122982, -.0204830, 1.3299098]])
XYZ_SRGB = np.array([[3.2404542, -1.5371385, -.4985314],
                     [-.9692660, 1.8760108, .0415560],
                     [.0556434, -.2040259, 1.0572252]])
TO_SRGB = XYZ_SRGB @ BRADFORD_D50_D65 @ PROPHOTO_XYZ
LUMA = np.array([.2126, .7152, .0722], dtype=np.float32)


def linearize(rgb):
    rgb = np.asarray(rgb, dtype=np.float32) / 255
    return np.where(rgb <= .04045, rgb / 12.92, ((rgb + .055) / 1.055) ** 2.4)


def display(rgb):
    rgb = np.clip(rgb, 0, 1)
    return np.rint(255 * np.where(rgb <= .0031308, rgb * 12.92,
                                 1.055 * rgb ** (1 / 2.4) - .055)).astype(np.uint8)


def small(image, edge=1536):
    h, w = image.shape[:2]
    scale = min(1, edge / max(w, h))
    return cv2.resize(image, (round(w * scale), round(h * scale)), interpolation=cv2.INTER_AREA), scale


def audit_path(prefix, suffix):
    return Path(str(prefix) + suffix)


def feature_gray(rgb):
    y = np.maximum(rgb @ LUMA, 0)
    white = max(float(np.percentile(y, 99.5)), 1e-8)
    values = np.rint(255 * np.sqrt(np.clip(y / white, 0, 1))).astype(np.uint8)
    return cv2.createCLAHE(2, (8, 8)).apply(values)


def compare(prefix, jpeg, output):
    output.mkdir(parents=True, exist_ok=True)
    meta = json.loads(audit_path(prefix, '.json').read_text())
    path = audit_path(prefix, '.f32')
    shape = (meta['height'], meta['width'], 3)
    if path.stat().st_size != int(np.prod(shape)) * 4:
        raise ValueError('f32 payload size disagrees with audit metadata')
    pixels = np.memmap(path, dtype='<f4', mode='r', shape=shape)
    raw, raw_scale = small(pixels)
    raw = cv2.transform(raw, TO_SRGB.astype(np.float32))
    with Image.open(jpeg) as opened:
        image = ImageOps.exif_transpose(opened)
        if image.mode != 'RGB':
            raise ValueError('comparison requires an RGB camera JPEG')
        icc = image.info.get('icc_profile')
        if not icc:
            raise ValueError('JPEG has no ICC profile; choose an explicit input profile first')
        source = ImageCms.ImageCmsProfile(io.BytesIO(icc))
        managed = ImageCms.profileToProfile(image.convert('RGB'), source,
                    ImageCms.createProfile('sRGB'), renderingIntent=1, outputMode='RGB')
        jpeg_rgb = np.asarray(managed)
        profile_name = ImageCms.getProfileDescription(source).strip()
        untagged, _ = small(np.asarray(image.convert('RGB')))
        jpeg_icc, jpeg_scale = small(jpeg_rgb)
    camera = linearize(jpeg_icc)
    sift = cv2.SIFT_create(nfeatures=8000)
    ka, da = sift.detectAndCompute(feature_gray(raw), None)
    kb, db = sift.detectAndCompute(feature_gray(camera), None)
    if da is None or db is None:
        raise ValueError('not enough image detail for registration')
    matches = []
    for pair in cv2.BFMatcher().knnMatch(da, db, k=2):
        if len(pair) == 2 and pair[0].distance < .7 * pair[1].distance:
            matches.append(pair[0])
    if len(matches) < 12:
        raise ValueError('not enough unambiguous feature matches')
    a = np.float32([ka[m.queryIdx].pt for m in matches])
    b = np.float32([kb[m.trainIdx].pt for m in matches])
    transform, mask = cv2.findHomography(a, b, cv2.RANSAC, 2)
    if transform is None:
        raise ValueError('registration failed')
    inliers = mask.ravel().astype(bool)
    errors = np.linalg.norm(cv2.perspectiveTransform(a[:, None], transform)[:, 0] - b, axis=1)
    coverage = float(cv2.contourArea(cv2.convexHull(a[inliers])) / (raw.shape[0] * raw.shape[1]))
    median_error = float(np.median(errors[inliers]) / jpeg_scale)
    if inliers.sum() < 30 or inliers.mean() < .3 or coverage < .15 or median_error > 2:
        raise ValueError('registration lacks enough spatially distributed agreeing matches')
    h, w = camera.shape[:2]
    registered = cv2.warpPerspective(raw, transform, (w, h), flags=cv2.INTER_LINEAR)
    valid = cv2.warpPerspective(np.ones(raw.shape[:2], np.uint8), transform, (w, h),
                               flags=cv2.INTER_NEAREST).astype(bool)
    yr, yj = registered @ LUMA, camera @ LUMA
    selected = valid & (yr > .02) & (yr < .7) & (yj > .02) & (yj < .7)
    selected &= (registered.min(axis=2) > 0) & (camera.max(axis=2) < .98)
    if selected.sum() < 100:
        raise ValueError('not enough unclipped midtones for exposure-only diagnostic')
    gain = float(np.median(yj[selected] / yr[selected]))
    normalized = registered * gain
    # Chromaticity is diagnostic scene content, not Delta E/colour accuracy.
    rsum, jsum = normalized.sum(axis=2), camera.sum(axis=2)
    chroma = np.linalg.norm(normalized / np.maximum(rsum[..., None], 1e-8) -
                           camera / np.maximum(jsum[..., None], 1e-8), axis=2)
    neutral = selected & (camera.max(axis=2) - camera.min(axis=2) < .04)
    full_transform = (np.diag([1 / jpeg_scale, 1 / jpeg_scale, 1]) @ transform @
                      np.diag([raw_scale, raw_scale, 1]))
    sample = np.asarray(pixels[::8, ::8])
    report = dict(schema=1, mode=meta['mode'], raw_size=[meta['width'], meta['height']],
        jpeg_size=[jpeg_rgb.shape[1], jpeg_rgb.shape[0]], source_icc=profile_name,
        cmm='Pillow/LittleCMS relative colorimetric to 8-bit sRGB; output gamut may clip',
        keypoints=[len(ka), len(kb)], ratio_matches=len(matches), inliers=int(inliers.sum()),
        inlier_median_error_original_pixels=median_error,
        inlier_coverage_raw=coverage,
        raw_to_jpeg_homography=full_transform.tolist(),
        comparison_exposure_ev=float(np.log2(gain)), selected_midtones=int(selected.sum()),
        chromaticity_distance_median=float(np.median(chroma[selected])),
        neutral_pixels=int(neutral.sum()),
        raw_rgb_at_jpeg_neutrals=np.median(normalized[neutral], axis=0).tolist() if neutral.any() else None,
        sampled_prophoto_quantiles=np.quantile(sample, [0, .01, .5, .99, 1], axis=(0, 1)).tolist(),
        sampled_at_zero_fraction=(sample == 0).mean(axis=(0, 1)).tolist(),
        sampled_at_one_fraction=(sample == 1).mean(axis=(0, 1)).tolist(),
        camera_to_output_gray_axis=np.asarray(meta['camera_to_output']).reshape(3, 4).sum(axis=1).tolist(),
        icc_conversion_mean_abs_code_change=float(np.abs(untagged.astype(float) - jpeg_icc).mean()),
        limitations='JPEG vendor rendering and diagnostic exposure fit prevent film/colorimetric accuracy claims; source RAW and f32 unchanged')
    (output / 'comparison.json').write_text(json.dumps(report, indent=2) + '\n')
    panels = [display(registered), display(normalized), jpeg_icc]
    labels = ['Production linear RAW -> sRGB', 'RAW: exposure-only comparison', 'Camera JPEG: ICC -> sRGB']
    canvas = Image.new('RGB', (768 * 3, 620), '#222222')
    draw = ImageDraw.Draw(canvas)
    for i, (panel, label) in enumerate(zip(panels, labels)):
        tile = Image.fromarray(panel)
        tile.thumbnail((768, 576))
        canvas.paste(tile, (i * 768, 32))
        draw.text((i * 768 + 12, 10), label, fill='white')
    canvas.save(output / 'comparison.jpg', quality=95, icc_profile=ImageCms.ImageCmsProfile(
        ImageCms.createProfile('sRGB')).tobytes())
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('audit_prefix', type=Path)
    parser.add_argument('camera_jpeg', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    result = compare(args.audit_prefix, args.camera_jpeg, args.output)
    print(json.dumps({k: result[k] for k in ('inliers', 'inlier_median_error_original_pixels',
                                           'comparison_exposure_ev', 'source_icc')}))
