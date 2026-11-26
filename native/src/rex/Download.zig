//! Single-file `rex.data.bytes.v1` download: the stream that `download_begin` opens.
const std = @import("std");
const websocket = @import("../net/WebSocket.zig");
const WebSocket = websocket.WebSocket;
const protocol = @import("protocol.zig");

const subprotocol = "rex.data.bytes.v1";
/// The largest message the stream may send at once.
const max_message = 4 * 1024 * 1024;
/// A stream that sends nothing for this long is given up.
const idle_ms = 60_000;
/// The most a file may have: Velo keeps 256 MiB of opened files in its cache (`CACHE_BYTES` in
/// RemoteFileOpener.kt), so a larger one could not be kept anyway, and a server that keeps
/// sending would otherwise fill the phone until the person pressed Stop. Keep the two in step.
pub const max_file_bytes: u64 = 256 * 1024 * 1024;

pub const Guard = struct {
    ctx: *anyopaque,
    /// Thread-safe, nonblocking; once false for this download, remains false.
    active: *const fn (*anyopaque) bool,
};

/// Where the file's bytes go, in order. Called on the download's own thread.
pub const Sink = struct {
    ctx: *anyopaque,
    write: *const fn (*anyopaque, []const u8) anyerror!void,
};

/// Blocking, single use: opens the stream of `ticket`, writes the file to `sink` and returns how
/// many bytes it had. Succeeds only when the server ended the file with the empty frame and a
/// normal close; any other end means the sink does not hold the whole file. `announced` is the
/// size the server gave in its `download_begin` answer, if it did: a stream that goes past it,
/// or past `max_file_bytes` whatever the server announced, ends with `error.TooLarge` and writes
/// no byte beyond the limit.
pub fn fetch(
    gpa: std.mem.Allocator,
    io: std.Io,
    host: []const u8,
    net: websocket.Net,
    ticket: []const u8,
    sink: Sink,
    guard: Guard,
    announced: ?u64,
) !u64 {
    var run = Run.init(io, guard, sink);
    run.announced = announced;
    return run.start(gpa, host, net, ticket);
}

/// Blocking: ends the stream of a ticket whose file is not wanted, such as one the app already
/// holds a current copy of. A ticket left unused keeps the file open on the server until it expires.
pub fn release(gpa: std.mem.Allocator, io: std.Io, host: []const u8, net: websocket.Net, ticket: []const u8, guard: Guard) !void {
    var none: u8 = 0;
    var run = Run.init(io, guard, .{ .ctx = &none, .write = Run.refuse });
    run.release_only = true;
    _ = try run.start(gpa, host, net, ticket);
}

/// How long a canceled download may take to close its stream itself before its socket is cut.
const close_grace_ms = 500;

fn nowMs(io: std.Io) i64 {
    return @intCast(@divTrunc(std.Io.Clock.awake.now(io).nanoseconds, std.time.ns_per_ms));
}

