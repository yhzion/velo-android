//! Root of libvelocore.so: the JNI functions of io.github.yhzion.velo.core.VeloCore
//! (app/src/main/java/io/github/yhzion/velo/core/VeloCore.kt) and the CoreListener callbacks,
//! and those of io.github.yhzion.velo.TailnetNative and io.github.yhzion.velo.SshNative.
//! Network work runs on Core's threads, except sendComposer on its caller's worker.
const std = @import("std");
const core_mod = @import("rex/Core.zig");
const jni = @import("sys/jni.zig");
const glyphs = @import("sys/glyphs.zig");
const png = @import("sys/png.zig");
const tailnet = @import("sys/tailnet.zig");
const ssh = @import("sys/ssh.zig");

const Core = core_mod.Core;
const c = jni.c;
const Env = jni.Env;

const gpa = std.heap.c_allocator;

// Zig's defaults write logs and panic messages to stderr, which Android discards.
pub const std_options: std.Options = .{
    .logFn = logToLogcat,
    // Signals belong to ART and debuggerd, which use the stack bionic gives each
    // thread. Zig's default would replace it with a 256 KiB thread-local buffer.
    .signal_stack_size = null,
};
pub const panic = std.debug.FullPanic(panicToLogcat);

fn logToLogcat(
    comptime level: std.log.Level,
    comptime scope: @EnumLiteral(),
    comptime format: []const u8,
    args: anytype,
) void {
    const prio: jni.LogPriority = switch (level) {
        .err => .err,
        .warn => .warn,
        .info => .info,
        .debug => .debug,
    };
    const prefix = if (scope == .default) "" else "(" ++ @tagName(scope) ++ ") ";
    jni.log(prio, prefix ++ format, args);
}

fn panicToLogcat(message: []const u8, _: ?usize) noreturn {
    @branchHint(.cold);
    jni.log(.fatal, "panic: {s}", .{message});
    std.c.abort();
}

extern "android" fn ANativeWindow_fromSurface(env: Env, surface: c.jobject) ?*anyopaque;

