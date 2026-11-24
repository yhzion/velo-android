//! Host tests for the locator parser in Endpoint.zig.
const std = @import("std");
const ep = @import("Endpoint.zig");
const Endpoint = ep.Endpoint;
const Kind = ep.Kind;

const testing = std.testing;

const Row = struct {
    input: []const u8,
    kind: Kind,
    host: []const u8 = "",
    port: u16 = 0,
    path: []const u8 = "",
    user: []const u8 = "",
};

test "parse accepts the desktop's locator forms" {
    const rows = [_]Row{
        .{ .input = "https://rex.fixture.example", .kind = .https, .host = "rex.fixture.example", .port = 443 },
        .{ .input = "  HTTPS://Rex.Example.ts.net:8443/ ", .kind = .https, .host = "Rex.Example.ts.net", .port = 8443 },
        .{ .input = "https://rex.fixture.example:443/llms.txt?x=1#y", .kind = .https, .host = "rex.fixture.example", .port = 443 },
        .{ .input = "rex.example.ts.net", .kind = .https, .host = "rex.example.ts.net", .port = 443 },
        .{ .input = "rex.example.ts.net:8443", .kind = .https, .host = "rex.example.ts.net", .port = 8443 },
        .{ .input = "https://192.0.2.29", .kind = .https, .host = "192.0.2.29", .port = 443 },
        .{ .input = "https://[2001:db8::1]:8443", .kind = .https, .host = "2001:db8::1", .port = 8443 },
        .{ .input = "http://127.0.0.1:7890", .kind = .http, .host = "127.0.0.1", .port = 7890 },
        .{ .input = "http://host", .kind = .http, .host = "host", .port = 80 },
        .{ .input = "/tmp/rex.sock", .kind = .unix, .path = "/tmp/rex.sock" },
        .{ .input = "unix:///tmp/rex.sock", .kind = .unix, .path = "/tmp/rex.sock" },
        .{ .input = "/tmp/rex forward/server.sock", .kind = .unix, .path = "/tmp/rex forward/server.sock" },
        .{ .input = "ssh://me@host", .kind = .ssh, .host = "host", .port = 22, .user = "me" },
        .{ .input = "ssh://me@host:2222", .kind = .ssh, .host = "host", .port = 2222, .user = "me" },
    };
    for (rows) |row| {
        const got = ep.parse(row.input) catch |err| {
            std.debug.print("parse({s}) failed: {s}\n", .{ row.input, @errorName(err) });
            return err;
        };
        try testing.expectEqual(row.kind, got.kind);
        try testing.expectEqualStrings(row.host, got.host);
        try testing.expectEqual(row.port, got.port);
        try testing.expectEqualStrings(row.path, got.path);
        try testing.expectEqualStrings(row.user, got.user);
    }
}

test "parse rejects with the desktop's reasons" {
    const rows = [_]struct { input: []const u8, err: ep.ParseError }{
        .{ .input = "", .err = error.Empty },
        .{ .input = "   ", .err = error.Empty },
        .{ .input = "https://a b", .err = error.Whitespace },
        .{ .input = "https://me:pw@host", .err = error.Password },
        .{ .input = "ssh://me:pw@host", .err = error.Password },
        .{ .input = "https://", .err = error.NoHost },
        .{ .input = "https://:443", .err = error.NoHost },
        .{ .input = "ssh://host", .err = error.NoUser },
        .{ .input = "unix://tmp/rex.sock", .err = error.NotAbsolute },
        .{ .input = "ftp://host", .err = error.UnsupportedScheme },
        .{ .input = "wss://host", .err = error.UnsupportedScheme },
        .{ .input = "https://host:0", .err = error.Malformed },
        .{ .input = "https://host:65536", .err = error.Malformed },
        .{ .input = "https://host:x", .err = error.Malformed },
        .{ .input = "https://[::1", .err = error.Malformed },
        .{ .input = "ssh://me@host/path", .err = error.Malformed },
    };
    for (rows) |row| {
        try testing.expectError(row.err, ep.parse(row.input));
    }
}

test "hostHeader omits default ports and brackets IPv6" {
    const rows = [_]struct { input: []const u8, header: []const u8 }{
        .{ .input = "https://h", .header = "h" },
        .{ .input = "https://h:8443", .header = "h:8443" },
        .{ .input = "http://h", .header = "h" },
        .{ .input = "https://[fd7a::1]:8443", .header = "[fd7a::1]:8443" },
        .{ .input = "/tmp/rex.sock", .header = "localhost" },
        // The stream reaches the server's Unix socket through the SSH pool.
        .{ .input = "ssh://me@h:2222", .header = "localhost" },
    };
    for (rows) |row| {
        const e = try ep.parse(row.input);
        var buf: [Endpoint.max_host_header]u8 = undefined;
        try testing.expectEqualStrings(row.header, e.hostHeader(&buf));
    }
}

test "joinHostPort brackets IPv6 hosts so Go can split them again" {
    var buf: [ep.Endpoint.max_host_header + 1]u8 = undefined;
    try testing.expectEqualStrings("rex.fixture.example:443", try ep.joinHostPort(&buf, "rex.fixture.example", 443));
    try testing.expectEqualStrings("192.0.2.29:8443", try ep.joinHostPort(&buf, "192.0.2.29", 8443));
    try testing.expectEqualStrings("[2001:db8::1]:8443", try ep.joinHostPort(&buf, "2001:db8::1", 8443));
    try testing.expectEqualStrings("[::1]:80", try ep.joinHostPort(&buf, "::1", 80));
    const joined = try ep.joinHostPort(&buf, "::1", 80);
    try testing.expectEqual(@as(u8, 0), joined.ptr[joined.len]);
    var small: [8]u8 = undefined;
    try testing.expectError(error.NoSpaceLeft, ep.joinHostPort(&small, "fd7a::1", 8443));
}

test "same compares hosts without case" {
    try testing.expect(Endpoint.same(try ep.parse("https://REX.example"), try ep.parse("rex.example:443")));
    try testing.expect(!Endpoint.same(try ep.parse("/tmp/a.sock"), try ep.parse("/tmp/A.sock")));
}
