//! `pane.GlyphSource` over `CoreListener.rasterizeGlyph([BZZIII)[B`. Kotlin draws the
//! glyph with Android fonts and returns tightly packed pixels: A8 coverage
//! (len == w*h) or premultiplied RGBA8888 (len == w*h*4), where w is one cell wide
//! (two when `wide`) and h is the cell height.
const std = @import("std");
const pane = @import("pane");
const jni = @import("jni.zig");
const c = jni.c;

const gpa = std.heap.c_allocator;

/// Holds each render thread's pixel buffer; the destructor frees it when the thread exits.
var buffer_key: std.c.pthread_key_t = undefined;

/// What the app's rasterizer drew, for every core's panes: the app has one rasterizer and one
/// font, whatever remote a core shows. Lives as long as the library.
var shared: pane.GlyphCache = undefined;

/// Call once from `JNI_OnLoad`, before any pane exists.
pub fn init() void {
    _ = std.c.pthread_key_create(&buffer_key, freeThreadBuffer);
    shared = .init(gpa, pane.GlyphCache.default_cap);
}

fn freeThreadBuffer(value: *anyopaque) callconv(.c) void {
    const buffer: *std.ArrayList(u8) = @ptrCast(@alignCast(value));
    buffer.deinit(gpa);
    gpa.destroy(buffer);
}

fn threadBuffer() ?*std.ArrayList(u8) {
    if (std.c.pthread_getspecific(buffer_key)) |value| return @ptrCast(@alignCast(value));
    const buffer = gpa.create(std.ArrayList(u8)) catch return null;
    buffer.* = .empty;
    if (std.c.pthread_setspecific(buffer_key, buffer) != 0) {
        gpa.destroy(buffer);
        return null;
    }
    return buffer;
}

pub const Glyphs = struct {
    /// Global reference to the CoreListener. Owned by jni.zig, which outlives every pane.
    listener: c.jobject,
    rasterize_glyph: c.jmethodID,

    pub fn source(self: *Glyphs) pane.GlyphSource {
        return .{ .ctx = self, .rasterize = rasterize, .cache = &shared };
    }

    /// Runs on a pane's render thread, on atlas misses only. The returned pixels stay
    /// valid until the next call on the same thread.
    fn rasterize(
        ctx: *anyopaque,
        utf8: []const u8,
        bold: bool,
        italic: bool,
        cell_w: u32,
        cell_h: u32,
        columns: u32,
    ) ?pane.GlyphBitmap {
        const self: *Glyphs = @ptrCast(@alignCast(ctx));
        const w = cell_w * columns;
        const h = cell_h;
        if (utf8.len == 0 or w == 0 or h == 0) return null;

        const env = jni.attach() orelse return null;
        if (!jni.pushFrame(env, 4)) return null;
        defer jni.popFrame(env);

        const text = env.*.*.NewByteArray.?(env, @intCast(utf8.len));
        if (jni.clearException(env) or text == null) return null;
        env.*.*.SetByteArrayRegion.?(env, text, 0, @intCast(utf8.len), @ptrCast(utf8.ptr));

        const args = [_]c.jvalue{
            .{ .l = text },
            .{ .z = @intFromBool(bold) },
            .{ .z = @intFromBool(italic) },
            .{ .i = @intCast(cell_w) },
            .{ .i = @intCast(cell_h) },
            .{ .i = @intCast(columns) },
        };
        // Null: the font has no glyph for this text.
        const result = jni.callObject(env, self.listener, self.rasterize_glyph, &args) orelse return null;

        const len: usize = @intCast(env.*.*.GetArrayLength.?(env, result));
        const area = @as(usize, w) * h;
        if (len != area and len != area * 4) {
            jni.log(.warn, "rasterizeGlyph returned {d} bytes for a {d}x{d} glyph", .{ len, w, h });
            return null;
        }

        const buffer = threadBuffer() orelse return null;
        buffer.resize(gpa, len) catch return null;
        env.*.*.GetByteArrayRegion.?(env, result, 0, @intCast(len), @ptrCast(buffer.items.ptr));
        if (jni.clearException(env)) return null;
        return .{ .pixels = buffer.items, .w = w, .h = h };
    }
};
