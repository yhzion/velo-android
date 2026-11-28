//! Host tests for the Model unit: `zig test native/src/rex/test_model.zig`.

const std = @import("std");
const json = std.json;
const testing = std.testing;
const model = @import("model.zig");
const protocol = @import("protocol.zig");
const StreamFilter = @import("StreamFilter.zig");

const welcome_json = @embedFile("testdata/welcome.json");
const bootstrap_json = @embedFile("testdata/bootstrap.json");
const events_jsonl = @embedFile("testdata/events.jsonl");
const view_json = @embedFile("testdata/view.json");
const view_floating_json = @embedFile("testdata/view_floating.json");
const stream_bin = @embedFile("testdata/stream.bin");
const hosts_json = @embedFile("testdata/hosts.json");

const session_id = "session:00000000-0000-7000-9000-000000000004";
const window_id = "window:00000000-0000-7000-a000-000000000006";
const block_a = "block:00000000-0000-7000-a000-000000000005";
const block_b = "block:00000000-0000-7000-a000-000000000010";
const block_c = "block:00000000-0000-7000-9000-00000000000a";
const block_d = "block:00000000-0000-7000-a000-000000000008";

fn parseValue(arena: std.mem.Allocator, text: []const u8) !json.Value {
    return json.parseFromSliceLeaky(json.Value, arena, text, .{});
}

fn expectStr(v: json.Value, name: []const u8, expected: []const u8) !void {
    const got = model.str(model.get(v, name)) orelse {
        std.debug.print("missing string field '{s}'\n", .{name});
        return error.TestExpectedEqual;
    };
    try testing.expectEqualStrings(expected, got);
}

fn expectFields(v: json.Value, names: []const []const u8) !void {
    try testing.expect(v == .object);
    try testing.expectEqual(names.len, v.object.count());
    for (names) |name| {
        if (v.object.get(name) == null) {
            std.debug.print("missing field '{s}'\n", .{name});
            return error.TestExpectedEqual;
        }
    }
}

fn expectNum(v: ?json.Value, expected: f64) !void {
    try testing.expectApproxEqAbs(expected, model.num(v) orelse return error.TestExpectedEqual, 1e-6);
}

fn leafCount(layout: ?*const model.Layout) usize {
    const l = layout orelse return 0;
    return switch (l.*) {
        .block => 1,
        .split => |s| leafCount(s.first) + leafCount(s.second),
    };
}

// --- model.parseView ------------------------------------------------------------------

test "parseView: view.json split directions, ratios and blocks" {
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    const a = arena.allocator();

    const view = try model.parseView(a, try parseValue(a, view_json));
    try testing.expectEqualStrings(session_id, view.session);
    try testing.expectEqualStrings("corelane-fixture", view.label);
    try testing.expectEqual(@as(i64, 9), view.revision);
    try testing.expectEqualStrings(window_id, view.active_window.?);
    try testing.expectEqual(@as(usize, 1), view.windows.len);

    const w = view.windows[0];
    try testing.expectEqualStrings(window_id, w.id);
    try testing.expectEqualStrings("Window 00000006", w.label);
    try testing.expectEqualStrings(block_a, w.focused.?);
    try testing.expectEqual(@as(?model.Id, null), w.zoomed);
    try testing.expectEqual(@as(usize, 0), w.floating.len);

    // horizontal root: [vertical: [horizontal: a | b] / c] | d
    const root = w.layout.?.split;
    try testing.expectEqual(false, root.column);
    try testing.expectApproxEqAbs(@as(f32, 0.5), root.ratio, 1e-6);
    try testing.expectEqualStrings(block_d, root.second.block);

    const left = root.first.split;
    try testing.expectEqual(true, left.column);
    try testing.expectApproxEqAbs(@as(f32, 0.5), left.ratio, 1e-6);
    try testing.expectEqualStrings(block_c, left.second.block);

    const top = left.first.split;
    try testing.expectEqual(false, top.column);
    try testing.expectApproxEqAbs(@as(f32, 0.5), top.ratio, 1e-6);
    try testing.expectEqualStrings(block_a, top.first.block);
    try testing.expectEqualStrings(block_b, top.second.block);

    try testing.expectEqual(@as(usize, 4), leafCount(w.layout));
    try testing.expectEqual(@as(usize, 4), w.blocks.len);
    for (w.blocks) |b| {
        try testing.expect(b.terminal);
        try testing.expectEqualStrings("shell", b.label);
        try testing.expectEqual(@as(?model.Id, null), b.owner);
    }
    try testing.expectEqualStrings(block_a, w.blocks[0].id);
}

test "parseView: view_floating.json yields one Floating with the layer bounds" {
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    const a = arena.allocator();

    const view = try model.parseView(a, try parseValue(a, view_floating_json));
    const w = view.windows[0];
    try testing.expectEqual(@as(usize, 1), w.floating.len);
    const f = w.floating[0];
    try testing.expectEqualStrings(block_d, f.block);
    try testing.expectEqualStrings("layer:00000000-0000-4000-8000-0000000000f1", f.layer);
    try testing.expectApproxEqAbs(@as(f32, 0.2), f.x, 1e-6);
    try testing.expectApproxEqAbs(@as(f32, 0.15), f.y, 1e-6);
    try testing.expectApproxEqAbs(@as(f32, 0.5), f.w, 1e-6);
    try testing.expectApproxEqAbs(@as(f32, 0.6), f.h, 1e-6);

    // The tiled layer holds the other three blocks; the floating one is not in the layout.
    try testing.expectEqual(@as(usize, 3), leafCount(w.layout));
    try testing.expectEqual(true, w.layout.?.split.column);
    try testing.expectEqual(false, w.layout.?.split.first.split.column);
    try testing.expectEqual(@as(usize, 4), w.blocks.len);
    try testing.expectEqual(@as(?model.Id, null), w.zoomed); // "zoomed_block_id": null
}

test "parseView: minimized layers, split floating layers, owners, unknown values" {
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    const a = arena.allocator();

    const text =
        \\{"revision":3,"session_id":"session:s","windows":[
        \\ {"window_id":"window:w","zoomed_block_id":"block:t","layers":[
        \\  {"layer_id":"layer:t","kind":"tiled","bounds":[0,0,1,1],
        \\   "layout":{"rect":[0,0,1,1],"split":{"direction":"diagonal","ratio":0.25,
        \\     "before":{"rect":[0,0,0.25,1],"block_id":"block:t"},
        \\     "after":{"rect":[0.25,0,0.75,1],"block_id":"block:u"}}},
        \\   "blocks":[{"block_id":"block:t","creator_name":"com.superlogical.terminal","owner":"client:c"},
        \\             {"block_id":"block:u","label":"web","creator_name":"com.example.browser"}]},
        \\  {"layer_id":"layer:m","kind":"floating","state":"minimized","bounds":[0,0,0.5,0.5],
        \\   "layout":{"rect":[0,0,0.5,0.5],"block_id":"block:m"},"blocks":[{"block_id":"block:m"}]},
        \\  {"layer_id":"layer:f","kind":"popup","bounds":[0.1,0.1,0.8,0.4],
        \\   "layout":{"rect":[0.1,0.1,0.8,0.4],"split":{"direction":"horizontal","ratio":0.5,
        \\     "before":{"rect":[0.1,0.1,0.4,0.4],"block_id":"block:x"},
        \\     "after":{"rect":[0.5,0.1,0.4,0.4],"block_id":"block:y"}}},
        \\   "blocks":[{"block_id":"block:x"},{"block_id":"block:y"}]},
        \\  {"layer_id":"layer:e","kind":"floating","bounds":[0,0,1,1],"layout":null,"blocks":null}
        \\ ]}]}
    ;
    const view = try model.parseView(a, try parseValue(a, text));
    try testing.expectEqual(@as(?model.Id, null), view.active_window);
    try testing.expectEqualStrings("", view.label);
    const w = view.windows[0];
    try testing.expectEqualStrings("block:t", w.zoomed.?);
    try testing.expectEqual(@as(?model.Id, null), w.focused);

    // Unknown direction draws as horizontal.
    try testing.expectEqual(false, w.layout.?.split.column);
    try testing.expectApproxEqAbs(@as(f32, 0.25), w.layout.?.split.ratio, 1e-6);

    // Minimized layer skipped; unknown kind is floating; one Floating per leaf rect.
    try testing.expectEqual(@as(usize, 2), w.floating.len);
    try testing.expectEqualStrings("block:x", w.floating[0].block);
    try testing.expectEqualStrings("layer:f", w.floating[0].layer);
    try testing.expectApproxEqAbs(@as(f32, 0.4), w.floating[0].w, 1e-6);
    try testing.expectEqualStrings("block:y", w.floating[1].block);
    try testing.expectApproxEqAbs(@as(f32, 0.5), w.floating[1].x, 1e-6);

    try testing.expectEqual(@as(usize, 4), w.blocks.len);
    try testing.expect(w.blocks[0].terminal);
    try testing.expectEqualStrings("client:c", w.blocks[0].owner.?);
    try testing.expect(!w.blocks[1].terminal);
    try testing.expectEqualStrings("web", w.blocks[1].label);

    try testing.expectError(error.InvalidView, model.parseView(a, try parseValue(a, "[]")));
    try testing.expectError(error.InvalidView, model.parseView(a, try parseValue(a, "{\"revision\":1}")));
}

// --- model.stateJson ------------------------------------------------------------------

test "stateSnapshot: whole changed groups reconstruct the next state" {
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    const a = arena.allocator();
    const view = try model.parseView(a, try parseValue(a, view_json));
    const old_label = view.windows[0].label;
    const escaped = "line\n\"},{\"blocks\":{},\\end";
    var infos: std.StringHashMapUnmanaged(model.BlockInfo) = .empty;
    try infos.put(a, block_b, .{ .title = escaped });
    const base: model.UiState = .{
        .status = .connected,
        .server = "rex",
        .url = "https://rex",
        .session = session_id,
        .sessions = &.{},
        .view = &view,
        .blocks = &.empty,
    };
    const previous = try model.stateSnapshot(testing.allocator, base);
    defer previous.deinit(testing.allocator);
    var scratch: std.ArrayList(u8) = .empty;
    defer scratch.deinit(testing.allocator);

    // Each bit independently changes one group. Include disjoint groups to exercise
    // joining fragments whose original neighbours are not in the patch.
    const groups = [_][]const []const u8{
        &.{ "status", "server", "url", "account", "hosts", "hostsHash", "session", "window", "focused", "forwards" },
        &.{"sessions"},
        &.{"windows"},
        &.{"blocks"},
    };
    for (0..16) |mask| {
        var state = base;
        if (mask & 1 != 0) state.session = null;
        if (mask & 2 != 0) state.sessions = &.{.{ .id = session_id, .label = escaped }};
        view.windows[0].label = if (mask & 4 != 0) escaped else old_label;
        if (mask & 8 != 0) state.blocks = &infos;
        const current = try model.stateSnapshot(testing.allocator, state);
        defer current.deinit(testing.allocator);

        var no_memory = std.heap.FixedBufferAllocator.init(&.{});
        const fast_path = mask == 0 or mask == 15;
        const patch = try current.delta(previous, if (fast_path) no_memory.allocator() else testing.allocator, &scratch);
        if (mask == 0) {
            try testing.expect(patch == null);
            continue;
        }
        const text = patch orelse return error.TestExpectedEqual;
        if (mask == 15) {
            try testing.expect(text.ptr == current.text.ptr);
            try testing.expectEqual(current.text.len, text.len);
        } else {
            try testing.expect(text.ptr == scratch.items.ptr);
        }
        const change = try parseValue(a, text);
        try testing.expect(change == .object);
        var count: usize = 0;
        for (groups, 0..) |fields, i| {
            if (mask & (@as(usize, 1) << @intCast(i)) == 0) continue;
            count += fields.len;
            for (fields) |field| try testing.expect(change.object.contains(field));
        }
        try testing.expectEqual(count, change.object.count());
        if (mask & 1 != 0) try testing.expect(change.object.get("session").? == .null);

        var reconstructed = try parseValue(a, previous.text);
        var it = change.object.iterator();
        while (it.next()) |entry| try reconstructed.object.put(a, entry.key_ptr.*, entry.value_ptr.*);
        const expected = try parseValue(a, current.text);
        try testing.expectEqualStrings(try json.Stringify.valueAlloc(a, expected, .{}), try json.Stringify.valueAlloc(a, reconstructed, .{}));
    }
}

