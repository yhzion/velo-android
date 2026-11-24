//! One terminal data connection (`wss://HOST/api/data`, `rex.data.terminal.v1`) feeding
//! one `pane.Pane`. Runs on its own detached thread: HELLO/RESUME handshake, Ghostty
//! snapshot restore, then program bytes and Rex messages through `StreamFilter`.
//! Reconnects by RESUME inside the server's resume window, else with a new ticket.
const std = @import("std");
const pane = @import("pane");
const ws_mod = @import("../net/WebSocket.zig");
const protocol = @import("protocol.zig");
const sf = @import("StreamFilter.zig");

const Allocator = std.mem.Allocator;
const WebSocket = ws_mod.WebSocket;

/// Room for a base64url data ticket (22 characters today).
pub const max_ticket_len = 64;
/// A snapshot that has not completed within this many bytes is treated as broken.
const max_snapshot_bytes = 256 * 1024 * 1024;
/// Pause before reconnecting a stream that was delivering data.
const quick_retry_ms = 250;

pub const Ticket = union(enum) {
    /// Points into the caller's buffer.
    ok: []const u8,
    /// No ticket right now (control connection down, request failed): try again later.
    retry,
    /// The block is gone or the stream is being stopped.
    gone,
};

/// Implemented by Core. All callbacks run on the stream's thread.
pub const Owner = struct {
    ctx: *anyopaque,
    /// Blocks until a fresh ticket for the block is available (`data.connect`).
    ticket: *const fn (ctx: *anyopaque, buf: *[max_ticket_len]u8) Ticket,
    /// The terminal's title changed ("" when it has none).
    title: *const fn (ctx: *anyopaque, title: []const u8) void,
    /// Last call of the thread. The owner may free the stream during this call.
    exited: *const fn (ctx: *anyopaque) void,
    /// False while the stream must not connect: the owner's control connection stopped
    /// on a failure that retrying cannot fix (an ssh dial would start a new login and
    /// ask the user again). The stream then waits as after a failed attempt. Null: always.
    may_dial: ?*const fn (ctx: *anyopaque) bool = null,
};

/// Reconnect delay: 1 s doubling to a 15 s cap.
pub fn backoffMs(attempt: u32) u32 {
    if (attempt >= 4) return 15_000;
    return @min(@as(u32, 1000) << @intCast(attempt), 15_000);
}

pub fn nowMs(io: std.Io) i64 {
    return std.Io.Clock.boot.now(io).toMilliseconds();
}

/// Wait on `l` (held by the caller) for a signal or at most `ms` milliseconds.
pub fn timedWait(l: *pane.Lock, ms: u32) void {
    var ts: std.c.timespec = undefined;
    if (std.c.clock_gettime(.REALTIME, &ts) != 0) return;
    const ns: u64 = @as(u64, @intCast(ts.nsec)) + @as(u64, ms % 1000) * std.time.ns_per_ms;
    ts.sec += @intCast(ms / 1000 + ns / std.time.ns_per_s);
    ts.nsec = @intCast(ns % std.time.ns_per_s);
    _ = std.c.pthread_cond_timedwait(&l.cv, &l.m, &ts);
}

/// The record framing of a Ghostty snapshot, format version 1 (`snapshot.ksy` in
/// libghostty-vt): a ten-byte envelope ("GHOSTSNP", then the version as a u16), then
/// records, each a ten-byte header (u16 tag, u32 payload length, u32 CRC32C) and its
/// payload, the last one FINISH. It tells when a buffer holds a whole snapshot without
/// decoding it; the decoder still checks every record (#352).
pub const Framing = struct {
    /// Where the next record header starts; 0 until the envelope is in.
    next: usize = 0,
    /// An envelope this does not know: only the decoder can tell where the snapshot ends.
    unknown: bool = false,

    const envelope_len = 10;
    const header_len = 10;
    const finish_tag = 6;

    /// Whether `buf`, the snapshot so far from its first byte, may hold all of it: true once
    /// FINISH is in, or at every call for an envelope this does not know. A record that
    /// would end past `max` bytes is refused at once, before its bytes are collected: a
    /// broken length must not make the buffer grow to the limit first.
    pub fn complete(f: *Framing, buf: []const u8, max: usize) error{SnapshotTooLarge}!bool {
        if (f.unknown) return true;
        if (f.next == 0) {
            if (buf.len < envelope_len) return false;
            if (!std.mem.eql(u8, buf[0..8], "GHOSTSNP") or std.mem.readInt(u16, buf[8..10], .little) != 1) {
                f.unknown = true;
                return true;
            }
            f.next = envelope_len;
        }
        while (buf.len - f.next >= header_len) {
            const header = buf[f.next..][0..header_len];
            // In 64 bits: a u32 length must not wrap a 32-bit usize.
            const end64 = @as(u64, f.next) + header_len + std.mem.readInt(u32, header[2..6], .little);
            if (end64 > max) return error.SnapshotTooLarge;
            const end: usize = @intCast(end64);
            if (buf.len < end) return false;
            if (std.mem.readInt(u16, header[0..2], .little) == finish_tag) return true;
            f.next = end;
        }
        return false;
    }
};

