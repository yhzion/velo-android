//! One forwarded TCP connection over one `rex.data.bytes.v1` stream of a
//! `com.superlogical.tunnel` dial block, which connects each stream its owner opens to the
//! block's `host:port`. Rex.app's notes for API revision 14 describe the block. What the
//! server does with the stream was measured against a revision 18 server:
//!
//! | The app | The server |
//! |---|---|
//! | Sends binary messages | Writes them to the remote connection |
//! | Sends an empty binary message | Half-closes the remote connection (FIN) |
//! | Closes the stream before both halves ended, with any code, or drops it | Resets the remote connection |
//! | — | Sends an empty binary message when the remote half-closes, and closes with 1000 once both halves ended |
//! | — | Closes with 1001 when the remote connection fails or resets, or cannot be made |
//! | Opens a stream of a block it does not own | Closes it with 4006 |
const std = @import("std");
const websocket = @import("../net/WebSocket.zig");
const WebSocket = websocket.WebSocket;
const Stream = std.Io.net.Stream;
const protocol = @import("protocol.zig");

pub const subprotocol = "rex.data.bytes.v1";
/// The close code of a stream opened by a client that does not own the block.
pub const close_not_owner: u16 = 4006;
/// The most the app sends in one message.
const chunk_size = 32 * 1024;

pub const End = enum {
    /// Both sides ended their halves and the server closed the stream normally.
    closed,
    /// One side failed or went away: the other side's connection was reset.
    reset,
    /// The server refused the stream because this client does not own the block. The
    /// connection was reset.
    not_owner,
};

/// Blocking: opens the data connection of `ticket`, which `data.connect` gave for a `conn:`
/// stream of the block, on the server at `locator`, and does its handshake.
pub fn open(gpa: std.mem.Allocator, io: std.Io, locator: []const u8, net: websocket.Net, ticket: []const u8) !*WebSocket {
    var hello: [17]u8 = undefined;
    try protocol.dataHello(ticket, &hello);
    const ws = try WebSocket.connect(gpa, io, locator, "/api/data", subprotocol, net);
    errdefer ws.deinit();
    try handshake(ws, &hello);
    return ws;
}

fn handshake(ws: *WebSocket, hello: *const [17]u8) !void {
    // The server's messages hold at most 64 KiB.
    ws.max_message_len = 1024 * 1024;
    try ws.sendBinary(hello);
    const reply = (try ws.recv()) orelse
        return if (ws.close_code == close_not_owner) error.NotOwner else error.TicketRejected;
    if (reply.opcode != .binary) return error.BadHandshake;
    switch (protocol.parseDataHandshake(reply.data)) {
        .welcome => if (!std.mem.allEqual(u8, reply.data[17..20], 0)) return error.BadHandshake,
        .reject => return if (reply.data.len == 2) error.TicketRejected else error.BadHandshake,
        .invalid => return error.BadHandshake,
    }
}

/// Blocking. Relays `local`, a connection accepted on the phone, over `ws`, a data stream
/// whose handshake is done, until both ends finish or one fails. Closes `local`; the caller
/// still owns `ws`.
pub fn relay(io: std.Io, ws: *WebSocket, local: Stream) End {
    var up = io.concurrent(sendUp, .{ io, ws, local }) catch {
        ws.close();
        reset(io, local);
        return .reset;
    };
    const end = receiveDown(io, ws, local);
    if (end == .closed) {
        // The server closes normally only after the app's empty message: `sendUp` has
        // ended, and the browser has half-closed. Closing now ends the connection cleanly.
        up.cancel(io);
        local.close(io);
        return .closed;
    }
    // A close before both halves ended makes the server reset the remote connection.
    ws.close();
    // Set before `sendUp` stops reading, so the close below sends the reset.
    setResetOnClose(local);
    up.cancel(io);
    local.close(io);
    return end;
}

/// The browser's bytes to the stream, then its half-close as the empty message. A reset
/// from the browser closes the stream at once, which resets the remote connection.
fn sendUp(io: std.Io, ws: *WebSocket, local: Stream) void {
    var buffer: [chunk_size]u8 = undefined;
    while (true) {
        var data: [1][]u8 = .{&buffer};
        const n = local.read(io, &data) catch |err| switch (err) {
            error.Canceled => return,
            else => return ws.close(),
        };
        if (n == 0) {
            ws.sendBinary("") catch {};
            return;
        }
        ws.sendBinary(buffer[0..n]) catch return;
    }
}