test "stateJson: view.json round-trips as the JSON UiState.kt decodes" {
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    const a = arena.allocator();

    const view = try model.parseView(a, try parseValue(a, view_json));
    var infos: std.StringHashMapUnmanaged(model.BlockInfo) = .empty;
    try infos.put(a, block_b, .{ .title = "vim \"notes\"", .program = "vim", .cwd = "~/notes", .activity = .blocked, .since = at_1003 });
    const sessions = [_]model.Session{
        .{ .id = "session:other", .label = "work" },
        .{ .id = session_id, .label = "corelane-fixture" },
    };

    const text = try model.stateJson(testing.allocator, .{
        .status = .connected,
        .server = "rex.test.example",
        .url = "https://rex.test.example",
        .account = "alice",
        .sessions = &sessions,
        .session = session_id,
        .view = &view,
        .blocks = &infos,
    });
    defer testing.allocator.free(text);

    const root = try parseValue(a, text);
    try expectFields(root, &.{ "status", "server", "url", "account", "hosts", "hostsHash", "forwards", "sessions", "session", "windows", "window", "focused", "blocks" });
    try expectStr(root, "status", "connected");
    try expectStr(root, "server", "rex.test.example");
    try expectStr(root, "url", "https://rex.test.example");
    try expectStr(root, "account", "alice");
    try expectStr(root, "session", session_id);
    try expectStr(root, "window", window_id);
    try expectStr(root, "focused", block_a);

    const s = model.items(model.get(root, "sessions"));
    try testing.expectEqual(@as(usize, 2), s.len);
    // No view is named for either session here: neither has `tabs`.
    try expectFields(s[0], &.{ "id", "label" });
    try expectFields(s[1], &.{ "id", "label" });
    try expectStr(s[1], "id", session_id);
    try expectStr(s[1], "label", "corelane-fixture");

    const windows = model.items(model.get(root, "windows"));
    try testing.expectEqual(@as(usize, 1), windows.len);
    try expectFields(windows[0], &.{ "id", "label", "layout", "zoomed", "minimized", "floating" });
    try testing.expectEqual(false, model.boolean(model.get(windows[0], "minimized")).?);
    try expectStr(windows[0], "id", window_id);
    try expectStr(windows[0], "label", "Window 00000006");
    try testing.expect(model.get(windows[0], "zoomed").? == .null);
    try testing.expectEqual(@as(usize, 0), model.items(model.get(windows[0], "floating")).len);

    // "row" = Rex horizontal (side by side), "column" = Rex vertical.
    const layout = model.get(windows[0], "layout").?;
    try expectFields(layout, &.{ "dir", "ratio", "first", "second" });
    try expectStr(layout, "dir", "row");
    try expectNum(model.get(layout, "ratio"), 0.5);
    const second = model.get(layout, "second").?;
    try expectFields(second, &.{"block"});
    try expectStr(second, "block", block_d);
    const first = model.get(layout, "first").?;
    try expectStr(first, "dir", "column");
    try expectStr(model.get(first, "second").?, "block", block_c);
    const nested = model.get(first, "first").?;
    try expectStr(nested, "dir", "row");
    try expectStr(model.get(nested, "first").?, "block", block_a);
    try expectStr(model.get(nested, "second").?, "block", block_b);

    const blocks = model.get(root, "blocks").?;
    try testing.expectEqual(@as(usize, 4), blocks.object.count());
    const plain = blocks.object.get(block_a).?;
    try expectFields(plain, &.{ "titleIsLabel", "title", "program", "cwd", "activity", "attention" });
    try expectStr(plain, "title", "shell"); // falls back to the label
    try expectStr(plain, "program", "");
    try expectStr(plain, "activity", "idle");
    try testing.expectEqual(false, model.boolean(model.get(plain, "attention")).?);
    const waiting = blocks.object.get(block_b).?;
    try expectStr(waiting, "title", "vim \"notes\""); // reported title
    try expectStr(waiting, "program", "vim");
    try expectStr(waiting, "cwd", "~/notes");
    try expectStr(waiting, "activity", "blocked");
    try testing.expectEqual(true, model.boolean(model.get(waiting, "attention")).?);
    // Since when it waits; an idle block names no time.
    try expectFields(waiting, &.{ "icon", "title", "program", "cwd", "activity", "since", "attention" });
    // Its program names it, as a tab's lead pane is named (#217).
    try expectStr(waiting, "icon", "vim");
    try testing.expectEqual(@as(?i64, at_1003), model.int(model.get(waiting, "since")));
}

test "tildePath writes the home directory as ~ and leaves other paths alone" {
    var buf: [64]u8 = undefined;
    try testing.expectEqualStrings("~/src/app", model.tildePath(&buf, "/home/alice/src/app", "/home/alice"));
    try testing.expectEqualStrings("~", model.tildePath(&buf, "/home/alice", "/home/alice"));
    // Another account whose name starts the same way is not under this home.
    try testing.expectEqualStrings("/home/alice2/x", model.tildePath(&buf, "/home/alice2/x", "/home/alice"));
    try testing.expectEqualStrings("/etc", model.tildePath(&buf, "/etc", "/home/alice"));
    try testing.expectEqualStrings("/etc", model.tildePath(&buf, "/etc", ""));
    try testing.expectEqualStrings("/etc", model.tildePath(&buf, "/etc", "/"));
}

/// 2026-11-20T10:03:00Z in epoch milliseconds.
const at_1003: i64 = 1795168980_000;

test "parseRfc3339Ms: Z, numeric offsets and fractions give the same instant in epoch milliseconds" {
    const parse = model.parseRfc3339Ms;
    try testing.expectEqual(@as(?i64, at_1003), parse("2026-11-20T10:03:00Z"));
    try testing.expectEqual(@as(?i64, at_1003), parse("2026-11-20t10:03:00z"));
    try testing.expectEqual(@as(?i64, at_1003), parse("2026-11-20 10:03:00+00:00"));
    // The same instant on other clocks, east and west of UTC and across midnight.
    try testing.expectEqual(@as(?i64, at_1003), parse("2026-11-20T19:03:00+09:00"));
    try testing.expectEqual(@as(?i64, at_1003), parse("2026-11-20T05:33:00-04:30"));
    try testing.expectEqual(@as(?i64, at_1003), parse("2026-11-21T00:03:00+14:00"));
    try testing.expectEqual(@as(?i64, at_1003), parse("2026-11-19T22:03:00-12:00"));
    // Fractions of any length are cut to milliseconds.
    try testing.expectEqual(@as(?i64, at_1003 + 500), parse("2026-11-20T10:03:00.5Z"));
    try testing.expectEqual(@as(?i64, at_1003 + 250), parse("2026-11-20T19:03:00.25+09:00"));
    try testing.expectEqual(@as(?i64, at_1003 + 7), parse("2026-11-20T10:03:00.007Z"));
    try testing.expectEqual(@as(?i64, at_1003 + 123), parse("2026-11-20T10:03:00.123456789Z"));
    try testing.expectEqual(@as(?i64, at_1003 + 999), parse("2026-11-20T10:03:00.9999-00:00"));
    // The calendar: the epoch, the second before it, a leap day and a leap second.
    try testing.expectEqual(@as(?i64, 0), parse("1970-01-01T00:00:00Z"));
    try testing.expectEqual(@as(?i64, -1000), parse("1969-12-31T23:59:59Z"));
    try testing.expectEqual(@as(?i64, 1709208000_000), parse("2024-02-29T12:00:00Z"));
    try testing.expectEqual(@as(?i64, 951782400_000), parse("2000-02-29T00:00:00Z"));
    try testing.expectEqual(@as(?i64, 1483228800_000), parse("2016-12-31T23:59:60Z"));
}

test "parseRfc3339Ms: what is not an RFC 3339 timestamp is no time" {
    for ([_][]const u8{
        "",
        "now",
        "1795168980000",
        "2026-11-20",
        "2026-11-20T10:03:00", // no offset: whose clock?
        "2026-11-20T10:03Z",
        "2026-11-20T10:03:00.Z",
        "2026-11-20T10:03:00.5",
        "2026-11-20T10:03:00+0900",
        "2026-11-20T10:03:00+09",
        "2026-11-20T10:03:00+24:00",
        "2026-11-20T10:03:00+09:60",
        "2026-11-20T10:03:00Zx",
        "2026-11-20T10:03:00ZZ",
        " 2026-11-20T10:03:00Z",
        "2026/11/20T10:03:00Z",
        "2026-11-20T10.03.00Z",
        "+026-11-20T10:03:00Z",
        "2_26-11-20T10:03:00Z",
        "2026-11-20T10:03:00.5_Z",
        "2026-11-20T10:03:00-0:30",
        "2026-1_-20T10:03:00Z",
        "2026-00-20T10:03:00Z",
        "2026-13-20T10:03:00Z",
        "2026-11-00T10:03:00Z",
        "2026-11-31T10:03:00Z",
        "2026-02-29T10:03:00Z", // not a leap year
        "1900-02-29T10:03:00Z", // nor this one
        "2026-11-20T24:00:00Z",
        "2026-11-20T10:60:00Z",
        "2026-11-20T10:03:61Z",
        "2026-11-20T23:59:60Z", // leap seconds occur at the end of a UTC month
    }) |text| {
        if (model.parseRfc3339Ms(text)) |ms| {
            std.debug.print("'{s}' was read as {d}\n", .{ text, ms });
            return error.TestExpectedEqual;
        }
    }
}

test "Activity.parse treats unknown and missing states as idle" {
    try testing.expectEqual(model.Activity.blocked, model.Activity.parse("blocked"));
    try testing.expectEqual(model.Activity.@"error", model.Activity.parse("error"));
    try testing.expectEqual(model.Activity.working, model.Activity.parse("working"));
    try testing.expectEqual(model.Activity.idle, model.Activity.parse("paused"));
    try testing.expectEqual(model.Activity.idle, model.Activity.parse(null));
}

test "stateJson: view_floating.json emits the floating entry and blocks map" {
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    const a = arena.allocator();

    const view = try model.parseView(a, try parseValue(a, view_floating_json));
    const infos: std.StringHashMapUnmanaged(model.BlockInfo) = .empty;
    const text = try model.stateJson(testing.allocator, .{
        .status = .reconnecting,
        .server = "",
        .url = "",
        .sessions = &.{},
        .session = session_id,
        .view = &view,
        .blocks = &infos,
    });
    defer testing.allocator.free(text);

    // Fractions print as the shortest decimal, not as widened f32 noise.
    try testing.expect(std.mem.find(u8, text, "\"x\":0.2,\"y\":0.15,\"w\":0.5,\"h\":0.6") != null);

    const root = try parseValue(a, text);
    try expectStr(root, "status", "reconnecting");
    const w = model.items(model.get(root, "windows"))[0];
    const layout = model.get(w, "layout").?;
    try expectStr(layout, "dir", "column");
    try expectStr(model.get(layout, "first").?, "dir", "row");

    const floating = model.items(model.get(w, "floating"));
    try testing.expectEqual(@as(usize, 1), floating.len);
    try expectFields(floating[0], &.{ "block", "x", "y", "w", "h" });
    try expectStr(floating[0], "block", block_d);
    try expectNum(model.get(floating[0], "x"), 0.2);
    try expectNum(model.get(floating[0], "y"), 0.15);
    try expectNum(model.get(floating[0], "w"), 0.5);
    try expectNum(model.get(floating[0], "h"), 0.6);

    const blocks = model.get(root, "blocks").?;
    try testing.expectEqual(@as(usize, 4), blocks.object.count());
    try testing.expect(blocks.object.get(block_d) != null);
}

test "stateJson: no view, every status, invalid UTF-8 and allocation failure" {
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    const a = arena.allocator();
    const infos: std.StringHashMapUnmanaged(model.BlockInfo) = .empty;

    inline for (.{ "idle", "connecting", "connected", "reconnecting", "error" }) |name| {
        const text = try model.stateJson(testing.allocator, .{
            .status = @field(model.Status, name),
            .server = "bad \xff name",
            .url = "",
            .sessions = &.{},
            .session = null,
            .view = null,
            .blocks = &infos,
        });
        defer testing.allocator.free(text);
        const root = try parseValue(a, text);
        try expectStr(root, "status", name);
        try expectStr(root, "server", "bad \u{FFFD} name");
        try testing.expect(model.get(root, "session").? == .null);
        try testing.expect(model.get(root, "window").? == .null);
        try testing.expect(model.get(root, "focused").? == .null);
        try testing.expectEqual(@as(usize, 0), model.items(model.get(root, "windows")).len);
        try testing.expectEqual(@as(usize, 0), model.get(root, "blocks").?.object.count());
    }

    const view = try model.parseView(a, try parseValue(a, view_floating_json));
    try testing.checkAllAllocationFailures(testing.allocator, stateJsonOnce, .{ &view, &infos });
}

