//! TLS client for Rex connections: a copy of the Zig 0.17.1 standard library's
//! `lib/std/crypto/tls/Client.zig` with stricter certificate checks. Upstream checks
//! only issuer name, validity and signature along the chain, so a server certificate
//! signed with the key of any other server certificate (CA:FALSE) was accepted.
//!
//! Velo patches (each marked "Velo patch" below):
//!  - Every certificate that signs another one must be a CA: basicConstraints cA TRUE,
//!    its pathLenConstraint respected, keyUsage with keyCertSign when keyUsage is
//!    present (`checkIssuer`). The path length counts the intermediates below that are
//!    not self-issued (RFC 5280 6.1.4(l)). A trusted certificate from the CA directories
//!    that signs the server's chain passes the same check, except that, as in OpenSSL's
//!    check_ca, a self-signed v1 root (V1_ROOT) or a certificate with keyCertSign but no
//!    basicConstraints is accepted as a CA there; a trusted certificate identical to the
//!    server's needs no check.
//!  - The nameConstraints of every CA in the path, the trusted one included, hold for the
//!    names of the certificates below it (`checkNameConstraints`, RFC 5280 4.2.1.10): a
//!    wildcard name is held to the excluded names it would match, and directory names
//!    compare with case and spaces folded, as OpenSSL compares them. A subjectAltName entry
//!    must carry a GeneralName tag, since upstream's host match reads only the tag number.
//!  - The server sends one Certificate message, as TLS requires: the certificates kept for
//!    the checks above point into the buffer that holds it.
//!  - The server's certificate, when it has extendedKeyUsage, must allow TLS server use
//!    as Android's TrustManagerImpl requires, and no certificate the server presents may
//!    have a critical extension this client does not process (RFC 5280 6.1.4(o)).
//!  - An IP address host is not sent as SNI (RFC 6066 section 3) and matches only an
//!    iPAddress subjectAltName, never a dNSName (wildcard or not) or the common name.
//!  - A note above `cipher_suites` on why ChaCha20 comes first on Android (#337); comment
//!    only, marked "Velo note".
//! When moving to a newer Zig, copy the new upstream file and re-apply the patches.
//!
//! Upstream license (lib/std is MIT licensed):
//!
//! The MIT License (Expat)
//!
//! Copyright (c) Zig contributors
//!
//! Permission is hereby granted, free of charge, to any person obtaining a copy
//! of this software and associated documentation files (the "Software"), to deal
//! in the Software without restriction, including without limitation the rights
//! to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
//! copies of the Software, and to permit persons to whom the Software is
//! furnished to do so, subject to the following conditions:
//!
//! The above copyright notice and this permission notice shall be included in
//! all copies or substantial portions of the Software.
//!
//! THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
//! IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
//! FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
//! AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
//! LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
//! OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
//! THE SOFTWARE.
const builtin = @import("builtin");
const native_endian = builtin.cpu.arch.endian();

const std = @import("std"); // Velo patch: upstream imports "../../std.zig"
const tls = std.crypto.tls;
const Client = @This();
const mem = std.mem;
const crypto = std.crypto;
const assert = std.debug.assert;
const Certificate = std.crypto.Certificate;
const Reader = std.Io.Reader;
const Writer = std.Io.Writer;

const max_ciphertext_len = tls.max_ciphertext_len;
const hmacExpandLabel = tls.hmacExpandLabel;
const hkdfExpandLabel = tls.hkdfExpandLabel;
const int = tls.int;
const array = tls.array;

/// The encrypted stream from the server to the client. Bytes are pulled from
/// here via `reader`.
///
/// The buffer is asserted to have capacity at least `min_buffer_len`.
input: *Reader,
/// Decrypted stream from the server to the client.
reader: Reader,

/// The encrypted stream from the client to the server. Bytes are pushed here
/// via `writer`.
///
/// The buffer is asserted to have capacity at least `min_buffer_len`.
output: *Writer,
/// The plaintext stream from the client to the server.
writer: Writer,

/// Populated when `error.TlsAlert` is returned.
alert: ?tls.Alert = null,
read_err: ?ReadError = null,
tls_version: tls.ProtocolVersion,
read_seq: u64,
write_seq: u64,
/// When this is true, the stream may still not be at the end because there
/// may be data in the input buffer.
received_close_notify: bool,
allow_truncation_attacks: bool,
application_cipher: tls.ApplicationCipher,

/// If non-null, ssl secrets are logged to a stream. Creating such a log file
/// allows other programs with access to that file to decrypt all traffic over
/// this connection.
ssl_key_log: ?*SslKeyLog,

pub const ReadError = error{
    /// The alert description will be stored in `alert`.
    TlsAlert,
    TlsBadLength,
    TlsBadRecordMac,
    TlsConnectionTruncated,
    TlsDecodeError,
    TlsRecordOverflow,
    TlsUnexpectedMessage,
    TlsIllegalParameter,
    TlsSequenceOverflow,
};

pub const SslKeyLog = struct {
    client_key_seq: u64,
    server_key_seq: u64,
    client_random: [32]u8,
    writer: *Writer,

    fn clientCounter(key_log: *@This()) u64 {
        defer key_log.client_key_seq += 1;
        return key_log.client_key_seq;
    }

    fn serverCounter(key_log: *@This()) u64 {
        defer key_log.server_key_seq += 1;
        return key_log.server_key_seq;
    }
};

/// The `Reader` supplied to `init` requires a buffer capacity
/// at least this amount.
pub const min_buffer_len = tls.max_ciphertext_record_len;

pub const Options = struct {
    /// How to perform host verification of server certificates.
    host: union(enum) {
        /// No host verification is performed, which prevents a trusted connection from
        /// being established.
        no_verification,
        /// Verify that the server certificate was issued for a given host.
        explicit: []const u8,
    },
    /// How to verify the authenticity of server certificates.
    ca: union(enum) {
        /// No ca verification is performed, which prevents a trusted connection from
        /// being established.
        no_verification,
        /// Verify that the server certificate is a valid self-signed certificate.
        /// This provides no authorization guarantees, as anyone can create a
        /// self-signed certificate.
        self_signed,
        /// Verify that the server certificate is authorized by a given ca bundle.
        bundle: struct {
            gpa: std.mem.Allocator,
            io: std.Io,
            lock: *std.Io.RwLock,
            bundle: *Certificate.Bundle,
        },
    },
    write_buffer: []u8,
    read_buffer: []u8,
    /// Cryptographically secure random bytes. The pointer is not captured; data is only
    /// read during `init`.
    entropy: *const [entropy_len]u8,
    /// Current time according to the wall clock / calendar.
    realtime_now: std.Io.Timestamp,

    /// If non-null, ssl secrets are logged to this stream. Creating such a log file allows
    /// other programs with access to that file to decrypt all traffic over this connection.
    ///
    /// Only the `writer` field is observed during the handshake (`init`).
    /// After that, the other fields are populated.
    ssl_key_log: ?*SslKeyLog = null,
    /// By default, reaching the end-of-stream when reading from the server will
    /// cause `error.TlsConnectionTruncated` to be returned, unless a close_notify
    /// message has been received. By setting this flag to `true`, instead, the
    /// end-of-stream will be forwarded to the application layer above TLS.
    ///
    /// This makes the application vulnerable to truncation attacks unless the
    /// application layer itself verifies that the amount of data received equals
    /// the amount of data expected, such as HTTP with the Content-Length header.
    allow_truncation_attacks: bool = false,
    /// Populated when `error.TlsAlert` is returned from `init`.
    alert: ?*tls.Alert = null,

    pub const entropy_len = 240;
};

pub const InitError = error{
    InsufficientEntropy,
    DiskQuota,
    LockViolation,
    NotOpenForWriting,
    /// The alert description will be stored in `alert`.
    TlsAlert,
    TlsUnexpectedMessage,
    TlsIllegalParameter,
    TlsDecryptFailure,
    TlsRecordOverflow,
    TlsBadRecordMac,
    CertificateFieldHasInvalidLength,
    CertificateHostMismatch,
    CertificatePublicKeyInvalid,
    CertificateExpired,
    CertificateFieldHasWrongDataType,
    CertificateIssuerMismatch,
    CertificateNotYetValid,
    CertificateSignatureAlgorithmMismatch,
    CertificateSignatureAlgorithmUnsupported,
    CertificateSignatureInvalid,
    CertificateSignatureInvalidLength,
    CertificateSignatureNamedCurveUnsupported,
    CertificateSignatureUnsupportedBitCount,
    TlsCertificateNotVerified,
    // Velo patch: a certificate signed another one without being allowed to.
    TlsCertificateIssuerNotCa,
    TlsCertificatePathLenExceeded,
    TlsCertificateIssuerKeyUsage,
    // Velo patch: a name outside a CA's nameConstraints, a constraint on a name form
    // this client does not check, a server certificate not for TLS servers, a critical
    // extension this client does not process, and a chain longer than `max_path`.
    TlsCertificateNameConstraint,
    TlsCertificateNameConstraintUnsupported,
    TlsCertificateExtendedKeyUsage,
    TlsCertificateUnknownCriticalExtension,
    TlsCertificateChainTooLong,
    TlsBadSignatureScheme,
    TlsBadRsaSignatureBitCount,
    InvalidEncoding,
    IdentityElement,
    SignatureVerificationFailed,
    TlsDecryptError,
    TlsConnectionTruncated,
    TlsDecodeError,
    UnsupportedCertificateVersion,
    CertificateTimeInvalid,
    CertificateHasUnrecognizedObjectId,
    CertificateHasInvalidBitString,
    MessageTooLong,
    NegativeIntoUnsigned,
    TargetTooSmall,
    BufferTooSmall,
    InvalidSignature,
    NotSquare,
    NonCanonical,
    WeakPublicKey,
} || std.Io.Writer.Error || std.Io.Reader.ShortError || std.Io.Cancelable;

