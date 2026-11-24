//! Links.find: the link in a line of terminal text at a byte offset. Part of the term tests
//! (imported by test_pane.zig).
const std = @import("std");
const Links = @import("Links.zig");

/// The link around the first occurrence of `tap` in `text`.
fn at(text: []const u8, tap: []const u8) ?Links.Match {
    const i = std.mem.indexOf(u8, text, tap) orelse @panic("tap text not in line");
    return Links.find(text, i);
}

fn expectLink(text: []const u8, tap: []const u8, kind: Links.Kind, target: []const u8) !void {
    const m = at(text, tap) orelse return error.NoLink;
    try std.testing.expectEqual(kind, m.kind);
    try std.testing.expectEqualStrings(target, text[m.start..m.target_end]);
}

fn expectNone(text: []const u8, tap: []const u8) !void {
    try std.testing.expectEqual(@as(?Links.Match, null), at(text, tap));
}

test "a web URL ends before the sentence's closing punctuation" {
    try expectLink("see https://example.com/a?b=1. next", "example", .url, "https://example.com/a?b=1");
    try expectLink("go to https://example.com/a, then", "https", .url, "https://example.com/a");
    try expectNone("see https://example.com/a?b=1. next", ". next");
}

test "a URL keeps its own balanced parentheses and drops an unbalanced closer" {
    try expectLink("(https://en.wikipedia.org/wiki/Foo_(bar))", "wiki/", .url, "https://en.wikipedia.org/wiki/Foo_(bar)");
    try expectLink("[docs](https://x.com/y)", "x.com", .url, "https://x.com/y");
}

test "a URL starts at its scheme, not at the text glued before it" {
    try expectLink("foo:https://x.com/a", "x.com", .url, "https://x.com/a");
    try expectLink("<https://x.com/a>", "x.com", .url, "https://x.com/a");
    try expectLink("\"https://x.com/a\"", "x.com", .url, "https://x.com/a");
}

test "mail links and other schemes are URLs" {
    try expectLink("write to mailto:a@example.com today", "a@example", .url, "mailto:a@example.com");
    try expectLink("ssh://me@host:22/x", "host", .url, "ssh://me@host:22/x");
    try expectLink("vscode://file/home/me/a.kt", "file/", .url, "vscode://file/home/me/a.kt");
}

test "a URL to this machine's loopback is a localhost link" {
    try expectLink("  ➜  Local:   http://localhost:5173/", "localhost", .localhost, "http://localhost:5173/");
    try expectLink("https://127.0.0.1:8443/x", "127", .localhost, "https://127.0.0.1:8443/x");
    try expectLink("http://[::1]:3000", "::1", .localhost, "http://[::1]:3000");
    try expectLink("http://0.0.0.0:8000/", "0.0.0.0", .localhost, "http://0.0.0.0:8000/");
    try expectLink("http://app.localhost:3000/", "app", .localhost, "http://app.localhost:3000/");
    try expectLink("http://localhostess.com/", "ess", .url, "http://localhostess.com/");
}

test "a bare loopback address with a port is a localhost link without its scheme" {
    const text = "Serving HTTP on 0.0.0.0 port 8000 (http://0.0.0.0:8000/) ... listening on localhost:3000.";
    const m = at(text, "localhost:3000") orelse return error.NoLink;
    try std.testing.expectEqual(Links.Kind.localhost, m.kind);
    try std.testing.expect(m.scheme_missing);
    try std.testing.expectEqualStrings("localhost:3000", text[m.start..m.target_end]);
    try expectLink(text, "0.0.0.0:8000", .localhost, "http://0.0.0.0:8000/");
    try std.testing.expect(!at(text, "0.0.0.0:8000").?.scheme_missing);
    try expectNone(text, "0.0.0.0 port");
}

test "file URLs are file links" {
    try expectLink("file:///home/me/shot.png", "shot", .file, "file:///home/me/shot.png");
}

test "paths are file links" {
    try expectLink("wrote /tmp/out/report.pdf", "report", .file, "/tmp/out/report.pdf");
    try expectLink("see ~/shots/a.png", "shots", .file, "~/shots/a.png");
    try expectLink("run ./build.sh now", "build", .file, "./build.sh");
    try expectLink("../up/x.txt", "up", .file, "../up/x.txt");
    try expectLink("M  app/src/main/Foo.kt", "Foo", .file, "app/src/main/Foo.kt");
    try expectLink("README.md  build.zig  docs", "build.zig", .file, "build.zig");
}

test "a path loses quotes, an option name and closing punctuation" {
    try expectLink("open \"src/a.kt\".", "src", .file, "src/a.kt");
    try expectLink("--out=dist/app.js", "dist", .file, "dist/app.js");
    try expectLink("(see docs/x.md)", "docs", .file, "docs/x.md");
}

test "a path's line and column are kept apart from the path" {
    const text = "src/app.kt:12:3: error: nope";
    const m = at(text, "app") orelse return error.NoLink;
    try std.testing.expectEqual(Links.Kind.file, m.kind);
    try std.testing.expectEqualStrings("src/app.kt", text[m.start..m.target_end]);
    try std.testing.expectEqualStrings("src/app.kt:12:3", text[m.start..m.end]);
    try std.testing.expectEqual(@as(u32, 12), m.line);
    try std.testing.expectEqual(@as(u32, 3), m.col);
    const n = at("main.zig:7 ok", "main") orelse return error.NoLink;
    try std.testing.expectEqual(@as(u32, 7), n.line);
    try std.testing.expectEqual(@as(u32, 0), n.col);
}

test "words, numbers and versions are not links" {
    try expectNone("hello world", "hello");
    try expectNone("version 1.2.3 ok", "1.2");
    try expectNone("ratio 1/2", "1/2");
    try expectNone("e.g. this", "e.g");
    try expectNone("git@github.com:foo/bar.git", "foo");
    try expectNone("a  b", " ");
}

test "text other than ASCII stays in a URL, CJK punctuation ends it" {
    try expectLink("링크 https://ko.wikipedia.org/wiki/한글 입니다", "wiki", .url, "https://ko.wikipedia.org/wiki/한글");
    try expectLink("주소는「https://x.com/a」입니다", "x.com", .url, "https://x.com/a");
}

test "Korean written right after a link is not part of it" {
    try expectLink("https://github.com/a/b에서 확인", "github", .url, "https://github.com/a/b");
    try expectLink("http://localhost:3000에서 열림", "localhost", .localhost, "http://localhost:3000");
    try expectLink("src/app.kt에서 고쳤습니다", "app", .file, "src/app.kt");
    try expectLink("README.md를 보세요", "README", .file, "README.md");
    try expectNone("https://github.com/a/b에서 확인", "에서");
    // A segment that starts with Korean stays: it is the link's own text.
    try expectLink("https://ko.wikipedia.org/wiki/한글?q=가나", "wiki", .url, "https://ko.wikipedia.org/wiki/한글?q=가나");
}
