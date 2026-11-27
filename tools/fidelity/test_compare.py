"""Tests for fidelity.compare on a synthetic board. Run: python3 -m unittest discover -s tools/fidelity"""
import copy
import unittest

import numpy as np

import fidelity as f

DSF = 2.0


def board_and_image():
    els = [
        {"name": "screen", "x": 0, "y": 0, "w": 100, "h": 80, "background": "rgb(15, 16, 21)"},
        {"name": "card", "x": 10, "y": 10, "w": 80, "h": 30, "background": "rgb(21, 23, 29)"},
        {"name": "label", "x": 14, "y": 50, "w": 40, "h": 10, "background": "rgba(0, 0, 0, 0)"},
    ]
    img = np.zeros((160, 200, 3), dtype=np.uint8)
    img[:, :] = (15, 16, 21)
    img[20:80, 20:180] = (21, 23, 29)
    img[100:120, 28:108] = (243, 244, 249)
    return {"elements": els, "dsf": DSF}, img


def app_from(board):
    return {"w": 100, "h": 80, "nodes": [{"tag": e["name"], **{k: e[k] for k in "xywh"}} for e in board["elements"]]}


class CompareTest(unittest.TestCase):
    def test_identical_app_scores_full_marks(self):
        board, img = board_and_image()
        r, _, _ = f.compare({}, board, app_from(board), img, img.copy(), {}, DSF)
        self.assertEqual((r["geometry"], r["missing"], r["extra"]), (1.0, [], []))
        self.assertEqual(r["colour"], 1.0)
        self.assertAlmostEqual(r["max_delta_e"], 0.0)
        self.assertAlmostEqual(r["ssim"], 1.0, places=6)
        self.assertAlmostEqual(r["ssim_raw"], 1.0, places=6)
        self.assertIsNone(r["ssim_text"])
        self.assertEqual(r["text_coverage"], 0.0)
        self.assertEqual([x["name"] for x in r["fills"]], ["screen", "card"])

    def test_text_box_anti_aliasing_does_not_fail_judged_ssim(self):
        board, img = board_and_image()
        board["text_boxes"] = [{"x": 14, "y": 50, "w": 40, "h": 10}]
        app_img = img.copy()
        # Simulate Chromium vs Skia anti-aliasing inside the label's text box only:
        # shift bright stripes by 1 px so local SSIM inside the text box drops sharply.
        img[102:118, 30:106:2] = (243, 244, 249)
        img[102:118, 31:106:2] = (15, 16, 21)
        app_img[102:118, 30:106:2] = (15, 16, 21)
        app_img[102:118, 31:106:2] = (243, 244, 249)
        r, _, _ = f.compare({}, board, app_from(board), img, app_img, {}, DSF)
        self.assertGreater(r["ssim"], 0.98)
        self.assertLess(r["ssim_text"], 0.2)
        self.assertLess(r["ssim_raw"], r["ssim"])
        self.assertGreater(r["text_coverage"], 0.0)

    def test_narrow_gutter_text_differences_do_not_lower_judged_ssim(self):
        # 20-row text bands separated by 2-row gutters (narrower than the 11x11 SSIM window),
        # plus a clean non-text area at the bottom so non-text SSIM is defined.
        img = np.full((120, 100, 3), 20, dtype=np.uint8)
        app_img = img.copy()
        text_boxes = []
        for y in (10, 32, 54):  # 20 px bands at DSF=1 with 2 px gutters between them
            text_boxes.append({"x": 10, "y": y, "w": 80, "h": 20})
            img[y:y + 20, 10:90:2] = 240
            app_img[y:y + 20, 11:90:2] = 240
        board = {"elements": [], "text_boxes": text_boxes, "dsf": 1.0}
        app = {"w": 100, "h": 120, "nodes": []}

        identical, _, _ = f.compare({}, board, app, img, img.copy(), {}, 1.0)
        text_only, _, _ = f.compare({}, board, app, img, app_img, {}, 1.0)
        self.assertAlmostEqual(identical["ssim"], 1.0, places=6)
        self.assertAlmostEqual(text_only["ssim"], 1.0, places=6)
        self.assertGreaterEqual(text_only["ssim"], 0.92)
        self.assertLess(text_only["ssim_raw"], 0.92)

    def test_non_text_difference_still_drops_judged_ssim(self):
        board, img = board_and_image()
        board["text_boxes"] = [{"x": 14, "y": 50, "w": 40, "h": 10}]
        app_img = img.copy()
        app_img[20:80, 20:180] = (180, 185, 200)  # bright card fill mismatch outside text_boxes
        r, _, _ = f.compare({}, board, app_from(board), img, app_img, {}, DSF)
        self.assertLess(r["ssim"], 0.85)

    def test_shift_wrong_fill_and_missing_tag_are_each_reported(self):
        board, img = board_and_image()
        app = app_from(board)
        app["nodes"][1]["y"] += 3  # card 3 dp low
        del app["nodes"][2]  # label not tagged
        app["nodes"].append({"tag": "debug", "x": 0, "y": 0, "w": 1, "h": 1})
        app_img = img.copy()
        app_img[26:86, 20:180] = (40, 60, 120)  # card drawn in another colour, where the app puts it
        r, _, _ = f.compare({}, board, app, img, app_img, {}, DSF)
        self.assertAlmostEqual(r["geometry"], 1 / 3)
        self.assertEqual(r["missing"], ["label"])
        self.assertEqual(r["extra"], ["debug"])
        self.assertEqual([o["name"] for o in r["offsets"]], ["card"])
        card = next(x for x in r["fills"] if x["name"] == "card")
        self.assertEqual(card["app"], (40, 60, 120))
        self.assertGreater(r["max_delta_e"], 3)
        self.assertEqual(r["colour"], 0.5)
        self.assertLess(r["ssim"], 1.0)

    def test_excepted_elements_are_left_out(self):
        board, img = board_and_image()
        app = app_from(board)
        del app["nodes"][2]
        r, _, _ = f.compare({}, copy.deepcopy(board), app, img, img.copy(), {"elements": {"label": "platform"}}, DSF)
        self.assertEqual((r["elements"], r["missing"]), (2, []))

    def test_load_board_refuses_stale_render_without_text_boxes(self):
        import json
        import os
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            stale = os.path.join(d, "StartBar2Empty.json")
            fresh = os.path.join(d, "Main@0.json")
            with open(stale, "w") as fp:
                json.dump({"board": "StartBar2Empty", "elements": []}, fp)
            with open(fresh, "w") as fp:
                json.dump({"board": "Main@0", "elements": [], "text_boxes": []}, fp)
            with self.assertRaises(SystemExit) as ctx:
                f.load_board(stale)
            self.assertIn("StartBar2Empty.json", str(ctx.exception))
            self.assertIn("--skip-boards", str(ctx.exception))
            self.assertEqual(f.load_board(fresh)["text_boxes"], [])

    def test_screens_and_devices_from_canvas_boards(self):
        canvas = {"boards": {
            "SetBarUpdates.dc.html": {"w": 393, "h": 1240, "title": "t"},
            "SetOpenUpdates.dc.html": {"w": 821, "h": 1088},
            "SetUpdates.dc.html": {"w": 361, "h": 1120},
            "Tokens.dc.html": {"w": 1200, "h": 900},
        }}
        import json
        import os
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            json.dump(canvas, open(os.path.join(d, "canvas.json"), "w"))
            plan = f.plan_of(d, None)
            self.assertEqual([(p["screen"], p["device"], p["h"]) for p in plan],
                             [("settings-updates", "bar", 1240), ("settings-updates", "open", 1088)])
            self.assertEqual(f.plan_of(d, {"settings-about"}), [])

    def test_preview_screens_are_cut_from_their_board_at_each_time(self):
        canvas = {"boards": {"Main.dc.html": {"w": 1880, "h": 1270, "title": "Splash"}}}
        import json
        import os
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            json.dump(canvas, open(os.path.join(d, "canvas.json"), "w"))
            plan = f.plan_of(d, {"splash", "splash-240ms"})
            self.assertEqual([(p["screen"], p["device"], p["board"], p["w"], p["clip_index"]) for p in plan], [
                ("splash-240ms", "bar", "Main@240", 393, 0), ("splash-240ms", "closed", "Main@240", 475, 1),
                ("splash-240ms", "open", "Main@240", 821, 2),
                ("splash", "bar", "Main@1400", 393, 0), ("splash", "closed", "Main@1400", 475, 1),
                ("splash", "open", "Main@1400", 821, 2),
            ])
            self.assertTrue(all(p["clip"] == "screen-splash" for p in plan))


if __name__ == "__main__":
    unittest.main()
