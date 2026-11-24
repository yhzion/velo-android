//! libvelocore build.
//!
//!   zig build -Doptimize=ReleaseFast -Dtarget=aarch64-linux-android.30 \
//!       --prefix OUT --prefix-lib-dir .          -> OUT/libvelocore.so (run by Gradle task zigCore)
//!   zig build test                                -> host unit tests
//!   zig build probe -- LOCATOR [session]          -> host probe against a live Rex server
//!       (LOCATOR: https://HOST[:PORT], http://HOST[:PORT], a bare host name, or a Unix socket path)
//!
//! Named modules (see docs/native-core.md):
//!   c      host translate-c of src/c.h (libghostty-vt API), imported by `pane`
//!   pane   root src/term/Pane.zig
//!   jni_c  host translate-c of the NDK jni.h, imported by src/jni.zig and src/sys/*
//! Core (src/rex/Core.zig) is not a named module: a module can only import files below
//! the directory of its root file, and Core reaches ../net. Every root that uses it
//! (src/jni.zig, src/probe.zig, src/tests.zig) lives in src/ and imports it by path.
const std = @import("std");
const builtin = @import("builtin");

pub fn build(b: *std.Build) !void {
    const target = b.standardTargetOptions(.{});
    const optimize = b.standardOptimizeOption(.{});
    const host = b.graph.host;

    // libghostty-vt for the host: headers for translate-c, static library for host steps.
    const ghostty_host = ghosttyDep(b, host, optimize);

    // The ghostty headers only use fixed-width ints, size_t and bool, so one host
    // translation serves every target. Translating for Android does not work: NDK
    // headers #include_next into the host's glibc headers.
    const c_translate = b.addTranslateC(.{
        .root_source_file = b.path("src/c.h"),
        .target = host,
        .optimize = optimize,
        .link_libc = true,
    });
    c_translate.addIncludePath(ghostty_host.path("include"));
    c_translate.defineCMacro("GHOSTTY_STATIC", null);
    const c_source = c_translate.getOutput();

    // JNI types: host translation of the NDK's jni.h. It includes only <stdarg.h> and
    // <stdint.h>; the NDK directory is searched after the host's so those two come
    // from the host toolchain (a plain -I makes them resolve to bionic's and fail).
    const ndk = Ndk.find(b);
    const jni_source: ?std.Build.LazyPath = if (ndk) |n| jni: {
        const translate = b.addTranslateC(.{
            .root_source_file = b.addWriteFiles().add("jni_c.h", "#include <jni.h>\n"),
            .target = host,
            .optimize = optimize,
            .link_libc = true,
        });
        translate.addAfterIncludePath(.{ .cwd_relative = n.includeDir(b) });
        break :jni translate.getOutput();
    } else null;

    // ---- Default step: the Android shared library ----
    if (!target.result.abi.isAndroid()) {
        b.getInstallStep().dependOn(&b.addFail(
            "libvelocore is an Android library: pass -Dtarget=aarch64-linux-android.30 " ++
                "(host steps: `zig build test`, `zig build probe -- LOCATOR [session]`)",
        ).step);
    } else if (ndk) |n| {
        const mods: Modules = .init(b, c_source);
        const mod = b.createModule(.{
            .root_source_file = b.path("src/jni.zig"),
            .target = target,
            .optimize = optimize,
            .link_libc = true,
            .pic = true,
            // The APK carries this file as is: keep debug info out of release builds.
            .strip = optimize != .debug,
        });
        mod.addImport("c", mods.c);
        mod.addImport("pane", mods.pane);
        mod.addImport("jni_c", b.createModule(.{ .root_source_file = jni_source.? }));

        // Static libghostty-vt (the build system also links the simdutf and highway
        // archives it depends on), so the .so has no libghostty-vt dependency.
        mod.linkLibrary(ghosttyDep(b, target, optimize).artifact("ghostty-vt-static"));

        const api_lib_dir = n.apiLibDir(b, target.result) orelse return error.AndroidNdkUnsupportedTarget;
        mod.addLibraryPath(.{ .cwd_relative = api_lib_dir });
        for ([_][]const u8{ "EGL", "GLESv3", "android", "jnigraphics", "log", "m" }) |name| {
            mod.linkSystemLibrary(name, .{ .use_pkg_config = .no });
        }

        const lib = b.addLibrary(.{
            .name = "velocore",
            .linkage = .dynamic,
            .root_module = mod,
            // LLD applies the version script; Zig's own ELF linker ignores it.
            .use_llvm = true,
            .use_lld = true,
        });
        lib.setLibCFile(n.libcFile(b, target.result).?);
        // 16 KB pages, required for Android 15+.
        lib.link_z_max_page_size = 16384;
        // Export only the JNI entry points, not the ghostty_* C API linked in statically.
        lib.setVersionScript(b.addWriteFiles().add("velocore.map",
            \\{
            \\  global:
            \\    JNI_OnLoad;
            \\    Java_io_github_yhzion_velo_core_VeloCore_*;
            \\    Java_io_github_yhzion_velo_TailnetNative_*;
            \\    Java_io_github_yhzion_velo_SshNative_*;
            \\  local:
            \\    *;
            \\};
            \\
        ));
        b.getInstallStep().dependOn(&b.addInstallArtifact(lib, .{
            .dest_dir = .{ .override = .lib },
        }).step);
    } else {
        b.getInstallStep().dependOn(&b.addFail(
            "Android NDK not found: set ANDROID_HOME (an SDK with an ndk/ directory) or ANDROID_NDK_HOME",
        ).step);
    }

    // ---- Host steps ----
    const mods: Modules = .init(b, c_source);
    const vt_host = ghostty_host.artifact("ghostty-vt-static");

    const test_step = b.step("test", "Run the host unit tests");
    // `zig build test -Dtest-filter=tunnel` runs only the tests whose names contain it.
    const test_filters: []const []const u8 = if (b.option([]const u8, "test-filter", "Run only the tests whose names contain this")) |f| b.dupeStrings(&.{f}) else &.{};
    {
        // term/ alone: its files import "c" directly.
        const mod = b.createModule(.{
            .root_source_file = b.path("src/term/test_pane.zig"),
            .target = host,
            .optimize = optimize,
            .link_libc = true,
        });
        mod.addImport("c", mods.c);
        mod.linkLibrary(vt_host);
        const exe = b.addTest(.{ .name = "test-term", .root_module = mod, .filters = test_filters });
        test_step.dependOn(&b.addRunArtifact(exe).step);
    }
    {
        // net/ and rex/ (model, protocol, StreamFilter, Core, DataStream) through src/tests.zig.
        const mod = b.createModule(.{
            .root_source_file = b.path("src/tests.zig"),
            .target = host,
            .optimize = optimize,
            .link_libc = true,
        });
        mod.addImport("pane", mods.pane);
        // Tests that encode a snapshot as the server does (DataStream's consumer).
        mod.addImport("c", mods.c);
        mod.linkLibrary(vt_host);
        const exe = b.addTest(.{ .name = "test-core", .root_module = mod, .filters = test_filters });
        test_step.dependOn(&b.addRunArtifact(exe).step);
    }
    if (jni_source) |source| {
        // The string conversions in sys/jni.zig, and `CText` against a fake JNIEnv. Needs
        // the NDK only for jni.h.
        const mod = b.createModule(.{
            .root_source_file = b.path("src/sys/test_jni.zig"),
            .target = host,
            .optimize = optimize,
            .link_libc = true,
        });
        mod.addImport("jni_c", b.createModule(.{ .root_source_file = source }));
        const exe = b.addTest(.{ .name = "test-jni", .root_module = mod, .filters = test_filters });
        test_step.dependOn(&b.addRunArtifact(exe).step);
    }

    const probe_step = b.step("probe", "Run the host probe: zig build probe -- LOCATOR [session]");
    {
        const mod = b.createModule(.{
            .root_source_file = b.path("src/probe.zig"),
            .target = host,
            .optimize = optimize,
            .link_libc = true,
        });
        mod.addImport("pane", mods.pane);
        mod.linkLibrary(vt_host);
        const exe = b.addExecutable(.{ .name = "probe", .root_module = mod });
        const run = b.addRunArtifact(exe);
        run.addPassthruArgs();
        probe_step.dependOn(&run.step);
    }
}