/// Initiates a TLS handshake and establishes a TLSv1.2 or TLSv1.3 session.
///
/// `host` is only borrowed during this function call.
///
/// `input` is asserted to have buffer capacity at least `min_buffer_len`.
pub fn init(input: *Reader, output: *Writer, options: Options) InitError!Client {
    assert(input.buffer.len >= min_buffer_len);
    const host = switch (options.host) {
        .no_verification => "",
        .explicit => |host| host,
    };
    // Velo patch: SNI carries DNS names only (RFC 6066 section 3), not IP addresses.
    const sni: []const u8 = if (isIpHost(host)) "" else host;
    const sni_len: u16 = @intCast(sni.len);

    const client_hello_rand = options.entropy[0..32].*;
    var key_seq: u64 = 0;
    var server_hello_rand: [32]u8 = undefined;
    const legacy_session_id = options.entropy[32..64].*;

    var key_share = KeyShare.init(options.entropy[64..240]) catch |err| switch (err) {
        // Only possible to happen if the seed is all zeroes.
        error.IdentityElement => return error.InsufficientEntropy,
    };

    const extensions_payload = tls.extension(.supported_versions, array(u8, tls.ProtocolVersion, .{
        .tls_1_3,
        .tls_1_2,
    })) ++ tls.extension(.signature_algorithms, array(u16, tls.SignatureScheme, .{
        .ecdsa_secp256r1_sha256,
        .ecdsa_secp384r1_sha384,
        .rsa_pkcs1_sha256,
        .rsa_pkcs1_sha384,
        .rsa_pkcs1_sha512,
        .rsa_pss_rsae_sha256,
        .rsa_pss_rsae_sha384,
        .rsa_pss_rsae_sha512,
        .rsa_pss_pss_sha256,
        .rsa_pss_pss_sha384,
        .rsa_pss_pss_sha512,
        .rsa_pkcs1_sha1,
        .ed25519,
    })) ++ tls.extension(.supported_groups, array(u16, tls.NamedGroup, .{
        .x25519_ml_kem768,
        .secp256r1,
        .secp384r1,
        .x25519,
    })) ++ tls.extension(.psk_key_exchange_modes, array(u8, tls.PskKeyExchangeMode, .{
        .psk_dhe_ke,
    })) ++ tls.extension(.key_share, array(
        u16,
        u8,
        int(u16, @backingInt(tls.NamedGroup.x25519_ml_kem768)) ++
            array(u16, u8, key_share.ml_kem768_kp.public_key.toBytes() ++ key_share.x25519_kp.public_key) ++
            int(u16, @backingInt(tls.NamedGroup.secp256r1)) ++
            array(u16, u8, key_share.secp256r1_kp.public_key.toUncompressedSec1()) ++
            int(u16, @backingInt(tls.NamedGroup.secp384r1)) ++
            array(u16, u8, key_share.secp384r1_kp.public_key.toUncompressedSec1()) ++
            int(u16, @backingInt(tls.NamedGroup.x25519)) ++
            array(u16, u8, key_share.x25519_kp.public_key),
    ));
    const server_name_extension = int(u16, @backingInt(tls.ExtensionType.server_name)) ++
        int(u16, 2 + 1 + 2 + sni_len) ++ // byte length of this extension payload
        int(u16, 1 + 2 + sni_len) ++ // server_name_list byte count
        .{0x00} ++ // name_type
        int(u16, sni_len);
    const server_name_extension_len = if (sni.len == 0) 0 else server_name_extension.len + sni_len;

    const extensions_header =
        int(u16, @intCast(extensions_payload.len + server_name_extension_len)) ++
        extensions_payload ++
        server_name_extension;

    const client_hello =
        int(u16, @backingInt(tls.ProtocolVersion.tls_1_2)) ++
        client_hello_rand ++
        [1]u8{32} ++ legacy_session_id ++
        cipher_suites ++
        array(u8, tls.CompressionMethod, .{.null}) ++
        extensions_header;

    const out_handshake = .{@backingInt(tls.HandshakeType.client_hello)} ++
        int(u24, @intCast(client_hello.len - server_name_extension.len + server_name_extension_len)) ++
        client_hello;

    const cleartext_header_buf = .{@backingInt(tls.ContentType.handshake)} ++
        int(u16, @backingInt(tls.ProtocolVersion.tls_1_0)) ++
        int(u16, @intCast(out_handshake.len - server_name_extension.len + server_name_extension_len)) ++
        out_handshake;
    const cleartext_header = if (sni.len == 0)
        cleartext_header_buf[0 .. cleartext_header_buf.len - server_name_extension.len]
    else
        &cleartext_header_buf;

    {
        var iovecs: [2][]const u8 = .{ cleartext_header, sni };
        try output.writeVecAll(iovecs[0..if (sni.len == 0) 1 else 2]);
        try output.flush();
    }

    var tls_version: tls.ProtocolVersion = undefined;
    var chain: Certificate.Chain = if (Certificate.Chain != void) .empty;
    defer if (Certificate.Chain != void) chain.deinit();
    // These are used for two purposes:
    // * Detect whether a certificate is the first one presented, in which case
    //   we need to verify the host name.
    var cert_index: usize = 0;
    // * Flip back and forth between the two cleartext buffers in order to keep
    //   the previous certificate in memory so that it can be verified by the
    //   next one.
    var cert_buf_index: usize = 0;
    var write_seq: u64 = 0;
    var read_seq: u64 = 0;
    var prev_cert: Certificate.Parsed = undefined;
    // Velo patch: the certificates of the path so far, the server's first (all in the
    // one Certificate message, so in one buffer), and how many of the intermediates among
    // them are not self-issued: what a pathLenConstraint above them counts.
    var path: [max_path]Certificate.Parsed = undefined;
    var counted_intermediates: usize = 0;
    // Velo patch: TLS gives the server one Certificate message. A second one would be
    // read into a buffer that held the first, where `path` still points.
    var certificate_received = false;
    const CipherState = enum {
        /// No cipher is in use
        cleartext,
        /// Handshake cipher is in use
        handshake,
        /// Application cipher is in use
        application,
    };
    var pending_cipher_state: CipherState = .cleartext;
    var cipher_state = pending_cipher_state;
    const HandshakeState = enum {
        /// In this state we expect only a server hello message.
        hello,
        /// In this state we expect only an encrypted_extensions message.
        encrypted_extensions,
        /// In this state we expect certificate handshake messages.
        certificate,
        /// In this state we expect certificate or certificate_verify messages.
        /// certificate messages are ignored since the trust chain is already
        /// established.
        trust_chain_established,
        /// In this state, we expect only the server_hello_done handshake message.
        server_hello_done,
        /// In this state, we expect only the finished handshake message.
        finished,
    };
    var handshake_state: HandshakeState = .hello;
    var handshake_cipher: tls.HandshakeCipher = undefined;
    var main_cert_pub_key: CertificatePublicKey = undefined;
    var tls12_negotiated_group: ?tls.NamedGroup = null;
    const now_sec = options.realtime_now.toSeconds();

    var cleartext_fragment_start: usize = 0;
    var cleartext_fragment_end: usize = 0;
    var cleartext_bufs: [2][tls.max_ciphertext_inner_record_len]u8 = undefined;
    fragment: while (true) {
        const record_header = (input.takeArray(tls.record_header_len) catch |err| switch (err) {
            error.EndOfStream => return error.TlsConnectionTruncated,
            error.ReadFailed => |e| return e,
        }).*;
        const record_ct: tls.ContentType = @fromBackingInt(record_header[0]);
        // record_header[1..3] is legacy_version
        const record_len = mem.readInt(u16, record_header[3..5], .big);
        if (record_len > tls.max_ciphertext_len) return error.TlsRecordOverflow;
        const record_buffer = input.take(record_len) catch |err| switch (err) {
            error.EndOfStream => return error.TlsConnectionTruncated,
            error.ReadFailed => |e| return e,
        };
        var record_decoder: tls.Decoder = .fromTheirSlice(record_buffer);
        var ctd, const ct = content: switch (cipher_state) {
            .cleartext => .{ record_decoder, record_ct },
            .handshake => {
                assert(tls_version == .tls_1_3);
                if (record_ct != .application_data) return error.TlsUnexpectedMessage;
                try record_decoder.ensure(record_len);
                const cleartext_buf = &cleartext_bufs[cert_buf_index % 2];
                switch (handshake_cipher) {
                    inline else => |*p| {
                        const pv = &p.version.tls_1_3;
                        const P = @TypeOf(p.*).A;
                        if (record_len < P.AEAD.tag_length) return error.TlsRecordOverflow;
                        const ciphertext = record_decoder.slice(record_len - P.AEAD.tag_length);
                        const cleartext_fragment_buf = cleartext_buf[cleartext_fragment_end..];
                        if (ciphertext.len > cleartext_fragment_buf.len) return error.TlsRecordOverflow;
                        const cleartext = cleartext_fragment_buf[0..ciphertext.len];
                        const auth_tag = record_decoder.array(P.AEAD.tag_length).*;
                        const nonce = nonce: {
                            const V = @Vector(P.AEAD.nonce_length, u8);
                            const pad: [P.AEAD.nonce_length - 8]u8 = @splat(0);
                            const operand: V = pad ++ @as([8]u8, @bitCast(@byteSwap(read_seq)));
                            break :nonce @as(V, pv.server_handshake_iv) ^ operand;
                        };
                        P.AEAD.decrypt(cleartext, ciphertext, auth_tag, &record_header, nonce, pv.server_handshake_key) catch
                            return error.TlsBadRecordMac;
                        // TODO use scalar, non-slice version
                        const trimmed_len = mem.trimEnd(u8, cleartext, "\x00").len;
                        if (trimmed_len == 0) return error.TlsDecodeError;
                        cleartext_fragment_end += trimmed_len;
                    },
                }
                read_seq += 1;
                cleartext_fragment_end -= 1;
                const ct: tls.ContentType = @fromBackingInt(@intCast(cleartext_buf[cleartext_fragment_end]));
                if (ct != .handshake) return error.TlsUnexpectedMessage;
                break :content .{ tls.Decoder.fromTheirSlice(@constCast(cleartext_buf[cleartext_fragment_start..cleartext_fragment_end])), ct };
            },
            .application => {
                assert(tls_version == .tls_1_2);
                if (record_ct != .handshake) return error.TlsUnexpectedMessage;
                try record_decoder.ensure(record_len);
                const cleartext_buf = &cleartext_bufs[cert_buf_index % 2];
                switch (handshake_cipher) {
                    inline else => |*p| {
                        const pv = &p.version.tls_1_2;
                        const P = @TypeOf(p.*).A;
                        if (record_len < P.record_iv_length + P.mac_length) return error.TlsRecordOverflow;
                        const message_len: u16 = record_len - P.record_iv_length - P.mac_length;
                        const cleartext_fragment_buf = cleartext_buf[cleartext_fragment_end..];
                        if (message_len > cleartext_fragment_buf.len) return error.TlsRecordOverflow;
                        const cleartext = cleartext_fragment_buf[0..message_len];
                        const ad = mem.toBytes(big(read_seq)) ++
                            record_header[0 .. 1 + 2] ++
                            mem.toBytes(big(message_len));
                        const record_iv = record_decoder.array(P.record_iv_length).*;
                        const masked_read_seq = read_seq &
                            comptime std.math.shl(u64, std.math.maxInt(u64), 8 * P.record_iv_length);
                        const nonce: [P.AEAD.nonce_length]u8 = nonce: {
                            const V = @Vector(P.AEAD.nonce_length, u8);
                            const pad: [P.AEAD.nonce_length - 8]u8 = @splat(0);
                            const operand: V = pad ++ @as([8]u8, @bitCast(@byteSwap(masked_read_seq)));
                            break :nonce @as(V, pv.app_cipher.server_write_IV ++ record_iv) ^ operand;
                        };
                        const ciphertext = record_decoder.slice(message_len);
                        const auth_tag = record_decoder.array(P.mac_length);
                        P.AEAD.decrypt(cleartext, ciphertext, auth_tag.*, ad, nonce, pv.app_cipher.server_write_key) catch return error.TlsBadRecordMac;
                        cleartext_fragment_end += message_len;
                    },
                }
                read_seq += 1;
                break :content .{ tls.Decoder.fromTheirSlice(cleartext_buf[cleartext_fragment_start..cleartext_fragment_end]), record_ct };
            },
        };
        switch (ct) {
            .alert => {
                ctd.ensure(2) catch continue :fragment;
                if (options.alert) |a| a.* = .{
                    .level = ctd.decode(tls.Alert.Level),
                    .description = ctd.decode(tls.Alert.Description),
                };
                return error.TlsAlert;
            },
            .change_cipher_spec => {
                ctd.ensure(1) catch continue :fragment;
                if (ctd.decode(tls.ChangeCipherSpecType) != .change_cipher_spec) return error.TlsIllegalParameter;
                cipher_state = pending_cipher_state;
            },
            .handshake => while (true) {
                ctd.ensure(4) catch continue :fragment;
                const handshake_type = ctd.decode(tls.HandshakeType);
                const handshake_len = ctd.decode(u24);
                var hsd = ctd.sub(handshake_len) catch continue :fragment;
                const wrapped_handshake = ctd.buf[ctd.idx - handshake_len - 4 .. ctd.idx];
                switch (handshake_type) {
                    .server_hello => {
                        if (cipher_state != .cleartext) return error.TlsUnexpectedMessage;
                        if (handshake_state != .hello) return error.TlsUnexpectedMessage;
                        try hsd.ensure(2 + 32 + 1);
                        const legacy_version = hsd.decode(u16);
                        @memcpy(&server_hello_rand, hsd.array(32));
                        if (mem.eql(u8, &server_hello_rand, &tls.hello_retry_request_sequence)) {
                            // This is a HelloRetryRequest message. This client implementation
                            // does not expect to get one.
                            return error.TlsUnexpectedMessage;
                        }
                        const legacy_session_id_echo_len = hsd.decode(u8);
                        try hsd.ensure(legacy_session_id_echo_len + 2 + 1);
                        const legacy_session_id_echo = hsd.slice(legacy_session_id_echo_len);
                        const cipher_suite_tag = hsd.decode(tls.CipherSuite);
                        hsd.skip(1); // legacy_compression_method
                        var supported_version: ?u16 = null;
                        if (!hsd.eof()) {
                            try hsd.ensure(2);
                            const extensions_size = hsd.decode(u16);
                            var all_extd = try hsd.sub(extensions_size);
                            while (!all_extd.eof()) {
                                try all_extd.ensure(2 + 2);
                                const et = all_extd.decode(tls.ExtensionType);
                                const ext_size = all_extd.decode(u16);
                                var extd = try all_extd.sub(ext_size);
                                switch (et) {
                                    .supported_versions => {
                                        if (supported_version) |_| return error.TlsIllegalParameter;
                                        try extd.ensure(2);
                                        supported_version = extd.decode(u16);
                                    },
                                    .key_share => {
                                        if (key_share.getSharedSecret()) |_| return error.TlsIllegalParameter;
                                        try extd.ensure(4);
                                        const named_group = extd.decode(tls.NamedGroup);
                                        const key_size = extd.decode(u16);
                                        try extd.ensure(key_size);
                                        try key_share.exchange(named_group, extd.slice(key_size));
                                    },
                                    else => {},
                                }
                            }
                        }

                        tls_version = @fromBackingInt(@intCast(supported_version orelse legacy_version));
                        switch (tls_version) {
                            .tls_1_3 => if (!mem.eql(u8, legacy_session_id_echo, &legacy_session_id)) return error.TlsIllegalParameter,
                            .tls_1_2 => if (mem.eql(u8, server_hello_rand[24..31], "DOWNGRD") and
                                server_hello_rand[31] >> 1 == 0x00) return error.TlsIllegalParameter,
                            else => return error.TlsIllegalParameter,
                        }

                        switch (cipher_suite_tag) {
                            inline .AES_128_GCM_SHA256,
                            .AES_256_GCM_SHA384,
                            .CHACHA20_POLY1305_SHA256,
                            .AEGIS_256_SHA512,
                            .AEGIS_128L_SHA256,

                            .ECDHE_RSA_WITH_AES_128_GCM_SHA256,
                            .ECDHE_RSA_WITH_AES_256_GCM_SHA384,
                            .ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256,
                            => |tag| {
                                handshake_cipher = @unionInit(tls.HandshakeCipher, @tagName(tag.with()), .{
                                    .transcript_hash = .init(.{}),
                                    .version = undefined,
                                });
                                const p = &@field(handshake_cipher, @tagName(tag.with()));
                                p.transcript_hash.update(cleartext_header[tls.record_header_len..]); // Client Hello part 1
                                p.transcript_hash.update(sni); // Client Hello part 2
                                p.transcript_hash.update(wrapped_handshake);
                            },

                            else => return error.TlsIllegalParameter,
                        }
                        switch (tls_version) {
                            .tls_1_3 => {
                                switch (cipher_suite_tag) {
                                    inline .AES_128_GCM_SHA256,
                                    .AES_256_GCM_SHA384,
                                    .CHACHA20_POLY1305_SHA256,
                                    .AEGIS_256_SHA512,
                                    .AEGIS_128L_SHA256,
                                    => |tag| {
                                        const sk = key_share.getSharedSecret() orelse return error.TlsIllegalParameter;
                                        const p = &@field(handshake_cipher, @tagName(tag.with()));
                                        const P = @TypeOf(p.*).A;
                                        const hello_hash = p.transcript_hash.peek();
                                        const zeroes: [P.Hash.digest_length]u8 = @splat(0);
                                        const early_secret = P.Hkdf.extract(&[1]u8{0}, &zeroes);
                                        const empty_hash = tls.emptyHash(P.Hash);
                                        p.version = .{ .tls_1_3 = undefined };
                                        const pv = &p.version.tls_1_3;
                                        const hs_derived_secret = hkdfExpandLabel(P.Hkdf, early_secret, "derived", &empty_hash, P.Hash.digest_length);
                                        pv.handshake_secret = P.Hkdf.extract(&hs_derived_secret, sk);
                                        const ap_derived_secret = hkdfExpandLabel(P.Hkdf, pv.handshake_secret, "derived", &empty_hash, P.Hash.digest_length);
                                        pv.master_secret = P.Hkdf.extract(&ap_derived_secret, &zeroes);
                                        const client_secret = hkdfExpandLabel(P.Hkdf, pv.handshake_secret, "c hs traffic", &hello_hash, P.Hash.digest_length);
                                        const server_secret = hkdfExpandLabel(P.Hkdf, pv.handshake_secret, "s hs traffic", &hello_hash, P.Hash.digest_length);
                                        if (options.ssl_key_log) |key_log| logSecrets(key_log.writer, .{
                                            .client_random = &client_hello_rand,
                                        }, .{
                                            .SERVER_HANDSHAKE_TRAFFIC_SECRET = &server_secret,
                                            .CLIENT_HANDSHAKE_TRAFFIC_SECRET = &client_secret,
                                        });
                                        pv.client_finished_key = hkdfExpandLabel(P.Hkdf, client_secret, "finished", "", P.Hmac.key_length);
                                        pv.server_finished_key = hkdfExpandLabel(P.Hkdf, server_secret, "finished", "", P.Hmac.key_length);
                                        pv.client_handshake_key = hkdfExpandLabel(P.Hkdf, client_secret, "key", "", P.AEAD.key_length);
                                        pv.server_handshake_key = hkdfExpandLabel(P.Hkdf, server_secret, "key", "", P.AEAD.key_length);
                                        pv.client_handshake_iv = hkdfExpandLabel(P.Hkdf, client_secret, "iv", "", P.AEAD.nonce_length);
                                        pv.server_handshake_iv = hkdfExpandLabel(P.Hkdf, server_secret, "iv", "", P.AEAD.nonce_length);
                                    },
                                    else => return error.TlsIllegalParameter,
                                }
                                pending_cipher_state = .handshake;
                                handshake_state = .encrypted_extensions;
                            },
                            .tls_1_2 => switch (cipher_suite_tag) {
                                .ECDHE_RSA_WITH_AES_128_GCM_SHA256,
                                .ECDHE_RSA_WITH_AES_256_GCM_SHA384,
                                .ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256,
                                => handshake_state = .certificate,
                                else => return error.TlsIllegalParameter,
                            },
                            else => return error.TlsIllegalParameter,
                        }
                    },
                    .encrypted_extensions => {
                        if (tls_version != .tls_1_3) return error.TlsUnexpectedMessage;
                        if (cipher_state != .handshake) return error.TlsUnexpectedMessage;
                        if (handshake_state != .encrypted_extensions) return error.TlsUnexpectedMessage;
                        switch (handshake_cipher) {
                            inline else => |*p| p.transcript_hash.update(wrapped_handshake),
                        }
                        try hsd.ensure(2);
                        const total_ext_size = hsd.decode(u16);
                        var all_extd = try hsd.sub(total_ext_size);
                        while (!all_extd.eof()) {
                            try all_extd.ensure(4);
                            const et = all_extd.decode(tls.ExtensionType);
                            const ext_size = all_extd.decode(u16);
                            const extd = try all_extd.sub(ext_size);
                            _ = extd;
                            switch (et) {
                                .server_name => {},
                                else => {},
                            }
                        }
                        handshake_state = .certificate;
                    },
                    .certificate => cert: {
                        if (cipher_state == .application) return error.TlsUnexpectedMessage;
                        // Velo patch: one Certificate message only.
                        if (certificate_received) return error.TlsUnexpectedMessage;
                        certificate_received = true;
                        switch (handshake_state) {
                            .certificate => {},
                            .trust_chain_established => break :cert,
                            else => return error.TlsUnexpectedMessage,
                        }
                        switch (handshake_cipher) {
                            inline else => |*p| p.transcript_hash.update(wrapped_handshake),
                        }

                        switch (tls_version) {
                            .tls_1_3 => {
                                try hsd.ensure(1 + 3);
                                const cert_req_ctx_len = hsd.decode(u8);
                                if (cert_req_ctx_len != 0) return error.TlsIllegalParameter;
                            },
                            .tls_1_2 => try hsd.ensure(3),
                            else => unreachable,
                        }
                        const certs_size = hsd.decode(u24);
                        const certs = try hsd.sub(certs_size);

                        var certs_decoder = certs;
                        while (!certs_decoder.eof()) {
                            try certs_decoder.ensure(3);
                            const cert_size = certs_decoder.decode(u24);
                            const certd = try certs_decoder.sub(cert_size);

                            if (tls_version == .tls_1_3) {
                                try certs_decoder.ensure(2);
                                const total_ext_size = certs_decoder.decode(u16);
                                const all_extd = try certs_decoder.sub(total_ext_size);
                                _ = all_extd;
                            }

                            const subject_cert: Certificate = .{
                                .buffer = certd.buf,
                                .index = @intCast(certd.idx),
                            };
                            const subject = try subject_cert.parse();
                            // Velo patch: keep the path for the constraints of the CAs above.
                            if (cert_index >= max_path) return error.TlsCertificateChainTooLong;
                            path[cert_index] = subject;
                            if (cert_index == 0) {
                                // Verify the host on the first certificate.
                                switch (options.host) {
                                    .no_verification => {},
                                    // Velo patch: IP hosts match iPAddress names only.
                                    .explicit => try verifyHostName(subject, host),
                                }
                                // Velo patch: a certificate for TLS servers, every extension understood.
                                switch (options.ca) {
                                    .no_verification => {},
                                    else => try checkLeaf(subject),
                                }

                                // Keep track of the public key for the
                                // certificate_verify message later.
                                try main_cert_pub_key.init(subject.pub_key_algo, subject.pubKey());
                            } else {
                                try prev_cert.verify(subject, now_sec);
                                // Velo patch: `subject` signed `prev_cert`; its pathLenConstraint
                                // counts the intermediates below it, its nameConstraints hold for
                                // every certificate below it.
                                try checkIssuer(subject, .presented, counted_intermediates, now_sec);
                                try checkNameConstraints(subject, path[0..cert_index]);
                                if (!selfIssued(subject)) counted_intermediates += 1;
                            }

                            switch (options.ca) {
                                .no_verification => {
                                    handshake_state = .trust_chain_established;
                                    break :cert;
                                },
                                .self_signed => {
                                    try subject.verify(subject, now_sec);
                                    handshake_state = .trust_chain_established;
                                    break :cert;
                                },
                                .bundle => |ca| if (verify: {
                                    try ca.lock.lockShared(ca.io);
                                    defer ca.lock.unlockShared(ca.io);
                                    // Velo patch: the trusted issuer is checked too.
                                    break :verify verifyByAnchor(ca.bundle.*, subject, now_sec, counted_intermediates, path[0 .. cert_index + 1]);
                                }) {
                                    handshake_state = .trust_chain_established;
                                    break :cert;
                                } else |err| switch (err) {
                                    error.CertificateIssuerNotFound => {},
                                    else => |e| return e,
                                },
                            }

                            prev_cert = subject;
                            cert_index += 1;
                        }

                        if (Certificate.Chain != void) {
                            certs_decoder = certs;
                            while (!certs_decoder.eof()) {
                                try certs_decoder.ensure(3);
                                const cert_size = certs_decoder.decode(u24);
                                const certd = try certs_decoder.sub(cert_size);
                                chain.addCert(certd.rest()) catch |err| switch (err) {
                                    error.Unexpected => return error.TlsCertificateNotVerified,
                                };
                                if (tls_version == .tls_1_3) {
                                    try certs_decoder.ensure(2);
                                    const total_ext_size = certs_decoder.decode(u16);
                                    const all_extd = try certs_decoder.sub(total_ext_size);
                                    _ = all_extd;
                                }
                            }
                        }

                        cert_buf_index += 1;
                    },
                    .server_key_exchange => {
                        if (tls_version != .tls_1_2) return error.TlsUnexpectedMessage;
                        if (cipher_state != .cleartext) return error.TlsUnexpectedMessage;
                        switch (handshake_state) {
                            .trust_chain_established => {},
                            .certificate => try tryDownloadRootCert(&chain, &options),
                            else => return error.TlsUnexpectedMessage,
                        }

                        switch (handshake_cipher) {
                            inline else => |*p| p.transcript_hash.update(wrapped_handshake),
                        }
                        try hsd.ensure(1 + 2 + 1);
                        const curve_type = hsd.decode(u8);
                        if (curve_type != 0x03) return error.TlsIllegalParameter; // named_curve
                        const named_group = hsd.decode(tls.NamedGroup);
                        tls12_negotiated_group = named_group;
                        const key_size = hsd.decode(u8);
                        try hsd.ensure(key_size);
                        const server_pub_key = hsd.slice(key_size);
                        try main_cert_pub_key.verifySignature(&hsd, &.{ &client_hello_rand, &server_hello_rand, hsd.buf[0..hsd.idx] });
                        try key_share.exchange(named_group, server_pub_key);
                        handshake_state = .server_hello_done;
                    },
                    .server_hello_done => {
                        if (tls_version != .tls_1_2) return error.TlsUnexpectedMessage;
                        if (cipher_state != .cleartext) return error.TlsUnexpectedMessage;
                        if (handshake_state != .server_hello_done) return error.TlsUnexpectedMessage;

                        const public_key_bytes: []const u8 = switch (tls12_negotiated_group orelse .secp256r1) {
                            .secp256r1 => &key_share.secp256r1_kp.public_key.toUncompressedSec1(),
                            .secp384r1 => &key_share.secp384r1_kp.public_key.toUncompressedSec1(),
                            .x25519 => &key_share.x25519_kp.public_key,
                            else => return error.TlsIllegalParameter,
                        };

                        const client_key_exchange_prefix = .{@backingInt(tls.ContentType.handshake)} ++
                            int(u16, @backingInt(tls.ProtocolVersion.tls_1_2)) ++
                            int(u16, @intCast(public_key_bytes.len + 5)) ++ // record length
                            .{@backingInt(tls.HandshakeType.client_key_exchange)} ++
                            int(u24, @intCast(public_key_bytes.len + 1)) ++ // handshake message length
                            .{@as(u8, @intCast(public_key_bytes.len))}; // public key length
                        const client_change_cipher_spec_msg = .{@backingInt(tls.ContentType.change_cipher_spec)} ++
                            int(u16, @backingInt(tls.ProtocolVersion.tls_1_2)) ++
                            array(u16, tls.ChangeCipherSpecType, .{.change_cipher_spec});
                        const pre_master_secret = key_share.getSharedSecret().?;
                        switch (handshake_cipher) {
                            inline else => |*p| {
                                const P = @TypeOf(p.*).A;
                                p.transcript_hash.update(wrapped_handshake);
                                p.transcript_hash.update(client_key_exchange_prefix[tls.record_header_len..]);
                                p.transcript_hash.update(public_key_bytes);
                                const master_secret = hmacExpandLabel(P.Hmac, pre_master_secret, &.{
                                    "master secret",
                                    &client_hello_rand,
                                    &server_hello_rand,
                                }, 48);
                                if (options.ssl_key_log) |key_log| logSecrets(key_log.writer, .{
                                    .client_random = &client_hello_rand,
                                }, .{
                                    .CLIENT_RANDOM = &master_secret,
                                });
                                const key_block = hmacExpandLabel(
                                    P.Hmac,
                                    &master_secret,
                                    &.{ "key expansion", &server_hello_rand, &client_hello_rand },
                                    @sizeOf(P.Tls_1_2),
                                );
                                const client_verify_cleartext = .{@backingInt(tls.HandshakeType.finished)} ++
                                    array(u24, u8, hmacExpandLabel(
                                        P.Hmac,
                                        &master_secret,
                                        &.{ "client finished", &p.transcript_hash.peek() },
                                        P.verify_data_length,
                                    ));
                                p.transcript_hash.update(&client_verify_cleartext);
                                p.version = .{ .tls_1_2 = .{
                                    .expected_server_verify_data = hmacExpandLabel(
                                        P.Hmac,
                                        &master_secret,
                                        &.{ "server finished", &p.transcript_hash.finalResult() },
                                        P.verify_data_length,
                                    ),
                                    .app_cipher = mem.bytesToValue(P.Tls_1_2, &key_block),
                                } };
                                const pv = &p.version.tls_1_2;
                                const nonce: [P.AEAD.nonce_length]u8 = nonce: {
                                    const V = @Vector(P.AEAD.nonce_length, u8);
                                    const pad: [P.AEAD.nonce_length - 8]u8 = @splat(0);
                                    const operand: V = pad ++ @as([8]u8, @bitCast(@byteSwap(write_seq)));
                                    break :nonce @as(V, pv.app_cipher.client_write_IV ++ pv.app_cipher.client_salt) ^ operand;
                                };
                                var client_verify_msg = .{@backingInt(tls.ContentType.handshake)} ++
                                    int(u16, @backingInt(tls.ProtocolVersion.tls_1_2)) ++
                                    array(u16, u8, nonce[P.fixed_iv_length..].* ++
                                        @as([client_verify_cleartext.len + P.mac_length]u8, undefined));
                                P.AEAD.encrypt(
                                    client_verify_msg[client_verify_msg.len - P.mac_length -
                                        client_verify_cleartext.len ..][0..client_verify_cleartext.len],
                                    client_verify_msg[client_verify_msg.len - P.mac_length ..][0..P.mac_length],
                                    &client_verify_cleartext,
                                    mem.toBytes(big(write_seq)) ++ client_verify_msg[0 .. 1 + 2] ++ int(u16, client_verify_cleartext.len),
                                    nonce,
                                    pv.app_cipher.client_write_key,
                                );
                                var all_msgs_vec: [4][]const u8 = .{
                                    &client_key_exchange_prefix,
                                    public_key_bytes,
                                    &client_change_cipher_spec_msg,
                                    &client_verify_msg,
                                };
                                try output.writeVecAll(&all_msgs_vec);
                                try output.flush();
                            },
                        }
                        write_seq += 1;
                        pending_cipher_state = .application;
                        handshake_state = .finished;
                    },
                    .certificate_verify => {
                        if (tls_version != .tls_1_3) return error.TlsUnexpectedMessage;
                        if (cipher_state != .handshake) return error.TlsUnexpectedMessage;
                        switch (handshake_state) {
                            .trust_chain_established => {},
                            .certificate => try tryDownloadRootCert(&chain, &options),
                            else => return error.TlsUnexpectedMessage,
                        }
                        switch (handshake_cipher) {
                            inline else => |*p| {
                                const pad: [64]u8 = @splat(' ');
                                try main_cert_pub_key.verifySignature(&hsd, &.{
                                    pad ++ "TLS 1.3, server CertificateVerify\x00",
                                    &p.transcript_hash.peek(),
                                });
                                p.transcript_hash.update(wrapped_handshake);
                            },
                        }
                        handshake_state = .finished;
                    },
                    .finished => {
                        if (cipher_state == .cleartext) return error.TlsUnexpectedMessage;
                        if (handshake_state != .finished) return error.TlsUnexpectedMessage;
                        // This message is to trick buggy proxies into behaving correctly.
                        const client_change_cipher_spec_msg = .{@backingInt(tls.ContentType.change_cipher_spec)} ++
                            int(u16, @backingInt(tls.ProtocolVersion.tls_1_2)) ++
                            array(u16, tls.ChangeCipherSpecType, .{.change_cipher_spec});
                        const app_cipher = app_cipher: switch (handshake_cipher) {
                            inline else => |*p, tag| switch (tls_version) {
                                .tls_1_3 => {
                                    const pv = &p.version.tls_1_3;
                                    const P = @TypeOf(p.*).A;
                                    try hsd.ensure(P.Hmac.mac_length);
                                    const finished_digest = p.transcript_hash.peek();
                                    p.transcript_hash.update(wrapped_handshake);
                                    const expected_server_verify_data = tls.hmac(P.Hmac, &finished_digest, pv.server_finished_key);
                                    if (!std.crypto.timing_safe.eql([P.Hmac.mac_length]u8, expected_server_verify_data, hsd.array(P.Hmac.mac_length).*)) return error.TlsDecryptError;
                                    const handshake_hash = p.transcript_hash.finalResult();
                                    const verify_data = tls.hmac(P.Hmac, &handshake_hash, pv.client_finished_key);
                                    const out_cleartext = .{@backingInt(tls.HandshakeType.finished)} ++
                                        array(u24, u8, verify_data) ++
                                        .{@backingInt(tls.ContentType.handshake)};

                                    const wrapped_len = out_cleartext.len + P.AEAD.tag_length;

                                    var finished_msg = .{@backingInt(tls.ContentType.application_data)} ++
                                        int(u16, @backingInt(tls.ProtocolVersion.tls_1_2)) ++
                                        array(u16, u8, @as([wrapped_len]u8, undefined));

                                    const ad = finished_msg[0..tls.record_header_len];
                                    const ciphertext = finished_msg[tls.record_header_len..][0..out_cleartext.len];
                                    const auth_tag = finished_msg[finished_msg.len - P.AEAD.tag_length ..];
                                    const nonce = pv.client_handshake_iv;
                                    P.AEAD.encrypt(ciphertext, auth_tag, &out_cleartext, ad, nonce, pv.client_handshake_key);

                                    var all_msgs_vec: [2][]const u8 = .{
                                        &client_change_cipher_spec_msg,
                                        &finished_msg,
                                    };
                                    try output.writeVecAll(&all_msgs_vec);
                                    try output.flush();

                                    const client_secret = hkdfExpandLabel(P.Hkdf, pv.master_secret, "c ap traffic", &handshake_hash, P.Hash.digest_length);
                                    const server_secret = hkdfExpandLabel(P.Hkdf, pv.master_secret, "s ap traffic", &handshake_hash, P.Hash.digest_length);
                                    if (options.ssl_key_log) |key_log| logSecrets(key_log.writer, .{
                                        .counter = key_seq,
                                        .client_random = &client_hello_rand,
                                    }, .{
                                        .SERVER_TRAFFIC_SECRET = &server_secret,
                                        .CLIENT_TRAFFIC_SECRET = &client_secret,
                                    });
                                    key_seq += 1;
                                    break :app_cipher @unionInit(tls.ApplicationCipher, @tagName(tag), .{ .tls_1_3 = .{
                                        .client_secret = client_secret,
                                        .server_secret = server_secret,
                                        .client_key = hkdfExpandLabel(P.Hkdf, client_secret, "key", "", P.AEAD.key_length),
                                        .server_key = hkdfExpandLabel(P.Hkdf, server_secret, "key", "", P.AEAD.key_length),
                                        .client_iv = hkdfExpandLabel(P.Hkdf, client_secret, "iv", "", P.AEAD.nonce_length),
                                        .server_iv = hkdfExpandLabel(P.Hkdf, server_secret, "iv", "", P.AEAD.nonce_length),
                                    } });
                                },
                                .tls_1_2 => {
                                    const pv = &p.version.tls_1_2;
                                    const P = @TypeOf(p.*).A;
                                    try hsd.ensure(P.verify_data_length);
                                    if (!std.crypto.timing_safe.eql([P.verify_data_length]u8, pv.expected_server_verify_data, hsd.array(P.verify_data_length).*)) return error.TlsDecryptError;
                                    break :app_cipher @unionInit(tls.ApplicationCipher, @tagName(tag), .{ .tls_1_2 = pv.app_cipher });
                                },
                                else => unreachable,
                            },
                        };
                        if (options.ssl_key_log) |ssl_key_log| ssl_key_log.* = .{
                            .client_key_seq = key_seq,
                            .server_key_seq = key_seq,
                            .client_random = client_hello_rand,
                            .writer = ssl_key_log.writer,
                        };
                        return .{
                            .input = input,
                            .reader = .{
                                .buffer = options.read_buffer,
                                .vtable = &.{
                                    .stream = stream,
                                    .readVec = readVec,
                                },
                                .seek = 0,
                                .end = 0,
                            },
                            .output = output,
                            .writer = .{
                                .buffer = options.write_buffer,
                                .vtable = &.{
                                    .drain = drain,
                                    .flush = flush,
                                },
                            },
                            .tls_version = tls_version,
                            .read_seq = switch (tls_version) {
                                .tls_1_3 => 0,
                                .tls_1_2 => read_seq,
                                else => unreachable,
                            },
                            .write_seq = switch (tls_version) {
                                .tls_1_3 => 0,
                                .tls_1_2 => write_seq,
                                else => unreachable,
                            },
                            .received_close_notify = false,
                            .allow_truncation_attacks = options.allow_truncation_attacks,
                            .application_cipher = app_cipher,
                            .ssl_key_log = options.ssl_key_log,
                        };
                    },
                    else => return error.TlsUnexpectedMessage,
                }
                if (ctd.eof()) break;
                cleartext_fragment_start = ctd.idx;
            },
            else => return error.TlsUnexpectedMessage,
        }
        cleartext_fragment_start = 0;
        cleartext_fragment_end = 0;
    }
}

