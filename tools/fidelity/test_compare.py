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
        self.assertEqual([x["name"] for x in r["fills"]], ["screen", "card"])

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


if __name__ == "__main__":
    unittest.main()
