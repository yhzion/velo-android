//! Host tests (no EGL). Run:
//!   zig translate-c native/src/c.h -I ~/.cache/libghostty-vt/1.0.1/host/include -lc > /tmp/c.zig
//!   zig test --dep c -Mroot=native/src/term/test_pane.zig -Mc=/tmp/c.zig \
//!     -L ~/.cache/libghostty-vt/1.0.1/host/lib -lghostty-vt -lc
const std = @import("std");
const P = @import("Pane.zig");
const Renderer = @import("Renderer.zig");
const Pane = P.Pane;
const BoxDraw = P.BoxDraw;
const Atlas = P.Atlas;
const Keys = @import("Keys.zig");
const c = @import("c");

const stream = @embedFile("testdata/stream.bin");

fn noGlyph(_: *anyopaque, _: []const u8, _: bool, _: bool, _: u32, _: u32, _: u32) ?P.GlyphBitmap {
    return null;
}

var dummy: u8 = 0;
fn newPane() !*Pane {
    return Pane.create(std.testing.allocator, .{ .ctx = &dummy, .rasterize = noGlyph });
}

/// Strip Rex APCs (`ESC _ rex1;… ESC \`) like Core does.
fn stripRex(out: []u8, in: []const u8) []u8 {
    var n: usize = 0;
    var i: usize = 0;
    while (i < in.len) {
        if (std.mem.startsWith(u8, in[i..], "\x1b_rex1;")) {
            const end = std.mem.indexOfPos(u8, in, i, "\x1b\\") orelse in.len - 2;
            i = end + 2;
            continue;
        }
        out[n] = in[i];
        n += 1;
        i += 1;
    }
    return out[0..n];
}

test "snapshot restore from captured Rex stream" {
    const p = try newPane();
    defer p.destroy();
    // Partial input must ask for more data.
    try std.testing.expectError(error.NeedMoreData, p.restoreSnapshot(stream[0 .. stream.len / 3]));
    const g0 = p.generation();
    const used = try p.restoreSnapshot(stream);
    try std.testing.expect(used > 0 and used <= stream.len);
    try std.testing.expect(p.generation() > g0);

    var buf: [4096]u8 = undefined;
    p.write(stripRex(&buf, stream[used..]));
    const text = try p.copyText(std.testing.allocator);
    defer std.testing.allocator.free(text);
    try std.testing.expect(std.mem.indexOf(u8, text, "ok") != null);
    try std.testing.expect(std.mem.indexOf(u8, text, "┌─┐") != null);

    // A second snapshot replaces the terminal state.
    p.write("REPLACED-ME");
    _ = try p.restoreSnapshot(stream);
    const t2 = try p.copyText(std.testing.allocator);
    defer std.testing.allocator.free(t2);
    try std.testing.expect(std.mem.indexOf(u8, t2, "REPLACED-ME") == null);
}

test "write after restore, title, copyText" {
    const p = try newPane();
    defer p.destroy();
    _ = try p.restoreSnapshot(stream);
    p.resize(80, 24, 8, 16);
    const g = p.generation();
    p.write("\x1b]2;hello title\x07\x1b[2J\x1b[Hfirst line\r\nsecond \x1b[1mbold\x1b[0m");
    try std.testing.expect(p.generation() > g);
    var tb: [64]u8 = undefined;
    try std.testing.expectEqualStrings("hello title", p.title(&tb).?);
    const text = try p.copyText(std.testing.allocator);
    defer std.testing.allocator.free(text);
    try std.testing.expectEqualStrings("first line\nsecond bold", std.mem.trim(u8, text, "\n "));
}

test "touch selection then copyText returns selection" {
    const p = try newPane();
    defer p.destroy();
    p.setFontSizePx(10); // cell 6x12
    p.write("\x1b[2J\x1b[Habcdef ghijkl");
    // Drag from col 0 to col 2 on row 0.
    p.touch(0, 1, 2);
    p.touch(1, 2 * 6 + 5, 2);
    p.touch(2, 2 * 6 + 5, 2);
    const text = try p.copyText(std.testing.allocator);
    defer std.testing.allocator.free(text);
    try std.testing.expectEqualStrings("abc", text);
}

fn expectSelected(p: *Pane, expected: []const u8) !void {
    const text = try p.selectedText(std.testing.allocator);
    defer std.testing.allocator.free(text);
    try std.testing.expectEqualStrings(expected, text);
}

test "word and both handles follow wrapped cells in padded, panned surface pixels" {
    const p = try newPane();
    defer p.destroy();
    p.resize(6, 3, 1, 1);
    p.setFontSizePx(10); // 6 x 12 px
    p.setPaddingPx(4);
    p.surface_w = 38;
    p.surface_h = 40;
    _ = p.pan(-2, -2, 0, 0); // grid origin (2, 2)
    p.write("a abcd한 z\r\nnext"); // abcd ends at the wrap; 한 spans the first two cells below
    p.selectWord(10, 16); // second half of 한
    try expectSelected(p, "abcd한");
    try std.testing.expectEqual([4]i32{ 14, 14, 14, 26 }, p.selectionBounds().?);
    p.moveSelectionHandle(true, 6, 16); // start on wide owner
    try expectSelected(p, "한");
    p.moveSelectionHandle(false, 12, 28); // through hard newline, ending at 'e'
    try expectSelected(p, "한 z\nne");
    try std.testing.expectEqual([4]i32{ 2, 26, 14, 38 }, p.selectionBounds().?);
    p.moveSelectionHandle(false, 3, 3); // crossed start: collapse to the whole wide glyph
    try expectSelected(p, "한");
    try std.testing.expectEqual([4]i32{ 2, 26, 14, 26 }, p.selectionBounds().?);
}

test "a blank press clears the selection without copying the screen" {
    const p = try newPane();
    defer p.destroy();
    p.setFontSizePx(10);
    p.setPaddingPx(2);
    p.surface_w = 160;
    p.surface_h = 48;
    p.write("data  \u{3000}here");
    for ([_]f32{ 27, 45, 110, 0 }) |blank_x| { // space, wide space tail, unwritten cell, margin
        p.selectWord(3, 4);
        try std.testing.expect(p.hasSelection());
        p.selectWord(blank_x, 4);
        try std.testing.expect(!p.hasSelection());
        try std.testing.expect(p.selectionBounds() == null);
        try expectSelected(p, "");
    }
}

test "a wide character wrapped past the margin has handles on its glyph, not its spacer" {
    const p = try newPane();
    defer p.destroy();
    p.setFontSizePx(10);
    p.resize(4, 2, 6, 12);
    p.surface_w = 24;
    p.surface_h = 24;
    p.write("ab 한 x"); // unused last column on row 0; 한 starts row 1
    p.selectWord(20, 2);
    try expectSelected(p, "한");
    try std.testing.expectEqual([4]i32{ 0, 24, 12, 24 }, p.selectionBounds().?);
    p.moveSelectionHandle(true, 20, 14); // crossed end; still selects all of 한
    try expectSelected(p, "한");
    p.moveSelectionHandle(true, 1, 2);
    p.moveSelectionHandle(false, 20, 2); // spacer maps to the full wrapped glyph
    try expectSelected(p, "ab 한");
    try std.testing.expectEqual([4]i32{ 0, 12, 12, 24 }, p.selectionBounds().?);
}

test "selection tracks history and edits the viewport rather than the active grid" {
    const p = try newPane();
    defer p.destroy();
    p.setFontSizePx(10);
    p.resize(10, 2, 1, 1);
    p.surface_w = 60;
    p.surface_h = 24;
    p.write("one\r\ntwo");
    p.selectWord(7, 2);
    p.write("\r\nthree");
    try std.testing.expect(p.selectionBounds() == null);
    try expectSelected(p, "one");
    _ = p.pan(0, 12, 0, 0);
    try std.testing.expectEqual([4]i32{ 0, 12, 18, 12 }, p.selectionBounds().?);
    p.moveSelectionHandle(false, 7, 14);
    try expectSelected(p, "one\ntw");
    p.selectWord(7, 14);
    try expectSelected(p, "two");
    _ = try p.restoreSnapshot(stream);
    p.moveSelectionHandle(true, 0, 0);
    try std.testing.expect(p.selectionBounds() == null);
    try expectSelected(p, "");
}

test "hasSelection: says whether copyText copies a selection or the screen" {
    const p = try newPane();
    defer p.destroy();
    p.setFontSizePx(10); // cell 6x12
    p.write("\x1b[2J\x1b[Habcdef ghijkl");
    // No selection: the text of the screen is all that copyText can give.
    try std.testing.expect(!p.hasSelection());
    p.touch(0, 1, 2);
    p.touch(1, 2 * 6 + 5, 2);
    p.touch(2, 2 * 6 + 5, 2);
    try std.testing.expect(p.hasSelection());
    // A new touch-down replaces the selection: a press that lifts where it went down leaves none.
    p.touch(0, 4 * 6 + 1, 2);
    p.touch(2, 4 * 6 + 1, 2);
    try std.testing.expect(!p.hasSelection());
    const text = try p.copyText(std.testing.allocator);
    defer std.testing.allocator.free(text);
    try std.testing.expectEqualStrings("abcdef ghijkl", text);
}

test "clearSelection: the selection goes and copyText copies the screen again" {
    const p = try newPane();
    defer p.destroy();
    p.setFontSizePx(10); // cell 6x12
    p.write("\x1b[2J\x1b[Habcdef ghijkl");
    p.touch(0, 1, 2);
    p.touch(1, 2 * 6 + 5, 2);
    p.touch(2, 2 * 6 + 5, 2);
    try std.testing.expect(p.hasSelection());
    p.clearSelection();
    try std.testing.expect(!p.hasSelection());
    const text = try p.copyText(std.testing.allocator);
    defer std.testing.allocator.free(text);
    try std.testing.expectEqualStrings("abcdef ghijkl", text);
    // Without a selection it changes nothing.
    p.clearSelection();
    try std.testing.expect(!p.hasSelection());
}