fn drain(w: *Writer, data: []const []const u8, splat: usize) Writer.Error!usize {
    const c: *Client = @alignCast(@fieldParentPtr("writer", w));
    const output = c.output;
    const ciphertext_buf = try output.writableSliceGreedy(min_buffer_len);
    var ciphertext_end: usize = 0;
    var total_clear: usize = 0;
    done: {
        {
            const buf = w.buffered();
            const prepared = prepareCiphertextRecord(c, ciphertext_buf[ciphertext_end..], buf, .application_data);
            total_clear += prepared.cleartext_len;
            ciphertext_end += prepared.ciphertext_end;
            if (prepared.cleartext_len < buf.len) break :done;
        }
        for (data[0 .. data.len - 1]) |buf| {
            const prepared = prepareCiphertextRecord(c, ciphertext_buf[ciphertext_end..], buf, .application_data);
            total_clear += prepared.cleartext_len;
            ciphertext_end += prepared.ciphertext_end;
            if (prepared.cleartext_len < buf.len) break :done;
        }
        const buf = data[data.len - 1];
        for (0..splat) |_| {
            const prepared = prepareCiphertextRecord(c, ciphertext_buf[ciphertext_end..], buf, .application_data);
            total_clear += prepared.cleartext_len;
            ciphertext_end += prepared.ciphertext_end;
            if (prepared.cleartext_len < buf.len) break :done;
        }
    }
    output.advance(ciphertext_end);
    return w.consume(total_clear);
}

