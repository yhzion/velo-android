//! One terminal pane: a libghostty-vt terminal fed by Core's network thread, a
//! render thread with its own EGL context on Android, selection, copy and key
//! encoding. See docs/native-internal.md for the contract.
const std = @import("std");
const builtin = @import("builtin");
const c = @import("c");
const Keys = @import("Keys.zig");

pub const is_android = builtin.abi.isAndroid();
const Renderer = if (is_android) @import("Renderer.zig") else void;

pub const BoxDraw = @import("BoxDraw.zig");
pub const Links = @import("Links.zig");
pub const LinkKind = Links.Kind;
pub const Atlas = @import("Atlas.zig");
pub const GlyphCache = @import("GlyphCache.zig");
/// Trace sections and thread names, for the core outside this module too.
pub const Trace = @import("Trace.zig");

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
    rasterize: *const fn (ctx: *anyopaque, utf8: []const u8, bold: bool, italic: bool, cell_w: u32, cell_h: u32, columns: u32) ?GlyphBitmap,
    /// What this source drew, for every pane that has the source. Null: each render thread
    /// asks for what its own atlas lacks.
    cache: ?*GlyphCache = null,
};

pub const Theme = struct {
    foreground: ?[3]u8 = null,
    background: ?[3]u8 = null,
    cursor: ?[3]u8 = null,
    /// (index, rgb) overrides of the 256-color palette.
    palette: []const struct { u8, [3]u8 } = &.{},
};

/// pthread mutex/cond (std.Thread.Mutex is gone in Zig 0.17; libc is always linked).
pub const Lock = struct {
    m: std.c.pthread_mutex_t = .{},
    cv: std.c.pthread_cond_t = .{},

    pub fn lock(l: *Lock) void {
        _ = std.c.pthread_mutex_lock(&l.m);
    }
    pub fn unlock(l: *Lock) void {
        _ = std.c.pthread_mutex_unlock(&l.m);
    }
    pub fn wait(l: *Lock) void {
        _ = std.c.pthread_cond_wait(&l.cv, &l.m);
    }
    pub fn signal(l: *Lock) void {
        _ = std.c.pthread_cond_broadcast(&l.cv);
    }
    /// Waits as `wait`, at most `ns`. True when the time passed without a signal. The
    /// condition keeps the wall clock, so a step of it lengthens one wait by the step.
    pub fn timedWait(l: *Lock, ns: u64) bool {
        var ts: std.c.timespec = undefined;
        if (std.c.clock_gettime(.REALTIME, &ts) != 0) return true;
        const total = @as(u128, @intCast(ts.nsec)) + ns;
        ts.sec += @intCast(total / std.time.ns_per_s);
        ts.nsec = @intCast(total % std.time.ns_per_s);
        return std.c.pthread_cond_timedwait(&l.cv, &l.m, &ts) == .TIMEDOUT;
    }
    /// Waits as `wait`, until `deadline_ns` on the monotonic clock (`monoNow`), which a step of
    /// the wall clock does not move. True when the deadline passed without a signal.
    pub fn waitUntil(l: *Lock, deadline_ns: i128) bool {
        const at = @max(deadline_ns, 0);
        const ts: std.c.timespec = .{ .sec = @intCast(@divFloor(at, std.time.ns_per_s)), .nsec = @intCast(@mod(at, std.time.ns_per_s)) };
        return pthread_cond_clockwait(&l.cv, &l.m, .MONOTONIC, &ts) == .TIMEDOUT;
    }
};

/// Bionic from API 30 (Velo's minSdk), glibc from 2.30. Zig's `std.c` does not declare it.
extern "c" fn pthread_cond_clockwait(noalias cond: *std.c.pthread_cond_t, noalias mutex: *std.c.pthread_mutex_t, clock: std.c.clockid_t, noalias abstime: *const std.c.timespec) std.c.E;

fn rgb(v: [3]u8) c.GhosttyColorRgb {
    return .{ .r = v[0], .g = v[1], .b = v[2] };
}

/// What an attach does with the renderer a pane has.
pub const AttachAction = enum {
    /// The window the pane draws to already: the render thread and its EGL surface stay.
    keep,
    /// Another window, a first one, or one whose renderer failed: end it, start one.
    replace,
    /// No window: end it.
    detach,
};

/// Decide an attach. `current` is the window the pane draws to (null without a surface)
/// and `alive` whether its renderer runs; `wanted` is the window asked for (null to
/// detach). A window is the one the pane has when the addresses are equal: the pane
/// holds a reference to its window, so that address cannot belong to another window
/// meanwhile.
pub fn attachStep(current: ?*anyopaque, alive: bool, wanted: ?*anyopaque) AttachAction {
    const window = wanted orelse return .detach;
    return if (current == window and alive) .keep else .replace;
}

/// What becomes of a window reference in an attach.
pub const Ref = enum {
    /// Given up.
    released,
    /// The pane's: the reference to the window it draws to.
    held,
    /// Left to the render thread the pane gave up (#159, `RenderLink`): that thread's EGL
    /// surface is still on the window, and the thread gives the reference up if it ever ends.
    thread,
};

/// The two references an attach meets: the pane's own to the window it draws to
/// (`current`, when it has one) and the one the caller passed in with the window asked
/// for (`incoming`, when it asked for one).
pub const AttachRefs = struct { current: Ref, incoming: Ref };

/// Decide the references of an attach. `started` is whether the renderer a `.replace`
/// starts runs, and `given_up` whether the pane gave up the renderer it ended (it did not end
/// in time, `stop_wait_ns`). Whatever the step, the pane ends with one reference to the
/// window it draws to and no other.
pub fn attachRefs(action: AttachAction, started: bool, given_up: bool) AttachRefs {
    // The thread the pane gave up still has its EGL surface on that window.
    const ended: Ref = if (given_up) .thread else .released;
    return switch (action) {
        // The pane has its own reference to this window: the caller's is one too many.
        .keep => .{ .current = .held, .incoming = .released },
        // The new renderer draws to the window asked for; without one the pane draws to none.
        .replace => .{ .current = ended, .incoming = if (started) .held else .released },
        .detach => .{ .current = ended, .incoming = .released },
    };
}

/// Give a window reference up. The host has no windows: its tests count references
/// through `host_release` instead.
fn releaseWindow(window: *anyopaque) void {
    if (is_android) Renderer.releaseWindow(window) else if (host_release) |release| release(window);
}

/// Host tests only: called for every window reference a pane gives up.
pub var host_release: ?*const fn (window: *anyopaque) void = null;

/// One line when a pane gives up its render thread (`stopRenderer`).
fn logGivenUp(pane: *Pane, window: *anyopaque, waited_ns: i128) void {
    if (is_android) Renderer.logGivenUp(pane, window, waited_ns);
}

/// How long a pane waits for its render thread to end before it gives the thread up (#159).
/// Main waits here with `Core.lock` held (`surfaceDestroyed` → `paneSuspend`/`paneSurface`),
/// and `velo-control` does when the server closes a pane, while main waits for `Core.lock`.
/// Android reports an ANR when main has not answered an input event for 5 s: 2 s leaves the
/// rest of that to what main does besides. A thread that is not stuck ends within a frame and
/// its EGL teardown, tens of milliseconds.
pub const stop_wait_ns: u64 = 2 * std.time.ns_per_s;

/// How long a stop waits instead, when a pane gave its thread up less than `stop_wait_ns` ago
/// (`give_up_until_ns`). The stops come one after another: `HostConnection.destroy` detaches
/// every pane, a `surfaceDestroyed` suspends every pane of the view, and Core's sweep removes
/// each closed pane, while main waits for `Core.lock`. #117's trigger is a sync of the whole
/// window, so several threads can be stuck at once, and a bound for each would add up to an ANR.
/// With one shared bound, k stuck threads cost 2 s + k × 0.2 s. A thread that is not stuck ends
/// within tens of milliseconds, so it is still joined. One that is only slow and is given up
/// frees itself when it ends, as a stuck one would.
pub const burst_wait_ns: u64 = 200 * std.time.ns_per_ms;

/// Until when (`monoNow`) a stop waits `burst_wait_ns` and not `stop_wait_ns`: `stop_wait_ns`
/// past the last give-up, in any pane of any core. Main and `velo-control` both stop panes.
pub var give_up_until_ns: std.atomic.Value(i64) = .init(0);

/// How long past its bound a pane waits for a thread inside it to leave before it says so in
/// the log, once (`RenderLink.waitEnd`). That wait has no end of its own: a call inside the pane
/// that does not return keeps main waiting there, and the line shows it.
pub const leave_late_ns: u64 = 500 * std.time.ns_per_ms;

/// One line when a pane's thread is `leave_late_ns` past the bound and still inside the pane.
fn logLateLeave(window: *anyopaque, late_ns: i128) void {
    if (is_android) Renderer.logLateLeave(window, late_ns) else if (host_late_leave) |f| f(window);
}

/// Host tests only: called for every line `logLateLeave` writes.
pub var host_late_leave: ?*const fn (window: *anyopaque) void = null;

/// How long a stop that starts at `now` waits for its thread.
fn stopBound(now: i128) u64 {
    return if (now < give_up_until_ns.load(.monotonic)) burst_wait_ns else stop_wait_ns;
}

/// What a render thread shares with its pane, kept apart from the pane (#159). A pane waits for
/// its thread to end at most `stop_wait_ns` and then gives it up: a thread stuck in the platform
/// (#117) would otherwise stop main or `velo-control` for good. The pane may then be freed, or
/// draw with a new thread, while the one it gave up is still stuck. So the thread touches the
/// pane only between `enter` and `leave`, and once given up never again; the reference to the
/// window it draws to is then its own. The pane frees the link after joining its thread; a
/// thread given up frees it itself when it ends (`end`). One that never ends leaks the link,
/// its EGL objects and the window's reference.
pub const RenderLink = struct {
    gpa: std.mem.Allocator,
    /// The window the thread draws to. Its reference is the pane's until the pane gives the
    /// thread up.
    window: *anyopaque,
    lock: Lock = .{},
    /// The thread is between `enter` and `leave`.
    in_pane: bool = false,
    /// The thread is done with the pane and the link (`end`).
    done: bool = false,
    /// The pane gave the thread up (`waitEnd`): the thread enters no more.
    given_up: bool = false,

    pub fn create(gpa: std.mem.Allocator, window: *anyopaque) !*RenderLink {
        const l = try gpa.create(RenderLink);
        l.* = .{ .gpa = gpa, .window = window };
        return l;
    }

    pub fn destroy(l: *RenderLink) void {
        l.gpa.destroy(l);
    }

    /// The thread's side: whether it may touch the pane, until `leave`. False once the pane
    /// gave it up: the pane may be gone, or draw with another thread. Between the two the
    /// thread waits on nothing but the pane's lock: what waits on the platform (`present`,
    /// the EGL setup and teardown) happens outside.
    pub fn enter(l: *RenderLink) bool {
        l.lock.lock();
        defer l.lock.unlock();
        std.debug.assert(!l.in_pane);
        if (l.given_up) return false;
        l.in_pane = true;
        return true;
    }

    /// Whether the pane gave the thread up. Outside the pane the thread asks before it calls
    /// what the pane's owner may free once the thread is given up: the glyph source.
    pub fn givenUp(l: *RenderLink) bool {
        l.lock.lock();
        defer l.lock.unlock();
        return l.given_up;
    }

    pub fn leave(l: *RenderLink) void {
        l.lock.lock();
        defer l.lock.unlock();
        l.in_pane = false;
        // A pane past its wait gives the thread up only once it is out.
        l.lock.signal();
    }

    /// The thread's last act, after it gave up its EGL surface. A thread the pane waits for says
    /// it is done, and the pane joins it and frees the link. One the pane gave up gives up the
    /// window's reference and frees the link itself.
    pub fn end(l: *RenderLink) void {
        l.lock.lock();
        const given_up = l.given_up;
        l.done = true;
        l.lock.signal();
        l.lock.unlock();
        if (!given_up) return;
        releaseWindow(l.window);
        l.gpa.destroy(l);
    }

    /// The pane's side, once it asked the thread to quit: wait for the thread to end, at most
    /// `bound_ns`, then give it up. A thread inside the pane then is waited for until it
    /// leaves, which it does soon (`enter`); `leave_late_ns` past the bound, one line says it
    /// has not. True: the thread has ended; join it, then `destroy` the link. False: it is
    /// given up; detach it, and touch the link no more. The times are on the monotonic clock.
    pub fn waitEnd(l: *RenderLink, bound_ns: u64) bool {
        const deadline = monoNow() + bound_ns;
        const late = deadline + leave_late_ns;
        var said = false;
        l.lock.lock();
        defer l.lock.unlock();
        while (!l.done) {
            const now = monoNow();
            if (now < deadline) {
                _ = l.lock.waitUntil(deadline);
            } else if (!l.in_pane) {
                l.given_up = true;
                return false;
            } else if (said) {
                l.lock.wait();
            } else if (now < late) {
                _ = l.lock.waitUntil(late);
            } else {
                said = true;
                // Not under the link's lock, which the thread takes to leave.
                l.lock.unlock();
                logLateLeave(l.window, now - deadline);
                l.lock.lock();
            }
        }
        return true;
    }
};