test "theme does not crash" {
    const p = try newPane();
    defer p.destroy();
    p.setTheme(.{ .foreground = .{ 1, 2, 3 }, .background = .{ 4, 5, 6 }, .palette = &.{.{ 1, .{ 255, 0, 0 } }} });
}

test "layoutPreedit: Hangul takes two cells, Latin one, from the cursor" {
    var cells: [8]P.PreeditCell = undefined;
    const placed = P.layoutPreedit("안a녕", 3, 80, false, null, &cells);
    try std.testing.expectEqual(@as(usize, 3), placed.len);
    try std.testing.expectEqualStrings("안", placed[0].utf8);
    try std.testing.expectEqual(@as(u16, 3), placed[0].col);
    try std.testing.expectEqual(@as(u8, 2), placed[0].span);
    try std.testing.expectEqual(@as(u16, 5), placed[1].col);
    try std.testing.expectEqual(@as(u8, 1), placed[1].span);
    try std.testing.expectEqual(@as(u16, 6), placed[2].col);
    try std.testing.expectEqual(@as(u8, 2), placed[2].span);
}

test "layoutPreedit: a character that would cross the right edge, and what follows, is not placed" {
    var cells: [8]P.PreeditCell = undefined;
    // Columns 7 and 8 of 10 take 가; 나 would need 9 and 10, and the grid ends at 9.
    const placed = P.layoutPreedit("가나a", 7, 10, false, null, &cells);
    try std.testing.expectEqual(@as(usize, 1), placed.len);
    try std.testing.expectEqualStrings("가", placed[0].utf8);
    try std.testing.expectEqual(@as(usize, 0), P.layoutPreedit("가", 9, 10, false, null, &cells).len);
    try std.testing.expectEqual(@as(usize, 0), P.layoutPreedit("\xff", 0, 10, false, null, &cells).len);
}

test "layoutPreedit: an emoji outside the CJK blocks is wide, and a mark draws with its base" {
    var cells: [8]P.PreeditCell = undefined;
    const rocket = P.layoutPreedit("🚀한", 0, 80, false, null, &cells);
    try std.testing.expectEqual(@as(usize, 2), rocket.len);
    try std.testing.expectEqual(@as(u8, 2), rocket[0].span);
    try std.testing.expectEqual(@as(u16, 2), rocket[1].col);
    // e and a combining acute accent are one cell, drawn together.
    const accent = P.layoutPreedit("e\u{301}x", 0, 80, false, null, &cells);
    try std.testing.expectEqual(@as(usize, 2), accent.len);
    try std.testing.expectEqualStrings("e\u{301}", accent[0].utf8);
    try std.testing.expectEqual(@as(u16, 1), accent[1].col);
}

test "layoutPreedit: the composition takes the cells the terminal gives the same text" {
    const samples = [_][]const u8{ "🚀한", "안a녕", "👍🏽a", "e\u{301}x", "가✌️나", "🇰🇷한", "ｱ한" };
    for ([_]bool{ false, true }) |clustered| for (samples) |text| {
        const p = try newPane();
        defer p.destroy();
        if (clustered) p.write("\x1b[?2027h");
        p.write(text);
        var x: u16 = 0;
        _ = c.ghostty_terminal_get(p.term, c.GHOSTTY_TERMINAL_DATA_CURSOR_X, &x);
        var cells: [16]P.PreeditCell = undefined;
        const placed = P.layoutPreedit(text, 0, 80, clustered, null, &cells);
        const last = placed[placed.len - 1];
        std.testing.expectEqual(x, last.col + last.span) catch |e| {
            std.debug.print("{s} (mode 2027 {}): terminal {d}, preedit {d}\n", .{ text, clustered, x, last.col + last.span });
            return e;
        };
    };
}

test "layoutPreedit: only a joining cluster redraws its grid cell" {
    const Case = struct {
        prefix: []const u8,
        suffix: []const u8,
        merged: ?[]const u8,
        hidden: bool = false,
        legacy_span: ?u8 = null,
    };
    const cases = [_]Case{
        .{ .prefix = "✌", .suffix = "\u{fe0f}x", .merged = "✌️" },
        .{ .prefix = "👍", .suffix = "🏽x", .merged = "👍🏽" },
        .{ .prefix = "⌚", .suffix = "\u{fe0e}x", .merged = "⌚\u{fe0e}" },
        .{ .prefix = "🇰🇷", .suffix = "🇦x", .merged = null },
        .{ .prefix = "✌", .suffix = "x", .merged = null },
        .{ .prefix = "✌", .suffix = "\u{fe0f}x", .merged = null, .hidden = true },
        // A variation selector printed with 2027 off was stored, not applied.
        .{ .prefix = "❤\u{fe0f}", .legacy_span = 1, .suffix = "\u{301}x", .merged = "❤\u{fe0f}\u{301}" },
        .{ .prefix = "⌚\u{fe0e}", .legacy_span = 2, .suffix = "\u{301}x", .merged = "⌚\u{fe0e}\u{301}" },
        // A new spacing mark really does widen the stored narrow cell.
        .{ .prefix = "❤\u{fe0f}", .legacy_span = 1, .suffix = "\u{903}x", .merged = "❤\u{fe0f}\u{903}" },
    };
    for (cases) |sample| {
        const p = try newPane();
        defer p.destroy();
        p.write(if (sample.legacy_span != null) "\x1b[?2027l" else "\x1b[?2027h");
        if (sample.hidden) p.write("\x1b[8m");
        p.write(sample.prefix);
        if (sample.hidden) p.write("\x1b[0m");
        var x: u16 = undefined;
        try std.testing.expectEqual(c.GHOSTTY_SUCCESS, c.ghostty_terminal_get(p.term, c.GHOSTTY_TERMINAL_DATA_CURSOR_X, &x));
        if (sample.legacy_span) |span| {
            try std.testing.expectEqual(@as(u16, span), x);
            p.write("\x1b[?2027h");
        }
        var grid: P.PreeditGrid = .{ .term = p.term, .row = 0, .cursor_x = x };
        var cells: [4]P.PreeditCell = undefined;
        const placed = P.layoutPreedit(sample.suffix, x, 80, true, &grid, &cells);
        try std.testing.expect(placed.len > 0);
        if (sample.merged) |merged| {
            try std.testing.expectEqualStrings(merged, placed[0].utf8);
            try std.testing.expectEqual(@as(u16, 0), placed[0].col);
        } else try std.testing.expect(placed[0].col >= x);
        if (sample.hidden) try std.testing.expectEqual(@as(usize, 1), placed.len);

        // Commit only after layout so it cannot see the new grid. Compare every
        // emitted glyph to the actual cell contents and width at its predicted column.
        p.write(sample.suffix);
        for (placed) |cell| {
            var ref: c.GhosttyGridRef = .{};
            const pt: c.GhosttyPoint = .{ .tag = c.GHOSTTY_POINT_TAG_VIEWPORT, .value = .{ .coordinate = .{ .x = cell.col, .y = 0 } } };
            try std.testing.expectEqual(c.GHOSTTY_SUCCESS, c.ghostty_terminal_grid_ref(p.term, pt, &ref));
            var raw: c.GhosttyCell = undefined;
            var wide: c.GhosttyCellWide = undefined;
            try std.testing.expectEqual(c.GHOSTTY_SUCCESS, c.ghostty_grid_ref_cell(&ref, &raw));
            try std.testing.expectEqual(c.GHOSTTY_SUCCESS, c.ghostty_cell_get(raw, c.GHOSTTY_CELL_DATA_WIDE, &wide));
            try std.testing.expectEqual(@as(c.GhosttyCellWide, if (cell.span == 1) c.GHOSTTY_CELL_WIDE_NARROW else c.GHOSTTY_CELL_WIDE_WIDE), wide);
            var points: [8]u32 = undefined;
            var count: usize = undefined;
            try std.testing.expectEqual(c.GHOSTTY_SUCCESS, c.ghostty_grid_ref_graphemes(&ref, &points, points.len, &count));
            var it = (try std.unicode.Utf8View.init(cell.utf8)).iterator();
            for (points[0..count]) |cp| try std.testing.expectEqual(cp, @as(u32, it.nextCodepoint() orelse return error.MissingCodepoint));
            try std.testing.expect(it.nextCodepoint() == null);
        }
        try std.testing.expectEqual(c.GHOSTTY_SUCCESS, c.ghostty_terminal_get(p.term, c.GHOSTTY_TERMINAL_DATA_CURSOR_X, &x));
        const last = placed[placed.len - 1];
        try std.testing.expectEqual(x, last.col + last.span);
        try std.testing.expectEqualStrings("x", last.utf8);
    }
}

test "layoutPreedit: atlas keys distinguish suffixes beyond byte 79 and preserve short keys" {
    var atlas = Atlas.Atlas.init(std.testing.allocator);
    defer atlas.deinit();
    var buf: Renderer.GlyphKeyBuffer = undefined;
    var glyph: [81]u8 = undefined;
    glyph[0] = 'a';
    for (0..40) |i| @memcpy(glyph[1 + 2 * i ..][0..2], "\u{301}");
    _ = try atlas.put(Atlas.makeKey(&buf, &glyph, .{}), null, 0, 0);
    @memcpy(glyph[79..], "\u{300}");
    try std.testing.expect(atlas.get(Atlas.makeKey(&buf, &glyph, .{})) == null);
    @memcpy(glyph[79..], "\u{301}");
    try std.testing.expect(atlas.get(Atlas.makeKey(&buf, &glyph, .{})) != null);
    try std.testing.expectEqualStrings("a\x01", Atlas.makeKey(&buf, "a", .{ .bold = true }));
}

