"""Tests for the fidelity metrics. Run: python3 -m unittest discover -s tools/fidelity"""
import unittest

import numpy as np

import metrics as m


class ColourTest(unittest.TestCase):
    # Reference pairs from Sharma, Wu and Dalal (2005), "The CIEDE2000 colour-difference formula".
    SHARMA = [
        ((50.0, 2.6772, -79.7751), (50.0, 0.0, -82.7485), 2.0425),
        ((50.0, 0.0, 0.0), (50.0, -1.0, 2.0), 2.3669),
        ((50.0, 2.5, 0.0), (73.0, 25.0, -18.0), 27.1492),
        ((60.2574, -34.0099, 36.2677), (60.4626, -34.1751, 39.4387), 1.2644),
    ]

    def test_ciede2000_matches_published_pairs(self):
        for a, b, want in self.SHARMA:
            self.assertAlmostEqual(m.ciede2000(a, b), want, places=4)
            self.assertAlmostEqual(m.ciede2000(b, a), want, places=4)

    def test_srgb_white_and_black_in_lab(self):
        L, a, b = m.srgb_to_lab((255, 255, 255))
        self.assertAlmostEqual(L, 100.0, places=2)
        self.assertLess(abs(a) + abs(b), 0.05)
        self.assertAlmostEqual(m.srgb_to_lab((0, 0, 0))[0], 0.0, places=4)

    def test_delta_e_of_close_greys_is_small(self):
        self.assertLess(m.delta_e((15, 16, 21), (16, 17, 22)), 1.0)
        self.assertGreater(m.delta_e((15, 16, 21), (99, 162, 246)), 30)

    def test_css_colours(self):
        self.assertEqual(m.css_rgba("rgb(15, 16, 21)"), (15, 16, 21, 1.0))
        self.assertEqual(m.css_rgba("rgba(0, 0, 0, 0)"), (0, 0, 0, 0.0))
        self.assertEqual(m.css_rgba("rgba(99, 162, 246, 0.5)"), (99, 162, 246, 0.5))
        self.assertIsNone(m.css_rgba("transparent"))

    def test_dominant_colour_ignores_minority_pixels(self):
        img = np.zeros((20, 20, 3), dtype=np.uint8)
        img[:, :] = (21, 23, 29)
        img[8:10, 2:15] = (243, 244, 249)  # a line of text
        self.assertEqual(m.dominant_colour(img, (0, 0, 20, 20), inset=0), (21, 23, 29))


    def test_edge_colour_is_the_container_fill_not_its_children(self):
        img = np.zeros((200, 200, 3), dtype=np.uint8)
        img[:, :] = (15, 16, 21)
        img[0:2, :] = (43, 44, 48)  # a 1 dp border at density 2
        img[12:188, 12:188] = (21, 23, 29)  # a child covering most of the inside
        self.assertEqual(m.edge_colour(img, (0, 0, 200, 200), scale=2.0), (15, 16, 21))

    def test_edge_colour_skips_rounded_corners(self):
        img = np.zeros((40, 200, 3), dtype=np.uint8)  # a pill: background shows in the corners
        img[:, :] = (15, 16, 21)
        img[:, 20:180] = (99, 162, 246)
        img[4:36, 10:190] = (99, 162, 246)
        self.assertEqual(m.edge_colour(img, (0, 0, 200, 40), scale=2.0), (99, 162, 246))


class PairingTest(unittest.TestCase):
    def test_pairs_by_name_then_occurrence(self):
        board = [{"name": "row", "x": 0, "y": 0, "w": 10, "h": 10},
                 {"name": "row", "x": 0, "y": 20, "w": 10, "h": 10},
                 {"name": "title", "x": 0, "y": 40, "w": 10, "h": 10}]
        app = [{"tag": "row", "x": 0, "y": 1, "w": 10, "h": 10},
               {"tag": "extra", "x": 0, "y": 0, "w": 1, "h": 1},
               {"tag": "row", "x": 0, "y": 25, "w": 10, "h": 10}]
        pairs, missing, extra = m.pair(board, app)
        self.assertEqual([(p[0]["y"], p[1]["y"]) for p in pairs], [(0, 1), (20, 25)])
        self.assertEqual([e["name"] for e in missing], ["title"])
        self.assertEqual([e["tag"] for e in extra], ["extra"])

    def test_within_tolerance_checks_position_and_size(self):
        b = {"x": 10, "y": 10, "w": 100, "h": 40}
        self.assertTrue(m.within(b, {"x": 12, "y": 8, "w": 101.5, "h": 38}, 2))
        self.assertFalse(m.within(b, {"x": 12.5, "y": 10, "w": 100, "h": 40}, 2))
        self.assertFalse(m.within(b, {"x": 10, "y": 10, "w": 100, "h": 43}, 2))


