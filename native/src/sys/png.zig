//! Android's API 30 image decoder for Ghostty's process-global PNG callback.
const std = @import("std");
const c = @import("c");

const Decoder = opaque {};
const Header = opaque {};
const rgba_8888 = 1; // ANDROID_BITMAP_FORMAT_RGBA_8888
extern "jnigraphics" fn AImageDecoder_createFromBuffer(data: *const anyopaque, len: usize, decoder: *?*Decoder) c_int;
extern "jnigraphics" fn AImageDecoder_delete(decoder: *Decoder) void;
extern "jnigraphics" fn AImageDecoder_getHeaderInfo(decoder: *const Decoder) *const Header;
extern "jnigraphics" fn AImageDecoderHeaderInfo_getWidth(header: *const Header) i32;
extern "jnigraphics" fn AImageDecoderHeaderInfo_getHeight(header: *const Header) i32;
extern "jnigraphics" fn AImageDecoder_setAndroidBitmapFormat(decoder: *Decoder, format: i32) c_int;
extern "jnigraphics" fn AImageDecoder_setUnpremultipliedRequired(decoder: *Decoder, required: bool) c_int;
extern "jnigraphics" fn AImageDecoder_decodeImage(decoder: *Decoder, pixels: *anyopaque, stride: usize, size: usize) c_int;

/// ghostty_sys_set is not thread-safe: register once in JNI_OnLoad, never per pane.
pub fn register() bool {
    const callback: c.GhosttySysDecodePngFn = &decode;
    return c.ghostty_sys_set(c.GHOSTTY_SYS_OPT_DECODE_PNG, @ptrCast(callback)) == c.GHOSTTY_SUCCESS;
}

fn decode(
    _: ?*anyopaque,
    allocator: [*c]const c.GhosttyAllocator,
    data: [*c]const u8,
    len: usize,
    out: [*c]c.GhosttySysImage,
) callconv(.c) bool {
    // AImageDecoder sniffs many formats. Kitty f=100 specifically means PNG.
    if (data == null or out == null or !std.mem.startsWith(u8, data[0..len], "\x89PNG\r\n\x1a\n")) return false;

    var handle: ?*Decoder = null;
    if (AImageDecoder_createFromBuffer(data, len, &handle) != 0) return false;
    const decoder = handle orelse return false;
    defer AImageDecoder_delete(decoder);

    const header = AImageDecoder_getHeaderInfo(decoder);
    const width = AImageDecoderHeaderInfo_getWidth(header);
    const height = AImageDecoderHeaderInfo_getHeight(header);
    // Ghostty caps either axis at 10,000. This also makes both products below
    // fit in a 32-bit usize. Bound the Android pixel allocation separately.
    const max_dimension = 10_000;
    if (width <= 0 or height <= 0 or width > max_dimension or height > max_dimension) return false;
    const stride = @as(usize, @intCast(width)) * 4;
    const size = stride * @as(usize, @intCast(height));
    if (size > 64 * 1024 * 1024) return false;

    // Renderer.imageTexture premultiplies once, when preparing the GL texture.
    if (AImageDecoder_setAndroidBitmapFormat(decoder, rgba_8888) != 0 or
        AImageDecoder_setUnpremultipliedRequired(decoder, true) != 0) return false;

    const pixels = c.ghostty_alloc(allocator, size);
    if (pixels == null) return false;
    if (AImageDecoder_decodeImage(decoder, pixels, stride, size) != 0) {
        // Even an incomplete decode can have written pixels. Never publish them.
        c.ghostty_free(allocator, pixels, size);
        return false;
    }
    out.* = .{ .width = @intCast(width), .height = @intCast(height), .data = pixels, .data_len = size };
    return true; // Ghostty now owns pixels.
}