test "setPreedit: stores the composition, cut at a whole code point, and wakes the renderer only on a change" {
    const p = try newPane();
    defer p.destroy();
    p.wake = false;
    try std.testing.expect(p.setPreedit("한"));
    try std.testing.expect(p.setPreedit("한글"));
    p.setPreeditVisible(true);
    try std.testing.expectEqualStrings("한글", p.preeditLocked());
    try std.testing.expect(p.wake);
    p.wake = false;
    _ = p.setPreedit("한글");
    try std.testing.expect(!p.wake);
    _ = p.setPreedit("");
    try std.testing.expectEqualStrings("", p.preeditLocked());
    try std.testing.expect(p.wake);
    // The cap is 256 bytes. 86 syllables of three bytes are 258: the 256th byte falls inside
    // the 86th, which goes whole, so 255 remain. One-byte text keeps all 256.
    var long: [258]u8 = undefined;
    for (0..86) |i| @memcpy(long[i * 3 ..][0..3], "가");
    _ = p.setPreedit(&long);
    p.setPreeditVisible(true);
    try std.testing.expectEqual(@as(usize, 255), p.preeditLocked().len);
    try std.testing.expect(std.unicode.utf8ValidateSlice(p.preeditLocked()));
    @memset(&long, 'a');
    _ = p.setPreedit(&long);
    p.setPreeditVisible(true);
    try std.testing.expectEqual(@as(usize, P.preedit_cap), p.preeditLocked().len);
    // Deleting the clipped suffix changes the IME input, even though every stored byte matches.
    try std.testing.expect(p.setPreedit(long[0..P.preedit_cap]));
    try std.testing.expectEqualStrings("", p.preeditLocked());
}

test "setPreedit: a composition is drawn only once the remote says it echoes typed text" {
    const p = try newPane();
    defer p.destroy();
    // A new composition waits for the answer: a password prompt must not show it even briefly.
    try std.testing.expect(p.setPreedit("ㅎ"));
    try std.testing.expectEqualStrings("", p.preeditLocked());
    p.setPreeditVisible(false);
    _ = p.setPreedit("하");
    try std.testing.expectEqualStrings("", p.preeditLocked());
    p.setPreeditVisible(true);
    try std.testing.expectEqualStrings("하", p.preeditLocked());
    // Even the next edit within this same composition needs fresh permission.
    try std.testing.expect(p.setPreedit("한"));
    try std.testing.expectEqualStrings("", p.preeditLocked());
    _ = p.setPreedit("");
    try std.testing.expect(p.setPreedit("ㄱ"));
    try std.testing.expectEqualStrings("", p.preeditLocked());
}

fn scrollOffset(p: *Pane) u64 {
    var bar: c.GhosttyTerminalScrollbar = .{};
    _ = c.ghostty_terminal_get(p.term, c.GHOSTTY_TERMINAL_DATA_SCROLLBAR, &bar);
    return bar.offset;
}

test "pan: a downward drag scrolls back one row per cell height and keeps the remainder" {
    const p = try newPane();
    defer p.destroy();
    p.setFontSizePx(10); // cell 6x12
    p.surface_h = 288; // the 24-row grid fits, so a vertical drag only scrolls
    var i: usize = 0;
    while (i < 100) : (i += 1) p.write("line\r\n");
    const bottom = scrollOffset(p);
    try std.testing.expect(bottom > 5);

    _ = p.pan(0, 30, 0, 0); // 2.5 rows
    try std.testing.expectEqual(bottom - 2, scrollOffset(p));
    _ = p.pan(0, 6, 0, 0); // completes the third row
    try std.testing.expectEqual(bottom - 3, scrollOffset(p));
    _ = p.pan(0, -36, 0, 0);
    try std.testing.expectEqual(bottom, scrollOffset(p));
}

test "pan: a grid taller than the surface moves before scrollback does" {
    const p = try newPane();
    defer p.destroy();
    p.setFontSizePx(10); // cell 6x12, 24 rows = 288 px
    var i: usize = 0;
    while (i < 100) : (i += 1) p.write("line\r\n");
    const bottom = scrollOffset(p);
    p.surface_h = 188; // 100 px of the grid hidden below

    _ = p.pan(0, -60, 0, 0); // finger moves up, later rows come into view
    try std.testing.expectEqual(@as(f32, 60), p.pan_y);
    try std.testing.expectEqual(bottom, scrollOffset(p));
    _ = p.pan(0, -1000, 0, 0); // the last row is reachable; the rest of the drag has nowhere to go
    try std.testing.expectEqual(@as(f32, 100), p.pan_y);
    try std.testing.expectEqual(bottom, scrollOffset(p));

    _ = p.pan(0, 124, 0, 0); // back to the grid's top, then two rows into scrollback
    try std.testing.expectEqual(@as(f32, 0), p.pan_y);
    try std.testing.expectEqual(bottom - 2, scrollOffset(p));

    _ = p.pan(0, -60, 0, 0);
    p.setScale(0.5, false); // cell 3x6: the grid fits again
    try std.testing.expectEqual(@as(f32, 0), p.pan_y);
}

test "pan: sideways only when the grid is wider than the surface, clamped at both ends" {
    const p = try newPane();
    defer p.destroy();
    p.setFontSizePx(10); // cell 6x12, 80 columns = 480 px
    p.setPaddingPx(10); // content width 500

    p.surface_w = 600;
    _ = p.pan(-50, 0, 0, 0);
    try std.testing.expectEqual(@as(f32, 0), p.pan_x);

    p.surface_w = 300; // 200 px hidden
    _ = p.pan(-50, 0, 0, 0); // finger moves left, later columns come into view
    try std.testing.expectEqual(@as(f32, 50), p.pan_x);
    _ = p.pan(-1000, 0, 0, 0);
    try std.testing.expectEqual(@as(f32, 200), p.pan_x);
    _ = p.pan(1000, 0, 0, 0);
    try std.testing.expectEqual(@as(f32, 0), p.pan_x);
}

test "pan: a drag of 2.5 rows is 2 rows and half a row's offset, and a drag back returns to the row" {
    const p = try newPane();
    defer p.destroy();
    p.setFontSizePx(10); // cell 6x12
    p.surface_h = 288;
    var i: usize = 0;
    while (i < 100) : (i += 1) p.write("line\r\n");
    const bottom = scrollOffset(p);

    _ = p.pan(0, 30, 0, 0);
    try std.testing.expectEqual(bottom - 2, scrollOffset(p));
    try std.testing.expectEqual(@as(f32, 6), p.scrollOffsetPx());
    try std.testing.expectEqual(@as(i32, 0), Pane.scrollRows(6, 12)); // half a row stays on this side
    try std.testing.expectEqual(@as(i32, 1), Pane.scrollRows(6.02, 12)); // a hair past half is the next row
    try std.testing.expectEqual(@as(i32, 0), Pane.scrollRows(-6, 12));
    try std.testing.expectEqual(@as(i32, -1), Pane.scrollRows(-6.02, 12));
    _ = p.pan(0, -6, 0, 0);
    try std.testing.expectEqual(bottom - 2, scrollOffset(p));
    try std.testing.expectEqual(@as(f32, 0), p.scrollOffsetPx());
}

test "pan: the origin carries the offset" {
    const p = try newPane();
    defer p.destroy();
    p.setFontSizePx(10);
    p.surface_h = 288;
    var i: usize = 0;
    while (i < 100) : (i += 1) p.write("line\r\n");
    _ = p.pan(0, 4, 0, 0);
    const at = p.originLocked();
    try std.testing.expectEqual(@as(f32, 4), at.y);
}

test "pan: a drag past either end leaves no offset" {
    const p = try newPane();
    defer p.destroy();
    p.setFontSizePx(10);
    p.surface_h = 288;
    var i: usize = 0;
    while (i < 100) : (i += 1) p.write("line\r\n");
    _ = p.pan(0, -7, 0, 0); // at the newest line, pulling up
    try std.testing.expectEqual(@as(f32, 0), p.scrollOffsetPx());
    while (scrollOffset(p) > 0) _ = p.pan(0, 240, 0, 0); // to the oldest line
    try std.testing.expectEqual(@as(u64, 0), scrollOffset(p));
    _ = p.pan(0, 5, 0, 0); // at the oldest line, pulling down
    try std.testing.expectEqual(@as(f32, 0), p.scrollOffsetPx());
}

test "pan: the offset goes when the pane is resized, rescaled or restored" {
    const p = try newPane();
    defer p.destroy();
    p.setFontSizePx(10);
    p.surface_h = 288;
    var i: usize = 0;
    while (i < 100) : (i += 1) p.write("line\r\n");
    _ = p.pan(0, 7, 0, 0);
    try std.testing.expect(p.scrollOffsetPx() != 0);
    p.setScale(0.5, false);
    try std.testing.expectEqual(@as(f32, 0), p.scrollOffsetPx());
}

test "pan: the offset goes with the font size, the padding and a restored snapshot" {
    const p = try newPane();
    defer p.destroy();
    p.setFontSizePx(10);
    p.surface_h = 288;
    var i: usize = 0;
    while (i < 100) : (i += 1) p.write("line\r\n");
    _ = p.pan(0, 7, 0, 0);
    try std.testing.expect(p.scrollOffsetPx() != 0);
    p.setFontSizePx(11);
    try std.testing.expectEqual(@as(f32, 0), p.scrollOffsetPx());
    _ = p.pan(0, 7, 0, 0);
    try std.testing.expect(p.scrollOffsetPx() != 0);
    p.setPaddingPx(4);
    try std.testing.expectEqual(@as(f32, 0), p.scrollOffsetPx());
    _ = p.pan(0, 7, 0, 0);
    try std.testing.expect(p.scrollOffsetPx() != 0);
    _ = try p.restoreSnapshot(stream);
    try std.testing.expectEqual(@as(f32, 0), p.scrollOffsetPx());
}

test "pan: a touch selects the cells where they are drawn, with the offset" {
    const p = try newPane();
    defer p.destroy();
    p.setFontSizePx(10); // cell 6x12
    p.surface_h = 288;
    var i: usize = 0;
    while (i < 30) : (i += 1) p.write("line\r\n"); // history: without it the place cannot leave its rows
    p.write("ab\r\ncd");
    _ = p.pan(0, 4, 0, 0); // the rows are drawn 4 px lower
    const o = p.originLocked();
    try std.testing.expectEqual(@as(f32, 4), o.y);
    // 'ab' is drawn 4 px lower, in the second row from the bottom: a drag along the bottom of 'b'.
    p.touch(0, 7, 4 + 22 * 12 + 11);
    p.touch(1, 10, 4 + 22 * 12 + 11);
    p.touch(2, 10, 4 + 22 * 12 + 11);
    try expectSelected(p, "b");
}

