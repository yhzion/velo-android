//! Connection to a server locator (`Endpoint.zig`) on `std.Io.net`: TLS over TCP for
//! `https` (`TlsClient.zig`, std's client with stricter certificate checks), plain TCP
//! for `http`, a Unix socket for a path, and for `ssh` the socket the app's SSH pool
//! hands over (a stream to the server's Unix socket on the remote host).
//!
//! One `Conn` may be read by one thread and written by another: the read and write
//! halves keep separate state. Writers must be serialized by the caller.
//! (Exception inherited from std: a TLS 1.3 KeyUpdate that requests a reply rekeys
//! the write half from the reading thread.)
const std = @import("std");
const TlsClient = @import("TlsClient.zig");
const Bundle = std.crypto.Certificate.Bundle;
const endpoint_mod = @import("Endpoint.zig");
const Endpoint = endpoint_mod.Endpoint;

/// How a `Conn` reaches its server.
pub const Net = struct {
    /// Directories of CA certificates; empty → system rescan (host builds). Read for
    /// `https` only. Missing directories and unparsable certificate files are skipped.
    ca_dirs: []const []const u8 = &.{},
    /// Asked first for every TCP connection (`https`, `http`); the system network when
    /// absent or when it declines. Never asked for a Unix socket or `ssh`.
    dialer: ?Dialer = null,
    /// Opens every `ssh` connection; without it they fail with error.SshUnavailable.
    ssh: ?SshDialer = null,
};

/// Another network to open TCP connections on (the app's tailnet node).
pub const Dialer = struct {
    ctx: ?*anyopaque = null,
    /// A connected stream socket to `host`:`port`, owned by the caller from then on;
    /// null to use the system network instead.
    dial: *const fn (ctx: ?*anyopaque, host: []const u8, port: u16) anyerror!?std.posix.fd_t,
};

/// The app's SSH pool (`sys/ssh.zig`), which logs in, finds the Rex server and carries
/// the stream.
pub const SshDialer = struct {
    ctx: ?*anyopaque = null,
    /// A connected stream socket to the Rex server of the `ssh` locator (trimmed), owned
    /// by the caller from then on. On failure the pool may explain it in `problem`.
    dial: *const fn (ctx: ?*anyopaque, locator: []const u8, problem: *SshProblem) anyerror!std.posix.fd_t,
};

/// Why the SSH pool could not open a stream, in its words: a code, the desktop's
/// sentence, and a one-line command that fixes it ("" when none). Empty `code`: no
/// explanation. Fixed storage, so a dialer can fill it without an allocator.
pub const SshProblem = struct {
    buffer: [capacity]u8 = undefined,
    code_len: usize = 0,
    sentence_len: usize = 0,
    fix_len: usize = 0,

    /// Bytes for code, sentence and fix together.
    pub const capacity = 4096;

    pub fn code(self: *const SshProblem) []const u8 {
        return self.buffer[0..self.code_len];
    }

    pub fn sentence(self: *const SshProblem) []const u8 {
        return self.buffer[self.code_len..][0..self.sentence_len];
    }

    pub fn fix(self: *const SshProblem) []const u8 {
        return self.buffer[self.code_len + self.sentence_len ..][0..self.fix_len];
    }

    /// Keep copies. A fix that does not fit is left out whole (a cut command would do
    /// something else); a sentence that does not fit is cut at a character boundary.
    pub fn set(self: *SshProblem, code_text: []const u8, sentence_text: []const u8, fix_text: []const u8) void {
        const code_part = code_text[0..@min(code_text.len, 64)];
        const room = capacity - code_part.len;
        const fix_part = if (sentence_text.len + fix_text.len <= room) fix_text else "";
        var sentence_part = sentence_text[0..@min(sentence_text.len, room - fix_part.len)];
        if (sentence_part.len < sentence_text.len) {
            var end = sentence_part.len;
            while (end > 0 and (sentence_text[end] & 0xC0) == 0x80) end -= 1;
            sentence_part = sentence_part[0..end];
        }
        @memcpy(self.buffer[0..code_part.len], code_part);
        @memcpy(self.buffer[code_part.len..][0..sentence_part.len], sentence_part);
        @memcpy(self.buffer[code_part.len + sentence_part.len ..][0..fix_part.len], fix_part);
        self.code_len = code_part.len;
        self.sentence_len = sentence_part.len;
        self.fix_len = fix_part.len;
    }

    /// From the pool's error JSON, `{"code","sentence","fix"}`. error.BadProblem (and
    /// an empty problem) when it is not that JSON or names no code.
    pub fn setJson(self: *SshProblem, gpa: std.mem.Allocator, text: []const u8) !void {
        self.* = .{};
        const Fields = struct { code: []const u8 = "", sentence: []const u8 = "", fix: []const u8 = "" };
        const parsed = std.json.parseFromSlice(Fields, gpa, text, .{ .ignore_unknown_fields = true }) catch |err| switch (err) {
            error.OutOfMemory => |e| return e,
            else => return error.BadProblem,
        };
        defer parsed.deinit();
        if (parsed.value.code.len == 0) return error.BadProblem;
        self.set(parsed.value.code, parsed.value.sentence, parsed.value.fix);
    }
};

