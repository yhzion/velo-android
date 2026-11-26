//! The app's own Tailscale node: one per process, shared by every core. It is
//! libtailscale (tsnet as a C library, native/tailscale/), loaded with dlopen on the
//! first start so a process that never runs the node never loads the Go runtime.
//!
//! While the node is Running, every `Conn` of every core dials through it (`dialer`);
//! otherwise connections use the system network. Its log goes to logcat.
//!
//! Lifecycle: `start` and `stop` are serialized and synchronous. `start` returns once
//! tsnet has started (`tailscale_start`; login and Running come later, see `statusJson`),
//! and `stop` returns once the node is closed, so two nodes never share the state
//! directory. There is no `tailscale_up` thread: libtailscale's close of a node whose up
//! has not returned drops the handle without closing the node.
const std = @import("std");
const jni = @import("jni.zig");
const Dialer = @import("../net/Conn.zig").Dialer;
const endpoint = @import("../net/Endpoint.zig");

const Sd = c_int;

/// How long a Running status counts as evidence for dialing through the node.
const running_fresh_ms = 5_000;
/// Bound of one dial through the node.
const dial_timeout_ms = 10_000;

/// libtailscale's C API (tailscale.h) plus native/tailscale/android.go.
const Api = struct {
    new: *const fn () callconv(.c) Sd,
    set_dir: *const fn (Sd, [*:0]const u8) callconv(.c) c_int,
    set_hostname: *const fn (Sd, [*:0]const u8) callconv(.c) c_int,
    set_logfd: *const fn (Sd, c_int) callconv(.c) c_int,
    start: *const fn (Sd) callconv(.c) c_int,
    close: *const fn (Sd) callconv(.c) c_int,
    status_json: *const fn (Sd, *?[*:0]u8) callconv(.c) c_int,
    errmsg: *const fn (Sd, [*]u8, usize) callconv(.c) c_int,
    // android.go
    setenv: *const fn ([*:0]const u8, [*:0]const u8) callconv(.c) c_int,
    dial_timeout: *const fn (Sd, [*:0]const u8, c_int, *c_int) callconv(.c) c_int,
    logout: *const fn (Sd) callconv(.c) c_int,
    https_get: *const fn (Sd, [*:0]const u8, c_int, *c_int, *?[*:0]u8) callconv(.c) c_int,

    fn load() !Api {
        const lib = std.c.dlopen("libtailscale.so", .{ .NOW = true }) orelse {
            jni.log(.err, "tailnet: dlopen libtailscale.so: {s}", .{std.mem.span(std.c.dlerror() orelse "?")});
            return error.TailscaleLibraryMissing;
        };
        var out: Api = undefined;
        inline for (comptime std.meta.fieldNames(Api)) |name| {
            const symbol = std.c.dlsym(lib, "tailscale_" ++ name) orelse return error.TailscaleLibraryMissing;
            @field(out, name) = @ptrCast(@alignCast(symbol));
        }
        return out;
    }

    /// The node's last error, NUL-terminated in `buf`.
    fn lastError(self: Api, sd: Sd, buf: []u8) []const u8 {
        if (self.errmsg(sd, buf.ptr, buf.len) != 0) return "unknown error";
        return std.mem.sliceTo(buf, 0);
    }
};

/// Serializes start and stop (held across the libtailscale calls).
var life_lock: std.c.pthread_mutex_t = .{};
/// Guards the variables below (held only briefly).
var lock: std.c.pthread_mutex_t = .{};
var api: ?Api = null;
/// The started node; null while stopped.
var node: ?Sd = null;
/// Monotonic ms of the last status that showed Running; null when the last did not.
var running_at: ?i64 = null;
/// Write end of the pipe whose reader copies the node's log to logcat.
var log_fd: c_int = -1;