const Run = struct {
    io: std.Io,
    guard: Guard,
    sink: Sink,
    received: u64 = 0,
    /// The file's size as the server announced it.
    announced: ?u64 = null,
    /// No file is larger; a field only so that a test can lower it.
    ceiling: u64 = max_file_bytes,
    /// Only end the stream ([release]).
    release_only: bool = false,
    /// When the stream last sent something: the idle limit runs from here.
    last_ms: std.atomic.Value(i64),
    done: std.Io.Event = .unset,

    fn init(io: std.Io, guard: Guard, sink: Sink) Run {
        return .{ .io = io, .guard = guard, .sink = sink, .last_ms = .init(nowMs(io)) };
    }

    fn start(self: *Run, gpa: std.mem.Allocator, host: []const u8, net: websocket.Net, ticket: []const u8) !u64 {
        const io = self.io;
        var hello: [17]u8 = undefined;
        try protocol.dataHello(ticket, &hello);
        try self.check();
        var worker = try io.concurrent(connectAndFetch, .{ self, gpa, host, net, hello });
        // Cancel blocked socket I/O, then join before borrowed memory goes away.
        defer _ = worker.cancel(io) catch 0;
        while (!self.done.isSet()) {
            self.check() catch |err| {
                // The worker sees the cancel at its next message and closes the stream normally.
                self.done.waitTimeout(io, .{ .duration = .{ .clock = .awake, .raw = .fromMilliseconds(close_grace_ms) } }) catch {};
                return err;
            };
            if (nowMs(io) - self.last_ms.load(.acquire) >= idle_ms) return error.Timeout;
            self.done.waitTimeout(io, .{ .duration = .{ .clock = .awake, .raw = .fromMilliseconds(100) } }) catch |err| switch (err) {
                error.Timeout => continue,
                error.Canceled => return err,
            };
        }
        return worker.await(io);
    }

    fn refuse(_: *anyopaque, _: []const u8) anyerror!void {
        return error.UnexpectedData;
    }

    fn check(self: *const Run) !void {
        try self.io.checkCancel();
        if (!self.guard.active(self.guard.ctx)) return error.Canceled;
    }

    fn heard(self: *Run) void {
        self.last_ms.store(nowMs(self.io), .release);
    }

    fn connectAndFetch(self: *Run, gpa: std.mem.Allocator, host: []const u8, net: websocket.Net, hello: [17]u8) anyerror!u64 {
        defer self.done.set(self.io);
        try self.check();
        const ws = try WebSocket.connect(gpa, self.io, host, "/api/data", subprotocol, net);
        defer ws.deinit();
        if (self.release_only) {
            try self.releaseOn(ws, hello);
            return 0;
        }
        return self.transfer(ws, hello);
    }

    fn handshake(self: *Run, ws: *WebSocket, hello: [17]u8) !void {
        ws.max_message_len = max_message;
        try self.check();
        try ws.sendBinary(&hello);
        const reply = (try ws.recv()) orelse return error.TicketRejected;
        self.heard();
        if (reply.opcode != .binary) return error.BadHandshake;
        switch (protocol.parseDataHandshake(reply.data)) {
            .welcome => if (!std.mem.allEqual(u8, reply.data[17..20], 0)) return error.BadHandshake,
            .reject => return if (reply.data.len == 2) error.TicketRejected else error.BadHandshake,
            .invalid => return error.BadHandshake,
        }
    }

    /// A normal close from the client ends the stream: the server lets the file go.
    fn releaseOn(self: *Run, ws: *WebSocket, hello: [17]u8) !void {
        try self.handshake(ws, hello);
        ws.close();
    }

    fn transfer(self: *Run, ws: *WebSocket, hello: [17]u8) !u64 {
        try self.handshake(ws, hello);
        // A stream that ends here for any other reason ends with a normal close too: otherwise
        // the server keeps it, and the file open, waiting for a resume.
        errdefer if (ws.close_code == 0) ws.close();
        // The size is known before the first byte: a file too large is not read at all.
        const limit = @min(self.announced orelse self.ceiling, self.ceiling);
        if ((self.announced orelse 0) > self.ceiling) return error.TooLarge;
        // The empty frame is the stream's half-close: the file is whole once a normal close follows.
        var ended = false;
        while (try ws.recv()) |message| {
            self.heard();
            if (message.opcode != .binary) return error.BadStream;
            if (ended) return error.UnexpectedData;
            if (message.data.len == 0) {
                ended = true;
                continue;
            }
            try self.check();
            // A message that would pass the limit is not written, not even in part.
            if (message.data.len > limit - self.received) return error.TooLarge;
            try self.sink.write(self.sink.ctx, message.data);
            self.received += message.data.len;
        }
        if (!ended or ws.close_code != 1000) return error.Incomplete;
        return self.received;
    }
};

