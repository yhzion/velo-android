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
        self.assertEqual((r["text_compared"], r["text_diffs"]), (0, []))

    def test_paired_text_differences_are_reported_without_failing_verdict(self):
        board, img = board_and_image()
        board["elements"][2]["text"] = "Hello\n  world"
        board["elements"].append({"name": "subtitle", "x": 14, "y": 62, "w": 40, "h": 10,
                                  "background": "rgba(0, 0, 0, 0)", "text": "Expected copy"})
        app = app_from(board)
        app["nodes"][2]["text"] = "Hello world"
        app["nodes"][3]["text"] = "Wrong copy"
        r, _, _ = f.compare({}, board, app, img, img.copy(), {}, DSF)
        self.assertEqual(r["text_compared"], 2)
        self.assertEqual(r["text_diffs"], [{"name": "subtitle", "board": "Expected copy", "app": "Wrong copy"}])
        v = f.m.verdict(tokens=1.0, geometry=r["geometry"], missing=len(r["missing"]),
                        max_delta_e=r["max_delta_e"], ssim=r["ssim"])
        self.assertTrue(v["pass"])

        # Report HTML renders the text differences summary and details.
        entry = {"board": "SetBarTest", "title": "t", "size": [100, 80], "status": "measured", **r, **v,
                 "score": 100.0, "images": {"side": "side.png", "heat": "heat.png"}}
        page = f.render_html({
            "generated": "2026-11-27T00:00:00+00:00",
            "thresholds": {"tokens": 1.0, "geometry": 0.95, "tolerance_dp": 2.0,
                           "missing": 0, "delta_e_below": 3.0, "ssim": 0.92},
            "tokens": {"rate": 1.0, "cases": 1, "failed": [], "classes": ["T"]},
            "match_rate": 100.0, "match_rate_implemented": 100.0,
            "screens_total": 1, "screens_implemented": 1, "screens_passed": 1,
            "platform_exceptions": [],
            "screens": [{"screen": "settings-test", "implemented": True, "score": 100.0, "pass": True,
                         "text_diffs": [{"name": "subtitle", "board": "Expected copy", "app": "Wrong copy",
                                         "devices": ["bar"]}],
                         "devices": {"bar": entry}}],
        })
        self.assertIn("text diff 1", page)
        self.assertIn("Text differences (1)", page)
        self.assertIn("Expected copy", page)
        self.assertIn("Wrong copy", page)

    def test_text_diff_counts_counts_screens_and_per_screen_entries(self):
        rows = [
            {"screen": "a", "text_diffs": [{"name": "x", "board": "1", "app": "2", "devices": ["bar", "fold"]},
                                           {"name": "y", "board": "3", "app": "4", "devices": ["bar"]}]},
            {"screen": "b", "text_diffs": []},
            {"screen": "c"},
            {"screen": "d", "text_diffs": [{"name": "x", "board": "1", "app": "2", "devices": ["fold"]}]},
        ]
        self.assertEqual(f.text_diff_counts(rows), (2, 3))
        self.assertEqual(f.text_diff_counts([]), (0, 0))

    def test_known_text_difference_is_listed_apart_on_its_screens_only(self):
        board, img = board_and_image()
        board["elements"][2]["text"] = "Version 2.10.0"
        app = app_from(board)
        app["nodes"][2]["text"] = "Version 2.12.4"
        known = {"label": {"screens": ["settings-about"], "board": "Version  2.10.0", "reason": "the installed version"}}
        r, _, _ = f.compare({"screen": "settings-about"}, board, app, img, img.copy(), {}, DSF, known)
        self.assertEqual(r["text_diffs"], [])
        self.assertEqual(r["text_known"], [{"name": "label", "board": "Version 2.10.0", "app": "Version 2.12.4",
                                            "reason": "the installed version"}])
        # The same difference on a screen the entry does not name is new.
        r, _, _ = f.compare({"screen": "settings-home"}, board, app, img, img.copy(), {}, DSF, known)
        self.assertEqual([d["name"] for d in r["text_diffs"]], ["label"])
        self.assertEqual(r["text_known"], [])
        # A known text difference keeps the element in geometry and fills, unlike exceptions.json.
        self.assertEqual((r["elements"], r["geometry"]), (3, 1.0))

    def test_known_text_entry_applies_only_to_the_board_text_it_names(self):
        board, img = board_and_image()
        board["elements"][2]["text"] = "Version 2.10.0 released"
        app = app_from(board)
        app["nodes"][2]["text"] = "Version 2.12.4"
        known = {"label": {"screens": ["settings-about"], "board": "Version 2.10.0", "reason": "the installed version"}}
        r, _, _ = f.compare({"screen": "settings-about"}, board, app, img, img.copy(), {}, DSF, known)
        # Same element, other board text: a new difference, and the entry matched nothing.
        self.assertEqual([d["name"] for d in r["text_diffs"]], ["label"])
        self.assertEqual(r["text_known"], [])
        row = {"screen": "settings-about", "devices": {"bar": {"status": "measured", **r}}, "text_known": r["text_known"]}
        self.assertEqual(f.unused_known_text(known, [row]), [{"name": "label", "screen": "settings-about"}])

    def test_load_known_text_needs_screens_a_board_text_and_a_reason(self):
        import json
        import os
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            self.assertEqual(f.load_known_text(os.path.join(d, "none.json")), {})
            path = os.path.join(d, "known-text.json")
            ok = {"elements": {"x": {"screens": ["a"], "board": "1.0", "reason": "why"}}}
            with open(path, "w") as fp:
                json.dump(ok, fp)
            self.assertEqual(f.load_known_text(path), ok["elements"])
            entry = {"screens": ["a"], "board": "1.0", "reason": "why"}
            for bad, says in (
                    ([], "elements must map"),
                    ({"x": "why"}, "x must be an entry"),
                    ({"x": {**entry, "screens": []}}, "x needs a list of screens"),
                    ({"x": {**entry, "screens": "a"}}, "x needs a list of screens"),
                    ({"x": {**entry, "screens": ["a", 5]}}, "x has a screen that is not a screen id"),
                    ({"x": {**entry, "screens": [" "]}}, "x has a screen that is not a screen id"),
                    ({"x": {**entry, "reason": " "}}, "x needs a reason text"),
                    ({"x": {**entry, "reason": None}}, "x needs a reason text"),
                    ({"x": {**entry, "reason": 5}}, "x needs a reason text"),
                    ({"x": {k: v for k, v in entry.items() if k != "board"}}, "x needs a board text"),
                    ({"x": {**entry, "board": ""}}, "x needs a board text"),
                    ({"x": {**entry, "board": 2.1}}, "x needs a board text")):
                with open(path, "w") as fp:
                    json.dump({"elements": bad}, fp)
                with self.assertRaises(SystemExit) as caught:
                    f.load_known_text(path)
                self.assertIn(says, str(caught.exception))

    def test_screen_lists_merge_devices_and_unused_known_entries_are_named(self):
        known_diff = {"name": "v", "board": "1", "app": "2", "reason": "version"}
        devices = {
            "bar": {"status": "measured", "text_diffs": [{"name": "x", "board": "a", "app": "b"}], "text_known": [known_diff]},
            "closed": {"status": "no-scene"},
            "open": {"status": "measured", "text_diffs": [], "text_known": [known_diff]},
        }
        self.assertEqual(f.by_screen(devices, "text_diffs"), [{"name": "x", "board": "a", "app": "b", "devices": ["bar"]}])
        self.assertEqual(f.by_screen(devices, "text_known"), [{**known_diff, "devices": ["bar", "open"]}])
        rows = [{"screen": "s", "devices": devices, "text_known": f.by_screen(devices, "text_known")},
                {"screen": "t", "devices": {"bar": {"status": "measured"}}, "text_known": []},
                {"screen": "u", "devices": {"bar": {"status": "no-scene"}}, "text_known": []}]
        known = {"v": {"screens": ["s", "t", "u"], "board": "1", "reason": "version"},
                 "w": {"screens": ["s"], "board": "1", "reason": "r"}}
        # u is not measured, so its entry is not judged.
        self.assertEqual(f.unused_known_text(known, rows), [{"name": "v", "screen": "t"}, {"name": "w", "screen": "s"}])
        self.assertEqual(f.text_diff_counts(rows, "text_known"), (1, 1))

    def test_report_html_lists_known_text_apart(self):
        board, img = board_and_image()
        board["elements"][2]["text"] = "9:41 PM"
        app = app_from(board)
        app["nodes"][2]["text"] = "4:53 PM"
        known = {"label": {"screens": ["settings-test"], "board": "9:41 PM", "reason": "the clock time of the check"}}
        r, _, _ = f.compare({"screen": "settings-test"}, board, app, img, img.copy(), {}, DSF, known)
        entry = {"board": "SetBarTest", "title": "t", "size": [100, 80], "status": "measured", **r, "pass": True,
                 "reasons": [], "score": 100.0, "images": {"side": "side.png", "heat": "heat.png"}}
        devices = {"bar": entry}
        page = f.render_html({
            "generated": "2026-11-27T00:00:00+00:00",
            "thresholds": {"tokens": 1.0, "geometry": 0.95, "tolerance_dp": 2.0,
                           "missing": 0, "delta_e_below": 3.0, "ssim": 0.92},
            "tokens": {"rate": 1.0, "cases": 1, "failed": [], "classes": ["T"]},
            "match_rate": 100.0, "match_rate_implemented": 100.0,
            "screens_total": 1, "screens_implemented": 1, "screens_passed": 1,
            "platform_exceptions": [], "known_text_unused": [{"name": "gone", "screen": "settings-test"}],
            "screens": [{"screen": "settings-test", "implemented": True, "score": 100.0, "pass": True,
                         "text_diffs": [], "text_known": f.by_screen(devices, "text_known"), "devices": devices}],
        })
        self.assertIn("text diff 0 (+1 known)", page)
        self.assertIn("Text differences (0)", page)
        self.assertIn("Known text differences (1)", page)
        self.assertIn("the clock time of the check", page)
        self.assertIn("0 new differences on 0 screens; 1 known", page)
        self.assertIn("Known text that matched nothing", page)

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
