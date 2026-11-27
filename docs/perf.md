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
`VELO_PERF_TEXT` names a text file. The taps are for the LG Velvet in landscape
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

## Pitfalls

- Compare builds of the same commit, apart from the change. Two builds a few UI commits
  apart differ in Compose time by more than most changes do.
- `adb install -r` refuses a lower version code (`INSTALL_FAILED_VERSION_DOWNGRADE`). Check
  that each install says `Success` before you record.
- Run each build at least twice, alternating. The cold start's order varies: other tabs'
  streams can arrive inside a trace.
- The app runs from the APK with JIT after a sideloaded install. `adb shell cmd package
  compile -m speed -f io.github.yhzion.velo` compiles it ahead of time; `--reset` undoes it.
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
