//! Host tests for net/. Run:
//!   zig test native/src/net/test_net.zig -lc
//! Live tests need a reachable Rex server and are skipped without VELO_URL (a server
//! locator, `Endpoint.zig`; the trust store test needs an https one):
//!   VELO_URL=https://rex.example zig test native/src/net/test_net.zig -lc
//! VELO_CA_DIR=<dir of PEM files> additionally connects with that directory as the only
//! trust store (the Android path).
const std = @import("std");
const ws = @import("WebSocket.zig");
const WebSocket = ws.WebSocket;
const conn_mod = @import("Conn.zig");
const Conn = conn_mod.Conn;
const Endpoint = @import("Endpoint.zig");

const testing = std.testing;
const gpa = testing.allocator;

test "acceptKey matches the RFC 6455 example" {
    var out: [28]u8 = undefined;
    ws.acceptKey("dGhlIHNhbXBsZSBub25jZQ==", &out);
    try testing.expectEqualStrings("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", &out);
}

test "encodeFrame/decodeHeader round trip with masking" {
    const mask = [4]u8{ 0x37, 0xfa, 0x21, 0x3d };
    const cases = [_]struct { len: usize, header_len: usize }{
        .{ .len = 0, .header_len = 6 },
        .{ .len = 125, .header_len = 6 },
        .{ .len = 126, .header_len = 8 },
        .{ .len = 65535, .header_len = 8 },
        .{ .len = 65536, .header_len = 14 },
    };
    for (cases) |case| {
        const payload = try gpa.alloc(u8, case.len);
        defer gpa.free(payload);
        for (payload, 0..) |*b, i| b.* = @truncate(i *% 31 +% 7);

        var frame: std.ArrayList(u8) = .empty;
        defer frame.deinit(gpa);
        try ws.encodeFrame(&frame, gpa, true, 0x2, mask, payload);

        const header = ws.decodeHeader(frame.items).?;
        try testing.expect(header.fin);
        try testing.expectEqual(@as(u4, 0x2), header.opcode);
        try testing.expect(header.masked);
        try testing.expectEqualSlices(u8, &mask, &header.mask);
        try testing.expectEqual(@as(u64, case.len), header.len);
        try testing.expectEqual(case.header_len, header.header_len);
        try testing.expectEqual(case.header_len + case.len, frame.items.len);

        // Every strict prefix of the header needs more bytes.
        for (0..header.header_len) |n| try testing.expect(ws.decodeHeader(frame.items[0..n]) == null);

        const body = frame.items[header.header_len..];
        if (case.len >= 4) try testing.expect(!std.mem.eql(u8, body, payload));
        for (body, 0..) |*b, i| b.* ^= header.mask[i & 3];
        try testing.expectEqualSlices(u8, payload, body);
    }
}

test "encodeFrame matches the RFC 6455 masked text example" {
    var frame: std.ArrayList(u8) = .empty;
    defer frame.deinit(gpa);
    try ws.encodeFrame(&frame, gpa, true, 0x1, .{ 0x37, 0xfa, 0x21, 0x3d }, "Hello");
    try testing.expectEqualSlices(
        u8,
        &.{ 0x81, 0x85, 0x37, 0xfa, 0x21, 0x3d, 0x7f, 0x9f, 0x4d, 0x51, 0x58 },
        frame.items,
    );
}

test "decodeHeader reads unmasked server frames and non-final fragments" {
    const header = ws.decodeHeader(&.{ 0x01, 0x03, 'H', 'e', 'l' }).?;
    try testing.expect(!header.fin);
    try testing.expectEqual(@as(u4, 0x1), header.opcode);
    try testing.expect(!header.masked);
    try testing.expectEqual(@as(u64, 3), header.len);
    try testing.expectEqual(@as(usize, 2), header.header_len);

    const long = ws.decodeHeader(&.{ 0x82, 0x7F, 0, 0, 0, 0, 0, 1, 0, 0 }).?;
    try testing.expectEqual(@as(u64, 65536), long.len);
    try testing.expectEqual(@as(usize, 10), long.header_len);
}

/// Append an unmasked frame, as a server sends it.
fn serverFrame(out: *std.ArrayList(u8), fin: bool, opcode: u4, payload: []const u8) !void {
    try out.append(gpa, (@as(u8, @intFromBool(fin)) << 7) | opcode);
    if (payload.len < 126) {
        try out.append(gpa, @intCast(payload.len));
    } else if (payload.len <= 0xFFFF) {
        try out.append(gpa, 126);
        try out.appendSlice(gpa, &std.mem.toBytes(std.mem.nativeToBig(u16, @intCast(payload.len))));
    } else {
        try out.append(gpa, 127);
        try out.appendSlice(gpa, &std.mem.toBytes(std.mem.nativeToBig(u64, payload.len)));
    }
    try out.appendSlice(gpa, payload);
}

const ClientFrame = struct { opcode: u4, fin: bool, payload: []u8, rest: []u8 };