/// Frames the render thread draws on its own after an attach in place gave another
/// size: the window hands out buffers of the new size only from its next dequeue on.
pub const frames_after_resize: u8 = 3;

/// The offset rests this long after the last pan, then eases to 0 over the settle.
const scroll_wait_ns: i128 = 150 * std.time.ns_per_ms;
const scroll_settle_ns: i128 = 100 * std.time.ns_per_ms;
/// Frames asked for the offset's sake at most, in case time stands still.
const scroll_frames_max: u16 = 60;

/// The monotonic clock in ns (`libc` is linked; `std`'s needs an `Io` this pane has none of):
/// the settle measures spans of time, so it needs one that never steps.
pub fn monoNow() i128 {
    var ts: std.c.timespec = undefined;
    if (std.c.clock_gettime(.MONOTONIC, &ts) != 0) return 0;
    return @as(i128, ts.sec) * std.time.ns_per_s + ts.nsec;
}

pub const SearchHit = struct {
    start: c.GhosttyPointCoordinate,
    end: c.GhosttyPointCoordinate,

    pub fn contains(hit: SearchHit, x: u16, y: u64) bool {
        return y >= hit.start.y and y <= hit.end.y and
            (y != hit.start.y or x >= hit.start.x) and (y != hit.end.y or x <= hit.end.x);
    }
};

/// The link under a tap: what to open and the cells it covers (screen coordinates, inclusive).
pub const Link = struct {
    kind: LinkKind,
    /// Owned by the caller's allocator.
    target: []u8,
    /// A path's line and column after it; 0 when it has none.
    line: u32 = 0,
    col: u32 = 0,
    /// A program set it on the cells (OSC 8); false for a link read from the text there.
    set: bool = false,
    /// The text on its cells is its target. A link read from the text always shows it; one a
    /// program set can open something the person never saw, also from blank cells.
    shown: bool = true,
    start: c.GhosttyPointCoordinate,
    end: c.GhosttyPointCoordinate,

    pub fn deinit(link: Link, gpa: std.mem.Allocator) void {
        gpa.free(link.target);
    }
};

pub const SearchResult = struct {
    count: u32 = 0,
    position: u32 = 0, // one-based, newest first
    pending: bool = false,
};

/// The most UTF-8 bytes of a composition the pane draws; a longer one is cut at a code point.
pub const preedit_cap: usize = 256;

/// One base code point plus at most page.grapheme_max_len suffixes in libghostty-vt.
const grid_cell_points: usize = 1 + 64;
pub const grid_cell_utf8_cap: usize = 4 * grid_cell_points;
pub const preedit_glyph_cap: usize = grid_cell_utf8_cap + preedit_cap;

/// Column is absolute in the viewport, including a base to the left of the cursor.
pub const PreeditCell = struct {
    utf8: []const u8,
    col: u16,
    span: u8,
    /// Nonzero only when the glyph includes text already on the grid.
    base_span: u8 = 0,
};

/// Grid context and caller-owned storage for the one glyph that can straddle
/// the cursor. Returned glyph slices borrow either this buffer or the input.
pub const PreeditGrid = struct {
    term: c.GhosttyTerminal,
    row: u16,
    cursor_x: u16,
    joined: [preedit_glyph_cap]u8 = undefined,

    const Previous = struct { col: u16, span: u8, count: usize, visible: bool };

    fn previous(g: *const PreeditGrid, cols: u16, points: *[grid_cell_points]u32) ?Previous {
        if (g.cursor_x == 0 or g.cursor_x >= cols) return null;
        var col = g.cursor_x - 1;
        if (g.cursor_x == cols - 1) {
            var pending: bool = false;
            if (c.ghostty_terminal_get(g.term, c.GHOSTTY_TERMINAL_DATA_CURSOR_PENDING_WRAP, &pending) != c.GHOSTTY_SUCCESS) return null;
            if (pending) col = g.cursor_x;
        }
        var ref: c.GhosttyGridRef = .{};
        var wide: c.GhosttyCellWide = undefined;
        while (true) {
            const point: c.GhosttyPoint = .{ .tag = c.GHOSTTY_POINT_TAG_VIEWPORT, .value = .{ .coordinate = .{ .x = col, .y = g.row } } };
            if (c.ghostty_terminal_grid_ref(g.term, point, &ref) != c.GHOSTTY_SUCCESS) return null;
            var cell: c.GhosttyCell = undefined;
            // A wide glyph owns the cell immediately before its spacer tail.
            if (c.ghostty_grid_ref_cell(&ref, &cell) != c.GHOSTTY_SUCCESS or
                c.ghostty_cell_get(cell, c.GHOSTTY_CELL_DATA_WIDE, &wide) != c.GHOSTTY_SUCCESS) return null;
            if (wide == c.GHOSTTY_CELL_WIDE_SPACER_TAIL and col > 0) {
                col -= 1;
                continue;
            }
            if (wide != c.GHOSTTY_CELL_WIDE_NARROW and wide != c.GHOSTTY_CELL_WIDE_WIDE) return null;
            break;
        }
        var count: usize = 0;
        if (c.ghostty_grid_ref_graphemes(&ref, points, points.len, &count) != c.GHOSTTY_SUCCESS or count == 0) return null;
        var style: c.GhosttyStyle = undefined;
        const visible = c.ghostty_grid_ref_style(&ref, &style) == c.GHOSTTY_SUCCESS and style.flags & c.GHOSTTY_STYLE_FLAG_INVISIBLE == 0;
        return .{ .col = col, .span = if (wide == c.GHOSTTY_CELL_WIDE_WIDE) 2 else 1, .count = count, .visible = visible };
    }
};

/// ghostty_unicode_grapheme_width measures a cluster from scratch. After a mode
/// switch the *stored* span need not agree with that measurement. Apply only
/// the new, already-segmented continuation to the stored span.
fn continuedSpan(stored: u8, last_stored: u32, continuation: []const u32) u8 {
    var span = stored;
    var prev = last_stored;
    for (continuation) |cp| {
        if (cp == 0xfe0e or cp == 0xfe0f) {
            // A valid VS base responds to the selector opposite its natural
            // width. An invalid selector is ignored and does not replace prev.
            const natural = c.ghostty_unicode_codepoint_width(prev);
            const probe = [2]u32{ prev, if (natural == 2) 0xfe0e else 0xfe0f };
            var selected: u8 = 0;
            _ = c.ghostty_unicode_grapheme_width(&probe, probe.len, &selected);
            if (selected == natural) continue;
            span = if (cp == 0xfe0e) 1 else 2;
        } else if (span == 1) {
            // U+0600 is a one-cell Prepend: GB9b joins it to cp. The
            // resulting width distinguishes a widening continuation from a
            // mark that preserves width, without a second Unicode table.
            const probe = [2]u32{ 0x0600, cp };
            _ = c.ghostty_unicode_grapheme_width(&probe, probe.len, &span);
        }
        prev = cp;
    }
    return span;
}

/// The cells of `text` from the cursor at column `cursor_x` on a grid `cols` wide, measured
/// with the terminal's own width table. With `graphemes` (mode 2027) a cluster such as an
/// emoji with a skin tone takes one cell run; without it every code point does, as the
/// terminal prints it then. A zero-width code point joins the cell before it. A character
/// that would cross the right edge is not placed, nor is anything after it: the composition
/// shows where it starts, and the remote wraps the text once it is committed.
pub fn layoutPreedit(text: []const u8, cursor_x: u16, cols: u16, graphemes: bool, grid: ?*PreeditGrid, out: []PreeditCell) []PreeditCell {
    if (text.len == 0 or text.len > preedit_cap) return out[0..0];
    var storage: [grid_cell_points + preedit_cap]u32 = undefined;
    // Ghostty checks the previous grid cell only in mode 2027 and only for
    // non-Latin-1 input. The complete prefix matters (e.g. RI parity).
    var it = (std.unicode.Utf8View.init(text) catch return out[0..0]).iterator();
    const prev = if (grid != null and graphemes and it.peekCodepoint().? > 255)
        grid.?.previous(cols, storage[0..grid_cell_points])
    else
        null;
    const prefix_len = if (prev) |p| p.count else 0;
    const cps = storage[prefix_len..];
    var ends: [preedit_cap]u16 = undefined;
    var count: usize = 0;
    // All byte offsets below are relative to the composition, not the prefix.
    while (it.nextCodepoint()) |cp| {
        cps[count] = cp;
        ends[count] = @intCast(it.i);
        count += 1;
    }
    var n: usize = 0;
    var col: u32 = cursor_x;
    var i: usize = 0;
    var start_byte: usize = 0;
    if (prev) |p| {
        // A legacy cell can even contain a break under the current rules.
        // Only its final cluster determines whether the new text joins it.
        var take: usize = 0;
        while (take < prefix_len) {
            take += c.ghostty_unicode_grapheme_width(storage[take..].ptr, prefix_len + count - take, null);
        }
        if (take > prefix_len) {
            i = take - prefix_len;
            const width = continuedSpan(p.span, storage[prefix_len - 1], cps[0..i]);
            start_byte = ends[i - 1];
            col = @as(u32, p.col) + width;
            if (col > cols) return out[0..0];
            // Concealed code points affect placement but never reach the atlas.
            if (p.visible) {
                if (out.len == 0) return out[0..0];
                const joined = &grid.?.joined;
                var len: usize = 0;
                for (storage[0..prefix_len]) |cp| {
                    len += std.unicode.utf8Encode(@intCast(cp), joined[len..]) catch return out[0..0];
                }
                @memcpy(joined[len..][0..start_byte], text[0..start_byte]);
                out[0] = .{ .utf8 = joined[0 .. len + start_byte], .col = p.col, .span = width, .base_span = p.span };
                n = 1;
            }
        }
    }
    while (i < count) {
        var width: u8 = 0;
        const take: usize = if (graphemes)
            c.ghostty_unicode_grapheme_width(cps[i..count].ptr, count - i, &width)
        else blk: {
            width = c.ghostty_unicode_codepoint_width(cps[i]);
            break :blk 1;
        };
        const end_byte = ends[i + take - 1];
        i += take;
        if (width == 0) {
            // In mode 2027 an unattached zero-width cluster is ignored. In
            // legacy mode zero-width code points append to the preceding run.
            if (!graphemes and n > 0) out[n - 1].utf8 = text[out[n - 1].utf8.ptr - text.ptr .. end_byte];
            start_byte = end_byte;
            continue;
        }
        if (n == out.len or col + width > cols) break;
        out[n] = .{ .utf8 = text[start_byte..end_byte], .col = @intCast(col), .span = width };
        n += 1;
        col += width;
        start_byte = end_byte;
    }
    return out[0..n];
}