fn flush(w: *Writer) Writer.Error!void {
    const c: *Client = @alignCast(@fieldParentPtr("writer", w));
    const output = c.output;
    const ciphertext_buf = try output.writableSliceGreedy(min_buffer_len);
    const prepared = prepareCiphertextRecord(c, ciphertext_buf, w.buffered(), .application_data);
    output.advance(prepared.ciphertext_end);
    w.end = 0;
}

/// Sends a `close_notify` alert, which is necessary for the server to
/// distinguish between a properly finished TLS session, or a truncation
/// attack.
pub fn end(c: *Client) Writer.Error!void {
    try flush(&c.writer);
    const output = c.output;
    const ciphertext_buf = try output.writableSliceGreedy(min_buffer_len);
    const prepared = prepareCiphertextRecord(c, ciphertext_buf, &tls.close_notify_alert, .alert);
    output.advance(prepared.ciphertext_end);
}

fn prepareCiphertextRecord(
    c: *Client,
    ciphertext_buf: []u8,
    bytes: []const u8,
    inner_content_type: tls.ContentType,
) struct {
    ciphertext_end: usize,
    cleartext_len: usize,
} {
    // Due to the trailing inner content type byte in the ciphertext, we need
    // an additional buffer for storing the cleartext into before encrypting.
    var cleartext_buf: [max_ciphertext_len]u8 = undefined;
    var ciphertext_end: usize = 0;
    var bytes_i: usize = 0;
    switch (c.application_cipher) {
        inline else => |*p| switch (c.tls_version) {
            .tls_1_3 => {
                const pv = &p.tls_1_3;
                const P = @TypeOf(p.*);
                const overhead_len = tls.record_header_len + P.AEAD.tag_length + 1;
                while (true) {
                    const encrypted_content_len: u16 = @min(
                        bytes.len - bytes_i,
                        tls.max_ciphertext_inner_record_len,
                        ciphertext_buf.len -| (overhead_len + ciphertext_end),
                    );
                    if (encrypted_content_len == 0) return .{
                        .ciphertext_end = ciphertext_end,
                        .cleartext_len = bytes_i,
                    };

                    @memcpy(cleartext_buf[0..encrypted_content_len], bytes[bytes_i..][0..encrypted_content_len]);
                    cleartext_buf[encrypted_content_len] = @backingInt(inner_content_type);
                    bytes_i += encrypted_content_len;
                    const ciphertext_len = encrypted_content_len + 1;
                    const cleartext = cleartext_buf[0..ciphertext_len];

                    const ad = ciphertext_buf[ciphertext_end..][0..tls.record_header_len];
                    ad.* = .{@backingInt(tls.ContentType.application_data)} ++
                        int(u16, @backingInt(tls.ProtocolVersion.tls_1_2)) ++
                        int(u16, ciphertext_len + P.AEAD.tag_length);
                    ciphertext_end += ad.len;
                    const ciphertext = ciphertext_buf[ciphertext_end..][0..ciphertext_len];
                    ciphertext_end += ciphertext_len;
                    const auth_tag = ciphertext_buf[ciphertext_end..][0..P.AEAD.tag_length];
                    ciphertext_end += auth_tag.len;
                    const nonce = nonce: {
                        const V = @Vector(P.AEAD.nonce_length, u8);
                        const pad: [P.AEAD.nonce_length - 8]u8 = @splat(0);
                        const operand: V = pad ++ mem.toBytes(big(c.write_seq));
                        break :nonce @as(V, pv.client_iv) ^ operand;
                    };
                    P.AEAD.encrypt(ciphertext, auth_tag, cleartext, ad, nonce, pv.client_key);
                    c.write_seq += 1; // TODO send key_update on overflow
                }
            },
            .tls_1_2 => {
                const pv = &p.tls_1_2;
                const P = @TypeOf(p.*);
                const overhead_len = tls.record_header_len + P.record_iv_length + P.mac_length;
                while (true) {
                    const message_len: u16 = @min(
                        bytes.len - bytes_i,
                        tls.max_ciphertext_inner_record_len,
                        ciphertext_buf.len -| (overhead_len + ciphertext_end),
                    );
                    if (message_len == 0) return .{
                        .ciphertext_end = ciphertext_end,
                        .cleartext_len = bytes_i,
                    };

                    @memcpy(cleartext_buf[0..message_len], bytes[bytes_i..][0..message_len]);
                    bytes_i += message_len;
                    const cleartext = cleartext_buf[0..message_len];

                    const record_header = ciphertext_buf[ciphertext_end..][0..tls.record_header_len];
                    ciphertext_end += tls.record_header_len;
                    record_header.* = .{@backingInt(inner_content_type)} ++
                        int(u16, @backingInt(tls.ProtocolVersion.tls_1_2)) ++
                        int(u16, P.record_iv_length + message_len + P.mac_length);
                    const ad = mem.toBytes(big(c.write_seq)) ++ record_header[0 .. 1 + 2] ++ int(u16, message_len);
                    const record_iv = ciphertext_buf[ciphertext_end..][0..P.record_iv_length];
                    ciphertext_end += P.record_iv_length;
                    const nonce: [P.AEAD.nonce_length]u8 = nonce: {
                        const V = @Vector(P.AEAD.nonce_length, u8);
                        const pad: [P.AEAD.nonce_length - 8]u8 = @splat(0);
                        const operand: V = pad ++ @as([8]u8, @bitCast(@byteSwap(c.write_seq)));
                        break :nonce @as(V, pv.client_write_IV ++ pv.client_salt) ^ operand;
                    };
                    record_iv.* = nonce[P.fixed_iv_length..].*;
                    const ciphertext = ciphertext_buf[ciphertext_end..][0..message_len];
                    ciphertext_end += message_len;
                    const auth_tag = ciphertext_buf[ciphertext_end..][0..P.mac_length];
                    ciphertext_end += P.mac_length;
                    P.AEAD.encrypt(ciphertext, auth_tag, cleartext, ad, nonce, pv.client_write_key);
                    c.write_seq += 1; // TODO send key_update on overflow
                }
            },
            else => unreachable,
        },
    }
}

pub fn eof(c: Client) bool {
    return c.received_close_notify;
}

fn stream(r: *Reader, w: *Writer, limit: std.Io.Limit) Reader.StreamError!usize {
    // This function writes exclusively to the buffer.
    _ = w;
    _ = limit;
    const c: *Client = @alignCast(@fieldParentPtr("reader", r));
    return readIndirect(c);
}

fn readVec(r: *Reader, data: [][]u8) Reader.Error!usize {
    // This function writes exclusively to the buffer.
    _ = data;
    const c: *Client = @alignCast(@fieldParentPtr("reader", r));
    return readIndirect(c);
}

