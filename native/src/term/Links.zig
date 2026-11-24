//! Links in terminal text: what a tap on a line of output points at.
//!
//! `find` reads one logical line (the rows a soft wrap joined) and the byte offset of the
//! tapped cell. It finds the run of characters around the tap that links can be made of,
//! then looks in that run for, in this order: a URL with a scheme, a loopback address with a
//! port and no scheme (`localhost:3000`), and a file path. Closing punctuation of the sentence
//! around a link is not part of it. Nothing here checks that a path exists: the caller asks the
//! machine the terminal runs on.
const std = @import("std");

pub const Kind = enum {
    /// A URL to open as it is: web, mail, or a scheme another app handles.
    url,
    /// An http(s) URL to this machine's loopback, which only the terminal's machine can reach.
    localhost,
    /// A `file:` URL or a path on the terminal's machine.
    file,
};

pub const Match = struct {
    /// Bytes of the link in the line, for marking it: `text[start..end]`.
    start: usize,
    end: usize,
    /// The link's target is `text[start..target_end]`: a path's `:line:col` is left out.
    target_end: usize,
    kind: Kind,
    /// A bare loopback address (`localhost:3000`): the target needs `http://` before it.
    scheme_missing: bool = false,
    /// A path's line and column after it (`a.kt:12:3`); 0 when it has none.
    line: u32 = 0,
    col: u32 = 0,
};

/// The link around the byte `at` of `text`, if any.
pub fn find(text: []const u8, at: usize) ?Match {
    if (at >= text.len) return null;
    const run = runAround(text, at) orelse return null;
    if (findUrl(text, run, at)) |m| return m;
    if (findLoopback(text, run, at)) |m| return m;
    return findPath(text, run, at);
}

/// How a link target given whole (an OSC 8 hyperlink) is opened.
pub fn classify(uri: []const u8) Kind {
    const colon = std.mem.indexOfScalar(u8, uri, ':') orelse return .file;
    const scheme = uri[0..colon];
    if (std.ascii.eqlIgnoreCase(scheme, "file")) return .file;
    if ((std.ascii.eqlIgnoreCase(scheme, "http") or std.ascii.eqlIgnoreCase(scheme, "https")) and
        std.mem.startsWith(u8, uri[colon + 1 ..], "//") and isLoopbackHost(hostOf(uri[colon + 3 ..])))
        return .localhost;
    return .url;
}

const Run = struct { start: usize, end: usize };

/// What a character is to a run of link characters.
const Class = enum { separator, ascii_alnum, ascii_other, letter };

fn classOf(bytes: []const u8) Class {
    if (!linkChar(bytes)) return .separator;
    if (bytes.len > 1) return .letter;
    return if (isAlnum(bytes[0])) .ascii_alnum else .ascii_other;
}

/// The run of link characters that holds `at`, or null when `at` is on a separator. A letter
/// beyond ASCII right after an ASCII letter or digit, or the other way round, also ends a run:
/// Korean writes its particles onto the word before (`https://x.com/a에서`, `README.md를`).
/// After `/`, `?` or `=` such letters stay: they are the link's own text (`/wiki/한글`).
fn runAround(text: []const u8, at: usize) ?Run {
    var start: usize = 0;
    var prev: Class = .separator;
    var i: usize = 0;
    while (i < text.len) {
        const len = std.unicode.utf8ByteSequenceLength(text[i]) catch 1;
        const next = @min(i + len, text.len);
        const class = classOf(text[i..next]);
        const glued = (prev == .ascii_alnum and class == .letter) or (prev == .letter and class == .ascii_alnum);
        if (class == .separator or glued) {
            if (at >= start and at < i) return .{ .start = start, .end = i };
            if (class == .separator) {
                if (at < next) return null;
                start = next;
            } else start = i;
        }
        prev = class;
        i = next;
    }
    return if (at >= start) .{ .start = start, .end = text.len } else null;
}

