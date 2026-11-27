# Redesign fidelity

`tools/fidelity` measures how closely the app matches the redesign canvas, per screen and device
shape, and writes a match rate and a pass or fail verdict for every screen.

## Running it

You need the canvas project folder (`canvas.json` and the `*.dc.html` boards, downloaded from the
canvas artifact) and the Design type's runtime, `artifact-type/dc-runtime.js` of the same artifact.
Put the runtime at `<canvas>/../artifact-type/dc-runtime.js` or pass `--runtime`.

```sh
python3 tools/fidelity/fidelity.py --canvas <canvas>/project
python3 tools/fidelity/fidelity.py --canvas <canvas>/project --screens settings-updates,settings-about
```

It takes about a minute for one screen and a few minutes for the whole canvas. The results go to
`build/fidelity/`:

| File | Content |
|---|---|
| `report.html` | Screen by device table, then each capture: board, app and SSIM heatmap side by side, missing and extra tags, out-of-tolerance boxes, fills |
| `report.json` | The same numbers for tools |
| `boards/<Board>.json`, `.png` | Every `data-el` element of a board (box in dp, text, colours), its rendered text boxes, and the board's render |
| `app/<screen>@<device>.json`, `.png` | Every testTag node of the app scene: box in dp, text; the app's screenshot |
| `plan.json` | What was captured: screen, device, board and size |

`--skip-boards` and `--skip-app` reuse the renders already in `build/fidelity/`. Requirements:
Python 3 with Pillow and NumPy, Node 22 or later, and Chromium (`FIDELITY_CHROME`, by default the
Playwright copy under `~/.cache/ms-playwright/chromium-1217`).

## How it measures

**Screens.** Device boards are named `Set|Start|Ws` + `Bar|Closed|Open` + name. A board's screen
id drops the device: `SetBarUpdates`, `SetClosedUpdates` and `SetOpenUpdates` are
`settings-updates`. The width tells the device: 393 dp bar phone, 475 dp Fold8 closed, 821 dp Fold8
open. A board taller than its device is a scrolling page drawn at full length; the app is then drawn
at the same height.

**Boards.** `boards.mjs` serves the canvas folder on a local port, answers `support.js` with the
Design runtime, and opens each board in headless Chromium at density 2.625. This renders the
boards with the canvas's own runtime, so `dc-import`, `sc-if` and `sc-for` behave as on the canvas.
It needs no npm packages: Node drives Chromium over the DevTools protocol. Roboto requests are
answered with the Roboto files Robolectric draws the app with, when Robolectric has unpacked them
(`/tmp/robolectric-nativeruntime-assets`). Otherwise Google Fonts loads (`--fonts web`).

**App.** `FidelityCaptureTest` (app/src/test, Robolectric with native graphics, SDK 34) draws each
scene of `FidelityScenes` at each board's size and density 2.625. It saves a Roborazzi screenshot
and the box of every node with a testTag. It runs only when Gradle gets `-Pvelo.fidelity.out`, so
the normal unit test run skips it. A screen is measured once it has a scene. Add one in
`FidelityScenes.kt`, keyed by the screen id.

**Previews.** The splash has no device boards: its preview board, Main, draws the three devices
side by side, each a `screen-splash` element, and plays the 1.4 s timeline once. `PREVIEWS` in
`fidelity.py` lists such screens: `splash-0ms`, `splash-240ms`, `splash-480ms`, `splash-660ms`,
`splash-1000ms` and `splash` (1400 ms) are Main with its animations paused at that time
(`boards.mjs` renders `Main@<ms>`), and `splash-reduced` is Main with prefers-reduced-motion. Each
device is cut out of the board at its `screen-splash` box, with the elements inside it, and
compared like a device board. The app's scenes draw `SplashScreen(splashFrame(t))`.

**Pairing.** A board element's `data-el` name pairs with the app node whose testTag is the same
string. When a name repeats, the n-th element pairs with the n-th node, in document order on the
board and tree order in the app.

## The numbers

| Layer | Measure | Pass |
|---|---|---|
| Tokens | Share of passing cases in the token test (`RedesignTokensTest`, against `app/src/test/resources/redesign/tokens.json`) | 100% |
| Geometry | Share of the board's elements whose app node is within ±2 dp in x, y, width and height | ≥ 95% |
| Missing tags | Board elements with no app node of that name | 0 |
| Colour | CIEDE2000 difference of each fill: board elements with a background colour, sampled on both screenshots | every ΔE < 3 |
| Whole screen | Non-text SSIM: mean of the SSIM map (luma, Gaussian window 11, σ 1.5) outside the board's rendered text boxes | ≥ 0.92 |