class SsimTest(unittest.TestCase):
    def test_identical_images_score_one(self):
        rng = np.random.default_rng(1)
        img = rng.integers(0, 255, (64, 48, 3), dtype=np.uint8)
        score, smap = m.ssim(img, img)
        self.assertAlmostEqual(score, 1.0, places=6)
        self.assertEqual(smap.shape, (64, 48))

    def test_different_images_score_lower(self):
        a = np.zeros((64, 64, 3), dtype=np.uint8)
        a[:, 32:] = 255
        b = np.zeros((64, 64, 3), dtype=np.uint8)
        b[32:, :] = 255
        self.assertLess(m.ssim(a, b)[0], 0.9)

    def test_small_shift_stays_high(self):
        a = np.full((64, 64, 3), 20, dtype=np.uint8)
        a[20:40, 20:40] = 200
        b = np.roll(a, 1, axis=1)
        self.assertGreater(m.ssim(a, b)[0], 0.8)

    def test_text_mask_scales_and_clamps_boxes(self):
        mask = m.text_mask((20, 30), [
            {"x": 2, "y": 3, "w": 5, "h": 4},
            {"x": -2, "y": 8, "w": 4, "h": 5},
            {"x": 5, "y": 5, "w": 0, "h": 3},
        ], scale=2.0)
        self.assertEqual(mask.shape, (20, 30))
        self.assertTrue(mask[6:14, 4:14].all())
        self.assertTrue(mask[16:20, 0:4].all())
        self.assertFalse(mask[0:6, :].any())
        self.assertEqual(int(mask.sum()), 8 * 10 + 4 * 4)

    def test_split_ssim_separates_text_and_non_text_regions(self):
        smap = np.full((10, 10), 0.98)
        mask = np.zeros((10, 10), dtype=bool)
        mask[2:6, 2:7] = True
        smap[mask] = 0.50
        non_text, text = m.split_ssim(smap, mask)
        self.assertAlmostEqual(non_text, 0.98, places=6)
        self.assertAlmostEqual(text, 0.50, places=6)

        # Empty text mask: non-text equals the whole map, text is None.
        non_text_empty, text_empty = m.split_ssim(smap, np.zeros((10, 10), dtype=bool))
        self.assertAlmostEqual(non_text_empty, float(smap.mean()), places=6)
        self.assertIsNone(text_empty)

        # Full text mask: non-text is None, text equals the whole map.
        non_text_full, text_full = m.split_ssim(smap, np.ones((10, 10), dtype=bool))
        self.assertIsNone(non_text_full)
        self.assertAlmostEqual(text_full, float(smap.mean()), places=6)

    def test_dilate_mask_expands_by_radius_and_clamps_to_edges(self):
        mask = np.zeros((15, 15), dtype=bool)
        mask[7, 7] = True
        dilated = m.dilate_mask(mask, 2)
        self.assertTrue(dilated[5:10, 5:10].all())
        self.assertEqual(int(dilated.sum()), 25)
        # Zero radius and empty mask stay unchanged.
        self.assertTrue(np.array_equal(m.dilate_mask(mask, 0), mask))
        empty = np.zeros((5, 5), dtype=bool)
        self.assertFalse(m.dilate_mask(empty, 5).any())
        # Radius larger than the image fills the whole mask without wrapping.
        tiny = np.zeros((3, 4), dtype=bool)
        tiny[0, 0] = True
        self.assertTrue(m.dilate_mask(tiny, 5).all())

    def test_split_ssim_margin_excludes_window_halo(self):
        smap = np.full((30, 30), 1.0)
        mask = np.zeros((30, 30), dtype=bool)
        mask[12:18, 12:18] = True
        # Drop SSIM in the 5 px halo around the text box, mimicking the 11x11 window bleed.
        halo = m.dilate_mask(mask, m.SSIM_RADIUS) & ~mask
        smap[halo] = 0.25
        smap[mask] = 0.40
        raw_non_text, raw_text = m.split_ssim(smap, mask, margin=0)
        clean_non_text, clean_text = m.split_ssim(smap, mask, margin=m.SSIM_RADIUS)
        self.assertLess(raw_non_text, 0.90)
        self.assertAlmostEqual(clean_non_text, 1.0, places=6)
        self.assertAlmostEqual(raw_text, 0.40, places=6)
        self.assertAlmostEqual(clean_text, 0.40, places=6)


