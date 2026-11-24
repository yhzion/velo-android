//! What a trace (Perfetto, systrace) shows of the core (#339): sections, which are
//! `ATrace_beginSection` / `ATrace_endSection` (libandroid, API 23) and do nothing off
//! Android, and thread names. A trace then reads without stack sampling, which a release
//! build does not allow. Whether atrace records the sections of a release build on a user
//! build of Android is still to be checked on the phone (#339); the names show in any trace.
const std = @import("std");
const builtin = @import("builtin");

const is_android = builtin.abi.isAndroid();

extern "android" fn ATrace_beginSection(name: [*:0]const u8) void;
extern "android" fn ATrace_endSection() void;

/// Open a section on the calling thread. Each `begin` needs its `end` on the same thread.
pub fn begin(comptime name: [:0]const u8) void {
    if (is_android) ATrace_beginSection(name);
}

pub fn end() void {
    if (is_android) ATrace_endSection();
}

/// Name the calling thread `prefix` followed by `tag`, cut to the 15 bytes the kernel keeps.
pub fn nameThread(prefix: []const u8, tag: []const u8) void {
    if (builtin.os.tag != .linux) return;
    var buf: [16]u8 = @splat(0);
    const n = @min(prefix.len, buf.len - 1);
    @memcpy(buf[0..n], prefix[0..n]);
    const m = @min(tag.len, buf.len - 1 - n);
    @memcpy(buf[n..][0..m], tag[0..m]);
    _ = std.posix.prctl(.SET_NAME, .{@intFromPtr(&buf)}) catch {};
}

/// What names a pane's threads: the end of its block id, after `block:`, four bytes at most.
pub fn blockTag(block: []const u8) []const u8 {
    const id = if (std.mem.startsWith(u8, block, "block:")) block["block:".len..] else block;
    return id[id.len - @min(id.len, 4) ..];
}

test "nameThread: the name is cut to the 15 bytes the kernel keeps" {
    const Named = struct {
        var got: [16:0]u8 = @splat(0);
        fn run() void {
            nameThread("velo-draw-", "123456");
            _ = std.posix.prctl(.GET_NAME, .{@intFromPtr(&got)}) catch {};
        }
    };
    const thread = try std.Thread.spawn(.{}, Named.run, .{});
    thread.join();
    try std.testing.expectEqualStrings("velo-draw-12345", std.mem.sliceTo(&Named.got, 0));
}

test "blockTag: the last four bytes of the id after block:" {
    try std.testing.expectEqualStrings("1674", blockTag("block:01a2099a-d844-7878-94f1-c3840ee91674"));
    try std.testing.expectEqualStrings("t", blockTag("block:t"));
    try std.testing.expectEqualStrings("abcd", blockTag("xyzabcd"));
}
