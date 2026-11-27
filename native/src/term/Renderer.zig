//! Android render thread: EGL context on the pane's ANativeWindow, OpenGL ES 3.0,
//! instanced cell quads. Passes per frame:
//!   0. kitty images below text (layers BELOW_BACKGROUND, BELOW_TEXT draw after bg)
//!   1. cell backgrounds (+ selection tint)
//!   2. glyphs from the A8 / RGBA atlas texture arrays (box drawing = procedural A8)
//!   3. decorations: underline, strikethrough, overline, cursor
//!   4. kitty images above text
//! The server grid (libghostty cols/rows) is drawn at the local cell size; overflow
//! beyond the surface is clipped. A row above and below the viewport is captured too, for
//! the pane's scroll offset, and nothing is drawn outside the viewport's rectangle. Only
//! dirty rows are rebuilt and re-uploaded.
const std = @import("std");
const c = @import("c");
const gl = @import("gl.zig");
const PaneMod = @import("Pane.zig");
const Pane = PaneMod.Pane;
const BoxDraw = @import("BoxDraw.zig");
const Trace = @import("Trace.zig");
const AtlasMod = @import("Atlas.zig");

const page_size = AtlasMod.page_size;

/// One instance = one quad. Shared layout for every pass.
const Inst = extern struct {
    /// Destination rect in px: x, y, w, h.
    rect: [4]f32,
    /// Atlas UV rect in texels (x, y, w, h) and page layer; unused for solid quads.
    uv: [4]f32,
    layer: f32,
    /// 0 solid, 1 A8 mask tinted by color, 2 RGBA premultiplied texture.
    mode: f32,
    color: [4]u8,
};

const Row = struct {
    bg: std.ArrayList(Inst) = .empty,
    fg: std.ArrayList(Inst) = .empty,
    deco: std.ArrayList(Inst) = .empty,

    fn clear(r: *Row) void {
        r.bg.clearRetainingCapacity();
        r.fg.clearRetainingCapacity();
        r.deco.clearRetainingCapacity();
    }
    fn deinit(r: *Row, gpa: std.mem.Allocator) void {
        r.bg.deinit(gpa);
        r.fg.deinit(gpa);
        r.deco.deinit(gpa);
    }
};

const ImageTex = struct { tex: gl.GLuint, generation: u64, w: u32, h: u32, seen: bool };

const vs_src =
    \\#version 300 es
    \\layout(location=0) in vec4 a_rect;
    \\layout(location=1) in vec4 a_uv;
    \\layout(location=2) in float a_layer;
    \\layout(location=3) in float a_mode;
    \\layout(location=4) in vec4 a_color;
    \\uniform vec2 u_view;
    \\uniform vec2 u_origin;
    \\uniform vec2 u_tex;
    \\out vec3 v_uv;
    \\flat out float v_mode;
    \\out vec4 v_color;
    \\void main() {
    \\  vec2 corner = vec2(float(gl_VertexID & 1), float((gl_VertexID >> 1) & 1));
    \\  vec2 p = u_origin + a_rect.xy + corner * a_rect.zw;
    \\  gl_Position = vec4(p.x / u_view.x * 2.0 - 1.0, 1.0 - p.y / u_view.y * 2.0, 0.0, 1.0);
    \\  v_uv = vec3((a_uv.xy + corner * a_uv.zw) / u_tex, a_layer);
    \\  v_mode = a_mode;
    \\  v_color = a_color;
    \\}
;

const fs_src =
    \\#version 300 es
    \\precision mediump float;
    \\precision mediump sampler2DArray;
    \\uniform sampler2DArray u_mask;
    \\uniform sampler2DArray u_color;
    \\uniform sampler2D u_image;
    \\in vec3 v_uv;
    \\flat in float v_mode;
    \\in vec4 v_color;
    \\out vec4 o;
    \\void main() {
    \\  vec4 col = vec4(v_color.rgb * v_color.a, v_color.a);
    \\  if (v_mode < 0.5) { o = col; }
    \\  else if (v_mode < 1.5) { o = col * texture(u_mask, v_uv).r; }
    \\  else if (v_mode < 2.5) { o = texture(u_color, v_uv) * v_color.a; }
    \\  else { o = texture(u_image, v_uv.xy) * v_color.a; }
    \\}
;

const R = struct {
    pane: *Pane,
    gpa: std.mem.Allocator,
    dpy: gl.EGLDisplay = gl.EGL_NO_DISPLAY,
    surf: gl.EGLSurface = gl.EGL_NO_SURFACE,
    ctx: gl.EGLContext = gl.EGL_NO_CONTEXT,
    prog: gl.GLuint = 0,
    vao: gl.GLuint = 0,
    vbo: gl.GLuint = 0,
    vbo_cap: usize = 0,
    u_view: gl.GLint = -1,
    u_tex: gl.GLint = -1,
    u_origin: gl.GLint = -1,
    /// Left/top of the grid in surface px, read under the pane lock in `buildFrame`.
    origin: [2]f32 = .{ 0, 0 },
    /// Rows above the viewport that `rows` starts with, as the last update captured them
    /// (see `rowAt`).
    overscan_above: i32 = 0,
    /// Top and bottom of the viewport's rows in px of the grid as the frame draws it (0 is
    /// where the pane has the top of the viewport's first row). Nothing is drawn outside
    /// them; nothing is cut until `buildRows` sets them.
    viewport: [2]f32 = .{ 0, std.math.inf(f32) },
    /// Size in px of the buffer the frame is drawn to (the EGL surface), read in `buildFrame`.
    view_w: u32 = 0,
    view_h: u32 = 0,
    tex_mask: gl.GLuint = 0,
    tex_color: gl.GLuint = 0,
    mask_layers: usize = 0,
    color_layers: usize = 0,
    /// How both atlas textures are sampled: NEAREST while glyphs are drawn at the size they
    /// were made for, LINEAR while a pinch stretches them.
    filter: gl.GLint = gl.GL_NEAREST,

    state: c.GhosttyRenderState = null,
    rows_it: c.GhosttyRenderStateRowIterator = null,
    cells: c.GhosttyRenderStateRowCells = null,
    images_it: c.GhosttyRenderStateImageIterator = null,

    atlas: AtlasMod.Atlas,
    /// Cell size in px the grid is laid out on.
    cell_w: u32 = 0,
    cell_h: u32 = 0,
    /// Cell size in px the atlas glyphs were drawn for. Only while a pinch changes the scale
    /// does it differ from the cell size: the glyphs are then stretched to the cells.
    glyph_w: u32 = 0,
    glyph_h: u32 = 0,
    rows: std.ArrayList(Row) = .empty,
    frame: std.ArrayList(Inst) = .empty,
    images: std.AutoHashMapUnmanaged(u32, ImageTex) = .empty,
    img_below: std.ArrayList(Inst) = .empty,
    img_above: std.ArrayList(Inst) = .empty,
    img_tex_below: std.ArrayList(gl.GLuint) = .empty,
    img_tex_above: std.ArrayList(gl.GLuint) = .empty,
    clear_rgb: [3]u8 = .{ 0, 0, 0 },
    scratch: std.ArrayList(u8) = .empty,
    last_gen: u64 = 0,
    /// Leading instances of `frame` that are cell backgrounds.
    bg_count: usize = 0,
    /// Where the keyboard's composition starts in `frame`; it runs to the end.
    preedit_start: usize = 0,
    /// What the window shows: the last frame `present` handed it (`remember`). A frame that
    /// would draw the same is not drawn again (`drawsShown`).
    shown: Look = .{},
    shown_frame: std.ArrayList(Inst) = .empty,
    /// The images below the text, then the ones above it, with their textures.
    shown_images: std.ArrayList(Inst) = .empty,
    shown_image_tex: std.ArrayList(gl.GLuint) = .empty,
    /// The last `placeImages` made the texture of an image, or made it again.
    images_loaded: bool = false,
    /// The frame `build` made draws what the window shows: `present` draws nothing.
    same: bool = false,
    /// When the last frame began to build (`PaneMod.monoNow`).
    build_ns: i128 = 0,
    /// True on a render thread: a glyph the atlas lacks is drawn after the build, without the
    /// pane lock (`completeFrame`), and the cell waits for it. False (a test that builds a row
    /// alone): it is drawn at once.
    glyphs_later: bool = false,
    /// The glyphs the frame lacks, each once, their atlas keys in `missing_keys`.
    missing: std.ArrayList(Missing) = .empty,
    missing_keys: std.ArrayList(u8) = .empty,
    /// A glyph copied out of the glyph source's cache (`drawMissing`).
    shared_px: std.ArrayList(u8) = .empty,
    /// By the index of `rows`: the rows that lack a glyph, built again once it is drawn.
    waiting: std.ArrayList(bool) = .empty,
    /// The index in `rows` of the row being built; null while the frame is assembled.
    building: ?usize = null,
    /// What `buildRows` read for the frame, for building rows of it again (`buildWaiting`).
    colors: c.GhosttyRenderStateColors = std.mem.zeroes(c.GhosttyRenderStateColors),
    top: i64 = 0,
    /// The scaling mode this thread's buffers go to the window with (`swapScaling`); null
    /// before its first swap.
    scaling: ?Scaling = null,
    /// When this thread swapped its first frame (`PaneMod.monoNow`).
    first_swap_ns: i128 = 0,
    /// The frame this thread asked for itself (`askFrame`), as `Pane.frame_due_ns` has it; 0
    /// for none.
    due_ns: i128 = 0,
    /// `retry` and `give_up` answers of `scalingFrameDue` so far.
    due_retries: u8 = 0,
    /// Frames that went out scaled with another size than the window's: BLAST would have
    /// rejected them as FREEZE.
    scaled_off_size: u16 = 0,

    /// `run`'s drawing. `build` is called with the pane lock held.
    pub fn build(r: *R) bool {
        r.build_ns = PaneMod.monoNow();
        return buildFrame(r) catch false;
    }

    pub fn present(r: *R) bool {
        // A frame that lacks glyphs cannot be drawn as built: an error leaves those cells empty.
        completeFrame(r) catch logWarn("renderer could not draw missing glyphs");
        if (r.same) {
            Trace.begin("Renderer.unchanged");
            defer Trace.end();
            paceUntil(r.build_ns + unchanged_frame_ns);
            return true;
        }
        drawFrame(r);
        const first = r.scaling == null;
        const now = PaneMod.monoNow();
        if (r.scaling != .freeze) setSwapScaling(r, now);
        // Blocks until vsync (swap interval 1).
        if (gl.eglSwapBuffers(r.dpy, r.surf) != 0) {
            if (first) r.first_swap_ns = now;
            planFrameDue(r, first, now);
            remember(r);
            _ = r.pane.swaps.fetchAdd(1, .monotonic);
            return true;
        }
        logWarn("renderer swap failed");
        return false;
    }
};

/// What `drawFrame` draws a frame with, besides its quads and the textures they sample.
const Look = struct {
    /// False until a frame is shown, and after one could not be remembered.
    valid: bool = false,
    view: [2]u32 = .{ 0, 0 },
    origin: [2]f32 = .{ 0, 0 },
    clear_rgb: [3]u8 = .{ 0, 0, 0 },
    /// Where the passes start in the frame's quads, and how many images are below the text.
    bg_count: usize = 0,
    preedit_start: usize = 0,
    images_below: usize = 0,
    /// The cell and glyph sizes, which choose how the atlas is sampled.
    cell: [2]u32 = .{ 0, 0 },
    glyph: [2]u32 = .{ 0, 0 },

    fn of(r: *const R) Look {
        return .{
            .valid = true,
            .view = .{ r.view_w, r.view_h },
            .origin = r.origin,
            .clear_rgb = r.clear_rgb,
            .bg_count = r.bg_count,
            .preedit_start = r.preedit_start,
            .images_below = r.img_below.items.len,
            .cell = .{ r.cell_w, r.cell_h },
            .glyph = .{ r.glyph_w, r.glyph_h },
        };
    }
};

/// The least time from the start of one build to the next, after a frame that draws nothing
/// new: about a frame of a 120 Hz display. A swap would have waited for the display. Without
/// this wait, a stream of writes that change nothing on the screen would be built as fast as
/// it comes, each build taking the pane lock from the data thread.
const unchanged_frame_ns: i128 = 8 * std.time.ns_per_ms;

fn paceUntil(due_ns: i128) void {
    const left = due_ns - PaneMod.monoNow();
    if (left <= 0) return;
    var ts: std.c.timespec = .{ .sec = 0, .nsec = @intCast(left) };
    _ = std.c.nanosleep(&ts, null);
}

/// A glyph a frame lacks: its atlas key is `R.missing_keys[start..][0..len]`, the text and then
/// the flags byte.
const Missing = struct { start: u32, len: u32, bold: bool, italic: bool, columns: u32 };

/// Called with the pane lock held, while a frame is built: `key` waits to be drawn, once, and
/// the row being built waits for it (the keyboard's composition is assembled again anyway).
fn noteMissing(r: *R, key: []const u8, bold: bool, italic: bool, columns: u32) !void {
    if (r.building) |index| r.waiting.items[index] = true;
    for (r.missing.items) |m| if (std.mem.eql(u8, r.missing_keys.items[m.start..][0..m.len], key)) return;
    const start = r.missing_keys.items.len;
    try r.missing_keys.appendSlice(r.gpa, key);
    try r.missing.append(r.gpa, .{ .start = @intCast(start), .len = @intCast(key.len), .bold = bold, .italic = italic, .columns = columns });
}

/// Called without the pane lock, after a build: draws the glyphs the frame lacks into the
/// atlas, then builds again, with the lock, the rows that wait for them, and the frame. The
/// lock is not held while the glyph source draws: the data thread writes and a finger pans
/// meanwhile, where a frame full of new glyphs used to stop them for tens of milliseconds.
fn completeFrame(r: *R) !void {
    if (r.missing.items.len == 0) return;
    drawMissing(r);
    r.same = false;
    const p = r.pane;
    p.lock.lock();
    defer p.lock.unlock();
    try buildWaiting(r);
}

/// The render thread's own work: the atlas is its, and the glyph source needs no lock. A glyph
/// another pane's render thread drew already comes from the source's cache.
fn drawMissing(r: *R) void {
    Trace.begin("Renderer.drawMissing");
    defer Trace.end();
    const g = r.pane.glyphs;
    for (r.missing.items) |m| {
        const key = r.missing_keys.items[m.start..][0..m.len];
        const utf8 = key[0 .. key.len - 1];
        if (g.cache) |cache| {
            Trace.begin("Renderer.findGlyph");
            defer Trace.end();
            if (cache.find(key, r.glyph_w, r.glyph_h, m.columns, r.gpa, &r.shared_px)) |size| {
                _ = r.atlas.put(key, r.shared_px.items, size.w, size.h) catch {};
                continue;
            }
        }
        const epoch = if (g.cache) |cache| cache.epochNow() else 0;
        Trace.begin("Renderer.rasterizeGlyph");
        const bm = g.rasterize(g.ctx, utf8, m.bold, m.italic, r.glyph_w, r.glyph_h, m.columns);
        Trace.end();
        // Only what was drawn is shared: a glyph the font lacks now may come with a font file
        // read later, so the atlas alone remembers it is missing.
        if (bm) |b| if (g.cache) |cache| cache.keep(epoch, key, r.glyph_w, r.glyph_h, m.columns, b.pixels, b.w, b.h);
        // A glyph the atlas cannot take is looked for again by the next frame.
        _ = (if (bm) |b| r.atlas.put(key, b.pixels, b.w, b.h) else r.atlas.put(key, null, 0, 0)) catch {};
    }
    r.missing.clearRetainingCapacity();
    r.missing_keys.clearRetainingCapacity();
}

/// Called with the pane lock held: the rows that wait for glyphs are built again from the
/// render state, which the build left as it was, and the frame is assembled again. A glyph
/// still missing (the atlas could not take it) waits for the next frame.
fn buildWaiting(r: *R) !void {
    if (std.mem.indexOfScalar(bool, r.waiting.items, true) != null) {
        if (c.ghostty_render_state_get(r.state, c.GHOSTTY_RENDER_STATE_DATA_ROW_ITERATOR, @ptrCast(&r.rows_it)) != c.GHOSTTY_SUCCESS) return error.Update;
        while (c.ghostty_render_state_row_iterator_next(r.rows_it)) {
            var y: i32 = 0;
            _ = c.ghostty_render_state_row_get(r.rows_it, c.GHOSTTY_RENDER_STATE_ROW_DATA_VIEWPORT_Y, &y);
            const index = rowIndex(r, y) orelse break;
            if (r.waiting.items[index]) try buildRowAt(r, index, y);
        }
    }
    try assembleFrame(r, r.colors);
    if (r.missing.items.len > 0) r.pane.wake = true;
}

/// Called with the pane lock held, once a frame is built: whether it draws what the window
/// shows already, so that `present` need not hand it the same again. Writes that leave the
/// screen as it is make such frames: a new title, a bell, a mode. The frame is drawn all the
/// same after an attach in place (`Pane.present_due`), and while frames are how the window
/// gets a buffer of its size: during an attach-settle (`settling`, also on the frame that
/// spends it), while the pane's surface has another size than the buffer answered, and when
/// the frame asks for the next one itself.
fn drawsShown(r: *R, settling: bool) bool {
    const p = r.pane;
    const due = p.present_due;
    p.present_due = false;
    if (due or settling or p.wake or p.settle_frames > 0) return false;
    if (r.view_w != p.surface_w or r.view_h != p.surface_h) return false;
    if (!r.shown.valid or r.images_loaded) return false;
    if (!std.meta.eql(Look.of(r), r.shown)) return false;
    if (anyDirty(&r.atlas.mask) or anyDirty(&r.atlas.color)) return false;
    if (!sameQuads(r.frame.items, r.shown_frame.items)) return false;
    const below = r.img_below.items.len;
    const above = r.img_above.items.len;
    if (r.shown_images.items.len != below + above) return false;
    return sameQuads(r.img_below.items, r.shown_images.items[0..below]) and
        sameQuads(r.img_above.items, r.shown_images.items[below..]) and
        std.mem.eql(gl.GLuint, r.img_tex_below.items, r.shown_image_tex.items[0..below]) and
        std.mem.eql(gl.GLuint, r.img_tex_above.items, r.shown_image_tex.items[below..]);
}

