//! Single-file `rex.data.bytes.v1` upload. The server's API revision 18 `/llms.txt`
//! documents `upload_begin` and the fixed data handshake. For the bytes-specific EOF
//! and close, see shuv1337/rex-rune, d91f91c62a1a, src/upload.rs (`send`).
const std = @import("std");
const websocket = @import("../net/WebSocket.zig");
const WebSocket = websocket.WebSocket;
const protocol = @import("protocol.zig");

pub const max_bytes = 8 * 1024 * 1024;
const chunk_size = 32 * 1024; // Keeps the masked WebSocket send buffer reusable.
const subprotocol = "rex.data.bytes.v1";

pub const Guard = struct {
    ctx: *anyopaque,
    /// Thread-safe, nonblocking; once false for this upload, remains false.
    active: *const fn (*anyopaque) bool,
};

/// Blocking, single use. Borrow all arguments through return; `data.len` must be the
/// size declared in upload_begin. The ref belongs to that request, not to this socket.
/// On a lost close after EOF the outcome is unknown: never automatically retry.
/// `locator` is the server's (`net/Endpoint.zig`).
pub fn send(
    gpa: std.mem.Allocator,
    io: std.Io,
    locator: []const u8,
    net: websocket.Net,
    ticket: []const u8,
    data: []const u8,
    guard: Guard,
) !void {
    if (data.len > max_bytes) return error.FileTooLarge;
    var hello: [17]u8 = undefined;
    try protocol.dataHello(ticket, &hello);
    var run = Run.init(io, guard);
    try run.check();
    var worker = try io.concurrent(Run.connectAndSend, .{ &run, gpa, locator, net, hello, data });
    // Cancel blocked socket I/O, then join before borrowed memory goes away. A C
    // resolver or custom dial callback is not a Zig I/O cancellation point and must
    // supply its own bound (the Android tailnet dialer does).
    defer worker.cancel(io) catch {};
    while (!run.done.isSet()) {
        try run.check();
        run.done.waitTimeout(io, .{ .duration = .{ .clock = .awake, .raw = .fromMilliseconds(100) } }) catch |err| switch (err) {
            error.Timeout => continue,
            error.Canceled => return err,
        };
    }
    return worker.await(io);
}

const Run = struct {
    io: std.Io,
    guard: Guard,
    /// One deadline including connect/TLS, all chunks, and the final reply; a
    /// sequence of small reads or pings cannot keep extending the budget.
    deadline: std.Io.Timestamp,
    done: std.Io.Event = .unset,

    fn init(io: std.Io, guard: Guard) Run {
        return .{ .io = io, .guard = guard, .deadline = std.Io.Clock.awake.now(io).addDuration(.fromSeconds(120)) };
    }

    fn check(self: *const Run) !void {
        try self.io.checkCancel();
        if (!self.guard.active(self.guard.ctx)) return error.Canceled;
        if (std.Io.Clock.awake.now(self.io).nanoseconds >= self.deadline.nanoseconds) return error.Timeout;
    }

    fn connectAndSend(self: *Run, gpa: std.mem.Allocator, locator: []const u8, net: websocket.Net, hello: [17]u8, data: []const u8) anyerror!void {
        defer self.done.set(self.io);
        try self.check();
        const ws = try WebSocket.connect(gpa, self.io, locator, "/api/data", subprotocol, net);
        // Abort by dropping the transport on error, not by sending a success close.
        defer ws.deinit();
        try self.transfer(ws, hello, data);
    }

    fn transfer(self: *Run, ws: *WebSocket, hello: [17]u8, data: []const u8) !void {
        ws.max_message_len = 32;
        try self.check();
        try ws.sendBinary(&hello);
        const reply = (try ws.recv()) orelse return error.UploadNotConfirmed;
        if (reply.opcode != .binary) return error.BadUploadHandshake;
        switch (protocol.parseDataHandshake(reply.data)) {
            .welcome => if (!std.mem.allEqual(u8, reply.data[17..20], 0)) return error.BadUploadHandshake,
            .reject => return if (reply.data.len == 2) error.TicketRejected else error.BadUploadHandshake,
            .invalid => return error.BadUploadHandshake,
        }

        var rest = data;
        while (rest.len != 0) {
            try self.check();
            const n = @min(rest.len, chunk_size);
            try ws.sendBinary(rest[0..n]);
            rest = rest[n..];
        }
        try self.check();
        try ws.sendBinary(""); // EOF commits the reserved file, including a 0-byte file.
        if (try ws.recv() != null) return error.UnexpectedUploadReply;
        if (ws.close_code != 1000) return error.UploadNotConfirmed;
        try self.check();
    }
};

const testing = std.testing;
const Fixtures = struct {
    const zero8 = "\x00\x00\x00\x00\x00\x00\x00\x00";
    const welcome = "\x82\x20\x81" ++ zero8 ++ zero8 ++ "\x00\x00\x00" ++ "\x60\xea\x00\x00" ++ "\x01\x00\x00\x00\x00\x00\x00\x00";
    const close_ok = "\x88\x02\x03\xe8";
    const hello = "\x01\x00\x01\x02\x03\x04\x05\x06\x07\x08\x09\x0a\x0b\x0c\x0d\x0e\x0f";

    fn active(_: *anyopaque) bool {
        return true;
    }

    fn exchange(input: []const u8, data: []const u8, writer: *std.Io.Writer, guard: Guard) !void {
        var reader: std.Io.Reader = .fixed(input);
        const ws = try WebSocket.initStreams(testing.allocator, testing.io, &reader, writer);
        defer ws.deinit();
        var run = Run.init(testing.io, guard);
        try run.transfer(ws, hello.*, data);
    }

    fn frame(bytes: *[]u8, opcode: u4) ![]const u8 {
        const h = websocket.decodeHeader(bytes.*) orelse return error.MissingFrame;
        try testing.expect(h.fin and h.masked);
        try testing.expectEqual(opcode, h.opcode);
        const body = bytes.*[h.header_len..];
        try testing.expect(h.len <= body.len);
        const payload = body[0..@intCast(h.len)];
        for (payload, 0..) |*b, i| b.* ^= h.mask[i % 4];
        bytes.* = body[payload.len..];
        return payload;
    }
};