/// Applies the bytes of one terminal stream to a pane: the snapshot first, then program
/// output and Rex messages. Counts every byte for RESUME.
pub const Consumer = struct {
    gpa: Allocator,
    pane: *pane.Pane,
    /// Stream bytes used so far, across every connection of the stream.
    consumed: u64 = 0,
    /// Snapshot bytes received so far; empty once the snapshot is in the pane.
    snapshot: std.ArrayList(u8) = .empty,
    restored: bool = false,
    /// Times the current snapshot was given to the decoder (observed by host tests).
    restore_tries: u32 = 0,
    /// How far the current snapshot's framing has been read. The decoder parses a snapshot
    /// from its first byte, so it gets the buffer once the framing says FINISH is in, not
    /// for every chunk: a try per chunk made a long scrollback quadratic (#352).
    framing: Framing = .{},
    filter: ?sf.StreamFilter = null,

    pub fn init(gpa: Allocator, target: *pane.Pane) Consumer {
        return .{ .gpa = gpa, .pane = target };
    }

    pub fn deinit(self: *Consumer) void {
        self.snapshot.deinit(self.gpa);
        if (self.filter) |*f| f.deinit();
    }

    /// Start a new stream (after HELLO): its snapshot replaces the pane's terminal.
    /// A RESUME does not call this: the server goes on from `consumed`, so what follows
    /// belongs to the snapshot collected so far.
    pub fn begin(self: *Consumer, nonce: u64) void {
        self.consumed = 0;
        // What a stream that ended before FINISH collected goes, with its memory.
        self.snapshot.clearAndFree(self.gpa);
        self.restored = false;
        self.restore_tries = 0;
        self.framing = .{};
        if (self.filter) |*f| f.deinit();
        self.filter = sf.StreamFilter.init(self.gpa, nonce);
    }

    /// Stream bytes in arrival order, in any chunking.
    pub fn feed(self: *Consumer, data: []const u8) !void {
        // One section per received message, not per `Pane.write`: a flood writes once a line,
        // and a section each (96k in a 5 MB flood) cost 6 % of the thread while traced. The
        // writes' waits for the pane lock are in it.
        pane.Trace.begin("DataStream.feed");
        defer pane.Trace.end();
        const filter = if (self.filter) |*f| f else return error.StreamNotStarted;
        self.consumed += data.len;
        const sink: sf.Sink = .{ .ctx = self, .bytes = sinkBytes, .message = sinkMessage };
        if (self.restored) return filter.feed(data, sink);

        try self.snapshot.appendSlice(self.gpa, data);
        if (self.snapshot.items.len > max_snapshot_bytes) return error.SnapshotTooLarge;
        if (!try self.framing.complete(self.snapshot.items, max_snapshot_bytes)) return;
        self.restore_tries += 1;
        const used = self.pane.restoreSnapshot(self.snapshot.items) catch |e| switch (e) {
            // With FINISH in by its framing, a decoder that still wants more met a broken
            // record (it reports most decoding errors as truncation). Trying again with every
            // chunk would only cost time; the stream starts over. An unknown format waits on.
            error.NeedMoreData => if (self.framing.unknown) return else return error.InvalidSnapshot,
            else => return e,
        };
        self.restored = true;
        filter.feed(self.snapshot.items[used..], sink);
        self.snapshot.clearAndFree(self.gpa);
    }

    fn sinkBytes(ctx: *anyopaque, data: []const u8) void {
        const self: *Consumer = @ptrCast(@alignCast(ctx));
        self.pane.write(data);
    }

    fn sinkMessage(ctx: *anyopaque, message: sf.RexMessage) void {
        const self: *Consumer = @ptrCast(@alignCast(ctx));
        switch (message) {
            .resize => |r| self.pane.resize(r.cols, r.rows, r.cell_w_px, r.cell_h_px),
            // The palette slice is only valid during this call; setTheme copies it.
            .theme => |t| self.pane.setTheme(.{
                .foreground = t.foreground,
                .background = t.background,
                .cursor = t.cursor,
                .palette = t.palette,
            }),
            .child_exited => {},
        }
    }
};