fn anyDirty(plane: *const AtlasMod.Plane) bool {
    return std.mem.indexOfScalar(bool, plane.dirty.items, true) != null;
}

fn sameQuads(a: []const Inst, b: []const Inst) bool {
    return std.mem.eql(u8, std.mem.sliceAsBytes(a), std.mem.sliceAsBytes(b));
}

/// After a swap: the frame is what the window shows. One that cannot be kept is forgotten,
/// and the next frame is drawn.
fn remember(r: *R) void {
    keepShown(r) catch {
        r.shown.valid = false;
    };
}

fn keepShown(r: *R) !void {
    r.shown.valid = false;
    r.shown_frame.clearRetainingCapacity();
    try r.shown_frame.appendSlice(r.gpa, r.frame.items);
    r.shown_images.clearRetainingCapacity();
    try r.shown_images.appendSlice(r.gpa, r.img_below.items);
    try r.shown_images.appendSlice(r.gpa, r.img_above.items);
    r.shown_image_tex.clearRetainingCapacity();
    try r.shown_image_tex.appendSlice(r.gpa, r.img_tex_below.items);
    try r.shown_image_tex.appendSlice(r.gpa, r.img_tex_above.items);
    r.shown = Look.of(r);
}

pub fn releaseWindow(window: *anyopaque) void {
    gl.ANativeWindow_release(window);
}

fn logWarn(msg: [*:0]const u8) void {
    _ = gl.__android_log_write(gl.ANDROID_LOG_WARN, "VeloTerm", msg);
}

/// One line per renderer start and end: an attach in place writes none.
fn logLife(what: []const u8, pane: *Pane, window: ?*anyopaque) void {
    var buf: [96]u8 = undefined;
    const msg = std.mem.printSentinel(&buf, "renderer {s} pane={x} window={x}", .{ what, @intFromPtr(pane), @intFromPtr(window) }, 0) catch return;
    _ = gl.__android_log_write(gl.ANDROID_LOG_INFO, "VeloTerm", msg);
}

/// Before a swap of a thread whose buffers may still go out scaled: the scaling mode this
/// one goes with (`swapScaling`), from the size of its buffer and the window's now. A window
/// that cannot be asked keeps FREEZE, and the swap goes as it did before #117.
fn setSwapScaling(r: *R, now_ns: i128) void {
    const win = r.pane.window orelse return;
    var bw: gl.EGLint = 0;
    var bh: gl.EGLint = 0;
    _ = gl.eglQuerySurface(r.dpy, r.surf, gl.EGL_WIDTH, &bw);
    _ = gl.eglQuerySurface(r.dpy, r.surf, gl.EGL_HEIGHT, &bh);
    // The size BLAST asks buffers for now: the BufferQueue's default size, read where it is
    // kept (`Surface::query` hands NATIVE_WINDOW_WIDTH/HEIGHT on), not a copy.
    const ww = gl.ANativeWindow_getWidth(win);
    const wh = gl.ANativeWindow_getHeight(win);
    const since = if (r.scaling == null) 0 else now_ns - r.first_swap_ns;
    const mode = swapScaling(r.scaling, since, bw, bh, ww, wh);
    if (mode == .scale_to_window and (bw != ww or bh != wh)) r.scaled_off_size +|= 1;
    if (r.scaling == mode) return;
    if (!setWindowScaling(win, mode)) {
        logWarn(switch (mode) {
            .scale_to_window => "renderer could not scale its first buffers: they go out as before",
            .freeze => "renderer could not freeze its buffers again: they stay scaled",
        });
        // Nothing more is asked of this window.
        r.scaling = .freeze;
        return;
    }
    if (mode == .freeze) logScaling("freezes its buffers", r, now_ns);
    r.scaling = mode;
}

/// After a swap: the frame the thread asks for itself while its buffers go out scaled
/// (`scalingFrameDue`).
fn planFrameDue(r: *R, first: bool, now_ns: i128) void {
    const mode = r.scaling orelse return;
    switch (scalingFrameDue(mode, first, r.due_ns != 0, r.due_retries)) {
        .none => {},
        .after_hold => askFrame(r, r.first_swap_ns + scale_hold_ns),
        .retry => {
            r.due_retries += 1;
            askFrame(r, now_ns + scale_retry_ns);
        },
        .give_up => {
            r.due_retries += 1;
            logScaling("keeps its buffers scaled", r, now_ns);
        },
    }
}

/// The render thread draws a frame at `at_ns` (`PaneMod.monoNow`), with nothing else to draw.
fn askFrame(r: *R, at_ns: i128) void {
    r.due_ns = at_ns;
    r.pane.lock.lock();
    defer r.pane.lock.unlock();
    r.pane.frame_due_ns = at_ns;
}

/// `native_window_set_scaling_mode` (see gl.zig): the buffers the window is given from now on
/// go to it with `mode`. False when the window could not be asked.
fn setWindowScaling(window: *anyopaque, mode: Scaling) bool {
    const w: *gl.NativeWindow = @ptrCast(@alignCast(window));
    if (w.common.magic != gl.ANDROID_NATIVE_WINDOW_MAGIC) return false;
    if (w.common.version < @offsetOf(gl.NativeWindow, "perform") + @sizeOf(usize)) return false;
    const perform = w.perform orelse return false;
    const value: c_int = switch (mode) {
        .freeze => gl.NATIVE_WINDOW_SCALING_MODE_FREEZE,
        .scale_to_window => gl.NATIVE_WINDOW_SCALING_MODE_SCALE_TO_WINDOW,
    };
    return perform(w, gl.NATIVE_WINDOW_SET_SCALING_MODE, value) == 0;
}

/// One line when a thread's buffers stop going out scaled, or stay so for good.
fn logScaling(what: []const u8, r: *R, now_ns: i128) void {
    var buf: [128]u8 = undefined;
    const after_ms = @divTrunc(now_ns - r.first_swap_ns, std.time.ns_per_ms);
    const msg = std.mem.printSentinel(&buf, "renderer {s} after {d} ms, {d} scaled off size pane={x}", .{ what, after_ms, r.scaled_off_size, @intFromPtr(r.pane) }, 0) catch return;
    _ = gl.__android_log_write(gl.ANDROID_LOG_INFO, "VeloTerm", msg);
}

pub fn threadMain(pane: *Pane) void {
    Trace.nameThread("velo-draw-", pane.threadTag());
    var r: R = .{ .pane = pane, .gpa = pane.gpa, .atlas = .init(pane.gpa), .glyphs_later = true };
    // Every way out of this thread gives up what the thread owns, here and once: the EGL
    // surface and context and the GL objects. The window's reference is the pane's.
    defer teardown(&r);
    // Only an attach changes the window, and it joins this thread first.
    const window = pane.window;
    setup(&r) catch {
        logWarn("renderer setup failed");
        markFailed(pane);
        return;
    };
    logLife("start", pane, window);
    defer logLife("end", pane, window);
    run(pane, &r);
}

/// The render thread's loop, apart from what it draws with (the host tests run it with
/// a drawing of their own). `draw.build()` makes the frame from the terminal with the
/// pane lock held and says whether there is one to draw; `draw.present()` draws it and
/// hands it to the window, without the lock, and says whether the window took it.
/// Returns when the pane asks (`quit`), or by itself, with the pane marked
/// (`render_failed`), when the window does not take a frame: a dead surface returns from
/// the swap at once, so going on would draw every frame unseen and unthrottled.
pub fn run(pane: *Pane, draw: anytype) void {
    while (true) {
        pane.lock.lock();
        while (!pane.wake and !pane.quit) {
            // While the pane's scroll offset waits to rest, or a frame the thread asked for
            // itself waits to be due, sleep only until the first of them is due: past due, or
            // a wait that ran out, is a wake.
            const now = PaneMod.monoNow();
            const wait = soonest(pane.scrollWaitLocked(now), pane.frameWaitLocked(now)) orelse {
                pane.lock.wait();
                continue;
            };
            if (wait == 0 or pane.lock.timedWait(wait)) break;
        }
        if (pane.quit) {
            pane.lock.unlock();
            return;
        }
        pane.wake = false;
        Trace.begin("Renderer.buildFrame");
        const built = draw.build();
        Trace.end();
        // Only a frame that was built counts (it is drawn next, and if the window does not
        // take it this thread ends): one that could not be built does not spend one of these
        // frames and does not make the thread try again on its own, not for the scroll offset
        // either. These are an attach-settle's frames before its last: the last is spent by
        // `takeSurfaceSize`, at the take, whether or not that frame builds, so that a failed
        // frame cannot leave the expiry unspent for the next one to take again, resting the
        // offset a pan wrote in between.
        if (built and pane.settle_frames > 0) {
            // The window may still hand out a buffer of its size before the attach in place.
            pane.settle_frames -= 1;
            pane.wake = true;
        }
        if (!built) pane.scrollRestLocked();
        pane.lock.unlock();
        if (!built) continue;
        Trace.begin("Renderer.present");
        const presented = draw.present();
        Trace.end();
        if (!presented) {
            markFailed(pane);
            return;
        }
    }
}

fn soonest(a: ?u64, b: ?u64) ?u64 {
    const x = a orelse return b;
    const y = b orelse return x;
    return @min(x, y);
}

/// Drawing to the pane's window does not work and this thread ends: the next attach, of
/// this window too, joins it and starts a new renderer.
fn markFailed(pane: *Pane) void {
    pane.lock.lock();
    defer pane.lock.unlock();
    pane.render_failed = true;
}

/// The rows about the viewport are captured too, one above and one below: the pane's offset
/// shows part of them (`Pane.scroll_off`).
fn requestOverscan(state: c.GhosttyRenderState) void {
    var overscan: c.GhosttyRenderStateOverscan = .{ .above = 1, .below = 1 };
    _ = c.ghostty_render_state_set(state, c.GHOSTTY_RENDER_STATE_OPT_OVERSCAN, &overscan);
}

fn setup(r: *R) !void {
    const win = r.pane.window orelse return error.NoWindow;
    r.dpy = gl.eglGetDisplay(gl.EGL_DEFAULT_DISPLAY);
    if (r.dpy == gl.EGL_NO_DISPLAY or gl.eglInitialize(r.dpy, null, null) == 0) return error.Egl;
    const attrs = [_]gl.EGLint{
        gl.EGL_RENDERABLE_TYPE, gl.EGL_OPENGL_ES3_BIT,
        gl.EGL_SURFACE_TYPE,    gl.EGL_WINDOW_BIT,
        gl.EGL_RED_SIZE,        8,
        gl.EGL_GREEN_SIZE,      8,
        gl.EGL_BLUE_SIZE,       8,
        gl.EGL_ALPHA_SIZE,      8,
        gl.EGL_NONE,
    };
    var cfg: [1]gl.EGLConfig = .{null};
    var n: gl.EGLint = 0;
    if (gl.eglChooseConfig(r.dpy, &attrs, &cfg, 1, &n) == 0 or n < 1) return error.Egl;
    r.surf = gl.eglCreateWindowSurface(r.dpy, cfg[0], win, null);
    if (r.surf == gl.EGL_NO_SURFACE) return error.Egl;
    const ctx_attrs = [_]gl.EGLint{ gl.EGL_CONTEXT_CLIENT_VERSION, 3, gl.EGL_NONE };
    r.ctx = gl.eglCreateContext(r.dpy, cfg[0], gl.EGL_NO_CONTEXT, &ctx_attrs);
    if (r.ctx == gl.EGL_NO_CONTEXT) return error.Egl;
    if (gl.eglMakeCurrent(r.dpy, r.surf, r.surf, r.ctx) == 0) return error.Egl;
    _ = gl.eglSwapInterval(r.dpy, 1);

    r.prog = try program();
    gl.glUseProgram(r.prog);
    r.u_view = gl.glGetUniformLocation(r.prog, "u_view");
    r.u_tex = gl.glGetUniformLocation(r.prog, "u_tex");
    r.u_origin = gl.glGetUniformLocation(r.prog, "u_origin");
    gl.glUniform1i(gl.glGetUniformLocation(r.prog, "u_mask"), 0);
    gl.glUniform1i(gl.glGetUniformLocation(r.prog, "u_color"), 1);
    gl.glUniform1i(gl.glGetUniformLocation(r.prog, "u_image"), 2);

    var ids: [1]gl.GLuint = .{0};
    gl.glGenVertexArrays(1, &ids);
    r.vao = ids[0];
    gl.glGenBuffers(1, &ids);
    r.vbo = ids[0];
    gl.glBindVertexArray(r.vao);
    gl.glBindBuffer(gl.GL_ARRAY_BUFFER, r.vbo);
    const stride: gl.GLsizei = @sizeOf(Inst);
    attrib(0, 4, gl.GL_FLOAT, 0, stride, @offsetOf(Inst, "rect"));
    attrib(1, 4, gl.GL_FLOAT, 0, stride, @offsetOf(Inst, "uv"));
    attrib(2, 1, gl.GL_FLOAT, 0, stride, @offsetOf(Inst, "layer"));
    attrib(3, 1, gl.GL_FLOAT, 0, stride, @offsetOf(Inst, "mode"));
    attrib(4, 4, gl.GL_UNSIGNED_BYTE, 1, stride, @offsetOf(Inst, "color"));

    gl.glGenTextures(1, &ids);
    r.tex_mask = ids[0];
    gl.glGenTextures(1, &ids);
    r.tex_color = ids[0];
    gl.glPixelStorei(gl.GL_UNPACK_ALIGNMENT, 1);
    gl.glEnable(gl.GL_BLEND);
    gl.glBlendFunc(gl.GL_ONE, gl.GL_ONE_MINUS_SRC_ALPHA);

    if (c.ghostty_render_state_new(null, &r.state) != c.GHOSTTY_SUCCESS) return error.OutOfMemory;
    requestOverscan(r.state);
    if (c.ghostty_render_state_row_iterator_new(null, &r.rows_it) != c.GHOSTTY_SUCCESS) return error.OutOfMemory;
    if (c.ghostty_render_state_row_cells_new(null, &r.cells) != c.GHOSTTY_SUCCESS) return error.OutOfMemory;
    if (c.ghostty_render_state_image_iterator_new(null, &r.images_it) != c.GHOSTTY_SUCCESS) return error.OutOfMemory;
}

fn attrib(idx: gl.GLuint, size: gl.GLint, kind: gl.GLenum, norm: gl.GLboolean, stride: gl.GLsizei, off: usize) void {
    gl.glEnableVertexAttribArray(idx);
    gl.glVertexAttribPointer(idx, size, kind, norm, stride, @ptrFromInt(off));
    gl.glVertexAttribDivisor(idx, 1);
}

fn shader(kind: gl.GLenum, src: [*:0]const u8) !gl.GLuint {
    const s = gl.glCreateShader(kind);
    const srcs = [1][*]const u8{src};
    gl.glShaderSource(s, 1, &srcs, null);
    gl.glCompileShader(s);
    var ok: gl.GLint = 0;
    gl.glGetShaderiv(s, gl.GL_COMPILE_STATUS, &ok);
    if (ok == 0) {
        var log: [512:0]u8 = @splat(0);
        gl.glGetShaderInfoLog(s, 511, null, &log);
        logWarn(&log);
        gl.glDeleteShader(s);
        return error.Shader;
    }
    return s;
}

fn program() !gl.GLuint {
    const v = try shader(gl.GL_VERTEX_SHADER, vs_src);
    defer gl.glDeleteShader(v);
    const f = try shader(gl.GL_FRAGMENT_SHADER, fs_src);
    defer gl.glDeleteShader(f);
    const p = gl.glCreateProgram();
    gl.glAttachShader(p, v);
    gl.glAttachShader(p, f);
    gl.glLinkProgram(p);
    var ok: gl.GLint = 0;
    gl.glGetProgramiv(p, gl.GL_LINK_STATUS, &ok);
    if (ok == 0) return error.Shader;
    return p;
}

fn teardown(r: *R) void {
    if (r.images_it != null) c.ghostty_render_state_image_iterator_free(r.images_it);
    if (r.cells != null) c.ghostty_render_state_row_cells_free(r.cells);
    if (r.rows_it != null) c.ghostty_render_state_row_iterator_free(r.rows_it);
    if (r.state != null) c.ghostty_render_state_free(r.state);
    if (r.ctx != gl.EGL_NO_CONTEXT) {
        var it = r.images.valueIterator();
        while (it.next()) |t| gl.glDeleteTextures(1, @ptrCast(&t.tex));
        const texs = [2]gl.GLuint{ r.tex_mask, r.tex_color };
        gl.glDeleteTextures(2, &texs);
        gl.glDeleteBuffers(1, @ptrCast(&r.vbo));
        gl.glDeleteVertexArrays(1, @ptrCast(&r.vao));
        if (r.prog != 0) gl.glDeleteProgram(r.prog);
        _ = gl.eglMakeCurrent(r.dpy, gl.EGL_NO_SURFACE, gl.EGL_NO_SURFACE, gl.EGL_NO_CONTEXT);
        _ = gl.eglDestroyContext(r.dpy, r.ctx);
    }
    if (r.surf != gl.EGL_NO_SURFACE) _ = gl.eglDestroySurface(r.dpy, r.surf);
    _ = gl.eglReleaseThread();
    for (r.rows.items) |*row| row.deinit(r.gpa);
    r.rows.deinit(r.gpa);
    r.frame.deinit(r.gpa);
    r.images.deinit(r.gpa);
    r.img_below.deinit(r.gpa);
    r.img_above.deinit(r.gpa);
    r.img_tex_below.deinit(r.gpa);
    r.img_tex_above.deinit(r.gpa);
    r.shown_frame.deinit(r.gpa);
    r.shown_images.deinit(r.gpa);
    r.shown_image_tex.deinit(r.gpa);
    r.missing.deinit(r.gpa);
    r.missing_keys.deinit(r.gpa);
    r.waiting.deinit(r.gpa);
    r.shared_px.deinit(r.gpa);
    r.scratch.deinit(r.gpa);
    r.atlas.deinit();
}

fn rgba(col: c.GhosttyColorRgb, a: u8) [4]u8 {
    return .{ col.r, col.g, col.b, a };
}

/// What a frame does with the size the window's buffer answers.
pub const SizeTake = enum { keep, take_and_reset };