fn stateJsonOnce(gpa: std.mem.Allocator, view: *const model.View, infos: *const std.StringHashMapUnmanaged(model.BlockInfo)) !void {
    const text = try model.stateJson(gpa, .{
        .status = .connected,
        .server = "s",
        .url = "u",
        .sessions = &.{.{ .id = "session:x", .label = "x" }},
        .session = "session:x",
        .view = view,
        .blocks = infos,
    });
    gpa.free(text);
}

// --- hosts ----------------------------------------------------------------------------

test "parseHosts: hosts.json" {
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    const a = arena.allocator();

    const hosts = try model.parseHosts(a, try parseValue(a, hosts_json));
    try testing.expectEqual(@as(usize, 2), hosts.len);
    try testing.expectEqualStrings("host:00000000-0000-7000-a000-000000000001", hosts[0].id);
    try testing.expectEqualStrings("demo_user", hosts[0].label);
    try testing.expectEqual(@as(usize, 2), hosts[0].endpoints.len);
    try testing.expectEqualStrings("ssh://demo_user@192.0.2.10", hosts[0].endpoints[0]);
    try testing.expectEqualStrings("https://rex.fixture.example", hosts[0].endpoints[1]);
    try testing.expectEqualStrings("", hosts[0].icon);
    try testing.expectEqualStrings("host:00000000-0000-4000-9000-00000000001c", hosts[1].id);
    try testing.expectEqualStrings("demo_user@192.0.2.10", hosts[1].label);
    try testing.expectEqualStrings("globe", hosts[1].icon);
    try testing.expectEqual(@as(usize, 1), hosts[1].endpoints.len);
    try testing.expectEqualStrings("ssh://demo_user@192.0.2.10", hosts[1].endpoints[0]);

    // Entries without a label or with an empty one are skipped; a missing id or icon is "";
    // null lists are empty.
    const odd = try model.parseHosts(a, try parseValue(a,
        \\{"hosts":[{"id":"host:x","endpoints":["https://a.example"]},
        \\ {"id":"host:y","label":"","endpoints":["https://c.example"]},
        \\ {"label":"bare","endpoints":null},
        \\ {"label":"mixed","endpoints":["https://b.example",7,null]}],"hash":"h"}
    ));
    try testing.expectEqual(@as(usize, 2), odd.len);
    try testing.expectEqualStrings("bare", odd[0].label);
    try testing.expectEqualStrings("", odd[0].id);
    try testing.expectEqualStrings("", odd[0].icon);
    try testing.expectEqual(@as(usize, 0), odd[0].endpoints.len);
    try testing.expectEqual(@as(usize, 1), odd[1].endpoints.len);
    try testing.expectEqualStrings("https://b.example", odd[1].endpoints[0]);
    try testing.expectEqual(@as(usize, 0), (try model.parseHosts(a, try parseValue(a, "{\"hosts\":null,\"hash\":\"h\"}"))).len);
    try testing.expectError(error.InvalidView, model.parseHosts(a, try parseValue(a, "[]")));
}

test "stateJson: hosts and hostsHash" {
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    const a = arena.allocator();
    const infos: std.StringHashMapUnmanaged(model.BlockInfo) = .empty;

    const hosts = try model.parseHosts(a, try parseValue(a, hosts_json));
    const text = try model.stateJson(testing.allocator, .{
        .status = .connected,
        .server = "s",
        .url = "u",
        .hosts = hosts,
        .hosts_hash = "3a7bd3e2",
        .sessions = &.{},
        .session = null,
        .view = null,
        .blocks = &infos,
    });
    defer testing.allocator.free(text);
    const root = try parseValue(a, text);
    try expectStr(root, "hostsHash", "3a7bd3e2");
    const list = model.items(model.get(root, "hosts"));
    try testing.expectEqual(@as(usize, 2), list.len);
    try expectFields(list[0], &.{ "id", "label", "endpoints", "icon" });
    try expectStr(list[0], "id", "host:00000000-0000-7000-a000-000000000001");
    try expectStr(list[0], "label", "demo_user");
    try expectStr(list[0], "icon", "");
    const endpoints = model.items(model.get(list[0], "endpoints"));
    try testing.expectEqual(@as(usize, 2), endpoints.len);
    try testing.expectEqualStrings("ssh://demo_user@192.0.2.10", endpoints[0].string);
    try testing.expectEqualStrings("https://rex.fixture.example", endpoints[1].string);
    try expectStr(list[1], "icon", "globe");

    const empty = try model.stateJson(testing.allocator, .{
        .status = .idle,
        .server = "",
        .url = "",
        .sessions = &.{},
        .session = null,
        .view = null,
        .blocks = &infos,
    });
    defer testing.allocator.free(empty);
    try testing.expect(std.mem.find(u8, empty, "\"hosts\":[]") != null);
    try testing.expect(std.mem.find(u8, empty, "\"hostsHash\":\"\"") != null);
}

test "stateJson: every session lists its tabs with their lead block" {
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    const a = arena.allocator();

    // One tab with one shell.
    const one = try model.parseView(a, try parseValue(a,
        \\{"session_id":"session:one","label":"one","revision":3,"active_window_id":"window:1","windows":[
        \\{"window_id":"window:1","label":"Window 11111111","icon":"ssh","focused_block_id":"block:1","layers":[
        \\{"layer_id":"layer:1","kind":"tiled","bounds":[0,0,1,1],"layout":{"rect":[0,0,1,1],"block_id":"block:1"},
        \\"blocks":[{"block_id":"block:1","label":"shell","creator_name":"com.superlogical.terminal"}]}]}]}
    ));
    // Three tabs: a split with a floating pane (focus on the second block), the active
    // tab without a focused block, and a tab with no blocks at all.
    const three = try model.parseView(a, try parseValue(a,
        \\{"session_id":"session:three","label":"three","revision":8,"active_window_id":"window:b","windows":[
        \\{"window_id":"window:a","label":"editor","icon":"claude","focused_block_id":"block:a2","layers":[
        \\{"layer_id":"layer:a","kind":"tiled","bounds":[0,0,1,1],"layout":{"rect":[0,0,1,1],"split":{
        \\"split_id":"split:a","direction":"horizontal","ratio":0.5,
        \\"before":{"rect":[0,0,0.5,1],"block_id":"block:a1"},"after":{"rect":[0.5,0,0.5,1],"block_id":"block:a2"}}},
        \\"blocks":[{"block_id":"block:a1","label":"shell","creator_name":"com.superlogical.terminal"},
        \\{"block_id":"block:a2","label":"shell","creator_name":"com.superlogical.terminal"}]},
        \\{"layer_id":"layer:f","kind":"floating","bounds":[0.1,0.1,0.5,0.5],"layout":{"rect":[0.1,0.1,0.5,0.5],"block_id":"block:a3"},
        \\"blocks":[{"block_id":"block:a3","label":"shell","creator_name":"com.superlogical.terminal"}]}]},
        \\{"window_id":"window:b","label":"Window bbbbbbbb","icon":" ","layers":[
        \\{"layer_id":"layer:b","kind":"tiled","bounds":[0,0,1,1],"layout":{"rect":[0,0,1,1],"block_id":"block:b1"},
        \\"blocks":[{"block_id":"block:b1","label":"shell","creator_name":"com.superlogical.terminal"}]}]},
        \\{"window_id":"window:c","label":"empty","layers":[
        \\{"layer_id":"layer:c","kind":"tiled","bounds":[0,0,1,1],"layout":null,"blocks":[]}]},
        \\{"window_id":"window:d","label":"hidden","focused_block_id":"block:d1","layers":[
        \\{"layer_id":"layer:d","kind":"floating","state":"minimized","bounds":[0,0,1,1],"layout":{"rect":[0,0,1,1],"block_id":"block:d1"},
        \\"blocks":[{"block_id":"block:d1","label":"shell","creator_name":"com.superlogical.terminal"}]}]}]}
    ));
    // A session whose windows are known: it has none.
    const bare = try model.parseView(a, try parseValue(a,
        \\{"session_id":"session:bare","label":"bare","revision":1,"windows":[]}
    ));

    // `one` is the selected session: its blocks come from the panes. `three` is followed.
    var panes: std.StringHashMapUnmanaged(model.BlockInfo) = .empty;
    // A time left over from a state that ended is not published with an idle block.
    try panes.put(a, "block:1", .{ .title = "alice@mac:~", .program = "zsh", .cwd = "~", .since = at_1003 });
    var leads: std.StringHashMapUnmanaged(model.BlockInfo) = .empty;
    try leads.put(a, "block:a2", .{ .title = "claude", .program = "node", .cwd = "~/src/app", .activity = .blocked, .since = at_1003 });
    try leads.put(a, "block:b1", .{ .program = "/usr/bin/vim", .cwd = "/etc", .activity = .working });

    const sessions = [_]model.Session{
        .{ .id = "session:one", .label = "one", .view = &one },
        .{ .id = "session:three", .label = "three", .view = &three },
        .{ .id = "session:new", .label = "new" }, // its view has not arrived
        .{ .id = "session:bare", .label = "bare", .view = &bare },
    };
    const text = try model.stateJson(testing.allocator, .{
        .status = .connected,
        .server = "rex.test.example",
        .url = "https://rex.test.example",
        .sessions = &sessions,
        .session = "session:one",
        .view = &one,
        .blocks = &panes,
        .leads = &leads,
    });
    defer testing.allocator.free(text);

    // The exact text of the selected session's entry: field names and their order.
    try testing.expect(std.mem.find(u8, text, "{\"id\":\"session:one\",\"label\":\"one\",\"tabs\":[" ++
        "{\"id\":\"window:1\",\"label\":\"Window 11111111\",\"icon\":\"ssh\",\"panes\":1,\"terminalBlocks\":[\"block:1\"],\"active\":true,\"minimized\":false,\"block\":\"block:1\"," ++
        "\"title\":\"alice@mac:~\",\"program\":\"zsh\",\"cwd\":\"~\",\"activity\":\"idle\",\"attention\":false}]}") != null);

    const root = try parseValue(a, text);
    const list = model.items(model.get(root, "sessions"));
    try testing.expectEqual(@as(usize, 4), list.len);
    for (list[0..2]) |s| try expectFields(s, &.{ "id", "label", "tabs" });
    try testing.expectEqual(@as(usize, 1), model.items(model.get(list[0], "tabs")).len);
    // Unknown windows: no `tabs` at all. Known and none: an empty list.
    try expectFields(list[2], &.{ "id", "label" });
    try expectFields(list[3], &.{ "id", "label", "tabs" });
    try testing.expect(model.get(list[3], "tabs").? == .array);
    try testing.expectEqual(@as(usize, 0), model.items(model.get(list[3], "tabs")).len);
    try testing.expect(std.mem.find(u8, text, "{\"id\":\"session:new\",\"label\":\"new\"}") != null);
    try testing.expect(std.mem.find(u8, text, "{\"id\":\"session:bare\",\"label\":\"bare\",\"tabs\":[]}") != null);

    const tabs = model.items(model.get(list[1], "tabs"));
    try testing.expectEqual(@as(usize, 4), tabs.len);
    const full = [_][]const u8{ "id", "label", "icon", "panes", "terminalBlocks", "active", "minimized", "block", "title", "program", "cwd", "activity", "attention" };

    // Window order; tiled and floating blocks both count; the lead is the focused block.
    try expectFields(tabs[0], &(full ++ .{"since"}));
    try expectStr(tabs[0], "id", "window:a");
    try expectStr(tabs[0], "label", "editor");
    try expectStr(tabs[0], "icon", "claude");
    try expectNum(model.get(tabs[0], "panes"), 3);
    try testing.expectEqual(false, model.boolean(model.get(tabs[0], "active")).?);
    try expectStr(tabs[0], "block", "block:a2");
    try expectStr(tabs[0], "title", "claude");
    try expectStr(tabs[0], "program", "node");
    try expectStr(tabs[0], "cwd", "~/src/app");
    try expectStr(tabs[0], "activity", "blocked");
    try testing.expectEqual(true, model.boolean(model.get(tabs[0], "attention")).?);
    // `since` stands beside the state it times, as epoch milliseconds.
    try testing.expect(std.mem.find(u8, text, "\"activity\":\"blocked\",\"since\":1795168980000,\"attention\":true}") != null);

    // Without a focused block the lead is the first; without a title its label stands in.
    // Its program works and the server gave no time: `since` is left out.
    try expectFields(tabs[1], &(full ++ .{"titleIsLabel"}));
    try expectStr(tabs[1], "id", "window:b");
    try expectStr(tabs[1], "icon", "vim");
    try expectNum(model.get(tabs[1], "panes"), 1);
    try testing.expectEqual(true, model.boolean(model.get(tabs[1], "active")).?);
    try expectStr(tabs[1], "block", "block:b1");
    try expectStr(tabs[1], "title", "shell");
    try expectStr(tabs[1], "program", "/usr/bin/vim");
    try expectStr(tabs[1], "cwd", "/etc");
    try expectStr(tabs[1], "activity", "working");
    try testing.expectEqual(false, model.boolean(model.get(tabs[1], "attention")).?);

    // A tab without blocks names no lead block.
    try expectFields(tabs[2], &.{ "id", "label", "panes", "terminalBlocks", "active", "minimized", "title", "program", "cwd", "activity", "attention" });
    try expectStr(tabs[2], "id", "window:c");
    try expectNum(model.get(tabs[2], "panes"), 0);
    try testing.expectEqual(false, model.boolean(model.get(tabs[2], "active")).?);
    try expectStr(tabs[2], "title", "");
    // Minimized layers are not counted: a tab with nothing else shows no pane and no lead.
    try expectFields(tabs[3], &.{ "id", "label", "panes", "terminalBlocks", "active", "minimized", "title", "program", "cwd", "activity", "attention" });
    try expectStr(tabs[3], "id", "window:d");
    try expectNum(model.get(tabs[3], "panes"), 0);
    try expectStr(tabs[3], "title", "");

    // The selected session's top-level fields agree with its tabs.
    try expectStr(root, "session", "session:one");
    try expectStr(root, "window", "window:1");
    try expectStr(root, "focused", "block:1");
    const top = model.get(root, "blocks").?.object.get("block:1").?;
    const tab = model.items(model.get(list[0], "tabs"))[0];
    const window = model.items(model.get(root, "windows"))[0];
    try expectStr(window, "id", "window:1");
    try expectStr(window, "label", "Window 11111111");
    try expectStr(window, "icon", "ssh");
    inline for (.{ "title", "program", "cwd", "activity" }) |name| try expectStr(top, name, model.str(model.get(tab, name)).?);
    // Blocks of the other sessions are not in `blocks`.
    try testing.expectEqual(@as(usize, 1), model.get(root, "blocks").?.object.count());
}

