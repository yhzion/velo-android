//! Host tests of sys/jni.zig that need JNI's types (module `jni_c`), and the root of the
//! test-jni step (build.zig). They are not in jni.zig itself: its own tests also run in
//! test-core (tests.zig reaches it through sys/ssh.zig), which has no `jni_c`.
const std = @import("std");
const jni = @import("jni.zig");

const c = jni.c;
const Env = jni.Env;
const CText = jni.CText;

test {
    // jni.zig's own tests.
    _ = jni;
}

/// A JNIEnv with the string functions `CText` and `newString` use: every jstring is
/// `chars`, and NewString keeps the units it gets in `made`.
const FakeJava = struct {
    /// What `Env` points to: the function table.
    table: c.JNIEnv = &functions,
    chars: []const u16,
    /// GetStringChars fails (the VM is out of memory) and leaves an exception.
    no_chars: bool = false,
    exception: bool = false,
    /// GetStringChars calls not yet released.
    held: usize = 0,
    /// The units of the last NewString.
    made: [256]u16 = undefined,
    made_len: usize = 0,

    const functions: c.JNINativeInterface = .{
        .GetStringLength = getStringLength,
        .GetStringChars = getStringChars,
        .ReleaseStringChars = releaseStringChars,
        .NewString = newString,
        .ExceptionCheck = exceptionCheck,
        .ExceptionDescribe = exceptionDescribe,
        .ExceptionClear = exceptionClear,
    };

    fn newString(e: [*c]c.JNIEnv, units: [*c]const c.jchar, len: c.jsize) callconv(.c) c.jstring {
        const self = of(e);
        const n: usize = @intCast(len);
        @memcpy(self.made[0..n], units[0..n]);
        self.made_len = n;
        return @ptrCast(&some_string);
    }

    fn env(self: *FakeJava) Env {
        return &self.table;
    }

    fn of(e: [*c]c.JNIEnv) *FakeJava {
        return @fieldParentPtr("table", @as(*c.JNIEnv, @ptrCast(e)));
    }

    fn getStringLength(e: [*c]c.JNIEnv, _: c.jstring) callconv(.c) c.jsize {
        return @intCast(of(e).chars.len);
    }

    fn getStringChars(e: [*c]c.JNIEnv, _: c.jstring, _: [*c]c.jboolean) callconv(.c) [*c]const c.jchar {
        const self = of(e);
        if (self.no_chars) {
            self.exception = true;
            return null;
        }
        self.held += 1;
        return self.chars.ptr;
    }

    fn releaseStringChars(e: [*c]c.JNIEnv, _: c.jstring, _: [*c]const c.jchar) callconv(.c) void {
        of(e).held -= 1;
    }

    fn exceptionCheck(e: [*c]c.JNIEnv) callconv(.c) c.jboolean {
        return @intFromBool(of(e).exception);
    }

    fn exceptionDescribe(_: [*c]c.JNIEnv) callconv(.c) void {}

    fn exceptionClear(e: [*c]c.JNIEnv) callconv(.c) void {
        of(e).exception = false;
    }
};

/// Any jstring the fake takes: it only has to be non-null.
var some_string: u8 = 0;

test "CText: a Java string as NUL-terminated UTF-8" {
    const gpa = std.testing.allocator;
    var java: FakeJava = .{ .chars = &.{ '-', '-', '-', '\n', 0xD55C, 0xD83D, 0xDE00 } };
    const text = try CText.init(gpa, java.env(), &some_string);
    defer text.deinit(gpa);
    try std.testing.expectEqualStrings("---\n\u{D55C}\u{1F600}", text.bytes);
    try std.testing.expectEqual(0, text.bytes.ptr[text.bytes.len]);
    try std.testing.expectEqual(0, java.held);
}

test "CText: no memory is ENOMEM; a null string or one holding U+0000 is EINVAL" {
    const gpa = std.testing.allocator;
    const enomem: c.jint = @backingInt(std.posix.E.NOMEM);
    const einval: c.jint = @backingInt(std.posix.E.INVAL);
    var java: FakeJava = .{ .chars = &.{ 'k', 'e', 'y' } };

    // Each allocation in turn fails, until none does: every failure is ENOMEM, and
    // nothing is left allocated (the testing allocator checks).
    var fail_index: usize = 0;
    while (true) : (fail_index += 1) {
        var failing: std.testing.FailingAllocator = .init(gpa, .{ .fail_index = fail_index });
        const text = CText.init(failing.allocator(), java.env(), &some_string) catch |err| {
            try std.testing.expectEqual(error.OutOfMemory, err);
            try std.testing.expectEqual(enomem, CText.errno(err));
            continue;
        };
        text.deinit(failing.allocator());
        break;
    }
    // The UTF-8 conversion and the NUL-terminated copy.
    try std.testing.expect(fail_index >= 2);
    try std.testing.expectEqual(0, java.held);

    // The VM has no memory for the string's characters.
    java.no_chars = true;
    const vm_err = CText.init(gpa, java.env(), &some_string);
    try std.testing.expectError(error.OutOfMemory, vm_err);
    try std.testing.expectEqual(enomem, CText.errno(error.OutOfMemory));
    try std.testing.expect(!java.exception);
    java.no_chars = false;

    const null_err = CText.init(gpa, java.env(), null);
    try std.testing.expectError(error.NullString, null_err);
    try std.testing.expectEqual(einval, CText.errno(error.NullString));

    java.chars = &.{ 'a', 0, 'b' };
    const nul_err = CText.init(gpa, java.env(), &some_string);
    try std.testing.expectError(error.HasNul, nul_err);
    try std.testing.expectEqual(einval, CText.errno(error.HasNul));
    try std.testing.expectEqual(0, java.held);
}

