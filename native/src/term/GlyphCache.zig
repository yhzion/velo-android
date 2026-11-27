//! The glyph bitmaps the glyph source drew, shared by every pane's render thread. A render
//! thread starts with an empty atlas whenever its pane gets a window (a tab shown, an
//! overview card, the app back on the screen), and each pane has an atlas of its own; without
//! this, every one of them asked the glyph source again for each glyph it showed. The atlas
//! stays the render thread's: a glyph found here is copied into it.
//!
//! A bitmap is kept under the atlas key (the text and its flags byte) and the size it was
//! drawn at. Another font drops them all (`invalidate`); past `cap` bytes, everything kept goes
//! before the next bitmap is kept.
const std = @import("std");

const GlyphCache = @This();

lock: std.c.pthread_mutex_t = .{},
/// Keys and pixels; reset whenever everything kept goes.
arena: std.heap.ArenaAllocator,
map: std.HashMapUnmanaged(Key, Bitmap, KeyContext, std.hash_map.default_max_load_percentage) = .empty,
/// What the kept bitmaps and their keys take.
bytes: usize = 0,
cap: usize,
/// Advances on another font: a bitmap drawn before it is not kept (`keep`).
epoch: u64 = 0,

/// What a render thread keeps when its glyph source is the app's.
pub const default_cap: usize = 8 << 20;

const Key = struct { atlas_key: []const u8, cell_w: u32, cell_h: u32, columns: u32 };

const KeyContext = struct {
    pub fn hash(_: KeyContext, k: Key) u64 {
        var h = std.hash.Wyhash.init(0);
        h.update(k.atlas_key);
        h.update(std.mem.asBytes(&[3]u32{ k.cell_w, k.cell_h, k.columns }));
        return h.final();
    }
    pub fn eql(_: KeyContext, a: Key, b: Key) bool {
        return a.cell_w == b.cell_w and a.cell_h == b.cell_h and a.columns == b.columns and
            std.mem.eql(u8, a.atlas_key, b.atlas_key);
    }
};

const Bitmap = struct { pixels: []const u8, w: u32, h: u32 };

pub const Size = struct { w: u32, h: u32 };

pub fn init(gpa: std.mem.Allocator, cap: usize) GlyphCache {
    return .{ .arena = .init(gpa), .cap = cap };
}

pub fn deinit(self: *GlyphCache) void {
    self.map.deinit(self.arena.child_allocator);
    self.arena.deinit();
}

/// The epoch to give `keep` for a glyph about to be drawn: read it before drawing.
pub fn epochNow(self: *GlyphCache) u64 {
    _ = std.c.pthread_mutex_lock(&self.lock);
    defer _ = std.c.pthread_mutex_unlock(&self.lock);
    return self.epoch;
}

/// Copies into `out` the bitmap kept for `atlas_key` at that size, and gives its size; null
/// when there is none. The copy is the caller's: nothing kept is borrowed past the lock.
pub fn find(self: *GlyphCache, atlas_key: []const u8, cell_w: u32, cell_h: u32, columns: u32, gpa: std.mem.Allocator, out: *std.ArrayList(u8)) ?Size {
    _ = std.c.pthread_mutex_lock(&self.lock);
    defer _ = std.c.pthread_mutex_unlock(&self.lock);
    const b = self.map.get(.{ .atlas_key = atlas_key, .cell_w = cell_w, .cell_h = cell_h, .columns = columns }) orelse return null;
    out.clearRetainingCapacity();
    out.appendSlice(gpa, b.pixels) catch return null;
    return .{ .w = b.w, .h = b.h };
}

/// Keeps a copy of a bitmap the glyph source drew for `atlas_key` at that size, unless another
/// font came since `epoch` (`epochNow` before it was drawn): the bitmap may be of the old one.
/// A bitmap kept already stays as it is. Out of memory, nothing is kept.
pub fn keep(self: *GlyphCache, epoch: u64, atlas_key: []const u8, cell_w: u32, cell_h: u32, columns: u32, pixels: []const u8, w: u32, h: u32) void {
    _ = std.c.pthread_mutex_lock(&self.lock);
    defer _ = std.c.pthread_mutex_unlock(&self.lock);
    if (epoch != self.epoch) return;
    const key: Key = .{ .atlas_key = atlas_key, .cell_w = cell_w, .cell_h = cell_h, .columns = columns };
    if (self.map.contains(key)) return;
    const size = pixels.len + atlas_key.len;
    if (size > self.cap) return;
    if (self.bytes + size > self.cap) self.clearLocked();
    const gpa = self.arena.child_allocator;
    const arena = self.arena.allocator();
    const owned_key = arena.dupe(u8, atlas_key) catch return;
    const owned_pixels = arena.dupe(u8, pixels) catch return;
    self.map.put(gpa, .{ .atlas_key = owned_key, .cell_w = cell_w, .cell_h = cell_h, .columns = columns }, .{ .pixels = owned_pixels, .w = w, .h = h }) catch return;
    self.bytes += size;
}

