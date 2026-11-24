//! Thin helpers over the JNI C API (module `jni_c`: host translation of the NDK's
//! jni.h). The JavaVM is cached by `onLoad`; `attach` gives any native thread a JNIEnv
//! and detaches it when the thread exits.
const std = @import("std");

pub const c = @import("jni_c");

/// `JNIEnv*` as the JNI functions take it.
pub const Env = [*c]c.JNIEnv;

extern "log" fn __android_log_write(prio: c_int, tag: [*:0]const u8, text: [*:0]const u8) c_int;

pub const LogPriority = enum(c_int) { debug = 3, info = 4, warn = 5, err = 6, fatal = 7 };

/// Write one line to logcat with tag "velocore". Longer text is cut at 1023 bytes. Off
/// Android (host tests) there is no logcat, and nothing is written.
pub fn log(prio: LogPriority, comptime format: []const u8, args: anytype) void {
    if (comptime !@import("builtin").abi.isAndroid()) return;
    var buf: [1024]u8 = undefined;
    const text = std.fmt.bufPrint(buf[0 .. buf.len - 1], format, args) catch buf[0 .. buf.len - 1];
    buf[text.len] = 0;
    _ = __android_log_write(@intFromEnum(prio), "velocore", @ptrCast(&buf));
}

var vm: [*c]c.JavaVM = null;
/// Its value is non-null on threads that `attach` attached; the destructor detaches.
var detach_key: std.c.pthread_key_t = undefined;
var detach_key_made = false;

/// Call from `JNI_OnLoad`. Returns the JNI version to report to the VM.
pub fn onLoad(java_vm: [*c]c.JavaVM) c.jint {
    vm = java_vm;
    // Once per process: the host tests load a fake VM more than once.
    if (!detach_key_made) {
        _ = std.c.pthread_key_create(&detach_key, detachOnThreadExit);
        detach_key_made = true;
    }
    return c.JNI_VERSION_1_6;
}

fn detachOnThreadExit(_: *anyopaque) callconv(.c) void {
    if (vm != null) _ = vm.*.*.DetachCurrentThread.?(vm);
}

/// The JNIEnv of the calling thread. A native thread that the VM does not know yet is
/// attached and stays attached until it exits. Null if the VM refuses.
pub fn attach() ?Env {
    if (vm == null) return null;
    var raw: ?*anyopaque = null;
    if (vm.*.*.GetEnv.?(vm, &raw, c.JNI_VERSION_1_6) == c.JNI_OK) {
        // Cast to the C pointer first: `@ptrCast` straight to `?Env` (an optional of an
        // already nullable pointer) leaves the optional's tag undefined in release builds.
        const env: Env = @ptrCast(@alignCast(raw));
        if (env != null) return env;
    }
    var env: Env = null;
    // The VM renames the thread it attaches to the name it is given. A thread the core named
    // (`Trace.nameThread`: velo-…) keeps the name a trace knows it by (#339); any other one,
    // which carries the name of the thread that made it, is "velocore".
    var name: [16:0]u8 = @splat(0);
    _ = std.posix.prctl(.GET_NAME, .{@intFromPtr(&name)}) catch {};
    const own = std.mem.startsWith(u8, std.mem.sliceTo(&name, 0), "velo-");
    var args: c.JavaVMAttachArgs = .{ .version = c.JNI_VERSION_1_6, .name = if (own) &name else "velocore", .group = null };
    if (vm.*.*.AttachCurrentThread.?(vm, &env, &args) != c.JNI_OK or env == null) {
        log(.err, "AttachCurrentThread failed", .{});
        return null;
    }
    _ = std.c.pthread_setspecific(detach_key, @ptrCast(@constCast(vm)));
    return env;
}

/// Logs and clears a pending Java exception. Returns true if there was one.
pub fn clearException(env: Env) bool {
    if (env.*.*.ExceptionCheck.?(env) == 0) return false;
    env.*.*.ExceptionDescribe.?(env);
    env.*.*.ExceptionClear.?(env);
    return true;
}

pub fn newGlobalRef(env: Env, obj: c.jobject) c.jobject {
    return env.*.*.NewGlobalRef.?(env, obj);
}

pub fn deleteGlobalRef(env: Env, obj: c.jobject) void {
    if (obj != null) env.*.*.DeleteGlobalRef.?(env, obj);
}

/// Method ID of an instance method of `obj`'s class.
pub fn methodId(env: Env, obj: c.jobject, name: [*:0]const u8, signature: [*:0]const u8) !c.jmethodID {
    const class = env.*.*.GetObjectClass.?(env, obj);
    defer env.*.*.DeleteLocalRef.?(env, class);
    const id = env.*.*.GetMethodID.?(env, class, name, signature);
    if (clearException(env) or id == null) return error.MethodNotFound;
    return id;
}