/// `view` is the buffer size the last frame answered; `w`/`h` what it answers now;
/// `expired` whether this frame spends the last of a pending attach-settle's frames. The
/// pane takes the size — and its scroll offset rests with it — when the buffer's size
/// changed, or once when a pending attach-settle expires; never because no settle is in
/// flight.
pub fn retakeSize(view_w: u32, view_h: u32, w: u32, h: u32, expired: bool) SizeTake {
    if (w != view_w or h != view_h) return .take_and_reset;
    if (expired) return .take_and_reset;
    return .keep;
}

/// The scaling mode a frame's buffer goes to the window with (#117).
pub const Scaling = enum {
    /// `NATIVE_WINDOW_SCALING_MODE_FREEZE`, the window's own: a buffer is shown at its size,
    /// and BLAST rejects one whose size is neither the window's nor that of the last buffer
    /// the window took.
    freeze,
    /// `NATIVE_WINDOW_SCALING_MODE_SCALE_TO_WINDOW`: a buffer is scaled to the window's size,
    /// and BLAST rejects none.
    scale_to_window,
};

/// How long after its first swap a new render thread's buffers go out scaled at least. A
/// return to the app brings the window's size changes and the transition's window sync in its
/// first tens of ms; measured on the Velvet (#117): the stuck swaps came 12 ms after the thread
/// started and 47–52 ms after the transition's sync finished on the server, and the window took
/// its new size 3–27 ms after the first swap. The hold ends before the keyboard that comes up
/// again resizes the window (193–478 ms after the start, median 277 ms): a buffer that is
/// scaled when the window changes size shows scaled, squashed there, until the next one, where
/// a FREEZE buffer shows at its size.
pub const scale_hold_ns: i128 = 150 * std.time.ns_per_ms;

/// Decide the scaling mode of the buffer a swap queues (#117). `mode`: what the thread's last
/// swap queued with, null before its first; `since_first_ns`: the time since its first swap;
/// `buffer_w`/`buffer_h`: the size of the buffer the frame is drawn to (the EGL surface's);
/// `window_w`/`window_h`: the size the window gives buffers now.
///
/// While a window sync is set, a buffer BLAST rejects makes the swap wait for good: it was the
/// only frame, and the thread that would queue the next one is the one waiting. A FREEZE buffer
/// is rejected when its size is neither the window's nor that of the last FREEZE buffer the
/// window took; with none taken yet, or a scaled one last, only the window's size will do, and
/// that changes while the window returns. So a new thread's buffers go out scaled until the
/// hold is over and a buffer has the window's size. From then on an old-size buffer has the
/// size of the last one taken, as on every running thread, and FREEZE stays.
pub fn swapScaling(mode: ?Scaling, since_first_ns: i128, buffer_w: i32, buffer_h: i32, window_w: i32, window_h: i32) Scaling {
    const m = mode orelse return .scale_to_window;
    if (m == .freeze) return .freeze;
    if (since_first_ns < scale_hold_ns) return .scale_to_window;
    // A size that could not be read proves nothing: the buffers stay scaled.
    if (buffer_w <= 0 or buffer_h <= 0 or window_w <= 0 or window_h <= 0) return .scale_to_window;
    if (buffer_w != window_w or buffer_h != window_h) return .scale_to_window;
    return .freeze;
}

/// A frame that still went out scaled after the hold asks for the next one this much later, at
/// most `scale_retries` times: about 550 ms from the first swap in all.
pub const scale_retry_ns: i128 = 50 * std.time.ns_per_ms;
pub const scale_retries: u8 = 8;

/// The frame a render thread asks for itself after a swap while its buffers go out scaled.
pub const FrameDue = enum {
    /// Nothing to ask for.
    none,
    /// A frame when the hold is over: a pane that prints nothing draws none of its own.
    after_hold,
    /// A frame `scale_retry_ns` later.
    retry,
    /// The buffers stay scaled: the last retry did not find the window's size.
    give_up,
};

/// Decide the frame a render thread asks for itself after a swap (#117). `mode`: what that
/// swap queued with (`swapScaling`); `first`: it was the thread's first; `pending`: a frame the
/// thread asked for is not due yet; `retries`: the `retry` and `give_up` answers so far.
pub fn scalingFrameDue(mode: Scaling, first: bool, pending: bool, retries: u8) FrameDue {
    if (mode == .freeze) return .none;
    if (first) return .after_hold;
    if (pending) return .none;
    if (retries < scale_retries) return .retry;
    if (retries == scale_retries) return .give_up;
    return .none;
}

/// Called with the pane lock held, as a frame begins to build: the frame the thread asked for
/// itself (`askFrame`) is due, and goes to the window even when it draws what the window
/// shows, while the thread's buffers go out scaled.
fn takeFrameDue(r: *R, now_ns: i128) void {
    if (r.due_ns == 0 or now_ns < r.due_ns) return;
    r.due_ns = 0;
    r.pane.frame_due_ns = 0;
    if (r.scaling == .scale_to_window) r.pane.present_due = true;
}

/// A frame's size, without GL (the host tests drive it): `w`/`h` is what the window's
/// buffer answers. The renderer's notion of the buffer follows every answer; the pane takes
/// the size — and its scroll offset rests with it — per `retakeSize`.
fn takeSurfaceSize(r: *R, w: u32, h: u32) void {
    const p = r.pane;
    const expired = p.settle_frames == 1;
    const take_and_reset = retakeSize(r.view_w, r.view_h, w, h, expired) == .take_and_reset;
    r.view_w = w;
    r.view_h = h;
    if (take_and_reset) {
        p.surface_w = w;
        p.surface_h = h;
        p.scroll_off = 0; // a surface of another size, or an attach-settle run out: the offset is no fraction of a row
        // The take that ends an attach-settle spends it here, once, whether or not this frame
        // builds: a frame that fails must not leave the next one to take again, and rest the
        // offset a pan wrote in between.
        if (expired) p.settle_frames = 0;
    }
}

/// Called with the pane lock held: snapshot terminal → instance lists.
fn buildFrame(r: *R) !bool {
    const p = r.pane;
    // First, so that a frame that fails cannot leave it due and the loop waking at once.
    takeFrameDue(r, r.build_ns);
    // Before the take below spends the settle's last frame: that frame is drawn too.
    const settling = p.settle_frames > 0;
    // The EGL surface has the size of the buffer it draws to. That follows the window's
    // size (rotation, unfold, an attach in place) a buffer late, so the size an attach
    // in place gave stands until the buffer has it or the settle's last frame is due. That
    // frame takes the buffer's size and spends the settle at the take, whether or not it
    // builds: a frame that fails must not leave the expiry for the next one, which would
    // take again and rest the offset a pan wrote in between.
    var w: gl.EGLint = 0;
    var h: gl.EGLint = 0;
    _ = gl.eglQuerySurface(r.dpy, r.surf, gl.EGL_WIDTH, &w);
    _ = gl.eglQuerySurface(r.dpy, r.surf, gl.EGL_HEIGHT, &h);
    if (w > 0 and h > 0) {
        takeSurfaceSize(r, @intCast(w), @intCast(h));
    } else {
        r.view_w = p.surface_w;
        r.view_h = p.surface_h;
    }
    const colors = try buildRows(r);
    try buildImages(r);
    try assembleFrame(r, colors);
    // The offset rests on rows, a frame at a time: the next frame asks for itself.
    if (p.scrollStepLocked(PaneMod.monoNow())) p.wake = true;
    // A frame that lacks glyphs is drawn: they come before it is (`completeFrame`).
    r.same = drawsShown(r, settling) and r.missing.items.len == 0;
    return true;
}

/// The part of a frame that needs no window, which the host tests run. Called with the pane
/// lock held: where the grid is on the surface, a snapshot of the terminal into `rows`, by
/// their places, and the colors it is drawn with.
fn buildRows(r: *R) !c.GhosttyRenderStateColors {
    const p = r.pane;
    // What the last frame could not draw is looked for again in the rows that waited for it.
    r.missing.clearRetainingCapacity();
    r.missing_keys.clearRetainingCapacity();
    // The surface or the terminal width may have changed since the last drag.
    p.clampPanLocked();
    const origin = p.originLocked();
    r.origin = .{ origin.x, origin.y };

    var full = p.force_full;
    p.force_full = false;
    if (followCellSize(r)) full = true;

    if (c.ghostty_render_state_update(r.state, p.term) != c.GHOSTTY_SUCCESS) return error.Update;
    var dirty: c.GhosttyRenderStateDirty = 0;
    _ = c.ghostty_render_state_get(r.state, c.GHOSTTY_RENDER_STATE_DATA_DIRTY, &dirty);
    if (dirty == c.GHOSTTY_RENDER_STATE_DIRTY_FULL) full = true;
    r.last_gen = p.generation();

    var colors: c.GhosttyRenderStateColors = .{ .size = @sizeOf(c.GhosttyRenderStateColors) };
    _ = c.ghostty_render_state_get(r.state, c.GHOSTTY_RENDER_STATE_DATA_COLORS, &colors);
    r.clear_rgb = .{ colors.background.r, colors.background.g, colors.background.b };
    r.colors = colors;

    var nrows: u16 = 0;
    _ = c.ghostty_render_state_get(r.state, c.GHOSTTY_RENDER_STATE_DATA_ROWS, &nrows);
    // The rows about the viewport were asked for in `setup`: the pane's offset shows part of them.
    var overscan: c.GhosttyRenderStateOverscan = .{};
    _ = c.ghostty_render_state_get(r.state, c.GHOSTTY_RENDER_STATE_DATA_OVERSCAN, &overscan);
    r.overscan_above = overscan.above;
    const count = @as(usize, nrows) + overscan.above + overscan.below;
    if (r.rows.items.len != count) {
        for (r.rows.items) |*row| row.deinit(r.gpa);
        r.rows.clearRetainingCapacity();
        try r.rows.appendNTimes(r.gpa, .{}, count);
        full = true;
    }
    if (r.waiting.items.len != count) {
        try r.waiting.resize(r.gpa, count);
        @memset(r.waiting.items, false);
    }
    // The viewport's rows as the frame draws them, in px of the grid (0 is where the pane has
    // the top of its first row now): its offset shifted them, so the first and the last lose
    // what left the viewport, and the rows about them show what came in.
    const ch: f32 = @floatFromInt(r.cell_h);
    const shift = r.origin[1] - @round(p.pad - p.pan_y);
    r.viewport = .{ -shift, @as(f32, @floatFromInt(nrows)) * ch - shift };

    if (c.ghostty_render_state_get(r.state, c.GHOSTTY_RENDER_STATE_DATA_ROW_ITERATOR, @ptrCast(&r.rows_it)) != c.GHOSTTY_SUCCESS) return error.Update;
    var scroll: c.GhosttyTerminalScrollbar = .{};
    if (p.search_hit != null or p.link_mark != null) _ = c.ghostty_terminal_get(p.term, c.GHOSTTY_TERMINAL_DATA_SCROLLBAR, &scroll);
    r.top = @intCast(scroll.offset);
    while (c.ghostty_render_state_row_iterator_next(r.rows_it)) {
        var y: i32 = 0;
        _ = c.ghostty_render_state_row_get(r.rows_it, c.GHOSTTY_RENDER_STATE_ROW_DATA_VIEWPORT_Y, &y);
        const index = rowIndex(r, y) orelse break;
        var row_dirty = full or r.waiting.items[index];
        if (!row_dirty) _ = c.ghostty_render_state_row_get(r.rows_it, c.GHOSTTY_RENDER_STATE_ROW_DATA_DIRTY, &row_dirty);
        if (!row_dirty) continue;
        try buildRowAt(r, index, y);
        const clean = false;
        _ = c.ghostty_render_state_row_set(r.rows_it, c.GHOSTTY_RENDER_STATE_ROW_OPT_DIRTY, &clean);
    }
    const clean_state: c.GhosttyRenderStateDirty = c.GHOSTTY_RENDER_STATE_DIRTY_FALSE;
    _ = c.ghostty_render_state_set(r.state, c.GHOSTTY_RENDER_STATE_OPT_DIRTY, &clean_state);
    return colors;
}

/// The row `viewport_y` places under the top of the viewport (a negative place is above
/// it): what the last update captured of it, or null when it did not.
fn rowAt(r: *const R, viewport_y: i32) ?*Row {
    return if (rowIndex(r, viewport_y)) |index| &r.rows.items[index] else null;
}

/// Where in `rows` the row `viewport_y` places is (`rowAt`).
fn rowIndex(r: *const R, viewport_y: i32) ?usize {
    const i = viewport_y + r.overscan_above;
    if (i < 0) return null;
    const index: usize = @intCast(i);
    return if (index < r.rows.items.len) index else null;
}

/// Called with the pane lock held, the row iterator on the row: builds row `index` of `rows`,
/// at viewport place `y`, from the frame's colors. It waits again if a glyph of it is missing.
fn buildRowAt(r: *R, index: usize, y: i32) !void {
    r.waiting.items[index] = false;
    r.building = index;
    defer r.building = null;
    try buildRow(r, &r.rows.items[index], y, r.top + y, r.colors);
}

/// Called with the pane lock held, after `buildImages`: the frame's instances in the order
/// `drawFrame` draws them, none outside the viewport's rectangle (`R.viewport`). The pane's
/// margin stays empty, and its offset shows of the rows about the viewport what it uncovers.
fn assembleFrame(r: *R, colors: c.GhosttyRenderStateColors) !void {
    // Assemble frame: images below, bg, images-between handled in draw, glyphs, deco, cursor.
    r.frame.clearRetainingCapacity();
    try appendRows(r, "bg");
    const bg_end = r.frame.items.len;
    try appendRows(r, "fg");
    try appendRows(r, "deco");
    const overlay = r.frame.items.len; // the keyboard's composition, or the cursor, starts here
    r.preedit_start = overlay;
    if (!try appendPreedit(r, colors)) {
        try appendCursor(r, colors);
        r.preedit_start = r.frame.items.len;
    }
    r.bg_count = bg_end;
    // They are on a row too: cut like one.
    var n = overlay;
    for (r.frame.items[overlay..]) |inst| {
        if (cut(inst, r.viewport[0], r.viewport[1])) |kept| {
            r.frame.items[n] = kept;
            n += 1;
        }
    }
    r.frame.shrinkRetainingCapacity(n);
}

/// Every row's `list` ("bg", "fg" or "deco"), by the rows' places. A row that is not whole
/// on the viewport's rectangle gives what of it is (`cut`).
fn appendRows(r: *R, comptime list: []const u8) !void {
    const ch: f32 = @floatFromInt(r.cell_h);
    for (r.rows.items, 0..) |*row, i| {
        const insts = @field(row, list).items;
        const top = @as(f32, @floatFromInt(@as(i32, @intCast(i)) - r.overscan_above)) * ch;
        if (top >= r.viewport[0] and top + ch <= r.viewport[1]) {
            try r.frame.appendSlice(r.gpa, insts);
        } else {
            for (insts) |inst| if (cut(inst, r.viewport[0], r.viewport[1])) |kept| try r.frame.append(r.gpa, kept);
        }
    }
}

/// `inst` with what lies above `top` or below `bottom` px of the grid cut off: its rect
/// and, with it, the texels a glyph or an image shows, as the quad lays them out. Null when
/// nothing is left.
fn cut(inst: Inst, top: f32, bottom: f32) ?Inst {
    const kept_top = @max(inst.rect[1], top);
    const kept_bottom = @min(inst.rect[1] + inst.rect[3], bottom);
    if (kept_top >= kept_bottom) return null;
    var kept = inst;
    if (inst.mode != 0 and inst.uv[3] > 0) {
        const px_per_texel = inst.rect[3] / inst.uv[3];
        kept.uv[1] = inst.uv[1] + (kept_top - inst.rect[1]) / px_per_texel;
        kept.uv[3] = (kept_bottom - kept_top) / px_per_texel;
    }
    kept.rect[1] = kept_top;
    kept.rect[3] = kept_bottom - kept_top;
    return kept;
}

/// Called with the pane lock held: the frame follows the pane's cell size. True when every
/// row must be built again. While a pinch changes the scale, the glyphs drawn so far are
/// kept and stretched to the cells; the size it settles at draws them again, once (#334).
fn followCellSize(r: *R) bool {
    const p = r.pane;
    const cs = p.cellSizeLocked();
    const moved = cs.w != r.cell_w or cs.h != r.cell_h;
    r.cell_w = cs.w;
    r.cell_h = cs.h;
    if (!p.glyphs_stale) {
        const stretched = p.scale_live and r.glyph_w != 0;
        if (stretched or (cs.w == r.glyph_w and cs.h == r.glyph_h)) return moved;
    }
    p.glyphs_stale = false;
    r.glyph_w = cs.w;
    r.glyph_h = cs.h;
    r.atlas.reset();
    r.mask_layers = 0;
    r.color_layers = 0;
    return true;
}

/// The quad of atlas glyph `s` with its left top at (x, y): the glyph's size, stretched by
/// as much as the cells have grown since it was drawn.
fn glyphQuad(r: *const R, s: AtlasMod.Slot, x: f32, y: f32, color: [4]u8) Inst {
    const sw: f32 = @floatFromInt(s.w);
    const sh: f32 = @floatFromInt(s.h);
    const kx = @as(f32, @floatFromInt(r.cell_w)) / @as(f32, @floatFromInt(r.glyph_w));
    const ky = @as(f32, @floatFromInt(r.cell_h)) / @as(f32, @floatFromInt(r.glyph_h));
    return .{
        .rect = .{ x, y, sw * kx, sh * ky },
        .uv = .{ @floatFromInt(s.x), @floatFromInt(s.y), sw, sh },
        .layer = @floatFromInt(s.page),
        .mode = if (s.color) 2 else 1,
        .color = color,
    };
}

