"""Original opt-in finishing looks, not camera profiles or RAW development.

Co-created with GPT (OpenAI), 2026-11-15. Project LICENSE and NOTICE apply.
Oklab equations: https://bottosson.github.io/posts/oklab/ (2020-12-23).
ProPhoto conversion matches the existing app CubeLut.kt contract. All arrays
are float64 RGB with channels last. Signed linear RGB stays signed until the
target ProPhoto gamut mapping; no intermediate sRGB cube clipping.
"""
import numpy as np

PRO_TO_SRGB = np.array([[2.0340758, -.7273341, -.3067418],
                        [-.2288131, 1.2317301, -.0029169],
                        [-.0085698, -.1532866, 1.1618564]])
SRGB_TO_PRO = np.linalg.inv(PRO_TO_SRGB)
M1 = np.array([[.4122214708, .5363325363, .0514459929],
               [.2119034982, .6806995451, .1073969566],
               [.0883024619, .2817188376, .6299787005]])
M2 = np.array([[.2104542553, .7936177850, -.0040720468],
               [1.9779984951, -2.4285922050, .4505937099],
               [.0259040371, .7827717662, -.8086757660]])
M1_INV, M2_INV = np.linalg.inv(M1), np.linalg.inv(M2)


def pro_decode(rgb):
    x = np.asarray(rgb, dtype=np.float64)
    return np.where(x < 1 / 32, x / 16, np.maximum(x, 0) ** 1.8)


def pro_encode(rgb):
    x = np.asarray(rgb, dtype=np.float64)
    return np.where(x < 1 / 512, 16 * x, np.maximum(x, 0) ** (1 / 1.8))


def srgb_decode(rgb):
    x = np.asarray(rgb, dtype=np.float64)
    return np.where(x <= .04045, x / 12.92, ((np.maximum(x, 0) + .055) / 1.055) ** 2.4)


def srgb_encode(rgb):
    x = np.asarray(rgb, dtype=np.float64)
    return np.where(x <= .0031308, 12.92 * x, 1.055 * np.maximum(x, 0) ** (1 / 2.4) - .055)


def linear_srgb_to_lab(rgb):
    return np.cbrt(np.asarray(rgb) @ M1.T) @ M2.T


def lab_to_linear_srgb(lab):
    return ((np.asarray(lab) @ M2_INV.T) ** 3) @ M1_INV.T


def pro_to_lab(encoded):
    return linear_srgb_to_lab(pro_decode(encoded) @ PRO_TO_SRGB.T)


def lab_to_pro_linear(lab):
    return lab_to_linear_srgb(lab) @ SRGB_TO_PRO.T


def hue_weight(hue, center, width):
    """Periodic and differentiable, including the 0/360-degree seam."""
    d = 2 * np.sin((hue - np.deg2rad(center)) / 2)
    return np.exp(-.5 * (d / np.deg2rad(width)) ** 2)


def desired_lab(encoded, style):
    lab = pro_to_lab(encoded)
    light = np.clip(lab[..., 0], 0, 1)
    monochrome = style.get("chroma", 1) == 0
    if monochrome:
        # A stable luminance monochrome path also covers extreme wide-gamut
        # colors, without retaining colored primaries in a black/white look.
        luminance = pro_decode(encoded) @ (PRO_TO_SRGB.T @ np.array([.2126, .7152, .0722]))
        light = np.cbrt(np.maximum(luminance, 0))
    chroma = np.linalg.norm(lab[..., 1:], axis=-1)
    hue = np.arctan2(lab[..., 2], lab[..., 1])
    # A color proxy, never a face/skin detector. Fade the influence near gray.
    color_gate = chroma * chroma / (chroma * chroma + .02 ** 2)
    protection = style.get("skin_protect", 0) * hue_weight(hue, 50, 28) * color_gate
    effect = 1 - protection
    saturation = 1 + (style.get("chroma", 1) - 1) * effect
    density = np.full_like(light, style.get("density", 0))
    shift = np.zeros_like(light)
    lift = np.zeros_like(light)
    for band in style.get("bands", []):
        weight = hue_weight(hue, band["hue"], band["width"]) * effect
        saturation *= 1 + (band.get("chroma", 1) - 1) * weight
        shift += np.deg2rad(band.get("shift", 0)) * weight * color_gate
        density += band.get("density", 0) * weight
        lift += band.get("lightness", 0) * weight * color_gate
    if monochrome:
        lift = np.zeros_like(light)
    # Endpoint-anchored monotonic contrast for |contrast| <= .5.
    light = light + style.get("contrast", 0) * light * (1 - light) * (2 * light - 1)
    light = np.clip(light + 4 * light * (1 - light) *
                    (lift - density * chroma * effect), 0, 1)
    chroma = chroma * saturation
    out = np.stack((light, chroma * np.cos(hue + shift), chroma * np.sin(hue + shift)), axis=-1)
    split = style.get("split")
    if split:
        # Midrange-only tint: black/white anchors stay black/white.
        shadow = (1 - light) ** 2
        highlight = light ** 2
        envelope = 4 * light * (1 - light) * split["amount"]
        for k, trig in ((1, np.cos), (2, np.sin)):
            out[..., k] += envelope * (
                shadow * trig(np.deg2rad(split["shadow_hue"])) +
                highlight * trig(np.deg2rad(split["highlight_hue"])))
    return out