/// Reserve room for `capacity` local references. Pair with `popFrame`, which frees
/// every local reference created in between. Core threads never return to Java, so
/// their local references would otherwise pile up.
pub fn pushFrame(env: Env, capacity: c.jint) bool {
    if (env.*.*.PushLocalFrame.?(env, capacity) == 0) return true;
    _ = clearException(env);
    return false;
}

pub fn popFrame(env: Env) void {
    _ = env.*.*.PopLocalFrame.?(env, null);
}

/// Call a `void` method. Returns false if it threw (the exception is logged and cleared).
pub fn callVoid(env: Env, obj: c.jobject, method: c.jmethodID, args: []const c.jvalue) bool {
    env.*.*.CallVoidMethodA.?(env, obj, method, args.ptr);
    return !clearException(env);
}

/// Call a method that returns an object. Null if it returned null or threw.
pub fn callObject(env: Env, obj: c.jobject, method: c.jmethodID, args: []const c.jvalue) c.jobject {
    const result = env.*.*.CallObjectMethodA.?(env, obj, method, args.ptr);
    if (clearException(env)) return null;
    return result;
}

/// Java String → UTF-8. Caller frees. Goes through UTF-16 because JNI's own "UTF"
/// functions use modified UTF-8, which is not UTF-8 for characters above U+FFFF.
pub fn stringToUtf8(gpa: std.mem.Allocator, env: Env, string: c.jstring) ![]u8 {
    if (string == null) return error.NullString;
    const len: usize = @intCast(env.*.*.GetStringLength.?(env, string));
    const chars = env.*.*.GetStringChars.?(env, string, null);
    if (chars == null) {
        _ = clearException(env);
        return error.OutOfMemory;
    }
    defer env.*.*.ReleaseStringChars.?(env, string, chars);
    return utf16ToUtf8(gpa, chars[0..len]);
}

/// A Java String argument as NUL-terminated UTF-8 for the duration of one JNI call,
/// zeroed when freed: keys, passphrases and passwords pass through it.
pub const CText = struct {
    bytes: [:0]u8,

    pub const Error = error{
        /// The string is null.
        NullString,
        /// The string holds U+0000, which a C string cannot.
        HasNul,
        /// No memory, in this process or in the VM.
        OutOfMemory,
    };

    pub fn init(gpa: std.mem.Allocator, env: Env, string: c.jstring) Error!CText {
        const utf8 = try stringToUtf8(gpa, env, string);
        defer {
            std.crypto.secureZero(u8, utf8);
            gpa.free(utf8);
        }
        if (std.mem.findScalar(u8, utf8, 0) != null) return error.HasNul;
        return .{ .bytes = try gpa.dupeSentinel(u8, utf8, 0) };
    }

    pub fn deinit(self: CText, gpa: std.mem.Allocator) void {
        std.crypto.secureZero(u8, self.bytes);
        gpa.free(self.bytes);
    }

    /// The errno a JNI call returns for a string `init` could not take: ENOMEM when
    /// memory ran out, else EINVAL.
    pub fn errno(err: Error) c.jint {
        return switch (err) {
            error.OutOfMemory => @backingInt(std.posix.E.NOMEM),
            error.NullString, error.HasNul => @backingInt(std.posix.E.INVAL),
        };
    }
};

/// UTF-8 → new Java String (a local reference). Null on failure. Invalid UTF-8 becomes U+FFFD.
pub fn newString(gpa: std.mem.Allocator, env: Env, utf8: []const u8) c.jstring {
    const units = utf8ToUtf16(gpa, utf8) catch return null;
    defer gpa.free(units);
    return javaString(env, units);
}

/// `newString` for a secret (a private key): the UTF-16 copy made on the way is zeroed
/// before it is freed, and never reallocated (a move would leave a copy behind).
pub fn newSecretString(gpa: std.mem.Allocator, env: Env, utf8: []const u8) c.jstring {
    const units = gpa.alloc(u16, utf8.len) catch return null;
    defer gpa.free(units);
    return secretStringIn(env, utf8, units);
}

/// `newSecretString` with the UTF-16 copy in `scratch` (at least `utf8.len` units), which
/// it zeroes before it returns.
pub fn secretStringIn(env: Env, utf8: []const u8, scratch: []u16) c.jstring {
    defer std.crypto.secureZero(u16, scratch);
    return javaString(env, scratch[0..encodeUtf16(scratch, utf8)]);
}

