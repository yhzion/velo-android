//! Procedural box drawing (U+2500–U+257F) and block elements (U+2580–U+259F).
//! Glyphs are rasterized into an A8 coverage bitmap exactly one cell (or two
//! cells for wide) in size, so the quads tile edge to edge with no gaps between
//! rows or columns. Fonts are never consulted for this range.
const std = @import("std");

pub const Kind = enum { none, lines, dash, arc, diagonal, block };

/// Line weights per direction, packed as 0xURDL (0 none, 1 light, 2 heavy, 3 double).
/// Generated from Ghostty's src/font/sprite/draw/box.zig.
const line_table = [128]u16{
    0x0101, 0x0202, 0x1010, 0x2020, 0x0000, 0x0000, 0x0000, 0x0000,
    0x0000, 0x0000, 0x0000, 0x0000, 0x0110, 0x0210, 0x0120, 0x0220,
    0x0011, 0x0012, 0x0021, 0x0022, 0x1100, 0x1200, 0x2100, 0x2200,
    0x1001, 0x1002, 0x2001, 0x2002, 0x1110, 0x1210, 0x2110, 0x1120,
    0x2120, 0x2210, 0x1220, 0x2220, 0x1011, 0x1012, 0x2011, 0x1021,
    0x2021, 0x2012, 0x1022, 0x2022, 0x0111, 0x0112, 0x0211, 0x0212,
    0x0121, 0x0122, 0x0221, 0x0222, 0x1101, 0x1102, 0x1201, 0x1202,
    0x2101, 0x2102, 0x2201, 0x2202, 0x1111, 0x1112, 0x1211, 0x1212,
    0x2111, 0x1121, 0x2121, 0x2112, 0x2211, 0x1122, 0x1221, 0x2212,
    0x1222, 0x2122, 0x2221, 0x2222, 0x0000, 0x0000, 0x0000, 0x0000,
    0x0303, 0x3030, 0x0310, 0x0130, 0x0330, 0x0013, 0x0031, 0x0033,
    0x1300, 0x3100, 0x3300, 0x1003, 0x3001, 0x3003, 0x1310, 0x3130,
    0x3330, 0x1013, 0x3031, 0x3033, 0x0313, 0x0131, 0x0333, 0x1303,
    0x3101, 0x3303, 0x1313, 0x3131, 0x3333, 0x0000, 0x0000, 0x0000,
    0x0000, 0x0000, 0x0000, 0x0000, 0x0001, 0x1000, 0x0100, 0x0010,
    0x0002, 0x2000, 0x0200, 0x0020, 0x0201, 0x1020, 0x0102, 0x2010,
};

pub fn isProcedural(cp: u32) bool {
    return cp >= 0x2500 and cp <= 0x259F;
}

pub fn kind(cp: u32) Kind {
    if (!isProcedural(cp)) return .none;
    if (cp >= 0x2580) return .block;
    return switch (cp) {
        0x2504...0x250B, 0x254C...0x254F => .dash,
        0x256D...0x2570 => .arc,
        0x2571...0x2573 => .diagonal,
        else => .lines,
    };
}

/// Weights (up, right, down, left) for `.lines` code points.
pub fn lineWeights(cp: u32) [4]u2 {
    if (cp < 0x2500 or cp >= 0x2580) return .{ 0, 0, 0, 0 };
    const v = line_table[cp - 0x2500];
    return .{ @intCast(v >> 12 & 3), @intCast(v >> 8 & 3), @intCast(v >> 4 & 3), @intCast(v & 3) };
}