/// Decode and unmask (in place) the first client frame in `bytes`.
fn clientFrame(bytes: []u8) !ClientFrame {
    const header = ws.decodeHeader(bytes) orelse return error.TruncatedFrame;
    try testing.expect(header.masked);
    const len: usize = @intCast(header.len);
    const payload = bytes[header.header_len..][0..len];
    for (payload, 0..) |*b, i| b.* ^= header.mask[i & 3];
    return .{
        .opcode = header.opcode,
        .fin = header.fin,
        .payload = payload,
        .rest = bytes[header.header_len + len ..],
    };
}

test "recv reassembles fragments, answers ping, completes the close handshake" {
    var input: std.ArrayList(u8) = .empty;
    defer input.deinit(gpa);
    const big = try gpa.alloc(u8, 70000);
    defer gpa.free(big);
    for (big, 0..) |*b, i| b.* = @truncate(i);

    try serverFrame(&input, true, 0x1, "one");
    try serverFrame(&input, false, 0x1, "frag");
    try serverFrame(&input, true, 0x9, "ping-payload"); // control frame between fragments
    try serverFrame(&input, false, 0x0, "ment");
    try serverFrame(&input, true, 0x0, "ed");
    try serverFrame(&input, true, 0xA, ""); // unsolicited pong is ignored
    try serverFrame(&input, true, 0x2, big);
    try serverFrame(&input, true, 0x8, "\x0f\xa0block closed"); // 4000

    var reader: std.Io.Reader = .fixed(input.items);
    var sent: [256]u8 = undefined;
    var writer: std.Io.Writer = .fixed(&sent);
    const sock = try WebSocket.initStreams(gpa, testing.io, &reader, &writer);
    defer sock.deinit();

    var msg = (try sock.recv()).?;
    try testing.expectEqual(ws.Opcode.text, msg.opcode);
    try testing.expectEqualStrings("one", msg.data);

    msg = (try sock.recv()).?;
    try testing.expectEqual(ws.Opcode.text, msg.opcode);
    try testing.expectEqualStrings("fragmented", msg.data);

    msg = (try sock.recv()).?;
    try testing.expectEqual(ws.Opcode.binary, msg.opcode);
    try testing.expectEqualSlices(u8, big, msg.data);

    try testing.expect((try sock.recv()) == null);
    try testing.expectEqual(@as(u16, 4000), sock.close_code);
    try testing.expectEqualStrings("block closed", sock.close_reason);
    try testing.expect((try sock.recv()) == null); // stays closed

    // Written: a masked pong echoing the ping, then a masked close echoing the code.
    const pong = try clientFrame(writer.buffered());
    try testing.expectEqual(@as(u4, 0xA), pong.opcode);
    try testing.expect(pong.fin);
    try testing.expectEqualStrings("ping-payload", pong.payload);
    const close = try clientFrame(pong.rest);
    try testing.expectEqual(@as(u4, 0x8), close.opcode);
    try testing.expectEqualSlices(u8, "\x0f\xa0", close.payload);
    try testing.expectEqual(@as(usize, 0), close.rest.len);

    try testing.expectError(error.ConnectionClosed, sock.sendText("late"));
}

test "send masks frames; close sends 1000 once and ends recv" {
    var reader: std.Io.Reader = .fixed("");
    var sent: [256]u8 = undefined;
    var writer: std.Io.Writer = .fixed(&sent);
    const sock = try WebSocket.initStreams(gpa, testing.io, &reader, &writer);
    defer sock.deinit();

    try sock.sendText("hello");
    try sock.sendBinary(&.{ 1, 2, 3 });
    sock.close();
    sock.close();
    try testing.expectError(error.ConnectionClosed, sock.sendBinary("x"));
    try testing.expect((try sock.recv()) == null);

    const text = try clientFrame(writer.buffered());
    try testing.expectEqual(@as(u4, 0x1), text.opcode);
    try testing.expectEqualStrings("hello", text.payload);
    const binary = try clientFrame(text.rest);
    try testing.expectEqual(@as(u4, 0x2), binary.opcode);
    try testing.expectEqualSlices(u8, &.{ 1, 2, 3 }, binary.payload);
    const close = try clientFrame(binary.rest);
    try testing.expectEqual(@as(u4, 0x8), close.opcode);
    try testing.expectEqualSlices(u8, "\x03\xe8", close.payload);
    try testing.expectEqual(@as(usize, 0), close.rest.len);
}