/// Start the node with its state in `dir` (created if missing) under `hostname`.
/// Returns once tsnet has started; it reaches the tailnet in the background (login
/// may be needed). error.AlreadyStarted when a node runs.
pub fn start(dir: [:0]const u8, hostname: [:0]const u8) !void {
    _ = std.c.pthread_mutex_lock(&life_lock);
    defer _ = std.c.pthread_mutex_unlock(&life_lock);
    if (current() != null) return error.AlreadyStarted;
    if (api == null) api = try Api.load();
    const a = api.?;
    if (log_fd < 0) log_fd = startLogPipe();

    // The parent (the app's files directory) exists; an existing node directory is fine.
    _ = std.c.mkdir(dir, 0o700);
    // tailscaled's own log state (sockstat log) would otherwise look for a system or cache
    // directory, find none in an app sandbox and panic; and no logs go to Tailscale.
    // No network map from the disk: with one the node says Running within milliseconds of
    // its start, before it has spoken to the tailnet, with every peer missing or stale. A
    // dial then finds no peer for the name and goes out on the system network (measured on
    // a test device: the connection arrived from the VPN's node, not this one), and the first
    // sweep sees no server. Without it Running means the map came from the control plane.
    if (a.setenv("TS_LOGS_DIR", dir) != 0 or a.setenv("TS_NO_LOGS_NO_SUPPORT", "true") != 0 or
        a.setenv("TS_USE_CACHED_NETMAP", "false") != 0)
        return error.TailscaleConfig;
    const sd = a.new();
    var buf: [256]u8 = undefined;
    if (a.set_dir(sd, dir) != 0 or a.set_hostname(sd, hostname) != 0) {
        jni.log(.err, "tailnet: configure: {s}", .{a.lastError(sd, &buf)});
        _ = a.close(sd);
        return error.TailscaleConfig;
    }
    if (log_fd >= 0) {
        // libtailscale wraps the descriptor it is given in a Go file of the node's own.
        const fd = std.c.dup(log_fd);
        if (fd >= 0) _ = a.set_logfd(sd, fd);
    }
    if (a.start(sd) != 0) {
        // A failed tsnet start has closed what it opened; the handle is dropped.
        jni.log(.err, "tailnet: start: {s}", .{a.lastError(sd, &buf)});
        _ = a.close(sd);
        return error.TailscaleStart;
    }
    _ = std.c.pthread_mutex_lock(&lock);
    node = sd;
    running_at = null;
    _ = std.c.pthread_mutex_unlock(&lock);
}

/// Close the node and wait until it is closed. Connections it carries end; new ones use
/// the system network. The login stays in the node's directory (see `logout`).
pub fn stop() void {
    _ = std.c.pthread_mutex_lock(&life_lock);
    defer _ = std.c.pthread_mutex_unlock(&life_lock);
    _ = std.c.pthread_mutex_lock(&lock);
    const sd = node;
    node = null;
    running_at = null;
    _ = std.c.pthread_mutex_unlock(&lock);
    if (sd) |s| _ = api.?.close(s);
}

/// Log the node out of the tailnet; it then needs a new login. Error message (static or
/// in `buf`) on failure.
pub fn logout(buf: []u8) ?[]const u8 {
    _ = std.c.pthread_mutex_lock(&life_lock);
    defer _ = std.c.pthread_mutex_unlock(&life_lock);
    const sd = current() orelse return "the node is not running";
    if (api.?.logout(sd) != 0) return api.?.lastError(sd, buf);
    _ = std.c.pthread_mutex_lock(&lock);
    running_at = null;
    _ = std.c.pthread_mutex_unlock(&lock);
    return null;
}

/// The node's status for the app as JSON: `{}` when stopped, else
/// `{"statusError":"…" (only when unreadable), "status": <ipnstate.Status>}`. Caller frees.
pub fn statusJson(gpa: std.mem.Allocator) ![]u8 {
    const sd = current() orelse return gpa.dupe(u8, "{}");
    const a = api.?;
    var raw: ?[*:0]u8 = null;
    var buf: [256]u8 = undefined;
    const status: []const u8 = if (a.status_json(sd, &raw) == 0 and raw != null) std.mem.span(raw.?) else "null";
    defer if (raw) |r| std.c.free(r);
    const status_error: []const u8 = if (raw == null) a.lastError(sd, &buf) else "";
    noteStatus(sd, status);

    var out: std.Io.Writer.Allocating = .init(gpa);
    errdefer out.deinit();
    const w = &out.writer;
    try w.writeAll("{");
    if (status_error.len > 0) try w.print("\"statusError\":{f},", .{std.json.fmt(status_error, .{})});
    try w.print("\"status\":{s}}}", .{status});
    return out.toOwnedSlice();
}