def compress_rgb_ray(rgb, anchor, knee=.85):
    """C1 soft compression on a linear RGB ray from a neutral anchor.

    The RGB cube is convex along this ray. Its analytic first intersection is
    unique, avoiding the disconnected constant-Oklab-hue boundary problem.
    RGB channel ratios around the anchor are preserved; Oklab hue/L may move.
    """
    neutral = np.clip(np.asarray(anchor), 1e-12, 1 - 1e-12)
    direction = np.asarray(rgb) - neutral[..., None]
    distance = np.max(np.maximum(direction, 0) / (1 - neutral[..., None]) +
                      np.maximum(-direction, 0) / neutral[..., None], axis=-1)
    beyond = distance > knee
    scale = np.ones_like(distance)
    d = distance[beyond]
    compressed = knee + (1 - knee) * (-np.expm1(-(d - knee) / (1 - knee)))
    scale[beyond] = compressed / d
    return neutral[..., None] + scale[..., None] * direction


def gamut_map(lab, knee=.85):
    """Map into ProPhoto using a smooth linear-RGB neutral-ray compression.

    Oklab is the grading space; compression preserves RGB ratios rather than
    promising exact Oklab hue/lightness. This is SDR finishing, not ACES RGC.
    """
    values = np.asarray(lab, dtype=np.float64)
    flat = values.reshape(-1, 3).copy()
    flat[:, 0] = np.clip(flat[:, 0], 0, 1)
    rgb = lab_to_pro_linear(flat)
    anchor = flat[:, 0] ** 3
    compressed = compress_rgb_ray(rgb, anchor, knee)
    result = linear_srgb_to_lab(compressed @ PRO_TO_SRGB.T)
    # Preserve exact interior values; avoid a gratuitous matrix round trip.
    unchanged = np.max(abs(compressed - rgb), axis=1) < 1e-14
    result[unchanged] = flat[unchanged]
    return result.reshape(values.shape)


def grade(encoded, style):
    mapped = gamut_map(desired_lab(encoded, style))
    output = pro_encode(np.clip(lab_to_pro_linear(mapped), 0, 1))
    # Preserve the complete first 65-cube cell, where perceptual chroma is
    # under-resolved. Fade in through .12 instead of tinting near-black noise.
    source = np.asarray(encoded, dtype=np.float64)
    toe = np.clip((source.max(axis=-1) - 1 / 64) / (.12 - 1 / 64), 0, 1)
    toe = toe * toe * (3 - 2 * toe)
    # Extreme ProPhoto cube corners can have negative Oklab LMS responses.
    # Preserve those values instead of amplifying the cube-root singularity;
    # transition within the broad positive-LMS region, without sRGB clipping.
    lms = (pro_decode(source) @ PRO_TO_SRGB.T) @ M1.T
    confidence = np.clip(lms.min(axis=-1) / np.maximum(lms.max(axis=-1), 1e-15) / .12, 0, 1)
    confidence = confidence * confidence * (3 - 2 * confidence)
    if style.get("chroma", 1) == 0:
        confidence = np.ones_like(confidence)
    # Retain delicate bright colour detail instead of driving a 65-cube through
    # the steep white-corner compression. Monochrome keeps its achromatic map.
    highlight = np.ones_like(toe)
    if style.get("chroma", 1) > 0:
        light = np.cbrt(lms) @ M2[0]
        shoulder = np.clip((light - .90) / .10, 0, 1)
        highlight -= shoulder * shoulder * (3 - 2 * shoulder)
    return source + (output - source) * (toe * confidence * highlight)[..., None]


def srgb_to_pro(encoded):
    return pro_encode(srgb_decode(encoded) @ SRGB_TO_PRO.T)


def pro_to_srgb_preview(encoded):
    # The preview has a second, explicit sRGB display boundary. This clipping
    # does not change the delivered wide-gamut ProPhoto LUT.
    return np.clip(srgb_encode(pro_decode(encoded) @ PRO_TO_SRGB.T), 0, 1)


def cube_grid(side):
    b, g, r = np.meshgrid(*(np.linspace(0, 1, side),) * 3, indexing="ij")
    return np.stack((r, g, b), axis=-1).reshape(-1, 3)


def sample_tetra(table, encoded):
    """Sorted-simplex oracle, independent of the app's six explicit branches."""
    side = table.shape[0]
    shape = np.asarray(encoded).shape
    p = np.clip(np.asarray(encoded).reshape(-1, 3), 0, 1) * (side - 1)
    base = np.minimum(p.astype(np.int64), side - 2)
    frac = p - base
    order = np.argsort(-frac, axis=1, kind="stable")
    fractions = np.take_along_axis(frac, order, axis=1)
    weights = np.column_stack((1 - fractions[:, 0], fractions[:, 0] - fractions[:, 1],
                               fractions[:, 1] - fractions[:, 2], fractions[:, 2]))
    corner = base.copy()
    result = weights[:, 0, None] * table[corner[:, 2], corner[:, 1], corner[:, 0]]
    rows = np.arange(len(p))
    for step in range(3):
        corner[rows, order[:, step]] += 1
        result += weights[:, step + 1, None] * table[corner[:, 2], corner[:, 1], corner[:, 0]]
    return result.reshape(shape)
