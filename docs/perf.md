# Measuring Velo on a device

`tools/perf` records Perfetto traces of Velo on an Android device in a few fixed scenes and
summarises them. The core's own sections (`Renderer.*`, `Core.*`, from `native/src/term/Trace.zig`)
show up next to the system's: the scheduler, CPU clocks, Compose and the frame timeline. Use it
to compare two builds under the same conditions.

## Setting up

You need a device on adb with Perfetto (Android 11 or later), a release build of the app,
`rex` on this machine with its server running, and `uv` for the analysis scripts.

1. Build a release APK and sign it with the debug key, so that it replaces a debug install
   without losing its data:

   ```sh
   ANDROID_HOME=$HOME/Android/Sdk gradle :app:assembleRelease
   $ANDROID_HOME/build-tools/36.0.0/apksigner sign --ks ~/.android/debug.keystore \
     --ks-pass pass:android --ks-key-alias androiddebugkey --out velo.apk \
     app/build/outputs/apk/release/app-release-unsigned.apk
   ```

   A release build records the core's sections on a userdebug device. Whether a user build
   records them is not known yet.
2. Point the scripts at the device: `export ADB_SERIAL=<serial or host:port>`. The other
   settings are in `tools/perf/perf.env`.
3. Run `tools/perf/setup.sh`. It makes the session `velo-perf` with five tabs of bash.
4. Open that session once in Velo on the device. Velo opens the session it showed last, so
   it is there after every cold start. Use this session only: the device takes the size of
   the terminals it shows.

## Recording

```sh
tools/perf/scenes.sh base                 # every scene, traces named base-<scene>
tools/perf/scenes.sh new title ascii      # some of them
tools/perf/lowend.sh on                   # big cores off, GPU capped (root); `off` undoes it
```

Each scene starts from a cold start of the app, so the glyph atlases start empty.

| Scene | What runs | What it shows |
|---|---|---|
| `ko2350` | 8 s of random common Hangul, 30 lines a second | glyph atlas misses |
| `prose` | 8 s of Korean text, 30 lines a second | misses as a document has them |
| `ascii` | 8 s of random ASCII, 30 lines a second | the steady state |
| `textsize` | Text Size Larger, then Reset | every glyph drawn again |
| `title` | 30 title changes a second for 8 s | state delivery and Compose |
| `overview` | the tab overview, four swipes each way | the overview's frames |
| `overview-out` | the overview open while every tab prints | one atlas per preview |

`prose` prints `load.py prose` (words of 380 syllables, the common ones often) unless
`VELO_PERF_TEXT` names a text file. In `title`, the first tab's label (`t1`) names it, so the
new titles are drawn nowhere. To measure a tab named by its title, as an agent's tab is, clear
the label first: `rex window rename -s velo-perf 1 ""`. The taps are for the LG Velvet in landscape
(2460 x 1080 px, 95 columns); `scenes.sh` lists the `PERF_*` variables for another screen.

## Reading

```sh
uv run --with perfetto python tools/perf/analyze.py build/perf/traces/new-title.pftrace
```

| Script | Answers |
|---|---|
| `analyze.py` | sections, misses per frame, swaps, state deliveries, UI frames, CPU per thread |
| `buckets.py` | the render threads' work per 500 ms |
| `top.py TRACE THREAD` | where one thread's time went |
| `contention.py` | waits for the glyph rasterizer's lock |
| `cpuplace.py` | the cost of one glyph by core kind and clock |
| `compare.py TRACE...` | one line per trace for an A/B table: swaps, CPU per thread, recomposition, missed frames |
| `composables.py TRACE` | which composables a composition-traced run composed, how often and for how long |

### Composition tracing

A release build names no composable in its trace. To see which composables each state delivery
composes again, build a measuring APK with `tools/perf/compose-tracing.patch`: it hands the
composer a tracer that writes every composable it composes into the app's trace, and keeps R8
from removing the composer's trace calls.

```sh
git apply tools/perf/compose-tracing.patch
ANDROID_HOME=$HOME/Android/Sdk gradle :app:assembleRelease
git apply -R tools/perf/compose-tracing.patch
```

Sign and install it as above, record a scene, and read it with `composables.py`. The build is
slower than a release build: compare counts, and times only with each other.

## Pitfalls

- Compare builds of the same commit, apart from the change. Two builds a few UI commits
  apart differ in Compose time by more than most changes do.
