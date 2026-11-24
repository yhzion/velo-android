//! RFC 6455 WebSocket client over a `Conn`. No extensions/compression.
//!
//! Threading: one thread calls `recv`; any thread may call `sendText`, `sendBinary`
//! and `close`. `deinit` must not run concurrently with any other call.
const std = @import("std");
const conn_mod = @import("Conn.zig");
const Conn = conn_mod.Conn;
const endpoint_mod = @import("Endpoint.zig");
const Endpoint = endpoint_mod.Endpoint;
pub const Net = conn_mod.Net;
pub const Dialer = conn_mod.Dialer;
pub const SshDialer = conn_mod.SshDialer;
pub const SshProblem = conn_mod.SshProblem;

pub const Opcode = enum { text, binary };
pub const Message = struct { opcode: Opcode, data: []const u8 }; // valid until next recv()

const op_continuation: u4 = 0x0;
const op_text: u4 = 0x1;
const op_binary: u4 = 0x2;
const op_close: u4 = 0x8;
const op_ping: u4 = 0x9;
const op_pong: u4 = 0xA;

const max_control_len = 125;
const close_normal: u16 = 1000;
/// Close code reported when the peer's close frame carried none.
const close_no_status: u16 = 1005;

pub const WebSocket = struct {
    gpa: std.mem.Allocator,
    io: std.Io,
    conn: ?*Conn,
    reader: *std.Io.Reader,
    writer: *std.Io.Writer,

    /// Serializes frame writes (sends, pong, close).
    write_lock: std.Io.Mutex = .init,
    /// Frame being encoded; guarded by `write_lock`.
    out: std.ArrayList(u8) = .empty,
    /// A close frame was written (or writing failed): no more frames go out.
    close_sent: std.atomic.Value(bool) = .init(false),
    /// `close()` was called.
    local_close: std.atomic.Value(bool) = .init(false),

    /// Message being reassembled; owned by the recv thread.
    message: std.ArrayList(u8) = .empty,
    recv_state: enum { open, closed, failed } = .open,
    reason_buffer: [max_control_len - 2]u8 = undefined,

    /// Largest reassembled message `recv` accepts.
    max_message_len: usize = 64 * 1024 * 1024,

    close_code: u16 = 0,
    close_reason: []const u8 = "",
    status_code: u16 = 0,

    /// HTTP/1.1 upgrade of `path` on the server at `locator` (`Endpoint.zig`; over TLS
    /// for `https`) with `Sec-WebSocket-Protocol: subprotocol`. Fails with
    /// error.UpgradeRejected (status in `status_code`) or error.BadSubprotocol, and as
    /// `Conn.open` does for the locator.
    pub fn connect(
        gpa: std.mem.Allocator,
        io: std.Io,
        locator: []const u8,
        path: []const u8,
        subprotocol: []const u8,
        net: Net,
    ) !*WebSocket {
        return connectStatus(gpa, io, locator, path, subprotocol, net, null, null);
    }

    /// `connect` that also reports the HTTP status of the upgrade response, which is
    /// the only way to read it when the upgrade is rejected (no `WebSocket` exists),
    /// and why an `ssh` connection failed (`Conn.openExplained`).
    pub fn connectStatus(
        gpa: std.mem.Allocator,
        io: std.Io,
        locator: []const u8,
        path: []const u8,
        subprotocol: []const u8,
        net: Net,
        status_out: ?*u16,
        problem_out: ?*SshProblem,
    ) !*WebSocket {
        const endpoint = endpoint_mod.parse(locator) catch return error.BadLocator;
        var host_buffer: [Endpoint.max_host_header]u8 = undefined;
        const host = endpoint.hostHeader(&host_buffer);
        const conn = try Conn.openExplained(gpa, io, locator, net, problem_out);
        errdefer conn.deinit();

        var nonce: [16]u8 = undefined;
        io.random(&nonce);
        var key_buffer: [24]u8 = undefined;
        const key = std.base64.standard.Encoder.encode(&key_buffer, &nonce);

        writeUpgradeRequest(conn.writer(), host, path, key, subprotocol) catch return conn.writeError();
        try conn.flush();

        var accept: [28]u8 = undefined;
        acceptKey(key, &accept);
        var status: u16 = 0;
        defer if (status_out) |out| {
            out.* = status;
        };
        readUpgradeResponse(conn.reader(), &accept, subprotocol, &status) catch |err| switch (err) {
            error.ReadFailed => return conn.readError(),
            else => |e| return e,
        };

        const self = try gpa.create(WebSocket);
        self.* = .{
            .gpa = gpa,
            .io = io,
            .conn = conn,
            .reader = conn.reader(),
            .writer = conn.writer(),
            .status_code = status,
        };
        return self;
    }

    /// A WebSocket over already-upgraded streams (no handshake, no socket). For tests.
    pub fn initStreams(gpa: std.mem.Allocator, io: std.Io, reader: *std.Io.Reader, writer: *std.Io.Writer) !*WebSocket {
        const self = try gpa.create(WebSocket);
        self.* = .{ .gpa = gpa, .io = io, .conn = null, .reader = reader, .writer = writer };
        return self;
    }

    /// Blocks. Reassembles fragments, answers ping with pong, returns null on close
    /// (close code in `close_code`, reason in `close_reason`).
    ///
    /// A connection that ends without a close frame is `error.ConnectionClosed`,
    /// unless `close()` was called, which also yields null.
    pub fn recv(self: *WebSocket) !?Message {
        switch (self.recv_state) {
            .open => {},
            .closed => return null,
            .failed => return error.ConnectionClosed,
        }
        const message = self.recvMessage() catch |err| {
            if (self.local_close.load(.acquire)) {
                self.recv_state = .closed;
                return null;
            }
            self.recv_state = .failed;
            return switch (err) {
                error.EndOfStream => error.ConnectionClosed,
                error.ReadFailed => if (self.conn) |conn| conn.readError() else err,
                else => |e| e,
            };
        };
        if (message == null) self.recv_state = .closed;
        return message;
    }

    fn recvMessage(self: *WebSocket) !?Message {
        const r = self.reader;
        self.message.clearRetainingCapacity();
        if (self.message.capacity > 1024 * 1024) self.message.clearAndFree(self.gpa);
        var message_opcode: ?Opcode = null;

        while (true) {
            const first = try r.peek(2);
            // RSV1-3 are only set by extensions, and none was negotiated.
            if (first[0] & 0x70 != 0) return error.ProtocolError;
            const header_len = headerLen(first[1]);
            const header = decodeHeader(try r.peek(header_len)).?;
            r.toss(header_len);
            // A server must not mask its frames.
            if (header.masked) return error.ProtocolError;

            if (header.opcode >= 0x8) {
                if (!header.fin or header.len > max_control_len) return error.ProtocolError;
                var payload_buffer: [max_control_len]u8 = undefined;
                const payload = payload_buffer[0..@intCast(header.len)];
                try r.readSliceAll(payload);
                switch (header.opcode) {
                    op_ping => try self.sendControl(op_pong, payload),
                    op_pong => {},
                    op_close => {
                        try self.peerClosed(payload);
                        return null;
                    },
                    else => return error.ProtocolError,
                }
                continue;
            }

            switch (header.opcode) {
                op_continuation => if (message_opcode == null) return error.ProtocolError,
                op_text, op_binary => {
                    if (message_opcode != null) return error.ProtocolError;
                    message_opcode = if (header.opcode == op_text) .text else .binary;
                },
                else => return error.ProtocolError,
            }
            if (header.len > self.max_message_len - self.message.items.len) return error.MessageTooBig;
            const len: usize = @intCast(header.len);
            try self.message.ensureUnusedCapacity(self.gpa, len);
            try r.readSliceAll(self.message.unusedCapacitySlice()[0..len]);
            self.message.items.len += len;

            if (header.fin) {
                const opcode = message_opcode.?;
                if (opcode == .text and !std.unicode.utf8ValidateSlice(self.message.items))
                    return error.InvalidUtf8;
                return .{ .opcode = opcode, .data = self.message.items };
            }
        }
    }

    /// Record the peer's close frame and answer it, completing the close handshake.
    fn peerClosed(self: *WebSocket, payload: []const u8) !void {
        if (payload.len == 1) return error.ProtocolError;
        if (payload.len >= 2) {
            self.close_code = std.mem.readInt(u16, payload[0..2], .big);
            const reason = self.reason_buffer[0 .. payload.len - 2];
            @memcpy(reason, payload[2..]);
            self.close_reason = reason;
        } else {
            self.close_code = close_no_status;
            self.close_reason = "";
        }
        // Echo the status code. The connection is done either way, so a failed
        // write is not an error for the caller.
        self.sendClose(payload[0..@min(payload.len, 2)]);
    }

    /// Thread-safe against recv() and other sends (internal write lock). Masks frames.
    pub fn sendText(self: *WebSocket, data: []const u8) !void {
        return self.sendFrame(op_text, data);
    }

    pub fn sendBinary(self: *WebSocket, data: []const u8) !void {
        return self.sendFrame(op_binary, data);
    }

    fn sendControl(self: *WebSocket, opcode: u4, payload: []const u8) !void {
        // A pong after our close frame is not allowed and not needed.
        self.sendFrame(opcode, payload) catch |err| switch (err) {
            error.ConnectionClosed => {},
            else => |e| return e,
        };
    }

    fn sendFrame(self: *WebSocket, opcode: u4, payload: []const u8) !void {
        // Cancelable: a task waiting here, such as a pong behind a send stuck on a full
        // connection, still ends when it is canceled.
        try self.write_lock.lock(self.io);
        defer self.write_lock.unlock(self.io);
        if (self.close_sent.load(.acquire)) return error.ConnectionClosed;
        try self.writeFrameLocked(opcode, payload);
    }

    /// Write one final frame. Caller holds `write_lock`.
    fn writeFrameLocked(self: *WebSocket, opcode: u4, payload: []const u8) !void {
        var mask: [4]u8 = undefined;
        self.io.random(&mask);
        self.out.clearRetainingCapacity();
        defer if (self.out.capacity > 64 * 1024) self.out.clearAndFree(self.gpa);
        try encodeFrame(&self.out, self.gpa, true, opcode, mask, payload);
        self.writeAllLocked(self.out.items) catch |err| {
            // Part of a frame may be on the wire: the connection is unusable.
            self.close_sent.store(true, .release);
            if (self.conn) |conn| conn.shutdown();
            return err;
        };
    }

    fn writeAllLocked(self: *WebSocket, bytes: []const u8) !void {
        if (self.conn) |conn| {
            self.writer.writeAll(bytes) catch return conn.writeError();
            try conn.flush();
        } else {
            try self.writer.writeAll(bytes);
            try self.writer.flush();
        }
    }

    /// Send a close frame unless one went out already. Never blocks behind a sender
    /// that is stuck on a dead connection for longer than `close_wait_ms`.
    fn sendClose(self: *WebSocket, payload: []const u8) void {
        const close_wait_ms = 250;
        const poll_ms = 5;
        var waited: u32 = 0;
        while (!self.write_lock.tryLock()) : (waited += poll_ms) {
            if (waited >= close_wait_ms) return;
            std.Io.sleep(self.io, .fromMilliseconds(poll_ms), .awake) catch return;
        }
        defer self.write_lock.unlock(self.io);
        if (self.close_sent.swap(true, .acq_rel)) return;
        self.writeFrameLocked(op_close, payload) catch {};
    }

    /// Sends close (1000) if still open and shuts the socket down; unblocks recv().
    pub fn close(self: *WebSocket) void {
        self.local_close.store(true, .release);
        var payload: [2]u8 = undefined;
        std.mem.writeInt(u16, &payload, close_normal, .big);
        self.sendClose(&payload);
        if (self.conn) |conn| conn.shutdown();
    }

    pub fn deinit(self: *WebSocket) void {
        const gpa = self.gpa;
        if (self.conn) |conn| conn.deinit();
        self.out.deinit(gpa);
        self.message.deinit(gpa);
        gpa.destroy(self);
    }
};