fn readIndirect(c: *Client) Reader.Error!usize {
    const r = &c.reader;
    if (c.eof()) return error.EndOfStream;
    const input = c.input;
    // If at least one full encrypted record is not buffered, read once.
    const record_header = input.peek(tls.record_header_len) catch |err| switch (err) {
        error.EndOfStream => {
            // This is either a truncation attack, a bug in the server, or an
            // intentional omission of the close_notify message due to truncation
            // detection handled above the TLS layer.
            if (c.allow_truncation_attacks) {
                c.received_close_notify = true;
                return error.EndOfStream;
            } else {
                return failRead(c, error.TlsConnectionTruncated);
            }
        },
        error.ReadFailed => |e| return e,
    };
    const ct: tls.ContentType = @fromBackingInt(@intCast(record_header[0]));
    const legacy_version = mem.readInt(u16, record_header[1..][0..2], .big);
    _ = legacy_version;
    const record_len = mem.readInt(u16, record_header[3..][0..2], .big);
    if (record_len > max_ciphertext_len) return failRead(c, error.TlsRecordOverflow);
    const record_end = 5 + record_len;
    if (record_end > input.buffered().len) {
        input.fillMore() catch |err| switch (err) {
            error.EndOfStream => return failRead(c, error.TlsConnectionTruncated),
            error.ReadFailed => |e| return e,
        };
        if (record_end > input.buffered().len) return 0;
    }

    const cleartext_len, const inner_ct: tls.ContentType = cleartext: switch (c.application_cipher) {
        inline else => |*p| switch (c.tls_version) {
            .tls_1_3 => {
                const pv = &p.tls_1_3;
                const P = @TypeOf(p.*);
                if (record_len < P.AEAD.tag_length) return failRead(c, error.TlsRecordOverflow);
                const ad = input.take(tls.record_header_len) catch unreachable; // already peeked
                const ciphertext_len = record_len - P.AEAD.tag_length;
                const ciphertext = input.take(ciphertext_len) catch unreachable; // already peeked
                const auth_tag = (input.takeArray(P.AEAD.tag_length) catch unreachable).*; // already peeked
                const nonce = nonce: {
                    const V = @Vector(P.AEAD.nonce_length, u8);
                    const pad: [P.AEAD.nonce_length - 8]u8 = @splat(0);
                    const operand: V = pad ++ mem.toBytes(big(c.read_seq));
                    break :nonce @as(V, pv.server_iv) ^ operand;
                };
                rebase(r, ciphertext.len);
                const cleartext = r.buffer[r.end..][0..ciphertext.len];
                P.AEAD.decrypt(cleartext, ciphertext, auth_tag, ad, nonce, pv.server_key) catch
                    return failRead(c, error.TlsBadRecordMac);
                // TODO use scalar, non-slice version
                const msg = mem.trimEnd(u8, cleartext, "\x00");
                if (msg.len == 0) return failRead(c, error.TlsDecodeError);
                break :cleartext .{ msg.len - 1, @fromBackingInt(@intCast(msg[msg.len - 1])) };
            },
            .tls_1_2 => {
                const pv = &p.tls_1_2;
                const P = @TypeOf(p.*);
                if (record_len < P.record_iv_length + P.mac_length) return failRead(c, error.TlsRecordOverflow);
                const message_len: u16 = record_len - P.record_iv_length - P.mac_length;
                const ad_header = input.take(tls.record_header_len) catch unreachable; // already peeked
                const ad = mem.toBytes(big(c.read_seq)) ++
                    ad_header[0 .. 1 + 2] ++
                    mem.toBytes(big(message_len));
                const record_iv = (input.takeArray(P.record_iv_length) catch unreachable).*; // already peeked
                const masked_read_seq = c.read_seq &
                    comptime std.math.shl(u64, std.math.maxInt(u64), 8 * P.record_iv_length);
                const nonce: [P.AEAD.nonce_length]u8 = nonce: {
                    const V = @Vector(P.AEAD.nonce_length, u8);
                    const pad: [P.AEAD.nonce_length - 8]u8 = @splat(0);
                    const operand: V = pad ++ @as([8]u8, @bitCast(@byteSwap(masked_read_seq)));
                    break :nonce @as(V, pv.server_write_IV ++ record_iv) ^ operand;
                };
                const ciphertext = input.take(message_len) catch unreachable; // already peeked
                const auth_tag = (input.takeArray(P.mac_length) catch unreachable).*; // already peeked
                rebase(r, ciphertext.len);
                const cleartext = r.buffer[r.end..][0..ciphertext.len];
                P.AEAD.decrypt(cleartext, ciphertext, auth_tag, ad, nonce, pv.server_write_key) catch
                    return failRead(c, error.TlsBadRecordMac);
                break :cleartext .{ cleartext.len, ct };
            },
            else => unreachable,
        },
    };
    const cleartext = r.buffer[r.end..][0..cleartext_len];
    c.read_seq = std.math.add(u64, c.read_seq, 1) catch return failRead(c, error.TlsSequenceOverflow);
    switch (inner_ct) {
        .alert => {
            if (cleartext.len != 2) return failRead(c, error.TlsDecodeError);
            const alert: tls.Alert = .{
                .level = @fromBackingInt(@intCast(cleartext[0])),
                .description = @fromBackingInt(@intCast(cleartext[1])),
            };
            switch (alert.description) {
                .close_notify => {
                    c.received_close_notify = true;
                    return 0;
                },
                .user_canceled => {
                    // TODO: handle server-side closures
                    return failRead(c, error.TlsUnexpectedMessage);
                },
                else => {
                    c.alert = alert;
                    return failRead(c, error.TlsAlert);
                },
            }
        },
        .handshake => {
            var ct_i: usize = 0;
            while (true) {
                const handshake_type: tls.HandshakeType = @fromBackingInt(@intCast(cleartext[ct_i]));
                ct_i += 1;
                const handshake_len = mem.readInt(u24, cleartext[ct_i..][0..3], .big);
                ct_i += 3;
                const next_handshake_i = ct_i + handshake_len;
                if (next_handshake_i > cleartext.len) return failRead(c, error.TlsBadLength);
                const handshake = cleartext[ct_i..next_handshake_i];
                switch (handshake_type) {
                    .new_session_ticket => {
                        // This client implementation ignores new session tickets.
                    },
                    .key_update => {
                        if (handshake.len != 1) return failRead(c, error.TlsDecodeError);
                        switch (c.application_cipher) {
                            inline else => |*p| {
                                const pv = &p.tls_1_3;
                                const P = @TypeOf(p.*);
                                const server_secret = hkdfExpandLabel(P.Hkdf, pv.server_secret, "traffic upd", "", P.Hash.digest_length);
                                if (c.ssl_key_log) |key_log| logSecrets(key_log.writer, .{
                                    .counter = key_log.serverCounter(),
                                    .client_random = &key_log.client_random,
                                }, .{
                                    .SERVER_TRAFFIC_SECRET = &server_secret,
                                });
                                pv.server_secret = server_secret;
                                pv.server_key = hkdfExpandLabel(P.Hkdf, server_secret, "key", "", P.AEAD.key_length);
                                pv.server_iv = hkdfExpandLabel(P.Hkdf, server_secret, "iv", "", P.AEAD.nonce_length);
                            },
                        }
                        c.read_seq = 0;

                        switch (@as(tls.KeyUpdateRequest, @fromBackingInt(@intCast(handshake[0])))) {
                            .update_requested => {
                                switch (c.application_cipher) {
                                    inline else => |*p| {
                                        const pv = &p.tls_1_3;
                                        const P = @TypeOf(p.*);
                                        const client_secret = hkdfExpandLabel(P.Hkdf, pv.client_secret, "traffic upd", "", P.Hash.digest_length);
                                        if (c.ssl_key_log) |key_log| logSecrets(key_log.writer, .{
                                            .counter = key_log.clientCounter(),
                                            .client_random = &key_log.client_random,
                                        }, .{
                                            .CLIENT_TRAFFIC_SECRET = &client_secret,
                                        });
                                        pv.client_secret = client_secret;
                                        pv.client_key = hkdfExpandLabel(P.Hkdf, client_secret, "key", "", P.AEAD.key_length);
                                        pv.client_iv = hkdfExpandLabel(P.Hkdf, client_secret, "iv", "", P.AEAD.nonce_length);
                                    },
                                }
                                c.write_seq = 0;
                            },
                            .update_not_requested => {},
                            _ => return failRead(c, error.TlsIllegalParameter),
                        }
                    },
                    else => return failRead(c, error.TlsUnexpectedMessage),
                }
                ct_i = next_handshake_i;
                if (ct_i >= cleartext.len) break;
            }
            return 0;
        },
        .application_data => {
            r.end += cleartext.len;
            return 0;
        },
        else => return failRead(c, error.TlsUnexpectedMessage),
    }
}

fn rebase(r: *Reader, capacity: usize) void {
    if (r.buffer.len - r.end >= capacity) return;
    const data = r.buffer[r.seek..r.end];
    @memmove(r.buffer[0..data.len], data);
    r.seek = 0;
    r.end = data.len;
    assert(r.buffer.len - r.end >= capacity);
}

fn failRead(c: *Client, err: ReadError) error{ReadFailed} {
    c.read_err = err;
    return error.ReadFailed;
}

fn logSecrets(w: *Writer, context: anytype, secrets: anytype) void {
    inline for (@typeInfo(@TypeOf(secrets)).@"struct".field_names) |field_name| w.print("{s}" ++
        (if (@hasField(@TypeOf(context), "counter")) "_{d}" else "") ++ " {x} {x}\n", .{field_name} ++
        (if (@hasField(@TypeOf(context), "counter")) .{context.counter} else .{}) ++ .{
        context.client_random,
        @field(secrets, field_name),
    }) catch {};
}

fn big(x: anytype) @TypeOf(x) {
    return switch (native_endian) {
        .big => x,
        .little => @byteSwap(x),
    };
}

const KeyShare = struct {
    ml_kem768_kp: crypto.kem.ml_kem.MLKem768.KeyPair,
    secp256r1_kp: crypto.sign.ecdsa.EcdsaP256Sha256.KeyPair,
    secp384r1_kp: crypto.sign.ecdsa.EcdsaP384Sha384.KeyPair,
    x25519_kp: crypto.dh.X25519.KeyPair,
    sk_buf: [sk_max_len]u8,
    sk_len: std.math.IntFittingRange(0, sk_max_len),

    const sk_max_len = @max(
        crypto.dh.X25519.shared_length + crypto.kem.ml_kem.MLKem768.shared_length,
        crypto.ecc.P256.scalar.encoded_length,
        crypto.ecc.P384.scalar.encoded_length,
        crypto.dh.X25519.shared_length,
    );

    fn init(seed: *const [176]u8) error{IdentityElement}!KeyShare {
        return .{
            .ml_kem768_kp = try .generateDeterministic(seed[0..64].*),
            .secp256r1_kp = try .generateDeterministic(seed[64..96].*),
            .secp384r1_kp = try .generateDeterministic(seed[96..144].*),
            .x25519_kp = .generateDeterministic(seed[144..176].*),
            .sk_buf = undefined,
            .sk_len = 0,
        };
    }

    fn exchange(
        ks: *KeyShare,
        named_group: tls.NamedGroup,
        server_pub_key: []const u8,
    ) error{ TlsIllegalParameter, TlsDecryptFailure }!void {
        switch (named_group) {
            .x25519_ml_kem768 => {
                const hksl = crypto.kem.ml_kem.MLKem768.ciphertext_length;
                const xksl = hksl + crypto.dh.X25519.public_length;
                if (server_pub_key.len != xksl) return error.TlsIllegalParameter;

                const hsk = ks.ml_kem768_kp.secret_key.decaps(server_pub_key[0..hksl]) catch
                    return error.TlsDecryptFailure;
                const xsk = crypto.dh.X25519.scalarmult(ks.x25519_kp.secret_key, server_pub_key[hksl..xksl].*) catch
                    return error.TlsDecryptFailure;
                @memcpy(ks.sk_buf[0..hsk.len], &hsk);
                @memcpy(ks.sk_buf[hsk.len..][0..xsk.len], &xsk);
                ks.sk_len = hsk.len + xsk.len;
            },
            .secp256r1 => {
                const PublicKey = crypto.sign.ecdsa.EcdsaP256Sha256.PublicKey;
                const pk = PublicKey.fromSec1(server_pub_key) catch return error.TlsDecryptFailure;
                const mul = pk.p.mulPublic(ks.secp256r1_kp.secret_key.bytes, .big) catch
                    return error.TlsDecryptFailure;
                const sk = mul.affineCoordinates().x.toBytes(.big);
                @memcpy(ks.sk_buf[0..sk.len], &sk);
                ks.sk_len = sk.len;
            },
            .secp384r1 => {
                const PublicKey = crypto.sign.ecdsa.EcdsaP384Sha384.PublicKey;
                const pk = PublicKey.fromSec1(server_pub_key) catch return error.TlsDecryptFailure;
                const mul = pk.p.mulPublic(ks.secp384r1_kp.secret_key.bytes, .big) catch
                    return error.TlsDecryptFailure;
                const sk = mul.affineCoordinates().x.toBytes(.big);
                @memcpy(ks.sk_buf[0..sk.len], &sk);
                ks.sk_len = sk.len;
            },
            .x25519 => {
                const ksl = crypto.dh.X25519.public_length;
                if (server_pub_key.len != ksl) return error.TlsIllegalParameter;
                const sk = crypto.dh.X25519.scalarmult(ks.x25519_kp.secret_key, server_pub_key[0..ksl].*) catch
                    return error.TlsDecryptFailure;
                @memcpy(ks.sk_buf[0..sk.len], &sk);
                ks.sk_len = sk.len;
            },
            else => return error.TlsIllegalParameter,
        }
    }

    fn getSharedSecret(ks: *const KeyShare) ?[]const u8 {
        return if (ks.sk_len > 0) ks.sk_buf[0..ks.sk_len] else null;
    }
};

fn SchemeEcdsa(comptime scheme: tls.SignatureScheme) type {
    return switch (scheme) {
        .ecdsa_secp256r1_sha256 => crypto.sign.ecdsa.EcdsaP256Sha256,
        .ecdsa_secp384r1_sha384 => crypto.sign.ecdsa.EcdsaP384Sha384,
        else => @compileError("bad scheme"),
    };
}