/// The overlay uses default colors and sits above images. Do not copy a base
/// into that layer if it was clipped, covered, missing, or the same color as
/// its background. Skipping its quad does not change the following positions.
fn preeditBaseVisible(r: *const R, cell: PaneMod.PreeditCell, row_idx: u16, colors: c.GhosttyRenderStateColors) bool {
    const cw: f32 = @floatFromInt(r.cell_w);
    const ch: f32 = @floatFromInt(r.cell_h);
    const x = @as(f32, @floatFromInt(cell.col)) * cw;
    const y = @as(f32, @floatFromInt(row_idx)) * ch;
    const end = x + @as(f32, @floatFromInt(cell.base_span)) * cw;
    if (x + r.origin[0] < 0 or y + r.origin[1] < 0 or
        end + r.origin[0] > @as(f32, @floatFromInt(r.view_w)) or
        y + ch + r.origin[1] > @as(f32, @floatFromInt(r.view_h))) return false;
    for (r.img_above.items) |img| {
        const rect = img.rect;
        if (rect[0] < end and rect[0] + rect[2] > x and rect[1] < y + ch and rect[1] + rect[3] > y) return false;
    }
    const row = rowAt(r, row_idx) orelse return false;
    var bg = rgba(colors.background, 255);
    for (row.bg.items) |quad| {
        if (quad.rect[0] == x) bg = quad.color;
    }
    for (row.fg.items) |glyph| {
        if (glyph.rect[0] == x) return glyph.mode == 2 or !std.mem.eql(u8, glyph.color[0..3], bg[0..3]);
    }
    return false;
}

/// The keyboard's composition over the cells from the cursor on: an opaque background, the
/// glyphs in the foreground colour and an underline, so the text reads as not yet typed.
/// The cursor is drawn after it, at its end. False when there is none to draw here.
fn appendPreedit(r: *R, colors: c.GhosttyRenderStateColors) !bool {
    const text = r.pane.preeditLocked();
    if (text.len == 0) return false;
    var cur: c.GhosttyRenderStateCursor = .{ .size = @sizeOf(c.GhosttyRenderStateCursor) };
    if (c.ghostty_render_state_get(r.state, c.GHOSTTY_RENDER_STATE_DATA_CURSOR, &cur) != c.GHOSTTY_SUCCESS) return false;
    // A password prompt never shows what is typed, so its composition is not drawn either.
    if (!cur.viewport_has_value or cur.password_input) return false;
    var cols: u16 = 0;
    _ = c.ghostty_render_state_get(r.state, c.GHOSTTY_RENDER_STATE_DATA_COLS, &cols);
    const start: u16 = if (cur.wide_tail and cur.viewport_x > 0) cur.viewport_x - 1 else cur.viewport_x;
    var cells: [PaneMod.preedit_cap]PaneMod.PreeditCell = undefined;
    // The terminal clusters graphemes (an emoji with a skin tone in one cell run) in mode 2027.
    var graphemes: c.GhosttyTerminalModeConfig = .{ .mode = c.ghostty_mode_new(2027, false), .value = false };
    const clustered = c.ghostty_terminal_get(r.pane.term, c.GHOSTTY_TERMINAL_DATA_MODE, &graphemes) == c.GHOSTTY_SUCCESS and graphemes.value;
    var grid: PaneMod.PreeditGrid = .{ .term = r.pane.term, .row = cur.viewport_y, .cursor_x = cur.viewport_x };
    var placed = PaneMod.layoutPreedit(text, start, cols, clustered, &grid, &cells);
    if (placed.len != 0 and placed[0].base_span != 0 and !preeditBaseVisible(r, placed[0], cur.viewport_y, colors)) placed = placed[1..];
    if (placed.len == 0) return false;
    const cw: f32 = @floatFromInt(r.cell_w);
    const ch: f32 = @floatFromInt(r.cell_h);
    const y = @as(f32, @floatFromInt(cur.viewport_y)) * ch;
    const t = @max(1, @round(ch / 14));
    const fg = rgba(colors.foreground, 255);
    const last = placed[placed.len - 1];
    const end_col = @as(u32, last.col) + last.span;
    const x0 = @as(f32, @floatFromInt(placed[0].col)) * cw;
    const width = @as(f32, @floatFromInt(end_col - placed[0].col)) * cw;
    // VS15 can shrink the base: erase its old tail without advancing the caret.
    const cover = @max(width, @as(f32, @floatFromInt(placed[0].base_span)) * cw);
    try r.frame.append(r.gpa, .{ .rect = .{ x0, y, cover, ch }, .uv = @splat(0), .layer = 0, .mode = 0, .color = rgba(colors.background, 255) });
    for (placed) |cell| {
        const px = @as(f32, @floatFromInt(cell.col)) * cw;
        if (try glyphSlot(r, cell.utf8, 0, cell.span)) |s| try r.frame.append(r.gpa, glyphQuad(r, s, px, y, fg));
    }
    try r.frame.append(r.gpa, .{ .rect = .{ x0, y + ch - 2 * t, width, t }, .uv = @splat(0), .layer = 0, .mode = 0, .color = fg });
    // A thin bar after the composition, where the next character goes.
    if (cur.visible and end_col < cols)
        try r.frame.append(r.gpa, .{ .rect = .{ @as(f32, @floatFromInt(end_col)) * cw, y, t, ch }, .uv = @splat(0), .layer = 0, .mode = 0, .color = rgba(if (colors.cursor_has_value) colors.cursor else colors.foreground, 255) });
    return true;
}

/// Whether the cell at column `x` of viewport row `y` holds the first half of a wide
/// character. The render state's cursor reports only the second half (`wide_tail`), so
/// the head is read from the terminal's grid, which the render state was just taken from
/// under the same lock.
fn wideAt(term: c.GhosttyTerminal, x: u16, y: u16) bool {
    const point: c.GhosttyPoint = .{ .tag = c.GHOSTTY_POINT_TAG_VIEWPORT, .value = .{ .coordinate = .{ .x = x, .y = y } } };
    var ref: c.GhosttyGridRef = .{};
    var cell: c.GhosttyCell = undefined;
    var wide: c.GhosttyCellWide = c.GHOSTTY_CELL_WIDE_NARROW;
    if (c.ghostty_terminal_grid_ref(term, point, &ref) != c.GHOSTTY_SUCCESS or
        c.ghostty_grid_ref_cell(&ref, &cell) != c.GHOSTTY_SUCCESS or
        c.ghostty_cell_get(cell, c.GHOSTTY_CELL_DATA_WIDE, &wide) != c.GHOSTTY_SUCCESS) return false;
    return wide == c.GHOSTTY_CELL_WIDE_WIDE;
}

fn appendCursor(r: *R, colors: c.GhosttyRenderStateColors) !void {
    var cur: c.GhosttyRenderStateCursor = .{ .size = @sizeOf(c.GhosttyRenderStateCursor) };
    if (c.ghostty_render_state_get(r.state, c.GHOSTTY_RENDER_STATE_DATA_CURSOR, &cur) != c.GHOSTTY_SUCCESS) return;
    if (!cur.visible or !cur.viewport_has_value) return;
    const cw: f32 = @floatFromInt(r.cell_w);
    const ch: f32 = @floatFromInt(r.cell_h);
    var x = @as(f32, @floatFromInt(cur.viewport_x)) * cw;
    if (cur.wide_tail) x -= cw;
    // Over a wide character the cursor covers both of its cells, as Ghostty draws it, so
    // the glyph redrawn on the block lies on it whole. The bar stays at the first cell.
    const w = if (cur.wide_tail or wideAt(r.pane.term, cur.viewport_x, cur.viewport_y)) 2 * cw else cw;
    const y = @as(f32, @floatFromInt(cur.viewport_y)) * ch;
    const col = if (colors.cursor_has_value) colors.cursor else colors.foreground;
    const t = @max(1, @round(ch / 14));
    const solid = struct {
        fn q(xx: f32, yy: f32, ww: f32, hh: f32, k: [4]u8) Inst {
            return .{ .rect = .{ xx, yy, ww, hh }, .uv = @splat(0), .layer = 0, .mode = 0, .color = k };
        }
    }.q;
    switch (cur.visual_style) {
        c.GHOSTTY_CURSOR_STYLE_BAR => try r.frame.append(r.gpa, solid(x, y, t, ch, rgba(col, 255))),
        c.GHOSTTY_CURSOR_STYLE_UNDERLINE => try r.frame.append(r.gpa, solid(x, y + ch - t, w, t, rgba(col, 255))),
        c.GHOSTTY_CURSOR_STYLE_BLOCK_HOLLOW => {
            try r.frame.append(r.gpa, solid(x, y, w, t, rgba(col, 255)));
            try r.frame.append(r.gpa, solid(x, y + ch - t, w, t, rgba(col, 255)));
            try r.frame.append(r.gpa, solid(x, y, t, ch, rgba(col, 255)));
            try r.frame.append(r.gpa, solid(x + w - t, y, t, ch, rgba(col, 255)));
        },
        // Block: opaque in the cursor colour, with the glyph under it redrawn in the
        // background colour so it stays readable (a translucent block blended into the
        // background and left a dark cursor on dark themes).
        else => {
            try r.frame.append(r.gpa, solid(x, y, w, ch, rgba(col, 255)));
            if (rowAt(r, cur.viewport_y)) |row| for (row.fg.items) |g| {
                if (g.rect[0] == x and g.mode == 1) {
                    var inv = g;
                    inv.color = rgba(colors.background, 255);
                    try r.frame.append(r.gpa, inv);
                }
            };
        },
    }
}

const OperatorStart = struct {
    chars: [2]u8,
    len: u8,
    col: u16,
    style: c.GhosttyStyle,
    selected: bool,
};

fn supportedOperator(text: []const u8) bool {
    for ([_][]const u8{ "->", "=>", "<-", "==", "!=", "<=", ">=", "::", "&&", "||", "===", "!==", "==>" }) |op| {
        if (std.mem.eql(u8, text, op)) return true;
    }
    return false;
}

/// One captured row, `y` places under the top of the viewport (a negative place is above
/// it), which is row `screen_y` of the scrollable area.
fn buildRow(r: *R, row: *Row, y: i32, screen_y: i64, colors: c.GhosttyRenderStateColors) !void {
    row.clear();
    if (c.ghostty_render_state_row_get(r.rows_it, c.GHOSTTY_RENDER_STATE_ROW_DATA_CELLS, @ptrCast(&r.cells)) != c.GHOSTTY_SUCCESS) return;
    const cw: f32 = @floatFromInt(r.cell_w);
    const ch: f32 = @floatFromInt(r.cell_h);
    const py = @as(f32, @floatFromInt(y)) * ch;
    // A search hit and a link mark are known by their row of the scrollable area, which a
    // row above its first has none of: nothing of the kind is looked for there.
    const area_y: u64 = std.math.cast(u64, screen_y) orelse std.math.maxInt(u64);
    var previous: ?OperatorStart = null;
    var x: u16 = 0;
    while (c.ghostty_render_state_row_cells_next(r.cells)) : (x += 1) {
        const prior = previous;
        previous = null;
        const px = @as(f32, @floatFromInt(x)) * cw;
        var style: c.GhosttyStyle = .{};
        _ = c.ghostty_render_state_row_cells_get(r.cells, c.GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_STYLE, &style);
        var fg: c.GhosttyColorRgb = colors.foreground;
        var bg: c.GhosttyColorRgb = colors.background;
        const has_bg = c.ghostty_render_state_row_cells_get(r.cells, c.GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_BG_COLOR, &bg) == c.GHOSTTY_SUCCESS;
        if (!has_bg) bg = colors.background;
        if (c.ghostty_render_state_row_cells_get(r.cells, c.GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_FG_COLOR, &fg) != c.GHOSTTY_SUCCESS) fg = colors.foreground;
        var selected = false;
        _ = c.ghostty_render_state_row_cells_get(r.cells, c.GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_SELECTED, &selected);
        const flags = style.flags;
        const inverse = (flags & c.GHOSTTY_STYLE_FLAG_INVERSE != 0) != selected;
        var draw_bg = has_bg;
        if (inverse) {
            std.mem.swap(c.GhosttyColorRgb, &fg, &bg);
            draw_bg = true;
        }
        const found = if (r.pane.search_hit) |hit| hit.contains(x, area_y) else false;
        if (found) {
            bg = .{ .r = 255, .g = 198, .b = 64 };
            fg = .{ .r = 25, .g = 20, .b = 8 };
            draw_bg = true;
        }
        const fg_a: u8 = if (!found and flags & c.GHOSTTY_STYLE_FLAG_FAINT != 0) 140 else 255;

        var raw: c.GhosttyCell = 0;
        _ = c.ghostty_render_state_row_cells_get(r.cells, c.GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_RAW, &raw);
        var wide: c.GhosttyCellWide = c.GHOSTTY_CELL_WIDE_NARROW;
        _ = c.ghostty_cell_get(raw, c.GHOSTTY_CELL_DATA_WIDE, &wide);
        if (wide == c.GHOSTTY_CELL_WIDE_SPACER_TAIL or wide == c.GHOSTTY_CELL_WIDE_SPACER_HEAD) continue;
        const span: f32 = if (wide == c.GHOSTTY_CELL_WIDE_WIDE) 2 else 1;

        if (draw_bg) try row.bg.append(r.gpa, .{ .rect = .{ px, py, cw * span, ch }, .uv = @splat(0), .layer = 0, .mode = 0, .color = rgba(bg, 255) });

        if (flags & c.GHOSTTY_STYLE_FLAG_INVISIBLE == 0) {
            var gb: [PaneMod.grid_cell_utf8_cap]u8 = undefined;
            var buf: c.GhosttyBuffer = .{ .ptr = &gb, .cap = gb.len, .len = 0 };
            if (c.ghostty_render_state_row_cells_get(r.cells, c.GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_UTF8, &buf) == c.GHOSTTY_SUCCESS and buf.len > 0) {
                const utf8 = gb[0..buf.len];
                var joined = false;
                if (prior) |start| {
                    if (span == 1 and utf8.len == 1 and x == start.col + start.len and
                        selected == start.selected and std.mem.eql(u8, std.mem.asBytes(&style), std.mem.asBytes(&start.style)))
                    {
                        var operator_text: [3]u8 = undefined;
                        @memcpy(operator_text[0..start.len], start.chars[0..start.len]);
                        operator_text[start.len] = utf8[0];
                        const n = start.len + 1;
                        if (supportedOperator(operator_text[0..n])) {
                            if (try glyphSlot(r, operator_text[0..n], flags, n)) |slot| {
                                // Each cell owns its portion of the shaped bitmap. Cursor
                                // inversion, selection, and hit testing retain grid positions.
                                const first = row.fg.items.len - start.len;
                                var next = row.fg.items[first];
                                next.rect[0] = px;
                                try row.fg.append(r.gpa, next);
                                // The portions are in atlas texels: cells of the glyph size.
                                const gw: f32 = @floatFromInt(r.glyph_w);
                                const gh: f32 = @floatFromInt(r.glyph_h);
                                for (row.fg.items[first..], 0..) |*cell, i| {
                                    cell.uv = .{ @as(f32, @floatFromInt(slot.x)) + @as(f32, @floatFromInt(i)) * gw, @floatFromInt(slot.y), gw, gh };
                                    cell.layer = @floatFromInt(slot.page);
                                }
                                if (n == 2 and (std.mem.eql(u8, operator_text[0..2], "==") or std.mem.eql(u8, operator_text[0..2], "!=")))
                                    previous = .{ .chars = operator_text[0..2].*, .len = 2, .col = start.col, .style = style, .selected = selected };
                                joined = true;
                            }
                        }
                    }
                }
                if (!joined) {
                    if (try glyphSlot(r, utf8, flags, @intFromFloat(span))) |s| {
                        if (span == 1 and utf8.len == 1 and std.mem.indexOfScalar(u8, "-=<!>:&|", utf8[0]) != null)
                            previous = .{ .chars = .{ utf8[0], 0 }, .len = 1, .col = x, .style = style, .selected = selected };
                        try row.fg.append(r.gpa, glyphQuad(r, s, px, py, rgba(fg, fg_a)));
                    }
                }
            }
        }

        // Decorations.
        const t = @max(1, @round(ch / 14));
        // The link a finger is on reads as one: a single underline under all its cells.
        const linked = if (r.pane.link_mark) |mark| mark.contains(x, area_y) else false;
        const ul = if (linked) 1 else (flags & c.GHOSTTY_STYLE_UNDERLINE_MASK) >> c.GHOSTTY_STYLE_UNDERLINE_SHIFT;
        const dec_col = rgba(fg, fg_a);
        if (ul != 0) {
            try row.deco.append(r.gpa, .{ .rect = .{ px, py + ch - 2 * t, cw * span, t }, .uv = @splat(0), .layer = 0, .mode = 0, .color = dec_col });
            if (ul == 2) try row.deco.append(r.gpa, .{ .rect = .{ px, py + ch - 4 * t, cw * span, t }, .uv = @splat(0), .layer = 0, .mode = 0, .color = dec_col });
        }
        if (flags & c.GHOSTTY_STYLE_FLAG_STRIKETHROUGH != 0)
            try row.deco.append(r.gpa, .{ .rect = .{ px, py + @round(ch / 2), cw * span, t }, .uv = @splat(0), .layer = 0, .mode = 0, .color = dec_col });
        if (flags & c.GHOSTTY_STYLE_FLAG_OVERLINE != 0)
            try row.deco.append(r.gpa, .{ .rect = .{ px, py, cw * span, t }, .uv = @splat(0), .layer = 0, .mode = 0, .color = dec_col });
    }
}

/// Longest grid-plus-composition glyph followed by one atlas flags byte.
pub const GlyphKeyBuffer = [PaneMod.preedit_glyph_cap + 1]u8;

/// Atlas lookup; rasterizes on miss (procedural for U+2500–U+259F, GlyphSource otherwise),
/// at the size of the atlas glyphs.
fn glyphSlot(r: *R, utf8: []const u8, flags: c.GhosttyStyleFlags, columns: u32) !?AtlasMod.Slot {
    const glyph_w = columns * r.glyph_w;
    if (utf8.len == 1 and utf8[0] == ' ') return null;
    const cp = std.unicode.utf8Decode(utf8[0..(std.unicode.utf8ByteSequenceLength(utf8[0]) catch 1)]) catch 0;
    const box = BoxDraw.isProcedural(cp) and utf8.len == (std.unicode.utf8CodepointSequenceLength(@intCast(cp)) catch 0);
    const fl: AtlasMod.Flags = .{
        .bold = !box and flags & c.GHOSTTY_STYLE_FLAG_BOLD != 0,
        .italic = !box and flags & c.GHOSTTY_STYLE_FLAG_ITALIC != 0,
        .wide = columns > 1,
        .box = box,
    };
    var kb: GlyphKeyBuffer = undefined;
    const key = AtlasMod.makeKey(&kb, utf8, fl);
    if (r.atlas.get(key)) |s| return if (s.empty()) null else s;
    var slot: AtlasMod.Slot = undefined;
    if (box) {
        const n = @as(usize, glyph_w) * r.glyph_h;
        try r.scratch.resize(r.gpa, n);
        _ = BoxDraw.render(cp, r.scratch.items, glyph_w, r.glyph_h);
        slot = try r.atlas.put(key, r.scratch.items, glyph_w, r.glyph_h);
    } else if (r.glyphs_later) {
        // Drawn after the build, without the pane lock (`completeFrame`): the cell waits.
        try noteMissing(r, key, fl.bold, fl.italic, columns);
        return null;
    } else {
        const g = r.pane.glyphs;
        Trace.begin("Renderer.rasterizeGlyph");
        const bm = g.rasterize(g.ctx, utf8, fl.bold, fl.italic, r.glyph_w, r.glyph_h, columns);
        Trace.end();
        slot = if (bm) |b| try r.atlas.put(key, b.pixels, b.w, b.h) else try r.atlas.put(key, null, 0, 0);
    }
    return if (slot.empty()) null else slot;
}