/// The stream's bytes to the browser, and the remote's half-close as the browser's end of
/// stream, until the stream closes.
fn receiveDown(io: std.Io, ws: *WebSocket, local: Stream) End {
    var buffer: [4096]u8 = undefined;
    var writer = local.writer(io, &buffer);
    var remote_ended = false;
    while (true) {
        const message = (ws.recv() catch return .reset) orelse break;
        if (message.opcode != .binary or remote_ended) return .reset;
        if (message.data.len == 0) {
            remote_ended = true;
            local.shutdown(io, .send) catch return .reset;
            continue;
        }
        writer.interface.writeAll(message.data) catch return .reset;
        writer.interface.flush() catch return .reset;
    }
    return switch (ws.close_code) {
        1000 => if (remote_ended) .closed else .reset,
        close_not_owner => .not_owner,
        else => .reset,
    };
}

/// Makes the socket's close send a reset (RST) instead of the end of stream (FIN).
fn setResetOnClose(local: Stream) void {
    const linger: extern struct { on: c_int, seconds: c_int } = .{ .on = 1, .seconds = 0 };
    std.posix.setsockopt(local.socket.handle, std.posix.SOL.SOCKET, std.posix.SO.LINGER, std.mem.asBytes(&linger)) catch {};
}

/// Resets `local` and closes it.
pub fn reset(io: std.Io, local: Stream) void {
    setResetOnClose(local);
    local.close(io);
}

const testing = std.testing;

