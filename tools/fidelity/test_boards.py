"""Tests for boards.mjs text-box extraction on synthetic HTML boards.

Run: python3 -m unittest discover -s tools/fidelity
"""
import json
import os
import subprocess
import tempfile
import unittest

BOARDS_MJS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "boards.mjs")
CHROME = os.environ.get(
    "FIDELITY_CHROME",
    os.path.join(os.path.expanduser("~"), ".cache/ms-playwright/chromium-1217/chrome-linux64/chrome"),
)


def _render_synthetic_boards(bodies, width=393, height=894):
    with tempfile.TemporaryDirectory() as tmp:
        canvas = os.path.join(tmp, "canvas")
        out = os.path.join(tmp, "out")
        os.makedirs(canvas)
        with open(os.path.join(canvas, "support.js"), "w") as fp:
            fp.write("// stub runtime for unit test\n")
        manifest = {f"{name}.dc.html": {"w": width, "h": height, "title": name} for name in bodies}
        with open(os.path.join(canvas, "canvas.json"), "w") as fp:
            json.dump({"boards": manifest}, fp)
        for name, body in bodies.items():
            with open(os.path.join(canvas, f"{name}.dc.html"), "w") as fp:
                fp.write(
                    "<!doctype html><html><head><meta charset=utf-8>"
                    "<style>html,body{margin:0;padding:0;background:#101216;color:#f3f4f9;font:16px/20px sans-serif}</style>"
                    "</head><body>" + body + "</body></html>"
                )
        subprocess.run(
            ["node", BOARDS_MJS, "--canvas", canvas, "--runtime", os.path.join(canvas, "support.js"),
             "--out", out, "--boards", ",".join(bodies), "--fonts", "web", "--chrome", CHROME],
            check=True, capture_output=True, text=True,
        )
        results = {}
        for name in bodies:
            with open(os.path.join(out, f"{name}.json")) as fp:
                results[name] = json.load(fp)
        return results


@unittest.skipUnless(os.path.exists(CHROME), f"headless Chromium not found at {CHROME}")
class BoardsTextBoxTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.boards = _render_synthetic_boards({
            "SetBarPlaceholder": (
                '<textarea data-el="notes" placeholder="Hi" '
                'style="position:fixed;left:20px;top:20px;width:200px;height:140px;padding:8px;border:0;'
                'box-sizing:border-box;font:16px/20px sans-serif"></textarea>'
            ),
            "SetBarWrapped": (
                '<textarea id="ta" data-el="wrapped" '
                'style="position:fixed;left:20px;top:20px;width:120px;height:56px;padding:8px;border:0;'
                'box-sizing:border-box;font:16px/20px sans-serif;overflow:auto">'
                'Line one wraps across multiple lines inside the box\nSecond paragraph here</textarea>'
                '<script>document.getElementById("ta").scrollTop = 20;</script>'
            ),
            "SetBarInput": (
                '<input data-el="wide-input" type="text" value="OK" '
                'style="position:fixed;left:20px;top:20px;width:300px;height:48px;padding:0 16px;border:0;'
                'box-sizing:border-box;font:16px/20px sans-serif">'
                '<input data-el="check" type="checkbox" checked '
                'style="position:fixed;left:20px;top:100px;width:20px;height:20px">'
                '<p data-el="long-copy" style="position:fixed;left:20px;top:140px;width:350px">'
                + ("word " * 60).strip() + '</p>'
            ),
        })

    def test_textarea_masks_only_occupied_placeholder_line_not_empty_area(self):
        boxes = self.boards["SetBarPlaceholder"]["text_boxes"]
        self.assertEqual(len(boxes), 1, boxes)
        box = boxes[0]
        # Content box is 184x124 starting at (28, 28); "Hi" occupies a single short line.
        self.assertAlmostEqual(box["x"], 28.0, delta=2.0)
        self.assertAlmostEqual(box["y"], 28.0, delta=2.0)
        self.assertLess(box["w"], 60.0)
        self.assertLess(box["h"], 30.0)

    def test_textarea_wrapping_and_scroll_clip_to_content_box(self):
        boxes = self.boards["SetBarWrapped"]["text_boxes"]
        # Content box is y in [28, 68] (height 40 = 2 visible lines of 20px), x in [28, 132].
        self.assertGreaterEqual(len(boxes), 2, boxes)
        for b in boxes:
            self.assertGreaterEqual(b["x"], 27.9)
            self.assertLessEqual(b["x"] + b["w"], 132.1)
            self.assertGreaterEqual(b["y"], 27.9)
            self.assertLessEqual(b["y"] + b["h"], 68.1)

    def test_input_masks_occupied_value_only_and_ignores_checkbox(self):
        board = self.boards["SetBarInput"]
        # One box for wide-input ("OK") plus the wrapped lines of long-copy, none for the checkbox.
        input_boxes = [b for b in board["text_boxes"] if b["y"] < 80]
        self.assertEqual(len(input_boxes), 1, input_boxes)
        box = input_boxes[0]
        # Vertically centered inside y in [20, 68] (center y=44), starting at x=36, narrow width.
        self.assertAlmostEqual(box["x"], 36.0, delta=2.0)
        self.assertAlmostEqual(box["y"] + box["h"] / 2, 44.0, delta=3.0)
        self.assertLess(box["w"], 60.0)
        self.assertLess(box["h"], 30.0)
        # No text_box around checkbox at y=100..120.
        self.assertFalse(any(90 <= b["y"] <= 125 for b in board["text_boxes"]), board["text_boxes"])

        by_name = {e["name"]: e for e in board["elements"]}
        self.assertEqual(by_name["wide-input"]["text"], "OK")
        self.assertEqual(by_name["check"]["text"], "")
        self.assertEqual(by_name["long-copy"]["text"], ("word " * 60).strip())
        self.assertGreater(len(by_name["long-copy"]["text"]), 200)


if __name__ == "__main__":
    unittest.main()