/// An image the render state reports: what `images` knows its texture by, the quad it is
/// drawn as before the viewport's rectangle cuts it, and whether above the text.
const ReportedImage = struct { id: u32, gen: u64, inst: Inst, above: bool };

/// The image the image iterator is on, drawn at the local cell size.
fn reportedImage(r: *R) ReportedImage {
    const cw: f32 = @floatFromInt(r.cell_w);
    const ch: f32 = @floatFromInt(r.cell_h);
    var id: u32 = 0;
    var gen: u64 = 0;
    var layer: c.GhosttyImageLayer = 0;
    var col: i32 = 0;
    var row: i32 = 0;
    var gcols: u32 = 0;
    var grows: u32 = 0;
    var offx: u32 = 0;
    var offy: u32 = 0;
    var sx: u32 = 0;
    var sy: u32 = 0;
    var sw: u32 = 0;
    var sh: u32 = 0;
    const get = c.ghostty_render_state_image_get;
    const ii = r.images_it;
    _ = get(ii, c.GHOSTTY_RENDER_STATE_IMAGE_DATA_IMAGE_ID, &id);
    _ = get(ii, c.GHOSTTY_RENDER_STATE_IMAGE_DATA_IMAGE_GENERATION, &gen);
    _ = get(ii, c.GHOSTTY_RENDER_STATE_IMAGE_DATA_LAYER, &layer);
    _ = get(ii, c.GHOSTTY_RENDER_STATE_IMAGE_DATA_VIEWPORT_COL, &col);
    _ = get(ii, c.GHOSTTY_RENDER_STATE_IMAGE_DATA_VIEWPORT_ROW, &row);
    _ = get(ii, c.GHOSTTY_RENDER_STATE_IMAGE_DATA_GRID_COLS, &gcols);
    _ = get(ii, c.GHOSTTY_RENDER_STATE_IMAGE_DATA_GRID_ROWS, &grows);
    _ = get(ii, c.GHOSTTY_RENDER_STATE_IMAGE_DATA_CELL_OFFSET_X, &offx);
    _ = get(ii, c.GHOSTTY_RENDER_STATE_IMAGE_DATA_CELL_OFFSET_Y, &offy);
    _ = get(ii, c.GHOSTTY_RENDER_STATE_IMAGE_DATA_SOURCE_X, &sx);
    _ = get(ii, c.GHOSTTY_RENDER_STATE_IMAGE_DATA_SOURCE_Y, &sy);
    _ = get(ii, c.GHOSTTY_RENDER_STATE_IMAGE_DATA_SOURCE_WIDTH, &sw);
    _ = get(ii, c.GHOSTTY_RENDER_STATE_IMAGE_DATA_SOURCE_HEIGHT, &sh);

    // Offsets are in server pixels; rescale to local cells.
    const scx: f32 = if (r.pane.server_cell_w > 0) cw / @as(f32, @floatFromInt(r.pane.server_cell_w)) else 1;
    const scy: f32 = if (r.pane.server_cell_h > 0) ch / @as(f32, @floatFromInt(r.pane.server_cell_h)) else 1;
    return .{
        .id = id,
        .gen = gen,
        .inst = .{
            .rect = .{
                @as(f32, @floatFromInt(col)) * cw + @as(f32, @floatFromInt(offx)) * scx,
                @as(f32, @floatFromInt(row)) * ch + @as(f32, @floatFromInt(offy)) * scy,
                @as(f32, @floatFromInt(gcols)) * cw,
                @as(f32, @floatFromInt(grows)) * ch,
            },
            // Normalized via u_tex = image size for image draws.
            .uv = .{ @floatFromInt(sx), @floatFromInt(sy), @floatFromInt(sw), @floatFromInt(sh) },
            .layer = 0,
            .mode = 3,
            .color = .{ 255, 255, 255, 255 },
        },
        .above = layer == c.GHOSTTY_IMAGE_LAYER_ABOVE_TEXT,
    };
}

/// The texture an image had at this generation, counted as reported for the frame's cleanup.
fn cachedTexture(r: *R, id: u32, gen: u64) ?gl.GLuint {
    const t = r.images.getPtr(id) orelse return null;
    if (t.generation != gen) return null;
    t.seen = true;
    return t.tex;
}

/// What `buildImages` does with the images the terminal reports, without GL: every reported
/// image keeps its texture, whether or not it is on the viewport this frame (the cut applies
/// to the placed instance only), and the part of it on the viewport's rectangle is placed.
/// `textures` makes the texture of an image that has none yet.
fn placeImages(r: *R, textures: anytype) !void {
    r.img_below.clearRetainingCapacity();
    r.img_above.clearRetainingCapacity();
    r.img_tex_below.clearRetainingCapacity();
    r.img_tex_above.clearRetainingCapacity();
    r.images_loaded = false;
    if (c.ghostty_render_state_get(r.state, c.GHOSTTY_RENDER_STATE_DATA_IMAGE_ITERATOR, @ptrCast(&r.images_it)) != c.GHOSTTY_SUCCESS) return;
    var it = r.images.valueIterator();
    while (it.next()) |t| t.seen = false;

    while (c.ghostty_render_state_image_iterator_next(r.images_it)) {
        const image = reportedImage(r);
        // A texture stays as long as the terminal reports its image, on the viewport or not.
        const tex = cachedTexture(r, image.id, image.gen) orelse blk: {
            const made = (try textures.load(r, image.id, image.gen)) orelse continue;
            r.images_loaded = true;
            break :blk made;
        };
        const inst = cut(image.inst, r.viewport[0], r.viewport[1]) orelse continue;
        if (image.above) {
            try r.img_above.append(r.gpa, inst);
            try r.img_tex_above.append(r.gpa, tex);
        } else {
            try r.img_below.append(r.gpa, inst);
            try r.img_tex_below.append(r.gpa, tex);
        }
    }
}

/// The images' textures, made on the render thread: their pixels fetched, converted and
/// uploaded.
const UploadedTextures = struct {
    kg: c.GhosttyKittyGraphics,

    pub fn load(t: @This(), r: *R, id: u32, gen: u64) !?gl.GLuint {
        return uploadTexture(r, t.kg, id, gen);
    }
};

fn buildImages(r: *R) !void {
    var kg: c.GhosttyKittyGraphics = null;
    if (c.ghostty_terminal_get(r.pane.term, c.GHOSTTY_TERMINAL_DATA_KITTY_GRAPHICS, @ptrCast(&kg)) != c.GHOSTTY_SUCCESS) return;
    try placeImages(r, UploadedTextures{ .kg = kg });
    // Drop textures for images the terminal no longer reports.
    var rm: std.ArrayList(u32) = .empty;
    defer rm.deinit(r.gpa);
    var kit = r.images.iterator();
    while (kit.next()) |e| if (!e.value_ptr.seen) {
        gl.glDeleteTextures(1, @ptrCast(&e.value_ptr.tex));
        try rm.append(r.gpa, e.key_ptr.*);
    };
    for (rm.items) |k| _ = r.images.remove(k);
}

fn uploadTexture(r: *R, kg: c.GhosttyKittyGraphics, id: u32, gen: u64) !?gl.GLuint {
    const img = c.ghostty_kitty_graphics_image(kg, id);
    if (img == null) return null;
    var w: u32 = 0;
    var h: u32 = 0;
    var fmt: c.GhosttyKittyImageFormat = 0;
    var px: c.GhosttyString = .{};
    _ = c.ghostty_kitty_graphics_image_get(img, c.GHOSTTY_KITTY_GRAPHICS_IMAGE_DATA_WIDTH, &w);
    _ = c.ghostty_kitty_graphics_image_get(img, c.GHOSTTY_KITTY_GRAPHICS_IMAGE_DATA_HEIGHT, &h);
    _ = c.ghostty_kitty_graphics_image_get(img, c.GHOSTTY_KITTY_GRAPHICS_IMAGE_DATA_FORMAT, &fmt);
    if (c.ghostty_kitty_graphics_image_get(img, c.GHOSTTY_KITTY_GRAPHICS_IMAGE_DATA_PIXELS, &px) != c.GHOSTTY_SUCCESS) return null;
    if (w == 0 or h == 0 or px.ptr == null) return null;
    const n = @as(usize, w) * h;
    // Convert to premultiplied RGBA8.
    try r.scratch.resize(r.gpa, n * 4);
    const out = r.scratch.items;
    const src = px.ptr[0..px.len];
    var i: usize = 0;
    while (i < n) : (i += 1) {
        var p: [4]u8 = .{ 0, 0, 0, 255 };
        switch (fmt) {
            c.GHOSTTY_KITTY_IMAGE_FORMAT_RGBA => p = src[i * 4 ..][0..4].*,
            c.GHOSTTY_KITTY_IMAGE_FORMAT_RGB => p = .{ src[i * 3], src[i * 3 + 1], src[i * 3 + 2], 255 },
            c.GHOSTTY_KITTY_IMAGE_FORMAT_GRAY => p = .{ src[i], src[i], src[i], 255 },
            c.GHOSTTY_KITTY_IMAGE_FORMAT_GRAY_ALPHA => p = .{ src[i * 2], src[i * 2], src[i * 2], src[i * 2 + 1] },
            else => return null,
        }
        const a: u16 = p[3];
        out[i * 4 ..][0..4].* = .{ @intCast(p[0] * a / 255), @intCast(p[1] * a / 255), @intCast(p[2] * a / 255), p[3] };
    }
    const gop = try r.images.getOrPut(r.gpa, id);
    if (!gop.found_existing) {
        var t: [1]gl.GLuint = .{0};
        gl.glGenTextures(1, &t);
        gop.value_ptr.* = .{ .tex = t[0], .generation = gen, .w = w, .h = h, .seen = true };
    }
    gop.value_ptr.generation = gen;
    gop.value_ptr.w = w;
    gop.value_ptr.h = h;
    gop.value_ptr.seen = true;
    gl.glActiveTexture(gl.GL_TEXTURE0 + 2);
    gl.glBindTexture(gl.GL_TEXTURE_2D, gop.value_ptr.tex);
    gl.glTexParameteri(gl.GL_TEXTURE_2D, gl.GL_TEXTURE_MIN_FILTER, gl.GL_LINEAR);
    gl.glTexParameteri(gl.GL_TEXTURE_2D, gl.GL_TEXTURE_MAG_FILTER, gl.GL_LINEAR);
    gl.glTexParameteri(gl.GL_TEXTURE_2D, gl.GL_TEXTURE_WRAP_S, gl.GL_CLAMP_TO_EDGE);
    gl.glTexParameteri(gl.GL_TEXTURE_2D, gl.GL_TEXTURE_WRAP_T, gl.GL_CLAMP_TO_EDGE);
    gl.glTexImage2D(gl.GL_TEXTURE_2D, 0, gl.GL_RGBA8, @intCast(w), @intCast(h), 0, gl.GL_RGBA, gl.GL_UNSIGNED_BYTE, out.ptr);
    return gop.value_ptr.tex;
}

/// Mirror atlas pages into GL texture arrays; upload only dirty pages. `refilter`: the
/// texture is sampled with `r.filter` from now on.
fn syncAtlas(r: *R, plane: *AtlasMod.Plane, tex: gl.GLuint, layers: *usize, unit: gl.GLenum, internal: gl.GLint, format: gl.GLenum, refilter: bool) void {
    gl.glActiveTexture(unit);
    gl.glBindTexture(gl.GL_TEXTURE_2D_ARRAY, tex);
    if (refilter) setFilter(r.filter);
    const n = plane.pages.items.len;
    if (n == 0) return;
    if (n != layers.*) {
        // Reallocate storage with the new layer count; every page re-uploads.
        gl.glTexImage3D(gl.GL_TEXTURE_2D_ARRAY, 0, internal, page_size, page_size, @intCast(n), 0, format, gl.GL_UNSIGNED_BYTE, null);
        setFilter(r.filter);
        gl.glTexParameteri(gl.GL_TEXTURE_2D_ARRAY, gl.GL_TEXTURE_WRAP_S, gl.GL_CLAMP_TO_EDGE);
        gl.glTexParameteri(gl.GL_TEXTURE_2D_ARRAY, gl.GL_TEXTURE_WRAP_T, gl.GL_CLAMP_TO_EDGE);
        for (plane.dirty.items) |*d| d.* = true;
        layers.* = n;
    }
    for (plane.pages.items, plane.dirty.items, 0..) |pg, *d, i| if (d.*) {
        gl.glTexSubImage3D(gl.GL_TEXTURE_2D_ARRAY, 0, 0, 0, @intCast(i), page_size, page_size, 1, format, gl.GL_UNSIGNED_BYTE, pg.ptr);
        d.* = false;
    };
}

/// Every glyph has an empty texel around it in the atlas, so LINEAR never blends in a neighbour.
fn setFilter(filter: gl.GLint) void {
    gl.glTexParameteri(gl.GL_TEXTURE_2D_ARRAY, gl.GL_TEXTURE_MIN_FILTER, filter);
    gl.glTexParameteri(gl.GL_TEXTURE_2D_ARRAY, gl.GL_TEXTURE_MAG_FILTER, filter);
}

fn upload(r: *R, items: []const Inst) void {
    const bytes = items.len * @sizeOf(Inst);
    gl.glBindBuffer(gl.GL_ARRAY_BUFFER, r.vbo);
    if (bytes > r.vbo_cap) {
        r.vbo_cap = @max(bytes, r.vbo_cap * 2);
        gl.glBufferData(gl.GL_ARRAY_BUFFER, @intCast(r.vbo_cap), null, gl.GL_DYNAMIC_DRAW);
    }
    if (bytes > 0) gl.glBufferSubData(gl.GL_ARRAY_BUFFER, 0, @intCast(bytes), items.ptr);
}

fn drawImages(r: *R, list: []const Inst, texs: []const gl.GLuint) void {
    for (list, texs) |inst, t| {
        const info = blk: {
            var it = r.images.valueIterator();
            while (it.next()) |v| if (v.tex == t) break :blk v.*;
            continue;
        };
        gl.glUniform2f(r.u_tex, @floatFromInt(info.w), @floatFromInt(info.h));
        gl.glActiveTexture(gl.GL_TEXTURE0 + 2);
        gl.glBindTexture(gl.GL_TEXTURE_2D, t);
        upload(r, &.{inst});
        gl.glDrawArraysInstanced(gl.GL_TRIANGLE_STRIP, 0, 4, 1);
    }
}

/// Called without the pane lock: GL work only on render-thread-owned data.
fn drawFrame(r: *R) void {
    const w = r.view_w;
    const h = r.view_h;
    if (w == 0 or h == 0) return;
    gl.glViewport(0, 0, @intCast(w), @intCast(h));
    gl.glClearColor(
        @as(f32, @floatFromInt(r.clear_rgb[0])) / 255,
        @as(f32, @floatFromInt(r.clear_rgb[1])) / 255,
        @as(f32, @floatFromInt(r.clear_rgb[2])) / 255,
        1,
    );
    gl.glClear(gl.GL_COLOR_BUFFER_BIT);
    gl.glUseProgram(r.prog);
    gl.glBindVertexArray(r.vao);
    gl.glUniform2f(r.u_view, @floatFromInt(w), @floatFromInt(h));
    gl.glUniform2f(r.u_origin, r.origin[0], r.origin[1]);

    // Glyphs at the size they were drawn for map texel to pixel; stretched ones are smoothed.
    const filter = if (r.glyph_w == r.cell_w and r.glyph_h == r.cell_h) gl.GL_NEAREST else gl.GL_LINEAR;
    const refilter = filter != r.filter;
    r.filter = filter;
    syncAtlas(r, &r.atlas.mask, r.tex_mask, &r.mask_layers, gl.GL_TEXTURE0, gl.GL_R8, gl.GL_RED, refilter);
    syncAtlas(r, &r.atlas.color, r.tex_color, &r.color_layers, gl.GL_TEXTURE0 + 1, gl.GL_RGBA8, gl.GL_RGBA, refilter);

    // Backgrounds first, then images below text, then glyphs/decorations/cursor, images above
    // text, and last the keyboard's composition, which nothing of the terminal may cover.
    const bg = r.frame.items[0..r.bg_count];
    const rest = r.frame.items[r.bg_count..r.preedit_start];
    const preedit = r.frame.items[r.preedit_start..];
    gl.glUniform2f(r.u_tex, page_size, page_size);
    upload(r, bg);
    if (bg.len > 0) gl.glDrawArraysInstanced(gl.GL_TRIANGLE_STRIP, 0, 4, @intCast(bg.len));
    drawImages(r, r.img_below.items, r.img_tex_below.items);
    gl.glUniform2f(r.u_tex, page_size, page_size);
    upload(r, rest);
    if (rest.len > 0) gl.glDrawArraysInstanced(gl.GL_TRIANGLE_STRIP, 0, 4, @intCast(rest.len));
    drawImages(r, r.img_above.items, r.img_tex_above.items);
    if (preedit.len > 0) {
        gl.glUniform2f(r.u_tex, page_size, page_size);
        upload(r, preedit);
        gl.glDrawArraysInstanced(gl.GL_TRIANGLE_STRIP, 0, 4, @intCast(preedit.len));
    }
}

