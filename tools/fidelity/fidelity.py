#!/usr/bin/env python3
"""Measures how closely the app matches the redesign canvas, per screen and device shape.

Steps (docs/fidelity.md):
  1. boards: render every device board of the canvas in headless Chromium (boards.mjs) and record
     each data-el element's box, text and colours, plus a screenshot.
  2. app: run the token test and FidelityCaptureTest (Robolectric) through Gradle, which draws every
     registered scene at each board's size and records each testTag's box and a screenshot.
  3. compare: pair elements by name, measure geometry, fill colour and SSIM, and write
     build/fidelity/report.json and build/fidelity/report.html.

Usage: python3 tools/fidelity/fidelity.py --canvas <canvas project dir> [--screens a,b] [--skip-boards]
       [--skip-app] [--runtime dc-runtime.js] [--token-tests PATTERN]
"""
import argparse
import fnmatch
import glob
import html
import json
import os
import subprocess
import sys
import xml.etree.ElementTree as ET
from datetime import datetime, timezone

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import metrics as m  # noqa: E402

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
DEVICES = {"bar": (393, 894), "closed": (475, 751), "open": (821, 1088)}
DEVICE_ORDER = list(DEVICES)
# Screens drawn on a preview board rather than on device boards of their own. The splash's preview (board Main) draws
# the three devices side by side, each a screen-splash element, and plays the splash's timeline once. Each screen is
# that board paused at a time in ms, or drawn with reduced motion ("reduced"); boards.mjs renders Main@<time>.
PREVIEWS = [
    {"board": "Main", "clip": "screen-splash", "screens": {
        "splash-0ms": "0", "splash-240ms": "240", "splash-480ms": "480", "splash-660ms": "660",
        "splash-1000ms": "1000", "splash": "1400", "splash-reduced": "reduced"}},
]
# The token comparison test (layer 1); its JUnit results give the token match rate.
TOKEN_TESTS = "io.github.yhzion.velo.ui.theme.RedesignTokens*"


def parse_args():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--canvas", default=os.environ.get("FIDELITY_CANVAS"), help="canvas project folder (canvas.json, *.dc.html)")
    p.add_argument("--runtime", help="the Design runtime (dc-runtime.js); default: <canvas>/../artifact-type/dc-runtime.js")
    p.add_argument("--out", default=os.path.join(ROOT, "build", "fidelity"))
    p.add_argument("--screens", help="comma-separated screen ids to measure (default: all)")
    p.add_argument("--token-tests", default=TOKEN_TESTS, help="Gradle --tests pattern of the token comparison test")
    p.add_argument("--skip-boards", action="store_true", help="reuse the board renders in --out")
    p.add_argument("--skip-app", action="store_true", help="reuse the app captures and test results")
    p.add_argument("--exceptions", default=os.path.join(os.path.dirname(__file__), "exceptions.json"))
    a = p.parse_args()
    if not a.canvas:
        p.error("--canvas (or FIDELITY_CANVAS) is required")
    return a


def plan_of(canvas_dir, only):
    """One capture per device board of the canvas: its screen, device and size. Then the screens of the preview
    boards (PREVIEWS): one capture per screen and device, the board at that time cut to that device's screen."""
    canvas = json.load(open(os.path.join(canvas_dir, "canvas.json")))
    out = []
    for file, b in canvas["boards"].items():
        board = file.removesuffix(".dc.html")
        screen = m.screen_of(board)
        device = next((d for d, (w, h) in DEVICES.items() if b["w"] == w and b["h"] >= h), None)
        if screen and device and (not only or screen in only):
            out.append({"screen": screen, "device": device, "board": board, "w": b["w"], "h": b["h"],
                        "title": b.get("title", "")})
    for p in PREVIEWS:
        b = canvas["boards"].get(p["board"] + ".dc.html")
        if not b:
            continue
        for screen, at in p["screens"].items():
            if only and screen not in only:
                continue
            for i, (device, (w, h)) in enumerate(DEVICES.items()):
                out.append({"screen": screen, "device": device, "board": f"{p['board']}@{at}", "w": w, "h": h,
                            "title": f"{b.get('title', '')} · {at}", "clip": p["clip"], "clip_index": i})
    return out


def run_boards(a, plan):
    boards_dir = os.path.join(a.out, "boards")
    cmd = ["node", os.path.join(os.path.dirname(__file__), "boards.mjs"), "--canvas", a.canvas, "--out", boards_dir,
           "--boards", ",".join(p["board"] for p in plan)]
    if a.runtime:
        cmd += ["--runtime", a.runtime]
    subprocess.run(cmd, check=True)


