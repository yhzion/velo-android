//! Host probe: connects the core to a live Rex server and prints what it sees.
//!
//!   probe LOCATOR [session-label]
//!
//! LOCATOR is any server locator the core connects to (`rex/Core.zig` `locate`): an
//! https or http URL, a bare host name, or a Unix socket path. A locator the core
//! refuses is reported with an explanation and exits 2.
//! Prints every state JSON the core publishes, one per line. Once the core is
//! connected and the focused terminal of the shown window has its snapshot, prints
//! `SCREEN<<` + that pane's text + `>>` and exits 0. Exits 1 after 20 s.
//! With a session label, selects that session first.
const std = @import("std");
const pane = @import("pane");
const core_mod = @import("rex/Core.zig");

const Core = core_mod.Core;

const timeout_ms = 20_000;
const poll_ms = 50;

const Probe = struct {
    gpa: std.mem.Allocator,
    lock: pane.Lock = .{},
    /// Latest state JSON (protected by `lock`).
    state: std.ArrayList(u8) = .empty,
    /// Serializes writes to stdout/stderr.
    out_lock: pane.Lock = .{},

    fn write(self: *Probe, fd: std.c.fd_t, parts: []const []const u8) void {
        self.out_lock.lock();
        defer self.out_lock.unlock();
        for (parts) |part| {
            var rest = part;
            while (rest.len > 0) {
                const n = std.c.write(fd, rest.ptr, rest.len);
                if (n <= 0) return;
                rest = rest[@intCast(n)..];
            }
        }
    }

    fn onState(ctx: *anyopaque, _: u64, text: []const u8, _: ?[]const u8) void {
        const self: *Probe = @ptrCast(@alignCast(ctx));
        self.write(1, &.{ text, "\n" });
        self.lock.lock();
        defer self.lock.unlock();
        self.state.clearRetainingCapacity();
        self.state.appendSlice(self.gpa, text) catch {};
    }

    fn onReconnectToken(ctx: *anyopaque, token: []const u8) void {
        const self: *Probe = @ptrCast(@alignCast(ctx));
        // The token is a secret: report only that one arrived.
        var buf: [64]u8 = undefined;
        const line = std.fmt.bufPrint(&buf, "TOKEN<<{d} bytes>>\n", .{token.len}) catch return;
        self.write(1, &.{line});
    }

    fn onError(ctx: *anyopaque, code: []const u8, message: []const u8, fix: []const u8) void {
        const self: *Probe = @ptrCast(@alignCast(ctx));
        if (fix.len == 0) return self.write(1, &.{ "ERROR<<", code, ": ", message, ">>\n" });
        self.write(1, &.{ "ERROR<<", code, ": ", message, " FIX: ", fix, ">>\n" });
    }

    /// The probe has no fonts; the host build never renders.
    fn noGlyph(_: *anyopaque, _: []const u8, _: bool, _: bool, _: u32, _: u32, _: u32) ?pane.GlyphBitmap {
        return null;
    }
};

fn field(v: std.json.Value, name: []const u8) ?std.json.Value {
    return switch (v) {
        .object => |o| o.get(name),
        else => null,
    };
}

fn string(v: ?std.json.Value) ?[]const u8 {
    return switch (v orelse return null) {
        .string => |s| s,
        else => null,
    };
}

fn firstLeaf(node: ?std.json.Value) ?[]const u8 {
    const n = node orelse return null;
    if (string(field(n, "block"))) |block| return block;
    return firstLeaf(field(n, "first")) orelse firstLeaf(field(n, "second"));
}

/// What the main loop needs from one published state.
const Snapshot = struct {
    connected: bool = false,
    /// Selected session id.
    session: ?[]const u8 = null,
    /// Id of the session labelled `wanted`.
    wanted_session: ?[]const u8 = null,
    /// Focused block of the shown window, else its first tiled block.
    block: ?[]const u8 = null,
};

fn inspect(arena: std.mem.Allocator, state: []const u8, wanted_label: ?[]const u8) Snapshot {
    const root = std.json.parseFromSliceLeaky(std.json.Value, arena, state, .{}) catch return .{};
    var out: Snapshot = .{
        .connected = std.mem.eql(u8, string(field(root, "status")) orelse "", "connected"),
        .session = string(field(root, "session")),
        .block = string(field(root, "focused")),
    };
    if (wanted_label) |label| {
        const sessions: []const std.json.Value = switch (field(root, "sessions") orelse .null) {
            .array => |a| a.items,
            else => &.{},
        };
        for (sessions) |s| {
            if (std.mem.eql(u8, string(field(s, "label")) orelse "", label)) {
                out.wanted_session = string(field(s, "id"));
                break;
            }
        }
    }
    if (out.block == null) {
        const window = string(field(root, "window")) orelse return out;
        const windows: []const std.json.Value = switch (field(root, "windows") orelse .null) {
            .array => |a| a.items,
            else => &.{},
        };
        for (windows) |w| {
            if (std.mem.eql(u8, string(field(w, "id")) orelse "", window)) out.block = firstLeaf(field(w, "layout"));
        }
    }
    return out;
}

pub fn main(init: std.process.Init) !void {
    const gpa = init.gpa;
    const io = init.io;
    const args = try init.minimal.args.toSlice(init.arena.allocator());
    if (args.len < 2 or args.len > 3) {
        std.debug.print("usage: probe LOCATOR [session-label]\n", .{});
        std.process.exit(2);
    }
    const url: []const u8 = args[1];
    switch (core_mod.locate(url)) {
        .endpoint => {},
        .refused => |refusal| {
            std.debug.print("probe: {f}\n", .{refusal});
            std.process.exit(2);
        },
    }
    const wanted_label: ?[]const u8 = if (args.len > 2) args[2] else null;

    var probe: Probe = .{ .gpa = gpa };
    defer probe.state.deinit(gpa);
    const core = try Core.create(gpa, .{
        .ctx = &probe,
        .onState = Probe.onState,
        .onReconnectToken = Probe.onReconnectToken,
        .onError = Probe.onError,
        .glyphs = .{ .ctx = &probe, .rasterize = Probe.noGlyph },
    }, .{}, 1);
    core.connect(url, null);

    var arena: std.heap.ArenaAllocator = .init(gpa);
    defer arena.deinit();
    var selected_wanted = false;
    var waited: u32 = 0;
    while (waited < timeout_ms) : (waited += poll_ms) {
        try std.Io.sleep(io, .fromMilliseconds(poll_ms), .awake);
        _ = arena.reset(.retain_capacity);
        probe.lock.lock();
        const state = arena.allocator().dupe(u8, probe.state.items) catch "";
        probe.lock.unlock();
        if (state.len == 0) continue;

        const snap = inspect(arena.allocator(), state, wanted_label);
        if (!snap.connected) continue;
        if (wanted_label != null) {
            const wanted = snap.wanted_session orelse continue;
            if (!std.mem.eql(u8, snap.session orelse "", wanted)) {
                if (!selected_wanted) core.selectSession(wanted);
                selected_wanted = true;
                continue;
            }
        }
        const block = snap.block orelse continue;
        if (!core.paneReady(block)) continue;

        const screen = try core.paneCopy(gpa, block);
        defer gpa.free(screen);
        probe.write(1, &.{ "SCREEN<<", screen, ">>\n" });
        core.destroy();
        return;
    }

    probe.write(2, &.{"probe: timed out waiting for a connected core and a restored terminal pane\n"});
    std.process.exit(1);
}