test "operator bitmap slices preserve cursor, style and selection cell boundaries" {
    const allocator = std.testing.allocator;
    const Source = struct {
        pixels: [6 * 12 * 3]u8 = @splat(255),

        fn raster(ctx: *anyopaque, _: []const u8, _: bool, _: bool, w: u32, h: u32, columns: u32) ?PaneMod.GlyphBitmap {
            const self: *@This() = @ptrCast(@alignCast(ctx));
            return .{ .w = w * columns, .h = h, .pixels = self.pixels[0 .. w * h * columns] };
        }
    };
    var source: Source = .{};
    const p = try Pane.create(allocator, .{ .ctx = &source, .rasterize = Source.raster });
    defer p.destroy();
    p.setFontSizePx(10); // cell 6x12
    p.write("=== -\x1b[31m>\x1b[0m !=\x1b[1;2H");
    var r: R = .{ .gpa = allocator, .pane = p, .atlas = .init(allocator), .cell_w = 6, .cell_h = 12, .glyph_w = 6, .glyph_h = 12 };
    defer r.atlas.deinit();
    try std.testing.expectEqual(c.GHOSTTY_SUCCESS, c.ghostty_render_state_new(null, &r.state));
    defer c.ghostty_render_state_free(r.state);
    try std.testing.expectEqual(c.GHOSTTY_SUCCESS, c.ghostty_render_state_row_iterator_new(null, &r.rows_it));
    defer c.ghostty_render_state_row_iterator_free(r.rows_it);
    try std.testing.expectEqual(c.GHOSTTY_SUCCESS, c.ghostty_render_state_row_cells_new(null, &r.cells));
    defer c.ghostty_render_state_row_cells_free(r.cells);
    try r.rows.append(allocator, .{});
    defer r.rows.deinit(allocator);
    const row = &r.rows.items[0];
    defer row.deinit(allocator);
    defer r.frame.deinit(allocator);

    // Select the final '=' only, keeping '!' on the preceding cell unselected.
    p.touch(0, 8 * 6 + 1, 2);
    p.touch(1, 9 * 6 + 1, 2);
    p.touch(2, 9 * 6 + 1, 2);
    try std.testing.expectEqual(c.GHOSTTY_SUCCESS, c.ghostty_render_state_update(r.state, p.term));
    var colors: c.GhosttyRenderStateColors = .{ .size = @sizeOf(c.GhosttyRenderStateColors) };
    _ = c.ghostty_render_state_get(r.state, c.GHOSTTY_RENDER_STATE_DATA_COLORS, &colors);
    _ = c.ghostty_render_state_get(r.state, c.GHOSTTY_RENDER_STATE_DATA_ROW_ITERATOR, @ptrCast(&r.rows_it));
    try std.testing.expect(c.ghostty_render_state_row_iterator_next(r.rows_it));
    try buildRow(&r, row, 0, 0, colors);

    const cells = row.fg.items;
    try std.testing.expectEqual(@as(usize, 7), cells.len);
    for (0..3) |col| {
        const x: f32 = @floatFromInt(col * 6);
        try std.testing.expectEqual(x, cells[col].rect[0]);
        try std.testing.expectEqual(@as(f32, 6), cells[col].rect[2]);
        try std.testing.expectEqual(cells[0].uv[0] + x, cells[col].uv[0]);
    }
    try std.testing.expect(!std.meta.eql(cells[3].color, cells[4].color));
    try std.testing.expect(!std.meta.eql(cells[5].color, cells[6].color));
    // Individually packed entries have an atlas gutter between them.
    try std.testing.expect(cells[3].uv[0] + 6 != cells[4].uv[0]);
    try std.testing.expect(cells[5].uv[0] + 6 != cells[6].uv[0]);
    try appendCursor(&r, colors);
    const inverted = r.frame.items[r.frame.items.len - 1];
    try std.testing.expectEqual(cells[1].rect, inverted.rect);
    try std.testing.expectEqual(cells[1].uv, inverted.uv);
}

test "a cursor on a wide character covers both of its cells" {
    const allocator = std.testing.allocator;
    const Source = struct {
        pixels: [6 * 12 * 2]u8 = @splat(255),

        fn raster(ctx: *anyopaque, _: []const u8, _: bool, _: bool, w: u32, h: u32, columns: u32) ?PaneMod.GlyphBitmap {
            const self: *@This() = @ptrCast(@alignCast(ctx));
            return .{ .w = w * columns, .h = h, .pixels = self.pixels[0 .. w * h * columns] };
        }
    };
    var source: Source = .{};
    const p = try Pane.create(allocator, .{ .ctx = &source, .rasterize = Source.raster });
    defer p.destroy();
    p.setFontSizePx(10); // cell 6x12
    // "설" on columns 0-1, "a" on column 2, an ideographic space (wide, no ink) on 3-4.
    p.write("설a\u{3000}b");
    var r: R = .{ .gpa = allocator, .pane = p, .atlas = .init(allocator), .cell_w = 6, .cell_h = 12, .glyph_w = 6, .glyph_h = 12 };
    defer r.atlas.deinit();
    try std.testing.expectEqual(c.GHOSTTY_SUCCESS, c.ghostty_render_state_new(null, &r.state));
    defer c.ghostty_render_state_free(r.state);
    try std.testing.expectEqual(c.GHOSTTY_SUCCESS, c.ghostty_render_state_row_iterator_new(null, &r.rows_it));
    defer c.ghostty_render_state_row_iterator_free(r.rows_it);
    try std.testing.expectEqual(c.GHOSTTY_SUCCESS, c.ghostty_render_state_row_cells_new(null, &r.cells));
    defer c.ghostty_render_state_row_cells_free(r.cells);
    try r.rows.append(allocator, .{});
    defer r.rows.deinit(allocator);
    const row = &r.rows.items[0];
    defer row.deinit(allocator);
    defer r.frame.deinit(allocator);

    const Shape = struct { x: f32, w: f32 };
    const Check = struct {
        /// The cursor quads `appendCursor` draws with the cursor moved by `seq`: the first is
        /// the block (or the bar, or the underline), any glyph redrawn on it follows.
        fn cursorAt(rr: *R, pp: *Pane, rw: *Row, seq: []const u8) !c.GhosttyRenderStateColors {
            pp.write(seq);
            try std.testing.expectEqual(c.GHOSTTY_SUCCESS, c.ghostty_render_state_update(rr.state, pp.term));
            var colors: c.GhosttyRenderStateColors = .{ .size = @sizeOf(c.GhosttyRenderStateColors) };
            _ = c.ghostty_render_state_get(rr.state, c.GHOSTTY_RENDER_STATE_DATA_COLORS, &colors);
            _ = c.ghostty_render_state_get(rr.state, c.GHOSTTY_RENDER_STATE_DATA_ROW_ITERATOR, @ptrCast(&rr.rows_it));
            try std.testing.expect(c.ghostty_render_state_row_iterator_next(rr.rows_it));
            try buildRow(rr, rw, 0, 0, colors);
            rr.frame.clearRetainingCapacity();
            try appendCursor(rr, colors);
            return colors;
        }

        /// The block's shape, and every redrawn glyph lies inside it.
        fn block(rr: *R, colors: c.GhosttyRenderStateColors) !Shape {
            const items = rr.frame.items;
            try std.testing.expect(items.len >= 1);
            const b = items[0];
            try std.testing.expectEqual(@as(f32, 0), b.mode);
            for (items[1..]) |g| {
                try std.testing.expectEqual(rgba(colors.background, 255), g.color);
                try std.testing.expect(g.rect[0] >= b.rect[0]);
                try std.testing.expect(g.rect[0] + g.rect[2] <= b.rect[0] + b.rect[2]);
            }
            return .{ .x = b.rect[0], .w = b.rect[2] };
        }
    };

    // On the head cell of "설": two cells wide, with the glyph redrawn on it.
    var colors = try Check.cursorAt(&r, p, row, "\x1b[1;1H");
    try std.testing.expectEqual(Shape{ .x = 0, .w = 12 }, try Check.block(&r, colors));
    try std.testing.expectEqual(@as(usize, 2), r.frame.items.len);
    try std.testing.expectEqual(@as(f32, 12), r.frame.items[1].rect[2]);
    // On its tail cell: the same.
    colors = try Check.cursorAt(&r, p, row, "\x1b[1;2H");
    try std.testing.expectEqual(Shape{ .x = 0, .w = 12 }, try Check.block(&r, colors));
    try std.testing.expectEqual(@as(usize, 2), r.frame.items.len);
    // On "a": one cell.
    colors = try Check.cursorAt(&r, p, row, "\x1b[1;3H");
    try std.testing.expectEqual(Shape{ .x = 12, .w = 6 }, try Check.block(&r, colors));
    try std.testing.expectEqual(@as(usize, 2), r.frame.items.len);
    // On a wide character with no ink: still two cells.
    colors = try Check.cursorAt(&r, p, row, "\x1b[1;4H");
    try std.testing.expectEqual(Shape{ .x = 18, .w = 12 }, try Check.block(&r, colors));
    // On an empty cell: one cell.
    colors = try Check.cursorAt(&r, p, row, "\x1b[1;7H");
    try std.testing.expectEqual(Shape{ .x = 36, .w = 6 }, try Check.block(&r, colors));
    try std.testing.expectEqual(@as(usize, 1), r.frame.items.len);

    // The underline spans both cells of "설"; the bar stays thin at its head.
    _ = try Check.cursorAt(&r, p, row, "\x1b[4 q\x1b[1;2H");
    try std.testing.expectEqual(@as(usize, 1), r.frame.items.len);
    try std.testing.expectEqual(@as(f32, 0), r.frame.items[0].rect[0]);
    try std.testing.expectEqual(@as(f32, 12), r.frame.items[0].rect[2]);
    _ = try Check.cursorAt(&r, p, row, "\x1b[4 q\x1b[1;3H");
    try std.testing.expectEqual(@as(f32, 6), r.frame.items[0].rect[2]);
    _ = try Check.cursorAt(&r, p, row, "\x1b[6 q\x1b[1;2H");
    try std.testing.expectEqual(@as(usize, 1), r.frame.items.len);
    try std.testing.expectEqual(@as(f32, 0), r.frame.items[0].rect[0]);
    try std.testing.expectEqual(@as(f32, 1), r.frame.items[0].rect[2]);
}

/// A glyph source that counts what it is asked to draw: every glyph is a full cell of ink.
const CountingSource = struct {
    calls: usize = 0,
    pixels: [96 * 96 * 3]u8 = @splat(255),

    fn raster(ctx: *anyopaque, _: []const u8, _: bool, _: bool, w: u32, h: u32, columns: u32) ?PaneMod.GlyphBitmap {
        const self: *@This() = @ptrCast(@alignCast(ctx));
        self.calls += 1;
        return .{ .w = w * columns, .h = h, .pixels = self.pixels[0 .. w * h * columns] };
    }
};

/// The GL-free part of a render thread: what `buildFrame` makes of the pane's cell size and
/// its rows, without a surface to draw to.
const TestRenderer = struct {
    r: R,

    fn init(p: *Pane) !TestRenderer {
        const gpa = std.testing.allocator;
        var t: TestRenderer = .{ .r = .{ .gpa = gpa, .pane = p, .atlas = .init(gpa), .glyphs_later = true } };
        errdefer t.deinit();
        if (c.ghostty_render_state_new(null, &t.r.state) != c.GHOSTTY_SUCCESS) return error.OutOfMemory;
        requestOverscan(t.r.state);
        if (c.ghostty_render_state_row_iterator_new(null, &t.r.rows_it) != c.GHOSTTY_SUCCESS) return error.OutOfMemory;
        if (c.ghostty_render_state_row_cells_new(null, &t.r.cells) != c.GHOSTTY_SUCCESS) return error.OutOfMemory;
        if (c.ghostty_render_state_image_iterator_new(null, &t.r.images_it) != c.GHOSTTY_SUCCESS) return error.OutOfMemory;
        return t;
    }

    fn deinit(t: *TestRenderer) void {
        const gpa = t.r.gpa;
        if (t.r.cells != null) c.ghostty_render_state_row_cells_free(t.r.cells);
        if (t.r.rows_it != null) c.ghostty_render_state_row_iterator_free(t.r.rows_it);
        if (t.r.images_it != null) c.ghostty_render_state_image_iterator_free(t.r.images_it);
        if (t.r.state != null) c.ghostty_render_state_free(t.r.state);
        for (t.r.rows.items) |*row| row.deinit(gpa);
        t.r.rows.deinit(gpa);
        t.r.frame.deinit(gpa);
        t.r.scratch.deinit(gpa);
        t.r.atlas.deinit();
        t.r.images.deinit(gpa);
        t.r.img_below.deinit(gpa);
        t.r.img_above.deinit(gpa);
        t.r.img_tex_below.deinit(gpa);
        t.r.img_tex_above.deinit(gpa);
        t.r.shown_frame.deinit(gpa);
        t.r.shown_images.deinit(gpa);
        t.r.shown_image_tex.deinit(gpa);
        t.r.missing.deinit(gpa);
        t.r.missing_keys.deinit(gpa);
        t.r.waiting.deinit(gpa);
        t.r.shared_px.deinit(gpa);
    }

    /// One frame without a window, as `buildFrame` makes it when the buffer does not answer:
    /// the pane's surface is the view. Without the images: their textures need GL.
    fn build(t: *TestRenderer) !void {
        try t.buildOnly();
        try completeFrame(&t.r);
    }

    /// The build alone, as `buildFrame` makes it with the pane lock held: missing glyphs wait.
    fn buildOnly(t: *TestRenderer) !void {
        t.r.view_w = t.r.pane.surface_w;
        t.r.view_h = t.r.pane.surface_h;
        const colors = try buildRows(&t.r);
        try assembleFrame(&t.r, colors);
    }

    /// The quad of the glyph in column `x` of the viewport's first row.
    fn glyph(t: *const TestRenderer, x: usize) Inst {
        return rowAt(&t.r, 0).?.fg.items[x];
    }

    /// The images the last update reports, placed as `buildImages` draws them: into the
    /// frame's image lists, with `textures` standing in for their GL textures.
    fn placeImagesWith(t: *TestRenderer, textures: anytype) !void {
        try placeImages(&t.r, textures);
    }

    /// A frame as `run` makes one, the wake taken first: whether `present` would hand it to
    /// the window. `textures` places the images too (null: the frame keeps the ones it has).
    fn frame(t: *TestRenderer, textures: anytype) !bool {
        t.r.pane.wake = false;
        const settling = t.r.pane.settle_frames > 0;
        try t.build();
        if (@TypeOf(textures) != @TypeOf(null)) try t.placeImagesWith(textures);
        return !drawsShown(&t.r, settling);
    }

    /// What `present` keeps of a frame it hands the window, without the GL: the atlas pages
    /// it uploads are clean afterwards.
    fn show(t: *TestRenderer) void {
        for (t.r.atlas.mask.dirty.items) |*d| d.* = false;
        for (t.r.atlas.color.dirty.items) |*d| d.* = false;
        remember(&t.r);
    }
};

test "a frame that draws what the window shows is not drawn again: a title is not, a cell is" {
    var source: CountingSource = .{};
    const p = try Pane.create(std.testing.allocator, .{ .ctx = &source, .rasterize = CountingSource.raster });
    defer p.destroy();
    p.setFontSizePx(10);
    p.write("abc");
    var t = try TestRenderer.init(p);
    defer t.deinit();

    try std.testing.expect(try t.frame(null)); // the window shows nothing yet
    t.show();
    try std.testing.expect(!try t.frame(null)); // woken for nothing new
    p.write("\x1b]0;building\x07"); // the program sets its title, as agents do many times a second
    try std.testing.expect(!try t.frame(null));
    p.write("\x07"); // a bell
    try std.testing.expect(!try t.frame(null));

    p.write("d"); // a cell
    try std.testing.expect(try t.frame(null));
    t.show();
    p.write("\x1b[1;1H"); // the cursor
    try std.testing.expect(try t.frame(null));
    t.show();
    try std.testing.expect(!try t.frame(null));
    p.setScale(1.5, true); // a pinch: other cells, the same glyphs
    try std.testing.expect(try t.frame(null));
}

test "the window gets a frame that draws the same after an attach in place, while it settles and while its buffer lags" {
    var source: CountingSource = .{};
    const p = try Pane.create(std.testing.allocator, .{ .ctx = &source, .rasterize = CountingSource.raster });
    defer p.destroy();
    p.setFontSizePx(10);
    p.surface_w = 640;
    p.surface_h = 400;
    p.write("abc");
    var t = try TestRenderer.init(p);
    defer t.deinit();
    _ = try t.frame(null);
    t.show();

    p.present_due = true; // an attach in place
    try std.testing.expect(try t.frame(null));
    try std.testing.expect(!try t.frame(null)); // once
    p.settle_frames = 1; // the last frame of an attach-settle, which spends it at the take
    try std.testing.expect(try t.frame(null));
    p.settle_frames = 0;

    // The frame asks for the next one itself (the scroll offset rests a frame at a time).
    p.wake = false;
    try t.build();
    p.wake = true;
    try std.testing.expect(!drawsShown(&t.r, false));

    // The pane has a size the window's buffer does not answer yet: drawing gets it one.
    p.wake = false;
    try t.build();
    p.surface_w = 320;
    try std.testing.expect(!drawsShown(&t.r, false));
}

/// A glyph source that says whether the pane lock was held while it drew, and draws every
/// glyph as a full cell of ink.
const LockWatchingSource = struct {
    pane: ?*Pane = null,
    calls: usize = 0,
    under_lock: usize = 0,
    pixels: [96 * 96 * 3]u8 = @splat(255),

    fn raster(ctx: *anyopaque, _: []const u8, _: bool, _: bool, w: u32, h: u32, columns: u32) ?PaneMod.GlyphBitmap {
        const self: *@This() = @ptrCast(@alignCast(ctx));
        self.calls += 1;
        if (self.pane) |p| {
            if (std.c.pthread_mutex_trylock(&p.lock.m) == .SUCCESS) {
                _ = std.c.pthread_mutex_unlock(&p.lock.m);
            } else self.under_lock += 1;
        }
        return .{ .w = w * columns, .h = h, .pixels = self.pixels[0 .. w * h * columns] };
    }
};