class ScreensTest(unittest.TestCase):
    def test_screen_ids_from_board_names(self):
        self.assertEqual(m.screen_of("SetBarUpdates"), "settings-updates")
        self.assertEqual(m.screen_of("SetOpenAddRemote"), "settings-add-remote")
        self.assertEqual(m.screen_of("StartClosed1"), "start-1")
        self.assertEqual(m.screen_of("StartBar1Wait"), "start-1-wait")
        self.assertEqual(m.screen_of("WsOpenDocked"), "workspace-docked")
        self.assertIsNone(m.screen_of("Tokens"))


class ClipTest(unittest.TestCase):
    def test_a_device_on_a_preview_board_becomes_a_board_of_its_own(self):
        board = {"board": "Main", "dsf": 2.0, "elements": [
            {"name": "preview", "x": 0, "y": 0, "w": 300, "h": 100},
            {"name": "screen", "x": 10, "y": 20, "w": 50, "h": 60},
            {"name": "mark", "x": 20, "y": 30, "w": 10, "h": 10},
            {"name": "screen", "x": 100, "y": 20, "w": 50, "h": 60},
            {"name": "mark", "x": 110, "y": 40, "w": 10, "h": 10},
        ]}
        img = np.zeros((200, 600, 3), dtype=np.uint8)
        img[40:160, 200:300] = 7
        clipped, cut = m.clip_board(board, img, "screen", 1, 2.0)
        self.assertEqual([(e["name"], e["x"], e["y"]) for e in clipped["elements"]], [("screen", 0, 0), ("mark", 10, 20)])
        self.assertEqual(clipped["size"], [50, 60])
        self.assertEqual(cut.shape, (120, 100, 3))
        self.assertTrue((cut == 7).all())
        with self.assertRaises(ValueError):
            m.clip_board(board, img, "screen", 2, 2.0)

    def test_clip_board_clips_and_translates_text_boxes(self):
        board = {
            "board": "Main", "dsf": 2.0,
            "elements": [
                {"name": "screen", "x": 100, "y": 20, "w": 50, "h": 60},
            ],
            "text_boxes": [
                {"x": 10, "y": 30, "w": 20, "h": 10},   # outside
                {"x": 110, "y": 30, "w": 20, "h": 10},  # fully inside
                {"x": 140, "y": 75, "w": 20, "h": 10},  # partially overlapping right/bottom
            ],
        }
        img = np.zeros((200, 400, 3), dtype=np.uint8)
        clipped, _ = m.clip_board(board, img, "screen", 0, 2.0)
        self.assertEqual(clipped["text_boxes"], [
            {"x": 10.0, "y": 10.0, "w": 20.0, "h": 10.0},
            {"x": 40.0, "y": 55.0, "w": 10.0, "h": 5.0},
        ])


class VerdictTest(unittest.TestCase):
    GOOD = dict(tokens=1.0, geometry=0.96, missing=0, max_delta_e=2.9, ssim=0.93)

    def test_passes_at_thresholds(self):
        self.assertEqual(m.verdict(**self.GOOD)["pass"], True)
        self.assertEqual(m.verdict(**{**self.GOOD, "ssim": m.MIN_SSIM})["pass"], True)

    def test_each_threshold_fails_on_its_own(self):
        for key, bad in [("tokens", 0.99), ("geometry", 0.949), ("missing", 1), ("max_delta_e", 3.0),
                         ("ssim", 0.919), ("tokens", None)]:
            v = m.verdict(**{**self.GOOD, key: bad})
            self.assertFalse(v["pass"], key)
            self.assertTrue(any(key.split("_")[-1] in r for r in v["reasons"]), (key, v["reasons"]))

    def test_no_fills_to_compare_is_not_a_failure(self):
        self.assertTrue(m.verdict(**{**self.GOOD, "max_delta_e": None})["pass"])

    def test_score_is_the_mean_of_the_four_rates(self):
        s = m.score(tokens=1.0, geometry=0.5, colour=1.0, ssim=0.5)
        self.assertAlmostEqual(s, 75.0)
        # No token result counts as 0; a board without fills leaves colour out of the mean.
        self.assertAlmostEqual(m.score(tokens=None, geometry=0.5, colour=None, ssim=0.5), 100 / 3)


if __name__ == "__main__":
    unittest.main()