test "pan: asked a thousand times inside the wait, the step wants no frame and the offset stays" {
    const p = try newPane();
    defer p.destroy();
    p.setFontSizePx(10);
    p.surface_h = 288;
    var i: usize = 0;
    while (i < 100) : (i += 1) p.write("line\r\n");
    _ = p.pan(0, 4, 0, 0);
    const t0 = P.monoNow();
    const ms = std.time.ns_per_ms;
    var wanted: usize = 0;
    var asks: usize = 0;
    while (asks < 1000) : (asks += 1) {
        if (p.scrollStep(t0 + 100 * ms)) wanted += 1;
    }
    try std.testing.expectEqual(@as(f32, 4), p.scrollOffsetPx()); // it rests by the clock, never because frames passed
    try std.testing.expectEqual(@as(usize, 0), wanted); // and wants no frame while it waits
}

test "pan: the offset rests on rows 150 ms after the last pan, over 100 ms" {
    const p = try newPane();
    defer p.destroy();
    p.setFontSizePx(10);
    p.surface_h = 288;
    var i: usize = 0;
    while (i < 100) : (i += 1) p.write("line\r\n");
    _ = p.pan(0, 4, 0, 0);
    const t0 = P.monoNow();
    const ms = std.time.ns_per_ms;
    try std.testing.expect(!p.scrollStep(t0 + 100 * ms)); // it waits for the 150 ms, and wants no frame meanwhile
    try std.testing.expectEqual(@as(f32, 4), p.scrollOffsetPx());
    try std.testing.expect(p.scrollStep(t0 + 150 * ms + 50 * ms)); // easing: each frame asks for the next
    const midway = p.scrollOffsetPx();
    try std.testing.expect(midway > 0 and midway < 4);
    try std.testing.expect(!p.scrollStep(t0 + 150 * ms + 100 * ms));
    try std.testing.expectEqual(@as(f32, 0), p.scrollOffsetPx());
}

test "pan: a pan during the settle goes on from where it is" {
    const p = try newPane();
    defer p.destroy();
    p.setFontSizePx(10);
    p.surface_h = 288;
    var i: usize = 0;
    while (i < 100) : (i += 1) p.write("line\r\n");
    _ = p.pan(0, 4, 0, 0);
    const t0 = P.monoNow();
    _ = p.scrollStep(t0 + 200 * std.time.ns_per_ms); // easing
    _ = p.pan(0, 1, 0, 0); // the finger again, while it eases
    try std.testing.expect(p.scrollOffsetPx() >= 2.5 and p.scrollOffsetPx() <= 3.2); // from where it was (2), not from 0 or 4
    const t1 = P.monoNow();
    try std.testing.expect(!p.scrollStep(t1)); // and the wait starts over: no frame until it is due
    try std.testing.expect(p.scrollStep(t1 + 200 * std.time.ns_per_ms)); // then it eases on, from there
    const onward = p.scrollOffsetPx();
    try std.testing.expect(onward > 0 and onward < 3);
}

test "pan: a clock that stands still while the offset eases ends the settle by its frame cap" {
    const p = try newPane();
    defer p.destroy();
    p.setFontSizePx(10);
    p.surface_h = 288;
    var i: usize = 0;
    while (i < 100) : (i += 1) p.write("line\r\n");
    _ = p.pan(0, 4, 0, 0);
    const t0 = P.monoNow();
    var wanted = true;
    var frames: usize = 0;
    const ms = std.time.ns_per_ms;
    const stuck = t0 + 200 * ms; // 50 ms into the easing, for good
    while (wanted and frames < 100) : (frames += 1) wanted = p.scrollStep(stuck); // time stands still
    try std.testing.expect(!wanted);
    try std.testing.expectEqual(@as(f32, 0), p.scrollOffsetPx());
}

test "pan: the render thread waits only until the offset's settle is due" {
    const p = try newPane();
    defer p.destroy();
    p.setFontSizePx(10);
    p.surface_h = 288;
    var i: usize = 0;
    while (i < 100) : (i += 1) p.write("line\r\n");
    const t0 = P.monoNow();
    const ms: u64 = std.time.ns_per_ms;
    try std.testing.expectEqual(@as(?u64, null), p.scrollWait(t0)); // at rest nothing is due
    _ = p.pan(0, 4, 0, 0);
    const t1 = P.monoNow();
    const left = p.scrollWait(t1 + 100 * ms).?;
    try std.testing.expect(left > 49 * ms and left <= 50 * ms); // the time left of the 150 ms wait
    try std.testing.expectEqual(@as(?u64, 0), p.scrollWait(t1 + 150 * ms)); // due now
    _ = p.scrollStep(t1 + 200 * ms); // easing: every frame is due at once
    try std.testing.expectEqual(@as(?u64, 0), p.scrollWait(t1 + 200 * ms));
    _ = p.scrollStep(t1 + 250 * ms); // rested
    try std.testing.expectEqual(@as(?u64, null), p.scrollWait(t1 + 300 * ms));
}

test "pan: a touch in the band above the viewport selects the row it shows" {
    const p = try newPane();
    defer p.destroy();
    p.setFontSizePx(10); // cell 6x12
    p.surface_h = 288;
    var line: [3]u8 = undefined;
    var i: usize = 0;
    while (i < 30) : (i += 1) {
        line = .{ 'a' + @as(u8, @intCast(i % 26)), '\r', '\n' };
        p.write(&line);
    }
    _ = p.pan(0, 24, 0, 0); // two rows back: the viewport's top is line 5 ('f')
    _ = p.pan(0, 4, 0, 0); // and 4 px lower: the band above the viewport shows line 4 ('e')
    p.touch(0, 1, 1);
    p.touch(1, 4, 1);
    p.touch(2, 4, 1);
    try expectSelected(p, "e");
}

test "pan: a touch in the band below the viewport selects the row it shows" {
    const p = try newPane();
    defer p.destroy();
    p.setFontSizePx(10); // cell 6x12
    p.surface_h = 288;
    var line: [3]u8 = undefined;
    var i: usize = 0;
    while (i < 30) : (i += 1) {
        line = .{ 'a' + @as(u8, @intCast(i % 26)), '\r', '\n' };
        p.write(&line);
    }
    _ = p.pan(0, 24, 0, 0); // two rows back: the viewport's last row is line 28 ('c')
    _ = p.pan(0, -4, 0, 0); // and 4 px higher: the band below the viewport shows line 29 ('d')
    p.touch(0, 1, 285);
    p.touch(1, 4, 285);
    p.touch(2, 4, 285);
    try expectSelected(p, "d");
    p.selectWord(1, 285); // and the word the band shows is that row's
    try expectSelected(p, "d");
}

test "pan: a selection handle dragged below the grid holds at the last row" {
    const p = try newPane();
    defer p.destroy();
    p.resize(6, 3, 1, 1); // a 6x3 grid: the last row is one drag away
    p.setFontSizePx(10); // cell 6x12
    p.write("ab\r\ncd\r\nlast");
    // A selection on "ab" (row 0, drawn at y 0..12).
    p.touch(0, 1, 1);
    p.touch(1, 11, 1);
    p.touch(2, 11, 1);
    try expectSelected(p, "ab");
    // The end handle, dragged below the grid, extends to the last row's last cell.
    p.moveSelectionHandle(false, 20, 400);
    try expectSelected(p, "ab\ncd\nlast");
}

test "pan: a selection handle in the band above the viewport extends to the row it shows" {
    const p = try newPane();
    defer p.destroy();
    p.setFontSizePx(10); // cell 6x12
    p.surface_h = 288;
    var line: [3]u8 = undefined;
    var i: usize = 0;
    while (i < 30) : (i += 1) {
        line = .{ 'a' + @as(u8, @intCast(i % 26)), '\r', '\n' };
        p.write(&line);
    }
    _ = p.pan(0, 24, 0, 0); // two rows back: the viewport's top is line 5 ('f')
    _ = p.pan(0, 4, 0, 0); // and 4 px lower: the band above shows line 4 ('e')
    p.touch(0, 1, 10);
    p.touch(1, 7, 10);
    p.touch(2, 7, 10);
    try expectSelected(p, "f");
    p.moveSelectionHandle(true, 1, 1); // the start handle, in the band above: the row it shows
    try expectSelected(p, "e\nf");
}

test "pan: a thousand ordinary frames of an unchanged size leave the offset alone" {
    const p = try newPane();
    defer p.destroy();
    p.setFontSizePx(10);
    p.surface_h = 288;
    var i: usize = 0;
    while (i < 100) : (i += 1) p.write("line\r\n");
    _ = p.pan(0, 4, 0, 0);
    var frames: usize = 0;
    while (frames < 1000) : (frames += 1) {
        try std.testing.expectEqual(.keep, Renderer.retakeSize(640, 400, 640, 400, false));
        try std.testing.expectEqual(@as(f32, 4), p.scrollOffsetPx()); // the frame keeps it
    }
}

test "fitGrid: the cells that fit the surface less the padding on both sides" {
    const p = try newPane();
    defer p.destroy();
    p.setFontSizePx(10); // cell 6x12
    try std.testing.expectEqual(Pane.Fit{ .cols = 100, .rows = 30, .cell_w = 6, .cell_h = 12 }, p.fitGrid(600, 360));
    // A partial cell at the right or bottom edge does not count.
    try std.testing.expectEqual(Pane.Fit{ .cols = 100, .rows = 30, .cell_w = 6, .cell_h = 12 }, p.fitGrid(605, 371));

    // 10 px on every side: 580 x 340 px are left for the grid.
    p.setPaddingPx(10);
    try std.testing.expectEqual(Pane.Fit{ .cols = 96, .rows = 28, .cell_w = 6, .cell_h = 12 }, p.fitGrid(600, 360));
    // Half pixels count on both sides: 2 x 10.5 leaves 579 px, 96 columns and a rest.
    p.setPaddingPx(10.5);
    try std.testing.expectEqual(@as(u16, 96), p.fitGrid(600, 360).cols);
    try std.testing.expectEqual(@as(u16, 96), p.fitGrid(597, 360).cols);
    try std.testing.expectEqual(@as(u16, 95), p.fitGrid(596, 360).cols);
}