const Canvas = struct {
    px: []u8,
    w: i32,
    h: i32,

    fn rect(c: Canvas, x0: i32, y0: i32, x1: i32, y1: i32, v: u8) void {
        const ax = std.math.clamp(@min(x0, x1), 0, c.w);
        const bx = std.math.clamp(@max(x0, x1), 0, c.w);
        const ay = std.math.clamp(@min(y0, y1), 0, c.h);
        const by = std.math.clamp(@max(y0, y1), 0, c.h);
        var y = ay;
        while (y < by) : (y += 1) {
            var x = ax;
            while (x < bx) : (x += 1) {
                const i: usize = @intCast(y * c.w + x);
                c.px[i] = @max(c.px[i], v);
            }
        }
    }

    fn fill(c: Canvas, x0: f32, y0: f32, x1: f32, y1: f32) void {
        c.rect(@intFromFloat(@round(x0)), @intFromFloat(@round(y0)), @intFromFloat(@round(x1)), @intFromFloat(@round(y1)), 255);
    }
};

/// Rasterize `cp` into `out` (len >= w*h), A8 coverage. Returns false when the
/// code point is not procedural.
pub fn render(cp: u32, out: []u8, w: u32, h: u32) bool {
    if (!isProcedural(cp) or w == 0 or h == 0) return false;
    const c: Canvas = .{ .px = out[0 .. w * h], .w = @intCast(w), .h = @intCast(h) };
    @memset(c.px, 0);
    const light: i32 = @max(1, @as(i32, @intFromFloat(@round(@as(f32, @floatFromInt(h)) / 16.0))));
    switch (kind(cp)) {
        .lines => lines(c, lineWeights(cp), light),
        .dash => dash(c, cp, light),
        .arc => arc(c, cp, light),
        .diagonal => diagonal(c, cp, light),
        .block => block(c, cp),
        .none => return false,
    }
    return true;
}

fn lines(c: Canvas, wt: [4]u2, light: i32) void {
    const up, const right, const down, const left = wt;
    const heavy = light * 2;
    const cx = @divFloor(c.w, 2);
    const cy = @divFloor(c.h, 2);
    const thick = struct {
        fn t(x: u2, l: i32, hv: i32) i32 {
            return switch (x) {
                0 => 0,
                1 => l,
                2 => hv,
                3 => l,
            };
        }
    }.t;
    // Double lines sit `light` away from the center on each side.
    const gap = light;
    // Extent of the horizontal / vertical strokes at the center so joins meet.
    const hv_w = @max(thick(up, light, heavy), thick(down, light, heavy));
    const hh_w = @max(thick(left, light, heavy), thick(right, light, heavy));
    const any_dbl_v = up == 3 or down == 3;
    const any_dbl_h = left == 3 or right == 3;
    const v_half: i32 = if (any_dbl_v) gap + light else @divFloor(hv_w + 1, 2);
    const h_half: i32 = if (any_dbl_h) gap + light else @divFloor(hh_w + 1, 2);

    // Vertical arms.
    inline for (.{ .{ up, true }, .{ down, false } }) |arm| {
        const wgt: u2 = arm[0];
        if (wgt != 0) {
            const y0: i32 = if (arm[1]) 0 else cy - h_half;
            const y1: i32 = if (arm[1]) cy + h_half + light else c.h;
            if (wgt == 3) {
                const ya: i32 = if (arm[1]) 0 else (if (any_dbl_h) cy - gap else cy - h_half);
                const yb: i32 = if (arm[1]) (if (any_dbl_h) cy + gap + light else cy + h_half + light) else c.h;
                c.rect(cx - gap - light, if (arm[1]) 0 else (if (any_dbl_h) cy + gap else ya), cx - gap, if (arm[1]) (if (any_dbl_h) cy - gap + light else yb) else c.h, 255);
                c.rect(cx + gap, ya, cx + gap + light, yb, 255);
            } else {
                const t = thick(wgt, light, heavy);
                c.rect(cx - @divFloor(t, 2), y0, cx - @divFloor(t, 2) + t, y1, 255);
            }
        }
    }
    // Horizontal arms.
    inline for (.{ .{ left, true }, .{ right, false } }) |arm| {
        const wgt: u2 = arm[0];
        if (wgt != 0) {
            const x0: i32 = if (arm[1]) 0 else cx - v_half;
            const x1: i32 = if (arm[1]) cx + v_half + light else c.w;
            if (wgt == 3) {
                const xa: i32 = if (arm[1]) 0 else (if (any_dbl_v) cx - gap else cx - v_half);
                const xb: i32 = if (arm[1]) (if (any_dbl_v) cx + gap + light else cx + v_half + light) else c.w;
                c.rect(if (arm[1]) 0 else (if (any_dbl_v) cx + gap else xa), cy - gap - light, if (arm[1]) (if (any_dbl_v) cx - gap + light else xb) else c.w, cy - gap, 255);
                c.rect(xa, cy + gap, xb, cy + gap + light, 255);
            } else {
                const t = thick(wgt, light, heavy);
                c.rect(x0, cy - @divFloor(t, 2), x1, cy - @divFloor(t, 2) + t, 255);
            }
        }
    }
}

