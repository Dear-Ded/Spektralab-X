#!/usr/bin/env python3
# Spektralab-X - https://github.com/Dear-Ded/Spektralab-X
# Copyright (C) 2026 Spektralab-X contributors
# Additional terms under GPL-3.0 section 7 apply; see NOTICE.
# Co-created with GPT (OpenAI), 2026-11-11.
"""Independent double CIEDE2000 check, not an RGB/ICC conversion or HDR metric.

Sharma, Wu and Dalal, Color Research & Application 30(1), 2005, pp. 21-30.
Author-supplied supplementary pairs, retrieved 2026-11-11:
https://hajim.rochester.edu/ece/sites/gsharma/ciede2000/
https://hajim.rochester.edu/ece/sites/gsharma/ciede2000/dataNprograms/ciede2000testdata.txt

The reference answers have four decimal places, so their rounding interval is
5e-5. This test validates the difference formula only. A caller must establish
one common CIELAB whitepoint and viewing contract before supplying Lab pairs.
"""
import argparse
import importlib.util
import json
import math
import random
import sys


def delta_e00(first, second):
    """CIEDE2000 with kL=kC=kH=1, using degrees for hue and radians for trig."""
    if len(first) != 3 or len(second) != 3:
        raise ValueError("CIELAB triples required")
    if not all(math.isfinite(value) for value in (*first, *second)):
        raise ValueError("nonfinite CIELAB input")
    light1, a1, b1 = first
    light2, a2, b2 = second
    mean_chroma = (math.hypot(a1, b1) + math.hypot(a2, b2)) / 2
    correction = (1 - math.sqrt(mean_chroma**7 / (mean_chroma**7 + 25**7))) / 2
    aa1, aa2 = a1 * (1 + correction), a2 * (1 + correction)
    chroma1, chroma2 = math.hypot(aa1, b1), math.hypot(aa2, b2)

    def hue(a, b):
        if a == 0 and b == 0:
            return 0.0
        angle = math.degrees(math.atan2(b, a))
        return angle + 360 if angle < 0 else angle

    hue1, hue2 = hue(aa1, b1), hue(aa2, b2)
    hue_difference = hue2 - hue1
    if chroma1 == 0 or chroma2 == 0:
        hue_difference = 0.0
        mean_hue = hue1 + hue2
    else:
        if hue_difference > 180:
            hue_difference -= 360
        elif hue_difference < -180:
            hue_difference += 360
        if abs(hue1 - hue2) <= 180:
            mean_hue = (hue1 + hue2) / 2
        elif hue1 + hue2 < 360:
            mean_hue = (hue1 + hue2 + 360) / 2
        else:
            mean_hue = (hue1 + hue2 - 360) / 2

    mean_light = (light1 + light2) / 2
    mean_chroma = (chroma1 + chroma2) / 2
    delta_light = light2 - light1
    delta_chroma = chroma2 - chroma1
    delta_hue = 2 * math.sqrt(chroma1 * chroma2) * math.sin(math.radians(hue_difference / 2))
    t = (1 - .17 * math.cos(math.radians(mean_hue - 30))
         + .24 * math.cos(math.radians(2 * mean_hue))
         + .32 * math.cos(math.radians(3 * mean_hue + 6))
         - .20 * math.cos(math.radians(4 * mean_hue - 63)))
    light_weight = 1 + .015 * (mean_light - 50)**2 / math.sqrt(20 + (mean_light - 50)**2)
    chroma_weight = 1 + .045 * mean_chroma
    hue_weight = 1 + .015 * mean_chroma * t
    rotation = (-2 * math.sqrt(mean_chroma**7 / (mean_chroma**7 + 25**7))
                * math.sin(math.radians(60 * math.exp(-((mean_hue - 275) / 25)**2))))
    dl, dc, dh = delta_light / light_weight, delta_chroma / chroma_weight, delta_hue / hue_weight
    return math.sqrt(max(0.0, dl * dl + dc * dc + dh * dh + rotation * dc * dh))