test "stateJson: minimized tells an empty tab, one whose panes are all minimized, a mixed one and a plain shell apart" {
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    const a = arena.allocator();
    const shell = "\"creator_name\":\"com.superlogical.terminal\"";
    const text_view =
        \\{"session_id":"session:s","label":"s","revision":3,"active_window_id":"window:mixed","windows":[
        \\{"window_id":"window:empty","label":"empty","layers":[
        \\{"layer_id":"layer:e","kind":"tiled","bounds":[0,0,1,1],"layout":null,"blocks":[]}]},
        \\{"window_id":"window:hidden","label":"hidden","layers":[
        \\{"layer_id":"layer:h0","kind":"tiled","bounds":[0,0,1,1],"layout":null,"blocks":[]},
        \\{"layer_id":"layer:h1","kind":"floating","state":"minimized","bounds":[0,0,1,1],"layout":{"rect":[0,0,1,1],"block_id":"block:h1"},
        \\"blocks":[{"block_id":"block:h1","label":"shell",
    ++ shell ++
        \\}]}]},
        \\{"window_id":"window:mixed","label":"mixed","focused_block_id":"block:m1","layers":[
        \\{"layer_id":"layer:m0","kind":"tiled","bounds":[0,0,1,1],"layout":{"rect":[0,0,1,1],"block_id":"block:m1"},
        \\"blocks":[{"block_id":"block:m1","label":"shell",
    ++ shell ++
        \\}]},
        \\{"layer_id":"layer:m1","kind":"floating","state":"minimized","bounds":[0.1,0.1,0.5,0.5],"layout":{"rect":[0.1,0.1,0.5,0.5],"block_id":"block:m2"},
        \\"blocks":[{"block_id":"block:m2","label":"build",
    ++ shell ++
        \\}]},
        \\{"layer_id":"layer:m2","kind":"floating","state":"minimized","bounds":[0,0,1,1],"layout":null,"blocks":[]}]},
        \\{"window_id":"window:plain","label":"plain","layers":[
        \\{"layer_id":"layer:p0","kind":"tiled","bounds":[0,0,1,1],"layout":{"rect":[0,0,1,1],"block_id":"block:p1"},
        \\"blocks":[{"block_id":"block:p1","label":"shell",
    ++ shell ++
        \\}]},
        \\{"layer_id":"layer:p1","kind":"floating","state":"minimized","bounds":[0,0,1,1],"layout":null,"blocks":[]}]}]}
    ;
    const view = try model.parseView(a, try parseValue(a, text_view));
    try testing.expectEqual(false, view.windows[0].minimized);
    try testing.expectEqual(true, view.windows[1].minimized);
    try testing.expectEqual(true, view.windows[2].minimized);
    // A minimized layer that holds no block hides nothing.
    try testing.expectEqual(false, view.windows[3].minimized);

    var none: std.StringHashMapUnmanaged(model.BlockInfo) = .empty;
    const Case = struct { id: []const u8, panes: f64, minimized: bool, block: ?[]const u8 };
    const cases = [_]Case{
        .{ .id = "window:empty", .panes = 0, .minimized = false, .block = null },
        .{ .id = "window:hidden", .panes = 0, .minimized = true, .block = null },
        // The visible shell is counted and leads; the program that runs unseen is flagged.
        .{ .id = "window:mixed", .panes = 1, .minimized = true, .block = "block:m1" },
        .{ .id = "window:plain", .panes = 1, .minimized = false, .block = "block:p1" },
    };
    // As the selected session, and as a followed one: the same tabs either way.
    for ([_]bool{ true, false }) |selected| {
        const sessions = [_]model.Session{.{ .id = "session:s", .label = "s", .view = &view }};
        const text = try model.stateJson(testing.allocator, .{
            .status = .connected,
            .server = "rex.test.example",
            .url = "https://rex.test.example",
            .sessions = &sessions,
            .session = if (selected) "session:s" else null,
            .view = if (selected) &view else null,
            .blocks = &none,
            .leads = &none,
        });
        defer testing.allocator.free(text);
        const root = try parseValue(a, text);
        const tabs = model.items(model.get(model.items(model.get(root, "sessions"))[0], "tabs"));
        try testing.expectEqual(cases.len, tabs.len);
        for (cases, tabs) |want, tab| {
            try expectStr(tab, "id", want.id);
            try expectNum(model.get(tab, "panes"), want.panes);
            try testing.expect(model.get(tab, "minimized").? == .bool);
            try testing.expectEqual(want.minimized, model.boolean(model.get(tab, "minimized")).?);
            try testing.expectEqualStrings(want.block orelse "<none>", model.str(model.get(tab, "block")) orelse "<none>");
        }
        // The same value in both places: the tab and the selected session's window.
        const windows = model.items(model.get(root, "windows"));
        try testing.expectEqual(if (selected) cases.len else 0, windows.len);
        if (selected) for (windows, cases) |w, want| {
            try expectStr(w, "id", want.id);
            try testing.expectEqual(want.minimized, model.boolean(model.get(w, "minimized")).?);
        };
        if (selected) {
            // The mixed tab, as text: one pane, a hidden one, in both places.
            try testing.expect(std.mem.find(u8, text, "{\"id\":\"window:mixed\",\"label\":\"mixed\",\"panes\":1,\"terminalBlocks\":[\"block:m1\",\"block:m2\"],\"active\":true,\"minimized\":true,\"block\":\"block:m1\",") != null);
            try testing.expect(std.mem.find(u8, text, "\"zoomed\":null,\"minimized\":true,\"minimizedBlocks\":[\"block:m2\"],\"minimizedLayers\":[[\"block:m2\"]],\"floating\":[]}") != null);
            // The drawing is as before: the hidden block is in no layout and no floating list,
            // but it is named in the blocks map.
            try testing.expect(model.get(root, "blocks").?.object.contains("block:m2"));
            try testing.expectEqual(@as(usize, 0), model.items(model.get(windows[2], "floating")).len);
        }
    }
}

test "stateJson: every minimized pane is named, grouped by layer, with its block info" {
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    const a = arena.allocator();
    const view = try model.parseView(a, try parseValue(a,
        \\{"session_id":"session:s","label":"s","revision":3,"active_window_id":"w","windows":[
        \\{"window_id":"w","label":"w","focused_block_id":"a","layers":[
        \\{"layer_id":"layer:t","kind":"tiled","bounds":[0,0,1,1],"layout":{"rect":[0,0,1,1],"block_id":"a"},
        \\"blocks":[{"block_id":"a","title":"ta","creator_name":"com.superlogical.terminal"}]},
        \\{"layer_id":"layer:m1","kind":"floating","state":"minimized","bounds":[0.1,0.1,0.5,0.5],"layout":{"rect":[0.1,0.1,0.5,0.5],"block_id":"b"},
        \\"blocks":[{"block_id":"b","title":"tb","creator_name":"com.superlogical.terminal"}]},
        \\{"layer_id":"layer:m2","kind":"floating","state":"minimized","bounds":[0.1,0.1,0.5,0.5],"layout":{"rect":[0.1,0.1,0.5,0.5],"split":{"direction":"horizontal","ratio":0.5,
        \\"before":{"rect":[0.1,0.1,0.25,0.5],"block_id":"c"},"after":{"rect":[0.35,0.1,0.25,0.5],"block_id":"d"}}},
        \\"blocks":[{"block_id":"c","title":"tc"},{"block_id":"d","title":"td"}]}]}]}
    ));
    var none: std.StringHashMapUnmanaged(model.BlockInfo) = .empty;
    const sessions = [_]model.Session{.{ .id = "session:s", .label = "s", .view = &view }};
    const text = try model.stateJson(testing.allocator, .{
        .status = .connected,
        .server = "rex.test.example",
        .url = "https://rex.test.example",
        .sessions = &sessions,
        .session = "session:s",
        .view = &view,
        .blocks = &none,
        .leads = &none,
    });
    defer testing.allocator.free(text);
    const root = try parseValue(a, text);
    const w = model.items(model.get(root, "windows"))[0];
    try testing.expectEqual(true, model.boolean(model.get(w, "minimized")).?);
    const flat = model.items(model.get(w, "minimizedBlocks"));
    try testing.expectEqual(@as(usize, 3), flat.len);
    for ([_][]const u8{ "b", "c", "d" }, flat) |want, got| try testing.expectEqualStrings(want, model.str(got).?);
    const layers = model.items(model.get(w, "minimizedLayers"));
    try testing.expectEqual(@as(usize, 2), layers.len);
    const first = model.items(layers[0]);
    try testing.expectEqual(@as(usize, 1), first.len);
    try testing.expectEqualStrings("b", model.str(first[0]).?);
    const second = model.items(layers[1]);
    try testing.expectEqual(@as(usize, 2), second.len);
    try testing.expectEqualStrings("c", model.str(second[0]).?);
    try testing.expectEqualStrings("d", model.str(second[1]).?);
    const blocks = model.get(root, "blocks").?;
    try expectStr(model.get(blocks, "a").?, "title", "ta");
    try expectStr(model.get(blocks, "b").?, "title", "tb");
    try expectStr(model.get(blocks, "c").?, "title", "tc");
    try expectStr(model.get(blocks, "d").?, "title", "td");
    // A window with nothing minimized writes neither list.
    const lone = try model.parseView(a, try parseValue(a,
        \\{"session_id":"session:s","label":"s","revision":3,"windows":[
        \\{"window_id":"w","label":"w","layers":[
        \\{"layer_id":"layer:t","kind":"tiled","bounds":[0,0,1,1],"layout":{"rect":[0,0,1,1],"block_id":"a"},"blocks":[{"block_id":"a"}]}]}]}
    ));
    const plain = try model.stateJson(testing.allocator, .{
        .status = .connected,
        .server = "rex.test.example",
        .url = "https://rex.test.example",
        .sessions = &.{},
        .session = "session:s",
        .view = &lone,
        .blocks = &none,
    });
    defer testing.allocator.free(plain);
    try testing.expect(std.mem.find(u8, plain, "minimizedBlocks") == null);
    try testing.expect(std.mem.find(u8, plain, "minimizedLayers") == null);
}

// --- protocol.parse -------------------------------------------------------------------

