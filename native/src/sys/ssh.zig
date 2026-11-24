//! The app's SSH pool: package rexssh in libtailscale.so (native/tailscale/ssh.go), one
//! per process, loaded with dlopen on first use like `tailnet.zig` loads the same library.
//! The core opens `ssh` locators through `dialer`; jni.zig exposes the rest to Kotlin
//! (io.github.yhzion.velo.SshNative). Every C string the pool returns is malloc'd; callers free
//! it with `std.c.free` (after zeroing it when it holds a key).
const std = @import("std");
const jni = @import("jni.zig");
const conn = @import("../net/Conn.zig");
const tailnet = @import("tailnet.zig");

/// What the JNI calls return when the library or its rexssh symbols are missing.
pub const unavailable: c_int = -1;

/// The C exports of ssh.go, without their `rexssh_` prefix. Return values: 0 is success,
/// -1 a dial explained by its error JSON, 1000+N from `set_identities` (the key at place N
/// was left out, the others set), anything else an errno value (see ssh.go).
pub const Api = struct {
    init: *const fn (state_dir: [*:0]const u8) callconv(.c) c_int,
    dial: *const fn (sd: c_int, locator: [*:0]const u8, timeout_ms: c_int, conn_out: *c_int, error_json_out: ?*?[*:0]u8) callconv(.c) c_int,
    questions: *const fn (json_out: *?[*:0]u8) callconv(.c) c_int,
    answer: *const fn (id: c_longlong, answer: ?[*:0]const u8, ok: c_int) callconv(.c) c_int,
    set_identities: *const fn (json_array_of_pem: [*:0]const u8) callconv(.c) c_int,
    generate_key: *const fn (comment: ?[*:0]const u8, pem_out: *?[*:0]u8, public_out: *?[*:0]u8) callconv(.c) c_int,
    decrypt_key: *const fn (pem: [*:0]const u8, passphrase: ?[*:0]const u8, pem_out: *?[*:0]u8) callconv(.c) c_int,
    install_key: *const fn (locator: [*:0]const u8, public_line: [*:0]const u8, timeout_ms: c_int) callconv(.c) c_int,
    remembered: *const fn (locator: [*:0]const u8, json_out: *?[*:0]u8) callconv(.c) c_int,
    forget_socket: *const fn (locator: [*:0]const u8) callconv(.c) c_int,
    forget: *const fn (locator: [*:0]const u8) callconv(.c) c_int,
};

/// Guards `api`.
var lock: std.c.pthread_mutex_t = .{};
var api: ?Api = null;

/// The pool's functions; null when libtailscale.so or a symbol is missing (tried again
/// on the next call).
pub fn load() ?Api {
    _ = std.c.pthread_mutex_lock(&lock);
    defer _ = std.c.pthread_mutex_unlock(&lock);
    if (api) |a| return a;
    // The library `tailnet.zig` may have loaded already: dlopen returns the same one.
    const lib = std.c.dlopen("libtailscale.so", .{ .NOW = true }) orelse {
        jni.log(.err, "ssh: dlopen libtailscale.so: {s}", .{std.mem.span(std.c.dlerror() orelse "?")});
        return null;
    };
    var out: Api = undefined;
    inline for (comptime std.meta.fieldNames(Api)) |name| {
        const symbol = std.c.dlsym(lib, "rexssh_" ++ name) orelse {
            jni.log(.err, "ssh: libtailscale.so has no rexssh_" ++ name, .{});
            return null;
        };
        @field(out, name) = @ptrCast(@alignCast(symbol));
    }
    api = out;
    return out;
}

/// Whether an init that returned `rc` left the process without a pool: EEXIST is a pool
/// made already (with another directory), which dials all the same.
fn initFailed(rc: c_int) bool {
    return rc != 0 and rc != @backingInt(std.posix.E.EXIST);
}

/// The last `SshNative.init` failed (`initDone`): a dial the pool refuses for want of an
/// init will not start working by waiting, so it is ssh_unavailable, which stops the core.
var init_failed = std.atomic.Value(bool).init(false);

/// What `SshNative.init` returned (jni.zig); an init that works clears an earlier failure.
pub fn initDone(rc: c_int) void {
    init_failed.store(initFailed(rc), .release);
}

/// The SSH dialer every core uses.
pub fn dialer() conn.SshDialer {
    return .{ .dial = dial };
}