fn javaString(env: Env, units: []const u16) c.jstring {
    // NewString reads `len` units; give it a valid pointer even for the empty string.
    const empty: [1]u16 = .{0};
    const ptr: [*]const u16 = if (units.len == 0) &empty else units.ptr;
    const string = env.*.*.NewString.?(env, ptr, @intCast(units.len));
    if (clearException(env)) return null;
    return string;
}

/// UTF-16 → UTF-8. An unpaired surrogate becomes U+FFFD. Caller frees.
pub fn utf16ToUtf8(gpa: std.mem.Allocator, units: []const u16) ![]u8 {
    var out: std.ArrayList(u8) = .empty;
    errdefer out.deinit(gpa);
    // At most 3 bytes per unit: a surrogate pair is 2 units and 4 bytes.
    try out.ensureTotalCapacity(gpa, units.len * 3);
    var i: usize = 0;
    while (i < units.len) {
        var cp: u21 = units[i];
        i += 1;
        if (cp >= 0xD800 and cp <= 0xDBFF and i < units.len and units[i] >= 0xDC00 and units[i] <= 0xDFFF) {
            cp = 0x10000 + ((cp - 0xD800) << 10) + (units[i] - 0xDC00);
            i += 1;
        } else if (cp >= 0xD800 and cp <= 0xDFFF) {
            cp = std.unicode.replacement_character;
        }
        var buf: [4]u8 = undefined;
        const n = std.unicode.utf8Encode(cp, &buf) catch unreachable;
        out.appendSliceAssumeCapacity(buf[0..n]);
    }
    return out.toOwnedSlice(gpa);
}

/// UTF-8 → UTF-16. Each byte that is not part of a valid sequence becomes U+FFFD.
/// Caller frees.
pub fn utf8ToUtf16(gpa: std.mem.Allocator, bytes: []const u8) ![]u16 {
    // Never more units than bytes: 4 bytes make 2 units, an invalid byte makes 1.
    var out = try gpa.alloc(u16, bytes.len);
    errdefer gpa.free(out);
    out = try gpa.realloc(out, encodeUtf16(out, bytes));
    return out;
}

/// Writes `bytes` as UTF-16 to `out` (at least `bytes.len` long), as `utf8ToUtf16`
/// converts them, and returns how many units it wrote.
fn encodeUtf16(out: []u16, bytes: []const u8) usize {
    var n: usize = 0;
    var i: usize = 0;
    while (i < bytes.len) {
        const cp: u21 = decode: {
            const len = std.unicode.utf8ByteSequenceLength(bytes[i]) catch break :decode null;
            if (i + len > bytes.len) break :decode null;
            const value = std.unicode.utf8Decode(bytes[i..][0..len]) catch break :decode null;
            i += len;
            break :decode value;
        } orelse invalid: {
            i += 1;
            break :invalid std.unicode.replacement_character;
        };
        if (cp >= 0x10000) {
            const v = cp - 0x10000;
            out[n] = @intCast(0xD800 + (v >> 10));
            out[n + 1] = @intCast(0xDC00 + (v & 0x3FF));
            n += 2;
        } else {
            out[n] = @intCast(cp);
            n += 1;
        }
    }
    return n;
}

test "utf16ToUtf8: BMP, surrogate pair, unpaired surrogates" {
    const gpa = std.testing.allocator;
    const out = try utf16ToUtf8(gpa, &.{ 'a', 0xD55C, 0xD83D, 0xDE00, 0xD83D, 'b', 0xDE00 });
    defer gpa.free(out);
    try std.testing.expectEqualStrings("a\u{D55C}\u{1F600}\u{FFFD}b\u{FFFD}", out);

    const empty = try utf16ToUtf8(gpa, &.{});
    defer gpa.free(empty);
    try std.testing.expectEqual(0, empty.len);
}

test "utf8ToUtf16: BMP, supplementary plane, invalid and truncated bytes" {
    const gpa = std.testing.allocator;
    const out = try utf8ToUtf16(gpa, "a\u{D55C}\u{1F600}\xffb\xf0\x9f");
    defer gpa.free(out);
    try std.testing.expectEqualSlices(u16, &.{ 'a', 0xD55C, 0xD83D, 0xDE00, 0xFFFD, 'b', 0xFFFD, 0xFFFD }, out);

    const empty = try utf8ToUtf16(gpa, "");
    defer gpa.free(empty);
    try std.testing.expectEqual(0, empty.len);
}

test "UTF-8 → UTF-16 → UTF-8 round trip" {
    const gpa = std.testing.allocator;
    const text = "{\"title\":\"\u{1F680} vim \u{E0B0} \u{D55C}\u{AE00}\"}";
    const units = try utf8ToUtf16(gpa, text);
    defer gpa.free(units);
    const back = try utf16ToUtf8(gpa, units);
    defer gpa.free(back);
    try std.testing.expectEqualStrings(text, back);
}