/// A forwarded connection with both of its ends in the test: the browser's socket, and the
/// Rex server's end of the data connection, which reads the app's frames and writes its own.
const Fixture = struct {
    io: std.Io,
    browser: Stream,
    local: Stream,
    server: Stream,
    client: Stream,
    client_reader: Stream.Reader,
    client_writer: Stream.Writer,
    server_reader: Stream.Reader,
    client_in: [64 * 1024]u8 = undefined,
    client_out: [64 * 1024]u8 = undefined,
    server_in: [64 * 1024]u8 = undefined,
    ws: *WebSocket,
    relay: ?std.Io.Future(End) = null,
    started: bool = false,
    watchdog: ?std.Io.Future(void) = null,

    /// A connected pair of loopback sockets.
    fn pair(io: std.Io) ![2]Stream {
        var address: std.Io.net.IpAddress = .{ .ip4 = .loopback(0) };
        var listener = try address.listen(io, .{});
        defer listener.deinit(io);
        const connected = try listener.socket.address.connect(io, .{ .mode = .stream });
        errdefer connected.close(io);
        return .{ connected, try listener.accept(io) };
    }

    /// After 5 s, ends the browser's and the server's sockets: a test that waits for
    /// something that never comes fails instead of hanging.
    fn watch(self: *Fixture) void {
        std.Io.sleep(self.io, .fromSeconds(5), .awake) catch return;
        self.browser.shutdown(self.io, .both) catch {};
        self.server.shutdown(self.io, .both) catch {};
    }

    fn create() !*Fixture {
        const io = testing.io;
        const self = try testing.allocator.create(Fixture);
        errdefer testing.allocator.destroy(self);
        const forwarded = try pair(io);
        const data = try pair(io);
        self.* = .{
            .io = io,
            .browser = forwarded[0],
            .local = forwarded[1],
            .server = data[0],
            .client = data[1],
            .client_reader = undefined,
            .client_writer = undefined,
            .server_reader = undefined,
            .ws = undefined,
        };
        self.client_reader = self.client.reader(io, &self.client_in);
        self.client_writer = self.client.writer(io, &self.client_out);
        self.server_reader = self.server.reader(io, &self.server_in);
        self.ws = try WebSocket.initStreams(testing.allocator, io, &self.client_reader.interface, &self.client_writer.interface);
        self.watchdog = try io.concurrent(watch, .{self});
        return self;
    }

    fn start(self: *Fixture) !void {
        self.relay = try self.io.concurrent(relay, .{ self.io, self.ws, self.local });
        self.started = true;
    }

    /// The relay's end, once it ends by itself.
    fn end(self: *Fixture) End {
        const result = self.relay.?.await(self.io);
        self.relay = null;
        return result;
    }

    fn destroy(self: *Fixture) void {
        if (self.watchdog) |*w| w.cancel(self.io);
        // The relay closes `local` when it ends.
        if (self.relay) |*r| _ = r.cancel(self.io);
        if (!self.started) self.local.close(self.io);
        self.ws.deinit();
        self.client.close(self.io);
        self.server.close(self.io);
        self.browser.close(self.io);
        testing.allocator.destroy(self);
    }

    /// Writes one unmasked frame, as the server does.
    fn serverSend(self: *Fixture, opcode: u4, payload: []const u8) !void {
        var header: [10]u8 = undefined;
        header[0] = 0x80 | @as(u8, opcode);
        var n: usize = 2;
        if (payload.len < 126) {
            header[1] = @intCast(payload.len);
        } else if (payload.len <= std.math.maxInt(u16)) {
            header[1] = 126;
            std.mem.writeInt(u16, header[2..4], @intCast(payload.len), .big);
            n = 4;
        } else {
            header[1] = 127;
            std.mem.writeInt(u64, header[2..10], payload.len, .big);
            n = 10;
        }
        var buffer: [256]u8 = undefined;
        var w = self.server.writer(self.io, &buffer);
        try w.interface.writeAll(header[0..n]);
        try w.interface.writeAll(payload);
        try w.interface.flush();
    }

    fn serverClose(self: *Fixture, code: u16) !void {
        var payload: [2]u8 = undefined;
        std.mem.writeInt(u16, &payload, code, .big);
        try self.serverSend(0x8, &payload);
    }

    const Frame = struct { opcode: u4, payload: []u8 };

    /// Reads one frame the app sent, unmasked. The payload lives until the next read.
    fn serverRecv(self: *Fixture) !Frame {
        const r = &self.server_reader.interface;
        var n: usize = 2;
        const header = while (true) : (n += 1) {
            if (websocket.decodeHeader(try r.peek(n))) |h| break h;
        };
        try testing.expect(header.fin and header.masked);
        r.toss(header.header_len);
        const payload = try r.take(@intCast(header.len));
        for (payload, 0..) |*b, i| b.* ^= header.mask[i % 4];
        return .{ .opcode = header.opcode, .payload = payload };
    }

    fn expectFrame(self: *Fixture, opcode: u4, payload: []const u8) !void {
        const frame = try self.serverRecv();
        try testing.expectEqual(opcode, frame.opcode);
        try testing.expectEqualSlices(u8, payload, frame.payload);
    }

    /// Reads exactly `want` from the browser's socket.
    fn browserExpect(self: *Fixture, want: []const u8) !void {
        var got: [256]u8 = undefined;
        var at: usize = 0;
        while (at < want.len) {
            var data: [1][]u8 = .{got[at..want.len]};
            const n = try self.browser.read(self.io, &data);
            if (n == 0) return error.EndOfStream;
            at += n;
        }
        try testing.expectEqualSlices(u8, want, got[0..want.len]);
    }

    /// What the browser's next read finds: 0 for the end of the stream.
    fn browserRead(self: *Fixture) !usize {
        var got: [256]u8 = undefined;
        var data: [1][]u8 = .{&got};
        return self.browser.read(self.io, &data);
    }

    fn browserWrite(self: *Fixture, bytes: []const u8) !void {
        var buffer: [256]u8 = undefined;
        var w = self.browser.writer(self.io, &buffer);
        try w.interface.writeAll(bytes);
        try w.interface.flush();
    }
};

