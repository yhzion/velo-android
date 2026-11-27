//! What the host tests of panes and render threads share (test_pane.zig, Renderer.zig's tests).
const std = @import("std");
const P = @import("Pane.zig");

/// A glyph source with its own allocation and reference count, as sys/glyphs.zig's (#178). It
/// starts with its owner's reference (jni.zig's `Handle`), and the last `drop` frees it. Made
/// with `Unmapping`, a read of it once freed faults. What the tests see of it is in its `Watch`,
/// which outlives it.
pub const CountedSource = struct {
    refs: P.GlyphSource.Refs = .{ .free = free },
    gpa: std.mem.Allocator,
    watch: *Watch,
    /// Read after the call's wait, as glyphs.zig reads the listener and its method for
    /// `CallObjectMethodA` after `NewByteArray`, which can wait for a GC.
    listener: usize = listener_value,
    method: usize = method_value,

    const listener_value = 0x4c15;
    const method_value = 0x3e7d;

    /// Every glyph is a full cell of ink.
    var ink: [96 * 96 * 3]u8 = @splat(255);

    pub const Watch = struct {
        lock: P.Lock = .{},
        calls: usize = 0,
        frees: usize = 0,
        /// The first call waits, before it reads the source, until `letGo`.
        hold_first: bool = false,
        /// The first call is waiting.
        held: bool = false,
        /// How long each call draws.
        glyph_ns: u64 = 0,

        pub fn callsNow(w: *Watch) usize {
            w.lock.lock();
            defer w.lock.unlock();
            return w.calls;
        }

        pub fn freesNow(w: *Watch) usize {
            w.lock.lock();
            defer w.lock.unlock();
            return w.frees;
        }

        /// Wait until the first call waits, 5 s at most.
        pub fn waitHeld(w: *Watch) !void {
            w.lock.lock();
            defer w.lock.unlock();
            for (0..500) |_| {
                if (w.held) return;
                _ = w.lock.timedWait(10 * std.time.ns_per_ms);
            }
            if (!w.held) return error.TestUnexpectedResult;
        }

        /// Wait until the source was called `n` times, 5 s at most.
        pub fn waitCalls(w: *Watch, n: usize) !void {
            for (0..5000) |_| {
                if (w.callsNow() >= n) return;
                sleepNs(std.time.ns_per_ms);
            }
            return error.TestUnexpectedResult;
        }

        /// The first call goes on, and every later one draws at once.
        pub fn letGo(w: *Watch) void {
            w.lock.lock();
            defer w.lock.unlock();
            w.hold_first = false;
            w.glyph_ns = 0;
            w.lock.signal();
        }
    };

    pub fn create(gpa: std.mem.Allocator, watch: *Watch) !*CountedSource {
        const s = try gpa.create(CountedSource);
        s.* = .{ .gpa = gpa, .watch = watch };
        return s;
    }

    pub fn source(s: *CountedSource) P.GlyphSource {
        return .{ .ctx = s, .rasterize = raster, .refs = &s.refs };
    }

    fn free(refs: *P.GlyphSource.Refs) void {
        const s: *CountedSource = @fieldParentPtr("refs", refs);
        const w = s.watch;
        s.gpa.destroy(s);
        w.lock.lock();
        defer w.lock.unlock();
        w.frees += 1;
    }

    fn raster(ctx: *anyopaque, _: []const u8, _: bool, _: bool, w: u32, h: u32, columns: u32) ?P.GlyphBitmap {
        const s: *CountedSource = @ptrCast(@alignCast(ctx));
        // Read while the source is alive: the call has not waited yet.
        const watch = s.watch;
        watch.lock.lock();
        watch.calls += 1;
        if (watch.calls == 1 and watch.hold_first) {
            watch.held = true;
            watch.lock.signal();
            while (watch.hold_first) watch.lock.wait();
        }
        const glyph_ns = watch.glyph_ns;
        watch.lock.unlock();
        if (glyph_ns > 0) sleepNs(glyph_ns);
        // What glyphs.zig reads after its wait: on a freed source this faults.
        if (s.listener != listener_value or s.method != method_value) @panic("the glyph source was overwritten");
        return .{ .w = w * columns, .h = h, .pixels = ink[0 .. w * h * columns] };
    }
};

/// A test's allocator whose every allocation has pages of its own, unmapped when freed: a use
/// after free faults at once (the test allocator unmaps only large ones). It counts what is
/// still allocated, so a leak shows too.
pub const Unmapping = struct {
    live: std.atomic.Value(usize) = .init(0),

    pub fn allocator(u: *Unmapping) std.mem.Allocator {
        return .{ .ptr = u, .vtable = &vtable };
    }

    const vtable: std.mem.Allocator.VTable = .{ .alloc = alloc, .resize = resize, .remap = remap, .free = free };
    const pages = std.heap.page_allocator;

    fn alloc(ctx: *anyopaque, len: usize, alignment: std.mem.Alignment, ra: usize) ?[*]u8 {
        const u: *Unmapping = @ptrCast(@alignCast(ctx));
        const ptr = pages.rawAlloc(len, alignment, ra) orelse return null;
        _ = u.live.fetchAdd(1, .monotonic);
        return ptr;
    }

    fn resize(_: *anyopaque, memory: []u8, alignment: std.mem.Alignment, new_len: usize, ra: usize) bool {
        return pages.rawResize(memory, alignment, new_len, ra);
    }

    fn remap(_: *anyopaque, memory: []u8, alignment: std.mem.Alignment, new_len: usize, ra: usize) ?[*]u8 {
        return pages.rawRemap(memory, alignment, new_len, ra);
    }

    fn free(ctx: *anyopaque, memory: []u8, alignment: std.mem.Alignment, ra: usize) void {
        const u: *Unmapping = @ptrCast(@alignCast(ctx));
        pages.rawFree(memory, alignment, ra);
        _ = u.live.fetchSub(1, .monotonic);
    }
};

pub fn sleepNs(ns: u64) void {
    var ts: std.c.timespec = .{ .sec = @intCast(ns / std.time.ns_per_s), .nsec = @intCast(ns % std.time.ns_per_s) };
    _ = std.c.nanosleep(&ts, null);
}