fn dash(c: Canvas, cp: u32, light: i32) void {
    const heavy = (cp & 1) == 1;
    const t: i32 = if (heavy) light * 2 else light;
    const count: i32 = switch (cp) {
        0x2504...0x2507 => 3,
        0x2508...0x250B => 4,
        else => 2,
    };
    const vertical = switch (cp) {
        0x2506, 0x2507, 0x250A, 0x250B, 0x254E, 0x254F => true,
        else => false,
    };
    const len = if (vertical) c.h else c.w;
    // Each segment owns len/count pixels; the gap is centered so dashes tile across cells.
    var i: i32 = 0;
    while (i < count) : (i += 1) {
        const s = @divFloor(len * i, count);
        const e = @divFloor(len * (i + 1), count);
        const g = @max(1, @divFloor(e - s, 4));
        const a = s + @divFloor(g, 2);
        const b = e - (g - @divFloor(g, 2));
        if (vertical) {
            const cx = @divFloor(c.w, 2) - @divFloor(t, 2);
            c.rect(cx, a, cx + t, b, 255);
        } else {
            const cy = @divFloor(c.h, 2) - @divFloor(t, 2);
            c.rect(a, cy, b, cy + t, 255);
        }
    }
}

/// ╭ ╮ ╯ ╰: a quarter circle joining the two cell-edge midpoints, with straight
/// tails so the stroke reaches the cell edges exactly.
fn arc(c: Canvas, cp: u32, light: i32) void {
    const w: f32 = @floatFromInt(c.w);
    const h: f32 = @floatFromInt(c.h);
    const t: f32 = @floatFromInt(light);
    const cx = @as(f32, @floatFromInt(@divFloor(c.w, 2))) - @as(f32, @floatFromInt(@divFloor(light, 2))) + t / 2;
    const cy = @as(f32, @floatFromInt(@divFloor(c.h, 2))) - @as(f32, @floatFromInt(@divFloor(light, 2))) + t / 2;
    const r = @min(w, h) / 2;
    // Arc center offset toward the open quadrant.
    const right = cp == 0x256D or cp == 0x2570; // ╭ ╰ go right
    const down = cp == 0x256D or cp == 0x256E; // ╭ ╮ go down
    const ox: f32 = if (right) cx + r else cx - r;
    const oy: f32 = if (down) cy + r else cy - r;
    var y: i32 = 0;
    while (y < c.h) : (y += 1) {
        var x: i32 = 0;
        while (x < c.w) : (x += 1) {
            const fx = @as(f32, @floatFromInt(x)) + 0.5;
            const fy = @as(f32, @floatFromInt(y)) + 0.5;
            const dx = fx - ox;
            const dy = fy - oy;
            // Restrict to the quadrant facing the cell center.
            const in_q = (if (right) dx <= 0 else dx >= 0) and (if (down) dy <= 0 else dy >= 0);
            if (!in_q) continue;
            const d = @abs(@sqrt(dx * dx + dy * dy) - r);
            const cov = std.math.clamp(t / 2 + 0.5 - d, 0, 1);
            if (cov > 0) {
                const i: usize = @intCast(y * c.w + x);
                c.px[i] = @max(c.px[i], @as(u8, @intFromFloat(cov * 255)));
            }
        }
    }
    // Straight tails from the arc ends to the cell edges.
    const half = t / 2;
    if (right) c.fill(ox, cy - half, w, cy + half) else c.fill(0, cy - half, ox, cy + half);
    if (down) c.fill(cx - half, oy, cx + half, h) else c.fill(cx - half, 0, cx + half, oy);
}