test "fitGrid: the pinch scale and the line height change the cell, and so the grid" {
    const p = try newPane();
    defer p.destroy();
    p.setFontSizePx(10); // cell 6x12
    p.setScale(2, false); // cell 12x24
    try std.testing.expectEqual(Pane.Fit{ .cols = 50, .rows = 15, .cell_w = 12, .cell_h = 24 }, p.fitGrid(600, 360));
    p.setScale(0.5, false); // cell 3x6
    try std.testing.expectEqual(Pane.Fit{ .cols = 200, .rows = 60, .cell_w = 3, .cell_h = 6 }, p.fitGrid(600, 360));
    p.setScale(1, false);
    p.setFont(1.5); // cell 6x15
    try std.testing.expectEqual(Pane.Fit{ .cols = 100, .rows = 24, .cell_w = 6, .cell_h = 15 }, p.fitGrid(600, 360));
}

test "fitGrid: a surface smaller than one cell, or than its padding, is 1 x 1" {
    const p = try newPane();
    defer p.destroy();
    p.setFontSizePx(10); // cell 6x12
    try std.testing.expectEqual(Pane.Fit{ .cols = 1, .rows = 1, .cell_w = 6, .cell_h = 12 }, p.fitGrid(5, 11));
    try std.testing.expectEqual(Pane.Fit{ .cols = 1, .rows = 1, .cell_w = 6, .cell_h = 12 }, p.fitGrid(0, 0));
    // The padding alone is wider than the surface.
    p.setPaddingPx(256);
    try std.testing.expectEqual(Pane.Fit{ .cols = 1, .rows = 1, .cell_w = 6, .cell_h = 12 }, p.fitGrid(300, 500));
    try std.testing.expectEqual(@as(u16, 1), p.fitGrid(512 + 6, 512 + 12).cols);
    try std.testing.expectEqual(@as(u16, 2), p.fitGrid(512 + 12, 512 + 24).rows);
    // More cells than a terminal can have.
    p.setPaddingPx(0);
    p.setFontSizePx(4);
    p.setScale(0.25, false); // cell 1x2
    try std.testing.expectEqual(@as(u16, 65535), p.fitGrid(1_000_000, 10).cols);
}

test "touch maps through padding and pan to the cell under the finger" {
    const p = try newPane();
    defer p.destroy();
    p.setFontSizePx(10); // cell 6x12
    p.setPaddingPx(10);
    p.surface_w = 300;
    p.write("\x1b[2J\x1b[Habcdef ghijkl");
    _ = p.pan(-12, 0, 0, 0); // grid origin moves to x = 10 - 12 = -2
    // Column c (index 2) spans x = 10..16. Drag from c to e.
    p.touch(0, 11, 12);
    p.touch(1, -2 + 4 * 6 + 5, 12);
    p.touch(2, -2 + 4 * 6 + 5, 12);
    const text = try p.copyText(std.testing.allocator);
    defer std.testing.allocator.free(text);
    try std.testing.expectEqualStrings("cde", text);
}

test "encodeKey" {
    const p = try newPane();
    defer p.destroy();
    var out: [64]u8 = undefined;
    try std.testing.expectEqualStrings("\r", p.encodeKey(&out, Keys.KEYCODE_ENTER, 0, 0, 0));
    try std.testing.expectEqualStrings("\x03", p.encodeKey(&out, Keys.KEYCODE_A + 2, Keys.META_CTRL_ON, 0, 0));
    try std.testing.expectEqualStrings("\x1b[A", p.encodeKey(&out, Keys.KEYCODE_DPAD_UP, 0, 0, 0));
    try std.testing.expectEqualStrings("a", p.encodeKey(&out, Keys.KEYCODE_A, 0, 'a', 0));
    try std.testing.expectEqualStrings("A", p.encodeKey(&out, Keys.KEYCODE_A, Keys.META_SHIFT_ON, 'A', 0));
    try std.testing.expectEqualStrings("\x1ba", p.encodeKey(&out, Keys.KEYCODE_A, Keys.META_ALT_ON, 'a', 0));
    try std.testing.expectEqualStrings("\x7f", p.encodeKey(&out, Keys.KEYCODE_DEL, 0, 0, 0));
    try std.testing.expectEqualStrings("\t", p.encodeKey(&out, Keys.KEYCODE_TAB, 0, 0, 0));
    try std.testing.expectEqualStrings("\x1b", p.encodeKey(&out, Keys.KEYCODE_ESCAPE, 0, 0, 0));
    try std.testing.expectEqualStrings("\x1bOP", p.encodeKey(&out, Keys.KEYCODE_F1, 0, 0, 0));
    try std.testing.expectEqualStrings("\x1b[1;5C", p.encodeKey(&out, Keys.KEYCODE_DPAD_RIGHT, Keys.META_CTRL_ON, 0, 0));
    try std.testing.expectEqualStrings("", p.encodeKey(&out, Keys.KEYCODE_A, 0, 'a', 1));
    // Application cursor mode (DECCKM) is honored.
    p.write("\x1b[?1h");
    try std.testing.expectEqualStrings("\x1bOA", p.encodeKey(&out, Keys.KEYCODE_DPAD_UP, 0, 0, 0));
}

var window_a: u8 = 0;
var window_b: u8 = 0;

test "attachStep: the same window keeps the renderer, another one replaces it, none ends it" {
    const a: *anyopaque = &window_a;
    const b: *anyopaque = &window_b;
    const Action = P.AttachAction;

    // The window the pane draws to already: nothing is torn down.
    try std.testing.expectEqual(Action.keep, P.attachStep(a, true, a));
    // Another window, or the first one.
    try std.testing.expectEqual(Action.replace, P.attachStep(a, true, b));
    try std.testing.expectEqual(Action.replace, P.attachStep(null, true, a));
    // The same window, but its renderer has ended: keeping it would keep a dead surface.
    try std.testing.expectEqual(Action.replace, P.attachStep(a, false, a));
    try std.testing.expectEqual(Action.replace, P.attachStep(a, false, b));
    // No window.
    try std.testing.expectEqual(Action.detach, P.attachStep(a, true, null));
    try std.testing.expectEqual(Action.detach, P.attachStep(a, false, null));
    try std.testing.expectEqual(Action.detach, P.attachStep(null, true, null));
}

test "attachRefs: the pane ends with one reference to the window it draws to and no other" {
    const Refs = P.AttachRefs;
    // In place: the pane has its reference already, so the one passed in is given up.
    try std.testing.expectEqual(Refs{ .current = .held, .incoming = .released }, P.attachRefs(.keep, true));
    // A new renderer runs: it draws to the window passed in, whose reference is the pane's now.
    try std.testing.expectEqual(Refs{ .current = .released, .incoming = .held }, P.attachRefs(.replace, true));
    // A new renderer that does not start draws to nothing: both are given up.
    try std.testing.expectEqual(Refs{ .current = .released, .incoming = .released }, P.attachRefs(.replace, false));
    // No window: there is none passed in, and the pane's own is given up.
    try std.testing.expectEqual(P.Ref.released, P.attachRefs(.detach, true).current);
    try std.testing.expectEqual(P.Ref.released, P.attachRefs(.detach, true).incoming);
}

/// References the panes of a test gave up, by window.
const Released = struct {
    var a: usize = 0;
    var b: usize = 0;
    /// The pane whose render thread must have ended before any reference is given up.
    var joined: ?*Pane = null;
    /// References given up while that pane still had a render thread.
    var under_thread: usize = 0;

    fn count(window: *anyopaque) void {
        if (window == @as(*anyopaque, &window_a)) a += 1;
        if (window == @as(*anyopaque, &window_b)) b += 1;
        if (joined) |p| if (p.thread != null) {
            under_thread += 1;
        };
    }
    fn watch() void {
        a = 0;
        b = 0;
        joined = null;
        under_thread = 0;
        P.host_release = count;
    }
    fn expect(want_a: usize, want_b: usize) !void {
        try std.testing.expectEqual(want_a, a);
        try std.testing.expectEqual(want_b, b);
    }
};

test "attach references: every attach gives up what attachRefs says, once" {
    Released.watch();
    defer P.host_release = null;
    const p = try newPane();
    defer p.destroy();

    // A first window whose renderer does not start (the host cannot start one).
    try std.testing.expectError(error.Unsupported, p.attachSurface(&window_a, 300, 200));
    try Released.expect(1, 0);

    // In place, as if a renderer drew to window_a: the reference passed in is one too
    // many. The tab overview does this for every pane when it opens and when it closes.
    p.window = &window_a;
    try p.attachSurface(&window_a, 300, 200);
    try Released.expect(2, 0);
    try p.attachSurface(&window_a, 320, 240);
    try Released.expect(3, 0);
    try std.testing.expectEqual(@as(?*anyopaque, &window_a), p.window);

    // Another window whose renderer does not start: the pane's own and the one passed in.
    try std.testing.expectError(error.Unsupported, p.attachSurface(&window_b, 300, 200));
    try Released.expect(4, 1);
    try std.testing.expectEqual(@as(?*anyopaque, null), p.window);

    // Detach: the pane's own; a pane without a window has none.
    p.window = &window_b;
    p.detachSurface();
    try Released.expect(4, 2);
    p.detachSurface();
    try Released.expect(4, 2);
}