test "recv rejects protocol violations and a dropped connection" {
    const cases = [_]struct { bytes: []const u8, err: anyerror }{
        .{ .bytes = "\x81\x83\x01\x02\x03\x04abc", .err = error.ProtocolError }, // masked server frame
        .{ .bytes = "\xc1\x01a", .err = error.ProtocolError }, // RSV1 without an extension
        .{ .bytes = "\x80\x01a", .err = error.ProtocolError }, // continuation without a start
        .{ .bytes = "\x01\x01a\x81\x01b", .err = error.ProtocolError }, // new message inside a fragmented one
        .{ .bytes = "\x09\x00", .err = error.ProtocolError }, // fragmented control frame
        .{ .bytes = "\x83\x00", .err = error.ProtocolError }, // reserved opcode
        .{ .bytes = "\x81\x02\xc3\x28", .err = error.InvalidUtf8 },
        .{ .bytes = "\x81\x05he", .err = error.ConnectionClosed }, // truncated payload
        .{ .bytes = "", .err = error.ConnectionClosed }, // no close frame
    };
    for (cases) |case| {
        var reader: std.Io.Reader = .fixed(case.bytes);
        var sent: [64]u8 = undefined;
        var writer: std.Io.Writer = .fixed(&sent);
        const sock = try WebSocket.initStreams(gpa, testing.io, &reader, &writer);
        defer sock.deinit();
        try testing.expectError(case.err, sock.recv());
        try testing.expectError(error.ConnectionClosed, sock.recv());
    }
}

