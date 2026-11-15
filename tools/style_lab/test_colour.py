"""Numerical invariants for the new opt-in generator. Cloud execution only."""
import json
import unittest
from pathlib import Path

import numpy as np

from colour import (PRO_TO_SRGB, compress_rgb_ray, cube_grid, desired_lab, gamut_map, grade, hue_weight,
                    lab_to_pro_linear, pro_decode, pro_encode, pro_to_lab,
                    sample_tetra, srgb_to_pro)


class ColourTests(unittest.TestCase):
    def setUp(self):
        self.rng = np.random.default_rng(20261115)
        self.styles = json.loads(Path(__file__).with_name("candidates.json").read_text(encoding="utf-8"))["styles"]

    def test_prophoto_transfer_round_trip_and_toe(self):
        x = np.r_[np.linspace(0, 1, 10001), 1 / 32, 1 / 32 - 1e-10, 1 / 32 + 1e-10]
        np.testing.assert_allclose(pro_encode(pro_decode(x)), x, atol=2e-15)
        self.assertLess(abs(pro_decode(1 / 32 - 1e-10) - pro_decode(1 / 32 + 1e-10)), 1e-9)

    def test_signed_wide_gamut_round_trip(self):
        x = self.rng.random((10000, 3))
        np.testing.assert_allclose(lab_to_pro_linear(pro_to_lab(x)), pro_decode(x), atol=4e-15)
        # ProPhoto primaries can be outside sRGB. Preserve signed components.
        self.assertLess((pro_decode(x) @ PRO_TO_SRGB.T).min(), -.2)

    def test_official_oklab_red_reference(self):
        expected = np.array([.62795536, .22486306, .12584630])
        np.testing.assert_allclose(pro_to_lab(srgb_to_pro([[1., 0., 0.]]))[0], expected, atol=1e-8)

    def test_periodic_hue_has_no_wrap_seam(self):
        for center in (0, 50, 145, 265):
            self.assertAlmostEqual(float(hue_weight(-np.pi, center, 28)), float(hue_weight(np.pi, center, 28)), places=14)
            a = hue_weight(-np.pi + 1e-6, center, 28) - hue_weight(-np.pi, center, 28)
            b = hue_weight(np.pi, center, 28) - hue_weight(np.pi - 1e-6, center, 28)
            self.assertLess(abs(a - b), 1e-10)

    def test_soft_gamut_contains_wide_colors(self):
        lab = np.column_stack((self.rng.uniform(.05, .95, 10000), self.rng.uniform(-.5, .5, (10000, 2))))
        mapped = gamut_map(lab)
        rgb = lab_to_pro_linear(mapped)
        self.assertGreaterEqual(rgb.min(), -1e-9)
        self.assertLessEqual(rgb.max(), 1 + 1e-9)
        raw = lab_to_pro_linear(lab)
        anchor = lab[:, 0, None] ** 3
        cross = np.cross(rgb - anchor, raw - anchor)
        self.assertLess(abs(cross).max(), 1e-12)

    def test_interior_gamut_bypass_is_exact(self):
        lab = pro_to_lab(srgb_to_pro(self.rng.uniform(.2, .8, (1000, 3))))
        np.testing.assert_array_equal(gamut_map(lab), lab)

    def test_disconnected_oklab_ray_does_not_bypass_rgb_mapping(self):
        # C/knee is inside again after a negative-red interval. An endpoint
        # probe plus global bisection incorrectly bypassed this real case.
        lab = np.array([[.35336876, -.42473134, -.17196187]])
        rgb = lab_to_pro_linear(gamut_map(lab))
        self.assertGreaterEqual(rgb.min(), -1e-10)

    def test_soft_knee_value_and_derivative_are_continuous(self):
        d = np.array([.85 - 1e-5, .85, .85 + 1e-5])
        rgb = np.column_stack((.2 + .8 * d, np.full(3, .2), np.full(3, .2)))
        mapped = compress_rgb_ray(rgb, np.full(3, .2))
        slopes = np.diff(mapped[:, 0]) / 1e-5
        self.assertLess(abs(slopes[0] - slopes[1]), 1e-4)
        self.assertLess(abs(mapped[1, 0] - rgb[1, 0]), 1e-14)

    def test_rgb_ray_response_monotonic_at_boundary(self):
        for angle in np.linspace(-np.pi, np.pi, 25):
            chroma = np.linspace(0, .7, 2048)
            lab = np.column_stack((np.full_like(chroma, .65), chroma * np.cos(angle), chroma * np.sin(angle)))
            direction = np.array([np.cos(angle), np.sin(angle), -.2])
            rgb = .2 + chroma[:, None] * direction
            mapped = compress_rgb_ray(rgb, np.full_like(chroma, .2))
            self.assertGreaterEqual(np.diff(np.linalg.norm(mapped - .2, axis=1)).min(), -1e-14)

    def test_candidates_neutrals_monotonic_and_tints_explicit(self):
        neutral = np.repeat(np.linspace(0, 1, 4097)[:, None], 3, axis=1)
        for style in self.styles:
            output = grade(neutral, style)
            self.assertTrue(np.isfinite(output).all(), style["id"])
            self.assertGreaterEqual(np.diff(pro_to_lab(output)[:, 0]).min(), -1e-9, style["id"])
            np.testing.assert_allclose(output[[0, -1]], neutral[[0, -1]], atol=3e-7)
            if "split" not in style:
                self.assertLess(np.ptp(output, axis=1).max(), 1e-6, style["id"])

    def test_color_proxy_protection_reduces_skin_range_changes(self):
        pixels = srgb_to_pro([[.65, .4, .3], [.85, .65, .5], [.35, .2, .14]])
        style = {"chroma": 1.3, "density": .12, "bands": [{"hue": 50, "width": 30, "shift": 10}]}
        unprotected = np.linalg.norm(desired_lab(pixels, style) - pro_to_lab(pixels), axis=1)
        protected = np.linalg.norm(desired_lab(pixels, {**style, "skin_protect": .85}) - pro_to_lab(pixels), axis=1)
        self.assertTrue((protected < unprotected * .65).all())

    def test_sorted_tetra_identity_axis_order_and_ties(self):
        grid = cube_grid(9).reshape(9, 9, 9, 3)
        pixels = np.r_[self.rng.random((10000, 3)), [[0, 0, 0], [1, 1, 1], [.25, .25, .25], [.2, .2, .7]]]
        np.testing.assert_allclose(sample_tetra(grid, pixels), pixels, atol=3e-16)
        np.testing.assert_array_equal(grid[8, 0, 0], [0, 0, 1])

    def test_deepest_cube_cell_preserves_original_color(self):
        pixels = self.rng.random((1000, 3)) / 64
        for style in self.styles:
            np.testing.assert_array_equal(grade(pixels, style), pixels)

    def test_negative_lms_extremes_preserve_input(self):
        pixels = np.array([[0., 0., 1.], [1., 0., 0.]])
        for style in self.styles:
            if style.get("chroma", 1) > 0:
                np.testing.assert_array_equal(grade(pixels[:1], style), pixels[:1])


if __name__ == "__main__":
    unittest.main(verbosity=2)