def run_app(a, plan_file, only):
    """Runs the token test and the capture test. A failing token test is a result, not an error."""
    app_dir = os.path.join(a.out, "app")
    env = {**os.environ, "ANDROID_HOME": os.environ.get("ANDROID_HOME", os.path.expanduser("~/Android/Sdk"))}
    for old in glob.glob(os.path.join(ROOT, "app/build/test-results/testDebugUnitTest/*.xml")):
        os.remove(old)
    cmd = ["gradle", "--no-daemon", "--no-scan", "--continue", ":app:testDebugUnitTest", "-x", "zigCore", "-x", "tailscale",
           "--tests", "io.github.yhzion.velo.fidelity.FidelityCaptureTest", "--tests", a.token_tests,
           f"-Pvelo.fidelity.out={app_dir}", f"-Pvelo.fidelity.plan={plan_file}"]
    if only:
        cmd.append(f"-Pvelo.fidelity.screens={','.join(sorted(only))}")
    r = subprocess.run(cmd, cwd=ROOT, env=env)
    if r.returncode:
        print(f"gradle exited {r.returncode}; reading what it produced", file=sys.stderr)


def token_results(pattern):
    """Share of passing token test cases, from the JUnit XML of classes matching the pattern."""
    cases, failed, names = 0, [], set()
    for f in glob.glob(os.path.join(ROOT, "app/build/test-results/testDebugUnitTest/*.xml")):
        suite = ET.parse(f).getroot()
        for case in suite.iter("testcase"):
            cls = case.get("classname", "")
            if not (fnmatch.fnmatchcase(cls, pattern) or fnmatch.fnmatchcase(cls.split(".")[-1], pattern)):
                continue
            if case.find("skipped") is not None:
                continue
            names.add(cls)
            cases += 1
            if case.find("failure") is not None or case.find("error") is not None:
                failed.append(f"{cls.split('.')[-1]}.{case.get('name')}")
    if not cases:
        return {"rate": None, "cases": 0, "failed": [], "classes": []}
    return {"rate": (cases - len(failed)) / cases, "cases": cases, "failed": failed, "classes": sorted(names)}


def load_png(path):
    return np.asarray(Image.open(path).convert("RGB"))


def load_board(path):
    """Loads a rendered board JSON and refuses stale renders from before text_boxes existed."""
    with open(path) as fp:
        board = json.load(fp)
    if "text_boxes" not in board:
        raise SystemExit(f"{os.path.basename(path)} predates text boxes; run without --skip-boards")
    return board


def compare(capture, board, app, board_img, app_img, exceptions, dsf):
    excluded = set(exceptions.get("elements", {}))
    board_els = [e for e in board["elements"] if e["name"] not in excluded and e["w"] > 0 and e["h"] > 0]
    nodes = [n for n in app["nodes"] if n["tag"] not in excluded]
    pairs, missing, extra = m.pair(board_els, nodes)
    good = [(b, n) for b, n in pairs if m.within(b, n)]
    geometry = len(good) / len(board_els) if board_els else 1.0

    # Fills: the board elements that paint a background. Both sides are sampled from their screenshots,
    # in a band just inside the box's edges (metrics.edge_colour), so alpha and drawing order count the same way.
    fills, paired = [], {id(b): n for b, n in pairs}
    a_scale = app_img.shape[1] / app["w"]
    for b in board_els:
        rgba = m.css_rgba(b.get("background"))
        if not rgba or rgba[3] == 0:
            continue
        n = paired.get(id(b))
        want = m.edge_colour(board_img, tuple(v * dsf for v in (b["x"], b["y"], b["w"], b["h"])), scale=dsf)
        got = None if n is None else m.edge_colour(
            app_img, tuple(v * a_scale for v in (n["x"], n["y"], n["w"], n["h"])), scale=a_scale)
        de = m.delta_e(want, got) if want and got else None
        fills.append({"name": b["name"], "board": want, "app": got, "delta_e": de})
    compared = [f["delta_e"] for f in fills if f["delta_e"] is not None]
    colour = sum(1 for f in fills if f["delta_e"] is not None and f["delta_e"] < m.MAX_DELTA_E) / len(fills) if fills else None

    # Whole screen: the app shot is scaled to the board shot's pixels (they differ by rounding only).
    # Text boxes are masked out of the judged SSIM so Chromium/Skia text anti-aliasing does not decide
    # the verdict; text-box SSIM and raw whole-screen SSIM are reported separately.
    if app_img.shape[:2] != board_img.shape[:2]:
        app_img = np.asarray(Image.fromarray(app_img).resize((board_img.shape[1], board_img.shape[0]), Image.BILINEAR))
    raw_ssim, smap = m.ssim(board_img, app_img)
    mask = m.text_mask(smap.shape, board.get("text_boxes", []), scale=dsf)
    non_text_ssim, text_ssim = m.split_ssim(smap, mask)
    judged_ssim = raw_ssim if non_text_ssim is None else non_text_ssim
    return {
        "elements": len(board_els), "paired": len(pairs), "within": len(good),
        "geometry": geometry, "missing": [e["name"] for e in missing], "extra": [n["tag"] for n in extra],
        "offsets": [{"name": b["name"], "dx": round(n["x"] - b["x"], 2), "dy": round(n["y"] - b["y"], 2),
                     "dw": round(n["w"] - b["w"], 2), "dh": round(n["h"] - b["h"], 2)}
                    for b, n in pairs if not m.within(b, n)],
        "fills": fills, "colour": colour, "max_delta_e": max(compared) if compared else None,
        "ssim": judged_ssim, "ssim_text": text_ssim, "ssim_raw": raw_ssim,
        "text_coverage": float(mask.mean()),
    }, smap, app_img


