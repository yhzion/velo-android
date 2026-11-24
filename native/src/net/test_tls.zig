//! Host tests for the TLS certificate checks (`TlsClient.zig` through `Conn.open`): the
//! chain walk and the host name match. Each case serves one chain with
//! `openssl s_server` on 127.0.0.1 (or ::1); testdata/tls_chains.sh makes the chains at
//! test time (needs `openssl` and `python3` on PATH).
const std = @import("std");
const Conn = @import("Conn.zig").Conn;
const ScratchDir = @import("test_net.zig").ScratchDir;

const testing = std.testing;
const gpa = testing.allocator;
const io = testing.io;

const chains_script = @embedFile("testdata/tls_chains.sh");
const two_certificates_script = @embedFile("testdata/tls_two_certificates.py");

/// Runs tls_chains.sh into `dir`.
fn makeChains(dir: []const u8) !void {
    const result = try std.process.run(gpa, io, .{ .argv = &.{ "sh", "-c", chains_script, "sh", dir } });
    defer gpa.free(result.stdout);
    defer gpa.free(result.stderr);
    if (!result.term.success()) {
        std.debug.print("tls_chains.sh failed ({f}):\n{s}\n", .{ result.term, result.stderr });
        return error.ChainsScriptFailed;
    }
}

/// A server for one handshake: `openssl s_server` on `case.listen` (127.0.0.1 or [::1]),
/// presenting `case.name`.pem (and `case.name`.issuers.pem after it when that file
/// exists), or tls_two_certificates.py presenting `case.name`.pem.
const Server = struct {
    child: std.process.Child,
    port: u16,

    fn start(dir: []const u8, case: Case) !Server {
        const name = case.name;
        var cert_buf: [128]u8 = undefined;
        var key_buf: [128]u8 = undefined;
        var issuers_buf: [128]u8 = undefined;
        var accept_buf: [64]u8 = undefined;
        const accept = try std.fmt.bufPrint(&accept_buf, "{s}:0", .{case.listen});
        const cert = try std.fmt.bufPrint(&cert_buf, "{s}/{s}.pem", .{ dir, name });
        const key = try std.fmt.bufPrint(&key_buf, "{s}/{s}.key", .{ dir, name });
        const issuers = try std.fmt.bufPrint(&issuers_buf, "{s}/{s}.issuers.pem", .{ dir, name });
        const has_issuers = if (std.Io.Dir.accessAbsolute(io, issuers, .{})) true else |_| false;

        var argv_buf: [16][]const u8 = undefined;
        var argv: std.ArrayList([]const u8) = .initBuffer(&argv_buf);
        switch (case.server) {
            .openssl => {
                argv.appendSliceAssumeCapacity(&.{
                    "openssl", "s_server", "-accept", accept, "-naccept", "1", "-cert", cert, "-key", key,
                });
                if (has_issuers) argv.appendSliceAssumeCapacity(&.{ "-cert_chain", issuers });
            },
            .two_certificates => argv.appendSliceAssumeCapacity(&.{ "python3", "-c", two_certificates_script, cert }),
        }

        var child = try std.process.spawn(io, .{
            .argv = argv.items,
            // Held open until `stop`: s_server ends the connection at end of input.
            .stdin = .pipe,
            .stdout = .pipe,
            .stderr = .ignore,
        });
        errdefer child.kill(io);

        // s_server prints "ACCEPT 127.0.0.1:PORT" (or "ACCEPT [::1]:PORT") once it listens.
        var buffer: [256]u8 = undefined;
        var reader = child.stdout.?.readerStreaming(io, &buffer);
        while (try reader.interface.takeDelimiter('\n')) |line| {
            if (std.mem.startsWith(u8, line, "ACCEPT ")) {
                const colon = std.mem.lastIndexOfScalar(u8, line, ':') orelse continue;
                return .{ .child = child, .port = try std.fmt.parseInt(u16, line[colon + 1 ..], 10) };
            }
        }
        return error.ServerDidNotListen;
    }

    fn stop(self: *Server) void {
        self.child.kill(io);
    }
};

const Case = struct {
    /// The chain the server presents (tls_chains.sh).
    name: []const u8,
    /// The trust store: a subdirectory of the chains directory.
    ca: []const u8 = "ca",
    host: []const u8 = "localhost",
    /// Where the server listens.
    listen: []const u8 = "127.0.0.1",
    server: enum { openssl, two_certificates } = .openssl,
    /// null: the handshake succeeds.
    err: ?anyerror,
    why: []const u8,
};