test "attach in place: the window the pane draws to takes the size and nothing else changes" {
    const p = try newPane();
    defer p.destroy();
    // The host cannot start a renderer, so a first window is refused and changes nothing.
    try std.testing.expectError(error.Unsupported, p.attachSurface(&window_a, 300, 200));
    try std.testing.expectEqual(@as(?*anyopaque, null), p.window);

    // As if a renderer drew to window_a.
    p.window = &window_a;
    p.setFontSizePx(10); // cell 6x12, 80 columns = 480 px
    p.surface_w = 300;
    p.surface_h = 200;
    p.setScale(2, false);
    _ = p.pan(-50, -30, 0, 0);
    const pan_x = p.pan_x;
    const pan_y = p.pan_y;
    try std.testing.expect(pan_x > 0 and pan_y > 0);

    // The same window and size again (a preview becomes the shown pane, or back): the
    // renderer is woken and nothing else changes.
    p.wake = false;
    try p.attachSurface(&window_a, 300, 200);
    try std.testing.expect(p.wake);
    try std.testing.expectEqual(@as(?*anyopaque, &window_a), p.window);
    try std.testing.expectEqual(@as(f32, 2), p.scale);
    try std.testing.expectEqual(pan_x, p.pan_x);
    try std.testing.expectEqual(pan_y, p.pan_y);
    try std.testing.expectEqual(@as(u8, 0), p.settle_frames);

    // Another size for the same window is taken without a new renderer, which then draws
    // until the window hands out buffers of that size.
    try p.attachSurface(&window_a, 320, 240);
    try std.testing.expectEqual(P.frames_after_resize, p.settle_frames);
    try std.testing.expectEqual(@as(u32, 320), p.surface_w);
    try std.testing.expectEqual(@as(u32, 240), p.surface_h);
    try std.testing.expectEqual(@as(?*anyopaque, &window_a), p.window);

    p.detachSurface();
    try std.testing.expectEqual(@as(?*anyopaque, null), p.window);
}

test "attach: another window, or a renderer that failed, starts again" {
    const p = try newPane();
    defer p.destroy();
    // As if a renderer drew to window_a. Starting again is what the host cannot do.
    p.window = &window_a;
    try std.testing.expectError(error.Unsupported, p.attachSurface(&window_b, 300, 200));
    try std.testing.expectEqual(@as(?*anyopaque, null), p.window);

    // The same window again, but its renderer gave up: in place would keep a dead surface.
    p.window = &window_a;
    p.render_failed = true;
    try std.testing.expectError(error.Unsupported, p.attachSurface(&window_a, 300, 200));
    try std.testing.expectEqual(@as(?*anyopaque, null), p.window);
    try std.testing.expect(!p.render_failed);
}

/// A render thread's drawing for `Renderer.run`: what `build` and `present` answer is
/// given, and the pane is told to quit with the last build.
const FakeDraw = struct {
    pane: *Pane,
    /// What each `build` answers, in order.
    builds: []const bool,
    /// The `present` that fails, counted from 0.
    present_fails_at: ?usize = null,
    /// Terminal output arrives during every build, so the pane has work again.
    output: bool = true,
    built: usize = 0,
    presented: usize = 0,
    /// `settle_frames` as each build found it.
    settle: [8]u8 = @splat(0),

    /// With the pane lock held, as the renderer's own.
    pub fn build(f: *FakeDraw) bool {
        const i = f.built;
        f.built += 1;
        f.settle[i] = f.pane.settle_frames;
        if (f.output) f.pane.wake = true;
        if (f.built == f.builds.len) f.pane.quit = true;
        return f.builds[i];
    }

    pub fn present(f: *FakeDraw) bool {
        const i = f.presented;
        f.presented += 1;
        return f.present_fails_at != i;
    }
};

test "render loop: a window that does not take a frame ends the loop" {
    const p = try newPane();
    defer p.destroy();
    var draw: FakeDraw = .{ .pane = p, .builds = &.{ true, true, true, true }, .present_fails_at = 1 };
    Renderer.run(p, &draw);
    // The frame that failed was the last one built: nothing is drawn to a dead surface.
    try std.testing.expectEqual(@as(usize, 2), draw.built);
    try std.testing.expectEqual(@as(usize, 2), draw.presented);
    try std.testing.expect(p.render_failed);
    // It ended by itself, not because the pane asked.
    try std.testing.expect(!p.quit);
}

test "render loop: frames after a resize count only when drawn" {
    const p = try newPane();
    defer p.destroy();
    // A build that fails inside the frames after a resize does not spend one of them.
    p.settle_frames = P.frames_after_resize;
    var draw: FakeDraw = .{ .pane = p, .builds = &.{ true, false, false, true, true, true } };
    Renderer.run(p, &draw);
    try std.testing.expectEqualSlices(u8, &.{ 3, 2, 2, 2, 1, 0 }, draw.settle[0..draw.built]);
    try std.testing.expectEqual(@as(usize, 4), draw.presented);
    try std.testing.expectEqual(@as(u8, 0), p.settle_frames);
    try std.testing.expect(!p.render_failed);

    // Nor does it make the thread draw again on its own: with nothing else to draw the
    // thread waits, and the frames are still owed.
    p.quit = false;
    p.wake = true;
    p.settle_frames = P.frames_after_resize;
    var idle: FakeDraw = .{ .pane = p, .builds = &.{false}, .output = false };
    Renderer.run(p, &idle);
    try std.testing.expect(!p.wake);
    try std.testing.expectEqual(P.frames_after_resize, p.settle_frames);

    // A frame that was drawn does: the thread draws the next one without being asked.
    p.quit = false;
    p.wake = true;
    var one: FakeDraw = .{ .pane = p, .builds = &.{true}, .output = false };
    Renderer.run(p, &one);
    try std.testing.expect(p.wake);
    try std.testing.expectEqual(P.frames_after_resize - 1, p.settle_frames);
}

/// Wakes a pane after 2 s unless told the loop has returned: a loop that waits for a wake
/// that never comes would hang the test.
const Watchdog = struct {
    pane: *Pane,
    finished: std.atomic.Value(bool) = .init(false),
    fired: std.atomic.Value(bool) = .init(false),

    fn watch(w: *Watchdog) void {
        while (!w.finished.load(.acquire)) {
            // Two seconds without the loop returning: the deadline is not waking it, so this does.
            var waited: u32 = 0;
            while (!w.finished.load(.acquire) and waited < 400) : (waited += 1) {
                // A raw sleep: the test clock is not for a thread of its own, and this one only counts.
                var ts: std.c.timespec = .{ .sec = 0, .nsec = 5 * std.time.ns_per_ms };
                _ = std.c.nanosleep(&ts, null);
            }
            if (w.finished.load(.acquire)) return;
            w.fired.store(true, .release);
            w.pane.lock.lock();
            w.pane.wake = true;
            w.pane.lock.signal();
            w.pane.lock.unlock();
        }
    }
};

/// A pane with 100 lines of history, its offset waiting to rest after a drag of 4 px.
fn paneWaitingToRest() !*Pane {
    const p = try newPane();
    errdefer p.destroy();
    p.setFontSizePx(10);
    p.surface_h = 288;
    var i: usize = 0;
    while (i < 100) : (i += 1) p.write("line\r\n");
    _ = p.pan(0, 4, 0, 0);
    return p;
}

test "render loop: while the scroll offset waits to rest, the thread sleeps until that is due" {
    const p = try paneWaitingToRest();
    defer p.destroy();
    var watchdog: Watchdog = .{ .pane = p };
    const thread = try std.Thread.spawn(.{}, Watchdog.watch, .{&watchdog});
    // No output, so nothing else wakes the thread: the offset's settle is due 150 ms after the pan.
    var draw: FakeDraw = .{ .pane = p, .builds = &.{ true, true, true }, .output = false };
    Renderer.run(p, &draw);
    watchdog.finished.store(true, .release);
    thread.join();
    try std.testing.expect(!watchdog.fired.load(.acquire)); // woken by the clock, not by the watchdog
    try std.testing.expectEqual(@as(usize, 3), draw.built);
    try std.testing.expectEqual(@as(usize, 3), draw.presented);
}

test "render loop: a frame that could not be built does not make the thread try again for the offset" {
    const p = try paneWaitingToRest();
    defer p.destroy();
    var draw: FakeDraw = .{ .pane = p, .builds = &.{false} };
    Renderer.run(p, &draw);
    try std.testing.expectEqual(@as(usize, 1), draw.built);
    try std.testing.expectEqual(@as(f32, 0), p.scrollOffsetPx()); // the offset rests with the frame
}

fn runFake(p: *Pane, draw: *FakeDraw) void {
    Renderer.run(p, draw);
}

/// Start a render thread on `window_a` whose first frame the window does not take, and
/// wait until it has said so.
fn startFailingRenderer(p: *Pane, draw: *FakeDraw) !void {
    draw.* = .{ .pane = p, .builds = &.{ true, true, true, true }, .present_fails_at = 0 };
    p.window = &window_a;
    p.quit = false;
    p.wake = true;
    p.thread = try std.Thread.spawn(.{}, runFake, .{ p, draw });
    // About 5 s: a loop that ends without saying so must fail the test, not hang it.
    for (0..5000) |_| {
        p.lock.lock();
        const failed = p.render_failed;
        p.lock.unlock();
        if (failed) return;
        std.Io.sleep(std.testing.io, .fromMilliseconds(1), .awake) catch {};
    }
    return error.TestUnexpectedResult;
}

test "a render thread that ended by itself is joined by the next attach or detach" {
    Released.watch();
    defer P.host_release = null;
    const p = try newPane();
    defer p.destroy();
    var draw: FakeDraw = undefined;
    // A window released under a render thread that is not joined yet would be freed
    // under its EGL surface: every reference here goes after the join.
    Released.joined = p;
    defer Released.joined = null;

    // The same window again: not in place, the thread is joined and a new renderer asked
    // for (which the host cannot start, so both references are given up).
    try startFailingRenderer(p, &draw);
    try std.testing.expectEqual(P.AttachAction.replace, P.attachStep(p.window, !p.render_failed, &window_a));
    try std.testing.expectError(error.Unsupported, p.attachSurface(&window_a, 300, 200));
    try std.testing.expectEqual(@as(?std.Thread, null), p.thread);
    try std.testing.expectEqual(@as(usize, 1), draw.built);
    try std.testing.expectEqual(@as(?*anyopaque, null), p.window);
    try std.testing.expect(!p.render_failed);
    try Released.expect(2, 0);

    // Detach: joined, and the pane's reference given up once.
    try startFailingRenderer(p, &draw);
    p.detachSurface();
    try std.testing.expectEqual(@as(?std.Thread, null), p.thread);
    try std.testing.expectEqual(@as(usize, 1), draw.built);
    try std.testing.expect(!p.render_failed);
    try Released.expect(3, 0);
    try std.testing.expectEqual(@as(usize, 0), Released.under_thread);
}

