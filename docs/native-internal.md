# libvelocore internal module boundary

The native core separates protocol and rendering responsibilities. This file describes the boundary.

| Layer | Files |
|---|---|
| Core | `native/build.zig`, `native/src/c.h`, `native/src/jni.zig` (root of the shared library), `native/src/net/**` (TLS, WebSocket), `native/src/rex/**` (control/data protocol, state, onState JSON, intents), `native/src/test/**` (fixtures and host probe) |
| Terminal | `native/src/term/**` (libghostty-vt wrapper, renderer, keys, selection) |

Core imports `term/Pane.zig` and never calls libghostty-vt directly. Term never touches
the network, JSON or JNI.

Terminal size and block ownership are Core's: when to send `session.own_block` and
`com.superlogical.terminal.resize` is decided in `rex/Core.zig` with `rex/SizeRules.zig`,
from `paneSurface`, `panePreview`, input, settings and the app's `{"op":"fit"}` and
`{"op":"touch"}` reports. `Pane` only measures (`fitGrid`). Whether a pane is shown or
only previewed is one state, `SizeRules.State` (`shown`, `preview`): a preview takes no
block and asks for no size.

## C headers (Zig 0.17 has no `@cImport`)

- `native/src/c.h` contains only `#include <ghostty/vt.h>`. The ghostty headers use only
  fixed-width ints, `size_t`, `bool` and `INT_MAX`, identical on x86_64-linux and
  aarch64-linux-android. `build.zig` translates `c.h` with `b.addTranslateC` for the HOST
  target (`b.graph.host`, `link_libc = true`) and adds the result as import `"c"` to every
  module (`const c = @import("c");`). Translating for the Android target does not work:
  NDK headers `#include_next` into the host's glibc headers.
- Android/JNI/EGL/GLES symbols are NOT translated. Declare the needed functions and
  constants by hand as `extern "EGL" fn …`, `extern "GLESv3" fn …`, `extern "android" fn …`,
  `extern "log" fn …`: EGL/GLES/ANativeWindow in `native/src/term/gl.zig` (Term), JNI in
  `native/src/sys/jni.zig` (Core). Use C ABI types (`c_int`, `?*anyopaque`,
  `[*:0]const u8`). JNI: hand-written `JNIEnv`/`JavaVM` function tables with index-accurate
  slots per NDK 30 `jni.h`, covering only the functions used.

## `native/src/term/Pane.zig`