pub const DataStream = struct {
    gpa: Allocator,
    io: std.Io,
    /// The server's locator (`net/Endpoint.zig`). Owned copy.
    locator: []u8,
    /// Borrowed from the owner; outlive the stream.
    net: ws_mod.Net,
    pane: *pane.Pane,
    owner: Owner,

    lock: pane.Lock = .{},
    /// The open connection, published so `stop` can close it (protected by `lock`).
    ws: ?*WebSocket = null,
    stopping: std.atomic.Value(bool) = .init(false),
    /// Ends the current reconnect delay early (protected by `lock`).
    woken: bool = false,
    /// True once the current stream's snapshot is in the pane.
    restored: std.atomic.Value(bool) = .init(false),
    /// A restored snapshot with data received on the current socket (not a held preview).
    live_snapshot: std.atomic.Value(bool) = .init(false),
    /// Restart calls observed by host tests.
    restarts: std.atomic.Value(u32) = .init(0),

    /// Ticket from `session.bootstrap`, used for the first HELLO.
    initial: [max_ticket_len]u8 = undefined,
    initial_len: usize = 0,

    /// Start the stream thread (detached). `target` must stay valid until `owner.exited`.
    pub fn start(
        gpa: Allocator,
        io: std.Io,
        locator: []const u8,
        net: ws_mod.Net,
        target: *pane.Pane,
        initial_ticket: ?[]const u8,
        owner: Owner,
    ) !*DataStream {
        const self = try gpa.create(DataStream);
        errdefer gpa.destroy(self);
        const locator_copy = try gpa.dupe(u8, locator);
        errdefer gpa.free(locator_copy);
        self.* = .{ .gpa = gpa, .io = io, .locator = locator_copy, .net = net, .pane = target, .owner = owner };
        if (initial_ticket) |t| if (t.len <= max_ticket_len) {
            @memcpy(self.initial[0..t.len], t);
            self.initial_len = t.len;
        };
        const thread = try std.Thread.spawn(.{}, run, .{self});
        thread.detach();
        return self;
    }

    /// Ask the thread to end; never waits for it. Closing with status 1000 ends the
    /// server-side stream. The thread reports through `owner.exited`.
    pub fn stop(self: *DataStream) void {
        self.lock.lock();
        self.stopping.store(true, .release);
        self.live_snapshot.store(false, .release);
        if (self.ws) |w| w.close();
        self.lock.signal();
        self.lock.unlock();
    }

    /// Drop the current connection without a close frame and reconnect now. For a
    /// link that may have died silently, where a blocked read would never notice.
    /// The server keeps the stream, so the thread continues it with a RESUME.
    pub fn restart(self: *DataStream) void {
        self.lock.lock();
        if (@import("builtin").is_test) _ = self.restarts.fetchAdd(1, .monotonic);
        if (self.ws) |w| if (w.conn) |conn| conn.shutdown();
        self.woken = true;
        self.live_snapshot.store(false, .release);
        self.lock.signal();
        self.lock.unlock();
    }

    /// Free the stream. Only after (or during) `owner.exited`.
    pub fn destroy(self: *DataStream) void {
        const gpa = self.gpa;
        gpa.free(self.locator);
        gpa.destroy(self);
    }

    pub fn isRestored(self: *const DataStream) bool {
        return self.restored.load(.acquire);
    }

    // ------------------------------------------------------------ thread

    /// Stream state that survives reconnects.
    const State = struct {
        consumer: Consumer,
        have_token: bool = false,
        token: [16]u8 = undefined,
        resume_ms: u32 = 0,
        nonce: u64 = 0,
        /// Last time the stream was known alive (WELCOME or data).
        alive_ms: i64 = 0,
        generation: u64 = 0,
        title: [256]u8 = undefined,
        title_len: usize = 0,
    };

    const Outcome = enum { finished, retry_soon, retry_backoff };

    fn run(self: *DataStream) void {
        pane.Trace.nameThread("velo-data-", self.pane.threadTag());
        self.loop();
        const owner = self.owner;
        owner.exited(owner.ctx); // `self` may be freed from here on
    }

    fn loop(self: *DataStream) void {
        var st: State = .{ .consumer = .init(self.gpa, self.pane) };
        defer st.consumer.deinit();
        var attempt: u32 = 0;
        while (!self.stopping.load(.acquire)) {
            const delay: u32 = switch (self.connectOnce(&st)) {
                .finished => return,
                .retry_soon => blk: {
                    attempt = 0;
                    break :blk quick_retry_ms;
                },
                .retry_backoff => blk: {
                    const d = backoffMs(attempt);
                    attempt +|= 1;
                    break :blk d;
                },
            };
            if (!self.sleep(delay)) return;
        }
    }

    /// False when the stream is stopping.
    fn sleep(self: *DataStream, ms: u32) bool {
        const deadline = nowMs(self.io) + ms;
        self.lock.lock();
        defer self.lock.unlock();
        while (!self.stopping.load(.acquire)) {
            if (self.woken) break;
            const now = nowMs(self.io);
            if (now >= deadline) break;
            timedWait(&self.lock, @intCast(@min(deadline - now, 500)));
        }
        self.woken = false;
        return !self.stopping.load(.acquire);
    }

    /// What a closed connection means for the stream.
    fn closed(st: *State, ws: *WebSocket) Outcome {
        switch (ws.close_code) {
            // Block closed, stream ended, or the block has no terminal data.
            4000, 1000, 1008 => return .finished,
            // The server wants a new stream.
            1011, 1013 => {
                st.have_token = false;
                return .retry_backoff;
            },
            else => return .retry_soon,
        }
    }

    /// One connection: handshake, then stream bytes until it closes or drops.
    fn connectOnce(self: *DataStream, st: *State) Outcome {
        if (self.owner.may_dial) |mayDial| if (!mayDial(self.owner.ctx)) return .retry_backoff;
        var handshake: [25]u8 = undefined;
        var handshake_len: usize = 17;
        const resuming = st.have_token and nowMs(self.io) - st.alive_ms < st.resume_ms;
        if (resuming) {
            protocol.dataResume(st.token, st.consumer.consumed, &handshake);
            handshake_len = 25;
        } else {
            st.have_token = false;
            var buf: [max_ticket_len]u8 = undefined;
            var ticket: []const u8 = self.initial[0..self.initial_len];
            self.initial_len = 0;
            if (ticket.len == 0) switch (self.owner.ticket(self.owner.ctx, &buf)) {
                .ok => |t| ticket = t,
                .retry => return .retry_backoff,
                .gone => return .finished,
            };
            protocol.dataHello(ticket, handshake[0..17]) catch return .retry_backoff;
        }

        const ws = WebSocket.connect(self.gpa, self.io, self.locator, "/api/data", protocol.terminal_subprotocol, self.net) catch
            return .retry_backoff;
        self.lock.lock();
        if (self.stopping.load(.acquire)) {
            self.lock.unlock();
            ws.close();
            ws.deinit();
            return .finished;
        }
        self.ws = ws;
        self.lock.unlock();
        defer {
            self.lock.lock();
            self.ws = null;
            self.live_snapshot.store(false, .release);
            self.lock.unlock();
            ws.deinit();
        }

        ws.sendBinary(handshake[0..handshake_len]) catch return .retry_backoff;
        const first = (ws.recv() catch return .retry_backoff) orelse
            return if (ws.close_code == 4000) .finished else .retry_backoff;
        if (first.opcode != .binary) return .retry_backoff;
        switch (protocol.parseDataHandshake(first.data)) {
            .welcome => |w| {
                if (resuming and w.nonce != st.nonce) {
                    // Not the stream we were reading: start over.
                    st.have_token = false;
                    return .retry_backoff;
                }
                st.token = w.token;
                st.resume_ms = w.resume_ms;
                st.have_token = true;
                st.alive_ms = nowMs(self.io);
                if (!resuming) {
                    st.nonce = w.nonce;
                    st.consumer.begin(w.nonce);
                    self.restored.store(false, .release);
                }
            },
            .reject => {
                // A refused RESUME ends the stream: start over with a new ticket.
                if (resuming) {
                    st.have_token = false;
                    return .retry_soon;
                }
                return .retry_backoff;
            },
            .invalid => return .retry_backoff,
        }

        while (true) {
            const message = (ws.recv() catch return .retry_soon) orelse return closed(st, ws);
            if (message.opcode != .binary) continue;
            st.alive_ms = nowMs(self.io);
            st.consumer.feed(message.data) catch {
                // The pane cannot follow this stream any more: start a new one.
                st.have_token = false;
                return .retry_backoff;
            };
            if (st.consumer.restored) {
                self.restored.store(true, .release);
                self.lock.lock();
                if (!self.woken and !self.stopping.load(.acquire)) self.live_snapshot.store(true, .release);
                self.lock.unlock();
                self.pollTitle(st);
            }
        }
    }

    fn pollTitle(self: *DataStream, st: *State) void {
        const generation = self.pane.generation();
        if (generation == st.generation) return;
        st.generation = generation;
        var buf: [256]u8 = undefined;
        const title = self.pane.title(&buf) orelse "";
        if (std.mem.eql(u8, title, st.title[0..st.title_len])) return;
        @memcpy(st.title[0..title.len], title);
        st.title_len = title.len;
        self.owner.title(self.owner.ctx, title);
    }
};