test "parse: welcome.json" {
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    const msg = try protocol.parse(arena.allocator(), welcome_json);
    try testing.expectEqualStrings("client:00000000-0000-7000-b000-00000000000c", msg.welcome.client_id);
    try testing.expectEqualStrings("REDACTED", msg.welcome.reconnect_token);
    try testing.expectEqual(@as(i64, 18), msg.welcome.api_revision);
}

test "parse: bootstrap.json response with the attached session view and data tickets" {
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    const a = arena.allocator();

    const msg = try protocol.parse(a, bootstrap_json);
    const r = msg.response;
    try testing.expectEqual(@as(u64, 1), r.id);
    try testing.expectEqual(@as(i64, 0), r.seq);
    try testing.expect(r.err == null);
    const payload = r.payload.?;

    const sessions = model.items(model.get(model.get(payload, "attach_all").?, "sessions"));
    try testing.expectEqual(@as(usize, 3), sessions.len);
    try expectStr(sessions[2], "session_id", session_id);
    try expectStr(sessions[2], "label", "corelane-fixture");
    try testing.expect(model.get(payload, "selected_session").? == .null);

    const attached = model.items(model.get(payload, "attached_sessions"));
    try testing.expectEqual(@as(usize, 1), attached.len);
    const view = try model.parseView(a, model.get(attached[0], "view").?);
    try testing.expectEqualStrings(session_id, view.session);
    try testing.expectEqual(@as(i64, 5), view.revision);
    try testing.expectEqual(@as(usize, 3), view.windows[0].blocks.len);
    try testing.expectEqual(@as(usize, 3), leafCount(view.windows[0].layout));
    try testing.expectEqual(false, view.windows[0].layout.?.split.column);
    try testing.expectEqual(true, view.windows[0].layout.?.split.first.split.column);

    const tickets = model.items(model.get(payload, "data_tickets"));
    try testing.expectEqual(@as(usize, 3), tickets.len);
    for (tickets, view.windows[0].blocks) |t, b| {
        try expectStr(t, "session_id", session_id);
        try expectStr(t, "block_id", b.id);
        try expectStr(t, "data_ref_id", "terminal");
        try expectStr(t, "subprotocol", "rex.data.terminal.v1");
        try testing.expect(model.str(model.get(t, "ticket")) != null);
    }
}

test "parse: every line of events.jsonl, session_event unwrapped" {
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    const a = arena.allocator();

    var lines = std.mem.tokenizeScalar(u8, events_jsonl, '\n');
    var count: i64 = 0;
    var view_changes: usize = 0;
    var block_events: usize = 0;
    while (lines.next()) |line| {
        count += 1;
        const msg = try protocol.parse(a, line);
        const ev = msg.event;
        try testing.expectEqual(count, ev.seq);
        try testing.expect(!std.mem.eql(u8, ev.kind, "session_event"));
        try testing.expect(std.mem.startsWith(u8, ev.session.?, "session:"));
        try testing.expect(ev.payload == .object);

        if (std.mem.eql(u8, ev.kind, "session_view_changed")) {
            view_changes += 1;
            try testing.expect(model.int(model.get(ev.payload, "revision")).? >= 6);
        } else if (std.mem.eql(u8, ev.kind, "block_event")) {
            block_events += 1;
            try expectStr(ev.payload, "creator_name", "com.superlogical.terminal");
            try expectStr(ev.payload, "name", "process_changed");
        }
        switch (count) {
            3 => {
                try testing.expectEqualStrings("session_label_changed", ev.kind);
                try testing.expectEqualStrings(session_id, ev.session.?);
                try expectStr(ev.payload, "label", "corelane-fixture2");
            },
            4 => {
                try testing.expectEqualStrings("session_view_changed", ev.kind);
                try testing.expectEqual(@as(i64, 6), model.int(model.get(ev.payload, "revision")).?);
            },
            11 => {
                try testing.expectEqualStrings("block_created", ev.kind);
                try expectStr(ev.payload, "block_id", block_b);
            },
            12 => try testing.expectEqualStrings("block_owner_changed", ev.kind),
            else => {},
        }
    }
    try testing.expectEqual(@as(i64, 36), count);
    try testing.expectEqual(@as(usize, 4), view_changes);
    try testing.expectEqual(@as(usize, 2), block_events);
}

test "parse: errors, server events, unknown types, copies out of the input" {
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    const a = arena.allocator();

    const failed = try protocol.parse(a,
        \\{"type":"response","id":"2","seq":7,"error":{"code":"not_found","message":"block not found","status":404}}
    );
    try testing.expectEqual(@as(u64, 2), failed.response.id);
    try testing.expectEqual(@as(i64, 7), failed.response.seq);
    try testing.expect(failed.response.payload == null);
    try testing.expectEqualStrings("not_found", failed.response.err.?.code);
    try testing.expectEqualStrings("block not found", failed.response.err.?.message);
    try testing.expectEqual(@as(i64, 404), failed.response.err.?.status);

    // A server-level event is not wrapped and names no session.
    const created = try protocol.parse(a,
        \\{"type":"event","seq":1,"payload":{"type":"session_created","payload":{"session":{"session_id":"session:n","label":"work"}}}}
    );
    try testing.expectEqualStrings("session_created", created.event.kind);
    try testing.expectEqual(@as(?model.Id, null), created.event.session);
    try expectStr(model.get(created.event.payload, "session").?, "label", "work");

    try testing.expect(try protocol.parse(a, "{\"type\":\"future\",\"x\":1}") == .other);
    try testing.expect(try protocol.parse(a, "{\"type\":\"response\",\"id\":\"abc\",\"seq\":0}") == .other);
    try testing.expectError(error.InvalidMessage, protocol.parse(a, "[1]"));
    try testing.expectError(error.UnsupportedProtocol, protocol.parse(a, "{\"type\":\"welcome\",\"protocol\":\"rex.control.v2\"}"));
    try testing.expect(std.meta.isError(protocol.parse(a, "{\"type\":")));

    // The WebSocket buffer is reused after recv(); results must not point into it.
    const buf = try testing.allocator.dupe(u8, welcome_json);
    const msg = try protocol.parse(a, buf);
    @memset(buf, 'x');
    testing.allocator.free(buf);
    try testing.expectEqualStrings("REDACTED", msg.welcome.reconnect_token);
}

// --- protocol builders ----------------------------------------------------------------

test "hello: under 512 bytes, api_revision 18" {
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    const a = arena.allocator();

    const plain = try protocol.hello(a, "0.1.0", null);
    try testing.expect(plain.len < 512);
    try testing.expect(std.mem.find(u8, plain, "\"api_revision\":18") != null);
    const root = try parseValue(a, plain);
    try expectFields(root, &.{ "type", "api_revision", "client" });
    try expectStr(root, "type", "hello");
    try testing.expectEqual(@as(i64, 18), model.int(model.get(root, "api_revision")).?);
    const client = model.get(root, "client").?;
    try expectFields(client, &.{ "kind", "platform", "name", "version" });
    try expectStr(client, "kind", "app");
    try expectStr(client, "platform", "other");
    try expectStr(client, "name", "Velo");
    try expectStr(client, "version", "0.1.0");

    const resumed = try protocol.hello(a, "0.1.0", "AAECAwQFBgcICQoLDA0ODw");
    try testing.expect(resumed.len < 512);
    try expectStr(try parseValue(a, resumed), "reconnect_token", "AAECAwQFBgcICQoLDA0ODw");

    // An absurd version is cut on a character boundary; the hello stays under the limit.
    var long_version: [801]u8 = undefined;
    long_version[0] = 'v';
    for (0..400) |i| long_version[1 + i * 2 ..][0..2].* = "\u{00e9}".*;
    const long = try protocol.hello(a, &long_version, "tok");
    try testing.expect(long.len < 512);
    const version = model.str(model.get(model.get(try parseValue(a, long), "client").?, "version")).?;
    try testing.expectEqual(@as(usize, 63), version.len);
    try testing.expect(std.unicode.utf8ValidateSlice(version));

    const huge_token: [500]u8 = @splat('t');
    try testing.expectError(error.HelloTooLarge, protocol.hello(a, "0.1.0", &huge_token));
}

test "request: string id, method, raw payload" {
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    const a = arena.allocator();

    const text = try protocol.request(a, 7, protocol.methods.sessionView, try protocol.sessionView(a, session_id));
    try testing.expectEqualStrings(
        "{\"type\":\"request\",\"id\":\"7\",\"method\":\"session.view\",\"payload\":{\"session_id\":\"" ++ session_id ++ "\"}}",
        text,
    );
    const bare = try parseValue(a, try protocol.request(a, std.math.maxInt(u64), "server.status", ""));
    try expectFields(bare, &.{ "type", "id", "method" });
    try expectStr(bare, "id", "18446744073709551615");
}