/// `host` is the `Host:` header value (`Endpoint.hostHeader`).
fn writeUpgradeRequest(
    w: *std.Io.Writer,
    host: []const u8,
    path: []const u8,
    key: []const u8,
    subprotocol: []const u8,
) std.Io.Writer.Error!void {
    try w.print("GET {s} HTTP/1.1\r\n" ++
        "Host: {s}\r\n" ++
        "Upgrade: websocket\r\n" ++
        "Connection: Upgrade\r\n" ++
        "Sec-WebSocket-Key: {s}\r\n" ++
        "Sec-WebSocket-Version: 13\r\n", .{ path, host, key });
    if (subprotocol.len != 0) try w.print("Sec-WebSocket-Protocol: {s}\r\n", .{subprotocol});
    try w.writeAll("\r\n");
}

pub const UpgradeError = error{
    /// The response was not `101`; the status is in `status`.
    UpgradeRejected,
    /// The server did not select the requested subprotocol.
    BadSubprotocol,
    /// Not a valid WebSocket upgrade response (including a wrong accept key).
    BadHandshake,
    ConnectionClosed,
    ReadFailed,
};

/// Read and verify the HTTP response to an upgrade request, up to and including the
/// blank line. `accept` is the expected `Sec-WebSocket-Accept`. `status` receives the
/// HTTP status as soon as the status line is parsed.
pub fn readUpgradeResponse(
    r: *std.Io.Reader,
    accept: []const u8,
    subprotocol: []const u8,
    status: *u16,
) UpgradeError!void {
    const max_lines = 100;
    var line_buffer: [max_line_len]u8 = undefined;

    const status_line = try takeLine(r, &line_buffer);
    if (status_line.len < 12 or !std.mem.startsWith(u8, status_line, "HTTP/1.")) return error.BadHandshake;
    if (status_line[8] != ' ') return error.BadHandshake;
    status.* = std.fmt.parseInt(u16, status_line[9..12], 10) catch return error.BadHandshake;
    if (status.* != 101) return error.UpgradeRejected;

    var upgrade = false;
    var connection = false;
    var accepted = false;
    var protocol_seen = false;
    var protocol_ok = false;
    for (0..max_lines) |_| {
        const line = try takeLine(r, &line_buffer);
        if (line.len == 0) {
            if (!upgrade or !connection or !accepted) return error.BadHandshake;
            if (protocol_seen != (subprotocol.len != 0) or (protocol_seen and !protocol_ok))
                return error.BadSubprotocol;
            return;
        }
        const colon = std.mem.findScalar(u8, line, ':') orelse return error.BadHandshake;
        const name = line[0..colon];
        const value = std.mem.trim(u8, line[colon + 1 ..], " \t");
        if (std.ascii.eqlIgnoreCase(name, "upgrade")) {
            upgrade = std.ascii.eqlIgnoreCase(value, "websocket");
        } else if (std.ascii.eqlIgnoreCase(name, "connection")) {
            var tokens = std.mem.splitScalar(u8, value, ',');
            while (tokens.next()) |token| {
                if (std.ascii.eqlIgnoreCase(std.mem.trim(u8, token, " \t"), "upgrade")) connection = true;
            }
        } else if (std.ascii.eqlIgnoreCase(name, "sec-websocket-accept")) {
            if (!std.mem.eql(u8, value, accept)) return error.BadHandshake;
            accepted = true;
        } else if (std.ascii.eqlIgnoreCase(name, "sec-websocket-protocol")) {
            protocol_seen = true;
            protocol_ok = std.mem.eql(u8, value, subprotocol);
        } else if (std.ascii.eqlIgnoreCase(name, "sec-websocket-extensions")) {
            // None was offered, so the server may not select one.
            return error.BadHandshake;
        }
    }
    return error.BadHandshake;
}