test "missing glyphs are drawn after the build, once each, without the pane lock" {
    var source: LockWatchingSource = .{};
    const p = try Pane.create(std.testing.allocator, .{ .ctx = &source, .rasterize = LockWatchingSource.raster });
    defer p.destroy();
    source.pane = p;
    p.setFontSizePx(10);
    p.write("\xea\xb0\x80\xea\xb0\x80\xeb\x82\x98\r\nab");
    var t = try TestRenderer.init(p);
    defer t.deinit();

    // The build, with the pane lock held as `run` holds it: nothing is drawn, the rows wait.
    p.lock.lock();
    try t.buildOnly();
    p.lock.unlock();
    try std.testing.expectEqual(@as(usize, 0), source.calls);
    try std.testing.expectEqual(@as(usize, 4), t.r.missing.items.len); // the first syllable twice: once
    try std.testing.expectEqual(@as(usize, 0), rowAt(&t.r, 0).?.fg.items.len);
    try std.testing.expect(!drawsShown(&t.r, false)); // a frame that lacks glyphs is never the shown one

    // The frame is completed without the lock: every glyph drawn once, the rows built again.
    try completeFrame(&t.r);
    try std.testing.expectEqual(@as(usize, 4), source.calls);
    try std.testing.expectEqual(@as(usize, 0), source.under_lock);
    try std.testing.expectEqual(@as(usize, 0), t.r.missing.items.len);
    try std.testing.expectEqual(@as(usize, 3), rowAt(&t.r, 0).?.fg.items.len);
    try std.testing.expectEqual(@as(usize, 2), rowAt(&t.r, 1).?.fg.items.len);
    try std.testing.expectEqual(@as(usize, 5), t.r.frame.items.len - 1); // and the frame, with the cursor

    // Drawn once: the next frame finds them all.
    p.write("\xea\xb0\x80");
    try t.build();
    try std.testing.expectEqual(@as(usize, 4), source.calls);
}

test "a glyph drawn for one window is not drawn again for another: the panes share it" {
    var source: LockWatchingSource = .{};
    var cache: PaneMod.GlyphCache = .init(std.testing.allocator, PaneMod.GlyphCache.default_cap);
    defer cache.deinit();
    const glyphs: PaneMod.GlyphSource = .{ .ctx = &source, .rasterize = LockWatchingSource.raster, .cache = &cache };
    const a = try Pane.create(std.testing.allocator, glyphs);
    defer a.destroy();
    const b = try Pane.create(std.testing.allocator, glyphs);
    defer b.destroy();
    for ([_]*Pane{ a, b }) |p| {
        p.setFontSizePx(10);
        p.write("\xea\xb0\x80\xeb\x82\x98ab");
    }
    var ta = try TestRenderer.init(a);
    defer ta.deinit();
    try ta.build();
    try std.testing.expectEqual(@as(usize, 4), source.calls);

    // A pane that gets a window anew starts with an empty atlas: it takes the glyphs drawn.
    var tb = try TestRenderer.init(b);
    defer tb.deinit();
    try tb.build();
    try std.testing.expectEqual(@as(usize, 4), source.calls);
    try std.testing.expectEqual(@as(usize, 4), rowAt(&tb.r, 0).?.fg.items.len);
    try std.testing.expectEqualDeep(ta.glyph(0), tb.glyph(0));

    // At another size they are drawn again.
    b.setFontSizePx(14);
    try tb.build();
    try std.testing.expectEqual(@as(usize, 8), source.calls);
}

test "another font drops the glyphs every pane shares" {
    var source: LockWatchingSource = .{};
    var cache: PaneMod.GlyphCache = .init(std.testing.allocator, PaneMod.GlyphCache.default_cap);
    defer cache.deinit();
    const glyphs: PaneMod.GlyphSource = .{ .ctx = &source, .rasterize = LockWatchingSource.raster, .cache = &cache };
    const a = try Pane.create(std.testing.allocator, glyphs);
    defer a.destroy();
    const b = try Pane.create(std.testing.allocator, glyphs);
    defer b.destroy();
    for ([_]*Pane{ a, b }) |p| {
        p.setFontSizePx(10);
        p.write("ab");
    }
    var ta = try TestRenderer.init(a);
    defer ta.deinit();
    try ta.build();
    try std.testing.expectEqual(@as(usize, 2), source.calls);
    // The glyph source draws another font, with the line height the panes have (the cells keep
    // their size): the panes are told, one after another.
    a.setFont(1.2);
    var tb = try TestRenderer.init(b);
    defer tb.deinit();
    try tb.build();
    try std.testing.expectEqual(@as(usize, 4), source.calls);
}

test "the keyboard's composition waits for its glyph as a row does" {
    var source: LockWatchingSource = .{};
    const p = try Pane.create(std.testing.allocator, .{ .ctx = &source, .rasterize = LockWatchingSource.raster });
    defer p.destroy();
    p.setFontSizePx(10);
    var t = try TestRenderer.init(p);
    defer t.deinit();
    try t.build();
    const before = t.r.frame.items.len;
    _ = p.setPreedit("\xed\x95\x9c");
    p.setPreeditVisible(true);
    try t.buildOnly();
    try std.testing.expectEqual(@as(usize, 1), t.r.missing.items.len);
    try completeFrame(&t.r);
    try std.testing.expectEqual(@as(usize, 1), source.calls);
    try std.testing.expect(t.r.frame.items.len > before);
}

test "a frame whose quads are the same is drawn when a glyph or an image under them is new" {
    var source: CountingSource = .{};
    const p = try paneWithHistory(&source);
    defer p.destroy();
    writeImageAbove(p);
    _ = p.pan(0, 12, 0, 0); // one row back: the image is on the viewport
    var t = try TestRenderer.init(p);
    defer t.deinit();
    var textures: CountingTextures = .{};

    try std.testing.expect(try t.frame(&textures));
    try std.testing.expectEqual(@as(usize, 1), textures.loads);
    t.show();
    try std.testing.expect(!try t.frame(&textures)); // placed from the texture it has

    t.r.images.getPtr(1).?.generation += 1; // the image is sent again: its texture is made again
    try std.testing.expect(try t.frame(&textures));
    try std.testing.expectEqual(@as(usize, 2), textures.loads);
    t.show();
    try std.testing.expect(!try t.frame(&textures));

    t.r.atlas.mask.dirty.items[0] = true; // a glyph drawn into the atlas, where an old one was
    try std.testing.expect(try t.frame(&textures));
    t.show();
    try std.testing.expect(!try t.frame(&textures));
}

fn cellOf(p: *Pane) Pane.CellSize {
    p.lock.lock();
    defer p.lock.unlock();
    return p.cellSizeLocked();
}

test "a pinch keeps the glyph atlas and stretches its glyphs; the size it settles at is drawn once" {
    var source: CountingSource = .{};
    const p = try Pane.create(std.testing.allocator, .{ .ctx = &source, .rasterize = CountingSource.raster });
    defer p.destroy();
    p.setFontSizePx(10);
    p.write("abc");
    var t = try TestRenderer.init(p);
    defer t.deinit();

    try t.build();
    try std.testing.expectEqual(@as(usize, 3), source.calls);
    const start = cellOf(p);
    const sw: f32 = @floatFromInt(start.w);
    const sh: f32 = @floatFromInt(start.h);

    // Each step of the fingers changes the cell size; no glyph is drawn again.
    for ([_]f32{ 1.2, 1.5, 2 }) |scale| {
        p.setScale(scale, true);
        try t.build();
        try std.testing.expectEqual(@as(usize, 3), source.calls);
        const cs = cellOf(p);
        const cw: f32 = @floatFromInt(cs.w);
        const ch: f32 = @floatFromInt(cs.h);
        // Laid out on the new cells, drawn from the glyphs of the old ones.
        try std.testing.expectEqual([4]f32{ cw, 0, cw, ch }, t.glyph(1).rect);
        try std.testing.expectEqual(sw, t.glyph(1).uv[2]);
        try std.testing.expectEqual(sh, t.glyph(1).uv[3]);
    }

    // The fingers lift: the glyphs are drawn again at the size they left, once.
    p.setScale(2, false);
    try t.build();
    try std.testing.expectEqual(@as(usize, 6), source.calls);
    const end = cellOf(p);
    const ew: f32 = @floatFromInt(end.w);
    const eh: f32 = @floatFromInt(end.h);
    try std.testing.expectEqual([4]f32{ ew, 0, ew, eh }, t.glyph(1).rect);
    try std.testing.expectEqual([4]f32{ t.glyph(1).uv[0], t.glyph(1).uv[1], ew, eh }, t.glyph(1).uv);
    try t.build();
    try std.testing.expectEqual(@as(usize, 6), source.calls);
}

test "a text size that no pinch changes draws its glyphs at once" {
    var source: CountingSource = .{};
    const p = try Pane.create(std.testing.allocator, .{ .ctx = &source, .rasterize = CountingSource.raster });
    defer p.destroy();
    p.setFontSizePx(10);
    p.write("abc");
    var t = try TestRenderer.init(p);
    defer t.deinit();
    try t.build();

    p.setScale(1.5, false);
    try t.build();
    try std.testing.expectEqual(@as(usize, 6), source.calls);
    const cs = cellOf(p);
    try std.testing.expectEqual(@as(f32, @floatFromInt(cs.w)), t.glyph(1).uv[2]);
}

test "another font during a pinch drops the glyphs at once" {
    var source: CountingSource = .{};
    const p = try Pane.create(std.testing.allocator, .{ .ctx = &source, .rasterize = CountingSource.raster });
    defer p.destroy();
    p.setFontSizePx(10);
    p.write("abc");
    var t = try TestRenderer.init(p);
    defer t.deinit();
    try t.build();

    p.setScale(1.5, true);
    p.setFont(1.2);
    try t.build();
    try std.testing.expectEqual(@as(usize, 6), source.calls);
}

test "a pinch that never ends is over when the pane loses its surface" {
    var source: CountingSource = .{};
    const p = try Pane.create(std.testing.allocator, .{ .ctx = &source, .rasterize = CountingSource.raster });
    defer p.destroy();
    p.setFontSizePx(10);
    p.write("abc");
    var t = try TestRenderer.init(p);
    defer t.deinit();
    try t.build();

    p.setScale(1.5, true);
    try t.build();
    try std.testing.expectEqual(@as(usize, 3), source.calls);
    // The app leaves the screen with the fingers still down: no settled size ever comes.
    p.detachSurface();
    // Shown again, the pane draws with a new render thread, and the next size it is given
    // (here a font size setting) is drawn as it is, not stretched.
    var shown = try TestRenderer.init(p);
    defer shown.deinit();
    try shown.build();
    const before = source.calls;
    p.setFontSizePx(12);
    try shown.build();
    try std.testing.expectEqual(before + 3, source.calls);
}

/// A pane of 6x12 cells on a 288 px surface with 100 lines written: the 24-row viewport is
/// at the newest line, with history above it.
fn paneWithHistory(source: *CountingSource) !*Pane {
    const p = try Pane.create(std.testing.allocator, .{ .ctx = source, .rasterize = CountingSource.raster });
    errdefer p.destroy();
    p.setFontSizePx(10);
    p.surface_h = 288;
    var i: usize = 0;
    while (i < 100) : (i += 1) p.write("line\r\n");
    return p;
}

test "renderer: one row of overscan is asked for, and rows are found by their place" {
    var source: CountingSource = .{};
    const p = try paneWithHistory(&source);
    defer p.destroy();
    _ = p.pan(0, 24, 0, 0); // two rows into the scrollback: a row above the viewport, and one below
    var t = try TestRenderer.init(p);
    defer t.deinit();
    try t.build();

    var rows: u16 = 0;
    try std.testing.expectEqual(c.GHOSTTY_SUCCESS, c.ghostty_render_state_get(t.r.state, c.GHOSTTY_RENDER_STATE_DATA_ROWS, &rows));
    try std.testing.expectEqual(@as(i32, 1), t.r.overscan_above);
    try std.testing.expectEqual(@as(usize, 1) + rows + 1, t.r.rows.items.len);
    try std.testing.expect(rowAt(&t.r, -1) != null);
    try std.testing.expect(rowAt(&t.r, rows) != null);
    try std.testing.expect(rowAt(&t.r, -2) == null);
    try std.testing.expect(rowAt(&t.r, rows + 1) == null);
}

test "renderer: the origin has the offset" {
    var source: CountingSource = .{};
    const p = try paneWithHistory(&source);
    defer p.destroy();
    var t = try TestRenderer.init(p);
    defer t.deinit();
    try t.build();
    const plain = t.r.origin[1]; // at rest the offset is 0
    _ = p.pan(0, 4, 0, 0); // a third of a row: no row scrolls, the place moves
    try t.build();
    try std.testing.expectEqual(p.originLocked().y, t.r.origin[1]);
    try std.testing.expectEqual(plain + 4, t.r.origin[1]);
}

test "renderer: a scrolled row is drawn where its place is" {
    var source: CountingSource = .{};
    const p = try Pane.create(std.testing.allocator, .{ .ctx = &source, .rasterize = CountingSource.raster });
    defer p.destroy();
    p.setFontSizePx(10);
    p.surface_h = 288;
    // Every row has one glyph more than the one before it: a row is known by its glyphs.
    var i: usize = 0;
    while (i < 100) : (i += 1) {
        for (0..(i % 16) + 1) |_| p.write("x");
        p.write("\r\n");
    }
    var t = try TestRenderer.init(p);
    defer t.deinit();
    try t.build();
    const top = rowAt(&t.r, 0).?.fg.items.len;
    const second = rowAt(&t.r, 1).?.fg.items.len;
    try std.testing.expect(top != second);

    _ = p.pan(0, 12, 0, 0); // one row back: the line before the top one is on top, and every row is a place lower
    try t.build();
    try std.testing.expectEqual(top - 1, rowAt(&t.r, 0).?.fg.items.len);
    try std.testing.expectEqual(top, rowAt(&t.r, 1).?.fg.items.len);
    try std.testing.expectEqual(second, rowAt(&t.r, 2).?.fg.items.len);
    try std.testing.expectEqual(top - 2, rowAt(&t.r, -1).?.fg.items.len); // and the row above the viewport has its own line
}

/// `inst` lies between `top` and `bottom` px of the surface, under `origin_y`.
fn expectOn(origin_y: f32, inst: Inst, top: f32, bottom: f32) !void {
    const y = origin_y + inst.rect[1];
    try std.testing.expect(y >= top and y + inst.rect[3] <= bottom);
}

/// How many instances of the last frame are drawn `height` px tall from `y` px of the surface.
fn drawnAt(t: *TestRenderer, y: f32, height: f32) usize {
    var n: usize = 0;
    for (t.r.frame.items) |inst| {
        if (t.r.origin[1] + inst.rect[1] == y and inst.rect[3] == height) n += 1;
    }
    return n;
}

/// A texture source for checks that only look at placement: every image gets the same one.
const PlainTextures = struct {
    fn load(_: @This(), _: *R, _: u32, _: u64) !?gl.GLuint {
        return 7;
    }
};

/// Every instance of the last frame, and every image the last update reports, lies between
/// `top` and `bottom` px of the surface: the viewport's rectangle.
fn expectOnViewport(t: *TestRenderer, top: f32, bottom: f32) !void {
    try placeImages(&t.r, PlainTextures{});
    for (t.r.frame.items) |inst| try expectOn(t.r.origin[1], inst, top, bottom);
    for (t.r.img_below.items) |inst| try expectOn(t.r.origin[1], inst, top, bottom);
    for (t.r.img_above.items) |inst| try expectOn(t.r.origin[1], inst, top, bottom);
}

/// An image in the cursor's cell, then 24 lines of output: it ends one row above the
/// viewport. The server's cells are 6x12 px, as the local ones.
fn writeImageAbove(p: *Pane) void {
    p.resize(80, 24, 6, 12);
    p.write("\x1b_Ga=T,t=d,f=24,i=1,p=1,s=1,v=2;////////\x1b\\");
    var i: usize = 0;
    while (i < 24) : (i += 1) p.write("\r\n");
}

test "renderer: nothing is drawn outside the viewport's rectangle at rest, with history above" {
    var source: CountingSource = .{};
    const p = try paneWithHistory(&source);
    defer p.destroy();
    p.setPaddingPx(20); // a margin above and below the grid, on a surface that has it
    p.surface_h = 328;
    writeImageAbove(p);
    var t = try TestRenderer.init(p);
    defer t.deinit();
    try t.build();
    try std.testing.expectEqual(@as(i32, 1), t.r.overscan_above); // the row above the viewport is captured
    try expectOnViewport(&t, 20, 20 + 24 * 12);
}

test "renderer: the offset shows of the rows about the viewport only what it uncovers" {
    var source: CountingSource = .{};
    const p = try paneWithHistory(&source);
    defer p.destroy();
    p.setPaddingPx(20);
    p.surface_h = 328;
    var t = try TestRenderer.init(p);
    defer t.deinit();
    _ = p.pan(0, 4, 0, 0); // the viewport's rows 4 px lower: the row above shows its bottom 4 px, the last row hides as much
    try t.build();
    try expectOnViewport(&t, 20, 20 + 24 * 12);

    try std.testing.expectEqual(@as(usize, 4), drawnAt(&t, 20, 4)); // "line": four glyphs, 4 px tall now, at the top of the viewport
    try std.testing.expect(drawnAt(&t, 300, 8) > 0); // the last row still shows 8 px

    _ = p.pan(0, 24, 0, 0);
    _ = p.pan(0, -8, 0, 0); // two rows back, and the rows 4 px higher: the first row hides its top, the row below shows as much
    try t.build();
    try expectOnViewport(&t, 20, 20 + 24 * 12);
    try std.testing.expectEqual(@as(usize, 4), drawnAt(&t, 304, 4)); // the top 4 px of the row below the viewport
    try std.testing.expect(drawnAt(&t, 20, 8) > 0); // the first row still shows 8 px
}

/// The instances of the images the last `placeImagesWith` placed, above or below the text.
fn placedImageRects(t: *const TestRenderer) []const Inst {
    return if (t.r.img_below.items.len > 0) t.r.img_below.items else t.r.img_above.items;
}