/// The note of an `ssh` locator in a build without the SSH pool (host builds).
pub const ssh_unavailable = "SSH needs the app's native library.";

pub const Conn = struct {
    gpa: std.mem.Allocator,
    io: std.Io,
    stream: std.Io.net.Stream,
    stream_reader: std.Io.net.Stream.Reader,
    stream_writer: std.Io.net.Stream.Writer,
    /// null for `http` and Unix sockets: callers use the stream itself.
    tls_client: ?TlsClient,
    /// Backing memory of the socket buffers, then (TLS only) the TLS buffers.
    buffers: []u8,
    shut: std.atomic.Value(bool),

    const buffer_len = TlsClient.min_buffer_len;
    /// Unconsumed bytes a caller may keep buffered in `reader()` (peeked, not yet
    /// tossed) while asking for more. The TLS layer decrypts a whole record (up to
    /// `buffer_len` bytes) behind them and asserts that it fits. Without TLS they sit
    /// in the socket read buffer.
    pub const max_peek = 16 * 1024;
    const read_buffer_len = buffer_len + max_peek;
    comptime {
        std.debug.assert(buffer_len >= max_peek);
    }

    /// Connect to `locator` over `net`: TLS for `https`, as is for `http`, a Unix socket
    /// path and `ssh`. Fails with error.BadLocator when it does not parse, and with
    /// error.SshUnavailable for `ssh` when `net` has no SSH dialer.
    pub fn open(gpa: std.mem.Allocator, io: std.Io, locator: []const u8, net: Net) !*Conn {
        return openExplained(gpa, io, locator, net, null);
    }

    /// `open` that also says why an `ssh` connection failed, when the SSH pool (or the
    /// lack of one) explains it: `problem` gets a code then, and keeps none otherwise.
    pub fn openExplained(gpa: std.mem.Allocator, io: std.Io, locator: []const u8, net: Net, problem: ?*SshProblem) !*Conn {
        if (problem) |p| p.* = .{};
        const endpoint = endpoint_mod.parse(locator) catch return error.BadLocator;
        const secure = switch (endpoint.kind) {
            .https => true,
            .http, .unix, .ssh => false,
        };
        const now = std.Io.Clock.real.now(io);

        // The bundle is only consulted during the handshake.
        var bundle: Bundle = .empty;
        defer bundle.deinit(gpa);
        if (secure) {
            if (net.ca_dirs.len == 0) {
                try bundle.rescan(gpa, io, now);
            } else {
                for (net.ca_dirs) |dir| try addCaDir(&bundle, gpa, io, now, dir);
            }
            if (bundle.map.count() == 0) return error.NoCaCertificates;
        }
        var bundle_lock: std.Io.RwLock = .init;

        const self = try gpa.create(Conn);
        errdefer gpa.destroy(self);
        const buffers = try gpa.alloc(u8, if (secure) 3 * buffer_len + read_buffer_len else 2 * buffer_len);
        errdefer gpa.free(buffers);

        const stream = if (endpoint.kind == .ssh)
            try connectSsh(locator, net.ssh, problem)
        else
            try connect(io, endpoint, net.dialer);
        errdefer stream.close(io);

        self.* = .{
            .gpa = gpa,
            .io = io,
            .stream = stream,
            .stream_reader = stream.reader(io, buffers[0..buffer_len]),
            .stream_writer = stream.writer(io, buffers[buffer_len..][0..buffer_len]),
            .tls_client = null,
            .buffers = buffers,
            .shut = .init(false),
        };
        if (!secure) return self;

        var entropy: [TlsClient.Options.entropy_len]u8 = undefined;
        io.random(&entropy);
        self.tls_client = TlsClient.init(&self.stream_reader.interface, &self.stream_writer.interface, .{
            .host = .{ .explicit = endpoint.host },
            .ca = .{ .bundle = .{ .gpa = gpa, .io = io, .lock = &bundle_lock, .bundle = &bundle } },
            .write_buffer = buffers[2 * buffer_len ..][0..buffer_len],
            .read_buffer = buffers[3 * buffer_len ..],
            .entropy = &entropy,
            .realtime_now = now,
            // The layer above (WebSocket) frames its own messages and close, so a
            // TCP end-of-stream is reported as end-of-stream, not as a TLS error.
            .allow_truncation_attacks = true,
        }) catch |err| switch (err) {
            error.WriteFailed => return self.stream_writer.err orelse err,
            error.ReadFailed => return self.stream_reader.err orelse err,
            else => |e| return e,
        };
        return self;
    }

    /// Plaintext stream from the server (decrypted when TLS).
    pub fn reader(self: *Conn) *std.Io.Reader {
        if (self.tls_client) |*client| return &client.reader;
        return &self.stream_reader.interface;
    }

    /// Plaintext stream to the server. Nothing is sent before `flush`.
    pub fn writer(self: *Conn) *std.Io.Writer {
        if (self.tls_client) |*client| return &client.writer;
        return &self.stream_writer.interface;
    }

    /// Flush the TLS writer, if any, and the socket writer.
    pub fn flush(self: *Conn) !void {
        if (self.tls_client) |*client| client.writer.flush() catch return self.writeError();
        self.stream_writer.interface.flush() catch return self.writeError();
    }

    /// The cause of an `error.ReadFailed` from `reader()`.
    pub fn readError(self: *Conn) anyerror {
        if (self.tls_client) |*client| if (client.read_err) |err| return err;
        return self.stream_reader.err orelse error.ReadFailed;
    }

    /// The cause of an `error.WriteFailed` from `writer()`.
    pub fn writeError(self: *Conn) anyerror {
        return self.stream_writer.err orelse error.WriteFailed;
    }

    /// Idempotent; safe to call from another thread to unblock a blocked read
    /// (shuts the socket down, then the owner thread calls deinit).
    pub fn shutdown(self: *Conn) void {
        if (self.shut.swap(true, .acq_rel)) return;
        self.stream.shutdown(self.io, .both) catch {};
    }

    pub fn deinit(self: *Conn) void {
        const gpa = self.gpa;
        self.stream.close(self.io);
        gpa.free(self.buffers);
        gpa.destroy(self);
    }
};