/// Longest accepted line of the upgrade response.
const max_line_len = 8192;

/// One CRLF-terminated line without its terminator, copied into `buffer`.
/// Bytes are consumed one at a time, so nothing stays peeked in `r` while it reads
/// more (the TLS reader needs its buffer space for whole records).
fn takeLine(r: *std.Io.Reader, buffer: *[max_line_len]u8) UpgradeError![]const u8 {
    var len: usize = 0;
    while (true) {
        const byte = r.takeByte() catch |err| switch (err) {
            error.ReadFailed => |e| return e,
            error.EndOfStream => return error.ConnectionClosed,
        };
        if (byte == '\n') break;
        if (len == buffer.len) return error.BadHandshake;
        buffer[len] = byte;
        len += 1;
    }
    return std.mem.trimEnd(u8, buffer[0..len], "\r");
}

// Pure helpers, unit-tested without a network:

/// Append one client frame (always masked with `mask`) to `out`.
pub fn encodeFrame(
    out: *std.ArrayList(u8),
    gpa: std.mem.Allocator,
    fin: bool,
    opcode: u4,
    mask: [4]u8,
    payload: []const u8,
) !void {
    var header: [14]u8 = undefined;
    header[0] = (@as(u8, @intFromBool(fin)) << 7) | opcode;
    var n: usize = 2;
    if (payload.len < 126) {
        header[1] = 0x80 | @as(u8, @intCast(payload.len));
    } else if (payload.len <= std.math.maxInt(u16)) {
        header[1] = 0x80 | 126;
        std.mem.writeInt(u16, header[2..4], @intCast(payload.len), .big);
        n = 4;
    } else {
        header[1] = 0x80 | 127;
        std.mem.writeInt(u64, header[2..10], payload.len, .big);
        n = 10;
    }
    header[n..][0..4].* = mask;
    n += 4;

    try out.ensureUnusedCapacity(gpa, n + payload.len);
    out.appendSliceAssumeCapacity(header[0..n]);
    const masked = out.addManyAsSliceAssumeCapacity(payload.len);
    for (masked, payload, 0..) |*dst, src, i| dst.* = src ^ mask[i & 3];
}