test "bytes go both ways, and the browser's half-close reaches the remote as the empty message" {
    const f = try Fixture.create();
    defer f.destroy();
    try f.start();
    try f.browserWrite("GET / HTTP/1.0\r\n\r\n");
    try f.expectFrame(0x2, "GET / HTTP/1.0\r\n\r\n");
    try f.serverSend(0x2, "HTTP/1.0 200 OK\r\n\r\n");
    try f.browserExpect("HTTP/1.0 200 OK\r\n\r\n");
    try f.browser.shutdown(f.io, .send);
    try f.expectFrame(0x2, "");
    try f.serverSend(0x2, "listing");
    try f.serverSend(0x2, "");
    try f.browserExpect("listing");
    try testing.expectEqual(0, try f.browserRead());
    try f.serverClose(1000);
    try testing.expectEqual(End.closed, f.end());
    // The close is answered, so the server ends the stream.
    try f.expectFrame(0x8, "\x03\xe8");
}

test "the remote's half-close ends the browser's reading, and the browser can still send" {
    const f = try Fixture.create();
    defer f.destroy();
    try f.start();
    try f.serverSend(0x2, "banner");
    try f.serverSend(0x2, "");
    try f.browserExpect("banner");
    try testing.expectEqual(0, try f.browserRead());
    try f.browserWrite("late");
    try f.expectFrame(0x2, "late");
    try f.browser.shutdown(f.io, .send);
    try f.expectFrame(0x2, "");
    try f.serverClose(1000);
    try testing.expectEqual(End.closed, f.end());
}

test "a large message reaches the browser whole, and a large write leaves in pieces" {
    const f = try Fixture.create();
    defer f.destroy();
    try f.start();
    const big = try testing.allocator.alloc(u8, 200 * 1024);
    defer testing.allocator.free(big);
    for (big, 0..) |*b, i| b.* = @truncate(i *% 7);
    try f.serverSend(0x2, big);
    var got: std.ArrayList(u8) = .empty;
    defer got.deinit(testing.allocator);
    while (got.items.len < big.len) {
        var buffer: [16 * 1024]u8 = undefined;
        var data: [1][]u8 = .{&buffer};
        const n = try f.browser.read(f.io, &data);
        try testing.expect(n > 0);
        try got.appendSlice(testing.allocator, buffer[0..n]);
    }
    try testing.expectEqualSlices(u8, big, got.items);

    try f.browserWrite(big[0..1000]);
    var sent: usize = 0;
    while (sent < 1000) {
        const frame = try f.serverRecv();
        try testing.expectEqual(0x2, frame.opcode);
        try testing.expect(frame.payload.len > 0 and frame.payload.len <= chunk_size);
        try testing.expectEqualSlices(u8, big[sent..][0..frame.payload.len], frame.payload);
        sent += frame.payload.len;
    }
}

test "a close other than 1000 resets the browser's connection" {
    const f = try Fixture.create();
    defer f.destroy();
    try f.start();
    try f.browserWrite("x");
    try f.expectFrame(0x2, "x");
    try f.serverClose(1001);
    try testing.expectEqual(End.reset, f.end());
    try testing.expectError(error.ConnectionResetByPeer, f.browserRead());
}

test "a close of 1000 before the remote's half-close resets the browser's connection" {
    const f = try Fixture.create();
    defer f.destroy();
    try f.start();
    try f.serverSend(0x2, "partial");
    try f.browserExpect("partial");
    try f.serverClose(1000);
    try testing.expectEqual(End.reset, f.end());
    try testing.expectError(error.ConnectionResetByPeer, f.browserRead());
}

test "a dropped data connection resets the browser's connection" {
    const f = try Fixture.create();
    defer f.destroy();
    try f.start();
    try f.server.shutdown(f.io, .both);
    try testing.expectEqual(End.reset, f.end());
    try testing.expectError(error.ConnectionResetByPeer, f.browserRead());
}

test "a stream of a block this client does not own ends as not_owner" {
    const f = try Fixture.create();
    defer f.destroy();
    try f.start();
    try f.serverClose(close_not_owner);
    try testing.expectEqual(End.not_owner, f.end());
    try testing.expectError(error.ConnectionResetByPeer, f.browserRead());
}

test "data after the remote's half-close, or a text message, resets the connection" {
    inline for (.{ .{ 0x2, "" }, .{ 0x1, "text" } }) |first| {
        const f = try Fixture.create();
        defer f.destroy();
        try f.start();
        try f.serverSend(first[0], first[1]);
        try f.serverSend(0x2, "more");
        try testing.expectEqual(End.reset, f.end());
        // The stream is closed before both halves ended: the server resets the remote.
        try f.expectFrame(0x8, "\x03\xe8");
    }
}