```zig
pub const GlyphBitmap = struct {
    /// Tightly packed. len == w*h → A8 coverage; len == w*h*4 → RGBA8888 premultiplied.
    pixels: []const u8,
    w: u32,
    h: u32,
};

/// Implemented by Core (calls CoreListener.rasterizeGlyph over JNI). Called only from
/// the pane's render thread, only on atlas misses that `cache` does not have, after the
/// frame's build and without the pane lock (`Renderer.completeFrame`). Returned memory is
/// valid until the next call on the same thread.
pub const GlyphSource = struct {
    ctx: *anyopaque,
    rasterize: *const fn (ctx: *anyopaque, utf8: []const u8, bold: bool, italic: bool,
        cell_w: u32, cell_h: u32, columns: u32) ?GlyphBitmap,
    /// What this source drew, for every pane that has the source (one for the process,
    /// `sys/glyphs.zig`). `Pane.setFont` empties it.
    cache: ?*GlyphCache = null,
    /// Who keeps the source alive: its owner and each render thread's link (#178, below).
    /// Null: the source outlives every render thread (the host tests', the probe's).
    refs: ?*Refs = null,

    pub const Refs = struct {
        count: std.atomic.Value(u32) = .init(1), // the owner's
        free: *const fn (refs: *Refs) void, // called once, by the last drop
        pub fn take(r: *Refs) void;
        pub fn drop(r: *Refs) void;
    };
};

pub const Theme = struct {
    foreground: ?[3]u8 = null,
    background: ?[3]u8 = null,
    cursor: ?[3]u8 = null,
    /// (index, rgb) overrides of the 256-color palette.
    palette: []const struct { u8, [3]u8 } = &.{},
};

pub const Pane = struct {
    pub fn create(gpa: std.mem.Allocator, glyphs: GlyphSource) !*Pane;
    pub fn destroy(self: *Pane) void;

    // Stream input (called on Core's network thread). Core removes Rex APCs
    // (`ESC _ rex1;<nonce>;… ESC \`) before calling these.
    /// Feed bytes that start with a Ghostty snapshot. Returns how many bytes the
    /// snapshot used, or error.NeedMoreData until the whole snapshot has arrived
    /// (Core keeps buffering and calls again with the longer slice).
    pub fn restoreSnapshot(self: *Pane, bytes: []const u8) !usize;
    pub fn write(self: *Pane, bytes: []const u8) void;
    /// Server terminal size from Rex `resize`.
    pub fn resize(self: *Pane, cols: u16, rows: u16, cell_w_px: u32, cell_h_px: u32) void;
    pub fn setTheme(self: *Pane, theme: Theme) void;
    /// OSC 0/2 title, or null. Copies into `buf`.
    pub fn title(self: *Pane, buf: []u8) ?[]const u8;
    /// Increments whenever title or screen content changed (Core uses it to republish).
    pub fn generation(self: *const Pane) u64;

    // Rendering (called from JNI/UI thread; Pane owns its render thread + EGL context).
    /// Make `window` the pane's surface; consumes the window reference.
    /// A window the pane draws to already is taken in place (see "Surfaces" below).
    pub fn attachSurface(self: *Pane, window: *anyopaque /* ANativeWindow */, width_px: u32, height_px: u32) !void;
    pub fn detachSurface(self: *Pane) void;
    /// Local text scale (pinch). The core asks the server for the size that follows
    /// from it when the pinch ends (`fitGrid`).
    pub fn setScale(self: *Pane, scale: f32) void;
    /// Columns and rows of the local cell size (font size × scale) that fit a surface
    /// of this size less the padding on every side, at least 1 × 1, and that cell size
    /// in px. No side effects: the core decides what to ask the server for.
    pub const Fit = struct { cols: u16, rows: u16, cell_w: u16, cell_h: u16 };
    pub fn fitGrid(self: *Pane, surface_w: u32, surface_h: u32) Fit;
    /// Base font size in px at scale 1 (from settings).
    pub fn setFontSizePx(self: *Pane, px: f32) void;
    /// Margin in px around the grid (keeps text clear of rounded pane corners).
    pub fn setPaddingPx(self: *Pane, px: f32) void;
    /// Drag the view by (dx, dy) px: a grid larger than the surface moves first; what is
    /// left of a vertical drag scrolls through scrollback one row per cell height.
    pub fn pan(self: *Pane, dx: f32, dy: f32) void;
    /// action: 0 down, 1 move, 2 up (selection gestures). x/y in surface px.
    pub fn touch(self: *Pane, action: u8, x: f32, y: f32) void;
    /// Selected text, or the visible screen text when nothing is selected. Caller frees.
    pub fn copyText(self: *Pane, gpa: std.mem.Allocator) ![]u8;
    /// True when the terminal has a selection, which `copyText` then copies.
    pub fn hasSelection(self: *Pane) bool;
    /// End the selection, if there is one: `copyText` copies the screen again.
    pub fn clearSelection(self: *Pane) void;

    /// Encode an Android key event with libghostty-vt's key encoder using the terminal's
    /// current modes. Returns bytes to send with com.superlogical.terminal.write
    /// (empty for keys that produce nothing). action: 0 down, 1 up, 2 repeat.
    pub fn encodeKey(self: *Pane, out: []u8, key_code: i32, meta_state: i32, unicode: u32, action: u8) []const u8;
};
```