test "payload builders: documented field names" {
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    const a = arena.allocator();

    {
        const p = try parseValue(a, try protocol.bootstrap(a, .{ .session = session_id }));
        try expectFields(p, &.{ "event_scope", "attach_session_ids", "data_tickets" });
        // `all`: the block events of sessions the core is not attached to arrive too.
        try expectStr(p, "event_scope", "all");
        const ids = model.items(model.get(p, "attach_session_ids"));
        try testing.expectEqual(@as(usize, 1), ids.len);
        try testing.expectEqualStrings(session_id, ids[0].string);
        const tickets = model.items(model.get(p, "data_tickets"));
        try testing.expectEqual(@as(usize, 1), tickets.len);
        try expectFields(tickets[0], &.{ "creator_name", "data_ref_id", "subprotocol" });
        try expectStr(tickets[0], "creator_name", "com.superlogical.terminal");
        try expectStr(tickets[0], "data_ref_id", "terminal");
        try expectStr(tickets[0], "subprotocol", "rex.data.terminal.v1");

        // No session named: the server picks one (selected_session).
        const auto = try parseValue(a, try protocol.bootstrap(a, .pick));
        try expectFields(auto, &.{ "event_scope", "data_tickets" });

        // An empty list attaches none.
        const none = try protocol.bootstrap(a, .none);
        try testing.expect(std.mem.find(u8, none, "\"attach_session_ids\":[]") != null);
        const parsed = try parseValue(a, none);
        try expectFields(parsed, &.{ "event_scope", "attach_session_ids", "data_tickets" });
        try testing.expectEqual(@as(usize, 0), model.items(model.get(parsed, "attach_session_ids")).len);
    }
    {
        const p = try parseValue(a, try protocol.sessionView(a, session_id));
        try expectFields(p, &.{"session_id"});
        try expectStr(p, "session_id", session_id);
    }
    {
        const p = try parseValue(a, try protocol.dataConnect(a, session_id, block_a));
        try expectFields(p, &.{ "session_id", "block_id", "data_ref_id", "subprotocol" });
        try expectStr(p, "session_id", session_id);
        try expectStr(p, "block_id", block_a);
        try expectStr(p, "data_ref_id", "terminal");
        try expectStr(p, "subprotocol", "rex.data.terminal.v1");
    }
    inline for (.{
        .{ .left, "horizontal", "before" }, .{ .right, "horizontal", "after" },
        .{ .above, "vertical", "before" },  .{ .below, "vertical", "after" },
    }) |case| {
        const p = try parseValue(a, try protocol.newSplit(a, session_id, block_a, case[0]));
        try expectFields(p, &.{ "session_id", "anchor_id", "direction", "side", "layout", "focus" });
        try expectStr(p, "session_id", session_id);
        try expectStr(p, "anchor_id", block_a);
        try expectStr(p, "direction", case[1]);
        try expectStr(p, "side", case[2]);
        try testing.expectEqual(true, model.boolean(model.get(p, "focus")).?);
        const block = model.get(model.get(p, "layout").?, "block").?;
        try expectFields(block, &.{"flavor"});
        try expectStr(block, "flavor", "com.superlogical.terminal.shell");
    }
    {
        const p = try parseValue(a, try protocol.floatBlock(a, session_id, block_a));
        try expectFields(p, &.{ "session_id", "block_id", "bounds" });
        try expectStr(p, "block_id", block_a);
        const bounds = model.items(model.get(p, "bounds"));
        try testing.expectEqual(@as(usize, 4), bounds.len);
        for (bounds, [_]f64{ 0.15, 0.15, 0.7, 0.7 }) |got, want| try expectNum(got, want);
    }
    {
        const p = try parseValue(a, try protocol.moveBlock(a, session_id, block_a, block_b));
        try expectFields(p, &.{ "session_id", "block_id", "anchor_block_id", "direction", "side" });
        try expectStr(p, "block_id", block_a);
        try expectStr(p, "anchor_block_id", block_b);
        try expectStr(p, "direction", "horizontal");
        try expectStr(p, "side", "after");
    }
    inline for (.{ true, false }) |zoom| {
        const p = try parseValue(a, try protocol.zoomBlock(a, session_id, block_a, zoom));
        try expectFields(p, &.{ "session_id", "block_id", "zoom" });
        try expectStr(p, "block_id", block_a);
        try testing.expectEqual(zoom, model.boolean(model.get(p, "zoom")).?);
    }
    {
        const p = try parseValue(a, try protocol.closeBlock(a, session_id, block_a));
        try expectFields(p, &.{ "session_id", "block_id", "keep_last" });
        try expectStr(p, "block_id", block_a);
        try testing.expectEqual(true, model.boolean(model.get(p, "keep_last")).?);
    }
    {
        const p = try parseValue(a, try protocol.focusBlock(a, session_id, block_a));
        try expectFields(p, &.{ "session_id", "block_id" });
        try expectStr(p, "session_id", session_id);
        try expectStr(p, "block_id", block_a);
    }
    {
        const p = try parseValue(a, try protocol.newWindow(a, session_id));
        try expectFields(p, &.{ "session_id", "layout", "focus" });
        try expectStr(model.get(model.get(p, "layout").?, "block").?, "flavor", "com.superlogical.terminal.shell");
        try testing.expectEqual(true, model.boolean(model.get(p, "focus")).?);
    }
    {
        const p = try parseValue(a, try protocol.createSession(a, "work"));
        try expectFields(p, &.{ "label", "initial_windows" });
        try expectStr(p, "label", "work");
        const windows = model.items(model.get(p, "initial_windows"));
        try testing.expectEqual(@as(usize, 1), windows.len);
        const layout = model.get(windows[0], "layout").?;
        try expectStr(model.get(layout, "block").?, "flavor", "com.superlogical.terminal.shell");
    }
    {
        // No label: the server picks the name; the shell window is still requested.
        const p = try parseValue(a, try protocol.createSession(a, null));
        try expectFields(p, &.{"initial_windows"});
        try testing.expectEqual(@as(usize, 1), model.items(model.get(p, "initial_windows")).len);
    }
    {
        const p = try parseValue(a, try protocol.focusWindow(a, session_id, window_id));
        try expectFields(p, &.{ "session_id", "window_id" });
        try expectStr(p, "window_id", window_id);
    }
    {
        const typed = "echo \"hi\"\r\x1b[A \u{d55c}\u{ae00}\n";
        const p = try parseValue(a, try protocol.terminalWrite(a, session_id, block_a, typed));
        try expectFields(p, &.{ "session_id", "block_id", "args" });
        try expectStr(p, "session_id", session_id);
        try expectStr(p, "block_id", block_a);
        try expectFields(model.get(p, "args").?, &.{"data"});
        try expectStr(model.get(p, "args").?, "data", typed);
    }
    {
        const p = try parseValue(a, try protocol.hostsPut(a, "host:x", "studio", "https://studio.example.ts.net"));
        try expectFields(p, &.{"host"});
        const host = model.get(p, "host").?;
        try expectFields(host, &.{ "id", "label", "endpoints", "added_from" });
        try expectStr(host, "id", "host:x");
        try expectStr(host, "label", "studio");
        try expectStr(host, "added_from", "Velo");
        const endpoints = model.items(model.get(host, "endpoints"));
        try testing.expectEqual(@as(usize, 1), endpoints.len);
        try testing.expectEqualStrings("https://studio.example.ts.net", endpoints[0].string);
        try testing.expectEqualStrings(
            "{\"host\":{\"id\":\"host:x\",\"label\":\"studio\",\"endpoints\":[\"https://studio.example.ts.net\"],\"added_from\":\"Velo\"}}",
            try protocol.hostsPut(a, "host:x", "studio", "https://studio.example.ts.net"),
        );
    }
    {
        try testing.expectEqualStrings("{\"id\":\"host:x\"}", try protocol.hostsRemove(a, "host:x"));
    }
    {
        // Exact text: the requests that close and rename.
        try testing.expectEqualStrings(
            "{\"session_id\":\"" ++ session_id ++ "\",\"window_id\":\"" ++ window_id ++ "\"}",
            try protocol.closeWindow(a, session_id, window_id),
        );
        try testing.expectEqualStrings("{\"session_id\":\"" ++ session_id ++ "\"}", try protocol.destroySession(a, session_id));
        try testing.expectEqualStrings(
            "{\"session_id\":\"" ++ session_id ++ "\",\"label\":\"work \\\"2\\\"\"}",
            try protocol.setSessionLabel(a, session_id, "work \"2\""),
        );
        try testing.expectEqualStrings(
            "{\"session_id\":\"" ++ session_id ++ "\",\"window_id\":\"" ++ window_id ++ "\",\"label\":\"logs\"}",
            try protocol.setWindowLabel(a, session_id, window_id, "logs"),
        );
        // An empty label is sent as is: the server restores its own name.
        try testing.expectEqualStrings(
            "{\"session_id\":\"" ++ session_id ++ "\",\"window_id\":\"" ++ window_id ++ "\",\"label\":\"\"}",
            try protocol.setWindowLabel(a, session_id, window_id, ""),
        );
    }
    {
        // Exact text: the server reads these names, and `mode` must be `take`.
        try testing.expectEqualStrings(
            "{\"session_id\":\"" ++ session_id ++ "\",\"block_ids\":[\"" ++ block_a ++ "\"],\"mode\":\"take\"}",
            try protocol.ownBlock(a, session_id, block_a),
        );
        try testing.expectEqualStrings(
            "{\"session_id\":\"" ++ session_id ++ "\",\"block_id\":\"" ++ block_a ++
                "\",\"args\":{\"columns\":100,\"rows\":30,\"cell_width_px\":17,\"cell_height_px\":34}}",
            try protocol.terminalResize(a, session_id, block_a, 100, 30, 17, 34),
        );
        // The server refuses 0 for any of the four: the smallest request is 1.
        const least = try parseValue(a, try protocol.terminalResize(a, session_id, block_a, 0, 0, 0, 0));
        const args = model.get(least, "args").?;
        try expectFields(args, &.{ "columns", "rows", "cell_width_px", "cell_height_px" });
        inline for (.{ "columns", "rows", "cell_width_px", "cell_height_px" }) |name| try expectNum(model.get(args, name), 1);
        const most = try parseValue(a, try protocol.terminalResize(a, session_id, block_a, 65535, 65535, 65535, 65535));
        try expectNum(model.get(model.get(most, "args").?, "columns"), 65535);
    }

    try testing.expectEqualStrings("session.bootstrap", protocol.methods.bootstrap);
    try testing.expectEqualStrings("session.view", protocol.methods.sessionView);
    try testing.expectEqualStrings("data.connect", protocol.methods.dataConnect);
    try testing.expectEqualStrings("session.new_split", protocol.methods.newSplit);
    try testing.expectEqualStrings("session.float_block", protocol.methods.floatBlock);
    try testing.expectEqualStrings("session.move_block", protocol.methods.moveBlock);
    try testing.expectEqualStrings("session.zoom_block", protocol.methods.zoomBlock);
    try testing.expectEqualStrings("block.close", protocol.methods.closeBlock);
    try testing.expectEqualStrings("session.focus_block", protocol.methods.focusBlock);
    try testing.expectEqualStrings("session.new_window", protocol.methods.newWindow);
    try testing.expectEqualStrings("session.focus_window", protocol.methods.focusWindow);
    try testing.expectEqualStrings("session.close_window", protocol.methods.closeWindow);
    try testing.expectEqualStrings("session.destroy", protocol.methods.destroySession);
    try testing.expectEqualStrings("session.set_label", protocol.methods.setSessionLabel);
    try testing.expectEqualStrings("session.set_window_label", protocol.methods.setWindowLabel);
    try testing.expectEqualStrings("com.superlogical.terminal.title", protocol.methods.terminalTitle);
    try testing.expectEqualStrings("session.create", protocol.methods.createSession);
    try testing.expectEqualStrings("com.superlogical.terminal.write", protocol.methods.terminalWrite);
    try testing.expectEqualStrings("hosts.list", protocol.methods.hostsList);
    try testing.expectEqualStrings("hosts.put", protocol.methods.hostsPut);
    try testing.expectEqualStrings("hosts.remove", protocol.methods.hostsRemove);
    try testing.expectEqualStrings("session.own_block", protocol.methods.ownBlock);
    try testing.expectEqualStrings("com.superlogical.terminal.resize", protocol.methods.terminalResize);
    try testing.expectEqualStrings("com.superlogical.terminal.size", protocol.methods.terminalSize);
}

test "builders survive allocation failure without leaking" {
    try testing.checkAllAllocationFailures(testing.allocator, buildAll, .{});
}

fn buildAll(gpa: std.mem.Allocator) !void {
    const payload = try protocol.newSplit(gpa, session_id, block_a, .below);
    defer gpa.free(payload);
    const req = try protocol.request(gpa, 3, protocol.methods.newSplit, payload);
    defer gpa.free(req);
    const hello = try protocol.hello(gpa, "0.1.0", "token");
    defer gpa.free(hello);
    const typed: [600]u8 = @splat('x');
    const write = try protocol.terminalWrite(gpa, session_id, block_a, &typed);
    defer gpa.free(write);
    const boot = try protocol.bootstrap(gpa, .{ .session = session_id });
    defer gpa.free(boot);
    const create = try protocol.createSession(gpa, "work");
    defer gpa.free(create);
    const put = try protocol.hostsPut(gpa, "host:x", "studio", "https://studio.example.ts.net");
    defer gpa.free(put);
    const remove = try protocol.hostsRemove(gpa, "host:x");
    defer gpa.free(remove);
    const close = try protocol.closeWindow(gpa, session_id, window_id);
    defer gpa.free(close);
    const destroy = try protocol.destroySession(gpa, session_id);
    defer gpa.free(destroy);
    const relabel = try protocol.setSessionLabel(gpa, session_id, "work");
    defer gpa.free(relabel);
    const retitle = try protocol.setWindowLabel(gpa, session_id, window_id, "logs");
    defer gpa.free(retitle);
    const own = try protocol.ownBlock(gpa, session_id, block_a);
    defer gpa.free(own);
    const resize = try protocol.terminalResize(gpa, session_id, block_a, 100, 30, 17, 34);
    defer gpa.free(resize);
}

// --- data handshake -------------------------------------------------------------------

test "dataHello: 22-char base64url ticket gives 0x01 + 16 bytes" {
    var out: [17]u8 = undefined;
    try protocol.dataHello("AAECAwQFBgcICQoLDA0ODw", &out);
    try testing.expectEqualSlices(u8, &.{ 0x01, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15 }, &out);

    // URL-safe alphabet: '-' and '_' are digits 62 and 63.
    try protocol.dataHello("-_-_-_-_-_-_-_-_-_-_-w", &out);
    try testing.expectEqual(@as(u8, 0x01), out[0]);
    try testing.expectEqual(@as(u8, 0xfb), out[1]);

    try testing.expectError(error.InvalidTicket, protocol.dataHello("AAECAwQFBgcICQoLDA0O", &out));
    try testing.expectError(error.InvalidTicket, protocol.dataHello("AAECAwQFBgcICQoLDA0ODw==", &out));
    try testing.expectError(error.InvalidTicket, protocol.dataHello("AAECAwQFBgcICQoLDA0O+/", &out));
    try testing.expectError(error.InvalidTicket, protocol.dataHello("", &out));
}

test "dataResume: 0x02 + token + consumed u64 LE" {
    var token: [16]u8 = undefined;
    for (&token, 0..) |*b, i| b.* = @intCast(0xa0 + i);
    var out: [25]u8 = undefined;
    protocol.dataResume(token, 0x0102030405060708, &out);
    try testing.expectEqual(@as(u8, 0x02), out[0]);
    try testing.expectEqualSlices(u8, &token, out[1..17]);
    try testing.expectEqualSlices(u8, &.{ 8, 7, 6, 5, 4, 3, 2, 1 }, out[17..25]);
}