test "readUpgradeResponse verifies status, accept key and subprotocol" {
    const accept = "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=";
    const ok = "HTTP/1.1 101 Switching Protocols\r\n" ++
        "upgrade: WebSocket\r\n" ++
        "Connection: keep-alive, Upgrade\r\n" ++
        "Sec-Websocket-Accept: " ++ accept ++ "\r\n";
    const cases = [_]struct { response: []const u8, subprotocol: []const u8, err: ?anyerror, status: u16 }{
        .{ .response = ok ++ "Sec-WebSocket-Protocol: rex.control.v1\r\n\r\n\x81\x00", .subprotocol = "rex.control.v1", .err = null, .status = 101 },
        .{ .response = ok ++ "\r\n", .subprotocol = "", .err = null, .status = 101 },
        .{ .response = ok ++ "\r\n", .subprotocol = "rex.control.v1", .err = error.BadSubprotocol, .status = 101 },
        .{ .response = ok ++ "Sec-WebSocket-Protocol: other\r\n\r\n", .subprotocol = "rex.control.v1", .err = error.BadSubprotocol, .status = 101 },
        .{ .response = ok ++ "Sec-WebSocket-Protocol: other\r\n\r\n", .subprotocol = "", .err = error.BadSubprotocol, .status = 101 },
        .{ .response = "HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n", .subprotocol = "p", .err = error.UpgradeRejected, .status = 403 },
        .{ .response = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" ++
            "Sec-WebSocket-Accept: AAAAAAAAAAAAAAAAAAAAAAAAAAA=\r\n\r\n", .subprotocol = "", .err = error.BadHandshake, .status = 101 },
        .{ .response = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\n", .subprotocol = "", .err = error.BadHandshake, .status = 101 },
        .{ .response = ok ++ "Sec-WebSocket-Extensions: permessage-deflate\r\n\r\n", .subprotocol = "", .err = error.BadHandshake, .status = 101 },
        .{ .response = "HTTP/1.1 101 Switching", .subprotocol = "", .err = error.ConnectionClosed, .status = 0 },
        .{ .response = "garbage\r\n\r\n", .subprotocol = "", .err = error.BadHandshake, .status = 0 },
    };
    for (cases) |case| {
        var reader: std.Io.Reader = .fixed(case.response);
        var status: u16 = 0;
        const result = ws.readUpgradeResponse(&reader, accept, case.subprotocol, &status);
        if (case.err) |err| try testing.expectError(err, result) else try result;
        try testing.expectEqual(case.status, status);
    }

    // Bytes after the blank line stay in the reader for the frame layer.
    var reader: std.Io.Reader = .fixed(cases[0].response);
    var status: u16 = 0;
    try ws.readUpgradeResponse(&reader, accept, "rex.control.v1", &status);
    try testing.expectEqualSlices(u8, "\x81\x00", reader.buffered());
}

// ---- Live tests ----

const control_path = "/api/control";
const control_protocol = "rex.control.v1";
const hello =
    \\{"type":"hello","api_revision":18,"client":{"kind":"cli","name":"net-test"}}
;

fn env(comptime name: [:0]const u8) ?[]const u8 {
    const value = std.mem.span(std.c.getenv(name) orelse return null);
    return if (value.len == 0) null else value;
}

/// VELO_URL, a server locator (`Endpoint.zig`), or skip.
fn liveLocator() ![]const u8 {
    return env("VELO_URL") orelse error.SkipZigTest;
}

/// Connect, send hello, expect the welcome. Returns the open socket.
fn liveWelcome(locator: []const u8, ca_dirs: []const []const u8) !*WebSocket {
    const sock = try WebSocket.connect(gpa, testing.io, locator, control_path, control_protocol, .{ .ca_dirs = ca_dirs });
    errdefer sock.deinit();
    try testing.expectEqual(@as(u16, 101), sock.status_code);
    try sock.sendText(hello);
    const msg = (try sock.recv()) orelse {
        std.debug.print("closed before welcome: {d} {s}\n", .{ sock.close_code, sock.close_reason });
        return error.ClosedBeforeWelcome;
    };
    try testing.expectEqual(ws.Opcode.text, msg.opcode);
    try testing.expect(std.mem.find(u8, msg.data, "\"type\":\"welcome\"") != null);
    try testing.expect(std.mem.find(u8, msg.data, "\"protocol\":\"rex.control.v1\"") != null);
    return sock;
}

test "live: control hello/welcome with the system trust store" {
    const locator = try liveLocator();
    const sock = try liveWelcome(locator, &.{});
    defer sock.deinit();
    sock.close();
    try testing.expect((try sock.recv()) == null);
}

test "live: control hello/welcome with VELO_CA_DIR as the only trust store" {
    const locator = try liveLocator();
    const dir = env("VELO_CA_DIR") orelse return error.SkipZigTest;
    const sock = try liveWelcome(locator, &.{dir});
    defer sock.deinit();
    sock.close();
    try testing.expect((try sock.recv()) == null);
}

/// A fresh directory under /tmp, removed by `deinit`. Also used by rex/test_core.zig.
pub const ScratchDir = struct {
    buffer: [64]u8 = undefined,
    len: usize = 0,

    pub fn init(self: *ScratchDir) !void {
        var random: [8]u8 = undefined;
        testing.io.random(&random);
        const p = try std.fmt.bufPrint(&self.buffer, "/tmp/velo-net-test-{x}", .{std.mem.readInt(u64, &random, .little)});
        self.len = p.len;
        try std.Io.Dir.createDirAbsolute(testing.io, p, .default_dir);
    }

    pub fn path(self: *const ScratchDir) []const u8 {
        return self.buffer[0..self.len];
    }

    fn write(self: *const ScratchDir, name: []const u8, data: []const u8) !void {
        var dir = try std.Io.Dir.openDirAbsolute(testing.io, self.path(), .{});
        defer dir.close(testing.io);
        try dir.writeFile(testing.io, .{ .sub_path = name, .data = data });
    }

    pub fn deinit(self: *const ScratchDir) void {
        std.Io.Dir.cwd().deleteTree(testing.io, self.path()) catch {};
    }
};

/// Microsoft ECC Root Certificate Authority 2017: a valid root that did not issue
/// the server's chain.
const unrelated_root =
    \\-----BEGIN CERTIFICATE-----
    \\MIICWTCCAd+gAwIBAgIQZvI9r4fei7FK6gxXMQHC7DAKBggqhkjOPQQDAzBlMQsw
    \\CQYDVQQGEwJVUzEeMBwGA1UEChMVTWljcm9zb2Z0IENvcnBvcmF0aW9uMTYwNAYD
    \\VQQDEy1NaWNyb3NvZnQgRUNDIFJvb3QgQ2VydGlmaWNhdGUgQXV0aG9yaXR5IDIw
    \\MTcwHhcNMTkxMjE4MjMwNjQ1WhcNNDIwNzE4MjMxNjA0WjBlMQswCQYDVQQGEwJV
    \\UzEeMBwGA1UEChMVTWljcm9zb2Z0IENvcnBvcmF0aW9uMTYwNAYDVQQDEy1NaWNy
    \\b3NvZnQgRUNDIFJvb3QgQ2VydGlmaWNhdGUgQXV0aG9yaXR5IDIwMTcwdjAQBgcq
    \\hkjOPQIBBgUrgQQAIgNiAATUvD0CQnVBEyPNgASGAlEvaqiBYgtlzPbKnR5vSmZR
    \\ogPZnZH6thaxjG7efM3beaYvzrvOcS/lpaso7GMEZpn4+vKTEAXhgShC48Zo9OYb
    \\hGBKia/teQ87zvH2RPUBeMCjVDBSMA4GA1UdDwEB/wQEAwIBhjAPBgNVHRMBAf8E
    \\BTADAQH/MB0GA1UdDgQWBBTIy5lycFIM+Oa+sgRXKSrPQhDtNTAQBgkrBgEEAYI3
    \\FQEEAwIBADAKBggqhkjOPQQDAwNoADBlAjBY8k3qDPlfXu5gKcs68tvWMoQZP3zV
    \\L8KxzJOuULsJMsbG7X7JNpQS5GiFBqIb0C8CMQCZ6Ra0DvpWSNSkMBaReNtUjGUB
    \\iudQZsIxtzm6uBoiB078a1QWIP8rtedMDE2mT3M=
    \\-----END CERTIFICATE-----
    \\
;

test "live: a trust store without the server's root fails the handshake" {
    const locator = try liveLocator();
    const endpoint = Endpoint.parse(locator) catch return error.SkipZigTest;
    if (endpoint.kind != .https) return error.SkipZigTest;
    var scratch: ScratchDir = .{};
    try scratch.init();
    defer scratch.deinit();

    // No certificates at all (a missing directory counts the same).
    try testing.expectError(error.NoCaCertificates, Conn.open(gpa, testing.io, locator, .{ .ca_dirs = &.{scratch.path()} }));
    try testing.expectError(error.NoCaCertificates, Conn.open(gpa, testing.io, locator, .{ .ca_dirs = &.{"/nonexistent/cacerts"} }));

    // Malformed files are skipped; the one valid root is not the server's.
    try scratch.write("junk.0", "not a certificate\n");
    try scratch.write("cut.0", "-----BEGIN CERTIFICATE-----\nMIICWTCCAd+g\n");
    try testing.expectError(error.NoCaCertificates, Conn.open(gpa, testing.io, locator, .{ .ca_dirs = &.{scratch.path()} }));
    try scratch.write("root.0", unrelated_root ++ "Certificate:\n    Data:\n        Version: 3 (0x2)\n");
    try testing.expectError(
        error.TlsCertificateNotVerified,
        Conn.open(gpa, testing.io, locator, .{ .ca_dirs = &.{ "/nonexistent/cacerts", scratch.path() } }),
    );
}

fn sendFromThread(sock: *WebSocket, text: []const u8, failed: *bool) void {
    sock.sendText(text) catch {
        failed.* = true;
    };
}

fn closeFromThread(sock: *WebSocket) void {
    std.Io.sleep(testing.io, .fromMilliseconds(300), .awake) catch {};
    sock.close();
}

test "live: send from another thread, close from another thread unblocks recv" {
    const locator = try liveLocator();
    const sock = try liveWelcome(locator, &.{});
    defer sock.deinit();

    // A read-only request sent by a second thread while this one is in recv().
    var failed = false;
    const sender = try std.Thread.spawn(.{}, sendFromThread, .{
        sock,
        \\{"type":"request","id":"net-test-1","method":"server.version"}
        ,
        &failed,
    });
    var answered = false;
    while (try sock.recv()) |msg| {
        if (std.mem.find(u8, msg.data, "\"id\":\"net-test-1\"") != null) {
            try testing.expect(std.mem.find(u8, msg.data, "\"type\":\"response\"") != null);
            try testing.expect(std.mem.find(u8, msg.data, "\"error\"") == null);
            answered = true;
            break;
        }
    }
    sender.join();
    try testing.expect(!failed);
    try testing.expect(answered);

    // Nothing more is due from the server, so recv() blocks until close().
    const closer = try std.Thread.spawn(.{}, closeFromThread, .{sock});
    const started = std.Io.Clock.awake.now(testing.io);
    while (try sock.recv()) |_| {}
    const waited = started.durationTo(std.Io.Clock.awake.now(testing.io)).toMilliseconds();
    closer.join();
    try testing.expect(waited >= 200 and waited < 5000);
    try testing.expectError(error.ConnectionClosed, sock.sendText("{}"));
}

test "live: upgrade is rejected on a path that is not a WebSocket" {
    const locator = try liveLocator();
    var status: u16 = 0;
    try testing.expectError(
        error.UpgradeRejected,
        WebSocket.connectStatus(gpa, testing.io, locator, "/api/nope", control_protocol, .{}, &status, null),
    );
    try testing.expectEqual(@as(u16, 404), status);
}

test "live: a subprotocol the server does not select is refused" {
    const locator = try liveLocator();
    try testing.expectError(
        error.BadSubprotocol,
        WebSocket.connect(gpa, testing.io, locator, control_path, "rex.bogus.v1", .{}),
    );
}

/// Records the dial and hands out one end of a socketpair; the test holds the other.
const PairDialer = struct {
    host: [64]u8 = undefined,
    host_len: usize = 0,
    port: u16 = 0,
    peer: std.posix.fd_t = -1,
    decline: bool = false,

    fn dial(ctx: ?*anyopaque, host: []const u8, port: u16) anyerror!?std.posix.fd_t {
        const self: *PairDialer = @ptrCast(@alignCast(ctx.?));
        @memcpy(self.host[0..host.len], host);
        self.host_len = host.len;
        self.port = port;
        if (self.decline) return error.TailnetDown;
        var fds: [2]std.posix.fd_t = undefined;
        if (std.c.socketpair(std.posix.AF.UNIX, std.posix.SOCK.STREAM, 0, &fds) != 0) return error.SystemResources;
        self.peer = fds[1];
        return fds[0];
    }

    fn dialer(self: *PairDialer) ws.Dialer {
        return .{ .ctx = self, .dial = dial };
    }
};

/// Reads what the client sent first, then hangs up so the handshake fails.
fn readClientHello(fd: std.posix.fd_t, out: *[5]u8, got: *usize) void {
    got.* = 0;
    while (got.* < out.len) {
        const n = std.c.read(fd, out[got.*..].ptr, out.len - got.*);
        if (n <= 0) break;
        got.* += @intCast(n);
    }
    _ = std.c.close(fd);
}

/// A CA directory holding one root (not the server's): `Conn.open` gets to the dial
/// without the host's trust store.
fn oneRootDir(scratch: *ScratchDir) ![]const u8 {
    try scratch.init();
    try scratch.write("root.0", unrelated_root);
    return scratch.path();
}

/// The dial happens inside open; the reader starts once the peer end exists, and gives
/// up after 5 s if open fails before it dials.
fn watchPeer(p: *PairDialer, out: *[5]u8, n: *usize) void {
    var waited: u32 = 0;
    while (@atomicLoad(std.posix.fd_t, &p.peer, .acquire) < 0) : (waited += 1) {
        if (waited == 5000) return;
        std.Io.sleep(testing.io, .fromMilliseconds(1), .awake) catch {};
    }
    readClientHello(p.peer, out, n);
}

test "a dialer carries the connection: TLS goes out on the socket it returns" {
    var scratch: ScratchDir = .{};
    const ca_dir = try oneRootDir(&scratch);
    defer scratch.deinit();
    var pair: PairDialer = .{};
    var record: [5]u8 = undefined;
    var got: usize = 0;
    const watcher = try std.Thread.spawn(.{}, watchPeer, .{ &pair, &record, &got });
    const result = Conn.open(gpa, testing.io, "rex.example.ts.net", .{ .ca_dirs = &.{ca_dir}, .dialer = pair.dialer() });
    watcher.join();
    if (result) |conn| conn.deinit() else |_| {}
    try testing.expect(std.meta.isError(result));
    try testing.expectEqualStrings("rex.example.ts.net", pair.host[0..pair.host_len]);
    try testing.expectEqual(@as(u16, 443), pair.port);
    // A TLS record of type handshake (0x16), version 3.x: the ClientHello.
    try testing.expectEqual(@as(usize, 5), got);
    try testing.expectEqual(@as(u8, 0x16), record[0]);
    try testing.expectEqual(@as(u8, 0x03), record[1]);
}

test "a dialer error fails the connection without falling back to the system network" {
    var scratch: ScratchDir = .{};
    const ca_dir = try oneRootDir(&scratch);
    defer scratch.deinit();
    var pair: PairDialer = .{ .decline = true };
    try testing.expectError(error.TailnetDown, Conn.open(gpa, testing.io, "rex.example.ts.net", .{ .ca_dirs = &.{ca_dir}, .dialer = pair.dialer() }));
    try testing.expectEqual(@as(u16, 443), pair.port);
}

test "a dialer is asked for the locator's port" {
    var scratch: ScratchDir = .{};
    const ca_dir = try oneRootDir(&scratch);
    defer scratch.deinit();
    var pair: PairDialer = .{};
    var record: [5]u8 = undefined;
    var got: usize = 0;
    const watcher = try std.Thread.spawn(.{}, watchPeer, .{ &pair, &record, &got });
    const result = Conn.open(gpa, testing.io, "https://rex.example.ts.net:8443", .{ .ca_dirs = &.{ca_dir}, .dialer = pair.dialer() });
    watcher.join();
    if (result) |conn| conn.deinit() else |_| {}
    try testing.expect(std.meta.isError(result));
    try testing.expectEqualStrings("rex.example.ts.net", pair.host[0..pair.host_len]);
    try testing.expectEqual(@as(u16, 8443), pair.port);
    // Still TLS: the ClientHello goes out on the dialed socket.
    try testing.expectEqual(@as(usize, 5), got);
    try testing.expectEqual(@as(u8, 0x16), record[0]);
    try testing.expectEqual(@as(u8, 0x03), record[1]);
}

/// Reads the first TLS record the client sends (the ClientHello) from the dialed peer,
/// then hangs up.
fn readHelloRecord(p: *PairDialer, out: *[4096]u8, n: *usize) void {
    var waited: u32 = 0;
    while (@atomicLoad(std.posix.fd_t, &p.peer, .acquire) < 0) : (waited += 1) {
        if (waited == 5000) return;
        std.Io.sleep(testing.io, .fromMilliseconds(1), .awake) catch {};
    }
    defer _ = std.c.close(p.peer);
    n.* = 0;
    var want: usize = 5;
    while (n.* < want) {
        const got = std.c.read(p.peer, out[n.*..].ptr, want - n.*);
        if (got <= 0) return;
        n.* += @intCast(got);
        if (n.* == 5) want = @min(out.len, 5 + @as(usize, std.mem.readInt(u16, out[3..5], .big)));
    }
}

test "the ClientHello names a DNS host (SNI) and never an IP host" {
    var scratch: ScratchDir = .{};
    const ca_dir = try oneRootDir(&scratch);
    defer scratch.deinit();
    const cases = [_]struct { locator: []const u8, host: []const u8, sni: bool }{
        .{ .locator = "https://rex.example.ts.net:8443", .host = "rex.example.ts.net", .sni = true },
        .{ .locator = "https://192.0.2.29:8443", .host = "192.0.2.29", .sni = false },
        .{ .locator = "https://[2001:db8::1]:8443", .host = "2001:db8::1", .sni = false },
    };
    for (cases) |case| {
        var pair: PairDialer = .{};
        var record: [4096]u8 = undefined;
        var got: usize = 0;
        const reader = try std.Thread.spawn(.{}, readHelloRecord, .{ &pair, &record, &got });
        const result = Conn.open(gpa, testing.io, case.locator, .{ .ca_dirs = &.{ca_dir}, .dialer = pair.dialer() });
        reader.join();
        if (result) |conn| conn.deinit() else |_| {}
        try testing.expectEqualStrings(case.host, pair.host[0..pair.host_len]);
        try testing.expect(got > 5);
        try testing.expectEqual(@as(u8, 0x16), record[0]);
        const named = std.mem.find(u8, record[0..got], case.host) != null;
        if (named != case.sni) {
            std.debug.print("{s}: SNI {s}\n", .{ case.locator, if (named) "sent" else "missing" });
            return error.TestUnexpectedResult;
        }
    }
}

/// A server on a real socket: accepts one client on its own thread and keeps the first
/// bytes it sends. Gives up after 5 s when no client comes.
const Listener = struct {
    server: std.Io.net.Server,
    thread: std.Thread = undefined,
    got: [3]u8 = undefined,
    len: usize = 0,

    fn start(self: *Listener) !void {
        self.thread = try std.Thread.spawn(.{}, run, .{self});
    }

    fn run(self: *Listener) void {
        var fds = [_]std.posix.pollfd{.{ .fd = self.server.socket.handle, .events = std.posix.POLL.IN, .revents = 0 }};
        if ((std.posix.poll(&fds, 5000) catch 0) == 0) return;
        const stream = self.server.accept(testing.io) catch return;
        defer stream.close(testing.io);
        var buffer: [16]u8 = undefined;
        var reader = stream.reader(testing.io, &buffer);
        reader.interface.readSliceAll(&self.got) catch return;
        self.len = self.got.len;
    }

    /// Waits for the server thread, closes the listening socket, returns what it read.
    fn finish(self: *Listener) []const u8 {
        self.thread.join();
        self.server.deinit(testing.io);
        return self.got[0..self.len];
    }
};

/// Open `locator`, send the start of an HTTP request, close.
fn sendGet(locator: []const u8, net: ws.Net) !void {
    const conn = try Conn.open(gpa, testing.io, locator, net);
    defer conn.deinit();
    try conn.writer().writeAll("GET / HTTP/1.1\r\n\r\n");
    try conn.flush();
}

test "plain http sends the request without TLS" {
    const address: std.Io.net.IpAddress = .{ .ip4 = .loopback(0) };
    var listener: Listener = .{ .server = try address.listen(testing.io, .{}) };
    try listener.start();
    var text: [32]u8 = undefined;
    const locator = try std.fmt.bufPrint(&text, "http://127.0.0.1:{d}", .{listener.server.socket.address.getPort()});
    const sent = sendGet(locator, .{});
    const got = listener.finish();
    try sent;
    try testing.expectEqualStrings("GET", got);
}

test "a unix socket path connects without TLS" {
    var scratch: ScratchDir = .{};
    try scratch.init();
    defer scratch.deinit();
    var text: [96]u8 = undefined;
    const path = try std.fmt.bufPrint(&text, "{s}/rex.sock", .{scratch.path()});
    const address = try std.Io.net.UnixAddress.init(path);
    var listener: Listener = .{ .server = try address.listen(testing.io, .{}) };
    try listener.start();
    var pair: PairDialer = .{ .decline = true };
    const sent = sendGet(path, .{ .dialer = pair.dialer() });
    const got = listener.finish();
    try sent;
    try testing.expectEqualStrings("GET", got);
    try testing.expectEqual(@as(usize, 0), pair.host_len); // the dialer was not asked
}

test "a missing socket path fails with FileNotFound" {
    var scratch: ScratchDir = .{};
    try scratch.init();
    defer scratch.deinit();
    var text: [96]u8 = undefined;
    const path = try std.fmt.bufPrint(&text, "{s}/none.sock", .{scratch.path()});
    try testing.expectError(error.FileNotFound, Conn.open(gpa, testing.io, path, .{}));
}

/// Stands in for the app's SSH pool: records the locator and hands out one end of a
/// socketpair; the test holds the other.
const SshPair = struct {
    locator: [64]u8 = undefined,
    locator_len: usize = 0,
    peer: std.posix.fd_t = -1,

    fn dial(ctx: ?*anyopaque, locator: []const u8, _: *conn_mod.SshProblem) anyerror!std.posix.fd_t {
        const self: *SshPair = @ptrCast(@alignCast(ctx.?));
        @memcpy(self.locator[0..locator.len], locator);
        self.locator_len = locator.len;
        var fds: [2]std.posix.fd_t = undefined;
        if (std.c.socketpair(std.posix.AF.UNIX, std.posix.SOCK.STREAM, 0, &fds) != 0) return error.SystemResources;
        @atomicStore(std.posix.fd_t, &self.peer, fds[1], .release);
        return fds[0];
    }

    fn dialer(self: *SshPair) ws.SshDialer {
        return .{ .ctx = self, .dial = dial };
    }
};

/// Reads the client's request head from the peer end, then hangs up so the upgrade
/// fails. Gives up after 5 s if no dial comes.
fn readRequestHead(p: *SshPair, out: *[512]u8, n: *usize) void {
    var waited: u32 = 0;
    while (@atomicLoad(std.posix.fd_t, &p.peer, .acquire) < 0) : (waited += 1) {
        if (waited == 5000) return;
        std.Io.sleep(testing.io, .fromMilliseconds(1), .awake) catch {};
    }
    defer _ = std.c.close(p.peer);
    n.* = 0;
    while (n.* < out.len and std.mem.find(u8, out[0..n.*], "\r\n\r\n") == null) {
        const got = std.c.read(p.peer, out[n.*..].ptr, out.len - n.*);
        if (got <= 0) return;
        n.* += @intCast(got);
    }
}

test "an ssh locator dials the ssh dialer and speaks plain HTTP" {
    var pair: SshPair = .{};
    var tcp: PairDialer = .{ .decline = true };
    var head: [512]u8 = undefined;
    var got: usize = 0;
    const reader = try std.Thread.spawn(.{}, readRequestHead, .{ &pair, &head, &got });
    const result = WebSocket.connect(gpa, testing.io, " ssh://me@h:2222 ", control_path, control_protocol, .{
        .dialer = tcp.dialer(),
        .ssh = pair.dialer(),
    });
    reader.join();
    if (result) |sock| sock.deinit() else |_| {}
    try testing.expect(std.meta.isError(result));
    // The locator as given, trimmed: the pool parses it again.
    try testing.expectEqualStrings("ssh://me@h:2222", pair.locator[0..pair.locator_len]);
    // No TLS: the request goes out as is, to the server's socket (Host as for a socket path).
    const want = "GET /api/control HTTP/1.1\r\nHost: localhost\r\n";
    if (!std.mem.startsWith(u8, head[0..got], want)) {
        std.debug.print("request head: '{s}'\n", .{head[0..got]});
        return error.TestUnexpectedResult;
    }
    try testing.expectEqual(@as(usize, 0), tcp.host_len); // the TCP dialer was not asked
}

test "an ssh locator without an ssh dialer is SshUnavailable" {
    var problem: conn_mod.SshProblem = .{};
    try testing.expectError(error.SshUnavailable, Conn.openExplained(gpa, testing.io, "ssh://me@h", .{}, &problem));
    try testing.expectEqualStrings("ssh_unavailable", problem.code());
    try testing.expectEqualStrings("SSH needs the app's native library.", problem.sentence());
    try testing.expectEqualStrings("", problem.fix());
    try testing.expectError(error.SshUnavailable, Conn.open(gpa, testing.io, "ssh://me@h", .{}));
    try testing.expectError(error.BadLocator, Conn.open(gpa, testing.io, "ftp://h", .{}));
}

test "the ssh pool's error JSON becomes the problem" {
    var problem: conn_mod.SshProblem = .{};
    try problem.setJson(gpa,
        \\{"code":"not_installed","sentence":"Velo couldn't find Rex on h.\nSee \"rex\".","fix":"rex server install ssh://me@h"}
    );
    try testing.expectEqualStrings("not_installed", problem.code());
    try testing.expectEqualStrings("Velo couldn't find Rex on h.\nSee \"rex\".", problem.sentence());
    try testing.expectEqualStrings("rex server install ssh://me@h", problem.fix());

    // No fix, an unknown field: still a problem.
    try problem.setJson(gpa,
        \\{"code":"unreachable","sentence":"Velo couldn't reach h on port 22.","fix":"","extra":1}
    );
    try testing.expectEqualStrings("unreachable", problem.code());
    try testing.expectEqualStrings("Velo couldn't reach h on port 22.", problem.sentence());
    try testing.expectEqualStrings("", problem.fix());

    // Not the pool's JSON: an error, and the problem is empty.
    try testing.expectError(error.BadProblem, problem.setJson(gpa, "not json"));
    try testing.expectEqualStrings("", problem.code());
    try testing.expectError(error.BadProblem, problem.setJson(gpa, "{\"sentence\":\"no code\"}"));
    try testing.expectEqualStrings("", problem.code());

    // A fix that does not fit is dropped whole, never cut; the sentence is cut at a
    // character boundary.
    const long_fix: [conn_mod.SshProblem.capacity]u8 = @splat('x');
    problem.set("key_refused", "h didn't accept this phone's key for me.", &long_fix);
    try testing.expectEqualStrings("key_refused", problem.code());
    try testing.expectEqualStrings("h didn't accept this phone's key for me.", problem.sentence());
    try testing.expectEqualStrings("", problem.fix());
    // U+D55C, 3 bytes, again and again: no cut at the capacity falls on a boundary by luck.
    var long_sentence: [3 * conn_mod.SshProblem.capacity]u8 = undefined;
    for (0..conn_mod.SshProblem.capacity) |i| @memcpy(long_sentence[3 * i ..][0..3], "\u{D55C}");
    problem.set("unreachable", &long_sentence, "");
    try testing.expect(problem.sentence().len < long_sentence.len);
    try testing.expect(std.unicode.utf8ValidateSlice(problem.sentence()));
}
