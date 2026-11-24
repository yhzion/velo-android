//! Glyph atlas bookkeeping (no GL). Two planes: an A8 coverage plane for
//! monochrome glyphs and procedural box drawing, and an RGBA (premultiplied)
//! plane for color glyphs. Each plane grows by adding fixed-size pages; the
//! renderer mirrors pages into GL texture-array layers and re-uploads pages
//! flagged dirty.
const std = @import("std");

pub const page_size: u32 = 1024;

pub const Slot = struct {
    page: u16 = 0,
    x: u16 = 0,
    y: u16 = 0,
    w: u16 = 0,
    h: u16 = 0,
    /// RGBA plane when true, A8 plane otherwise.
    color: bool = false,

    pub fn empty(s: Slot) bool {
        return s.w == 0 or s.h == 0;
    }
};

pub const Plane = struct {
    bpp: u32,
    pages: std.ArrayList([]u8) = .empty,
    dirty: std.ArrayList(bool) = .empty,
    x: u32 = 0,
    y: u32 = 0,
    shelf: u32 = 0,

    fn deinit(p: *Plane, gpa: std.mem.Allocator) void {
        for (p.pages.items) |pg| gpa.free(pg);
        p.pages.deinit(gpa);
        p.dirty.deinit(gpa);
        p.* = .{ .bpp = p.bpp };
    }

    fn addPage(p: *Plane, gpa: std.mem.Allocator) !void {
        const pg = try gpa.alloc(u8, page_size * page_size * p.bpp);
        @memset(pg, 0);
        errdefer gpa.free(pg);
        try p.pages.append(gpa, pg);
        try p.dirty.append(gpa, true);
        p.x = 0;
        p.y = 0;
        p.shelf = 0;
    }

    /// Shelf packing with a 1px gutter.
    fn place(p: *Plane, gpa: std.mem.Allocator, w: u32, h: u32) !?Slot {
        if (w + 1 > page_size or h + 1 > page_size) return null;
        if (p.pages.items.len == 0) try p.addPage(gpa);
        if (p.x + w + 1 > page_size) {
            p.y += p.shelf;
            p.x = 0;
            p.shelf = 0;
        }
        if (p.y + h + 1 > page_size) try p.addPage(gpa);
        const s: Slot = .{
            .page = @intCast(p.pages.items.len - 1),
            .x = @intCast(p.x),
            .y = @intCast(p.y),
            .w = @intCast(w),
            .h = @intCast(h),
        };
        p.x += w + 1;
        p.shelf = @max(p.shelf, h + 1);
        return s;
    }

    fn blit(p: *Plane, s: Slot, pixels: []const u8) void {
        const pg = p.pages.items[s.page];
        const row_bytes = @as(usize, s.w) * p.bpp;
        var r: usize = 0;
        while (r < s.h) : (r += 1) {
            const dst = ((@as(usize, s.y) + r) * page_size + s.x) * p.bpp;
            @memcpy(pg[dst..][0..row_bytes], pixels[r * row_bytes ..][0..row_bytes]);
        }
        p.dirty.items[s.page] = true;
    }
};

pub const Atlas = struct {
    gpa: std.mem.Allocator,
    map: std.StringHashMapUnmanaged(Slot) = .empty,
    mask: Plane = .{ .bpp = 1 },
    color: Plane = .{ .bpp = 4 },

    pub fn init(gpa: std.mem.Allocator) Atlas {
        return .{ .gpa = gpa };
    }

    pub fn deinit(a: *Atlas) void {
        a.clearMap();
        a.map.deinit(a.gpa);
        a.mask.deinit(a.gpa);
        a.color.deinit(a.gpa);
    }

    fn clearMap(a: *Atlas) void {
        var it = a.map.keyIterator();
        while (it.next()) |k| a.gpa.free(k.*);
        a.map.clearRetainingCapacity();
    }

    /// Drop every glyph (the cell size they were drawn for changed). The pages go too: the
    /// renderer allocates its texture arrays again for the pages that follow.
    pub fn reset(a: *Atlas) void {
        a.clearMap();
        a.mask.deinit(a.gpa);
        a.color.deinit(a.gpa);
    }

    pub fn get(a: *const Atlas, key: []const u8) ?Slot {
        return a.map.get(key);
    }

    /// Store a bitmap under `key`. `pixels == null` records a negative entry so
    /// the glyph source is not asked again. A8 when len == w*h, RGBA when w*h*4.
    pub fn put(a: *Atlas, key: []const u8, pixels: ?[]const u8, w: u32, h: u32) !Slot {
        var slot: Slot = .{};
        if (pixels) |px| if (w > 0 and h > 0) {
            const n = @as(usize, w) * h;
            const plane: ?*Plane = if (px.len == n) &a.mask else if (px.len == n * 4) &a.color else null;
            if (plane) |p| if (try p.place(a.gpa, w, h)) |s| {
                slot = s;
                slot.color = p == &a.color;
                p.blit(slot, px);
            };
        };
        const owned = try a.gpa.dupe(u8, key);
        errdefer a.gpa.free(owned);
        try a.map.put(a.gpa, owned, slot);
        return slot;
    }
};

/// Atlas key: grapheme UTF-8 followed by one flag byte.
pub const Flags = packed struct(u8) {
    bold: bool = false,
    italic: bool = false,
    wide: bool = false,
    box: bool = false,
    _pad: u4 = 0,
};

/// Caller provides room for the entire glyph and one flags byte. A prefix is not a key.
pub fn makeKey(buf: []u8, utf8: []const u8, flags: Flags) []const u8 {
    std.debug.assert(utf8.len < buf.len);
    @memcpy(buf[0..utf8.len], utf8);
    buf[utf8.len] = @bitCast(flags);
    return buf[0 .. utf8.len + 1];
}