fn diagonal(c: Canvas, cp: u32, light: i32) void {
    const w: f32 = @floatFromInt(c.w);
    const h: f32 = @floatFromInt(c.h);
    const t: f32 = @floatFromInt(light);
    const len = @sqrt(w * w + h * h);
    var y: i32 = 0;
    while (y < c.h) : (y += 1) {
        var x: i32 = 0;
        while (x < c.w) : (x += 1) {
            const fx = @as(f32, @floatFromInt(x)) + 0.5;
            const fy = @as(f32, @floatFromInt(y)) + 0.5;
            // ╲: from (0,0) to (w,h); ╱: from (w,0) to (0,h).
            const d1 = @abs(h * fx - w * fy) / len;
            const d2 = @abs(h * (w - fx) - w * fy) / len;
            var d: f32 = std.math.inf(f32);
            if (cp == 0x2572 or cp == 0x2573) d = @min(d, d1);
            if (cp == 0x2571 or cp == 0x2573) d = @min(d, d2);
            const cov = std.math.clamp(t / 2 + 0.5 - d, 0, 1);
            const i: usize = @intCast(y * c.w + x);
            c.px[i] = @max(c.px[i], @as(u8, @intFromFloat(cov * 255)));
        }
    }
}

fn block(c: Canvas, cp: u32) void {
    const w = c.w;
    const h = c.h;
    const e8h = struct {
        fn f(n: i32, total: i32) i32 {
            return @divFloor(total * n, 8);
        }
    }.f;
    const hx = @divFloor(w, 2);
    const hy = @divFloor(h, 2);
    switch (cp) {
        0x2580 => c.rect(0, 0, w, hy, 255), // upper half
        0x2581...0x2588 => c.rect(0, h - e8h(@intCast(cp - 0x2580), h), w, h, 255), // lower n/8
        0x2589...0x258F => c.rect(0, 0, e8h(@intCast(8 - (cp - 0x2588)), w), h, 255), // left n/8
        0x2590 => c.rect(hx, 0, w, h, 255), // right half
        0x2591 => c.rect(0, 0, w, h, 64), // light shade
        0x2592 => c.rect(0, 0, w, h, 128), // medium shade
        0x2593 => c.rect(0, 0, w, h, 191), // dark shade
        0x2594 => c.rect(0, 0, w, e8h(1, h), 255), // upper 1/8
        0x2595 => c.rect(w - e8h(1, w), 0, w, h, 255), // right 1/8
        0x2596...0x259F => {
            // Quadrants: bit 0 UL, 1 UR, 2 LL, 3 LR.
            const q: u4 = switch (cp) {
                0x2596 => 0b0100,
                0x2597 => 0b1000,
                0x2598 => 0b0001,
                0x2599 => 0b1101,
                0x259A => 0b1001,
                0x259B => 0b0111,
                0x259C => 0b1011,
                0x259D => 0b0010,
                0x259E => 0b0110,
                0x259F => 0b1110,
                else => 0,
            };
            if (q & 1 != 0) c.rect(0, 0, hx, hy, 255);
            if (q & 2 != 0) c.rect(hx, 0, w, hy, 255);
            if (q & 4 != 0) c.rect(0, hy, hx, h, 255);
            if (q & 8 != 0) c.rect(hx, hy, w, h, 255);
        },
        else => {},
    }
}