const cases = [_]Case{
    .{ .name = "valid", .err = null, .why = "root > CA with pathlen 0 > server" },
    .{ .name = "valid", .host = "127.0.0.1", .err = null, .why = "an IP locator matches an iPAddress name" },
    .{ .name = "self", .ca = "ca-self", .err = null, .why = "a server certificate trusted as itself" },
    .{ .name = "forged", .err = error.TlsCertificateIssuerNotCa, .why = "signed with the key of a server certificate (CA:FALSE)" },
    .{ .name = "nobc", .err = error.TlsCertificateIssuerNotCa, .why = "signed by a certificate without basicConstraints" },
    .{ .name = "pathlen", .err = error.TlsCertificatePathLenExceeded, .why = "a CA with pathlen 0 signed another CA" },
    .{ .name = "nocertsign", .err = error.TlsCertificateIssuerKeyUsage, .why = "the issuer's keyUsage lacks keyCertSign" },
    .{ .name = "anchor_pathlen", .ca = "ca-pathlen0", .err = error.TlsCertificatePathLenExceeded, .why = "a root with pathlen 0 signed another CA" },
    .{ .name = "forged_only", .ca = "ca-leaf", .err = error.TlsCertificateIssuerNotCa, .why = "the trusted certificate that signed it is CA:FALSE" },
    .{ .name = "ip_wild", .host = "127.0.0.1", .err = error.CertificateHostMismatch, .why = "dNSName *.0.0.1 for an IP locator" },
    .{ .name = "ip_dns", .host = "127.0.0.1", .err = error.CertificateHostMismatch, .why = "dNSName 127.0.0.1 for an IP locator" },
    .{ .name = "ip_cn", .host = "127.0.0.1", .err = error.CertificateHostMismatch, .why = "common name 127.0.0.1, no subjectAltName" },
    .{ .name = "direct", .err = null, .why = "a root signs the server's certificate directly" },
    .{ .name = "valid6", .host = "[::1]", .listen = "[::1]", .err = null, .why = "an IPv6 locator matches an iPAddress name" },
    .{ .name = "v1_root", .ca = "ca-v1root", .err = null, .why = "a self-signed v1 root signs (OpenSSL's V1_ROOT)" },
    .{ .name = "v1_signed", .ca = "ca-v1signed", .err = error.TlsCertificateIssuerNotCa, .why = "a trusted v1 certificate that is not self-signed signs" },
    .{ .name = "rollover", .err = null, .why = "a self-issued intermediate under a pathlen 0 CA is not counted (RFC 5280 6.1.4(l))" },
    .{ .name = "nc_ok", .err = null, .why = "names within an intermediate's nameConstraints" },
    .{ .name = "nc_outside", .err = error.TlsCertificateNameConstraint, .why = "a dNSName outside the permitted subtree" },
    .{ .name = "nc_ip", .host = "127.0.0.1", .err = error.TlsCertificateNameConstraint, .why = "an iPAddress outside the permitted range" },
    .{ .name = "nc_excluded", .err = error.TlsCertificateNameConstraint, .why = "a dNSName in an excluded subtree" },
    .{ .name = "nc_email", .err = error.TlsCertificateNameConstraintUnsupported, .why = "an email constraint over a certificate with an email name" },
    .{ .name = "nc_anchor", .ca = "ca-nc", .err = null, .why = "a name-constrained root, as the sandbox test CA, signs a name within it" },
    .{ .name = "nc_anchor_outside", .ca = "ca-nc", .err = error.TlsCertificateNameConstraint, .why = "a name-constrained root signs a name outside it" },
    .{ .name = "san_mistagged", .err = error.CertificateFieldHasWrongDataType, .why = "a dNSName with another tag, which the host match would take" },
    .{ .name = "nc_wildcard", .err = error.TlsCertificateNameConstraint, .why = "a wildcard name that covers an excluded name" },
    .{ .name = "nc_dir_case", .err = error.TlsCertificateNameConstraint, .why = "a subject in other case than an excluded directoryName" },
    .{ .name = "eku_client", .err = error.TlsCertificateExtendedKeyUsage, .why = "a server certificate for TLS clients only" },
    .{ .name = "eku_any", .err = null, .why = "anyExtendedKeyUsage allows a TLS server" },
    .{ .name = "eku_none", .err = null, .why = "no extendedKeyUsage puts no limit on the use (RFC 5280, Android)" },
    .{ .name = "crit_leaf", .err = error.TlsCertificateUnknownCriticalExtension, .why = "an unknown critical extension in the server's certificate" },
    .{ .name = "valid", .server = .two_certificates, .err = error.TlsUnexpectedMessage, .why = "a second Certificate message" },
    .{ .name = "crit_inter", .err = error.TlsCertificateUnknownCriticalExtension, .why = "an unknown critical extension in an intermediate" },
};

/// One handshake against `case`'s server; the error, or null when it connected.
fn handshake(dir: []const u8, case: Case) !?anyerror {
    var server = try Server.start(dir, case);
    defer server.stop();
    var ca_buf: [128]u8 = undefined;
    var locator_buf: [64]u8 = undefined;
    const ca_dir = try std.fmt.bufPrint(&ca_buf, "{s}/{s}", .{ dir, case.ca });
    const locator = try std.fmt.bufPrint(&locator_buf, "https://{s}:{d}", .{ case.host, server.port });
    const conn = Conn.open(gpa, io, locator, .{ .ca_dirs = &.{ca_dir} }) catch |err| return @as(?anyerror, err);
    conn.deinit();
    return null;
}

test "TLS: only CA certificates within their path length and name constraints sign, for TLS servers, and IP locators match IP names only" {
    var scratch: ScratchDir = .{};
    try scratch.init();
    defer scratch.deinit();
    try makeChains(scratch.path());

    var failed: usize = 0;
    for (cases) |case| {
        const got = try handshake(scratch.path(), case);
        const ok = if (case.err) |want| got != null and got.? == want else got == null;
        if (!ok) {
            failed += 1;
            std.debug.print("FAIL {s} via https://{s} ({s}): want {s}, got {s}\n", .{
                case.name,
                case.host,
                case.why,
                if (case.err) |e| @errorName(e) else "connected",
                if (got) |e| @errorName(e) else "connected",
            });
        }
    }
    try testing.expectEqual(@as(usize, 0), failed);
}