/// GET `url` (https://… on the tailnet) through the node. The body when the answer is
/// 200, else null. Caller frees.
pub fn get200(gpa: std.mem.Allocator, url: [:0]const u8, timeout_ms: u31) ?[]u8 {
    const sd = current() orelse return null;
    var status: c_int = 0;
    var raw: ?[*:0]u8 = null;
    const rc = api.?.https_get(sd, url, timeout_ms, &status, &raw);
    defer if (raw) |r| std.c.free(r);
    if (rc != 0 or status != 200 or raw == null) return null;
    return gpa.dupe(u8, std.mem.span(raw.?)) catch null;
}

/// The dialer every core uses.
pub fn dialer() Dialer {
    return .{ .dial = dial };
}

/// The node's handle while it is Running, by a status at most `running_fresh_ms` old
/// (read again here when older); null otherwise. A node that left Running (logged out,
/// key expired, no network) would hold a dial until its timeout instead of letting it
/// use the system network.
pub fn runningNode() ?Sd {
    const sd = current() orelse return null;
    if (!runningFresh()) {
        refreshStatus(sd);
        if (!runningFresh()) return null;
    }
    return sd;
}

fn dial(_: ?*anyopaque, host: []const u8, port: u16) anyerror!?std.posix.fd_t {
    // Through the node only while it is Running. The dial is bounded by `dial_timeout_ms`.
    const sd = runningNode() orelse return null;

    var addr_buf: [std.Io.net.HostName.max_len + 10]u8 = undefined;
    const addr = endpoint.joinHostPort(&addr_buf, host, port) catch return error.UnknownHostName;
    var fd: c_int = -1;
    const a = api.?;
    if (a.dial_timeout(sd, addr, dial_timeout_ms, &fd) != 0) {
        var buf: [256]u8 = undefined;
        jni.log(.warn, "tailnet: dial {s}: {s}", .{ addr, a.lastError(sd, &buf) });
        return error.TailnetDialFailed;
    }
    return fd;
}

fn current() ?Sd {
    _ = std.c.pthread_mutex_lock(&lock);
    defer _ = std.c.pthread_mutex_unlock(&lock);
    return node;
}

/// Read the node's status only to update `running_at`.
fn refreshStatus(sd: Sd) void {
    var raw: ?[*:0]u8 = null;
    if (api.?.status_json(sd, &raw) != 0 or raw == null) return noteStatus(sd, "");
    defer std.c.free(raw.?);
    noteStatus(sd, std.mem.span(raw.?));
}

fn noteStatus(sd: Sd, status: []const u8) void {
    const running = std.mem.find(u8, status, "\"BackendState\":\"Running\"") != null;
    _ = std.c.pthread_mutex_lock(&lock);
    defer _ = std.c.pthread_mutex_unlock(&lock);
    if (node != sd) return;
    running_at = if (running) nowMs() else null;
}

fn runningFresh() bool {
    _ = std.c.pthread_mutex_lock(&lock);
    defer _ = std.c.pthread_mutex_unlock(&lock);
    const at = running_at orelse return false;
    return nowMs() - at <= running_fresh_ms;
}

fn nowMs() i64 {
    var ts: std.c.timespec = undefined;
    if (std.c.clock_gettime(.MONOTONIC, &ts) != 0) return 0;
    return @as(i64, ts.sec) * 1000 + @divTrunc(@as(i64, ts.nsec), 1_000_000);
}

/// A pipe whose reader thread copies each line to logcat. Returns the write end, or -1.
/// The process's stderr goes there too: Android discards it, and it is where the Go
/// runtime writes a panic before it aborts.
fn startLogPipe() c_int {
    var fds: [2]c_int = undefined;
    if (std.c.pipe(&fds) != 0) return -1;
    const thread = std.Thread.spawn(.{}, logMain, .{fds[0]}) catch {
        _ = std.c.close(fds[0]);
        _ = std.c.close(fds[1]);
        return -1;
    };
    thread.detach();
    _ = std.c.dup2(fds[1], 2);
    return fds[1];
}