/// The glyph source draws another font: everything kept goes, and a bitmap drawn before
/// this is not kept.
pub fn invalidate(self: *GlyphCache) void {
    _ = std.c.pthread_mutex_lock(&self.lock);
    defer _ = std.c.pthread_mutex_unlock(&self.lock);
    self.epoch += 1;
    self.clearLocked();
}

fn clearLocked(self: *GlyphCache) void {
    self.map.clearRetainingCapacity();
    _ = self.arena.reset(.free_all);
    self.bytes = 0;
}

test "a bitmap kept is found at its size, a copy of it" {
    const gpa = std.testing.allocator;
    var cache: GlyphCache = .init(gpa, default_cap);
    defer cache.deinit();
    var out: std.ArrayList(u8) = .empty;
    defer out.deinit(gpa);
    try std.testing.expect(cache.find("a\x00", 6, 12, 1, gpa, &out) == null);
    var pixels: [6 * 12]u8 = @splat(7);
    cache.keep(cache.epochNow(), "a\x00", 6, 12, 1, &pixels, 6, 12);
    pixels[0] = 0; // what the glyph source returned is only lent
    const size = cache.find("a\x00", 6, 12, 1, gpa, &out).?;
    try std.testing.expectEqual(Size{ .w = 6, .h = 12 }, size);
    try std.testing.expectEqual(@as(usize, 6 * 12), out.items.len);
    try std.testing.expectEqual(@as(u8, 7), out.items[0]);
    // Another size, another span of columns, another flags byte: none of them is this one.
    try std.testing.expect(cache.find("a\x00", 7, 12, 1, gpa, &out) == null);
    try std.testing.expect(cache.find("a\x00", 6, 14, 1, gpa, &out) == null);
    try std.testing.expect(cache.find("a\x00", 6, 12, 2, gpa, &out) == null);
    try std.testing.expect(cache.find("a\x01", 6, 12, 1, gpa, &out) == null);
}

test "another font: what was kept goes, and a bitmap drawn before it is not kept" {
    const gpa = std.testing.allocator;
    var cache: GlyphCache = .init(gpa, default_cap);
    defer cache.deinit();
    var out: std.ArrayList(u8) = .empty;
    defer out.deinit(gpa);
    const pixels: [4]u8 = @splat(1);
    cache.keep(cache.epochNow(), "a\x00", 2, 2, 1, &pixels, 2, 2);
    // A render thread reads the epoch and draws "b" while the font changes.
    const before = cache.epochNow();
    cache.invalidate();
    try std.testing.expect(cache.find("a\x00", 2, 2, 1, gpa, &out) == null);
    cache.keep(before, "b\x00", 2, 2, 1, &pixels, 2, 2);
    try std.testing.expect(cache.find("b\x00", 2, 2, 1, gpa, &out) == null);
    cache.keep(cache.epochNow(), "b\x00", 2, 2, 1, &pixels, 2, 2);
    try std.testing.expect(cache.find("b\x00", 2, 2, 1, gpa, &out) != null);
}

test "past the cap, everything kept goes before the next bitmap" {
    const gpa = std.testing.allocator;
    // Room for two bitmaps of 8 bytes under 2-byte keys, not three.
    var cache: GlyphCache = .init(gpa, 25);
    defer cache.deinit();
    var out: std.ArrayList(u8) = .empty;
    defer out.deinit(gpa);
    const pixels: [8]u8 = @splat(1);
    cache.keep(0, "a\x00", 2, 4, 1, &pixels, 2, 4);
    cache.keep(0, "b\x00", 2, 4, 1, &pixels, 2, 4);
    try std.testing.expectEqual(@as(usize, 20), cache.bytes);
    cache.keep(0, "c\x00", 2, 4, 1, &pixels, 2, 4);
    try std.testing.expect(cache.find("a\x00", 2, 4, 1, gpa, &out) == null);
    try std.testing.expect(cache.find("c\x00", 2, 4, 1, gpa, &out) != null);
    try std.testing.expectEqual(@as(usize, 10), cache.bytes);
    // One bitmap larger than the whole cap is not kept, and nothing goes for it.
    const big: [32]u8 = @splat(1);
    cache.keep(0, "d\x00", 4, 8, 1, &big, 4, 8);
    try std.testing.expect(cache.find("c\x00", 2, 4, 1, gpa, &out) != null);
}