- `adb install -r` refuses a lower version code (`INSTALL_FAILED_VERSION_DOWNGRADE`). Check
  that each install says `Success` before you record.
- Run each build at least twice, alternating. The cold start's order varies: other tabs'
  streams can arrive inside a trace.
- The app runs from the APK with JIT after a sideloaded install. `adb shell cmd package
  compile -m speed -f io.github.yhzion.velo` compiles it ahead of time; `--reset` undoes it.
- Traces and APKs fill `/tmp` quickly. When the user's quota is full, a signed APK is cut short
  without an error from the signer, and `adb install` fails with `INSTALL_PARSE_FAILED_NOT_APK`.
  Check it with `apksigner verify`.
- A change that removes work from one thread can make others slower: the cores of a cluster
  share one clock, which drops with the cluster's load. Compare CPU cycles, not only time.

## Results

### 2026-11-27: before any change (Velo 2.12.2, LG Velvet)

The render thread rasterizes a missing glyph through `Canvas.drawText` while it holds the
pane lock.

| Scene | Default | Low-end |
|---|---|---|
| `ko2350`: misses, rasterize total | 2228, 1780 ms | 2261, 1938 ms |
| `ko2350`: builds over 16.7 ms | 35 of 231 | 42 of 243 |
| `textsize`: the longest frame | 44 ms | 86 ms |
| `title`: Compose recomposition | 1381 ms | 2289 ms |
| `title`: app frames that missed their deadline | 75 of 81 | 81 of 84 |
| `overview-out`: first frame of a preview | 65 to 99 ms | about 100 ms |

- One glyph takes 0.2 ms on a big core at 2.1 GHz and about 2 ms on a little core at 600 MHz.
  Compiling the app ahead of time does not change it.
- In `title`, the server sends a title event every 100 ms; each one costs about 17 ms of
  Compose on the main thread. The terminal surface was swapped about 30 times a second,
  although its content did not change.

### 2026-11-27: a frame that draws the same is not swapped

The render thread compares each frame with the one the window shows and skips the swap when
they are the same (`Renderer.drawsShown`). `title`, same commit with and without the change,
three runs each by default and two low-end, mean values:

| | Default before | Default after | Low-end before | Low-end after |
|---|---|---|---|---|
| Terminal surface swaps | 258 | 3 | 242 | 3 |
| Render thread CPU | 643 ms | 162 ms | 796 ms | 116 ms |
| SurfaceFlinger CPU | 2918 ms | 1299 ms | 1975 ms | 862 ms |
| Main thread CPU | 2139 ms | 2355 ms | 3105 ms | 3380 ms |
| App frames that missed their deadline | 77 of 81 | 78 of 81 | 82 of 82 | 81 of 82 |

The four threads use 30 % (default) and 23 % (low-end) less CPU. The main thread does the same
work but takes longer: low-end, it needs the same cycles at a 11 % lower clock; by default, it
runs more often on a little core. The missed frames do not change: they come from Compose
recomposing on every state delivery.

### 2026-11-27: the workspace is composed without the titles

The workspace read the whole state of the connection, so every state delivery composed the
whole screen again: the panes, the rail, the caption. A new title is such a delivery, several
times a second from an agent. The workspace now reads the state without the titles
(`HostConnection.steady`), and what draws a title reads it where it draws it: the caption, the
rail's wheel, the overview's cards while they show, the minimized panes, the pane picker, the
palette and the dialogs. `title`, same commit with and without the change, means of six and two
runs (first tab named by its label) and of four runs each (first tab named by its title):

| | Label before | Label after | Title before | Title after |
|---|---|---|---|---|
| Main thread CPU | 2447 ms | 1801 ms | 2568 ms | 1868 ms |
| Compose recomposition | 1617 ms | 668 ms | 1586 ms | 832 ms |
| Main-thread frames over 16.7 ms | 75 | 4 | 79 | 35 |
| App frames drawn | 84 | 3 | 82 | 82 |
| App frames that missed their deadline | 78 | 3 | 79 | 78 |
| Compose's RenderThread CPU | 546 ms | 34 ms | 518 ms | 584 ms |
| SurfaceFlinger CPU | 1303 ms | 550 ms | 1291 ms | 1178 ms |

- With the label, a title changes nothing on the screen, and the screen draws nothing.
- With the title, the caption and the rail's wheel are composed again for each title, and only
  they (`composables.py`). The frames still miss their deadline: each needs about 10 ms of the
  main thread and 7 ms of RenderThread on the Velvet's little cores at a low clock.