fn SchemeRsa(comptime scheme: tls.SignatureScheme) type {
    return switch (scheme) {
        .rsa_pkcs1_sha256,
        .rsa_pkcs1_sha384,
        .rsa_pkcs1_sha512,
        .rsa_pkcs1_sha1,
        => Certificate.rsa.PKCS1v1_5Signature,
        .rsa_pss_rsae_sha256,
        .rsa_pss_rsae_sha384,
        .rsa_pss_rsae_sha512,
        .rsa_pss_pss_sha256,
        .rsa_pss_pss_sha384,
        .rsa_pss_pss_sha512,
        => Certificate.rsa.PSSSignature,
        else => @compileError("bad scheme"),
    };
}

fn SchemeEddsa(comptime scheme: tls.SignatureScheme) type {
    return switch (scheme) {
        .ed25519 => crypto.sign.Ed25519,
        else => @compileError("bad scheme"),
    };
}

fn SchemeHash(comptime scheme: tls.SignatureScheme) type {
    return switch (scheme) {
        .rsa_pkcs1_sha256,
        .ecdsa_secp256r1_sha256,
        .rsa_pss_rsae_sha256,
        .rsa_pss_pss_sha256,
        => crypto.hash.sha2.Sha256,
        .rsa_pkcs1_sha384,
        .ecdsa_secp384r1_sha384,
        .rsa_pss_rsae_sha384,
        .rsa_pss_pss_sha384,
        => crypto.hash.sha2.Sha384,
        .rsa_pkcs1_sha512,
        .ecdsa_secp521r1_sha512,
        .rsa_pss_rsae_sha512,
        .rsa_pss_pss_sha512,
        => crypto.hash.sha2.Sha512,
        .rsa_pkcs1_sha1,
        .ecdsa_sha1,
        => crypto.hash.Sha1,
        else => @compileError("bad scheme"),
    };
}

const CertificatePublicKey = struct {
    algo: Certificate.AlgorithmCategory,
    buf: [600]u8,
    len: u16,

    fn init(
        cert_pub_key: *CertificatePublicKey,
        algo: Certificate.AlgorithmCategory,
        pub_key: []const u8,
    ) error{CertificatePublicKeyInvalid}!void {
        if (pub_key.len > cert_pub_key.buf.len) return error.CertificatePublicKeyInvalid;
        cert_pub_key.algo = algo;
        @memcpy(cert_pub_key.buf[0..pub_key.len], pub_key);
        cert_pub_key.len = @intCast(pub_key.len);
    }

    const VerifyError = error{ TlsDecodeError, TlsBadSignatureScheme, InvalidEncoding } ||
        // ecdsa
        crypto.errors.EncodingError ||
        crypto.errors.NotSquareError ||
        crypto.errors.NonCanonicalError ||
        SchemeEcdsa(.ecdsa_secp256r1_sha256).Signature.VerifyError ||
        SchemeEcdsa(.ecdsa_secp384r1_sha384).Signature.VerifyError ||
        // rsa
        error{TlsBadRsaSignatureBitCount} ||
        Certificate.rsa.PublicKey.ParseDerError ||
        Certificate.rsa.PublicKey.FromBytesError ||
        Certificate.rsa.PSSSignature.VerifyError ||
        Certificate.rsa.PKCS1v1_5Signature.VerifyError ||
        // eddsa
        SchemeEddsa(.ed25519).Signature.VerifyError;

    fn verifySignature(
        cert_pub_key: *const CertificatePublicKey,
        sigd: *tls.Decoder,
        msg: []const []const u8,
    ) VerifyError!void {
        const pub_key = cert_pub_key.buf[0..cert_pub_key.len];

        try sigd.ensure(2 + 2);
        const scheme = sigd.decode(tls.SignatureScheme);
        const sig_len = sigd.decode(u16);
        try sigd.ensure(sig_len);
        const encoded_sig = sigd.slice(sig_len);

        if (cert_pub_key.algo != @as(Certificate.AlgorithmCategory, switch (scheme) {
            .ecdsa_secp256r1_sha256,
            .ecdsa_secp384r1_sha384,
            => .X9_62_id_ecPublicKey,
            .rsa_pkcs1_sha256,
            .rsa_pkcs1_sha384,
            .rsa_pkcs1_sha512,
            .rsa_pss_rsae_sha256,
            .rsa_pss_rsae_sha384,
            .rsa_pss_rsae_sha512,
            .rsa_pkcs1_sha1,
            => .rsaEncryption,
            .rsa_pss_pss_sha256,
            .rsa_pss_pss_sha384,
            .rsa_pss_pss_sha512,
            => .rsassa_pss,
            else => return error.TlsBadSignatureScheme,
        })) return error.TlsBadSignatureScheme;

        switch (scheme) {
            inline .ecdsa_secp256r1_sha256,
            .ecdsa_secp384r1_sha384,
            => |comptime_scheme| {
                const Ecdsa = SchemeEcdsa(comptime_scheme);
                const sig = try Ecdsa.Signature.fromDer(encoded_sig);
                const key = try Ecdsa.PublicKey.fromSec1(pub_key);
                var ver = try sig.verifier(key);
                for (msg) |part| ver.update(part);
                try ver.verify();
            },
            inline .rsa_pkcs1_sha256,
            .rsa_pkcs1_sha384,
            .rsa_pkcs1_sha512,
            .rsa_pss_rsae_sha256,
            .rsa_pss_rsae_sha384,
            .rsa_pss_rsae_sha512,
            .rsa_pss_pss_sha256,
            .rsa_pss_pss_sha384,
            .rsa_pss_pss_sha512,
            .rsa_pkcs1_sha1,
            => |comptime_scheme| {
                const RsaSignature = SchemeRsa(comptime_scheme);
                const Hash = SchemeHash(comptime_scheme);
                const PublicKey = Certificate.rsa.PublicKey;
                const components = try PublicKey.parseDer(pub_key);
                const exponent = components.exponent;
                const modulus = components.modulus;
                switch (modulus.len) {
                    inline 128, 256, 384, 512 => |modulus_len| {
                        const key: PublicKey = try .fromBytes(exponent, modulus);
                        const sig = RsaSignature.fromBytes(modulus_len, encoded_sig);
                        try RsaSignature.concatVerify(modulus_len, &sig, msg, key, Hash);
                    },
                    else => return error.TlsBadRsaSignatureBitCount,
                }
            },
            inline .ed25519 => |comptime_scheme| {
                const Eddsa = SchemeEddsa(comptime_scheme);
                if (encoded_sig.len != Eddsa.Signature.encoded_length) return error.InvalidEncoding;
                const sig = Eddsa.Signature.fromBytes(encoded_sig[0..Eddsa.Signature.encoded_length].*);
                if (pub_key.len != Eddsa.PublicKey.encoded_length) return error.InvalidEncoding;
                const key = try Eddsa.PublicKey.fromBytes(pub_key[0..Eddsa.PublicKey.encoded_length].*);
                var ver = try sig.verifier(key);
                for (msg) |part| ver.update(part);
                try ver.verify();
            },
            else => unreachable,
        }
    }
};

fn tryDownloadRootCert(chain: *Certificate.Chain, options: *const Options) !void {
    if (Certificate.Chain != void) switch (options.ca) {
        else => {},
        .bundle => |ca| {
            chain.verify(options.realtime_now) catch |err| switch (err) {
                error.Unexpected => return error.TlsCertificateNotVerified,
                else => |e| return e,
            };
            var bundle: Certificate.Bundle = .empty;
            defer bundle.deinit(ca.gpa);
            if (bundle.rescan(ca.gpa, ca.io, options.realtime_now)) {
                try ca.lock.lock(ca.io);
                defer ca.lock.unlock(ca.io);
                std.mem.swap(Certificate.Bundle, ca.bundle, &bundle);
            } else |err| switch (err) {
                error.Canceled => |e| return e,
                else => {},
            }
            return; // the os has verified the certificate for us
        },
    };
    return error.TlsCertificateNotVerified;
}

// ---- Velo patch: certificate checks upstream does not make ----

/// Where an issuer came from: the server's chain, or the trusted CA directories.
const IssuerSource = enum { presented, anchor };

/// The most certificates a server's chain may have. Real chains have 2 to 4.
const max_path = 10;

/// `subject` verified by the trusted certificate that issued it (`Bundle.verify`), which
/// then has to be allowed to sign with `below` counted intermediates under it, and whose
/// nameConstraints hold for `path`: the presented certificates from the server's to
/// `subject`. A trusted certificate identical to `subject` is trusted as itself and signs
/// nothing else.
fn verifyByAnchor(bundle: Certificate.Bundle, subject: Certificate.Parsed, now_sec: i64, below: usize, path: []const Certificate.Parsed) !void {
    const index = bundle.find(subject.issuer()) orelse return error.CertificateIssuerNotFound;
    const anchor_cert: Certificate = .{ .buffer = bundle.bytes.items, .index = index };
    // Every certificate in the bundle was parsed before it was added.
    const anchor = anchor_cert.parse() catch unreachable;
    try subject.verify(anchor, now_sec);
    if (mem.eql(u8, try certBytes(anchor.certificate), try certBytes(subject.certificate))) return;
    try checkIssuer(anchor, .anchor, below, now_sec);
    try checkNameConstraints(anchor, path);
}

/// `issuer` signed a certificate that has `below` intermediates under it that are not
/// self-issued (0: it signed the server's certificate; RFC 5280 6.1.4(l)). It must be a
/// CA whose path length allows that, with keyCertSign when it has keyUsage. A trusted
/// certificate without basicConstraints still counts as a CA when it is a self-signed
/// v1 root or has keyCertSign, as in OpenSSL's check_ca; a presented one never does. A
/// presented CA is part of the path, so a critical extension this client does not
/// process makes it unusable (6.1.4(o)); a trusted one is the trust anchor, outside it.
fn checkIssuer(issuer: Certificate.Parsed, source: IssuerSource, below: usize, now_sec: i64) !void {
    const ext = try extensions(issuer.certificate);
    if (source == .presented and ext.unknown_critical) return error.TlsCertificateUnknownCriticalExtension;
    if (ext.basic) |basic| {
        if (!basic.ca) return error.TlsCertificateIssuerNotCa;
    } else switch (source) {
        .presented => return error.TlsCertificateIssuerNotCa,
        .anchor => if (ext.key_cert_sign == null and !(issuer.version == .v1 and selfSigned(issuer, now_sec)))
            return error.TlsCertificateIssuerNotCa,
    }
    if (ext.key_cert_sign) |sign| if (!sign) return error.TlsCertificateIssuerKeyUsage;
    if (ext.basic) |basic| if (basic.path_len) |max| if (below > max) return error.TlsCertificatePathLenExceeded;
}

/// The server's certificate: no critical extension this client does not process (RFC 5280
/// 6.1.4(o)), and an extendedKeyUsage, when it has one, that allows a TLS server, with the
/// purposes Android's TrustManagerImpl accepts.
fn checkLeaf(leaf: Certificate.Parsed) !void {
    try checkNameTags(leaf);
    const ext = try extensions(leaf.certificate);
    if (ext.unknown_critical) return error.TlsCertificateUnknownCriticalExtension;
    if (ext.server_auth) |allowed| if (!allowed) return error.TlsCertificateExtendedKeyUsage;
}

/// Issuer and subject are the same, non-empty name (RFC 5280 3.2, "self-issued").
fn selfIssued(cert: Certificate.Parsed) bool {
    return cert.subject().len > 0 and mem.eql(u8, cert.subject(), cert.issuer());
}

/// Self-issued, and signed with its own key.
fn selfSigned(cert: Certificate.Parsed, now_sec: i64) bool {
    if (!selfIssued(cert)) return false;
    cert.verify(cert, now_sec) catch return false;
    return true;
}

const Extensions = struct {
    /// null: no basicConstraints extension.
    basic: ?struct { ca: bool, path_len: ?u64 } = null,
    /// null: no keyUsage extension; else whether it has keyCertSign.
    key_cert_sign: ?bool = null,
    /// null: no extendedKeyUsage extension; else whether it allows a TLS server.
    server_auth: ?bool = null,
    /// The NameConstraints SEQUENCE of the nameConstraints extension, when there is one.
    name_constraints: ?[]const u8 = null,
    /// A critical extension that is none of the ones `extensions` names.
    unknown_critical: bool = false,
};

const oid_basic_constraints = [_]u8{ 0x55, 0x1d, 0x13 }; // 2.5.29.19
const oid_key_usage = [_]u8{ 0x55, 0x1d, 0x0f }; // 2.5.29.15
const oid_ext_key_usage = [_]u8{ 0x55, 0x1d, 0x25 }; // 2.5.29.37
const oid_subject_alt_name = [_]u8{ 0x55, 0x1d, 0x11 }; // 2.5.29.17
const oid_name_constraints = [_]u8{ 0x55, 0x1d, 0x1e }; // 2.5.29.30
const oid_certificate_policies = [_]u8{ 0x55, 0x1d, 0x20 }; // 2.5.29.32

/// The extendedKeyUsage purposes that allow a TLS server, as in Android's
/// ExtendedKeyUsagePKIXCertPathChecker: serverAuth, anyExtendedKeyUsage, and the
/// Netscape and Microsoft server gated cryptography purposes.
const server_purposes = [_][]const u8{
    &.{ 0x2b, 0x06, 0x01, 0x05, 0x05, 0x07, 0x03, 0x01 }, // 1.3.6.1.5.5.7.3.1
    &.{ 0x55, 0x1d, 0x25, 0x00 }, // 2.5.29.37.0
    &.{ 0x60, 0x86, 0x48, 0x01, 0x86, 0xf8, 0x42, 0x04, 0x01 }, // 2.16.840.1.113730.4.1
    &.{ 0x2b, 0x06, 0x01, 0x04, 0x01, 0x82, 0x37, 0x0a, 0x03, 0x03 }, // 1.3.6.1.4.1.311.10.3.3
};

