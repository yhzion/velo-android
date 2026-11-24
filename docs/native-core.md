# libvelocore: core modules contract

`native/src/` minus `term/` (see `native-internal.md` for `term/Pane.zig`). Four owners,
file-disjoint. Every file imports only what its row lists.

| Unit | Files | Imports |
|---|---|---|
| Build + JNI | `native/build.zig`, `native/src/jni.zig`, `native/src/tests.zig`, `native/src/sys/jni.zig`, `native/src/sys/glyphs.zig` | `rex/Core.zig` (relative), `pane`, `c`, `jni_c`, std |
| Net | `native/src/net/Conn.zig`, `net/WebSocket.zig`, `net/test_net.zig` | std only |
| Model | `native/src/rex/model.zig`, `rex/protocol.zig`, `rex/StreamFilter.zig`, `rex/test_model.zig`, `rex/testdata/*` | std only |
| Core | `native/src/rex/Core.zig`, `rex/DataStream.zig`, `rex/test_core.zig`, `native/src/probe.zig` | `net/*`, `rex/model.zig`, `rex/protocol.zig`, `rex/StreamFilter.zig`, `pane` (named module), std |

Named modules (set up in build.zig): `c` (host translate-c of `src/c.h`, ghostty only),
`pane` (root `src/term/Pane.zig`, import `c`), `jni_c` (host translate-c of NDK `jni.h`).
There is no `core` module: Zig 0.17 rejects imports outside a module's root directory, so
every root that needs Core sits in `src/` and imports `rex/Core.zig` relatively:
`src/jni.zig` (the library), `src/probe.zig` (host executable), `src/tests.zig` (host test
root importing `net/test_net.zig`, `rex/test_model.zig`, `rex/test_core.zig`). The term
tests keep their own root `src/term/test_pane.zig`.

## Zig 0.17.1 implementation notes

- No `@cImport`. No `std.Thread.Mutex`/`Condition`: use `pane.Lock` (pthread mutex+cond
  wrapper exported by `term/Pane.zig`: `lock()`, `unlock()`, `wait()`, `signal()`), or
  `std.c.pthread_mutex_t`. Threads: `std.Thread.spawn(.{}, fn, .{args})`.
- I/O goes through `std.Io`. Create one `var threaded: std.Io.Threaded = .init(gpa, .{});`
  per core and use `threaded.io()`. libc is always linked (host and Android).
- TCP: `const host: std.Io.net.HostName = try .init(name);`
  `const stream = try host.connect(io, 443, .{ .mode = .stream });` (uses libc
  `getaddrinfo`, which works on Android). `stream.reader(io, buf)` / `stream.writer(io, buf)`
  return structs whose `.interface` is the `std.Io.Reader` / `std.Io.Writer`.
  `stream.close(io)`.
- TLS: `std.crypto.tls.Client.init(&reader.interface, &writer.interface, .{ .host =
  .{ .explicit = name }, .ca = .{ .bundle = .{ .gpa, .io, .lock = &rwlock, .bundle = &bundle } },
  .read_buffer, .write_buffer, .entropy = &entropy, .realtime_now = std.Io.Clock.real.now(io) })`.
  Buffers ≥ `std.crypto.tls.Client.min_buffer_len`; the socket reader buffer too.
  `entropy`: `[std.crypto.tls.Client.Options.entropy_len]u8` filled by `io.random(&buf)`.
  `std.Io.RwLock` init is `.init`. Reference: `lib/std/http/Client.zig` `Tls.create`.
  After init use `client.reader` / `client.writer` (`std.Io.Reader`/`Writer`), flush both
  the TLS writer and the socket writer. Rex connections use `net/TlsClient.zig`, a copy of
  this client with the same API and stricter certificate checks (see its header).