pub const Pane = struct {
    gpa: std.mem.Allocator,
    glyphs: GlyphSource,
    lock: Lock = .{},
    term: c.GhosttyTerminal,
    search: c.GhosttySearch = null,
    search_hit: ?SearchHit = null,
    /// The link a finger is on, underlined while it is down (screen coordinates).
    link_mark: ?SearchHit = null,
    search_gen: u64 = 0,
    encoder: c.GhosttyKeyEncoder = null,
    key_event: c.GhosttyKeyEvent = null,
    gesture: c.GhosttySelectionGesture = null,
    gesture_event: [3]c.GhosttySelectionGestureEvent = .{ null, null, null },
    gen: std.atomic.Value(u64) = .init(1),
    /// The frames the pane's surfaces have taken, each a swap of a frame that drew something
    /// new (`Renderer.present`). The same number twice is the same picture on the surface: the
    /// app keeps a still of a preview until it changes (`ui/PaneStill.kt`).
    swaps: std.atomic.Value(u64) = .init(0),

    /// Theme as last set by Core (re-applied after a snapshot restore).
    theme_fg: ?[3]u8 = null,
    theme_bg: ?[3]u8 = null,
    theme_cursor: ?[3]u8 = null,
    palette: [256]c.GhosttyColorRgb = undefined,
    palette_set: bool = false,

    /// Server cell size in px from Rex `resize` (0 when unknown).
    server_cell_w: u32 = 0,
    server_cell_h: u32 = 0,

    // View state (protected by `lock`).
    font_px: f32 = 28,
    /// Cell height in em of the font size: the terminal font's line at the size whose 'M'
    /// fills a cell 0.6 em wide (setting `fontLineHeight`).
    line_height: f32 = 1.2,
    scale: f32 = 1,
    /// Empty margin in px around the terminal grid at the scroll origin.
    pad: f32 = 0,
    /// Horizontal view offset in px into content wider than the surface.
    pan_x: f32 = 0,
    /// Vertical view offset in px into a grid taller than the surface.
    pan_y: f32 = 0,
    /// Px the viewport is drawn below its row (positive: the bottom of an older row shows
    /// above it). Half a row at most, either way.
    scroll_off: f32 = 0,
    /// Of a drag, the px that are not a wheel event or cursor keys yet. A program that takes
    /// the mouse, and the alternate screen, scroll in whole rows: nothing of it is shown.
    wheel_rem: f32 = 0,
    /// When the last pan was, so the offset knows when to rest (`scroll_off`).
    scroll_last_pan_ns: i128 = 0,
    /// The offset the settle began from, and when it began (0: it is not settling).
    scroll_settle_from: f32 = 0,
    scroll_settle_start_ns: i128 = 0,
    /// Frames drawn for the offset's sake since the last pan.
    scroll_wakes: u16 = 0,
    surface_w: u32 = 0,
    surface_h: u32 = 0,
    /// Set whenever the render thread should draw a new frame.
    wake: bool = true,
    /// The next frame goes to the window even when it draws what the window shows already:
    /// an attach in place may come with a window that waits for a buffer. Other frames that
    /// draw the same are not drawn again (`Renderer.drawsShown`).
    present_due: bool = true,
    /// Ends the names of this pane's threads (`Trace.blockTag` of its block); empty until set.
    thread_tag: [4]u8 = @splat(0),
    thread_tag_len: u8 = 0,
    /// Render thread must rebuild every row (terminal replaced by a snapshot).
    force_full: bool = true,
    /// Render thread must drop every cached glyph (the glyph source draws another font).
    glyphs_stale: bool = false,
    /// A pinch is still changing `scale`: the render thread keeps the glyphs it has and
    /// stretches them to the cells, and draws them again once the scale settles (#334).
    scale_live: bool = false,
    quit: bool = false,
    window: ?*anyopaque = null,
    /// Drawing to `window` does not work (EGL setup or a buffer swap failed) and the
    /// render thread has ended. The next attach, of this window too, starts a new one.
    render_failed: bool = false,
    /// Frames the render thread still draws on its own (see `frames_after_resize`).
    settle_frames: u8 = 0,
    /// When the render thread draws a frame it asked for itself (`monoNow`), with
    /// nothing else to draw; 0 for none. It asks while its buffers go out scaled (#117).
    frame_due_ns: i128 = 0,
    thread: ?std.Thread = null,
    /// What `thread` shares with the pane; set with it.
    render_link: ?*RenderLink = null,
    /// What the soft keyboard is still composing for this pane (UTF-8). It is drawn at the
    /// cursor and never sent: the keyboard commits it, and the remote echoes that.
    preedit_buf: [preedit_cap]u8 = undefined,
    preedit_len: usize = 0,
    preedit_clipped: bool = false,
    /// Permission applies only to the buffer revision that was checked by the core.
    preedit_visible: bool = false,

    pub fn create(gpa: std.mem.Allocator, glyphs: GlyphSource) !*Pane {
        const self = try gpa.create(Pane);
        errdefer gpa.destroy(self);
        var term: c.GhosttyTerminal = null;
        if (c.ghostty_terminal_new(null, &term, 80, 24) != c.GHOSTTY_SUCCESS) return error.OutOfMemory;
        self.* = .{ .gpa = gpa, .glyphs = glyphs, .term = term };
        errdefer self.freeHandles();
        if (c.ghostty_key_encoder_new(null, &self.encoder) != c.GHOSTTY_SUCCESS) return error.OutOfMemory;
        if (c.ghostty_key_event_new(null, &self.key_event) != c.GHOSTTY_SUCCESS) return error.OutOfMemory;
        if (c.ghostty_selection_gesture_new(null, &self.gesture) != c.GHOSTTY_SUCCESS) return error.OutOfMemory;
        const kinds = [3]c_int{
            c.GHOSTTY_SELECTION_GESTURE_EVENT_TYPE_PRESS,
            c.GHOSTTY_SELECTION_GESTURE_EVENT_TYPE_DRAG,
            c.GHOSTTY_SELECTION_GESTURE_EVENT_TYPE_RELEASE,
        };
        for (kinds, 0..) |k, i| {
            if (c.ghostty_selection_gesture_event_new(null, &self.gesture_event[i], @intCast(k)) != c.GHOSTTY_SUCCESS)
                return error.OutOfMemory;
        }
        return self;
    }

    fn freeHandles(self: *Pane) void {
        c.ghostty_search_free(self.search);
        if (self.gesture != null) c.ghostty_selection_gesture_free(self.gesture, self.term);
        for (self.gesture_event) |e| if (e != null) c.ghostty_selection_gesture_event_free(e);
        if (self.key_event != null) c.ghostty_key_event_free(self.key_event);
        if (self.encoder != null) c.ghostty_key_encoder_free(self.encoder);
        c.ghostty_terminal_free(self.term);
    }

    pub fn destroy(self: *Pane) void {
        self.detachSurface();
        self.freeHandles();
        self.gpa.destroy(self);
    }

    fn bump(self: *Pane) void {
        self.setSearchHitLocked(null);
        _ = self.gen.fetchAdd(1, .release);
        self.wake = true;
        self.lock.signal();
    }

    // ---------------------------------------------------------------- stream

    /// Feed bytes that start with a Ghostty snapshot. Returns how many bytes the
    /// snapshot used, or error.NeedMoreData until the whole snapshot has arrived.
    /// Replaces any existing terminal state (a restarted data stream sends a new snapshot).
    pub fn restoreSnapshot(self: *Pane, bytes: []const u8) !usize {
        Trace.begin("Pane.restoreSnapshot");
        defer Trace.end();
        var dec: c.GhosttySnapshotDecoder = null;
        if (c.ghostty_snapshot_decoder_new_buf(null, &dec, bytes.ptr, bytes.len) != c.GHOSTTY_SUCCESS)
            return error.OutOfMemory;
        defer c.ghostty_snapshot_decoder_free(dec);
        var term: c.GhosttyTerminal = null;
        const r = c.ghostty_snapshot_decoder_decode(dec, &term);
        switch (r) {
            c.GHOSTTY_SUCCESS => {},
            // Truncated input reports INVALID_VALUE (buffer ended before a marker).
            c.GHOSTTY_INVALID_VALUE => return error.NeedMoreData,
            c.GHOSTTY_OUT_OF_MEMORY => return error.OutOfMemory,
            else => return error.InvalidSnapshot,
        }
        var used: usize = 0;
        if (c.ghostty_snapshot_decoder_get(dec, c.GHOSTTY_SNAPSHOT_DECODER_DATA_SOURCE_OFFSET, &used) != c.GHOSTTY_SUCCESS) {
            c.ghostty_terminal_free(term);
            return error.InvalidSnapshot;
        }

        self.lock.lock();
        defer self.lock.unlock();
        self.closeSearchLocked();
        c.ghostty_selection_gesture_reset(self.gesture, self.term);
        self.force_full = true;
        self.scroll_off = 0;
        self.wheel_rem = 0;
        c.ghostty_terminal_free(self.term);
        self.term = term;
        self.applyThemeLocked();
        self.bump();
        return used;
    }

    pub fn write(self: *Pane, bytes: []const u8) void {
        if (bytes.len == 0) return;
        self.lock.lock();
        defer self.lock.unlock();
        c.ghostty_terminal_vt_write(self.term, bytes.ptr, bytes.len);
        self.bump();
    }

    /// Name this pane's threads (the render thread, the core's data stream) after `tag`.
    /// Set before they start: they read it once, as they name themselves.
    pub fn setThreadTag(self: *Pane, tag: []const u8) void {
        const n = @min(tag.len, self.thread_tag.len);
        @memcpy(self.thread_tag[0..n], tag[0..n]);
        self.thread_tag_len = @intCast(n);
    }

    pub fn threadTag(self: *const Pane) []const u8 {
        return self.thread_tag[0..self.thread_tag_len];
    }

    /// Server terminal size from Rex `resize`.
    pub fn resize(self: *Pane, cols: u16, rows: u16, cell_w_px: u32, cell_h_px: u32) void {
        if (cols == 0 or rows == 0) return;
        self.lock.lock();
        defer self.lock.unlock();
        self.server_cell_w = cell_w_px;
        self.server_cell_h = cell_h_px;
        _ = c.ghostty_terminal_resize(self.term, cols, rows, @max(cell_w_px, 1), @max(cell_h_px, 1));
        self.bump();
    }

    pub fn setTheme(self: *Pane, theme: Theme) void {
        self.lock.lock();
        defer self.lock.unlock();
        self.theme_fg = theme.foreground;
        self.theme_bg = theme.background;
        self.theme_cursor = theme.cursor;
        c.ghostty_color_palette_default(&self.palette);
        for (theme.palette) |e| self.palette[e[0]] = rgb(e[1]);
        self.palette_set = theme.palette.len > 0;
        self.applyThemeLocked();
        self.bump();
    }

    /// Replace the local preview and revoke its display permission under the same lock: no
    /// render frame may read a new syllable using the previous syllable's permission.
    /// Returns whether the input changed. The remote write never uses this bounded buffer.
    pub fn setPreedit(self: *Pane, text: []const u8) bool {
        var n = @min(text.len, preedit_cap);
        while (n > 0 and n < text.len and text[n] & 0xC0 == 0x80) n -= 1;
        self.lock.lock();
        defer self.lock.unlock();
        const same = std.mem.eql(u8, self.preedit_buf[0..self.preedit_len], text[0..n]);
        const clipped = text.len != n;
        if (same and !clipped and !self.preedit_clipped) return false;
        self.preedit_clipped = clipped;
        if (!same) @memcpy(self.preedit_buf[0..n], text[0..n]);
        self.preedit_len = n;
        self.preeditVisibilityLocked(false);
        return true;
    }

    /// Revoke a previous check on a link or process change. A nonempty buffer needs rechecking.
    pub fn hidePreedit(self: *Pane) bool {
        self.lock.lock();
        defer self.lock.unlock();
        self.preeditVisibilityLocked(false);
        return self.preedit_len != 0;
    }

    pub fn setPreeditVisible(self: *Pane, visible: bool) void {
        self.lock.lock();
        defer self.lock.unlock();
        self.preeditVisibilityLocked(visible and self.preedit_len != 0);
    }

    fn preeditVisibilityLocked(self: *Pane, visible: bool) void {
        if (self.preedit_visible == visible) return;
        self.preedit_visible = visible;
        self.wake = true;
        self.lock.signal();
    }

    /// Only the checked composition may be drawn. Borrowed while the pane lock is held.
    pub fn preeditLocked(self: *const Pane) []const u8 {
        return if (self.preedit_visible) self.preedit_buf[0..self.preedit_len] else "";
    }

    fn applyThemeLocked(self: *Pane) void {
        const opts = [3]c_int{ c.GHOSTTY_TERMINAL_OPT_COLOR_FOREGROUND, c.GHOSTTY_TERMINAL_OPT_COLOR_BACKGROUND, c.GHOSTTY_TERMINAL_OPT_COLOR_CURSOR };
        const vals = [3]?[3]u8{ self.theme_fg, self.theme_bg, self.theme_cursor };
        for (opts, vals) |o, v| {
            var col: c.GhosttyColorRgb = undefined;
            const p: ?*const anyopaque = if (v) |x| blk: {
                col = rgb(x);
                break :blk &col;
            } else null;
            _ = c.ghostty_terminal_set(self.term, @intCast(o), p);
        }
        _ = c.ghostty_terminal_set(self.term, c.GHOSTTY_TERMINAL_OPT_COLOR_PALETTE, if (self.palette_set) @ptrCast(&self.palette) else null);
    }

    /// OSC 0/2 title, or null. Copies into `buf`.
    pub fn title(self: *Pane, buf: []u8) ?[]const u8 {
        self.lock.lock();
        defer self.lock.unlock();
        var s: c.GhosttyString = .{};
        if (c.ghostty_terminal_get(self.term, c.GHOSTTY_TERMINAL_DATA_TITLE, &s) != c.GHOSTTY_SUCCESS) return null;
        if (s.len == 0 or s.ptr == null) return null;
        const n = @min(s.len, buf.len);
        @memcpy(buf[0..n], s.ptr[0..n]);
        return buf[0..n];
    }

    /// Increments whenever title or screen content changed.
    pub fn generation(self: *const Pane) u64 {
        return self.gen.load(.acquire);
    }

    // ------------------------------------------------------------- rendering

    /// Make `window` the pane's surface. The window reference is consumed (`attachRefs`
    /// says how). The window the pane draws to already is kept as it is, with its render
    /// thread and EGL surface: only the size changes, so the next frame differs from the
    /// last one only where the size makes it differ.
    pub fn attachSurface(self: *Pane, window: *anyopaque, width_px: u32, height_px: u32) !void {
        return self.setSurface(window, width_px, height_px);
    }

    pub fn detachSurface(self: *Pane) void {
        // A pinch that is still under way ends here: its settled scale may never come (the
        // app left the screen with the fingers down), and the glyphs must not stay stretched.
        self.lock.lock();
        self.scale_live = false;
        self.lock.unlock();
        self.setSurface(null, 0, 0) catch unreachable; // only starting a renderer fails
    }

    fn setSurface(self: *Pane, window: ?*anyopaque, width_px: u32, height_px: u32) !void {
        self.lock.lock();
        const current = self.window;
        const action = attachStep(current, !self.render_failed, window);
        if (action == .keep) {
            if (self.surface_w != width_px or self.surface_h != height_px) {
                self.surface_w = width_px;
                self.surface_h = height_px;
                self.settle_frames = frames_after_resize;
            }
            self.wake = true;
            self.present_due = true;
            self.lock.signal();
        }
        self.lock.unlock();
        var start: StartError!void = {};
        var given_up = false;
        switch (action) {
            .keep => {},
            .replace => {
                given_up = !self.stopRenderer();
                start = self.startRenderer(window.?, width_px, height_px);
            },
            .detach => given_up = !self.stopRenderer(),
        }
        // The only place the pane gives a reference up, and only after the old render thread
        // has ended: its EGL surface is on the window `current` refers to. A thread the pane
        // gave up keeps that reference (`Ref.thread`).
        const refs = attachRefs(action, if (start) |_| true else |_| false, given_up);
        if (refs.current == .released) if (current) |w| releaseWindow(w);
        if (refs.incoming == .released) if (window) |w| releaseWindow(w);
        return start;
    }

    const StartError = std.Thread.SpawnError || error{Unsupported};

    /// Start a render thread on `window`. The window's reference is not touched here.
    fn startRenderer(self: *Pane, window: *anyopaque, width_px: u32, height_px: u32) StartError!void {
        if (!is_android) return error.Unsupported;
        return self.startThread(window, width_px, height_px, Renderer.threadMain, .{});
    }

    /// Start `body` as the pane's render thread on `window`: it runs `body(pane, link, extra...)`,
    /// where `link` is the thread's `RenderLink`. `startRenderer` starts `Renderer.threadMain`; the
    /// host tests start a drawing of their own. The window's reference is not touched here.
    pub fn startThread(self: *Pane, window: *anyopaque, width_px: u32, height_px: u32, comptime body: anytype, extra: anytype) StartError!void {
        const link = try RenderLink.create(self.gpa, window);
        self.lock.lock();
        self.window = window;
        self.surface_w = width_px;
        self.surface_h = height_px;
        self.quit = false;
        self.wake = true;
        self.lock.unlock();
        self.thread = std.Thread.spawn(.{}, body, .{ self, link } ++ extra) catch |e| {
            link.destroy();
            self.lock.lock();
            self.window = null;
            self.lock.unlock();
            return e;
        };
        self.render_link = link;
    }

    /// End the render thread, if there is one: one that ended by itself (`render_failed`)
    /// is joined all the same. One that does not end within `stop_wait_ns` is given up
    /// (#159): it is left to end by itself, if it ever does, and the pane forgets it. The pane
    /// draws to no window afterwards; the reference to the one it drew to is not touched here.
    /// False when the thread was given up.
    fn stopRenderer(self: *Pane) bool {
        var ended = true;
        if (self.thread) |t| {
            const link = self.render_link.?;
            // Read now: a thread given up may free its link at any time.
            const window = link.window;
            self.lock.lock();
            self.quit = true;
            self.lock.signal();
            self.lock.unlock();
            const start = monoNow();
            if (link.waitEnd(stopBound(start))) {
                t.join();
                link.destroy();
            } else {
                t.detach();
                const now = monoNow();
                // The stops that follow soon wait briefly (`burst_wait_ns`).
                _ = give_up_until_ns.fetchMax(@intCast(now + stop_wait_ns), .monotonic);
                logGivenUp(self, window, now - start);
                ended = false;
            }
            self.thread = null;
            self.render_link = null;
        }
        self.lock.lock();
        self.window = null;
        self.render_failed = false;
        self.settle_frames = 0;
        self.frame_due_ns = 0;
        self.lock.unlock();
        return ended;
    }

    /// Local text scale (pinch). The size the terminal should have follows from it
    /// (`fitGrid`); asking the server for that size is the core's work. `live`: a pinch is
    /// still changing it, and a scale that is not live follows when it settles.
    pub fn setScale(self: *Pane, scale: f32, live: bool) void {
        self.lock.lock();
        defer self.lock.unlock();
        self.scale_live = live;
        const old = self.scale;
        self.scale = std.math.clamp(scale, 0.25, 8);
        // Keep the same content under the left and top edges of the view.
        self.pan_x = self.pan_x * self.scale / old;
        self.pan_y = self.pan_y * self.scale / old;
        self.scroll_off = 0; // cells of another size: the offset is no fraction of a row
        self.clampPanLocked();
        self.wake = true;
        self.lock.signal();
    }

    /// Margin in px around the grid (keeps text clear of rounded pane corners).
    pub fn setPaddingPx(self: *Pane, px: f32) void {
        self.lock.lock();
        defer self.lock.unlock();
        self.pad = std.math.clamp(px, 0, 256);
        self.scroll_off = 0;
        self.clampPanLocked();
        self.wake = true;
        self.lock.signal();
    }

    /// Base font size in px at scale 1 (from settings).
    pub fn setFontSizePx(self: *Pane, px: f32) void {
        self.lock.lock();
        defer self.lock.unlock();
        self.font_px = @max(px, 4);
        self.scroll_off = 0;
        self.wake = true;
        self.lock.signal();
    }

    /// The glyph source now draws another font with this line height in em.
    pub fn setFont(self: *Pane, line_height: f32) void {
        // Before the renderer sees `glyphs_stale`: the atlas it empties then must not take back
        // a glyph of the old font from the cache.
        if (self.glyphs.cache) |cache| cache.invalidate();
        self.lock.lock();
        defer self.lock.unlock();
        self.line_height = std.math.clamp(line_height, 1, 2);
        self.glyphs_stale = true;
        self.clampPanLocked();
        self.wake = true;
        self.lock.signal();
    }

    pub const CellSize = struct { w: u32, h: u32 };

    /// Local cell size: font size × scale; width is the monospace advance (0.6 em),
    /// height `line_height`. Caller holds the lock.
    pub fn cellSizeLocked(self: *const Pane) CellSize {
        const px = self.font_px * self.scale;
        return .{
            .w = @max(1, @as(u32, @intFromFloat(@ceil(px * 0.6)))),
            .h = @max(1, @as(u32, @intFromFloat(@ceil(px * self.line_height)))),
        };
    }

    /// A grid that fits a surface, with the size of its cells in px.
    pub const Fit = struct { cols: u16, rows: u16, cell_w: u16, cell_h: u16 };

    /// Columns and rows of the local cell size (font size × scale) that fit a surface of
    /// `surface_w` × `surface_h` px less the padding on every side. Never less than 1 × 1.
    /// The size is the caller's, so that a surface the pane does not draw to can be
    /// measured too.
    pub fn fitGrid(self: *Pane, surface_w: u32, surface_h: u32) Fit {
        self.lock.lock();
        defer self.lock.unlock();
        const cs = self.cellSizeLocked();
        const max = std.math.maxInt(u16);
        return .{
            .cols = fitCount(surface_w, self.pad, cs.w),
            .rows = fitCount(surface_h, self.pad, cs.h),
            .cell_w = @intCast(@min(cs.w, max)),
            .cell_h = @intCast(@min(cs.h, max)),
        };
    }

    /// Cells of `cell` px that fit `surface` px less `pad` px at both ends; at least one.
    fn fitCount(surface: u32, pad: f32, cell: u32) u16 {
        const usable = @as(f32, @floatFromInt(surface)) - 2 * pad;
        if (usable < @as(f32, @floatFromInt(cell))) return 1;
        return @intFromFloat(@min(@floor(usable / @as(f32, @floatFromInt(cell))), std.math.maxInt(u16)));
    }

    pub const ScrollInput = struct {
        count: u16,
        event: union(enum) { wheel: struct { x: u16, y: u16, delta_y: i8 }, cursor: []const u8 },
    };

    fn modeEnabled(self: *Pane, number: u16) bool {
        var config = c.GhosttyTerminalModeConfig{ .mode = c.ghostty_mode_new(number, false), .value = false };
        return c.ghostty_terminal_get(self.term, c.GHOSTTY_TERMINAL_DATA_MODE, &config) == c.GHOSTTY_SUCCESS and config.value;
    }

    /// Drag the view by (dx, dy) px, as a finger moving that far would: content follows
    /// the finger. A grid larger than the surface moves first; what is left of a vertical
    /// drag at the grid's edge scrolls history by the nearer whole row and moves the pane's
    /// place by the rest (`scroll_off`), or produces input for the active TUI, in whole rows.
    pub fn pan(self: *Pane, dx: f32, dy: f32, x: f32, y: f32) ?ScrollInput {
        if (!std.math.isFinite(dx) or !std.math.isFinite(dy) or !std.math.isFinite(x) or !std.math.isFinite(y)) return null;
        self.lock.lock();
        defer self.lock.unlock();
        self.pan_x -= dx;
        const wanted_y = self.pan_y - dy;
        self.pan_y = wanted_y;
        self.clampPanLocked();
        // The settle waits again from now, wherever the drag leaves the offset.
        self.scroll_last_pan_ns = monoNow();
        self.scroll_settle_start_ns = 0;
        self.scroll_wakes = 0;

        const ch: f32 = @floatFromInt(self.cellSizeLocked().h);
        const left = self.pan_y - wanted_y; // of the drag, what the grid's pan could not take
        const height = self.termU16(c.GHOSTTY_TERMINAL_DATA_ROWS);
        // Mouse reporting wins on either screen. DEC 1007 makes the alternate screen scroll
        // with cursor keys only when the PTY hasn't requested the mouse. Both take whole rows:
        // what is not one yet waits in `wheel_rem`, where nothing shows it.
        var mouse = false;
        _ = c.ghostty_terminal_get(self.term, c.GHOSTTY_TERMINAL_DATA_MOUSE_TRACKING, &mouse);
        const wheel = mouse and height > 0;
        const keys = !wheel and blk: {
            var screen: c.GhosttyTerminalScreen = c.GHOSTTY_TERMINAL_SCREEN_PRIMARY;
            _ = c.ghostty_terminal_get(self.term, c.GHOSTTY_TERMINAL_DATA_ACTIVE_SCREEN, &screen);
            break :blk screen == c.GHOSTTY_TERMINAL_SCREEN_ALTERNATE and self.modeEnabled(1007);
        };
        var rows: i32 = 0;
        if (wheel or keys) {
            self.scroll_off = 0;
            self.wheel_rem += left;
            rows = @intFromFloat(std.math.clamp(@trunc(self.wheel_rem / ch), -10_000, 10_000));
            self.wheel_rem -= @as(f32, @floatFromInt(rows)) * ch;
        } else {
            self.scroll_off += left;
            rows = scrollRows(self.scroll_off, ch);
        }
        defer {
            self.wake = true;
            self.lock.signal();
        }
        var before: c.GhosttyTerminalScrollbar = .{};
        if (rows != 0) {
            const count: u16 = @intCast(@min(@abs(rows), height));
            if (wheel) {
                const width = self.termU16(c.GHOSTTY_TERMINAL_DATA_COLS);
                if (width == 0) return null;
                const origin = self.originLocked();
                const cw: f32 = @floatFromInt(self.cellSizeLocked().w);
                return .{ .count = count, .event = .{ .wheel = .{
                    .x = @intFromFloat(std.math.clamp((x - origin.x) / cw, 0, @as(f32, @floatFromInt(width - 1)))),
                    .y = @intFromFloat(std.math.clamp((y - origin.y) / ch, 0, @as(f32, @floatFromInt(height - 1)))),
                    .delta_y = if (rows > 0) -1 else 1,
                } } };
            }
            if (keys) {
                const seq: []const u8 = if (self.modeEnabled(1))
                    (if (rows > 0) "\x1bOA" else "\x1bOB")
                else
                    (if (rows > 0) "\x1b[A" else "\x1b[B");
                return .{ .count = count, .event = .{ .cursor = seq } };
            }
            // Dragging down (dy > 0) shows earlier rows: a negative viewport delta.
            _ = c.ghostty_terminal_get(self.term, c.GHOSTTY_TERMINAL_DATA_SCROLLBAR, &before);
            _ = c.ghostty_terminal_scroll_viewport(self.term, .{
                .tag = c.GHOSTTY_TERMINAL_SCROLL_VIEWPORT_DELTA,
                .value = .{ .delta = -rows },
            });
        }
        // The rows that had nowhere to go take none of the place with them, and neither end
        // of the history has a row to uncover.
        var after: c.GhosttyTerminalScrollbar = .{};
        _ = c.ghostty_terminal_get(self.term, c.GHOSTTY_TERMINAL_DATA_SCROLLBAR, &after);
        if (rows != 0 and @as(i128, after.offset) != @as(i128, before.offset) - rows) {
            self.scroll_off = 0;
        } else {
            self.scroll_off -= @as(f32, @floatFromInt(rows)) * ch;
        }
        if (after.offset == 0) self.scroll_off = @min(self.scroll_off, 0);
        if (after.offset + after.len == after.total) self.scroll_off = @max(self.scroll_off, 0);
        return null;
    }

    /// The whole rows to scroll for a place `off` px off its rows, of `cell` px each: the
    /// nearer row; half a row stays where it is until a thousandth of a row past it. At most
    /// 10,000 rows either way.
    pub fn scrollRows(off: f32, cell: f32) i32 {
        const whole = @trunc((@abs(off) + cell / 2 - cell / 1000) / cell);
        const rows: i32 = @intFromFloat(std.math.clamp(whole, -10_000, 10_000));
        return if (off < 0) -rows else rows;
    }

    /// How far the viewport is drawn off its rows, in px (see `scroll_off`).
    pub fn scrollOffsetPx(self: *Pane) f32 {
        self.lock.lock();
        defer self.lock.unlock();
        return self.scroll_off;
    }

    /// Whether the render thread should draw another frame for the offset to rest on rows.
    pub fn scrollStep(self: *Pane, now_ns: i128) bool {
        self.lock.lock();
        defer self.lock.unlock();
        return self.scrollStepLocked(now_ns);
    }

    /// Advances the offset by the clock (see `scroll_off`): it waits `scroll_wait_ns` after
    /// the last pan, wanting no frame for that (`scrollWaitLocked` holds the render thread
    /// until it is due), then eases to nothing over `scroll_settle_ns`, a frame at a time.
    /// `scroll_frames_max` bounds the easing only, against a clock that stands still: without
    /// it a frozen clock would ease forever and never let the offset rest. Caller holds the
    /// pane mutex.
    pub fn scrollStepLocked(self: *Pane, now_ns: i128) bool {
        if (self.scroll_off == 0) {
            self.scrollRestLocked();
            return false;
        }
        if (self.scroll_settle_start_ns == 0) {
            const due = self.scroll_last_pan_ns + scroll_wait_ns;
            if (now_ns < due) return false; // the offset waits for its 150 ms
            self.scroll_settle_from = self.scroll_off;
            self.scroll_settle_start_ns = due;
        }
        const t = @as(f32, @floatFromInt(now_ns - self.scroll_settle_start_ns)) / @as(f32, @floatFromInt(scroll_settle_ns));
        if (t >= 1 or self.scroll_wakes >= scroll_frames_max) {
            self.scrollRestLocked();
            return false;
        }
        const s = std.math.clamp(t, 0, 1);
        self.scroll_off = self.scroll_settle_from * (1 - s * s * (3 - 2 * s));
        self.scroll_wakes += 1;
        return true;
    }

    /// How long the render thread may wait before the offset needs it (ns; 0: it is due now),
    /// or null when nothing is: the offset rests, and a pan or output wakes the thread.
    /// Caller holds the pane mutex.
    pub fn scrollWaitLocked(self: *const Pane, now_ns: i128) ?u64 {
        if (self.scroll_off == 0) return null;
        const due = self.scroll_last_pan_ns + scroll_wait_ns;
        if (self.scroll_settle_start_ns != 0 or now_ns >= due) return 0;
        return @intCast(due - now_ns);
    }

    /// How long the render thread waits for the frame it asked for itself (`frame_due_ns`):
    /// null for none, 0 when it is due. Caller holds the pane mutex.
    pub fn frameWaitLocked(self: *const Pane, now_ns: i128) ?u64 {
        if (self.frame_due_ns == 0) return null;
        if (now_ns >= self.frame_due_ns) return 0;
        return @intCast(self.frame_due_ns - now_ns);
    }

    /// (locks) `scrollWaitLocked`, for callers without the mutex held.
    pub fn scrollWait(self: *Pane, now_ns: i128) ?u64 {
        self.lock.lock();
        defer self.lock.unlock();
        return self.scrollWaitLocked(now_ns);
    }

    /// The offset rests; nothing is asked for until the next pan. Caller holds the pane mutex.
    pub fn scrollRestLocked(self: *Pane) void {
        self.scroll_off = 0;
        self.scroll_settle_from = 0;
        self.scroll_settle_start_ns = 0;
        self.scroll_wakes = 0;
    }

    /// Width in px of the grid plus its margins. Caller holds the lock.
    pub fn contentWidthLocked(self: *Pane) f32 {
        const cols: f32 = @floatFromInt(self.termU16(c.GHOSTTY_TERMINAL_DATA_COLS));
        return cols * @as(f32, @floatFromInt(self.cellSizeLocked().w)) + 2 * self.pad;
    }

    /// Height in px of the grid plus its margins. Caller holds the lock.
    pub fn contentHeightLocked(self: *Pane) f32 {
        const rows: f32 = @floatFromInt(self.termU16(c.GHOSTTY_TERMINAL_DATA_ROWS));
        return rows * @as(f32, @floatFromInt(self.cellSizeLocked().h)) + 2 * self.pad;
    }

    /// Keep the view offsets inside [0, content size − surface size]. Caller holds the lock.
    pub fn clampPanLocked(self: *Pane) void {
        const max_x = @max(self.contentWidthLocked() - @as(f32, @floatFromInt(self.surface_w)), 0);
        const max_y = @max(self.contentHeightLocked() - @as(f32, @floatFromInt(self.surface_h)), 0);
        self.pan_x = std.math.clamp(self.pan_x, 0, max_x);
        self.pan_y = std.math.clamp(self.pan_y, 0, max_y);
    }

    /// Left/top of the grid in surface px. Caller holds the lock.
    pub fn originLocked(self: *const Pane) struct { x: f32, y: f32 } {
        return .{ .x = @round(self.pad - self.pan_x), .y = @round(self.pad - self.pan_y + self.scroll_off) };
    }

    fn termU16(self: *Pane, data: c_int) u16 {
        var v: u16 = 0;
        _ = c.ghostty_terminal_get(self.term, @intCast(data), &v);
        return v;
    }

    fn gridRef(self: *Pane, tag: c.GhosttyPointTag, coord: c.GhosttyPointCoordinate) ?c.GhosttyGridRef {
        var ref: c.GhosttyGridRef = .{};
        const pt: c.GhosttyPoint = .{ .tag = tag, .value = .{ .coordinate = coord } };
        return if (c.ghostty_terminal_grid_ref(self.term, pt, &ref) == c.GHOSTTY_SUCCESS) ref else null;
    }

    fn coordinate(self: *Pane, ref: *const c.GhosttyGridRef, tag: c.GhosttyPointTag) ?c.GhosttyPointCoordinate {
        var pt: c.GhosttyPointCoordinate = undefined;
        return if (c.ghostty_terminal_point_from_grid_ref(self.term, ref, tag, &pt) == c.GHOSTTY_SUCCESS) pt else null;
    }

    /// Presses in the margin have no word; drags beyond the grid stop at its edge. In the
    /// band the pane's offset uncovers, the hit is the row shown there, as a screen row.
    fn hitLocked(self: *Pane, x: f32, y: f32, clamp: bool) ?c.GhosttyGridRef {
        if (!std.math.isFinite(x) or !std.math.isFinite(y)) return null;
        const o = self.originLocked();
        const cs = self.cellSizeLocked();
        const col = @floor((x - o.x) / @as(f32, @floatFromInt(cs.w)));
        const row = @floor((y - o.y) / @as(f32, @floatFromInt(cs.h)));
        const cols: f32 = @floatFromInt(self.termU16(c.GHOSTTY_TERMINAL_DATA_COLS));
        const rows: f32 = @floatFromInt(self.termU16(c.GHOSTTY_TERMINAL_DATA_ROWS));
        if (cols == 0 or rows == 0) return null;
        if (row < 0 or row >= rows) {
            // Off the viewport: the band the offset uncovers has a row to show (as a screen
            // row); with the handle's clamp the drag holds at the grid's edge instead, as it
            // did before the band existed.
            if (self.bandRow(y)) |band| {
                if (!clamp and (col < 0 or col >= cols)) return null;
                return self.gridRef(c.GHOSTTY_POINT_TAG_SCREEN, .{
                    .x = @intFromFloat(std.math.clamp(col, 0, cols - 1)),
                    .y = @intCast(band),
                });
            }
            if (!clamp) return null;
        }
        if (!clamp and (col < 0 or col >= cols)) return null;
        return self.gridRef(c.GHOSTTY_POINT_TAG_VIEWPORT, .{
            .x = @intFromFloat(std.math.clamp(col, 0, cols - 1)),
            .y = @intFromFloat(std.math.clamp(row, 0, rows - 1)),
        });
    }

    /// The row of the scrollable area that the pane's offset uncovers at `y` px of the
    /// surface: the row above the viewport with a positive offset, the row below it with a
    /// negative one. Null outside that band. Caller holds the pane mutex.
    fn bandRow(self: *Pane, y: f32) ?u64 {
        if (self.scroll_off == 0) return null;
        const o = self.originLocked();
        const cs = self.cellSizeLocked();
        const rows_px = @as(f32, @floatFromInt(self.termU16(c.GHOSTTY_TERMINAL_DATA_ROWS))) * @as(f32, @floatFromInt(cs.h));
        const top = @round(self.pad - self.pan_y); // where the viewport's first row rests
        var scroll: c.GhosttyTerminalScrollbar = .{};
        if (c.ghostty_terminal_get(self.term, c.GHOSTTY_TERMINAL_DATA_SCROLLBAR, &scroll) != c.GHOSTTY_SUCCESS) return null;
        if (self.scroll_off > 0 and y >= top and y < o.y) return scroll.offset - 1;
        if (self.scroll_off < 0 and y >= o.y + rows_px and y < top + rows_px) return scroll.offset + self.termU16(c.GHOSTTY_TERMINAL_DATA_ROWS);
        return null;
    }

    /// The inclusive first/last cell of the glyph under ref. A spacer at the
    /// wrap margin belongs to the wide character at the start of the next row.
    fn glyphEdge(self: *Pane, ref: c.GhosttyGridRef, start: bool) ?c.GhosttyGridRef {
        var cell: c.GhosttyCell = undefined;
        var wide: c.GhosttyCellWide = undefined;
        if (c.ghostty_grid_ref_cell(&ref, &cell) != c.GHOSTTY_SUCCESS or
            c.ghostty_cell_get(cell, c.GHOSTTY_CELL_DATA_WIDE, &wide) != c.GHOSTTY_SUCCESS) return null;
        if (wide == c.GHOSTTY_CELL_WIDE_NARROW or (start and wide == c.GHOSTTY_CELL_WIDE_WIDE) or
            (!start and wide == c.GHOSTTY_CELL_WIDE_SPACER_TAIL)) return ref;
        var pt = self.coordinate(&ref, c.GHOSTTY_POINT_TAG_SCREEN) orelse return null;
        switch (wide) {
            c.GHOSTTY_CELL_WIDE_WIDE => pt.x += 1,
            c.GHOSTTY_CELL_WIDE_SPACER_TAIL => pt.x -= 1,
            c.GHOSTTY_CELL_WIDE_SPACER_HEAD => {
                pt.x = if (start) 0 else 1;
                pt.y += 1;
            },
            else => return null,
        }
        return self.gridRef(c.GHOSTTY_POINT_TAG_SCREEN, pt);
    }

    fn setSelectionLocked(self: *Pane, sel: ?*const c.GhosttySelection) void {
        _ = c.ghostty_terminal_set(self.term, c.GHOSTTY_TERMINAL_OPT_SELECTION, sel);
        self.wake = true;
        self.lock.signal();
    }

    fn wordLocked(self: *Pane, x: f32, y: f32) ?c.GhosttySelection {
        const ref = self.glyphEdge(self.hitLocked(x, y, false) orelse return null, true) orelse return null;
        var cell: c.GhosttyCell = undefined;
        var cp: u32 = undefined;
        if (c.ghostty_grid_ref_cell(&ref, &cell) != c.GHOSTTY_SUCCESS or
            c.ghostty_cell_get(cell, c.GHOSTTY_CELL_DATA_CODEPOINT, &cp) != c.GHOSTTY_SUCCESS) return null;
        // Ghostty selects runs of boundary characters too. Empty cells and
        // Unicode White_Space specifically offer Paste without selecting a run.
        switch (cp) {
            0, 9...13, 0x20, 0x85, 0xa0, 0x1680, 0x2000...0x200a, 0x2028, 0x2029, 0x202f, 0x205f, 0x3000 => return null,
            else => {},
        }
        const opts: c.GhosttyTerminalSelectWordOptions = .{ .size = @sizeOf(c.GhosttyTerminalSelectWordOptions), .ref = ref };
        var sel: c.GhosttySelection = .{};
        if (c.ghostty_terminal_select_word(self.term, &opts, &sel) != c.GHOSTTY_SUCCESS) return null;
        sel.start = self.glyphEdge(sel.start, true) orelse return null;
        sel.end = self.glyphEdge(sel.end, false) orelse return null;
        return sel;
    }

    pub fn selectWord(self: *Pane, x: f32, y: f32) void {
        self.lock.lock();
        defer self.lock.unlock();
        c.ghostty_selection_gesture_reset(self.gesture, self.term);
        const word = self.wordLocked(x, y);
        self.setSelectionLocked(if (word) |*sel| sel else null);
    }

    /// Ghostty owns the tracked endpoints. Only borrow their current positions
    /// under the lock: output, reflow or a snapshot may move or remove them.
    fn orderedSelectionLocked(self: *Pane) ?c.GhosttySelection {
        var sel: c.GhosttySelection = .{};
        var ordered: c.GhosttySelection = .{};
        if (c.ghostty_terminal_get(self.term, c.GHOSTTY_TERMINAL_DATA_SELECTION, &sel) != c.GHOSTTY_SUCCESS or
            c.ghostty_terminal_selection_ordered(self.term, &sel, c.GHOSTTY_SELECTION_ORDER_FORWARD, &ordered) != c.GHOSTTY_SUCCESS) return null;
        ordered.start = self.glyphEdge(ordered.start, true) orelse return null;
        ordered.end = self.glyphEdge(ordered.end, false) orelse return null;
        return ordered;
    }

    pub fn moveSelectionHandle(self: *Pane, start: bool, x: f32, y: f32) void {
        self.lock.lock();
        defer self.lock.unlock();
        var sel = self.orderedSelectionLocked() orelse return;
        const target = if (start) &sel.start else &sel.end;
        target.* = self.glyphEdge(self.hitLocked(x, y, true) orelse return, start) orelse return;
        // Never swap the handle under the finger. At a crossing leave just the
        // fixed endpoint's glyph selected (including both cells if wide).
        var order: c.GhosttySelectionOrder = undefined;
        if (c.ghostty_terminal_selection_order(self.term, &sel, &order) != c.GHOSTTY_SUCCESS) return;
        if (order == c.GHOSTTY_SELECTION_ORDER_REVERSE)
            target.* = self.glyphEdge(if (start) sel.end else sel.start, start) orelse return;
        self.setSelectionLocked(&sel);
    }

    /// Pane-local pixel anchors, not a rectangle: on a soft wrap end.x can
    /// be less than start.x. Never substitute a different, visible endpoint.
    pub fn selectionBounds(self: *Pane) ?[4]i32 {
        self.lock.lock();
        defer self.lock.unlock();
        const sel = self.orderedSelectionLocked() orelse return null;
        const a = self.coordinate(&sel.start, c.GHOSTTY_POINT_TAG_VIEWPORT) orelse return null;
        const b = self.coordinate(&sel.end, c.GHOSTTY_POINT_TAG_VIEWPORT) orelse return null;
        if (b.y >= self.termU16(c.GHOSTTY_TERMINAL_DATA_ROWS)) return null;
        const o = self.originLocked();
        const cs = self.cellSizeLocked();
        const left = @as(i64, @intFromFloat(o.x)) + @as(i64, a.x) * cs.w;
        const right = @as(i64, @intFromFloat(o.x)) + (@as(i64, b.x) + 1) * cs.w;
        const first = @as(i64, @intFromFloat(o.y)) + (@as(i64, a.y) + 1) * cs.h;
        const last = @as(i64, @intFromFloat(o.y)) + (@as(i64, b.y) + 1) * cs.h;
        const w = @min(self.surface_w, std.math.maxInt(i32));
        const h = @min(self.surface_h, std.math.maxInt(i32));
        if (left < 0 or left >= w or right <= 0 or right > w or first <= 0 or last > h) return null;
        return .{ @intCast(left), @intCast(first), @intCast(right), @intCast(last) };
    }

    /// action: 0 down, 1 move, 2 up (selection gestures). x/y in surface px.
    pub fn touch(self: *Pane, action: u8, x: f32, y: f32) void {
        if (action > 2) return;
        self.lock.lock();
        defer self.lock.unlock();
        const cs = self.cellSizeLocked();
        const cols = self.termU16(c.GHOSTTY_TERMINAL_DATA_COLS);
        const rows = self.termU16(c.GHOSTTY_TERMINAL_DATA_ROWS);
        if (cols == 0 or rows == 0) return;
        const origin = self.originLocked();
        const fx = @max(x - origin.x, 0);
        var fy = @max(y - origin.y, 0);
        if (self.bandRow(y) != null) {
            // The pointer goes with the row the band shows, a whole row off the viewport's.
            fy = if (self.scroll_off > 0)
                (y - origin.y) + @as(f32, @floatFromInt(cs.h))
            else
                (y - origin.y) - @as(f32, @floatFromInt(rows)) * @as(f32, @floatFromInt(cs.h));
        }

        var ref: c.GhosttyGridRef = .{};
        var have_ref = false;
        if (self.hitLocked(x, y, true)) |h| {
            ref = h;
            have_ref = true;
        }

        const ev = self.gesture_event[action];
        const kind: c_int = switch (action) {
            0 => c.GHOSTTY_SELECTION_GESTURE_EVENT_OPT_REF,
            else => c.GHOSTTY_SELECTION_GESTURE_EVENT_OPT_REF,
        };
        _ = c.ghostty_selection_gesture_event_set(ev, @intCast(kind), if (have_ref) &ref else null);
        if (action != 2) {
            const pos: c.GhosttySurfacePosition = .{ .x = fx, .y = fy };
            _ = c.ghostty_selection_gesture_event_set(ev, c.GHOSTTY_SELECTION_GESTURE_EVENT_OPT_POSITION, &pos);
        }
        if (action == 1) {
            const geo: c.GhosttySelectionGestureGeometry = .{
                .columns = cols,
                .cell_width = cs.w,
                .padding_left = 0,
                .screen_height = @max(self.surface_h, cs.h * rows),
            };
            _ = c.ghostty_selection_gesture_event_set(ev, c.GHOSTTY_SELECTION_GESTURE_EVENT_OPT_GEOMETRY, &geo);
        }
        if (action != 2 and !have_ref) return;
        var sel: c.GhosttySelection = .{};
        const r = c.ghostty_selection_gesture_event(self.gesture, self.term, ev, &sel);
        if (r == c.GHOSTTY_SUCCESS) {
            _ = c.ghostty_terminal_set(self.term, c.GHOSTTY_TERMINAL_OPT_SELECTION, &sel);
        } else if (r == c.GHOSTTY_NO_VALUE and action == 0) {
            _ = c.ghostty_terminal_set(self.term, c.GHOSTTY_TERMINAL_OPT_SELECTION, null);
        }
        self.wake = true;
        self.lock.signal();
    }

    fn setSearchHitLocked(self: *Pane, hit: ?SearchHit) void {
        if (std.meta.eql(self.search_hit, hit)) return;
        self.search_hit = hit;
        self.force_full = true;
        self.wake = true;
        self.lock.signal();
    }

    fn closeSearchLocked(self: *Pane) void {
        c.ghostty_search_free(self.search);
        self.search = null;
        self.setSearchHitLocked(null);
    }

    /// One bounded step on the current screen and its local history. Selection is
    /// independent of touch selection. Never retain Ghostty grid references after unlocking.
    pub fn find(self: *Pane, needle: []const u8, direction: i32) !SearchResult {
        self.lock.lock();
        defer self.lock.unlock();
        if (needle.len == 0) {
            self.closeSearchLocked();
            return .{};
        }
        if (self.search == null and c.ghostty_search_new(null, &self.search, self.term) != c.GHOSTTY_SUCCESS)
            return error.SearchUnavailable;
        const query: c.GhosttyString = .{ .ptr = needle.ptr, .len = needle.len };
        if (c.ghostty_search_set(self.search, c.GHOSTTY_SEARCH_OPT_NEEDLE, &query) != c.GHOSTTY_SUCCESS)
            return error.SearchUnavailable;
        var status: c.GhosttySearchStatus = undefined;
        _ = c.ghostty_search_get(self.search, c.GHOSTTY_SEARCH_DATA_STATUS, &status);
        if (self.search_gen != self.generation() or status == c.GHOSTTY_SEARCH_STATUS_FEED_REQUIRED) {
            if (c.ghostty_search_feed(self.search) != c.GHOSTTY_SUCCESS) return error.SearchUnavailable;
            self.search_gen = self.generation();
        }
        for (0..4) |_| {
            if (c.ghostty_search_tick(self.search, &status) != c.GHOSTTY_SUCCESS) return error.SearchUnavailable;
            if (status != c.GHOSTTY_SEARCH_STATUS_RUNNING) break;
        }
        var count: usize = 0;
        var index: usize = 0;
        _ = c.ghostty_search_get(self.search, c.GHOSTTY_SEARCH_DATA_TOTAL_MATCHES, &count);
        const selected = c.ghostty_search_get(self.search, c.GHOSTTY_SEARCH_DATA_SELECTED_INDEX, &index) == c.GHOSTTY_SUCCESS;
        const navigate = count > 0 and (direction != 0 or !selected);
        if (navigate) {
            const result = if (direction < 0) c.ghostty_search_select_prev(self.search) else c.ghostty_search_select_next(self.search);
            if (result != c.GHOSTTY_SUCCESS and result != c.GHOSTTY_NO_VALUE) return error.SearchUnavailable;
        }
        var match: c.GhosttySelection = .{};
        var hit: SearchHit = undefined;
        var position: u32 = 0;
        if (c.ghostty_search_get(self.search, c.GHOSTTY_SEARCH_DATA_SELECTED_MATCH, &match) == c.GHOSTTY_SUCCESS and
            c.ghostty_terminal_point_from_grid_ref(self.term, &match.start, c.GHOSTTY_POINT_TAG_SCREEN, &hit.start) == c.GHOSTTY_SUCCESS and
            c.ghostty_terminal_point_from_grid_ref(self.term, &match.end, c.GHOSTTY_POINT_TAG_SCREEN, &hit.end) == c.GHOSTTY_SUCCESS)
        {
            self.setSearchHitLocked(hit);
            _ = c.ghostty_search_get(self.search, c.GHOSTTY_SEARCH_DATA_SELECTED_INDEX, &index);
            position = @intCast(index + 1);
            if (navigate) {
                var start: c.GhosttyPointCoordinate = undefined;
                if (c.ghostty_terminal_point_from_grid_ref(self.term, &match.start, c.GHOSTTY_POINT_TAG_VIEWPORT, &start) == c.GHOSTTY_SUCCESS) {
                    const cell = self.cellSizeLocked();
                    self.pan_x = revealCell(self.pan_x, self.pad, start.x, cell.w, self.surface_w);
                    self.pan_y = revealCell(self.pan_y, self.pad, start.y, cell.h, self.surface_h);
                    self.clampPanLocked();
                }
                self.wake = true;
                self.lock.signal();
            }
        } else self.setSearchHitLocked(null);
        return .{ .count = @intCast(count), .position = position, .pending = status != c.GHOSTTY_SEARCH_STATUS_COMPLETE };
    }

    fn revealCell(offset: f32, pad: f32, index: u32, cell: u32, surface: u32) f32 {
        const size: f32 = @floatFromInt(cell);
        const start = pad + @as(f32, @floatFromInt(index)) * size;
        return @min(start, @max(offset, start + size - @as(f32, @floatFromInt(surface))));
    }

    /// Selection-only copy. Both the existence check and formatting use the
    /// same locked terminal snapshot, so a disappearing range never copies the screen.
    pub fn selectedText(self: *Pane, gpa: std.mem.Allocator) ![]u8 {
        self.lock.lock();
        defer self.lock.unlock();
        var sel: c.GhosttySelection = .{};
        if (c.ghostty_terminal_get(self.term, c.GHOSTTY_TERMINAL_DATA_SELECTION, &sel) != c.GHOSTTY_SUCCESS) return gpa.alloc(u8, 0);
        return self.formatTextLocked(gpa, &sel, false);
    }

    /// Selected text, or the visible screen text when nothing is selected. Caller frees.
    pub fn copyText(self: *Pane, gpa: std.mem.Allocator) ![]u8 {
        self.lock.lock();
        defer self.lock.unlock();
        var sel: c.GhosttySelection = .{};
        const selected = c.ghostty_terminal_get(self.term, c.GHOSTTY_TERMINAL_DATA_SELECTION, &sel) == c.GHOSTTY_SUCCESS;
        return self.formatTextLocked(gpa, if (selected) &sel else null, true);
    }

    fn visibleAxis(origin: f32, pixels: u32, cell: u32, count: u16) ?[2]u16 {
        const size: f32 = @floatFromInt(cell);
        const first = @max(0, @floor(-origin / size));
        const end = @min(@as(f32, @floatFromInt(count)), @ceil((@as(f32, @floatFromInt(pixels)) - origin) / size));
        if (pixels == 0 or first >= end) return null;
        return .{ @intFromFloat(first), @intFromFloat(end - 1) };
    }

    /// The cells intersecting the surface, independent of the touch selection.
    pub fn viewportText(self: *Pane, gpa: std.mem.Allocator) ![]u8 {
        self.lock.lock();
        defer self.lock.unlock();
        const origin = self.originLocked();
        const cell = self.cellSizeLocked();
        const cols = self.termU16(c.GHOSTTY_TERMINAL_DATA_COLS);
        const x = visibleAxis(origin.x, self.surface_w, cell.w, cols) orelse return gpa.alloc(u8, 0);
        const y = visibleAxis(origin.y, self.surface_h, cell.h, self.termU16(c.GHOSTTY_TERMINAL_DATA_ROWS)) orelse return gpa.alloc(u8, 0);
        const end_col = @as(usize, x[1]) + 1;
        const full_width = x[0] == 0 and end_col == cols;
        var text: std.ArrayList(u8) = .empty;
        defer text.deinit(gpa);
        for (y[0]..@as(usize, y[1]) + 1) |row| {
            var ref: c.GhosttyGridRef = .{};
            // A wide glyph can start one cell to the left and still intersect the surface.
            for (x[0] -| 1..end_col) |col| {
                const point: c.GhosttyPoint = .{ .tag = c.GHOSTTY_POINT_TAG_VIEWPORT, .value = .{ .coordinate = .{ .x = @intCast(col), .y = @intCast(row) } } };
                if (c.ghostty_terminal_grid_ref(self.term, point, &ref) != c.GHOSTTY_SUCCESS) return error.FormatFailed;
                var raw: c.GhosttyCell = undefined;
                var wide: c.GhosttyCellWide = undefined;
                if (c.ghostty_grid_ref_cell(&ref, &raw) != c.GHOSTTY_SUCCESS or
                    c.ghostty_cell_get(raw, c.GHOSTTY_CELL_DATA_WIDE, &wide) != c.GHOSTTY_SUCCESS) return error.FormatFailed;
                const width: usize = switch (wide) {
                    c.GHOSTTY_CELL_WIDE_NARROW => 1,
                    c.GHOSTTY_CELL_WIDE_WIDE => 2,
                    else => continue, // A spacer has no glyph of its own.
                };
                const visible_width = @min(col + width, end_col) - @max(col, x[0]);
                if (visible_width == 0) continue;
                var style: c.GhosttyStyle = undefined;
                if (c.ghostty_grid_ref_style(&ref, &style) != c.GHOSTTY_SUCCESS) return error.FormatFailed;
                if (style.flags & c.GHOSTTY_STYLE_FLAG_INVISIBLE != 0) {
                    try text.appendNTimes(gpa, ' ', visible_width);
                } else {
                    try appendCellText(gpa, &text, &ref);
                }
            }
            if (full_width and row < y[1]) {
                var raw_row: c.GhosttyRow = undefined;
                var wraps: bool = false;
                if (c.ghostty_grid_ref_row(&ref, &raw_row) != c.GHOSTTY_SUCCESS or
                    c.ghostty_row_get(raw_row, c.GHOSTTY_ROW_DATA_WRAP, &wraps) != c.GHOSTTY_SUCCESS) return error.FormatFailed;
                if (wraps) continue;
            }
            text.shrinkRetainingCapacity(std.mem.trimEnd(u8, text.items, " ").len);
            if (row < y[1]) try text.append(gpa, '\n');
        }
        text.shrinkRetainingCapacity(std.mem.trimEnd(u8, text.items, "\n").len);
        return text.toOwnedSlice(gpa);
    }

    fn appendCellText(gpa: std.mem.Allocator, text: *std.ArrayList(u8), ref: *const c.GhosttyGridRef) !void {
        var stack: [8]u32 = undefined;
        var points: []u32 = &stack;
        var count: usize = 0;
        var result = c.ghostty_grid_ref_graphemes(ref, points.ptr, points.len, &count);
        if (result == c.GHOSTTY_OUT_OF_SPACE) {
            points = try gpa.alloc(u32, count);
            result = c.ghostty_grid_ref_graphemes(ref, points.ptr, points.len, &count);
        }
        defer if (points.ptr != &stack) gpa.free(points);
        if (result != c.GHOSTTY_SUCCESS) return error.FormatFailed;
        if (count == 0) return text.append(gpa, ' ');
        for (points[0..count]) |cp| {
            var utf8: [4]u8 = undefined;
            const len = try std.unicode.utf8Encode(@intCast(cp), &utf8);
            try text.appendSlice(gpa, utf8[0..len]);
        }
    }

    fn formatTextLocked(self: *Pane, gpa: std.mem.Allocator, selection: ?*c.GhosttySelection, trim: bool) ![]u8 {
        var f: c.GhosttyFormatter = null;
        if (c.ghostty_formatter_terminal_new(null, &f, self.term) != c.GHOSTTY_SUCCESS) return error.OutOfMemory;
        defer c.ghostty_formatter_free(f);
        const fmt: c.GhosttyFormatterFormat = c.GHOSTTY_FORMATTER_FORMAT_PLAIN;
        const yes = true;
        _ = c.ghostty_formatter_set(f, c.GHOSTTY_FORMATTER_OPT_EMIT, &fmt);
        _ = c.ghostty_formatter_set(f, c.GHOSTTY_FORMATTER_OPT_UNWRAP, &yes);
        _ = c.ghostty_formatter_set(f, c.GHOSTTY_FORMATTER_OPT_TRIM, &trim);
        if (selection) |sel| {
            if (c.ghostty_formatter_set(f, c.GHOSTTY_FORMATTER_OPT_SELECTION, sel) != c.GHOSTTY_SUCCESS) return error.FormatFailed;
        }
        var need: usize = 0;
        const r = c.ghostty_formatter_format_buf(f, null, 0, &need);
        if (r == c.GHOSTTY_SUCCESS) return gpa.alloc(u8, 0);
        if (r != c.GHOSTTY_OUT_OF_SPACE) return error.FormatFailed;
        const out = try gpa.alloc(u8, need);
        errdefer gpa.free(out);
        var n: usize = 0;
        if (c.ghostty_formatter_format_buf(f, out.ptr, out.len, &n) != c.GHOSTTY_SUCCESS) return error.FormatFailed;
        if (n < out.len) return gpa.realloc(out, n);
        return out;
    }

    /// The link under the surface point (x, y): an OSC 8 hyperlink, else a URL or path in the
    /// text of the logical line there (the rows a soft wrap joined).
    pub fn linkAt(self: *Pane, gpa: std.mem.Allocator, x: f32, y: f32) !?Link {
        self.lock.lock();
        defer self.lock.unlock();
        return self.linkAtLocked(gpa, x, y);
    }

    /// Underline the link under (x, y) until `unmarkLink`; false when there is none.
    pub fn markLink(self: *Pane, x: f32, y: f32) bool {
        self.lock.lock();
        defer self.lock.unlock();
        const link = (self.linkAtLocked(self.gpa, x, y) catch null) orelse {
            self.setLinkMarkLocked(null);
            return false;
        };
        defer link.deinit(self.gpa);
        self.setLinkMarkLocked(.{ .start = link.start, .end = link.end });
        return true;
    }

    pub fn unmarkLink(self: *Pane) void {
        self.lock.lock();
        defer self.lock.unlock();
        self.setLinkMarkLocked(null);
    }

    fn setLinkMarkLocked(self: *Pane, mark: ?SearchHit) void {
        if (std.meta.eql(self.link_mark, mark)) return;
        self.link_mark = mark;
        self.force_full = true;
        self.wake = true;
        self.lock.signal();
    }

    /// Rows of a logical line read around a tap, each way. A link that runs on past them is no
    /// link: a cut one would open somewhere else (an OAuth URL is 700 bytes, 16 rows on a phone).
    const link_rows: u32 = 64;

    /// One glyph of a logical line: where its text starts and where it is.
    const LineCell = struct { offset: usize, at: c.GhosttyPointCoordinate };

    fn linkAtLocked(self: *Pane, gpa: std.mem.Allocator, x: f32, y: f32) !?Link {
        const hit = self.hitLocked(x, y, false) orelse return null;
        const tapped = self.glyphEdge(hit, true) orelse return null;
        const at = self.coordinate(&tapped, c.GHOSTTY_POINT_TAG_SCREEN) orelse return null;

        var top = at.y;
        while (top > 0 and at.y - top < link_rows and self.rowWraps(top - 1)) top -= 1;
        const cut_top = top > 0 and self.rowWraps(top - 1);
        var bottom = at.y;
        while (bottom - at.y < link_rows and self.rowWraps(bottom)) bottom += 1;
        const cut_bottom = self.rowWraps(bottom);

        var text: std.ArrayList(u8) = .empty;
        defer text.deinit(gpa);
        var cells: std.ArrayList(LineCell) = .empty;
        defer cells.deinit(gpa);
        var tapped_index: ?usize = null;
        const cols = self.termU16(c.GHOSTTY_TERMINAL_DATA_COLS);
        var row = top;
        while (row <= bottom) : (row += 1) {
            for (0..cols) |col| {
                const pt: c.GhosttyPointCoordinate = .{ .x = @intCast(col), .y = row };
                var ref = self.gridRef(c.GHOSTTY_POINT_TAG_SCREEN, pt) orelse break;
                var raw: c.GhosttyCell = undefined;
                var wide: c.GhosttyCellWide = undefined;
                if (c.ghostty_grid_ref_cell(&ref, &raw) != c.GHOSTTY_SUCCESS or
                    c.ghostty_cell_get(raw, c.GHOSTTY_CELL_DATA_WIDE, &wide) != c.GHOSTTY_SUCCESS) return null;
                if (wide == c.GHOSTTY_CELL_WIDE_SPACER_TAIL or wide == c.GHOSTTY_CELL_WIDE_SPACER_HEAD) continue;
                if (pt.x == at.x and pt.y == at.y) tapped_index = cells.items.len;
                try cells.append(gpa, .{ .offset = text.items.len, .at = pt });
                try appendCellText(gpa, &text, &ref);
            }
        }
        const index = tapped_index orelse return null;

        // A hyperlink a program set on the cells comes first: its target can differ from its text.
        const uri = try cellUri(gpa, &tapped);
        defer gpa.free(uri);
        if (uri.len > 0) {
            var first = index;
            while (first > 0 and try self.sameUri(gpa, cells.items[first - 1].at, uri)) first -= 1;
            var last = index;
            while (last + 1 < cells.items.len and try self.sameUri(gpa, cells.items[last + 1].at, uri)) last += 1;
            // What the person reads on those cells, without the blanks around it. A link that
            // runs on past the rows read here is cut, so it does not show its target either.
            const text_end = if (last + 1 < cells.items.len) cells.items[last + 1].offset else text.items.len;
            const label = std.mem.trim(u8, text.items[cells.items[first].offset..text_end], " ");
            return .{
                .kind = Links.classify(uri),
                .target = try gpa.dupe(u8, uri),
                .set = true,
                .shown = std.mem.eql(u8, label, uri),
                .start = cells.items[first].at,
                .end = cells.items[last].at,
            };
        }

        const m = Links.find(text.items, cells.items[index].offset) orelse return null;
        if ((cut_top and m.start == 0) or (cut_bottom and m.end == text.items.len)) return null;
        const target = text.items[m.start..m.target_end];
        return .{
            .kind = m.kind,
            .target = if (m.scheme_missing) try std.mem.concat(gpa, u8, &.{ "http://", target }) else try gpa.dupe(u8, target),
            .line = m.line,
            .col = m.col,
            .start = cells.items[cellOf(cells.items, m.start)].at,
            .end = cells.items[cellOf(cells.items, m.end - 1)].at,
        };
    }

    /// The glyph whose text holds byte `offset` of the line.
    fn cellOf(cells: []const LineCell, offset: usize) usize {
        var i: usize = 0;
        while (i + 1 < cells.len and cells[i + 1].offset <= offset) i += 1;
        return i;
    }

    fn rowWraps(self: *Pane, y: u32) bool {
        const ref = self.gridRef(c.GHOSTTY_POINT_TAG_SCREEN, .{ .x = 0, .y = y }) orelse return false;
        var raw_row: c.GhosttyRow = undefined;
        var wraps = false;
        if (c.ghostty_grid_ref_row(&ref, &raw_row) != c.GHOSTTY_SUCCESS or
            c.ghostty_row_get(raw_row, c.GHOSTTY_ROW_DATA_WRAP, &wraps) != c.GHOSTTY_SUCCESS) return false;
        return wraps;
    }

    /// The OSC 8 URI of a cell, or empty when it has none. Caller frees.
    fn cellUri(gpa: std.mem.Allocator, ref: *const c.GhosttyGridRef) ![]u8 {
        var len: usize = 0;
        if (c.ghostty_grid_ref_hyperlink_uri(ref, null, 0, &len) != c.GHOSTTY_OUT_OF_SPACE) return gpa.alloc(u8, 0);
        const uri = try gpa.alloc(u8, len);
        errdefer gpa.free(uri);
        if (c.ghostty_grid_ref_hyperlink_uri(ref, uri.ptr, uri.len, &len) != c.GHOSTTY_SUCCESS or len != uri.len) {
            gpa.free(uri);
            return gpa.alloc(u8, 0);
        }
        return uri;
    }

    fn sameUri(self: *Pane, gpa: std.mem.Allocator, at: c.GhosttyPointCoordinate, uri: []const u8) !bool {
        const ref = self.gridRef(c.GHOSTTY_POINT_TAG_SCREEN, at) orelse return false;
        var len: usize = 0;
        if (c.ghostty_grid_ref_hyperlink_uri(&ref, null, 0, &len) != c.GHOSTTY_OUT_OF_SPACE or len != uri.len) return false;
        const other = try cellUri(gpa, &ref);
        defer gpa.free(other);
        return std.mem.eql(u8, other, uri);
    }

    /// True when the terminal has a selection, which `copyText` then copies. Without one it
    /// copies the screen, so its text cannot tell the two apart.
    pub fn hasSelection(self: *Pane) bool {
        self.lock.lock();
        defer self.lock.unlock();
        var sel: c.GhosttySelection = .{};
        return c.ghostty_terminal_get(self.term, c.GHOSTTY_TERMINAL_DATA_SELECTION, &sel) == c.GHOSTTY_SUCCESS;
    }

    /// End the selection, if there is one: `copyText` copies the screen again.
    pub fn clearSelection(self: *Pane) void {
        self.lock.lock();
        defer self.lock.unlock();
        self.setSelectionLocked(null);
    }

    pub fn bracketedPaste(self: *Pane) bool {
        self.lock.lock();
        defer self.lock.unlock();
        var query: c.GhosttyTerminalModeConfig = .{ .mode = c.ghostty_mode_new(2004, false), .value = false };
        return c.ghostty_terminal_get(self.term, c.GHOSTTY_TERMINAL_DATA_MODE, &query) == c.GHOSTTY_SUCCESS and query.value;
    }

    /// The cell under the surface point (x, y), when the program in the terminal takes the mouse:
    /// a tap there is its click. Null in the margin, when the program does not take the mouse,
    /// and while the surface shows history instead of the live bottom.
    pub fn mouseCell(self: *Pane, x: f32, y: f32) ?struct { x: u16, y: u16 } {
        self.lock.lock();
        defer self.lock.unlock();
        var mouse = false;
        _ = c.ghostty_terminal_get(self.term, c.GHOSTTY_TERMINAL_DATA_MOUSE_TRACKING, &mouse);
        if (!mouse or !std.math.isFinite(x) or !std.math.isFinite(y)) return null;
        // The program counts rows from the live bottom of the screen. Scrolled back, the rows on
        // the surface are older ones, and a click would land on the wrong row.
        var scroll: c.GhosttyTerminalScrollbar = .{};
        if (c.ghostty_terminal_get(self.term, c.GHOSTTY_TERMINAL_DATA_SCROLLBAR, &scroll) == c.GHOSTTY_SUCCESS and
            scroll.offset + scroll.len < scroll.total) return null;
        const o = self.originLocked();
        const cs = self.cellSizeLocked();
        const col = @floor((x - o.x) / @as(f32, @floatFromInt(cs.w)));
        const row = @floor((y - o.y) / @as(f32, @floatFromInt(cs.h)));
        const cols: f32 = @floatFromInt(self.termU16(c.GHOSTTY_TERMINAL_DATA_COLS));
        const rows: f32 = @floatFromInt(self.termU16(c.GHOSTTY_TERMINAL_DATA_ROWS));
        if (col < 0 or col >= cols or row < 0 or row >= rows) return null;
        return .{ .x = @intFromFloat(col), .y = @intFromFloat(row) };
    }

    /// Encode an Android key event with libghostty-vt's key encoder using the
    /// terminal's current modes. action: 0 down, 1 up, 2 repeat.
    pub fn encodeKey(self: *Pane, out: []u8, key_code: i32, meta_state: i32, unicode: u32, action: u8) []const u8 {
        self.lock.lock();
        defer self.lock.unlock();
        _ = c.ghostty_key_encoder_set_from_terminal(self.encoder, self.term);
        const m = Keys.mapKey(key_code);
        const mods = Keys.mapMods(meta_state);
        const ev = self.key_event;
        _ = c.ghostty_key_event_set_action(ev, Keys.mapAction(action));
        _ = c.ghostty_key_event_set_key(ev, m.key);
        _ = c.ghostty_key_event_set_mods(ev, mods);
        _ = c.ghostty_key_event_set_unshifted_codepoint(ev, m.unshifted);
        _ = c.ghostty_key_event_set_composing(ev, false);

        // Text the key produces. Android reports the shifted character in `unicode`
        // (0 for control/function keys and usually for Ctrl combinations).
        var text_buf: [4]u8 = undefined;
        var text: []const u8 = &.{};
        if (unicode >= 0x20 and unicode != 0x7f and unicode <= 0x10FFFF) {
            const cp: u21 = @intCast(unicode);
            if (std.unicode.utf8CodepointSequenceLength(cp)) |len| {
                _ = std.unicode.utf8Encode(cp, &text_buf) catch 0;
                text = text_buf[0..len];
            } else |_| {}
        }
        _ = c.ghostty_key_event_set_utf8(ev, .{ .ptr = if (text.len > 0) text.ptr else null, .len = text.len });
        // Shift was used to produce the text, so the encoder must not report it again.
        const consumed: c.GhosttyMods = if (text.len > 0) mods & @as(c.GhosttyMods, @intCast(c.GHOSTTY_MODS_SHIFT)) else 0;
        _ = c.ghostty_key_event_set_consumed_mods(ev, consumed);

        var n: usize = 0;
        if (c.ghostty_key_encoder_encode(self.encoder, ev, out.ptr, out.len, &n) != c.GHOSTTY_SUCCESS) return out[0..0];
        return out[0..n];
    }
};