test "parseDataHandshake: 32-byte 0x81 welcome and 2-byte 0x82 reject" {
    var welcome: [32]u8 = @splat(0);
    welcome[0] = 0x81;
    for (welcome[1..17], 0..) |*b, i| b.* = @intCast(i + 1);
    std.mem.writeInt(u32, welcome[20..24], 60000, .little);
    std.mem.writeInt(u64, welcome[24..32], 17992251057005343352, .little);

    const w = protocol.parseDataHandshake(&welcome).welcome;
    try testing.expectEqualSlices(u8, &.{ 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16 }, &w.token);
    try testing.expectEqual(@as(u32, 60000), w.resume_ms);
    try testing.expectEqual(@as(u64, 17992251057005343352), w.nonce);

    try testing.expectEqual(@as(u8, 0x02), protocol.parseDataHandshake(&.{ 0x82, 0x02 }).reject);

    try testing.expect(protocol.parseDataHandshake(welcome[0..31]) == .invalid);
    try testing.expect(protocol.parseDataHandshake(&(welcome ++ [1]u8{0})) == .invalid);
    var zero_nonce = welcome;
    @memset(zero_nonce[24..32], 0);
    try testing.expect(protocol.parseDataHandshake(&zero_nonce) == .invalid);
    try testing.expect(protocol.parseDataHandshake(&.{0x82}) == .invalid);
    try testing.expect(protocol.parseDataHandshake(&.{ 0x01, 0x02 }) == .invalid);
    try testing.expect(protocol.parseDataHandshake("") == .invalid);
}

// --- intents --------------------------------------------------------------------------

test "parseIntent: every op Kotlin sends" {
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    const a = arena.allocator();

    inline for ([_]protocol.SplitDirection{ .left, .right, .above, .below }) |direction| {
        const intent = try protocol.parseIntent(a, "{\"op\":\"split\",\"block\":\"block:a\",\"dir\":\"" ++ @tagName(direction) ++ "\"}");
        try testing.expectEqualStrings("block:a", intent.split.block);
        try testing.expectEqual(direction, intent.split.direction);
    }
    const fallback = try protocol.parseIntent(a, "{\"op\":\"split\",\"block\":\"block:a\"}");
    try testing.expectEqual(protocol.SplitDirection.right, fallback.split.direction);

    try testing.expectEqualStrings("block:a", (try protocol.parseIntent(a, "{\"op\":\"float\",\"block\":\"block:a\"}")).float);
    try testing.expectEqualStrings("block:a", (try protocol.parseIntent(a, "{\"op\":\"dock\",\"block\":\"block:a\"}")).dock);
    try testing.expectEqualStrings("block:a", (try protocol.parseIntent(a, "{\"op\":\"zoom\",\"block\":\"block:a\"}")).zoom);
    try testing.expectEqualStrings("block:a", (try protocol.parseIntent(a, "{\"op\":\"unzoom\",\"block\":\"block:a\"}")).unzoom);
    try testing.expectEqualStrings("block:a", (try protocol.parseIntent(a, "{\"op\":\"close\",\"block\":\"block:a\"}")).close);
    try testing.expectEqualStrings("block:a", (try protocol.parseIntent(a, "{\"op\":\"focus\",\"block\":\"block:a\"}")).focus);
    try testing.expectEqualStrings("block:a", (try protocol.parseIntent(a, "{\"op\":\"fit\",\"block\":\"block:a\"}")).fit);
    try testing.expectEqualStrings("block:a", (try protocol.parseIntent(a, "{\"op\":\"touch\",\"block\":\"block:a\"}")).touch);
    try testing.expect(try protocol.parseIntent(a, "{\"op\":\"fit\"}") == .unknown);
    try testing.expect(try protocol.parseIntent(a, "{\"op\":\"touch\"}") == .unknown);
    // Without `session` the tab ops act on the selected session, as before.
    const tab_new = try protocol.parseIntent(a, "{\"op\":\"newWindow\"}");
    try testing.expect(tab_new.new_window.session == null);
    const tab_new_in = try protocol.parseIntent(a, "{\"op\":\"newWindow\",\"session\":\"session:s\"}");
    try testing.expectEqualStrings("session:s", tab_new_in.new_window.session.?);
    const session_new = try protocol.parseIntent(a, "{\"op\":\"newSession\"}");
    try testing.expect(session_new.new_session.label == null);
    const session_named = try protocol.parseIntent(a, "{\"op\":\"newSession\",\"label\":\"work\"}");
    try testing.expectEqualStrings("work", session_named.new_session.label.?);
    const tab = try protocol.parseIntent(a, "{\"op\":\"selectWindow\",\"window\":\"window:w\"}");
    try testing.expectEqualStrings("window:w", tab.select_window.window);
    try testing.expect(tab.select_window.session == null);
    const tab_in = try protocol.parseIntent(a, "{\"op\":\"selectWindow\",\"session\":\"session:s\",\"window\":\"window:w\"}");
    try testing.expectEqualStrings("window:w", tab_in.select_window.window);
    try testing.expectEqualStrings("session:s", tab_in.select_window.session.?);
    try testing.expect(try protocol.parseIntent(a, "{\"op\":\"selectWindow\",\"session\":\"session:s\"}") == .unknown);

    const close_tab = try protocol.parseIntent(a, "{\"op\":\"closeWindow\",\"session\":\"session:s\",\"window\":\"window:w\"}");
    try testing.expectEqualStrings("session:s", close_tab.close_window.session);
    try testing.expectEqualStrings("window:w", close_tab.close_window.window);
    try testing.expectEqualStrings("session:s", (try protocol.parseIntent(a, "{\"op\":\"closeSession\",\"session\":\"session:s\"}")).close_session);
    const rename = try protocol.parseIntent(a, "{\"op\":\"renameSession\",\"session\":\"session:s\",\"label\":\"work\"}");
    try testing.expectEqualStrings("session:s", rename.rename_session.session);
    try testing.expectEqualStrings("work", rename.rename_session.label);
    const retitle = try protocol.parseIntent(a, "{\"op\":\"renameWindow\",\"session\":\"session:s\",\"window\":\"window:w\",\"label\":\"\"}");
    try testing.expectEqualStrings("session:s", retitle.rename_window.session);
    try testing.expectEqualStrings("window:w", retitle.rename_window.window);
    try testing.expectEqualStrings("", retitle.rename_window.label);
    const move_tab = try protocol.parseIntent(a, "{\"op\":\"moveWindow\",\"session\":\"session:s\",\"window\":\"window:w\",\"before\":\"window:v\"}");
    try testing.expectEqualStrings("session:s", move_tab.move_window.session);
    try testing.expectEqualStrings("window:w", move_tab.move_window.window);
    try testing.expectEqualStrings("window:v", move_tab.move_window.before.?);
    const move_tab_last = try protocol.parseIntent(a, "{\"op\":\"moveWindow\",\"session\":\"session:s\",\"window\":\"window:w\"}");
    try testing.expect(move_tab_last.move_window.before == null);
    // Closing, renaming and moving never guess their target.
    for ([_][]const u8{
        "{\"op\":\"moveWindow\",\"session\":\"session:s\"}",
        "{\"op\":\"moveWindow\",\"window\":\"window:w\"}",
        "{\"op\":\"closeWindow\",\"window\":\"window:w\"}",
        "{\"op\":\"closeWindow\",\"session\":\"session:s\"}",
        "{\"op\":\"closeSession\"}",
        "{\"op\":\"renameSession\",\"session\":\"session:s\"}",
        "{\"op\":\"renameSession\",\"label\":\"work\"}",
        "{\"op\":\"renameWindow\",\"session\":\"session:s\",\"label\":\"logs\"}",
        "{\"op\":\"renameWindow\",\"session\":\"session:s\",\"window\":\"window:w\"}",
    }) |text| try testing.expect(try protocol.parseIntent(a, text) == .unknown);

    const send = try protocol.parseIntent(a, "{\"op\":\"sendText\",\"block\":\"block:a\",\"text\":\"ls -la\\n\\u001b[A\"}");
    try testing.expectEqualStrings("block:a", send.send_text.block);
    try testing.expectEqualStrings("ls -la\n\x1b[A", send.send_text.text);

    const paste = try protocol.parseIntent(a, "{\"op\":\"paste\",\"block\":\"block:a\",\"text\":\"ls\\npwd\",\"confirmed\":true}");
    try testing.expectEqualStrings("block:a", paste.paste.block);
    try testing.expectEqualStrings("ls\npwd", paste.paste.text);
    try testing.expect(paste.paste.confirmed);
    // A paste that does not say it was confirmed was not.
    try testing.expect(!(try protocol.parseIntent(a, "{\"op\":\"paste\",\"block\":\"block:a\",\"text\":\"ls\",\"confirmed\":false}")).paste.confirmed);
    try testing.expect(!(try protocol.parseIntent(a, "{\"op\":\"paste\",\"block\":\"block:a\",\"text\":\"ls\"}")).paste.confirmed);
    try testing.expect(try protocol.parseIntent(a, "{\"op\":\"paste\",\"block\":\"block:a\",\"confirmed\":true}") == .unknown);
    try testing.expect(try protocol.parseIntent(a, "{\"op\":\"paste\",\"text\":\"ls\",\"confirmed\":true}") == .unknown);

    // The setting envelope preserves the type of the value, even for an unrecognised key.
    const string = try protocol.parseIntent(a, "{\"op\":\"setting\",\"key\":\"example\",\"value\":\"sample\"}");
    try testing.expectEqualStrings("example", string.setting.key);
    try testing.expectEqualStrings("sample", string.setting.value.string);
    const font = try protocol.parseIntent(a, "{\"op\":\"setting\",\"key\":\"fontSize\",\"value\":13.5}");
    try testing.expectEqualStrings("fontSize", font.setting.key);
    try expectNum(font.setting.value, 13.5);
    const font_int = try protocol.parseIntent(a, "{\"op\":\"setting\",\"key\":\"fontSize\",\"value\":14}");
    try expectNum(font_int.setting.value, 14);
    const flag = try protocol.parseIntent(a, "{\"op\":\"setting\",\"key\":\"paneStyle\",\"value\":true}");
    try testing.expectEqual(true, flag.setting.value.bool);

    const put = try protocol.parseIntent(a, "{\"op\":\"hostsPut\",\"id\":\"host:x\",\"label\":\"studio\",\"url\":\"https://studio.example.ts.net\"}");
    try testing.expectEqualStrings("host:x", put.hosts_put.id);
    try testing.expectEqualStrings("studio", put.hosts_put.label);
    try testing.expectEqualStrings("https://studio.example.ts.net", put.hosts_put.url);
    try testing.expectEqualStrings("host:x", (try protocol.parseIntent(a, "{\"op\":\"hostsRemove\",\"id\":\"host:x\"}")).hosts_remove);
    try testing.expect(try protocol.parseIntent(a, "{\"op\":\"detach\"}") == .detach);
    try testing.expectEqualStrings("velo-test-3-", (try protocol.parseIntent(a, "{\"op\":\"fence\",\"prefix\":\"velo-test-3-\"}")).fence);
    // No prefix, or an empty one: no fence.
    try testing.expectEqualStrings("", (try protocol.parseIntent(a, "{\"op\":\"fence\",\"prefix\":\"\"}")).fence);
    try testing.expectEqualStrings("", (try protocol.parseIntent(a, "{\"op\":\"fence\"}")).fence);
    try testing.expect(try protocol.parseIntent(a, "{\"op\":\"fence\",\"prefix\":3}") == .unknown);

    try testing.expect(try protocol.parseIntent(a, "{\"op\":\"teleport\",\"block\":\"block:a\"}") == .unknown);
    try testing.expect(try protocol.parseIntent(a, "{\"op\":\"float\"}") == .unknown);
    try testing.expect(try protocol.parseIntent(a, "{\"op\":\"split\",\"block\":\"block:a\",\"dir\":\"up\"}") == .unknown);
    try testing.expect(try protocol.parseIntent(a, "{\"block\":\"block:a\"}") == .unknown);
    try testing.expect(try protocol.parseIntent(a, "[]") == .unknown);
    try testing.expect(try protocol.parseIntent(a, "{\"op\":\"hostsPut\",\"id\":\"host:x\",\"label\":\"studio\"}") == .unknown);
    try testing.expect(try protocol.parseIntent(a, "{\"op\":\"hostsRemove\"}") == .unknown);
    try testing.expect(std.meta.isError(protocol.parseIntent(a, "{\"op\":")));
}

// --- StreamFilter ---------------------------------------------------------------------