All methods are thread-safe with respect to each other (Pane holds its own mutex).
Rendering: OpenGL ES 3, instanced cells from libghostty-vt render state (dirty rows),
A8/RGBA glyph atlas (kept through a live pinch, its glyphs stretched to the cells and
sampled LINEAR, and drawn again once the scale settles), box drawing U+2500–U+259F as
geometry spanning the full cell,
cursor, selection, Kitty images from the render state image iterator.
The [Kitty graphics protocol](https://sw.kovidgoyal.net/kitty/graphics-protocol/#the-graphics-escape-code)
transports RGB (`f=24`), RGBA (`f=32`), or PNG (`f=100`). Other source formats
such as SVG or JPEG must first be rasterized by the sending program. Remote
senders should stream the bytes, not refer to files or shared memory on the host.

### Surfaces

A pane has at most one surface. `term/` does not know whether it is the surface of a
shown pane (`Core.paneSurface`) or a preview (`Core.panePreview`): the tab overview keeps
every pane's view and only moves it, so a preview draws exactly what a shown pane draws
(same cell size, padding, pan and viewport). What differs is the size rules, and that
state is `SizeRules.State` in Core.

Attach in place. `attachStep(current, alive, wanted)` is the whole decision of what an
attach does with the renderer, and `attachRefs(action, started, given_up)` the whole
decision of what it does with the window references; both are pure functions tested on
the host.

| `wanted` window | Step | What happens |
|---|---|---|
| the window the pane draws to, renderer alive | `keep` | The render thread, EGL surface, EGL context and glyph atlas stay. The pane takes the new size and draws one frame. |
| another window, a first one, or the same one after its renderer failed | `replace` | The old render thread is joined (one that ended by itself too), or given up after 2 s (200 ms right after another give-up), then a new one starts. |
| none (`detachSurface`) | `detach` | The render thread is joined, or given up after 2 s (200 ms right after another give-up). |

Limit. `keep` is decided from `render_failed` under the pane lock, and the render thread
sets that flag only after a swap has failed; the swap runs without the lock. An attach in
place that falls between the build of a frame and the mark for its failed swap therefore
takes `keep` and returns success for a renderer that is ending: the window keeps its last
good frame, and the ended thread is joined and replaced only by the next attach
(`replace`) or detach. `term/` does not report a failure to the app. A surface that dies
is detached by `surfaceDestroyed` right after, so what remains is a context lost at the
moment of an attach in place, healed by the next attach.

References. An attach meets two: the pane's own to the window it draws to (`current`)
and the one the caller passed in with the window it asks for (`incoming`). The pane ends
every attach with one reference to the window it draws to and no other:

| Step | `current` | `incoming` |
|---|---|---|
| `keep` | held | released: the pane has its own to this window |
| `replace`, the new renderer started | released | held: it is the pane's now |
| `replace`, the new renderer did not start | released | released: the pane draws to no window |
| `detach` | released | none was passed |

For a `replace` or `detach` whose old render thread the pane gave up (below), `current` is
`thread`: the reference stays with that thread, whose EGL surface is still on the window.

`Pane.setSurface` releases what `attachRefs` says and nothing else, and only after the
old render thread has ended, because that thread's EGL surface is on the window
`current` refers to (a host test with a real thread checks the order). `startRenderer`
and `stopRenderer` release nothing, and neither does the render thread (it owns the EGL
surface and context and its GL objects, not the window), unless the pane gave it up. On
the host there is no window to release, so the tests count the releases through
`Pane.zig`'s `host_release`.

Two windows are the same when their addresses are equal. That is an identity because the
pane holds a reference to its window for as long as it draws to it, so no other window
can have that address meanwhile. So a preview becomes the shown pane (and back) without
a new EGL surface, and a `surfaceChanged` for a new size does not start a new renderer.

Size. The renderer draws to the size of the EGL surface's current buffer
(`eglQuerySurface`), and that is the pane's size for pan limits. The buffer follows the
window's size one buffer late, so after an attach in place with another size the size
passed in stands and the render thread draws `frames_after_resize` more frames on its
own; by then the buffer has the new size, and from then on the buffer's size is the
pane's size again. A frame counts when it was built: it is drawn next, and if the window
does not take it the thread ends, so the count no longer matters. A frame that could not
be built does not count and does not make the thread draw again on its own; it waits for
the next thing to draw. The size is not frozen by that: every build, one that fails
later too, first reads the buffer's size and adopts it for the pane when it differs from
the last one read, whatever the count.

A new render thread's buffers go to the window scaled (`SCALE_TO_WINDOW`, through the
window's `perform` hook) until 150 ms after its first swap and a buffer of the window's size,
with the window the size it had at the previous swap, then FREEZE as before, so that BLAST
rejects none while the window returns: a buffer it rejects during a window sync stops the
swap for good (#117; logcat `renderer freezes its buffers after … ms`). A pane that
draws nothing asks for a frame of its own when the hold ends (`Pane.frame_due_ns`, woken in
`run`'s wait), then every 50 ms while it is still scaled, 8 times at most; after that it asks
for none, and its next frame can still go back to FREEZE. The swap that goes back can still
be rejected if the window's update lands between its check and the queue: on the Velvet this
happens in about 2–4% of new threads, during the keyboard's resize, harmless without a window
sync but a hang under one; such a thread is given up (below) rather than joined for good.
The design assumes the driver queues inside `eglSwapBuffers` on the calling thread (Adreno
does; other GPUs are not checked). The Vulkan swapchain sets
`SCALE_TO_WINDOW` on its window, so where GLES is ANGLE over Vulkan, FREEZE lasts only until
ANGLE's next swapchain and the window stays scaled after that, as all buffers were before
#117.

A render thread that does not end within `stop_wait_ns` (2 s) of the pane asking is given up
(#159): main waits for it with `Core.lock` held (`surfaceDestroyed` → `paneSuspend`), and
`velo-control` does when the server closes a pane, so a thread stuck in a swap (#117) would
otherwise be an ANR. The pane detaches the thread, forgets it and goes on: it may then be freed,
or start a new thread on the next attach. Stops come one after another (`HostConnection.destroy`
detaches every pane, a `surfaceDestroyed` suspends every pane of the view, Core's sweep removes
each closed pane), and one sync of the window can leave several threads stuck. So the bound is
shared: for 2 s after a give-up, in any pane, a stop waits only `burst_wait_ns` (200 ms), and k
stuck threads cost 2 s + k × 0.2 s. A thread that is not stuck ends within tens of milliseconds;
one that is only slow and is given up frees itself when it ends. The waits run on the monotonic
clock (`Lock.waitUntil`, `pthread_cond_clockwait`), so a step of the wall clock does not move them.

The thread and the pane share a `RenderLink`, kept apart from the pane. The thread touches the
pane only between `link.enter()` and `link.leave()`: `run` is inside except while `present` runs,
and `present` enters only to rebuild rows after drawing glyphs and after its swap. Inside, the
thread takes the pane's lock and builds the frame under it, and the build calls the platform:
`buildFrame` asks `eglQuerySurface` for the buffer's size twice, and `buildImages` makes GL
texture calls (`glGenTextures`, `glTexImage2D`, `glDeleteTextures`). These are not known to
block, since the thread owns its surface and they queue no buffer. The pane gives the thread up
only outside: one inside when the bound runs out is waited for until it leaves, with no bound of
its own, and 500 ms past the bound logcat has `renderer still in its pane … ms past the bound
window=…`. The bound does not cover a thread stuck while it holds the pane's lock either:
`stopRenderer` takes that lock to set `quit` before its wait starts. It covers a thread stuck
outside the pane, in the swap, in EGL setup or teardown, or in the glyph source. Right after
`quit`, the pane marks the link as stopping (`RenderLink.stop`, #178). The thread sees `quit`
only inside the pane, but it sees `stopping` before each glyph it has the source draw. So a
thread that is only drawing many glyphs, such as the first frame of a pane full of Hangul or
CJK, ends within one glyph and is joined, also within the 200 ms of a burst. The frame it was
drawing is not shown.

A thread given up enters no more and asks the glyph source for no further glyph. The source
outlives it all the same (#178). `Glyphs` (sys/glyphs.zig) has its own allocation, its own
global reference to the listener and an atomic reference count (`GlyphSource.Refs`). jni.zig's
`Handle` holds one reference and drops it after the core has stopped its threads. Each link
holds one from the moment the pane makes it until the link is freed. The last drop deletes the
global reference through `jni.attach`, on whatever thread it runs, and frees `Glyphs`. Before
`CallObjectMethodA`, a source call reads the listener and its method, after `NewByteArray`,
which can wait for a GC. A thread given up during that wait, while a disconnect frees the
`Handle`, still finds both valid when it goes on. The count costs nothing per glyph: the
reference is the link's, not the call's. When the thread wakes it ends without the pane and
gives up its EGL objects. Then it gives up the window's reference (which the pane left it,
`Ref.thread`) and frees the link, which drops the link's reference to the source. One that
never wakes leaks all of these, and the listener's global reference, which keeps the destroyed
`HostConnection` reachable. Logcat has `renderer given up after … ms pane=… window=…`.
A thread that ends in time is joined as before. After the join the pane gives up the window's
reference and frees the link with its reference.

A renderer that cannot set up EGL for its window, or whose `eglSwapBuffers` fails, marks
the pane (`render_failed`) and its thread ends, releasing its EGL surface, EGL context
and GL objects as on any other exit. Nothing is drawn until the next attach, which joins
the thread and starts again even for the same window (`alive` is `!render_failed`). The
loop is `Renderer.run(pane, link, draw)`, which the host tests run with a drawing of their own,
also on a thread of the pane's (`Pane.startThread`).
Logcat tag `VeloTerm` has one line per renderer start and end (`renderer start pane=…
window=…`), and `renderer setup failed` or `renderer swap failed` before the end of one
that failed; an attach in place writes none. A new thread writes `renderer freezes its
buffers after …` when it goes back to FREEZE, or `renderer keeps its buffers scaled …` when
its retries end first; `renderer could not scale its first buffers …` or `renderer could
not freeze its buffers again …` means the window's `perform` hook refused the mode.

## Host tests

Run `zig build test` from `native/`. The build includes `src/term/test_pane.zig` in the host suite,
translates `src/c.h`, and links the host build of libghostty-vt. The terminal tests can exercise
`Renderer.run` without EGL or GL: a test that reaches the Android rendering backend cannot link
on the host. Use `-Dtest-filter=<name>` to run tests whose names contain the given text.
