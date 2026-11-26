//! Host tests for the pure parts of Core and DataStream, and for the parts that need
//! no server: applying a recorded terminal stream to a pane, and the core's lifecycle.
//! Runs through native/src/tests.zig (`cd native && zig build test`).
//! The `live:` tests need a reachable Rex server and are skipped without VELO_URL. They
//! create and destroy sessions labelled `velo-size-test` or `velo-test-core-*`, and
//! change no other. (A core follows every session: it reads the view of the others.)
const std = @import("std");
const pane = @import("pane");
/// libghostty-vt's C API, to encode a snapshot as the server does.
const vt = @import("c");
const core_mod = @import("Core.zig");
const ds = @import("DataStream.zig");
const model = @import("model.zig");
const protocol = @import("protocol.zig");
const rules = @import("SizeRules.zig");
const ws_mod = @import("../net/WebSocket.zig");
const test_net = @import("../net/test_net.zig");

const testing = std.testing;
const Core = core_mod.Core;

const view_json = @embedFile("testdata/view.json");
const view_floating_json = @embedFile("testdata/view_floating.json");
const stream_bin = @embedFile("testdata/stream.bin");
const hosts_json = @embedFile("testdata/hosts.json");
const hosts_hash = "3a7bd3e2360a3d29eea436fcfb7e44c735d117c42d1c1835420b6b9942dd4f1b";

// Blocks of the recorded views.
const block_a = "block:00000000-0000-7000-a000-000000000005"; // focused
const block_b = "block:00000000-0000-7000-a000-000000000010";
const block_d = "block:00000000-0000-7000-a000-000000000008"; // floating in view_floating

test "keyAction maps Android KeyEvent actions" {
    try testing.expectEqual(@as(?u8, 0), core_mod.keyAction(0)); // ACTION_DOWN
    try testing.expectEqual(@as(?u8, 1), core_mod.keyAction(1)); // ACTION_UP
    try testing.expectEqual(@as(?u8, 2), core_mod.keyAction(2)); // ACTION_MULTIPLE → repeat
    try testing.expectEqual(@as(?u8, null), core_mod.keyAction(3));
    try testing.expectEqual(@as(?u8, null), core_mod.keyAction(255));
}

test "touchAction maps Android MotionEvent actions to pane actions" {
    try testing.expectEqual(@as(?u8, 0), core_mod.touchAction(0)); // ACTION_DOWN → down
    try testing.expectEqual(@as(?u8, 2), core_mod.touchAction(1)); // ACTION_UP → up
    try testing.expectEqual(@as(?u8, 1), core_mod.touchAction(2)); // ACTION_MOVE → move
    try testing.expectEqual(@as(?u8, 2), core_mod.touchAction(3)); // ACTION_CANCEL → up
    try testing.expectEqual(@as(?u8, null), core_mod.touchAction(4)); // ACTION_OUTSIDE
    try testing.expectEqual(@as(?u8, null), core_mod.touchAction(5)); // ACTION_POINTER_DOWN
}

test "backoff goes from 1 s to 15 s" {
    const expected = [_]u32{ 1000, 2000, 4000, 8000, 15_000, 15_000, 15_000 };
    for (expected, 0..) |ms, attempt| {
        try testing.expectEqual(ms, core_mod.backoffMs(@intCast(attempt)));
    }
    try testing.expectEqual(@as(u32, 15_000), core_mod.backoffMs(31));
    try testing.expectEqual(@as(u32, 15_000), core_mod.backoffMs(32));
    try testing.expectEqual(@as(u32, 15_000), core_mod.backoffMs(std.math.maxInt(u32)));
    // Never decreases.
    var previous: u32 = 0;
    for (0..40) |attempt| {
        const ms = core_mod.backoffMs(@intCast(attempt));
        try testing.expect(ms >= previous);
        previous = ms;
    }
}

test "writeChunkLen never cuts a UTF-8 sequence" {
    try testing.expectEqual(@as(usize, 5), core_mod.writeChunkLen("hello", 8));
    try testing.expectEqual(@as(usize, 5), core_mod.writeChunkLen("hello", 5));
    try testing.expectEqual(@as(usize, 3), core_mod.writeChunkLen("hello", 3));
    // "a한b": 'a', 3 bytes of U+D55C, 'b'.
    const text = "a\xed\x95\x9cb";
    try testing.expectEqual(@as(usize, 1), core_mod.writeChunkLen(text, 1));
    try testing.expectEqual(@as(usize, 1), core_mod.writeChunkLen(text, 2));
    try testing.expectEqual(@as(usize, 1), core_mod.writeChunkLen(text, 3));
    try testing.expectEqual(@as(usize, 4), core_mod.writeChunkLen(text, 4));
    try testing.expectEqual(@as(usize, 5), core_mod.writeChunkLen(text, 5));
    // Bytes that are not UTF-8 still make progress.
    try testing.expectEqual(@as(usize, 2), core_mod.writeChunkLen("\x80\x80\x80\x80", 2));
}

fn parseView(arena: std.mem.Allocator, text: []const u8) !model.View {
    const value = try std.json.parseFromSliceLeaky(std.json.Value, arena, text, .{});
    return model.parseView(arena, value);
}

test "dockAnchor picks the focused tiled block of the block's window" {
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    const view = try parseView(arena.allocator(), view_floating_json);

    // The floating block docks beside the focused tiled block.
    try testing.expectEqualStrings(block_a, core_mod.dockAnchor(&view, block_d).?);
    // A block of no window has no anchor.
    try testing.expectEqual(@as(?model.Id, null), core_mod.dockAnchor(&view, "block:unknown"));
}

test "dockAnchor never returns the block itself" {
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    const view = try parseView(arena.allocator(), view_json);

    // The focused block is tiled: docking it falls back to the first other leaf.
    const anchor = core_mod.dockAnchor(&view, block_a).?;
    try testing.expect(!std.mem.eql(u8, anchor, block_a));
    // Another tiled block docks beside the focused one.
    try testing.expectEqualStrings(block_a, core_mod.dockAnchor(&view, block_b).?);
}

test "dockAnchor falls back to the first tiled block when focus is not tiled" {
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    var view = try parseView(arena.allocator(), view_floating_json);
    // Focus on the floating block itself.
    view.windows[0].focused = block_d;
    const anchor = core_mod.dockAnchor(&view, block_d).?;
    try testing.expect(!std.mem.eql(u8, anchor, block_d));
    // A window whose only tiled block is the one asked about has no anchor.
    var leaf: model.Layout = .{ .block = block_d };
    view.windows[0].layout = &leaf;
    try testing.expectEqual(@as(?model.Id, null), core_mod.dockAnchor(&view, block_d));
    // Nor has a window without a tiled layout.
    view.windows[0].layout = null;
    try testing.expectEqual(@as(?model.Id, null), core_mod.dockAnchor(&view, block_d));
}

// ------------------------------------------------------------ stream → pane

fn noGlyph(_: *anyopaque, _: []const u8, _: bool, _: bool, _: u32, _: u32, _: u32) ?pane.GlyphBitmap {
    return null;
}

var glyph_ctx: u8 = 0;
const no_glyphs: pane.GlyphSource = .{ .ctx = &glyph_ctx, .rasterize = noGlyph };

/// Channel nonce of the Rex messages in stream.bin.
fn fixtureNonce() !u64 {
    const intro = "\x1b_rex1;";
    const start = (std.mem.find(u8, stream_bin, intro) orelse return error.NoRexMessage) + intro.len;
    const end = std.mem.findScalarPos(u8, stream_bin, start, ';') orelse return error.NoRexMessage;
    return std.fmt.parseUnsigned(u64, stream_bin[start..end], 10);
}

fn expectFixtureScreen(p: *pane.Pane) !void {
    const text = try p.copyText(testing.allocator);
    defer testing.allocator.free(text);
    try testing.expect(std.mem.find(u8, text, "corelane stream fixture") != null);
    try testing.expect(std.mem.find(u8, text, "after-foreign-apc") != null);
    try testing.expect(std.mem.find(u8, text, "tail line") != null);
    // Rex's own message never reaches the terminal as text.
    try testing.expect(std.mem.find(u8, text, "rex1") == null);
    var buf: [64]u8 = undefined;
    try testing.expectEqualStrings("corelane-title", p.title(&buf).?);
}

test "Consumer restores the snapshot and applies the stream, in any chunking" {
    const nonce = try fixtureNonce();
    for ([_]usize{ stream_bin.len, 1, 7, 64, 1000 }) |chunk| {
        const p = try pane.Pane.create(testing.allocator, no_glyphs);
        defer p.destroy();
        var consumer: ds.Consumer = .init(testing.allocator, p);
        defer consumer.deinit();
        consumer.begin(nonce);

        var offset: usize = 0;
        var restored_at: ?usize = null;
        while (offset < stream_bin.len) {
            const end = @min(offset + chunk, stream_bin.len);
            try consumer.feed(stream_bin[offset..end]);
            offset = end;
            if (restored_at == null and consumer.restored) restored_at = offset;
        }

        try testing.expect(consumer.restored);
        // The decoder gets the snapshot once, when its last record is in, even when record
        // headers are cut between chunks (#352).
        try testing.expectEqual(@as(u32, 1), consumer.restore_tries);
        // Every stream byte counts for RESUME, the snapshot included.
        try testing.expectEqual(@as(u64, stream_bin.len), consumer.consumed);
        // The snapshot buffer is released once it is in the pane.
        try testing.expectEqual(@as(usize, 0), consumer.snapshot.items.len);
        // The snapshot is complete before the first Rex message.
        const first_message = std.mem.find(u8, stream_bin, "\x1b_rex1;").?;
        try testing.expect(restored_at.? <= first_message + chunk);
        try expectFixtureScreen(p);
    }
}

/// A snapshot of a terminal with `lines` lines of output, most of them scrollback, encoded
/// as the server encodes one. The caller frees it.
fn bigSnapshot(lines: usize) ![]u8 {
    return markedSnapshot(lines, "scrollback");
}

/// `bigSnapshot` with `word` in place of "scrollback" in every line.
fn markedSnapshot(lines: usize, word: []const u8) ![]u8 {
    var term: vt.GhosttyTerminal = null;
    if (vt.ghostty_terminal_new(null, &term, 80, 24) != vt.GHOSTTY_SUCCESS) return error.OutOfMemory;
    defer vt.ghostty_terminal_free(term);
    var buf: [96]u8 = undefined;
    for (0..lines) |i| {
        const line = try std.fmt.bufPrint(&buf, "{d:0>6} \x1b[3{d}m{s} line\x1b[0m of the snapshot test\r\n", .{ i, i % 7, word });
        vt.ghostty_terminal_vt_write(term, line.ptr, line.len);
    }
    var len: usize = 0;
    _ = vt.ghostty_snapshot_encode_buf(term, null, 0, &len);
    const out = try testing.allocator.alloc(u8, len);
    errdefer testing.allocator.free(out);
    if (vt.ghostty_snapshot_encode_buf(term, out.ptr, out.len, &len) != vt.GHOSTTY_SUCCESS) return error.Encode;
    return out[0..len];
}

test "Consumer decodes a large snapshot once, however many chunks it comes in (#352)" {
    const lines = 600;
    const snapshot = try bigSnapshot(lines);
    defer testing.allocator.free(snapshot);
    const chunk = 1024;
    // It really comes in many pieces (the snapshot's pages make it some 100 KB).
    try testing.expect(snapshot.len / chunk >= 40);
    const p = try pane.Pane.create(testing.allocator, no_glyphs);
    defer p.destroy();
    var consumer: ds.Consumer = .init(testing.allocator, p);
    defer consumer.deinit();
    consumer.begin(1);
    var offset: usize = 0;
    while (offset < snapshot.len) {
        const end = @min(offset + chunk, snapshot.len);
        try consumer.feed(snapshot[offset..end]);
        offset = end;
    }
    try testing.expect(consumer.restored);
    // Each try decodes the buffer from its start: one per chunk grows with the square of its length.
    try testing.expectEqual(@as(u32, 1), consumer.restore_tries);
    const text = try p.copyText(testing.allocator);
    defer testing.allocator.free(text);
    var last: [32]u8 = undefined;
    try testing.expect(std.mem.find(u8, text, try std.fmt.bufPrint(&last, "{d:0>6} scrollback line", .{lines - 1})) != null);
}

test "Consumer ends a snapshot whose record claims more than the limit, before buffering it (#352)" {
    const p = try pane.Pane.create(testing.allocator, no_glyphs);
    defer p.destroy();
    var consumer: ds.Consumer = .init(testing.allocator, p);
    defer consumer.deinit();
    consumer.begin(1);
    // A broken length: the record would end some 4 GB in.
    const envelope = "GHOSTSNP\x01\x00";
    const header = "\x01\x00" ++ "\xf0\xff\xff\xff" ++ "crc!";
    try testing.expectError(error.SnapshotTooLarge, consumer.feed(envelope ++ header ++ "payload"));
    try testing.expectEqual(@as(u32, 0), consumer.restore_tries);
}

test "Consumer ends a damaged snapshot once its last record is in, without trying again (#352)" {
    const snapshot = try markedSnapshot(300, "damaged");
    defer testing.allocator.free(snapshot);
    // One byte of the first record's payload changes: its CRC no longer matches.
    snapshot[10 + 10 + 2] ^= 0xff;
    const p = try pane.Pane.create(testing.allocator, no_glyphs);
    defer p.destroy();
    var consumer: ds.Consumer = .init(testing.allocator, p);
    defer consumer.deinit();
    consumer.begin(1);
    var result: anyerror!void = {};
    var offset: usize = 0;
    while (offset < snapshot.len) : (offset = @min(offset + 1024, snapshot.len)) {
        result = consumer.feed(snapshot[offset..@min(offset + 1024, snapshot.len)]);
        result catch break;
    }
    // With FINISH in, a decoder that wants more has met a broken record: the stream starts over.
    try testing.expectError(error.InvalidSnapshot, result);
    try testing.expectEqual(@as(u32, 1), consumer.restore_tries);
}

test "Consumer drops a half-received snapshot when a new stream begins (#352)" {
    const first = try markedSnapshot(300, "first");
    defer testing.allocator.free(first);
    const second = try markedSnapshot(300, "second");
    defer testing.allocator.free(second);
    const p = try pane.Pane.create(testing.allocator, no_glyphs);
    defer p.destroy();
    var consumer: ds.Consumer = .init(testing.allocator, p);
    defer consumer.deinit();

    consumer.begin(1);
    try consumer.feed(first[0 .. first.len / 2]);
    try testing.expect(!consumer.restored);
    // The connection dropped and could not be resumed: a new ticket, a new HELLO.
    consumer.begin(2);
    try testing.expectEqual(@as(usize, 0), consumer.snapshot.capacity);
    var offset: usize = 0;
    while (offset < second.len) {
        const end = @min(offset + 1024, second.len);
        try consumer.feed(second[offset..end]);
        offset = end;
    }
    try testing.expect(consumer.restored);
    try testing.expectEqual(@as(u32, 1), consumer.restore_tries);
    const text = try p.copyText(testing.allocator);
    defer testing.allocator.free(text);
    try testing.expect(std.mem.find(u8, text, "second line") != null);
    try testing.expect(std.mem.find(u8, text, "first line") == null);
}

test "Framing leaves a snapshot it does not know to the decoder" {
    var framing: ds.Framing = .{};
    // Not yet ten bytes: no envelope to judge.
    try testing.expect(!try framing.complete("GHOSTSN", 1 << 20));
    // Version 2 is not known here: every call says "try the decoder".
    try testing.expect(try framing.complete("GHOSTSNP\x02\x00", 1 << 20));
    try testing.expect(try framing.complete("GHOSTSNP\x02\x00more", 1 << 20));

    // Version 1: a record that is not FINISH, then FINISH with its payload split.
    var v1: ds.Framing = .{};
    const envelope = "GHOSTSNP\x01\x00";
    const terminal = "\x01\x00" ++ "\x03\x00\x00\x00" ++ "crc!" ++ "abc";
    const finish = "\x06\x00" ++ "\x02\x00\x00\x00" ++ "crc!" ++ "xy";
    const whole = envelope ++ terminal ++ finish;
    try testing.expect(!try v1.complete(envelope ++ terminal, 1 << 20));
    try testing.expect(!try v1.complete(whole[0 .. whole.len - 1], 1 << 20));
    try testing.expect(try v1.complete(whole, 1 << 20));
    // The same records against a limit they exceed.
    var small: ds.Framing = .{};
    try testing.expectError(error.SnapshotTooLarge, small.complete(whole, whole.len - 1));
    // Headers cut between calls: nothing is read until all ten bytes of one are in.
    var split: ds.Framing = .{};
    try testing.expect(!try split.complete(whole[0 .. envelope.len + 4], 1 << 20));
    try testing.expect(!try split.complete(whole[0 .. envelope.len + terminal.len + 3], 1 << 20));
    try testing.expect(try split.complete(whole, 1 << 20));
}

test "Consumer.begin starts over with a new snapshot" {
    const nonce = try fixtureNonce();
    const p = try pane.Pane.create(testing.allocator, no_glyphs);
    defer p.destroy();
    var consumer: ds.Consumer = .init(testing.allocator, p);
    defer consumer.deinit();

    // Feeding before a stream began is refused and counts nothing.
    try testing.expectError(error.StreamNotStarted, consumer.feed("x"));
    try testing.expectEqual(@as(u64, 0), consumer.consumed);

    consumer.begin(nonce);
    try consumer.feed(stream_bin);
    try consumer.feed("extra line\r\n");
    try testing.expectEqual(@as(u64, stream_bin.len + 12), consumer.consumed);

    // A new stream (new ticket + HELLO) sends a fresh snapshot.
    consumer.begin(nonce);
    try testing.expect(!consumer.restored);
    try testing.expectEqual(@as(u64, 0), consumer.consumed);
    try consumer.feed(stream_bin[0..100]);
    try testing.expect(!consumer.restored);
    try consumer.feed(stream_bin[100..]);
    try testing.expect(consumer.restored);
    try testing.expectEqual(@as(u64, stream_bin.len), consumer.consumed);

    const text = try p.copyText(testing.allocator);
    defer testing.allocator.free(text);
    try testing.expect(std.mem.find(u8, text, "corelane stream fixture") != null);
    try testing.expect(std.mem.find(u8, text, "extra line") == null);
}

test "Consumer passes Rex messages with another nonce to the terminal" {
    const p = try pane.Pane.create(testing.allocator, no_glyphs);
    defer p.destroy();
    var consumer: ds.Consumer = .init(testing.allocator, p);
    defer consumer.deinit();
    // With a nonce the fixture does not use, its resize is program output: an APC the
    // terminal ignores. The stream still applies.
    consumer.begin((try fixtureNonce()) +% 1);
    try consumer.feed(stream_bin);
    try testing.expect(consumer.restored);
    const text = try p.copyText(testing.allocator);
    defer testing.allocator.free(text);
    try testing.expect(std.mem.find(u8, text, "corelane stream fixture") != null);
}

// ------------------------------------------------------------ core lifecycle

const Recorder = struct {
    lock: pane.Lock = .{},
    states: std.ArrayList([]u8) = .empty,
    wire_states: bool = false,
    last_gen: u64 = 0,
    last_full: bool = false,
    errors: std.ArrayList([]u8) = .empty,
    /// The message of each error, in the order of `errors`.
    messages: std.ArrayList([]u8) = .empty,
    /// The fix of each error ("" for none), in the order of `errors`.
    fixes: std.ArrayList([]u8) = .empty,
    tokens: usize = 0,
    reports: std.ArrayList([]u8) = .empty,
    writes: std.ArrayList(struct { tag: []u8, result: Core.PaneWriteResult, has_message: bool }) = .empty,

    fn deinit(self: *Recorder) void {
        const gpa = testing.allocator;
        for (self.states.items) |s| gpa.free(s);
        for (self.errors.items) |s| gpa.free(s);
        for (self.messages.items) |s| gpa.free(s);
        for (self.fixes.items) |s| gpa.free(s);
        for (self.reports.items) |r| gpa.free(r);
        for (self.writes.items) |w| gpa.free(w.tag);
        self.writes.deinit(gpa);
        self.reports.deinit(gpa);
        self.states.deinit(gpa);
        self.errors.deinit(gpa);
        self.messages.deinit(gpa);
        self.fixes.deinit(gpa);
    }

    fn host(self: *Recorder) core_mod.Host {
        return .{ .ctx = self, .onState = onState, .onPaneReport = onPaneReport, .onPaneWrite = onPaneWrite, .onReconnectToken = onToken, .onError = onError, .glyphs = no_glyphs };
    }

    fn onState(ctx: *anyopaque, gen: u64, snapshot: []const u8, patch: ?[]const u8) void {
        const self: *Recorder = @ptrCast(@alignCast(ctx));
        self.lock.lock();
        defer self.lock.unlock();
        self.last_gen = gen;
        self.last_full = patch == null;
        const text = if (self.wire_states) patch orelse snapshot else snapshot;
        const copy = testing.allocator.dupe(u8, text) catch return;
        self.states.append(testing.allocator, copy) catch testing.allocator.free(copy);
    }

    fn onPaneReport(ctx: *anyopaque, text: []const u8) void {
        const self: *Recorder = @ptrCast(@alignCast(ctx));
        const copy = testing.allocator.dupe(u8, text) catch return;
        self.reports.append(testing.allocator, copy) catch testing.allocator.free(copy);
    }

    fn onPaneWrite(ctx: *anyopaque, tag: []const u8, result: Core.PaneWriteResult, message: []const u8) void {
        const self: *Recorder = @ptrCast(@alignCast(ctx));
        self.lock.lock();
        defer self.lock.unlock();
        const copy = testing.allocator.dupe(u8, tag) catch @panic("out of memory");
        self.writes.append(testing.allocator, .{ .tag = copy, .result = result, .has_message = message.len != 0 }) catch @panic("out of memory");
    }

    // Consume the one completion for this command. A prior answer with the same tag cannot satisfy it.
    fn expectPaneWrite(self: *Recorder, tag: []const u8, result: Core.PaneWriteResult) !void {
        try testing.expectEqual(0, self.reports.items.len);
        try testing.expectEqual(1, self.writes.items.len);
        const w = self.writes.pop().?;
        defer testing.allocator.free(w.tag);
        try testing.expectEqualStrings(tag, w.tag);
        try testing.expectEqual(result, w.result);
        try testing.expectEqual(result != .sent, w.has_message);
    }

    fn onToken(ctx: *anyopaque, _: []const u8) void {
        const self: *Recorder = @ptrCast(@alignCast(ctx));
        self.lock.lock();
        defer self.lock.unlock();
        self.tokens += 1;
    }

    fn onError(ctx: *anyopaque, code: []const u8, message: []const u8, fix: []const u8) void {
        const self: *Recorder = @ptrCast(@alignCast(ctx));
        self.lock.lock();
        defer self.lock.unlock();
        const gpa = testing.allocator;
        const code_copy = gpa.dupe(u8, code) catch @panic("out of memory");
        const message_copy = gpa.dupe(u8, message) catch @panic("out of memory");
        const fix_copy = gpa.dupe(u8, fix) catch @panic("out of memory");
        self.errors.append(gpa, code_copy) catch @panic("out of memory");
        self.messages.append(gpa, message_copy) catch @panic("out of memory");
        self.fixes.append(gpa, fix_copy) catch @panic("out of memory");
    }

    /// Wait up to `ms` for at least `n` errors.
    fn waitErrors(self: *Recorder, n: usize, ms: u32) !void {
        var waited: u32 = 0;
        while (true) : (waited += 10) {
            self.lock.lock();
            const have = self.errors.items.len;
            self.lock.unlock();
            if (have >= n) return;
            if (waited >= ms) return error.Timeout;
            try std.Io.sleep(testing.io, .fromMilliseconds(10), .awake);
        }
    }

    fn errorCount(self: *Recorder) usize {
        self.lock.lock();
        defer self.lock.unlock();
        return self.errors.items.len;
    }

    /// Whether any published state had `"status":"STATUS"`.
    fn sawStatus(self: *Recorder, comptime status: []const u8) bool {
        self.lock.lock();
        defer self.lock.unlock();
        for (self.states.items) |state| {
            if (std.mem.find(u8, state, "\"status\":\"" ++ status ++ "\"") != null) return true;
        }
        return false;
    }

    /// Code, message and fix of error `i`, copied into `arena`.
    fn errorAt(self: *Recorder, arena: std.mem.Allocator, i: usize) !struct { code: []const u8, message: []const u8, fix: []const u8 } {
        self.lock.lock();
        defer self.lock.unlock();
        if (i >= self.errors.items.len) return error.NoError;
        return .{
            .code = try arena.dupe(u8, self.errors.items[i]),
            .message = try arena.dupe(u8, self.messages.items[i]),
            .fix = try arena.dupe(u8, self.fixes.items[i]),
        };
    }

    fn lastValue(self: *Recorder, arena: std.mem.Allocator, name: []const u8) !std.json.Value {
        self.lock.lock();
        defer self.lock.unlock();
        if (self.states.items.len == 0) return error.NoState;
        const text = self.states.items[self.states.items.len - 1];
        const value = try std.json.parseFromSliceLeaky(std.json.Value, arena, text, .{ .allocate = .alloc_always });
        return model.get(value, name) orelse error.NoField;
    }

    fn lastString(self: *Recorder, arena: std.mem.Allocator, name: []const u8) ![]const u8 {
        return model.str(try self.lastValue(arena, name)) orelse error.NoField;
    }

    fn lastStatus(self: *Recorder, arena: std.mem.Allocator) ![]const u8 {
        return self.lastString(arena, "status");
    }
};

test "Core: a change of status does not resend the pane catalog" {
    var recorder: Recorder = .{ .wire_states = true };
    defer recorder.deinit();
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    const core = try Core.create(testing.allocator, recorder.host(), .{}, 1);
    defer core.destroy();
    core.connect("ftp://not-a-server", null);
    try testing.expect(recorder.last_full);
    try testing.expectEqual(@as(u64, 1), recorder.last_gen);
    _ = try recorder.lastValue(arena.allocator(), "sessions");
    core.disconnect();
    try testing.expectEqualStrings("idle", try recorder.lastStatus(arena.allocator()));
    try testing.expectError(error.NoField, recorder.lastValue(arena.allocator(), "sessions"));
    try testing.expect(!recorder.last_full);
    try testing.expectEqual(@as(u64, 2), recorder.last_gen);
    core.disconnect(); // unchanged: no new generation
    try testing.expectEqual(@as(u64, 2), recorder.last_gen);
    core.requestState();
    try testing.expect(recorder.last_full);
    try testing.expectEqual(@as(u64, 3), recorder.last_gen);
    _ = try recorder.lastValue(arena.allocator(), "sessions");
}

test "Core: create and destroy without a connection" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    const core = try Core.create(testing.allocator, recorder.host(), .{}, 2.5);
    // Calls for blocks and sessions the core does not know are no-ops.
    core.key("block:none", 66, 0, 0, 0);
    core.paneScale("block:none", 2, false);
    core.panePan("block:none", 4, -3, 8, 9);
    core.paneTouch("block:none", 0, 1, 1);
    core.paneSurface("block:none", null, 0, 0);
    try testing.expectError(error.UnknownBlock, core.paneCopy(testing.allocator, "block:none"));
    try testing.expect(!core.paneReady("block:none"));
    core.destroy();
    // Nothing changed, so nothing was published.
    try testing.expectEqual(@as(usize, 0), recorder.states.items.len);
    try testing.expectEqual(@as(usize, 0), recorder.errors.items.len);
}

test "Core: a locator the core does not connect to is an error state" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();

    const core = try Core.create(testing.allocator, recorder.host(), .{}, 1);
    core.connect("ftp://rex.example.ts.net:8080", null);
    // connect publishes on the calling thread.
    try testing.expectEqualStrings("error", try recorder.lastStatus(arena.allocator()));
    try testing.expectEqual(@as(usize, 1), recorder.errors.items.len);
    try testing.expectEqualStrings("bad_url", recorder.errors.items[0]);

    core.disconnect();
    try testing.expectEqualStrings("idle", try recorder.lastStatus(arena.allocator()));
    core.destroy();
    try testing.expectEqual(@as(usize, 0), recorder.tokens);
}

test "Core: connect accepts every desktop locator" {
    var scratch: test_net.ScratchDir = .{};
    try scratch.init();
    defer scratch.deinit();
    var text: [96]u8 = undefined;
    // A socket path of this test only: nothing can answer on it.
    const socket_path = try std.fmt.bufPrint(&text, "{s}/rex.sock", .{scratch.path()});

    var recorder: Recorder = .{};
    defer recorder.deinit();
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    var gate: Gate = .{};
    const core = try Core.create(testing.allocator, recorder.host(), gate.net(), 1);
    defer core.destroy();
    // Before `destroy`, which waits for a connect in progress.
    defer gate.set(false);

    const accepted = [_][]const u8{
        "http://127.0.0.1:7890",
        "https://rex.fixture.example",
        "rex.example.ts.net:8443",
        socket_path,
        "  HTTPS://Rex.Example.ts.net:8443/ ",
        "ssh://me@h",
    };
    for (accepted, 0..) |locator, i| {
        core.connect(locator, null);
        try testing.expectEqualStrings("connecting", try recorder.lastStatus(arena.allocator()));
        // The locator as given, trimmed.
        try testing.expectEqualStrings(std.mem.trim(u8, locator, " "), try recorder.lastString(arena.allocator(), "server"));
        // No connection is ever made. The first locator (plain TCP: no CA bundle to load
        // first) takes the control thread into the closed gate before the next connect,
        // and the gate is its only way back, so it never takes up the later ones.
        if (i == 0) try gate.waitEntered(1, 5000);
    }
    try testing.expectEqual(@as(usize, 0), recorder.errors.items.len);

    core.connect("ftp://h", null);
    try testing.expectEqualStrings("error", try recorder.lastStatus(arena.allocator()));
    const ftp = try recorder.errorAt(arena.allocator(), 0);
    try testing.expectEqualStrings("bad_url", ftp.code);
    try testing.expectEqualStrings("Velo doesn't support ftp:// server addresses.", ftp.message);
}

test "Core: a refused locator explains why it was refused" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    const core = try Core.create(testing.allocator, recorder.host(), .{}, 1);
    defer core.destroy();

    const cases = [_]struct { locator: []const u8, code: []const u8, message: []const u8 }{
        .{ .locator = "", .code = "bad_url", .message = "Enter a server address." },
        .{ .locator = " \t\n", .code = "bad_url", .message = "Enter a server address." },
        .{ .locator = "https://rex example.ts.net", .code = "bad_url", .message = "Use spaces only in Unix socket paths, not in server URLs." },
        .{ .locator = "https://me:pw@rex.example.ts.net", .code = "bad_url", .message = "Remove the password from the server address." },
        // A password is refused as such, even in an ssh locator.
        .{ .locator = "ssh://me:pw@h", .code = "bad_url", .message = "Remove the password from the server address." },
        .{ .locator = "https://:8443", .code = "bad_url", .message = "Enter the hostname or IP address of the server." },
        .{ .locator = "unix://tmp/rex.sock", .code = "bad_url", .message = "Use a full Unix socket path starting with /, for example /tmp/rex.sock." },
        .{ .locator = "FTP://h", .code = "bad_url", .message = "Velo doesn't support ftp:// server addresses." },
        .{ .locator = "wss://rex.example.ts.net", .code = "bad_url", .message = "Velo doesn't support wss:// server addresses." },
        .{ .locator = "https://rex.example.ts.net:0", .code = "bad_url", .message = "Check the format of the server address." },
        .{ .locator = "https://user@rex.example.ts.net", .code = "bad_url", .message = "Check the format of the server address." },
        .{ .locator = "ssh://h", .code = "no_user", .message = "Missing SSH username in ssh://h. For example: ssh://user@h." },
        // An ssh locator without a host lacks the host first.
        .{ .locator = "ssh://", .code = "bad_url", .message = "Enter the hostname or IP address of the server." },
        .{ .locator = "ssh://:2222", .code = "bad_url", .message = "Enter the hostname or IP address of the server." },
        // Nor is a user suggested for a host or port that is no good.
        .{ .locator = "ssh://bad!host", .code = "bad_url", .message = "Check the format of the server address." },
        .{ .locator = "ssh://[", .code = "bad_url", .message = "Check the format of the server address." },
        .{ .locator = "ssh://h:99999", .code = "bad_url", .message = "Check the format of the server address." },
    };
    for (cases, 0..) |case, i| {
        core.connect(case.locator, null);
        try testing.expectEqualStrings("error", try recorder.lastStatus(arena.allocator()));
        const got = try recorder.errorAt(arena.allocator(), i);
        testing.expectEqualStrings(case.code, got.code) catch |err| {
            std.debug.print("locator '{s}'\n", .{case.locator});
            return err;
        };
        testing.expectEqualStrings(case.message, got.message) catch |err| {
            std.debug.print("locator '{s}'\n", .{case.locator});
            return err;
        };
        // A refusal has no command to copy.
        try testing.expectEqualStrings("", got.fix);
    }
}

/// Stands in for the app's SSH pool. `hold`: every dial waits for `release` first.
const SshFake = struct {
    lock: pane.Lock = .{},
    hold: bool = false,
    released: bool = false,
    problem: ?struct { code: []const u8, sentence: []const u8, fix: []const u8 } = null,
    /// What a dial does after the hold:
    /// - `fail`: fails, explained by `problem` when there is one.
    /// - `hang_up`: fills `problem` all the same, then hands out a socket whose far end
    ///   is closed (the dial worked, the upgrade fails).
    /// - `serve_first`: the first dial hands out a socket to `serveOneConnection`; the
    ///   later ones fail as `fail` does.
    mode: enum { fail, hang_up, serve_first } = .fail,
    /// The `serveOneConnection` thread of `serve_first`; joined by `finish`.
    server: ?std.Thread = null,
    /// Calls of `dial` so far, including those held.
    entered: usize = 0,
    /// The locator of the last dial.
    locator: [64]u8 = undefined,
    locator_len: usize = 0,

    fn net(self: *SshFake) core_mod.Net {
        return .{ .ssh = .{ .ctx = self, .dial = dial } };
    }

    fn dial(ctx: ?*anyopaque, locator: []const u8, problem: *core_mod.SshProblem) anyerror!std.posix.fd_t {
        const self: *SshFake = @ptrCast(@alignCast(ctx.?));
        self.lock.lock();
        defer self.lock.unlock();
        self.entered += 1;
        @memcpy(self.locator[0..locator.len], locator);
        self.locator_len = locator.len;
        while (self.hold and !self.released) self.lock.wait();
        if (self.mode == .serve_first and self.entered == 1) {
            const fds = try socketPair();
            self.server = std.Thread.spawn(.{}, serveOneConnection, .{fds[1]}) catch |err| {
                _ = std.c.close(fds[0]);
                _ = std.c.close(fds[1]);
                return err;
            };
            return fds[0];
        }
        if (self.problem) |p| problem.set(p.code, p.sentence, p.fix);
        if (self.mode == .hang_up) {
            const fds = try socketPair();
            _ = std.c.close(fds[1]);
            return fds[0];
        }
        return error.SshDialFailed;
    }

    fn dials(self: *SshFake) usize {
        self.lock.lock();
        defer self.lock.unlock();
        return self.entered;
    }

    /// Join the server thread of `serve_first`, if one started.
    fn finish(self: *SshFake) void {
        self.lock.lock();
        const server = self.server;
        self.server = null;
        self.lock.unlock();
        if (server) |t| t.join();
    }

    fn release(self: *SshFake) void {
        self.lock.lock();
        defer self.lock.unlock();
        self.released = true;
        self.lock.signal();
    }

    /// Hold the dials from now on, until `release`.
    fn holdDials(self: *SshFake) void {
        self.lock.lock();
        defer self.lock.unlock();
        self.hold = true;
        self.released = false;
    }

    /// Wait up to `ms` for `n` calls of `dial`; the locator of the last one.
    fn waitEntered(self: *SshFake, n: usize, ms: u32) ![]const u8 {
        var waited: u32 = 0;
        while (true) : (waited += 5) {
            self.lock.lock();
            const have = self.entered;
            self.lock.unlock();
            if (have >= n) return self.locator[0..self.locator_len];
            if (waited >= ms) return error.Timeout;
            try std.Io.sleep(testing.io, .fromMilliseconds(5), .awake);
        }
    }
};

fn socketPair() ![2]std.posix.fd_t {
    var fds: [2]std.posix.fd_t = undefined;
    if (std.c.socketpair(std.posix.AF.UNIX, std.posix.SOCK.STREAM, 0, &fds) != 0) return error.SystemResources;
    return fds;
}

/// The server end of one control connection on a socket: reads what the core sends and
/// writes frames the way a Rex server does.
const RexWire = struct {
    fd: std.posix.fd_t,
    buf: [16 * 1024]u8 = undefined,
    len: usize = 0,

    fn fill(self: *RexWire) !void {
        if (self.len == self.buf.len) return error.TooLong;
        const n = std.c.read(self.fd, self.buf[self.len..].ptr, self.buf.len - self.len);
        if (n <= 0) return error.EndOfStream;
        self.len += @intCast(n);
    }

    fn drop(self: *RexWire, n: usize) void {
        std.mem.copyForwards(u8, &self.buf, self.buf[n..self.len]);
        self.len -= n;
    }

    fn writeAll(self: *RexWire, bytes: []const u8) !void {
        var rest = bytes;
        while (rest.len > 0) {
            const n = std.c.write(self.fd, rest.ptr, rest.len);
            if (n <= 0) return error.WriteFailed;
            rest = rest[@intCast(n)..];
        }
    }

    /// Read the upgrade request and accept it.
    fn upgrade(self: *RexWire) !void {
        while (std.mem.find(u8, self.buf[0..self.len], "\r\n\r\n") == null) try self.fill();
        const head_len = std.mem.find(u8, self.buf[0..self.len], "\r\n\r\n").? + 4;
        var key: []const u8 = "";
        var lines = std.mem.splitSequence(u8, self.buf[0..head_len], "\r\n");
        while (lines.next()) |line| {
            const name = "Sec-WebSocket-Key:";
            if (std.ascii.startsWithIgnoreCase(line, name)) key = std.mem.trim(u8, line[name.len..], " ");
        }
        var accept: [28]u8 = undefined;
        ws_mod.acceptKey(key, &accept);
        self.drop(head_len);
        var response: [256]u8 = undefined;
        try self.writeAll(try std.fmt.bufPrint(&response, "HTTP/1.1 101 Switching Protocols\r\n" ++
            "Upgrade: websocket\r\n" ++
            "Connection: Upgrade\r\n" ++
            "Sec-WebSocket-Accept: {s}\r\n" ++
            "Sec-WebSocket-Protocol: {s}\r\n\r\n", .{ &accept, protocol.control_protocol }));
    }

    /// The payload of the next frame the core sends, unmasked, into `out`.
    fn frame(self: *RexWire, out: []u8) ![]u8 {
        while (true) {
            if (ws_mod.decodeHeader(self.buf[0..self.len])) |header| {
                const len: usize = @intCast(header.len);
                if (header.header_len + len <= self.len) {
                    if (len > out.len) return error.TooLong;
                    for (out[0..len], self.buf[header.header_len..][0..len], 0..) |*o, b, i| o.* = b ^ header.mask[i & 3];
                    self.drop(header.header_len + len);
                    return out[0..len];
                }
            }
            try self.fill();
        }
    }

    /// One unmasked text frame.
    fn sendText(self: *RexWire, text: []const u8) !void {
        var header: [4]u8 = .{ 0x81, 0, 0, 0 };
        if (text.len < 126) {
            header[1] = @intCast(text.len);
            try self.writeAll(header[0..2]);
        } else {
            header[1] = 126;
            std.mem.writeInt(u16, header[2..4], @intCast(text.len), .big);
            try self.writeAll(&header);
        }
        try self.writeAll(text);
    }
};

/// Plays a Rex server for one control connection on `fd`: the upgrade, the welcome, an
/// answer to the bootstrap with no sessions; then hangs up once the core sends its next
/// request (it took the answer).
fn serveOneConnection(fd: std.posix.fd_t) void {
    defer _ = std.c.close(fd);
    serveBootstrap(fd) catch {};
}

fn serveBootstrap(fd: std.posix.fd_t) !void {
    var wire: RexWire = .{ .fd = fd };
    try wire.upgrade();
    var message: [4096]u8 = undefined;
    _ = try wire.frame(&message); // hello
    try wire.sendText("{\"type\":\"welcome\",\"protocol\":\"rex.control.v1\",\"client\":{\"id\":\"client:ssh-test\",\"reconnect_token\":\"\"},\"api_revision\":18}");
    const request = try wire.frame(&message);
    const parsed = try std.json.parseFromSlice(struct { id: []const u8 }, testing.allocator, request, .{ .ignore_unknown_fields = true });
    defer parsed.deinit();
    var reply: [512]u8 = undefined;
    try wire.sendText(try std.fmt.bufPrint(&reply, "{{\"type\":\"response\",\"id\":\"{s}\",\"payload\":{{\"attach_all\":{{\"sessions\":[]}},\"selected_session\":null,\"data_tickets\":[]}}}}", .{parsed.value.id}));
    _ = try wire.frame(&message); // the core's next request: it is connected
}

test "Core: connect accepts ssh locators" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    var fake: SshFake = .{ .hold = true };
    const core = try Core.create(testing.allocator, recorder.host(), fake.net(), 1);
    defer core.destroy();
    // Before `destroy`, which waits for the dial in progress.
    defer fake.release();

    core.connect(" ssh://me@h:2222 ", null);
    try testing.expectEqualStrings("connecting", try recorder.lastStatus(arena.allocator()));
    try testing.expectEqualStrings("ssh://me@h:2222", try recorder.lastString(arena.allocator(), "server"));
    // The control thread asks the ssh dialer for the locator, trimmed.
    try testing.expectEqualStrings("ssh://me@h:2222", try fake.waitEntered(1, 5000));
    try testing.expectEqual(@as(usize, 0), recorder.errors.items.len);
}

test "Core: ssh without a user reports no_user" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    var fake: SshFake = .{};
    const core = try Core.create(testing.allocator, recorder.host(), fake.net(), 1);
    defer core.destroy();

    const cases = [_]struct { locator: []const u8, message: []const u8 }{
        .{ .locator = "ssh://h", .message = "Missing SSH username in ssh://h. For example: ssh://user@h." },
        .{ .locator = " SSH://h:2222 ", .message = "Missing SSH username in SSH://h:2222. For example: ssh://user@h." },
        .{ .locator = "ssh://@h", .message = "Missing SSH username in ssh://@h. For example: ssh://user@h." },
        .{ .locator = "ssh://[fd7a::1]:22", .message = "Missing SSH username in ssh://[fd7a::1]:22. For example: ssh://user@[fd7a::1]." },
    };
    for (cases, 0..) |case, i| {
        core.connect(case.locator, null);
        try testing.expectEqualStrings("error", try recorder.lastStatus(arena.allocator()));
        const got = try recorder.errorAt(arena.allocator(), i);
        try testing.expectEqualStrings("no_user", got.code);
        try testing.expectEqualStrings(case.message, got.message);
        try testing.expectEqualStrings("", got.fix);
    }
    // Refused before any dial.
    try testing.expectEqual(@as(usize, 0), fake.entered);
}

test "Core: an ssh dial failure reports the pool's code, sentence and fix" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    const sentence = "Velo couldn't find Rex on h.";
    var fake: SshFake = .{ .problem = .{ .code = "not_installed", .sentence = sentence, .fix = "rex server install ssh://me@h" } };
    const core = try Core.create(testing.allocator, recorder.host(), fake.net(), 1);
    defer core.destroy();

    core.connect("ssh://me@h", null);
    try recorder.waitErrors(1, 5000);
    const got = try recorder.errorAt(arena.allocator(), 0);
    try testing.expectEqualStrings("not_installed", got.code);
    try testing.expectEqualStrings(sentence, got.message);
    try testing.expectEqualStrings("rex server install ssh://me@h", got.fix);
}

test "Core: an ssh dial failure without a problem keeps the connect_failed note" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    var fake: SshFake = .{};
    const core = try Core.create(testing.allocator, recorder.host(), fake.net(), 1);
    defer core.destroy();

    core.connect("ssh://me@h", null);
    try recorder.waitErrors(1, 5000);
    const got = try recorder.errorAt(arena.allocator(), 0);
    try testing.expectEqualStrings("connect_failed", got.code);
    try testing.expectEqualStrings("could not reach the server (SshDialFailed)", got.message);
    try testing.expectEqualStrings("", got.fix);
}

const declined = "Velo ended the connection attempt without an answer to the SSH prompt.";

test "Core: an ssh failure retrying cannot fix stops until Reconnect" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    var fake: SshFake = .{ .problem = .{ .code = "declined", .sentence = declined, .fix = "" } };
    const core = try Core.create(testing.allocator, recorder.host(), fake.net(), 1);
    defer core.destroy();

    core.connect("ssh://me@h", null);
    try recorder.waitErrors(1, 5000);
    // Backing off would have dialed again after 1 s, and after 2 s more.
    try std.Io.sleep(testing.io, .fromMilliseconds(2500), .awake);
    try testing.expectEqual(@as(usize, 1), fake.dials());
    try testing.expectEqual(@as(usize, 1), recorder.errorCount());
    const got = try recorder.errorAt(arena.allocator(), 0);
    try testing.expectEqualStrings("declined", got.code);
    try testing.expectEqualStrings(declined, got.message);
    try testing.expectEqualStrings("error", try recorder.lastStatus(arena.allocator()));

    // Reconnect (the same locator) dials once more, and stops again.
    core.connect("ssh://me@h", null);
    _ = try fake.waitEntered(2, 5000);
    try recorder.waitErrors(2, 5000);
    try std.Io.sleep(testing.io, .fromMilliseconds(1500), .awake);
    try testing.expectEqual(@as(usize, 2), fake.dials());
    try testing.expectEqual(@as(usize, 2), recorder.errorCount());
    try testing.expectEqualStrings("error", try recorder.lastStatus(arena.allocator()));
}

test "Core: an ssh failure retrying cannot fix is reported after a first connection too" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    const changed = "Verify the new SSH fingerprint with the host administrator.";
    var fake: SshFake = .{
        .mode = .serve_first,
        .problem = .{ .code = "host_key_changed", .sentence = changed, .fix = "ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub" },
    };
    defer fake.finish();
    const core = try Core.create(testing.allocator, recorder.host(), fake.net(), 1);
    defer core.destroy();

    // The first connection reaches a bootstrap, then the server hangs up; the next dial
    // (after 1 s) fails for good.
    core.connect("ssh://me@h", null);
    try recorder.waitErrors(1, 8000);
    try testing.expect(recorder.sawStatus("connected"));
    try std.Io.sleep(testing.io, .fromMilliseconds(2500), .awake);
    try testing.expectEqual(@as(usize, 2), fake.dials());
    try testing.expectEqual(@as(usize, 1), recorder.errorCount());
    const got = try recorder.errorAt(arena.allocator(), 0);
    try testing.expectEqualStrings("host_key_changed", got.code);
    try testing.expectEqualStrings(changed, got.message);
    try testing.expectEqualStrings("ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub", got.fix);
    // Not `reconnecting`: nothing will reconnect until the user does.
    try testing.expectEqualStrings("error", try recorder.lastStatus(arena.allocator()));

    core.connect("ssh://me@h", null);
    _ = try fake.waitEntered(3, 5000);
}

test "Core: the ssh failures retrying cannot fix" {
    for ([_][]const u8{
        "declined",  "host_key_changed", "password_refused", "key_refused",         "not_installed",
        "malformed", "no_user",          "ssh_unavailable",  "no_common_algorithm", "login_refused",
    }) |code| try testing.expect(core_mod.permanentFailure(code));
    for ([_][]const u8{ "unreachable", "not_ssh", "closed_before_login", "ssh_not_ready", "internal", "" }) |code| {
        try testing.expect(!core_mod.permanentFailure(code));
    }
}

test "Core: a transient ssh failure backs off and retries" {
    for ([_][]const u8{ "unreachable", "ssh_not_ready" }) |code| {
        var recorder: Recorder = .{};
        defer recorder.deinit();
        var arena: std.heap.ArenaAllocator = .init(testing.allocator);
        defer arena.deinit();
        var fake: SshFake = .{ .problem = .{ .code = code, .sentence = "Velo couldn't reach h on port 22.", .fix = "" } };
        const core = try Core.create(testing.allocator, recorder.host(), fake.net(), 1);
        defer core.destroy();

        core.connect("ssh://me@h", null);
        // The second dial comes after the first backoff (1 s).
        _ = try fake.waitEntered(2, 5000);
        try recorder.waitErrors(1, 5000);
        const got = try recorder.errorAt(arena.allocator(), 0);
        try testing.expectEqualStrings(code, got.code);
        try testing.expectEqualStrings("error", try recorder.lastStatus(arena.allocator()));
    }
}

test "Core: a dial that works and an upgrade that fails is connect_failed, without the dialer's problem" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    var fake: SshFake = .{
        .mode = .hang_up,
        .problem = .{ .code = "not_installed", .sentence = "Velo couldn't find Rex on h.", .fix = "rex server install ssh://me@h" },
    };
    const core = try Core.create(testing.allocator, recorder.host(), fake.net(), 1);
    defer core.destroy();

    core.connect("ssh://me@h", null);
    try recorder.waitErrors(1, 5000);
    const got = try recorder.errorAt(arena.allocator(), 0);
    try testing.expectEqualStrings("connect_failed", got.code);
    try testing.expectStringStartsWith(got.message, "could not reach the server (");
    try testing.expectEqualStrings("", got.fix);
}

/// A data stream owner whose control connection stopped until `allow`. It has no
/// tickets: a stream that needs one ends.
const StoppedOwner = struct {
    lock: pane.Lock = .{},
    allowed: bool = false,
    exited: bool = false,

    fn owner(self: *StoppedOwner) ds.Owner {
        return .{ .ctx = self, .ticket = ticket, .title = title, .exited = onExited, .may_dial = mayDial };
    }

    fn ticket(_: *anyopaque, _: *[ds.max_ticket_len]u8) ds.Ticket {
        return .gone;
    }

    fn title(_: *anyopaque, _: []const u8) void {}

    fn onExited(ctx: *anyopaque) void {
        const self: *StoppedOwner = @ptrCast(@alignCast(ctx));
        self.lock.lock();
        defer self.lock.unlock();
        self.exited = true;
    }

    fn mayDial(ctx: *anyopaque) bool {
        const self: *StoppedOwner = @ptrCast(@alignCast(ctx));
        self.lock.lock();
        defer self.lock.unlock();
        return self.allowed;
    }

    fn allow(self: *StoppedOwner) void {
        self.lock.lock();
        defer self.lock.unlock();
        self.allowed = true;
    }

    fn waitExited(self: *StoppedOwner, ms: u32) !void {
        var waited: u32 = 0;
        while (true) : (waited += 5) {
            self.lock.lock();
            const done = self.exited;
            self.lock.unlock();
            if (done) return;
            if (waited >= ms) return error.Timeout;
            try std.Io.sleep(testing.io, .fromMilliseconds(5), .awake);
        }
    }
};

test "DataStream: no dial while the owner's control connection is stopped" {
    var fake: SshFake = .{};
    var owner: StoppedOwner = .{};
    const p = try pane.Pane.create(testing.allocator, no_glyphs);
    defer p.destroy();
    // A well-formed ticket (16 bytes, base64url), so that only the owner holds the dial back.
    const ticket = "AAAAAAAAAAAAAAAAAAAAAA";
    const stream = try ds.DataStream.start(testing.allocator, testing.io, "ssh://me@h", fake.net(), p, ticket, owner.owner());

    // Through the first backoff (1 s): the stream waits instead of dialing.
    try std.Io.sleep(testing.io, .fromMilliseconds(1500), .awake);
    try testing.expectEqual(@as(usize, 0), fake.dials());

    // Reconnected: the stream dials again (woken, as a reattach does).
    owner.allow();
    stream.restart();
    _ = try fake.waitEntered(1, 5000);
    stream.stop();
    try owner.waitExited(5000);
    stream.destroy();
}

/// A bootstrap answer that attaches `session:a` with the terminal `term_block` and a data
/// ticket for it: the core opens the block's data stream, which may dial at once without
/// asking for a ticket.
fn bootstrapWithTicket(core: *Core) !void {
    const id = registerBootstrap(core);
    var buf: [1024]u8 = undefined;
    try receiveFmt(core,
        \\{{"type":"response","id":"{d}","payload":{{"attach_all":{{"sessions":[{{"session_id":"session:a","label":"a"}}]}},
        \\"attached_sessions":[{{"session":{{"session_id":"session:a","label":"a"}},"view":{s}}}],
        \\"data_tickets":[{{"block_id":"{s}","ticket":"AAAAAAAAAAAAAAAAAAAAAA"}}]}}}}
    , .{ id, try sizeView(&buf, 1, null), term_block });
}

/// Wait up to `ms` until the core's connection is stopped (`Core.stopped`).
fn waitStopped(core: *Core, ms: u32) !void {
    var waited: u32 = 0;
    while (true) : (waited += 5) {
        core.lock.lock();
        const stopped = core.stopped == core.epoch;
        core.lock.unlock();
        if (stopped) return;
        if (waited >= ms) return error.Timeout;
        try std.Io.sleep(testing.io, .fromMilliseconds(5), .awake);
    }
}

/// A host whose `onError`, for the first note, has a bootstrap answer with a data ticket
/// arrive on another thread (as the receive loop's would) and counts the dials the new
/// data stream makes while the note is delivered.
const DialsDuringNote = struct {
    fake: *SshFake,
    /// Set before the core connects.
    core: ?*Core = null,
    lock: pane.Lock = .{},
    notes: usize = 0,
    feeder: ?std.Thread = null,
    /// The dials made while the first note was delivered; null until it was.
    dials: ?usize = null,

    fn host(self: *DialsDuringNote) core_mod.Host {
        return .{ .ctx = self, .onState = onState, .onReconnectToken = onToken, .onError = onError, .glyphs = no_glyphs };
    }

    fn onState(_: *anyopaque, _: u64, _: []const u8, _: ?[]const u8) void {}

    fn onToken(_: *anyopaque, _: []const u8) void {}

    fn onError(ctx: *anyopaque, _: []const u8, _: []const u8, _: []const u8) void {
        const self: *DialsDuringNote = @ptrCast(@alignCast(ctx));
        self.lock.lock();
        self.notes += 1;
        const first = self.notes == 1;
        self.lock.unlock();
        if (!first) return;
        const before = self.fake.dials();
        // Its `receive` delivers after this note (the host lock); the stream starts before.
        const feeder = std.Thread.spawn(.{}, feed, .{self.core.?}) catch @panic("spawn");
        // A stream that may dial does so at once.
        var waited: u32 = 0;
        while (self.fake.dials() == before and waited < 1000) : (waited += 10) {
            std.Io.sleep(testing.io, .fromMilliseconds(10), .awake) catch {};
        }
        self.lock.lock();
        defer self.lock.unlock();
        self.feeder = feeder;
        self.dials = self.fake.dials() - before;
    }

    fn feed(core: *Core) void {
        bootstrapWithTicket(core) catch @panic("bootstrap answer");
    }

    /// Wait up to `ms` for the first note; the dials made while it was delivered.
    fn waitDials(self: *DialsDuringNote, ms: u32) !usize {
        var waited: u32 = 0;
        while (true) : (waited += 10) {
            self.lock.lock();
            const dials = self.dials;
            self.lock.unlock();
            if (dials) |n| return n;
            if (waited >= ms) return error.Timeout;
            try std.Io.sleep(testing.io, .fromMilliseconds(10), .awake);
        }
    }

    fn join(self: *DialsDuringNote) void {
        self.lock.lock();
        const feeder = self.feeder;
        self.feeder = null;
        self.lock.unlock();
        if (feeder) |t| t.join();
    }
};

test "Core: a stop is recorded before its note, so no data stream dials while the note is delivered" {
    var fake: SshFake = .{ .problem = .{ .code = "declined", .sentence = declined, .fix = "" } };
    var witness: DialsDuringNote = .{ .fake = &fake };
    const core = try Core.create(testing.allocator, witness.host(), fake.net(), 1);
    defer core.destroy();
    witness.core = core;
    // Before `destroy`: the feeder uses the core.
    defer witness.join();

    core.connect("ssh://me@h", null);
    try testing.expectEqual(@as(usize, 0), try witness.waitDials(5000));
    // Only the control connection dialed.
    try testing.expectEqual(@as(usize, 1), fake.dials());
}

test "Core: a stopped core's data stream does not dial until Reconnect" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    var fake: SshFake = .{ .problem = .{ .code = "declined", .sentence = declined, .fix = "" } };
    const core = try Core.create(testing.allocator, recorder.host(), fake.net(), 1);
    defer core.destroy();
    // Before `destroy`, which waits for the dials in progress.
    defer fake.release();

    core.connect("ssh://me@h", null);
    try recorder.waitErrors(1, 5000);
    try waitStopped(core, 5000);
    try bootstrapWithTicket(core);
    const stream = blk: {
        core.lock.lock();
        defer core.lock.unlock();
        break :blk core.entries.get(term_block).?.stream.?;
    };

    // Woken (as a reattach does) and through the first backoff (1 s): the stream holds
    // its ticket and does not dial.
    stream.restart();
    try std.Io.sleep(testing.io, .fromMilliseconds(1500), .awake);
    try testing.expectEqual(@as(usize, 1), fake.dials());

    // Reconnect: the stream dials again. Dials are held from here, so that the control
    // connection does not stop the core again before the stream dials.
    fake.holdDials();
    core.connect("ssh://me@h", null);
    _ = try fake.waitEntered(2, 5000);
    stream.restart();
    _ = try fake.waitEntered(3, 5000);
}

test "Core: without an ssh dialer an ssh locator needs the native library" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    const core = try Core.create(testing.allocator, recorder.host(), .{}, 1);
    defer core.destroy();

    core.connect("ssh://me@h", null);
    try recorder.waitErrors(1, 5000);
    const got = try recorder.errorAt(arena.allocator(), 0);
    try testing.expectEqualStrings("ssh_unavailable", got.code);
    try testing.expectEqualStrings("SSH needs the app's native library.", got.message);
    try testing.expectEqualStrings("", got.fix);
}

fn sessionCount(core: *Core) usize {
    core.lock.lock();
    defer core.lock.unlock();
    return core.sessions.items.len;
}

test "Core: switching to the same server in another spelling keeps the model" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    var gate: Gate = .{};
    const core = try Core.create(testing.allocator, recorder.host(), gate.net(), 1);
    defer core.destroy();
    // Before `destroy`, which waits for a connect in progress. No connection is ever made.
    defer gate.set(false);

    core.connect("https://rex.example.ts.net", null);
    const id = registerBootstrap(core);
    try receiveFmt(core,
        \\{{"type":"response","id":"{d}","payload":{{"attach_all":{{"sessions":[
        \\{{"session_id":"session:a","label":"a"}},{{"session_id":"session:b","label":"b"}}]}},
        \\"selected_session":null,"data_tickets":[]}}}}
    , .{id});
    try testing.expectEqual(@as(usize, 2), sessionCount(core));

    core.connect("HTTPS://REX.example.ts.net:443", null);
    try testing.expectEqual(@as(usize, 2), sessionCount(core));

    // Another port is another server.
    core.connect("https://rex.example.ts.net:8443", null);
    try testing.expectEqual(@as(usize, 0), sessionCount(core));
}

/// Answers one WebSocket upgrade on a listening socket the way a Rex server does, and
/// keeps the request it read. Gives up after 5 s when no client comes.
const UpgradeServer = struct {
    server: std.Io.net.Server,
    thread: std.Thread = undefined,
    request: [2048]u8 = undefined,
    len: usize = 0,

    fn start(self: *UpgradeServer) !void {
        self.thread = try std.Thread.spawn(.{}, run, .{self});
    }

    fn run(self: *UpgradeServer) void {
        var fds = [_]std.posix.pollfd{.{ .fd = self.server.socket.handle, .events = std.posix.POLL.IN, .revents = 0 }};
        if ((std.posix.poll(&fds, 5000) catch 0) == 0) return;
        const stream = self.server.accept(testing.io) catch return;
        defer stream.close(testing.io);
        var read_buffer: [1024]u8 = undefined;
        var reader = stream.reader(testing.io, &read_buffer);
        var key: []const u8 = "";
        while (true) {
            const line = reader.interface.takeDelimiterInclusive('\n') catch return;
            if (line.len > self.request.len - self.len) return;
            const kept = self.request[self.len..][0..line.len];
            @memcpy(kept, line);
            self.len += line.len;
            const name = "Sec-WebSocket-Key:";
            if (std.ascii.startsWithIgnoreCase(kept, name)) key = std.mem.trim(u8, kept[name.len..], " \r\n");
            if (std.mem.eql(u8, kept, "\r\n")) break;
        }
        var accept: [28]u8 = undefined;
        ws_mod.acceptKey(key, &accept);
        var write_buffer: [512]u8 = undefined;
        var writer = stream.writer(testing.io, &write_buffer);
        writer.interface.print("HTTP/1.1 101 Switching Protocols\r\n" ++
            "Upgrade: websocket\r\n" ++
            "Connection: Upgrade\r\n" ++
            "Sec-WebSocket-Accept: {s}\r\n" ++
            "Sec-WebSocket-Protocol: {s}\r\n\r\n", .{ &accept, protocol.control_protocol }) catch return;
        writer.interface.flush() catch return;
    }

    /// Waits for the server thread, closes the listening socket, returns the request.
    fn finish(self: *UpgradeServer) []const u8 {
        self.thread.join();
        self.server.deinit(testing.io);
        return self.request[0..self.len];
    }
};

test "a control socket over a unix path completes the WebSocket upgrade" {
    var scratch: test_net.ScratchDir = .{};
    try scratch.init();
    defer scratch.deinit();
    var text: [96]u8 = undefined;
    const path = try std.fmt.bufPrint(&text, "{s}/rex.sock", .{scratch.path()});
    const address = try std.Io.net.UnixAddress.init(path);
    var server: UpgradeServer = .{ .server = try address.listen(testing.io, .{}) };
    server.start() catch |err| {
        server.server.deinit(testing.io);
        return err;
    };

    const result = ws_mod.WebSocket.connect(testing.allocator, testing.io, path, "/api/control", protocol.control_protocol, .{});
    const request = server.finish();
    const sock = try result;
    defer sock.deinit();
    try testing.expectEqual(@as(u16, 101), sock.status_code);
    try testing.expectStringStartsWith(request, "GET /api/control HTTP/1.1\r\nHost: localhost\r\n");
}

test "Core: a missing socket path reports an error and keeps retrying" {
    var scratch: test_net.ScratchDir = .{};
    try scratch.init();
    defer scratch.deinit();
    var text: [96]u8 = undefined;
    const path = try std.fmt.bufPrint(&text, "{s}/none.sock", .{scratch.path()});

    var recorder: Recorder = .{};
    defer recorder.deinit();
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    const core = try Core.create(testing.allocator, recorder.host(), .{}, 1);
    defer core.destroy();
    core.connect(path, null);

    // The first attempt fails at once; the status line has words for it.
    try recorder.waitErrors(1, 2000);
    const first = try recorder.errorAt(arena.allocator(), 0);
    try testing.expectEqualStrings("connect_failed", first.code);
    try testing.expect(first.message.len > 0);
    const status = try recorder.lastStatus(arena.allocator());
    try testing.expect(std.mem.eql(u8, status, "error") or std.mem.eql(u8, status, "connecting"));
    // After the backoff (1 s) it tries again.
    try recorder.waitErrors(2, 5000);
}

/// The error note of the first attempt to connect to `path`.
fn firstConnectError(arena: std.mem.Allocator, path: []const u8) !struct { code: []const u8, message: []const u8 } {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    const core = try Core.create(testing.allocator, recorder.host(), .{}, 1);
    defer core.destroy();
    core.connect(path, null);
    try recorder.waitErrors(1, 2000);
    const first = try recorder.errorAt(arena, 0);
    return .{ .code = first.code, .message = first.message };
}

test "Core: a socket path too long for this phone is named in the error note" {
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    const too_long = "/tmp/" ++ @as([99]u8, @splat('a')) ++ ".sock";
    comptime std.debug.assert(too_long.len == 109);
    const first = try firstConnectError(arena.allocator(), too_long);
    try testing.expectEqualStrings("connect_failed", first.code);
    try testing.expectEqualStrings("The socket path is too long for this phone: it must be at most 108 bytes.", first.message);

    // 108 bytes fit (`sockaddr_un` needs no terminating zero): the socket is just not there.
    const longest = "/tmp/" ++ @as([98]u8, @splat('a')) ++ ".sock";
    comptime std.debug.assert(longest.len == 108);
    const missing = try firstConnectError(arena.allocator(), longest);
    try testing.expectEqualStrings("connect_failed", missing.code);
    try testing.expectEqualStrings("could not reach the server (FileNotFound)", missing.message);
}

test "Core: commands without a connection report errors and settings apply" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    const core = try Core.create(testing.allocator, recorder.host(), .{}, 1);
    defer core.destroy();

    // Settings never need the server and never fail.
    core.command("{\"op\":\"setting\",\"key\":\"fontSize\",\"value\":15}");
    core.command("{\"op\":\"setting\",\"key\":\"paneStyle\",\"value\":\"cards\"}");
    try testing.expectEqual(@as(usize, 0), recorder.errors.items.len);
    try testing.expectEqual(@as(f32, 15), core.font_sp);

    core.command("{\"op\":\"newWindow\"}");
    core.command("{\"op\":\"newSession\"}");
    core.command("{\"op\":\"bogus\"}");
    core.command("not json");
    try testing.expectEqual(@as(usize, 4), recorder.errors.items.len);
    try testing.expectEqualStrings("not_connected", recorder.errors.items[0]);
    try testing.expectEqualStrings("not_connected", recorder.errors.items[1]);
    try testing.expectEqualStrings("bad_request", recorder.errors.items[2]);
    try testing.expectEqualStrings("bad_request", recorder.errors.items[3]);
}

test "Core: hosts intents without a connection report not_connected" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    const core = try Core.create(testing.allocator, recorder.host(), .{}, 1);
    defer core.destroy();

    core.command("{\"op\":\"hostsRemove\",\"id\":\"host:x\"}");
    core.command("{\"op\":\"hostsPut\",\"id\":\"host:x\",\"label\":\"studio\",\"url\":\"https://studio.example.ts.net\"}");
    try testing.expectEqual(@as(usize, 2), recorder.errors.items.len);
    try testing.expectEqualStrings("not_connected", recorder.errors.items[0]);
    try testing.expectEqualStrings("not_connected", recorder.errors.items[1]);
}

test "Core: detach without a connection clears the selection" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    const core = try Core.create(testing.allocator, recorder.host(), .{}, 1);
    defer core.destroy();

    core.selectSession("session:a");
    try testing.expectEqualStrings("session:a", try recorder.lastString(arena.allocator(), "session"));
    core.command("{\"op\":\"detach\"}");
    try testing.expect(try recorder.lastValue(arena.allocator(), "session") == .null);
    try testing.expectEqual(@as(usize, 0), recorder.errors.items.len);
}

// ------------------------------------------- core handlers without a server
//
// Seam: `Core.receive` feeds control message text through the receive loop's own
// dispatch (`handleIncoming` → `handleResponse`/`handleEvent`). The test registers the
// bootstrap request in `pending` itself, as `serve` does after the welcome; every later
// request is queued by the core in `outbox` (no writer thread runs without a link).

/// A core that took a bootstrap answer with sessions `session:a` and `session:b` and no
/// panes. `selected_session` is null, so the core picks `session:a` itself.
fn bootstrapped(recorder: *Recorder) !*Core {
    const core = try Core.create(testing.allocator, recorder.host(), .{}, 1);
    errdefer core.destroy();
    const id = registerBootstrap(core);
    try receiveFmt(core,
        \\{{"type":"response","id":"{d}","payload":{{"attach_all":{{"sessions":[
        \\{{"session_id":"session:a","label":"a"}},{{"session_id":"session:b","label":"b"}}]}},
        \\"selected_session":null,"data_tickets":[]}}}}
    , .{id});
    return core;
}

/// Register a `session.bootstrap` request the way `serve` does; returns its id.
fn registerBootstrap(core: *Core) u64 {
    core.lock.lock();
    defer core.lock.unlock();
    const id = core.next_id;
    core.next_id += 1;
    core.pending.put(core.gpa, id, .bootstrap) catch unreachable;
    return id;
}

fn receiveFmt(core: *Core, comptime fmt: []const u8, args: anytype) !void {
    const text = try std.fmt.allocPrint(testing.allocator, fmt, args);
    defer testing.allocator.free(text);
    core.receive(text);
}

/// Id of the one pending request of kind `tag`; null when there is none.
fn pendingId(core: *Core, tag: anytype) !?u64 {
    core.lock.lock();
    defer core.lock.unlock();
    var found: ?u64 = null;
    var it = core.pending.iterator();
    while (it.next()) |kv| if (std.meta.activeTag(kv.value_ptr.*) == tag) {
        if (found != null) return error.SeveralPending;
        found = kv.key_ptr.*;
    };
    return found;
}

/// Queued requests for `method`.
fn queued(core: *Core, method: []const u8) usize {
    core.lock.lock();
    defer core.lock.unlock();
    var buf: [64]u8 = undefined;
    const needle = std.fmt.bufPrint(&buf, "\"method\":\"{s}\"", .{method}) catch unreachable;
    var n: usize = 0;
    for (core.outbox.items) |text| {
        if (std.mem.find(u8, text, needle) != null) n += 1;
    }
    return n;
}

test "Core: a detached core does not attach again on a session.list answer" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    const core = try bootstrapped(&recorder);
    defer core.destroy();

    try testing.expectEqualStrings("connected", try recorder.lastStatus(arena.allocator()));
    try testing.expectEqualStrings("session:a", try recorder.lastString(arena.allocator(), "session"));
    try testing.expectEqual(@as(usize, 1), queued(core, "session.attach"));

    core.command("{\"op\":\"detach\"}");
    try testing.expect(try recorder.lastValue(arena.allocator(), "session") == .null);
    try testing.expectEqual(@as(usize, 1), queued(core, "session.detach"));

    // A session event asks for the list; its answer must not pick a session.
    core.receive("{\"type\":\"event\",\"seq\":2,\"payload\":{\"type\":\"session_created\",\"payload\":{\"session_id\":\"session:c\"}}}");
    const list_id = (try pendingId(core, .list)) orelse return error.NoListRequest;
    try receiveFmt(core,
        \\{{"type":"response","id":"{d}","payload":{{"sessions":[{{"session_id":"session:a","label":"a"}},
        \\{{"session_id":"session:b","label":"b"}},{{"session_id":"session:c","label":"c"}}]}}}}
    , .{list_id});
    try testing.expect(try recorder.lastValue(arena.allocator(), "session") == .null);
    try testing.expectEqual(@as(usize, 3), model.items(try recorder.lastValue(arena.allocator(), "sessions")).len);
    try testing.expectEqual(@as(usize, 1), queued(core, "session.attach"));

    // Selecting a session attaches again.
    core.selectSession("session:b");
    try testing.expectEqualStrings("session:b", try recorder.lastString(arena.allocator(), "session"));
    try testing.expectEqual(@as(usize, 2), queued(core, "session.attach"));
    try testing.expectEqual(@as(usize, 0), recorder.errors.items.len);
}

test "Core: a detach while session.create is pending suppresses its attach" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    const core = try bootstrapped(&recorder);
    defer core.destroy();

    core.command("{\"op\":\"newSession\"}");
    const id = (try pendingId(core, .create_session)) orelse return error.NoCreateRequest;
    core.command("{\"op\":\"detach\"}");
    const attaches = queued(core, "session.attach");
    try answer(core, id, "{\"session_id\":\"session:new\"}");

    try testing.expect(core.selected == null);
    try testing.expect(!core.auto_pick);
    try testing.expectEqual(attaches, queued(core, "session.attach"));
}

test "Core: an initially parked bootstrap opens no pane until the remote is promoted" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    const core = try Core.create(testing.allocator, recorder.host(), .{}, 1);
    defer core.destroy();

    core.command("{\"op\":\"detach\"}");
    try testing.expect(core.bootstrapWanted() == .none);
    // Even a bootstrap reply carrying a terminal view must leave the core parked.
    try bootstrapAgain(core, null);
    try testing.expect(core.ready);
    try testing.expect(core.selected == null);
    try testing.expectEqual(@as(usize, 0), core.live_streams);
    try testing.expectEqual(@as(usize, 0), counts(core).panes);
    try testing.expectEqual(@as(usize, 0), queued(core, "session.attach"));

    core.command("{\"op\":\"resumeSession\",\"session\":\"session:b\"}");
    try testing.expectEqualStrings("session:b", core.selected.?);
    try expectRequest(core, "session.attach", "{\"session_id\":\"session:b\"}");
}

test "Core: promotion after the saved session was destroyed attaches the first remaining session" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    const core = try bootstrapped(&recorder);
    defer core.destroy();
    core.command("{\"op\":\"detach\"}");
    try sessionEvent(core, "session:b", "session_destroyed", "{}");

    // Main still has the old snapshot and names b. The core has already removed it.
    core.command("{\"op\":\"resumeSession\",\"session\":\"session:b\"}");
    try testing.expectEqualStrings("session:a", core.selected.?);
    try testing.expectEqual(@as(usize, 2), queuedFor(core, "session.attach", "session:a"));
    try testing.expectEqual(@as(usize, 0), queuedFor(core, "session.attach", "session:b"));

    // If the last session also disappears, promotion survives until the list fills.
    core.command("{\"op\":\"detach\"}");
    try sessionEvent(core, "session:a", "session_destroyed", "{}");
    core.command("{\"op\":\"resumeSession\"}");
    try testing.expect(core.selected == null);
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    try answerList(core, arena.allocator(), &.{ "c", "d" });
    try testing.expectEqualStrings("session:c", core.selected.?);
    try expectRequest(core, "session.attach", "{\"session_id\":\"session:c\"}");
}

test "Core: a reconnect after detach attaches nothing" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    const core = try bootstrapped(&recorder);
    defer core.destroy();

    try testing.expect(core_mod.bootstrapAttach(core.selected, core.auto_pick) == .session);
    core.command("{\"op\":\"detach\"}");
    // The next connection's bootstrap sends `attach_session_ids: []`.
    try testing.expect(core_mod.bootstrapAttach(core.selected, core.auto_pick) == .none);

    // A server that still chose a session (an older one ignoring the empty list) is
    // detached at once instead of shown.
    core.lock.lock();
    core.ready = false;
    core.lock.unlock();
    const id = registerBootstrap(core);
    try receiveFmt(core,
        \\{{"type":"response","id":"{d}","payload":{{"attach_all":{{"sessions":[
        \\{{"session_id":"session:a","label":"a"}},{{"session_id":"session:b","label":"b"}}]}},
        \\"selected_session":{{"session":{{"session_id":"session:b","label":"b"}},
        \\"view":{{"session_id":"session:b","label":"b","revision":1,"windows":[]}}}},"data_tickets":[]}}}}
    , .{id});
    try testing.expectEqualStrings("connected", try recorder.lastStatus(arena.allocator()));
    try testing.expect(try recorder.lastValue(arena.allocator(), "session") == .null);
    try testing.expectEqual(@as(usize, 2), queued(core, "session.detach"));
    try testing.expectEqual(@as(usize, 1), queued(core, "session.attach"));

    // Without a detach the server's pick is shown.
    core.disconnect();
    try testing.expect(core_mod.bootstrapAttach(core.selected, core.auto_pick) == .pick);
}

test "Core: a session selected before connecting is the one the bootstrap names" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    var gate: Gate = .{};
    const core = try Core.create(testing.allocator, recorder.host(), gate.net(), 1);
    defer core.destroy();
    // Before `destroy`, which waits for a connect in progress. No connection is ever made.
    defer gate.set(false);

    core.selectSession("session:b");
    core.connect("https://rex.example.ts.net", null);
    {
        core.lock.lock();
        defer core.lock.unlock();
        try testing.expectEqualStrings("session:b", core.selected orelse "");
        try testing.expect(core_mod.bootstrapAttach(core.selected, core.auto_pick) == .session);
    }

    // Another server has other sessions: the selection does not go with the core.
    core.connect("https://other.example.ts.net", null);
    core.lock.lock();
    defer core.lock.unlock();
    try testing.expect(core_mod.bootstrapAttach(core.selected, core.auto_pick) == .pick);
}

test "Core: hosts.list answers are published and hosts_changed asks only for a new hash" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    const core = try bootstrapped(&recorder);
    defer core.destroy();

    // The bootstrap asked for the list.
    try testing.expectEqualStrings("", try recorder.lastString(arena.allocator(), "hostsHash"));
    const list_id = (try pendingId(core, .hosts_list)) orelse return error.NoHostsRequest;
    try testing.expectEqual(@as(usize, 1), queued(core, "hosts.list"));
    try receiveFmt(core, "{{\"type\":\"response\",\"id\":\"{d}\",\"payload\":{s}}}", .{ list_id, hosts_json });

    // (a) The state carries the list and its hash.
    try testing.expectEqualStrings(hosts_hash, try recorder.lastString(arena.allocator(), "hostsHash"));
    const hosts = model.items(try recorder.lastValue(arena.allocator(), "hosts"));
    try testing.expectEqual(@as(usize, 2), hosts.len);
    try testing.expectEqualStrings("demo_user", model.str(model.get(hosts[0], "label")).?);
    try testing.expectEqualStrings("globe", model.str(model.get(hosts[1], "icon")).?);
    try testing.expectEqual(@as(?u64, null), try pendingId(core, .hosts_list));

    // (b) The same hash: no new request.
    core.receive("{\"type\":\"event\",\"seq\":3,\"payload\":{\"type\":\"hosts_changed\",\"payload\":{\"hash\":\"" ++ hosts_hash ++ "\"}}}");
    try testing.expectEqual(@as(?u64, null), try pendingId(core, .hosts_list));
    try testing.expectEqual(@as(usize, 1), queued(core, "hosts.list"));

    // (c) Another hash: the list is asked for again.
    core.receive("{\"type\":\"event\",\"seq\":4,\"payload\":{\"type\":\"hosts_changed\",\"payload\":{\"hash\":\"0000\"}}}");
    try testing.expect(try pendingId(core, .hosts_list) != null);
    try testing.expectEqual(@as(usize, 2), queued(core, "hosts.list"));
    try testing.expectEqual(@as(usize, 0), recorder.errors.items.len);
}

// ------------------------------------------------- terminal size: the rules

const fit_a: rules.Size = .{ .cols = 100, .rows = 30, .cell_w = 8, .cell_h = 16 };
const fit_b: rules.Size = .{ .cols = 50, .rows = 15, .cell_w = 16, .cell_h = 32 };

fn expectActions(take: bool, ask: ?rules.Size, got: rules.Actions) !void {
    try testing.expectEqual(take, got.take);
    try testing.expectEqual(ask, got.resize);
}

fn commandFmt(core: *Core, comptime fmt: []const u8, args: anytype) !void {
    const text = try std.fmt.allocPrint(testing.allocator, fmt, args);
    defer testing.allocator.free(text);
    core.command(text);
}

test "size rules: the first surface takes the block and asks for the fit; a later surface sends nothing" {
    var s: rules.State = .{};
    try expectActions(true, fit_a, s.step(.surface, fit_a, true));
    // The view changed size (keyboard, rotation, fold): the app reports the settled size
    // with `fit`, so the frames of the animation ask for nothing.
    try expectActions(false, null, s.step(.surface, fit_b, true));
    try expectActions(false, null, s.step(.surface, fit_a, true));
    try expectActions(false, fit_b, s.step(.fit, fit_b, true));
}

test "size rules: no take when this client is the owner" {
    var s: rules.State = .{ .owned = true };
    try expectActions(false, fit_a, s.step(.surface, fit_a, true));
    try expectActions(false, null, s.step(.used, fit_a, true));
    // Shown again after a tab switch: still the owner, and the server holds the size.
    try expectActions(false, null, s.step(.hidden, null, true));
    try expectActions(false, null, s.step(.surface, fit_a, true));
}

test "size rules: fit asks only when the size changed, and only for a shown pane" {
    var s: rules.State = .{ .owned = true };
    // Not shown yet: nothing to fit.
    try expectActions(false, null, s.step(.fit, fit_a, true));
    try expectActions(false, fit_a, s.step(.surface, fit_a, true));
    try expectActions(false, null, s.step(.fit, fit_a, true));
    try expectActions(false, null, s.step(.fit, fit_a, true));
    // A finished pinch gave another cell size.
    try expectActions(false, fit_b, s.step(.fit, fit_b, true));
    try expectActions(false, null, s.step(.fit, fit_b, true));
    // Back to the first size: that is a change again.
    try expectActions(false, fit_a, s.step(.fit, fit_a, true));
    // Only the cell size differs: the terminal reports px sizes to its programs.
    var wider = fit_a;
    wider.cell_w += 1;
    try expectActions(false, wider, s.step(.fit, wider, true));
    // A surface without an area has nothing to ask for; the ask waits for one.
    try expectActions(false, null, s.step(.fit, null, true));
    try expectActions(false, null, s.step(.online, null, true));
    try expectActions(false, fit_b, s.step(.surface, fit_b, true));
    try expectActions(false, null, s.step(.fit, fit_b, true));
    try expectActions(false, wider, s.step(.fit, wider, true));
    // Hidden: a setting changes nothing on the server; shown again, the new fit is asked.
    try expectActions(false, null, s.step(.hidden, null, true));
    try expectActions(false, null, s.step(.fit, fit_b, true));
    try expectActions(false, fit_b, s.step(.surface, fit_b, true));
}

test "size rules: one take in flight, however much is typed" {
    var s: rules.State = .{};
    try expectActions(true, fit_a, s.step(.surface, fit_a, true));
    // Keys typed before the answer.
    for (0..20) |_| try expectActions(false, null, s.step(.used, fit_a, true));
    // The answer names this client: nothing more to take.
    s.take_inflight = false;
    s.owned = true;
    try expectActions(false, null, s.step(.used, fit_a, true));
    // An answer that failed (the block closed on the way) leaves the next key to try.
    s.owned = false;
    try expectActions(true, null, s.step(.used, fit_a, true));
    try expectActions(false, null, s.step(.used, fit_a, true));
}

test "size rules: a touch when another client owns takes the block and asks for nothing" {
    var s: rules.State = .{};
    try expectActions(true, fit_a, s.step(.surface, fit_a, true));
    s.take_inflight = false;
    s.owned = true;
    // The desktop took the block.
    s.owned = false;
    // The server applies the size this client asked for last.
    try expectActions(true, null, s.step(.used, fit_a, true));
}

test "size rules: a reconnect sends nothing; the next fit asks again" {
    var s: rules.State = .{};
    try expectActions(true, fit_a, s.step(.surface, fit_a, true));
    s.linkLost();
    // Offline: input is dropped and takes nothing.
    try expectActions(false, null, s.step(.used, fit_a, false));
    // Connected again. Another client may own the block by now: still nothing.
    try expectActions(false, null, s.step(.online, fit_a, true));
    try expectActions(false, null, s.step(.online, fit_a, true));
    // The same size as before the drop is asked for again.
    try expectActions(false, fit_a, s.step(.fit, fit_a, true));
    try expectActions(false, null, s.step(.fit, fit_a, true));
    // A take that was in flight when the link dropped does not block the next one.
    try expectActions(true, null, s.step(.used, fit_a, true));
}

test "size rules: a pane shown or fitted while offline is sent with the connection" {
    var s: rules.State = .{};
    try expectActions(false, null, s.step(.surface, fit_a, false));
    try expectActions(false, null, s.step(.fit, fit_b, false));
    try expectActions(true, fit_b, s.step(.online, fit_b, true));
    try expectActions(false, null, s.step(.online, fit_b, true));

    // Hidden again before the connection is back: nothing is owed.
    var gone: rules.State = .{};
    try expectActions(false, null, gone.step(.surface, fit_a, false));
    try expectActions(false, null, gone.step(.hidden, null, false));
    try expectActions(false, null, gone.step(.online, null, true));

    // A fit that could not be sent is not lost.
    var shown: rules.State = .{ .owned = true };
    try expectActions(false, fit_a, shown.step(.surface, fit_a, true));
    shown.linkLost();
    try expectActions(false, null, shown.step(.fit, fit_b, false));
    try expectActions(false, fit_b, shown.step(.online, fit_b, true));
}

test "size rules: a take after a reconnect asks for the size the server may not hold" {
    var s: rules.State = .{};
    try expectActions(true, fit_a, s.step(.surface, fit_a, true));
    s.take_inflight = false;
    s.linkLost();
    try expectActions(false, null, s.step(.online, fit_a, true));
    try expectActions(true, fit_a, s.step(.used, fit_a, true));
}

test "size rules: a surface without an area shows nothing; the first one with an area does" {
    var s: rules.State = .{};
    // `surfaceChanged(0, 0)`: no take, no ask, and the pane is not shown.
    try expectActions(false, null, s.step(.surface, null, true));
    try testing.expect(!s.shown);
    // A `fit` meanwhile sends nothing and uses nothing up.
    try expectActions(false, null, s.step(.fit, null, true));
    try expectActions(false, null, s.step(.surface, null, true));
    // The first real size is the show: one take, one ask.
    try expectActions(true, fit_a, s.step(.surface, fit_a, true));
    try expectActions(false, null, s.step(.surface, fit_a, true));
    try expectActions(false, null, s.step(.fit, fit_a, true));

    // Shown, then resized to nothing and back: a fit asked for meanwhile waits for the
    // area and goes out with it, once.
    try expectActions(false, null, s.step(.surface, null, true));
    try testing.expect(s.shown);
    try expectActions(false, null, s.step(.fit, null, true));
    try expectActions(false, fit_b, s.step(.surface, fit_b, true));
    try expectActions(false, null, s.step(.fit, fit_b, true));
    // Without a fit in between, the surface coming back sends nothing.
    try expectActions(false, null, s.step(.surface, null, true));
    try expectActions(false, null, s.step(.surface, fit_a, true));
    try expectActions(false, fit_a, s.step(.fit, fit_a, true));
}

test "size rules: a pane shown again while a take is in flight keeps its take" {
    var s: rules.State = .{};
    try expectActions(true, fit_a, s.step(.surface, fit_a, true));
    try expectActions(false, null, s.step(.hidden, null, true));
    // One take is in flight: no second one yet, and the show is not forgotten.
    try expectActions(false, null, s.step(.surface, fit_a, true));
    try expectActions(false, null, s.step(.fit, fit_a, true));
    // The answer names this client: the show has what it wanted.
    s.take_inflight = false;
    s.owned = true;
    try expectActions(false, null, s.step(.answered, fit_a, true));
    try expectActions(false, null, s.step(.online, fit_a, true));

    // The same, but another client took the block before the answer: take again, once.
    var lost: rules.State = .{};
    try expectActions(true, fit_a, lost.step(.surface, fit_a, true));
    try expectActions(false, null, lost.step(.hidden, null, true));
    try expectActions(false, null, lost.step(.surface, fit_a, true));
    lost.take_inflight = false;
    try expectActions(true, null, lost.step(.answered, fit_a, true));
    try expectActions(false, null, lost.step(.answered, fit_a, true));

    // Hidden again before the answer: nothing is owed.
    var gone: rules.State = .{};
    try expectActions(true, fit_a, gone.step(.surface, fit_a, true));
    try expectActions(false, null, gone.step(.hidden, null, true));
    try expectActions(false, null, gone.step(.surface, fit_a, true));
    try expectActions(false, null, gone.step(.hidden, null, true));
    gone.take_inflight = false;
    try expectActions(false, null, gone.step(.answered, null, true));

    // A refused take is not sent again by itself, whatever was pending.
    var refused: rules.State = .{};
    try expectActions(true, fit_a, refused.step(.surface, fit_a, true));
    try expectActions(false, null, refused.step(.hidden, null, true));
    try expectActions(false, null, refused.step(.surface, fit_a, true));
    refused.take_inflight = false;
    try expectActions(false, null, refused.step(.refused, fit_a, true));
    try expectActions(false, null, refused.step(.online, fit_a, true));
    // The next use tries.
    try expectActions(true, null, refused.step(.used, fit_a, true));
}

test "size rules: a preview is not shown, and using it takes nothing" {
    // A shown pane becomes a preview: nothing is sent, and nothing is owed.
    var s: rules.State = .{};
    try expectActions(true, fit_a, s.step(.surface, fit_a, true));
    s.take_inflight = false;
    s.owned = true;
    try expectActions(false, null, s.step(.preview, fit_a, true));
    try testing.expect(!s.shown);
    try expectActions(false, null, s.step(.fit, fit_b, true));
    try expectActions(false, null, s.step(.online, fit_b, true));
    // Another client took the block meanwhile: touching or typing on a preview takes nothing.
    s.owned = false;
    try expectActions(false, null, s.step(.used, fit_a, true));
    try expectActions(false, null, s.step(.preview, fit_a, true));
    try expectActions(false, null, s.step(.used, fit_a, true));
    // Shown again in place: as a first surface, a take; the size is the one asked last.
    try expectActions(true, null, s.step(.surface, fit_a, true));
    s.take_inflight = false;
    s.owned = true;
    // And with another fit, the owner asks for it without a take.
    try expectActions(false, null, s.step(.preview, fit_a, true));
    try expectActions(false, fit_b, s.step(.surface, fit_b, true));

    // A pane that was never shown: a preview, then its first surface as a shown pane.
    var first: rules.State = .{};
    try expectActions(false, null, first.step(.preview, fit_a, true));
    try expectActions(false, null, first.step(.used, fit_a, true));
    try expectActions(false, null, first.step(.fit, fit_a, true));
    try expectActions(true, fit_a, first.step(.surface, fit_a, true));

    // Its surface goes away: a pane without a surface, which a use takes as before.
    var gone: rules.State = .{};
    try expectActions(false, null, gone.step(.preview, fit_a, true));
    try expectActions(false, null, gone.step(.hidden, null, true));
    try expectActions(true, null, gone.step(.used, null, true));

    // A take owed when the pane became a preview is dropped with it.
    var owed: rules.State = .{};
    try expectActions(false, null, owed.step(.surface, fit_a, false));
    try expectActions(false, null, owed.step(.preview, fit_a, false));
    try expectActions(false, null, owed.step(.online, fit_a, true));
    var inflight: rules.State = .{};
    try expectActions(true, fit_a, inflight.step(.surface, fit_a, true));
    try expectActions(false, null, inflight.step(.hidden, null, true));
    try expectActions(false, null, inflight.step(.surface, fit_a, true));
    try expectActions(false, null, inflight.step(.preview, fit_a, true));
    inflight.take_inflight = false;
    try expectActions(false, null, inflight.step(.answered, fit_a, true));
}

test "size rules: back from a preview on the pane that was shown claims nothing" {
    // The pane was shown, and another client has taken its block since.
    var s: rules.State = .{};
    try expectActions(true, fit_a, s.step(.surface, fit_a, true));
    s.take_inflight = false;
    try expectActions(false, null, s.step(.preview, fit_a, true));
    // Back on it with nothing changed: no take and no size.
    try expectActions(false, null, s.step(.resumed, fit_a, true));
    try testing.expect(s.shown);
    // It is shown as it was: a fit asks, and only a use takes.
    try expectActions(false, fit_b, s.step(.fit, fit_b, true));
    try expectActions(true, null, s.step(.used, fit_b, true));

    // The fit changed while it was a preview: the new size is asked, nothing is taken.
    var resized: rules.State = .{};
    try expectActions(true, fit_a, resized.step(.surface, fit_a, true));
    resized.take_inflight = false;
    try expectActions(false, null, resized.step(.preview, fit_a, true));
    try expectActions(false, fit_b, resized.step(.resumed, fit_b, true));
    try expectActions(false, null, resized.step(.fit, fit_b, true));

    // A pane that was never shown has nothing to come back to: its first surface.
    var first: rules.State = .{};
    try expectActions(false, null, first.step(.preview, fit_a, true));
    try expectActions(true, fit_a, first.step(.resumed, fit_a, true));

    // Nor has one whose surface went away while it was a preview.
    var gone: rules.State = .{};
    try expectActions(true, fit_a, gone.step(.surface, fit_a, true));
    gone.take_inflight = false;
    try expectActions(false, null, gone.step(.preview, fit_a, true));
    try expectActions(false, null, gone.step(.hidden, null, true));
    try expectActions(false, null, gone.step(.preview, fit_a, true));
    try expectActions(true, null, gone.step(.resumed, fit_a, true));

    // A return is used once: shown by the take rules since, the pane is a preview that
    // was shown again, and that return is its own.
    var once: rules.State = .{};
    try expectActions(true, fit_a, once.step(.surface, fit_a, true));
    once.take_inflight = false;
    try expectActions(false, null, once.step(.preview, fit_a, true));
    try expectActions(false, null, once.step(.resumed, fit_a, true));
    try expectActions(false, null, once.step(.hidden, null, true));
    try expectActions(true, null, once.step(.resumed, fit_a, true));

    // A take that was still owed when the preview began is owed again, and no other.
    var owed: rules.State = .{};
    try expectActions(false, null, owed.step(.surface, fit_a, false));
    try expectActions(false, null, owed.step(.preview, fit_a, false));
    try expectActions(false, null, owed.step(.online, fit_a, true));
    try expectActions(false, null, owed.step(.resumed, fit_a, false));
    try expectActions(true, fit_a, owed.step(.online, fit_a, true));

    // Without an area there is nothing to show: the pane is not shown, as by a surface.
    var flat: rules.State = .{};
    try expectActions(true, fit_a, flat.step(.surface, fit_a, true));
    flat.take_inflight = false;
    try expectActions(false, null, flat.step(.preview, fit_a, true));
    try expectActions(false, null, flat.step(.resumed, null, true));
    try testing.expect(!flat.shown);
    try expectActions(true, null, flat.step(.surface, fit_a, true));
}

test "size rules: a pane whose surface went away with the app comes back as it was" {
    // Shown, then the screen goes off and another client takes the terminal.
    var s: rules.State = .{};
    try expectActions(true, fit_a, s.step(.surface, fit_a, true));
    s.take_inflight = false;
    try expectActions(false, null, s.step(.suspended, null, true));
    try testing.expect(!s.shown);
    try expectActions(false, null, s.step(.fit, fit_a, true));
    // Awake again with nothing changed: no take and no size.
    try expectActions(false, null, s.step(.resumed, fit_a, true));
    try testing.expect(s.shown);
    // Shown as it was: only a use takes.
    try expectActions(true, null, s.step(.used, fit_a, true));

    // The pane area changed meanwhile (the phone was folded): the size is asked, nothing is taken.
    var folded: rules.State = .{};
    try expectActions(true, fit_a, folded.step(.surface, fit_a, true));
    folded.take_inflight = false;
    try expectActions(false, null, folded.step(.suspended, null, true));
    try expectActions(false, fit_b, folded.step(.resumed, fit_b, true));

    // The link dropped while the screen was off: the size is asked again, still no take.
    var dropped: rules.State = .{};
    try expectActions(true, fit_a, dropped.step(.surface, fit_a, true));
    dropped.take_inflight = false;
    try expectActions(false, null, dropped.step(.suspended, null, true));
    dropped.linkLost();
    try expectActions(false, null, dropped.step(.online, null, true));
    try expectActions(false, fit_a, dropped.step(.resumed, fit_a, true));

    // The overview was open when the screen went off: back, the pane is a preview again,
    // and closing the overview on its tab still claims nothing.
    var overview: rules.State = .{};
    try expectActions(true, fit_a, overview.step(.surface, fit_a, true));
    overview.take_inflight = false;
    try expectActions(false, null, overview.step(.preview, fit_a, true));
    try expectActions(false, null, overview.step(.suspended, null, true));
    try expectActions(false, null, overview.step(.preview, fit_a, true));
    try expectActions(false, null, overview.step(.resumed, fit_a, true));

    // The pane was closed while the app was away: nothing to come back to.
    var closed: rules.State = .{};
    try expectActions(true, fit_a, closed.step(.surface, fit_a, true));
    closed.take_inflight = false;
    try expectActions(false, null, closed.step(.suspended, null, true));
    try expectActions(false, null, closed.step(.hidden, null, true));
    try expectActions(true, null, closed.step(.resumed, fit_a, true));

    // A pane that was never shown is shown by its first surface, with its take.
    var first: rules.State = .{};
    try expectActions(false, null, first.step(.suspended, null, true));
    try expectActions(true, fit_a, first.step(.resumed, fit_a, true));
}

test "size rules: a pane the app's own sidebar or screen covered comes back as it was" {
    // Shown, and another device owns the terminal. The compact sidebar or Settings takes
    // the workspace's place: the pane's view goes, which the app reports as `suspended`.
    var s: rules.State = .{};
    try expectActions(true, fit_a, s.step(.surface, fit_a, true));
    s.take_inflight = false;
    try testing.expect(!s.owned);
    try expectActions(false, null, s.step(.suspended, null, true));
    try testing.expect(!s.shown);
    // Shown again with the size it had: no take and no size.
    try expectActions(false, null, s.step(.resumed, fit_a, true));
    try testing.expect(s.shown);
    // Looking was no use; typing in the pane is, and takes as before.
    try expectActions(true, null, s.step(.used, fit_a, true));

    // The window was rotated or folded under the cover: the size is asked for, as for a
    // shown pane whose size changes, and nothing is taken.
    var turned: rules.State = .{};
    try expectActions(true, fit_a, turned.step(.surface, fit_a, true));
    turned.take_inflight = false;
    try expectActions(false, null, turned.step(.suspended, null, true));
    try expectActions(false, fit_b, turned.step(.resumed, fit_b, true));
    try expectActions(false, null, turned.step(.fit, fit_b, true));
    try expectActions(true, null, turned.step(.used, fit_b, true));

    // Covered a second time: each return is its own, and each claims nothing.
    var twice: rules.State = .{};
    try expectActions(true, fit_a, twice.step(.surface, fit_a, true));
    twice.take_inflight = false;
    try expectActions(false, null, twice.step(.suspended, null, true));
    try expectActions(false, null, twice.step(.resumed, fit_a, true));
    try expectActions(false, null, twice.step(.suspended, null, true));
    try expectActions(false, null, twice.step(.resumed, fit_a, true));

    // Another tab was picked under the cover, and this one again later: a pane that the app
    // does not report as back is shown by the take rules.
    var picked: rules.State = .{};
    try expectActions(true, fit_a, picked.step(.surface, fit_a, true));
    picked.take_inflight = false;
    try expectActions(false, null, picked.step(.suspended, null, true));
    try expectActions(true, null, picked.step(.surface, fit_a, true));

    // This device owns the terminal: the same, and the changed size is the owner's.
    var mine: rules.State = .{};
    try expectActions(true, fit_a, mine.step(.surface, fit_a, true));
    mine.take_inflight = false;
    mine.owned = true;
    try expectActions(false, null, mine.step(.suspended, null, true));
    try expectActions(false, null, mine.step(.resumed, fit_a, true));
    try expectActions(false, null, mine.step(.suspended, null, true));
    try expectActions(false, fit_b, mine.step(.resumed, fit_b, true));
}

// ------------------------------------------- terminal size: the core's wiring

const term_block = "block:t";
const me = "client:me";
const other = "client:other";

/// A `session.view` of `session:a` with one terminal block owned by `owner`.
fn sizeView(buf: []u8, revision: u32, owner: ?[]const u8) ![]const u8 {
    var owner_buf: [64]u8 = undefined;
    const owner_field = if (owner) |o| try std.fmt.bufPrint(&owner_buf, ",\"owner\":\"{s}\"", .{o}) else "";
    return std.fmt.bufPrint(buf,
        \\{{"session_id":"session:a","label":"a","revision":{d},"active_window_id":"window:w","windows":[
        \\{{"window_id":"window:w","label":"w","focused_block_id":"{s}","layers":[{{"layer_id":"layer:l",
        \\"kind":"tiled","bounds":[0,0,1,1],"layout":{{"rect":[0,0,1,1],"block_id":"{s}"}},
        \\"blocks":[{{"block_id":"{s}","creator_name":"com.superlogical.terminal"{s}}}]}}]}}]}}
    , .{ revision, term_block, term_block, term_block, owner_field });
}

/// A bootstrapped core that is `client:me` and attached `session:a`, whose view has
/// the terminal `block:t` owned by `owner`. Its font is 13 px with a cell of 8 x 16 px.
fn withTerminal(recorder: *Recorder, owner: ?[]const u8) !*Core {
    const core = try bootstrapped(recorder);
    errdefer core.destroy();
    core.receive("{\"type\":\"welcome\",\"protocol\":\"rex.control.v1\",\"client\":{\"id\":\"" ++ me ++ "\",\"reconnect_token\":\"\"},\"api_revision\":18}");
    const id = (try pendingId(core, .attach)) orelse return error.NoAttachRequest;
    var buf: [1024]u8 = undefined;
    try receiveFmt(core, "{{\"type\":\"response\",\"id\":\"{d}\",\"payload\":{{\"client_id\":\"" ++ me ++ "\",\"view\":{s}}}}}", .{ id, try sizeView(&buf, 1, owner) });
    return core;
}

test "Core: a held TUI snapshot cannot send scroll input until the data stream is live" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    const core = try withTerminal(&recorder, me);
    defer core.destroy();
    const entry = core.entries.get(term_block).?;
    // The test control socket is ready throughout. Only the data socket changes.
    const stream = entry.stream.?;
    stream.live_snapshot.store(false, .release);
    entry.pane.setFontSizePx(10);
    entry.pane.surface_h = 288;
    entry.pane.write("\x1b[?1049h\x1b[?1007h");
    core.panePan(term_block, 0, 12, 30, 30);
    try testing.expectEqual(@as(usize, 0), queued(core, protocol.methods.terminalWrite));
    entry.pane.write("\x1b[?1000h");
    core.panePan(term_block, 0, 12, 30, 30);
    try testing.expectEqual(@as(usize, 0), queued(core, protocol.methods.blockEvent));
    stream.live_snapshot.store(true, .release);
    core.panePan(term_block, 0, 24, 30, 30);
    try testing.expectEqual(@as(usize, 2), queued(core, protocol.methods.blockEvent));
}

test "composer accepts literal Unicode, separates a file, and frames LF/tab only in paste mode" {
    const gpa = testing.allocator;
    const plain = try protocol.composerInput(gpa, "look Ω", true, false);
    defer gpa.free(plain);
    try testing.expectEqualStrings("look Ω ", plain);
    const paste = try protocol.composerInput(gpa, "line\n\ttwo", false, true);
    defer gpa.free(paste);
    try testing.expectEqualStrings("\x1b[200~line\n\ttwo\x1b[201~", paste);
    try testing.expectError(error.PasteModeRequired, protocol.composerInput(gpa, "line\ntwo", false, false));
    for ([_][]const u8{ "\r", "\x00", "\x7f", "\u{9b}201~", "end\x1b[201~" }) |bad|
        try testing.expectError(error.ControlCharacter, protocol.composerInput(gpa, bad, false, true));
}

test "composer reads the terminal's current paste mode and rejects a superseded target" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    const core = try withTerminal(&recorder, me);
    defer core.destroy();
    const epoch = core.composerTarget("session:a", term_block, false);
    try testing.expect(epoch != 0);
    try testing.expectEqual(@as(u64, 0), core.composerTarget("session:b", term_block, false));
    core.lock.lock();
    const terminal = core.entries.get(term_block).?.pane;
    core.lock.unlock();
    try testing.expect(!terminal.bracketedPaste());
    try testing.expectEqual(@as(u64, 0), core.composerTarget("session:a", term_block, true));
    terminal.write("\x1b[?2004h");
    try testing.expect(terminal.bracketedPaste());
    terminal.write("\x1b[?2004l");
    try testing.expect(!terminal.bracketedPaste());
    core.lock.lock();
    core.linkEnded();
    core.lock.unlock();
    // Found before anything is written: a name of its own, so the app need not warn of a partial write.
    try testing.expectError(error.TargetChangedBeforeWrite, core.sendComposer(epoch, "session:a", term_block, "no", null, null));
    try testing.expectError(error.TargetChangedBeforeWrite, core.sendComposer(0, "session:a", term_block, "no", null, null));
}

test "composer upload_begin uses one canonical ref, basename and exact size under args" {
    const p = try protocol.uploadBegin(testing.allocator, "s", "b", @splat(0), "cat.png", 12);
    defer testing.allocator.free(p);
    try testing.expectEqualStrings("{\"session_id\":\"s\",\"block_id\":\"b\",\"args\":{\"ref\":\"upload:00000000-0000-4000-8000-000000000000\",\"name\":\"cat.png\",\"size\":12}}", p);
    for ([_][]const u8{ "", "../x", "a\\b", ".", "..", "a\nb", "a\u{9b}" }) |bad| try testing.expectError(error.BadFileName, protocol.validateUploadName(bad));
}

test "composer confirms only an exact matching write; a dropped link abandons the request" {
    const Worker = struct {
        err: ?anyerror = null,
        fn send(w: *@This(), core: *Core, generation: u64) void {
            core.sendComposer(generation, "session:a", term_block, "ok", null, null) catch |err| { w.err = err; };
        }
    };
    var recorder: Recorder = .{};
    defer recorder.deinit();
    const core = try withTerminal(&recorder, me);
    defer core.destroy();
    const generation = core.composerTarget("session:a", term_block, false);
    for ([_]?anyerror{ null, error.Unconfirmed, error.ServerRefused, error.TargetChanged }, 0..) |want, i| {
        var worker: Worker = .{};
        {
            const t = try std.Thread.spawn(.{}, Worker.send, .{ &worker, core, generation });
            defer t.join();
            const id = for (0..200) |_| {
                if (try pendingId(core, .composer)) |id| break id;
                try std.Io.sleep(testing.io, .fromMilliseconds(5), .awake);
            } else return error.NoComposerRequest;
            try testing.expectError(error.InputBusy, core.sendComposer(generation, "session:a", term_block, "again", null, null));
            switch (i) {
                0 => try answer(core, id, "{\"bytes_written\":2}"),
                1 => try answer(core, id, "{\"bytes_written\":1}"),
                2 => try answerError(core, id, "conflict"),
                else => {
                    core.lock.lock();
                    core.linkEnded();
                    core.lock.unlock();
                    try answer(core, id, "{\"bytes_written\":2}");
                },
            }
        }
        try testing.expectEqual(want, worker.err);
        try testing.expect((try pendingId(core, .composer)) == null);
    }
}

/// Turn bracketed paste on in the terminal of `block`, as its program would, and say its
/// snapshot is live, as the stream's thread does after the first data (no data socket
/// runs in a host test).
fn bracketedPasteOn(core: *Core, block: []const u8) void {
    core.lock.lock();
    defer core.lock.unlock();
    const entry = core.entries.get(block).?;
    entry.pane.write("\x1b[?2004h");
    entry.stream.?.live_snapshot.store(true, .release);
}

/// Send the `paste` command for `text` to a new core's terminal, whose bracketed paste is
/// `bracketed`. `want` is the bytes of the one terminal write this queues; null is no write
/// and the error `paste_needs_confirm`.
fn expectPaste(bracketed: bool, text: []const u8, confirmed: bool, want: ?[]const u8) !void {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    const core = try withTerminal(&recorder, me);
    defer core.destroy();
    if (bracketed) bracketedPasteOn(core, term_block);
    try commandFmt(core, "{{\"op\":\"paste\",\"block\":\"" ++ term_block ++ "\",\"text\":{f},\"confirmed\":{}}}", .{ std.json.fmt(text, .{}), confirmed });

    const bytes = want orelse {
        try testing.expectEqual(@as(usize, 0), queued(core, terminal_write));
        try testing.expectEqual(@as(usize, 1), recorder.errors.items.len);
        return testing.expectEqualStrings("paste_needs_confirm", recorder.errors.items[0]);
    };
    try testing.expectEqual(@as(usize, 0), recorder.errors.items.len);
    try testing.expectEqual(@as(usize, 1), queued(core, terminal_write));
    const request = try lastQueued(core, terminal_write);
    defer testing.allocator.free(request);
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    const root = try std.json.parseFromSliceLeaky(std.json.Value, arena.allocator(), request, .{});
    const args = model.get(model.get(root, "payload").?, "args").?;
    try testing.expectEqualStrings(bytes, model.str(model.get(args, "data")).?);
}

test "Core: a paste is framed for a terminal with bracketed paste, and the text cannot end the paste itself" {
    try expectPaste(true, "ls\n\tpwd\n", false, "\x1b[200~ls\n\tpwd\n\x1b[201~");
    try expectPaste(true, "ls\x1b[201~\nrm -r x\n", false, "\x1b[200~ls\nrm -r x\n\x1b[201~");
    // Taking the end mark out must not join the bytes around it into another one.
    try expectPaste(true, "ls\x1b[20\x1b[201~1~\nrm -r x\n", false, "\x1b[200~ls\nrm -r x\n\x1b[201~");
    // The terminal's mode decides, not the answer: a confirmed paste is framed all the same.
    try expectPaste(true, "ls\npwd\n", true, "\x1b[200~ls\npwd\n\x1b[201~");
}

test "Core: without bracketed paste a text of one line is written as it is" {
    try expectPaste(false, "echo Ω\tdone", false, "echo Ω\tdone");
}

test "Core: without bracketed paste a text with a line break or a control character is written only when confirmed" {
    for ([_][]const u8{ "ls\npwd\n", "ls\r", "ls\x1b[A", "\x00", "ls\x7f", "ls\u{9b}A" }) |text| {
        try expectPaste(false, text, false, null);
        try expectPaste(false, text, true, text);
    }
}

test "Core: a paste for a block the core does not mirror is held to the rule without bracketed paste" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    const core = try withTerminal(&recorder, me);
    defer core.destroy();
    // No pane, so no mode to read: a line break still waits for the confirmation.
    core.command("{\"op\":\"paste\",\"block\":\"block:none\",\"text\":\"ls\\npwd\",\"confirmed\":false}");
    try testing.expectEqual(@as(usize, 0), queued(core, terminal_write));
    try testing.expectEqual(@as(usize, 1), recorder.errors.items.len);
    try testing.expectEqualStrings("paste_needs_confirm", recorder.errors.items[0]);
    // What may be written goes to the server, which knows the block, as `sendText` does.
    core.command("{\"op\":\"paste\",\"block\":\"block:none\",\"text\":\"ls\",\"confirmed\":false}");
    try expectRequest(core, terminal_write, "{\"session_id\":\"session:a\",\"block_id\":\"block:none\",\"args\":{\"data\":\"ls\"}}");
}

test "Core: the app is told a terminal has bracketed paste only while its snapshot is live" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    const core = try withTerminal(&recorder, me);
    defer core.destroy();
    try testing.expect(!core.paneBracketedPaste(term_block));
    bracketedPasteOn(core, term_block);
    try testing.expect(core.paneBracketedPaste(term_block));
    try testing.expect(!core.paneBracketedPaste("block:none"));
    // A stream that reconnects shows a held picture: its mode may be the old program's.
    core.lock.lock();
    core.entries.get(term_block).?.stream.?.restart();
    core.lock.unlock();
    try testing.expect(!core.paneBracketedPaste(term_block));
}

test "Core: the app is told whether a pane has a selection" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    const core = try withTerminal(&recorder, me);
    defer core.destroy();
    core.lock.lock();
    core.entries.get(term_block).?.pane.write("\x1b[2J\x1b[Habcdef ghijkl");
    core.lock.unlock();
    try testing.expect(!core.paneHasSelection(term_block));
    // Android's down, move and up: a drag along the first row.
    core.paneTouch(term_block, 0, 1, 1);
    core.paneTouch(term_block, 2, 200, 1);
    core.paneTouch(term_block, 1, 200, 1);
    try testing.expect(core.paneHasSelection(term_block));
    try testing.expect(!core.paneHasSelection("block:none"));
    // A tap on the pane clears it.
    core.paneClearSelection(term_block);
    try testing.expect(!core.paneHasSelection(term_block));
    core.paneClearSelection("block:none");
}

test "range edits and selection-only copy stay on the requested block" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    const core = try withTerminal(&recorder, me);
    defer core.destroy();
    const p = core.entries.get(term_block).?.pane;
    p.setPaddingPx(0);
    p.setFontSizePx(10);
    p.surface_w = 120;
    p.surface_h = 48;
    p.write("left right");
    core.paneSelectWord(term_block, 32, 3);
    core.paneSelectWord("block:none", 1, 1);
    core.paneMoveSelectionHandle("block:none", true, 1, 1);
    try testing.expect(core.paneSelectionBounds("block:none") == null);
    try testing.expectError(error.UnknownBlock, core.paneSelectedText(testing.allocator, "block:none"));
    try testing.expectEqual([4]i32{ 30, 12, 60, 12 }, core.paneSelectionBounds(term_block).?);
    core.paneMoveSelectionHandle(term_block, true, 19, 3);
    const text = try core.paneSelectedText(testing.allocator, term_block);
    defer testing.allocator.free(text);
    try testing.expectEqualStrings("t right", text);
    core.paneMoveSelectionHandle(term_block, false, 26, 3); // include the written space
    const spaced = try core.paneSelectedText(testing.allocator, term_block);
    defer testing.allocator.free(spaced);
    try testing.expectEqualStrings("t ", spaced);
    core.paneClearSelection(term_block);
    const empty = try core.paneSelectedText(testing.allocator, term_block);
    defer testing.allocator.free(empty);
    try testing.expectEqualStrings("", empty);
}

/// Stands in for an ANativeWindow: host builds never touch it.
var fake_window: u8 = 0;

/// The last queued request for `method`. Caller frees.
fn lastQueued(core: *Core, method: []const u8) ![]u8 {
    core.lock.lock();
    defer core.lock.unlock();
    var buf: [64]u8 = undefined;
    const needle = try std.fmt.bufPrint(&buf, "\"method\":\"{s}\"", .{method});
    var i = core.outbox.items.len;
    while (i > 0) {
        i -= 1;
        if (std.mem.find(u8, core.outbox.items[i], needle) != null) return testing.allocator.dupe(u8, core.outbox.items[i]);
    }
    return error.NothingQueued;
}

fn expectLastResize(core: *Core, comptime args: []const u8) !void {
    const text = try lastQueued(core, protocol.methods.terminalResize);
    defer testing.allocator.free(text);
    const want = "\"payload\":{\"session_id\":\"session:a\",\"block_id\":\"" ++ term_block ++ "\",\"args\":{" ++ args ++ "}}";
    if (std.mem.find(u8, text, want) == null) {
        std.debug.print("resize request\n  {s}\nlacks\n  {s}\n", .{ text, want });
        return error.TestExpectedEqual;
    }
}

/// Answer the pending `session.own_block` with `owner` as the block's owner.
fn answerTake(core: *Core, owner: []const u8) !void {
    const id = (try pendingId(core, .take)) orelse return error.NoTakeRequest;
    try receiveFmt(core, "{{\"type\":\"response\",\"id\":\"{d}\",\"payload\":{{\"blocks\":[{{\"block_id\":\"" ++ term_block ++ "\",\"owner\":\"{s}\"}}]}}}}", .{ id, owner });
}

fn ownerChanged(core: *Core, owner: []const u8) !void {
    try receiveFmt(core,
        \\{{"type":"event","seq":40,"payload":{{"type":"session_event","payload":{{"session_id":"session:a",
        \\"event":{{"type":"block_owner_changed","payload":{{"block_id":"{s}","owner":"{s}","reason":"take"}}}}}}}}}}
    , .{ term_block, owner });
}

const own_block = protocol.methods.ownBlock;
const resize = protocol.methods.terminalResize;

test "Core: the first surface takes the block and asks for the fit; a resized surface waits for fit" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    const core = try withTerminal(&recorder, other);
    defer core.destroy();

    // Attaching alone claims nothing.
    try testing.expectEqual(@as(usize, 0), queued(core, own_block));
    try testing.expectEqual(@as(usize, 0), queued(core, resize));

    core.paneSurface(term_block, &fake_window, 1000, 600);
    try testing.expectEqual(@as(usize, 1), queued(core, own_block));
    try testing.expectEqual(@as(usize, 1), queued(core, resize));
    const take = try lastQueued(core, own_block);
    defer testing.allocator.free(take);
    try testing.expect(std.mem.find(u8, take, "\"payload\":{\"session_id\":\"session:a\",\"block_ids\":[\"" ++ term_block ++ "\"],\"mode\":\"take\"}") != null);
    try expectLastResize(core, "\"columns\":125,\"rows\":37,\"cell_width_px\":8,\"cell_height_px\":16");
    {
        // The take is queued before the size. (The pane's data stream queues its own
        // `data.connect` at any time, so only the order of the two is checked.)
        core.lock.lock();
        defer core.lock.unlock();
        var take_at: ?usize = null;
        var size_at: ?usize = null;
        for (core.outbox.items, 0..) |text, i| {
            if (std.mem.find(u8, text, own_block) != null) take_at = i;
            if (std.mem.find(u8, text, resize) != null) size_at = i;
        }
        try testing.expect(take_at.? < size_at.?);
    }

    // The view changes size (keyboard animation): every frame replaces the surface.
    core.paneSurface(term_block, &fake_window, 1000, 500);
    core.paneSurface(term_block, &fake_window, 1000, 400);
    core.paneSurface(term_block, &fake_window, 800, 600);
    try testing.expectEqual(@as(usize, 1), queued(core, own_block));
    try testing.expectEqual(@as(usize, 1), queued(core, resize));

    // The app reports the settled size.
    core.command("{\"op\":\"fit\",\"block\":\"" ++ term_block ++ "\"}");
    try testing.expectEqual(@as(usize, 2), queued(core, resize));
    try expectLastResize(core, "\"columns\":100,\"rows\":37,\"cell_width_px\":8,\"cell_height_px\":16");
    core.command("{\"op\":\"fit\",\"block\":\"" ++ term_block ++ "\"}");
    try testing.expectEqual(@as(usize, 2), queued(core, resize));

    // A pinch changes the scale many times; only its end asks.
    core.paneScale(term_block, 1.3, true);
    core.paneScale(term_block, 1.7, true);
    core.paneScale(term_block, 2, true);
    core.paneScale(term_block, 2, false);
    try testing.expectEqual(@as(usize, 2), queued(core, resize));
    core.command("{\"op\":\"fit\",\"block\":\"" ++ term_block ++ "\"}");
    try testing.expectEqual(@as(usize, 3), queued(core, resize));
    try expectLastResize(core, "\"columns\":50,\"rows\":18,\"cell_width_px\":16,\"cell_height_px\":32");

    // A block the core does not know is ignored.
    core.command("{\"op\":\"fit\",\"block\":\"block:none\"}");
    core.command("{\"op\":\"touch\",\"block\":\"block:none\"}");
    try testing.expectEqual(@as(usize, 1), queued(core, own_block));
    try testing.expectEqual(@as(usize, 3), queued(core, resize));
    try testing.expectEqual(@as(usize, 0), recorder.errors.items.len);
}

test "Core: no take for a block this client owns; a setting asks where the fit changed" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    const core = try withTerminal(&recorder, me);
    defer core.destroy();

    core.paneSurface(term_block, &fake_window, 1000, 600);
    core.paneTouch(term_block, 0, 10, 10);
    core.key(term_block, 66, 0, 0, 0); // KEYCODE_ENTER
    core.command("{\"op\":\"sendText\",\"block\":\"" ++ term_block ++ "\",\"text\":\"ls\"}");
    try testing.expectEqual(@as(usize, 0), queued(core, own_block));
    try testing.expectEqual(@as(usize, 1), queued(core, resize));

    // 20 px on every side: 960 x 560 px for cells of 8 x 16.
    core.command("{\"op\":\"setting\",\"key\":\"panePaddingPx\",\"value\":20}");
    try testing.expectEqual(@as(usize, 2), queued(core, resize));
    try expectLastResize(core, "\"columns\":120,\"rows\":35,\"cell_width_px\":8,\"cell_height_px\":16");
    // The same values again change nothing.
    core.command("{\"op\":\"setting\",\"key\":\"panePaddingPx\",\"value\":20}");
    core.command("{\"op\":\"setting\",\"key\":\"fontSize\",\"value\":13}");
    core.command("{\"op\":\"setting\",\"key\":\"fontLineHeight\",\"value\":1.2}");
    try testing.expectEqual(@as(usize, 2), queued(core, resize));
    core.command("{\"op\":\"setting\",\"key\":\"fontSize\",\"value\":26}");
    try testing.expectEqual(@as(usize, 3), queued(core, resize));
    try expectLastResize(core, "\"columns\":60,\"rows\":17,\"cell_width_px\":16,\"cell_height_px\":32");

    // A pane that is not shown asks for nothing; shown again it asks for its new fit.
    core.paneSurface(term_block, null, 0, 0);
    core.command("{\"op\":\"setting\",\"key\":\"fontSize\",\"value\":13}");
    core.command("{\"op\":\"fit\",\"block\":\"" ++ term_block ++ "\"}");
    try testing.expectEqual(@as(usize, 3), queued(core, resize));
    core.paneSurface(term_block, &fake_window, 1000, 600);
    try testing.expectEqual(@as(usize, 4), queued(core, resize));
    try expectLastResize(core, "\"columns\":120,\"rows\":35,\"cell_width_px\":8,\"cell_height_px\":16");
    // Hidden and shown with nothing changed: the server holds the size.
    core.paneSurface(term_block, null, 0, 0);
    core.paneSurface(term_block, &fake_window, 1000, 600);
    try testing.expectEqual(@as(usize, 4), queued(core, resize));
    try testing.expectEqual(@as(usize, 0), queued(core, own_block));
    try testing.expectEqual(@as(usize, 0), recorder.errors.items.len);
}

test "Core: a new display scale fits every pane once, at its new font size and padding" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    const core = try withTerminal(&recorder, me);
    defer core.destroy();

    core.command("{\"op\":\"setting\",\"key\":\"fontSize\",\"value\":13}");
    core.command("{\"op\":\"setting\",\"key\":\"panePaddingPx\",\"value\":10}");
    core.paneSurface(term_block, &fake_window, 1000, 600);
    core.paneTouch(term_block, 0, 10, 10);
    const before = queued(core, resize);
    // The display's scale doubled (Display size): cells of 16 x 32 px for the same 13 sp, and
    // 20 px of padding on every side, so 960 x 560 px. One resize, not one for each value.
    core.command("{\"op\":\"setting\",\"key\":\"display\",\"value\":{\"pxPerSp\":2,\"panePaddingPx\":20}}");
    try testing.expectEqual(before + 1, queued(core, resize));
    try expectLastResize(core, "\"columns\":60,\"rows\":17,\"cell_width_px\":16,\"cell_height_px\":32");
    // The same scale again asks for nothing; one that is not a number or is missing a value is ignored.
    core.command("{\"op\":\"setting\",\"key\":\"display\",\"value\":{\"pxPerSp\":2,\"panePaddingPx\":20}}");
    core.command("{\"op\":\"setting\",\"key\":\"display\",\"value\":{\"pxPerSp\":0,\"panePaddingPx\":20}}");
    core.command("{\"op\":\"setting\",\"key\":\"display\",\"value\":{\"pxPerSp\":1}}");
    try testing.expectEqual(before + 1, queued(core, resize));
}

test "Core: input takes a block another client owns, with one take in flight" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    const core = try withTerminal(&recorder, other);
    defer core.destroy();

    core.paneSurface(term_block, &fake_window, 1000, 600);
    try testing.expectEqual(@as(usize, 1), queued(core, own_block));
    // Typing before the answer sends no second take.
    core.key(term_block, 66, 0, 0, 0);
    core.command("{\"op\":\"sendText\",\"block\":\"" ++ term_block ++ "\",\"text\":\"ls\"}");
    core.paneTouch(term_block, 0, 10, 10);
    core.command("{\"op\":\"touch\",\"block\":\"" ++ term_block ++ "\"}");
    try testing.expectEqual(@as(usize, 1), queued(core, own_block));
    try answerTake(core, me);
    core.key(term_block, 66, 0, 0, 0);
    try testing.expectEqual(@as(usize, 1), queued(core, own_block));

    // The desktop takes the block; each kind of use takes it back, and asks for no size.
    try ownerChanged(core, other);
    try testing.expectEqual(@as(usize, 1), queued(core, own_block));
    core.key(term_block, 66, 0, 0, 0);
    try testing.expectEqual(@as(usize, 2), queued(core, own_block));
    core.key(term_block, 66, 0, 0, 1); // key up: no bytes, no use
    try answerTake(core, me);

    try ownerChanged(core, other);
    core.command("{\"op\":\"sendText\",\"block\":\"" ++ term_block ++ "\",\"text\":\"ls\"}");
    try testing.expectEqual(@as(usize, 3), queued(core, own_block));
    try answerTake(core, me);

    try ownerChanged(core, other);
    core.paneTouch(term_block, 2, 10, 10); // a move takes nothing
    core.paneTouch(term_block, 1, 10, 10); // nor does a finger going up
    try testing.expectEqual(@as(usize, 3), queued(core, own_block));
    core.paneTouch(term_block, 0, 10, 10);
    try testing.expectEqual(@as(usize, 4), queued(core, own_block));
    try answerTake(core, me);

    try ownerChanged(core, other);
    core.command("{\"op\":\"touch\",\"block\":\"" ++ term_block ++ "\"}");
    try testing.expectEqual(@as(usize, 5), queued(core, own_block));

    // A refused take is no error for the user and is not sent again by itself.
    const id = (try pendingId(core, .take)) orelse return error.NoTakeRequest;
    try receiveFmt(core, "{{\"type\":\"response\",\"id\":\"{d}\",\"error\":{{\"code\":\"bad_request\",\"message\":\"no such block\",\"status\":400}}}}", .{id});
    try testing.expectEqual(@as(usize, 5), queued(core, own_block));
    try testing.expectEqual(@as(usize, 0), recorder.errors.items.len);
    // The next use tries again.
    core.command("{\"op\":\"touch\",\"block\":\"" ++ term_block ++ "\"}");
    try testing.expectEqual(@as(usize, 6), queued(core, own_block));

    // Through all of it the size was asked for once.
    try testing.expectEqual(@as(usize, 1), queued(core, resize));
}

test "Core: a surface with a zero axis shows nothing; the first real size takes and asks once" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    const core = try withTerminal(&recorder, other);
    defer core.destroy();

    // `surfaceChanged(0, 0)`, then a zero in one axis.
    core.paneSurface(term_block, &fake_window, 0, 0);
    core.paneSurface(term_block, &fake_window, 800, 0);
    core.paneSurface(term_block, &fake_window, 0, 600);
    try testing.expectEqual(@as(usize, 0), queued(core, own_block));
    try testing.expectEqual(@as(usize, 0), queued(core, resize));
    // A `fit` while there is no area sends nothing and uses nothing up.
    core.command("{\"op\":\"fit\",\"block\":\"" ++ term_block ++ "\"}");
    core.command("{\"op\":\"setting\",\"key\":\"fontSize\",\"value\":13}");
    try testing.expectEqual(@as(usize, 0), queued(core, own_block));
    try testing.expectEqual(@as(usize, 0), queued(core, resize));

    // The first size with an area is the show.
    core.paneSurface(term_block, &fake_window, 800, 600);
    try testing.expectEqual(@as(usize, 1), queued(core, own_block));
    try testing.expectEqual(@as(usize, 1), queued(core, resize));
    try expectLastResize(core, "\"columns\":100,\"rows\":37,\"cell_width_px\":8,\"cell_height_px\":16");
    core.paneSurface(term_block, &fake_window, 800, 600);
    core.command("{\"op\":\"fit\",\"block\":\"" ++ term_block ++ "\"}");
    try testing.expectEqual(@as(usize, 1), queued(core, own_block));
    try testing.expectEqual(@as(usize, 1), queued(core, resize));

    // A shown pane that collapses to nothing and comes back: the fit waits for the area.
    core.paneSurface(term_block, &fake_window, 0, 0);
    core.command("{\"op\":\"fit\",\"block\":\"" ++ term_block ++ "\"}");
    try testing.expectEqual(@as(usize, 1), queued(core, resize));
    core.paneSurface(term_block, &fake_window, 1000, 600);
    try testing.expectEqual(@as(usize, 2), queued(core, resize));
    try expectLastResize(core, "\"columns\":125,\"rows\":37,\"cell_width_px\":8,\"cell_height_px\":16");
    try testing.expectEqual(@as(usize, 1), queued(core, own_block));
    try testing.expectEqual(@as(usize, 0), recorder.errors.items.len);
}

test "Core: a take answer decides the owner: none listed is this client, another client is not" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    const core = try withTerminal(&recorder, other);
    defer core.destroy();

    core.paneSurface(term_block, &fake_window, 1000, 600);
    try testing.expectEqual(@as(usize, 1), queued(core, own_block));

    // The answer names a different client: it took the block right after. Not owned.
    try answerTake(core, other);
    try testing.expectEqual(@as(usize, 1), queued(core, own_block));
    // The next touch sends one take, and the keys after it none.
    core.command("{\"op\":\"touch\",\"block\":\"" ++ term_block ++ "\"}");
    try testing.expectEqual(@as(usize, 2), queued(core, own_block));
    for (0..10) |_| core.key(term_block, 66, 0, 0, 0);
    core.command("{\"op\":\"touch\",\"block\":\"" ++ term_block ++ "\"}");
    try testing.expectEqual(@as(usize, 2), queued(core, own_block));

    // `blocks: null`: the take succeeded, so this client is the owner. No take per key.
    const null_id = (try pendingId(core, .take)) orelse return error.NoTakeRequest;
    try receiveFmt(core, "{{\"type\":\"response\",\"id\":\"{d}\",\"payload\":{{\"blocks\":null}}}}", .{null_id});
    for (0..10) |_| core.key(term_block, 66, 0, 0, 0);
    core.command("{\"op\":\"touch\",\"block\":\"" ++ term_block ++ "\"}");
    try testing.expectEqual(@as(usize, 2), queued(core, own_block));

    // The same for an answer that lists other blocks only, or no payload at all.
    try ownerChanged(core, other);
    core.command("{\"op\":\"touch\",\"block\":\"" ++ term_block ++ "\"}");
    try testing.expectEqual(@as(usize, 3), queued(core, own_block));
    const other_id = (try pendingId(core, .take)) orelse return error.NoTakeRequest;
    try receiveFmt(core, "{{\"type\":\"response\",\"id\":\"{d}\",\"payload\":{{\"blocks\":[{{\"block_id\":\"block:else\",\"owner\":\"" ++ other ++ "\"}}]}}}}", .{other_id});
    core.key(term_block, 66, 0, 0, 0);
    try testing.expectEqual(@as(usize, 3), queued(core, own_block));

    try ownerChanged(core, other);
    core.command("{\"op\":\"touch\",\"block\":\"" ++ term_block ++ "\"}");
    try testing.expectEqual(@as(usize, 4), queued(core, own_block));
    const bare_id = (try pendingId(core, .take)) orelse return error.NoTakeRequest;
    try receiveFmt(core, "{{\"type\":\"response\",\"id\":\"{d}\"}}", .{bare_id});
    core.key(term_block, 66, 0, 0, 0);
    try testing.expectEqual(@as(usize, 4), queued(core, own_block));

    // Through all of it the size was asked for once.
    try testing.expectEqual(@as(usize, 1), queued(core, resize));
    try testing.expectEqual(@as(usize, 0), recorder.errors.items.len);
}

test "Core: a pane hidden and shown within one take round trip is taken when the answer says it must" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    const core = try withTerminal(&recorder, other);
    defer core.destroy();

    core.paneSurface(term_block, &fake_window, 1000, 600);
    core.paneSurface(term_block, null, 0, 0);
    core.paneSurface(term_block, &fake_window, 1000, 600);
    // One take in flight; the second show waits for its answer.
    try testing.expectEqual(@as(usize, 1), queued(core, own_block));
    // Another client took the block before the answer: the second show takes it.
    try answerTake(core, other);
    try testing.expectEqual(@as(usize, 2), queued(core, own_block));
    try answerTake(core, me);
    try testing.expectEqual(@as(usize, 2), queued(core, own_block));

    // The usual case: the answer names this client and nothing more goes out.
    try ownerChanged(core, other);
    core.paneSurface(term_block, null, 0, 0);
    core.paneSurface(term_block, &fake_window, 1000, 600);
    try testing.expectEqual(@as(usize, 3), queued(core, own_block));
    core.paneSurface(term_block, null, 0, 0);
    core.paneSurface(term_block, &fake_window, 1000, 600);
    try answerTake(core, me);
    try testing.expectEqual(@as(usize, 3), queued(core, own_block));

    // A refusal does not send the pending take.
    try ownerChanged(core, other);
    core.paneSurface(term_block, null, 0, 0);
    core.paneSurface(term_block, &fake_window, 1000, 600);
    core.paneSurface(term_block, null, 0, 0);
    core.paneSurface(term_block, &fake_window, 1000, 600);
    try testing.expectEqual(@as(usize, 4), queued(core, own_block));
    const id = (try pendingId(core, .take)) orelse return error.NoTakeRequest;
    try receiveFmt(core, "{{\"type\":\"response\",\"id\":\"{d}\",\"error\":{{\"code\":\"bad_request\",\"message\":\"no such block\",\"status\":400}}}}", .{id});
    try testing.expectEqual(@as(usize, 4), queued(core, own_block));
    try testing.expectEqual(@as(usize, 1), queued(core, resize));
    try testing.expectEqual(@as(usize, 0), recorder.errors.items.len);
}

test "Core: a refused resize is no error and the next fit asks again" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    const core = try withTerminal(&recorder, me);
    defer core.destroy();

    core.paneSurface(term_block, &fake_window, 1000, 600);
    const id = (try pendingId(core, .resize)) orelse return error.NoResizeRequest;
    try receiveFmt(core, "{{\"type\":\"response\",\"id\":\"{d}\",\"error\":{{\"code\":\"conflict\",\"message\":\"busy\",\"status\":409}}}}", .{id});
    try testing.expectEqual(@as(usize, 1), queued(core, resize));
    try testing.expectEqual(@as(usize, 0), recorder.errors.items.len);
    core.command("{\"op\":\"fit\",\"block\":\"" ++ term_block ++ "\"}");
    try testing.expectEqual(@as(usize, 2), queued(core, resize));
    // An accepted one is not repeated.
    const again = (try pendingId(core, .resize)) orelse return error.NoResizeRequest;
    try receiveFmt(core, "{{\"type\":\"response\",\"id\":\"{d}\",\"payload\":{{}}}}", .{again});
    core.command("{\"op\":\"fit\",\"block\":\"" ++ term_block ++ "\"}");
    try testing.expectEqual(@as(usize, 2), queued(core, resize));
}

/// Drop the control connection and bootstrap a new one that attached `session:a` with
/// `block:t` owned by `owner`.
fn reconnect(core: *Core, owner: ?[]const u8) !void {
    core.lock.lock();
    core.linkEnded();
    core.lock.unlock();
    try bootstrapAgain(core, owner);
}

fn bootstrapAgain(core: *Core, owner: ?[]const u8) !void {
    const id = registerBootstrap(core);
    var buf: [1024]u8 = undefined;
    try receiveFmt(core,
        \\{{"type":"response","id":"{d}","payload":{{"attach_all":{{"sessions":[
        \\{{"session_id":"session:a","label":"a"}},{{"session_id":"session:b","label":"b"}}]}},
        \\"attached_sessions":[{{"session":{{"session_id":"session:a","label":"a"}},"view":{s}}}],"data_tickets":[]}}}}
    , .{ id, try sizeView(&buf, 2, owner) });
}

test "Core: a reconnect claims nothing and the next fit asks again" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    const core = try withTerminal(&recorder, other);
    defer core.destroy();

    core.paneSurface(term_block, &fake_window, 1000, 600);
    try answerTake(core, me);

    // The server gave the block to the desktop while the phone was away. The pane is
    // still shown: nothing is claimed and nothing is asked.
    try reconnect(core, other);
    try testing.expectEqual(@as(usize, 0), queued(core, own_block));
    try testing.expectEqual(@as(usize, 0), queued(core, resize));
    // Nor when the server restored the block.
    try reconnect(core, me);
    try testing.expectEqual(@as(usize, 0), queued(core, own_block));
    try testing.expectEqual(@as(usize, 0), queued(core, resize));

    // The same size as before the drop is asked for again, once.
    core.command("{\"op\":\"fit\",\"block\":\"" ++ term_block ++ "\"}");
    try testing.expectEqual(@as(usize, 1), queued(core, resize));
    try expectLastResize(core, "\"columns\":125,\"rows\":37,\"cell_width_px\":8,\"cell_height_px\":16");
    core.command("{\"op\":\"fit\",\"block\":\"" ++ term_block ++ "\"}");
    try testing.expectEqual(@as(usize, 1), queued(core, resize));
    try testing.expectEqual(@as(usize, 0), queued(core, own_block));

    // A take that was in flight when the link dropped does not block the next one.
    try reconnect(core, other);
    core.command("{\"op\":\"touch\",\"block\":\"" ++ term_block ++ "\"}");
    try testing.expectEqual(@as(usize, 1), queued(core, own_block));
    try reconnect(core, other);
    core.command("{\"op\":\"touch\",\"block\":\"" ++ term_block ++ "\"}");
    try testing.expectEqual(@as(usize, 1), queued(core, own_block));
    try testing.expectEqual(@as(usize, 0), recorder.errors.items.len);
}

test "Core: a pane shown while the link is down is taken and sized when it is back" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    const core = try withTerminal(&recorder, other);
    defer core.destroy();

    core.lock.lock();
    core.linkEnded();
    core.lock.unlock();
    // The app comes back to the front during the reconnect.
    core.paneSurface(term_block, &fake_window, 1000, 600);
    core.key(term_block, 66, 0, 0, 0);
    try testing.expectEqual(@as(usize, 0), queued(core, own_block));
    try testing.expectEqual(@as(usize, 0), queued(core, resize));

    try bootstrapAgain(core, other);
    try testing.expectEqual(@as(usize, 1), queued(core, own_block));
    try testing.expectEqual(@as(usize, 1), queued(core, resize));
    try expectLastResize(core, "\"columns\":125,\"rows\":37,\"cell_width_px\":8,\"cell_height_px\":16");
    try testing.expectEqual(@as(usize, 0), recorder.errors.items.len);
}

test "Core: the owner is read from the view and from block_owner_changed" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    // A block this client made is its own from the start (`create`).
    const core = try withTerminal(&recorder, me);
    defer core.destroy();
    core.paneSurface(term_block, &fake_window, 1000, 600);
    try testing.expectEqual(@as(usize, 0), queued(core, own_block));

    // Another session's event about a block of the same id changes nothing.
    try receiveFmt(core,
        \\{{"type":"event","seq":41,"payload":{{"type":"session_event","payload":{{"session_id":"session:b",
        \\"event":{{"type":"block_owner_changed","payload":{{"block_id":"{s}","owner":"{s}"}}}}}}}}}}
    , .{ term_block, other });
    core.command("{\"op\":\"touch\",\"block\":\"" ++ term_block ++ "\"}");
    try testing.expectEqual(@as(usize, 0), queued(core, own_block));

    // No owner at all (the owner left and nobody stood in line): a touch takes.
    try receiveFmt(core,
        \\{{"type":"event","seq":42,"payload":{{"type":"session_event","payload":{{"session_id":"session:a",
        \\"event":{{"type":"block_owner_changed","payload":{{"block_id":"{s}","previous":"{s}","reason":"disown"}}}}}}}}}}
    , .{ term_block, me });
    core.command("{\"op\":\"touch\",\"block\":\"" ++ term_block ++ "\"}");
    try testing.expectEqual(@as(usize, 1), queued(core, own_block));
    try answerTake(core, me);

    // A newer view that names another owner: the next touch takes again.
    core.receive("{\"type\":\"event\",\"seq\":43,\"payload\":{\"type\":\"session_event\",\"payload\":{\"session_id\":\"session:a\",\"event\":{\"type\":\"session_view_changed\",\"payload\":{\"revision\":5}}}}}");
    const view_id = (try pendingId(core, .view)) orelse return error.NoViewRequest;
    var buf: [1024]u8 = undefined;
    try receiveFmt(core, "{{\"type\":\"response\",\"id\":\"{d}\",\"payload\":{s}}}", .{ view_id, try sizeView(&buf, 5, other) });
    // The view alone sends nothing.
    try testing.expectEqual(@as(usize, 1), queued(core, own_block));
    try testing.expectEqual(@as(usize, 1), queued(core, resize));
    core.command("{\"op\":\"touch\",\"block\":\"" ++ term_block ++ "\"}");
    try testing.expectEqual(@as(usize, 2), queued(core, own_block));
    try testing.expectEqual(@as(usize, 0), recorder.errors.items.len);
}

// ------------------------------------------- terminal size: preview surfaces

const other_block = "block:u";
const terminal_write = protocol.methods.terminalWrite;

/// Queued requests for `method` that name `block`.
fn queuedFor(core: *Core, method: []const u8, block: []const u8) usize {
    core.lock.lock();
    defer core.lock.unlock();
    var method_buf: [64]u8 = undefined;
    var block_buf: [64]u8 = undefined;
    const method_needle = std.fmt.bufPrint(&method_buf, "\"method\":\"{s}\"", .{method}) catch unreachable;
    const block_needle = std.fmt.bufPrint(&block_buf, "\"{s}\"", .{block}) catch unreachable;
    var n: usize = 0;
    for (core.outbox.items) |text| {
        if (std.mem.find(u8, text, method_needle) != null and std.mem.find(u8, text, block_needle) != null) n += 1;
    }
    return n;
}

/// Queued requests for every method but `method`. (A pane's data stream queues its own
/// `data.connect` at any time, so a count of everything is not stable.)
fn queuedApart(core: *Core, method: []const u8) usize {
    core.lock.lock();
    defer core.lock.unlock();
    var buf: [64]u8 = undefined;
    const needle = std.fmt.bufPrint(&buf, "\"method\":\"{s}\"", .{method}) catch unreachable;
    var n: usize = 0;
    for (core.outbox.items) |text| {
        if (std.mem.find(u8, text, needle) == null) n += 1;
    }
    return n;
}

/// Like `withTerminal`, with the terminals `block:t` and `block:u` side by side, both
/// owned by this client.
fn withTwoTerminals(recorder: *Recorder) !*Core {
    const core = try bootstrapped(recorder);
    errdefer core.destroy();
    core.receive("{\"type\":\"welcome\",\"protocol\":\"rex.control.v1\",\"client\":{\"id\":\"" ++ me ++ "\",\"reconnect_token\":\"\"},\"api_revision\":18}");
    const id = (try pendingId(core, .attach)) orelse return error.NoAttachRequest;
    const view =
        \\{"session_id":"session:a","label":"a","revision":1,"active_window_id":"window:w","windows":[
        \\{"window_id":"window:w","label":"w","focused_block_id":"block:t","layers":[{"layer_id":"layer:l",
        \\"kind":"tiled","bounds":[0,0,1,1],"layout":{"rect":[0,0,1,1],"split":{"direction":"horizontal","ratio":0.5,
        \\"before":{"rect":[0,0,0.5,1],"block_id":"block:t"},"after":{"rect":[0.5,0,0.5,1],"block_id":"block:u"}}},
        \\"blocks":[{"block_id":"block:t","creator_name":"com.superlogical.terminal","owner":"client:me"},
        \\{"block_id":"block:u","creator_name":"com.superlogical.terminal","owner":"client:me"}]}]}]}
    ;
    try receiveFmt(core, "{{\"type\":\"response\",\"id\":\"{d}\",\"payload\":{{\"client_id\":\"" ++ me ++ "\",\"view\":{s}}}}}", .{ id, view });
    return core;
}

test "Core: a shown pane that becomes a preview sends nothing, and neither does a fit for it" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    const core = try withTerminal(&recorder, other);
    defer core.destroy();

    core.paneSurface(term_block, &fake_window, 1000, 600);
    try answerTake(core, me);
    try testing.expectEqual(@as(usize, 1), queued(core, own_block));
    try testing.expectEqual(@as(usize, 1), queued(core, resize));

    // The overview opens: the same surface, now a preview.
    core.panePreview(term_block, &fake_window, 1000, 600);
    try testing.expectEqual(@as(usize, 1), queued(core, own_block));
    try testing.expectEqual(@as(usize, 1), queued(core, resize));
    // A fit for another size (a pinch ended on it) asks for nothing while it is a preview.
    core.paneScale(term_block, 2, false);
    core.command("{\"op\":\"fit\",\"block\":\"" ++ term_block ++ "\"}");
    core.panePreview(term_block, &fake_window, 1000, 500);
    core.command("{\"op\":\"fit\",\"block\":\"" ++ term_block ++ "\"}");
    try testing.expectEqual(@as(usize, 1), queued(core, own_block));
    try testing.expectEqual(@as(usize, 1), queued(core, resize));
    try testing.expectEqual(@as(usize, 0), recorder.errors.items.len);
}

test "Core: a preview shown in place takes a block another client owns, and none this client owns" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    const core = try withTerminal(&recorder, other);
    defer core.destroy();

    core.paneSurface(term_block, &fake_window, 1000, 600);
    try answerTake(core, me);
    try testing.expectEqual(@as(usize, 1), queued(core, own_block));
    try testing.expectEqual(@as(usize, 1), queued(core, resize));

    // Into the overview and back with nothing changed: this client is the owner and the
    // server holds the size, so nothing is sent.
    core.panePreview(term_block, &fake_window, 1000, 600);
    core.paneSurface(term_block, &fake_window, 1000, 600);
    try testing.expectEqual(@as(usize, 1), queued(core, own_block));
    try testing.expectEqual(@as(usize, 1), queued(core, resize));

    // The desktop takes the block while the pane is a preview: that sends nothing.
    core.panePreview(term_block, &fake_window, 1000, 600);
    try ownerChanged(core, other);
    try testing.expectEqual(@as(usize, 1), queued(core, own_block));
    // Shown in place: a take, as for a first surface, and no size the server holds already.
    core.paneSurface(term_block, &fake_window, 1000, 600);
    try testing.expectEqual(@as(usize, 2), queued(core, own_block));
    try testing.expectEqual(@as(usize, 1), queued(core, resize));
    const take = try lastQueued(core, own_block);
    defer testing.allocator.free(take);
    try testing.expect(std.mem.find(u8, take, "\"payload\":{\"session_id\":\"session:a\",\"block_ids\":[\"" ++ term_block ++ "\"],\"mode\":\"take\"}") != null);
    try answerTake(core, me);

    // The fit changed while it was a preview (a pinch): shown again, the new one is asked.
    core.panePreview(term_block, &fake_window, 1000, 600);
    core.paneScale(term_block, 2, false);
    core.command("{\"op\":\"fit\",\"block\":\"" ++ term_block ++ "\"}");
    try testing.expectEqual(@as(usize, 1), queued(core, resize));
    core.paneSurface(term_block, &fake_window, 1000, 600);
    try testing.expectEqual(@as(usize, 2), queued(core, own_block));
    try testing.expectEqual(@as(usize, 2), queued(core, resize));
    try expectLastResize(core, "\"columns\":62,\"rows\":18,\"cell_width_px\":16,\"cell_height_px\":32");
    try testing.expectEqual(@as(usize, 0), recorder.errors.items.len);
}

test "Core: back from a preview on the pane that was shown takes nothing from another client" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    const core = try withTerminal(&recorder, other);
    defer core.destroy();

    core.paneSurface(term_block, &fake_window, 1000, 600);
    try answerTake(core, me);
    try testing.expectEqual(@as(usize, 1), queued(core, own_block));
    try testing.expectEqual(@as(usize, 1), queued(core, resize));

    // The desktop takes the terminal, then the overview opens and closes on the same tab.
    try ownerChanged(core, other);
    core.panePreview(term_block, &fake_window, 1000, 600);
    core.paneResume(term_block, &fake_window, 1000, 600);
    try testing.expectEqual(@as(usize, 1), queued(core, own_block));
    try testing.expectEqual(@as(usize, 1), queued(core, resize));

    // The pane is shown again: a touch on it takes the terminal, as before.
    core.command("{\"op\":\"touch\",\"block\":\"" ++ term_block ++ "\"}");
    try testing.expectEqual(@as(usize, 2), queued(core, own_block));
    try testing.expectEqual(@as(usize, 1), queued(core, resize));
    try answerTake(core, me);

    // The pane area changed while the overview was open: the size is asked, without a take.
    try ownerChanged(core, other);
    core.panePreview(term_block, &fake_window, 1000, 600);
    core.paneResume(term_block, &fake_window, 1000, 300);
    try testing.expectEqual(@as(usize, 2), queued(core, own_block));
    try testing.expectEqual(@as(usize, 2), queued(core, resize));

    try testing.expectEqual(@as(usize, 0), recorder.errors.items.len);
}

test "Core: a pane that was never shown is not resumed but shown, with its take" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    const core = try withTerminal(&recorder, other);
    defer core.destroy();

    core.panePreview(term_block, &fake_window, 1000, 600);
    try testing.expectEqual(@as(usize, 0), queued(core, own_block));
    core.paneResume(term_block, &fake_window, 1000, 600);
    try testing.expectEqual(@as(usize, 1), queued(core, own_block));
    try testing.expectEqual(@as(usize, 1), queued(core, resize));
    try testing.expectEqual(@as(usize, 0), recorder.errors.items.len);
}

test "Core: a pane back after the screen was off takes nothing from another client" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    const core = try withTerminal(&recorder, other);
    defer core.destroy();

    core.paneSurface(term_block, &fake_window, 1000, 600);
    try answerTake(core, me);
    try testing.expectEqual(@as(usize, 1), queued(core, own_block));
    try testing.expectEqual(@as(usize, 1), queued(core, resize));

    // The desktop takes the terminal; the phone's screen goes off and comes back.
    try ownerChanged(core, other);
    core.paneSuspend(term_block);
    core.paneResume(term_block, &fake_window, 1000, 600);
    try testing.expectEqual(@as(usize, 1), queued(core, own_block));
    try testing.expectEqual(@as(usize, 1), queued(core, resize));

    // A touch on the pane takes the terminal, as before.
    core.command("{\"op\":\"touch\",\"block\":\"" ++ term_block ++ "\"}");
    try testing.expectEqual(@as(usize, 2), queued(core, own_block));
    try testing.expectEqual(@as(usize, 0), recorder.errors.items.len);
}

test "Core: a pane back from under the app's own sidebar or screen takes nothing and asks only for a changed size" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    const core = try withTerminal(&recorder, other);
    defer core.destroy();

    core.paneSurface(term_block, &fake_window, 1000, 600);
    try answerTake(core, me);
    try testing.expectEqual(@as(usize, 1), queued(core, own_block));
    try testing.expectEqual(@as(usize, 1), queued(core, resize));

    // Another device takes the terminal. The compact sidebar opens and closes: the pane's
    // view goes and a new one comes, with the size the pane had.
    try ownerChanged(core, other);
    core.paneSuspend(term_block);
    core.paneResume(term_block, &fake_window, 1000, 600);
    try testing.expectEqual(@as(usize, 1), queued(core, own_block));
    try testing.expectEqual(@as(usize, 1), queued(core, resize));

    // Settings opens and the window is rotated before it closes: the new size is asked for,
    // and the terminal stays the other device's.
    core.paneSuspend(term_block);
    core.paneResume(term_block, &fake_window, 1000, 300);
    try testing.expectEqual(@as(usize, 1), queued(core, own_block));
    try testing.expectEqual(@as(usize, 2), queued(core, resize));

    // A key typed into the pane takes the terminal, as before.
    core.command("{\"op\":\"sendText\",\"block\":\"" ++ term_block ++ "\",\"text\":\"x\"}");
    try testing.expectEqual(@as(usize, 2), queued(core, own_block));
    try testing.expectEqual(@as(usize, 2), queued(core, resize));
    try testing.expectEqual(@as(usize, 0), recorder.errors.items.len);
}

test "Core: a setting asks for the shown pane and not for the preview beside it" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    const core = try withTwoTerminals(&recorder);
    defer core.destroy();

    // Both panes are shown, then the second one becomes a preview (its tab leaves the
    // workspace for a card).
    core.paneSurface(term_block, &fake_window, 1000, 600);
    core.paneSurface(other_block, &fake_window, 1000, 600);
    try testing.expectEqual(@as(usize, 1), queuedFor(core, resize, term_block));
    try testing.expectEqual(@as(usize, 1), queuedFor(core, resize, other_block));
    core.panePreview(other_block, &fake_window, 1000, 600);
    try testing.expectEqual(@as(usize, 2), queued(core, resize));

    core.command("{\"op\":\"setting\",\"key\":\"fontSize\",\"value\":26}");
    try testing.expectEqual(@as(usize, 3), queued(core, resize));
    try testing.expectEqual(@as(usize, 2), queuedFor(core, resize, term_block));
    try expectLastResize(core, "\"columns\":62,\"rows\":18,\"cell_width_px\":16,\"cell_height_px\":32");
    core.command("{\"op\":\"setting\",\"key\":\"panePaddingPx\",\"value\":20}");
    core.command("{\"op\":\"setting\",\"key\":\"fontLineHeight\",\"value\":1.5}");
    try testing.expectEqual(@as(usize, 4), queuedFor(core, resize, term_block));
    try testing.expectEqual(@as(usize, 1), queuedFor(core, resize, other_block));
    try testing.expectEqual(@as(usize, 0), queued(core, own_block));
    try testing.expectEqual(@as(usize, 0), recorder.errors.items.len);
}

test "Core: on a pane that is only a preview, touch and fit queue nothing and typing queues only the write" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    const core = try withTerminal(&recorder, other);
    defer core.destroy();

    core.panePreview(term_block, &fake_window, 1000, 600);
    const before = queuedApart(core, protocol.methods.dataConnect);
    core.command("{\"op\":\"fit\",\"block\":\"" ++ term_block ++ "\"}");
    core.command("{\"op\":\"touch\",\"block\":\"" ++ term_block ++ "\"}");
    core.paneTouch(term_block, 0, 10, 10);
    core.command("{\"op\":\"setting\",\"key\":\"fontSize\",\"value\":26}");
    try testing.expectEqual(@as(usize, 0), queued(core, own_block));
    try testing.expectEqual(@as(usize, 0), queued(core, resize));
    try testing.expectEqual(@as(usize, 0), queued(core, terminal_write));

    // Typed text reaches the terminal without claiming it: the write and nothing else.
    // (One write per block is in flight, so the key waits for the text's answer.)
    core.command("{\"op\":\"sendText\",\"block\":\"" ++ term_block ++ "\",\"text\":\"ls\"}");
    core.key(term_block, 66, 0, 0, 0); // KEYCODE_ENTER
    try testing.expectEqual(@as(usize, 0), queued(core, own_block));
    try testing.expectEqual(@as(usize, 0), queued(core, resize));
    try testing.expectEqual(@as(usize, 1), queued(core, terminal_write));
    const write = try lastQueued(core, terminal_write);
    defer testing.allocator.free(write);
    try testing.expect(std.mem.find(u8, write, "\"payload\":{\"session_id\":\"session:a\",\"block_id\":\"" ++ term_block ++ "\",\"args\":{\"data\":\"ls\"}}") != null);
    try testing.expectEqual(before + 1, queuedApart(core, protocol.methods.dataConnect));

    // Its first surface as a shown pane is the show: one take, one size.
    core.paneSurface(term_block, &fake_window, 1000, 600);
    try testing.expectEqual(@as(usize, 1), queued(core, own_block));
    try testing.expectEqual(@as(usize, 1), queued(core, resize));
    try expectLastResize(core, "\"columns\":62,\"rows\":18,\"cell_width_px\":16,\"cell_height_px\":32");
    try testing.expectEqual(@as(usize, 0), recorder.errors.items.len);
}

test "Core: a preview surface taken away leaves a pane without a surface, as paneSurface does" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    const core = try withTerminal(&recorder, me);
    defer core.destroy();

    core.paneSurface(term_block, &fake_window, 1000, 600);
    try testing.expectEqual(@as(usize, 1), queued(core, resize));
    // A shown pane loses its surface through panePreview: not shown any more.
    core.panePreview(term_block, null, 0, 0);
    core.paneScale(term_block, 2, false);
    core.command("{\"op\":\"fit\",\"block\":\"" ++ term_block ++ "\"}");
    try testing.expectEqual(@as(usize, 1), queued(core, resize));
    core.paneSurface(term_block, &fake_window, 1000, 600);
    try testing.expectEqual(@as(usize, 2), queued(core, resize));

    // A preview loses its surface: the pane is not a preview any more, so a touch for it
    // takes the block as for any pane without a surface.
    core.panePreview(term_block, &fake_window, 1000, 600);
    try ownerChanged(core, other);
    core.command("{\"op\":\"touch\",\"block\":\"" ++ term_block ++ "\"}");
    try testing.expectEqual(@as(usize, 0), queued(core, own_block));
    core.panePreview(term_block, null, 0, 0);
    core.command("{\"op\":\"touch\",\"block\":\"" ++ term_block ++ "\"}");
    try testing.expectEqual(@as(usize, 1), queued(core, own_block));
    try testing.expectEqual(@as(usize, 2), queued(core, resize));
    try testing.expectEqual(@as(usize, 0), recorder.errors.items.len);
}

// --------------------------------------------- sessions, tabs and their intents
//
// The core is attached to the selected session and follows the view of every other one.
// These tests answer its requests by hand; blocks are terminals only where a test needs
// what a terminal reports (a terminal of the selected session gets a pane and a stream).

const Tab = struct { window: []const u8, label: []const u8 = "w", blocks: []const []const u8, focused: ?[]const u8 = null };

/// A `session.view` value: each tab holds its blocks in one tiled layer.
fn tabsView(a: std.mem.Allocator, session: []const u8, revision: i64, active: ?[]const u8, tabs: []const Tab, terminal: bool) ![]u8 {
    var out: std.ArrayList(u8) = .empty;
    try out.print(a, "{{\"session_id\":\"{s}\",\"label\":\"x\",\"revision\":{d}", .{ session, revision });
    if (active) |w| try out.print(a, ",\"active_window_id\":\"{s}\"", .{w});
    try out.appendSlice(a, ",\"windows\":[");
    for (tabs, 0..) |t, i| {
        if (i > 0) try out.append(a, ',');
        try out.print(a, "{{\"window_id\":\"{s}\",\"label\":\"{s}\"", .{ t.window, t.label });
        if (t.focused) |f| try out.print(a, ",\"focused_block_id\":\"{s}\"", .{f});
        try out.appendSlice(a, ",\"layers\":[{\"layer_id\":\"layer:l\",\"kind\":\"tiled\",\"bounds\":[0,0,1,1],\"layout\":null,\"blocks\":[");
        for (t.blocks, 0..) |b, j| {
            if (j > 0) try out.append(a, ',');
            try out.print(a, "{{\"block_id\":\"{s}\",\"label\":\"shell\"{s}}}", .{ b, if (terminal) ",\"creator_name\":\"com.superlogical.terminal\"" else "" });
        }
        try out.appendSlice(a, "]}]}");
    }
    try out.appendSlice(a, "]}");
    return out.toOwnedSlice(a);
}

/// Requests queued since the link came up.
fn sent(core: *Core) usize {
    core.lock.lock();
    defer core.lock.unlock();
    return core.outbox.items.len;
}

/// Id of the newest queued request for `method` whose text holds `needle`.
fn requestId(core: *Core, method: []const u8, needle: []const u8) !u64 {
    core.lock.lock();
    defer core.lock.unlock();
    var buf: [96]u8 = undefined;
    const wanted = try std.fmt.bufPrint(&buf, "\"method\":\"{s}\"", .{method});
    var i = core.outbox.items.len;
    while (i > 0) {
        i -= 1;
        const text = core.outbox.items[i];
        if (std.mem.find(u8, text, wanted) == null or std.mem.find(u8, text, needle) == null) continue;
        const start = (std.mem.find(u8, text, "\"id\":\"") orelse continue) + 6;
        const end = std.mem.findScalarPos(u8, text, start, '"') orelse continue;
        return std.fmt.parseUnsigned(u64, text[start..end], 10);
    }
    return error.NoSuchRequest;
}

fn answer(core: *Core, id: u64, payload: []const u8) !void {
    try receiveFmt(core, "{{\"type\":\"response\",\"id\":\"{d}\",\"payload\":{s}}}", .{ id, payload });
}

fn answerError(core: *Core, id: u64, code: []const u8) !void {
    try receiveFmt(core, "{{\"type\":\"response\",\"id\":\"{d}\",\"error\":{{\"code\":\"{s}\",\"message\":\"no\",\"status\":404}}}}", .{ id, code });
}

/// An event of `session`, as the server wraps it.
fn sessionEvent(core: *Core, session: []const u8, kind: []const u8, payload: []const u8) !void {
    try receiveFmt(core,
        \\{{"type":"event","seq":50,"payload":{{"type":"session_event","payload":{{"session_id":"{s}",
        \\"event":{{"type":"{s}","payload":{s}}}}}}}}}
    , .{ session, kind, payload });
}

fn blockEvent(core: *Core, session: []const u8, block: []const u8, name: []const u8, payload: []const u8) !void {
    var buf: [512]u8 = undefined;
    const text = try std.fmt.bufPrint(&buf, "{{\"block_id\":\"{s}\",\"creator_name\":\"com.superlogical.terminal\",\"name\":\"{s}\",\"payload\":{s}}}", .{ block, name, payload });
    try sessionEvent(core, session, "block_event", text);
}

/// The newest queued request for `method` is exactly this one.
fn expectRequest(core: *Core, comptime method: []const u8, comptime payload: []const u8) !void {
    const text = try lastQueued(core, method);
    defer testing.allocator.free(text);
    const want = "\"method\":\"" ++ method ++ "\",\"payload\":" ++ payload ++ "}";
    if (!std.mem.endsWith(u8, text, want)) {
        std.debug.print("request\n  {s}\ndoes not end with\n  {s}\n", .{ text, want });
        return error.TestExpectedEqual;
    }
}

/// The last requests queued are for these methods, in this order.
fn expectTail(core: *Core, methods: []const []const u8) !void {
    core.lock.lock();
    defer core.lock.unlock();
    const items = core.outbox.items;
    try testing.expect(items.len >= methods.len);
    for (methods, items[items.len - methods.len ..]) |method, text| {
        var buf: [96]u8 = undefined;
        const wanted = try std.fmt.bufPrint(&buf, "\"method\":\"{s}\"", .{method});
        if (std.mem.find(u8, text, wanted) == null) {
            std.debug.print("expected {s}, queued {s}\n", .{ method, text });
            return error.TestExpectedEqual;
        }
    }
}

/// The `tabs` of `session` in the state published last; none when the state has no
/// `tabs` for it (see `knowsTabs`).
fn tabsOf(recorder: *Recorder, a: std.mem.Allocator, session: []const u8) ![]const std.json.Value {
    for (model.items(try recorder.lastValue(a, "sessions"))) |s| {
        if (std.mem.eql(u8, model.str(model.get(s, "id")) orelse "", session)) return model.items(model.get(s, "tabs"));
    }
    return error.NoSuchSession;
}

/// Whether the state published last has `tabs` for `session`: left out while its windows
/// are not known, a list (also an empty one) once they are.
fn knowsTabs(recorder: *Recorder, a: std.mem.Allocator, session: []const u8) !bool {
    for (model.items(try recorder.lastValue(a, "sessions"))) |s| {
        if (!std.mem.eql(u8, model.str(model.get(s, "id")) orelse "", session)) continue;
        const tabs = model.get(s, "tabs") orelse return false;
        try testing.expect(tabs == .array);
        return true;
    }
    return error.NoSuchSession;
}

fn field(v: std.json.Value, name: []const u8) []const u8 {
    return model.str(model.get(v, name)) orelse "<none>";
}

const view_method = protocol.methods.sessionView;
const process_method = protocol.methods.terminalProcess;

test "Core: a selected terminal's process supplies the same tab and window icon hint" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    const a = arena.allocator();
    const core = try withTerminal(&recorder, me);
    defer core.destroy();

    const tabs = try tabsOf(&recorder, a, "session:a");
    try testing.expect(model.get(tabs[0], "icon") == null);
    try answer(core, try requestId(core, process_method, "\"" ++ term_block ++ "\""),
        \\{"last_exit":null,"child":{"name":"bash","path":"/usr/bin/bash"},
        \\"foreground":{"name":"nvim","path":"/usr/bin/nvim","cwd":"/fixture/home"},"home":"/fixture/home"}
    );

    const tab = (try tabsOf(&recorder, a, "session:a"))[0];
    const window = (try recorder.lastValue(a, "windows")).array.items[0];
    try testing.expectEqualStrings("nvim", field(tab, "program"));
    for ([_]std.json.Value{ tab, window }) |item| {
        try testing.expectEqualStrings("nvim", field(item, "icon"));
        try testing.expectEqualStrings("w", field(item, "label"));
    }
}

/// `bootstrapped`, with the attach of `session:a` answered (one tab, `window:a1`, whose
/// block `block:a1` is no terminal) and the view of `session:b` answered: three tabs of
/// terminals, `window:b2` active with two panes and the focus on `block:b3`.
fn following(recorder: *Recorder, a: std.mem.Allocator) !*Core {
    const core = try bootstrapped(recorder);
    errdefer core.destroy();
    const attach_id = (try pendingId(core, .attach)) orelse return error.NoAttachRequest;
    const own = try tabsView(a, "session:a", 1, "window:a1", &.{.{ .window = "window:a1", .blocks = &.{"block:a1"}, .focused = "block:a1" }}, false);
    try receiveFmt(core, "{{\"type\":\"response\",\"id\":\"{d}\",\"payload\":{{\"client_id\":\"client:me\",\"view\":{s}}}}}", .{ attach_id, own });
    try answer(core, try requestId(core, view_method, "\"session:b\""), try tabsView(a, "session:b", 7, "window:b2", &.{
        .{ .window = "window:b1", .blocks = &.{"block:b1"} },
        .{ .window = "window:b2", .label = "editor", .blocks = &.{ "block:b2", "block:b3" }, .focused = "block:b3" },
        .{ .window = "window:b3", .blocks = &.{"block:b4"} },
    }, true));
    return core;
}

fn counts(core: *Core) struct { follows: usize, leads: usize, panes: usize } {
    core.lock.lock();
    defer core.lock.unlock();
    return .{ .follows = core.follows.count(), .leads = core.leads.count(), .panes = core.entries.count() };
}

/// 2026-11-20T10:00:00Z in epoch milliseconds.
const at_1000: i64 = 1795168800_000;

fn report(a: std.mem.Allocator, records: []const u8) !core_mod.Report {
    const text = try std.fmt.allocPrint(a, "{{\"records\":[{s}]}}", .{records});
    return core_mod.worstActivity(try std.json.parseFromSliceLeaky(std.json.Value, a, text, .{}));
}

test "worstActivity: the most urgent state, since the earliest record that carries it" {
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    const a = arena.allocator();
    const none: ?i64 = null;

    // The time is the one of the record that decides the state, not the newest or the
    // oldest of all: the program that waits is what the row times.
    var r = try report(a,
        \\{"id":"a","state":"working","updated_at":"2026-11-20T09:00:00Z"},
        \\{"id":"b","state":"blocked","updated_at":"2026-11-20T10:03:00Z"},
        \\{"id":"c","state":"done","updated_at":"2026-11-20T10:05:00Z"}
    );
    try testing.expectEqual(model.Activity.blocked, r.activity);
    try testing.expectEqual(@as(?i64, at_1000 + 180_000), r.since);
    // The same with the urgent record first.
    r = try report(a,
        \\{"state":"blocked","updated_at":"2026-11-20T10:03:00Z"},
        \\{"state":"working","updated_at":"2026-11-20T09:00:00Z"}
    );
    try testing.expectEqual(@as(?i64, at_1000 + 180_000), r.since);

    // Several records in that state: the earliest, in whatever order and on whatever
    // clock they are written (19:05+09:00 is 10:05Z).
    r = try report(a,
        \\{"id":"a","state":"working","updated_at":"2026-11-20T19:05:00+09:00"},
        \\{"id":"b","state":"working","updated_at":"2026-11-20T10:00:00.250Z"},
        \\{"id":"c","state":"working","updated_at":"2026-11-20T10:03:00Z"}
    );
    try testing.expectEqual(model.Activity.working, r.activity);
    try testing.expectEqual(@as(?i64, at_1000 + 250), r.since);
    // One of them without a time that can be read: the others still say since when.
    r = try report(a,
        \\{"id":"a","state":"working"},
        \\{"id":"b","state":"working","updated_at":"2026-11-20T10:03:00Z"},
        \\{"id":"c","state":"working","updated_at":"yesterday"}
    );
    try testing.expectEqual(@as(?i64, at_1000 + 180_000), r.since);

    // The urgent record has no time: none, rather than the time of a lesser record.
    r = try report(a,
        \\{"state":"working","updated_at":"2026-11-20T10:00:00Z"},
        \\{"state":"error","updated_at":"soon"}
    );
    try testing.expectEqual(model.Activity.@"error", r.activity);
    try testing.expectEqual(none, r.since);
    r = try report(a, "{\"state\":\"done\",\"updated_at\":12}");
    try testing.expectEqual(model.Activity.done, r.activity);
    try testing.expectEqual(none, r.since);

    // Idle, an unknown state and no record at all time nothing.
    r = try report(a,
        \\{"state":"idle","updated_at":"2026-11-20T10:00:00Z"},
        \\{"state":"paused","updated_at":"2026-11-20T10:00:00Z"}
    );
    try testing.expectEqual(model.Activity.idle, r.activity);
    try testing.expectEqual(none, r.since);
    try testing.expectEqual(core_mod.Report{}, try report(a, ""));
    try testing.expectEqual(core_mod.Report{}, core_mod.worstActivity(.null));
}

test "Core: selected and followed tab metadata from the Rex terminal methods" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    const a = arena.allocator();
    const core = try withTerminal(&recorder, me);
    defer core.destroy();
    try answer(core, try requestId(core, view_method, "\"session:b\""), try tabsView(a, "session:b", 3, "window:b1", &.{
        .{ .window = "window:b1", .blocks = &.{"block:b1"}, .focused = "block:b1" },
    }, true));

    // An example Rex payload with only the fields used here.
    // Its version-named process is deliberately indistinguishable from the control.
    for ([_][]const u8{ "session:a", "session:b" }, [_][]const u8{ term_block, "block:b1" }) |session, block| {
        const target = try std.fmt.allocPrint(a, "\"{s}\"", .{block});
        try answer(core, try requestId(core, process_method, target),
            \\{"foreground":{"name":"2.1.329","path":"/fixture/bin/2.1.329","invoked_path":"/fixture/bin/2.1.329","argv0":"/fixture/bin/2.1.329"}}
        );
        try blockEvent(core, session, block, "title_changed", "{\"title\":\"Example Claude terminal\"}");
        // A slower initial title query must not undo the OSC event.
        try answer(core, try requestId(core, protocol.methods.terminalTitle, target), "{\"title\":\"stale\"}");
        try answer(core, try requestId(core, protocol.methods.terminalProgramStatus, target),
            \\{"records":[{"id":"worker","state":"working","app":"claude-code","effective_app":"claude-code","title":"Example Claude thread · Deployment check"}]}
        );
        var tab = (try tabsOf(&recorder, a, session))[0];
        try testing.expectEqualStrings("claude", field(tab, "icon"));
        try testing.expectEqualStrings("2.1.329", field(tab, "program"));
        try testing.expectEqualStrings("Example Claude terminal", field(tab, "title"));
        try testing.expectEqualStrings("Example Claude thread · Deployment check", field(tab, "statusTitle"));

        try blockEvent(core, session, block, "program_status_changed", "{}");
        try answer(core, try requestId(core, protocol.methods.terminalProgramStatus, target),
            \\{"records":[{"id":"worker","state":"working","effective_app":"omp","title":"Renamed thread"}]}
        );
        tab = (try tabsOf(&recorder, a, session))[0];
        try testing.expectEqualStrings("omp", field(tab, "icon"));
        try testing.expectEqualStrings("Renamed thread", field(tab, "statusTitle"));
        for ([_][]const u8{ "Checking patch", "Running tests" }) |summary| {
            try blockEvent(core, session, block, "program_status_changed", "{}");
            const response = try std.fmt.allocPrint(a, "{{\"records\":[{{\"state\":\"working\",\"msg\":\"{s}\"}}]}}", .{summary});
            try answer(core, try requestId(core, protocol.methods.terminalProgramStatus, target), response);
            tab = (try tabsOf(&recorder, a, session))[0];
            try testing.expect(model.get(tab, "statusTitle") == null);
            try testing.expectEqualStrings(summary, field(tab, "statusSummary"));
            if (std.mem.eql(u8, session, "session:a")) {
                const info = model.get(try recorder.lastValue(a, "blocks"), block).?;
                try testing.expectEqualStrings(summary, field(info, "statusSummary"));
            }
        }
        try blockEvent(core, session, block, "program_status_removed", "{}");
        try answer(core, try requestId(core, protocol.methods.terminalProgramStatus, target), "{\"records\":[]}");
        tab = (try tabsOf(&recorder, a, session))[0];
        try testing.expectEqualStrings("2.1.329", field(tab, "icon"));
        try testing.expect(model.get(tab, "statusTitle") == null);
        try testing.expect(model.get(tab, "statusSummary") == null);
        try testing.expect(model.get(tab, "reportedIdle") == null);
    }
}

test "Core: a session switch refreshes the status still pending on the old pane" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    const a = arena.allocator();
    const core = try withTerminal(&recorder, me);
    defer core.destroy();
    const status = protocol.methods.terminalProgramStatus;
    try answer(core, try requestId(core, process_method, term_block), "{\"foreground\":{\"name\":\"codex\"}}");
    try answer(core, try requestId(core, status, term_block), "{\"records\":[{\"state\":\"working\"}]}");
    try blockEvent(core, "session:a", term_block, "program_status_changed", "{}");
    const pane_request = try requestId(core, status, term_block);
    core.selectSession("session:b");
    const tab = (try tabsOf(&recorder, a, "session:a"))[0];
    try testing.expectEqualStrings("codex", field(tab, "program"));
    try testing.expectEqualStrings("working", field(tab, "activity"));
    const lead_request = try requestId(core, status, term_block);
    try testing.expect(lead_request > pane_request);
    const done = "{\"records\":[{\"state\":\"done\"}]}";
    try answer(core, pane_request, done);
    try answer(core, lead_request, done);
    try testing.expectEqualStrings("done", field((try tabsOf(&recorder, a, "session:a"))[0], "activity"));
}

test "Core: a late working response cannot restart the selected tab's completed task" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    const core = try withTerminal(&recorder, me);
    defer core.destroy();
    const method = protocol.methods.terminalProgramStatus;
    const first = try requestId(core, method, term_block);
    try blockEvent(core, "session:a", term_block, "program_status_changed", "{}");
    const second = try requestId(core, method, term_block);
    try testing.expect(second > first);
    try answer(core, second, "{\"records\":[{\"state\":\"done\",\"title\":\"Ready to review\"}]}");
    try answer(core, first, "{\"records\":[{\"state\":\"working\",\"title\":\"Building\"}]}");
    const tab = (try tabsOf(&recorder, arena.allocator(), "session:a"))[0];
    try testing.expectEqualStrings("done", field(tab, "activity"));
    try testing.expectEqualStrings("Ready to review", field(tab, "statusTitle"));
}

test "Core: a dropped selected title change recovers the final title without its spinner" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    const a = arena.allocator();
    const core = try withTerminal(&recorder, me);
    defer core.destroy();
    const method = protocol.methods.terminalTitle;
    const initial = try requestId(core, method, term_block);
    try blockEvent(core, "session:a", term_block, "title_changed", "{\"title\":\"⠋ Build\"}");
    try testing.expectEqualStrings("⠋ Build", field((try tabsOf(&recorder, a, "session:a"))[0], "title"));

    try receiveFmt(core,
        \\{{"type":"event","seq":60,"payload":{{"type":"event_dropped","payload":{{"event_type":"block_event",
        \\"session_id":"session:a","block_id":"{s}","block_event_name":"title_changed","size":70000}}}}}}
    , .{term_block});
    const recovery = try requestId(core, method, term_block);
    try testing.expect(recovery > initial);
    try answer(core, recovery, "{\"title\":\"Build\"}");
    try answer(core, initial, "{\"title\":\"⠋ Build\"}");
    try testing.expectEqualStrings("Build", field((try tabsOf(&recorder, a, "session:a"))[0], "title"));
    const info = model.get(try recorder.lastValue(a, "blocks"), term_block).?;
    try testing.expectEqualStrings("Build", field(info, "title"));
}

test "Core: a session switch during lost title recovery removes the old Codex spinner" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    const a = arena.allocator();
    const core = try withTerminal(&recorder, me);
    defer core.destroy();
    const method = protocol.methods.terminalTitle;
    try answer(core, try requestId(core, process_method, term_block), "{\"foreground\":{\"name\":\"codex\"}}");
    const initial = try requestId(core, method, term_block);
    try blockEvent(core, "session:a", term_block, "title_changed", "{\"title\":\"⠋ Build\"}");
    try testing.expectEqualStrings("⠋ Build", field((try tabsOf(&recorder, a, "session:a"))[0], "title"));

    try receiveFmt(core,
        \\{{"type":"event","seq":60,"payload":{{"type":"event_dropped","payload":{{"event_type":"block_event",
        \\"session_id":"session:a","block_id":"{s}","block_event_name":"title_changed","size":70000}}}}}}
    , .{term_block});
    const recovery = try requestId(core, method, term_block);
    try testing.expect(recovery > initial);
    core.selectSession("session:b");
    const lead_request = try requestId(core, method, term_block);
    try testing.expect(lead_request > recovery);
    try answer(core, lead_request, "{\"title\":\"Build\"}");
    try answer(core, recovery, "{\"title\":\"⠋ Old\"}");
    try testing.expectEqualStrings("Build", field((try tabsOf(&recorder, a, "session:a"))[0], "title"));
}

test "Core: a working cargo child leaves the Codex conversation identity on the tab" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    const a = arena.allocator();
    const core = try withTerminal(&recorder, me);
    defer core.destroy();
    const status = protocol.methods.terminalProgramStatus;
    try blockEvent(core, "session:a", term_block, "title_changed", "{\"title\":\"⠋ Build\"}");

    // Codex can be identified by the root report even while cargo is in the foreground.
    try answer(core, try requestId(core, process_method, term_block), "{\"foreground\":{\"name\":\"cargo\"}}");
    try answer(core, try requestId(core, status, term_block),
        \\{"records":[{"id":"worker","app":"cargo","state":"working","msg":"Compiling"},
        \\{"app":"codex","state":"idle"}]}
    );
    var tab = (try tabsOf(&recorder, a, "session:a"))[0];
    try testing.expectEqualStrings("codex", field(tab, "icon"));
    try testing.expectEqualStrings("working", field(tab, "activity"));
    try testing.expectEqualStrings("Compiling", field(tab, "statusSummary"));

    // The process can also identify Codex when only its child reports a status.
    try blockEvent(core, "session:a", term_block, "process_changed", "{}");
    try answer(core, try requestId(core, process_method, term_block), "{\"foreground\":{\"name\":\"node\",\"argv0\":\"/bin/codex\"}}");
    try blockEvent(core, "session:a", term_block, "program_status_changed", "{}");
    try answer(core, try requestId(core, status, term_block), "{\"records\":[{\"id\":\"worker\",\"app\":\"cargo\",\"state\":\"working\"}]}");
    tab = (try tabsOf(&recorder, a, "session:a"))[0];
    try testing.expectEqualStrings("codex", field(tab, "icon"));
    try testing.expectEqualStrings("working", field(tab, "activity"));
    try testing.expectEqualStrings("⠋ Build", field(tab, "title"));
    try testing.expect(model.get(tab, "statusTitle") == null);
}

test "Core: an explicit empty terminal title supersedes a stale selected or followed view" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    const a = arena.allocator();
    const core = try bootstrapped(&recorder);
    defer core.destroy();
    for ([_][]const u8{ "session:a", "session:b" }, [_][]const u8{ "block:a", "block:b" }, 0..) |session, block, i| {
        const view = try std.fmt.allocPrint(a,
            \\{{"session_id":"{s}","revision":1,"windows":[{{"window_id":"w","layers":[{{"kind":"tiled","blocks":[
            \\{{"block_id":"{s}","creator_name":"com.superlogical.terminal","title":"⠋ Old thread","label":"shell"}}]}}]}}]}}
        , .{ session, block });
        if (i == 0) {
            const id = (try pendingId(core, .attach)) orelse return error.NoAttachRequest;
            try answer(core, id, try std.fmt.allocPrint(a, "{{\"client_id\":\"client:me\",\"view\":{s}}}", .{view}));
        } else {
            try answer(core, try requestId(core, view_method, session), view);
        }
        const before = (try tabsOf(&recorder, a, session))[0];
        try testing.expectEqualStrings("⠋ Old thread", field(before, "title"));
        try blockEvent(core, session, block, "title_changed", "{\"title\":\"\"}");
        try answer(core, try requestId(core, protocol.methods.terminalTitle, block), "{\"title\":\"⠋ Old thread\"}");
        const after = (try tabsOf(&recorder, a, session))[0];
        try testing.expectEqualStrings("shell", field(after, "title"));
        try testing.expect(model.boolean(model.get(after, "titleIsLabel")).?);
        if (i == 0) {
            const info = model.get(try recorder.lastValue(a, "blocks"), block).?;
            try testing.expectEqualStrings("shell", field(info, "title"));
            try testing.expect(model.boolean(model.get(info, "titleIsLabel")).?);
        }
    }
}

test "Core: an updated report time is published even when the activity stays the same" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    const a = arena.allocator();
    const status_method = protocol.methods.terminalProgramStatus;

    // A followed session: the lead of its tab.
    const core = try withTerminal(&recorder, me);
    defer core.destroy();
    try answer(core, try requestId(core, view_method, "\"session:b\""), try tabsView(a, "session:b", 3, "window:b1", &.{
        .{ .window = "window:b1", .blocks = &.{"block:b1"}, .focused = "block:b1" },
    }, true));
    try answer(core, try requestId(core, status_method, "\"block:b1\""),
        \\{"records":[{"id":"","state":"working","updated_at":"2026-11-20T10:00:00Z"}]}
    );
    var tab = (try tabsOf(&recorder, a, "session:b"))[0];
    try testing.expectEqualStrings("working", field(tab, "activity"));
    try testing.expectEqual(@as(?i64, at_1000), model.int(model.get(tab, "since")));

    // The program reports the same state again later, so the row counts from that report.
    try blockEvent(core, "session:b", "block:b1", "program_status_changed", "{\"id\":\"\"}");
    try answer(core, try requestId(core, status_method, "\"block:b1\""),
        \\{"records":[{"id":"","state":"working","updated_at":"2026-11-20T10:03:00Z"}]}
    );
    tab = (try tabsOf(&recorder, a, "session:b"))[0];
    try testing.expectEqual(@as(?i64, at_1000 + 180_000), model.int(model.get(tab, "since")));
    // The same answer once more publishes nothing.
    const published = recorder.states.items.len;
    try blockEvent(core, "session:b", "block:b1", "program_status_changed", "{\"id\":\"\"}");
    try answer(core, try requestId(core, status_method, "\"block:b1\""),
        \\{"records":[{"id":"","state":"working","updated_at":"2026-11-20T10:03:00Z"}]}
    );
    try testing.expectEqual(published, recorder.states.items.len);

    // A status refresh with an invalid timestamp clears the previously displayed time.
    try blockEvent(core, "session:b", "block:b1", "program_status_changed", "{\"id\":\"\"}");
    try answer(core, try requestId(core, status_method, "\"block:b1\""),
        \\{"records":[{"state":"working","updated_at":"unknown"}]}
    );
    tab = (try tabsOf(&recorder, a, "session:b"))[0];
    try testing.expectEqualStrings("working", field(tab, "activity"));
    try testing.expect(model.get(tab, "since") == null);

    // Its record is removed: idle, and no time left over.
    try blockEvent(core, "session:b", "block:b1", "program_status_removed", "{\"id\":\"\"}");
    try answer(core, try requestId(core, status_method, "\"block:b1\""), "{\"records\":[]}");
    tab = (try tabsOf(&recorder, a, "session:b"))[0];
    try testing.expectEqualStrings("idle", field(tab, "activity"));
    try testing.expect(model.get(tab, "since") == null);

    // The selected session: the block of the pane and the tab that it leads agree.
    try answer(core, try requestId(core, status_method, "\"" ++ term_block ++ "\""),
        \\{"records":[{"id":"x","state":"blocked","updated_at":"2026-11-20T19:03:00+09:00"},
        \\{"id":"y","state":"blocked","updated_at":"2026-11-20T10:00:00Z"}]}
    );
    const own = (try recorder.lastValue(a, "blocks")).object.get(term_block).?;
    try testing.expectEqualStrings("blocked", field(own, "activity"));
    try testing.expectEqual(@as(?i64, at_1000), model.int(model.get(own, "since")));
    tab = (try tabsOf(&recorder, a, "session:a"))[0];
    try testing.expectEqualStrings("blocked", field(tab, "activity"));
    try testing.expectEqual(@as(?i64, at_1000), model.int(model.get(tab, "since")));
    try testing.expectEqual(@as(usize, 0), recorder.errors.items.len);
}

test "neighbour: the next session, else the previous, else none" {
    const three = [_]model.Session{ .{ .id = "s:a", .label = "" }, .{ .id = "s:b", .label = "" }, .{ .id = "s:c", .label = "" } };
    try testing.expectEqualStrings("s:b", core_mod.neighbour(&three, "s:a").?);
    try testing.expectEqualStrings("s:c", core_mod.neighbour(&three, "s:b").?);
    try testing.expectEqualStrings("s:b", core_mod.neighbour(&three, "s:c").?);
    try testing.expectEqual(@as(?model.Id, null), core_mod.neighbour(three[0..1], "s:a"));
    try testing.expectEqual(@as(?model.Id, null), core_mod.neighbour(&three, "s:gone"));
    try testing.expectEqual(@as(?model.Id, null), core_mod.neighbour(&.{}, "s:a"));
}

test "Core: the state lists every session's tabs, one session selected and the others followed" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    const a = arena.allocator();

    {
        const fresh = try bootstrapped(&recorder);
        defer fresh.destroy();
        // One attach, for the selected session; one view request, for the other.
        try testing.expectEqual(@as(usize, 1), queued(fresh, "session.attach"));
        try testing.expectEqual(@as(usize, 1), queuedFor(fresh, view_method, "session:b"));
        try testing.expectEqual(@as(usize, 0), queuedFor(fresh, view_method, "session:a"));
        // Until the answers arrive the tabs are not known: the state has none to show.
        try testing.expect(!try knowsTabs(&recorder, a, "session:a"));
        try testing.expect(!try knowsTabs(&recorder, a, "session:b"));
    }

    var second: Recorder = .{};
    defer second.deinit();
    const followed = try following(&second, a);
    defer followed.destroy();

    try testing.expectEqualStrings("session:a", try second.lastString(a, "session"));
    const own = try tabsOf(&second, a, "session:a");
    try testing.expectEqual(@as(usize, 1), own.len);
    try testing.expectEqualStrings("window:a1", field(own[0], "id"));
    try testing.expectEqual(true, model.boolean(model.get(own[0], "active")).?);
    try testing.expectEqualStrings("block:a1", field(own[0], "block"));
    // The top-level fields are the selected session's and agree with its tabs.
    try testing.expectEqualStrings("window:a1", try second.lastString(a, "window"));
    try testing.expectEqualStrings("block:a1", try second.lastString(a, "focused"));
    try testing.expectEqual(@as(usize, 1), model.items(try second.lastValue(a, "windows")).len);
    try testing.expectEqual(@as(usize, 1), (try second.lastValue(a, "blocks")).object.count());

    var tabs = try tabsOf(&second, a, "session:b");
    try testing.expectEqual(@as(usize, 3), tabs.len);
    try testing.expectEqualStrings("window:b1", field(tabs[0], "id"));
    try testing.expectEqualStrings("window:b2", field(tabs[1], "id"));
    try testing.expectEqualStrings("window:b3", field(tabs[2], "id"));
    try testing.expectEqualStrings("editor", field(tabs[1], "label"));
    try testing.expectEqual(@as(i64, 2), model.int(model.get(tabs[1], "panes")).?);
    try testing.expectEqual(false, model.boolean(model.get(tabs[0], "active")).?);
    try testing.expectEqual(true, model.boolean(model.get(tabs[1], "active")).?);
    try testing.expectEqualStrings("block:b1", field(tabs[0], "block"));
    try testing.expectEqualStrings("block:b3", field(tabs[1], "block"));

    // The lead block of each tab is asked about once; the pane beside it is not.
    inline for (.{ process_method, protocol.methods.terminalProgramStatus, protocol.methods.terminalTitle }) |method| {
        try testing.expectEqual(@as(usize, 3), queued(followed, method));
        try testing.expectEqual(@as(usize, 0), queuedFor(followed, method, "block:b2"));
        try testing.expectEqual(@as(usize, 1), queuedFor(followed, method, "block:b3"));
    }
    try answer(followed, try requestId(followed, process_method, "\"block:b3\""),
        \\{"last_exit":null,"child":{"name":"zsh","cwd":"/home/alice"},"foreground":{"name":"vim","cwd":"/home/alice/notes"},"home":"/home/alice"}
    );
    try answer(followed, try requestId(followed, protocol.methods.terminalProgramStatus, "\"block:b3\""), "{\"records\":[{\"state\":\"working\"},{\"id\":\"x\",\"state\":\"blocked\"}]}");
    try answer(followed, try requestId(followed, protocol.methods.terminalTitle, "\"block:b3\""), "{\"title\":\"notes.md\"}");
    tabs = try tabsOf(&second, a, "session:b");
    try testing.expectEqualStrings("vim", field(tabs[1], "program"));
    try testing.expectEqualStrings("~/notes", field(tabs[1], "cwd"));
    try testing.expectEqualStrings("notes.md", field(tabs[1], "title"));
    try testing.expectEqualStrings("blocked", field(tabs[1], "activity"));
    try testing.expectEqual(true, model.boolean(model.get(tabs[1], "attention")).?);
    try testing.expectEqualStrings("", field(tabs[0], "program"));

    // Followed sessions have no pane, and their blocks are not in `blocks`.
    try testing.expectEqual(@as(usize, 0), counts(followed).panes);
    try testing.expectEqual(@as(usize, 3), counts(followed).leads);
    try testing.expectEqual(@as(usize, 1), (try second.lastValue(a, "blocks")).object.count());

    // Another client closes a tab, renames one, moves one and adds one: one event, one
    // view request, and the state follows.
    try sessionEvent(followed, "session:b", "session_view_changed", "{\"revision\":9}");
    try testing.expectEqual(@as(usize, 2), queuedFor(followed, view_method, "session:b"));
    try answer(followed, try requestId(followed, view_method, "\"session:b\""), try tabsView(a, "session:b", 9, "window:b9", &.{
        .{ .window = "window:b3", .label = "logs", .blocks = &.{"block:b4"} },
        .{ .window = "window:b1", .blocks = &.{"block:b1"} },
        .{ .window = "window:b9", .blocks = &.{"block:b9"} },
    }, true));
    tabs = try tabsOf(&second, a, "session:b");
    try testing.expectEqual(@as(usize, 3), tabs.len);
    try testing.expectEqualStrings("window:b3", field(tabs[0], "id"));
    try testing.expectEqualStrings("logs", field(tabs[0], "label"));
    try testing.expectEqualStrings("window:b1", field(tabs[1], "id"));
    try testing.expectEqualStrings("window:b9", field(tabs[2], "id"));
    try testing.expectEqual(true, model.boolean(model.get(tabs[2], "active")).?);
    // The closed tab's lead is forgotten; only the new one is asked about.
    try testing.expectEqual(@as(usize, 3), counts(followed).leads);
    try testing.expectEqual(@as(usize, 4), queued(followed, process_method));
    try testing.expectEqual(@as(usize, 1), queuedFor(followed, process_method, "block:b9"));
    try testing.expectEqualStrings("session:a", try second.lastString(a, "session"));
    try testing.expectEqual(@as(usize, 1), queued(followed, "session.attach"));
    try testing.expectEqual(@as(usize, 0), second.errors.items.len);
}

/// A core that took a bootstrap answer listing `session:<name>` for each name, with
/// `selected_session` null: it picks the first itself.
fn bootstrappedList(recorder: *Recorder, a: std.mem.Allocator, names: []const []const u8) !*Core {
    const core = try Core.create(testing.allocator, recorder.host(), .{}, 1);
    errdefer core.destroy();
    var list: std.ArrayList(u8) = .empty;
    for (names, 0..) |name, i| {
        if (i > 0) try list.append(a, ',');
        try list.print(a, "{{\"session_id\":\"session:{s}\",\"label\":\"{s}\"}}", .{ name, name });
    }
    const id = registerBootstrap(core);
    try receiveFmt(core, "{{\"type\":\"response\",\"id\":\"{d}\",\"payload\":{{\"attach_all\":{{\"sessions\":[{s}]}},\"selected_session\":null,\"data_tickets\":[]}}}}", .{ id, list.items });
    return core;
}

test "Core: confirmed last pane closes only the named block and keeps its scope" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    const a = arena.allocator();
    const core = try following(&recorder, a);
    defer core.destroy();

    const last_pane =
        \\{"op":"closeLastPane","session":"session:a","window":"window:a1","block":"block:a1"}
    ;
    core.command(last_pane);
    try expectRequest(core, protocol.methods.closeBlock,
        \\{"session_id":"session:a","block_id":"block:a1","keep_last":false}
    );

    core.command(
        \\{"op":"close","block":"block:a1"}
    );
    try expectRequest(core, protocol.methods.closeBlock,
        \\{"session_id":"session:a","block_id":"block:a1","keep_last":true}
    );

    const before = queued(core, protocol.methods.closeBlock);
    core.command(
        \\{"op":"closeLastPane","session":"session:a","window":"window:a1","block":"block:gone"}
    );
    core.command(
        \\{"op":"closeLastPane","session":"session:a","window":"window:gone","block":"block:a1"}
    );
    core.command(
        \\{"op":"closeLastPane","session":"session:b","window":"window:b1","block":"block:b1"}
    );
    try sessionEvent(core, "session:a", "session_view_changed",
        \\{"revision":2}
    );
    const updated = try tabsView(a, "session:a", 2, "window:a1", &.{
        .{ .window = "window:a1", .blocks = &.{ "block:a1", "block:new" } },
    }, false);
    try answer(core, (try pendingId(core, .view)) orelse return error.NoViewRequest, updated);
    core.command(last_pane);
    try testing.expectEqual(before, queued(core, protocol.methods.closeBlock));
    try testing.expectEqual(@as(usize, 0), queued(core, protocol.methods.closeWindow));
}

test "Core: released border sends its two subtree anchors and local ratio" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    const core = try following(&recorder, arena.allocator());
    defer core.destroy();
    core.command(
        \\{"op":"resizePane","before":"block:a1","after":"block:a2","direction":"vertical","ratio":0.625}
    );
    const text = try lastQueued(core, protocol.methods.resizePane);
    defer testing.allocator.free(text);
    const envelope = try std.json.parseFromSliceLeaky(std.json.Value, arena.allocator(), text, .{});
    const payload = model.get(envelope, "payload").?;
    try testing.expectEqualStrings("session:a", field(payload, "session_id"));
    try testing.expectEqualStrings("block:a1", field(payload, "before_block_id"));
    try testing.expectEqualStrings("block:a2", field(payload, "after_block_id"));
    try testing.expectEqualStrings("vertical", field(payload, "direction"));
    try testing.expectEqual(@as(f64, 0.625), model.num(model.get(payload, "ratio")).?);
}

test "Core: the tab and session intents send these requests" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    const a = arena.allocator();
    const core = try following(&recorder, a);
    defer core.destroy();

    // Closing and renaming name their session: nothing is selected or attached for them.
    core.command("{\"op\":\"closeWindow\",\"session\":\"session:b\",\"window\":\"window:b1\"}");
    try expectRequest(core, "session.close_window", "{\"session_id\":\"session:b\",\"window_id\":\"window:b1\"}");
    core.command("{\"op\":\"closeSession\",\"session\":\"session:b\"}");
    try expectRequest(core, "session.destroy", "{\"session_id\":\"session:b\"}");
    core.command("{\"op\":\"renameSession\",\"session\":\"session:b\",\"label\":\"work\"}");
    try expectRequest(core, "session.set_label", "{\"session_id\":\"session:b\",\"label\":\"work\"}");
    core.command("{\"op\":\"renameWindow\",\"session\":\"session:b\",\"window\":\"window:b1\",\"label\":\"logs\"}");
    try expectRequest(core, "session.set_window_label", "{\"session_id\":\"session:b\",\"window_id\":\"window:b1\",\"label\":\"logs\"}");
    // An empty label restores the server's own name.
    core.command("{\"op\":\"renameWindow\",\"session\":\"session:b\",\"window\":\"window:b1\",\"label\":\"\"}");
    try expectRequest(core, "session.set_window_label", "{\"session_id\":\"session:b\",\"window_id\":\"window:b1\",\"label\":\"\"}");
    try testing.expectEqualStrings("session:a", try recorder.lastString(a, "session"));
    try testing.expectEqual(@as(usize, 1), queued(core, "session.attach"));
    try testing.expectEqual(@as(usize, 0), queued(core, "session.detach"));

    // Without `session`, and with the selected one, the tab ops act where they did before.
    const new_tab_a = "{\"session_id\":\"session:a\",\"layout\":{\"block\":{\"flavor\":\"com.superlogical.terminal.shell\"}},\"focus\":true}";
    core.command("{\"op\":\"newWindow\"}");
    try expectRequest(core, "session.new_window", new_tab_a);
    core.command("{\"op\":\"newWindow\",\"session\":\"session:a\"}");
    try expectRequest(core, "session.new_window", new_tab_a);
    try testing.expectEqual(@as(usize, 2), queued(core, "session.new_window"));
    core.command("{\"op\":\"selectWindow\",\"window\":\"window:a1\"}");
    try expectRequest(core, "session.focus_window", "{\"session_id\":\"session:a\",\"window_id\":\"window:a1\"}");
    core.command("{\"op\":\"selectWindow\",\"session\":\"session:a\",\"window\":\"window:a1\"}");
    try testing.expectEqual(@as(usize, 2), queued(core, "session.focus_window"));
    try testing.expectEqual(@as(usize, 1), queued(core, "session.attach"));
    try testing.expectEqual(@as(usize, 0), queued(core, "session.detach"));

    // A new tab in another session: that session is selected first.
    core.command("{\"op\":\"newWindow\",\"session\":\"session:b\"}");
    try expectTail(core, &.{ "session.detach", "session.attach", "session.new_window" });
    try expectRequest(core, "session.detach", "{\"session_id\":\"session:a\"}");
    try expectRequest(core, "session.attach", "{\"session_id\":\"session:b\"}");
    try expectRequest(core, "session.new_window", "{\"session_id\":\"session:b\",\"layout\":{\"block\":{\"flavor\":\"com.superlogical.terminal.shell\"}},\"focus\":true}");
    try testing.expectEqualStrings("session:b", try recorder.lastString(a, "session"));
    try testing.expectEqual(@as(usize, 0), recorder.errors.items.len);
}

test "Core: the session intents without a connection report not_connected" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    const core = try Core.create(testing.allocator, recorder.host(), .{}, 1);
    defer core.destroy();
    const intents = [_][]const u8{
        "{\"op\":\"closeWindow\",\"session\":\"session:b\",\"window\":\"window:b1\"}",
        "{\"op\":\"closeSession\",\"session\":\"session:b\"}",
        "{\"op\":\"renameSession\",\"session\":\"session:b\",\"label\":\"work\"}",
        "{\"op\":\"renameWindow\",\"session\":\"session:b\",\"window\":\"window:b1\",\"label\":\"logs\"}",
        "{\"op\":\"newWindow\",\"session\":\"session:b\"}",
        "{\"op\":\"selectWindow\",\"session\":\"session:b\",\"window\":\"window:b1\"}",
    };
    for (intents) |text| core.command(text);
    try testing.expectEqual(intents.len, recorder.errors.items.len);
    for (recorder.errors.items) |code| try testing.expectEqualStrings("not_connected", code);
    // Nothing was selected for a command that could not be sent.
    try testing.expectEqual(@as(usize, 0), recorder.states.items.len);
}

test "Core: selectWindow in another session ends on that tab, whichever answer comes first" {
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    const a = arena.allocator();
    // The attach answer: the view read before the focus ran (tab b2 shown), or after it.
    const before = try tabsView(a, "session:b", 7, "window:b2", &.{
        .{ .window = "window:b1", .blocks = &.{"block:b1"} },
        .{ .window = "window:b2", .blocks = &.{ "block:b2", "block:b3" }, .focused = "block:b3" },
        .{ .window = "window:b3", .blocks = &.{"block:b4"} },
    }, false);
    const after = try tabsView(a, "session:b", 8, "window:b3", &.{
        .{ .window = "window:b1", .blocks = &.{"block:b1"} },
        .{ .window = "window:b2", .blocks = &.{ "block:b2", "block:b3" }, .focused = "block:b3" },
        .{ .window = "window:b3", .blocks = &.{"block:b4"} },
    }, false);

    for ([_]bool{ true, false }) |attach_first| {
        var recorder: Recorder = .{};
        defer recorder.deinit();
        const core = try following(&recorder, a);
        defer core.destroy();

        core.command("{\"op\":\"selectWindow\",\"session\":\"session:b\",\"window\":\"window:b3\"}");
        try expectTail(core, &.{ "session.detach", "session.attach", "session.focus_window" });
        try expectRequest(core, "session.attach", "{\"session_id\":\"session:b\"}");
        try expectRequest(core, "session.focus_window", "{\"session_id\":\"session:b\",\"window_id\":\"window:b3\"}");
        try testing.expectEqualStrings("session:b", try recorder.lastString(a, "session"));
        // Both sessions keep their rows meanwhile, and the one left costs no request.
        try testing.expectEqual(@as(usize, 1), (try tabsOf(&recorder, a, "session:a")).len);
        try testing.expectEqual(@as(usize, 3), (try tabsOf(&recorder, a, "session:b")).len);
        try testing.expectEqual(@as(usize, 0), queuedFor(core, view_method, "session:a"));

        const attach_id = try requestId(core, "session.attach", "\"session:b\"");
        const focus_id = try requestId(core, "session.focus_window", "\"window:b3\"");
        if (attach_first) {
            // The server read the view, then ran the focus: its event says so.
            try receiveFmt(core, "{{\"type\":\"response\",\"id\":\"{d}\",\"payload\":{{\"client_id\":\"client:me\",\"view\":{s}}}}}", .{ attach_id, before });
            try testing.expectEqualStrings("window:b2", try recorder.lastString(a, "window"));
            try sessionEvent(core, "session:b", "active_window_changed", "{\"window_id\":\"window:b3\"}");
            try sessionEvent(core, "session:b", "session_view_changed", "{\"revision\":8}");
            try answer(core, focus_id, "{\"revision\":8}");
            try testing.expectEqual(@as(usize, 2), queuedFor(core, view_method, "session:b"));
            try answer(core, (try pendingId(core, .view)) orelse return error.NoViewRequest, after);
        } else {
            // The focus ran first; its event overtakes the attach answer.
            try sessionEvent(core, "session:b", "session_view_changed", "{\"revision\":8}");
            try answer(core, focus_id, "{\"revision\":8}");
            try receiveFmt(core, "{{\"type\":\"response\",\"id\":\"{d}\",\"payload\":{{\"client_id\":\"client:me\",\"view\":{s}}}}}", .{ attach_id, after });
            // The answer holds the change already: nothing more is asked.
            try testing.expectEqual(@as(usize, 1), queuedFor(core, view_method, "session:b"));
        }
        try testing.expectEqualStrings("session:b", try recorder.lastString(a, "session"));
        try testing.expectEqualStrings("window:b3", try recorder.lastString(a, "window"));
        const tabs = try tabsOf(&recorder, a, "session:b");
        try testing.expectEqual(true, model.boolean(model.get(tabs[2], "active")).?);
        try testing.expectEqual(false, model.boolean(model.get(tabs[1], "active")).?);
        // The session shown before is followed from the view its panes had.
        try testing.expectEqual(@as(usize, 1), (try tabsOf(&recorder, a, "session:a")).len);
        try testing.expectEqual(@as(usize, 0), queuedFor(core, view_method, "session:a"));
        try testing.expectEqual(@as(usize, 1), counts(core).follows);
        try testing.expectEqual(@as(usize, 0), recorder.errors.items.len);
    }
}

test "Core: destroying the selected session selects the next one, else the previous, else none" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    const a = arena.allocator();
    const core = try bootstrappedList(&recorder, a, &.{ "a", "b", "c", "d" });
    defer core.destroy();
    try testing.expectEqualStrings("session:a", try recorder.lastString(a, "session"));

    // A session that is not selected goes away: the selection stays.
    core.receive("{\"type\":\"event\",\"seq\":5,\"payload\":{\"type\":\"session_destroyed\",\"payload\":{\"session_id\":\"session:d\"}}}");
    try testing.expectEqualStrings("session:a", try recorder.lastString(a, "session"));
    try testing.expectEqual(@as(usize, 3), model.items(try recorder.lastValue(a, "sessions")).len);
    try testing.expectEqual(@as(usize, 1), queued(core, "session.attach"));

    // This client closes the selected session `b`: the next one, `c`, is selected.
    core.selectSession("session:b");
    core.command("{\"op\":\"closeSession\",\"session\":\"session:b\"}");
    try expectRequest(core, "session.destroy", "{\"session_id\":\"session:b\"}");
    try testing.expectEqualStrings("session:b", try recorder.lastString(a, "session"));
    try sessionEvent(core, "session:b", "session_destroyed", "{\"session_id\":\"session:b\"}");
    try testing.expectEqualStrings("session:c", try recorder.lastString(a, "session"));
    try expectRequest(core, "session.attach", "{\"session_id\":\"session:c\"}");
    try testing.expectEqual(@as(usize, 2), model.items(try recorder.lastValue(a, "sessions")).len);
    try answer(core, (try pendingId(core, .close)) orelse return error.NoCloseRequest, "{}");

    // Another client destroys `c`, the last in the list: the previous one, `a`.
    core.receive("{\"type\":\"event\",\"seq\":9,\"payload\":{\"type\":\"session_destroyed\",\"payload\":{\"session_id\":\"session:c\"}}}");
    try testing.expectEqualStrings("session:a", try recorder.lastString(a, "session"));
    try expectRequest(core, "session.attach", "{\"session_id\":\"session:a\"}");

    // The only session left goes: none is selected, and nothing is attached for it.
    const attaches = queued(core, "session.attach");
    try sessionEvent(core, "session:a", "session_destroyed", "{\"session_id\":\"session:a\"}");
    try testing.expect(try recorder.lastValue(a, "session") == .null);
    try testing.expectEqual(@as(usize, 0), model.items(try recorder.lastValue(a, "sessions")).len);
    try testing.expectEqual(attaches, queued(core, "session.attach"));
    try testing.expectEqual(@as(usize, 0), counts(core).follows);
    try testing.expectEqual(@as(usize, 0), recorder.errors.items.len);
}

test "Core: detach stops everything for the core and selectSession brings it back" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    const a = arena.allocator();
    const core = try following(&recorder, a);
    defer core.destroy();
    try testing.expectEqual(@as(usize, 1), counts(core).follows);
    try testing.expectEqual(@as(usize, 3), counts(core).leads);

    core.command("{\"op\":\"detach\"}");
    try expectRequest(core, "session.detach", "{\"session_id\":\"session:a\"}");
    try testing.expect(try recorder.lastValue(a, "session") == .null);
    try testing.expect(try recorder.lastValue(a, "window") == .null);
    try testing.expectEqual(@as(usize, 0), model.items(try recorder.lastValue(a, "windows")).len);
    try testing.expectEqual(@as(usize, 0), (try recorder.lastValue(a, "blocks")).object.count());
    // The sessions stay listed; what their tabs are is not known to a parked core.
    try testing.expectEqual(@as(usize, 2), model.items(try recorder.lastValue(a, "sessions")).len);
    try testing.expect(!try knowsTabs(&recorder, a, "session:a"));
    try testing.expect(!try knowsTabs(&recorder, a, "session:b"));
    const none = counts(core);
    try testing.expectEqual(@as(usize, 0), none.follows + none.leads + none.panes);

    // Nothing the server says makes a parked core ask about a session's windows.
    const quiet = sent(core);
    try sessionEvent(core, "session:b", "session_view_changed", "{\"revision\":20}");
    try sessionEvent(core, "session:a", "session_view_changed", "{\"revision\":20}");
    try blockEvent(core, "session:b", "block:b3", "process_changed", "{\"generation\":4}");
    try sessionEvent(core, "session:b", "window_label_changed", "{\"window_id\":\"window:b1\",\"label\":\"x\"}");
    try testing.expectEqual(quiet, sent(core));
    core.receive("{\"type\":\"event\",\"seq\":60,\"payload\":{\"type\":\"session_created\",\"payload\":{\"session_id\":\"session:c\"}}}");
    try answer(core, (try pendingId(core, .list)) orelse return error.NoListRequest,
        \\{"sessions":[{"session_id":"session:a","label":"a"},{"session_id":"session:b","label":"b"},{"session_id":"session:c","label":"c"}]}
    );
    try testing.expectEqual(quiet + 1, sent(core));
    try testing.expect(try recorder.lastValue(a, "session") == .null);
    try testing.expectEqual(@as(usize, 0), counts(core).follows);

    // Back: the session is attached and the others are read again.
    core.selectSession("session:b");
    try testing.expectEqualStrings("session:b", try recorder.lastString(a, "session"));
    try expectRequest(core, "session.attach", "{\"session_id\":\"session:b\"}");
    try testing.expectEqual(quiet + 4, sent(core));
    try testing.expectEqual(@as(usize, 1), queuedFor(core, view_method, "session:a") - 0);
    try testing.expectEqual(@as(usize, 1), queuedFor(core, view_method, "session:c"));
    try testing.expectEqual(@as(usize, 2), counts(core).follows);
    try testing.expectEqual(@as(usize, 0), recorder.errors.items.len);
}

test "Core: a close that finds its target gone reports no error" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    const a = arena.allocator();
    const core = try following(&recorder, a);
    defer core.destroy();
    const states = recorder.states.items.len;

    // Measured: a window closed twice answers `not_found`, a destroyed session `session_not_found`.
    core.command("{\"op\":\"closeWindow\",\"session\":\"session:b\",\"window\":\"window:b1\"}");
    try answerError(core, (try pendingId(core, .close)) orelse return error.NoCloseRequest, "not_found");
    core.command("{\"op\":\"closeSession\",\"session\":\"session:b\"}");
    try answerError(core, (try pendingId(core, .close)) orelse return error.NoCloseRequest, "session_not_found");
    try testing.expectEqual(@as(usize, 0), recorder.errors.items.len);
    // Nor does it change the state: the status stays `connected`.
    try testing.expectEqual(states, recorder.states.items.len);
    try testing.expectEqualStrings("connected", try recorder.lastStatus(a));

    // Any other failure of a close is the user's to know, and so is a rename that fails.
    core.command("{\"op\":\"closeWindow\",\"session\":\"session:b\",\"window\":\"window:b1\"}");
    try answerError(core, (try pendingId(core, .close)) orelse return error.NoCloseRequest, "conflict");
    core.command("{\"op\":\"renameWindow\",\"session\":\"session:b\",\"window\":\"window:gone\",\"label\":\"x\"}");
    try answerError(core, try requestId(core, "session.set_window_label", "\"window:gone\""), "not_found");
    try testing.expectEqual(@as(usize, 2), recorder.errors.items.len);
    try testing.expectEqualStrings("conflict", recorder.errors.items[0]);
    try testing.expectEqualStrings("not_found", recorder.errors.items[1]);
}

test "Core: another session's owner change and process change touch its tab only" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    const a = arena.allocator();
    // `session:a` is selected and its terminal `block:t` is shown and owned by this client.
    const core = try withTerminal(&recorder, me);
    defer core.destroy();
    try answer(core, try requestId(core, view_method, "\"session:b\""), try tabsView(a, "session:b", 3, "window:b1", &.{
        .{ .window = "window:b1", .blocks = &.{ "block:b1", "block:b2" }, .focused = "block:b1" },
    }, true));
    core.paneSurface(term_block, &fake_window, 1000, 600);
    const takes = queued(core, own_block);
    const sizes = queued(core, resize);
    try testing.expectEqual(@as(usize, 1), sizes);
    try testing.expectEqual(@as(usize, 1), counts(core).panes);

    // An owner change in the other session, even one that names this session's block.
    try sessionEvent(core, "session:b", "block_owner_changed", "{\"block_id\":\"block:b1\",\"owner\":\"" ++ other ++ "\",\"reason\":\"take\"}");
    try sessionEvent(core, "session:b", "block_owner_changed", "{\"block_id\":\"" ++ term_block ++ "\",\"owner\":\"" ++ other ++ "\",\"reason\":\"take\"}");
    {
        core.lock.lock();
        defer core.lock.unlock();
        try testing.expect(core.entries.get(term_block).?.size.owned);
    }

    // Its lead terminal was asked about once, when the view arrived.
    var buf: [256]u8 = undefined;
    const session_b = try std.fmt.bufPrint(&buf, "\"session_id\":\"session:b\",\"block_id\":\"block:b1\"", .{});
    try testing.expectEqual(@as(usize, 1), queuedFor(core, process_method, "block:b1"));
    try answer(core, try requestId(core, process_method, session_b),
        \\{"last_exit":null,"foreground":{"name":"zsh","cwd":"/home/alice"},"home":"/home/alice"}
    );
    const shell_tab = (try tabsOf(&recorder, a, "session:b"))[0];
    try testing.expectEqualStrings("zsh", field(shell_tab, "program"));
    try testing.expectEqualStrings("zsh", field(shell_tab, "icon"));
    // It runs another program: one request, for that block.
    try blockEvent(core, "session:b", "block:b1", "process_changed", "{\"generation\":2}");
    try testing.expectEqual(@as(usize, 2), queuedFor(core, process_method, "block:b1"));
    // More events while that request is out are answered by one more, not one each.
    try blockEvent(core, "session:b", "block:b1", "process_changed", "{\"generation\":3}");
    try blockEvent(core, "session:b", "block:b1", "process_changed", "{\"generation\":4}");
    try testing.expectEqual(@as(usize, 2), queuedFor(core, process_method, "block:b1"));
    // A pane beside the lead is not on any row: no request.
    try blockEvent(core, "session:b", "block:b2", "process_changed", "{\"generation\":2}");
    try testing.expectEqual(@as(usize, 0), queuedFor(core, process_method, "block:b2"));
    try blockEvent(core, "session:b", "block:b1", "title_changed", "{\"title\":\"make test\"}");
    try blockEvent(core, "session:b", "block:b1", "program_status_changed", "{\"id\":\"\"}");
    try answer(core, try requestId(core, protocol.methods.terminalProgramStatus, "\"block:b1\""), "{\"records\":[{\"state\":\"working\"}]}");

    try answer(core, try requestId(core, process_method, session_b),
        \\{"last_exit":null,"foreground":{"name":"make","cwd":"/srv/app"},"home":"/home/alice"}
    );
    // The events that came meanwhile: one more request.
    try testing.expectEqual(@as(usize, 3), queuedFor(core, process_method, "block:b1"));

    const tab = (try tabsOf(&recorder, a, "session:b"))[0];
    try testing.expectEqualStrings("make", field(tab, "program"));
    try testing.expectEqualStrings("make", field(tab, "icon"));
    try testing.expectEqualStrings("/srv/app", field(tab, "cwd"));
    try testing.expectEqualStrings("make test", field(tab, "title"));
    try testing.expectEqualStrings("working", field(tab, "activity"));

    // Nothing of it reached the size rules or a pane.
    try testing.expectEqual(takes, queued(core, own_block));
    try testing.expectEqual(sizes, queued(core, resize));
    try testing.expectEqual(@as(usize, 0), queuedFor(core, own_block, "block:b1"));
    try testing.expectEqual(@as(usize, 0), queuedFor(core, resize, "block:b1"));
    try testing.expectEqual(@as(usize, 0), queuedFor(core, protocol.methods.dataConnect, "block:b1"));
    try testing.expectEqual(@as(usize, 1), counts(core).panes);
    const blocks = (try recorder.lastValue(a, "blocks")).object;
    try testing.expectEqual(@as(usize, 1), blocks.count());
    try testing.expect(blocks.get(term_block) != null);
    try testing.expectEqual(@as(usize, 0), recorder.errors.items.len);
}

test "Core: thirty sessions cost one view request each at the start and one per change after it" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    const a = arena.allocator();
    var names: [30][]const u8 = undefined;
    for (&names, 0..) |*name, i| name.* = try std.fmt.allocPrint(a, "s{d:0>2}", .{i});
    const core = try bootstrappedList(&recorder, a, &names);
    defer core.destroy();

    // One attach (the first session) and one view request for each of the others.
    try testing.expectEqual(@as(usize, 1), queued(core, "session.attach"));
    try testing.expectEqual(@as(usize, 29), queued(core, view_method));
    for (names[1..]) |name| try testing.expectEqual(@as(usize, 1), queuedFor(core, view_method, try std.fmt.allocPrint(a, "session:{s}", .{name})));
    try testing.expectEqual(@as(usize, 0), queuedFor(core, view_method, "session:s00"));

    // A list answer with the same sessions (the silence probe, an event) repeats nothing.
    var list: std.ArrayList(u8) = .empty;
    for (names, 0..) |name, i| {
        if (i > 0) try list.append(a, ',');
        try list.print(a, "{{\"session_id\":\"session:{s}\",\"label\":\"{s}\"}}", .{ name, name });
    }
    core.receive("{\"type\":\"event\",\"seq\":3,\"payload\":{\"type\":\"session_moved\",\"payload\":{\"session_id\":\"session:s04\"}}}");
    try answer(core, (try pendingId(core, .list)) orelse return error.NoListRequest, try std.fmt.allocPrint(a, "{{\"sessions\":[{s}]}}", .{list.items}));
    try testing.expectEqual(@as(usize, 29), queued(core, view_method));
    // A new session is read once.
    core.receive("{\"type\":\"event\",\"seq\":4,\"payload\":{\"type\":\"session_created\",\"payload\":{\"session_id\":\"session:new\"}}}");
    try answer(core, (try pendingId(core, .list)) orelse return error.NoListRequest, try std.fmt.allocPrint(a, "{{\"sessions\":[{s},{{\"session_id\":\"session:new\",\"label\":\"new\"}}]}}", .{list.items}));
    try testing.expectEqual(@as(usize, 30), queued(core, view_method));
    try testing.expectEqual(@as(usize, 1), queuedFor(core, view_method, "session:new"));

    // The events scope `all` adds, and kinds this client does not know: no request.
    const before = sent(core);
    core.receive("{\"type\":\"event\",\"seq\":5,\"payload\":{\"type\":\"client_joined\",\"payload\":{\"client_id\":\"client:x\"}}}");
    core.receive("{\"type\":\"event\",\"seq\":6,\"payload\":{\"type\":\"client_changed\",\"payload\":{\"client_id\":\"client:x\"}}}");
    core.receive("{\"type\":\"event\",\"seq\":7,\"payload\":{\"type\":\"client_left\",\"payload\":{\"client_id\":\"client:x\"}}}");
    core.receive("{\"type\":\"event\",\"seq\":8,\"payload\":{\"type\":\"client_performed\",\"payload\":{\"client_id\":\"client:x\",\"action\":\"y\"}}}");
    try sessionEvent(core, "session:s17", "client_connected", "{\"client_id\":\"client:x\"}");
    try sessionEvent(core, "session:s17", "block_created", "{\"block_id\":\"block:n\"}");
    try sessionEvent(core, "session:s17", "window_created", "{\"window_id\":\"window:n\",\"label\":\"n\"}");
    try sessionEvent(core, "session:s17", "active_window_changed", "{\"window_id\":\"window:n\"}");
    try sessionEvent(core, "session:s17", "some_future_event", "{}");
    try testing.expectEqual(before, sent(core));

    // One change in one session: one view request, for that session.
    try answer(core, try requestId(core, view_method, "\"session:s17\""), try tabsView(a, "session:s17", 4, "window:1", &.{.{ .window = "window:1", .blocks = &.{"block:1"} }}, false));
    try sessionEvent(core, "session:s17", "session_view_changed", "{\"revision\":5}");
    try testing.expectEqual(before + 1, sent(core));
    try testing.expectEqual(@as(usize, 2), queuedFor(core, view_method, "session:s17"));
    // Events that come while it is out are compared with its answer.
    try sessionEvent(core, "session:s17", "session_view_changed", "{\"revision\":6}");
    try sessionEvent(core, "session:s17", "session_view_changed", "{\"revision\":7}");
    // One the view already holds asks for nothing.
    try sessionEvent(core, "session:s17", "session_view_changed", "{\"revision\":3}");
    try testing.expectEqual(before + 1, sent(core));
    // The answer is older than the newest revision named: one more read; then none.
    try answer(core, try requestId(core, view_method, "\"session:s17\""), try tabsView(a, "session:s17", 5, "window:1", &.{.{ .window = "window:1", .blocks = &.{"block:1"} }}, false));
    try testing.expectEqual(before + 2, sent(core));
    try answer(core, try requestId(core, view_method, "\"session:s17\""), try tabsView(a, "session:s17", 7, "window:1", &.{
        .{ .window = "window:1", .blocks = &.{"block:1"} },
        .{ .window = "window:2", .blocks = &.{"block:2"} },
    }, false));
    try testing.expectEqual(before + 2, sent(core));
    try testing.expectEqual(@as(usize, 2), (try tabsOf(&recorder, a, "session:s17")).len);
    try testing.expectEqual(@as(usize, 0), recorder.errors.items.len);
}

test "Core: after a reconnect the sessions and their tabs come back without the user" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    const a = arena.allocator();
    const core = try following(&recorder, a);
    defer core.destroy();

    {
        core.lock.lock();
        defer core.lock.unlock();
        core.linkEnded();
    }
    // The rows stay while the link is down.
    try testing.expectEqual(@as(usize, 3), (try tabsOf(&recorder, a, "session:b")).len);
    try testing.expectEqual(@as(usize, 0), sent(core));

    // The bootstrap of the next connection names the selected session and nothing else.
    try testing.expect(core_mod.bootstrapAttach(core.selected, core.auto_pick) == .session);
    const id = registerBootstrap(core);
    const own = try tabsView(a, "session:a", 2, "window:a1", &.{.{ .window = "window:a1", .blocks = &.{"block:a1"}, .focused = "block:a1" }}, false);
    try receiveFmt(core,
        \\{{"type":"response","id":"{d}","payload":{{"attach_all":{{"sessions":[
        \\{{"session_id":"session:a","label":"a"}},{{"session_id":"session:b","label":"b"}}]}},
        \\"attached_sessions":[{{"session":{{"session_id":"session:a","label":"a"}},"view":{s}}}],"data_tickets":[]}}}}
    , .{ id, own });
    try testing.expectEqualStrings("connected", try recorder.lastStatus(a));
    try testing.expectEqualStrings("session:a", try recorder.lastString(a, "session"));
    try testing.expectEqual(@as(usize, 0), queued(core, "session.attach"));
    // The other session is read again: a tab closed while the link was down goes.
    try testing.expectEqual(@as(usize, 1), queuedFor(core, view_method, "session:b"));
    try answer(core, try requestId(core, view_method, "\"session:b\""), try tabsView(a, "session:b", 12, "window:b2", &.{
        .{ .window = "window:b1", .blocks = &.{"block:b1"} },
        .{ .window = "window:b2", .label = "editor", .blocks = &.{ "block:b2", "block:b3" }, .focused = "block:b3" },
    }, true));
    try testing.expectEqual(@as(usize, 2), (try tabsOf(&recorder, a, "session:b")).len);
    // What the lead blocks run may have changed meanwhile: each is asked once more.
    try testing.expectEqual(@as(usize, 2), queued(core, process_method));
    try testing.expectEqual(@as(usize, 1), queuedFor(core, process_method, "block:b3"));
    try testing.expectEqual(@as(usize, 0), recorder.errors.items.len);
}

test "Core: tabs are left out until a session's windows are known; a session without windows lists none" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    const a = arena.allocator();
    const core = try bootstrapped(&recorder);
    defer core.destroy();

    // (1) Before the views arrive.
    try testing.expect(!try knowsTabs(&recorder, a, "session:a"));
    try testing.expect(!try knowsTabs(&recorder, a, "session:b"));

    // (2) The view request of the followed session fails: still unknown, and no error.
    try answerError(core, try requestId(core, view_method, "\"session:b\""), "internal");
    try testing.expect(!try knowsTabs(&recorder, a, "session:b"));
    try testing.expectEqual(@as(usize, 0), recorder.errors.items.len);

    // Known and empty: `[]`, for the selected session and for a followed one.
    const attach_id = (try pendingId(core, .attach)) orelse return error.NoAttachRequest;
    try receiveFmt(core, "{{\"type\":\"response\",\"id\":\"{d}\",\"payload\":{{\"client_id\":\"client:me\",\"view\":{s}}}}}", .{
        attach_id, try tabsView(a, "session:a", 1, null, &.{}, false),
    });
    try testing.expect(try knowsTabs(&recorder, a, "session:a"));
    try testing.expectEqual(@as(usize, 0), (try tabsOf(&recorder, a, "session:a")).len);
    try testing.expect(!try knowsTabs(&recorder, a, "session:b"));
    // The next change of the followed session is read, and it has no windows.
    try sessionEvent(core, "session:b", "session_view_changed", "{\"revision\":4}");
    try testing.expectEqual(@as(usize, 2), queuedFor(core, view_method, "session:b"));
    try answer(core, try requestId(core, view_method, "\"session:b\""), try tabsView(a, "session:b", 4, null, &.{}, true));
    try testing.expect(try knowsTabs(&recorder, a, "session:b"));
    try testing.expectEqual(@as(usize, 0), (try tabsOf(&recorder, a, "session:b")).len);

    // (3) Parked: nothing is known about any session's windows.
    core.command("{\"op\":\"detach\"}");
    try testing.expect(!try knowsTabs(&recorder, a, "session:a"));
    try testing.expect(!try knowsTabs(&recorder, a, "session:b"));
    try testing.expectEqual(@as(usize, 0), recorder.errors.items.len);
}

/// The three tabs of `session:b` that `following` answers with, at `revision`.
fn viewOfB(a: std.mem.Allocator, revision: i64) ![]u8 {
    return tabsView(a, "session:b", revision, "window:b2", &.{
        .{ .window = "window:b1", .blocks = &.{"block:b1"} },
        .{ .window = "window:b2", .label = "editor", .blocks = &.{ "block:b2", "block:b3" }, .focused = "block:b3" },
        .{ .window = "window:b3", .blocks = &.{"block:b4"} },
    }, true);
}

test "Core: an attach answered session_not_found reports nothing and the first listed session is attached" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    const a = arena.allocator();
    const core = try bootstrappedList(&recorder, a, &.{ "a", "b", "c" });
    defer core.destroy();

    // `b` went while this client did not see it: the user taps its row.
    core.selectSession("session:b");
    try testing.expectEqual(@as(usize, 1), queuedFor(core, "session.attach", "session:a"));
    try answerError(core, try requestId(core, "session.attach", "\"session:b\""), "session_not_found");
    try testing.expectEqual(@as(usize, 0), recorder.errors.items.len);
    // Its row goes at once and nothing is selected until the list answers.
    try testing.expectEqual(@as(usize, 2), model.items(try recorder.lastValue(a, "sessions")).len);
    try testing.expect(try recorder.lastValue(a, "session") == .null);
    try testing.expectEqualStrings("connected", try recorder.lastStatus(a));
    try answer(core, (try pendingId(core, .list)) orelse return error.NoListRequest,
        \\{"sessions":[{"session_id":"session:a","label":"a"},{"session_id":"session:c","label":"c"}]}
    );
    // As for a saved session that is gone: the first listed one.
    try testing.expectEqual(@as(usize, 2), queuedFor(core, "session.attach", "session:a"));
    try testing.expectEqual(@as(usize, 0), queuedFor(core, "session.attach", "session:c"));
    try testing.expectEqualStrings("session:a", try recorder.lastString(a, "session"));
    try testing.expectEqual(@as(usize, 0), recorder.errors.items.len);
}

test "Core: an attach the server refuses is reported and no other session is attached" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    const a = arena.allocator();
    const core = try bootstrappedList(&recorder, a, &.{ "a", "b", "c" });
    defer core.destroy();

    core.selectSession("session:b");
    try testing.expectEqual(@as(usize, 2), queued(core, "session.attach"));
    try answerError(core, try requestId(core, "session.attach", "\"session:b\""), "conflict");
    try testing.expectEqual(@as(usize, 1), recorder.errors.items.len);
    try testing.expectEqualStrings("conflict", recorder.errors.items[0]);
    // The session is still there; none is selected and none is attached in its place.
    try testing.expectEqual(@as(usize, 3), model.items(try recorder.lastValue(a, "sessions")).len);
    try testing.expect(try recorder.lastValue(a, "session") == .null);
    try testing.expectEqual(@as(usize, 2), queued(core, "session.attach"));
    // Nor by the next list answer.
    core.receive("{\"type\":\"event\",\"seq\":2,\"payload\":{\"type\":\"session_moved\",\"payload\":{\"session_id\":\"session:b\"}}}");
    try answer(core, (try pendingId(core, .list)) orelse return error.NoListRequest,
        \\{"sessions":[{"session_id":"session:a","label":"a"},{"session_id":"session:b","label":"b"},{"session_id":"session:c","label":"c"}]}
    );
    try testing.expectEqual(@as(usize, 2), queued(core, "session.attach"));
    try testing.expectEqual(@as(usize, 0), counts(core).follows);
    // The user picks another one.
    core.selectSession("session:c");
    try testing.expectEqual(@as(usize, 1), queuedFor(core, "session.attach", "session:c"));
    try testing.expectEqualStrings("session:c", try recorder.lastString(a, "session"));
    try testing.expectEqual(@as(usize, 1), recorder.errors.items.len);
}

test "Core: a session that left the list without a destroy event is no longer followed" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    const a = arena.allocator();
    const core = try following(&recorder, a);
    defer core.destroy();
    const held = counts(core);
    try testing.expectEqual(@as(usize, 1), held.follows);
    try testing.expectEqual(@as(usize, 3), held.leads);

    // The destroy event was lost (a link that dropped, an event too large): only the
    // list says so.
    core.receive("{\"type\":\"event\",\"seq\":2,\"payload\":{\"type\":\"session_moved\",\"payload\":{\"session_id\":\"session:a\"}}}");
    try answer(core, (try pendingId(core, .list)) orelse return error.NoListRequest,
        \\{"sessions":[{"session_id":"session:a","label":"a"}]}
    );
    const left = counts(core);
    try testing.expectEqual(@as(usize, 0), left.follows);
    try testing.expectEqual(@as(usize, 0), left.leads);
    try testing.expectEqual(@as(usize, 1), model.items(try recorder.lastValue(a, "sessions")).len);
    try testing.expectEqualStrings("session:a", try recorder.lastString(a, "session"));
    // Nothing more is asked about it, whatever still arrives for it.
    const before = sent(core);
    try sessionEvent(core, "session:b", "session_view_changed", "{\"revision\":9}");
    try blockEvent(core, "session:b", "block:b3", "process_changed", "{\"generation\":2}");
    try testing.expectEqual(before, sent(core));
    try testing.expectEqual(@as(usize, 0), recorder.errors.items.len);
}

/// An `event_dropped` for an event of `session:b`.
fn droppedEvent(core: *Core, event_type: []const u8, rest: []const u8) !void {
    try receiveFmt(core,
        \\{{"type":"event","seq":60,"payload":{{"type":"event_dropped","payload":{{"event_type":"{s}",
        \\"session_id":"session:b","size":70000{s}}}}}}}
    , .{ event_type, rest });
}

test "Core: a dropped event of a followed session reads its view again, and its lead block when it names one" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    const a = arena.allocator();
    const core = try following(&recorder, a);
    defer core.destroy();
    try testing.expectEqual(@as(usize, 1), queuedFor(core, view_method, "session:b"));

    // The event that was too large to send would have changed the view.
    try droppedEvent(core, "session_view_changed", "");
    try testing.expectEqual(@as(usize, 2), queuedFor(core, view_method, "session:b"));
    try testing.expectEqual(@as(usize, 0), queuedFor(core, view_method, "session:a"));
    try answer(core, try requestId(core, view_method, "\"session:b\""), try viewOfB(a, 7));

    // A dropped block event of a lead block: that terminal is asked about again.
    for ([_][]const u8{ "block:b1", "block:b3", "block:b4" }) |block| {
        for ([_][]const u8{ process_method, protocol.methods.terminalProgramStatus, protocol.methods.terminalTitle }) |method| {
            try answer(core, try requestId(core, method, try std.fmt.allocPrint(a, "\"{s}\"", .{block})), "{}");
        }
    }
    try testing.expectEqual(@as(usize, 1), queuedFor(core, process_method, "block:b3"));
    try droppedEvent(core, "block_event", ",\"block_id\":\"block:b3\",\"creator_name\":\"com.superlogical.terminal\",\"block_event_name\":\"process_changed\"");
    try testing.expectEqual(@as(usize, 3), queuedFor(core, view_method, "session:b"));
    try testing.expectEqual(@as(usize, 2), queuedFor(core, process_method, "block:b3"));
    try testing.expectEqual(@as(usize, 2), queuedFor(core, protocol.methods.terminalProgramStatus, "block:b3"));
    try testing.expectEqual(@as(usize, 1), queuedFor(core, process_method, "block:b1"));
    try testing.expectEqual(@as(usize, 0), recorder.errors.items.len);
}

test "Core: a burst of events of a followed session costs at most two requests of each kind" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    const a = arena.allocator();
    const core = try following(&recorder, a);
    defer core.destroy();
    const status_method = protocol.methods.terminalProgramStatus;
    const title_method = protocol.methods.terminalTitle;
    const methods = [_][]const u8{ process_method, status_method, title_method };
    const b3 = "\"block:b3\"";
    // The first answers about the lead terminals are in.
    for ([_][]const u8{ "block:b1", "block:b3", "block:b4" }) |block| {
        for (methods) |method| try answer(core, try requestId(core, method, try std.fmt.allocPrint(a, "\"{s}\"", .{block})), "{}");
    }
    const burst = 200;
    const start = sent(core);
    const published = recorder.states.items.len;

    // A terminal that starts and ends programs faster than the server answers, a view
    // that changes as fast, and events too large to send.
    for (0..burst) |i| {
        try blockEvent(core, "session:b", "block:b3", "process_changed", "{\"generation\":2}");
        try blockEvent(core, "session:b", "block:b3", "program_status_changed", "{\"id\":\"x\"}");
        try sessionEvent(core, "session:b", "session_view_changed", try std.fmt.allocPrint(a, "{{\"revision\":{d}}}", .{8 + i}));
        try droppedEvent(core, "block_event", ",\"block_id\":\"block:b3\",\"creator_name\":\"com.superlogical.terminal\",\"block_event_name\":\"title_changed\"");
    }
    // One request of each kind is out, whatever the number of events; no state yet.
    try testing.expectEqual(start + 4, sent(core));
    try testing.expectEqual(@as(usize, 2), queuedFor(core, view_method, "session:b"));
    for (methods) |method| try testing.expectEqual(@as(usize, 2), queuedFor(core, method, "block:b3"));
    try testing.expectEqual(published, recorder.states.items.len);

    // Each answer is followed by one more request, for what came meanwhile.
    try answer(core, try requestId(core, view_method, "\"session:b\""), try viewOfB(a, 100));
    for (methods) |method| try answer(core, try requestId(core, method, b3), "{}");
    try testing.expectEqual(start + 8, sent(core));
    try testing.expectEqual(@as(usize, 3), queuedFor(core, view_method, "session:b"));
    for (methods) |method| try testing.expectEqual(@as(usize, 3), queuedFor(core, method, "block:b3"));

    // And those answers by none.
    try answer(core, try requestId(core, view_method, "\"session:b\""), try viewOfB(a, 8 + burst));
    for (methods) |method| try answer(core, try requestId(core, method, b3), "{}");
    try testing.expectEqual(start + 8, sent(core));
    // 800 events, 8 requests, and the state was published for the views only.
    try testing.expect(recorder.states.items.len <= published + 2);
    try testing.expectEqual(@as(usize, 0), recorder.errors.items.len);
}

test "Core: a view change of a followed session that the view held already has asks nothing" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    const a = arena.allocator();
    // The view of `session:b` held is revision 7.
    const core = try following(&recorder, a);
    defer core.destroy();
    const before = sent(core);

    // Events can arrive out of revision order, and after the view that holds them.
    try sessionEvent(core, "session:b", "session_view_changed", "{\"revision\":7}");
    try sessionEvent(core, "session:b", "session_view_changed", "{\"revision\":5}");
    try testing.expectEqual(before, sent(core));
    try testing.expectEqual(@as(usize, 1), queuedFor(core, view_method, "session:b"));
    // A newer one is read.
    try sessionEvent(core, "session:b", "session_view_changed", "{\"revision\":8}");
    try testing.expectEqual(@as(usize, 2), queuedFor(core, view_method, "session:b"));
    try testing.expectEqual(before + 1, sent(core));
}

test "Core: back to the session shown before, the session left keeps the view its panes had" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    const a = arena.allocator();
    const core = try following(&recorder, a);
    defer core.destroy();

    // a -> b: the attach answers, then `b` gets a fourth tab while it is shown.
    core.selectSession("session:b");
    const three = try tabsView(a, "session:b", 7, "window:b2", &.{
        .{ .window = "window:b1", .blocks = &.{"block:b1"} },
        .{ .window = "window:b2", .blocks = &.{ "block:b2", "block:b3" }, .focused = "block:b3" },
        .{ .window = "window:b3", .blocks = &.{"block:b4"} },
    }, false);
    try receiveFmt(core, "{{\"type\":\"response\",\"id\":\"{d}\",\"payload\":{{\"client_id\":\"client:me\",\"view\":{s}}}}}", .{
        try requestId(core, "session.attach", "\"session:b\""), three,
    });
    try sessionEvent(core, "session:b", "session_view_changed", "{\"revision\":9}");
    try answer(core, (try pendingId(core, .view)) orelse return error.NoViewRequest, try tabsView(a, "session:b", 9, "window:b5", &.{
        .{ .window = "window:b1", .blocks = &.{"block:b1"} },
        .{ .window = "window:b2", .blocks = &.{ "block:b2", "block:b3" }, .focused = "block:b3" },
        .{ .window = "window:b3", .blocks = &.{"block:b4"} },
        .{ .window = "window:b5", .blocks = &.{"block:b5"} },
    }, false));
    try testing.expectEqual(@as(usize, 4), (try tabsOf(&recorder, a, "session:b")).len);
    const views = queued(core, view_method);

    // b -> a: `b` shows its four tabs at once, from the view its panes had, and costs no
    // request.
    core.selectSession("session:a");
    try testing.expectEqualStrings("session:a", try recorder.lastString(a, "session"));
    const tabs = try tabsOf(&recorder, a, "session:b");
    try testing.expectEqual(@as(usize, 4), tabs.len);
    try testing.expectEqual(true, model.boolean(model.get(tabs[3], "active")).?);
    try testing.expectEqual(views, queued(core, view_method));
    try testing.expectEqual(@as(usize, 1), (try tabsOf(&recorder, a, "session:a")).len);
    try testing.expectEqual(@as(usize, 0), recorder.errors.items.len);
}

test "Core: a saved session that is gone at the bootstrap gives way to the first listed session" {
    // Measured: a bootstrap that names a session which no longer exists succeeds, with
    // `attached_sessions` empty or absent and `selected_session` null. The neighbour rule
    // needs the place the session had in the list, which this connection never saw, so
    // the core takes the first listed session, as it does with nothing saved.
    for ([_][]const u8{ "\"attached_sessions\":[],", "" }) |attached| {
        var recorder: Recorder = .{};
        defer recorder.deinit();
        var arena: std.heap.ArenaAllocator = .init(testing.allocator);
        defer arena.deinit();
        const a = arena.allocator();
        const core = try Core.create(testing.allocator, recorder.host(), .{}, 1);
        defer core.destroy();

        core.selectSession("session:gone");
        try testing.expect(core_mod.bootstrapAttach(core.selected, core.auto_pick) == .session);
        const id = registerBootstrap(core);
        try receiveFmt(core,
            \\{{"type":"response","id":"{d}","payload":{{"attach_all":{{"sessions":[
            \\{{"session_id":"session:a","label":"a"}},{{"session_id":"session:b","label":"b"}}]}},
            \\{s}"selected_session":null,"data_tickets":[]}}}}
        , .{ id, attached });

        try testing.expectEqualStrings("connected", try recorder.lastStatus(a));
        try testing.expectEqualStrings("session:a", try recorder.lastString(a, "session"));
        try testing.expectEqual(@as(usize, 1), queued(core, "session.attach"));
        try expectRequest(core, "session.attach", "{\"session_id\":\"session:a\"}");
        try testing.expectEqual(@as(usize, 0), queued(core, "session.detach"));
        // The other listed session is followed, not attached.
        try testing.expectEqual(@as(usize, 1), queuedFor(core, view_method, "session:b"));

        // The next list answer attaches nothing more.
        core.receive("{\"type\":\"event\",\"seq\":2,\"payload\":{\"type\":\"session_moved\",\"payload\":{\"session_id\":\"session:b\"}}}");
        try answer(core, (try pendingId(core, .list)) orelse return error.NoListRequest,
            \\{"sessions":[{"session_id":"session:a","label":"a"},{"session_id":"session:b","label":"b"}]}
        );
        try testing.expectEqual(@as(usize, 1), queued(core, "session.attach"));
        try testing.expectEqualStrings("session:a", try recorder.lastString(a, "session"));
        try testing.expectEqual(@as(usize, 0), recorder.errors.items.len);
    }
}

// ------------------------------------------------------------------ the fence
//
// With `{"op":"fence","prefix":P}` a session exists for the core only when its label
// starts with P or the core created it. In these tests P is `mine-`: `session:m1` is
// labelled `mine-m1`, and a session without the prefix is labelled as it is named.

/// Queued requests whose text holds `needle` (an id in quotes).
fn naming(core: *Core, needle: []const u8) usize {
    core.lock.lock();
    defer core.lock.unlock();
    var n: usize = 0;
    for (core.outbox.items) |text| {
        if (std.mem.find(u8, text, needle) != null) n += 1;
    }
    return n;
}

fn sessionIds(recorder: *Recorder, a: std.mem.Allocator) ![]const u8 {
    var out: std.ArrayList(u8) = .empty;
    for (model.items(try recorder.lastValue(a, "sessions"))) |s| {
        if (out.items.len > 0) try out.append(a, ' ');
        try out.appendSlice(a, field(s, "id"));
    }
    return out.items;
}

/// The bootstrap request the core would send now.
fn bootstrapText(core: *Core) ![]u8 {
    core.lock.lock();
    const wanted = core.bootstrapWanted();
    const text = protocol.bootstrap(testing.allocator, wanted);
    core.lock.unlock();
    return text;
}

const fence_prefix = "mine-";

/// A `sessions` array: `session:<name>` for each name, labelled `mine-<name>` when the
/// name starts with `m`, else `<name>`.
fn labelled(a: std.mem.Allocator, names: []const []const u8) ![]const u8 {
    var list: std.ArrayList(u8) = .empty;
    for (names, 0..) |name, i| {
        if (i > 0) try list.append(a, ',');
        try list.print(a, "{{\"session_id\":\"session:{s}\",\"label\":\"{s}{s}\"}}", .{ name, if (name[0] == 'm') fence_prefix else "", name });
    }
    return list.items;
}

/// A core fenced to `mine-` before it connects, that took a bootstrap answer listing
/// `names` (see `labelled`) with nothing attached. `selected`: named before the connect.
fn fencedList(recorder: *Recorder, a: std.mem.Allocator, selected: ?[]const u8, names: []const []const u8) !*Core {
    const core = try Core.create(testing.allocator, recorder.host(), .{}, 1);
    errdefer core.destroy();
    core.command("{\"op\":\"fence\",\"prefix\":\"" ++ fence_prefix ++ "\"}");
    if (selected) |s| core.selectSession(s);
    try answerFencedBootstrap(core, a, names);
    return core;
}

fn answerFencedBootstrap(core: *Core, a: std.mem.Allocator, names: []const []const u8) !void {
    const id = registerBootstrap(core);
    try receiveFmt(core, "{{\"type\":\"response\",\"id\":\"{d}\",\"payload\":{{\"attach_all\":{{\"sessions\":[{s}]}},\"attached_sessions\":[],\"selected_session\":null,\"data_tickets\":[]}}}}", .{ id, try labelled(a, names) });
}

/// Answer the newest `session.list` request with `names`.
fn answerList(core: *Core, a: std.mem.Allocator, names: []const []const u8) !void {
    try answer(core, try requestId(core, "session.list", "\"id\""), try std.fmt.allocPrint(a, "{{\"sessions\":[{s}]}}", .{try labelled(a, names)}));
}

/// Answer the pending `session.attach` with `view`.
fn answerAttach(core: *Core, view: []const u8) !void {
    const id = (try pendingId(core, .attach)) orelse return error.NoAttachRequest;
    try receiveFmt(core, "{{\"type\":\"response\",\"id\":\"{d}\",\"payload\":{{\"client_id\":\"client:me\",\"view\":{s}}}}}", .{ id, view });
}

test "fence: the first listed session lacks the prefix, so the pick takes the second; the state leaves the others out" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    const a = arena.allocator();
    const core = try fencedList(&recorder, a, null, &.{ "x1", "m1", "x2", "m2" });
    defer core.destroy();

    try testing.expectEqual(@as(usize, 1), queued(core, "session.attach"));
    try expectRequest(core, "session.attach", "{\"session_id\":\"session:m1\"}");
    try testing.expectEqualStrings("session:m1", try recorder.lastString(a, "session"));
    // Neither of the others is in the state, or followed, or named in any request.
    try testing.expectEqualStrings("session:m1 session:m2", try sessionIds(&recorder, a));
    try testing.expectEqual(@as(usize, 1), counts(core).follows);
    try testing.expectEqual(@as(usize, 1), queuedFor(core, view_method, "session:m2"));
    try testing.expectEqual(@as(usize, 0), naming(core, "\"session:x1\""));
    try testing.expectEqual(@as(usize, 0), naming(core, "\"session:x2\""));
    try testing.expectEqual(@as(usize, 0), recorder.errors.items.len);
}

test "fence: the neighbour of a destroyed session skips sessions without the prefix in both directions, and is none when no other is left" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    const a = arena.allocator();
    // Server order: m1 x1 m2 x2 m3.
    const core = try fencedList(&recorder, a, null, &.{ "m1", "x1", "m2", "x2", "m3" });
    defer core.destroy();
    try testing.expectEqualStrings("session:m1", try recorder.lastString(a, "session"));

    // Forwards: after `m2` comes `x2`; the next that exists is `m3`.
    core.selectSession("session:m2");
    try sessionEvent(core, "session:m2", "session_destroyed", "{\"session_id\":\"session:m2\"}");
    try testing.expectEqualStrings("session:m3", try recorder.lastString(a, "session"));
    try expectRequest(core, "session.attach", "{\"session_id\":\"session:m3\"}");
    // Backwards: `m3` is the last; before it come `x2` and `x1`, then `m1`.
    try sessionEvent(core, "session:m3", "session_destroyed", "{\"session_id\":\"session:m3\"}");
    try testing.expectEqualStrings("session:m1", try recorder.lastString(a, "session"));
    try expectRequest(core, "session.attach", "{\"session_id\":\"session:m1\"}");
    // No other is left: none.
    const attaches = queued(core, "session.attach");
    try sessionEvent(core, "session:m1", "session_destroyed", "{\"session_id\":\"session:m1\"}");
    try testing.expect(try recorder.lastValue(a, "session") == .null);
    try testing.expectEqual(attaches, queued(core, "session.attach"));
    // The list answer still names the two others: nothing is picked from it.
    try answerList(core, a, &.{ "x1", "x2" });
    try testing.expectEqual(attaches, queued(core, "session.attach"));
    try testing.expectEqualStrings("", try sessionIds(&recorder, a));
    try testing.expectEqual(@as(usize, 0), naming(core, "\"session:x1\""));
    try testing.expectEqual(@as(usize, 0), naming(core, "\"session:x2\""));
    try testing.expectEqual(@as(usize, 0), recorder.errors.items.len);
}

test "fence: with a fence the bootstrap asks for attach none, also with a selection" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    const core = try Core.create(testing.allocator, recorder.host(), .{}, 1);
    defer core.destroy();
    const pick = "{\"event_scope\":\"all\",\"data_tickets\":";
    const none = "{\"event_scope\":\"all\",\"attach_session_ids\":[],\"data_tickets\":";
    const named = "{\"event_scope\":\"all\",\"attach_session_ids\":[\"session:m1\"],";
    const Step = struct { do: []const u8, select: bool = false, want: []const u8 };
    const steps = [_]Step{
        // No fence: the server picks, or the selection is named, as before.
        .{ .do = "", .want = pick },
        .{ .do = "{\"op\":\"fence\",\"prefix\":\"\"}", .want = pick },
        .{ .do = "{\"op\":\"fence\"}", .want = pick },
        // A fence: no label is known at the bootstrap, so none is attached by it.
        .{ .do = "{\"op\":\"fence\",\"prefix\":\"mine-\"}", .want = none },
        .{ .do = "", .select = true, .want = none },
        // Lifted with the selection kept: it is named again.
        .{ .do = "{\"op\":\"fence\",\"prefix\":\"\"}", .want = named },
        .{ .do = "{\"op\":\"fence\",\"prefix\":\"mine-\"}", .want = none },
    };
    for (steps) |step| {
        if (step.do.len > 0) core.command(step.do);
        if (step.select) core.selectSession("session:m1");
        const text = try bootstrapText(core);
        defer testing.allocator.free(text);
        if (!std.mem.startsWith(u8, text, step.want)) {
            std.debug.print("after `{s}` the bootstrap is\n  {s}\nwanted\n  {s}\n", .{ step.do, text, step.want });
            return error.TestExpectedEqual;
        }
    }
    try testing.expectEqual(@as(usize, 0), recorder.errors.items.len);
}

test "fence: the selection is attached after the list when it has the prefix, and replaced by a pick when it has not" {
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    const a = arena.allocator();
    {
        var recorder: Recorder = .{};
        defer recorder.deinit();
        const core = try fencedList(&recorder, a, "session:m2", &.{ "x1", "m1", "m2" });
        defer core.destroy();
        try testing.expectEqual(@as(usize, 1), queued(core, "session.attach"));
        try expectRequest(core, "session.attach", "{\"session_id\":\"session:m2\"}");
        try testing.expectEqualStrings("session:m2", try recorder.lastString(a, "session"));
        try testing.expectEqualStrings("session:m1 session:m2", try sessionIds(&recorder, a));
        try testing.expectEqual(@as(usize, 0), naming(core, "\"session:x1\""));
        try testing.expectEqual(@as(usize, 0), recorder.errors.items.len);
    }
    // The saved session is one of the others, or is gone: the first that exists.
    for ([_][]const u8{ "session:x1", "session:gone" }) |saved| {
        var recorder: Recorder = .{};
        defer recorder.deinit();
        const core = try fencedList(&recorder, a, saved, &.{ "x1", "m1", "m2" });
        defer core.destroy();
        try testing.expectEqual(@as(usize, 1), queued(core, "session.attach"));
        try expectRequest(core, "session.attach", "{\"session_id\":\"session:m1\"}");
        try testing.expectEqualStrings("session:m1", try recorder.lastString(a, "session"));
        try testing.expectEqual(@as(usize, 0), naming(core, "\"session:x1\""));
        try testing.expectEqual(@as(usize, 0), naming(core, "\"session:gone\""));
        try testing.expectEqual(@as(usize, 0), recorder.errors.items.len);
    }
}

test "fence: a session the bootstrap answer reports attached is detached before it has a pane, unless it exists for the core" {
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    const a = arena.allocator();
    var buf: [1024]u8 = undefined;
    // `session:a` with the terminal `block:t`.
    const view = try sizeView(&buf, 1, null);

    for ([_][]const u8{ "selected_session", "attached" }) |how| {
        for ([_][]const u8{ "a", fence_prefix ++ "a" }) |label| {
            var recorder: Recorder = .{};
            defer recorder.deinit();
            const core = try Core.create(testing.allocator, recorder.host(), .{}, 1);
            defer core.destroy();
            core.command("{\"op\":\"fence\",\"prefix\":\"" ++ fence_prefix ++ "\"}");
            const entry = try std.fmt.allocPrint(a, "{{\"session\":{{\"session_id\":\"session:a\",\"label\":\"{s}\"}},\"view\":{s}}}", .{ label, view });
            const selected = std.mem.eql(u8, how, "selected_session");
            const id = registerBootstrap(core);
            try receiveFmt(core,
                \\{{"type":"response","id":"{d}","payload":{{"attach_all":{{"sessions":[
                \\{{"session_id":"session:a","label":"{s}"}},{{"session_id":"session:m1","label":"mine-m1"}}]}},
                \\"attached_sessions":[{s}],"selected_session":{s},"data_tickets":[]}}}}
            , .{ id, label, if (selected) "" else entry, if (selected) entry else "null" });

            if (std.mem.startsWith(u8, label, fence_prefix)) {
                // One of this core's: kept, with its pane.
                try testing.expectEqual(@as(usize, 0), queued(core, "session.detach"));
                try testing.expectEqual(@as(usize, 1), counts(core).panes);
                try testing.expectEqualStrings("session:a", try recorder.lastString(a, "session"));
            } else {
                try testing.expectEqual(@as(usize, 1), queuedFor(core, "session.detach", "session:a"));
                try testing.expectEqual(@as(usize, 1), naming(core, "\"session:a\""));
                try testing.expectEqual(@as(usize, 0), counts(core).panes);
                try testing.expectEqual(@as(usize, 0), queued(core, protocol.methods.dataConnect));
                try testing.expectEqualStrings("session:m1", try sessionIds(&recorder, a));
                try testing.expectEqualStrings("session:m1", try recorder.lastString(a, "session"));
                try expectRequest(core, "session.attach", "{\"session_id\":\"session:m1\"}");
            }
            try testing.expectEqual(@as(usize, 0), recorder.errors.items.len);
        }
    }
}

test "fence: a session made later by someone else, without the prefix, is never listed, attached or followed, and its events cause no request" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    const a = arena.allocator();
    const core = try fencedList(&recorder, a, null, &.{"m1"});
    defer core.destroy();
    try testing.expectEqualStrings("session:m1", try recorder.lastString(a, "session"));
    const before = sent(core);
    const states = recorder.states.items.len;

    // The owner makes a session while the app runs, and works in it.
    core.receive("{\"type\":\"event\",\"seq\":70,\"payload\":{\"type\":\"session_created\",\"payload\":{\"session\":{\"session_id\":\"session:x9\",\"label\":\"brave-shoal\"}}}}");
    try sessionEvent(core, "session:x9", "session_view_changed", "{\"revision\":9}");
    try blockEvent(core, "session:x9", "block:x1", "process_changed", "{\"generation\":2}");
    try blockEvent(core, "session:x9", "block:x1", "title_changed", "{\"title\":\"secret\"}");
    try sessionEvent(core, "session:x9", "session_label_changed", "{\"label\":\"renamed\"}");
    try sessionEvent(core, "session:x9", "window_label_changed", "{\"window_id\":\"window:x1\",\"label\":\"w\"}");
    try sessionEvent(core, "session:x9", "block_owner_changed", "{\"block_id\":\"block:x1\",\"owner\":\"client:other\",\"reason\":\"take\"}");
    try receiveFmt(core, "{{\"type\":\"event\",\"seq\":61,\"payload\":{{\"type\":\"event_dropped\",\"payload\":{{\"event_type\":\"block_event\",\"session_id\":\"session:x9\",\"size\":70000}}}}}}", .{});
    core.receive("{\"type\":\"event\",\"seq\":62,\"payload\":{\"type\":\"session_moved\",\"payload\":{\"session_id\":\"session:x9\"}}}");
    try testing.expectEqual(before, sent(core));

    // Nothing the user does through the core reaches it.
    core.selectSession("session:x9");
    const intents = [_][]const u8{
        "{\"op\":\"closeSession\",\"session\":\"session:x9\"}",
        "{\"op\":\"closeWindow\",\"session\":\"session:x9\",\"window\":\"window:x1\"}",
        "{\"op\":\"renameSession\",\"session\":\"session:x9\",\"label\":\"mine-now\"}",
        "{\"op\":\"renameWindow\",\"session\":\"session:x9\",\"window\":\"window:x1\",\"label\":\"mine\"}",
        "{\"op\":\"newWindow\",\"session\":\"session:x9\"}",
        "{\"op\":\"selectWindow\",\"session\":\"session:x9\",\"window\":\"window:x1\"}",
    };
    for (intents) |text| core.command(text);
    try testing.expectEqual(before, sent(core));
    try testing.expectEqual(@as(usize, 0), naming(core, "\"session:x9\""));
    // Nothing changed for the user either: the selection, the state, no error.
    try testing.expectEqual(states, recorder.states.items.len);
    try testing.expectEqualStrings("session:m1", try recorder.lastString(a, "session"));
    try testing.expectEqual(@as(usize, 0), recorder.errors.items.len);

    // The session shown is closed: the newer session is no neighbour and no pick.
    core.receive("{\"type\":\"event\",\"seq\":63,\"payload\":{\"type\":\"session_destroyed\",\"payload\":{\"session_id\":\"session:m1\"}}}");
    try answerList(core, a, &.{"x9"});
    try testing.expect(try recorder.lastValue(a, "session") == .null);
    try testing.expectEqualStrings("", try sessionIds(&recorder, a));
    try testing.expectEqual(@as(usize, 1), queued(core, "session.attach"));
    try testing.expectEqual(@as(usize, 0), naming(core, "\"session:x9\""));
    // Its destruction is nothing to this core either.
    const quiet = sent(core);
    core.receive("{\"type\":\"event\",\"seq\":64,\"payload\":{\"type\":\"session_destroyed\",\"payload\":{\"session_id\":\"session:x9\"}}}");
    try testing.expectEqual(quiet, sent(core));
    // The same intent on one of this core's sessions is sent.
    core.receive("{\"type\":\"event\",\"seq\":71,\"payload\":{\"type\":\"session_created\",\"payload\":{\"session\":{\"session_id\":\"session:m2\",\"label\":\"mine-m2\"}}}}");
    try answerList(core, a, &.{ "x9", "m2" });
    core.command("{\"op\":\"renameWindow\",\"session\":\"session:m2\",\"window\":\"window:1\",\"label\":\"w\"}");
    try testing.expectEqual(@as(usize, 1), queued(core, "session.set_window_label"));
    try testing.expectEqual(@as(usize, 0), naming(core, "\"session:x9\""));
    try testing.expectEqual(@as(usize, 0), recorder.errors.items.len);
}

test "fence: a session this core created is kept although its label lacks the prefix" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    const a = arena.allocator();
    const core = try fencedList(&recorder, a, null, &.{ "m1", "x1" });
    defer core.destroy();

    // The `+` of the sidebar: the server names the new session itself.
    core.command("{\"op\":\"newSession\"}");
    core.receive("{\"type\":\"event\",\"seq\":70,\"payload\":{\"type\":\"session_created\",\"payload\":{\"session\":{\"session_id\":\"session:new\",\"label\":\"quiet-meadow\"}}}}");
    try answer(core, (try pendingId(core, .create_session)) orelse return error.NoCreateRequest, "{\"session_id\":\"session:new\",\"initial_windows\":[]}");
    try expectRequest(core, "session.attach", "{\"session_id\":\"session:new\"}");
    try testing.expectEqualStrings("session:new", try recorder.lastString(a, "session"));
    // It is in the list, with its label, and stays there.
    try answer(core, try requestId(core, "session.list", "\"id\""),
        \\{"sessions":[{"session_id":"session:m1","label":"mine-m1"},{"session_id":"session:x1","label":"x1"},{"session_id":"session:new","label":"quiet-meadow"}]}
    );
    try testing.expectEqualStrings("session:m1 session:new", try sessionIds(&recorder, a));
    try testing.expectEqualStrings("session:new", try recorder.lastString(a, "session"));
    // One detach so far, of the session shown before; a new label of its own costs none.
    try testing.expectEqual(@as(usize, 1), queued(core, "session.detach"));
    try sessionEvent(core, "session:new", "session_label_changed", "{\"label\":\"calm-river\"}");
    try testing.expectEqualStrings("session:new", try recorder.lastString(a, "session"));
    try testing.expectEqual(@as(usize, 1), queued(core, "session.detach"));
    // Its intents are sent; the other session without the prefix stays out.
    core.command("{\"op\":\"closeSession\",\"session\":\"session:new\"}");
    try expectRequest(core, "session.destroy", "{\"session_id\":\"session:new\"}");
    try testing.expectEqual(@as(usize, 0), naming(core, "\"session:x1\""));
    try testing.expectEqual(@as(usize, 0), recorder.errors.items.len);
}

test "fence: the selected session is renamed out of the prefix: it is detached and the core picks as after a destroy; a rename into it brings a session in" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    const a = arena.allocator();
    const core = try fencedList(&recorder, a, "session:m2", &.{ "m1", "m2", "x1", "m3" });
    defer core.destroy();
    // `m2` is attached and shows the terminal `block:t`; `m1` and `m3` are followed.
    var buf: [1024]u8 = undefined;
    try answerAttach(core, try std.mem.replaceOwned(u8, a, try sizeView(&buf, 1, null), "session:a", "session:m2"));
    try testing.expectEqual(@as(usize, 1), counts(core).panes);
    try testing.expectEqual(@as(usize, 2), counts(core).follows);
    // The pane's stream requests its initial ticket on another thread. Count it before
    // measuring what the rename sends; otherwise a pre-rename ticket looks like a leak.
    for (0..200) |_| {
        if (queuedFor(core, protocol.methods.dataConnect, "session:m2") != 0) break;
        try std.Io.sleep(testing.io, .fromMilliseconds(5), .awake);
    } else return error.MissingInitialTicketRequest;
    const about = naming(core, "\"session:m2\"");

    // Someone renames it. One more request names it, the detach; then it does not exist.
    try sessionEvent(core, "session:m2", "session_label_changed", "{\"label\":\"taken-over\"}");
    try testing.expectEqual(about + 1, naming(core, "\"session:m2\""));
    try testing.expectEqual(@as(usize, 1), queuedFor(core, "session.detach", "session:m2"));
    try testing.expectEqual(@as(usize, 0), counts(core).panes);
    // The next in order is `x1`, which does not exist: `m3`.
    try testing.expectEqualStrings("session:m3", try recorder.lastString(a, "session"));
    try testing.expectEqualStrings("session:m1 session:m3", try sessionIds(&recorder, a));
    try expectRequest(core, "session.attach", "{\"session_id\":\"session:m3\"}");
    const quiet = sent(core);
    try sessionEvent(core, "session:m2", "session_view_changed", "{\"revision\":40}");
    core.selectSession("session:m2");
    try testing.expectEqual(quiet, sent(core));

    // A followed session renamed out of the prefix is forgotten too, by the event...
    try sessionEvent(core, "session:m1", "session_label_changed", "{\"label\":\"gone-away\"}");
    try testing.expectEqualStrings("session:m3", try sessionIds(&recorder, a));
    try testing.expectEqual(quiet, sent(core));
    // ...and a session renamed into the prefix starts to exist: the list is read.
    try sessionEvent(core, "session:x1", "session_label_changed", "{\"label\":\"mine-x1\"}");
    try testing.expectEqual(quiet + 1, sent(core));
    try answer(core, try requestId(core, "session.list", "\"id\""),
        \\{"sessions":[{"session_id":"session:m1","label":"gone-away"},{"session_id":"session:m2","label":"taken-over"},
        \\{"session_id":"session:x1","label":"mine-x1"},{"session_id":"session:m3","label":"mine-m3"}]}
    );
    try testing.expectEqualStrings("session:x1 session:m3", try sessionIds(&recorder, a));
    try testing.expectEqual(@as(usize, 1), queuedFor(core, view_method, "session:x1"));
    try testing.expectEqualStrings("session:m3", try recorder.lastString(a, "session"));

    // Without the event (lost, or before the list): the list answer decides the same.
    try answerAttach(core, try tabsView(a, "session:m3", 1, null, &.{}, false));
    core.receive("{\"type\":\"event\",\"seq\":80,\"payload\":{\"type\":\"session_moved\",\"payload\":{\"session_id\":\"session:m3\"}}}");
    try answer(core, try requestId(core, "session.list", "\"id\""),
        \\{"sessions":[{"session_id":"session:x1","label":"mine-x1"},{"session_id":"session:m3","label":"theirs"}]}
    );
    try testing.expectEqual(@as(usize, 1), queuedFor(core, "session.detach", "session:m3"));
    try testing.expectEqualStrings("session:x1", try recorder.lastString(a, "session"));
    try testing.expectEqualStrings("session:x1", try sessionIds(&recorder, a));
    try testing.expectEqual(@as(usize, 0), recorder.errors.items.len);
}

test "fence: reattach restarts the retained stream once and queues ownership before the first write" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    const a = arena.allocator();
    const core = try fencedList(&recorder, a, null, &.{ "m1", "x1" });
    defer core.destroy();
    core.receive("{\"type\":\"welcome\",\"protocol\":\"rex.control.v1\",\"client\":{\"id\":\"" ++ me ++ "\",\"reconnect_token\":\"\"},\"api_revision\":18}");
    var buf: [1024]u8 = undefined;
    const view = try std.mem.replaceOwned(u8, a, try sizeView(&buf, 1, me), "session:a", "session:m1");
    const reattached = try std.mem.replaceOwned(u8, a, try sizeView(&buf, 2, other), "session:a", "session:m1");
    try answerAttach(core, view);
    core.paneSurface(term_block, &fake_window, 1000, 600);
    try testing.expectEqual(@as(usize, 1), counts(core).panes);

    {
        core.lock.lock();
        defer core.lock.unlock();
        core.linkEnded();
    }
    try answerFencedBootstrap(core, a, &.{ "m1", "x1" });
    // The pane was not thrown away, and the session is attached again, once.
    try testing.expectEqual(@as(usize, 1), counts(core).panes);
    try testing.expectEqual(@as(usize, 1), queued(core, "session.attach"));
    try expectRequest(core, "session.attach", "{\"session_id\":\"session:m1\"}");
    try testing.expectEqualStrings("session:m1", try recorder.lastString(a, "session"));

    // Keep the two inputs on this pane while the server's attach answer is held.
    core.command("{\"op\":\"sendText\",\"block\":\"" ++ term_block ++ "\",\"text\":\"first\"}");
    core.key(term_block, 66, 0, 0, 0); // KEYCODE_ENTER
    try sessionEvent(core, "session:m1", "session_view_changed", "{\"revision\":3}");
    try blockEvent(core, "session:m1", term_block, "process_changed", "{\"generation\":2}");
    try testing.expectEqual(@as(usize, 0), queued(core, own_block));
    try testing.expectEqual(@as(usize, 0), queued(core, terminal_write));
    try testing.expectEqual(@as(usize, 0), queued(core, protocol.methods.dataConnect));
    try testing.expectEqual(@as(usize, 0), queued(core, protocol.methods.terminalProcess));
    try testing.expectEqual(@as(usize, 0), queuedFor(core, view_method, "session:m1"));
    try testing.expectEqual(@as(u32, 0), streamRestarts(core, term_block));

    const attach_id = try requestId(core, "session.attach", "session:m1");
    try answerAttach(core, reattached);
    try testing.expectEqual(@as(usize, 1), counts(core).panes);
    try testing.expectEqual(@as(u32, 1), streamRestarts(core, term_block));
    try testing.expectEqual(@as(usize, 1), queued(core, protocol.methods.terminalProcess));
    try testing.expectEqual(@as(usize, 1), queuedFor(core, view_method, "session:m1"));
    try testing.expectEqual(@as(usize, 1), queued(core, own_block));
    try testing.expectEqual(@as(usize, 1), queued(core, terminal_write));
    const take_id = try requestId(core, own_block, "\"session_id\":\"session:m1\"");
    const write_id = try requestId(core, terminal_write, "\"session_id\":\"session:m1\"");
    try testing.expect(attach_id < take_id and take_id < write_id);
    try expectRequest(core, own_block,
        "{\"session_id\":\"session:m1\",\"block_ids\":[\"" ++ term_block ++ "\"],\"mode\":\"take\"}");
    try expectRequest(core, terminal_write,
        "{\"session_id\":\"session:m1\",\"block_id\":\"" ++ term_block ++ "\",\"args\":{\"data\":\"first\\r\"}}");

    try answerTake(core, me);
    try answer(core, write_id, "{\"bytes_written\":6}");
    // Duplicate response: the pending request is gone.
    try receiveFmt(core, "{{\"type\":\"response\",\"id\":\"{d}\",\"payload\":{{\"view\":{s}}}}}", .{ attach_id, reattached });
    try testing.expectEqual(@as(usize, 1), queued(core, terminal_write));
    try testing.expectEqual(@as(u32, 1), streamRestarts(core, term_block));
    try testing.expectEqual(@as(usize, 0), naming(core, "\"session:x1\""));
    try testing.expectEqual(@as(usize, 0), recorder.errors.items.len);
}

fn streamRestarts(core: *Core, block: []const u8) u32 {
    core.lock.lock();
    defer core.lock.unlock();
    return core.entries.get(block).?.stream.?.restarts.load(.monotonic);
}

test "fence: a superseded reattach drops the old pane's buffered input" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    const a = arena.allocator();
    const core = try fencedList(&recorder, a, "session:m1", &.{ "m1", "m2" });
    defer core.destroy();
    var buf: [1024]u8 = undefined;
    const view = try sizeView(&buf, 1, null);
    const first = try std.mem.replaceOwned(u8, a, view, "session:a", "session:m1");
    const second = try std.mem.replaceOwned(u8, a, view, "session:a", "session:m2");
    try answerAttach(core, first);
    core.lock.lock();
    core.linkEnded();
    core.lock.unlock();
    try answerFencedBootstrap(core, a, &.{ "m1", "m2" });
    const old_id = try requestId(core, "session.attach", "session:m1");
    core.command("{\"op\":\"sendText\",\"block\":\"" ++ term_block ++ "\",\"text\":\"old session\"}");

    // Reuse the block id in the new view to check that the buffer belongs to the
    // old entry, even when the new session has an entry under the same key.
    core.selectSession("session:m2");
    const new_id = try requestId(core, "session.attach", "session:m2");
    try receiveFmt(core, "{{\"type\":\"response\",\"id\":\"{d}\",\"payload\":{{\"view\":{s}}}}}", .{ new_id, second });
    try receiveFmt(core, "{{\"type\":\"response\",\"id\":\"{d}\",\"payload\":{{\"view\":{s}}}}}", .{ old_id, first });
    try testing.expectEqualStrings("session:m2", try recorder.lastString(a, "session"));
    try testing.expectEqual(@as(usize, 0), queued(core, terminal_write));
    try testing.expectEqual(@as(u32, 0), streamRestarts(core, term_block));

    core.command("{\"op\":\"sendText\",\"block\":\"" ++ term_block ++ "\",\"text\":\"new session\"}");
    try testing.expectEqual(@as(usize, 1), queued(core, terminal_write));
    try expectRequest(core, terminal_write,
        "{\"session_id\":\"session:m2\",\"block_id\":\"" ++ term_block ++ "\",\"args\":{\"data\":\"new session\"}}");
    try testing.expectEqual(@as(usize, 0), recorder.errors.items.len);
}

test "fence: no prefix changes nothing" {
    var plain: Recorder = .{};
    defer plain.deinit();
    var fenced: Recorder = .{};
    defer fenced.deinit();
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    const a = arena.allocator();

    const one = try following(&plain, a);
    defer one.destroy();
    const two = try Core.create(testing.allocator, fenced.host(), .{}, 1);
    defer two.destroy();
    two.command("{\"op\":\"fence\",\"prefix\":\"\"}");
    const id = registerBootstrap(two);
    try receiveFmt(two,
        \\{{"type":"response","id":"{d}","payload":{{"attach_all":{{"sessions":[
        \\{{"session_id":"session:a","label":"a"}},{{"session_id":"session:b","label":"b"}}]}},
        \\"selected_session":null,"data_tickets":[]}}}}
    , .{id});
    try answerAttach(two, try tabsView(a, "session:a", 1, "window:a1", &.{.{ .window = "window:a1", .blocks = &.{"block:a1"}, .focused = "block:a1" }}, false));
    try answer(two, try requestId(two, view_method, "\"session:b\""), try viewOfB(a, 7));
    // Sent again while connected, in both spellings: still nothing.
    two.command("{\"op\":\"fence\"}");
    two.command("{\"op\":\"fence\",\"prefix\":\"\"}");

    // The same requests, in the same order, and the same state.
    one.lock.lock();
    two.lock.lock();
    defer one.lock.unlock();
    defer two.lock.unlock();
    try testing.expectEqual(one.outbox.items.len, two.outbox.items.len);
    for (one.outbox.items, two.outbox.items) |x, y| try testing.expectEqualStrings(x, y);
    try testing.expectEqualStrings(plain.states.items[plain.states.items.len - 1], fenced.states.items[fenced.states.items.len - 1]);
    try testing.expectEqual(@as(usize, 0), fenced.errors.items.len);
}


// ------------------------------------------------- terminal size: live server

const live_label = "velo-size-test";

fn liveUrl() ![]const u8 {
    const value = std.mem.span(std.c.getenv("VELO_URL") orelse return error.SkipZigTest);
    return if (value.len == 0) error.SkipZigTest else value;
}

/// A control connection that sends one request at a time and waits for its answer.
const Live = struct {
    sock: *ws_mod.WebSocket,
    arena: std.heap.ArenaAllocator,
    client_id: []const u8 = "",
    next_id: u64 = 1,
    /// When set (after `open`), the sessions of this test are those whose label starts
    /// with it, instead of those labelled `live_label`.
    prefix: ?[]const u8 = null,
    /// While set, `call` counts the events of this session it reads past in `seen`.
    watch: ?[]const u8 = null,
    seen: Seen = .{},

    fn owns(self: *const Live, label: []const u8) bool {
        return if (self.prefix) |p| std.mem.startsWith(u8, label, p) else std.mem.eql(u8, label, live_label);
    }

    fn open(self: *Live, url: []const u8) !void {
        const gpa = testing.allocator;
        self.* = .{
            .sock = try ws_mod.WebSocket.connect(gpa, testing.io, url, "/api/control", protocol.control_protocol, .{}),
            .arena = .init(gpa),
        };
        errdefer self.close();
        const hello = try protocol.hello(gpa, "size-test", null);
        defer gpa.free(hello);
        try self.sock.sendText(hello);
        const first = (try self.sock.recv()) orelse return error.ClosedBeforeWelcome;
        switch (try protocol.parse(self.arena.allocator(), first.data)) {
            .welcome => |w| self.client_id = w.client_id,
            else => return error.NoWelcome,
        }
        try testing.expect(self.client_id.len > 0);
    }

    fn close(self: *Live) void {
        self.sock.close();
        self.sock.deinit();
        self.arena.deinit();
    }

    /// Send `method` with `payload` (freed here) and return its result, which lives until
    /// `close`. An error answer fails the call.
    fn call(self: *Live, method: []const u8, payload: std.mem.Allocator.Error![]u8) !std.json.Value {
        const gpa = testing.allocator;
        const args = try payload;
        defer gpa.free(args);
        const id = self.next_id;
        self.next_id += 1;
        const text = try protocol.request(gpa, id, method, args);
        defer gpa.free(text);
        try self.sock.sendText(text);
        while (true) {
            const message = (try self.sock.recv()) orelse return error.ClosedBeforeAnswer;
            if (message.opcode != .text) continue;
            switch (protocol.parse(self.arena.allocator(), message.data) catch continue) {
                .response => |r| if (r.id == id) {
                    if (r.err) |e| {
                        std.debug.print("{s} failed: {s} ({s})\n", .{ method, e.code, e.message });
                        return error.LiveCallFailed;
                    }
                    return r.payload orelse .null;
                },
                .event => |e| if (self.watch) |w| {
                    if (std.mem.eql(u8, e.session orelse "", w)) self.seen.count(e.kind, model.str(model.get(e.payload, "name")) orelse "");
                },
                else => {},
            }
        }
    }

    fn none() std.mem.Allocator.Error![]u8 {
        return &.{};
    }

    /// Fails unless `session` is on the server with this test's label. The server has
    /// sessions in use: no request of these tests may name one of them.
    fn mine(self: *Live, session: []const u8) !void {
        const list = try self.call("session.list", none());
        for (model.items(model.get(list, "sessions"))) |s| {
            if (!std.mem.eql(u8, model.str(model.get(s, "session_id")) orelse "", session)) continue;
            const label = model.str(model.get(s, "label")) orelse "";
            if (self.owns(label)) return;
            std.debug.print("REFUSED: {s} is labelled '{s}', not '{s}'\n", .{ session, label, self.prefix orelse live_label });
            return error.NotMySession;
        }
        std.debug.print("REFUSED: {s} is not on the server\n", .{session});
        return error.NotMySession;
    }

    /// `call` for a request that names `session`: sent only when the session is `mine`.
    fn callOn(self: *Live, session: []const u8, method: []const u8, payload: std.mem.Allocator.Error![]u8) !std.json.Value {
        self.mine(session) catch |err| {
            if (payload) |text| testing.allocator.free(text) else |_| {}
            return err;
        };
        return self.call(method, payload);
    }

    /// Destroy sessions an earlier run of these tests left behind.
    fn sweep(self: *Live) !void {
        const list = try self.call("session.list", none());
        for (model.items(model.get(list, "sessions"))) |s| {
            if (!self.owns(model.str(model.get(s, "label")) orelse "")) continue;
            self.destroy(model.str(model.get(s, "session_id")) orelse continue);
        }
    }

    const Created = struct { session: []const u8, block: []const u8 };

    /// A new session with one shell, attached. The caller destroys it on every path.
    fn create(self: *Live) !Created {
        return self.createLabelled(live_label);
    }

    fn createLabelled(self: *Live, label: []const u8) !Created {
        if (!self.owns(label)) return error.NotMySession;
        const made = try self.call(protocol.methods.createSession, protocol.createSession(testing.allocator, label));
        const session = model.str(model.get(made, "session_id")) orelse return error.NoSession;
        errdefer self.destroy(session);
        const windows = model.items(model.get(made, "initial_windows"));
        const blocks = if (windows.len > 0) model.items(model.get(windows[0], "block_ids")) else &.{};
        const block = if (blocks.len > 0) model.str(blocks[0]) orelse return error.NoBlock else return error.NoBlock;
        _ = try self.callOn(session, "session.attach", protocol.sessionView(testing.allocator, session));
        return .{ .session = session, .block = block };
    }

    fn destroy(self: *Live, session: []const u8) void {
        const gpa = testing.allocator;
        // Only an attached connection may destroy a session.
        _ = self.callOn(session, "session.attach", protocol.sessionView(gpa, session)) catch {};
        _ = self.callOn(session, "session.destroy", protocol.sessionView(gpa, session)) catch |err|
            std.debug.print("LEFT BEHIND: {s} ({s}) on the server: {s}\n", .{ session, live_label, @errorName(err) });
    }

    fn has(self: *Live, session: []const u8) !bool {
        const list = try self.call("session.list", none());
        for (model.items(model.get(list, "sessions"))) |s| {
            if (std.mem.eql(u8, model.str(model.get(s, "session_id")) orelse "", session)) return true;
        }
        return false;
    }

    /// `destroy`, for a session the test may have closed itself.
    fn remove(self: *Live, session: []const u8) void {
        if (self.has(session) catch true) self.destroy(session);
    }

    const TerminalSize = struct { cols: i64, rows: i64, cell_w: i64, cell_h: i64, owner: ?[]const u8 };

    fn size(self: *Live, session: []const u8, block: []const u8) !TerminalSize {
        const v = try self.callOn(session, protocol.methods.terminalSize, protocol.blockCall(testing.allocator, session, block));
        return .{
            .cols = model.int(model.get(v, "columns")) orelse return error.NoColumns,
            .rows = model.int(model.get(v, "rows")) orelse return error.NoRows,
            .cell_w = model.int(model.get(v, "cell_width_px")) orelse 0,
            .cell_h = model.int(model.get(v, "cell_height_px")) orelse 0,
            .owner = model.str(model.get(v, "owner")),
        };
    }

    /// Wait until the terminal has this size and owner; fails with the last answer.
    fn expectSize(self: *Live, session: []const u8, block: []const u8, cols: i64, rows: i64, owner: ?[]const u8) !TerminalSize {
        var last: TerminalSize = undefined;
        for (0..100) |_| {
            last = try self.size(session, block);
            const same_owner = if (owner) |o| (if (last.owner) |l| std.mem.eql(u8, l, o) else false) else last.owner == null;
            if (last.cols == cols and last.rows == rows and same_owner) return last;
            try std.Io.sleep(testing.io, .fromMilliseconds(100), .awake);
        }
        std.debug.print("terminal is {d}x{d} owned by {s}; expected {d}x{d} owned by {s}\n", .{
            last.cols, last.rows, last.owner orelse "nobody", cols, rows, owner orelse "nobody",
        });
        return error.TestExpectedEqual;
    }
};

/// Holds back a core's connections until the test has named the session to attach, so
/// that its bootstrap never asks the server to pick one (the server's pick is a session
/// in use). A test that ends before that refuses the connection instead.
const Gate = struct {
    lock: pane.Lock = .{},
    state: enum { closed, open, refused } = .closed,
    /// Calls of `dial` so far, including those held at the gate.
    entered: usize = 0,

    fn net(self: *Gate) core_mod.Net {
        return .{ .dialer = .{ .ctx = self, .dial = dial } };
    }

    fn dial(ctx: ?*anyopaque, _: []const u8, _: u16) anyerror!?std.posix.fd_t {
        const self: *Gate = @ptrCast(@alignCast(ctx.?));
        self.lock.lock();
        defer self.lock.unlock();
        self.entered += 1;
        while (self.state == .closed) self.lock.wait();
        // Null: connect on the system network.
        return if (self.state == .open) null else error.GateRefused;
    }

    fn set(self: *Gate, open: bool) void {
        self.lock.lock();
        defer self.lock.unlock();
        if (self.state != .closed) return;
        self.state = if (open) .open else .refused;
        self.lock.signal();
    }

    /// Wait up to `ms` for `n` calls of `dial`.
    fn waitEntered(self: *Gate, n: usize, ms: u32) !void {
        var waited: u32 = 0;
        while (true) : (waited += 5) {
            self.lock.lock();
            const have = self.entered;
            self.lock.unlock();
            if (have >= n) return;
            if (waited >= ms) return error.Timeout;
            try std.Io.sleep(testing.io, .fromMilliseconds(5), .awake);
        }
    }
};

/// Fails unless `core` is set to attach `session` and nothing else, ever, and `session`
/// carries this test's label. Checked before every action that makes the core send.
fn expectCoreOn(core: *Core, desk: *Live, session: []const u8) !void {
    try desk.mine(session);
    core.lock.lock();
    defer core.lock.unlock();
    try testing.expectEqualStrings(session, core.selected orelse "");
    try testing.expect(!core.auto_pick);
}

test "live: own a new session's terminal and resize it to 100x30" {
    const url = try liveUrl();
    const gpa = testing.allocator;
    var client: Live = undefined;
    try client.open(url);
    defer client.close();
    try client.sweep();

    const made = try client.create();
    defer client.destroy(made.session);
    const block = made.block;

    // The blocks of a new session have no owner.
    const before = try client.size(made.session, block);
    try testing.expectEqual(@as(?[]const u8, null), before.owner);

    const taken = try client.callOn(made.session, protocol.methods.ownBlock, protocol.ownBlock(gpa, made.session, block));
    const blocks = model.items(model.get(taken, "blocks"));
    try testing.expectEqual(@as(usize, 1), blocks.len);
    try testing.expectEqualStrings(block, model.str(model.get(blocks[0], "block_id")).?);
    try testing.expectEqualStrings(client.client_id, model.str(model.get(blocks[0], "owner")).?);

    _ = try client.callOn(made.session, protocol.methods.terminalResize, protocol.terminalResize(gpa, made.session, block, 100, 30, 9, 18));
    const after = try client.expectSize(made.session, block, 100, 30, client.client_id);
    try testing.expectEqual(@as(i64, 9), after.cell_w);
    try testing.expectEqual(@as(i64, 18), after.cell_h);
}

test "live: a pane the core shows sizes its terminal, and a touch takes it back from another client" {
    const url = try liveUrl();
    const gpa = testing.allocator;
    // Stands in for the desktop app.
    var desk: Live = undefined;
    try desk.open(url);
    defer desk.close();
    try desk.sweep();

    const made = try desk.create();
    defer desk.destroy(made.session);
    const block = made.block;

    var recorder: Recorder = .{};
    defer recorder.deinit();
    var gate: Gate = .{};
    const core = try Core.create(gpa, recorder.host(), gate.net(), 1);
    defer core.destroy();
    // Before `destroy`, which waits for a connect in progress.
    defer gate.set(false);
    // As a debug build of the app: no other session of the server exists for the core.
    core.command("{\"op\":\"fence\",\"prefix\":\"" ++ live_label ++ "\"}");
    core.connect(url, null);
    core.selectSession(made.session);
    {
        // If the session goes away the core attaches none, instead of the first listed.
        core.lock.lock();
        defer core.lock.unlock();
        core.auto_pick = false;
    }
    try expectCoreOn(core, &desk, made.session);
    gate.set(true);

    for (0..200) |_| {
        if (core.paneReady(block)) break;
        try std.Io.sleep(testing.io, .fromMilliseconds(100), .awake);
    } else return error.PaneNotReady;

    core.lock.lock();
    const phone = gpa.dupe(u8, core.client_id);
    core.lock.unlock();
    const phone_id = try phone;
    defer gpa.free(phone_id);
    try testing.expect(phone_id.len > 0);
    try testing.expect(!std.mem.eql(u8, phone_id, desk.client_id));

    // Attached, not shown: the core claimed nothing and asked for nothing.
    const untouched = try desk.size(made.session, block);
    try testing.expectEqual(@as(?[]const u8, null), untouched.owner);
    try testing.expect(untouched.cols != 125 or untouched.rows != 37);

    // The pane becomes shown: 1000 x 600 px of 8 x 16 px cells.
    try expectCoreOn(core, &desk, made.session);
    core.paneSurface(block, &fake_window, 1000, 600);
    const fitted = try desk.expectSize(made.session, block, 125, 37, phone_id);
    try testing.expectEqual(@as(i64, 8), fitted.cell_w);
    try testing.expectEqual(@as(i64, 16), fitted.cell_h);

    // The view settles at another size.
    try expectCoreOn(core, &desk, made.session);
    core.paneSurface(block, &fake_window, 800, 480);
    try commandFmt(core, "{{\"op\":\"fit\",\"block\":\"{s}\"}}", .{block});
    _ = try desk.expectSize(made.session, block, 100, 30, phone_id);

    // The desktop is used: it takes the block and the terminal has its size.
    _ = try desk.callOn(made.session, protocol.methods.ownBlock, protocol.ownBlock(gpa, made.session, block));
    _ = try desk.callOn(made.session, protocol.methods.terminalResize, protocol.terminalResize(gpa, made.session, block, 90, 25, 10, 20));
    _ = try desk.expectSize(made.session, block, 90, 25, desk.client_id);

    // The phone is used again: the take alone brings back the size it asked for last.
    try expectCoreOn(core, &desk, made.session);
    try commandFmt(core, "{{\"op\":\"touch\",\"block\":\"{s}\"}}", .{block});
    _ = try desk.expectSize(made.session, block, 100, 30, phone_id);

    recorder.lock.lock();
    defer recorder.lock.unlock();
    try testing.expectEqual(@as(usize, 0), recorder.errors.items.len);
}

// ------------------------------------------- sessions and tabs: live server

const core_prefix = "velo-test-core-";

/// What the published state must say about one session.
const LiveWant = struct {
    session: []const u8,
    gone: bool = false,
    tabs: ?usize = null,
    selected: ?bool = null,
    label: ?[]const u8 = null,
    /// Label of its first tab.
    first_tab: ?[]const u8 = null,
    /// Its first tab names the program of its lead terminal.
    program: bool = false,

    /// The session's tabs when the state says all of it.
    fn holds(want: LiveWant, root: std.json.Value) ?[]const std.json.Value {
        const entry = for (model.items(model.get(root, "sessions"))) |s| {
            if (std.mem.eql(u8, model.str(model.get(s, "id")) orelse "", want.session)) break s;
        } else return if (want.gone) &.{} else null;
        if (want.gone) return null;
        const tabs = model.items(model.get(entry, "tabs"));
        if (want.tabs) |n| if (tabs.len != n) return null;
        if (want.selected) |yes| if (std.mem.eql(u8, model.str(model.get(root, "session")) orelse "", want.session) != yes) return null;
        if (want.label) |l| if (!std.mem.eql(u8, model.str(model.get(entry, "label")) orelse "", l)) return null;
        if (want.first_tab) |l| if (tabs.len == 0 or !std.mem.eql(u8, field(tabs[0], "label"), l)) return null;
        if (want.program) if (tabs.len == 0 or model.str(model.get(tabs[0], "program")) == null or field(tabs[0], "program").len == 0) return null;
        return tabs;
    }
};

/// The state published last, parsed into `a`.
fn lastState(recorder: *Recorder, a: std.mem.Allocator) !std.json.Value {
    recorder.lock.lock();
    defer recorder.lock.unlock();
    if (recorder.states.items.len == 0) return error.NoState;
    return std.json.parseFromSliceLeaky(std.json.Value, a, recorder.states.items[recorder.states.items.len - 1], .{ .allocate = .alloc_always });
}

/// Wait until the published state says `want`; returns the session's tabs (in `a`).
fn expectLive(recorder: *Recorder, a: std.mem.Allocator, want: LiveWant) ![]const std.json.Value {
    var scratch: std.heap.ArenaAllocator = .init(testing.allocator);
    defer scratch.deinit();
    for (0..300) |_| {
        _ = scratch.reset(.retain_capacity);
        const seen = if (lastState(recorder, scratch.allocator())) |root| want.holds(root) != null else |_| false;
        if (seen) if (want.holds(try lastState(recorder, a))) |tabs| return tabs;
        try std.Io.sleep(testing.io, .fromMilliseconds(100), .awake);
    }
    recorder.lock.lock();
    defer recorder.lock.unlock();
    const states = recorder.states.items;
    std.debug.print("the state never said {any} of {s}; last state:\n{s}\n", .{ want, want.session, if (states.len > 0) states[states.len - 1] else "(none)" });
    return error.TestExpectedEqual;
}

/// If its session goes away the core attaches none, instead of a neighbour: on this
/// server the neighbours are sessions in use. A command that selects a session lifts it.
fn pin(core: *Core) void {
    core.lock.lock();
    defer core.lock.unlock();
    core.auto_pick = false;
}

/// A command that names `session`: sent only when the session carries this test's label.
fn liveCommand(core: *Core, desk: *Live, session: []const u8, comptime fmt: []const u8, args: anytype) !void {
    try desk.mine(session);
    try commandFmt(core, fmt, args);
    pin(core);
}

/// Label prefix of sessions that stand in for someone else's on the test server: made
/// and removed by the desk, never to be named by the core under test.
const live_other = "velo-test-cx-";

/// Fence the core under test to this test's label prefix, as a debug build of the app
/// does, before the connection opens: the core then names no other session of the
/// server, not even to read a view.
fn fenceOthers(core: *Core) void {
    core.command("{\"op\":\"fence\",\"prefix\":\"" ++ core_prefix ++ "\"}");
}

test "live: the core lists every session's tabs; tabs and sessions are made, renamed and closed by session" {
    const url = try liveUrl();
    const gpa = testing.allocator;
    var arena: std.heap.ArenaAllocator = .init(gpa);
    defer arena.deinit();
    const a = arena.allocator();
    // Makes the sessions, guards every request by their label, and cleans up.
    var desk: Live = undefined;
    try desk.open(url);
    defer desk.close();
    desk.prefix = core_prefix;
    try desk.sweep();

    // Stands in for a session in use: listed first, without this test's prefix, and
    // never to be named by the core under test. (The desk makes and removes it.)
    desk.prefix = live_other;
    try desk.sweep();
    const off = try desk.createLabelled(live_other ++ "in-use");
    defer {
        desk.prefix = live_other;
        desk.remove(off.session);
    }
    desk.prefix = core_prefix;
    const one = try desk.createLabelled(core_prefix ++ "a");
    defer desk.remove(one.session);
    const two = try desk.createLabelled(core_prefix ++ "b");
    defer desk.remove(two.session);

    var recorder: Recorder = .{};
    defer recorder.deinit();
    var gate: Gate = .{};
    const core = try Core.create(gpa, recorder.host(), gate.net(), 1);
    defer core.destroy();
    // Before `destroy`, which waits for a connect in progress.
    defer gate.set(false);
    fenceOthers(core);
    std.debug.print("fenced out: {s}\nmade: {s} {s}\n", .{ off.session, one.session, two.session });
    core.connect(url, null);
    core.selectSession(one.session);
    pin(core);
    try expectCoreOn(core, &desk, one.session);
    gate.set(true);

    // Both sessions with their one tab and its program: the first is attached, the
    // second only followed.
    const own = try expectLive(&recorder, a, .{ .session = one.session, .tabs = 1, .selected = true, .program = true });
    const first = try expectLive(&recorder, a, .{ .session = two.session, .tabs = 1, .selected = false, .program = true });
    try testing.expectEqualStrings(one.block, field(own[0], "block"));
    try testing.expectEqualStrings(two.block, field(first[0], "block"));
    try testing.expectEqual(@as(?i64, 1), model.int(model.get(first[0], "panes")));
    try testing.expectEqual(true, model.boolean(model.get(first[0], "active")).?);

    // Two more tabs in the second session: it becomes the selected one.
    try liveCommand(core, &desk, two.session, "{{\"op\":\"newWindow\",\"session\":\"{s}\"}}", .{two.session});
    _ = try expectLive(&recorder, a, .{ .session = two.session, .tabs = 2, .selected = true });
    try liveCommand(core, &desk, two.session, "{{\"op\":\"newWindow\",\"session\":\"{s}\"}}", .{two.session});
    const three = try expectLive(&recorder, a, .{ .session = two.session, .tabs = 3, .selected = true });
    _ = try expectLive(&recorder, a, .{ .session = one.session, .tabs = 1, .selected = false, .program = true });
    // One tab is active, a new one, and the top-level fields are this session's.
    var active: ?[]const u8 = null;
    for (three) |tab| if (model.boolean(model.get(tab, "active")) orelse false) {
        try testing.expectEqual(@as(?[]const u8, null), active);
        active = field(tab, "id");
    };
    try testing.expect(active != null);
    try testing.expect(!std.mem.eql(u8, active.?, field(first[0], "id")));
    try testing.expectEqualStrings(field(first[0], "id"), field(three[0], "id"));
    try testing.expectEqualStrings(active.?, try recorder.lastString(a, "window"));
    try testing.expectEqual(@as(usize, 3), model.items(try recorder.lastValue(a, "windows")).len);

    // Back to the first session by its tab; the second is followed again.
    try liveCommand(core, &desk, one.session, "{{\"op\":\"selectWindow\",\"session\":\"{s}\",\"window\":\"{s}\"}}", .{ one.session, field(own[0], "id") });
    _ = try expectLive(&recorder, a, .{ .session = one.session, .tabs = 1, .selected = true });
    _ = try expectLive(&recorder, a, .{ .session = two.session, .tabs = 3, .selected = false });

    // A tab of the session that is not selected closes.
    try liveCommand(core, &desk, two.session, "{{\"op\":\"closeWindow\",\"session\":\"{s}\",\"window\":\"{s}\"}}", .{ two.session, field(three[1], "id") });
    const left = try expectLive(&recorder, a, .{ .session = two.session, .tabs = 2, .selected = false });
    try testing.expectEqualStrings(field(three[0], "id"), field(left[0], "id"));
    try testing.expectEqualStrings(field(three[2], "id"), field(left[1], "id"));

    // New names reach the state; the session keeps this test's prefix.
    try liveCommand(core, &desk, two.session, "{{\"op\":\"renameWindow\",\"session\":\"{s}\",\"window\":\"{s}\",\"label\":\"velo tab\"}}", .{ two.session, field(left[0], "id") });
    _ = try expectLive(&recorder, a, .{ .session = two.session, .tabs = 2, .first_tab = "velo tab" });
    try liveCommand(core, &desk, two.session, "{{\"op\":\"renameSession\",\"session\":\"{s}\",\"label\":\"" ++ core_prefix ++ "renamed\"}}", .{two.session});
    _ = try expectLive(&recorder, a, .{ .session = two.session, .label = core_prefix ++ "renamed", .first_tab = "velo tab" });

    // The session that is not selected closes; the selected one stays.
    try liveCommand(core, &desk, two.session, "{{\"op\":\"closeSession\",\"session\":\"{s}\"}}", .{two.session});
    _ = try expectLive(&recorder, a, .{ .session = two.session, .gone = true });
    _ = try expectLive(&recorder, a, .{ .session = one.session, .tabs = 1, .selected = true });
    // The selected one closes: pinned, the core selects none.
    try liveCommand(core, &desk, one.session, "{{\"op\":\"closeSession\",\"session\":\"{s}\"}}", .{one.session});
    _ = try expectLive(&recorder, a, .{ .session = one.session, .gone = true });
    try testing.expectEqual(@as(?[]const u8, null), model.str(recorder.lastValue(a, "session") catch .null));
    try testing.expect(!try desk.has(one.session));
    try testing.expect(!try desk.has(two.session));

    // The session without the prefix: selecting it and closing it through the core does
    // nothing, and no state ever listed it.
    core.selectSession(off.session);
    try commandFmt(core, "{{\"op\":\"closeSession\",\"session\":\"{s}\"}}", .{off.session});
    try commandFmt(core, "{{\"op\":\"newWindow\",\"session\":\"{s}\"}}", .{off.session});
    pin(core);
    try std.Io.sleep(testing.io, .fromMilliseconds(500), .awake);
    desk.prefix = live_other;
    try testing.expect(try desk.has(off.session));
    const view = try desk.callOn(off.session, protocol.methods.sessionView, protocol.sessionView(gpa, off.session));
    try testing.expectEqual(@as(usize, 1), model.items(model.get(view, "windows")).len);

    recorder.lock.lock();
    defer recorder.lock.unlock();
    for (recorder.states.items) |state| try testing.expect(std.mem.find(u8, state, off.session) == null);
    try testing.expectEqual(@as(usize, 0), recorder.errors.items.len);
}

/// Events of one session, by what the core does with them.
const Seen = struct {
    process: usize = 0,
    status: usize = 0,
    title: usize = 0,
    view: usize = 0,
    other: usize = 0,

    fn count(self: *Seen, kind: []const u8, name: []const u8) void {
        const slot = if (std.mem.eql(u8, kind, "session_view_changed"))
            &self.view
        else if (!std.mem.eql(u8, kind, "block_event"))
            &self.other
        else if (std.mem.eql(u8, name, "process_changed"))
            &self.process
        else if (std.mem.startsWith(u8, name, "program_status_"))
            &self.status
        else if (std.mem.eql(u8, name, "title_changed"))
            &self.title
        else
            &self.other;
        slot.* += 1;
    }

    fn total(self: Seen) usize {
        return self.process + self.status + self.title + self.view + self.other;
    }
};

const Cost = struct { requests: u64, states: usize };

fn costNow(core: *Core, recorder: *Recorder) Cost {
    core.lock.lock();
    const requests = core.next_id;
    core.lock.unlock();
    recorder.lock.lock();
    defer recorder.lock.unlock();
    return .{ .requests = requests, .states = recorder.states.items.len };
}

test "live: a loud terminal of a session that is not selected costs the core no more requests than it sends events" {
    const url = try liveUrl();
    const gpa = testing.allocator;
    var arena: std.heap.ArenaAllocator = .init(gpa);
    defer arena.deinit();
    const a = arena.allocator();
    var desk: Live = undefined;
    try desk.open(url);
    defer desk.close();
    desk.prefix = core_prefix;

    const quiet = try desk.createLabelled(core_prefix ++ "quiet");
    defer desk.remove(quiet.session);
    const loud = try desk.createLabelled(core_prefix ++ "loud");
    defer desk.remove(loud.session);

    var recorder: Recorder = .{};
    defer recorder.deinit();
    var gate: Gate = .{};
    const core = try Core.create(gpa, recorder.host(), gate.net(), 1);
    defer core.destroy();
    defer gate.set(false);
    fenceOthers(core);
    core.connect(url, null);
    core.selectSession(quiet.session);
    pin(core);
    try expectCoreOn(core, &desk, quiet.session);
    gate.set(true);
    _ = try expectLive(&recorder, a, .{ .session = quiet.session, .tabs = 1, .selected = true, .program = true });
    _ = try expectLive(&recorder, a, .{ .session = loud.session, .tabs = 1, .selected = false, .program = true });

    // Output only, then 300 short programs one after another.
    const loads = [_][]const u8{ "seq 1 300000\n", "for i in $(seq 1 300); do /bin/echo -n; done\n" };
    for (loads) |load| {
        // Nothing of the start is still on its way: the shells have settled and the core
        // has sent nothing for a second and a half.
        var idle: usize = 0;
        var sent_before: u64 = 0;
        for (0..200) |_| {
            core.lock.lock();
            const waiting = core.pending.count();
            const next = core.next_id;
            core.lock.unlock();
            idle = if (waiting == 0 and next == sent_before) idle + 1 else 0;
            sent_before = next;
            if (idle >= 15) break;
            try std.Io.sleep(testing.io, .fromMilliseconds(100), .awake);
        }
        const before = costNow(core, &recorder);
        desk.watch = loud.session;
        desk.seen = .{};
        _ = try desk.callOn(loud.session, protocol.methods.terminalWrite, protocol.terminalWrite(gpa, loud.session, loud.block, load));
        // Until the terminal has said nothing for a second, at least three seconds.
        var still: usize = 0;
        var last: usize = 0;
        for (0..200) |i| {
            try std.Io.sleep(testing.io, .fromMilliseconds(100), .awake);
            _ = try desk.call("session.list", Live.none());
            const now = desk.seen.total();
            still = if (now == last) still + 1 else 0;
            last = now;
            if (i >= 30 and still >= 10) break;
        }
        desk.watch = null;
        const after = costNow(core, &recorder);
        const seen = desk.seen;
        const requests = after.requests - before.requests;
        std.debug.print("loud terminal `{s}`: events process={d} status={d} title={d} view={d} other={d}; the core sent {d} requests and published {d} states\n", .{
            std.mem.trimEnd(u8, load, "\n"), seen.process, seen.status, seen.title, seen.view, seen.other, requests, after.states - before.states,
        });
        try testing.expect(requests <= seen.process + seen.status + seen.view);
    }

    recorder.lock.lock();
    defer recorder.lock.unlock();
    try testing.expectEqual(@as(usize, 0), recorder.errors.items.len);
}

test "pane subscription resyncs non-lead records on reconnect before delivering live events" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    const core = try following(&recorder, arena.allocator());
    defer core.destroy();
    const record =
        \\{"id":"worker","record":{"state":"blocked","kind":"question","updated_at":"2026-11-20T10:03:00Z","title":"Release review","msg":"Which branch ships?"}}
    ;
    try blockEvent(core, "session:b", "block:b2", "program_status_changed", record);
    try testing.expectEqual(0, recorder.reports.items.len);
    core.command(watch_b2);
    try answer(core, (try pendingId(core, .pane_baseline)).?, "{\"records\":[]}");
    const start = sent(core);
    try blockEvent(core, "session:a", "block:b2", "program_status_changed", record);
    try testing.expectEqual(1, recorder.reports.items.len);
    try blockEvent(core, "session:b", "block:b2", "program_status_changed", record);
    try testing.expectEqual(2, recorder.reports.items.len);
    try testing.expectEqual(start, sent(core));

    try reconnect(core, me);
    const baseline_id = (try pendingId(core, .pane_baseline)).?;
    try testing.expectEqual(baseline_id, try requestId(core, protocol.methods.terminalProgramStatus, "\"block:b2\""));
    try answer(core, try requestId(core, view_method, "\"session:b\""), try tabsView(arena.allocator(), "session:b", 8, "window:b2", &.{
        .{ .window = "window:b2", .blocks = &.{ "block:b2", "block:b3" }, .focused = "block:b3" },
    }, true));
    try blockEvent(core, "session:b", "block:b2", "program_status_changed",
        \\{"id":"worker","record":{"state":"working","updated_at":"2026-11-20T10:02:00Z"}}
    );
    try blockEvent(core, "session:b", "block:b2", "program_status_changed", record);
    try testing.expectEqual(2, recorder.reports.items.len);
    try answer(core, baseline_id,
        \\{"records":[{"id":"worker","state":"blocked","updated_at":"2026-11-20T10:03:00Z","message":"private"}]}
    );
    try testing.expectEqual(3, recorder.reports.items.len);
    const baseline = try std.json.parseFromSliceLeaky(std.json.Value, arena.allocator(), recorder.reports.items[2], .{});
    const observed = model.items(model.get(baseline, "baseline"))[0];
    try testing.expectEqualStrings("worker", field(observed, "id"));
    try testing.expectEqualStrings("blocked", field(observed, "state"));
    try testing.expectEqualStrings("2026-11-20T10:03:00Z", field(observed, "updatedAt"));
    try testing.expect(model.get(observed, "message") == null);
    const pending = model.items(model.get(baseline, "pending"));
    try testing.expectEqual(2, pending.len);
    try testing.expectEqualStrings("working", field(pending[0], "state"));
    const live = pending[1];
    try testing.expectEqualStrings("window:b2", field(model.get(live, "target").?, "window"));
    try testing.expectEqualStrings("blocked", field(live, "state"));
    try testing.expectEqualStrings("question", field(live, "kind"));
    try testing.expectEqualStrings("Release review", field(live, "title"));
    try testing.expectEqualStrings("Which branch ships?", field(live, "msg"));
    try testing.expectEqual(0, recorder.writes.items.len);
}

const watch_b2 =
    \\{"op":"paneWatches","panes":[{"session":"session:b","window":"window:b2","block":"block:b2"}]}
;

test "parked alerts check the latest tab and discard moved or unsubscribed panes" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    const a = arena.allocator();
    const core = try following(&recorder, a);
    defer core.destroy();
    core.command(watch_b2);
    try answer(core, (try pendingId(core, .pane_baseline)).?, "{\"records\":[]}");
    core.command("{\"op\":\"detach\"}");
    const at_original = try tabsView(a, "session:b", 7, "window:b2", &.{.{ .window = "window:b2", .blocks = &.{"block:b2"} }}, true);
    const moved = try tabsView(a, "session:b", 8, "window:b1", &.{.{ .window = "window:b1", .blocks = &.{"block:b2"} }}, true);
    const removed = "{\"ids\":[\"worker\"]}";
    try blockEvent(core, "session:b", "block:b2", "program_status_removed", removed);
    try testing.expectEqual(1, recorder.reports.items.len);
    try answer(core, (try pendingId(core, .pane_location)).?, at_original);
    try testing.expectEqual(2, recorder.reports.items.len);

    try blockEvent(core, "session:b", "block:b2", "program_status_changed", "{\"record\":{\"state\":\"blocked\"}}");
    try sessionEvent(core, "session:b", "session_view_changed", "{\"revision\":8}");
    try answer(core, (try pendingId(core, .pane_location)).?, at_original);
    try testing.expectEqual(2, recorder.reports.items.len);
    try answer(core, (try pendingId(core, .pane_location)).?, moved);
    try testing.expectEqual(2, recorder.reports.items.len);

    try blockEvent(core, "session:b", "block:b2", "program_status_removed", removed);
    const pending = (try pendingId(core, .pane_location)).?;
    core.command("{\"op\":\"paneWatches\",\"panes\":[]}");
    try answer(core, pending, at_original);
    try testing.expectEqual(2, recorder.reports.items.len);
    try testing.expectEqual(0, counts(core).panes);
}

test "restorePane only restores the layer holding the exact hidden target" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    const core = try bootstrapped(&recorder);
    defer core.destroy();
    const attach_id = (try pendingId(core, .attach)).?;
    try answer(core, attach_id,
        \\{"view":{"session_id":"session:a","revision":1,"windows":[{"window_id":"w","layers":[
        \\{"kind":"floating","state":"minimized","layer_id":"layer:r","blocks":[{"block_id":"right"}]}]}]}}
    );
    core.command(
        \\{"op":"paneWatches","panes":[{"session":"session:a","window":"w","block":"right"}]}
    );
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    const windows = model.items(try recorder.lastValue(arena.allocator(), "windows"));
    const hidden = model.items(model.get(windows[0], "minimizedBlocks"));
    try testing.expectEqual(1, hidden.len);
    try testing.expectEqualStrings("right", model.str(hidden[0]).?);
    const before = sent(core);
    core.command("{\"op\":\"restorePane\",\"session\":\"session:b\",\"window\":\"w\",\"block\":\"right\"}");
    core.command("{\"op\":\"restorePane\",\"session\":\"session:a\",\"window\":\"moved\",\"block\":\"right\"}");
    core.command("{\"op\":\"restorePane\",\"session\":\"session:a\",\"window\":\"w\",\"block\":\"gone\"}");
    try testing.expectEqual(before, sent(core));
    core.command("{\"op\":\"restorePane\",\"session\":\"session:a\",\"window\":\"w\",\"block\":\"right\"}");
    try expectRequest(core, "session.set_layer_state", "{\"session_id\":\"session:a\",\"layer_id\":\"layer:r\",\"state\":\"normal\",\"focus\":false}");
    try testing.expectEqual(before + 1, sent(core));
}

const stash_tiled_a = "{\"kind\":\"tiled\",\"layer_id\":\"layer:t\",\"layout\":{\"block_id\":\"a\"},\"blocks\":[{\"block_id\":\"a\"}]}";

/// A bootstrapped core whose selected session `session:a` has one tab `w` made of `layers`
/// (the text between the brackets of `layers`).
fn stashView(recorder: *Recorder, comptime layers: []const u8) !*Core {
    const core = try bootstrapped(recorder);
    errdefer core.destroy();
    try answer(core, (try pendingId(core, .attach)).?, "{\"view\":{\"session_id\":\"session:a\",\"revision\":1,\"windows\":[{\"window_id\":\"w\",\"layers\":[" ++ layers ++ "]}]}}");
    return core;
}

const float_f_b = "{\"kind\":\"floating\",\"layer_id\":\"layer:f\",\"bounds\":[0.15,0.15,0.7,0.7],\"layout\":{\"block_id\":\"b\"},\"blocks\":[{\"block_id\":\"b\"}]}";

test "minimizePane minimizes a floating pane's layer" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    const core = try stashView(&recorder, stash_tiled_a ++ "," ++ float_f_b);
    defer core.destroy();
    const before = sent(core);
    core.command("{\"op\":\"minimizePane\",\"block\":\"b\"}");
    try expectRequest(core, "session.set_layer_state", "{\"session_id\":\"session:a\",\"layer_id\":\"layer:f\",\"state\":\"minimized\"}");
    try testing.expectEqual(before + 1, sent(core));
    try testing.expectEqual(0, recorder.errors.items.len);
}

const stash_tiled_ab =
    "{\"kind\":\"tiled\",\"layer_id\":\"layer:t\",\"layout\":{\"split\":{\"direction\":\"horizontal\",\"ratio\":0.5," ++
    "\"before\":{\"block_id\":\"a\"},\"after\":{\"block_id\":\"b\"}}},\"blocks\":[{\"block_id\":\"a\"},{\"block_id\":\"b\"}]}";

test "minimizePane floats a tiled pane, then minimizes the layer its answer names" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    const core = try stashView(&recorder, stash_tiled_ab);
    defer core.destroy();
    core.command("{\"op\":\"minimizePane\",\"block\":\"b\"}");
    try expectRequest(core, "session.float_block", "{\"session_id\":\"session:a\",\"block_id\":\"b\",\"bounds\":[0.15,0.15,0.7,0.7]}");
    const before = sent(core);
    try answer(core, (try pendingId(core, .stash_floated)).?, "{\"layer_id\":\"layer:n\",\"revision\":5}");
    try expectRequest(core, "session.set_layer_state", "{\"session_id\":\"session:a\",\"layer_id\":\"layer:n\",\"state\":\"minimized\"}");
    try testing.expectEqual(before + 1, sent(core));
    try testing.expectEqual(0, recorder.errors.items.len);
}

test "minimizePane leaves the pane floating and says so when the answer names no layer" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    const core = try stashView(&recorder, stash_tiled_ab);
    defer core.destroy();
    core.command("{\"op\":\"minimizePane\",\"block\":\"b\"}");
    const before = sent(core);
    try answer(core, (try pendingId(core, .stash_floated)).?, "{\"revision\":5}");
    try testing.expectEqual(before, sent(core));
    try testing.expectEqual(1, recorder.errors.items.len);
    try testing.expectEqualStrings("internal", recorder.errors.items[0]);
}

test "minimizePane sends nothing after a failed float" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    const core = try stashView(&recorder, stash_tiled_ab);
    defer core.destroy();
    core.command("{\"op\":\"minimizePane\",\"block\":\"b\"}");
    const before = sent(core);
    try answerError(core, (try pendingId(core, .stash_floated)).?, "not_found");
    try testing.expectEqual(before, sent(core));
    try testing.expectEqual(1, recorder.errors.items.len);
    try testing.expectEqualStrings("not_found", recorder.errors.items[0]);
}

test "minimizePane refuses the tab's last pane on screen" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    const core = try stashView(&recorder, stash_tiled_a ++
        ",{\"kind\":\"floating\",\"state\":\"minimized\",\"layer_id\":\"layer:m\",\"blocks\":[{\"block_id\":\"b\"}]}");
    defer core.destroy();
    const before = sent(core);
    core.command("{\"op\":\"minimizePane\",\"block\":\"a\"}");
    try testing.expectEqual(before, sent(core));
    try testing.expectEqual(1, recorder.errors.items.len);
    try testing.expectEqualStrings("bad_request", recorder.errors.items[0]);

    var floats_rec: Recorder = .{};
    defer floats_rec.deinit();
    const floats = try stashView(&floats_rec, "{\"kind\":\"tiled\",\"layer_id\":\"layer:t\",\"layout\":null,\"blocks\":[]}," ++
        "{\"kind\":\"floating\",\"layer_id\":\"layer:f\",\"bounds\":[0.15,0.15,0.7,0.7],\"layout\":{\"split\":{\"direction\":\"horizontal\"," ++
        "\"ratio\":0.5,\"before\":{\"block_id\":\"b\"},\"after\":{\"block_id\":\"c\"}}},\"blocks\":[{\"block_id\":\"b\"},{\"block_id\":\"c\"}]}");
    defer floats.destroy();
    const floats_before = sent(floats);
    floats.command("{\"op\":\"minimizePane\",\"block\":\"b\"}");
    try testing.expectEqual(floats_before, sent(floats));
    try testing.expectEqual(1, floats_rec.errors.items.len);
}

test "restorePane with focus raises the layer" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    const core = try stashView(&recorder, stash_tiled_a ++
        ",{\"kind\":\"floating\",\"state\":\"minimized\",\"layer_id\":\"layer:r\",\"blocks\":[{\"block_id\":\"right\"}]}");
    defer core.destroy();
    core.command("{\"op\":\"restorePane\",\"session\":\"session:a\",\"window\":\"w\",\"block\":\"right\",\"focus\":true}");
    try expectRequest(core, "session.set_layer_state", "{\"session_id\":\"session:a\",\"layer_id\":\"layer:r\",\"state\":\"normal\",\"focus\":true}");
}

test "minimizeFloating minimizes every floating layer, and shows every minimized one" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    const core = try stashView(&recorder, stash_tiled_a ++ "," ++ float_f_b ++
        ",{\"kind\":\"floating\",\"layer_id\":\"layer:f2\",\"bounds\":[0.1,0.1,0.5,0.5],\"layout\":{\"block_id\":\"c\"},\"blocks\":[{\"block_id\":\"c\"}]}");
    defer core.destroy();
    const before = sent(core);
    core.command("{\"op\":\"minimizeFloating\",\"window\":\"w\",\"minimize\":true}");
    try testing.expectEqual(before + 2, sent(core));
    try testing.expectEqual(2, queued(core, "session.set_layer_state"));
    try expectRequest(core, "session.set_layer_state", "{\"session_id\":\"session:a\",\"layer_id\":\"layer:f2\",\"state\":\"minimized\"}");
    core.command("{\"op\":\"minimizeFloating\",\"window\":\"gone\",\"minimize\":true}");
    try testing.expectEqual(before + 2, sent(core));

    // A view with two blocks in one minimized layer: one request.
    var reader: Recorder = .{};
    defer reader.deinit();
    const shown = try stashView(&reader, stash_tiled_a ++
        ",{\"kind\":\"floating\",\"state\":\"minimized\",\"layer_id\":\"layer:m1\",\"blocks\":[{\"block_id\":\"d\"},{\"block_id\":\"e\"}]}");
    defer shown.destroy();
    const shown_before = sent(shown);
    shown.command("{\"op\":\"minimizeFloating\",\"window\":\"w\",\"minimize\":false}");
    try expectRequest(shown, "session.set_layer_state", "{\"session_id\":\"session:a\",\"layer_id\":\"layer:m1\",\"state\":\"normal\",\"focus\":false}");
    try testing.expectEqual(shown_before + 1, sent(shown));
}

test "minimizeFloating minimizes nothing without a tiled pane" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    const core = try stashView(&recorder, "{\"kind\":\"tiled\",\"layer_id\":\"layer:t\",\"layout\":null,\"blocks\":[]}," ++ float_f_b);
    defer core.destroy();
    const before = sent(core);
    core.command("{\"op\":\"minimizeFloating\",\"window\":\"w\",\"minimize\":true}");
    try testing.expectEqual(before, sent(core));
}

test "restoring a floating-only pane creates one tab, moves the pane, and cleans only the temporary anchor" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    const core = try bootstrapped(&recorder);
    defer core.destroy();
    try answer(core, (try pendingId(core, .attach)).?,
        \\{"view":{"session_id":"session:a","revision":1,"windows":[{"window_id":"w","layers":[
        \\{"kind":"tiled","layer_id":"layer:t","layout":null,"blocks":[]},
        \\{"kind":"floating","layer_id":"layer:f","bounds":[0.15,0.15,0.7,0.7],
        \\"layout":{"block_id":"block:original"},"blocks":[{"block_id":"block:original"}]}]}]}}
    );

    const restore =
        \\{"op":"restoreInNewTab","block":"block:original"}
    ;
    core.command(restore);
    core.command(restore);
    try testing.expectEqual(1, queued(core, "session.new_window"));
    const create_id = (try pendingId(core, .dock_tab_created)).?;
    try answer(core, create_id,
        \\{"window_id":"window:new","block_ids":["block:temporary"],"revision":2}
    );
    try expectRequest(core, "session.move_block",
        \\{"session_id":"session:a","block_id":"block:original","anchor_block_id":"block:temporary","direction":"horizontal","side":"after"}
    );
    try testing.expectEqual(0, queued(core, "block.close"));
    const move_id = (try pendingId(core, .dock_tab_moved)).?;
    try answer(core, move_id,
        \\{"created_split_id":"split:temporary","revision":3}
    );
    try expectRequest(core, "block.close",
        \\{"session_id":"session:a","block_id":"block:temporary","keep_last":false}
    );
    try expectRequest(core, "session.focus_block",
        \\{"session_id":"session:a","block_id":"block:original"}
    );
    try testing.expectEqual(0, recorder.errors.items.len);
}

test "a failed float restoration closes only its unused anchor and never focuses another pane" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    const core = try bootstrapped(&recorder);
    defer core.destroy();
    try answer(core, (try pendingId(core, .attach)).?,
        \\{"view":{"session_id":"session:a","revision":1,"windows":[{"window_id":"w","layers":[
        \\{"kind":"floating","layer_id":"layer:f","bounds":[0.15,0.15,0.7,0.7],
        \\"layout":{"block_id":"block:original"},"blocks":[{"block_id":"block:original"}]}]}]}}
    );
    core.command(
        \\{"op":"restoreInNewTab","block":"block:original"}
    );
    try answer(core, (try pendingId(core, .dock_tab_created)).?,
        \\{"window_id":"window:new","block_ids":["block:temporary"],"revision":2}
    );
    try answerError(core, (try pendingId(core, .dock_tab_moved)).?, "not_found");
    try expectRequest(core, "block.close",
        \\{"session_id":"session:a","block_id":"block:temporary","keep_last":false}
    );
    try testing.expectEqual(0, queued(core, "session.focus_block"));
    try testing.expectEqualStrings("not_found", recorder.errors.items[0]);
}

// ------------------------------------------------------------- moving panes

/// Three tabs, in this order: `window:ab` splits `block:a` | `block:b` at 0.3, `window:d`
/// holds only `block:d` (labelled "logs" by its owner), and `window:c` holds `block:c` and
/// floats `block:f`.
const move_windows =
    \\"windows":[
    \\{"window_id":"window:ab","label":"ab","layers":[{"kind":"tiled","layer_id":"layer:ab","layout":
    \\{"split":{"direction":"horizontal","ratio":0.3,"before":{"block_id":"block:a"},"after":{"block_id":"block:b"}}},
    \\"blocks":[{"block_id":"block:a"},{"block_id":"block:b"}]}]},
    \\{"window_id":"window:d","label":"logs","layers":[
    \\{"kind":"tiled","layer_id":"layer:d","layout":{"block_id":"block:d"},"blocks":[{"block_id":"block:d"}]}]},
    \\{"window_id":"window:c","label":"c","layers":[
    \\{"kind":"tiled","layer_id":"layer:c","layout":{"block_id":"block:c"},"blocks":[{"block_id":"block:c"}]},
    \\{"kind":"floating","layer_id":"layer:f","bounds":[0.15,0.15,0.7,0.7],"layout":{"block_id":"block:f"},
    \\"blocks":[{"block_id":"block:f"}]}]}]}
;

/// The tabs of `move_windows` after `block:d` moved to the right of `block:c`: its tab closed.
const moved_d_windows =
    \\"windows":[
    \\{"window_id":"window:ab","label":"ab","layers":[{"kind":"tiled","layer_id":"layer:ab","layout":
    \\{"split":{"direction":"horizontal","ratio":0.3,"before":{"block_id":"block:a"},"after":{"block_id":"block:b"}}},
    \\"blocks":[{"block_id":"block:a"},{"block_id":"block:b"}]}]},
    \\{"window_id":"window:c","label":"c","layers":[{"kind":"tiled","layer_id":"layer:c","layout":
    \\{"split":{"direction":"horizontal","ratio":0.5,"before":{"block_id":"block:c"},"after":{"block_id":"block:d"}}},
    \\"blocks":[{"block_id":"block:c"},{"block_id":"block:d"}]},
    \\{"kind":"floating","layer_id":"layer:f","bounds":[0.15,0.15,0.7,0.7],"layout":{"block_id":"block:f"},
    \\"blocks":[{"block_id":"block:f"}]}]}]}
;

/// A `session.view` of `session:a` at `revision` with these tabs.
fn moveView(comptime revision: []const u8, comptime windows: []const u8) []const u8 {
    return "{\"session_id\":\"session:a\",\"label\":\"a\",\"revision\":" ++ revision ++ "," ++ windows;
}

/// A core attached to `session:a`, holding `move_windows` at revision 4.
fn withMoveView(recorder: *Recorder) !*Core {
    const core = try bootstrapped(recorder);
    errdefer core.destroy();
    try answer(core, (try pendingId(core, .attach)).?, "{\"view\":" ++ comptime moveView("4", move_windows) ++ "}");
    return core;
}

/// The server announces a new revision of `session:a` and answers the view request it causes.
fn viewChanged(core: *Core, comptime revision: []const u8, comptime windows: []const u8) !void {
    try sessionEvent(core, "session:a", "session_view_changed", "{\"revision\":" ++ revision ++ "}");
    try answer(core, (try pendingId(core, .view)) orelse return error.NoViewRequest, comptime moveView(revision, windows));
}

/// Answer the newest `method` request for `block` with `revision`, then the focus that follows it.
fn completeMove(core: *Core, comptime method: []const u8, comptime block: []const u8, comptime revision: []const u8) !void {
    const needle = "\"block_id\":\"" ++ block ++ "\"";
    try answer(core, try requestId(core, method, needle), "{\"created_split_id\":\"split:x\",\"revision\":" ++ revision ++ "}");
    try answer(core, try requestId(core, "session.focus_block", needle), "{\"revision\":" ++ revision ++ "}");
}

test "movePane sends move_block with the direction and side of each dir, then focus_block" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    const core = try withMoveView(&recorder);
    defer core.destroy();

    inline for (.{
        .{ "left", "\"direction\":\"horizontal\",\"side\":\"before\"" },
        .{ "right", "\"direction\":\"horizontal\",\"side\":\"after\"" },
        .{ "above", "\"direction\":\"vertical\",\"side\":\"before\"" },
        .{ "below", "\"direction\":\"vertical\",\"side\":\"after\"" },
    }) |case| {
        core.command("{\"op\":\"movePane\",\"block\":\"block:a\",\"anchor\":\"block:c\",\"dir\":\"" ++ case[0] ++ "\"}");
        try expectRequest(core, "session.move_block", "{\"session_id\":\"session:a\",\"block_id\":\"block:a\",\"anchor_block_id\":\"block:c\"," ++ case[1] ++ "}");
    }
    try testing.expectEqual(4, queued(core, "session.move_block"));
    // The pane is focused only once its move is done, so that its tab shows.
    try testing.expectEqual(0, queued(core, "session.focus_block"));
    try answer(core, try requestId(core, "session.move_block", "\"direction\":\"vertical\",\"side\":\"after\""),
        \\{"created_split_id":"split:x","revision":5}
    );
    try expectRequest(core, "session.focus_block",
        \\{"session_id":"session:a","block_id":"block:a"}
    );
    try testing.expectEqual(1, queued(core, "session.focus_block"));
    try testing.expectEqual(0, recorder.errors.items.len);
}

test "movePane refuses a pane that left the view, a floating anchor, and the same pane" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    const core = try withMoveView(&recorder);
    defer core.destroy();

    const before = sent(core);
    const refused = [_][]const u8{
        \\{"op":"movePane","block":"block:gone","anchor":"block:c","dir":"left"}
        ,
        \\{"op":"movePane","block":"block:a","anchor":"block:gone","dir":"left"}
        ,
        \\{"op":"movePane","block":"block:a","anchor":"block:f","dir":"left"}
        ,
        \\{"op":"movePane","block":"block:a","anchor":"block:a","dir":"left"}
        ,
    };
    for (refused, 1..) |op, n| {
        core.command(op);
        try testing.expectEqual(n, recorder.errors.items.len);
        try testing.expectEqualStrings("conflict", recorder.errors.items[n - 1]);
    }
    try testing.expectEqual(0, queued(core, "session.move_block"));
    try testing.expectEqual(before, sent(core));
}

test "swapPanes sends swap_blocks then focus_block" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    const core = try withMoveView(&recorder);
    defer core.destroy();

    // The other pane may float: the server swaps blocks of any layer.
    core.command(
        \\{"op":"swapPanes","block":"block:a","other":"block:f"}
    );
    try expectRequest(core, "session.swap_blocks",
        \\{"session_id":"session:a","block_id":"block:a","other_block_id":"block:f"}
    );
    try testing.expectEqual(0, queued(core, "session.focus_block"));
    try answer(core, try requestId(core, "session.swap_blocks", "block:a"), "{\"revision\":5}");
    try expectRequest(core, "session.focus_block",
        \\{"session_id":"session:a","block_id":"block:a"}
    );

    const before = sent(core);
    core.command(
        \\{"op":"swapPanes","block":"block:a","other":"block:a"}
    );
    core.command(
        \\{"op":"swapPanes","block":"block:a","other":"block:gone"}
    );
    try testing.expectEqual(before, sent(core));
    try testing.expectEqual(2, recorder.errors.items.len);
    for (recorder.errors.items) |code| try testing.expectEqualStrings("conflict", code);
}

test "moveToNewTab moves a tiled pane out of a tab that keeps another pane" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    const core = try withMoveView(&recorder);
    defer core.destroy();

    core.command(
        \\{"op":"moveToNewTab","block":"block:a"}
    );
    try testing.expectEqual(1, queued(core, "session.new_window"));
    _ = try requestId(core, "session.new_window", "\"focus\":false,\"layout\"");
    _ = try requestId(core, "session.new_window", "Temporary dock anchor");
    try answer(core, (try pendingId(core, .dock_tab_created)).?,
        \\{"window_id":"window:new","block_ids":["block:temporary"],"revision":5}
    );
    try expectRequest(core, "session.move_block",
        \\{"session_id":"session:a","block_id":"block:a","anchor_block_id":"block:temporary","direction":"horizontal","side":"after"}
    );
    try testing.expectEqual(0, queued(core, "block.close"));
    try answer(core, (try pendingId(core, .dock_tab_moved)).?,
        \\{"created_split_id":"split:temporary","revision":6}
    );
    try expectRequest(core, "block.close",
        \\{"session_id":"session:a","block_id":"block:temporary","keep_last":false}
    );
    try expectRequest(core, "session.focus_block",
        \\{"session_id":"session:a","block_id":"block:a"}
    );
    try testing.expectEqual(0, recorder.errors.items.len);
}

test "moveToNewTab refuses the only tiled pane of a tab" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    const core = try withMoveView(&recorder);
    defer core.destroy();

    const before = sent(core);
    core.command(
        \\{"op":"moveToNewTab","block":"block:d"}
    );
    try testing.expectEqual(before, sent(core));
    try testing.expectEqual(1, recorder.errors.items.len);
    try testing.expectEqualStrings("conflict", recorder.errors.items[0]);
}

test "undoMove beside sends move_block with the old ratio" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    const core = try withMoveView(&recorder);
    defer core.destroy();

    // `block:a` leaves its 0.3 split with `block:b` for the right of `block:c`.
    core.command(
        \\{"op":"movePane","block":"block:a","anchor":"block:c","dir":"right"}
    );
    try completeMove(core, "session.move_block", "block:a", "5");
    core.command(
        \\{"op":"undoMove","kind":"beside","block":"block:a","anchor":"block:b","dir":"left","ratio":0.3}
    );
    try expectRequest(core, "session.move_block",
        \\{"session_id":"session:a","block_id":"block:a","anchor_block_id":"block:b","direction":"horizontal","side":"before","ratio":0.3}
    );
    try testing.expectEqual(0, recorder.errors.items.len);

    // An Undo has no redo, however its answers come back.
    try completeMove(core, "session.move_block", "block:a", "6");
    const before = sent(core);
    core.command(
        \\{"op":"undoMove","kind":"beside","block":"block:a","anchor":"block:c","dir":"right","ratio":0.5}
    );
    try testing.expectEqual(before, sent(core));
    try testing.expectEqual(1, recorder.errors.items.len);
    try testing.expectEqualStrings("conflict", recorder.errors.items[0]);
}

test "undoMove is refused after another revision" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    const core = try withMoveView(&recorder);
    defer core.destroy();

    const undo =
        \\{"op":"undoMove","kind":"swap","block":"block:a","other":"block:f"}
    ;
    // No move has completed yet: there is nothing to undo.
    var before = sent(core);
    core.command(undo);
    try testing.expectEqual(before, sent(core));
    try testing.expectEqual(1, recorder.errors.items.len);

    core.command(
        \\{"op":"swapPanes","block":"block:a","other":"block:f"}
    );
    try completeMove(core, "session.swap_blocks", "block:a", "5");
    // Another client changed the session after the swap.
    try viewChanged(core, "6", move_windows);
    before = sent(core);
    core.command(undo);
    try testing.expectEqual(before, sent(core));
    try testing.expectEqual(2, recorder.errors.items.len);
    for (recorder.errors.items) |code| try testing.expectEqualStrings("conflict", code);
}

test "undoMove is refused after a session switch" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    const core = try withMoveView(&recorder);
    defer core.destroy();

    core.command(
        \\{"op":"swapPanes","block":"block:a","other":"block:f"}
    );
    try completeMove(core, "session.swap_blocks", "block:a", "5");
    // The owner looks at another session and comes back. The session is still at the revision the swap ended
    // with, so only the switch can refuse the Undo: the last move was in the view the switch dropped.
    core.selectSession("session:b");
    try answerAttach(core, "{\"session_id\":\"session:b\",\"label\":\"b\",\"revision\":1,\"windows\":[]}");
    core.selectSession("session:a");
    try answerAttach(core, comptime moveView("5", move_windows));
    const before = sent(core);
    core.command(
        \\{"op":"undoMove","kind":"swap","block":"block:a","other":"block:f"}
    );
    try testing.expectEqual(before, sent(core));
    try testing.expectEqual(1, queued(core, "session.swap_blocks"));
    try testing.expectEqual(1, recorder.errors.items.len);
    try testing.expectEqualStrings("conflict", recorder.errors.items[0]);
}

test "undoMove retab rebuilds the tab with its label and place" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    const core = try withMoveView(&recorder);
    defer core.destroy();

    // `block:d` was the only pane of its tab, which closes when it moves.
    core.command(
        \\{"op":"movePane","block":"block:d","anchor":"block:c","dir":"right"}
    );
    try completeMove(core, "session.move_block", "block:d", "5");
    try viewChanged(core, "5", moved_d_windows);

    core.command(
        \\{"op":"undoMove","kind":"retab","block":"block:d","label":"logs","before":"window:c"}
    );
    try testing.expectEqual(1, queued(core, "session.new_window"));
    try answer(core, (try pendingId(core, .dock_tab_created)).?,
        \\{"window_id":"window:new","block_ids":["block:temporary"],"revision":6}
    );
    try expectRequest(core, "session.move_block",
        \\{"session_id":"session:a","block_id":"block:d","anchor_block_id":"block:temporary","direction":"horizontal","side":"after"}
    );
    try testing.expectEqual(0, queued(core, "session.set_window_label"));
    try testing.expectEqual(0, queued(core, "session.move_window"));
    try answer(core, (try pendingId(core, .dock_tab_moved)).?,
        \\{"created_split_id":"split:temporary","revision":7}
    );
    try expectTail(core, &.{ "block.close", "session.focus_block", "session.set_window_label", "session.move_window" });
    try expectRequest(core, "session.set_window_label",
        \\{"session_id":"session:a","window_id":"window:new","label":"logs"}
    );
    try expectRequest(core, "session.move_window",
        \\{"session_id":"session:a","window_id":"window:new","before_window_id":"window:c"}
    );
    try testing.expectEqual(0, recorder.errors.items.len);
}

test "a pane move is not replayed after a reconnect" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    const core = try withMoveView(&recorder);
    defer core.destroy();

    core.command(
        \\{"op":"movePane","block":"block:a","anchor":"block:c","dir":"right"}
    );
    try testing.expectEqual(1, queued(core, "session.move_block"));

    // The link drops before the answer; a new connection attaches the same session.
    core.lock.lock();
    core.linkEnded();
    core.lock.unlock();
    try receiveFmt(core,
        \\{{"type":"response","id":"{d}","payload":{{"attach_all":{{"sessions":[
        \\{{"session_id":"session:a","label":"a"}},{{"session_id":"session:b","label":"b"}}]}},
        \\"attached_sessions":[{{"session":{{"session_id":"session:a","label":"a"}},"view":{s}}}],"data_tickets":[]}}}}
    , .{ registerBootstrap(core), comptime moveView("4", move_windows) });
    try testing.expectEqual(0, queued(core, "session.move_block"));
    try testing.expectEqual(0, queued(core, "session.focus_block"));

    // No move completed on this connection: there is nothing to undo.
    const before = sent(core);
    core.command(
        \\{"op":"undoMove","kind":"beside","block":"block:a","anchor":"block:b","dir":"left","ratio":0.3}
    );
    try testing.expectEqual(before, sent(core));
    try testing.expectEqual(1, recorder.errors.items.len);
    try testing.expectEqualStrings("conflict", recorder.errors.items[0]);
}

// ------------------------------------------------------------- moving tabs

test "moveWindow sends move_window before a tab, or to the end" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    const core = try withMoveView(&recorder);
    defer core.destroy();

    core.command(
        \\{"op":"moveWindow","session":"session:a","window":"window:c","before":"window:ab"}
    );
    try expectRequest(core, "session.move_window",
        \\{"session_id":"session:a","window_id":"window:c","before_window_id":"window:ab"}
    );
    core.command(
        \\{"op":"moveWindow","session":"session:a","window":"window:ab"}
    );
    try expectRequest(core, "session.move_window",
        \\{"session_id":"session:a","window_id":"window:ab"}
    );
    try testing.expectEqual(2, queued(core, "session.move_window"));
    try testing.expectEqual(0, recorder.errors.items.len);
}

test "a moved tab's newer revision asks for the view once" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    const core = try withMoveView(&recorder);
    defer core.destroy();

    const views = queued(core, "session.view");
    core.command(
        \\{"op":"moveWindow","session":"session:a","window":"window:c","before":"window:ab"}
    );
    try answer(core, (try pendingId(core, .window_moved)).?, "{\"revision\":5}");
    try testing.expectEqual(views + 1, queued(core, "session.view"));
    try answer(core, (try pendingId(core, .view)) orelse return error.NoViewRequest, comptime moveView("5", move_windows));
    // The server's own event for the same revision adds nothing: the view is current.
    try sessionEvent(core, "session:a", "session_view_changed", "{\"revision\":5}");
    try testing.expectEqual(views + 1, queued(core, "session.view"));
    try testing.expectEqual(0, recorder.errors.items.len);
}

test "a moved tab's unchanged revision asks for nothing" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    const core = try withMoveView(&recorder);
    defer core.destroy();

    const views = queued(core, "session.view");
    core.command(
        \\{"op":"moveWindow","session":"session:a","window":"window:c","before":"window:ab"}
    );
    try answer(core, (try pendingId(core, .window_moved)).?, "{\"revision\":4}");
    try testing.expectEqual(views, queued(core, "session.view"));
    try testing.expectEqual(0, recorder.errors.items.len);
}

test "moving a tab of a followed session asks for its view" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    const a = arena.allocator();
    const core = try following(&recorder, a);
    defer core.destroy();

    try testing.expectEqual(1, queuedFor(core, view_method, "session:b"));
    core.command(
        \\{"op":"moveWindow","session":"session:b","window":"window:b3","before":"window:b1"}
    );
    try expectRequest(core, "session.move_window",
        \\{"session_id":"session:b","window_id":"window:b3","before_window_id":"window:b1"}
    );
    try answer(core, (try pendingId(core, .window_moved)).?, "{\"revision\":8}");
    try testing.expectEqual(2, queuedFor(core, view_method, "session:b"));
    try testing.expectEqual(0, queuedFor(core, view_method, "session:a"));
    try testing.expectEqual(0, recorder.errors.items.len);
}

test "a refused tab move is reported" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    const core = try withMoveView(&recorder);
    defer core.destroy();

    const views = queued(core, "session.view");
    core.command(
        \\{"op":"moveWindow","session":"session:a","window":"window:c","before":"window:ab"}
    );
    try answerError(core, (try pendingId(core, .window_moved)).?, "not_found");
    try testing.expectEqual(1, recorder.errors.items.len);
    try testing.expectEqualStrings("not_found", recorder.errors.items[0]);
    try testing.expectEqual(views, queued(core, "session.view"));
}

// ------------------------------------------------------------- paneWrite

const blocked_question =
    \\{"records":[{"id":"claude","state":"blocked","kind":"question","updated_at":"2026-11-21T09:00:00.000Z"}]}
;

test "paneWrite checks the status, then writes to a session that is not selected" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    const a = arena.allocator();
    const core = try following(&recorder, a);
    defer core.destroy();
    try testing.expectEqualStrings("session:a", try recorder.lastString(a, "session"));
    core.command(
        \\{"op":"paneWrite","tag":"n1","session":"session:b","block":"block:b2","text":"2",
        \\"expect":{"id":"claude","kind":"question","updatedAt":"2026-11-21T09:00:00Z"}}
    );
    // Nothing is written before the check answers.
    try testing.expectEqual(0, queued(core, protocol.methods.terminalWrite));
    try expectRequest(core, protocol.methods.terminalProgramStatus,
        \\{"session_id":"session:b","block_id":"block:b2"}
    );
    try answer(core, (try pendingId(core, .pane_write_check)).?, blocked_question);
    try expectRequest(core, protocol.methods.terminalWrite,
        \\{"session_id":"session:b","block_id":"block:b2","args":{"data":"2"}}
    );
    const write_id = (try pendingId(core, .pane_write)).?;
    try testing.expectEqual(0, recorder.writes.items.len);
    try answer(core, write_id, "{\"bytes_written\":1}");
    try recorder.expectPaneWrite("n1", .sent);
    // A duplicate server response must not deliver a second completion on either callback.
    try answer(core, write_id, "{\"bytes_written\":1}");
    try testing.expectEqual(0, recorder.writes.items.len);
    try testing.expectEqual(0, recorder.reports.items.len);
    try testing.expectEqual(0, recorder.errors.items.len);
}

test "paneWrite writes while the core is parked" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    const a = arena.allocator();
    const core = try following(&recorder, a);
    defer core.destroy();
    core.command("{\"op\":\"detach\"}");
    try testing.expect(try recorder.lastValue(a, "session") == .null);
    // `sendText` has no session to write to now.
    core.command("{\"op\":\"sendText\",\"block\":\"block:a1\",\"text\":\"x\"}");
    try testing.expectEqual(0, queued(core, protocol.methods.terminalWrite));
    core.command(
        \\{"op":"paneWrite","tag":"n2","session":"session:a","block":"block:a1","text":"y",
        \\"expect":{"id":"claude","kind":"question","updatedAt":"2026-11-21T09:00:00.000Z"}}
    );
    try answer(core, (try pendingId(core, .pane_write_check)).?, blocked_question);
    try expectRequest(core, protocol.methods.terminalWrite,
        \\{"session_id":"session:a","block_id":"block:a1","args":{"data":"y"}}
    );
    try testing.expectEqual(1, queued(core, protocol.methods.terminalWrite));
    try answer(core, (try pendingId(core, .pane_write)).?, "{\"bytes_written\":1}");
    try recorder.expectPaneWrite("n2", .sent);
}

test "paneWrite writes nothing when the prompt changed or the link is down" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    const a = arena.allocator();
    const core = try following(&recorder, a);
    defer core.destroy();
    const request =
        \\{"op":"paneWrite","tag":"t","session":"session:b","block":"block:b2","text":"1",
        \\"expect":{"id":"claude","kind":"permission","updatedAt":"2026-11-21T09:00:00Z"}}
    ;
    const answers = [_][]const u8{
        // Another kind, another time, done, another record, no record.
        \\{"records":[{"id":"claude","state":"blocked","kind":"question","updated_at":"2026-11-21T09:00:00Z"}]}
        ,
        \\{"records":[{"id":"claude","state":"blocked","kind":"permission","updated_at":"2026-11-21T09:00:05Z"}]}
        ,
        \\{"records":[{"id":"claude","state":"done","kind":"permission","updated_at":"2026-11-21T09:00:00Z"}]}
        ,
        \\{"records":[{"id":"codex","state":"blocked","kind":"permission","updated_at":"2026-11-21T09:00:00Z"}]}
        ,
        \\{"records":[]}
    };
    for (answers) |status| {
        core.command(request);
        try answer(core, (try pendingId(core, .pane_write_check)).?, status);
        try recorder.expectPaneWrite("t", .changed);
    }
    try testing.expectEqual(0, queued(core, protocol.methods.terminalWrite));

    core.command(request);
    try answerError(core, (try pendingId(core, .pane_write_check)).?, "not_found");
    try recorder.expectPaneWrite("t", .not_sent);

    // A check in flight when the link drops writes nothing, and nothing waits for the next link.
    core.command(request);
    core.lock.lock();
    core.linkEnded();
    core.lock.unlock();
    core.command("{\"op\":\"fit\",\"block\":\"none\"}");
    try recorder.expectPaneWrite("t", .not_sent);
    core.command(request);
    try recorder.expectPaneWrite("t", .not_sent);
    try testing.expectEqual(0, sent(core));
}

test "paneWrite reports a write the server did not confirm" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    const a = arena.allocator();
    const core = try following(&recorder, a);
    defer core.destroy();
    const request =
        \\{"op":"paneWrite","tag":"u","session":"session:b","block":"block:b2","text":"망고\r",
        \\"expect":{"id":"claude","kind":"question"}}
    ;
    core.command(request);
    // Without a time only the record, its state and its kind are compared.
    try answer(core, (try pendingId(core, .pane_write_check)).?,
        \\{"records":[{"id":"claude","state":"blocked","kind":"question","updated_at":"2026-11-21T09:09:09Z"}]}
    );
    try answer(core, (try pendingId(core, .pane_write)).?, "{\"bytes_written\":3}");
    try recorder.expectPaneWrite("u", .unconfirmed);
    core.command(request);
    try answer(core, (try pendingId(core, .pane_write_check)).?, blocked_question);
    try answerError(core, (try pendingId(core, .pane_write)).?, "internal");
    try recorder.expectPaneWrite("u", .unconfirmed);
}

const PromptCheck = struct {
    mode: u64,
    process: u64,

    fn latest(core: *Core) !PromptCheck {
        return .{
            .mode = try requestId(core, protocol.methods.terminalInputMode, term_block),
            .process = try requestId(core, protocol.methods.terminalProcess, term_block),
        };
    }
};

const readline_mode = "{\"echo\":false,\"canonical\":false,\"signals\":true}";
const silent_mode = "{\"echo\":false,\"canonical\":true,\"signals\":true}";
const echo_mode = "{\"echo\":true,\"canonical\":true,\"signals\":true}";
const bash_process = "{\"foreground\":{\"name\":\"bash\"}}";

test "preedit: readline may draw; silent reads, password programs and unusable replies may not" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    const core = try withTerminal(&recorder, me);
    defer core.destroy();
    const view = core.entries.get(term_block).?.pane;
    const Case = struct { mode: []const u8, proc: []const u8 = bash_process, visible: bool = false };
    for ([_]Case{
        .{ .mode = readline_mode, .visible = true },
        .{ .mode = echo_mode, .visible = true },
        .{ .mode = silent_mode },
        .{ .mode = readline_mode, .proc = "{\"foreground\":{\"name\":\"sudo\"},\"child\":{\"name\":\"bash\"}}" },
        .{ .mode = echo_mode, .proc = "{\"foreground\":{\"name\":\"passwd\"}}" },
        .{ .mode = "{}" },
        .{ .mode = "{\"echo\":\"false\",\"canonical\":true}" },
        .{ .mode = readline_mode, .proc = "{\"foreground\":null,\"child\":{\"name\":\"bash\"}}" },
        .{ .mode = readline_mode, .proc = "{\"foreground\":{\"name\":\"\"}}" },
    }) |case| {
        core.panePreedit(term_block, "한");
        const check = try PromptCheck.latest(core);
        try answer(core, check.mode, case.mode);
        try testing.expectEqualStrings("", view.preeditLocked());
        try answer(core, check.process, case.proc);
        try testing.expectEqualStrings(if (case.visible) "한" else "", view.preeditLocked());
        core.panePreedit(term_block, "");
    }
    for ([_][]const u8{ "not_found", "method_not_found", "internal" }) |code| {
        core.panePreedit(term_block, "안");
        const check = try PromptCheck.latest(core);
        try answer(core, check.process, bash_process);
        try answerError(core, check.mode, code);
        try testing.expectEqualStrings("", view.preeditLocked());
        core.panePreedit(term_block, "");
    }
}

test "preedit: coalesced edits, process changes and reconnects cannot reuse a stale permission" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    const core = try withTerminal(&recorder, me);
    defer core.destroy();
    const view = core.entries.get(term_block).?.pane;
    core.panePreedit(term_block, "ㅎ");
    const first = try PromptCheck.latest(core);
    core.panePreedit(term_block, "하");
    core.panePreedit(term_block, "한");
    try testing.expectEqual(first, try PromptCheck.latest(core));
    try answer(core, first.process, bash_process);
    try answer(core, first.mode, readline_mode);
    try testing.expectEqualStrings("", view.preeditLocked());
    const latest = try PromptCheck.latest(core);
    try testing.expect(latest.mode > first.mode);
    try answer(core, latest.mode, readline_mode);
    try answer(core, latest.process, bash_process);
    try testing.expectEqualStrings("한", view.preeditLocked());
    // A second client can move Bash from its line editor into read -s without changing PID.
    core.panePreedit(term_block, "한국");
    try testing.expectEqualStrings("", view.preeditLocked());
    const silent = try PromptCheck.latest(core);
    try answer(core, silent.process, bash_process);
    try answer(core, silent.mode, silent_mode);
    try testing.expectEqualStrings("", view.preeditLocked());
    // A foreground change invalidates even a stationary composition.
    try blockEvent(core, "session:a", term_block, "process_changed", "{}");
    const back = try PromptCheck.latest(core);
    try answer(core, back.mode, readline_mode);
    try answer(core, back.process, bash_process);
    try testing.expectEqualStrings("한국", view.preeditLocked());
    core.lock.lock();
    core.linkEnded();
    core.lock.unlock();
    try testing.expectEqualStrings("", view.preeditLocked());
    try bootstrapAgain(core, me);
    const restored = try PromptCheck.latest(core);
    try answer(core, restored.process, bash_process);
    try answer(core, restored.mode, readline_mode);
    try testing.expectEqualStrings("한국", view.preeditLocked());
    // Finish and start another composition before the old pair returns.
    core.panePreedit(term_block, "한국어");
    const late = try PromptCheck.latest(core);
    core.panePreedit(term_block, "");
    core.panePreedit(term_block, "ㅁ");
    const next = try PromptCheck.latest(core);
    try answer(core, late.process, bash_process);
    try answer(core, late.mode, readline_mode);
    try testing.expectEqualStrings("", view.preeditLocked());
    try answer(core, next.mode, readline_mode);
    try answerError(core, next.process, "internal");
    try testing.expectEqualStrings("", view.preeditLocked());
}

test "Core: the app reads the link under a finger as JSON and can underline it" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    const core = try withTerminal(&recorder, me);
    defer core.destroy();
    core.lock.lock();
    const term = core.entries.get(term_block).?.pane;
    term.write("\x1b[2J\x1b[Hsee https://example.com/a src/b.kt:7");
    const cs = term.cellSizeLocked();
    const origin = term.originLocked();
    core.lock.unlock();
    const Tap = struct {
        fn x(o: f32, w: u32, col: u32) f32 {
            return o + @as(f32, @floatFromInt(col * w + w / 2));
        }
    };
    const row0 = origin.y + @as(f32, @floatFromInt(cs.h / 2));

    const url = (try core.paneLink(testing.allocator, term_block, Tap.x(origin.x, cs.w, 6), row0)) orelse return error.NoLink;
    defer testing.allocator.free(url);
    try testing.expectEqualStrings("{\"kind\":\"url\",\"target\":\"https://example.com/a\",\"line\":0,\"col\":0,\"set\":false,\"shown\":true}", url);
    const path = (try core.paneLink(testing.allocator, term_block, Tap.x(origin.x, cs.w, 28), row0)) orelse return error.NoLink;
    defer testing.allocator.free(path);
    try testing.expectEqualStrings("{\"kind\":\"file\",\"target\":\"src/b.kt\",\"line\":7,\"col\":0,\"set\":false,\"shown\":true}", path);
    try testing.expect((try core.paneLink(testing.allocator, term_block, Tap.x(origin.x, cs.w, 1), row0)) == null);
    try testing.expect((try core.paneLink(testing.allocator, "block:none", Tap.x(origin.x, cs.w, 6), row0)) == null);

    try testing.expect(core.paneMarkLink(term_block, Tap.x(origin.x, cs.w, 6), row0));
    try testing.expect(term.link_mark != null);
    core.paneUnmarkLink(term_block);
    try testing.expect(term.link_mark == null);
    try testing.expect(!core.paneMarkLink(term_block, Tap.x(origin.x, cs.w, 1), row0));
    try testing.expect(!core.paneMarkLink("block:none", 0, 0));
    core.paneUnmarkLink("block:none");

    // A hyperlink a program set under other text says so.
    core.lock.lock();
    term.write("\x1b[2J\x1b[H\x1b]8;;https://evil.example/\x1b\\click\x1b]8;;\x1b\\");
    core.lock.unlock();
    const set = (try core.paneLink(testing.allocator, term_block, Tap.x(origin.x, cs.w, 2), row0)) orelse return error.NoLink;
    defer testing.allocator.free(set);
    try testing.expectEqualStrings("{\"kind\":\"url\",\"target\":\"https://evil.example/\",\"line\":0,\"col\":0,\"set\":true,\"shown\":false}", set);
}

test "Core: a tap on a pane whose program takes the mouse is a left click at the tapped cell" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    const core = try withTerminal(&recorder, me);
    defer core.destroy();
    const entry = core.entries.get(term_block).?;
    entry.stream.?.live_snapshot.store(true, .release);
    core.lock.lock();
    entry.pane.setFontSizePx(10);
    const cs = entry.pane.cellSizeLocked();
    const origin = entry.pane.originLocked();
    core.lock.unlock();
    const x = origin.x + @as(f32, @floatFromInt(3 * cs.w + cs.w / 2));
    const y = origin.y + @as(f32, @floatFromInt(1 * cs.h + cs.h / 2));

    // A shell does not take the mouse: the tap is the app's own, and nothing is sent.
    try testing.expect(!core.paneClick(term_block, x, y));
    try testing.expectEqual(@as(usize, 0), queued(core, protocol.methods.blockEvent));

    entry.pane.write("\x1b[?1000h\x1b[?1006h");
    try testing.expect(core.paneClick(term_block, x, y));
    try testing.expectEqual(@as(usize, 2), queued(core, protocol.methods.blockEvent));
    core.lock.lock();
    const items = core.outbox.items;
    const press = items[items.len - 2];
    const release = items[items.len - 1];
    core.lock.unlock();
    for ([_][]const u8{ press, release }) |text| {
        try testing.expect(std.mem.find(u8, text, "\"type\":\"mouse\"") != null);
        try testing.expect(std.mem.find(u8, text, "\"button\":\"left\"") != null);
        try testing.expect(std.mem.find(u8, text, "\"x\":3,\"y\":1") != null);
    }
    try testing.expect(std.mem.find(u8, press, "\"action\":\"press\"") != null);
    try testing.expect(std.mem.find(u8, release, "\"action\":\"release\"") != null);

    // In the margin there is no cell to click, and an unknown pane takes nothing.
    try testing.expect(!core.paneClick(term_block, -50, y));
    try testing.expect(!core.paneClick("block:none", x, y));
    try testing.expectEqual(@as(usize, 2), queued(core, protocol.methods.blockEvent));
}

test "Core: a held TUI snapshot sends no click until the data stream is live" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    const core = try withTerminal(&recorder, me);
    defer core.destroy();
    const entry = core.entries.get(term_block).?;
    const stream = entry.stream.?;
    entry.pane.write("\x1b[?1000h");
    stream.live_snapshot.store(false, .release);
    try testing.expect(!core.paneClick(term_block, 30, 30));
    try testing.expectEqual(@as(usize, 0), queued(core, protocol.methods.blockEvent));
    stream.live_snapshot.store(true, .release);
    try testing.expect(core.paneClick(term_block, 30, 30));
    try testing.expectEqual(@as(usize, 2), queued(core, protocol.methods.blockEvent));
}

test "Core: a tap on a terminal this device has not taken, or is taking, clicks nothing" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    // Another client sized the terminal: the tap takes it and refits the program, so a click
    // at the old layout's cell would land on something else.
    const core = try withTerminal(&recorder, "client:someone-else");
    defer core.destroy();
    const entry = core.entries.get(term_block).?;
    entry.stream.?.live_snapshot.store(true, .release);
    entry.pane.write("\x1b[?1000h");
    try testing.expect(!core.paneClick(term_block, 30, 30));
    core.lock.lock();
    entry.size.owned = true;
    entry.size.take_inflight = true;
    core.lock.unlock();
    try testing.expect(!core.paneClick(term_block, 30, 30));
    try testing.expectEqual(@as(usize, 0), queued(core, protocol.methods.blockEvent));
    core.lock.lock();
    entry.size.take_inflight = false;
    core.lock.unlock();
    try testing.expect(core.paneClick(term_block, 30, 30));
}

test "Core: download_begin is asked for the pane's file, and the answer reaches the app as JSON" {
    const Worker = struct {
        answer: ?[]u8 = null,
        err: ?anyerror = null,
        if_none_match: ?[]const u8 = null,
        fn begin(w: *@This(), core: *Core) void {
            w.answer = core.downloadBegin(testing.allocator, term_block, "src/a.kt", w.if_none_match, core.downloadGeneration()) catch |err| blk: {
                w.err = err;
                break :blk null;
            };
        }
    };
    var recorder: Recorder = .{};
    defer recorder.deinit();
    const core = try withTerminal(&recorder, me);
    defer core.destroy();
    const cases = [_]struct { match: ?[]const u8, reply: []const u8, err: bool, want: []const u8 }{
        .{
            .match = null,
            .reply = "{\"ticket\":\"AAECAwQFBgcICQoLDA0ODw\",\"expires_in_ms\":30000,\"path\":\"/home/me/src/a.kt\",\"name\":\"a.kt\",\"size\":12,\"modified_at\":\"2026-11-22T00:00:00Z\",\"version\":\"v1\"}",
            .err = false,
            .want = "{\"status\":\"ok\",\"ticket\":\"AAECAwQFBgcICQoLDA0ODw\",\"path\":\"/home/me/src/a.kt\",\"name\":\"a.kt\",\"size\":12,\"version\":\"v1\"}",
        },
        .{
            .match = "v1",
            .reply = "{\"not_modified\":true,\"path\":\"/home/me/src/a.kt\",\"name\":\"a.kt\",\"size\":12,\"modified_at\":\"2026-11-22T00:00:00Z\",\"version\":\"v1\"}",
            .err = false,
            .want = "{\"status\":\"not_modified\",\"path\":\"/home/me/src/a.kt\",\"name\":\"a.kt\",\"size\":12,\"version\":\"v1\"}",
        },
        .{ .match = null, .reply = "not_found", .err = true, .want = "{\"status\":\"error\",\"code\":\"not_found\",\"message\":\"no\"}" },
    };
    for (cases) |case| {
        var worker: Worker = .{ .if_none_match = case.match };
        {
            const t = try std.Thread.spawn(.{}, Worker.begin, .{ &worker, core });
            defer t.join();
            const id = for (0..200) |_| {
                if (try pendingId(core, .download)) |id| break id;
                try std.Io.sleep(testing.io, .fromMilliseconds(5), .awake);
            } else return error.NoDownloadRequest;
            const request = try lastQueued(core, protocol.methods.downloadBegin);
            defer testing.allocator.free(request);
            try testing.expect(std.mem.find(u8, request, "\"path\":\"src/a.kt\"") != null);
            try testing.expect(std.mem.find(u8, request, "\"ref\":\"download:") != null);
            try testing.expectEqual(case.match != null, std.mem.find(u8, request, "\"if_none_match\":\"v1\"") != null);
            if (case.err) try answerError(core, id, case.reply) else try answer(core, id, case.reply);
        }
        try testing.expectEqual(@as(?anyerror, null), worker.err);
        const got = worker.answer orelse return error.NoAnswer;
        defer testing.allocator.free(got);
        try testing.expectEqualStrings(case.want, got);
        try testing.expect((try pendingId(core, .download)) == null);
    }
    try testing.expectError(error.UnknownBlock, core.downloadBegin(testing.allocator, "block:none", "a", null, core.downloadGeneration()));
}

test "Core: a cancel or a lost link ends a download_begin that waits for its answer" {
    const Worker = struct {
        err: ?anyerror = null,
        generation: u64,
        fn begin(w: *@This(), core: *Core) void {
            if (core.downloadBegin(testing.allocator, term_block, "a.png", null, w.generation)) |got| testing.allocator.free(got) else |err| w.err = err;
        }
    };
    var recorder: Recorder = .{};
    defer recorder.deinit();
    const core = try withTerminal(&recorder, me);
    defer core.destroy();
    for ([_]anyerror{ error.Canceled, error.NotConnected }) |want| {
        var worker: Worker = .{ .generation = core.downloadGeneration() };
        {
            const t = try std.Thread.spawn(.{}, Worker.begin, .{ &worker, core });
            defer t.join();
            for (0..200) |_| {
                if (try pendingId(core, .download) != null) break;
                try std.Io.sleep(testing.io, .fromMilliseconds(5), .awake);
            } else return error.NoDownloadRequest;
            if (want == error.Canceled) core.downloadCancel() else {
                core.lock.lock();
                core.linkEnded();
                core.lock.unlock();
            }
        }
        try testing.expectEqual(@as(?anyerror, want), worker.err);
        try testing.expect((try pendingId(core, .download)) == null);
    }
}

test "Core: a cancel before download_begin asks, such as one between a download's steps, still counts" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    const core = try withTerminal(&recorder, me);
    defer core.destroy();
    const generation = core.downloadGeneration();
    core.downloadCancel();
    try testing.expectError(error.Canceled, core.downloadBegin(testing.allocator, term_block, "a.png", null, generation));
    try testing.expectEqual(@as(usize, 0), queued(core, protocol.methods.downloadBegin));
    try testing.expectError(error.Canceled, core.downloadFetch("AAECAwQFBgcICQoLDA0ODw", 1, generation));
}

// ------------------------------------------------------------ port forwards (#226)

/// A port of the loopback address that nothing listens on now.
fn freePort() !u16 {
    var address: std.Io.net.IpAddress = .{ .ip4 = .loopback(0) };
    var listener = try address.listen(testing.io, .{});
    defer listener.deinit(testing.io);
    return listener.socket.address.getPort();
}

fn connectLoopback(port: u16) !std.Io.net.Stream {
    var address: std.Io.net.IpAddress = .{ .ip4 = .loopback(port) };
    return address.connect(testing.io, .{ .mode = .stream });
}

/// The id of the one request the core waits for on another thread (`Pending.ask`), once
/// it is there. It must be the newest queued `method`.
fn waitAsk(core: *Core, method: []const u8) !u64 {
    for (0..400) |_| {
        if (try pendingId(core, .ask)) |id| {
            const text = lastQueued(core, method) catch return error.WrongRequest;
            defer testing.allocator.free(text);
            var buf: [32]u8 = undefined;
            if (std.mem.find(u8, text, try std.fmt.bufPrint(&buf, "\"id\":\"{d}\"", .{id})) == null) return error.WrongRequest;
            return id;
        }
        try std.Io.sleep(testing.io, .fromMilliseconds(5), .awake);
    }
    return error.NoRequest;
}

/// Waits for the request `method` that another thread waits on, checks that it holds
/// `part`, and answers it with `payload`. Checked first: the answer lets the core go on.
fn answerAsk(core: *Core, method: []const u8, part: []const u8, payload: []const u8) !void {
    const id = try waitAsk(core, method);
    try expectQueued(core, method, part);
    try answer(core, id, payload);
}

fn expectQueued(core: *Core, method: []const u8, part: []const u8) !void {
    const text = try lastQueued(core, method);
    defer testing.allocator.free(text);
    if (std.mem.find(u8, text, part) == null) {
        std.debug.print("{s} lacks {s}\n", .{ text, part });
        return error.TestUnexpectedResult;
    }
}

const ForwardWorker = struct {
    port: u16,
    answer: ?[]u8 = null,
    err: ?anyerror = null,

    fn open(w: *ForwardWorker, core: *Core) void {
        w.answer = core.forwardOpen(testing.allocator, term_block, "localhost", w.port) catch |err| blk: {
            w.err = err;
            break :blk null;
        };
    }

    fn deinit(w: *ForwardWorker) void {
        if (w.answer) |a| testing.allocator.free(a);
    }
};

/// Opens a forward of `localhost:port`, whose block the server names `block:fwd`.
fn openForward(core: *Core, port: u16) !void {
    var worker: ForwardWorker = .{ .port = port };
    defer worker.deinit();
    {
        const t = try std.Thread.spawn(.{}, ForwardWorker.open, .{ &worker, core });
        defer t.join();
        try answer(core, try waitAsk(core, "session.new_block"), "{\"block_id\":\"block:fwd\",\"revision\":1}");
    }
    if (worker.err) |err| return err;
}

/// The forwards in the last state.
fn lastForwards(recorder: *Recorder, arena: std.mem.Allocator) ![]std.json.Value {
    const value = try recorder.lastValue(arena, "forwards");
    if (value != .array) return error.NotAnArray;
    return value.array.items;
}

/// Waits until the last state says the first forward relays `count` connections.
fn waitConnections(recorder: *Recorder, count: i64) !void {
    for (0..400) |_| {
        var arena: std.heap.ArenaAllocator = .init(testing.allocator);
        defer arena.deinit();
        const forwards = try lastForwards(recorder, arena.allocator());
        if (forwards.len > 0 and model.int(model.get(forwards[0], "connections")) == count) return;
        try std.Io.sleep(testing.io, .fromMilliseconds(5), .awake);
    }
    return error.ConnectionsNotReported;
}

/// A browser's connection to the forward asks for a stream of its block; the refused
/// stream resets the connection.
fn expectRefusedStream(core: *Core, recorder: *Recorder, port: u16) !void {
    const browser = try connectLoopback(port);
    defer browser.close(testing.io);
    const id = try waitAsk(core, protocol.methods.dataConnect);
    // Read before the answer: none comes before it, so this is the request of `id`.
    try expectQueued(core, protocol.methods.dataConnect, "\"block_id\":\"block:fwd\",\"data_ref_id\":\"conn:");
    try expectQueued(core, protocol.methods.dataConnect, "\"subprotocol\":\"rex.data.bytes.v1\"");
    try waitConnections(recorder, 1);
    try answerError(core, id, "internal");
    var buffer: [16]u8 = undefined;
    var data: [1][]u8 = .{&buffer};
    try testing.expectError(error.ConnectionResetByPeer, browser.read(testing.io, &data));
    try waitConnections(recorder, 0);
}

test "Core: a forward makes a dial tunnel for the link's host and port, and listens on that port of the phone" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    const core = try withTerminal(&recorder, me);
    defer core.destroy();
    const port = try freePort();

    var worker: ForwardWorker = .{ .port = port };
    defer worker.deinit();
    {
        const t = try std.Thread.spawn(.{}, ForwardWorker.open, .{ &worker, core });
        defer t.join();
        const id = try waitAsk(core, "session.new_block");
        try expectQueued(core, "session.new_block", try std.fmt.allocPrint(arena.allocator(),
            \\"payload":{{"session_id":"session:a","flavor":"com.superlogical.tunnel.dial","options":{{"host":"localhost","port":{d},"client_address":"127.0.0.1:{d}"}}}}
        , .{ port, port }));
        try answer(core, id, "{\"block_id\":\"block:fwd\",\"revision\":1}");
    }
    try testing.expectEqual(@as(?anyerror, null), worker.err);
    const ok = try std.fmt.allocPrint(arena.allocator(), "{{\"status\":\"ok\",\"port\":{d}}}", .{port});
    try testing.expectEqualStrings(ok, worker.answer.?);

    const forwards = try lastForwards(&recorder, arena.allocator());
    try testing.expectEqual(@as(usize, 1), forwards.len);
    try testing.expectEqualStrings("block:fwd", model.str(model.get(forwards[0], "block")).?);
    try testing.expectEqualStrings("localhost", model.str(model.get(forwards[0], "host")).?);
    try testing.expectEqual(@as(?i64, port), model.int(model.get(forwards[0], "port")));
    try testing.expectEqual(@as(?i64, port), model.int(model.get(forwards[0], "localPort")));
    try testing.expectEqual(@as(?i64, 0), model.int(model.get(forwards[0], "connections")));

    // A second tap on the same link uses the same forward.
    const made = queued(core, "session.new_block");
    const again = try core.forwardOpen(testing.allocator, term_block, "localhost", port);
    defer testing.allocator.free(again);
    try testing.expectEqualStrings(ok, again);
    try testing.expectEqual(made, queued(core, "session.new_block"));

    try expectRefusedStream(core, &recorder, port);

    // Closing it closes its block and stops listening.
    try core.forwardClose("block:fwd");
    try expectQueued(core, protocol.methods.closeBlock, "\"block_id\":\"block:fwd\"");
    try testing.expectEqual(@as(usize, 0), (try lastForwards(&recorder, arena.allocator())).len);
    try testing.expectError(error.ConnectionRefused, connectLoopback(port));
}

test "Core: a server without tunnel blocks is too old to forward ports, and the phone's port is given back" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    const core = try withTerminal(&recorder, me);
    defer core.destroy();
    const port = try freePort();
    var worker: ForwardWorker = .{ .port = port };
    defer worker.deinit();
    {
        const t = try std.Thread.spawn(.{}, ForwardWorker.open, .{ &worker, core });
        defer t.join();
        try answerError(core, try waitAsk(core, "session.new_block"), "not_found");
    }
    try testing.expectEqualStrings("{\"status\":\"error\",\"code\":\"too_old\",\"message\":\"This remote's Rex server is too old to forward ports.\"}", worker.answer.?);
    try testing.expectError(error.ConnectionRefused, connectLoopback(port));
}

test "Core: a port another program holds on the phone is replaced by a free one" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    const core = try withTerminal(&recorder, me);
    defer core.destroy();
    var address: std.Io.net.IpAddress = .{ .ip4 = .loopback(0) };
    var taken = try address.listen(testing.io, .{});
    defer taken.deinit(testing.io);
    const port = taken.socket.address.getPort();

    try openForward(core, port);
    const forwards = try lastForwards(&recorder, arena.allocator());
    const local: u16 = @intCast(model.int(model.get(forwards[0], "localPort")).?);
    try testing.expect(local != port and local != 0);
    try expectQueued(core, "session.new_block", try std.fmt.allocPrint(arena.allocator(), "\"client_address\":\"127.0.0.1:{d}\"", .{local}));
    try expectRefusedStream(core, &recorder, local);
}

test "Core: after a lost link a forward takes its block again before it asks for a stream" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    const core = try withTerminal(&recorder, me);
    defer core.destroy();
    const port = try freePort();
    try openForward(core, port);
    try reconnect(core, me);

    const browser = try connectLoopback(port);
    defer browser.close(testing.io);
    try answerAsk(core, protocol.methods.ownBlock, "{\"session_id\":\"session:a\",\"block_ids\":[\"block:fwd\"],\"mode\":\"take\"}", "{\"blocks\":[{\"block_id\":\"block:fwd\",\"owner\":\"client:me\"}]}");
    try answerError(core, try waitAsk(core, protocol.methods.dataConnect), "internal");
    try waitConnections(&recorder, 0);

    // Taken once per link.
    const takes = queued(core, protocol.methods.ownBlock);
    try expectRefusedStream(core, &recorder, port);
    try testing.expectEqual(takes, queued(core, protocol.methods.ownBlock));
}

test "Core: a forward whose session the core left attaches it again and takes its block" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    const core = try withTerminal(&recorder, me);
    defer core.destroy();
    const port = try freePort();
    try openForward(core, port);
    core.selectSession("session:b");

    const browser = try connectLoopback(port);
    defer browser.close(testing.io);
    try answerAsk(core, "session.attach", "\"payload\":{\"session_id\":\"session:a\"}", "{\"client_id\":\"client:me\",\"view\":{\"session_id\":\"session:a\",\"revision\":3,\"windows\":[]}}");
    try answer(core, try waitAsk(core, protocol.methods.ownBlock), "{\"blocks\":[{\"block_id\":\"block:fwd\",\"owner\":\"client:me\"}]}");
    try answerError(core, try waitAsk(core, protocol.methods.dataConnect), "internal");
    try waitConnections(&recorder, 0);
}

test "Core: the sweep closes the tunnel blocks this client left behind, and only those" {
    const Sweeper = struct {
        err: ?anyerror = null,
        fn run(w: *@This(), core: *Core) void {
            core.forwardSweep() catch |err| {
                w.err = err;
            };
        }
    };
    var recorder: Recorder = .{};
    defer recorder.deinit();
    const core = try withTerminal(&recorder, me);
    defer core.destroy();
    try openForward(core, try freePort());

    var sweeper: Sweeper = .{};
    {
        const t = try std.Thread.spawn(.{}, Sweeper.run, .{ &sweeper, core });
        defer t.join();
        try answerAsk(core, "session.list_blocks", "\"payload\":{\"session_id\":\"session:a\"}",
            \\{"blocks":[{"block_id":"block:t","creator_name":"com.superlogical.terminal","flavor":"com.superlogical.terminal.shell"},
            \\{"block_id":"block:fwd","creator_name":"com.superlogical.tunnel","flavor":"com.superlogical.tunnel.dial"},
            \\{"block_id":"block:old","creator_name":"com.superlogical.tunnel","flavor":"com.superlogical.tunnel.dial"},
            \\{"block_id":"block:theirs","creator_name":"com.superlogical.tunnel","flavor":"com.superlogical.tunnel.dial"},
            \\{"block_id":"block:listen","creator_name":"com.superlogical.tunnel","flavor":"com.superlogical.tunnel.listen"}]}
        );
        try answerAsk(core, "com.superlogical.tunnel.info", "\"block_id\":\"block:old\"", "{\"flavor\":\"dial\",\"target\":\"localhost:3000\",\"last_owner\":\"client:me\",\"connections\":0}");
        try answerAsk(core, "com.superlogical.tunnel.info", "\"block_id\":\"block:theirs\"", "{\"flavor\":\"dial\",\"target\":\"localhost:3000\",\"last_owner\":\"client:other\",\"connections\":0}");
        try answerAsk(core, "session.list_blocks", "\"payload\":{\"session_id\":\"session:b\"}", "{\"blocks\":[]}");
    }
    try testing.expectEqual(@as(?anyerror, null), sweeper.err);
    try testing.expectEqual(@as(usize, 2), queued(core, "com.superlogical.tunnel.info"));
    try testing.expectEqual(@as(usize, 1), queued(core, protocol.methods.closeBlock));
    try expectQueued(core, protocol.methods.closeBlock, "\"block_id\":\"block:old\"");
}

test "Core: a core that goes closes the blocks of its forwards first" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    const core = try withTerminal(&recorder, me);
    const port = try freePort();
    openForward(core, port) catch |err| {
        core.destroy();
        return err;
    };
    const Destroyer = struct {
        fn run(c: *Core) void {
            c.destroy();
        }
    };
    const t = try std.Thread.spawn(.{}, Destroyer.run, .{core});
    defer t.join();
    // Closing its link ends this client on the server: nothing would know the blocks as its own.
    // The core is freed once the answer arrives: nothing reads it after.
    try answerAsk(core, protocol.methods.closeBlock, "\"block_id\":\"block:fwd\"", "{\"block_id\":\"block:fwd\",\"revision\":2}");
}

/// The id of the request `method` that another thread waits for, among several such.
fn waitAskFor(core: *Core, method: []const u8, part: []const u8) !u64 {
    var buf: [64]u8 = undefined;
    const needle = try std.fmt.bufPrint(&buf, "\"method\":\"{s}\"", .{method});
    for (0..400) |_| {
        core.lock.lock();
        defer core.lock.unlock();
        var it = core.pending.iterator();
        while (it.next()) |kv| {
            if (kv.value_ptr.* != .ask) continue;
            var id_buf: [32]u8 = undefined;
            const id_part = try std.fmt.bufPrint(&id_buf, "\"id\":\"{d}\"", .{kv.key_ptr.*});
            for (core.outbox.items) |text| {
                if (std.mem.find(u8, text, id_part) != null and std.mem.find(u8, text, needle) != null and std.mem.find(u8, text, part) != null)
                    return kv.key_ptr.*;
            }
        }
        core.lock.unlock();
        std.Io.sleep(testing.io, .fromMilliseconds(5), .awake) catch {};
        core.lock.lock();
    }
    return error.NoRequest;
}

test "Core: a forward whose block is gone on the remote is made again by the next tap" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    const core = try withTerminal(&recorder, me);
    defer core.destroy();
    const port = try freePort();
    try openForward(core, port);
    {
        const browser = try connectLoopback(port);
        defer browser.close(testing.io);
        try answerError(core, try waitAsk(core, protocol.methods.dataConnect), "not_found");
        try waitConnections(&recorder, 0);
    }
    var worker: ForwardWorker = .{ .port = port };
    defer worker.deinit();
    {
        const t = try std.Thread.spawn(.{}, ForwardWorker.open, .{ &worker, core });
        defer t.join();
        try answer(core, try waitAsk(core, "session.new_block"), "{\"block_id\":\"block:again\",\"revision\":2}");
    }
    // On the same port: the old forward gave it back first.
    try testing.expectEqualStrings(try std.fmt.allocPrint(arena.allocator(), "{{\"status\":\"ok\",\"port\":{d}}}", .{port}), worker.answer.?);
    const forwards = try lastForwards(&recorder, arena.allocator());
    try testing.expectEqual(@as(usize, 1), forwards.len);
    try testing.expectEqualStrings("block:again", model.str(model.get(forwards[0], "block")).?);
}

test "Core: the sweep leaves a block that became a forward while it asked about it" {
    const Sweeper = struct {
        err: ?anyerror = null,
        fn run(w: *@This(), core: *Core) void {
            core.forwardSweep() catch |err| {
                w.err = err;
            };
        }
    };
    var recorder: Recorder = .{};
    defer recorder.deinit();
    const core = try withTerminal(&recorder, me);
    defer core.destroy();
    var sweeper: Sweeper = .{};
    const t = try std.Thread.spawn(.{}, Sweeper.run, .{ &sweeper, core });
    defer t.join();
    try answer(core, try waitAsk(core, "session.list_blocks"),
        \\{"blocks":[{"block_id":"block:fwd","creator_name":"com.superlogical.tunnel","flavor":"com.superlogical.tunnel.dial"}]}
    );
    const info = try waitAskFor(core, "com.superlogical.tunnel.info", "block:fwd");
    // Meanwhile a tap makes a forward, whose new block is the one asked about.
    var worker: ForwardWorker = .{ .port = try freePort() };
    defer worker.deinit();
    {
        const opener = try std.Thread.spawn(.{}, ForwardWorker.open, .{ &worker, core });
        defer opener.join();
        try answer(core, try waitAskFor(core, "session.new_block", "tunnel.dial"), "{\"block_id\":\"block:fwd\",\"revision\":1}");
    }
    try answer(core, info, "{\"flavor\":\"dial\",\"target\":\"localhost:3000\",\"last_owner\":\"client:me\",\"connections\":0}");
    try answer(core, try waitAskFor(core, "session.list_blocks", "session:b"), "{\"blocks\":[]}");
    try testing.expectEqual(@as(usize, 0), queued(core, protocol.methods.closeBlock));
}

test "Core: a listener takes its port again while the last one's connections wait out TIME_WAIT" {
    const io = testing.io;
    const port = try freePort();
    var first = try core_mod.listenLoopback(io, port);
    try testing.expectEqual(port, first.socket.address.getPort());
    {
        const browser = try connectLoopback(port);
        defer browser.close(io);
        // The phone's side ends first, as after a remote's keep-alive ran out: its socket waits.
        const accepted = try first.accept(io);
        accepted.close(io);
        var buffer: [8]u8 = undefined;
        var data: [1][]u8 = .{&buffer};
        try testing.expectEqual(@as(usize, 0), try browser.read(io, &data));
    }
    first.deinit(io);
    var second = try core_mod.listenLoopback(io, port);
    defer second.deinit(io);
    try testing.expectEqual(port, second.socket.address.getPort());
    // A listener still keeps its port: another forward gets a free one.
    var third = try core_mod.listenLoopback(io, port);
    defer third.deinit(io);
    try testing.expect(third.socket.address.getPort() != port);
}

// ------------------------------------------------- port forwards: live server

/// A web server on this machine's loopback, which is the remote's here. Each connection's
/// request is read to its end (the browser's half-close), then answered with a body that
/// names it, long enough to take many messages, and closed.
const LiveWeb = struct {
    listener: std.Io.net.Server,
    tasks: std.Io.Group = .init,
    const body_len = 300 * 1024;

    fn start(self: *LiveWeb) !void {
        var address: std.Io.net.IpAddress = .{ .ip4 = .loopback(0) };
        self.* = .{ .listener = try address.listen(testing.io, .{}) };
        try self.tasks.concurrent(testing.io, accept, .{self});
    }

    fn port(self: *const LiveWeb) u16 {
        return self.listener.socket.address.getPort();
    }

    fn stop(self: *LiveWeb) void {
        self.tasks.cancel(testing.io);
        self.listener.deinit(testing.io);
    }

    fn accept(self: *LiveWeb) std.Io.Cancelable!void {
        while (true) {
            const conn = self.listener.accept(testing.io) catch |err| switch (err) {
                error.Canceled => return error.Canceled,
                else => return,
            };
            self.tasks.concurrent(testing.io, serve, .{conn}) catch conn.close(testing.io);
        }
    }

    fn serve(conn: std.Io.net.Stream) void {
        defer conn.close(testing.io);
        var request: [512]u8 = undefined;
        var got: usize = 0;
        while (true) {
            var data: [1][]u8 = .{request[got..]};
            const n = conn.read(testing.io, &data) catch return;
            if (n == 0) break;
            got += n;
            if (got == request.len) return;
        }
        var buffer: [4096]u8 = undefined;
        var w = conn.writer(testing.io, &buffer);
        w.interface.print("HTTP/1.0 200 OK\r\n\r\nyou asked: {s}\n", .{std.mem.trimEnd(u8, request[0..got], "\r\n")}) catch return;
        w.interface.splatByteAll('.', body_len) catch return;
        w.interface.flush() catch return;
    }
};

/// What a browser gets for `request` from `port` of this machine: it sends the request,
/// half-closes, and reads to the end.
fn browse(port: u16, request: []const u8) ![]u8 {
    const conn = try connectLoopback(port);
    defer conn.close(testing.io);
    var buffer: [256]u8 = undefined;
    var w = conn.writer(testing.io, &buffer);
    try w.interface.writeAll(request);
    try w.interface.flush();
    try conn.shutdown(testing.io, .send);
    var page: std.ArrayList(u8) = .empty;
    errdefer page.deinit(testing.allocator);
    while (true) {
        var chunk: [16 * 1024]u8 = undefined;
        var data: [1][]u8 = .{&chunk};
        const n = try conn.read(testing.io, &data);
        if (n == 0) return page.toOwnedSlice(testing.allocator);
        try page.appendSlice(testing.allocator, chunk[0..n]);
    }
}

fn expectPage(page: []const u8, request: []const u8) !void {
    const head = try std.fmt.allocPrint(testing.allocator, "HTTP/1.0 200 OK\r\n\r\nyou asked: {s}\n", .{request});
    defer testing.allocator.free(head);
    try testing.expect(std.mem.startsWith(u8, page, head));
    try testing.expectEqual(head.len + LiveWeb.body_len, page.len);
}

/// A core of the server at `url` that shows `session`, once its terminal `block` is a pane.
fn liveForwardCore(recorder: *Recorder, url: []const u8, session: []const u8, block: []const u8, token: ?[]const u8) !*Core {
    const core = try Core.create(testing.allocator, recorder.host(), .{}, 1);
    errdefer core.destroy();
    core.command("{\"op\":\"fence\",\"prefix\":\"" ++ live_label ++ "\"}");
    core.selectSession(session);
    core.connect(url, token);
    for (0..300) |_| {
        core.lock.lock();
        const shown = core.ready and core.entries.contains(block);
        core.lock.unlock();
        if (shown) return core;
        try std.Io.sleep(testing.io, .fromMilliseconds(50), .awake);
    }
    return error.NotConnected;
}

/// The tunnel blocks of `session`.
fn liveTunnels(desk: *Live, session: []const u8) !usize {
    const listed = try desk.callOn(session, protocol.methods.listBlocks, protocol.sessionView(testing.allocator, session));
    var n: usize = 0;
    for (model.items(model.get(listed, "blocks"))) |b| {
        if (std.mem.eql(u8, model.str(model.get(b, "flavor")) orelse "", protocol.tunnel_dial)) n += 1;
    }
    return n;
}

test "live: a forward relays browsers to a web server on the remote's loopback, and its close closes the block" {
    const url = try liveUrl();
    const gpa = testing.allocator;
    var desk: Live = undefined;
    try desk.open(url);
    defer desk.close();
    try desk.sweep();
    const made = try desk.create();
    defer desk.destroy(made.session);
    var web: LiveWeb = undefined;
    try web.start();
    defer web.stop();

    var recorder: Recorder = .{};
    defer recorder.deinit();
    const core = try liveForwardCore(&recorder, url, made.session, made.block, null);
    defer core.destroy();
    const opened = try core.forwardOpen(gpa, made.block, "127.0.0.1", web.port());
    defer gpa.free(opened);
    const parsed = try std.json.parseFromSlice(struct { status: []const u8, port: u16 }, gpa, opened, .{});
    defer parsed.deinit();
    try testing.expectEqualStrings("ok", parsed.value.status);
    // The web server holds its own port here, so the phone side listens on another.
    try testing.expect(parsed.value.port != web.port());
    try testing.expectEqual(@as(usize, 1), try liveTunnels(&desk, made.session));

    // Two browsers at once.
    const Browser = struct {
        request: []const u8,
        page: ?[]u8 = null,
        err: ?anyerror = null,
        fn run(b: *@This(), port: u16) void {
            b.page = browse(port, b.request) catch |err| blk: {
                b.err = err;
                break :blk null;
            };
        }
    };
    var browsers = [_]Browser{ .{ .request = "GET /a" }, .{ .request = "GET /b" } };
    {
        var threads: [2]std.Thread = undefined;
        for (&browsers, &threads) |*b, *t| t.* = try std.Thread.spawn(.{}, Browser.run, .{ b, parsed.value.port });
        for (threads) |t| t.join();
    }
    for (browsers) |b| {
        defer if (b.page) |p| gpa.free(p);
        try testing.expectEqual(@as(?anyerror, null), b.err);
        try expectPage(b.page.?, b.request);
    }

    var arena: std.heap.ArenaAllocator = .init(gpa);
    defer arena.deinit();
    const forwards = try lastForwards(&recorder, arena.allocator());
    try core.forwardClose(model.str(model.get(forwards[0], "block")).?);
    try expectNoTunnels(&desk, made.session);
    try testing.expectError(error.ConnectionRefused, connectLoopback(parsed.value.port));
}

/// Waits until `session` has no tunnel block: the core sends a block's close and goes on.
fn expectNoTunnels(desk: *Live, session: []const u8) !void {
    for (0..100) |_| {
        if (try liveTunnels(desk, session) == 0) return;
        try std.Io.sleep(testing.io, .fromMilliseconds(50), .awake);
    }
    return error.TunnelLeft;
}

test "live: a core that comes back as the same client sweeps the block of the forward it left" {
    const url = try liveUrl();
    const gpa = testing.allocator;
    var desk: Live = undefined;
    try desk.open(url);
    defer desk.close();
    try desk.sweep();
    const made = try desk.create();
    defer desk.destroy(made.session);
    var web: LiveWeb = undefined;
    try web.start();
    defer web.stop();

    var before: Recorder = .{};
    defer before.deinit();
    const first = try liveForwardCore(&before, url, made.session, made.block, null);
    gpa.free(first.forwardOpen(gpa, made.block, "127.0.0.1", web.port()) catch |err| {
        first.destroy();
        return err;
    });
    first.lock.lock();
    const token = gpa.dupe(u8, first.token orelse "");
    // A stopped app: its connection drops without a close, which would end its client.
    if (first.ws) |w| if (w.conn) |c| c.shutdown();
    first.lock.unlock();
    // Its port goes, its block stays.
    first.destroy();
    const kept = try token;
    defer gpa.free(kept);
    try testing.expect(kept.len > 0);
    try testing.expectEqual(@as(usize, 1), try liveTunnels(&desk, made.session));

    var after: Recorder = .{};
    defer after.deinit();
    const second = try liveForwardCore(&after, url, made.session, made.block, kept);
    defer second.destroy();
    try second.forwardSweep();
    try expectNoTunnels(&desk, made.session);
}

test "live: a core that goes leaves no block of its forwards" {
    const url = try liveUrl();
    var desk: Live = undefined;
    try desk.open(url);
    defer desk.close();
    try desk.sweep();
    const made = try desk.create();
    defer desk.destroy(made.session);
    var web: LiveWeb = undefined;
    try web.start();
    defer web.stop();

    var recorder: Recorder = .{};
    defer recorder.deinit();
    const core = try liveForwardCore(&recorder, url, made.session, made.block, null);
    testing.allocator.free(core.forwardOpen(testing.allocator, made.block, "127.0.0.1", web.port()) catch |err| {
        core.destroy();
        return err;
    });
    try testing.expectEqual(@as(usize, 1), try liveTunnels(&desk, made.session));
    core.destroy();
    try expectNoTunnels(&desk, made.session);
}

/// Whether a thread of this process is named `name` (Linux keeps it in /proc/self/task/<tid>/comm).
/// The core's threads name themselves as they start, so this waits up to five seconds
/// (the host can be loaded); it returns as soon as the name is there.
fn threadNamed(name: []const u8) !bool {
    for (0..500) |_| {
        var dir = try std.Io.Dir.openDirAbsolute(testing.io, "/proc/self/task", .{ .iterate = true });
        defer dir.close(testing.io);
        var it = dir.iterate();
        while (try it.next(testing.io)) |entry| {
            var path_buf: [64]u8 = undefined;
            const path = try std.fmt.bufPrint(&path_buf, "{s}/comm", .{entry.name});
            var buf: [32]u8 = undefined;
            const comm = dir.readFile(testing.io, path, &buf) catch continue;
            if (std.mem.eql(u8, std.mem.trimEnd(u8, comm, "\n"), name)) return true;
        }
        try std.Io.sleep(testing.io, .fromMilliseconds(10), .awake);
    }
    return false;
}

test "Core: its threads carry names a trace tells apart, a pane's with the end of its block id (#339)" {
    var recorder: Recorder = .{};
    defer recorder.deinit();
    const core = try withTerminal(&recorder, me);
    defer core.destroy();
    try testing.expect(try threadNamed("velo-control"));
    // `block:t`: what follows `block:`, at most its last four bytes.
    try testing.expect(try threadNamed("velo-data-t"));
}