A screen passes when every device it has passes all five. A missing element counts against
geometry and, when it has a fill, against colour. Extra tags (app nodes the board does not name)
are listed, not penalised.

The fill colour is the most frequent colour in a band 1.5 to 4 dp inside the element's edges, over
the middle half of each side. The band misses a 1 dp border and rounded corners. For a container it
usually misses the children too. Both sides are sampled the same way.

**Non-text SSIM.** Chromium and Robolectric's Skia anti-alias text differently (see Platform
exceptions). On these dark screens, thin strokes that differ by half a pixel score about 0.40 to
0.65 locally. Whole-screen SSIM therefore fell below 0.90 on matching screens with much text
(`start-2-empty`, and `settings-updates` on the bar phone), with geometry at 100% and every fill at
ΔE 0. A lower threshold for such screens would also pass real layout and style changes, and it
would need a text-density cutoff. So the tool leaves the text out.

`boards.mjs` records the boxes of the board's visible text: `Range.getClientRects()` on text nodes
and the occupied lines of text inputs and textareas (measured via a hidden mirror element so
wrapping, scroll offsets and placeholders are honoured without hiding empty area inside a control),
each clipped to its control's content box and overflow-clipping ancestors. `compare` dilates that
mask by the SSIM window's 5 px radius (`(11 - 1) / 2`) before averaging non-text SSIM, so no
judged map position reads text pixels through its 11×11 Gaussian window. Fills, borders, corners,
dividers, buttons, switches, SVG icons and spacing stay in the measure. A symbol set as text, such
as a step's ✓, leaves with the text. Without text, matching screens score 0.935 to 0.999. The
measured regressions score 0.766 to 0.917: a wrong background, step or page, a card 8 dp off, a
missing card fill. The threshold is therefore 0.92, up from 0.90. The report also gives `ssim_text`
(inside the unexpanded text boxes, with `text_coverage`) and `ssim_raw` (the whole screen). The
heatmap shows the whole map.

Missing tags and geometry still catch a missing text element or a box that moves more than 2 dp.
Text that spills out of the board's line box still lowers non-text SSIM. **Blind spot:** a change
of text colour, weight, copy or size inside the board's text box is caught by neither the verdict
nor the score. `fidelity.py` does not compare an element's `text` or `color`, and the colour check
samples fills only. `ssim_text` spans about 0.39 to 0.84 on matching captures, so it cannot serve
as a floor.

**Score.** A capture's score is the mean of four rates, 0 to 100: tokens, geometry, the share of
fills with ΔE < 3, and non-text SSIM. With no token result, tokens count as 0. A board without fills
leaves colour out. A screen's score is the mean over its devices; a device without a capture counts
as 0. The overall **match rate** is the mean over every screen of the canvas, so an unimplemented
screen counts as 0. The report also gives the mean over implemented screens.

SSIM alone is weak on these dark screens: two unrelated settings pages score about 0.75 to 0.91,
because large flat areas agree. Read it together with geometry, which carries most of the verdict.

## Platform exceptions

The report lists these apart from the scores:

- **Status bar.** Robolectric draws no system bars, and the boards draw none. Nothing is masked.
- **Font hinting.** Chromium and Robolectric's Skia anti-alias text differently. The judged SSIM
  masks out the board's rendered text boxes and reports text-box SSIM separately. Because nothing
  else in the tool compares element `text` or ink `color`, a colour, weight, copy or within-box size
  difference inside a board text box is not caught by the verdict or score (only missing tags, box
  shifts over 2 dp and text spilling outside the board's line box are caught).
- **Font weights.** Both sides use Robolectric's static Roboto faces (300, 400, 500, 700, 900).
  Weight 600 draws as 500 on both: Robolectric's `sans-serif` family lists Medium before Bold, and
  Android's closest match takes the first of the two, so `boards.mjs` gives 600 the Medium face too.
  Devices use variable Roboto and draw 600.
- **SDK.** Captures run on Robolectric SDK 34. Newer SDKs need Java 21, and the build runs tests on
  Java 17.

To leave an element out of the measures on purpose, name it in `tools/fidelity/exceptions.json`
with the reason: `{"elements": {"rail-clock": "the time differs per run"}}`.

## Tests

```sh
python3 -m unittest discover -s tools/fidelity
ANDROID_HOME=$HOME/Android/Sdk gradle --no-daemon :app:testDebugUnitTest -x zigCore -x tailscale --tests 'io.github.yhzion.velo.fidelity.*'
```

The capture dependencies (Robolectric, Roborazzi, Compose UI test) are on the test classpath only.
They are not in a release build, so `licenses/android/RELEASE-RUNTIME.md` is unchanged.