const testing = std.testing;
const Fixtures = struct {
    const zero8 = "\x00\x00\x00\x00\x00\x00\x00\x00";
    const welcome = "\x82\x20\x81" ++ zero8 ++ zero8 ++ "\x00\x00\x00" ++ "\x60\xea\x00\x00" ++ "\x01\x00\x00\x00\x00\x00\x00\x00";
    const eof = "\x82\x00";
    const close_ok = "\x88\x02\x03\xe8";
    const close_internal = "\x88\x02\x03\xf3";
    const hello = "\x01\x00\x01\x02\x03\x04\x05\x06\x07\x08\x09\x0a\x0b\x0c\x0d\x0e\x0f";

    fn active(_: *anyopaque) bool {
        return true;
    }

    const Received = struct {
        bytes: std.ArrayList(u8) = .empty,
        /// Turn the guard off once this many bytes have arrived.
        stop_after: ?usize = null,

        fn write(ctx: *anyopaque, data: []const u8) anyerror!void {
            const self: *Received = @ptrCast(@alignCast(ctx));
            try self.bytes.appendSlice(testing.allocator, data);
        }

        fn active(ctx: *anyopaque) bool {
            const self: *Received = @ptrCast(@alignCast(ctx));
            const limit = self.stop_after orelse return true;
            return self.bytes.items.len < limit;
        }
    };

    fn exchange(input: []const u8, writer: *std.Io.Writer, received: *Received) !u64 {
        return exchangeLimited(input, writer, received, null, max_file_bytes);
    }

    /// `exchange` for a download of a file the server announced as `announced` bytes, under a
    /// `ceiling` smaller than the real one (a test cannot stream 256 MiB).
    fn exchangeLimited(input: []const u8, writer: *std.Io.Writer, received: *Received, announced: ?u64, ceiling: u64) !u64 {
        var reader: std.Io.Reader = .fixed(input);
        const ws = try WebSocket.initStreams(testing.allocator, testing.io, &reader, writer);
        defer ws.deinit();
        var run = Run.init(testing.io, .{ .ctx = received, .active = Received.active }, .{ .ctx = received, .write = Received.write });
        run.announced = announced;
        run.ceiling = ceiling;
        return run.transfer(ws, hello.*);
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

test "download writes each chunk in order, then ends at the empty frame and a normal close" {
    var received: Fixtures.Received = .{};
    defer received.bytes.deinit(testing.allocator);
    var sent: [64]u8 = undefined;
    var writer: std.Io.Writer = .fixed(&sent);
    const n = try Fixtures.exchange(Fixtures.welcome ++ "\x82\x06hello " ++ "\x82\x05world" ++ Fixtures.eof ++ Fixtures.close_ok, &writer, &received);
    try testing.expectEqual(@as(u64, 11), n);
    try testing.expectEqualStrings("hello world", received.bytes.items);
    var bytes = writer.buffered();
    try testing.expectEqualSlices(u8, Fixtures.hello, try Fixtures.frame(&bytes, 2));
    // The close is answered: the server's stream ends instead of waiting for a resume.
    try testing.expectEqualSlices(u8, "\x03\xe8", try Fixtures.frame(&bytes, 8));
    try testing.expectEqual(0, bytes.len);
}

test "an empty file is a stream of only the empty frame" {
    var received: Fixtures.Received = .{};
    defer received.bytes.deinit(testing.allocator);
    var sent: [64]u8 = undefined;
    var writer: std.Io.Writer = .fixed(&sent);
    try testing.expectEqual(@as(u64, 0), try Fixtures.exchange(Fixtures.welcome ++ Fixtures.eof ++ Fixtures.close_ok, &writer, &received));
}

test "a stream that ends without its empty frame, with another close, or drops, is incomplete" {
    inline for (.{
        .{ "\x82\x01x" ++ Fixtures.close_ok, error.Incomplete },
        .{ "\x82\x01x" ++ Fixtures.eof ++ Fixtures.close_internal, error.Incomplete },
        .{ "\x82\x01x", error.ConnectionClosed },
    }) |case| {
        var received: Fixtures.Received = .{};
        defer received.bytes.deinit(testing.allocator);
        var sent: [64]u8 = undefined;
        var writer: std.Io.Writer = .fixed(&sent);
        try testing.expectError(case[1], Fixtures.exchange(Fixtures.welcome ++ case[0], &writer, &received));
    }
}

test "a refused ticket or a bad handshake writes nothing" {
    inline for (.{
        .{ "\x82\x02\x82\x01", error.TicketRejected },
        .{ "\x82\x01\x81", error.BadHandshake },
        .{ "\x81\x01x", error.BadHandshake },
    }) |case| {
        var received: Fixtures.Received = .{};
        defer received.bytes.deinit(testing.allocator);
        var sent: [64]u8 = undefined;
        var writer: std.Io.Writer = .fixed(&sent);
        try testing.expectError(case[1], Fixtures.exchange(case[0] ++ "\x82\x01x", &writer, &received));
        try testing.expectEqual(0, received.bytes.items.len);
    }
}

test "data after the empty frame, or a text frame, is not the file" {
    inline for (.{
        .{ "\x82\x01x" ++ Fixtures.eof ++ "\x82\x01y" ++ Fixtures.close_ok, error.UnexpectedData },
        .{ "\x81\x01x" ++ Fixtures.close_ok, error.BadStream },
    }) |case| {
        var received: Fixtures.Received = .{};
        defer received.bytes.deinit(testing.allocator);
        var sent: [64]u8 = undefined;
        var writer: std.Io.Writer = .fixed(&sent);
        try testing.expectError(case[1], Fixtures.exchange(Fixtures.welcome ++ case[0], &writer, &received));
    }
}

test "a canceled download stops before the next chunk and ends the stream with a normal close" {
    var received: Fixtures.Received = .{ .stop_after = 1 };
    defer received.bytes.deinit(testing.allocator);
    var sent: [64]u8 = undefined;
    var writer: std.Io.Writer = .fixed(&sent);
    try testing.expectError(error.Canceled, Fixtures.exchange(Fixtures.welcome ++ "\x82\x01a" ++ "\x82\x01b" ++ Fixtures.eof ++ Fixtures.close_ok, &writer, &received));
    try testing.expectEqualStrings("a", received.bytes.items);
    var bytes = writer.buffered();
    _ = try Fixtures.frame(&bytes, 2);
    // Without it the server keeps the stream, and the file open, for a resume.
    try testing.expectEqualSlices(u8, "\x03\xe8", try Fixtures.frame(&bytes, 8));
    try testing.expectEqual(0, bytes.len);
}

test "a released ticket opens its stream and ends it with a normal close, reading no file" {
    var sent: [64]u8 = undefined;
    var writer: std.Io.Writer = .fixed(&sent);
    var reader: std.Io.Reader = .fixed(Fixtures.welcome ++ "\x82\x01a");
    const ws = try WebSocket.initStreams(testing.allocator, testing.io, &reader, &writer);
    defer ws.deinit();
    var received: Fixtures.Received = .{};
    defer received.bytes.deinit(testing.allocator);
    var run = Run.init(testing.io, .{ .ctx = &received, .active = Fixtures.Received.active }, .{ .ctx = &received, .write = Fixtures.Received.write });
    try run.releaseOn(ws, Fixtures.hello.*);
    try testing.expectEqual(0, received.bytes.items.len);
    var bytes = writer.buffered();
    try testing.expectEqualSlices(u8, Fixtures.hello, try Fixtures.frame(&bytes, 2));
    try testing.expectEqualSlices(u8, "\x03\xe8", try Fixtures.frame(&bytes, 8));
    try testing.expectEqual(0, bytes.len);
}

/// The stream ended with a normal close from this side: the server lets the file go instead of
/// waiting for a resume.
fn expectClosedNormally(writer: *std.Io.Writer) !void {
    var bytes = writer.buffered();
    _ = try Fixtures.frame(&bytes, 2);
    try testing.expectEqualSlices(u8, "\x03\xe8", try Fixtures.frame(&bytes, 8));
    try testing.expectEqual(0, bytes.len);
}

test "a stream longer than the size the server announced ends with TooLarge and writes no more" {
    var received: Fixtures.Received = .{};
    defer received.bytes.deinit(testing.allocator);
    var sent: [64]u8 = undefined;
    var writer: std.Io.Writer = .fixed(&sent);
    // 6 bytes announced as 5: the second message would pass the size, so it is not written.
    try testing.expectError(error.TooLarge, Fixtures.exchangeLimited(Fixtures.welcome ++ "\x82\x03abc" ++ "\x82\x03def" ++ Fixtures.eof ++ Fixtures.close_ok, &writer, &received, 5, max_file_bytes));
    try testing.expectEqualStrings("abc", received.bytes.items);
    try expectClosedNormally(&writer);
}

test "one message that alone passes the announced size is not written" {
    var received: Fixtures.Received = .{};
    defer received.bytes.deinit(testing.allocator);
    var sent: [64]u8 = undefined;
    var writer: std.Io.Writer = .fixed(&sent);
    try testing.expectError(error.TooLarge, Fixtures.exchangeLimited(Fixtures.welcome ++ "\x82\x06abcdef" ++ Fixtures.eof ++ Fixtures.close_ok, &writer, &received, 5, max_file_bytes));
    try testing.expectEqual(0, received.bytes.items.len);
    try expectClosedNormally(&writer);
}

test "a stream longer than the ceiling ends with TooLarge when the server announced no size" {
    var received: Fixtures.Received = .{};
    defer received.bytes.deinit(testing.allocator);
    var sent: [64]u8 = undefined;
    var writer: std.Io.Writer = .fixed(&sent);
    try testing.expectError(error.TooLarge, Fixtures.exchangeLimited(Fixtures.welcome ++ "\x82\x02ab" ++ "\x82\x03cde" ++ Fixtures.eof ++ Fixtures.close_ok, &writer, &received, null, 4));
    try testing.expectEqualStrings("ab", received.bytes.items);
    try expectClosedNormally(&writer);
}

test "a file announced as larger than the ceiling is refused before it is read" {
    var received: Fixtures.Received = .{};
    defer received.bytes.deinit(testing.allocator);
    var sent: [64]u8 = undefined;
    var writer: std.Io.Writer = .fixed(&sent);
    try testing.expectError(error.TooLarge, Fixtures.exchangeLimited(Fixtures.welcome ++ "\x82\x01a" ++ Fixtures.eof ++ Fixtures.close_ok, &writer, &received, 5, 4));
    try testing.expectEqual(0, received.bytes.items.len);
    try expectClosedNormally(&writer);
    // With the real ceiling: one byte over 256 MiB.
    var again: Fixtures.Received = .{};
    defer again.bytes.deinit(testing.allocator);
    var sent2: [64]u8 = undefined;
    var writer2: std.Io.Writer = .fixed(&sent2);
    try testing.expectError(error.TooLarge, Fixtures.exchangeLimited(Fixtures.welcome ++ "\x82\x01a", &writer2, &again, max_file_bytes + 1, max_file_bytes));
    try testing.expectEqual(0, again.bytes.items.len);
    try testing.expectEqual(@as(u64, 256 * 1024 * 1024), max_file_bytes);
}

test "a file of exactly the announced size, or the ceiling, arrives whole; a shorter one is not an error" {
    inline for (.{
        .{ @as(?u64, 11), @as(u64, max_file_bytes) },
        .{ @as(?u64, null), @as(u64, 11) },
        .{ @as(?u64, 11), @as(u64, 11) },
        // The file shrank after the server announced its size.
        .{ @as(?u64, 100), @as(u64, max_file_bytes) },
    }) |case| {
        var received: Fixtures.Received = .{};
        defer received.bytes.deinit(testing.allocator);
        var sent: [64]u8 = undefined;
        var writer: std.Io.Writer = .fixed(&sent);
        const n = try Fixtures.exchangeLimited(Fixtures.welcome ++ "\x82\x06hello " ++ "\x82\x05world" ++ Fixtures.eof ++ Fixtures.close_ok, &writer, &received, case[0], case[1]);
        try testing.expectEqual(@as(u64, 11), n);
        try testing.expectEqualStrings("hello world", received.bytes.items);
    }
}