def heatmap(board_img, smap):
    """The board in grey with SSIM loss in red: the redder, the less alike."""
    grey = m.luma(board_img) * 0.35
    loss = np.clip((1 - smap) * 1.5, 0, 1)
    rgb = np.stack([grey + loss * (255 - grey), grey * (1 - loss), grey * (1 - loss)], axis=-1)
    return Image.fromarray(rgb.clip(0, 255).astype(np.uint8))


def side_by_side(board_img, app_img, heat, path, width=300):
    tiles = [Image.fromarray(board_img), Image.fromarray(app_img), heat]
    h = round(board_img.shape[0] * width / board_img.shape[1])
    out = Image.new("RGB", (width * 3 + 16, h), (40, 40, 40))
    for i, t in enumerate(tiles):
        out.paste(t.resize((width, h), Image.LANCZOS), (i * (width + 8), 0))
    out.save(path)


def main():
    a = parse_args()
    only = set(a.screens.split(",")) if a.screens else None
    os.makedirs(a.out, exist_ok=True)
    plan = plan_of(a.canvas, only)
    plan_file = os.path.join(a.out, "plan.json")
    json.dump({"captures": plan}, open(plan_file, "w"), indent=1)
    exceptions = json.load(open(a.exceptions)) if os.path.exists(a.exceptions) else {}

    if not a.skip_boards:
        run_boards(a, plan)
    if not a.skip_app:
        run_app(a, plan_file, only)
    tokens = token_results(a.token_tests)

    index = json.load(open(os.path.join(a.out, "boards", "index.json")))
    img_dir = os.path.join(a.out, "images")
    os.makedirs(img_dir, exist_ok=True)
    screens = {}
    for cap in plan:
        board_file = os.path.join(a.out, "boards", cap["board"])
        app_file = os.path.join(a.out, "app", f"{cap['screen']}@{cap['device']}")
        entry = {"board": cap["board"], "title": cap["title"], "size": [cap["w"], cap["h"]]}
        if not os.path.exists(board_file + ".json"):
            entry.update(status="no-board-render")
        elif not os.path.exists(app_file + ".json"):
            entry.update(status="no-scene")
        else:
            board = load_board(board_file + ".json")
            with open(app_file + ".json") as fp:
                app = json.load(fp)
            board_img, app_img = load_png(board_file + ".png"), load_png(app_file + ".png")
            if cap.get("clip"):
                board, board_img = m.clip_board(board, board_img, cap["clip"], cap["clip_index"], board["dsf"])
            r, smap, app_img = compare(cap, board, app, board_img, app_img, exceptions, board["dsf"])
            name = f"{cap['screen']}@{cap['device']}"
            heat = heatmap(board_img, smap)
            heat.save(os.path.join(img_dir, f"{name}.heat.png"))
            side_by_side(board_img, app_img, heat, os.path.join(img_dir, f"{name}.side.png"))
            v = m.verdict(tokens=tokens["rate"], geometry=r["geometry"], missing=len(r["missing"]),
                          max_delta_e=r["max_delta_e"], ssim=r["ssim"])
            entry.update(status="measured", **r, **v,
                         score=m.score(tokens=tokens["rate"], geometry=r["geometry"], colour=r["colour"], ssim=r["ssim"]),
                         images={"side": f"images/{name}.side.png", "heat": f"images/{name}.heat.png"})
        screens.setdefault(cap["screen"], {})[cap["device"]] = entry

    rows = []
    for screen, devices in sorted(screens.items()):
        measured = [d for d in devices.values() if d["status"] == "measured"]
        implemented = len(measured) == len(devices)
        score = sum(d["score"] for d in measured) / len(devices) if measured else 0.0
        rows.append({"screen": screen, "implemented": implemented, "score": score,
                     "pass": implemented and all(d["pass"] for d in measured), "devices": devices})
    impl = [r["score"] for r in rows if r["implemented"]]
    report = {
        "generated": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "canvas": os.path.abspath(a.canvas),
        "thresholds": {"tokens": 1.0, "geometry": m.MIN_GEOMETRY, "tolerance_dp": m.TOLERANCE_DP,
                       "missing": 0, "delta_e_below": m.MAX_DELTA_E, "ssim": m.MIN_SSIM},
        "tokens": tokens,
        "match_rate": sum(r["score"] for r in rows) / len(rows) if rows else 0.0,
        "match_rate_implemented": sum(impl) / len(impl) if impl else None,
        "screens_total": len(rows), "screens_implemented": len(impl), "screens_passed": sum(r["pass"] for r in rows),
        "platform_exceptions": platform_exceptions(index, exceptions),
        "screens": rows,
    }
    json.dump(report, open(os.path.join(a.out, "report.json"), "w"), indent=1)
    open(os.path.join(a.out, "report.html"), "w").write(render_html(report))
    print(f"match rate {report['match_rate']:.1f} over {len(rows)} screens "
          f"({len(impl)} implemented, {report['screens_passed']} pass); tokens {fmt_rate(tokens['rate'])}")
    print(os.path.join(a.out, "report.html"))