/// Connect to the Unix socket at the endpoint's path, or TCP-connect to its host and
/// port: through `dialer` when it takes the connection, else on the system network.
fn connect(io: std.Io, endpoint: Endpoint, dialer: ?Dialer) !std.Io.net.Stream {
    if (endpoint.kind == .unix) {
        const address = try std.Io.net.UnixAddress.init(endpoint.path);
        return address.connect(io);
    }
    if (dialer) |d| {
        if (try d.dial(d.ctx, endpoint.host, endpoint.port)) |fd| {
            // A socket the dialer bridges to its own network (libtailscale: one end of a
            // socketpair); the TCP options below do not apply to it.
            return .{ .socket = .{ .handle = fd, .address = .{ .ip4 = .unspecified(endpoint.port) } } };
        }
    }
    const stream = try connectTcp(io, endpoint.host, endpoint.port);
    configureSocket(stream.socket.handle);
    return stream;
}

/// The stream the SSH pool opens for `locator`.
fn connectSsh(locator: []const u8, ssh: ?SshDialer, problem: ?*SshProblem) !std.Io.net.Stream {
    var scratch: SshProblem = .{};
    const explained = problem orelse &scratch;
    const d = ssh orelse {
        explained.set("ssh_unavailable", ssh_unavailable, "");
        return error.SshUnavailable;
    };
    const fd = try d.dial(d.ctx, std.mem.trim(u8, locator, " \t\r\n"), explained);
    // A problem explains a failure only.
    explained.* = .{};
    // One end of a socketpair the pool bridges to an SSH channel; the TCP options of
    // `connect` do not apply to it.
    return .{ .socket = .{ .handle = fd, .address = .{ .ip4 = .unspecified(0) } } };
}