/// The libghostty-vt package configured to build only the library. We link the
/// artifact "ghostty-vt-static".
fn ghosttyDep(
    b: *std.Build,
    target: std.Build.ResolvedTarget,
    optimize: std.builtin.Optimize,
) *std.Build.Dependency {
    return b.dependency("ghostty", .{
        .target = target,
        .optimize = optimize,
        .@"emit-lib-vt" = true,
    });
}

/// The modules `c` and `pane` (which imports `c`). Target and optimize mode are
/// inherited from the importing root module, so the host steps share one set and the
/// Android library gets its own.
const Modules = struct {
    c: *std.Build.Module,
    pane: *std.Build.Module,

    fn init(b: *std.Build, c_source: std.Build.LazyPath) Modules {
        const c = b.createModule(.{ .root_source_file = c_source });
        const pane = b.createModule(.{ .root_source_file = b.path("src/term/Pane.zig") });
        pane.addImport("c", c);
        return .{ .c = c, .pane = pane };
    }
};

/// The Android NDK. Zig has no bionic of its own, so the NDK sysroot provides libc and
/// the system libraries. Same lookup as libghostty-vt's pkg/android-ndk (that helper
/// is not importable from here), so both builds use the same NDK: `ANDROID_NDK_HOME`,
/// else the newest NDK under `ANDROID_HOME` / `ANDROID_SDK_ROOT`, else under
/// `~/Android/Sdk`.
const Ndk = struct {
    sysroot: []const u8,

    fn find(b: *std.Build) ?Ndk {
        const host_tag = switch (builtin.target.os.tag) {
            .linux => "linux-x86_64",
            .macos => "darwin-x86_64",
            .windows => "windows-x86_64",
            else => return null,
        };
        const root = findRoot(b) orelse return null;
        return .{ .sysroot = b.pathJoin(&.{ root, "toolchains", "llvm", "prebuilt", host_tag, "sysroot" }) };
    }

    fn includeDir(ndk: Ndk, b: *std.Build) []const u8 {
        return b.pathJoin(&.{ ndk.sysroot, "usr", "include" });
    }

    /// sysroot/usr/lib/<triple>/<api>: crt objects, libc.so and the system libraries.
    fn apiLibDir(ndk: Ndk, b: *std.Build, target: std.Target) ?[]const u8 {
        return b.pathJoin(&.{
            ndk.sysroot,
            "usr",
            "lib",
            triple(target) orelse return null,
            b.fmt("{d}", .{target.os.version_range.linux.android}),
        });
    }

    /// The `--libc` file that points Zig at bionic.
    fn libcFile(ndk: Ndk, b: *std.Build, target: std.Target) ?std.Build.LazyPath {
        const include_dir = ndk.includeDir(b);
        const api_lib_dir = ndk.apiLibDir(b, target) orelse return null;
        // Every key must be present, and cc_dir must be non-empty for Linux targets.
        return b.addWriteFiles().add("libc.txt", b.fmt(
            \\include_dir={s}
            \\sys_include_dir={s}
            \\cc_dir={s}
            \\crt_dir={s}
            \\msvc_lib_dir=
            \\kernel32_lib_dir=
            \\darwin_sdk_dir=
            \\
        , .{ include_dir, b.pathJoin(&.{ include_dir, triple(target).? }), api_lib_dir, api_lib_dir }));
    }

    fn triple(target: std.Target) ?[]const u8 {
        return switch (target.cpu.arch) {
            .arm => "arm-linux-androideabi",
            .aarch64 => "aarch64-linux-android",
            .x86 => "i686-linux-android",
            .x86_64 => "x86_64-linux-android",
            else => null,
        };
    }

    fn findRoot(b: *std.Build) ?[]const u8 {
        const env = &b.graph.environ_map;
        if (env.get("ANDROID_NDK_HOME")) |path| {
            if (path.len == 0) return null;
            var dir = std.Io.Dir.openDirAbsolute(b.graph.io, path, .{}) catch return null;
            dir.close(b.graph.io);
            return path;
        }
        for ([_][]const u8{ "ANDROID_HOME", "ANDROID_SDK_ROOT" }) |name| {
            const sdk = env.get(name) orelse continue;
            if (sdk.len == 0) continue;
            if (latestIn(b, sdk)) |root| return root;
        }
        const home = env.get(if (builtin.target.os.tag == .windows) "LOCALAPPDATA" else "HOME") orelse return null;
        return latestIn(b, b.pathJoin(&.{ home, switch (builtin.target.os.tag) {
            .linux, .windows => "Android/Sdk",
            .macos => "Library/Android/Sdk",
            else => return null,
        } }));
    }

    /// The NDK with the highest version under `<sdk>/ndk`.
    fn latestIn(b: *std.Build, sdk: []const u8) ?[]const u8 {
        const io = b.graph.io;
        const ndk_dir = b.pathJoin(&.{ sdk, "ndk" });
        var dir = std.Io.Dir.openDirAbsolute(io, ndk_dir, .{ .iterate = true }) catch return null;
        defer dir.close(io);

        var best: ?struct { name: []const u8, version: std.SemanticVersion } = null;
        var it = dir.iterate();
        while (it.next(io) catch null) |entry| {
            if (entry.kind != .directory) continue;
            const version = std.SemanticVersion.parse(entry.name) catch continue;
            if (best) |cur| {
                if (version.order(cur.version) != .gt) continue;
            }
            best = .{ .name = b.dupe(entry.name), .version = version };
        }
        return b.pathJoin(&.{ ndk_dir, (best orelse return null).name });
    }
};