/// One encoded character that a link can contain. Beyond ASCII only letters count, so the
/// borders and bullets that programs draw around a link end it.
fn linkChar(bytes: []const u8) bool {
    if (bytes.len == 1) return switch (bytes[0]) {
        'A'...'Z', 'a'...'z', '0'...'9' => true,
        '-', '.', '_', '~', ':', '/', '?', '#', '[', ']', '@', '!', '$', '&', '(', ')', '*', '+', ',', ';', '=', '%' => true,
        else => false,
    };
    const cp = std.unicode.utf8Decode(bytes) catch return false;
    return switch (cp) {
        0x00C0...0x024F, // Latin letters with marks
        0x0300...0x036F, // combining marks
        0x0370...0x052F, // Greek, Cyrillic
        0x0590...0x08FF, // Hebrew, Arabic
        0x0900...0x0DFF, // Indic scripts
        0x0E00...0x0EFF, // Thai, Lao
        0x1100...0x11FF, // Hangul jamo
        0x3040...0x30FF, // kana
        0x3130...0x318F, // Hangul compatibility jamo
        0x3400...0x9FFF, // CJK ideographs
        0xAC00...0xD7AF, // Hangul syllables
        => true,
        else => false,
    };
}

fn isAlnum(b: u8) bool {
    return std.ascii.isAlphanumeric(b);
}

/// Sentence punctuation after a link, and closers it did not open: `text[start..end]` without them.
fn trimEnd(text: []const u8, start: usize, end: usize) usize {
    var e = end;
    while (e > start) {
        const last = text[e - 1];
        const drop = switch (last) {
            '.', ',', ';', ':', '!', '?' => true,
            ')' => std.mem.count(u8, text[start..e], ")") > std.mem.count(u8, text[start..e], "("),
            ']' => std.mem.count(u8, text[start..e], "]") > std.mem.count(u8, text[start..e], "["),
            else => false,
        };
        if (!drop) break;
        e -= 1;
    }
    return e;
}

/// Schemes whose URLs have no `//` that still read as links.
const opaque_schemes = [_][]const u8{ "mailto", "tel", "sms", "file", "news", "magnet" };

fn findUrl(text: []const u8, run: Run, at: usize) ?Match {
    var p = run.start;
    while (p < run.end) : (p += 1) {
        if (!std.ascii.isAlphabetic(text[p]) or (p > run.start and isAlnum(text[p - 1]))) continue;
        var colon = p + 1;
        while (colon < run.end and (isAlnum(text[colon]) or text[colon] == '+' or text[colon] == '.' or text[colon] == '-')) colon += 1;
        if (colon >= run.end or text[colon] != ':') continue;
        const scheme = text[p..colon];
        const slashes = std.mem.startsWith(u8, text[colon + 1 .. run.end], "//");
        if (!slashes and !isOpaque(scheme)) continue;
        const body = colon + 1 + @as(usize, if (slashes) 2 else 0);
        const end = trimEnd(text, p, run.end);
        if (end <= body or at < p or at >= end) continue;
        return .{ .start = p, .end = end, .target_end = end, .kind = classify(text[p..end]) };
    }
    return null;
}

fn isOpaque(scheme: []const u8) bool {
    for (opaque_schemes) |s| if (std.ascii.eqlIgnoreCase(scheme, s)) return true;
    return false;
}

/// The host of a URL's authority (`user@host:port/…` → `host`, `[::1]:80` → `::1`).
fn hostOf(authority_and_rest: []const u8) []const u8 {
    const end = std.mem.indexOfAny(u8, authority_and_rest, "/?#") orelse authority_and_rest.len;
    var auth = authority_and_rest[0..end];
    if (std.mem.lastIndexOfScalar(u8, auth, '@')) |i| auth = auth[i + 1 ..];
    if (auth.len > 0 and auth[0] == '[') {
        const close = std.mem.indexOfScalar(u8, auth, ']') orelse return auth[1..];
        return auth[1..close];
    }
    return auth[0 .. std.mem.indexOfScalar(u8, auth, ':') orelse auth.len];
}

