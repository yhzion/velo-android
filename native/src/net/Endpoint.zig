//! Server locators as the desktop app accepts them: `https` (any port, DNS name or IP),
//! `http`, a Unix socket path, and `ssh`. Parsing is pure and depends on std only.
//! Whether a kind can be connected to is the caller's decision, not the parser's.
const std = @import("std");

pub const Kind = enum { https, http, unix, ssh };

pub const ParseError = error{
    Empty,
    Whitespace,
    Password,
    NoHost,
    NoUser,
    NotAbsolute,
    UnsupportedScheme,
    Malformed,
};

pub const Endpoint = struct {
    kind: Kind,
    /// A slice of the input. IPv6 addresses come without brackets.
    host: []const u8 = "",
    /// 443 / 80 / 22 when the locator has none; 0 for unix.
    port: u16 = 0,
    /// unix only, verbatim.
    path: []const u8 = "",
    /// ssh only.
    user: []const u8 = "",

    /// Room for the longest `hostHeader`: 253-byte name or 45-byte IPv6 in brackets, plus `:65535`.
    pub const max_host_header = 270;

    /// `host`, `host:port` (port only when not the default), `[v6]:port`; "localhost" for
    /// unix and ssh (whose stream ends at the server's Unix socket on the remote host).
    pub fn hostHeader(self: Endpoint, buf: *[max_host_header]u8) []const u8 {
        if (self.kind == .unix or self.kind == .ssh) return "localhost";
        var w: std.Io.Writer = .fixed(buf);
        const v6 = std.mem.findScalar(u8, self.host, ':') != null;
        if (v6) w.writeByte('[') catch unreachable;
        w.writeAll(self.host) catch unreachable;
        if (v6) w.writeByte(']') catch unreachable;
        if (self.port != defaultPort(self.kind)) w.print(":{d}", .{self.port}) catch unreachable;
        return w.buffered();
    }

    /// Same server: kind, host (ASCII case-insensitive), port, path, user.
    pub fn same(a: Endpoint, b: Endpoint) bool {
        return a.kind == b.kind and
            std.ascii.eqlIgnoreCase(a.host, b.host) and
            a.port == b.port and
            std.mem.eql(u8, a.path, b.path) and
            std.mem.eql(u8, a.user, b.user);
    }
};

/// `host:port` as Go's `net.JoinHostPort` writes it, NUL-terminated: for the tailnet
/// dialer, which splits it again with `net.SplitHostPort`.
/// IPv6 hosts go in brackets: `fd7a::1:8443` would not split.
pub fn joinHostPort(buf: []u8, host: []const u8, port: u16) error{NoSpaceLeft}![:0]u8 {
    if (std.mem.findScalar(u8, host, ':') != null) {
        return std.fmt.bufPrintSentinel(buf, "[{s}]:{d}", .{ host, port }, 0);
    }
    return std.fmt.bufPrintSentinel(buf, "{s}:{d}", .{ host, port }, 0);
}

fn defaultPort(kind: Kind) u16 {
    return switch (kind) {
        .https => 443,
        .http => 80,
        .ssh => 22,
        .unix => 0,
    };
}

const max_name = 253;
const max_v6 = 45;

/// Parse one locator. The result points into `text`.
pub fn parse(text: []const u8) ParseError!Endpoint {
    const trimmed = std.mem.trim(u8, text, " \t\r\n");
    if (trimmed.len == 0) return error.Empty;
    if (trimmed[0] == '/') return .{ .kind = .unix, .path = trimmed };

    var kind: Kind = .https;
    var rest = trimmed;
    if (std.mem.find(u8, trimmed, "://")) |at| {
        const scheme = trimmed[0..at];
        rest = trimmed[at + 3 ..];
        if (std.ascii.eqlIgnoreCase(scheme, "https")) {
            kind = .https;
        } else if (std.ascii.eqlIgnoreCase(scheme, "http")) {
            kind = .http;
        } else if (std.ascii.eqlIgnoreCase(scheme, "ssh")) {
            kind = .ssh;
        } else if (std.ascii.eqlIgnoreCase(scheme, "unix")) {
            if (rest.len == 0 or rest[0] != '/') return error.NotAbsolute;
            return .{ .kind = .unix, .path = rest };
        } else {
            return error.UnsupportedScheme;
        }
    }
    if (std.mem.findAny(u8, trimmed, " \t\r\n") != null) return error.Whitespace;

    // Path, query and fragment mean nothing for http(s); ssh takes none.
    var authority = rest;
    if (std.mem.findAny(u8, rest, "/?#")) |end| {
        if (kind == .ssh) return error.Malformed;
        authority = rest[0..end];
    }

    var user: []const u8 = "";
    if (std.mem.findScalar(u8, authority, '@')) |at| {
        const info = authority[0..at];
        if (std.mem.findScalar(u8, info, ':') != null) return error.Password;
        if (kind != .ssh) return error.Malformed;
        user = info;
        authority = authority[at + 1 ..];
    }
    if (kind == .ssh and user.len == 0) return error.NoUser;

    var host: []const u8 = undefined;
    var port_text: ?[]const u8 = null;
    if (authority.len > 0 and authority[0] == '[') {
        const close = std.mem.findScalar(u8, authority, ']') orelse return error.Malformed;
        host = authority[1..close];
        const tail = authority[close + 1 ..];
        if (tail.len > 0) {
            if (tail[0] != ':') return error.Malformed;
            port_text = tail[1..];
        }
        if (host.len == 0) return error.NoHost;
        if (!validIpv6(host)) return error.Malformed;
    } else {
        if (std.mem.findScalar(u8, authority, ':')) |colon| {
            host = authority[0..colon];
            port_text = authority[colon + 1 ..];
        } else {
            host = authority;
        }
        if (host.len == 0) return error.NoHost;
        if (!validName(host)) return error.Malformed;
    }

    const port = if (port_text) |p| try parsePort(p) else defaultPort(kind);
    return .{ .kind = kind, .host = host, .port = port, .user = user };
}

fn parsePort(text: []const u8) ParseError!u16 {
    if (text.len == 0 or text.len > 5) return error.Malformed;
    for (text) |ch| if (!std.ascii.isDigit(ch)) return error.Malformed;
    const port = std.fmt.parseInt(u16, text, 10) catch return error.Malformed;
    if (port == 0) return error.Malformed;
    return port;
}

/// Letters, digits, `.`, `-`, `_`: what the core accepted before locators. Covers dotted IPv4.
fn validName(host: []const u8) bool {
    if (host.len > max_name) return false;
    for (host) |ch| {
        if (!std.ascii.isAlphanumeric(ch) and ch != '.' and ch != '-' and ch != '_') return false;
    }
    return true;
}

fn validIpv6(host: []const u8) bool {
    if (host.len > max_v6) return false;
    if (std.mem.findScalar(u8, host, ':') == null) return false;
    for (host) |ch| {
        if (!std.ascii.isHex(ch) and ch != ':' and ch != '.') return false;
    }
    return true;
}