/// TCP-connect to `host`:`port`, trying each resolved address in order. Names are
/// resolved by libc: std's own resolver reads /etc/resolv.conf, which Android does
/// not have (netd resolves names there, and only bionic talks to it).
fn connectTcp(io: std.Io, host: []const u8, port: u16) !std.Io.net.Stream {
    var name: [std.Io.net.HostName.max_len + 1]u8 = undefined;
    if (host.len == 0 or host.len >= name.len) return error.UnknownHostName;
    @memcpy(name[0..host.len], host);
    name[host.len] = 0;

    const hints: std.c.addrinfo = .{
        .flags = .{},
        .family = std.posix.AF.UNSPEC,
        .socktype = std.posix.SOCK.STREAM,
        .protocol = 0,
        .addrlen = 0,
        .canonname = null,
        .addr = null,
        .next = null,
    };
    var list: ?*std.c.addrinfo = null;
    const rc = std.c.getaddrinfo(@ptrCast(&name), null, &hints, &list);
    if (@intFromEnum(rc) != 0) return switch (rc) {
        .AGAIN => error.TemporaryNameServerFailure,
        .NONAME, .NODATA => error.UnknownHostName,
        .MEMORY => error.OutOfMemory,
        else => error.NameServerFailure,
    };
    defer if (list) |first| std.c.freeaddrinfo(first);

    var last: anyerror = error.UnknownHostName;
    var next = list;
    while (next) |info| : (next = info.next) {
        const sockaddr = info.addr orelse continue;
        var address = std.Io.Threaded.addressFromPosix(@ptrCast(@alignCast(sockaddr)));
        address.setPort(port);
        return address.connect(io, .{ .mode = .stream }) catch |err| {
            last = err;
            continue;
        };
    }
    return last;
}

/// Add every certificate file of `path` (PEM; Android stores one per file, followed
/// by a text dump). Per file, so one unreadable or malformed file does not drop the
/// rest of the store.
fn addCaDir(bundle: *Bundle, gpa: std.mem.Allocator, io: std.Io, now: std.Io.Timestamp, path: []const u8) !void {
    if (!std.Io.Dir.path.isAbsolute(path)) return;
    var dir = std.Io.Dir.openDirAbsolute(io, path, .{ .iterate = true }) catch return;
    defer dir.close(io);
    var it = dir.iterate();
    while (it.next(io) catch null) |entry| {
        switch (entry.kind) {
            .file, .sym_link => {},
            else => continue,
        }
        bundle.addCertsFromFilePath(gpa, io, now, dir, entry.name) catch |err| switch (err) {
            error.OutOfMemory => |e| return e,
            else => {},
        };
    }
}

/// Best effort: a socket without these options still works.
fn configureSocket(fd: std.posix.socket_t) void {
    const posix = std.posix;
    const options = [_]struct { level: i32, name: u32, value: c_int }{
        // Keystrokes are small writes; do not let Nagle hold them back.
        .{ .level = posix.IPPROTO.TCP, .name = posix.TCP.NODELAY, .value = 1 },
        // A phone changes networks without the peer noticing. Probe an idle
        // connection after 30 s, every 10 s, and fail a blocked read after 3 misses.
        .{ .level = posix.SOL.SOCKET, .name = posix.SO.KEEPALIVE, .value = 1 },
        .{ .level = posix.IPPROTO.TCP, .name = posix.TCP.KEEPIDLE, .value = 30 },
        .{ .level = posix.IPPROTO.TCP, .name = posix.TCP.KEEPINTVL, .value = 10 },
        .{ .level = posix.IPPROTO.TCP, .name = posix.TCP.KEEPCNT, .value = 3 },
    };
    for (options) |option| {
        posix.setsockopt(fd, option.level, option.name, std.mem.asBytes(&option.value)) catch {};
    }
}