pub const FrameHeader = struct { fin: bool, opcode: u4, masked: bool, mask: [4]u8, len: u64, header_len: usize };

/// Header length implied by the second header byte.
fn headerLen(second: u8) usize {
    const extended: usize = switch (second & 0x7F) {
        126 => 2,
        127 => 8,
        else => 0,
    };
    return 2 + extended + @as(usize, if (second & 0x80 != 0) 4 else 0);
}

/// Decode a frame header from the start of `bytes`. `mask` is zero when unmasked.
pub fn decodeHeader(bytes: []const u8) ?FrameHeader { // null = need more bytes
    if (bytes.len < 2) return null;
    const header_len = headerLen(bytes[1]);
    if (bytes.len < header_len) return null;
    const masked = bytes[1] & 0x80 != 0;
    const len: u64 = switch (bytes[1] & 0x7F) {
        126 => std.mem.readInt(u16, bytes[2..4], .big),
        127 => std.mem.readInt(u64, bytes[2..10], .big),
        else => |short| short,
    };
    return .{
        .fin = bytes[0] & 0x80 != 0,
        .opcode = @truncate(bytes[0]),
        .masked = masked,
        .mask = if (masked) bytes[header_len - 4 ..][0..4].* else @splat(0),
        .len = len,
        .header_len = header_len,
    };
}

/// Sec-WebSocket-Accept for a Sec-WebSocket-Key.
pub fn acceptKey(key_b64: []const u8, out: *[28]u8) void {
    const Sha1 = std.crypto.hash.Sha1;
    var hasher: Sha1 = .init(.{});
    hasher.update(key_b64);
    hasher.update("258EAFA5-E914-47DA-95CA-C5AB0DC85B11");
    var digest: [Sha1.digest_length]u8 = undefined;
    hasher.final(&digest);
    _ = std.base64.standard.Encoder.encode(out, &digest);
}