test "a reset from the browser closes the stream before its end, so the remote is reset too" {
    const f = try Fixture.create();
    defer f.destroy();
    try f.start();
    try f.browserWrite("x");
    try f.expectFrame(0x2, "x");
    const linger: extern struct { on: c_int, seconds: c_int } = .{ .on = 1, .seconds = 0 };
    try std.posix.setsockopt(f.browser.socket.handle, std.posix.SOL.SOCKET, std.posix.SO.LINGER, std.mem.asBytes(&linger));
    f.browser.close(f.io);
    // A socket for destroy() to close.
    const spare = try Fixture.pair(f.io);
    spare[1].close(f.io);
    f.browser = spare[0];
    try f.expectFrame(0x8, "\x03\xe8");
    try testing.expectEqual(End.reset, f.end());
}

test "two connections relay at once without mixing their bytes" {
    const a = try Fixture.create();
    defer a.destroy();
    const b = try Fixture.create();
    defer b.destroy();
    try a.start();
    try b.start();
    try a.browserWrite("from a");
    try b.browserWrite("from b");
    try b.expectFrame(0x2, "from b");
    try a.expectFrame(0x2, "from a");
    try b.serverSend(0x2, "to b");
    try a.serverSend(0x2, "to a");
    try a.browserExpect("to a");
    try b.browserExpect("to b");
    for ([_]*Fixture{ a, b }) |f| {
        try f.browser.shutdown(f.io, .send);
        try f.expectFrame(0x2, "");
        try f.serverSend(0x2, "");
        try testing.expectEqual(0, try f.browserRead());
        try f.serverClose(1000);
        try testing.expectEqual(End.closed, f.end());
    }
}

test "a close of 4006 before the welcome says the stream is not this client's" {
    var reader: std.Io.Reader = .fixed("\x88\x02\x0f\xa6");
    var sent: [64]u8 = undefined;
    var writer: std.Io.Writer = .fixed(&sent);
    const ws = try WebSocket.initStreams(testing.allocator, testing.io, &reader, &writer);
    defer ws.deinit();
    const hello = "\x01\x00\x01\x02\x03\x04\x05\x06\x07\x08\x09\x0a\x0b\x0c\x0d\x0e\x0f";
    try testing.expectError(error.NotOwner, handshake(ws, hello));
}

test "a canceled relay ends while its sending half is stuck on a full stream and a ping waits for its answer" {
    const f = try Fixture.create();
    defer f.destroy();
    // Small buffers fill at once.
    const small: c_int = 16 * 1024;
    try std.posix.setsockopt(f.client.socket.handle, std.posix.SOL.SOCKET, std.posix.SO.SNDBUF, std.mem.asBytes(&small));
    try std.posix.setsockopt(f.server.socket.handle, std.posix.SOL.SOCKET, std.posix.SO.RCVBUF, std.mem.asBytes(&small));
    try f.start();
    // The browser sends more than the stream takes: the relay's write blocks, holding the
    // frame lock, since the server reads nothing.
    const Flood = struct {
        fn run(browser: Stream) void {
            var chunk: [64 * 1024]u8 = @splat('x');
            var buffer: [256]u8 = undefined;
            var w = browser.writer(testing.io, &buffer);
            for (0..1024) |_| w.interface.writeAll(&chunk) catch return;
        }
    };
    const flood = try std.Thread.spawn(.{}, Flood.run, .{f.browser});
    defer flood.join();
    try std.Io.sleep(testing.io, .fromMilliseconds(300), .awake);
    // The pong waits for the frame lock.
    try f.serverSend(0x9, "ping");
    try std.Io.sleep(testing.io, .fromMilliseconds(100), .awake);

    const start = std.Io.Clock.awake.now(testing.io);
    _ = f.relay.?.cancel(testing.io);
    f.relay = null;
    const waited = std.Io.Clock.awake.now(testing.io).nanoseconds - start.nanoseconds;
    try testing.expect(waited < std.time.ns_per_s);
}