test "renderer: an image in the rows about the viewport is drawn only where the offset uncovers it" {
    var source: CountingSource = .{};
    const p = try paneWithHistory(&source);
    defer p.destroy();
    writeImageAbove(p);
    var t = try TestRenderer.init(p);
    defer t.deinit();

    try t.build();
    try t.placeImagesWith(PlainTextures{});
    try std.testing.expectEqual(@as(usize, 0), placedImageRects(&t).len); // at rest none of it is on the viewport

    _ = p.pan(0, 4, 0, 0); // the row it is in shows its bottom 4 px
    try t.build();
    try t.placeImagesWith(PlainTextures{});
    try std.testing.expectEqual(@as(usize, 1), placedImageRects(&t).len);
    try std.testing.expectEqual([4]f32{ 0, -4, 6, 4 }, placedImageRects(&t)[0].rect);
}

/// A texture source that counts what it loads and caches one fake texture per image: what
/// the render thread's own loader does, without the GL.
const CountingTextures = struct {
    loads: usize = 0,

    fn load(l: *@This(), r: *R, id: u32, gen: u64) !?gl.GLuint {
        l.loads += 1;
        const gop = try r.images.getOrPut(r.gpa, id);
        if (!gop.found_existing) gop.value_ptr.* = .{ .tex = 7, .generation = gen, .w = 1, .h = 1, .seen = true };
        gop.value_ptr.generation = gen;
        gop.value_ptr.seen = true;
        return gop.value_ptr.tex;
    }
};

test "images: a texture stays while the terminal reports its image, on the viewport or not" {
    var source: CountingSource = .{};
    const p = try paneWithHistory(&source);
    defer p.destroy();
    writeImageAbove(p); // the image ends one row above the viewport, which the render state reports
    var t = try TestRenderer.init(p);
    defer t.deinit();
    var textures: CountingTextures = .{};

    _ = p.pan(0, 12, 0, 0); // one row back: the image is on the viewport, so it gets a texture
    try t.build();
    try t.placeImagesWith(&textures);
    try std.testing.expectEqual(@as(usize, 1), textures.loads);
    try std.testing.expectEqual(@as(usize, 1), placedImageRects(&t).len);

    _ = p.pan(0, -12, 0, 0); // at the newest line again: the image is only in the row above, unplaced
    try t.build();
    try t.placeImagesWith(&textures);
    try std.testing.expectEqual(@as(usize, 0), placedImageRects(&t).len);
    try std.testing.expectEqual(@as(usize, 1), textures.loads); // it was not fetched again
    try std.testing.expect(t.r.images.get(1).?.seen); // reported: the frame's cleanup keeps it

    _ = p.pan(0, 4, 0, 0); // the row it is in shows its bottom 4 px again
    try t.build();
    try t.placeImagesWith(&textures);
    try std.testing.expectEqual(@as(usize, 1), placedImageRects(&t).len);
    try std.testing.expectEqual(@as(usize, 1), textures.loads); // placed from the texture it had
    try std.testing.expectEqual([4]f32{ 0, -4, 6, 4 }, placedImageRects(&t)[0].rect);
}

test "cut: a quad loses its texels with its px, and is nothing outside" {
    const glyph: Inst = .{ .rect = .{ 6, -12, 6, 12 }, .uv = .{ 10, 20, 6, 12 }, .layer = 1, .mode = 1, .color = .{ 1, 2, 3, 255 } };
    const bottom = cut(glyph, -4, 284).?; // the row above the viewport, of which 4 px show
    try std.testing.expectEqual([4]f32{ 6, -4, 6, 4 }, bottom.rect);
    try std.testing.expectEqual([4]f32{ 10, 28, 6, 4 }, bottom.uv);
    try std.testing.expect(cut(glyph, 0, 284) == null); // at rest the row is wholly above the viewport

    const image: Inst = .{ .rect = .{ 0, 264, 60, 24 }, .uv = .{ 0, 0, 10, 20 }, .layer = 0, .mode = 3, .color = .{ 255, 255, 255, 255 } };
    const top = cut(image, -4, 284).?; // the last row, of which 4 px are below the viewport
    try std.testing.expectEqual([4]f32{ 0, 264, 60, 20 }, top.rect);
    try std.testing.expectEqual(@as(f32, 0), top.uv[1]);
    try std.testing.expect(top.uv[3] > 16 and top.uv[3] < 17); // 20 px of the 24 it was drawn as

    const solid: Inst = .{ .rect = .{ 0, 276, 6, 12 }, .uv = @splat(0), .layer = 0, .mode = 0, .color = @splat(255) };
    try std.testing.expectEqual([4]f32{ 0, 276, 6, 8 }, cut(solid, -4, 284).?.rect);
}

test "renderer: a thousand ordinary frames of an unchanged size keep the pane's size and offset" {
    // The decision, pure: the size is unchanged → keep, whether or not a settle is pending,
    // however many ordinary frames ask.
    var asks: usize = 0;
    while (asks < 1000) : (asks += 1) {
        try std.testing.expectEqual(.keep, retakeSize(1280, 800, 1280, 800, false));
        try std.testing.expectEqual(.keep, retakeSize(1280, 800, 1280, 800, false));
        try std.testing.expectEqual(.take_and_reset, retakeSize(1280, 800, 1000, 700, false)); // a changed size
        try std.testing.expectEqual(.take_and_reset, retakeSize(1280, 800, 1280, 800, true)); // the settle expires
    }

    // The pane: a thousand frames that answer the same size leave its offset as the drag left it.
    var source: CountingSource = .{};
    const p = try paneWithHistory(&source);
    defer p.destroy();
    p.surface_w = 640;
    p.surface_h = 400;
    _ = p.pan(0, 4, 0, 0);
    var t = try TestRenderer.init(p);
    defer t.deinit();
    t.r.view_w = 640; // the buffer has answered this size before
    t.r.view_h = 400;
    var frames: usize = 0;
    while (frames < 1000) : (frames += 1) {
        takeSurfaceSize(&t.r, 640, 400);
        try std.testing.expectEqual(@as(f32, 4), p.scrollOffsetPx()); // not thrown away once a frame
    }
    try std.testing.expectEqual(@as(u32, 640), p.surface_w);
    try std.testing.expectEqual(@as(f32, 4), p.scrollOffsetPx());
}

const ms = std.time.ns_per_ms;

test "swapScaling: a new thread's first buffer goes out scaled, whatever its size" {
    try std.testing.expectEqual(Scaling.scale_to_window, swapScaling(null, 0, 863, 1026, 863, 1026));
    try std.testing.expectEqual(Scaling.scale_to_window, swapScaling(null, 0, 863, 610, 863, 1026));
}

test "swapScaling: right after the start a buffer of the window's size still goes out scaled" {
    // #117: the window's size and its sync come within the first tens of ms of a return.
    try std.testing.expectEqual(Scaling.scale_to_window, swapScaling(.scale_to_window, 10 * ms, 863, 1026, 863, 1026));
    try std.testing.expectEqual(Scaling.scale_to_window, swapScaling(.scale_to_window, scale_hold_ns - 1, 863, 1026, 863, 1026));
}

test "swapScaling: the hold covers a return's first tens of ms and ends before the keyboard's resize" {
    // Measured on the Velvet (#117): the stuck swaps 12 ms after the thread started, the window's
    // size changes and the transition's sync within 52 ms; the keyboard's resize at 193 ms at the
    // earliest. A buffer that is scaled when the window changes size shows scaled until the next.
    try std.testing.expect(scale_hold_ns > 52 * ms);
    try std.testing.expect(scale_hold_ns < 193 * ms);
    // The retries end within about half a second of the first swap.
    try std.testing.expect(scale_hold_ns + scale_retries * scale_retry_ns <= 600 * ms);
}

test "swapScaling: after the hold a buffer of the window's size goes back to FREEZE" {
    try std.testing.expectEqual(Scaling.freeze, swapScaling(.scale_to_window, scale_hold_ns, 863, 1026, 863, 1026));
    try std.testing.expectEqual(Scaling.freeze, swapScaling(.scale_to_window, 2000 * ms, 863, 610, 863, 610));
}

test "swapScaling: after the hold a buffer of another size still goes out scaled" {
    // The buffer was taken before the window's last size change: as FREEZE it would be rejected.
    try std.testing.expectEqual(Scaling.scale_to_window, swapScaling(.scale_to_window, 600 * ms, 863, 610, 863, 1026));
    try std.testing.expectEqual(Scaling.scale_to_window, swapScaling(.scale_to_window, 600 * ms, 1080, 1026, 863, 1026));
}

test "swapScaling: once FREEZE, a thread stays FREEZE whatever the sizes" {
    // An old-size buffer is then the size of the last one the window took, and is not rejected.
    try std.testing.expectEqual(Scaling.freeze, swapScaling(.freeze, 0, 863, 610, 863, 1026));
    try std.testing.expectEqual(Scaling.freeze, swapScaling(.freeze, 600 * ms, 863, 1026, 863, 1026));
}

test "scalingFrameDue: a first swap that went out scaled asks for a frame when the hold is over" {
    // A pane that prints nothing draws no frame of its own: without one it would stay scaled.
    try std.testing.expectEqual(FrameDue.after_hold, scalingFrameDue(.scale_to_window, true, false, 0));
}

test "scalingFrameDue: a frame asked for already is not asked for again" {
    // The frames of a printing pane during the hold, or before the retry is due.
    try std.testing.expectEqual(FrameDue.none, scalingFrameDue(.scale_to_window, false, true, 0));
    try std.testing.expectEqual(FrameDue.none, scalingFrameDue(.scale_to_window, false, true, 3));
}

test "scalingFrameDue: a frame after the hold that still went out scaled asks again, a bounded number of times" {
    // The keyboard was still moving: the buffer did not have the window's size.
    var retries: u8 = 0;
    while (retries < scale_retries) : (retries += 1) {
        try std.testing.expectEqual(FrameDue.retry, scalingFrameDue(.scale_to_window, false, false, retries));
    }
    try std.testing.expectEqual(FrameDue.give_up, scalingFrameDue(.scale_to_window, false, false, scale_retries));
    // Given up: the buffers stay scaled and nothing more is asked or logged.
    try std.testing.expectEqual(FrameDue.none, scalingFrameDue(.scale_to_window, false, false, scale_retries + 1));
}

test "scalingFrameDue: a thread that went back to FREEZE asks for nothing" {
    try std.testing.expectEqual(FrameDue.none, scalingFrameDue(.freeze, false, false, 0));
    try std.testing.expectEqual(FrameDue.none, scalingFrameDue(.freeze, false, true, 2));
    // The window could not be asked to scale the first buffer: it went out as FREEZE.
    try std.testing.expectEqual(FrameDue.none, scalingFrameDue(.freeze, true, false, 0));
}

test "swapScaling: a size that could not be read keeps the buffers scaled" {
    // ANativeWindow_getWidth/Height answer a negative error code; eglQuerySurface leaves 0.
    try std.testing.expectEqual(Scaling.scale_to_window, swapScaling(.scale_to_window, 600 * ms, 863, 1026, -19, -19));
    try std.testing.expectEqual(Scaling.scale_to_window, swapScaling(.scale_to_window, 600 * ms, 0, 0, 863, 1026));
}

test "renderer: a size is taken once, and a pending attach-settle takes it once when it expires" {
    var source: CountingSource = .{};
    const p = try paneWithHistory(&source);
    defer p.destroy();
    p.surface_w = 640;
    p.surface_h = 400;
    _ = p.pan(0, 4, 0, 0);
    var t = try TestRenderer.init(p);
    defer t.deinit();

    // A changed size is taken, and the offset rests with it, once.
    takeSurfaceSize(&t.r, 800, 500);
    try std.testing.expectEqual(@as(u32, 800), p.surface_w);
    try std.testing.expectEqual(@as(u32, 800), t.r.view_w);
    try std.testing.expectEqual(@as(f32, 0), p.scrollOffsetPx());

    // The same answer again: kept, not taken again.
    _ = p.pan(0, 4, 0, 0); // an offset again
    takeSurfaceSize(&t.r, 800, 500);
    try std.testing.expectEqual(@as(f32, 4), p.scrollOffsetPx());

    // A pending attach-settle keeps the size it was given until it expires: the frame that
    // spends the last of them takes and rests once, and the counter standing at 0 never does.
    p.settle_frames = PaneMod.frames_after_resize;
    var spent: u8 = PaneMod.frames_after_resize;
    while (spent > 0) : (spent -= 1) {
        p.settle_frames = spent;
        takeSurfaceSize(&t.r, 800, 500);
        if (spent > 1) try std.testing.expectEqual(@as(f32, 4), p.scrollOffsetPx()); // pending keeps
    }
    try std.testing.expectEqual(@as(f32, 0), p.scrollOffsetPx()); // expired: taken and rested once
    p.settle_frames = 0;
    _ = p.pan(0, 4, 0, 0);
    takeSurfaceSize(&t.r, 800, 500);
    try std.testing.expectEqual(@as(f32, 4), p.scrollOffsetPx()); // 0 across frames never rests it
}

test "renderer: a failed frame that took the settle's last size does not take again" {
    var source: CountingSource = .{};
    const p = try paneWithHistory(&source);
    defer p.destroy();
    p.surface_w = 640;
    p.surface_h = 400;
    var t = try TestRenderer.init(p);
    defer t.deinit();
    t.r.view_w = 640; // the buffer has answered this size before
    t.r.view_h = 400;
    p.settle_frames = 1; // the attach-settle's last frame is due

    // What `buildFrame` does first, then the frame's own outcome.
    const Draw = struct {
        r: *R,
        pane: *Pane,
        builds: []const bool,
        built: usize = 0,
        failed: std.atomic.Value(bool) = .init(false),

        fn build(d: *@This()) bool {
            takeSurfaceSize(d.r, 640, 400);
            const ok = d.builds[d.built];
            d.built += 1;
            if (!ok) d.failed.store(true, .release);
            if (d.built == d.builds.len) d.pane.quit = true;
            return ok;
        }

        fn present(_: *@This()) bool {
            return true;
        }
    };
    // The finger, once the take's frame has failed: its pan comes before the next frame.
    const Finger = struct {
        fn run(pane: *Pane, failed: *const std.atomic.Value(bool)) void {
            while (!failed.load(.acquire)) {
                var ts: std.c.timespec = .{ .sec = 0, .nsec = std.time.ns_per_ms };
                _ = std.c.nanosleep(&ts, null);
            }
            _ = pane.pan(0, 4, 0, 0);
        }
    };
    var draw: Draw = .{ .r = &t.r, .pane = p, .builds = &.{ false, true } };
    const finger = try std.Thread.spawn(.{}, Finger.run, .{ p, &draw.failed });
    run(p, &draw);
    finger.join();

    try std.testing.expectEqual(@as(usize, 2), draw.built);
    try std.testing.expectEqual(@as(u8, 0), p.settle_frames); // the settle was spent, once
    try std.testing.expectEqual(@as(f32, 4), p.scrollOffsetPx()); // and the pan's offset survived the next frame
}

test "renderer: a frame the thread asked for itself wakes it when it is due, with nothing else to draw" {
    // #117: a pane that prints nothing still draws the frame that ends its scaled buffers.
    var source: CountingSource = .{};
    const p = try paneWithHistory(&source);
    defer p.destroy();
    p.wake = false;
    const asked = PaneMod.monoNow() + 30 * ms;
    p.frame_due_ns = asked;

    const Draw = struct {
        pane: *Pane,
        at: i128 = 0,

        fn build(d: *@This()) bool {
            d.at = PaneMod.monoNow();
            d.pane.frame_due_ns = 0;
            d.pane.quit = true;
            return false;
        }

        fn present(_: *@This()) bool {
            return true;
        }
    };
    // Ends the loop if the frame never comes, so that the test fails instead of hanging.
    const Watchdog = struct {
        fn run(pane: *Pane, done: *const std.atomic.Value(bool)) void {
            var waited: usize = 0;
            while (!done.load(.acquire) and waited < 200) : (waited += 1) {
                var ts: std.c.timespec = .{ .sec = 0, .nsec = 10 * std.time.ns_per_ms };
                _ = std.c.nanosleep(&ts, null);
            }
            pane.lock.lock();
            defer pane.lock.unlock();
            pane.quit = true;
            pane.lock.signal();
        }
    };
    var done: std.atomic.Value(bool) = .init(false);
    var draw: Draw = .{ .pane = p };
    const watchdog = try std.Thread.spawn(.{}, Watchdog.run, .{ p, &done });
    run(p, &draw);
    done.store(true, .release);
    watchdog.join();

    try std.testing.expect(draw.at != 0); // the loop woke for the frame
    try std.testing.expect(draw.at >= asked); // and not before it was due
}

test "renderer: a frame the thread asked for itself goes to the window when due, also when it draws the same" {
    var source: CountingSource = .{};
    const p = try paneWithHistory(&source);
    defer p.destroy();
    var t = try TestRenderer.init(p);
    defer t.deinit();

    // Scaled buffers: the frame is due at 1000 ns.
    t.r.scaling = .scale_to_window;
    t.r.due_ns = 1000;
    p.frame_due_ns = 1000;
    p.present_due = false;
    takeFrameDue(&t.r, 999); // not yet
    try std.testing.expect(!p.present_due);
    try std.testing.expectEqual(@as(i128, 1000), p.frame_due_ns);
    takeFrameDue(&t.r, 1000);
    try std.testing.expect(p.present_due); // drawn even when it draws what the window shows
    try std.testing.expectEqual(@as(i128, 0), p.frame_due_ns);
    try std.testing.expectEqual(@as(i128, 0), t.r.due_ns);

    // Back to FREEZE before it was due: nothing is drawn for it.
    t.r.scaling = .freeze;
    t.r.due_ns = 2000;
    p.frame_due_ns = 2000;
    p.present_due = false;
    takeFrameDue(&t.r, 2500);
    try std.testing.expect(!p.present_due);
    try std.testing.expectEqual(@as(i128, 0), p.frame_due_ns);
    try std.testing.expectEqual(@as(i128, 0), t.r.due_ns);
}

test "gl: the window's perform hook is where the platform's struct ANativeWindow has it" {
    // system/window.h (android16-release) on a 64-bit ABI: common is 56 bytes, perform at 152,
    // the struct 192 bytes (common.version). `setWindowScaling` calls through this offset.
    if (@sizeOf(usize) != 8) return error.SkipZigTest;
    try std.testing.expectEqual(@as(usize, 56), @sizeOf(gl.NativeBase));
    try std.testing.expectEqual(@as(usize, 152), @offsetOf(gl.NativeWindow, "perform"));
}