def platform_exceptions(index, exceptions):
    font = ("Boards drawn with Robolectric's static Roboto faces (300/400/500/700/900), the same files the app "
            "screenshots use; weight 600 resolves to 500 (Medium) on both sides. Devices use variable Roboto."
            if index.get("fonts") == "local" else
            "Boards drawn with Google Fonts Roboto; app screenshots with Robolectric's static Roboto faces.")
    return [
        {"what": "status bar", "note": "Robolectric draws no system bars and the boards draw none; nothing is masked."},
        {"what": "font hinting", "note": "Chromium and Robolectric's Skia anti-alias text differently, so the judged "
                                         "SSIM masks out the board's rendered text boxes and reports text-box SSIM "
                                         "separately. " + font},
        {"what": "Android SDK", "note": "Captures run on Robolectric SDK 34 (the build's test JVM is Java 17)."},
    ] + [{"what": f"element {n}", "note": r} for n, r in exceptions.get("elements", {}).items()]


def fmt_rate(v):
    return "n/a" if v is None else f"{v * 100:.0f}%"


def render_html(r):
    e = html.escape
    t = r["thresholds"]
    rows = []
    for s in r["screens"]:
        cells = []
        for d in DEVICE_ORDER:
            c = s["devices"].get(d)
            if c is None:
                cells.append("<td class=na>—</td>")
            elif c["status"] != "measured":
                cells.append(f"<td class=na>{e(c['status'])}<br><small>{e(c['board'])}</small></td>")
            else:
                text_note = "" if c.get("ssim_text") is None else f" (text {c['ssim_text']:.3f})"
                cells.append(
                    f"<td class={'pass' if c['pass'] else 'fail'}><a href='#{e(s['screen'])}-{d}'><b>{c['score']:.0f}</b></a>"
                    f"<br><small>geom {fmt_rate(c['geometry'])} · ΔE {'—' if c['max_delta_e'] is None else round(c['max_delta_e'], 2)}"
                    f" · SSIM {c['ssim']:.3f}{text_note}<br>missing {len(c['missing'])} · extra {len(c['extra'])}</small></td>")
        verdict = "PASS" if s["pass"] else ("fail" if s["implemented"] else "not implemented")
        rows.append(f"<tr><th>{e(s['screen'])}</th>{''.join(cells)}<td>{s['score']:.1f}</td><td>{verdict}</td></tr>")
    details = []
    for s in r["screens"]:
        for d in DEVICE_ORDER:
            c = s["devices"].get(d)
            if not c or c["status"] != "measured":
                continue
            fills = "".join(f"<li>{e(f['name'])}: board {f['board']} app {f['app']} ΔE "
                            f"{'—' if f['delta_e'] is None else round(f['delta_e'], 2)}</li>" for f in c["fills"])
            offs = "".join(f"<li>{e(o['name'])}: dx {o['dx']} dy {o['dy']} dw {o['dw']} dh {o['dh']}</li>" for o in c["offsets"])
            ssim_line = f"SSIM {c['ssim']:.3f} non-text"
            if c.get("ssim_text") is not None:
                ssim_line += f", {c['ssim_text']:.3f} text ({c['text_coverage']:.0%} of pixels)"
            if c.get("ssim_raw") is not None:
                ssim_line += f", {c['ssim_raw']:.3f} whole screen"
            details.append(f"""
<section id="{e(s['screen'])}-{d}"><h2>{e(s['screen'])} · {d} <small>({e(c['board'])}, {c['size'][0]}×{c['size'][1]} dp)</small></h2>
<p>Score {c['score']:.1f} — {'PASS' if c['pass'] else 'fail: ' + e('; '.join(c['reasons']))}</p>
<p>Elements {c['elements']}, paired {c['paired']}, within ±{t['tolerance_dp']:g} dp {c['within']}
 ({fmt_rate(c['geometry'])}); {ssim_line}; colour {fmt_rate(c['colour'])}</p>
<figure><img src="{e(c['images']['side'])}" alt="board, app and SSIM heatmap"><figcaption>Board · App · SSIM loss (red)</figcaption></figure>
<details><summary>Missing tags ({len(c['missing'])})</summary><p>{e(', '.join(c['missing']))}</p></details>
<details><summary>Extra tags ({len(c['extra'])})</summary><p>{e(', '.join(c['extra']))}</p></details>
<details><summary>Out of tolerance ({len(c['offsets'])})</summary><ul>{offs}</ul></details>
<details><summary>Fills ({len(c['fills'])})</summary><ul>{fills}</ul></details>
</section>""")
    exc = "".join(f"<li><b>{e(x['what'])}</b>: {e(x['note'])}</li>" for x in r["platform_exceptions"])
    tok = r["tokens"]
    tok_line = (f"{fmt_rate(tok['rate'])} of {tok['cases']} token checks ({e(', '.join(tok['classes']))})"
                + (f"; failing: {e(', '.join(tok['failed']))}" if tok["failed"] else "")
                if tok["cases"] else "no token test results found")
    impl = r["match_rate_implemented"]
    return f"""<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Velo redesign fidelity</title>
<style>
body{{font:14px/1.45 system-ui,sans-serif;margin:24px;color:#1d1d1f;background:#fafafa}}
table{{border-collapse:collapse;margin:12px 0}} th,td{{border:1px solid #ddd;padding:6px 10px;text-align:left;vertical-align:top}}
td.pass{{background:#e7f6ea}} td.fail{{background:#fdecea}} td.na{{color:#888}}
img{{max-width:100%;border:1px solid #ccc}} section{{margin:28px 0}} small{{color:#555}}
</style></head><body>
<h1>Velo redesign fidelity</h1>
<p>Generated {e(r['generated'])}. Match rate <b>{r['match_rate']:.1f}</b> over {r['screens_total']} screens
 (unimplemented screens count as 0); {r['screens_implemented']} implemented
 {'' if impl is None else f'(mean {impl:.1f})'}; {r['screens_passed']} pass.</p>
<p>Tokens: {tok_line}.</p>
<p>Pass: tokens 100%, ≥{t['geometry']:.0%} of elements within ±{t['tolerance_dp']:g} dp, no missing tags,
 fill ΔE₀₀ &lt; {t['delta_e_below']:g}, non-text SSIM ≥ {t['ssim']}. See docs/fidelity.md.</p>
<table><tr><th>Screen</th>{''.join(f'<th>{d}</th>' for d in DEVICE_ORDER)}<th>Score</th><th>Verdict</th></tr>
{''.join(rows)}</table>
<h2>Platform exceptions</h2><ul>{exc}</ul>
{''.join(details)}
</body></html>"""


if __name__ == "__main__":
    main()