test "upload sends raw binary chunks and exactly one empty binary EOF, including for an empty file" {
    var ctx: u8 = 0;
    const guard: Guard = .{ .ctx = &ctx, .active = Fixtures.active };
    const data = try testing.allocator.alloc(u8, 65539);
    defer testing.allocator.free(data);
    for (data, 0..) |*b, i| b.* = @truncate(i);
    const sent = try testing.allocator.alloc(u8, data.len + 256);
    defer testing.allocator.free(sent);
    for ([_]usize{ 0, data.len }) |n| {
        var writer: std.Io.Writer = .fixed(sent);
        try Fixtures.exchange(Fixtures.welcome ++ Fixtures.close_ok, data[0..n], &writer, guard);
        var bytes = writer.buffered();
        try testing.expectEqualSlices(u8, Fixtures.hello, try Fixtures.frame(&bytes, 2));
        var used: usize = 0;
        while (true) {
            const chunk = try Fixtures.frame(&bytes, 2);
            if (chunk.len == 0) break;
            try testing.expect(chunk.len <= 65536 and chunk.len <= n - used);
            try testing.expectEqualSlices(u8, data[used..][0..chunk.len], chunk);
            used += chunk.len;
        }
        try testing.expectEqual(n, used);
        try testing.expectEqualSlices(u8, "\x03\xe8", try Fixtures.frame(&bytes, 8));
        try testing.expectEqual(0, bytes.len);
    }
}

test "upload rejects malformed or refused welcomes before writing file data" {
    var ctx: u8 = 0;
    const guard: Guard = .{ .ctx = &ctx, .active = Fixtures.active };
    for ([_]struct { []const u8, anyerror }{
        .{ "\x82\x02\x82\x01", error.TicketRejected },
        .{ "\x82\x01\x81", error.BadUploadHandshake },
        .{ "\x81\x01x", error.BadUploadHandshake },
        .{ Fixtures.welcome[0..26] ++ Fixtures.zero8, error.BadUploadHandshake },
    }) |case| {
        var sent: [64]u8 = undefined;
        var writer: std.Io.Writer = .fixed(&sent);
        try testing.expectError(case[1], Fixtures.exchange(case[0], "private data", &writer, guard));
        var bytes = writer.buffered();
        try testing.expectEqualSlices(u8, Fixtures.hello, try Fixtures.frame(&bytes, 2));
        try testing.expectEqual(0, bytes.len);
    }
}

test "upload completion requires the peer's normal close, not an EOF, payload, or abnormal close" {
    var ctx: u8 = 0;
    const guard: Guard = .{ .ctx = &ctx, .active = Fixtures.active };
    inline for (.{
        .{ "", error.ConnectionClosed },
        .{ "\x88\x00", error.UploadNotConfirmed },
        .{ "\x88\x02\x03\xf3", error.UploadNotConfirmed },
        .{ "\x82\x01x" ++ Fixtures.close_ok, error.UnexpectedUploadReply },
    }) |case| {
        var sent: [128]u8 = undefined;
        var writer: std.Io.Writer = .fixed(&sent);
        try testing.expectError(case[1], Fixtures.exchange(Fixtures.welcome ++ case[0], "x", &writer, guard));
    }
}

test "retarget after data prevents the committing EOF" {
    const Check = struct {
        fn beforeData(ctx: *anyopaque) bool {
            const writer: *std.Io.Writer = @ptrCast(@alignCast(ctx));
            return writer.buffered().len <= 6 + 17; // Only the masked HELLO has gone out.
        }
    };
    var sent: [128]u8 = undefined;
    var writer: std.Io.Writer = .fixed(&sent);
    try testing.expectError(error.Canceled, Fixtures.exchange(Fixtures.welcome, "x", &writer, .{ .ctx = &writer, .active = Check.beforeData }));
    var bytes = writer.buffered();
    _ = try Fixtures.frame(&bytes, 2);
    try testing.expectEqualStrings("x", try Fixtures.frame(&bytes, 2));
    try testing.expectEqual(0, bytes.len);
}

test "size and ticket errors do not dial, and exactly 8 MiB passes the size limit" {
    const data = try testing.allocator.alloc(u8, max_bytes + 1);
    defer testing.allocator.free(data);
    var ctx: u8 = 0;
    const guard: Guard = .{ .ctx = &ctx, .active = Fixtures.active };
    try testing.expectError(error.FileTooLarge, send(testing.allocator, .failing, "", .{}, "", data, guard));
    try testing.expectError(error.InvalidTicket, send(testing.allocator, .failing, "", .{}, "", data[0..max_bytes], guard));
}

test "expired deadline prevents any HELLO or file bytes" {
    var ctx: u8 = 0;
    var run = Run.init(testing.io, .{ .ctx = &ctx, .active = Fixtures.active });
    run.deadline = std.Io.Clock.awake.now(testing.io);
    var reader: std.Io.Reader = .fixed("");
    var sent: [64]u8 = undefined;
    var writer: std.Io.Writer = .fixed(&sent);
    const ws = try WebSocket.initStreams(testing.allocator, testing.io, &reader, &writer);
    defer ws.deinit();
    try testing.expectError(error.Timeout, run.transfer(ws, Fixtures.hello.*, "private data"));
    try testing.expectEqual(0, writer.buffered().len);
}