test "box drawing classification and geometry" {
    try std.testing.expectEqual(BoxDraw.Kind.lines, BoxDraw.kind(0x2502));
    try std.testing.expectEqual([4]u2{ 1, 0, 1, 0 }, BoxDraw.lineWeights(0x2502));
    try std.testing.expectEqual(BoxDraw.Kind.arc, BoxDraw.kind(0x256D));
    try std.testing.expectEqual(BoxDraw.Kind.block, BoxDraw.kind(0x2588));
    try std.testing.expectEqual(BoxDraw.Kind.none, BoxDraw.kind('a'));
    try std.testing.expectEqual([4]u2{ 3, 3, 3, 3 }, BoxDraw.lineWeights(0x256C));

    const w = 10;
    const h = 20;
    var px: [w * h]u8 = undefined;
    // │ spans top to bottom row.
    try std.testing.expect(BoxDraw.render(0x2502, &px, w, h));
    try std.testing.expect(px[w / 2] == 255 and px[(h - 1) * w + w / 2] == 255);
    try std.testing.expect(px[0] == 0);
    // ─ spans left to right edge.
    _ = BoxDraw.render(0x2500, &px, w, h);
    try std.testing.expect(px[(h / 2) * w] == 255 and px[(h / 2) * w + w - 1] == 255);
    // ╭ reaches right edge and bottom edge, not top-left.
    _ = BoxDraw.render(0x256D, &px, w, h);
    try std.testing.expect(px[(h / 2) * w + w - 1] > 0);
    try std.testing.expect(px[(h - 1) * w + w / 2] > 0);
    try std.testing.expect(px[0] == 0);
    // █ fills everything, ▀ only the top half.
    _ = BoxDraw.render(0x2588, &px, w, h);
    for (px) |v| try std.testing.expectEqual(@as(u8, 255), v);
    _ = BoxDraw.render(0x2580, &px, w, h);
    try std.testing.expect(px[0] == 255 and px[(h - 1) * w] == 0);
}

test "atlas packs A8 and RGBA, negative entries, page growth" {
    var a = Atlas.Atlas.init(std.testing.allocator);
    defer a.deinit();
    var kb: [32]u8 = undefined;
    const a8 = @as([8 * 16]u8, @splat(1));
    const s1 = try a.put(Atlas.makeKey(&kb, "x", .{}), &a8, 8, 16);
    try std.testing.expect(!s1.color and !s1.empty());
    const rgba = @as([8 * 16 * 4]u8, @splat(2));
    const s2 = try a.put(Atlas.makeKey(&kb, "x", .{ .bold = true }), &rgba, 8, 16);
    try std.testing.expect(s2.color);
    _ = try a.put(Atlas.makeKey(&kb, "?", .{}), null, 8, 16);
    try std.testing.expect(a.get(Atlas.makeKey(&kb, "?", .{})).?.empty());
    try std.testing.expect(a.get(Atlas.makeKey(&kb, "x", .{})).?.x == s1.x);
    var big: [200 * 200]u8 = undefined;
    @memset(&big, 3);
    var i: usize = 0;
    while (i < 40) : (i += 1) {
        var nb: [8]u8 = undefined;
        const name = try std.fmt.bufPrint(&nb, "g{d}", .{i});
        _ = try a.put(Atlas.makeKey(&kb, name, .{}), &big, 200, 200);
    }
    try std.testing.expect(a.mask.pages.items.len >= 2);
}

test "reader formats only visible cells independently of the touch selection" {
    const p = try newPane();
    defer p.destroy();
    p.setFontSizePx(10);
    p.resize(12, 2, 6, 12);
    p.surface_w = 12 * 6;
    p.surface_h = 2 * 12;
    p.write("offscreen\r\nfirst line\r\nsecond line");
    p.touch(0, 1, 2);
    p.touch(1, 17, 2);
    p.touch(2, 17, 2);
    const text = try p.viewportText(std.testing.allocator);
    defer std.testing.allocator.free(text);
    try std.testing.expectEqualStrings("first line\nsecond line", text);
    const selected = try p.copyText(std.testing.allocator);
    defer std.testing.allocator.free(selected);
    try std.testing.expectEqualStrings("fir", selected);
    p.surface_w = 3 * 6;
    const clipped = try p.viewportText(std.testing.allocator);
    defer std.testing.allocator.free(clipped);
    try std.testing.expectEqualStrings("fir\nsec", clipped);
}

fn expectReaderText(p: *Pane, expected: []const u8) !void {
    const text = try p.viewportText(std.testing.allocator);
    defer std.testing.allocator.free(text);
    try std.testing.expectEqualStrings(expected, text);
}

test "reader replaces concealed cells with spaces of the visible width" {
    const p = try newPane();
    defer p.destroy();
    p.setFontSizePx(10); // cell 6x12
    p.surface_w = 12 * 6;
    p.surface_h = 12;
    p.write("A\x1b[8mHIDDEN⚡\x1b[28mB");
    try expectReaderText(p, "A        B");
    p.surface_w = 2 * 6;
    p.pan_x = 8 * 6; // Only the second cell of the concealed wide glyph is visible.
    try expectReaderText(p, " B");
}

test "reader joins soft wraps only when the full width is visible" {
    const p = try newPane();
    defer p.destroy();
    p.setFontSizePx(10);
    p.resize(6, 2, 6, 12);
    p.surface_h = 2 * 12;
    p.write("abcdefGHI");
    p.surface_w = 6 * 6;
    try expectReaderText(p, "abcdefGHI");
    p.surface_w = 3 * 6;
    try expectReaderText(p, "abc\nGHI");
}

test "reader stops before a wide character wrapped below the surface" {
    const p = try newPane();
    defer p.destroy();
    p.setFontSizePx(10);
    p.resize(3, 2, 6, 12);
    p.surface_w = 3 * 6;
    p.write("1A⚡Z");
    p.surface_h = 12;
    try expectReaderText(p, "1A");
    p.surface_h = 2 * 12;
    try expectReaderText(p, "1A⚡Z");
}

test "pan: mouse reporting uses touched cells on either screen, ahead of alternate scroll" {
    const p = try newPane();
    defer p.destroy();
    p.setFontSizePx(10); // cell 6x12
    p.setPaddingPx(3);
    p.surface_w = 486;
    p.surface_h = 294;
    p.write("\x1b[?1000h");
    const main = p.pan(0, 24, 15, 39).?;
    try std.testing.expectEqual(@as(u16, 2), main.count);
    try std.testing.expectEqual(@as(u16, 2), main.event.wheel.x);
    try std.testing.expectEqual(@as(u16, 3), main.event.wheel.y);
    try std.testing.expectEqual(@as(i8, -1), main.event.wheel.delta_y);
    p.write("\x1b[?1049h\x1b[?1007h");
    const alt = p.pan(0, -12, 1000, -5).?;
    try std.testing.expectEqual(@as(i8, 1), alt.event.wheel.delta_y);
    try std.testing.expectEqual(@as(u16, 79), alt.event.wheel.x);
    try std.testing.expectEqual(@as(u16, 0), alt.event.wheel.y);
}

test "pan: alternate scroll honors the application's cursor keys and opt-out" {
    const p = try newPane();
    defer p.destroy();
    p.setFontSizePx(10);
    p.surface_h = 288;
    p.write("\x1b[?1049h\x1b[?1007h");
    try std.testing.expect(p.pan(0, 6, 4, 4) == null);
    try std.testing.expectEqualStrings("\x1b[A", p.pan(0, 6, 4, 4).?.event.cursor);
    p.write("\x1b[?1h");
    try std.testing.expectEqualStrings("\x1bOB", p.pan(0, -12, 4, 4).?.event.cursor);
    p.write("\x1b[?1007l");
    try std.testing.expect(p.pan(0, 12, 4, 4) == null);
    p.write("\x1b[?1049l\x1b[?1007h");
    try std.testing.expect(p.pan(0, 12, 4, 4) == null);
}

fn findComplete(pane: *Pane, query: []const u8, direction: i32) !P.SearchResult {
    var result = try pane.find(query, direction);
    for (0..1000) |_| {
        if (!result.pending) return result;
        result = try pane.find(query, 0);
    }
    return error.SearchTimedOut;
}

test "find traverses history and live grid, wraps Korean, preserves hard lines and isolates panes" {
    const pane = try newPane();
    defer pane.destroy();
    const other = try newPane();
    defer other.destroy();
    pane.resize(12, 4, 6, 12);
    pane.write("1234567890한글\r\n");
    for (0..100) |_| pane.write("line\r\n");
    pane.write("한글\r\nhard\r\nbreak");
    other.write("한글");
    try std.testing.expectEqual(@as(u32, 2), (try findComplete(pane, "한글", 0)).count);
    try std.testing.expectEqual(@as(u32, 2), (try findComplete(pane, "한글", 1)).position);
    const hit = pane.search_hit.?;
    try std.testing.expect(hit.start.y < hit.end.y);
    try std.testing.expect(other.search_hit == null);
    try std.testing.expect(!pane.hasSelection());
    try std.testing.expectEqual(@as(u32, 1), (try findComplete(pane, "한글", -1)).position);
    try std.testing.expectEqual(@as(u32, 2), (try findComplete(pane, "한글", -1)).position);
    try std.testing.expectEqual(@as(u32, 0), (try findComplete(pane, "hardbreak", 0)).count);
    try std.testing.expect(pane.search_hit == null);
    pane.write("\r\n한글");
    try std.testing.expectEqual(@as(u32, 3), (try findComplete(pane, "한글", 0)).count);
    pane.write("\x1b[?1049h");
    try std.testing.expectEqual(@as(u32, 0), (try findComplete(pane, "한글", 0)).count);
    pane.write("\x1b[?1049l");
    try std.testing.expectEqual(@as(u32, 3), (try findComplete(pane, "한글", 0)).count);
    _ = try pane.restoreSnapshot(stream);
    try std.testing.expect(pane.search == null and pane.search_hit == null);
    _ = try findComplete(pane, "hello", 0);
    _ = try pane.find("", 0);
    try std.testing.expect(pane.search == null and pane.search_hit == null);
}