/// The note of a dial the pool refuses before its init: `SshNative.init` has not run yet.
const not_ready = "SSH is still starting on this phone. Try again in a moment.";
/// The note of a dial the pool refuses after its init failed.
const init_failed_note = "SSH couldn't start on this phone. Restart Velo to try again.";

fn dial(_: ?*anyopaque, locator: []const u8, problem: *conn.SshProblem) anyerror!std.posix.fd_t {
    const a = load() orelse {
        problem.set("ssh_unavailable", conn.ssh_unavailable, "");
        return error.SshUnavailable;
    };
    const gpa = std.heap.c_allocator;
    const locator_z = try gpa.dupeSentinel(u8, locator, 0);
    defer gpa.free(locator_z);
    // TCP goes through the tailnet node when it is Running and the host is one of its
    // peers (the pool decides), else on the system network.
    const sd: c_int = tailnet.runningNode() orelse -1;

    var fd: c_int = -1;
    var error_json: ?[*:0]u8 = null;
    // No time limit of the core's own: a host key or password question waits up to
    // 120 s for the user, and the pool bounds every other step itself. A caller limit
    // could report a connection failure while the user is still answering.
    const rc = a.dial(sd, locator_z, 0, &fd, &error_json);
    defer if (error_json) |text| std.c.free(text);
    if (rc == 0) return fd;
    const err = explain(gpa, rc, if (error_json) |text| std.mem.span(text) else null, init_failed.load(.acquire), problem);
    jni.log(.warn, "ssh: rexssh_dial: {d} {s}", .{ rc, problem.code() });
    return err;
}

/// What a failed `rexssh_dial` means for the core: `rc` is not 0, `error_json` is its
/// error JSON (with `rc` -1), `failed_init` whether the last init failed. Fills `problem`
/// and returns the error to fail with.
pub fn explain(gpa: std.mem.Allocator, rc: c_int, error_json: ?[]const u8, failed_init: bool, problem: *conn.SshProblem) error{ SshNotReady, SshDialFailed, SshUnavailable } {
    problem.* = .{};
    if (rc == -1) {
        // Unreadable JSON leaves the problem empty.
        if (error_json) |text| problem.setJson(gpa, text) catch {};
        if (problem.code().len == 0) problem.set("unreachable", "The SSH connection failed.", "");
        return error.SshDialFailed;
    }
    // EPERM: the pool has no init yet. Transient while the app makes it at its start; for
    // good once that failed.
    if (rc == @backingInt(std.posix.E.PERM)) {
        if (failed_init) {
            problem.set("ssh_unavailable", init_failed_note, "");
            return error.SshUnavailable;
        }
        problem.set("ssh_not_ready", not_ready, "");
        return error.SshNotReady;
    }
    // EINVAL, EMFILE and the like: no explanation from the pool.
    var buf: [96]u8 = undefined;
    const sentence = std.fmt.bufPrint(&buf, "The phone could not open the SSH connection (error {d}).", .{rc}) catch unreachable;
    problem.set("internal", sentence, "");
    return error.SshDialFailed;
}

/// The JSON array of PEM strings `rexssh_set_identities` takes, NUL-terminated. It is
/// written in place at its exact size, so no other copy of a key is left behind; the
/// caller zeroes and frees it.
pub fn identitiesJson(gpa: std.mem.Allocator, keys: []const []const u8) error{OutOfMemory}![:0]u8 {
    var counter: std.Io.Writer.Discarding = .init(&.{});
    writeIdentities(&counter.writer, keys) catch unreachable; // counting does not fail
    const out = try gpa.allocSentinel(u8, @intCast(counter.fullCount()), 0);
    var w: std.Io.Writer = .fixed(out);
    writeIdentities(&w, keys) catch unreachable; // exactly the size counted
    return out;
}

fn writeIdentities(w: *std.Io.Writer, keys: []const []const u8) std.Io.Writer.Error!void {
    try w.writeByte('[');
    for (keys, 0..) |key, i| {
        if (i > 0) try w.writeByte(',');
        try std.json.Stringify.encodeJsonString(key, .{}, w);
    }
    try w.writeByte(']');
}