/// The extensions of `cert` this client processes (RFC 5280 4.2.1.3, 4.2.1.9, 4.2.1.10,
/// 4.2.1.12), and whether it has a critical one besides those, subjectAltName and
/// certificatePolicies (checked by host name, and accepted for any policy). The policy
/// constraint extensions are not processed: marked critical, they are unknown.
/// Bounds-checked: a presented certificate is untrusted input.
fn extensions(cert: Certificate) !Extensions {
    var out: Extensions = .{};
    const certificate = try derRead(try certBytes(cert), 0x30);
    const tbs = try derRead(certificate.body, 0x30);
    // Extensions are the [3] EXPLICIT field, the last one of the TBSCertificate.
    var fields = tbs.body;
    const list = while (fields.len > 0) {
        const field = try derNext(fields);
        fields = field.rest;
        if (field.tag == 0xa3) break (try derRead(field.body, 0x30)).body;
    } else return out;

    var rest = list;
    while (rest.len > 0) {
        const extension = try derRead(rest, 0x30);
        rest = extension.rest;
        const oid = try derRead(extension.body, 0x06);
        var value = try derNext(oid.rest);
        var critical = false;
        if (value.tag == 0x01) {
            if (value.body.len != 1) return error.CertificateFieldHasInvalidLength;
            critical = value.body[0] != 0;
            value = try derNext(value.rest);
        }
        if (value.tag != 0x04) return error.CertificateFieldHasWrongDataType;
        if (mem.eql(u8, oid.body, &oid_basic_constraints)) {
            if (out.basic != null) return error.CertificateFieldHasWrongDataType;
            const seq = try derRead(value.body, 0x30);
            var ca = false;
            var path_len: ?u64 = null;
            var items = seq.body;
            if (items.len > 0 and items[0] == 0x01) {
                const flag = try derNext(items);
                if (flag.body.len != 1) return error.CertificateFieldHasInvalidLength;
                ca = flag.body[0] != 0;
                items = flag.rest;
            }
            if (items.len > 0) {
                const len = try derRead(items, 0x02);
                path_len = try derUint(len.body);
            }
            out.basic = .{ .ca = ca, .path_len = path_len };
        } else if (mem.eql(u8, oid.body, &oid_key_usage)) {
            if (out.key_cert_sign != null) return error.CertificateFieldHasWrongDataType;
            const bits = try derRead(value.body, 0x03);
            // First byte: unused bit count. keyCertSign is bit 5: 0x04 of the next byte.
            out.key_cert_sign = bits.body.len >= 2 and bits.body[1] & 0x04 != 0;
        } else if (mem.eql(u8, oid.body, &oid_ext_key_usage)) {
            if (out.server_auth != null) return error.CertificateFieldHasWrongDataType;
            var purposes = (try derRead(value.body, 0x30)).body;
            var allowed = false;
            while (purposes.len > 0) {
                const purpose = try derRead(purposes, 0x06);
                purposes = purpose.rest;
                for (server_purposes) |known| allowed = allowed or mem.eql(u8, purpose.body, known);
            }
            out.server_auth = allowed;
        } else if (mem.eql(u8, oid.body, &oid_name_constraints)) {
            if (out.name_constraints != null) return error.CertificateFieldHasWrongDataType;
            out.name_constraints = (try derRead(value.body, 0x30)).body;
        } else if (critical and !mem.eql(u8, oid.body, &oid_subject_alt_name) and
            !mem.eql(u8, oid.body, &oid_certificate_policies))
        {
            out.unknown_critical = true;
        }
    }
    return out;
}

/// The subjectAltName entries of `cert` are GeneralNames: otherName [0] to registeredID [8]
/// (RFC 5280 4.2.1.6), with the tags DER gives them. Upstream's host match reads only the
/// tag number, so a dNSName written with another tag class would match a host while the
/// name constraints passed it by as a name of no constrained form.
fn checkNameTags(cert: Certificate.Parsed) !void {
    const san = cert.subjectAltName();
    if (san.len == 0) return;
    var names = (try derRead(san, 0x30)).body;
    while (names.len > 0) {
        const name = try derNext(names);
        names = name.rest;
        if (mem.indexOfScalar(u8, &general_name_tags, name.tag) == null) return error.CertificateFieldHasWrongDataType;
    }
}

const general_name_tags = [_]u8{ 0xa0, 0x81, 0x82, 0xa3, 0xa4, 0xa5, 0x86, 0x87, 0x88 };

/// GeneralName tags (RFC 5280 4.2.1.6), context-specific: the forms checked here.
const name_dns: u8 = 0x82; // [2] IMPLICIT IA5String
const name_ip: u8 = 0x87; // [7] IMPLICIT OCTET STRING
const name_directory: u8 = 0xa4; // [4] EXPLICIT Name
const name_rfc822: u8 = 0x81; // [1] IMPLICIT IA5String

const oid_email_address = [_]u8{ 0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 0x01, 0x09, 0x01 }; // 1.2.840.113549.1.9.1

/// RFC 5280 4.2.1.10 and 6.1.3(b): the nameConstraints of `issuer` hold for every
/// certificate of `path` (the ones below it, the server's first) except a self-issued
/// intermediate. dNSName, iPAddress and directoryName constraints are checked. A
/// constraint on another name form fails the chain when a certificate has a name of
/// that form, as RFC 5280 asks: process it, or reject.
fn checkNameConstraints(issuer: Certificate.Parsed, path: []const Certificate.Parsed) !void {
    const constraints = (try extensions(issuer.certificate)).name_constraints orelse return;
    var permitted: []const u8 = &.{};
    var excluded: []const u8 = &.{};
    var fields = constraints;
    while (fields.len > 0) {
        const field = try derNext(fields);
        fields = field.rest;
        switch (field.tag) {
            0xa0 => permitted = field.body, // [0] permittedSubtrees
            0xa1 => excluded = field.body, // [1] excludedSubtrees
            else => return error.CertificateFieldHasWrongDataType,
        }
    }
    for (path, 0..) |cert, i| {
        if (i > 0 and selfIssued(cert)) continue;
        try checkNames(cert, i == 0, permitted, excluded);
    }
}

/// Every name of `cert` within `permitted` and outside `excluded`: its subjectAltName
/// entries, its subject (a directoryName, and any emailAddress in it, an rfc822Name), and
/// the common name of a server certificate without subjectAltName, which is then its
/// host name.
fn checkNames(cert: Certificate.Parsed, server: bool, permitted: []const u8, excluded: []const u8) !void {
    try checkNameTags(cert);
    const san = cert.subjectAltName();
    if (san.len > 0) {
        var names = (try derRead(san, 0x30)).body;
        while (names.len > 0) {
            const name = try derNext(names);
            names = name.rest;
            // A directoryName holds a Name SEQUENCE; its RDNs are what is compared.
            const body = if (name.tag == name_directory) (try derRead(name.body, 0x30)).body else name.body;
            try checkName(name.tag, body, permitted, excluded);
        }
    } else if (server and cert.commonName().len > 0) {
        try checkName(name_dns, cert.commonName(), permitted, excluded);
    }
    const subject = cert.subject();
    if (subject.len == 0) return;
    try checkName(name_directory, subject, permitted, excluded);
    var rdns = subject;
    while (rdns.len > 0) {
        const rdn = try derRead(rdns, 0x31);
        rdns = rdn.rest;
        var attributes = rdn.body;
        while (attributes.len > 0) {
            const attribute = try derRead(attributes, 0x30);
            attributes = attribute.rest;
            const kind = try derRead(attribute.body, 0x06);
            if (mem.eql(u8, kind.body, &oid_email_address)) try checkName(name_rfc822, (try derNext(kind.rest)).body, permitted, excluded);
        }
    }
}

/// One name of form `tag`: within a permitted subtree of that form when there are any,
/// and in no excluded subtree of that form.
fn checkName(tag: u8, name: []const u8, permitted: []const u8, excluded: []const u8) !void {
    var constrained = false;
    var allowed = false;
    var subtrees = permitted;
    while (subtrees.len > 0) {
        const base = try subtreeBase(&subtrees);
        if (base.tag != tag) continue;
        constrained = true;
        if (try nameMatches(tag, base.body, name)) allowed = true;
    }
    if (constrained and !allowed) return error.TlsCertificateNameConstraint;
    subtrees = excluded;
    while (subtrees.len > 0) {
        const base = try subtreeBase(&subtrees);
        if (base.tag != tag) continue;
        if (try nameMatches(tag, base.body, name)) return error.TlsCertificateNameConstraint;
        if (tag == name_dns and wildcardReaches(base.body, name)) return error.TlsCertificateNameConstraint;
    }
}

/// The base GeneralName of the next GeneralSubtree in `subtrees`, which moves past it.
/// RFC 5280 requires minimum 0 and no maximum; a subtree with either is not understood.
fn subtreeBase(subtrees: *[]const u8) !Der {
    const subtree = try derRead(subtrees.*, 0x30);
    subtrees.* = subtree.rest;
    const base = try derNext(subtree.body);
    if (base.rest.len > 0) return error.TlsCertificateNameConstraintUnsupported;
    if (base.tag != name_directory) return base;
    return .{ .tag = base.tag, .body = (try derRead(base.body, 0x30)).body, .rest = base.rest };
}

/// Whether `name` is in the subtree `constraint`, both of form `tag`.
fn nameMatches(tag: u8, constraint: []const u8, name: []const u8) !bool {
    return switch (tag) {
        name_dns => dnsInSubtree(constraint, name),
        name_ip => try ipInSubtree(constraint, name),
        name_directory => try directoryInSubtree(constraint, name),
        else => error.TlsCertificateNameConstraintUnsupported,
    };
}

/// RFC 5280 4.2.1.10: a DNS name is in the subtree when adding labels to the left of the
/// constraint makes it; "" holds every name. A constraint with a leading period, as some
/// CAs write, holds only names below it. Case does not matter.
fn dnsInSubtree(constraint: []const u8, name: []const u8) bool {
    if (constraint.len == 0) return true;
    if (constraint[0] == '.') return name.len > constraint.len and std.ascii.endsWithIgnoreCase(name, constraint);
    if (std.ascii.eqlIgnoreCase(name, constraint)) return true;
    return name.len > constraint.len and name[name.len - constraint.len - 1] == '.' and
        std.ascii.endsWithIgnoreCase(name, constraint);
}

/// A wildcard name ("*.example.com") stands for every name one label left of its base, so
/// it reaches an excluded subtree that is one of them ("bank.example.com"): that name, and
/// the names below it, are what the host match would take it for. Case does not matter.
fn wildcardReaches(constraint: []const u8, name: []const u8) bool {
    if (!mem.startsWith(u8, name, "*.")) return false;
    const base = name[1..];
    if (constraint.len <= base.len or !std.ascii.endsWithIgnoreCase(constraint, base)) return false;
    return mem.indexOfScalar(u8, constraint[0 .. constraint.len - base.len], '.') == null;
}

/// An iPAddress subtree is an address and a mask of its length (8 or 32 bytes in all);
/// a name of the other address family is not in it.
fn ipInSubtree(constraint: []const u8, name: []const u8) !bool {
    if (constraint.len != 8 and constraint.len != 32) return error.CertificateFieldHasInvalidLength;
    const half = constraint.len / 2;
    if (name.len != half) return false;
    for (name, constraint[0..half], constraint[half..]) |byte, address, mask| {
        if (byte & mask != address & mask) return false;
    }
    return true;
}

/// A directoryName is in the subtree when the constraint's RDNs begin it, each RDN equal
/// as `sameRdn` compares them.
fn directoryInSubtree(constraint: []const u8, name: []const u8) !bool {
    var want = constraint;
    var have = name;
    while (want.len > 0) {
        if (have.len == 0) return false;
        const a = try derRead(want, 0x31);
        const b = try derRead(have, 0x31);
        if (!try sameRdn(a.body, b.body)) return false;
        want = a.rest;
        have = b.rest;
    }
    return true;
}

/// Two RDNs with the same attribute types in the same order, the values equal as
/// `sameValue` compares them.
fn sameRdn(a: []const u8, b: []const u8) !bool {
    var x = a;
    var y = b;
    while (x.len > 0 and y.len > 0) {
        const p = try derRead(x, 0x30);
        const q = try derRead(y, 0x30);
        x = p.rest;
        y = q.rest;
        const p_type = try derRead(p.body, 0x06);
        const q_type = try derRead(q.body, 0x06);
        if (!mem.eql(u8, p_type.body, q_type.body)) return false;
        if (!try sameValue(try derNext(p_type.rest), try derNext(q_type.rest))) return false;
    }
    return x.len == 0 and y.len == 0;
}

/// RFC 5280 7.1 compares names after the string preparation of RFC 4518. As OpenSSL's
/// canonical names approximate it, string values compare as Unicode text with ASCII case
/// folded, the spaces at the ends dropped and each run of spaces inside made one, whatever
/// string type holds them; other values compare as bytes.
fn sameValue(a: Der, b: Der) !bool {
    var x = FoldedText.init(a) orelse return a.tag == b.tag and mem.eql(u8, a.body, b.body);
    var y = FoldedText.init(b) orelse return false;
    while (true) {
        const c = try x.next();
        if (c != try y.next()) return false;
        if (c == null) return true;
    }
}

/// The characters of a directory string value, folded as `sameValue` compares them.
const FoldedText = struct {
    /// Bytes per character: UTF8String varies (0), BMPString 2, UniversalString 4, the
    /// others (PrintableString, TeletexString as Latin-1, IA5String, VisibleString) 1.
    width: u3,
    rest: []const u8,
    started: bool = false,
    pending: ?u21 = null,

    fn init(value: Der) ?FoldedText {
        const width: u3 = switch (value.tag) {
            0x0c => 0,
            0x1e => 2,
            0x1c => 4,
            0x13, 0x14, 0x16, 0x1a => 1,
            else => return null,
        };
        return .{ .width = width, .rest = value.body };
    }

    fn next(self: *FoldedText) !?u21 {
        if (self.pending) |c| {
            self.pending = null;
            return c;
        }
        var c = (try self.raw()) orelse return null;
        if (c < 0x80 and std.ascii.isWhitespace(@intCast(c))) {
            while (c < 0x80 and std.ascii.isWhitespace(@intCast(c))) c = (try self.raw()) orelse return null;
            if (self.started) {
                self.pending = fold(c);
                return ' ';
            }
        }
        self.started = true;
        return fold(c);
    }

    fn fold(c: u21) u21 {
        return if (c < 0x80) std.ascii.toLower(@intCast(c)) else c;
    }

    fn raw(self: *FoldedText) !?u21 {
        if (self.rest.len == 0) return null;
        const len: usize = if (self.width == 0)
            std.unicode.utf8ByteSequenceLength(self.rest[0]) catch return error.CertificateFieldHasWrongDataType
        else
            self.width;
        if (self.rest.len < len) return error.CertificateFieldHasInvalidLength;
        const bytes = self.rest[0..len];
        self.rest = self.rest[len..];
        return switch (self.width) {
            0 => std.unicode.utf8Decode(bytes) catch error.CertificateFieldHasWrongDataType,
            2 => mem.readInt(u16, bytes[0..2], .big),
            4 => std.math.cast(u21, mem.readInt(u32, bytes[0..4], .big)) orelse error.CertificateFieldHasWrongDataType,
            else => bytes[0],
        };
    }
};