# Lab1, Lab2, published DeltaE00. Numeric supplementary test data, not image data.
SHARMA = (
    ((50, 2.6772, -79.7751), (50, 0, -82.7485), 2.0425),
    ((50, 3.1571, -77.2803), (50, 0, -82.7485), 2.8615),
    ((50, 2.8361, -74.0200), (50, 0, -82.7485), 3.4412),
    ((50, -1.3802, -84.2814), (50, 0, -82.7485), 1.0000),
    ((50, -1.1848, -84.8006), (50, 0, -82.7485), 1.0000),
    ((50, -.9009, -85.5211), (50, 0, -82.7485), 1.0000),
    ((50, 0, 0), (50, -1, 2), 2.3669),
    ((50, -1, 2), (50, 0, 0), 2.3669),
    ((50, 2.49, -.001), (50, -2.49, .0009), 7.1792),
    ((50, 2.49, -.001), (50, -2.49, .001), 7.1792),
    ((50, 2.49, -.001), (50, -2.49, .0011), 7.2195),
    ((50, 2.49, -.001), (50, -2.49, .0012), 7.2195),
    ((50, -.001, 2.49), (50, .0009, -2.49), 4.8045),
    ((50, -.001, 2.49), (50, .001, -2.49), 4.8045),
    ((50, -.001, 2.49), (50, .0011, -2.49), 4.7461),
    ((50, 2.5, 0), (50, 0, -2.5), 4.3065),
    ((50, 2.5, 0), (73, 25, -18), 27.1492),
    ((50, 2.5, 0), (61, -5, 29), 22.8977),
    ((50, 2.5, 0), (56, -27, -3), 31.9030),
    ((50, 2.5, 0), (58, 24, 15), 19.4535),
    ((50, 2.5, 0), (50, 3.1736, .5854), 1.0000),
    ((50, 2.5, 0), (50, 3.2972, 0), 1.0000),
    ((50, 2.5, 0), (50, 1.8634, .5757), 1.0000),
    ((50, 2.5, 0), (50, 3.2592, .3350), 1.0000),
    ((60.2574, -34.0099, 36.2677), (60.4626, -34.1751, 39.4387), 1.2644),
    ((63.0109, -31.0961, -5.8663), (62.8187, -29.7946, -4.0864), 1.2630),
    ((61.2901, 3.7196, -5.3901), (61.4292, 2.2480, -4.9620), 1.8731),
    ((35.0831, -44.1164, 3.7933), (35.0232, -40.0716, 1.5901), 1.8645),
    ((22.7233, 20.0904, -46.6940), (23.0331, 14.9730, -42.5619), 2.0373),
    ((36.4612, 47.8580, 18.3852), (36.2715, 50.5065, 21.2231), 1.4146),
    ((90.8027, -2.0831, 1.4410), (91.1528, -1.6435, .0447), 1.4441),
    ((90.9257, -.5406, -.9208), (88.6381, -.8985, -.7239), 1.5381),
    ((6.7747, -.2908, -2.4247), (5.8714, -.0985, -2.2286), .6377),
    ((2.0776, .0795, -1.1350), (.9033, -.0636, -.5514), .9082),
)


def verify(function):
    errors = [abs(function(a, b) - expected) for a, b, expected in SHARMA]
    assert max(errors) <= 5e-5 + 1e-12, max(errors)
    symmetry = max(abs(function(a, b) - function(b, a)) for a, b, _ in SHARMA)
    assert symmetry < 1e-12, symmetry
    assert all(function(a, a) == 0 for a, _, _ in SHARMA)
    return dict(pairs=len(SHARMA), max_rounded_reference_error=max(errors), max_symmetry_error=symmetry)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--compare", help="Existing metric module exposing delta_e(Lab1, Lab2)")
    args = parser.parse_args()
    result = {"reference": verify(delta_e00), "reference_rounding_bound": 5e-5}
    for value in (math.nan, math.inf, -math.inf):
        try:
            delta_e00((50, 0, value), (50, 0, 0))
        except ValueError:
            continue
        raise AssertionError("nonfinite metric input was accepted")
    if args.compare:
        sys.dont_write_bytecode = True
        spec = importlib.util.spec_from_file_location("existing_lut_metric", args.compare)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        result["existing_metric"] = verify(module.delta_e)
        rng = random.Random(0x20261111)
        maximum = 0.0
        for _ in range(4096):
            samples = [(rng.uniform(0, 100), rng.uniform(-128, 128), rng.uniform(-128, 128)) for _ in range(2)]
            maximum = max(maximum, abs(delta_e00(*samples) - module.delta_e(*samples)))
        assert maximum < 1e-12, maximum
        result["random_crosscheck"] = dict(pairs=4096, seed="0x20261111", max_difference=maximum)
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