/// `line` with each URL (`http://` or `https://` up to the next blank) replaced by `<url>`,
/// written to `out`, which must be as long as `line` (the result never is longer). The node
/// writes the login URL to its log in three places (tsnet's login loop, which goes to stderr,
/// and two lines of the control client), and whoever opens it first can log the node in to
/// their own tailnet, while logcat is readable over adb and lands in shared bug reports. The
/// app's debug builds show the URL themselves, from `velo.tailnet`, so no build needs it here.
fn redactUrls(out: []u8, line: []const u8) []const u8 {
    const marker = "<url>";
    var n: usize = 0;
    var i: usize = 0;
    while (i < line.len) {
        const rest = line[i..];
        if (std.mem.startsWith(u8, rest, "http://") or std.mem.startsWith(u8, rest, "https://")) {
            @memcpy(out[n..][0..marker.len], marker);
            n += marker.len;
            i += std.mem.findAny(u8, rest, " \t\r") orelse rest.len;
        } else {
            out[n] = line[i];
            n += 1;
            i += 1;
        }
    }
    return out[0..n];
}

fn logMain(fd: c_int) void {
    @import("pane").Trace.nameThread("velo-tslog", "");
    var buf: [4096]u8 = undefined;
    var shown: [4096]u8 = undefined;
    var len: usize = 0;
    while (true) {
        if (len == buf.len) {
            jni.log(.debug, "tailscale: {s}", .{redactUrls(&shown, buf[0..len])});
            len = 0;
        }
        const n = std.c.read(fd, buf[len..].ptr, buf.len - len);
        if (n <= 0) return;
        len += @intCast(n);
        var start_at: usize = 0;
        while (std.mem.findScalarPos(u8, buf[0..len], start_at, '\n')) |nl| {
            jni.log(.debug, "tailscale: {s}", .{redactUrls(&shown, buf[start_at..nl])});
            start_at = nl + 1;
        }
        std.mem.copyForwards(u8, &buf, buf[start_at..len]);
        len -= start_at;
    }
}

test "redactUrls: the login URL of the node's own log lines does not reach logcat" {
    var buf: [256]u8 = undefined;
    // The lines tailscale.com v1.104.1 writes with the URL: tsnet's login loop (to stderr, so
    // with a date), and control's "AuthURL is" and "control says to open URL".
    try std.testing.expectEqualStrings(
        "2026/11/26 10:00:00 To start this tsnet server, restart with TS_AUTHKEY set, or go to: <url>",
        redactUrls(&buf, "2026/11/26 10:00:00 To start this tsnet server, restart with TS_AUTHKEY set, or go to: https://login.tailscale.com/a/1a2b3c4d5e6f"),
    );
    try std.testing.expectEqualStrings("control: AuthURL is <url>", redactUrls(&buf, "control: AuthURL is https://login.tailscale.com/a/1a2b3c4d5e6f"));
    try std.testing.expectEqualStrings(
        // A URL ends at a blank, so the ";" after it goes too: better that than a URL cut short.
        "netmap: control says to open URL <url> no popBrowser func",
        redactUrls(&buf, "netmap: control says to open URL https://login.tailscale.com/a/1a2b3c4d5e6f; no popBrowser func"),
    );
}

test "redactUrls: a plain http URL and every URL on a line go; a line without one stays" {
    var buf: [256]u8 = undefined;
    try std.testing.expectEqualStrings("a <url> b <url>", redactUrls(&buf, "a http://login.example/x b https://login.example/y"));
    try std.testing.expectEqualStrings("<url>", redactUrls(&buf, "https://x"));
    try std.testing.expectEqualStrings("magicsock: 3 peers, derp home is 1", redactUrls(&buf, "magicsock: 3 peers, derp home is 1"));
    try std.testing.expectEqualStrings("", redactUrls(&buf, ""));
    // The scheme word alone is not a URL.
    try std.testing.expectEqualStrings("https is a scheme", redactUrls(&buf, "https is a scheme"));
}

test "redactUrls: the result is never longer than the line, so a buffer of the line's size is enough" {
    var buf: [7]u8 = undefined;
    try std.testing.expectEqualStrings("<url>", redactUrls(&buf, "http://"));
}