/// The whole DER encoding of `cert` (it may sit inside a larger buffer).
fn certBytes(cert: Certificate) ![]const u8 {
    if (cert.index >= cert.buffer.len) return error.CertificateFieldHasInvalidLength;
    const from = cert.buffer[cert.index..];
    const element = try derNext(from);
    return from[0 .. from.len - element.rest.len];
}

const Der = struct { tag: u8, body: []const u8, rest: []const u8 };

/// The DER element at the start of `bytes` (single-byte tags, definite lengths).
fn derNext(bytes: []const u8) !Der {
    if (bytes.len < 2) return error.CertificateFieldHasInvalidLength;
    var len: usize = bytes[1];
    var at: usize = 2;
    if (len & 0x80 != 0) {
        const n = len & 0x7f;
        if (n == 0 or n > 4 or bytes.len < 2 + n) return error.CertificateFieldHasInvalidLength;
        len = 0;
        for (bytes[2..][0..n]) |b| len = (len << 8) | b;
        at += n;
    }
    if (bytes.len - at < len) return error.CertificateFieldHasInvalidLength;
    return .{ .tag = bytes[0], .body = bytes[at..][0..len], .rest = bytes[at + len ..] };
}

/// `derNext`, requiring `tag`.
fn derRead(bytes: []const u8, tag: u8) !Der {
    const element = try derNext(bytes);
    if (element.tag != tag) return error.CertificateFieldHasWrongDataType;
    return element;
}

/// A non-negative INTEGER body; values past u64 saturate (no limit in practice).
fn derUint(body: []const u8) !u64 {
    if (body.len == 0 or body[0] & 0x80 != 0) return error.CertificateFieldHasWrongDataType;
    var value: u64 = 0;
    for (body) |b| {
        if (value > std.math.maxInt(u64) >> 8) return std.math.maxInt(u64);
        value = (value << 8) | b;
    }
    return value;
}

/// The 4 or 16 address bytes when `host` is an IP address literal.
fn ipHostBytes(host: []const u8, out: *[16]u8) ?[]const u8 {
    if (std.Io.net.Ip4Address.parse(host, 0)) |address| {
        out[0..4].* = address.bytes;
        return out[0..4];
    } else |_| {}
    switch (std.Io.net.Ip6Address.Unresolved.parse(host)) {
        .success => |parsed| {
            out.* = parsed.bytes;
            return out;
        },
        else => return null,
    }
}

/// An IP address literal, or anything with a colon (no DNS name has one).
fn isIpHost(host: []const u8) bool {
    var bytes: [16]u8 = undefined;
    return ipHostBytes(host, &bytes) != null or mem.findScalar(u8, host, ':') != null;
}

/// std's check for DNS hosts. An IP host matches only an iPAddress subjectAltName with
/// the same bytes: never a dNSName, a wildcard or the common name (RFC 6125 6.2.1).
fn verifyHostName(subject: Certificate.Parsed, host: []const u8) !void {
    if (!isIpHost(host)) return subject.verifyHostName(host);
    var buffer: [16]u8 = undefined;
    const address = ipHostBytes(host, &buffer) orelse return error.CertificateHostMismatch;
    const san = subject.subjectAltName();
    if (san.len == 0) return error.CertificateHostMismatch;
    var names = (try derRead(san, 0x30)).body;
    while (names.len > 0) {
        const name = try derNext(names);
        names = name.rest;
        // iPAddress is [7] IMPLICIT OCTET STRING.
        if (name.tag == 0x87 and mem.eql(u8, name.body, address)) return;
    }
    return error.CertificateHostMismatch;
}

test "derNext refuses lengths past the buffer" {
    try std.testing.expectError(error.CertificateFieldHasInvalidLength, derNext(&.{0x30}));
    try std.testing.expectError(error.CertificateFieldHasInvalidLength, derNext(&.{ 0x30, 0x05, 0x00 }));
    try std.testing.expectError(error.CertificateFieldHasInvalidLength, derNext(&.{ 0x30, 0x82, 0x01 }));
    try std.testing.expectError(error.CertificateFieldHasInvalidLength, derNext(&.{ 0x30, 0x80 }));
    const ok = try derNext(&.{ 0x04, 0x01, 0xaa, 0x05, 0x00 });
    try std.testing.expectEqualSlices(u8, &.{0xaa}, ok.body);
    try std.testing.expectEqualSlices(u8, &.{ 0x05, 0x00 }, ok.rest);
}

test "sameValue: directory strings compare as text, case and spaces folded, across string types" {
    const printable: Der = .{ .tag = 0x13, .body = "rex test", .rest = "" };
    try std.testing.expect(try sameValue(.{ .tag = 0x0c, .body = "  Rex   TEST ", .rest = "" }, printable));
    try std.testing.expect(try sameValue(.{ .tag = 0x1e, .body = "\x00R\x00e\x00x\x00 \x00t\x00e\x00s\x00t", .rest = "" }, printable));
    try std.testing.expect(try sameValue(.{ .tag = 0x0c, .body = "caf\xc3\xa9", .rest = "" }, .{ .tag = 0x1e, .body = "\x00c\x00a\x00f\x00\xe9", .rest = "" }));
    try std.testing.expect(!try sameValue(printable, .{ .tag = 0x13, .body = "rextest", .rest = "" }));
    try std.testing.expect(!try sameValue(printable, .{ .tag = 0x13, .body = "rex tests", .rest = "" }));
    // Not a string: the same bytes under the same tag.
    try std.testing.expect(!try sameValue(.{ .tag = 0x04, .body = "Rex", .rest = "" }, .{ .tag = 0x04, .body = "rex", .rest = "" }));
    try std.testing.expectError(error.CertificateFieldHasInvalidLength, sameValue(.{ .tag = 0x1e, .body = "\x00R\x00", .rest = "" }, printable));
}

test "wildcardReaches: a wildcard reaches the names one label left of its base" {
    try std.testing.expect(wildcardReaches("bank.example.test", "*.example.test"));
    try std.testing.expect(wildcardReaches("BANK.Example.test", "*.example.TEST"));
    try std.testing.expect(!wildcardReaches("a.bank.example.test", "*.example.test"));
    try std.testing.expect(!wildcardReaches(".bank.example.test", "*.example.test"));
    try std.testing.expect(!wildcardReaches("bank.example.test", "www.example.test"));
    try std.testing.expect(!wildcardReaches("example.test", "*.example.test"));
}

test "isIpHost: IP literals and colons, not DNS names" {
    try std.testing.expect(isIpHost("192.0.2.29"));
    try std.testing.expect(isIpHost("fd7a:115c:a1e0::1"));
    try std.testing.expect(isIpHost("fe80::1%wlan0"));
    try std.testing.expect(!isIpHost("rex.example.ts.net"));
    try std.testing.expect(!isIpHost("localhost"));
    try std.testing.expect(!isIpHost("1.example"));
}

/// Velo note (#337): libvelocore builds for baseline CPUs, so on Android
/// `has_hardware_support` is false and ChaCha20 comes first. The Rex server (Go's
/// crypto/tls) puts ChaCha20 ahead of AES-GCM when the client lists it first, so it
/// settles on ChaCha20. A server that enforces its own order (for example nginx with
/// ssl_prefer_server_ciphers on) may pick AES-GCM, which then runs in software; see
/// docs/native-core.md.
///
/// The priority order here is chosen based on what crypto algorithms Zig has
/// available in the standard library as well as what is faster. Following are
/// a few data points on the relative performance of these algorithms.
///
/// Measurement taken with 0.11.0-dev.810+c2f5848fe
/// on x86_64-linux Intel(R) Core(TM) i9-9980HK CPU @ 2.40GHz:
/// zig run .lib/std/crypto/benchmark.zig -OReleaseFast
///       aegis-128l:      15382 MiB/s
///        aegis-256:       9553 MiB/s
///       aes128-gcm:       3721 MiB/s
///       aes256-gcm:       3010 MiB/s
/// chacha20Poly1305:        597 MiB/s
///
/// Measurement taken with 0.11.0-dev.810+c2f5848fe
/// on x86_64-linux Intel(R) Core(TM) i9-9980HK CPU @ 2.40GHz:
/// zig run .lib/std/crypto/benchmark.zig -OReleaseFast -mcpu=baseline
///       aegis-128l:        629 MiB/s
/// chacha20Poly1305:        529 MiB/s
///        aegis-256:        461 MiB/s
///       aes128-gcm:        138 MiB/s
///       aes256-gcm:        120 MiB/s
const cipher_suites = if (crypto.core.aes.has_hardware_support)
    array(u16, tls.CipherSuite, .{
        .AEGIS_128L_SHA256,
        .AEGIS_256_SHA512,
        .AES_128_GCM_SHA256,
        .ECDHE_RSA_WITH_AES_128_GCM_SHA256,
        .AES_256_GCM_SHA384,
        .ECDHE_RSA_WITH_AES_256_GCM_SHA384,
        .CHACHA20_POLY1305_SHA256,
        .ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256,
    })
else
    array(u16, tls.CipherSuite, .{
        .CHACHA20_POLY1305_SHA256,
        .ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256,
        .AEGIS_128L_SHA256,
        .AEGIS_256_SHA512,
        .AES_128_GCM_SHA256,
        .ECDHE_RSA_WITH_AES_128_GCM_SHA256,
        .AES_256_GCM_SHA384,
        .ECDHE_RSA_WITH_AES_256_GCM_SHA384,
    });

fn testReadError(input_buf: []const u8, tls_version: tls.ProtocolVersion, cipher: tls.ApplicationCipher) ReadError {
    var input_reader: Reader = .fixed(input_buf);
    var read_buf: [tls.max_ciphertext_record_len]u8 = undefined;
    var c: Client = .{
        .input = &input_reader,
        .reader = .{
            .buffer = &read_buf,
            .vtable = &.{ .stream = stream, .readVec = readVec },
            .seek = 0,
            .end = 0,
        },
        .output = undefined,
        .writer = undefined,
        .tls_version = tls_version,
        .read_seq = 0,
        .write_seq = 0,
        .received_close_notify = false,
        .allow_truncation_attacks = false,
        .application_cipher = cipher,
        .ssl_key_log = null,
    };
    var w: Writer = .failing;
    std.testing.expectError(error.ReadFailed, c.reader.stream(&w, .unlimited)) catch
        @panic("expected ReadFailed");
    return c.read_err.?;
}

test "empty inner plaintext" {
    const AEAD = crypto.aead.chacha_poly.ChaCha20Poly1305;
    const key: [AEAD.key_length]u8 = @splat(0);
    const iv: [AEAD.nonce_length]u8 = @splat(0);

    const plaintext = [1]u8{0x00};
    var ciphertext: [plaintext.len]u8 = undefined;
    var tag: [AEAD.tag_length]u8 = undefined;
    const content_len: u16 = plaintext.len + AEAD.tag_length;
    const record_header = [_]u8{ 0x17, 0x03, 0x03 } ++ mem.toBytes(big(content_len));
    AEAD.encrypt(&ciphertext, &tag, &plaintext, &record_header, iv, key);

    try std.testing.expectEqual(error.TlsDecodeError, testReadError(
        &record_header ++ ciphertext ++ tag,
        .tls_1_3,
        .{ .CHACHA20_POLY1305_SHA256 = .{ .tls_1_3 = .{
            .server_key = key,
            .server_iv = iv,
            .client_secret = undefined,
            .server_secret = undefined,
            .client_key = undefined,
            .client_iv = undefined,
        } } },
    ));
}

test "record shorter than tag" {
    const AEAD = crypto.aead.chacha_poly.ChaCha20Poly1305;
    const record_len: u16 = AEAD.tag_length - 1;
    const header = [_]u8{ 0x17, 0x03, 0x03 } ++ mem.toBytes(big(record_len));
    const wire = header ++ @as([record_len]u8, @splat(0));

    try std.testing.expectEqual(error.TlsRecordOverflow, testReadError(
        &wire,
        .tls_1_3,
        .{ .CHACHA20_POLY1305_SHA256 = .{ .tls_1_3 = .{
            .server_key = undefined,
            .server_iv = undefined,
            .client_secret = undefined,
            .server_secret = undefined,
            .client_key = undefined,
            .client_iv = undefined,
        } } },
    ));
}

test "TLS 1.2 record shorter than IV plus tag" {
    const P = tls.ApplicationCipherT(crypto.aead.aes_gcm.Aes128Gcm, crypto.hash.sha2.Sha256, 8);
    const record_len: u16 = P.record_iv_length + P.mac_length - 1;
    const header = [_]u8{ 0x17, 0x03, 0x03 } ++ mem.toBytes(big(record_len));

    try std.testing.expectEqual(error.TlsRecordOverflow, testReadError(
        &(header ++ @as([record_len]u8, @splat(0))),
        .tls_1_2,
        .{ .AES_128_GCM_SHA256 = .{ .tls_1_2 = mem.zeroes(P.Tls_1_2) } },
    ));
}

test "zero-length key_update body" {
    const Chacha = crypto.aead.chacha_poly.ChaCha20Poly1305;
    const plaintext = [_]u8{ 0x18, 0x00, 0x00, 0x00, 0x16 };
    const header = [_]u8{ 0x17, 0x03, 0x03 } ++ mem.toBytes(big(@as(u16, plaintext.len + Chacha.tag_length)));
    var ct: [plaintext.len]u8 = undefined;
    var tag: [Chacha.tag_length]u8 = undefined;
    Chacha.encrypt(&ct, &tag, &plaintext, &header, @splat(0), @splat(0));
    const wire = header ++ ct ++ tag;
    try std.testing.expectEqual(error.TlsDecodeError, testReadError(
        &wire,
        .tls_1_3,
        .{ .CHACHA20_POLY1305_SHA256 = .{ .tls_1_3 = .{
            .server_key = @splat(0),
            .server_iv = @splat(0),
            .client_secret = undefined,
            .server_secret = undefined,
            .client_key = undefined,
            .client_iv = undefined,
        } } },
    ));
}