fn isLoopbackHost(host: []const u8) bool {
    if (std.ascii.eqlIgnoreCase(host, "localhost") or std.mem.eql(u8, host, "::1") or std.mem.eql(u8, host, "0.0.0.0")) return true;
    if (host.len > ".localhost".len and std.ascii.endsWithIgnoreCase(host, ".localhost")) return true;
    if (!std.mem.startsWith(u8, host, "127.")) return false;
    for (host) |b| if (!std.ascii.isDigit(b) and b != '.') return false;
    return true;
}

const loopback_prefixes = [_][]const u8{ "localhost:", "127.0.0.1:", "0.0.0.0:", "[::1]:" };

fn findLoopback(text: []const u8, run: Run, at: usize) ?Match {
    var p = run.start;
    while (p < run.end) : (p += 1) {
        if (p > run.start and isAlnum(text[p - 1])) continue;
        const prefix = for (loopback_prefixes) |pre| {
            if (std.ascii.startsWithIgnoreCase(text[p..run.end], pre)) break pre;
        } else continue;
        var e = p + prefix.len;
        const digits = e;
        while (e < run.end and std.ascii.isDigit(text[e])) e += 1;
        if (e == digits) continue;
        if (e < run.end and text[e] == '/') e = trimEnd(text, p, run.end);
        if (at < p or at >= e) continue;
        return .{ .start = p, .end = e, .target_end = e, .kind = .localhost, .scheme_missing = true };
    }
    return null;
}

fn findPath(text: []const u8, run: Run, at: usize) ?Match {
    // An option's value or one item of a list: `--out=dist/app.js`, `a.kt,b.kt`.
    var start = run.start;
    var end = run.end;
    var i = run.start;
    while (i < run.end) : (i += 1) {
        if (text[i] != '=' and text[i] != ',') continue;
        if (i < at) start = i + 1 else {
            end = i;
            break;
        }
    }
    while (start < end and (text[start] == '(' or text[start] == '[')) start += 1;
    end = trimEnd(text, start, end);
    if (start >= end or at < start or at >= end) return null;

    var m: Match = .{ .start = start, .end = end, .target_end = end, .kind = .file };
    // `path:line` or `path:line:col`.
    var numbers: [2]u32 = .{ 0, 0 };
    var count: usize = 0;
    var e = end;
    while (count < 2) {
        var d = e;
        while (d > start and std.ascii.isDigit(text[d - 1])) d -= 1;
        if (d == e or d <= start + 1 or text[d - 1] != ':') break;
        numbers[count] = std.fmt.parseInt(u32, text[d..e], 10) catch break;
        count += 1;
        e = d - 1;
    }
    if (count > 0) {
        m.target_end = e;
        m.line = numbers[count - 1];
        m.col = if (count == 2) numbers[0] else 0;
    }
    return if (isPath(text[start..m.target_end])) m else null;
}

fn isPath(path: []const u8) bool {
    if (std.mem.indexOfScalar(u8, path, ':') != null) return false;
    var letter = false;
    for (path) |b| {
        if (std.ascii.isAlphabetic(b) or b >= 0x80) letter = true;
    }
    if (!letter) return false;
    if (std.mem.indexOfScalar(u8, path, '/') != null) return true;
    // A bare name with an extension: `build.zig`, not `e.g` or `1.2`.
    const dot = std.mem.lastIndexOfScalar(u8, path, '.') orelse return false;
    const name = path[0..dot];
    const ext = path[dot + 1 ..];
    if (ext.len == 0 or ext.len > 8) return false;
    var ext_letter = false;
    for (ext) |b| {
        if (!isAlnum(b)) return false;
        if (std.ascii.isAlphabetic(b)) ext_letter = true;
    }
    if (!ext_letter) return false;
    // `.env` is a name; `x.c` is too short to tell from an abbreviation.
    return name.len >= 2 or (name.len == 0 and ext.len >= 2);
}