/// What the `Long` handle given to Kotlin points to.
const Handle = struct {
    core: *Core,
    /// Global reference to the CoreListener.
    listener: c.jobject,
    on_state: c.jmethodID,
    /// Only advanced after a successful Java callback, under Core.host_lock.
    delivered_gen: u64 = 0,
    on_pane_report: c.jmethodID,
    on_pane_write: c.jmethodID,
    on_reconnect_token: c.jmethodID,
    on_error: c.jmethodID,
    glyphs: glyphs.Glyphs,
    /// CA directories passed to `Core.create`; kept until `destroy`.
    ca_dirs: [][]u8,

    fn create(env: Env, listener: c.jobject, ca_dirs_array: c.jobjectArray, px_per_sp: f32) !*Handle {
        if (listener == null) return error.NullListener;
        const global = jni.newGlobalRef(env, listener) orelse return error.OutOfMemory;
        errdefer jni.deleteGlobalRef(env, global);

        const self = try gpa.create(Handle);
        errdefer gpa.destroy(self);

        const ca_dirs = try readStringArray(env, ca_dirs_array);
        errdefer freeStrings(ca_dirs);

        self.* = .{
            .core = undefined,
            .listener = global,
            .on_pane_report = try jni.methodId(env, global, "onPaneReport", "(Ljava/lang/String;)V"),
            .on_pane_write = try jni.methodId(env, global, "onPaneWrite", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V"),
            .on_state = try jni.methodId(env, global, "onState", "(JZLjava/lang/String;)V"),
            .on_reconnect_token = try jni.methodId(env, global, "onReconnectToken", "(Ljava/lang/String;)V"),
            .on_error = try jni.methodId(env, global, "onError", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V"),
            .glyphs = .{
                .listener = global,
                .rasterize_glyph = try jni.methodId(env, global, "rasterizeGlyph", "([BZZIII)[B"),
            },
            .ca_dirs = ca_dirs,
        };
        self.core = try Core.create(gpa, .{
            .ctx = self,
            .onPaneReport = onPaneReport,
            .onPaneWrite = onPaneWrite,
            .onState = onState,
            .onReconnectToken = onReconnectToken,
            .onError = onError,
            .glyphs = self.glyphs.source(),
        }, .{ .ca_dirs = ca_dirs, .dialer = tailnet.dialer(), .ssh = ssh.dialer() }, px_per_sp);
        return self;
    }

    /// Stops the core (it joins its threads, so no callback runs after this) and frees
    /// everything.
    fn destroy(self: *Handle, env: Env) void {
        self.core.destroy();
        jni.deleteGlobalRef(env, self.listener);
        freeStrings(self.ca_dirs);
        gpa.destroy(self);
    }

    /// Call a `void` listener method whose parameters are all Strings, from any thread.
    fn notify(self: *Handle, method: c.jmethodID, strings: []const []const u8) void {
        const env = jni.attach() orelse return;
        if (!jni.pushFrame(env, 4)) return;
        defer jni.popFrame(env);

        var args: [3]c.jvalue = undefined;
        for (strings, args[0..strings.len]) |string, *arg| {
            arg.* = .{ .l = jni.newString(gpa, env, string) orelse return };
        }
        _ = jni.callVoid(env, self.listener, method, args[0..strings.len]);
    }
};

fn onPaneReport(ctx: *anyopaque, json: []const u8) void {
    const self: *Handle = @ptrCast(@alignCast(ctx));
    self.notify(self.on_pane_report, &.{json});
}

fn onPaneWrite(ctx: *anyopaque, tag: []const u8, result: Core.PaneWriteResult, message: []const u8) void {
    const self: *Handle = @ptrCast(@alignCast(ctx));
    self.notify(self.on_pane_write, &.{ tag, @tagName(result), message });
}

fn onState(ctx: *anyopaque, gen: u64, snapshot: []const u8, patch: ?[]const u8) void {
    const self: *Handle = @ptrCast(@alignCast(ctx));
    const env = jni.attach() orelse return;
    if (!jni.pushFrame(env, 1)) return;
    defer jni.popFrame(env);
    const delta = if (gen == self.delivered_gen + 1) patch else null;
    const text = jni.newString(gpa, env, delta orelse snapshot) orelse return;
    if (jni.callVoid(env, self.listener, self.on_state, &.{
        .{ .j = @intCast(gen) }, .{ .z = @intFromBool(delta == null) }, .{ .l = text },
    })) self.delivered_gen = gen;
}

fn onReconnectToken(ctx: *anyopaque, token: []const u8) void {
    const self: *Handle = @ptrCast(@alignCast(ctx));
    self.notify(self.on_reconnect_token, &.{token});
}

fn onError(ctx: *anyopaque, code: []const u8, message: []const u8, fix: []const u8) void {
    const self: *Handle = @ptrCast(@alignCast(ctx));
    // Also to logcat: the UI shows only the latest error.
    jni.log(.warn, "{s}: {s}", .{ code, message });
    self.notify(self.on_error, &.{ code, message, fix });
}

fn readStringArray(env: Env, array: c.jobjectArray) ![][]u8 {
    const count: usize = if (array == null) 0 else @intCast(env.*.*.GetArrayLength.?(env, array));
    const out = try gpa.alloc([]u8, count);
    var filled: usize = 0;
    errdefer {
        for (out[0..filled]) |string| gpa.free(string);
        gpa.free(out);
    }
    while (filled < count) : (filled += 1) {
        const element = env.*.*.GetObjectArrayElement.?(env, array, @intCast(filled));
        defer env.*.*.DeleteLocalRef.?(env, element);
        out[filled] = try jni.stringToUtf8(gpa, env, element);
    }
    return out;
}

fn freeStrings(strings: [][]u8) void {
    for (strings) |string| gpa.free(string);
    gpa.free(strings);
}

fn handleFrom(handle: c.jlong) ?*Handle {
    return @ptrFromInt(@as(usize, @intCast(@as(u64, @bitCast(handle)))));
}

/// A Java String argument as UTF-8 for the duration of one JNI call.
const Text = struct {
    bytes: []u8,

    fn init(env: Env, string: c.jstring) ?Text {
        return .{ .bytes = jni.stringToUtf8(gpa, env, string) catch return null };
    }

    fn deinit(self: Text) void {
        gpa.free(self.bytes);
    }
};

export fn JNI_OnLoad(vm: [*c]c.JavaVM, _: ?*anyopaque) callconv(.c) c.jint {
    if (!png.register()) {
        jni.log(.err, "Ghostty PNG decoder registration failed", .{});
        return c.JNI_ERR;
    }
    glyphs.init();
    return jni.onLoad(vm);
}

/// start(listener: CoreListener, caDirs: Array<String>, fontScaleDp: Float): Long
/// The Float is pixels per sp. Returns 0 on failure.
export fn Java_io_github_yhzion_velo_core_VeloCore_start(
    env: Env,
    _: c.jclass,
    listener: c.jobject,
    ca_dirs: c.jobjectArray,
    px_per_sp: c.jfloat,
) callconv(.c) c.jlong {
    const handle = Handle.create(env, listener, ca_dirs, px_per_sp) catch |err| {
        jni.log(.err, "start failed: {t}", .{err});
        return 0;
    };
    return @bitCast(@as(u64, @intFromPtr(handle)));
}

/// connect(core: Long, url: String, reconnectToken: String?)
export fn Java_io_github_yhzion_velo_core_VeloCore_connect(
    env: Env,
    _: c.jclass,
    handle: c.jlong,
    url: c.jstring,
    reconnect_token: c.jstring,
) callconv(.c) void {
    const self = handleFrom(handle) orelse return;
    const url_text = Text.init(env, url) orelse return;
    defer url_text.deinit();
    const token_text: ?Text = if (reconnect_token == null) null else Text.init(env, reconnect_token);
    defer if (token_text) |text| text.deinit();
    self.core.connect(url_text.bytes, if (token_text) |text| text.bytes else null);
}

/// disconnect(core: Long)
export fn Java_io_github_yhzion_velo_core_VeloCore_disconnect(_: Env, _: c.jclass, handle: c.jlong) callconv(.c) void {
    const self = handleFrom(handle) orelse return;
    self.core.disconnect();
}

/// requestState(core: Long)
export fn Java_io_github_yhzion_velo_core_VeloCore_requestState(_: Env, _: c.jclass, handle: c.jlong) callconv(.c) void {
    const self = handleFrom(handle) orelse return;
    self.core.requestState();
}

/// destroy(core: Long)
export fn Java_io_github_yhzion_velo_core_VeloCore_destroy(env: Env, _: c.jclass, handle: c.jlong) callconv(.c) void {
    const self = handleFrom(handle) orelse return;
    self.destroy(env);
}

/// selectSession(core: Long, sessionId: String)
export fn Java_io_github_yhzion_velo_core_VeloCore_selectSession(
    env: Env,
    _: c.jclass,
    handle: c.jlong,
    session: c.jstring,
) callconv(.c) void {
    const self = handleFrom(handle) orelse return;
    const session_text = Text.init(env, session) orelse return;
    defer session_text.deinit();
    self.core.selectSession(session_text.bytes);
}

/// command(core: Long, json: String)
export fn Java_io_github_yhzion_velo_core_VeloCore_command(
    env: Env,
    _: c.jclass,
    handle: c.jlong,
    json: c.jstring,
) callconv(.c) void {
    const self = handleFrom(handle) orelse return;
    const json_text = Text.init(env, json) orelse return;
    defer json_text.deinit();
    self.core.command(json_text.bytes);
}

export fn Java_io_github_yhzion_velo_core_VeloCore_composerTarget(env: Env, _: c.jclass, handle: c.jlong, session: c.jstring, block: c.jstring, require_paste: c.jboolean) callconv(.c) c.jlong {
    const self = handleFrom(handle) orelse return 0;
    const s = Text.init(env, session) orelse return 0;
    defer s.deinit();
    const b = Text.init(env, block) orelse return 0;
    defer b.deinit();
    return @bitCast(self.core.composerTarget(s.bytes, b.bytes, require_paste != 0));
}

export fn Java_io_github_yhzion_velo_core_VeloCore_invalidateComposer(_: Env, _: c.jclass, handle: c.jlong) callconv(.c) void {
    if (handleFrom(handle)) |self| self.core.invalidateComposer();
}

/// Only the explicit "OK" token means success (including if allocating the JNI result fails).
export fn Java_io_github_yhzion_velo_core_VeloCore_sendComposer(env: Env, _: c.jclass, handle: c.jlong, epoch: c.jlong, session: c.jstring, block: c.jstring, text: c.jstring, name: c.jstring, data: c.jbyteArray) callconv(.c) c.jstring {
    const self = handleFrom(handle) orelse return null;
    const s = Text.init(env, session) orelse return null;
    defer s.deinit();
    const b = Text.init(env, block) orelse return null;
    defer b.deinit();
    const t = Text.init(env, text) orelse return null;
    defer t.deinit();
    const n: ?Text = if (name != null) Text.init(env, name) orelse return null else null;
    defer if (n) |value| value.deinit();
    const bytes: ?[]u8 = if (data != null) blk: {
        const len = env.*.*.GetArrayLength.?(env, data);
        if (len < 0 or len > @import("rex/Upload.zig").max_bytes) return jni.newString(gpa, env, "FileTooLarge");
        const copy = gpa.alloc(u8, @intCast(len)) catch return null;
        if (len != 0) env.*.*.GetByteArrayRegion.?(env, data, 0, len, @ptrCast(copy.ptr));
        if (jni.clearException(env)) {
            gpa.free(copy);
            return null;
        }
        break :blk copy;
    } else null;
    defer if (bytes) |copy| gpa.free(copy);
    self.core.sendComposer(@bitCast(epoch), s.bytes, b.bytes, t.bytes, if (n) |value| value.bytes else null, bytes) catch |err| return jni.newString(gpa, env, @errorName(err));
    return jni.newString(gpa, env, "OK");
}

/// downloadGeneration(core: Long): Long
/// What a download started now passes on: a `downloadCancel` after this ends it.
export fn Java_io_github_yhzion_velo_core_VeloCore_downloadGeneration(_: Env, _: c.jclass, handle: c.jlong) callconv(.c) c.jlong {
    const self = handleFrom(handle) orelse return 0;
    return @bitCast(self.core.downloadGeneration());
}

/// paneDownloadBegin(core: Long, block: String, path: String, ifNoneMatch: String?, generation: Long): String
/// Blocking: asks the terminal to open `path` and returns `Core.downloadBegin`'s JSON. A failure
/// before an answer is `{"status":"error","code":"<error>"}`.
export fn Java_io_github_yhzion_velo_core_VeloCore_paneDownloadBegin(env: Env, _: c.jclass, handle: c.jlong, block: c.jstring, path: c.jstring, if_none_match: c.jstring, generation: c.jlong) callconv(.c) c.jstring {
    const self = handleFrom(handle) orelse return null;
    const b = Text.init(env, block) orelse return null;
    defer b.deinit();
    const p = Text.init(env, path) orelse return null;
    defer p.deinit();
    const v: ?Text = if (if_none_match != null) Text.init(env, if_none_match) orelse return null else null;
    defer if (v) |value| value.deinit();
    const answer = self.core.downloadBegin(gpa, b.bytes, p.bytes, if (v) |value| value.bytes else null, @bitCast(generation)) catch |err| {
        var buf: [96]u8 = undefined;
        const text = std.fmt.bufPrint(&buf, "{{\"status\":\"error\",\"code\":\"{s}\"}}", .{@errorName(err)}) catch return null;
        return jni.newString(gpa, env, text);
    };
    defer gpa.free(answer);
    return jni.newString(gpa, env, answer);
}

/// downloadFetch(core: Long, ticket: String, fd: Int, announced: Long, generation: Long): Long
/// Blocking: writes the file of `ticket` to the open file `fd`, which the caller closes.
/// `announced` is the size the server gave in the begin answer, or -1 when it gave none. Returns
/// the file's size, -1 when `downloadCancel` ended it, -2 when it failed or ended incomplete, or
/// -3 when the file is larger than it was announced to be or than Velo opens (`fd` then holds
/// part of it, up to the limit).
export fn Java_io_github_yhzion_velo_core_VeloCore_downloadFetch(env: Env, _: c.jclass, handle: c.jlong, ticket: c.jstring, fd: c.jint, announced: c.jlong, generation: c.jlong) callconv(.c) c.jlong {
    const self = handleFrom(handle) orelse return -2;
    const t = Text.init(env, ticket) orelse return -2;
    defer t.deinit();
    const size = self.core.downloadFetch(t.bytes, fd, if (announced < 0) null else @intCast(announced), @bitCast(generation)) catch |err| return switch (err) {
        error.Canceled => -1,
        error.TooLarge => -3,
        else => -2,
    };
    return @intCast(size);
}

/// downloadRelease(core: Long, ticket: String, generation: Long): Boolean
/// Blocking: ends the stream of a ticket whose file is not wanted; false when that failed.
export fn Java_io_github_yhzion_velo_core_VeloCore_downloadRelease(env: Env, _: c.jclass, handle: c.jlong, ticket: c.jstring, generation: c.jlong) callconv(.c) c.jboolean {
    const self = handleFrom(handle) orelse return c.JNI_FALSE;
    const t = Text.init(env, ticket) orelse return c.JNI_FALSE;
    defer t.deinit();
    self.core.downloadRelease(t.bytes, @bitCast(generation)) catch return c.JNI_FALSE;
    return c.JNI_TRUE;
}

/// downloadCancel(core: Long)
export fn Java_io_github_yhzion_velo_core_VeloCore_downloadCancel(_: Env, _: c.jclass, handle: c.jlong) callconv(.c) void {
    const self = handleFrom(handle) orelse return;
    self.core.downloadCancel();
}

/// forwardOpen(core: Long, block: String, host: String, port: Int): String
/// Blocking: forwards `host`:`port` of the remote, which a link in the terminal `block` names,
/// to the phone. `{"status":"ok","port":N}` for the phone's 127.0.0.1:N, or
/// `{"status":"error","code":…,"message"?:…}`.
export fn Java_io_github_yhzion_velo_core_VeloCore_forwardOpen(env: Env, _: c.jclass, handle: c.jlong, block: c.jstring, host: c.jstring, port: c.jint) callconv(.c) c.jstring {
    const self = handleFrom(handle) orelse return null;
    const b = Text.init(env, block) orelse return null;
    defer b.deinit();
    const h = Text.init(env, host) orelse return null;
    defer h.deinit();
    const answer = blk: {
        const p = std.math.cast(u16, port) orelse break :blk error.BadPort;
        if (p == 0) break :blk error.BadPort;
        break :blk self.core.forwardOpen(gpa, b.bytes, h.bytes, p);
    } catch |err| {
        var buf: [96]u8 = undefined;
        const text = std.fmt.bufPrint(&buf, "{{\"status\":\"error\",\"code\":\"{s}\"}}", .{@errorName(err)}) catch return null;
        return jni.newString(gpa, env, text);
    };
    defer gpa.free(answer);
    return jni.newString(gpa, env, answer);
}

/// forwardClose(core: Long, block: String)
/// Blocking: ends the forward of the tunnel block `block`, its connections, and the block.
export fn Java_io_github_yhzion_velo_core_VeloCore_forwardClose(env: Env, _: c.jclass, handle: c.jlong, block: c.jstring) callconv(.c) void {
    const self = handleFrom(handle) orelse return;
    const b = Text.init(env, block) orelse return;
    defer b.deinit();
    self.core.forwardClose(b.bytes) catch {};
}

/// forwardSweep(core: Long): Boolean
/// Blocking: closes the tunnel blocks this client left behind; false when it could not look.
export fn Java_io_github_yhzion_velo_core_VeloCore_forwardSweep(_: Env, _: c.jclass, handle: c.jlong) callconv(.c) c.jboolean {
    const self = handleFrom(handle) orelse return c.JNI_FALSE;
    self.core.forwardSweep() catch return c.JNI_FALSE;
    return c.JNI_TRUE;
}

/// key(core: Long, block: String, keyCode: Int, metaState: Int, unicode: Int, action: Int)
export fn Java_io_github_yhzion_velo_core_VeloCore_key(
    env: Env,
    _: c.jclass,
    handle: c.jlong,
    block: c.jstring,
    key_code: c.jint,
    meta_state: c.jint,
    unicode: c.jint,
    action: c.jint,
) callconv(.c) void {
    const self = handleFrom(handle) orelse return;
    const block_text = Text.init(env, block) orelse return;
    defer block_text.deinit();
    self.core.key(
        block_text.bytes,
        key_code,
        meta_state,
        @bitCast(unicode),
        std.math.cast(u8, action) orelse return,
    );
}

/// paneSurface(core: Long, block: String, surface: Surface?, widthPx: Int, heightPx: Int)
/// A null surface detaches. The pane owns the ANativeWindow reference and releases it.
export fn Java_io_github_yhzion_velo_core_VeloCore_paneSurface(
    env: Env,
    _: c.jclass,
    handle: c.jlong,
    block: c.jstring,
    surface: c.jobject,
    width_px: c.jint,
    height_px: c.jint,
) callconv(.c) void {
    const self = handleFrom(handle) orelse return;
    const block_text = Text.init(env, block) orelse return;
    defer block_text.deinit();
    const window: ?*anyopaque = if (surface == null) null else ANativeWindow_fromSurface(env, surface);
    self.core.paneSurface(block_text.bytes, window, @intCast(@max(width_px, 0)), @intCast(@max(height_px, 0)));
}

/// panePreview(core: Long, block: String, surface: Surface?, widthPx: Int, heightPx: Int)
/// `paneSurface` for a surface that draws the pane without the pane being shown.
export fn Java_io_github_yhzion_velo_core_VeloCore_panePreview(
    env: Env,
    _: c.jclass,
    handle: c.jlong,
    block: c.jstring,
    surface: c.jobject,
    width_px: c.jint,
    height_px: c.jint,
) callconv(.c) void {
    const self = handleFrom(handle) orelse return;
    const block_text = Text.init(env, block) orelse return;
    defer block_text.deinit();
    const window: ?*anyopaque = if (surface == null) null else ANativeWindow_fromSurface(env, surface);
    self.core.panePreview(block_text.bytes, window, @intCast(@max(width_px, 0)), @intCast(@max(height_px, 0)));
}

/// paneResume(core: Long, block: String, surface: Surface?, widthPx: Int, heightPx: Int)
/// `paneSurface` for a pane that is shown again where it was shown before its preview.
export fn Java_io_github_yhzion_velo_core_VeloCore_paneResume(
    env: Env,
    _: c.jclass,
    handle: c.jlong,
    block: c.jstring,
    surface: c.jobject,
    width_px: c.jint,
    height_px: c.jint,
) callconv(.c) void {
    const self = handleFrom(handle) orelse return;
    const block_text = Text.init(env, block) orelse return;
    defer block_text.deinit();
    const window: ?*anyopaque = if (surface == null) null else ANativeWindow_fromSurface(env, surface);
    self.core.paneResume(block_text.bytes, window, @intCast(@max(width_px, 0)), @intCast(@max(height_px, 0)));
}

/// paneSuspend(core: Long, block: String)
/// Detach the pane's surface because the app left the screen.
export fn Java_io_github_yhzion_velo_core_VeloCore_paneSuspend(
    env: Env,
    _: c.jclass,
    handle: c.jlong,
    block: c.jstring,
) callconv(.c) void {
    const self = handleFrom(handle) orelse return;
    const block_text = Text.init(env, block) orelse return;
    defer block_text.deinit();
    self.core.paneSuspend(block_text.bytes);
}

/// paneScale(core: Long, block: String, scale: Float, live: Boolean)
export fn Java_io_github_yhzion_velo_core_VeloCore_paneScale(
    env: Env,
    _: c.jclass,
    handle: c.jlong,
    block: c.jstring,
    scale: c.jfloat,
    live: c.jboolean,
) callconv(.c) void {
    const self = handleFrom(handle) orelse return;
    const block_text = Text.init(env, block) orelse return;
    defer block_text.deinit();
    self.core.paneScale(block_text.bytes, scale, live != 0);
}

/// Drag distance and pointer position, in surface pixels.
export fn Java_io_github_yhzion_velo_core_VeloCore_panePan(
    env: Env,
    _: c.jclass,
    handle: c.jlong,
    block: c.jstring,
    dx: c.jfloat,
    dy: c.jfloat,
    x: c.jfloat,
    y: c.jfloat,
) callconv(.c) void {
    const self = handleFrom(handle) orelse return;
    const block_text = Text.init(env, block) orelse return;
    defer block_text.deinit();
    self.core.panePan(block_text.bytes, dx, dy, x, y);
}

/// paneTouch(core: Long, block: String, action: Int, x: Float, y: Float)
export fn Java_io_github_yhzion_velo_core_VeloCore_paneTouch(
    env: Env,
    _: c.jclass,
    handle: c.jlong,
    block: c.jstring,
    action: c.jint,
    x: c.jfloat,
    y: c.jfloat,
) callconv(.c) void {
    const self = handleFrom(handle) orelse return;
    const block_text = Text.init(env, block) orelse return;
    defer block_text.deinit();
    self.core.paneTouch(block_text.bytes, std.math.cast(u8, action) orelse return, x, y);
}

/// Negative values mean unavailable. Otherwise bits 32..61 hold the count,
/// bits 0..31 the one-based position, and bit 62 indicates remaining search work.
export fn Java_io_github_yhzion_velo_core_VeloCore_paneFind(
    env: Env,
    _: c.jclass,
    handle: c.jlong,
    block: c.jstring,
    needle: c.jstring,
    direction: c.jint,
) callconv(.c) c.jlong {
    const self = handleFrom(handle) orelse return -1;
    const id = Text.init(env, block) orelse return -1;
    defer id.deinit();
    const query = Text.init(env, needle) orelse return -1;
    defer query.deinit();
    const result = self.core.paneFind(id.bytes, query.bytes, direction) catch return -1;
    return (@as(i64, @intFromBool(result.pending)) << 62) | (@as(i64, result.count) << 32) | result.position;
}

/// paneCopy(core: Long, block: String): String
/// Kotlin declares a non-null String: returns "" when the pane or its text is unavailable.
export fn Java_io_github_yhzion_velo_core_VeloCore_paneCopy(
    env: Env,
    _: c.jclass,
    handle: c.jlong,
    block: c.jstring,
    viewport: c.jboolean,
) callconv(.c) c.jstring {
    const text: ?[]u8 = copy: {
        const self = handleFrom(handle) orelse break :copy null;
        const block_text = Text.init(env, block) orelse break :copy null;
        defer block_text.deinit();
        if (viewport != 0) break :copy self.core.paneViewportText(gpa, block_text.bytes) catch null;
        break :copy self.core.paneCopy(gpa, block_text.bytes) catch null;
    };
    defer if (text) |bytes| gpa.free(bytes);
    return jni.newString(gpa, env, text orelse "") orelse jni.newString(gpa, env, "");
}

/// paneBracketedPaste(core: Long, block: String): Boolean
/// False when the pane is unavailable: the paste is then asked about, never framed unseen.
export fn Java_io_github_yhzion_velo_core_VeloCore_paneBracketedPaste(
    env: Env,
    _: c.jclass,
    handle: c.jlong,
    block: c.jstring,
) callconv(.c) c.jboolean {
    const self = handleFrom(handle) orelse return 0;
    const block_text = Text.init(env, block) orelse return 0;
    defer block_text.deinit();
    return @intFromBool(self.core.paneBracketedPaste(block_text.bytes));
}

/// paneLink(core: Long, block: String, x: Float, y: Float): String?
/// The link under the surface point as JSON (`Core.paneLink`), or null when there is none.
export fn Java_io_github_yhzion_velo_core_VeloCore_paneLink(
    env: Env,
    _: c.jclass,
    handle: c.jlong,
    block: c.jstring,
    x: c.jfloat,
    y: c.jfloat,
) callconv(.c) c.jstring {
    const self = handleFrom(handle) orelse return null;
    const id = Text.init(env, block) orelse return null;
    defer id.deinit();
    const text = (self.core.paneLink(gpa, id.bytes, x, y) catch null) orelse return null;
    defer gpa.free(text);
    return jni.newString(gpa, env, text);
}

/// paneMarkLink(core: Long, block: String, x: Float, y: Float): Boolean
/// Underlines the link under the point until paneUnmarkLink; false when there is none.
export fn Java_io_github_yhzion_velo_core_VeloCore_paneMarkLink(
    env: Env,
    _: c.jclass,
    handle: c.jlong,
    block: c.jstring,
    x: c.jfloat,
    y: c.jfloat,
) callconv(.c) c.jboolean {
    const self = handleFrom(handle) orelse return 0;
    const id = Text.init(env, block) orelse return 0;
    defer id.deinit();
    return @intFromBool(self.core.paneMarkLink(id.bytes, x, y));
}

/// paneUnmarkLink(core: Long, block: String)
export fn Java_io_github_yhzion_velo_core_VeloCore_paneUnmarkLink(
    env: Env,
    _: c.jclass,
    handle: c.jlong,
    block: c.jstring,
) callconv(.c) void {
    const self = handleFrom(handle) orelse return;
    const id = Text.init(env, block) orelse return;
    defer id.deinit();
    self.core.paneUnmarkLink(id.bytes);
}

/// paneClick(core: Long, block: String, x: Float, y: Float): Boolean
/// A tap that the pane's program takes as a left click at the tapped cell; false, with nothing
/// sent, when the program does not take the mouse.
export fn Java_io_github_yhzion_velo_core_VeloCore_paneClick(
    env: Env,
    _: c.jclass,
    handle: c.jlong,
    block: c.jstring,
    x: c.jfloat,
    y: c.jfloat,
) callconv(.c) c.jboolean {
    const self = handleFrom(handle) orelse return 0;
    const id = Text.init(env, block) orelse return 0;
    defer id.deinit();
    return @intFromBool(self.core.paneClick(id.bytes, x, y));
}

export fn Java_io_github_yhzion_velo_core_VeloCore_paneSelectWord(
    env: Env,
    _: c.jclass,
    handle: c.jlong,
    block: c.jstring,
    x: c.jfloat,
    y: c.jfloat,
) callconv(.c) void {
    const self = handleFrom(handle) orelse return;
    const id = Text.init(env, block) orelse return;
    defer id.deinit();
    self.core.paneSelectWord(id.bytes, x, y);
}

export fn Java_io_github_yhzion_velo_core_VeloCore_paneMoveSelectionHandle(
    env: Env,
    _: c.jclass,
    handle: c.jlong,
    block: c.jstring,
    start: c.jboolean,
    x: c.jfloat,
    y: c.jfloat,
) callconv(.c) void {
    const self = handleFrom(handle) orelse return;
    const id = Text.init(env, block) orelse return;
    defer id.deinit();
    self.core.paneMoveSelectionHandle(id.bytes, start != 0, x, y);
}

export fn Java_io_github_yhzion_velo_core_VeloCore_paneSelectionBounds(
    env: Env,
    _: c.jclass,
    handle: c.jlong,
    block: c.jstring,
) callconv(.c) c.jintArray {
    const self = handleFrom(handle) orelse return null;
    const id = Text.init(env, block) orelse return null;
    defer id.deinit();
    const bounds = self.core.paneSelectionBounds(id.bytes) orelse return null;
    const out = env.*.*.NewIntArray.?(env, bounds.len) orelse return null;
    env.*.*.SetIntArrayRegion.?(env, out, 0, bounds.len, &bounds);
    return out;
}

export fn Java_io_github_yhzion_velo_core_VeloCore_paneSelectedText(
    env: Env,
    _: c.jclass,
    handle: c.jlong,
    block: c.jstring,
) callconv(.c) c.jstring {
    const text: ?[]u8 = copy: {
        const self = handleFrom(handle) orelse break :copy null;
        const id = Text.init(env, block) orelse break :copy null;
        defer id.deinit();
        break :copy self.core.paneSelectedText(gpa, id.bytes) catch null;
    };
    defer if (text) |bytes| gpa.free(bytes);
    return jni.newString(gpa, env, text orelse "") orelse jni.newString(gpa, env, "");
}

/// paneHasSelection(core: Long, block: String): Boolean
export fn Java_io_github_yhzion_velo_core_VeloCore_paneHasSelection(
    env: Env,
    _: c.jclass,
    handle: c.jlong,
    block: c.jstring,
) callconv(.c) c.jboolean {
    const self = handleFrom(handle) orelse return 0;
    const block_text = Text.init(env, block) orelse return 0;
    defer block_text.deinit();
    return @intFromBool(self.core.paneHasSelection(block_text.bytes));
}

/// paneClearSelection(core: Long, block: String)
export fn Java_io_github_yhzion_velo_core_VeloCore_paneClearSelection(
    env: Env,
    _: c.jclass,
    handle: c.jlong,
    block: c.jstring,
) callconv(.c) void {
    const self = handleFrom(handle) orelse return;
    const block_text = Text.init(env, block) orelse return;
    defer block_text.deinit();
    self.core.paneClearSelection(block_text.bytes);
}

/// panePreedit(core: Long, block: String, text: String)
/// The keyboard's composition to draw at the pane's cursor ("" clears it).
export fn Java_io_github_yhzion_velo_core_VeloCore_panePreedit(
    env: Env,
    _: c.jclass,
    handle: c.jlong,
    block: c.jstring,
    text: c.jstring,
) callconv(.c) void {
    const self = handleFrom(handle) orelse return;
    const block_text = Text.init(env, block) orelse return;
    defer block_text.deinit();
    const preedit = Text.init(env, text) orelse return;
    defer preedit.deinit();
    self.core.panePreedit(block_text.bytes, preedit.bytes);
}

// ---- io.github.yhzion.velo.TailnetNative: the process-wide Tailscale node ----

/// start(dir: String, hostname: String): String?  Null when started, else why not.
export fn Java_io_github_yhzion_velo_TailnetNative_start(
    env: Env,
    _: c.jclass,
    dir: c.jstring,
    hostname: c.jstring,
) callconv(.c) c.jstring {
    const err_name: []const u8 = start: {
        const dir_text = Text.init(env, dir) orelse break :start "OutOfMemory";
        defer dir_text.deinit();
        const host_text = Text.init(env, hostname) orelse break :start "OutOfMemory";
        defer host_text.deinit();
        const dir_z = gpa.dupeSentinel(u8, dir_text.bytes, 0) catch break :start "OutOfMemory";
        defer gpa.free(dir_z);
        const host_z = gpa.dupeSentinel(u8, host_text.bytes, 0) catch break :start "OutOfMemory";
        defer gpa.free(host_z);
        tailnet.start(dir_z, host_z) catch |err| break :start @errorName(err);
        return null;
    };
    return jni.newString(gpa, env, err_name);
}

/// stop()
export fn Java_io_github_yhzion_velo_TailnetNative_stop(_: Env, _: c.jclass) callconv(.c) void {
    tailnet.stop();
}

/// statusJson(): String  (see sys/tailnet.zig `statusJson`; "{}" when stopped)
export fn Java_io_github_yhzion_velo_TailnetNative_statusJson(env: Env, _: c.jclass) callconv(.c) c.jstring {
    const json = tailnet.statusJson(gpa) catch return jni.newString(gpa, env, "{}");
    defer gpa.free(json);
    return jni.newString(gpa, env, json);
}

/// logout(): String?  Null when logged out, else why not.
export fn Java_io_github_yhzion_velo_TailnetNative_logout(env: Env, _: c.jclass) callconv(.c) c.jstring {
    var buf: [256]u8 = undefined;
    const failed = tailnet.logout(&buf) orelse return null;
    return jni.newString(gpa, env, failed);
}

/// get200(url: String, timeoutMs: Int): String?  The body of a 200 answer through the
/// node, else null.
export fn Java_io_github_yhzion_velo_TailnetNative_get200(
    env: Env,
    _: c.jclass,
    url: c.jstring,
    timeout_ms: c.jint,
) callconv(.c) c.jstring {
    const url_text = Text.init(env, url) orelse return null;
    defer url_text.deinit();
    const url_z = gpa.dupeSentinel(u8, url_text.bytes, 0) catch return null;
    defer gpa.free(url_z);
    const body = tailnet.get200(gpa, url_z, std.math.cast(u31, timeout_ms) orelse return null) orelse return null;
    defer gpa.free(body);
    return jni.newString(gpa, env, body);
}

// ---- io.github.yhzion.velo.SshNative: the process-wide SSH pool (sys/ssh.zig) ----
//
// An Int result is 0 for success, an errno value as native/tailscale/ssh.go returns it
// (EPERM: no init yet), or -1 when the native library has no SSH pool. A string argument
// the call cannot take is EINVAL when it is null or holds U+0000 (C cannot take it), and
// ENOMEM when there was no memory for it (`CText.errno`). Every string argument passes
// through `CText`, which zeroes its copy: keys, passphrases and passwords among them.

const CText = jni.CText;
const einval: c.jint = @backingInt(std.posix.E.INVAL);
const enomem: c.jint = @backingInt(std.posix.E.NOMEM);
const eio: c.jint = @backingInt(std.posix.E.IO);

/// Zero and free a C string of the pool that may hold a key.
fn freeSecret(text: ?[*:0]u8) void {
    const p = text orelse return;
    std.crypto.secureZero(u8, std.mem.span(p));
    std.c.free(p);
}

/// A new Java String[] of `strings`; null on failure. Each passes through
/// `newSecretString`: a private key is among them.
fn stringArray(env: Env, strings: []const []const u8) c.jobjectArray {
    const class = env.*.*.FindClass.?(env, "java/lang/String");
    if (jni.clearException(env) or class == null) return null;
    defer env.*.*.DeleteLocalRef.?(env, class);
    const array = env.*.*.NewObjectArray.?(env, @intCast(strings.len), class, null);
    if (jni.clearException(env) or array == null) return null;
    for (strings, 0..) |string, i| {
        const element = jni.newSecretString(gpa, env, string) orelse return null;
        env.*.*.SetObjectArrayElement.?(env, array, @intCast(i), element);
        env.*.*.DeleteLocalRef.?(env, element);
        if (jni.clearException(env)) return null;
    }
    return array;
}

/// init(stateDir: String): Int  Makes the pool, which remembers server sockets in
/// stateDir (host-endpoints.json). Again with the same directory: 0; another: EEXIST.
export fn Java_io_github_yhzion_velo_SshNative_init(env: Env, _: c.jclass, state_dir: c.jstring) callconv(.c) c.jint {
    const rc = initSsh(env, state_dir);
    // A failed init is for good: the dials the pool refuses stop the core (ssh_unavailable).
    ssh.initDone(rc);
    return rc;
}

fn initSsh(env: Env, state_dir: c.jstring) c.jint {
    const a = ssh.load() orelse return ssh.unavailable;
    const dir = CText.init(gpa, env, state_dir) catch |err| return CText.errno(err);
    defer dir.deinit(gpa);
    return a.init(dir.bytes);
}

/// questions(): String?  The JSON array of the questions waiting for an answer, oldest
/// first; null before init or without the pool.
export fn Java_io_github_yhzion_velo_SshNative_questions(env: Env, _: c.jclass) callconv(.c) c.jstring {
    const a = ssh.load() orelse return null;
    var out: ?[*:0]u8 = null;
    const rc = a.questions(&out);
    defer if (out) |text| std.c.free(text);
    if (rc != 0) return null;
    return jni.newString(gpa, env, std.mem.span(out orelse return null));
}

/// answer(id: Long, answer: String, ok: Boolean): Boolean  Answers question id (ok false
/// declines it). False when it no longer waits (or before init, or without the pool).
export fn Java_io_github_yhzion_velo_SshNative_answer(
    env: Env,
    _: c.jclass,
    id: c.jlong,
    answer: c.jstring,
    ok: c.jboolean,
) callconv(.c) c.jboolean {
    const a = ssh.load() orelse return c.JNI_FALSE;
    const text = CText.init(gpa, env, answer) catch return c.JNI_FALSE;
    defer text.deinit(gpa);
    return if (a.answer(id, text.bytes, @intFromBool(ok != 0)) == 0) c.JNI_TRUE else c.JNI_FALSE;
}

/// setIdentities(pems: Array<String>): Int  The keys logins offer, in order: the phone key
/// first, then imported keys as imported; a login offers the first five. Each is
/// unencrypted OpenSSH or PEM; an empty array clears them. EINVAL: a null array or
/// element (the keys stay as they were). 1000+N: a key that does not parse was left out,
/// N the place of the first (from 1); the others are set.
export fn Java_io_github_yhzion_velo_SshNative_setIdentities(env: Env, _: c.jclass, pems: c.jobjectArray) callconv(.c) c.jint {
    const a = ssh.load() orelse return ssh.unavailable;
    if (pems == null) return einval;
    const n: usize = @intCast(env.*.*.GetArrayLength.?(env, pems));
    const keys = gpa.alloc(CText, n) catch return enomem;
    var opened: usize = 0;
    defer {
        for (keys[0..opened]) |key| key.deinit(gpa);
        gpa.free(keys);
    }
    const texts = gpa.alloc([]const u8, n) catch return enomem;
    defer gpa.free(texts);
    for (keys, texts, 0..) |*key, *text, i| {
        const element = env.*.*.GetObjectArrayElement.?(env, pems, @intCast(i));
        if (jni.clearException(env)) return einval;
        defer if (element != null) env.*.*.DeleteLocalRef.?(env, element);
        key.* = CText.init(gpa, env, element) catch |err| return CText.errno(err);
        opened += 1;
        text.* = key.bytes;
    }
    const json = ssh.identitiesJson(gpa, texts) catch return enomem;
    defer {
        std.crypto.secureZero(u8, json);
        gpa.free(json);
    }
    return a.set_identities(json.ptr);
}

/// generateKey(comment: String): Array<String>?  A new Ed25519 key: [pem (unencrypted
/// OpenSSH), publicLine (authorized_keys line ending in comment)]; null on failure.
/// Needs no init.
export fn Java_io_github_yhzion_velo_SshNative_generateKey(env: Env, _: c.jclass, comment: c.jstring) callconv(.c) c.jobjectArray {
    const a = ssh.load() orelse return null;
    const text = CText.init(gpa, env, comment) catch return null;
    defer text.deinit(gpa);
    var pem: ?[*:0]u8 = null;
    var public: ?[*:0]u8 = null;
    const rc = a.generate_key(text.bytes, &pem, &public);
    defer freeSecret(pem);
    defer if (public) |line| std.c.free(line);
    if (rc != 0) return null;
    return stringArray(env, &.{ std.mem.span(pem orelse return null), std.mem.span(public orelse return null) });
}

/// decryptKey(pem: String, passphrase: String?, pemOut: Array<String?>): Int  Opens an
/// imported key with its passphrase (null or "" for none). 0: pemOut[0] is the key as
/// unencrypted OpenSSH PEM. EACCES: the passphrase is missing or wrong. EINVAL: not a key
/// the app can use (or pemOut is empty). Needs no init.
export fn Java_io_github_yhzion_velo_SshNative_decryptKey(
    env: Env,
    _: c.jclass,
    pem: c.jstring,
    passphrase: c.jstring,
    pem_out: c.jobjectArray,
) callconv(.c) c.jint {
    const a = ssh.load() orelse return ssh.unavailable;
    if (pem_out == null or env.*.*.GetArrayLength.?(env, pem_out) < 1) return einval;
    const key = CText.init(gpa, env, pem) catch |err| return CText.errno(err);
    defer key.deinit(gpa);
    const pass: ?CText = if (passphrase == null) null else (CText.init(gpa, env, passphrase) catch |err| return CText.errno(err));
    defer if (pass) |p| p.deinit(gpa);
    var out: ?[*:0]u8 = null;
    const rc = a.decrypt_key(key.bytes, if (pass) |p| p.bytes else null, &out);
    defer freeSecret(out);
    if (rc != 0) return rc;
    const text = jni.newSecretString(gpa, env, std.mem.span(out orelse return eio)) orelse return enomem;
    env.*.*.SetObjectArrayElement.?(env, pem_out, 0, text);
    env.*.*.DeleteLocalRef.?(env, text);
    if (jni.clearException(env)) return einval;
    return 0;
}

/// installKey(locator: String, publicLine: String, timeoutMs: Int): Int  Appends
/// publicLine to ~/.ssh/authorized_keys on the target, once, over the pool's connection,
/// within timeoutMs (0: the pool's own limits). ETIMEDOUT: out of time; EIO: it failed.
export fn Java_io_github_yhzion_velo_SshNative_installKey(
    env: Env,
    _: c.jclass,
    locator: c.jstring,
    public_line: c.jstring,
    timeout_ms: c.jint,
) callconv(.c) c.jint {
    const a = ssh.load() orelse return ssh.unavailable;
    const target = CText.init(gpa, env, locator) catch |err| return CText.errno(err);
    defer target.deinit(gpa);
    const line = CText.init(gpa, env, public_line) catch |err| return CText.errno(err);
    defer line.deinit(gpa);
    return a.install_key(target.bytes, line.bytes, timeout_ms);
}

/// remembered(locator: String): String?  What the pool remembers for the target, as JSON:
/// {"socket": its server socket or "", "refused_until": until when the host's refusal of
/// the forward holds, in unix seconds, 0 for none}. Null before init, for a malformed
/// locator, or without the pool.
export fn Java_io_github_yhzion_velo_SshNative_remembered(env: Env, _: c.jclass, locator: c.jstring) callconv(.c) c.jstring {
    const a = ssh.load() orelse return null;
    const target = CText.init(gpa, env, locator) catch return null;
    defer target.deinit(gpa);
    var out: ?[*:0]u8 = null;
    const rc = a.remembered(target.bytes, &out);
    defer if (out) |text| std.c.free(text);
    if (rc != 0) return null;
    return jni.newString(gpa, env, std.mem.span(out orelse return null));
}

/// forgetSocket(locator: String): Int  Forgets the target's server socket and refusal;
/// its connection and streams stay, and the next stream probes again.
export fn Java_io_github_yhzion_velo_SshNative_forgetSocket(env: Env, _: c.jclass, locator: c.jstring) callconv(.c) c.jint {
    const a = ssh.load() orelse return ssh.unavailable;
    const target = CText.init(gpa, env, locator) catch |err| return CText.errno(err);
    defer target.deinit(gpa);
    return a.forget_socket(target.bytes);
}

/// forget(locator: String): Int  Drops the target's connection and its remembered
/// socket and refusal.
export fn Java_io_github_yhzion_velo_SshNative_forget(env: Env, _: c.jclass, locator: c.jstring) callconv(.c) c.jint {
    const a = ssh.load() orelse return ssh.unavailable;
    const target = CText.init(gpa, env, locator) catch |err| return CText.errno(err);
    defer target.deinit(gpa);
    return a.forget(target.bytes);
}