test "explain maps rexssh_dial's failures to the core's problems" {
    const gpa = std.testing.allocator;
    const errno = struct {
        fn of(e: std.posix.E) c_int {
            return @backingInt(e);
        }
    };
    const pool_json =
        \\{"code":"key_refused","sentence":"h didn't accept this phone's key for me.","fix":"mkdir -p ~/.ssh"}
    ;
    const cases = [_]struct { rc: c_int, json: ?[]const u8, init_failed: bool = false, err: anyerror, code: []const u8, sentence: []const u8, fix: []const u8 }{
        .{ .rc = -1, .json = pool_json, .err = error.SshDialFailed, .code = "key_refused", .sentence = "h didn't accept this phone's key for me.", .fix = "mkdir -p ~/.ssh" },
        .{ .rc = -1, .json = null, .err = error.SshDialFailed, .code = "unreachable", .sentence = "The SSH connection failed.", .fix = "" },
        .{ .rc = -1, .json = "not json", .err = error.SshDialFailed, .code = "unreachable", .sentence = "The SSH connection failed.", .fix = "" },
        .{ .rc = -1, .json = "{\"sentence\":\"no code\"}", .err = error.SshDialFailed, .code = "unreachable", .sentence = "The SSH connection failed.", .fix = "" },
        .{ .rc = errno.of(.PERM), .json = null, .err = error.SshNotReady, .code = "ssh_not_ready", .sentence = "SSH is still starting on this phone. Try again in a moment.", .fix = "" },
        // The init failed: the pool will not start, so that is for good (ssh_unavailable stops the core).
        .{ .rc = errno.of(.PERM), .json = null, .init_failed = true, .err = error.SshUnavailable, .code = "ssh_unavailable", .sentence = "SSH couldn't start on this phone. Restart Velo to try again.", .fix = "" },
        // A pool that answers does not care how an earlier init went.
        .{ .rc = -1, .json = pool_json, .init_failed = true, .err = error.SshDialFailed, .code = "key_refused", .sentence = "h didn't accept this phone's key for me.", .fix = "mkdir -p ~/.ssh" },
        .{ .rc = errno.of(.MFILE), .json = null, .err = error.SshDialFailed, .code = "internal", .sentence = "The phone could not open the SSH connection (error 24).", .fix = "" },
        .{ .rc = errno.of(.INVAL), .json = null, .err = error.SshDialFailed, .code = "internal", .sentence = "The phone could not open the SSH connection (error 22).", .fix = "" },
    };
    for (cases) |case| {
        var problem: conn.SshProblem = .{};
        problem.set("stale", "an earlier problem", "stale fix");
        const err = explain(gpa, case.rc, case.json, case.init_failed, &problem);
        try std.testing.expectEqual(case.err, @as(anyerror, err));
        try std.testing.expectEqualStrings(case.code, problem.code());
        try std.testing.expectEqualStrings(case.sentence, problem.sentence());
        try std.testing.expectEqualStrings(case.fix, problem.fix());
    }
}

test "an init that fails is remembered; one that works, or finds the pool made, is not" {
    try std.testing.expect(!initFailed(0));
    try std.testing.expect(!initFailed(@backingInt(std.posix.E.EXIST)));
    try std.testing.expect(initFailed(@backingInt(std.posix.E.INVAL)));
    try std.testing.expect(initFailed(unavailable));
}

test "identitiesJson: the keys as a JSON array of strings, in order, at its exact size" {
    const gpa = std.testing.allocator;
    const keys = [_][]const u8{
        "-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaC1rZXktdjE=\n-----END OPENSSH PRIVATE KEY-----\n",
        "quote \" backslash \\ tab \t cr \r nul-free \x01 \x1f \u{D55C}\u{1F600}",
        "",
    };
    const js = try identitiesJson(gpa, &keys);
    defer gpa.free(js);
    try std.testing.expectEqualStrings(
        \\["-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaC1rZXktdjE=\n-----END OPENSSH PRIVATE KEY-----\n","quote \" backslash \\ tab \t cr \r nul-free \u0001 \u001f
    ++ " \u{D55C}\u{1F600}\",\"\"]", js);
    try std.testing.expectEqual(0, js.ptr[js.len]);

    // A JSON reader gets the keys back byte for byte.
    const parsed = try std.json.parseFromSlice([]const []const u8, gpa, js, .{});
    defer parsed.deinit();
    try std.testing.expectEqual(keys.len, parsed.value.len);
    for (keys, parsed.value) |want, got| try std.testing.expectEqualStrings(want, got);

    const none = try identitiesJson(gpa, &.{});
    defer gpa.free(none);
    try std.testing.expectEqualStrings("[]", none);

    var failing: std.testing.FailingAllocator = .init(gpa, .{ .fail_index = 0 });
    try std.testing.expectError(error.OutOfMemory, identitiesJson(failing.allocator(), &keys));
}