const Collector = struct {
    gpa: std.mem.Allocator,
    bytes: std.ArrayList(u8) = .empty,
    messages: std.ArrayList(StreamFilter.RexMessage) = .empty,
    /// Position in `bytes` at which each message arrived.
    offsets: std.ArrayList(usize) = .empty,

    fn deinit(self: *Collector) void {
        for (self.messages.items) |m| switch (m) {
            .theme => |t| self.gpa.free(t.palette),
            else => {},
        };
        self.bytes.deinit(self.gpa);
        self.messages.deinit(self.gpa);
        self.offsets.deinit(self.gpa);
    }

    fn sink(self: *Collector) StreamFilter.Sink {
        return .{ .ctx = self, .bytes = onBytes, .message = onMessage };
    }

    fn onBytes(ctx: *anyopaque, data: []const u8) void {
        const self: *Collector = @ptrCast(@alignCast(ctx));
        self.bytes.appendSlice(self.gpa, data) catch @panic("out of memory");
    }

    fn onMessage(ctx: *anyopaque, msg: StreamFilter.RexMessage) void {
        const self: *Collector = @ptrCast(@alignCast(ctx));
        var copy = msg;
        switch (copy) {
            // The palette is only valid during the callback.
            .theme => |*t| t.palette = self.gpa.dupe(StreamFilter.PaletteEntry, t.palette) catch @panic("out of memory"),
            else => {},
        }
        self.messages.append(self.gpa, copy) catch @panic("out of memory");
        self.offsets.append(self.gpa, self.bytes.items.len) catch @panic("out of memory");
    }
};

/// Feed `data` in chunks of `chunk` bytes (0 = all at once).
fn run(out: *Collector, nonce: u64, data: []const u8, chunk: usize) void {
    var filter: StreamFilter.StreamFilter = .init(out.gpa, nonce);
    defer filter.deinit();
    if (chunk == 0) return filter.feed(data, out.sink());
    var i: usize = 0;
    while (i < data.len) : (i += chunk) filter.feed(data[i..@min(i + chunk, data.len)], out.sink());
}

fn expectSame(a: *const Collector, b: *const Collector) !void {
    try testing.expectEqualSlices(u8, a.bytes.items, b.bytes.items);
    try testing.expectEqualDeep(a.messages.items, b.messages.items);
    try testing.expectEqualSlices(usize, a.offsets.items, b.offsets.items);
}

const rex_intro = "\x1b_rex1;";

/// The post-snapshot part of stream.bin and the channel nonce it carries.
fn fixtureStream() !struct { post: []const u8, nonce: u64, first_apc_len: usize } {
    const start = std.mem.find(u8, stream_bin, rex_intro) orelse return error.NoRexMessage;
    const post = stream_bin[start..];
    const digits_end = std.mem.findScalarPos(u8, post, rex_intro.len, ';') orelse return error.NoRexMessage;
    const nonce = try std.fmt.parseUnsigned(u64, post[rex_intro.len..digits_end], 10);
    const end = (std.mem.find(u8, post, "\x1b\\") orelse return error.NoRexMessage) + 2;
    return .{ .post = post, .nonce = nonce, .first_apc_len = end };
}

test "StreamFilter: stream.bin in 1-byte chunks and as one chunk" {
    const fx = try fixtureStream();
    try testing.expect(std.mem.startsWith(u8, stream_bin, "GHOSTSNP"));
    try testing.expect(fx.nonce != 0);
    // Program output follows the resize message in the fixture.
    const program = fx.post[fx.first_apc_len..];
    try testing.expect(program.len > 0);

    var whole: Collector = .{ .gpa = testing.allocator };
    defer whole.deinit();
    run(&whole, fx.nonce, fx.post, 0);

    var single: Collector = .{ .gpa = testing.allocator };
    defer single.deinit();
    run(&single, fx.nonce, fx.post, 1);

    try expectSame(&whole, &single);

    try testing.expectEqual(@as(usize, 1), whole.messages.items.len);
    const resize = whole.messages.items[0].resize;
    try testing.expectEqual(@as(u16, 80), resize.cols);
    try testing.expectEqual(@as(u16, 24), resize.rows);
    try testing.expectEqual(@as(u32, 8), resize.cell_w_px);
    try testing.expectEqual(@as(u32, 16), resize.cell_h_px);
    // The resize arrives before any program byte.
    try testing.expectEqual(@as(usize, 0), whole.offsets.items[0]);

    // Everything after the channel's own APC is program output, byte for byte. That
    // includes the APC the program printed with another nonce.
    try testing.expectEqualSlices(u8, program, whole.bytes.items);
    try testing.expect(std.mem.find(u8, whole.bytes.items, "corelane ") != null);
    try testing.expect(std.mem.find(u8, whole.bytes.items, "\x1b_rex1;12345;{\"type\":\"resize\",\"cols\":1,\"rows\":1}\x1b\\") != null);
    try testing.expect(std.mem.find(u8, whole.bytes.items, "\x1b]0;corelane-title\x07") != null);

    // Every other chunk size agrees too.
    for ([_]usize{ 2, 3, 5, 7, 13, 64, 1000 }) |chunk| {
        var other: Collector = .{ .gpa = testing.allocator };
        defer other.deinit();
        run(&other, fx.nonce, fx.post, chunk);
        try expectSame(&whole, &other);
    }
}

test "StreamFilter: an APC with a different nonce passes through as bytes" {
    const fx = try fixtureStream();
    const foreign = "\x1b_rex1;12345;{\"type\":\"resize\",\"cols\":132,\"rows\":50}\x1b\\";
    const data = "before" ++ foreign ++ "after";

    for ([_]usize{ 0, 1, 4 }) |chunk| {
        var out: Collector = .{ .gpa = testing.allocator };
        defer out.deinit();
        run(&out, fx.nonce, data, chunk);
        try testing.expectEqual(@as(usize, 0), out.messages.items.len);
        try testing.expectEqualStrings(data, out.bytes.items);
    }

    // The same bytes are a message on the channel that owns nonce 12345.
    var own: Collector = .{ .gpa = testing.allocator };
    defer own.deinit();
    run(&own, 12345, data, 1);
    try testing.expectEqualStrings("beforeafter", own.bytes.items);
    try testing.expectEqual(@as(u16, 132), own.messages.items[0].resize.cols);
    try testing.expectEqual(@as(usize, "before".len), own.offsets.items[0]);

    // A nonce that only shares a prefix with the channel's is foreign as well.
    var prefix: Collector = .{ .gpa = testing.allocator };
    defer prefix.deinit();
    const near = "\x1b_rex1;123456;{\"type\":\"child_exited\",\"exit_code\":1}\x1b\\";
    run(&prefix, 12345, near, 1);
    try testing.expectEqual(@as(usize, 0), prefix.messages.items.len);
    try testing.expectEqualStrings(near, prefix.bytes.items);
}

test "StreamFilter: theme, child_exited, unknown types and broken sequences" {
    const nonce: u64 = 18446744073709551615;
    const p = "\x1b_rex1;18446744073709551615;";
    const data = "a" ++
        p ++ "{\"type\":\"theme\",\"foreground\":\"#d6deeb\",\"background\":\"011627\",\"cursor\":\"\"," ++
        "\"palette\":[\"1=#ef5350\",\"255=00ff7F\",\"0=101112\",\"256=ffffff\",\"x\",\"1=#EF5351\"],\"scheme\":\"dark\"}\x1b\\" ++
        "b" ++
        p ++ "{\"type\":\"child_exited\",\"exit_code\":130}\x1b\\" ++
        "c" ++
        p ++ "{\"type\":\"future_thing\",\"x\":1}\x1b\\" ++ // unknown type: dropped
        "d" ++
        p ++ "not json\x1b\\" ++ // malformed message: dropped
        "e" ++
        p ++ "{\"type\":\"resize\"\x1b[0m" ++ // cut short by another escape: program bytes
        "f\x1b" ++ "\x1b_rex" ++ "g\x1b_" ++ // look-alike starts
        p ++ "{\"type\":\"resize\",\"cols\":100,\"rows\":30,\"cell_width_px\":0,\"cell_height_px\":0,\"owner\":\"client:c\"}\x1b\\" ++
        "h" ++ p ++ "{\"type\":\"theme\"}\x1b\\" ++
        p; // unterminated at the end of the input: held back
    const expected_bytes = "abcde" ++ p ++ "{\"type\":\"resize\"\x1b[0m" ++ "f\x1b\x1b_rexg\x1b_h";

    var whole: Collector = .{ .gpa = testing.allocator };
    defer whole.deinit();
    run(&whole, nonce, data, 0);

    try testing.expectEqualStrings(expected_bytes, whole.bytes.items);
    try testing.expectEqual(@as(usize, 4), whole.messages.items.len);

    const theme = whole.messages.items[0].theme;
    try testing.expectEqual(@as(?[3]u8, .{ 0xd6, 0xde, 0xeb }), theme.foreground);
    try testing.expectEqual(@as(?[3]u8, .{ 0x01, 0x16, 0x27 }), theme.background);
    try testing.expectEqual(@as(?[3]u8, null), theme.cursor);
    try testing.expectEqualDeep(@as([]const StreamFilter.PaletteEntry, &.{
        .{ 0, .{ 0x10, 0x11, 0x12 } },
        .{ 1, .{ 0xef, 0x53, 0x51 } }, // the later entry for an index wins
        .{ 255, .{ 0x00, 0xff, 0x7f } },
    }), theme.palette);
    try testing.expectEqual(@as(usize, 1), whole.offsets.items[0]);

    try testing.expectEqual(@as(i32, 130), whole.messages.items[1].child_exited);
    try testing.expectEqual(@as(usize, 2), whole.offsets.items[1]);

    const resize = whole.messages.items[2].resize;
    try testing.expectEqual(@as(u16, 100), resize.cols);
    try testing.expectEqual(@as(u16, 30), resize.rows);
    try testing.expectEqual(@as(u32, 0), resize.cell_w_px);

    // A theme without colors resets everything to the built-in ones.
    const reset = whole.messages.items[3].theme;
    try testing.expectEqual(@as(?[3]u8, null), reset.foreground);
    try testing.expectEqual(@as(usize, 0), reset.palette.len);

    // Every split of the input into two chunks gives the same result.
    for (1..data.len) |cut| {
        var two: Collector = .{ .gpa = testing.allocator };
        defer two.deinit();
        var filter: StreamFilter.StreamFilter = .init(testing.allocator, nonce);
        defer filter.deinit();
        filter.feed(data[0..cut], two.sink());
        filter.feed(data[cut..], two.sink());
        try expectSame(&whole, &two);
    }

    var single: Collector = .{ .gpa = testing.allocator };
    defer single.deinit();
    run(&single, nonce, data, 1);
    try expectSame(&whole, &single);
}

test "StreamFilter: no byte is lost when the message cannot be buffered" {
    const data = "x\x1b_rex1;7;{\"type\":\"resize\",\"cols\":80,\"rows\":24}\x1b\\y";
    var failing: testing.FailingAllocator = .init(testing.allocator, .{ .fail_index = 0 });

    var out: Collector = .{ .gpa = testing.allocator };
    defer out.deinit();
    var filter: StreamFilter.StreamFilter = .init(failing.allocator(), 7);
    defer filter.deinit();
    filter.feed(data, out.sink());

    try testing.expectEqual(@as(usize, 0), out.messages.items.len);
    try testing.expectEqualStrings(data, out.bytes.items);
}

test "stateJson: a forward is listed with the phone's port among the scalars" {
    var arena: std.heap.ArenaAllocator = .init(testing.allocator);
    defer arena.deinit();
    const a = arena.allocator();
    const forwards = [_]model.ForwardInfo{.{ .block = "block:fwd", .host = "localhost", .port = 5173, .local_port = 41000, .connections = 2 }};
    const snapshot = try model.stateSnapshot(testing.allocator, .{
        .status = .connected,
        .server = "rex",
        .url = "https://rex",
        .session = null,
        .sessions = &.{},
        .view = null,
        .blocks = &.empty,
        .forwards = &forwards,
    });
    defer snapshot.deinit(testing.allocator);
    try testing.expect(std.mem.find(u8, snapshot.text, "\"forwards\":[{\"block\":\"block:fwd\",\"host\":\"localhost\",\"port\":5173,\"localPort\":41000,\"connections\":2}]") != null);
    // A change of a forward alone sends the scalars again, not the sessions or the panes.
    const before = try model.stateSnapshot(testing.allocator, .{ .status = .connected, .server = "rex", .url = "https://rex", .session = null, .sessions = &.{}, .view = null, .blocks = &.empty });
    defer before.deinit(testing.allocator);
    var scratch: std.ArrayList(u8) = .empty;
    defer scratch.deinit(testing.allocator);
    const patch = (try snapshot.delta(before, testing.allocator, &scratch)).?;
    const change = try parseValue(a, patch);
    try testing.expect(change.object.contains("forwards") and change.object.contains("status"));
    try testing.expect(!change.object.contains("sessions") and !change.object.contains("blocks"));
}