- TLS cipher selection: the Android core builds for the baseline CPU of each ABI. `std.crypto`
  picks its AES code at compile time, so AES-GCM runs in software, and the client offers
  ChaCha20-Poly1305 first. The Rex server (Go's `crypto/tls`) puts ChaCha20 ahead of
  AES-GCM when the client lists it first, so Rex connections use ChaCha20. A server that
  enforces its own order (nginx with `ssl_prefer_server_ciphers on`, Python's `ssl` by
  default) may pick AES-GCM, which then runs in software, about four times slower than
  ChaCha20 by Zig's own figures for a baseline x86_64 CPU (`TlsClient.zig`).
  - Why not `-Dcpu=baseline+aes`: against the Rex server it would only trade ChaCha20 for
    hardware AES-GCM, and ChaCha20 took 0-3 % of the data thread's samples in a 5 MB
    flood. The core would also die with SIGILL on the first AES-GCM record on an arm64
    device without the crypto extensions, which ARMv8.0 leaves optional (the Raspberry
    Pi 3 and 4 lack them).
- CA bundle: `var bundle: std.crypto.Certificate.Bundle = .empty;` then
  `bundle.addCertsFromDirPathAbsolute(gpa, io, std.Io.Clock.real.now(io), dir)` per dir.
  Android versions can expose system roots in `/apex/com.android.conscrypt/cacerts` or
  `/system/etc/security/cacerts`. On the host use `bundle.rescan(gpa, io, now)`.
- JSON: `std.json.parseFromSlice(std.json.Value, gpa, bytes, .{})`,
  `std.json.Stringify.valueAlloc(gpa, value, .{})`. Base64: `std.base64.url_safe_no_pad`.
- `std.ArrayList(T)` is unmanaged: `.empty`, `append(gpa, x)`, `deinit(gpa)`.

## `net/Conn.zig` (Net)

```zig
/// How a `Conn` reaches its server.
pub const Net = struct {
    /// Directories of CA certificates; empty → system rescan (host builds).
    ca_dirs: []const []const u8 = &.{},
    /// Asked first for every TCP connection (https, http); the system network when absent
    /// or when it declines. Never asked for a Unix socket or ssh.
    dialer: ?Dialer = null,
    /// Opens every ssh connection (the app's SSH pool, `sys/ssh.zig`); without it they
    /// fail with error.SshUnavailable.
    ssh: ?SshDialer = null,
};
/// Another network to open TCP connections on (the app's tailnet node, `sys/tailnet.zig`).
pub const Dialer = struct {
    ctx: ?*anyopaque = null,
    /// A connected stream socket to host:port, owned by the caller; null = use the system network.
    dial: *const fn (ctx: ?*anyopaque, host: []const u8, port: u16) anyerror!?std.posix.fd_t,
};
/// The app's SSH pool: logs in, finds the Rex server, carries the stream.
pub const SshDialer = struct {
    ctx: ?*anyopaque = null,
    /// A connected stream socket to the Rex server of the ssh locator (trimmed), owned by
    /// the caller. On failure the pool may explain it in `problem`.
    dial: *const fn (ctx: ?*anyopaque, locator: []const u8, problem: *SshProblem) anyerror!std.posix.fd_t,
};
/// Why the pool could not open a stream: code, user-facing message, and a one-line
/// command that fixes it ("" when none). Fixed 4 KiB storage; an empty code explains
/// nothing. A fix that does not fit is left out whole; a sentence is cut at a character.
pub const SshProblem = struct {
    pub fn code(self: *const SshProblem) []const u8;
    pub fn sentence(self: *const SshProblem) []const u8;
    pub fn fix(self: *const SshProblem) []const u8;
    pub fn set(self: *SshProblem, code: []const u8, sentence: []const u8, fix: []const u8) void;
    /// From the pool's `{"code","sentence","fix"}`; error.BadProblem without a code.
    pub fn setJson(self: *SshProblem, gpa: std.mem.Allocator, text: []const u8) !void;
};
pub const Conn = struct {
    /// Connect to `locator` over `net`: TLS for HTTPS, plain TCP for explicit HTTP,
    /// a Unix socket for an absolute path (a bare host is HTTPS on 443),
    /// and for an SSH locator, plain HTTP over the socket `net.ssh` hands back.
    pub fn open(gpa: std.mem.Allocator, io: std.Io, locator: []const u8, net: Net) !*Conn;
    /// `open` that also says why an ssh connection failed: `problem` gets a code when the
    /// pool (or the lack of one) explains it. A problem with a working dial is cleared.
    pub fn openExplained(gpa: std.mem.Allocator, io: std.Io, locator: []const u8, net: Net, problem: ?*SshProblem) !*Conn;
    pub fn reader(self: *Conn) *std.Io.Reader;
    pub fn writer(self: *Conn) *std.Io.Writer;
    /// Flush TLS and socket writers.
    pub fn flush(self: *Conn) !void;
    /// Idempotent; safe to call from another thread to unblock a blocked read
    /// (shuts the socket down, then the owner thread calls deinit).
    pub fn shutdown(self: *Conn) void;
    pub fn deinit(self: *Conn) void;
};
```

## `net/WebSocket.zig` (Net)

RFC 6455 client over a `Conn`. No extensions/compression.

```zig
pub const Opcode = enum { text, binary };
pub const Message = struct { opcode: Opcode, data: []const u8 }; // valid until next recv()

pub const WebSocket = struct {
    /// HTTP/1.1 upgrade to wss://host{path} with `Sec-WebSocket-Protocol: subprotocol`.
    /// Fails with error.UpgradeRejected (status in `status_code`) or error.BadSubprotocol.
    pub fn connect(gpa: std.mem.Allocator, io: std.Io, host: []const u8, path: []const u8,
        subprotocol: []const u8, net: Net) !*WebSocket;
    /// Blocks. Reassembles fragments, answers ping with pong, returns null on close
    /// (close code in `close_code`, reason in `close_reason`).
    pub fn recv(self: *WebSocket) !?Message;
    /// Thread-safe against recv() and other sends (internal write lock). Masks frames.
    pub fn sendText(self: *WebSocket, data: []const u8) !void;
    pub fn sendBinary(self: *WebSocket, data: []const u8) !void;
    /// Sends close (1000) if still open and shuts the socket down; unblocks recv().
    pub fn close(self: *WebSocket) void;
    pub fn deinit(self: *WebSocket) void;
    close_code: u16 = 0,
    close_reason: []const u8 = "",
    status_code: u16 = 0,
};

// Pure helpers, unit-tested without a network:
pub fn encodeFrame(out: *std.ArrayList(u8), gpa: std.mem.Allocator, fin: bool, opcode: u4, mask: [4]u8, payload: []const u8) !void;
pub const FrameHeader = struct { fin: bool, opcode: u4, masked: bool, mask: [4]u8, len: u64, header_len: usize };
pub fn decodeHeader(bytes: []const u8) ?FrameHeader; // null = need more bytes
pub fn acceptKey(key_b64: []const u8, out: *[28]u8) void; // Sec-WebSocket-Accept
```

## `rex/model.zig` (Model) — pure data, no I/O

```zig
pub const Id = []const u8; // "session:<uuid>", "window:<uuid>", "block:<uuid>", "layer:<uuid>"

pub const Layout = union(enum) {
    block: Id,
    split: struct { column: bool, ratio: f32, first: *Layout, second: *Layout }, // column = vertical
};
pub const Floating = struct { block: Id, layer: Id, x: f32, y: f32, w: f32, h: f32 };
pub const Block = struct { id: Id, label: []const u8, terminal: bool, title: []const u8 = "", owner: ?Id = null };
/// `blocks`: those of the layers that are drawn. `minimized`: at least one block sits in a
/// minimized layer, which is not drawn and not in `blocks`.
pub const Window = struct { id: Id, label: []const u8, focused: ?Id, zoomed: ?Id, layout: ?*Layout, floating: []Floating, blocks: []Block, minimized: bool = false };
pub const View = struct { session: Id, label: []const u8, revision: i64, active_window: ?Id, windows: []Window };
/// `view`: what the core knows of the session's windows; null until the first answer.
pub const Session = struct { id: Id, label: []const u8, view: ?*const View = null };

/// Parse a `session.view` result (also the `view` member of bootstrap/attach results).
/// All memory from `arena`. Rex layout: `layers[]` (first kind "tiled" fills the window;
/// "floating" layers have `bounds` [x,y,w,h]; skip state "minimized"); layout node is
/// `{rect, block_id}` or `{rect, split:{direction:"horizontal"|"vertical", ratio, before, after}}`.
/// A floating layer with a split layout yields one Floating per leaf using the leaf `rect`.
/// `terminal` = creator_name == "com.superlogical.terminal".
pub fn parseView(arena: std.mem.Allocator, value: std.json.Value) !View;

pub const Status = enum { idle, connecting, connected, reconnecting, @"error" };
pub const Activity = enum { idle, done, working, @"error", blocked };
/// What the core learned about one terminal block beyond the view: the OSC title, the
/// foreground program and its directory (`com.superlogical.terminal.process`), and the
/// most urgent state its programs reported (`com.superlogical.terminal.program_status`).
pub const BlockInfo = struct { title: []const u8 = "", program: []const u8 = "", cwd: []const u8 = "", activity: Activity = .idle };
pub const UiState = struct {
    status: Status, server: []const u8, url: []const u8,
    /// Operating system account the server runs as (`server.whoami`); "" until it answers.
    account: []const u8 = "",
    /// The server's host list (`hosts.list`) and its hash; "" before the first answer.
    hosts: []const HostEntry = &.{}, hosts_hash: []const u8 = "",
    sessions: []const Session, session: ?Id,
    view: ?*const View,
    /// By block id. A block without an entry shows its label.
    blocks: *const std.StringHashMapUnmanaged(BlockInfo),
    /// By block id: the lead blocks of the tabs of sessions other than the selected one.
    leads: ?*const std.StringHashMapUnmanaged(BlockInfo) = null,
};
/// The block a tab is named after: its focused block, else its first.
pub fn leadBlock(w: *const Window) ?*const Block;
/// The JSON Kotlin consumes (app/src/main/java/io/github/yhzion/velo/core/UiState.kt):
/// {status, server, url, account, hosts:[{id,label,endpoints:[…],icon}], hostsHash,
///  sessions:[{id,label,tabs:[{id,label,panes,active,minimized,block,title,program,cwd,
///  activity,attention}]}], session, windows:[{id,label,icon,
///  layout, zoomed, minimized, minimizedBlocks:[id], minimizedLayers:[[id]],
///  floating:[{block,x,y,w,h}]}], window, focused,
///  blocks:{id:{title,program,cwd,activity,attention}}}
/// `tabs`: the session's windows in the session's order. Left out while the core does not
/// know them: before the session's view arrived, when its `session.view` failed, and for
/// every session of a core without a selected session. `[]` is a session that has no
/// tabs. `panes` = the tab's blocks, tiled and floating, without those of minimized
/// layers (`parseView` skips them), `active` = the session's active window (its first
/// when the view names none), `block` = the tab's lead block (`leadBlock`; left out for a
/// tab without blocks), and `title`, `program`, `cwd`, `activity`, `attention` describe
/// that block as its entry of `blocks` does (`title` falls back to the block's label).
/// `minimized` (always written; the same value in the tab and in the selected session's
/// entry of `windows`) is true when the tab has at least one block in a minimized layer:
/// a program that runs where nothing draws it. Such a block is not in `panes`, never the
/// tab's `block`, in no layout and no `floating`. So:
///   empty tab             panes 0, minimized false, no `block`
///   all panes minimized   panes 0, minimized true, no `block`, `title` "", `program` ""
///   visible + minimized   panes >= 1, minimized true, `block` = a visible one
///   one plain shell       panes 1, minimized false
/// and "this tab is one shell and nothing else" is `panes == 1 and not minimized`. What
/// the minimized blocks run is not published. A tab with a minimized block also writes
/// `minimizedBlocks` (the id of every block of every minimized layer, in layer stack
/// order) and `minimizedLayers` (the same ids grouped by layer, one array per layer), and
/// each of those blocks is named in the `blocks` map, so the app can show one entry per
/// stashed pane. `icon` is left out: the server's view names none.
/// `session`, `windows`, `window`, `focused` and `blocks` are the selected session's and
/// agree with its `tabs`; `blocks` holds no block of another session.
/// layout node: {"block":id} or {"dir":"row"|"column","ratio":f,"first":node,"second":node}
/// ("row" = side by side = Rex "horizontal"). `window` = active window, `focused` = its
/// focused block. `attention` is true while a program waits for the user or reports an
/// error. Caller frees.
pub fn stateJson(gpa: std.mem.Allocator, state: UiState) ![]u8;
```

## `rex/protocol.zig` (Model) — message builders/parsers, no I/O

```zig
pub const api_revision = 18;
pub fn hello(gpa, version: []const u8, reconnect_token: ?[]const u8) ![]u8; // < 512 bytes
pub fn request(gpa, id: u64, method: []const u8, payload_json: []const u8) ![]u8; // payload_json is a JSON object text
pub const Incoming = union(enum) {
    welcome: struct { client_id: []const u8, reconnect_token: []const u8, api_revision: i64 },
    response: struct { id: u64, seq: i64, payload: ?std.json.Value, err: ?struct { code: []const u8, message: []const u8, status: i64 } },
    event: struct { seq: i64, session: ?Id, kind: []const u8, payload: std.json.Value }, // session_event is unwrapped: kind = inner type
    other,
};
pub fn parse(arena, text: []const u8) !Incoming;

// Payload builders (JSON object text). Argument names verified against /llms.txt rev 18:
pub const Attach = union(enum) { pick, none, session: Id }; // the server picks / attach_session_ids [] / [id]
pub fn bootstrap(gpa, attach: Attach) ![]u8;            // event_scope "all" (every session's events, block events too); data_tickets terminal
pub fn sessionView(gpa, session: Id) ![]u8;
pub fn dataConnect(gpa, session: Id, block: Id) ![]u8;  // data_ref_id "terminal", subprotocol rex.data.terminal.v1
pub fn newSplit(gpa, session: Id, anchor_block: Id, below: bool) ![]u8; // direction vertical|horizontal, side "after", layout {block:{flavor:"com.superlogical.terminal.shell"}}, focus true
pub fn floatBlock(gpa, session: Id, block: Id) ![]u8;   // bounds [0.15,0.15,0.7,0.7]
pub fn moveBlock(gpa, session: Id, block: Id, anchor: Id) ![]u8; // dock: moveTo(…, .right, null)
pub fn moveTo(gpa, session: Id, block: Id, anchor: Id, direction: SplitDirection, ratio: ?f64) ![]u8; // session.move_block; direction and side as newSplit maps them; ratio = share of the before part, left out when null
pub fn swapBlocks(gpa, session: Id, first: Id, second: Id) ![]u8; // session.swap_blocks {block_id, other_block_id}
pub fn moveWindow(gpa, session: Id, window: Id, before: ?Id) ![]u8; // session.move_window; before_window_id left out = the end
pub fn zoomBlock(gpa, session: Id, block: Id, zoom: bool) ![]u8;
pub fn closeBlock(gpa, session: Id, block: Id) ![]u8;   // keep_last true
pub fn focusBlock(gpa, session: Id, block: Id) ![]u8;
pub fn newWindow(gpa, session: Id) ![]u8;               // layout one shell block, focus true
pub fn focusWindow(gpa, session: Id, window: Id) ![]u8;
pub fn closeWindow(gpa, session: Id, window: Id) ![]u8;  // session.close_window: the window and every block in it
pub fn destroySession(gpa, session: Id) ![]u8;           // session.destroy
pub fn setSessionLabel(gpa, session: Id, label: []const u8) ![]u8; // session.set_label; "" restores the server's name
pub fn setWindowLabel(gpa, session: Id, window: Id, label: []const u8) ![]u8; // session.set_window_label; "" restores `Window 89abcdef`
pub fn blockCall(gpa, session: Id, block: Id) ![]u8;     // a block method without arguments (terminal process, program_status, title)
pub fn terminalWrite(gpa, session: Id, block: Id, data: []const u8) ![]u8; // method com.superlogical.terminal.write, args {data}
pub const methods = struct { pub const bootstrap = "session.bootstrap"; /* … one const per method above */ };

// Data connection handshake:
pub fn dataHello(ticket_b64url: []const u8, out: *[17]u8) !void;
pub fn dataResume(token: [16]u8, consumed: u64, out: *[25]u8) void;
pub const DataWelcome = struct { token: [16]u8, resume_ms: u32, nonce: u64 };
pub fn parseDataHandshake(bytes: []const u8) union(enum) { welcome: DataWelcome, reject: u8, invalid };

/// User intent from Kotlin (`VeloCore.command` JSON).
pub const Intent = union(enum) {
    split: struct { block: Id, below: bool }, float: Id, dock: Id, zoom: Id, unzoom: Id,
    move_pane: struct { block: Id, anchor: Id, direction: SplitDirection }, // server structure, not screen
    swap_panes: struct { block: Id, other: Id },
    move_to_new_tab: Id,                                 // also op restoreInNewTab
    undo_move: union(enum) {
        swap: struct { block: Id, other: Id },
        beside: struct { block: Id, anchor: Id, direction: SplitDirection, ratio: f64 },
        retab: struct { block: Id, label: ?[]const u8, before: ?Id }, // null label = the server's name, null before = the end
    },
    close: Id, focus: Id, new_session: struct { label: ?[]const u8 },
    new_window: struct { session: ?Id },                 // null = the selected session
    select_window: struct { session: ?Id, window: Id },  // null = the selected session
    close_window: struct { session: Id, window: Id }, close_session: Id,
    move_window: struct { session: Id, window: Id, before: ?Id }, // null before = the end
    rename_session: struct { session: Id, label: []const u8 },
    rename_window: struct { session: Id, window: Id, label: []const u8 },
    send_text: struct { block: Id, text: []const u8 },
    paste: struct { block: Id, text: []const u8, confirmed: bool },
    fit: Id, touch: Id,
    setting: struct { key: []const u8, value: std.json.Value },
    hosts_put: struct { id: []const u8, label: []const u8, url: []const u8 }, hosts_remove: []const u8,
    detach, fence: []const u8, unknown,
};
/// ops: split{block,dir:"right"|"below"}, float, dock, zoom, unzoom, close, focus, fit,
/// touch (each {block}), movePane{block,anchor,dir:"left"|"right"|"above"|"below"},
/// swapPanes{block,other}, moveToNewTab{block} (alias restoreInNewTab{block}),
/// undoMove{kind:"swap",block,other} | undoMove{kind:"beside",block,anchor,dir,ratio} |
/// undoMove{kind:"retab",block,label?,before?}, newWindow{session?}, newSession{label?},
/// selectWindow{session?,window}, closeWindow{session,window}, moveWindow{session,window,before?},
/// closeSession{session}, renameSession{session,label}, renameWindow{session,window,label},
/// sendText{block,text}, paste{block,text,confirmed?}, setting{key,value},
/// hostsPut{id,label,url}, hostsRemove{id}, detach, fence{prefix?}. An unknown op, or one
/// missing a required field, is `.unknown`, and so is an undoMove beside with a ratio
/// outside (0, 1); a paste without `confirmed` is not confirmed.
pub fn parseIntent(arena, json: []const u8) !Intent;
/// The bytes a paste writes: with bracketed paste `ESC[200~` text `ESC[201~`, every
/// `ESC[201~` taken out of the text first; without it the text as it is, or
/// `error.NeedsConfirm` when it has a control character other than a tab and is not confirmed.
pub fn pasteInput(gpa, text: []const u8, bracketed: bool, confirmed: bool) ![]u8;

/// `session.own_block` in mode `take`.
pub fn ownBlock(gpa, session: Id, block: Id) ![]u8;
/// `com.superlogical.terminal.resize`; each value is sent as at least 1.
pub fn terminalResize(gpa, session: Id, block: Id, cols: u16, rows: u16, cell_w_px: u16, cell_h_px: u16) ![]u8;
```

## `rex/StreamFilter.zig` (Model)

Splits a terminal data stream into the Ghostty snapshot, program bytes and Rex messages.

```zig
pub const RexMessage = union(enum) {
    resize: struct { cols: u16, rows: u16, cell_w_px: u32, cell_h_px: u32 },
    theme: struct { foreground: ?[3]u8, background: ?[3]u8, cursor: ?[3]u8, palette: []const struct { u8, [3]u8 } },
    child_exited: i32,
};
pub const Sink = struct {
    ctx: *anyopaque,
    /// Bytes of program output (Rex APCs with the channel nonce removed).
    bytes: *const fn (ctx: *anyopaque, data: []const u8) void,
    message: *const fn (ctx: *anyopaque, msg: RexMessage) void,
};
pub const StreamFilter = struct {
    pub fn init(gpa: std.mem.Allocator, nonce: u64) StreamFilter;
    pub fn deinit(self: *StreamFilter) void;
    /// Feed post-snapshot stream bytes in any chunking. `ESC _ rex1;<nonce>;<json> ESC \`
    /// with a matching decimal nonce → `message`; any other bytes (including APCs with
    /// another nonce and sequences split across chunks) → `bytes`, in order.
    pub fn feed(self: *StreamFilter, data: []const u8, sink: Sink) void;
};
```

## `rex/Core.zig` + `rex/DataStream.zig` (Core)

```zig
pub const Host = struct {          // implemented by jni.zig (and by probe.zig / tests)
    ctx: *anyopaque,
    onState: *const fn (ctx: *anyopaque, gen: u64, snapshot: []const u8, patch: ?[]const u8) void,
    onPaneReport: ?*const fn (ctx: *anyopaque, json: []const u8) void = null,
    onPaneWrite: ?*const fn (ctx: *anyopaque, tag: []const u8, result: Core.PaneWriteResult, message: []const u8) void = null,
    onReconnectToken: *const fn (ctx: *anyopaque, token: []const u8) void,
    onError: *const fn (ctx: *anyopaque, code: []const u8, message: []const u8, fix: []const u8) void,
    glyphs: pane.GlyphSource,
};
pub const Core = struct {
    pub fn create(gpa: std.mem.Allocator, host: Host, net: Net, px_per_sp: f32) !*Core;
    pub fn destroy(self: *Core) void;
    pub fn connect(self: *Core, url: []const u8, reconnect_token: ?[]const u8) void; // e.g. https://rex.example.ts.net, ssh://user@rex.example.ts.net, or /path/to/rex.sock; also reconnects after a stop
    pub fn disconnect(self: *Core) void;
    pub fn requestState(self: *Core) void;
    pub fn selectSession(self: *Core, session: []const u8) void; // before the first connect: the session the bootstrap names
    pub fn command(self: *Core, json: []const u8) void;
    pub fn key(self: *Core, block: []const u8, key_code: i32, meta: i32, unicode: u32, action: u8) void;
    pub fn paneSurface(self: *Core, block: []const u8, window: ?*anyopaque, w: u32, h: u32) void;
    pub fn panePreview(self: *Core, block: []const u8, window: ?*anyopaque, w: u32, h: u32) void;
    pub fn paneResume(self: *Core, block: []const u8, window: ?*anyopaque, w: u32, h: u32) void;
    pub fn paneSuspend(self: *Core, block: []const u8) void;
    pub fn paneScale(self: *Core, block: []const u8, scale: f32, live: bool) void; // live: a pinch is still changing it
    pub fn panePan(self: *Core, block: []const u8, dx: f32, dy: f32, x: f32, y: f32) void;
    pub fn paneTouch(self: *Core, block: []const u8, action: u8, x: f32, y: f32) void;
    pub fn paneCopy(self: *Core, gpa: std.mem.Allocator, block: []const u8) ![]u8;
    pub fn paneBracketedPaste(self: *Core, block: []const u8) bool; // would a paste be framed now
    pub fn paneHasSelection(self: *Core, block: []const u8) bool; // would paneCopy copy a selection
    pub fn paneClearSelection(self: *Core, block: []const u8) void; // a tap on the pane ends its selection
};
```

Behaviour:
- One control thread: connect WebSocket `/api/control` (`rex.control.v1`), send hello,
  read welcome (publish reconnect token), `session.bootstrap`, then loop on `recv`.
  Publishes a full snapshot or a patch when state changes. On drop: status `reconnecting`, backoff
  1 s → 15 s, reconnect with the latest reconnect token.
- An ssh connection that fails gets the note of the SSH pool: its code and sentence, and
  `fix` for `onError` (otherwise `connect_failed`, fix ""). Codes retrying cannot fix
  (`declined`, `host_key_changed`, `password_refused`, `key_refused`, `not_installed`,
  `malformed`, `no_user`, `ssh_unavailable`, `no_common_algorithm`, `login_refused`) **stop** the core: the note goes out even
  after a first connection, status `error`, no backoff, and no data stream dials
  (`DataStream.Owner.may_dial` is false) until `connect` (Reconnect), `disconnect` or
  `destroy` changes the epoch. The stop is recorded with the note, before `onError` runs. Other codes (`unreachable`, `not_ssh`,
  `closed_before_login`, `ssh_not_ready`, `internal`) back off and retry as above.
- `session_view_changed` with a revision higher than the held view → `session.view`.
  `session_created`/`session_destroyed`/`session_label_changed`/`window_label_changed`
  → refresh the session list / view.
- One `DataStream` (thread + WebSocket `/api/data`) per terminal block of the selected
  session's windows. Ticket from bootstrap `data_tickets` when present, else
  `data.connect`. HELLO → WELCOME; first stream bytes are the snapshot. `Framing` reads
  its record headers as they arrive (format 1: a ten-byte envelope, then ten-byte headers
  that carry the payload length, ending with FINISH) and `pane.restoreSnapshot` gets the
  buffer once FINISH is in: once per snapshot, not per chunk, since the decoder parses from
  the first byte. An envelope it does not know is tried on every chunk until the
  decoder stops returning `error.NeedMoreData`; a record that would end past 256 MiB ends
  the stream as too large. A new stream (HELLO) frees what the last one collected; a RESUME
  keeps it. Then `StreamFilter` → `pane.write` / `pane.resize` /
  `pane.setTheme`. Count consumed stream bytes; on drop RESUME within the resume window,
  else new ticket + HELLO (pane gets a fresh snapshot). Close code 4000 = block closed.
- Panes live in a `block id → *pane.Pane` map, created on first sight with
  `host.glyphs`, `setFontSizePx(font_sp * px_per_sp)`; destroyed when the block leaves
  every window of the selected session. Pane title changes (poll `generation()` on the
  data thread after each write) republish state.
- Threads name themselves as they start, so a trace tells them apart:
  `velo-control`, `velo-writer` (the control socket's writer), `velo-tslog` (the tailnet log
  pipe), and per pane `velo-data-<tag>` and `velo-draw-<tag>`, where `<tag>` is the end of
  the block id (`Trace.blockTag`: four bytes at most). ATrace sections
  (`native/src/term/Trace.zig`, inactive off Android) include `Renderer.buildFrame`,
  `Renderer.present`, `Renderer.rasterizeGlyph` (an atlas
  miss), `DataStream.feed` (one received message: its `Pane.write`s and their waits for
  the pane lock; not one section per write, which a flood makes once a line),
  `Pane.restoreSnapshot`, `Core.stateSnapshot` and `Core.publishState`. A thread the
  core named keeps its name when it calls into Java (`jni.attach` attaches it under it).
- Android key actions: 0 down, 1 up, 2 multiple→treat as repeat. `key` encodes with
  `pane.encodeKey` and sends `com.superlogical.terminal.write`. Touch actions from
  Android `MotionEvent`: 0 down, 1 up, 2 move, 3 cancel → Pane expects 0 down, 1 move,
  2 up: map 0→0, 2→1, 1/3→2.
- Intents: split → `session.new_split`; float → `session.float_block`; dock →
  `session.move_block` beside the window's focused tiled block (or first tiled block);
  zoom/unzoom → `session.zoom_block`; close → `block.close`; focus →
  `session.focus_block`; newWindow → `session.new_window`; newSession →
  `session.create` (one shell window) then `session.attach` to its answer;
  selectWindow → `session.focus_window`; closeWindow → `session.close_window`;
  closeSession → `session.destroy`; renameSession → `session.set_label`; renameWindow →
  `session.set_window_label`; moveWindow → `session.move_window`; sendText → terminal
  write; setting `fontSize` (sp) → `setFontSizePx` on all panes. The Android appearance
  preference is local to the UI; the core receives no setting or server request when it
  changes.
- Stashing panes. A stashed pane is a block of a minimized floating layer: hidden, still
  running. Each request is checked against the held view of the selected session right
  before it is sent, like a pane move. The app never sends what the server would refuse
  (a tab's last panes on screen stay), and the core refuses it too.
  - `{"op":"minimizePane","block":B}`. B is a tiled block or a floating block of a tab of
    the view, else nothing is sent. Some placed block of that tab must stay on screen: a
    tab's other tiled block, or a floating block of another layer. Else nothing is sent
    and one `bad_request` error, "the tab's last pane on screen stays", is reported. A
    floating B → `session.set_layer_state` `minimized` for B's layer (no `focus`). A tiled
    B → `session.float_block` with the usual bounds, pending `stash_floated`; its answer's
    `layer_id` → `session.set_layer_state` `minimized` for that layer. A failed float
    reports its error and sends nothing more; an answer without `layer_id` reports
    `internal`, "the pane floated but could not be minimized" (the pane stays floating).
  - `{"op":"restorePane","session":S,"window":W,"block":B,"focus":true}`. Shows the
    minimized layer that holds B (`session.set_layer_state` `normal`). Notifications send it
    without `focus`, so no pane takes focus; the capsule sends `focus: true`, which raises
    the layer and focuses it. `focus` is optional (default false) and is sent as the
    request's `focus`. Keys of `session.set_layer_state`: `session_id`, `layer_id`, `state`,
    `focus` (left out when not given, as for minimize).
  - `{"op":"minimizeFloating","window":W,"minimize":true|false}`. W is a tab of the
    selected session's view. `true` needs a tiled block in W, then sends one `minimized`
    request per floating layer of W; `false` sends one `normal` with `focus:false` per
    minimized layer of W. Otherwise nothing is sent.
- Pane moves. The app chose each one from a layout that may have changed since, so the
  core checks it against the held view right before it sends it. A move that fails the
  check sends nothing and reports one `conflict` error, "the layout changed; drag it
  again". Nothing is replayed after an error or on a new connection.
  - movePane → `session.move_block` (`protocol.moveTo`, no ratio). Both blocks are drawn
    in a tab of the selected session, the anchor is a tiled block, and they differ.
  - swapPanes → `session.swap_blocks`. Both blocks are drawn in a tab (the other may
    float), and they differ.
  - moveToNewTab (alias restoreInNewTab) → `session.new_window` at the end, unfocused,
    with a temporary anchor (`newDockTab`, a `sleep 60` that exits on its own); then
    `session.move_block` beside the anchor; then `block.close` of the anchor only
    (`keep_last` false). The block's tab must hold another block (tiled, floating or
    minimized), or the block floats in a tab without tiled panes. The only tiled pane of
    a tab is refused. One move to a new tab runs at a time; another meanwhile is dropped.
  - After a successful move the core sends `session.focus_block` for the moved block, so
    that its tab shows, unless another session was selected meanwhile.
  - undoMove: the app works out the inverse from the layout before the move. The core
    keeps the highest `revision` among the answers to the last move's requests. It
    refuses an Undo with `conflict`, "the layout changed; Undo is no longer possible",
    when no move completed on this connection and selection, or when the held view (or a
    revision the server has announced) is newer. Then it runs like a move with the same
    checks: swap → `session.swap_blocks`; beside → `session.move_block` with the old
    `ratio`; retab → the new tab flow, then `session.set_window_label` with `label` and
    `session.move_window` before `before`, each when given. An Undo, and the next move,
    end what can be undone: an Undo has no redo.
- paste → terminal write of the bytes of `protocol.pasteInput`. The terminal has bracketed paste
  when its pane has mode 2004 set and its stream shows a live snapshot (the test the
  composer uses); a terminal the core does not mirror has not. With bracketed paste the
  text is framed, and the end mark `ESC[201~` is taken out of it, so the text cannot end
  the paste itself. Without it a text with no control character other than a tab is
  written as it is; one with a line break (`\n`, `\r`) or another control character
  (U+0000 to U+001F, U+007F to U+009F) is written as it is only with `confirmed: true`,
  else nothing is written and the error `paste_needs_confirm` is reported.
  `paneBracketedPaste` answers the same test for the app, which asks the user before it
  sends; the core reads the mode again when the command runs, so a mode that changed in
  between lets no unconfirmed line break through.
- Terminal size: see "Terminal size" below. Failed `session.own_block` and
  `com.superlogical.terminal.resize` requests are never reported to the host.
- Errors from requests → `host.onError(code, message)`. Not reported: a `closeWindow` or
  `closeSession` answered `not_found` or `session_not_found` (the target is gone, which
  is what was asked for); a `session.attach` answered `session_not_found` (the session
  went while this client did not see it: its row goes and the core picks, see below);
  and a failed view request of a followed session. Any other error of an attach is
  reported, and then no session is selected and none is attached in its place.

### Sessions and tabs

The core is attached to one session of its server, the selected one, and follows the
windows of every other one, so that the state lists each session's tabs.

- How it follows: the bootstrap asks for `event_scope: "all"`, which brings every
  session's events to a connection attached to one (scope `session` leaves out
  `block_event` of the sessions it is not attached to). For each session other than the
  selected one the core holds a `Follow` (its last `session.view`) and, per tab whose
  lead block is a terminal, a `Lead` (title, program, directory, activity). It attaches
  no session but the selected one: an attached client is listed in
  `session.list_clients`, is in line for the session's blocks and can take their size.
  Nothing of a followed session reaches a pane, a data stream or the size rules.
- Requests: one `session.view` per followed session after the bootstrap and after a
  session appears in the list; after that one per `session_view_changed` of that session
  with a revision above the view held. While one is in flight, further events only raise
  the wanted revision and at most one more request follows the answer. A list answer
  with the same sessions repeats nothing. A tab added, closed, renamed, reordered or
  made active in any session reaches the state this way.
- A new lead asks `com.superlogical.terminal.process`, `…program_status` and `…title`
  once. Afterwards `process_changed` asks `process` again, `program_status_changed` and
  `program_status_removed` ask `program_status` again, and `title_changed` carries the
  title itself. A block event of a block that leads no tab costs nothing. So `title`,
  `program`, `cwd`, `activity` and `attention` of the tabs of sessions that are not
  selected follow without a data stream.
- The bound, without a timer: per lead one request of each kind (`process`,
  `program_status`, `title`) is in flight at a time, and per followed session one
  `session.view`. An event that arrives meanwhile marks it stale, and one more request
  goes out when the answer arrives. So a burst of N events costs at most two requests
  of that kind, and a steady stream costs one request per round trip. The state is
  published only when an answer changes a field. Measured on the test server
  (2026-11-20): `seq 1 300000` in a terminal that is not shown sends 0 to 2
  `process_changed` and the core sends as many requests; 300 short programs in a row
  send 3 to 16 `process_changed` (the server coalesces them) and the core sends as many
  requests. With the bound taken out the counts are the same: on a LAN the answer comes
  back before the next event, so the bound is for slow links and for servers that
  coalesce less.
- `event_dropped` for a followed session reads its view again, and its lead block
  (all three methods) when the event names one. A session that leaves `session.list`
  without a `session_destroyed` is no longer followed.
- Selecting another session: the session shown until now is followed from the view and
  the block texts its panes held, so its tabs do not blink and nothing is asked again.
  The newly selected one keeps its followed view in the state until the attach answer
  brings its own; then its follow and leads end and its panes take over.
- Intents that name a session:
  - `{"op":"newWindow","session":S}` and `{"op":"selectWindow","session":S,"window":W}`:
    when S is not the selected session, `session.detach` of the old one and
    `session.attach` of S are queued first, then `session.new_window` /
    `session.focus_window` on the same connection. Whichever answer comes first, the
    panes end on the tab asked for: the view of the attach answer is replaced by the
    `session.view` the method's `session_view_changed` asks for. Without `session` both
    act on the selected session, as before.
  - `{"op":"closeWindow","session":S,"window":W}`, `{"op":"closeSession","session":S}`,
    `{"op":"renameSession","session":S,"label":L}` and
    `{"op":"renameWindow","session":S,"window":W,"label":L}` are sent for S whichever
    session is selected, and select nothing. (The server takes them from a connection
    that called `session.bootstrap`, attached to S or not; run live for all four.) An
    empty label restores the name the server made.
  - Each of them without a connection reports `not_connected`.
  - Tab moves: `{"op":"moveWindow","session":S,"window":W,"before":B}` puts tab W before tab
    B of S; without `before` it goes to the end. Without a connection it reports
    `not_connected`. It is one `session.move_window`, sent for S whichever session is
    selected, and selects nothing. The core does not check it against the held view: the app
    sends it from the order it shows, and the server refuses a tab that is gone (the error
    reaches `host.onError`, as a rename's does). The answer is `{"revision":N}`; the core
    takes N as a `session_view_changed` of S would be taken, so a newer N reads the view of
    S (selected or followed) at once and an N it already holds reads nothing. Measured on
    the real server: the answer was `{"revision":6}` and the server also sent one
    `session_view_changed` with that same revision. It adds nothing once the view is read;
    one that arrives while that read is in flight asks for one more read, as any event does.
    Never replayed on a reconnect.
- The selected session is destroyed (`session_destroyed`, by this client or another):
  the core attaches the session after it in the list, else the one before it, else none
  (`neighbour`), and the state says so at once. After `detach` it selects none.
- A session named before the first connect (`selectSession`, then `connect`) that the
  server no longer has, and a `session.attach` answered `session_not_found`: nothing is
  attached for it and the core attaches the first listed session (there is no place in
  the list to take a neighbour from).
- `{"op":"detach"}` (a parked remote): `session.detach`, no selected session, no view,
  no panes, no follows and no leads, so no view request; `tabs` is left out of every
  session. The session list itself stays current. A reconnect attaches nothing.
  `selectSession`, or an intent that names a session to select, brings all of it back.
- A reconnect: the bootstrap names the selected session; each followed session's view
  and each lead are asked for once on the new connection, since events sent while the
  link was down are gone. The tabs held stay in the state meanwhile.
- The fence, `{"op":"fence","prefix":"velo-test-3-"}`: with a prefix, a session
  exists for this core only when its label starts with the prefix, or this core created
  it since it started (the id its own `session.create` answered). A debug build of the
  app sends it, before `connect`, so that development on a shared server never touches a
  session someone else uses, including one made while the app runs; a release build
  sends none. The intent is accepted at any time and replaces the prefix; no prefix or
  `""` is no fence and changes nothing (the same requests and the same state as without
  the intent). The prefix is kept across connections. A session that does not exist:
  - is in no `sessions[]` of the state and is never the top-level `session`;
  - is never attached: `selectSession` and every intent that names it are dropped (no
    request, no `onError`), and the pick and the neighbour rule work on the list without
    it, so they skip it in both directions and give none when no other is left;
  - is never followed: no `session.view`, no lead request, and every event of it is
    dropped without a request (a `session_label_changed` is read for its label only).
  The label decides whenever it is known (`session.list`, `session_created`,
  `session_label_changed`): a session renamed out of the prefix stops existing (the
  selected one is detached, the one request that names it, and the core picks as after
  its destruction), one renamed into it starts existing (the list is read again).
  No label is known at the bootstrap, so with a fence the bootstrap always asks for
  `attach_session_ids: []`, also with a selection. When its answer has brought the list,
  the core attaches the selected session if it exists (`session.attach`), else picks
  the first that does. The attach answer confirms which panes survived. The core
  restarts each surviving stream once. Input entered on a held pane while the attach
  is pending stays with that pane. After attach, the core applies the size rules and
  queues any required `session.own_block` before sending that pane's buffered input.
  A pane removed by the attach answer or by a session switch loses its queued input.
  If the bootstrap reports an attachment to a different session, the core detaches
  it before creating a view or pane for it.

### Keyboard composition preview

The pane draws up to 256 bytes of uncommitted IME text locally, using Ghostty's cell widths.
The limit applies to the preview, not to the text sent on commit. Before displaying an edit,
the core asks for the block's `terminal.input_mode` and `terminal.process` in parallel. Neither
request contains the composition. A canonical read with echo off, or a foreground program named
`sudo`, `su`, `passwd` or `doas`, hides the preview. Bash readline disables both echo and canonical
input, so it can still show compositions. Missing or failed replies leave the preview hidden.

This is not a universal password detector. A different program that reads secrets in noncanonical
mode can look like a line editor. A slow or unsupported server can delay or suppress the preview;
neither condition prevents the keyboard from committing input.

### Terminal size (`rex/SizeRules.zig`)

A Rex terminal takes its size from the client that owns its block, and the server keeps
the size each client asked for last (`desired`) and applies it when that client becomes
the owner. So the device used last decides the size with takes alone: the core never
gives a block up (no `session.disown_block`) and has no timer. Kotlin reports events; the
core decides. `SizeRules.State` (one per terminal block, in `Entry.size`) holds the
decisions as a pure function `step(event, wanted, can_send) → {take, resize}`; `Core`
feeds it and sends what it returns through the request queue, the take before the size.

- Wanted size: `pane.fitGrid(w, h)` of the size the app gave the pane's surface last:
  columns and rows of the local cell (font size × pinch scale, line height) that fit the
  surface less the padding on both sides, at least 1 × 1, with the cell size in px.
- `paneSurface(block, window, w, h)` with an area, for a pane that is not shown: the pane
  is shown. The core sends `session.own_block` (`take`) unless this client owns the
  block, then `com.superlogical.terminal.resize` with the wanted size unless it is the
  size asked for last. A surface with a zero axis shows nothing: no take, no size, and a
  `fit` meanwhile waits for an area.
- `paneSurface` for a pane that is shown (the view changed size: keyboard, rotation,
  fold, split): the surface is replaced and its size kept; nothing is sent.
- `panePreview(block, window, w, h)` (the tab overview): the pane's surface is a preview.
  The pane has a surface but is not shown, exactly as after `paneSurface(block, null)`:
  nothing is sent, and a take or a size still owed is dropped. While it is a preview,
  `fit`, the settings, a touch, a key and `sendText` send no take and no size; typed
  input is still written to the terminal (`com.superlogical.terminal.write`), because
  whether keys reach a pane in the overview is the app's decision.
- `paneSurface` for a pane whose surface is a preview (the same window: the attach is in
  place, see docs/native-internal.md): the pane is shown, as by a first surface. A take
  unless this client owns the block, the wanted size unless it is the size asked for
  last. So into the overview and back with nothing changed sends nothing, unless another
  client took the terminal meanwhile: then this is a take. The app uses it for a tab that
  becomes the shown one (another card, a tab switch) and for a pane that was used on the
  way (a pinch changed its text size).
- `paneResume(block, window, w, h)` for a pane whose surface is a preview: the app says
  the overview closed on the tab it was opened from, and this pane was shown there.
  Looking at the overview is no use of a terminal, so a pane that was shown when its
  preview began, and has had a surface ever since, is shown again as it was: no take,
  also when another client has the terminal now, and the wanted size only when it is not
  the size asked for last (the pane area changed meanwhile). A take that was still owed
  when the preview began is owed again. The return is used once. For every other pane (it
  was never shown, or it lost its surface while it was a preview) the call is
  `paneSurface`. After it the pane is shown: a touch, a key or `sendText` takes as always.
- `paneSuspend(block)`: the pane's surface went away because the app left the screen (the
  screen went off, another app came to the front; Android destroys every surface then).
  The pane has no surface and is not shown, as after `paneSurface(block, null)`, but the
  rules remember that it was shown. `paneResume` for it, when the app is on the screen
  again, is the same return as from a preview: no take, also when a desktop client took
  the terminal while the phone was locked, and the wanted size only when it is not the
  size asked for last (the phone was folded meanwhile, or the link dropped and the server
  holds no size from the new connection). `paneSurface(block, null)` after a suspend (the
  pane was closed while the app was away) forgets the return. Without this the phone
  took every shown terminal back each time it was unlocked.
- The app calls `paneSuspend` and `paneResume` the same way when its own sidebar or
  screen covers the panes and gives them back (the sidebar where it takes the panes'
  place, Settings, the Tailscale screen, pairing). The pane's view is gone then and the
  pane is not, so the rules are the same: back with the size it had, the pane sends
  nothing, and back with another size (the window was rotated or folded meanwhile) it
  sends the size and no take. The app decides which panes come back (`PaneCover` in
  `ui/PaneCover.kt`): a pane of another tab or session, and one whose view went away
  without a cover, get `paneSurface`. Without this, opening and closing the compact
  sidebar took every shown terminal from the desktop.
- Intent `fit{block}` (the app: a surface size that has settled, a pinch that ended) and
  the settings `fontSize`, `fontLineHeight`, `panePaddingPx`, `display` (every shown pane):
  the wanted size is computed again and asked for when it differs from the size asked for
  last. `paneScale` alone sends nothing. `display{pxPerSp, panePaddingPx}` replaces the
  factor given to `create` and the padding together, so each pane is fitted once: the
  activity handles a `density` change itself and a font scale change keeps the cores, so
  the app sends the display's scale (Display size, Font size) when it changes. The app
  sends the padding only this way.
- Touch down (`paneTouch` action 0, or intent `touch{block}` for a touch the app does not
  pass to `paneTouch`), a key that produces bytes, `sendText`: a take when this client
  does not own the block and the pane's surface is not a preview. No size is sent with
  it, because the server applies the one it holds; only a shown pane that asked for none
  on this connection asks with it.
- At most one take per block is in flight; a pane shown again while one is in flight
  takes after its answer if this client is not the owner then. The owner is read from
  `session.view` (`Block.owner`) and `block_owner_changed`, compared with the welcome's
  client id (`Core.client_id`). A take that succeeds makes this client the owner unless
  its answer names another client for the block.
- `paneSurface(block, null)`, and `panePreview(block, null)` which is the same call: the
  pane has no surface and is not shown. Nothing is sent and the size asked for last is
  kept.
- A reconnect claims nothing. It forgets the size asked for last, so the next show or
  `fit` asks again. A pane shown, or fitted, while the link was down is taken and sized
  when the session is attached again.
- While another client owns a shown block the pane draws the server's grid as before.

## `jni.zig` (Build + JNI)

Exports `Java_io_github_yhzion_velo_core_VeloCore_{start,connect,disconnect,requestState,destroy,selectSession,
command,key,paneSurface,panePreview,paneResume,paneSuspend,paneScale,panePan,paneTouch,paneCopy,
paneBracketedPaste,paneHasSelection,paneClearSelection}` and `JNI_OnLoad`
(caches `JavaVM`). Signatures per `app/src/main/java/io/github/yhzion/velo/core/VeloCore.kt`.
`start` keeps a global ref to the listener and method IDs for `onState(long,boolean,String)`,
`onReconnectToken(String)`, `onError(String,String,String)` (code, message, fix),
`rasterizeGlyph([BZZIII)[B`. Callbacks attach the calling native thread
(`AttachCurrentThread`, detach via a pthread key destructor). `paneSurface`,
`panePreview` and `paneResume` convert `Surface` with `ANativeWindow_fromSurface` (Pane owns and releases
that reference).
`onState(gen, full, json)` sends a full snapshot first and on `requestState`. Otherwise
JNI sends only changed groups: scalars (including hosts and nullable selection), sessions,
windows, or blocks. A present group replaces its predecessor, including null IDs and empty
collections. The core still serializes the model once to compare group bytes. Generations
count changes in callback order. A missing baseline or generation gap needs a new full
snapshot; post `requestState` outside the callback, which holds the host delivery lock.
The command-line probe continues to print full snapshots.
`onPaneReport(String)` carries only watch reports. A write completion instead calls
`onPaneWrite(tag, result, message)`. The tag identifies the pending write; the result
is `sent`, `changed`, `not_sent`, or `unconfirmed`. Neither side encodes the reply as JSON.

`sys/glyphs.zig` implements `pane.GlyphSource` over `rasterizeGlyph`, copying the
returned `byte[]` into a per-thread buffer (A8 when len == w*h, RGBA when len == w*h*4).
JNI types come from a HOST translate-c of NDK `jni.h` (it includes only `stdarg.h` and
`stdint.h`; verified to translate: 1298 lines) added as module `jni_c`; `ANativeWindow_fromSurface`
and `__android_log_write` are declared `extern`.

Also `Java_io_github_yhzion_velo_TailnetNative_*` (`sys/tailnet.zig`) and
`Java_io_github_yhzion_velo_SshNative_{init,questions,answer,setIdentities,generateKey,decryptKey,installKey,remembered,forgetSocket,forget}`
(`sys/ssh.zig`; Kotlin `object SshNative` in `io.github.yhzion.velo`). The build's version
script exports these prefixes and `JNI_OnLoad` only.

## `sys/ssh.zig` (the app's SSH pool)

Package rexssh in `libtailscale.so` (`native/tailscale/ssh.go`), one pool per process,
loaded with `dlopen` on first use (the library `sys/tailnet.zig` loads). Every core gets
`dialer()`: `rexssh_dial(sd, locator, 0, …)` with the tailnet node's handle while it is
Running (`tailnet.runningNode()`), else -1, and no time limit of the core's own (a
question waits up to 120 s; the pool bounds every other step). `explain(gpa, rc, json,
failed_init, problem)` is the pure mapping of a failed dial, host-tested in the same file;
`failed_init` is what the last `SshNative.init` left (`initDone`: any result but 0 and
`EEXIST` is a failure):

| `rexssh_dial` | Error | Problem code |
|---|---|---|
| -1 with JSON | `SshDialFailed` | the JSON's code, sentence, fix |
| -1 without readable JSON | `SshDialFailed` | `unreachable`, "The SSH connection failed." |
| `EPERM` (no `SshNative.init` yet) | `SshNotReady` | `ssh_not_ready`, "SSH is still starting on this phone. Try again in a moment." |
| `EPERM` after an init that failed | `SshUnavailable` | `ssh_unavailable`, "SSH couldn't start on this phone. Restart Velo to try again." |
| other errno | `SshDialFailed` | `internal`, "The phone could not open the SSH connection (error N)." |
| library or symbol missing | `SshUnavailable` | `ssh_unavailable`, "SSH needs the app's native library." |

JNI (`SshNative`): `init(stateDir): Int`, `questions(): String?`, `answer(id: Long,
answer: String, ok: Boolean): Boolean`, `setIdentities(pems: Array<String>): Int` (in
offer order, phone key first; the JNI writes the JSON array `rexssh_set_identities` takes,
`identitiesJson`; a key that does not parse is left out and the others set, as OpenSSH skips
a bad identity, and the result is then 1000+N, N the place of the first left out),
`generateKey(comment): Array<String>?` (`[pem, publicLine]`),
`decryptKey(pem, passphrase: String?, pemOut: Array<String?>): Int`, `installKey(locator,
publicLine, timeoutMs): Int`, `remembered(locator): String?` (`{"socket","refused_until"}`),
`forgetSocket(locator): Int`, `forget(locator): Int`. An Int is 0, an errno value of
`ssh.go`, -1 without the pool, or 1000+N from `setIdentities`; a string argument that is null or holds U+0000 is
`EINVAL`, one there was no memory for `ENOMEM` (`sys/jni.zig` `CText`, host-tested against a
fake JNIEnv in `sys/test_jni.zig`). Copies of keys, passphrases and passwords are zeroed
after use: `CText` for the arguments, `newSecretString` for the private keys handed back
(`generateKey`, `decryptKey`), whose UTF-16 copy is never reallocated and is zeroed before
it is freed.

## `sys/tailnet.zig` (the app's Tailscale node)

One node per process, shared by every core: libtailscale (tsnet as a C library) at the
commit pinned in `native/tailscale/go.mod`, built by `native/tailscale/build.sh` (Gradle
tasks `tailscale_<abi>`, output `app/build/tailscale/jniLibs`, not committed) together with
`native/tailscale/android.go` (an interface getter, since Android denies apps
`net.Interfaces`, and `tailscale_setenv`). `libvelocore` loads it with `dlopen` on the
first start. `start(dir, hostname)` sets `TS_LOGS_DIR=dir`, `TS_NO_LOGS_NO_SUPPORT` and
`TS_USE_CACHED_NETMAP=false`, then calls `tailscale_start` and returns; there is no
`tailscale_up` thread. Without the cached network map the backend says Running only once
the control plane has sent the map. With a cached map the backend could report Running too
early and let a dial leave through the system network. The node's log and process stderr go to logcat
(`velocore`, `tailscale: …`). Every core gets `dialer()`: while the backend is Running (by a
status at most 5 s old, read again when older), `Conn` dials the locator's host and port (IPv6 in brackets) with
`tailscale_dial_timeout` (10 s) and uses the returned socket; otherwise the system
network. JNI: `Java_io_github_yhzion_velo_TailnetNative_{start,stop,logout,statusJson,get200}`
(`app/…/Tailnet.kt`).

### Android pane notifications

Open a pane's menu and choose **Pane notifications…**. **Needs your response**
and **Work completed** are independent switches, both initially off. Android 13
and later ask for notification permission on enable. The pane keeps its switches
if permission is denied. The menu links to Android settings when the app or its
notification channel is blocked.

The phone saves the remote row ID, session ID, window ID and block ID. Each host
filters its existing control stream for subscribed terminals, including inactive
splits and parked hosts. The filter reads individual program status records so
a blocked child cannot hide a different child's completion. On subscription and
after reconnect, the phone reads current records once as a silent baseline.
The core sends the baseline and any live reports received during the read together.
Matching records in the baseline retain their place in the live event sequence.
Before delivering live reports, the core verifies the saved tab against the session view. A missing or
outdated view triggers a location check, including on parked hosts. These checks
use the control connection without opening a terminal stream. The phone posts
only live transitions into enabled states. [Rex terminal events](https://www.superlogical.com/rex/docs/reference/events#terminal-events)

The process can post while the app is in the background or the screen is locked
if its control socket still receives events. Lock-screen visibility follows
Android settings. Tailnet's blocked-network callback pauses discovery while
Android withholds network access. The app has no cloud relay or background
service. Finishing the task releases the cores. Process death, suspension,
network restrictions and a disconnected socket can lose transitions. Reconnect
restores the subscriptions and processes future live reports.

A notification opens its saved remote and waits for the exact session, tab and
focused pane. When the view identifies it in a minimized layer, the app restores
that layer before focusing the target. If the pane was removed or the connection
cannot show it, the user can return to the workspace explicitly. A disconnect
keeps an open Compose draft visible and read-only. A focus change from another
client blocks input to the other pane. Tapping a notification during composition
defers navigation until the user sends or closes the original draft.