test "newSecretString: the UTF-16 copy of a private key is zeroed before it is freed" {
    // ASCII as a PEM is, and text whose UTF-16 is shorter than its UTF-8. The copy is made
    // in a buffer of the caller's, here the test's, to look at after the call (Allocator.free
    // overwrites what it frees in Debug builds, so a check at free time would see nothing).
    for ([_][]const u8{
        "-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaC1rZXktdjEAAAAABG5vbmU=\n-----END OPENSSH PRIVATE KEY-----\n",
        "key \u{D55C}\u{AE00} \u{1F511}\n",
    }) |secret| {
        var java: FakeJava = .{ .chars = &.{} };
        var scratch: [128]u16 = undefined;
        try std.testing.expect(jni.secretStringIn(java.env(), secret, scratch[0..secret.len]) != null);
        try std.testing.expect(std.mem.allEqual(u16, scratch[0..secret.len], 0));
        // Java got the text all the same.
        const back = try jni.utf16ToUtf8(std.testing.allocator, java.made[0..java.made_len]);
        defer std.testing.allocator.free(back);
        try std.testing.expectEqualStrings(secret, back);
    }

    // Through the allocator: the same text, nothing left allocated (the testing allocator
    // checks); the empty string; no memory is null.
    var java: FakeJava = .{ .chars = &.{} };
    try std.testing.expect(jni.newSecretString(std.testing.allocator, java.env(), "key \u{D55C}") != null);
    try std.testing.expectEqual(5, java.made_len);
    try std.testing.expect(jni.newSecretString(std.testing.allocator, java.env(), "") != null);
    try std.testing.expectEqual(0, java.made_len);
    var failing: std.testing.FailingAllocator = .init(std.testing.allocator, .{ .fail_index = 0 });
    try std.testing.expect(jni.newSecretString(failing.allocator(), java.env(), "key") == null);
}

/// A JavaVM that knows no thread yet: `attach` attaches the caller, and the name it asked
/// to be attached under is kept in `asked`.
const FakeVm = struct {
    var functions: c.struct_JNIInvokeInterface = .{
        .AttachCurrentThread = attachCurrentThread,
        .DetachCurrentThread = detachCurrentThread,
        .GetEnv = getEnv,
    };
    var vm: c.JavaVM = &functions;
    var some_env: c.JNIEnv = null;
    var asked: [32]u8 = undefined;
    var asked_len: usize = 0;

    fn getEnv(_: [*c]c.JavaVM, _: [*c]?*anyopaque, _: c.jint) callconv(.c) c.jint {
        return c.JNI_EDETACHED;
    }

    fn attachCurrentThread(_: [*c]c.JavaVM, env: [*c][*c]c.JNIEnv, raw: ?*anyopaque) callconv(.c) c.jint {
        const args: *c.JavaVMAttachArgs = @ptrCast(@alignCast(raw));
        const name = std.mem.span(args.name);
        asked_len = @min(name.len, asked.len);
        @memcpy(asked[0..asked_len], name[0..asked_len]);
        env.* = &some_env;
        return c.JNI_OK;
    }

    fn detachCurrentThread(_: [*c]c.JavaVM) callconv(.c) c.jint {
        return c.JNI_OK;
    }
};

test "attach: the VM attaches a native thread under the name the thread has (#339)" {
    // The VM renames a thread it attaches; one attached as "velocore" lost the name a trace knows it by.
    _ = jni.onLoad(&FakeVm.vm);
    defer _ = jni.onLoad(null);
    const Named = struct {
        fn run() void {
            _ = std.posix.prctl(.SET_NAME, .{@intFromPtr("velo-draw-1674")}) catch {};
            _ = jni.attach();
        }
    };
    const thread = try std.Thread.spawn(.{}, Named.run, .{});
    thread.join();
    try std.testing.expectEqualStrings("velo-draw-1674", FakeVm.asked[0..FakeVm.asked_len]);

    // A thread the core did not name has its maker's name: it is attached as "velocore".
    const Unnamed = struct {
        fn run() void {
            _ = std.posix.prctl(.SET_NAME, .{@intFromPtr("com.example.terminal")}) catch {};
            _ = jni.attach();
        }
    };
    const other = try std.Thread.spawn(.{}, Unnamed.run, .{});
    other.join();
    try std.testing.expectEqualStrings("velocore", FakeVm.asked[0..FakeVm.asked_len]);
}