test {
    _ = @import("test_links.zig");
    // The renderer's own tests (its GL-free part).
    _ = Renderer;
    _ = @import("Trace.zig");
}

/// A 20×3 grid of 6×12 px cells. `tapCell` taps the middle of a cell.
fn linkPane() !*Pane {
    const p = try newPane();
    p.setFontSizePx(10);
    p.resize(20, 3, 6, 12);
    p.surface_w = 120;
    p.surface_h = 36;
    return p;
}

fn tapCell(x: u16, y: u16) [2]f32 {
    return .{ @as(f32, @floatFromInt(x)) * 6 + 3, @as(f32, @floatFromInt(y)) * 12 + 6 };
}

fn expectLinkAt(p: *Pane, at: [2]f32, kind: P.LinkKind, target: []const u8) !P.Link {
    const link = (try p.linkAt(std.testing.allocator, at[0], at[1])) orelse return error.NoLink;
    errdefer link.deinit(std.testing.allocator);
    try std.testing.expectEqual(kind, link.kind);
    try std.testing.expectEqualStrings(target, link.target);
    return link;
}

test "a tap on a URL that wraps onto the next row finds the whole URL" {
    const p = try linkPane();
    defer p.destroy();
    p.write("see https://example.com/abc"); // "com/abc" wraps onto row 1
    const link = try expectLinkAt(p, tapCell(2, 1), .url, "https://example.com/abc");
    defer link.deinit(std.testing.allocator);
    try std.testing.expectEqual(@as(u16, 4), link.start.x);
    try std.testing.expectEqual(@as(u32, 0), link.start.y);
    try std.testing.expectEqual(@as(u16, 6), link.end.x);
    try std.testing.expectEqual(@as(u32, 1), link.end.y);
    const first = try expectLinkAt(p, tapCell(5, 0), .url, "https://example.com/abc");
    first.deinit(std.testing.allocator);
}

test "a tap off any link finds none" {
    const p = try linkPane();
    defer p.destroy();
    p.write("see https://example.com x");
    try std.testing.expect((try p.linkAt(std.testing.allocator, tapCell(1, 0)[0], tapCell(1, 0)[1])) == null);
    try std.testing.expect((try p.linkAt(std.testing.allocator, tapCell(10, 2)[0], tapCell(10, 2)[1])) == null);
    try std.testing.expect((try p.linkAt(std.testing.allocator, 500, 6)) == null);
}

test "an OSC 8 hyperlink is the link of every cell it covers" {
    const p = try linkPane();
    defer p.destroy();
    p.write("\x1b]8;;https://docs.example/x\x1b\\click here\x1b]8;;\x1b\\ plain");
    const link = try expectLinkAt(p, tapCell(7, 0), .url, "https://docs.example/x");
    defer link.deinit(std.testing.allocator);
    try std.testing.expectEqual(@as(u16, 0), link.start.x);
    try std.testing.expectEqual(@as(u16, 9), link.end.x);
    try std.testing.expect((try p.linkAt(std.testing.allocator, tapCell(13, 0)[0], tapCell(13, 0)[1])) == null);
}

test "an OSC 8 file link and a loopback link are told apart" {
    const p = try linkPane();
    defer p.destroy();
    p.write("\x1b]8;;file://host/home/me/a.png\x1b\\a.png\x1b]8;;\x1b\\ \x1b]8;;http://localhost:5173/\x1b\\app\x1b]8;;\x1b\\");
    const file = try expectLinkAt(p, tapCell(1, 0), .file, "file://host/home/me/a.png");
    file.deinit(std.testing.allocator);
    const local = try expectLinkAt(p, tapCell(7, 0), .localhost, "http://localhost:5173/");
    local.deinit(std.testing.allocator);
}

test "a link tells whether a program set it and whether its text shows its target" {
    const p = try linkPane();
    defer p.destroy();
    // Row 0: a hyperlink under other text. Row 1: one on blank cells. Row 2: a URL in the text.
    p.write("\x1b]8;;https://evil.example/x\x1b\\click here\x1b]8;;\x1b\\\r\n");
    p.write("\x1b]8;;https://evil.example/y\x1b\\    \x1b]8;;\x1b\\\r\n");
    p.write("see https://x.com/a");
    const hidden = try expectLinkAt(p, tapCell(7, 0), .url, "https://evil.example/x");
    hidden.deinit(std.testing.allocator);
    try std.testing.expect(hidden.set and !hidden.shown);
    const blank = try expectLinkAt(p, tapCell(2, 1), .url, "https://evil.example/y");
    blank.deinit(std.testing.allocator);
    try std.testing.expect(blank.set and !blank.shown);
    const read = try expectLinkAt(p, tapCell(6, 2), .url, "https://x.com/a");
    read.deinit(std.testing.allocator);
    try std.testing.expect(!read.set and read.shown);
}

test "a hyperlink a program set on its own target shows it, also over a wrap" {
    const p = try linkPane();
    defer p.destroy();
    // 22 cells on a 20 column grid: the last two wrap onto row 1.
    p.write("\x1b]8;;https://docs.example/a\x1b\\https://docs.example/a\x1b]8;;\x1b\\\r\n");
    // Another target under text that reads as a URL.
    p.write("\x1b]8;;https://evil.example/\x1b\\https://x.com/\x1b]8;;\x1b\\");
    const same = try expectLinkAt(p, tapCell(1, 1), .url, "https://docs.example/a");
    same.deinit(std.testing.allocator);
    try std.testing.expect(same.set and same.shown);
    const other = try expectLinkAt(p, tapCell(3, 2), .url, "https://evil.example/");
    other.deinit(std.testing.allocator);
    try std.testing.expect(other.set and !other.shown);
}

test "a path reports its line, and a bare loopback address gets http" {
    const p = try linkPane();
    defer p.destroy();
    p.write("src/a.kt:12 x\r\non localhost:3000.");
    const path = try expectLinkAt(p, tapCell(2, 0), .file, "src/a.kt");
    defer path.deinit(std.testing.allocator);
    try std.testing.expectEqual(@as(u32, 12), path.line);
    try std.testing.expectEqual(@as(u16, 10), path.end.x);
    const local = try expectLinkAt(p, tapCell(5, 1), .localhost, "http://localhost:3000");
    local.deinit(std.testing.allocator);
}

test "a marked link is the pane's link mark until it is cleared" {
    const p = try linkPane();
    defer p.destroy();
    p.write("see https://example.com/abc");
    try std.testing.expect(!p.markLink(tapCell(1, 0)[0], tapCell(1, 0)[1]));
    try std.testing.expect(p.link_mark == null);
    try std.testing.expect(p.markLink(tapCell(2, 1)[0], tapCell(2, 1)[1]));
    const mark = p.link_mark orelse return error.NoMark;
    try std.testing.expect(mark.contains(4, 0) and mark.contains(19, 0) and mark.contains(6, 1));
    try std.testing.expect(!mark.contains(3, 0) and !mark.contains(7, 1));
    p.unmarkLink();
    try std.testing.expect(p.link_mark == null);
}

test "a URL over many rows is found whole from any of its rows" {
    const p = try linkPane();
    defer p.destroy();
    var url: [400]u8 = undefined;
    @memcpy(url[0..14], "https://x.com/");
    @memset(url[14..], 'a');
    p.write(&url); // 20 rows of 20 columns; the last 3 are on screen
    const link = try expectLinkAt(p, tapCell(5, 1), .url, &url);
    link.deinit(std.testing.allocator);
}

test "a URL longer than the rows read around a tap is no link rather than a cut one" {
    const p = try linkPane();
    defer p.destroy();
    var url: [20 * 80]u8 = undefined;
    @memcpy(url[0..14], "https://x.com/");
    @memset(url[14..], 'a');
    p.write(&url);
    // Drag the history down until the URL's first row, with its scheme, is the top row.
    _ = p.pan(0, 12 * 100, 0, 0);
    const top = (try p.linkAt(std.testing.allocator, tapCell(5, 0)[0], tapCell(5, 0)[1]));
    defer if (top) |link| link.deinit(std.testing.allocator);
    try std.testing.expect(top == null);
}

test "a long OSC 8 hyperlink keeps its whole target" {
    // Ghostty's OSC parser takes up to 2048 bytes, "8;;" included: a longer link never exists.
    const p = try linkPane();
    defer p.destroy();
    var uri: [2040]u8 = undefined;
    @memcpy(uri[0..14], "https://x.com/");
    @memset(uri[14..], 'b');
    var buf: [2100]u8 = undefined;
    const seq = try std.fmt.bufPrint(&buf, "\x1b]8;;{s}\x1b\\click here\x1b]8;;\x1b\\", .{uri});
    p.write(seq);
    const link = try expectLinkAt(p, tapCell(2, 0), .url, &uri);
    link.deinit(std.testing.allocator);
}

test "a pane scrolled back into history has no cell for a click" {
    const p = try linkPane();
    defer p.destroy();
    p.write("1\r\n2\r\n3\r\n4\r\n5\r\n6\r\n7\r\n8");
    p.write("\x1b[?1000h");
    try std.testing.expect(p.mouseCell(tapCell(1, 1)[0], tapCell(1, 1)[1]) != null);
    p.write("\x1b[?1000l");
    _ = p.pan(0, 24, 0, 0); // two rows back into history
    p.write("\x1b[?1000h");
    // The program counts rows from the bottom; the rows on screen are older ones.
    try std.testing.expect(p.mouseCell(tapCell(1, 1)[0], tapCell(1, 1)[1]) == null);
}
