//! The Rex client core: one control connection, the session/window/block model, one
//! terminal pane and data stream per terminal block, and the user intents.
//! See docs/native-core.md (section `rex/Core.zig + rex/DataStream.zig`).
//!
//! Threads: one control thread for the lifetime of the core (connect, hello, bootstrap,
//! receive loop, reconnect), one writer thread per control connection (so that calls
//! from the UI thread never block on the socket), one thread per data stream.
//! `lock` guards every field below it. Host callbacks run outside `lock`, serialized
//! by `host_lock`, and must not call back into the core.
const std = @import("std");
const pane = @import("pane");
const ws_mod = @import("../net/WebSocket.zig");
const endpoint_mod = @import("../net/Endpoint.zig");
const model = @import("model.zig");
const tab_meta = @import("TabMetadata.zig");
const protocol = @import("protocol.zig");
const ds = @import("DataStream.zig");
const size_rules = @import("SizeRules.zig");
const upload = @import("Upload.zig");
const download = @import("Download.zig");
const tunnel = @import("Tunnel.zig");
const log = std.log.scoped(.forward);

const json = std.json;
const Allocator = std.mem.Allocator;
const WebSocket = ws_mod.WebSocket;
pub const Net = ws_mod.Net;
pub const Dialer = ws_mod.Dialer;
pub const SshDialer = ws_mod.SshDialer;
pub const SshProblem = ws_mod.SshProblem;

pub const DataStream = ds.DataStream;
pub const backoffMs = ds.backoffMs;

/// Sent in the hello; the app's versionName.
const client_version = "2.0.0";
/// Terminal font size in sp until the `fontSize` setting arrives.
const default_font_sp: f32 = 13;
/// Largest `com.superlogical.terminal.write` payload sent in one request.
const max_write_bytes = 64 * 1024;
/// How long a data stream waits for its `data.connect` ticket.
const ticket_wait_ms = 10_000;
/// A dead link gives no read error for minutes, and the server's pings are answered
/// inside the WebSocket. After this much silence the core asks for the session list;
/// with no message at all by `link_dead_ms` it drops the connection and reconnects.
const link_probe_ms = 30_000;
const link_dead_ms = 50_000;

const pane_events = @import("PaneEvents.zig");

pub const Host = struct { // implemented by jni.zig (and by probe.zig / tests)
    ctx: *anyopaque,
    /// snapshot is always complete (the CLI prints it); Android consumes patch when present.
    onState: *const fn (ctx: *anyopaque, gen: u64, snapshot: []const u8, patch: ?[]const u8) void,
    onPaneReport: ?*const fn (ctx: *anyopaque, json: []const u8) void = null,
    onPaneWrite: ?*const fn (ctx: *anyopaque, tag: []const u8, result: Core.PaneWriteResult, message: []const u8) void = null,
    /// The answer to the `paneScreen` intent `tag`: the last lines of the pane's text
    /// (`screenTail`), "" when they could not be read.
    onPaneScreen: ?*const fn (ctx: *anyopaque, tag: []const u8, text: []const u8) void = null,
    onReconnectToken: *const fn (ctx: *anyopaque, token: []const u8) void,
    /// `fix`: a one-line command that fixes the failure, for the user to copy (from the
    /// SSH pool); "" when there is none.
    onError: *const fn (ctx: *anyopaque, code: []const u8, message: []const u8, fix: []const u8) void,
    glyphs: pane.GlyphSource,
};

// ------------------------------------------------------------------ pure helpers

/// Error note for a socket path longer than the system's limit (`sockaddr_un`).
const socket_path_too_long = "The socket path is too long for this phone: it must be at most 108 bytes.";

/// Why an ssh connection failed (`SshProblem` codes) when retrying cannot fix it: the
/// user said no or let a question time out, the host's key changed, the host refused the
/// password or the phone's key, the host turned the login away and said why (PAM's
/// account or auth step: after a right password or a key it took, the pool reports the
/// first two as `closed_before_login`, which is retried), there is no Rex there, the locator is wrong,
/// this build has no SSH, or the host offers only algorithms the pool refuses. Trying
/// again would ask again, pile up refused logins or probe for nothing, so the core stops
/// until Reconnect.
const permanent_failures = [_][]const u8{
    "declined",            "host_key_changed", "password_refused", "key_refused",
    "not_installed",       "malformed",        "no_user",          "ssh_unavailable",
    "no_common_algorithm", "login_refused",
};

pub fn permanentFailure(code: []const u8) bool {
    for (permanent_failures) |permanent| if (std.mem.eql(u8, code, permanent)) return true;
    return false;
}

/// Why the core does not connect to a locator.
pub const Refusal = struct {
    /// Code of the error note: `no_user` for an ssh locator without a user, else `bad_url`.
    code: []const u8,
    /// Why the locator does not parse.
    problem: endpoint_mod.ParseError,
    /// The locator, trimmed.
    locator: []const u8,

    /// Explain why the locator was refused.
    pub fn format(self: Refusal, w: *std.Io.Writer) std.Io.Writer.Error!void {
        try w.writeAll(switch (self.problem) {
            error.Empty => "Enter a server address.",
            error.Whitespace => "Use spaces only in Unix socket paths, not in server URLs.",
            error.Password => "Remove the password from the server address.",
            error.NoHost => "Enter the hostname or IP address of the server.",
            error.NoUser => return w.print("Missing SSH username in {s}. For example: ssh://user@{s}.", .{ self.locator, sshHost(self.locator) }),
            error.NotAbsolute => "Use a full Unix socket path starting with /, for example /tmp/rex.sock.",
            error.Malformed => "Check the format of the server address.",
            error.UnsupportedScheme => {
                const scheme = self.locator[0 .. std.mem.find(u8, self.locator, "://") orelse 0];
                try w.writeAll("Velo doesn't support ");
                for (scheme) |ch| try w.writeByte(std.ascii.toLower(ch));
                return w.writeAll(":// server addresses.");
            },
        });
    }
};

/// What the core does with a locator: connect to its endpoint, or refuse it.
pub const Target = union(enum) {
    /// Points into the locator.
    endpoint: endpoint_mod.Endpoint,
    refused: Refusal,
};

/// The core connects to every locator `Endpoint.parse` takes. A password is refused as
/// such even in an ssh locator; an ssh locator without a user is `no_user`, unless its
/// host or port is no good too.
pub fn locate(locator: []const u8) Target {
    const trimmed = std.mem.trim(u8, locator, " \t\r\n");
    const endpoint = endpoint_mod.parse(trimmed) catch |err| {
        // Only an ssh locator lacks a user.
        const problem = if (err == error.NoUser) withoutUser(trimmed) else err;
        const code = if (problem == error.NoUser) "no_user" else "bad_url";
        return .{ .refused = .{ .code = code, .problem = problem, .locator = trimmed } };
    };
    return .{ .endpoint = endpoint };
}

/// Why an ssh locator without a user is refused: `NoUser` when it parses with a user,
/// so that the sentence suggests a locator that works; else what is wrong with its host
/// or port.
fn withoutUser(locator: []const u8) endpoint_mod.ParseError {
    const scheme_end = std.mem.find(u8, locator, "://") orelse return error.Malformed;
    var rest = locator[scheme_end + 3 ..];
    if (std.mem.findScalar(u8, rest, '@')) |at| rest = rest[at + 1 ..];
    var buf: [320]u8 = undefined;
    const with_user = std.fmt.bufPrint(&buf, "ssh://user@{s}", .{rest}) catch return error.Malformed;
    _ = endpoint_mod.parse(with_user) catch |err| return err;
    return error.NoUser;
}

/// The host of an ssh locator as written, without user and port; an IPv6 address keeps
/// its brackets, so `ssh://user@` + host is a locator again.
fn sshHost(locator: []const u8) []const u8 {
    const scheme_end = std.mem.find(u8, locator, "://") orelse return "";
    var authority = locator[scheme_end + 3 ..];
    if (std.mem.findScalar(u8, authority, '@')) |at| authority = authority[at + 1 ..];
    if (authority.len > 0 and authority[0] == '[') {
        const close = std.mem.findScalar(u8, authority, ']') orelse return authority;
        return authority[0 .. close + 1];
    }
    return authority[0 .. std.mem.findScalar(u8, authority, ':') orelse authority.len];
}

/// Android `KeyEvent` action → Pane key action (0 down, 1 up, 2 repeat).
/// ACTION_MULTIPLE (2) is a repeat. Null for anything else.
pub fn keyAction(android_action: u8) ?u8 {
    return switch (android_action) {
        0 => 0,
        1 => 1,
        2 => 2,
        else => null,
    };
}

/// Android `MotionEvent` action (0 down, 1 up, 2 move, 3 cancel) → Pane touch action
/// (0 down, 1 move, 2 up). Null for anything else.
pub fn touchAction(android_action: u8) ?u8 {
    return switch (android_action) {
        0 => 0,
        2 => 1,
        1, 3 => 2,
        else => null,
    };
}

fn layoutHas(layout: *const model.Layout, block: []const u8) bool {
    return switch (layout.*) {
        .block => |id| std.mem.eql(u8, id, block),
        .split => |s| layoutHas(s.first, block) or layoutHas(s.second, block),
    };
}

fn firstLeafExcept(layout: *const model.Layout, block: []const u8) ?model.Id {
    return switch (layout.*) {
        .block => |id| if (std.mem.eql(u8, id, block)) null else id,
        .split => |s| firstLeafExcept(s.first, block) orelse firstLeafExcept(s.second, block),
    };
}

/// The tiled block to dock `block` beside: the focused tiled block of the window that
/// holds `block`, else that window's first tiled block. Never `block` itself.
/// Null when the window has no other tiled block.
pub fn dockAnchor(view: *const model.View, block: []const u8) ?model.Id {
    for (view.windows) |w| {
        const here = for (w.blocks) |b| {
            if (std.mem.eql(u8, b.id, block)) break true;
        } else false;
        if (!here) continue;
        const layout = w.layout orelse return null;
        if (w.focused) |f| {
            if (!std.mem.eql(u8, f, block) and layoutHas(layout, f)) return f;
        }
        return firstLeafExcept(layout, block);
    }
    return null;
}

/// Whether `block` is drawn in a tab of `view`, tiled or floating.
fn placed(view: *const model.View, block: []const u8) bool {
    for (view.windows) |w| {
        for (w.blocks) |b| if (std.mem.eql(u8, b.id, block)) return true;
    }
    return false;
}

/// Whether `block` is a tiled block of a tab of `view`: what `session.move_block` takes as
/// its anchor.
fn tiled(view: *const model.View, block: []const u8) bool {
    for (view.windows) |w| {
        if (w.layout) |layout| if (layoutHas(layout, block)) return true;
    }
    return false;
}

/// Whether `block` can move out to a new tab: its tab in `view` holds another block
/// (tiled, floating or minimized) and stays, or the block floats in a tab without tiled
/// panes and becomes tiled in a tab of its own. The only pane of a tab would only make the
/// same tab again.
fn canMoveToNewTab(view: *const model.View, block: []const u8) bool {
    for (view.windows) |w| {
        const here = for (w.blocks) |b| {
            if (std.mem.eql(u8, b.id, block)) break true;
        } else false;
        if (!here) continue;
        if (w.blocks.len > 1 or w.hidden.len > 0) return true;
        if (w.layout != null) return false;
        for (w.floating) |f| if (std.mem.eql(u8, f.block, block)) return true;
        return false;
    }
    return false;
}

/// Length of the longest prefix of `bytes` of at most `max` bytes that does not end
/// inside a UTF-8 sequence.
pub fn writeChunkLen(bytes: []const u8, max: usize) usize {
    if (bytes.len <= max) return bytes.len;
    var n = max;
    while (n > 0 and (bytes[n] & 0xC0) == 0x80) n -= 1;
    return if (n == 0) max else n;
}

/// What a new connection's `session.bootstrap` attaches: the selected session, none after
/// a `detach` (so a reconnect does not undo it), else whatever the server picks.
pub fn bootstrapAttach(selected: ?[]const u8, auto_pick: bool) protocol.Attach {
    if (selected) |s| return .{ .session = s };
    return if (auto_pick) .pick else .none;
}

/// The session to show when `gone` goes away: the one after it in the list, else the one
/// before it, else none. The result points into `sessions`.
pub fn neighbour(sessions: []const model.Session, gone: []const u8) ?model.Id {
    for (sessions, 0..) |s, i| {
        if (!std.mem.eql(u8, s.id, gone)) continue;
        if (i + 1 < sessions.len) return sessions[i + 1].id;
        return if (i > 0) sessions[i - 1].id else null;
    }
    return null;
}

/// The foreground program of a `com.superlogical.terminal.process` result (else the
/// shell the terminal started) and its directory, with the home written as `~`. `cwd`
/// is part of the result or lives in `buf`.
fn processInfo(buf: []u8, result: json.Value) struct { program: []const u8, agent: ?[]const u8, cwd: []const u8 } {
    const proc = for ([_][]const u8{ "foreground", "child" }) |field| {
        if (model.get(result, field)) |p| if (p == .object) break p;
    } else json.Value.null;
    return .{
        .program = model.str(model.get(proc, "name")) orelse "",
        .agent = tab_meta.processAgent(proc),
        .cwd = model.tildePath(buf, model.str(model.get(proc, "cwd")) orelse "", model.str(model.get(result, "home")) orelse ""),
    };
}

/// A line editor such as Bash readline disables kernel echo and canonical input. A silent
/// canonical read (e.g. `read -s`) or a known password program must not show the IME preview.
/// Missing, malformed or failed replies never grant permission, including unsupported hosts.
const PreeditAsk = struct {
    mode_id: u64,
    process_id: u64,
    mode_allows: ?bool = null,
    program_allows: ?bool = null,
    /// Another edit arrived in flight. Query again rather than applying stale permission.
    again: bool = false,

    fn allowsMode(result: json.Value) bool {
        const echo = model.boolean(model.get(result, "echo")) orelse return false;
        const canonical = model.boolean(model.get(result, "canonical")) orelse return false;
        return echo or !canonical;
    }

    fn allowsProgram(result: json.Value) bool {
        // The shell that owns the PTY (child) is not evidence about the foreground program.
        const foreground = model.get(result, "foreground") orelse return false;
        const name = model.str(model.get(foreground, "name")) orelse return false;
        if (name.len == 0) return false;
        for ([_][]const u8{ "sudo", "su", "doas", "passwd" }) |p| if (std.mem.eql(u8, name, p)) return false;
        return true;
    }
};

/// What a `com.superlogical.terminal.program_status` result says of its terminal: the
/// state and when its report arrived, in epoch milliseconds.
pub const Report = struct {
    activity: model.Activity = .idle,
    since: ?i64 = null,
};

/// The most urgent state in a `com.superlogical.terminal.program_status` result, with the
/// `updated_at` of the record that carries it: the earliest when several records share
/// that state. Records with missing or unreadable timestamps supply no time.
pub fn worstActivity(result: json.Value) Report {
    var worst: Report = .{};
    for (model.items(model.get(result, "records"))) |record| {
        const a = model.Activity.parse(model.str(model.get(record, "state")));
        if (a == .idle or @backingInt(a) < @backingInt(worst.activity)) continue;
        const at = model.parseRfc3339Ms(model.str(model.get(record, "updated_at")) orelse "");
        if (a != worst.activity) {
            worst = .{ .activity = a, .since = at };
        } else if (at) |ms| {
            if (worst.since == null or ms < worst.since.?) worst.since = ms;
        }
    }
    return worst;
}

// ------------------------------------------------------------------------- Core

/// One terminal block of the selected session.
const Entry = struct {
    core: *Core,
    /// Block id; also the key in `Core.entries`.
    id: []u8,
    /// Names the entry in pending requests (an id would dangle once the block is gone).
    serial: u64,
    pane: *pane.Pane,
    /// Null when the stream's thread has ended.
    stream: ?*ds.DataStream = null,
    /// The block left the view: the entry is out of `Core.entries` and is destroyed
    /// when its stream thread ends.
    removed: bool = false,
    seen: bool = true,
    /// Null until the terminal reports its title, which may be empty.
    title: ?[]u8 = null,
    /// Foreground program's file name and working directory ("" when unknown).
    program: []u8 = &.{},
    agent: ?[]const u8 = null, // static process identity
    tab_report: tab_meta.Details = .{},
    title_revision: u64 = 0,
    has_control_title: bool = false,
    // Selected panes may have overlapping refreshes. A late response must
    // not restore a previous process or restart an already finished task.
    last_process_response: u64 = 0,
    last_status_response: u64 = 0,
    last_status_request: u64 = 0,
    last_title_request: u64 = 0,
    last_process_request: u64 = 0,
    /// At most one live pair of prompt checks per pane; never contains IME text.
    preedit_ask: ?PreeditAsk = null,
    cwd: []u8 = &.{},
    activity: model.Activity = .idle,
    /// Report arrival time for `activity`, in epoch milliseconds by the server's clock.
    since: ?i64 = null,

    ticket_state: enum { none, waiting, ready, failed } = .none,
    ticket: [ds.max_ticket_len]u8 = undefined,
    ticket_len: usize = 0,

    /// Typed bytes not sent yet. Terminal writes are not ordered by the server, so
    /// only one write per block is in flight and later input waits for its response.
    input: std.ArrayList(u8) = .empty,
    write_inflight: bool = false,

    /// Size the app gave the pane's surface last, in px (0 before the first surface).
    surface_w: u32 = 0,
    surface_h: u32 = 0,
    /// When this client takes the block and which size it asked for.
    size: size_rules.State = .{},
};

/// What the core knows of a session it is not attached to: its view, read again when a
/// `session_view_changed` of that session names a newer revision. No panes, no streams.
const Follow = struct {
    /// Session id; also the key in `Core.follows`.
    session: []u8,
    arena: ?std.heap.ArenaAllocator = null,
    view: ?model.View = null,
    /// Highest `session_view_changed` revision seen for the session.
    want_revision: i64 = -1,
    inflight: bool = false,
    again: bool = false,
    /// The view was asked for on this control connection.
    asked: bool = false,
};

/// The lead block of a tab of a followed session, when it is a terminal: what the state
/// shows for that tab. Kept current by the block's events and the methods they point to.
/// A terminal block of a minimized layer of the selected session is kept so too: it has
/// no pane, and the state names it from this (#233). So is each other terminal pane of a
/// followed session's tab, but only by what it runs (`Core.beside`, #226).
const Lead = struct {
    /// Block id; also the key in `Core.leads`, `Core.stashed` or `Core.beside`.
    id: []u8,
    /// Owned.
    session: []u8,
    /// Names the lead in pending requests.
    serial: u64,
    seen: bool = true,
    /// Its terminal was asked about on this control connection.
    asked: bool = false,
    title: ?[]u8 = null,
    program: []u8 = &.{},
    agent: ?[]const u8 = null, // static process identity
    tab_report: tab_meta.Details = .{},
    title_revision: u64 = 0,
    cwd: []u8 = &.{},
    activity: model.Activity = .idle,
    since: ?i64 = null,
    /// One request of each kind at a time; an event meanwhile asks once more after it.
    process: Refresh = .{},
    status: Refresh = .{},
    title_refresh: Refresh = .{},

    const Refresh = struct { inflight: bool = false, again: bool = false };
};

const TitleRequest = struct { serial: u64, revision: u64 };

/// A move of `block` out to a new tab: the tab is made with a temporary anchor, the block
/// moves beside it, then the anchor closes.
const DockTab = struct {
    session: []u8,
    block: []u8,
    select_gen: u64,
    /// The pane move this is (`Core.move_serial`); 0 for an Undo.
    serial: u64,
    anchor: ?[]u8 = null,
    /// The created tab, from the `session.new_window` answer.
    window: ?[]u8 = null,
    /// An Undo gives the tab the label and place of the tab the move closed.
    label: ?[]u8 = null,
    before: ?[]u8 = null,
};

/// A pane move or an Undo that is one request: on success `block` is focused.
const PaneMove = struct { session: []u8, block: []u8, select_gen: u64, serial: u64 };

const Pending = union(enum) {
    bootstrap,
    pane_baseline: u64,
    pane_location: u64,
    list,
    /// `select_gen` when the request was sent.
    view: u64,
    /// `session.view` of a followed session (its id).
    follow_view: []u8,
    /// `session.move_window` of a tab reorder, with the session it moved a tab in. Owned.
    window_moved: []u8,
    /// `session.float_block` of a pane being stashed, with the session it floated in. On the
    /// answer the new layer is minimized. Owned.
    stash_floated: []u8,
    attach: struct { gen: u64, session: []u8 },
    /// Whether to attach to the created session when the answer arrives.
    create_session: bool,
    /// Entry serial.
    ticket: u64,
    /// Entry serial.
    write: u64,
    composer,
    /// `download_begin` of the download waiting in `Core.download`.
    download,
    /// `server.whoami`: the account the server runs as.
    whoami,
    /// `hosts.list`: the server's host list.
    hosts_list,
    /// Entry serial: `com.superlogical.terminal.input_mode`, for the pane's composition.
    input_mode: u64,
    /// Entry serial: `com.superlogical.terminal.process`, for the pane's composition.
    preedit_process: u64,
    /// Entry serial: `com.superlogical.terminal.process`.
    process: u64,
    /// Entry serial: `com.superlogical.terminal.program_status`.
    program_status: u64,
    /// Entry serial: `session.own_block`.
    take: u64,
    /// Entry serial: `com.superlogical.terminal.resize`.
    resize: u64,
    /// Lead serial: `com.superlogical.terminal.process`.
    lead_process: u64,
    /// Lead serial: `com.superlogical.terminal.program_status`.
    lead_status: u64,
    /// Lead serial: `com.superlogical.terminal.title`.
    lead_title: TitleRequest,
    entry_title: TitleRequest,
    /// A close or destroy: a target that is already gone is no error.
    close,
    /// A user intent: only its error matters.
    command,
    dock_tab_created,
    dock_tab_moved,
    /// The `block.close` of a dock tab's temporary anchor, as `close`. Its revision counts
    /// for the pane move with this serial (0: none).
    dock_tab_closed: u64,
    /// `session.move_block` or `session.swap_blocks` of a pane move or an Undo. Owned.
    pane_moved: PaneMove,
    /// The `session.focus_block` after the pane move with this serial (0: an Undo).
    pane_focused: u64,
    /// A request whose answer a thread other than the control thread waits for (`Core.ask`).
    ask: *Ask,
    /// A `paneWrite` reading the pane's program status before it writes.
    pane_write_check: *PaneWriteOp,
    /// A `paneWrite` whose record held, reading the pane's processes: the record's owner
    /// (`PaneWriteOp.owner`) must still be in front (`ownerInFront`).
    pane_write_owner: *PaneWriteOp,
    /// The `terminal.write` of a `paneWrite` that passed its check: its tag (owned) and
    /// the byte count the server must confirm.
    pane_write: struct { tag: []u8, bytes: usize },
    /// The `terminal.format` of a `paneScreen`: the text, or its start and where the rest is.
    pane_screen: PaneScreenOp,
    /// The `terminal.blob_read` of a `paneScreen` whose text did not fit in one answer.
    pane_screen_tail: PaneScreenOp,
    /// Nothing to do with the answer.
    silent,
};

/// A `paneWrite` intent between its status check and its write. Owned by its pending entry.
const PaneWriteOp = struct {
    arena: std.heap.ArenaAllocator,
    write: protocol.PaneWrite,
    /// The pid the server named as the owner of the record the write answers; 0 before the
    /// status check answered.
    owner: i64 = 0,

    fn create(gpa: Allocator, w: protocol.PaneWrite) Allocator.Error!*PaneWriteOp {
        const op = try gpa.create(PaneWriteOp);
        op.owner = 0;
        op.arena = .init(gpa);
        errdefer {
            op.arena.deinit();
            gpa.destroy(op);
        }
        const a = op.arena.allocator();
        op.write = .{
            .tag = try a.dupe(u8, w.tag),
            .session = try a.dupe(u8, w.session),
            .block = try a.dupe(u8, w.block),
            .text = try a.dupe(u8, w.text),
            .expect = .{
                .id = try a.dupe(u8, w.expect.id),
                .kind = if (w.expect.kind) |k| try a.dupe(u8, k) else null,
                .updated_at = if (w.expect.updated_at) |t| try a.dupe(u8, t) else null,
            },
        };
        return op;
    }

    fn destroy(op: *PaneWriteOp, gpa: Allocator) void {
        op.arena.deinit();
        gpa.destroy(op);
    }
};

fn optionalEql(a: ?[]const u8, b: ?[]const u8) bool {
    if (a == null or b == null) return a == null and b == null;
    return std.mem.eql(u8, a.?, b.?);
}

/// Whether a `com.superlogical.terminal.program_status` result still holds the record a
/// `paneWrite` answers: the same id, blocked, of the same kind and, unless `expect` leaves
/// it out, reported at the same time (compared as instants; text that is no time as text).
pub fn statusHolds(expect: protocol.PaneExpect, result: json.Value) bool {
    for (model.items(model.get(result, "records"))) |record| {
        if (!std.mem.eql(u8, model.str(model.get(record, "id")) orelse "", expect.id)) continue;
        if (!std.mem.eql(u8, model.str(model.get(record, "state")) orelse "", "blocked")) return false;
        if (!optionalEql(model.str(model.get(record, "kind")), expect.kind)) return false;
        const wanted = expect.updated_at orelse return true;
        const at = model.str(model.get(record, "updated_at")) orelse return false;
        const a = model.parseRfc3339Ms(at);
        const b = model.parseRfc3339Ms(wanted);
        return if (a != null and b != null) a.? == b.? else std.mem.eql(u8, at, wanted);
    }
    return false;
}

/// The pid of the process the server names as the owner of record `id` in a
/// `com.superlogical.terminal.program_status` result: the pane's foreground process when
/// the record was reported. The server adds it itself; a report cannot set it. Null when
/// the record has none, as from a server that does not name owners.
pub fn ownerPid(id: []const u8, result: json.Value) ?i64 {
    for (model.items(model.get(result, "records"))) |record| {
        if (!std.mem.eql(u8, model.str(model.get(record, "id")) orelse "", id)) continue;
        const pid = model.get(model.get(record, "owner") orelse return null, "pid") orelse return null;
        return if (pid == .integer) pid.integer else null;
    }
    return null;
}

/// Whether a `com.superlogical.terminal.process` result still has the process `owner` in
/// front. A result that names no foreground process has nothing to compare with and counts
/// as in front, which is what the write did before the owner was checked (#32).
pub fn ownerInFront(owner: i64, result: json.Value) bool {
    const pid = model.get(model.get(result, "foreground") orelse return true, "pid") orelse return true;
    return pid != .integer or pid.integer == owner;
}

/// A `paneScreen` intent in flight. Owned by its pending entry.
const PaneScreenOp = struct {
    tag: []u8,
    session: []u8,
    block: []u8,
    /// The read starts after the start of the text, so its first line may be part of one.
    cut: bool = false,

    fn create(gpa: Allocator, tag: []const u8, session: []const u8, block: []const u8) Allocator.Error!PaneScreenOp {
        const t = try gpa.dupe(u8, tag);
        errdefer gpa.free(t);
        const s = try gpa.dupe(u8, session);
        errdefer gpa.free(s);
        return .{ .tag = t, .session = s, .block = try gpa.dupe(u8, block) };
    }

    fn free(op: PaneScreenOp, gpa: Allocator) void {
        gpa.free(op.tag);
        gpa.free(op.session);
        gpa.free(op.block);
    }
};

/// The most lines of a pane's text a notification shows (#32): enough for a prompt's
/// question and its choices, few enough to read on a watch.
pub const screen_lines = 8;
/// The most bytes of one such line: 120 columns of Latin text, 40 of Hangul. A longer line
/// is cut and ends with "…".
pub const screen_line_bytes = 120;
/// How much of the end of a pane's text is read for those lines.
pub const screen_tail_bytes = 8192;

/// The length of the box-drawing character (U+2500 to U+257F) `s` starts with, or 0.
fn boxLen(s: []const u8) usize {
    return if (s.len >= 3 and s[0] == 0xE2 and (s[1] == 0x94 or s[1] == 0x95) and s[2] & 0xC0 == 0x80) 3 else 0;
}

/// `line` without the blanks and the box-drawing characters at its ends: the text a program
/// drew inside a frame, and nothing for a rule of a frame.
fn screenLine(line: []const u8) []const u8 {
    var s = line;
    while (s.len > 0) {
        const n: usize = if (s[0] == ' ' or s[0] == '\t' or s[0] == '\r') 1 else boxLen(s);
        if (n == 0) break;
        s = s[n..];
    }
    while (s.len > 0) {
        const last = s[s.len - 1];
        const n: usize = if (last == ' ' or last == '\t' or last == '\r') 1 else if (s.len >= 3) boxLen(s[s.len - 3 ..]) else 0;
        if (n == 0) break;
        s = s[0 .. s.len - n];
    }
    return s;
}

/// The last `screen_lines` lines of `text` that hold text, joined by "\n" in their order:
/// what a pane shows at its end, for the notification of the prompt that is up there (#32).
/// Blank lines and the rules of frames are left out, and a line longer than
/// `screen_line_bytes` is cut at a character. `whole` is false for text read from the
/// middle of the pane's text: the first line may then be part of one and is left out.
/// Caller frees.
pub fn screenTail(gpa: Allocator, text: []const u8, whole: bool) Allocator.Error![]u8 {
    const body = if (whole) text else text[if (std.mem.findScalar(u8, text, '\n')) |i| i + 1 else text.len..];
    var lines: [screen_lines][]const u8 = undefined;
    var n: usize = 0;
    var it = std.mem.splitBackwardsScalar(u8, body, '\n');
    while (it.next()) |raw| {
        const line = screenLine(raw);
        if (line.len == 0) continue;
        n += 1;
        lines[screen_lines - n] = line;
        if (n == screen_lines) break;
    }
    var out: std.ArrayList(u8) = .empty;
    errdefer out.deinit(gpa);
    for (lines[screen_lines - n ..], 0..) |line, i| {
        if (i > 0) try out.append(gpa, '\n');
        if (line.len <= screen_line_bytes) {
            try out.appendSlice(gpa, line);
            continue;
        }
        var end: usize = screen_line_bytes;
        while (end > 0 and line[end] & 0xC0 == 0x80) end -= 1;
        try out.appendSlice(gpa, line[0..end]);
        try out.appendSlice(gpa, "…");
    }
    return out.toOwnedSlice(gpa);
}

// Lives on the sending thread's stack. Access only with Core.lock; no pointer to
// this or to an Entry is retained in the pending map or by the writer thread.
const Composer = struct {
    core: *Core,
    generation: u64,
    session: []const u8,
    block: []const u8,
    unsent: ?[]u8 = null,
    expected_bytes: ?usize = null, // null: upload_begin, otherwise terminal.write
    paste: bool = false,
    ticket: [ds.max_ticket_len]u8 = undefined,
    ticket_len: usize = 0,
    reply: enum { waiting, confirmed, refused, invalid, paste_changed } = .waiting,

    fn active(ctx: *anyopaque) bool {
        const op: *Composer = @ptrCast(@alignCast(ctx));
        const self = op.core;
        self.lock.lock();
        defer self.lock.unlock();
        return op.generation == self.composerGeneration(op.session, op.block);
    }
};

/// A `download_begin` waiting for its answer. Lives on the asking thread's stack and is reached
/// only through `Core.download`, under Core.lock.
const DownloadOp = struct {
    id: u64,
    gpa: Allocator,
    answer: ?[]u8 = null,
};

/// What the app reads of a `download_begin` answer.
const DownloadAnswer = struct {
    status: []const u8,
    ticket: ?[]const u8 = null,
    path: ?[]const u8 = null,
    name: ?[]const u8 = null,
    size: ?i64 = null,
    version: ?[]const u8 = null,
    code: ?[]const u8 = null,
    message: ?[]const u8 = null,
};

fn downloadAnswer(gpa: Allocator, payload: ?json.Value, err: ?protocol.ResponseError) Allocator.Error![]u8 {
    const options: json.Stringify.Options = .{ .emit_null_optional_fields = false };
    if (err) |e| return json.Stringify.valueAlloc(gpa, DownloadAnswer{ .status = "error", .code = e.code, .message = e.message }, options);
    const result = payload orelse json.Value.null;
    const current = if (model.get(result, "not_modified")) |v| v == .bool and v.bool else false;
    const ticket = model.str(model.get(result, "ticket"));
    if (!current and ticket == null)
        return json.Stringify.valueAlloc(gpa, DownloadAnswer{ .status = "error", .code = "invalid", .message = "the server sent no ticket" }, options);
    return json.Stringify.valueAlloc(gpa, DownloadAnswer{
        .status = if (current) "not_modified" else "ok",
        .ticket = if (current) null else ticket,
        .path = model.str(model.get(result, "path")),
        .name = model.str(model.get(result, "name")),
        .size = model.int(model.get(result, "size")),
        .version = model.str(model.get(result, "version")),
    }, options);
}

/// The open file a download writes to, and the cancel generation it was started in.
const FetchTarget = struct {
    core: *Core,
    generation: u64,
    fd: c_int,

    fn write(ctx: *anyopaque, bytes: []const u8) anyerror!void {
        const self: *FetchTarget = @ptrCast(@alignCast(ctx));
        var rest = bytes;
        while (rest.len != 0) {
            const n = std.c.write(self.fd, rest.ptr, rest.len);
            if (n < 0) {
                if (std.c._errno().* == @intFromEnum(std.c.E.INTR)) continue;
                return error.WriteFailed;
            }
            rest = rest[@intCast(n)..];
        }
    }

    fn active(ctx: *anyopaque) bool {
        const self: *FetchTarget = @ptrCast(@alignCast(ctx));
        return self.core.download_cancel.load(.acquire) == self.generation;
    }
};

/// A request that a thread other than the control thread sends and waits for (`Core.ask`).
const Ask = struct {
    id: u64 = 0,
    done: bool = false,
    /// The answer as JSON text; null when the server refused the request.
    payload: ?[]u8 = null,
    /// Why the server refused it.
    code: ?[]u8 = null,
    message: ?[]u8 = null,

    fn deinit(self: *Ask, gpa: Allocator) void {
        if (self.payload) |p| gpa.free(p);
        if (self.code) |c| gpa.free(c);
        if (self.message) |m| gpa.free(m);
        self.* = .{};
    }

    /// The string `name` of the answer, parsed with `arena`.
    fn string(self: *const Ask, arena: Allocator, name: []const u8) ?[]const u8 {
        const text = self.payload orelse return null;
        const value = json.parseFromSliceLeaky(json.Value, arena, text, .{}) catch return null;
        return model.str(model.get(value, name));
    }
};

const too_old_message = "This remote's Rex server is too old to forward ports.";

/// The connections one forward serves at a time. Any app on the phone can connect to its
/// port, and each connection costs a task here and a stream on the remote. A browser that
/// loads a page keeps a handful per origin, so a development page never comes near this.
pub const forward_connections_max = 64;

/// A link to the remote's loopback opened through a tunnel block (#226): the phone listens
/// on `127.0.0.1:local_port`, and each connection it accepts is relayed over a stream of
/// `block`, which connects it to `host`:`port` on the remote.
const Forward = struct {
    session: []u8,
    block: []u8,
    host: []u8,
    port: u16,
    local_port: u16,
    listener: std.Io.net.Server,
    /// Its accept loop and the connections it relays.
    tasks: std.Io.Group = .init,
    // Under `Core.lock`:
    /// Connections it relays now.
    connections: u32 = 0,
    /// This link is attached to `session` and owns `block`: a new block is the caller's.
    claimed: bool = true,
    closing: bool = false,
    /// The remote has no such block or session any more: the next tap makes the forward again.
    gone: bool = false,

    fn destroy(self: *Forward, gpa: Allocator) void {
        gpa.free(self.session);
        gpa.free(self.block);
        gpa.free(self.host);
        gpa.destroy(self);
    }
};

/// The phone's `127.0.0.1:port`, or a free port when another program has it or Android
/// keeps it from apps (below 1024).
pub fn listenLoopback(io: std.Io, port: u16) !std.Io.net.Server {
    _ = io;
    return listenOn(port) catch |err| switch (err) {
        error.AddressInUse, error.AccessDenied => listenOn(0),
        else => err,
    };
}

/// A listener on `127.0.0.1:port`. The connections of an earlier listener that wait out
/// TIME_WAIT do not keep the port from it (`SO_REUSEADDR`), so a forward stopped and made
/// again keeps its port, and with it the page's origin. A listening socket still keeps the
/// port: `SO_REUSEPORT`, which `std.Io.net` sets with `reuse_address`, would share it.
fn listenOn(port: u16) !std.Io.net.Server {
    const linux = std.os.linux;
    const rc = linux.socket(linux.AF.INET, linux.SOCK.STREAM | linux.SOCK.CLOEXEC, 0);
    if (linux.errno(rc) != .SUCCESS) return error.SystemResources;
    const fd: linux.fd_t = @intCast(rc);
    errdefer _ = linux.close(fd);
    const on: c_int = 1;
    if (linux.errno(linux.setsockopt(fd, linux.SOL.SOCKET, linux.SO.REUSEADDR, std.mem.asBytes(&on), @sizeOf(c_int))) != .SUCCESS)
        return error.SystemResources;
    var address: linux.sockaddr.in = .{ .port = std.mem.nativeToBig(u16, port), .addr = std.mem.nativeToBig(u32, 0x7f00_0001) };
    switch (linux.errno(linux.bind(fd, @ptrCast(&address), @sizeOf(linux.sockaddr.in)))) {
        .SUCCESS => {},
        .ADDRINUSE => return error.AddressInUse,
        .ACCES, .PERM => return error.AccessDenied,
        else => return error.Unexpected,
    }
    if (linux.errno(linux.listen(fd, 128)) != .SUCCESS) return error.Unexpected;
    var len: linux.socklen_t = @sizeOf(linux.sockaddr.in);
    if (linux.errno(linux.getsockname(fd, @ptrCast(&address), &len)) != .SUCCESS) return error.Unexpected;
    return .{
        .socket = .{ .handle = fd, .address = .{ .ip4 = .loopback(std.mem.bigToNative(u16, address.port)) } },
        .options = {},
    };
}

fn forwardAnswer(gpa: Allocator, port: u16) Allocator.Error![]u8 {
    return std.fmt.allocPrint(gpa, "{{\"status\":\"ok\",\"port\":{d}}}", .{port});
}

fn forwardRefusal(gpa: Allocator, code: []const u8, message: []const u8) Allocator.Error![]u8 {
    return json.Stringify.valueAlloc(gpa, .{ .status = "error", .code = code, .message = message }, .{});
}

const Note = union(enum) {
    pane_report: []u8,
    pane_write: struct { text: []u8, tag_len: usize, result: Core.PaneWriteResult },
    /// The tag, then the lines.
    pane_screen: struct { text: []u8, tag_len: usize },
    token: []u8,
    err: struct { code: []u8, message: []u8, fix: []u8 },
};

extern "android" fn ANativeWindow_release(window: *anyopaque) void;

pub const Core = struct {
    gpa: Allocator,
    host: Host,
    /// How connections reach the server. Borrowed: the caller keeps the CA directories
    /// and the dialer's context alive until `destroy`.
    net: Net,
    px_per_sp: f32,
    threaded: std.Io.Threaded,
    io: std.Io,
    control: ?std.Thread = null,

    /// Serializes callbacks and owns the baseline actually handed to the host. Captures
    /// can reach this lock out of order; never diff against a capture that was skipped.
    host_lock: pane.Lock = .{},
    delivered_seq: u64 = 0,
    state_gen: u64 = 0,
    last_state: ?model.StateSnapshot = null,
    patch_buf: std.ArrayList(u8) = .empty,

    lock: pane.Lock = .{},
    quit: bool = false,
    /// Bumped by connect/disconnect/destroy: the control thread drops what it is doing.
    epoch: u64 = 0,
    url: []u8 = &.{},
    /// The server's locator (`net/Endpoint.zig`) as given, trimmed: what the control
    /// connection and the data streams connect to. Empty when there is nothing to connect to.
    server: []u8 = &.{},
    token: ?[]u8 = null,
    status: model.Status = .idle,
    /// A bootstrap succeeded for this server: later failures are `reconnecting`.
    ever_connected: bool = false,
    /// The epoch whose connection stopped on a failure retrying cannot fix
    /// (`permanentFailure`): nothing connects, data streams included, until connect,
    /// disconnect or destroy changes the epoch.
    stopped: ?u64 = null,

    // Control connection.
    ws: ?*WebSocket = null,
    /// The writer thread is running and sends `outbox`.
    link_up: bool = false,
    /// Bootstrap is done: requests can be sent.
    ready: bool = false,
    /// The control thread must drop the connection (bootstrap failed).
    drop_link: bool = false,
    drop_reason: []const u8 = "",
    outbox: std.ArrayList([]u8) = .empty,
    pending: std.AutoHashMapUnmanaged(u64, Pending) = .empty,
    /// The `downloadBegin` waiting for its answer, if any.
    download: ?*DownloadOp = null,
    /// Bumped by `downloadCancel`: a running `downloadFetch` ends.
    download_cancel: std.atomic.Value(u64) = .init(0),
    /// The port forwards (#226), in the order they were made.
    forwards: std.ArrayList(*Forward) = .empty,
    dock_tab: ?DockTab = null,
    /// The session revision the last pane move ended with: the highest `revision` among
    /// the answers to its requests. An Undo is exact only for that layout, so it is refused
    /// once the session is newer (`undoMove`). Null until a move completes on this
    /// connection and selection, and again from the next move or Undo: an Undo has no redo.
    undo_revision: ?i64 = null,
    /// Numbers the pane moves. Answers of an older move, or of an Undo (serial 0), leave
    /// `undo_revision` alone.
    move_serial: u64 = 0,
    next_id: u64 = 1,
    composer_gen: u64 = 1,
    composer: ?*Composer = null,
    /// An event arrived before the bootstrap response: read the state again after it.
    refresh_pending: bool = false,
    /// When the last control message arrived.
    last_rx_ms: i64 = 0,
    /// The silence probe for the current quiet period went out.
    probed: bool = false,

    /// This client's id from the welcome ("" before the first one): the `owner` of the
    /// blocks it owns.
    client_id: []u8 = &.{},

    // Model.
    sessions: std.ArrayList(model.Session) = .empty,
    selected: ?[]u8 = null,
    /// Bumped whenever `selected` changes; answers for an older selection are stale.
    select_gen: u64 = 0,
    /// With no session selected, attach one (the server's pick, else the first listed).
    /// False after a `detach` intent until selection or promotion resumes it.
    auto_pick: bool = true,
    view_arena: ?std.heap.ArenaAllocator = null,
    view: ?model.View = null,
    /// Highest `session_view_changed` revision seen for the selected session.
    want_revision: i64 = -1,
    view_inflight: bool = false,
    view_again: bool = false,
    /// The other sessions of the server, by session id. Empty for a parked core.
    follows: std.StringHashMapUnmanaged(*Follow) = .empty,
    /// Lead blocks of the followed sessions' tabs, by block id.
    leads: std.StringHashMapUnmanaged(*Lead) = .empty,
    /// Terminal blocks of the selected session's minimized layers, by block id: no pane is
    /// drawn for them, so they are followed as lead blocks are (#233).
    stashed: std.StringHashMapUnmanaged(*Lead) = .empty,
    /// The terminal blocks of the followed sessions' tabs other than their lead blocks, by
    /// block id. Only their process is asked, for the program a tab's tile draws behind
    /// its face (#226): one request each at first and one per `process_changed`.
    beside: std.StringHashMapUnmanaged(*Lead) = .empty,
    /// The fence (intent `fence`): with a prefix, a session exists for this core only
    /// when its label starts with it or this core created it. "" is no fence.
    fence_prefix: []u8 = &.{},
    /// Sessions this core created (`session.create` answers) since it started. Owned keys.
    made: std.StringHashMapUnmanaged(void) = .empty,
    /// A fenced core's panes outlived the control connection. Their stream restarts
    /// and new input wait for the selected session's attach answer.
    resume_pending: bool = false,

    // Panes.
    entries: std.StringHashMapUnmanaged(*Entry) = .empty,
    /// Block id → what the UI shows for it. Keys and text are owned by the entries.
    blocks: std.StringHashMapUnmanaged(model.BlockInfo) = .empty,
    /// Operating system account the server runs as; "" until `server.whoami` answers.
    account: []u8 = &.{},
    /// The server's host list from the last `hosts.list` answer, and its hash ("" before
    /// the first answer). Both live in `hosts_arena`.
    hosts_arena: ?std.heap.ArenaAllocator = null,
    hosts: []const model.HostEntry = &.{},
    hosts_hash: []const u8 = "",
    next_serial: u64 = 1,
    /// Data stream threads that have not ended yet (including removed entries).
    live_streams: usize = 0,

    // Settings.
    font_sp: f32 = default_font_sp,
    /// Cell height in em of the font size: the line of the font the host draws, at the size
    /// whose 'M' fills a cell 0.6 em wide (setting `fontLineHeight`).
    line_height: f32 = 1.2,
    /// Margin around each terminal grid in px (setting `panePaddingPx`).
    pad_px: f32 = 0,

    pane_watches: ?pane_events.Watches = null,

    // Host notifications collected under `lock`, delivered by `release`.
    dirty: bool = false,
    state_seq: u64 = 0,
    /// The first capture that must go out in full. Later captures inherit the request
    /// if they overtake that capture before it reaches host_lock.
    full_from_seq: u64 = 0,
    notes: std.ArrayList(Note) = .empty,

    pub fn create(gpa: Allocator, host: Host, net: Net, px_per_sp: f32) !*Core {
        const self = try gpa.create(Core);
        errdefer gpa.destroy(self);
        self.* = .{
            .gpa = gpa,
            .host = host,
            .net = net,
            .px_per_sp = if (px_per_sp > 0) px_per_sp else 1,
            .threaded = .init(gpa, .{}),
            .io = undefined,
        };
        self.io = self.threaded.io();
        errdefer self.threaded.deinit();
        self.control = try std.Thread.spawn(.{}, controlMain, .{self});
        return self;
    }

    /// Stops every thread of the core, then frees it. No host callback runs after this
    /// returns. Waits for a TLS connect that is in progress.
    pub fn destroy(self: *Core) void {
        self.lock.lock();
        self.closeForwardBlocks();
        self.quit = true;
        self.epoch += 1;
        self.clearModel();
        if (self.ws) |w| w.close();
        self.lock.signal();
        self.lock.unlock();

        if (self.control) |t| t.join();

        self.lock.lock();
        while (self.live_streams > 0) self.lock.wait();
        var forwards = self.forwards;
        self.forwards = .empty;
        for (forwards.items) |f| f.closing = true;
        self.lock.unlock();
        for (forwards.items) |f| self.endForward(f);
        forwards.deinit(self.gpa);

        const gpa = self.gpa;
        self.clearLink();
        self.clearHosts();
        self.outbox.deinit(gpa);
        self.pending.deinit(gpa);
        self.sessions.deinit(gpa);
        self.entries.deinit(gpa);
        self.follows.deinit(gpa);
        self.leads.deinit(gpa);
        self.stashed.deinit(gpa);
        self.beside.deinit(gpa);
        gpa.free(self.fence_prefix);
        var made = self.made.keyIterator();
        while (made.next()) |id| gpa.free(id.*);
        self.made.deinit(gpa);
        self.blocks.deinit(gpa);
        gpa.free(self.account);
        gpa.free(self.client_id);
        if (self.pane_watches) |watches| watches.deinit(gpa);
        self.freeNotes(&self.notes);
        if (self.last_state) |last| last.deinit(gpa);
        self.patch_buf.deinit(gpa);
        gpa.free(self.url);
        gpa.free(self.server);
        if (self.token) |t| gpa.free(t);
        self.threaded.deinit();
        gpa.destroy(self);
    }

    /// Connect to the server at `url`, any locator the desktop takes (`locate`):
    /// `https://rex.example.ts.net`, `http://127.0.0.1:7890`, `/tmp/rex.sock`,
    /// `ssh://me@host:2222` (through `Net.ssh`). Also Reconnect after a stop.
    /// Replaces any current connection; another server (not `Endpoint.same`) also drops
    /// the session model. A session selected before the first connect stays: the
    /// bootstrap names it, so the server does not pick one. Returns at once.
    pub fn connect(self: *Core, url: []const u8, reconnect_token: ?[]const u8) void {
        self.lock.lock();
        defer self.release();
        if (self.quit) return;
        self.epoch += 1;
        if (self.ws) |w| w.close();
        self.lock.signal();
        self.dirty = true;

        const endpoint = switch (locate(url)) {
            .endpoint => |endpoint| endpoint,
            .refused => |refusal| {
                self.clearModel();
                self.clearTarget();
                self.status = .@"error";
                if (std.fmt.allocPrint(self.gpa, "{f}", .{refusal})) |sentence| {
                    defer self.gpa.free(sentence);
                    self.noteError(refusal.code, sentence);
                } else |_| {}
                return;
            },
        };
        const same = if (endpoint_mod.parse(self.server)) |old| old.same(endpoint) else |_| false;
        if (self.server.len > 0 and !same) self.clearModel();
        if (!self.setTarget(url, reconnect_token)) {
            self.status = .@"error";
            self.noteError("internal", "out of memory");
            return;
        }
        self.status = .connecting;
    }

    pub fn disconnect(self: *Core) void {
        self.lock.lock();
        defer self.release();
        if (self.quit) return;
        self.epoch += 1;
        if (self.ws) |w| w.close();
        self.clearModel();
        self.clearTarget();
        self.status = .idle;
        self.dirty = true;
        self.lock.signal();
    }

    /// Attach to `session` (a `session:<uuid>` id) and show it.
    pub fn selectSession(self: *Core, session: []const u8) void {
        self.lock.lock();
        defer self.release();
        if (self.quit or session.len == 0) return;
        if (self.isSelected(session)) return;
        // Before the list is known this is a wish; the bootstrap answer decides.
        if (self.ready and !self.exists(session)) return;
        if (self.ready) if (self.selected) |old| self.detach(old);
        self.auto_pick = true;
        self.attach(session);
    }

    /// Handle one control message text as the receive loop does, without a socket.
    /// Test seam: lets host tests drive responses and events through the real handlers.
    pub fn receive(self: *Core, text: []const u8) void {
        var arena: std.heap.ArenaAllocator = .init(self.gpa);
        defer arena.deinit();
        const incoming = protocol.parse(arena.allocator(), text) catch return;
        self.lock.lock();
        defer self.release();
        if (self.quit) return;
        self.handleIncoming(incoming);
    }

    /// A user intent as JSON (see `protocol.parseIntent`).
    pub fn command(self: *Core, text: []const u8) void {
        var arena: std.heap.ArenaAllocator = .init(self.gpa);
        defer arena.deinit();
        const intent = protocol.parseIntent(arena.allocator(), text) catch .unknown;

        self.lock.lock();
        defer self.release();
        if (self.quit) return;
        const gpa = self.gpa;
        switch (intent) {
            .focus, .select_window, .new_window, .new_session, .split, .close, .close_last_pane, .close_window, .close_session => self.invalidateComposerLocked(),
            else => {},
        }
        switch (intent) {
            .unknown => self.noteError("bad_request", "unknown command"),
            .restore_pane => |target| {
                if (!self.ready or !self.isSelected(target.session)) return;
                const view = self.view orelse return;
                for (view.windows) |w| {
                    if (!std.mem.eql(u8, w.id, target.window)) continue;
                    for (w.hidden) |b| {
                        if (b.layer.len == 0 or !std.mem.eql(u8, b.id, target.block)) continue;
                        // Restore without focusing a sibling in the same floating layer.
                        self.sendCommand(protocol.methods.setLayerState, protocol.setLayerState(gpa, target.session, b.layer, "normal", target.focus));
                        return;
                    }
                }
            },
            .minimize_pane => |block| if (self.sessionForCommand()) |session| self.minimizePane(session, block),
            .minimize_floating => |m| if (self.sessionForCommand()) |session| self.minimizeFloating(session, m.window, m.minimize),
            .pane_watches => |value| self.setPaneWatches(value),
            .setting => |s| self.applySetting(s.key, s.value),
            .hosts_put => |h| {
                if (!self.ready) return self.noteError("not_connected", "not connected to the server");
                self.sendCommand(protocol.methods.hostsPut, protocol.hostsPut(gpa, h.id, h.label, h.url));
            },
            .hosts_remove => |id| {
                if (!self.ready) return self.noteError("not_connected", "not connected to the server");
                self.sendCommand(protocol.methods.hostsRemove, protocol.hostsRemove(gpa, id));
            },
            .detach => {
                if (self.ready) if (self.selected) |old| self.detach(old);
                var pending = self.pending.valueIterator();
                while (pending.next()) |p| {
                    if (p.* == .create_session) p.create_session = false;
                }
                self.auto_pick = false;
                // Parked: with no session selected, none is followed either.
                self.setSelected(null);
            },
            .resume_session => |preferred| {
                // Choose under the same lock as session_destroyed and session.list.
                if (self.selected != null) return;
                self.auto_pick = true;
                if (preferred) |id| {
                    if (!self.ready or self.listed(id)) return self.attach(id);
                }
                // An empty list is handled by the next bootstrap or list answer.
                self.pickSession();
            },
            .fence => |prefix| self.setFence(prefix),
            .send_text => |t| {
                if (self.sessionForCommand() == null) return;
                self.writeText(t.block, t.text);
            },
            .pane_write => |w| self.paneWrite(w),
            .pane_screen => |s| self.paneScreen(s),
            .paste => |p| {
                if (self.sessionForCommand() == null) return;
                // The mode is read here, not taken from the app: it may have changed since
                // the app asked (`paneBracketedPaste`). A terminal this core does not mirror
                // has no mode to read.
                const bracketed = if (self.entries.get(p.block)) |entry| pasteMode(entry) else false;
                const input = protocol.pasteInput(gpa, p.text, bracketed, p.confirmed) catch |err| switch (err) {
                    error.NeedsConfirm => return self.noteError("paste_needs_confirm", "the terminal has bracketed paste off; paste again to confirm"),
                    error.OutOfMemory => return,
                };
                defer gpa.free(input);
                self.writeText(p.block, input);
            },
            // Reports about a pane, not requests of the user: never an error.
            .fit => |block| if (self.entries.get(block)) |entry| self.sizeStep(entry, .fit),
            .touch => |block| if (self.entries.get(block)) |entry| self.sizeStep(entry, .used),
            .resize_pane => |resize| if (self.sessionForCommand()) |session|
                self.sendCommand(protocol.methods.resizePane, protocol.resizePane(gpa, session, resize)),
            .split => |s| if (self.sessionForCommand()) |session|
                self.sendCommand(protocol.methods.newSplit, protocol.newSplit(gpa, session, s.block, s.direction)),
            .float => |block| if (self.sessionForCommand()) |session|
                self.sendCommand(protocol.methods.floatBlock, protocol.floatBlock(gpa, session, block)),
            .dock => |block| if (self.sessionForCommand()) |session| {
                const anchor = if (self.view) |*v| dockAnchor(v, block) else null;
                if (anchor) |a| {
                    self.sendCommand(protocol.methods.moveBlock, protocol.moveBlock(gpa, session, block, a));
                } else {
                    self.noteError("conflict", "no tiled pane to dock beside");
                }
            },
            .move_pane => |m| if (self.sessionForCommand()) |session|
                self.movePane(session, m.block, m.anchor, m.direction, null, false),
            .swap_panes => |s| if (self.sessionForCommand()) |session|
                self.swapPanes(session, s.block, s.other, false),
            .move_to_new_tab => |block| if (self.sessionForCommand()) |session|
                self.startDockTab(session, block, null) catch self.noteError("internal", "could not move the pane to a new tab"),
            .undo_move => |undo| if (self.sessionForCommand()) |session| self.undoMove(session, undo),
            .zoom => |block| if (self.sessionForCommand()) |session|
                self.sendCommand(protocol.methods.zoomBlock, protocol.zoomBlock(gpa, session, block, true)),
            .unzoom => |block| if (self.sessionForCommand()) |session|
                self.sendCommand(protocol.methods.zoomBlock, protocol.zoomBlock(gpa, session, block, false)),
            .close => |block| if (self.sessionForCommand()) |session|
                self.sendCommand(protocol.methods.closeBlock, protocol.closeBlock(gpa, session, block)),
            .close_last_pane => |target| {
                if (!self.isSelected(target.session)) return;
                const view = self.view orelse return;
                for (view.windows) |w| {
                    if (!std.mem.eql(u8, w.id, target.window)) continue;
                    if (!w.minimized and w.blocks.len == 1 and std.mem.eql(u8, w.blocks[0].id, target.block)) {
                        self.sendFor(target.session, protocol.methods.closeBlock, protocol.closeBlockAllowLast(gpa, target.session, target.block), .close);
                    }
                    break;
                }
            },
            .focus => |block| if (self.sessionForCommand()) |session|
                self.sendCommand(protocol.methods.focusBlock, protocol.focusBlock(gpa, session, block)),
            .new_session => |s| {
                if (!self.ready) return self.noteError("not_connected", "not connected to the server");
                const payload = protocol.createSession(gpa, s.label) catch return;
                _ = self.sendRequest(protocol.methods.createSession, payload, .{ .create_session = true });
            },
            .new_window => |n| if (self.sessionFor(n.session)) |session|
                self.sendCommand(protocol.methods.newWindow, protocol.newWindow(gpa, session)),
            // Sent with the attach, not after it: whichever the server runs first, the
            // view this core ends on is the one after the focus (see `applyView`).
            .select_window => |s| if (self.sessionFor(s.session)) |session|
                self.sendCommand(protocol.methods.focusWindow, protocol.focusWindow(gpa, session, s.window)),
            .close_window => |c| self.sendFor(c.session, protocol.methods.closeWindow, protocol.closeWindow(gpa, c.session, c.window), .close),
            // The answer's revision tells whether the tab strip changed, so the view is
            // read at once instead of waiting for the server's event (see `sessionRevision`).
            .move_window => |m| {
                const owned = gpa.dupe(u8, m.session) catch return;
                self.sendFor(m.session, protocol.methods.moveWindow, protocol.moveWindow(gpa, m.session, m.window, m.before), .{ .window_moved = owned });
            },
            .close_session => |session| self.sendFor(session, protocol.methods.destroySession, protocol.destroySession(gpa, session), .close),
            .rename_session => |r| self.sendFor(r.session, protocol.methods.setSessionLabel, protocol.setSessionLabel(gpa, r.session, r.label), .command),
            .rename_window => |r| self.sendFor(r.session, protocol.methods.setWindowLabel, protocol.setWindowLabel(gpa, r.session, r.window, r.label), .command),
        }
    }

    // ------------------------------------------------------------- stashing panes
    //
    // A stashed pane is a minimized floating layer: it keeps running, hidden. Each request
    // is checked against the held view right before it is sent, like a pane move, and the
    // server's refusal to hide a tab's last panes on screen is never provoked.

    const last_on_screen = "the tab's last pane on screen stays";

    /// Intent `minimizePane`: minimize the floating layer of `block`, or float a tiled
    /// `block` first and minimize the layer the answer names (`.stash_floated`).
    fn minimizePane(self: *Core, session: []const u8, block: []const u8) void {
        const gpa = self.gpa;
        const view = self.view orelse return;
        for (view.windows) |w| {
            const tiled_here = if (w.layout) |l| layoutHas(l, block) else false;
            const layer: ?model.Id = for (w.floating) |f| {
                if (std.mem.eql(u8, f.block, block)) break f.layer;
            } else null;
            if (!tiled_here and layer == null) continue;
            // Something placed in this tab must stay on screen.
            var stays = if (w.layout) |l| firstLeafExcept(l, block) != null else false;
            for (w.floating) |f| {
                if (std.mem.eql(u8, f.block, block)) continue;
                if (layer) |own| if (std.mem.eql(u8, f.layer, own)) continue;
                stays = true;
            }
            if (!stays) return self.noteError("bad_request", last_on_screen);
            if (layer) |own| {
                if (own.len == 0) return;
                return self.sendCommand(protocol.methods.setLayerState, protocol.setLayerState(gpa, session, own, "minimized", null));
            }
            const owned = gpa.dupe(u8, session) catch return;
            return self.sendFor(session, protocol.methods.floatBlock, protocol.floatBlock(gpa, session, block), .{ .stash_floated = owned });
        }
    }

    /// Intent `minimizeFloating`: one request per floating layer of tab `window` (`minimize`),
    /// which needs a tiled pane to stay, or per minimized layer of it (else).
    fn minimizeFloating(self: *Core, session: []const u8, window: []const u8, minimize: bool) void {
        const gpa = self.gpa;
        const view = self.view orelse return;
        for (view.windows) |w| {
            if (!std.mem.eql(u8, w.id, window)) continue;
            if (minimize) {
                if (w.layout == null) return;
                layers: for (w.floating, 0..) |f, i| {
                    if (f.layer.len == 0) continue;
                    for (w.floating[0..i]) |earlier| if (std.mem.eql(u8, earlier.layer, f.layer)) continue :layers;
                    self.sendCommand(protocol.methods.setLayerState, protocol.setLayerState(gpa, session, f.layer, "minimized", null));
                }
            } else {
                layers: for (w.hidden, 0..) |h, i| {
                    if (h.layer.len == 0) continue;
                    for (w.hidden[0..i]) |earlier| if (std.mem.eql(u8, earlier.layer, h.layer)) continue :layers;
                    self.sendCommand(protocol.methods.setLayerState, protocol.setLayerState(gpa, session, h.layer, "normal", false));
                }
            }
            return;
        }
    }

    /// Write `text` to the terminal of `block` in the selected session, which the caller
    /// has checked (`sessionForCommand`).
    fn writeText(self: *Core, block: []const u8, text: []const u8) void {
        if (self.entries.get(block)) |entry| {
            self.queueInput(entry, text);
        } else if (!self.resume_pending) {
            // Not a terminal this core mirrors: let the server decide.
            const session = self.selected.?;
            self.sendCommand(protocol.methods.terminalWrite, protocol.terminalWrite(self.gpa, session, block, text));
        }
    }

    pub const PaneWriteResult = enum {
        /// The server wrote all of the text.
        sent,
        /// The prompt is no longer the one the answer was for: nothing was written.
        changed,
        /// Not connected, no such session, or the check failed: nothing was written.
        not_sent,
        /// The write went out and was not confirmed: part or all of it may be there.
        unconfirmed,
    };

    /// Intent `paneWrite`: read the pane's program status, and write the text only while it
    /// still holds `expect` (`statusHolds`) and the process the server named as the record's
    /// owner is still the pane's foreground process (`ownerInFront`). Unlike `sendText` it names its session, so it
    /// works for a session that is not selected and for a parked core, whose control
    /// connection stays. Nothing is queued while the link is down. One answer for `tag`
    /// follows through the dedicated onPaneWrite callback.
    fn paneWrite(self: *Core, w: protocol.PaneWrite) void {
        if (!self.ready) return self.notePaneWrite(w.tag, .not_sent, "not connected to the server");
        if (!self.exists(w.session)) return self.notePaneWrite(w.tag, .not_sent, "the session is gone");
        if (w.text.len == 0 or w.text.len > protocol.max_composer_text) return self.notePaneWrite(w.tag, .not_sent, "the answer is empty or too long");
        const gpa = self.gpa;
        const op = PaneWriteOp.create(gpa, w) catch return self.notePaneWrite(w.tag, .not_sent, "out of memory");
        const payload = protocol.blockCall(gpa, w.session, w.block) catch {
            op.destroy(gpa);
            return self.notePaneWrite(w.tag, .not_sent, "out of memory");
        };
        if (!self.sendRequest(protocol.methods.terminalProgramStatus, payload, .{ .pane_write_check = op })) {
            self.notePaneWrite(w.tag, .not_sent, "the status check could not be sent");
            op.destroy(gpa);
        }
    }

    /// The answer to a `paneWrite` status check: write, or report why not.
    fn paneWriteChecked(self: *Core, op: *PaneWriteOp, payload: ?json.Value, err: ?protocol.ResponseError) void {
        const w = op.write;
        if (err) |e| return self.notePaneWrite(w.tag, .not_sent, e.message);
        if (!statusHolds(w.expect, payload orelse .null)) return self.notePaneWrite(w.tag, .changed, "the prompt was answered or changed");
        // Any program that writes to the pane can report a status, so a record that still
        // holds says nothing of which program would read the answer. The server names the
        // process that was in front when the record was reported: the answer goes out only
        // while that process is still in front, not to a program started since (#32). A
        // server that names no owner gives nothing to compare, and the record alone decides,
        // as it did before.
        const owner = ownerPid(w.expect.id, payload orelse .null) orelse return self.paneWriteSend(w);
        const gpa = self.gpa;
        const next = PaneWriteOp.create(gpa, w) catch return self.notePaneWrite(w.tag, .not_sent, "out of memory");
        next.owner = owner;
        const call = protocol.blockCall(gpa, w.session, w.block) catch {
            next.destroy(gpa);
            return self.notePaneWrite(w.tag, .not_sent, "out of memory");
        };
        if (!self.sendRequest(protocol.methods.terminalProcess, call, .{ .pane_write_owner = next })) {
            self.notePaneWrite(w.tag, .not_sent, "the process check could not be sent");
            next.destroy(gpa);
        }
    }

    /// The answer to a `paneWrite` process check: write while the record's owner is in front.
    fn paneWriteOwned(self: *Core, op: *PaneWriteOp, payload: ?json.Value, err: ?protocol.ResponseError) void {
        const w = op.write;
        if (err) |e| return self.notePaneWrite(w.tag, .not_sent, e.message);
        if (!ownerInFront(op.owner, payload orelse .null)) return self.notePaneWrite(w.tag, .changed, "another program is in front of the one that asked");
        self.paneWriteSend(w);
    }

    /// The write of a `paneWrite` that passed its checks.
    fn paneWriteSend(self: *Core, w: protocol.PaneWrite) void {
        const gpa = self.gpa;
        const tag = gpa.dupe(u8, w.tag) catch return self.notePaneWrite(w.tag, .not_sent, "out of memory");
        const write = protocol.terminalWrite(gpa, w.session, w.block, w.text) catch {
            gpa.free(tag);
            return self.notePaneWrite(w.tag, .not_sent, "out of memory");
        };
        if (!self.sendRequest(protocol.methods.terminalWrite, write, .{ .pane_write = .{ .tag = tag, .bytes = w.text.len } })) {
            self.notePaneWrite(tag, .not_sent, "the write could not be sent");
            gpa.free(tag);
        }
    }

    fn notePaneWrite(self: *Core, tag: []const u8, result: PaneWriteResult, message: []const u8) void {
        if (self.host.onPaneWrite == null) return;
        const text = std.mem.concat(self.gpa, u8, &.{ tag, message }) catch return;
        self.notes.append(self.gpa, .{ .pane_write = .{ .text = text, .tag_len = tag.len, .result = result } }) catch self.gpa.free(text);
    }

    /// Intent `paneScreen`: read the last lines of the pane's text from the server
    /// (`screenTail`), for the notification of a prompt (#32). The server keeps a terminal's
    /// text whether or not a client shows it, so this works for a pane that has no view, in
    /// a session that is not selected, and for a parked core; the core's own copy of a pane
    /// exists only for the selected session's. Nothing is queued while the link is down. One
    /// answer for `tag` follows through onPaneScreen, with no lines when they could not be read.
    fn paneScreen(self: *Core, s: protocol.PaneScreen) void {
        if (!self.ready or !self.exists(s.session)) return self.notePaneScreen(s.tag, "");
        const gpa = self.gpa;
        const op = PaneScreenOp.create(gpa, s.tag, s.session, s.block) catch return self.notePaneScreen(s.tag, "");
        const payload = protocol.terminalFormat(gpa, s.session, s.block, screen_tail_bytes) catch {
            op.free(gpa);
            return self.notePaneScreen(s.tag, "");
        };
        if (!self.sendRequest(protocol.methods.terminalFormat, payload, .{ .pane_screen = op })) {
            self.notePaneScreen(s.tag, "");
            op.free(gpa);
        }
    }

    /// The answer to a `paneScreen` format: all of the text, or its start. The text is the
    /// scrollback and then the screen, so only its end is wanted: when more remains, the
    /// last `screen_tail_bytes` of it are read.
    fn paneScreenFormatted(self: *Core, op: PaneScreenOp, payload: ?json.Value, err: ?protocol.ResponseError) void {
        const result = payload orelse .null;
        const content = model.str(model.get(result, "content"));
        if (err != null or content == null) return self.notePaneScreen(op.tag, "");
        const id = model.get(result, "id") orelse return self.notePaneLines(op.tag, content.?, true);
        // A server that does not say how long the text is gives no way to its end.
        const total = model.int(model.get(result, "total")) orelse return self.notePaneScreen(op.tag, "");
        if (id != .integer or total < 0) return self.notePaneScreen(op.tag, "");
        const offset = @max(0, total - screen_tail_bytes);
        const gpa = self.gpa;
        var next = PaneScreenOp.create(gpa, op.tag, op.session, op.block) catch return self.notePaneScreen(op.tag, "");
        next.cut = offset > 0;
        // A read counts its bytes as JSON text, where one byte takes up to six.
        const read = protocol.terminalBlobRead(gpa, op.session, op.block, id.integer, offset, screen_tail_bytes * 6) catch {
            next.free(gpa);
            return self.notePaneScreen(op.tag, "");
        };
        if (!self.sendRequest(protocol.methods.terminalBlobRead, read, .{ .pane_screen_tail = next })) {
            self.notePaneScreen(op.tag, "");
            next.free(gpa);
        }
    }

    /// The answer to a `paneScreen` read of the end of the text. A read that stops before
    /// the end would show lines that are not the last ones, so it counts as no answer.
    fn paneScreenRead(self: *Core, op: PaneScreenOp, payload: ?json.Value, err: ?protocol.ResponseError) void {
        const result = payload orelse .null;
        const data = model.str(model.get(result, "data"));
        if (err != null or data == null or model.boolean(model.get(result, "eof")) != true) return self.notePaneScreen(op.tag, "");
        self.notePaneLines(op.tag, data.?, !op.cut);
    }

    fn notePaneLines(self: *Core, tag: []const u8, text: []const u8, whole: bool) void {
        const lines = screenTail(self.gpa, text, whole) catch return self.notePaneScreen(tag, "");
        defer self.gpa.free(lines);
        self.notePaneScreen(tag, lines);
    }

    fn notePaneScreen(self: *Core, tag: []const u8, lines: []const u8) void {
        if (self.host.onPaneScreen == null) return;
        const text = std.mem.concat(self.gpa, u8, &.{ tag, lines }) catch return;
        self.notes.append(self.gpa, .{ .pane_screen = .{ .text = text, .tag_len = tag.len } }) catch self.gpa.free(text);
    }

    /// An Android key event for a terminal block (action: 0 down, 1 up, 2 multiple).
    pub fn key(self: *Core, block: []const u8, key_code: i32, meta: i32, unicode: u32, action: u8) void {
        const pane_action = keyAction(action) orelse return;
        self.lock.lock();
        defer self.release();
        const entry = self.entries.get(block) orelse return;
        var buf: [128]u8 = undefined;
        const bytes = entry.pane.encodeKey(&buf, key_code, meta, unicode, pane_action);
        if (bytes.len > 0) self.queueInput(entry, bytes);
    }

    /// Attach (`window` = ANativeWindow) or detach (null) the pane's render surface.
    /// The window reference is consumed in every case. The first surface with an area
    /// shows the pane: the core takes its block and asks for the size that fits
    /// (`SizeRules`). A surface with a zero axis shows nothing. A surface for a pane that
    /// is shown only replaces it; the app sends the `fit` intent once the new size has
    /// settled. A pane whose surface was a preview is shown by it as by a first surface.
    pub fn paneSurface(self: *Core, block: []const u8, window: ?*anyopaque, w: u32, h: u32) void {
        self.setPaneSurface(block, window, w, h, .surface);
    }

    /// Like `paneSurface`, but the surface is a preview (the tab overview): it draws the
    /// pane as a shown pane's surface does, while for the size rules the pane is not
    /// shown, so nothing is taken and no size is asked for, also when it is touched or
    /// typed into. `paneSurface` with the same window afterwards shows the pane without a
    /// new renderer, and the other way round.
    pub fn panePreview(self: *Core, block: []const u8, window: ?*anyopaque, w: u32, h: u32) void {
        self.setPaneSurface(block, window, w, h, .preview);
    }

    /// Like `paneSurface` for a pane that is back where it was shown before: its surface
    /// was a preview and the overview closed on the tab it was opened from, or its surface
    /// went away without the pane (`paneSuspend`) and is back: the app is on the screen
    /// again, or what covered the panes is gone. A pane that was shown there is shown again
    /// as it was. Nothing is taken, also not from a client that took the terminal meanwhile,
    /// and a size is asked for only when the fit changed. A pane that was not shown before,
    /// or was closed since, is shown as `paneSurface` shows it.
    pub fn paneResume(self: *Core, block: []const u8, window: ?*anyopaque, w: u32, h: u32) void {
        self.setPaneSurface(block, window, w, h, .resumed);
    }

    /// Detach the pane's surface because the app left the screen (the screen went off,
    /// another app came to the front) or its own sidebar or screen covers the panes: the
    /// pane's view is gone and the pane is not. `paneResume` shows the pane again without
    /// a take.
    pub fn paneSuspend(self: *Core, block: []const u8) void {
        self.lock.lock();
        defer self.lock.unlock();
        const entry = self.entries.get(block) orelse return;
        entry.pane.detachSurface();
        self.sizeStep(entry, .suspended);
    }

    /// `as` is what a window makes of the pane for the size rules: `.surface` (shown),
    /// `.resumed` (shown again as it was) or `.preview`. No window is `.hidden` for all
    /// three.
    fn setPaneSurface(self: *Core, block: []const u8, window: ?*anyopaque, w: u32, h: u32, as: size_rules.Event) void {
        self.lock.lock();
        defer self.lock.unlock();
        const entry = self.entries.get(block) orelse {
            // No pane to own the reference.
            if (window) |win| if (pane.is_android) ANativeWindow_release(win);
            return;
        };
        if (window) |win| {
            // On failure the pane has released the window.
            entry.pane.attachSurface(win, w, h) catch {};
            entry.surface_w = w;
            entry.surface_h = h;
            self.sizeStep(entry, as);
        } else {
            entry.pane.detachSurface();
            self.sizeStep(entry, .hidden);
        }
    }

    /// `live`: a pinch is still changing the scale (`Pane.setScale`).
    pub fn paneScale(self: *Core, block: []const u8, scale: f32, live: bool) void {
        self.lock.lock();
        defer self.lock.unlock();
        if (self.entries.get(block)) |entry| entry.pane.setScale(scale, live);
    }

    /// A drag of (dx, dy) px on the pane: pans sideways, scrolls through scrollback.
    pub fn panePan(self: *Core, block: []const u8, dx: f32, dy: f32, x: f32, y: f32) void {
        self.lock.lock();
        defer self.release();
        const entry = self.entries.get(block) orelse return;
        const input = entry.pane.pan(dx, dy, x, y) orelse return;
        const session = self.selected orelse return;
        const stream = entry.stream orelse return;
        if (!self.ready or self.resume_pending or !stream.live_snapshot.load(.acquire)) return;
        for (0..input.count) |_| switch (input.event) {
            .wheel => |w| self.sendCommand(protocol.methods.blockEvent, protocol.wheel(self.gpa, session, block, w.x, w.y, w.delta_y)),
            .cursor => |bytes| self.queueInput(entry, bytes),
        };
    }

    /// A tap at (x, y) of a pane whose program takes the mouse: a left click at the tapped cell.
    /// False, with nothing sent, when the program does not take the mouse or there is no cell there.
    pub fn paneClick(self: *Core, block: []const u8, x: f32, y: f32) bool {
        self.lock.lock();
        defer self.release();
        const entry = self.entries.get(block) orelse return false;
        // A terminal another device sized is taken by this very tap (the `touch` op): the program is
        // refitted before a click could reach it, and the cell would hold something else then.
        // That tap only takes the terminal; the next one clicks.
        if (!entry.size.owned or entry.size.take_inflight) return false;
        const cell = entry.pane.mouseCell(x, y) orelse return false;
        const session = self.selected orelse return false;
        const stream = entry.stream orelse return false;
        // As for the wheel: a held picture may be an old program's, which never asked for the mouse.
        if (!self.ready or self.resume_pending or !stream.live_snapshot.load(.acquire)) return false;
        self.sendCommand(protocol.methods.blockEvent, protocol.mouse(self.gpa, session, block, "press", cell.x, cell.y));
        self.sendCommand(protocol.methods.blockEvent, protocol.mouse(self.gpa, session, block, "release", cell.x, cell.y));
        return true;
    }

    /// An Android touch event (action: 0 down, 1 up, 2 move, 3 cancel), in surface px.
    pub fn paneTouch(self: *Core, block: []const u8, action: u8, x: f32, y: f32) void {
        const pane_action = touchAction(action) orelse return;
        self.lock.lock();
        defer self.lock.unlock();
        const entry = self.entries.get(block) orelse return;
        entry.pane.touch(pane_action, x, y);
        if (pane_action == 0) self.sizeStep(entry, .used);
    }

    pub fn paneFind(self: *Core, block: []const u8, needle: []const u8, direction: i32) !@import("pane").SearchResult {
        self.lock.lock();
        defer self.lock.unlock();
        const entry = self.entries.get(block) orelse return error.UnknownBlock;
        return entry.pane.find(needle, direction);
    }

    /// Selected text of the pane, or its visible screen text. Caller frees.
    pub fn paneCopy(self: *Core, gpa: Allocator, block: []const u8) ![]u8 {
        self.lock.lock();
        defer self.lock.unlock();
        const entry = self.entries.get(block) orelse return error.UnknownBlock;
        return entry.pane.copyText(gpa);
    }

    /// The link under the surface point (x, y) of the pane, as the app reads it:
    /// `{"kind":"url"|"localhost"|"file","target":"…","line":0,"col":0,"set":false,"shown":true}`.
    /// `set` says a program set the link (OSC 8), and `shown` that the text on its cells is its
    /// target: the app asks before it opens a link the person could not read. Null when there
    /// is no link there or no such pane. Caller frees.
    pub fn paneLink(self: *Core, gpa: Allocator, block: []const u8, x: f32, y: f32) !?[]u8 {
        self.lock.lock();
        defer self.lock.unlock();
        const entry = self.entries.get(block) orelse return null;
        const link = (try entry.pane.linkAt(gpa, x, y)) orelse return null;
        defer link.deinit(gpa);
        return try json.Stringify.valueAlloc(gpa, .{
            .kind = @tagName(link.kind),
            .target = link.target,
            .line = link.line,
            .col = link.col,
            .set = link.set,
            .shown = link.shown,
        }, .{});
    }

    /// Underline the link under (x, y) of the pane until `paneUnmarkLink`; false when there is none.
    pub fn paneMarkLink(self: *Core, block: []const u8, x: f32, y: f32) bool {
        self.lock.lock();
        defer self.lock.unlock();
        const entry = self.entries.get(block) orelse return false;
        return entry.pane.markLink(x, y);
    }

    pub fn paneUnmarkLink(self: *Core, block: []const u8) void {
        self.lock.lock();
        defer self.lock.unlock();
        if (self.entries.get(block)) |entry| entry.pane.unmarkLink();
    }

    pub fn paneSelectWord(self: *Core, block: []const u8, x: f32, y: f32) void {
        self.lock.lock();
        defer self.lock.unlock();
        if (self.entries.get(block)) |entry| entry.pane.selectWord(x, y);
    }

    pub fn paneMoveSelectionHandle(self: *Core, block: []const u8, start: bool, x: f32, y: f32) void {
        self.lock.lock();
        defer self.lock.unlock();
        if (self.entries.get(block)) |entry| entry.pane.moveSelectionHandle(start, x, y);
    }

    pub fn paneSelectionBounds(self: *Core, block: []const u8) ?[4]i32 {
        self.lock.lock();
        defer self.lock.unlock();
        const entry = self.entries.get(block) orelse return null;
        return entry.pane.selectionBounds();
    }

    pub fn paneSelectedText(self: *Core, gpa: Allocator, block: []const u8) ![]u8 {
        self.lock.lock();
        defer self.lock.unlock();
        const entry = self.entries.get(block) orelse return error.UnknownBlock;
        return entry.pane.selectedText(gpa);
    }

    /// How many frames the pane's surfaces have taken (`Pane.swaps`); 0 for no such pane. The
    /// same number again is the same picture on the surface.
    pub fn paneSwaps(self: *Core, block: []const u8) u64 {
        self.lock.lock();
        defer self.lock.unlock();
        const entry = self.entries.get(block) orelse return 0;
        return entry.pane.swaps.load(.monotonic);
    }

    /// True when the active screen of this pane has a selection.
    pub fn paneHasSelection(self: *Core, block: []const u8) bool {
        self.lock.lock();
        defer self.lock.unlock();
        const entry = self.entries.get(block) orelse return false;
        return entry.pane.hasSelection();
    }

    /// End the selection without feeding any input to the terminal.
    pub fn paneClearSelection(self: *Core, block: []const u8) void {
        self.lock.lock();
        defer self.lock.unlock();
        if (self.entries.get(block)) |entry| entry.pane.clearSelection();
    }

    /// The IME's uncommitted text stays local. Only the session and block IDs go into the
    /// two parallel queries. A fresh answer is needed even when the same word is edited.
    pub fn panePreedit(self: *Core, block: []const u8, text: []const u8) void {
        self.lock.lock();
        defer self.lock.unlock();
        const entry = self.entries.get(block) orelse return;
        if (!entry.pane.setPreedit(text)) return;
        if (text.len == 0) {
            entry.preedit_ask = null;
            return;
        }
        self.checkPreedit(entry);
    }

    fn checkPreedit(self: *Core, entry: *Entry) void {
        if (entry.preedit_ask) |*pending| {
            pending.again = true;
            return;
        }
        if (!self.ready or self.resume_pending) return;
        const session = self.selected orelse return;
        const mode = protocol.blockCall(self.gpa, session, entry.id) catch return;
        const mode_id = self.next_id;
        if (!self.sendRequest(protocol.methods.terminalInputMode, mode, .{ .input_mode = entry.serial })) return;
        const process = protocol.blockCall(self.gpa, session, entry.id) catch return;
        const process_id = self.next_id;
        if (!self.sendRequest(protocol.methods.terminalProcess, process, .{ .preedit_process = entry.serial })) return;
        entry.preedit_ask = .{ .mode_id = mode_id, .process_id = process_id };
    }

    fn finishPreeditCheck(self: *Core, entry: *Entry) void {
        const check = entry.preedit_ask.?;
        const mode = check.mode_allows orelse return;
        const program = check.program_allows orelse return;
        entry.preedit_ask = null;
        if (check.again) self.checkPreedit(entry) else entry.pane.setPreeditVisible(mode and program);
    }

    /// True when a paste into the pane of `block` would be framed as a bracketed paste
    /// (`pasteMode`). The app asks this to decide whether the user must confirm a paste;
    /// the `paste` command reads the mode again when it runs.
    pub fn paneBracketedPaste(self: *Core, block: []const u8) bool {
        self.lock.lock();
        defer self.lock.unlock();
        const entry = self.entries.get(block) orelse return false;
        return pasteMode(entry);
    }

    pub fn paneViewportText(self: *Core, gpa: Allocator, block: []const u8) ![]u8 {
        self.lock.lock();
        defer self.lock.unlock();
        const entry = self.entries.get(block) orelse return error.UnknownBlock;
        return entry.pane.viewportText(gpa);
    }

    /// True once the pane of `block` shows the terminal's snapshot.
    pub fn paneReady(self: *Core, block: []const u8) bool {
        self.lock.lock();
        defer self.lock.unlock();
        const entry = self.entries.get(block) orelse return false;
        return if (entry.stream) |s| s.isRestored() else false;
    }

    fn focusedBlock(view: ?model.View) ?[]const u8 {
        const v = view orelse return null;
        if (v.windows.len == 0) return null;
        const window = for (v.windows) |w| {
            if (std.mem.eql(u8, v.active_window orelse "", w.id)) break w;
        } else v.windows[0];
        if (window.focused) |id| return id;
        var layout = window.layout orelse return null;
        while (layout.* == .split) layout = layout.split.first;
        return layout.block;
    }

    fn composerGeneration(self: *Core, session: []const u8, block: []const u8) u64 {
        if (self.quit or !self.ready or self.status != .connected or self.resume_pending or
            !self.isSelected(session) or !self.entries.contains(block)) return 0;
        const focused = focusedBlock(self.view) orelse return 0;
        return if (std.mem.eql(u8, focused, block)) self.composer_gen else 0;
    }

    pub fn composerTarget(self: *Core, session: []const u8, block: []const u8, require_paste: bool) u64 {
        self.lock.lock();
        defer self.lock.unlock();
        const generation = self.composerGeneration(session, block);
        if (require_paste and generation != 0) {
            const entry = self.entries.get(block).?;
            if (self.composer != null or entry.write_inflight or entry.input.items.len != 0 or !pasteMode(entry)) return 0;
        }
        return generation;
    }

    fn invalidateComposerLocked(self: *Core) void {
        self.composer_gen += 1;
        if (self.composer != null) self.lock.signal();
    }

    pub fn invalidateComposer(self: *Core) void {
        self.lock.lock();
        defer self.lock.unlock();
        self.invalidateComposerLocked();
    }

    /// Only a live, restored terminal can vouch for a mode-dependent paste.
    fn pasteMode(entry: *Entry) bool {
        const stream = entry.stream orelse return false;
        return stream.live_snapshot.load(.acquire) and entry.pane.bracketedPaste();
    }

    /// Ask the terminal of `block` to open `path` for download. Blocking: returns the answer as
    /// the app reads it, `{"status":"ok"|"not_modified"|"error",…}`. Caller frees.
    /// `generation` is `downloadGeneration` when the download started: a cancel since then ends it.
    pub fn downloadBegin(self: *Core, gpa: Allocator, block: []const u8, path: []const u8, if_none_match: ?[]const u8, generation: u64) ![]u8 {
        self.lock.lock();
        defer self.lock.unlock();
        if (self.download_cancel.load(.acquire) != generation) return error.Canceled;
        if (!self.entries.contains(block)) return error.UnknownBlock;
        if (self.download != null) return error.Busy;
        const session = self.selected orelse return error.NotConnected;
        var random: [16]u8 = undefined;
        self.io.random(&random);
        var op: DownloadOp = .{ .id = self.next_id, .gpa = gpa };
        if (!self.sendRequest(protocol.methods.downloadBegin, try protocol.downloadBegin(self.gpa, session, block, random, path, if_none_match), .download))
            return error.NotConnected;
        self.download = &op;
        defer self.download = null;
        // An answer that comes after the wait gave up finds no request to match.
        defer _ = self.pending.remove(op.id);
        const epoch = self.epoch;
        const deadline = ds.nowMs(self.io) + ticket_wait_ms;
        while (op.answer == null) {
            if (self.download_cancel.load(.acquire) != generation) return error.Canceled;
            if (self.epoch != epoch or !self.ready) return error.NotConnected;
            const remaining = deadline - ds.nowMs(self.io);
            if (remaining <= 0) return error.Timeout;
            ds.timedWait(&self.lock, @intCast(remaining));
        }
        return op.answer.?;
    }

    /// Write the file of a `downloadBegin` ticket to the open file `fd` (the caller closes it).
    /// Blocking; returns the file's size. A `downloadCancel` since `generation` ends it with
    /// `error.Canceled`. `announced` is the `size` of the begin answer, when it had one: a file
    /// that turns out larger, or larger than `download.max_file_bytes`, ends with `error.TooLarge`.
    pub fn downloadFetch(self: *Core, ticket: []const u8, fd: c_int, announced: ?u64, generation: u64) !u64 {
        var target: FetchTarget = .{ .core = self, .generation = generation, .fd = fd };
        const server = try self.serverCopy();
        defer self.gpa.free(server);
        return download.fetch(self.gpa, self.io, server, self.net, ticket, .{ .ctx = &target, .write = FetchTarget.write }, .{ .ctx = &target, .active = FetchTarget.active }, announced);
    }

    /// End the stream of a `downloadBegin` ticket whose file is not wanted. Blocking.
    pub fn downloadRelease(self: *Core, ticket: []const u8, generation: u64) !void {
        var target: FetchTarget = .{ .core = self, .generation = generation, .fd = -1 };
        const server = try self.serverCopy();
        defer self.gpa.free(server);
        return download.release(self.gpa, self.io, server, self.net, ticket, .{ .ctx = &target, .active = FetchTarget.active });
    }

    fn serverCopy(self: *Core) Allocator.Error![]u8 {
        self.lock.lock();
        defer self.lock.unlock();
        return self.gpa.dupe(u8, self.server);
    }

    /// What a download started now passes to `downloadBegin` and `downloadFetch`.
    pub fn downloadGeneration(self: *Core) u64 {
        return self.download_cancel.load(.acquire);
    }

    /// End the download that `downloadBegin` or `downloadFetch` runs now, if any.
    pub fn downloadCancel(self: *Core) void {
        self.lock.lock();
        defer self.lock.unlock();
        _ = self.download_cancel.fetchAdd(1, .acq_rel);
        self.lock.signal();
    }

    // ------------------------------------------------------------ port forwards (#226)

    /// Sends `method` with `payload` (owned) and waits for the answer in `a`, at most
    /// `wait_ms`. For threads other than the control thread; called with `lock` held, which
    /// it releases while it waits. Ends without an answer when the link or the core ends,
    /// or when `stop` is set.
    fn ask(self: *Core, a: *Ask, method: []const u8, payload: []u8, stop: ?*const bool, wait_ms: i64) !void {
        a.deinit(self.gpa);
        a.id = self.next_id;
        if (!self.sendRequest(method, payload, .{ .ask = a })) return error.NotConnected;
        // An answer that comes after the wait gave up finds no request to match.
        defer _ = self.pending.remove(a.id);
        const epoch = self.epoch;
        const deadline = ds.nowMs(self.io) + wait_ms;
        while (!a.done) {
            if (self.quit) return error.Canceled;
            if (stop) |s| if (s.*) return error.Canceled;
            if (self.epoch != epoch or !self.ready) return error.NotConnected;
            const remaining = deadline - ds.nowMs(self.io);
            if (remaining <= 0) return error.Timeout;
            ds.timedWait(&self.lock, @intCast(remaining));
        }
    }

    fn findForward(self: *const Core, host: []const u8, port: u16) ?*Forward {
        for (self.forwards.items) |f| {
            if (f.port == port and std.mem.eql(u8, f.host, host)) return f;
        }
        return null;
    }

    /// Blocking, on an app thread: makes `host`:`port` of the remote, which a link in the
    /// terminal `block` names, reachable on the phone, and answers where as JSON:
    /// `{"status":"ok","port":N}` (the phone's `127.0.0.1:N`), or `{"status":"error","code",
    /// "message"}`. A forward of the same host and port is used again. The phone listens on
    /// the same port when it can, so the browser's origin is the one on the remote.
    pub fn forwardOpen(self: *Core, gpa: Allocator, block: []const u8, host: []const u8, port: u16) ![]u8 {
        const session = while (true) {
            self.lock.lock();
            if (self.findForward(host, port)) |f| {
                if (!f.gone) {
                    defer self.lock.unlock();
                    return forwardAnswer(gpa, f.local_port);
                }
                // Its block is gone: it gives its port back first.
                self.removeForward(f);
                self.release();
                self.endForward(f);
                continue;
            }
            defer self.lock.unlock();
            if (!self.entries.contains(block)) return error.UnknownBlock;
            if (!self.ready) return error.NotConnected;
            break try self.gpa.dupe(u8, self.selected orelse return error.NotConnected);
        };
        var session_owned = true;
        defer if (session_owned) self.gpa.free(session);
        var listener = listenLoopback(self.io, port) catch |err| {
            log.warn("could not listen for a forward: {s}", .{@errorName(err)});
            return forwardRefusal(gpa, "listen_failed", "Velo could not listen on a port of this phone.");
        };
        var listener_owned = true;
        defer if (listener_owned) listener.deinit(self.io);
        const local_port = listener.socket.address.getPort();

        self.lock.lock();
        defer self.release();
        var made: Ask = .{};
        defer made.deinit(self.gpa);
        try self.ask(&made, protocol.methods.newBlock, try protocol.newTunnel(self.gpa, session, host, port, local_port), null, ticket_wait_ms);
        var arena: std.heap.ArenaAllocator = .init(self.gpa);
        defer arena.deinit();
        const tunnel_block = made.string(arena.allocator(), "block_id") orelse {
            const code = made.code orelse "internal";
            // A server before API revision 14 has no tunnel blocks.
            if (std.mem.eql(u8, code, "not_found")) return forwardRefusal(gpa, "too_old", too_old_message);
            return forwardRefusal(gpa, code, made.message orelse "The remote did not make the forward.");
        };
        // Another tap made the same forward meanwhile: that one stays.
        if (self.findForward(host, port)) |f| if (!f.gone) {
            _ = self.sendRequest(protocol.methods.closeBlock, try protocol.closeBlock(self.gpa, session, tunnel_block), .close);
            return forwardAnswer(gpa, f.local_port);
        };

        const fwd = try self.gpa.create(Forward);
        fwd.* = .{
            .session = session,
            .block = self.gpa.dupe(u8, tunnel_block) catch |err| {
                self.gpa.destroy(fwd);
                return err;
            },
            .host = self.gpa.dupe(u8, host) catch |err| {
                self.gpa.free(fwd.block);
                self.gpa.destroy(fwd);
                return err;
            },
            .port = port,
            .local_port = local_port,
            .listener = listener,
        };
        session_owned = false;
        listener_owned = false;
        errdefer {
            listener_owned = true;
            fwd.destroy(self.gpa);
        }
        try self.forwards.append(self.gpa, fwd);
        errdefer _ = self.forwards.pop();
        try fwd.tasks.concurrent(self.io, acceptForwarded, .{ self, fwd });
        self.dirty = true;
        return forwardAnswer(gpa, local_port);
    }

    /// Blocking, on an app thread: ends the forward of the tunnel block `block`, its
    /// connections, and the block.
    pub fn forwardClose(self: *Core, block: []const u8) !void {
        self.lock.lock();
        const fwd = self.forwardOfBlock(block) orelse {
            self.lock.unlock();
            return error.UnknownForward;
        };
        self.removeForward(fwd);
        // A block this link cannot close now is closed by a later sweep (`forwardSweep`).
        if (protocol.closeBlock(self.gpa, fwd.session, fwd.block)) |payload| {
            _ = self.sendRequest(protocol.methods.closeBlock, payload, .close);
        } else |_| {}
        self.release();
        self.endForward(fwd);
    }

    fn forwardOfBlock(self: *const Core, block: []const u8) ?*Forward {
        for (self.forwards.items) |f| {
            if (std.mem.eql(u8, f.block, block)) return f;
        }
        return null;
    }

    /// Takes `fwd` out of `forwards` and tells its connections to end; `endForward` stops it.
    fn removeForward(self: *Core, fwd: *Forward) void {
        for (self.forwards.items, 0..) |f, i| {
            if (f == fwd) {
                _ = self.forwards.orderedRemove(i);
                break;
            }
        }
        fwd.closing = true;
        self.dirty = true;
        self.lock.signal();
    }

    /// Closes the forwards' blocks while the link is up. The core closes its link as it goes,
    /// which ends this client on the server: no later core could know them as its own.
    fn closeForwardBlocks(self: *Core) void {
        var a: Ask = .{};
        defer a.deinit(self.gpa);
        for (self.forwards.items) |f| {
            f.closing = true;
            if (!self.ready) continue;
            const payload = protocol.closeBlock(self.gpa, f.session, f.block) catch continue;
            self.ask(&a, protocol.methods.closeBlock, payload, null, 1000) catch {};
        }
    }

    /// Stops a forward no longer in `forwards`. Not under `lock`: its connections take it.
    fn endForward(self: *Core, fwd: *Forward) void {
        fwd.tasks.cancel(self.io);
        fwd.listener.deinit(self.io);
        fwd.destroy(self.gpa);
    }

    /// Blocking, on an app thread: closes the tunnel blocks that this client left in the
    /// sessions, such as the forwards of an app that was stopped. Blocks of other clients,
    /// and of this core's forwards, stay.
    pub fn forwardSweep(self: *Core) !void {
        const gpa = self.gpa;
        self.lock.lock();
        defer self.release();
        if (!self.ready) return error.NotConnected;
        // Copied: the list may change while the core waits for answers.
        var sessions: std.ArrayList([]u8) = .empty;
        defer {
            for (sessions.items) |id| gpa.free(id);
            sessions.deinit(gpa);
        }
        for (self.sessions.items) |s| {
            const id = try gpa.dupe(u8, s.id);
            sessions.append(gpa, id) catch |err| {
                gpa.free(id);
                return err;
            };
        }
        var blocks_answer: Ask = .{};
        defer blocks_answer.deinit(gpa);
        var info: Ask = .{};
        defer info.deinit(gpa);
        for (sessions.items) |session| {
            try self.ask(&blocks_answer, protocol.methods.listBlocks, try protocol.sessionView(gpa, session), null, ticket_wait_ms);
            var arena: std.heap.ArenaAllocator = .init(gpa);
            defer arena.deinit();
            const value = json.parseFromSliceLeaky(json.Value, arena.allocator(), blocks_answer.payload orelse continue, .{}) catch continue;
            const blocks = model.get(value, "blocks") orelse continue;
            if (blocks != .array) continue;
            for (blocks.array.items) |b| {
                if (!std.mem.eql(u8, model.str(model.get(b, "flavor")) orelse "", protocol.tunnel_dial)) continue;
                const id = model.str(model.get(b, "block_id")) orelse continue;
                if (self.forwardOfBlock(id) != null) continue;
                try self.ask(&info, protocol.methods.tunnelInfo, try protocol.blockCall(gpa, session, id), null, ticket_wait_ms);
                if (!self.isMe(info.string(arena.allocator(), "last_owner"))) continue;
                // A tap may have made it a forward while the core waited.
                if (self.forwardOfBlock(id) != null) continue;
                _ = self.sendRequest(protocol.methods.closeBlock, try protocol.closeBlock(gpa, session, id), .close);
            }
        }
    }

    /// The forward's accept loop, until the forward ends.
    fn acceptForwarded(self: *Core, fwd: *Forward) std.Io.Cancelable!void {
        while (true) {
            const local = fwd.listener.accept(self.io) catch |err| switch (err) {
                error.Canceled => return error.Canceled,
                else => {
                    // Such as no file descriptors left: try again in a moment.
                    try std.Io.sleep(self.io, .fromMilliseconds(100), .awake);
                    continue;
                },
            };
            // Counted here, not in its task: the next accept must already see this one.
            if (!self.admitConnection(fwd)) {
                tunnel.reset(self.io, local);
                continue;
            }
            fwd.tasks.concurrent(self.io, serveForwarded, .{ self, fwd, local }) catch {
                self.countConnection(fwd, -1);
                tunnel.reset(self.io, local);
            };
        }
    }

    /// Counts one more connection of `fwd`; false when it serves `forward_connections_max` already.
    fn admitConnection(self: *Core, fwd: *Forward) bool {
        {
            self.lock.lock();
            defer self.lock.unlock();
            if (fwd.connections >= forward_connections_max) return false;
        }
        self.countConnection(fwd, 1);
        return true;
    }

    /// One connection the forward accepted and counted: a stream of its block, then the relay.
    fn serveForwarded(self: *Core, fwd: *Forward, local: std.Io.net.Stream) void {
        defer self.countConnection(fwd, -1);
        var buffer: [ds.max_ticket_len]u8 = undefined;
        const ticket = self.forwardTicket(fwd, &buffer) catch |err| {
            log.info("no stream for a forwarded connection: {s}", .{@errorName(err)});
            return tunnel.reset(self.io, local);
        };
        const server = self.serverCopy() catch return tunnel.reset(self.io, local);
        defer self.gpa.free(server);
        const ws = tunnel.open(self.gpa, self.io, server, self.net, ticket) catch |err| {
            log.info("could not open a forwarded connection's stream: {s}", .{@errorName(err)});
            if (err == error.NotOwner) {
                self.lock.lock();
                fwd.claimed = false;
                self.lock.unlock();
            }
            return tunnel.reset(self.io, local);
        };
        defer ws.deinit();
        if (tunnel.relay(self.io, ws, local) == .not_owner) {
            // Another client took the block: the next connection takes it back.
            self.lock.lock();
            fwd.claimed = false;
            self.lock.unlock();
        }
    }

    fn countConnection(self: *Core, fwd: *Forward, change: i2) void {
        self.lock.lock();
        if (change > 0) fwd.connections += 1 else fwd.connections -= 1;
        self.dirty = true;
        self.release();
    }

    /// A ticket for a new stream of the forward's block, in `out`. Attaches the block's
    /// session and takes the block first when this link has not.
    fn forwardTicket(self: *Core, fwd: *Forward, out: *[ds.max_ticket_len]u8) ![]const u8 {
        const gpa = self.gpa;
        self.lock.lock();
        defer self.lock.unlock();
        if (fwd.closing) return error.Canceled;
        if (fwd.gone) return error.Gone;
        if (!self.ready) return error.NotConnected;
        var a: Ask = .{};
        defer a.deinit(gpa);
        if (!fwd.claimed) {
            // `data.connect` needs the session attached; the selected one is.
            if (!self.isSelected(fwd.session)) {
                try self.ask(&a, protocol.methods.attach, try protocol.sessionView(gpa, fwd.session), &fwd.closing, ticket_wait_ms);
                if (a.payload == null) return self.refusedStream(fwd, &a);
            }
            try self.ask(&a, protocol.methods.ownBlock, try protocol.ownBlock(gpa, fwd.session, fwd.block), &fwd.closing, ticket_wait_ms);
            if (a.payload == null) return self.refusedStream(fwd, &a);
            // An answer of the link that just ended: the next link has taken nothing.
            if (!self.ready) return error.NotConnected;
            fwd.claimed = true;
        }
        var random: [8]u8 = undefined;
        self.io.random(&random);
        var ref: [21]u8 = undefined;
        const name = std.fmt.bufPrint(&ref, "conn:{x}", .{random[0..]}) catch unreachable;
        try self.ask(&a, protocol.methods.dataConnect, try protocol.streamConnect(gpa, fwd.session, fwd.block, name, tunnel.subprotocol), &fwd.closing, ticket_wait_ms);
        var arena: std.heap.ArenaAllocator = .init(gpa);
        defer arena.deinit();
        const ticket = a.string(arena.allocator(), "ticket") orelse return self.refusedStream(fwd, &a);
        if (ticket.len > out.len) return error.Refused;
        @memcpy(out[0..ticket.len], ticket);
        return out[0..ticket.len];
    }

    /// What a refused step of `forwardTicket` says about the forward.
    fn refusedStream(self: *Core, fwd: *Forward, a: *const Ask) error{Refused} {
        _ = self;
        const code = a.code orelse "";
        // The session is not attached any more: attach it next time.
        if (std.mem.eql(u8, code, "conflict")) fwd.claimed = false;
        // No such block or session (`own_block` of a block the session lacks is a bad request).
        if (std.mem.eql(u8, code, "not_found") or std.mem.eql(u8, code, "session_not_found") or std.mem.eql(u8, code, "bad_request"))
            fwd.gone = true;
        return error.Refused;
    }

    /// A blocking, one-shot send. The caller holds the Core alive and supplies immutable
    /// arguments until return. No network I/O takes the core lock or runs on the UI thread.
    pub fn sendComposer(self: *Core, generation: u64, session: []const u8, block: []const u8, text: []const u8, name: ?[]const u8, data: ?[]const u8) !void {
        if ((name == null) != (data == null) or (data == null and text.len == 0)) return error.EmptyDraft;
        if (name) |n| try protocol.validateUploadName(n);
        if (data) |bytes| if (bytes.len > upload.max_bytes) return error.FileTooLarge;

        self.lock.lock();
        defer self.lock.unlock();
        // Nothing is written yet: the app may say so, unlike a TargetChanged that ends a write's wait.
        if (generation == 0 or generation != self.composerGeneration(session, block)) return error.TargetChangedBeforeWrite;
        const entry = self.entries.get(block).?;
        if (self.composer != null or entry.write_inflight or entry.input.items.len != 0) return error.InputBusy;
        var op: Composer = .{ .core = self, .generation = generation, .session = session, .block = block };
        self.composer = &op;
        defer {
            self.composer = null;
            if (self.entries.get(block)) |remaining| {
                if (generation == self.composerGeneration(session, block)) self.flushInput(remaining) else remaining.input.clearRetainingCapacity();
            }
        }

        if (text.len != 0) {
            op.paste = pasteMode(entry);
            const input = try protocol.composerInput(self.gpa, text, data != null, op.paste);
            defer self.gpa.free(input);
            op.expected_bytes = input.len;
            try self.composerRequest(&op, protocol.methods.terminalWrite, try protocol.terminalWrite(self.gpa, session, block, input));
        }
        if (data) |bytes| {
            var random: [16]u8 = undefined;
            self.io.random(&random);
            op.expected_bytes = null;
            try self.composerRequest(&op, protocol.methods.uploadBegin, try protocol.uploadBegin(self.gpa, session, block, random, name.?, bytes.len));
            const server = try self.gpa.dupe(u8, self.server);
            defer self.gpa.free(server);
            self.lock.unlock();
            const sent = upload.send(self.gpa, self.io, server, self.net, op.ticket[0..op.ticket_len], bytes, .{ .ctx = &op, .active = Composer.active });
            self.lock.lock();
            if (generation != self.composerGeneration(session, block)) return error.TargetChanged;
            try sent;
        }
    }

    /// A single guarded slot in the original control writer; abandoning a wait drops
    /// an unsent frame, and a late answer has no pending request to match.
    fn composerRequest(self: *Core, op: *Composer, method: []const u8, payload: []u8) !void {
        defer self.gpa.free(payload);
        const id = self.next_id;
        self.next_id += 1;
        try self.pending.put(self.gpa, id, .composer);
        defer _ = self.pending.remove(id);
        op.reply = .waiting;
        op.unsent = try protocol.request(self.gpa, id, method, payload);
        defer if (op.unsent) |s| {
            self.gpa.free(s);
            op.unsent = null;
        };
        self.lock.signal();
        const deadline = ds.nowMs(self.io) + ticket_wait_ms;
        while (op.generation == self.composerGeneration(op.session, op.block)) {
            switch (op.reply) {
                .confirmed => return,
                .refused => return error.ServerRefused,
                .invalid => return error.Unconfirmed,
                .paste_changed => return error.PasteModeRequired,
                .waiting => {},
            }
            const remaining = deadline - ds.nowMs(self.io);
            if (remaining <= 0) return error.Timeout;
            ds.timedWait(&self.lock, @intCast(remaining));
        }
        return error.TargetChanged;
    }

    fn takeComposerFrame(self: *Core) ?[]u8 {
        const op = self.composer orelse return null;
        const frame = op.unsent orelse return null;
        op.unsent = null;
        if (op.generation != self.composerGeneration(op.session, op.block)) {
            self.gpa.free(frame);
            return null;
        }
        if (op.expected_bytes != null and op.paste and !pasteMode(self.entries.get(op.block).?)) {
            self.gpa.free(frame);
            op.reply = .paste_changed;
            self.lock.signal();
            return null;
        }
        return frame;
    }

    // ------------------------------------------------------------ host delivery

    /// Republish a complete baseline, even when the model has not changed. Like other
    /// core calls, this must not be made synchronously from a host callback.
    pub fn requestState(self: *Core) void {
        self.lock.lock();
        defer self.release();
        self.full_from_seq = self.state_seq + 1;
        self.dirty = true;
    }

    /// Unlock `lock`, then deliver what was collected under it: errors, a new
    /// reconnect token, and any new state. Compare states in delivery order.
    fn release(self: *Core) void {
        const state = if (self.dirty and !self.quit) blk: {
            pane.Trace.begin("Core.stateSnapshot");
            defer pane.Trace.end();
            break :blk self.stateSnapshot() catch null;
        } else null;
        if (state != null) {
            self.dirty = false;
            self.state_seq += 1;
        }
        const seq = self.state_seq;
        const full_from = self.full_from_seq;
        var notes = self.notes;
        self.notes = .empty;
        const deliver = !self.quit;
        self.lock.unlock();

        defer self.freeNotes(&notes);
        if (!deliver or (state == null and notes.items.len == 0)) return;

        self.host_lock.lock();
        defer self.host_lock.unlock();
        const host = self.host;
        for (notes.items) |note| switch (note) {
            .pane_report => |r| if (host.onPaneReport) |callback| callback(host.ctx, r),
            .pane_write => |w| if (host.onPaneWrite) |callback| callback(host.ctx, w.text[0..w.tag_len], w.result, w.text[w.tag_len..]),
            .pane_screen => |s| if (host.onPaneScreen) |callback| callback(host.ctx, s.text[0..s.tag_len], s.text[s.tag_len..]),
            .token => |t| host.onReconnectToken(host.ctx, t),
            .err => |e| host.onError(host.ctx, e.code, e.message, e.fix),
        };
        if (state) |snapshot| self.publishState(snapshot, seq, full_from);
    }

    /// Consumes snapshot under host_lock. Generation counts publications, not captures.
    fn publishState(self: *Core, snapshot: model.StateSnapshot, seq: u64, full_from: u64) void {
        pane.Trace.begin("Core.publishState");
        defer pane.Trace.end();
        if (seq <= self.delivered_seq) return snapshot.deinit(self.gpa);
        const force_full = full_from > self.delivered_seq;
        self.delivered_seq = seq;
        var patch: ?[]const u8 = null;
        if (self.last_state) |last| {
            if (!force_full) {
                // A full snapshot is already available if the scratch buffer cannot grow.
                const changed = (snapshot.delta(last, self.gpa, &self.patch_buf) catch snapshot.text) orelse
                    return snapshot.deinit(self.gpa);
                if (changed.ptr != snapshot.text.ptr) patch = changed;
            }
            last.deinit(self.gpa);
        }
        self.last_state = snapshot;
        self.state_gen += 1;
        self.host.onState(self.host.ctx, self.state_gen, snapshot.text, patch);
    }

    fn stateSnapshot(self: *Core) !model.StateSnapshot {
        var arena: std.heap.ArenaAllocator = .init(self.gpa);
        defer arena.deinit();
        const a = arena.allocator();
        // Each session with the view the core holds for it.
        const sessions = try a.alloc(model.Session, self.sessions.items.len);
        for (self.sessions.items, sessions) |s, *out| out.* = .{ .id = s.id, .label = s.label, .view = self.viewOf(s.id) };
        const forwards = try a.alloc(model.ForwardInfo, self.forwards.items.len);
        for (self.forwards.items, forwards) |f, *out| out.* = .{
            .block = f.block,
            .host = f.host,
            .port = f.port,
            .local_port = f.local_port,
            .connections = f.connections,
        };
        var leads: std.StringHashMapUnmanaged(model.BlockInfo) = .empty;
        // The selected session's minimized panes are named from theirs (#233), the followed
        // sessions' other panes by what they run (#226).
        for ([_]*const std.StringHashMapUnmanaged(*Lead){ &self.stashed, &self.leads, &self.beside }) |map| {
            var it = map.valueIterator();
            while (it.next()) |l| try leads.put(a, l.*.id, .{
                .title = l.*.title,
                .program = l.*.program,
                .agent = l.*.agent,
                .tab_report = l.*.tab_report,
                .cwd = l.*.cwd,
                .activity = l.*.activity,
                .since = l.*.since,
            });
        }
        return model.stateSnapshot(self.gpa, .{
            .status = self.status,
            .server = self.server,
            .url = self.url,
            .account = self.account,
            .hosts = self.hosts,
            .hosts_hash = self.hosts_hash,
            .sessions = sessions,
            .session = self.selected,
            .view = if (self.view) |*v| v else null,
            .blocks = &self.blocks,
            .leads = &leads,
            .pane_watches = if (self.pane_watches) |watches| watches.targets.value else &.{},
            .forwards = forwards,
        });
    }

    fn freeNotes(self: *Core, notes: *std.ArrayList(Note)) void {
        const gpa = self.gpa;
        for (notes.items) |note| switch (note) {
            .pane_report => |r| gpa.free(r),
            .pane_write => |w| gpa.free(w.text),
            .pane_screen => |s| gpa.free(s.text),
            .token => |t| gpa.free(t),
            .err => |e| {
                gpa.free(e.code);
                gpa.free(e.message);
                gpa.free(e.fix);
            },
        };
        notes.deinit(gpa);
        notes.* = .empty;
    }

    fn noteError(self: *Core, code: []const u8, message: []const u8) void {
        self.noteFailure(code, message, "");
    }

    /// `noteError` with the command that fixes it ("" for none).
    fn noteFailure(self: *Core, code: []const u8, message: []const u8, fix: []const u8) void {
        const gpa = self.gpa;
        const code_copy = gpa.dupe(u8, code) catch return;
        const message_copy = gpa.dupe(u8, message) catch {
            gpa.free(code_copy);
            return;
        };
        const fix_copy = gpa.dupe(u8, fix) catch {
            gpa.free(code_copy);
            gpa.free(message_copy);
            return;
        };
        self.notes.append(gpa, .{ .err = .{ .code = code_copy, .message = message_copy, .fix = fix_copy } }) catch {
            gpa.free(code_copy);
            gpa.free(message_copy);
            gpa.free(fix_copy);
        };
    }

    fn noteResponseError(self: *Core, e: protocol.ResponseError) void {
        self.noteError(e.code, e.message);
    }

    // ---------------------------------------------------------------- settings

    fn applySetting(self: *Core, name: []const u8, value: json.Value) void {
        if (std.mem.eql(u8, name, "fontSize")) {
            const sp = model.num(value) orelse return;
            if (!std.math.isFinite(sp) or sp <= 0) return;
            self.font_sp = @floatCast(std.math.clamp(sp, 4, 96));
            var it = self.entries.valueIterator();
            while (it.next()) |entry| {
                entry.*.pane.setFontSizePx(self.font_sp * self.px_per_sp);
                self.sizeStep(entry.*, .fit);
            }
        } else if (std.mem.eql(u8, name, "display")) {
            // The display's scale changed while the app ran (Display size, Font size): px per sp
            // and the pane padding in px change together, and every pane is fitted once.
            if (value != .object) return;
            const scale = model.num(value.object.get("pxPerSp")) orelse return;
            const px = model.num(value.object.get("panePaddingPx")) orelse return;
            if (!std.math.isFinite(scale) or scale <= 0 or !std.math.isFinite(px) or px < 0) return;
            self.px_per_sp = @floatCast(scale);
            self.pad_px = @floatCast(px);
            var it = self.entries.valueIterator();
            while (it.next()) |entry| {
                entry.*.pane.setFontSizePx(self.font_sp * self.px_per_sp);
                entry.*.pane.setPaddingPx(self.pad_px);
                self.sizeStep(entry.*, .fit);
            }
        } else if (std.mem.eql(u8, name, "fontLineHeight")) {
            // Sent after the host switched fonts: every pane redraws its glyphs.
            const em = model.num(value) orelse return;
            if (!std.math.isFinite(em) or em <= 0) return;
            self.line_height = @floatCast(em);
            var it = self.entries.valueIterator();
            while (it.next()) |entry| {
                entry.*.pane.setFont(self.line_height);
                self.sizeStep(entry.*, .fit);
            }
        } else if (std.mem.eql(u8, name, "panePaddingPx")) {
            const px = model.num(value) orelse return;
            if (!std.math.isFinite(px) or px < 0) return;
            self.pad_px = @floatCast(px);
            var it = self.entries.valueIterator();
            while (it.next()) |entry| {
                entry.*.pane.setPaddingPx(self.pad_px);
                self.sizeStep(entry.*, .fit);
            }
        }
    }

    // ------------------------------------------------------------- connection

    /// Nothing to connect to.
    fn clearTarget(self: *Core) void {
        const gpa = self.gpa;
        gpa.free(self.url);
        gpa.free(self.server);
        if (self.token) |t| gpa.free(t);
        self.url = &.{};
        self.server = &.{};
        self.token = null;
    }

    /// Replace the server to connect to: `locator`, trimmed.
    fn setTarget(self: *Core, locator: []const u8, token: ?[]const u8) bool {
        const gpa = self.gpa;
        const trimmed = std.mem.trim(u8, locator, " \t\r\n");
        const url_copy = gpa.dupe(u8, trimmed) catch return false;
        const server_copy = gpa.dupe(u8, trimmed) catch {
            gpa.free(url_copy);
            return false;
        };
        const token_copy: ?[]u8 = if (token) |t| (if (t.len == 0) null else gpa.dupe(u8, t) catch {
            gpa.free(url_copy);
            gpa.free(server_copy);
            return false;
        }) else null;
        gpa.free(self.url);
        gpa.free(self.server);
        if (self.token) |t| gpa.free(t);
        self.url = url_copy;
        self.server = server_copy;
        self.token = token_copy;
        return true;
    }

    const Outcome = enum {
        /// connect/disconnect/destroy took over.
        replaced,
        /// Never reached a bootstrap on this connection.
        failed,
        /// Dropped after a successful bootstrap.
        dropped,
        /// Never reached a bootstrap, for a reason retrying cannot fix
        /// (`permanentFailure`): wait for connect instead.
        stopped,
    };

    fn controlMain(self: *Core) void {
        pane.Trace.nameThread("velo-control", "");
        const gpa = self.gpa;
        var attempt: u32 = 0;
        while (true) {
            self.lock.lock();
            while (!self.quit and self.server.len == 0) self.lock.wait();
            if (self.quit) {
                self.lock.unlock();
                return;
            }
            const epoch = self.epoch;
            const locator = gpa.dupe(u8, self.server) catch null;
            const token: ?[]u8 = if (self.token) |t| gpa.dupe(u8, t) catch null else null;
            self.lock.unlock();
            defer if (token) |t| gpa.free(t);
            defer if (locator) |l| gpa.free(l);

            const outcome: Outcome = if (locator) |l| self.runConnection(epoch, l, token) else .failed;

            self.lock.lock();
            if (self.quit) {
                self.lock.unlock();
                return;
            }
            if (outcome == .replaced or self.epoch != epoch) {
                attempt = 0;
                self.release();
                continue;
            }
            if (outcome == .stopped) {
                // `connectFailedAs` stopped the core with its note (same epoch, so it did):
                // no backoff and no data stream until connect (Reconnect), disconnect or
                // destroy changes the epoch.
                std.debug.assert(self.stopped == epoch);
                self.release();
                self.lock.lock();
                while (!self.quit and self.epoch == epoch) self.lock.wait();
                self.lock.unlock();
                attempt = 0;
                continue;
            }
            if (outcome == .dropped) attempt = 0;
            self.status = if (self.ever_connected) .reconnecting else .@"error";
            self.dirty = true;
            self.release();

            const delay = backoffMs(attempt);
            attempt +|= 1;
            self.sleep(epoch, delay);
        }
    }

    /// Wait `ms`, or until connect/disconnect/destroy.
    fn sleep(self: *Core, epoch: u64, ms: u32) void {
        const deadline = ds.nowMs(self.io) + ms;
        self.lock.lock();
        defer self.lock.unlock();
        while (!self.quit and self.epoch == epoch) {
            const now = ds.nowMs(self.io);
            if (now >= deadline) return;
            ds.timedWait(&self.lock, @intCast(@min(deadline - now, 1000)));
        }
    }

    fn runConnection(self: *Core, epoch: u64, locator: []const u8, token: ?[]const u8) Outcome {
        var http_status: u16 = 0;
        // Why an ssh connection failed, in the SSH pool's words (4 KiB, this thread's stack).
        var problem: SshProblem = .{};
        const ws = WebSocket.connectStatus(self.gpa, self.io, locator, "/api/control", protocol.control_protocol, self.net, &http_status, &problem) catch |err| {
            if (problem.code().len > 0) {
                // A failure that stops the core is noted even after a first connection:
                // nothing would report it later.
                const stop = permanentFailure(problem.code());
                self.connectFailedAs(epoch, problem.code(), problem.sentence(), problem.fix(), stop);
                return if (stop) .stopped else .failed;
            }
            var buf: [128]u8 = undefined;
            const message = if (err == error.UpgradeRejected)
                std.fmt.bufPrint(&buf, "the server refused the connection (HTTP {d})", .{http_status}) catch "the server refused the connection"
            else if (err == error.NameTooLong)
                // Only a Unix socket path gives it (`UnixAddress.init`).
                socket_path_too_long
            else
                std.fmt.bufPrint(&buf, "could not reach the server ({s})", .{@errorName(err)}) catch "could not reach the server";
            self.connectFailed(epoch, message);
            return .failed;
        };

        self.lock.lock();
        if (self.quit or self.epoch != epoch) {
            self.lock.unlock();
            ws.close();
            ws.deinit();
            return .replaced;
        }
        self.ws = ws;
        self.lock.unlock();

        var writer: ?std.Thread = null;
        var reason_buf: [160]u8 = undefined;
        var reason: []const u8 = "";
        const got_ready = self.serve(ws, epoch, token, &writer, &reason_buf, &reason);

        self.lock.lock();
        self.linkEnded();
        const replaced = self.quit or self.epoch != epoch;
        self.lock.signal();
        self.release();

        ws.close();
        if (writer) |t| t.join();
        ws.deinit();

        if (replaced) return .replaced;
        if (got_ready) return .dropped;
        self.connectFailed(epoch, reason);
        return .failed;
    }

    fn connectFailed(self: *Core, epoch: u64, message: []const u8) void {
        self.connectFailedAs(epoch, "connect_failed", if (message.len > 0) message else "connection closed", "", false);
    }

    /// The note of a connection that never reached a bootstrap, with its own code and fix:
    /// only before the first bootstrap for this server, unless `stop`. With `stop` (a
    /// failure retrying cannot fix) the core stops (`stopped`) in the same step, before
    /// `onError` runs: a data stream that dials during the callback would start a new login.
    fn connectFailedAs(self: *Core, epoch: u64, code: []const u8, message: []const u8, fix: []const u8, stop: bool) void {
        self.lock.lock();
        if (!self.quit and self.epoch == epoch) {
            if (stop) {
                self.stopped = epoch;
                self.status = .@"error";
                self.dirty = true;
            }
            if (stop or !self.ever_connected) self.noteFailure(code, message, fix);
        }
        self.release();
    }

    /// Hello, welcome, bootstrap, then the receive loop. Returns whether the
    /// connection reached a successful bootstrap; `reason` says why it ended.
    fn serve(
        self: *Core,
        ws: *WebSocket,
        epoch: u64,
        token: ?[]const u8,
        writer: *?std.Thread,
        reason_buf: *[160]u8,
        reason: *[]const u8,
    ) bool {
        const gpa = self.gpa;
        var arena: std.heap.ArenaAllocator = .init(gpa);
        defer arena.deinit();

        const hello = protocol.hello(gpa, client_version, token) catch |err| switch (err) {
            // Without the token the server starts a new client.
            error.HelloTooLarge => protocol.hello(gpa, client_version, null) catch return false,
            error.OutOfMemory => return false,
        };
        defer gpa.free(hello);
        ws.sendText(hello) catch |err| {
            reason.* = @errorName(err);
            return false;
        };

        const first = (ws.recv() catch |err| {
            reason.* = @errorName(err);
            return false;
        }) orelse {
            reason.* = closeReason(ws, reason_buf);
            return false;
        };
        const welcome = switch (protocol.parse(arena.allocator(), first.data) catch .other) {
            .welcome => |w| w,
            else => {
                reason.* = "the server did not send a welcome";
                return false;
            },
        };

        // Bootstrap is the first request; the writer thread starts after it is sent.
        self.lock.lock();
        if (self.quit or self.epoch != epoch) {
            self.lock.unlock();
            return false;
        }
        self.setClient(welcome.client_id);
        if (welcome.reconnect_token.len > 0) self.storeToken(welcome.reconnect_token);
        const id = self.next_id;
        self.next_id += 1;
        const request: ?[]u8 = blk: {
            const payload = protocol.bootstrap(gpa, self.bootstrapWanted()) catch break :blk null;
            defer gpa.free(payload);
            const text = protocol.request(gpa, id, protocol.methods.bootstrap, payload) catch break :blk null;
            self.pending.put(gpa, id, .bootstrap) catch {
                gpa.free(text);
                break :blk null;
            };
            break :blk text;
        };
        self.release();
        const text = request orelse return false;
        defer gpa.free(text);
        ws.sendText(text) catch |err| {
            reason.* = @errorName(err);
            return false;
        };

        self.lock.lock();
        self.link_up = true;
        self.last_rx_ms = ds.nowMs(self.io);
        self.probed = false;
        self.lock.unlock();
        writer.* = std.Thread.spawn(.{}, writerMain, .{ self, ws }) catch {
            reason.* = "out of threads";
            return false;
        };

        var got_ready = false;
        while (true) {
            const message = (ws.recv() catch |err| {
                reason.* = @errorName(err);
                return got_ready;
            }) orelse {
                reason.* = closeReason(ws, reason_buf);
                return got_ready;
            };
            if (message.opcode != .text) continue;
            _ = arena.reset(.{ .retain_with_limit = 1024 * 1024 });
            // A message this client cannot read is skipped, like an unknown type.
            const incoming = protocol.parse(arena.allocator(), message.data) catch continue;

            self.lock.lock();
            if (self.quit or self.epoch != epoch) {
                self.lock.unlock();
                return got_ready;
            }
            self.last_rx_ms = ds.nowMs(self.io);
            self.probed = false;
            self.handleIncoming(incoming);
            got_ready = got_ready or self.ready;
            const drop = self.drop_link;
            if (drop) {
                const n = @min(self.drop_reason.len, reason_buf.len);
                @memcpy(reason_buf[0..n], self.drop_reason[0..n]);
                reason.* = reason_buf[0..n];
            }
            self.release();
            if (drop) return got_ready;
        }
    }

    fn closeReason(ws: *WebSocket, buf: *[160]u8) []const u8 {
        if (ws.close_code == 0) return "connection closed";
        return std.fmt.bufPrint(buf, "closed by the server ({d} {s})", .{ ws.close_code, ws.close_reason }) catch "closed by the server";
    }

    fn storeToken(self: *Core, token: []const u8) void {
        const gpa = self.gpa;
        const copy = gpa.dupe(u8, token) catch return;
        if (self.token) |t| gpa.free(t);
        self.token = copy;
        const note = gpa.dupe(u8, token) catch return;
        self.notes.append(gpa, .{ .token = note }) catch gpa.free(note);
    }

    /// Sends queued requests so that API calls never block on the socket, and watches
    /// for a link that went silent.
    fn writerMain(self: *Core, ws: *WebSocket) void {
        pane.Trace.nameThread("velo-writer", "");
        const gpa = self.gpa;
        while (true) {
            self.lock.lock();
            while (self.link_up and self.outbox.items.len == 0) {
                if (self.takeComposerFrame()) |frame| {
                    self.lock.unlock();
                    defer gpa.free(frame);
                    ws.sendText(frame) catch {
                        dropLink(ws);
                        return;
                    };
                    self.lock.lock();
                    continue;
                }
                const quiet = ds.nowMs(self.io) - self.last_rx_ms;
                if (quiet >= link_dead_ms) {
                    self.lock.unlock();
                    dropLink(ws);
                    return;
                }
                if (quiet >= link_probe_ms and self.ready and !self.probed) {
                    self.probed = true;
                    self.requestList();
                    continue;
                }
                const next = if (quiet < link_probe_ms and self.ready) link_probe_ms - quiet else link_dead_ms - quiet;
                ds.timedWait(&self.lock, @intCast(next));
            }
            if (!self.link_up) {
                self.lock.unlock();
                return;
            }
            var batch = self.outbox;
            self.outbox = .empty;
            self.lock.unlock();

            var failed = false;
            for (batch.items) |text| {
                if (!failed) ws.sendText(text) catch {
                    failed = true;
                };
                gpa.free(text);
            }
            batch.deinit(gpa);
            if (failed) {
                dropLink(ws);
                return;
            }
        }
    }

    /// Make the control thread's recv fail, which tears the link down. No close
    /// frame: the server keeps the client for a reconnect with the token.
    fn dropLink(ws: *WebSocket) void {
        if (ws.conn) |conn| conn.shutdown() else ws.close();
    }

    /// Forget everything tied to the control connection that just ended.
    fn clearLink(self: *Core) void {
        const gpa = self.gpa;
        for (self.outbox.items) |text| gpa.free(text);
        self.outbox.clearRetainingCapacity();
        var it = self.pending.valueIterator();
        while (it.next()) |p| {
            switch (p.*) {
                // Nothing was written: the status check or the process check never answered.
                .pane_write_check, .pane_write_owner => |op| self.notePaneWrite(op.write.tag, .not_sent, "the connection to the server dropped"),
                // The server may or may not have run the write.
                .pane_write => |w| self.notePaneWrite(w.tag, .unconfirmed, "the connection to the server dropped"),
                .pane_screen, .pane_screen_tail => |op| self.notePaneScreen(op.tag, ""),
                else => {},
            }
            freePending(gpa, p.*);
        }
        self.pending.clearRetainingCapacity();
        self.clearDockTab();
        self.forgetMove();
        if (self.pane_watches) |watches| for (watches.items) |*w| w.reset(gpa);
        self.view_inflight = false;
        self.view_again = false;
        var follows = self.follows.valueIterator();
        while (follows.next()) |f| {
            f.*.inflight = false;
            f.*.again = false;
            f.*.asked = false;
        }
        for ([_]*std.StringHashMapUnmanaged(*Lead){ &self.leads, &self.stashed, &self.beside }) |map| {
            var leads = map.valueIterator();
            while (leads.next()) |l| {
                l.*.asked = false;
                l.*.process = .{};
                l.*.status = .{};
                l.*.title_refresh = .{};
            }
        }
        self.resume_pending = false;
        self.refresh_pending = false;
        var entries = self.entries.valueIterator();
        while (entries.next()) |e| {
            const entry = e.*;
            entry.preedit_ask = null;
            _ = entry.pane.hidePreedit();
            if (entry.ticket_state == .waiting) entry.ticket_state = .failed;
            // The server may or may not have run a write that was in flight; typing
            // it again could run a command twice.
            entry.write_inflight = false;
            entry.input.clearRetainingCapacity();
            entry.size.linkLost();
        }
    }

    fn freePending(gpa: Allocator, p: Pending) void {
        switch (p) {
            .attach => |a| gpa.free(a.session),
            .follow_view, .window_moved, .stash_floated => |session| gpa.free(session),
            .pane_write_check, .pane_write_owner => |op| op.destroy(gpa),
            .pane_write => |w| gpa.free(w.tag),
            .pane_screen, .pane_screen_tail => |op| op.free(gpa),
            .pane_moved => |m| {
                gpa.free(m.session);
                gpa.free(m.block);
            },
            else => {},
        }
    }

    /// Queue a request. Takes ownership of `payload` (JSON object text, may be empty).
    fn sendRequest(self: *Core, method: []const u8, payload: []const u8, pending: Pending) bool {
        const gpa = self.gpa;
        defer gpa.free(payload);
        if (!self.ready) return false;
        const id = self.next_id;
        const text = protocol.request(gpa, id, method, payload) catch return false;
        self.pending.put(gpa, id, pending) catch {
            gpa.free(text);
            return false;
        };
        self.outbox.append(gpa, text) catch {
            _ = self.pending.remove(id);
            gpa.free(text);
            return false;
        };
        self.next_id += 1;
        self.lock.signal();
        return true;
    }

    /// The selected session when a user command can be sent now; else reports why not.
    fn sessionForCommand(self: *Core) ?[]const u8 {
        if (self.ready) if (self.selected) |s| return s;
        self.noteError("not_connected", if (self.ready) "no session selected" else "not connected to the server");
        return null;
    }

    /// The session a tab command acts on: `wanted`, selected first when it is not, else
    /// the selected session. Reports why a command cannot be sent now.
    fn sessionFor(self: *Core, wanted: ?[]const u8) ?[]const u8 {
        const session = wanted orelse return self.sessionForCommand();
        if (!self.ready) {
            self.noteError("not_connected", "not connected to the server");
            return null;
        }
        if (!self.exists(session)) return null;
        if (!self.isSelected(session)) {
            if (self.selected) |old| self.detach(old);
            self.auto_pick = true;
            self.attach(session);
        }
        return self.selected;
    }

    /// A user command that names its session: sent whichever session is selected. One
    /// that names a session which does not exist for this core is dropped.
    /// `pending` may own memory: it is freed here whenever nothing is sent.
    fn sendFor(self: *Core, session: []const u8, method: []const u8, payload: Allocator.Error![]u8, pending: Pending) void {
        const text = payload catch return freePending(self.gpa, pending);
        if (!self.ready) {
            self.gpa.free(text);
            freePending(self.gpa, pending);
            return self.noteError("not_connected", "not connected to the server");
        }
        if (!self.exists(session)) {
            self.gpa.free(text);
            return freePending(self.gpa, pending);
        }
        if (!self.sendRequest(method, text, pending)) freePending(self.gpa, pending);
    }

    fn sendCommand(self: *Core, method: []const u8, payload: Allocator.Error![]u8) void {
        const text = payload catch return;
        _ = self.sendRequest(method, text, .command);
    }

    // ------------------------------------------------------------- pane moves
    //
    // The app chose a move from a layout that may have changed since, so each move is
    // checked against the held view right before it is sent, and refused without sending
    // when the held view no longer allows it. Nothing is replayed on an error or a new
    // connection.

    const layout_changed = "the layout changed; drag it again";
    const undo_stale = "the layout changed; Undo is no longer possible";

    fn refuseMove(self: *Core, undo: bool) void {
        self.noteError("conflict", if (undo) undo_stale else layout_changed);
    }

    /// Nothing is undoable any more: a pane move or an Undo starts, or the view or the
    /// connection of the last move is gone. Answers still on their way count for nothing.
    fn forgetMove(self: *Core) void {
        self.move_serial += 1;
        self.undo_revision = null;
    }

    /// The serial that the answers of a pane move about to be sent carry; 0 for an Undo,
    /// whose answers count for nothing.
    fn beginMove(self: *Core, undo: bool) u64 {
        self.forgetMove();
        return if (undo) 0 else self.move_serial;
    }

    /// Count the `revision` of an answer to pane move `serial` towards `undo_revision`.
    fn noteMoveRevision(self: *Core, serial: u64, payload: ?json.Value) void {
        if (serial == 0 or serial != self.move_serial) return;
        const revision = model.int(model.get(payload orelse .null, "revision")) orelse return;
        if (self.undo_revision == null or revision > self.undo_revision.?) self.undo_revision = revision;
    }

    /// Intents `movePane` and Undo `beside`.
    fn movePane(self: *Core, session: []const u8, block: []const u8, anchor: []const u8, direction: protocol.SplitDirection, ratio: ?f64, undo: bool) void {
        const view = self.view orelse return self.refuseMove(undo);
        if (std.mem.eql(u8, block, anchor) or !placed(&view, block) or !tiled(&view, anchor)) return self.refuseMove(undo);
        const payload = protocol.moveTo(self.gpa, session, block, anchor, direction, ratio) catch return;
        self.sendMove(protocol.methods.moveBlock, payload, session, block, undo);
    }

    /// Intents `swapPanes` and Undo `swap`. The other block may float: the server swaps
    /// blocks of any layer.
    fn swapPanes(self: *Core, session: []const u8, block: []const u8, other: []const u8, undo: bool) void {
        const view = self.view orelse return self.refuseMove(undo);
        if (std.mem.eql(u8, block, other) or !placed(&view, block) or !placed(&view, other)) return self.refuseMove(undo);
        const payload = protocol.swapBlocks(self.gpa, session, block, other) catch return;
        self.sendMove(protocol.methods.swapBlocks, payload, session, block, undo);
    }

    /// Send a pane move or an Undo that is one request. Takes `payload`.
    fn sendMove(self: *Core, method: []const u8, payload: []u8, session: []const u8, block: []const u8, undo: bool) void {
        const gpa = self.gpa;
        const session_copy = gpa.dupe(u8, session) catch return gpa.free(payload);
        const block_copy = gpa.dupe(u8, block) catch {
            gpa.free(session_copy);
            return gpa.free(payload);
        };
        const move: PaneMove = .{ .session = session_copy, .block = block_copy, .select_gen = self.select_gen, .serial = self.beginMove(undo) };
        if (!self.sendRequest(method, payload, .{ .pane_moved = move })) {
            gpa.free(session_copy);
            gpa.free(block_copy);
        }
    }

    /// Focus the block a pane move or an Undo placed, so that its tab shows, also when the
    /// block was not focused before.
    fn focusMoved(self: *Core, session: []const u8, block: []const u8, serial: u64) void {
        const payload = protocol.focusBlock(self.gpa, session, block) catch return;
        _ = self.sendRequest(protocol.methods.focusBlock, payload, .{ .pane_focused = serial });
    }

    /// Intent `undoMove`. The app worked the inverse out from the layout before the last
    /// move, so it is exact only while the session is still at the revision that move ended
    /// with. A newer revision the server announced, which the held view does not show yet,
    /// also refuses it.
    fn undoMove(self: *Core, session: []const u8, undo: protocol.UndoMove) void {
        const done = self.undo_revision orelse return self.refuseMove(true);
        const view = self.view orelse return self.refuseMove(true);
        if (@max(view.revision, self.want_revision) > done) return self.refuseMove(true);
        switch (undo) {
            .swap => |s| self.swapPanes(session, s.block, s.other, true),
            .beside => |b| self.movePane(session, b.block, b.anchor, b.direction, b.ratio, true),
            .retab => |r| self.startDockTab(session, r.block, .{ .label = r.label, .before = r.before }) catch
                self.noteError("internal", "could not move the pane to a new tab"),
        }
    }

    /// What an Undo `retab` gives the new tab.
    const Retab = struct { label: ?[]const u8, before: ?[]const u8 };

    /// Intents `moveToNewTab` and Undo `retab` (`undo` set): a new tab at the end with a
    /// temporary anchor, since `session.new_window` always starts a new block, then the
    /// block moves beside the anchor (`moveIntoDockTab`) and the anchor closes.
    fn startDockTab(self: *Core, session: []const u8, block: []const u8, undo: ?Retab) Allocator.Error!void {
        if (self.dock_tab != null) return;
        const view = self.view orelse return self.refuseMove(undo != null);
        if (!canMoveToNewTab(&view, block)) return self.refuseMove(undo != null);
        const gpa = self.gpa;
        const session_copy = try gpa.dupe(u8, session);
        errdefer gpa.free(session_copy);
        const block_copy = try gpa.dupe(u8, block);
        errdefer gpa.free(block_copy);
        const retab = undo orelse Retab{ .label = null, .before = null };
        const label: ?[]u8 = if (retab.label) |l| try gpa.dupe(u8, l) else null;
        errdefer if (label) |l| gpa.free(l);
        const before: ?[]u8 = if (retab.before) |w| try gpa.dupe(u8, w) else null;
        errdefer if (before) |w| gpa.free(w);
        const payload = try protocol.newDockTab(gpa, session);
        const serial = self.beginMove(undo != null);
        if (!self.sendRequest(protocol.methods.newWindow, payload, .dock_tab_created)) return error.OutOfMemory;
        self.dock_tab = .{
            .session = session_copy,
            .block = block_copy,
            .select_gen = self.select_gen,
            .serial = serial,
            .label = label,
            .before = before,
        };
    }

    fn clearDockTab(self: *Core) void {
        const dock = self.dock_tab orelse return;
        self.gpa.free(dock.session);
        self.gpa.free(dock.block);
        if (dock.anchor) |anchor| self.gpa.free(anchor);
        if (dock.window) |window| self.gpa.free(window);
        if (dock.label) |label| self.gpa.free(label);
        if (dock.before) |before| self.gpa.free(before);
        self.dock_tab = null;
    }

    fn moveIntoDockTab(self: *Core, payload: json.Value) Allocator.Error!void {
        const dock = if (self.dock_tab) |*d| d else return;
        const ids = model.items(model.get(payload, "block_ids"));
        const anchor = if (ids.len == 1) model.str(ids[0]) orelse "" else "";
        if (!std.mem.startsWith(u8, anchor, "block:") or std.mem.eql(u8, anchor, dock.block)) {
            self.clearDockTab();
            return self.noteError("invalid_response", "the server did not return a temporary dock anchor");
        }
        dock.anchor = try self.gpa.dupe(u8, anchor);
        if (model.str(model.get(payload, "window_id"))) |window| dock.window = try self.gpa.dupe(u8, window);
        const move = try protocol.moveBlock(self.gpa, dock.session, dock.block, anchor);
        if (!self.sendRequest(protocol.methods.moveBlock, move, .dock_tab_moved)) {
            self.finishDockTab(false);
            self.noteError("internal", "could not move the pane to the new tab");
        }
    }

    fn finishDockTab(self: *Core, moved: bool) void {
        const dock = self.dock_tab orelse return;
        defer self.clearDockTab();
        if (dock.anchor) |anchor| {
            // Close only the block returned by our new-window request. On move failure,
            // the empty temporary tab can close with it. Never close a whole tab by ID.
            // A failed move is no move: then the answer counts for no Undo.
            if (protocol.closeBlockAllowLast(self.gpa, dock.session, anchor)) |payload| {
                _ = self.sendRequest(protocol.methods.closeBlock, payload, .{ .dock_tab_closed = if (moved) dock.serial else 0 });
            } else |_| {
                // The temporary command closes its own pane after it exits.
            }
        }
        if (!moved) return;
        if (dock.select_gen == self.select_gen) self.focusMoved(dock.session, dock.block, dock.serial);
        // An Undo puts back the tab that the move closed: its label, then its place.
        const window = dock.window orelse return;
        if (dock.label) |label| self.sendCommand(protocol.methods.setWindowLabel, protocol.setWindowLabel(self.gpa, dock.session, window, label));
        if (dock.before) |before| self.sendCommand(protocol.methods.moveWindow, protocol.moveWindow(self.gpa, dock.session, window, before));
    }

    // --------------------------------------------------------------- responses

    fn handleIncoming(self: *Core, incoming: protocol.Incoming) void {
        switch (incoming) {
            // `serve` reads the welcome itself; this is the one a test feeds in.
            .welcome => |w| self.setClient(w.client_id),
            .other => {},
            .response => |r| if (self.pending.fetchRemove(r.id)) |kv| self.handleResponse(r.id, kv.value, r.payload, r.err),
            .event => |e| self.handleEvent(e.session, e.kind, e.payload),
        }
    }

    fn handleResponse(self: *Core, request_id: u64, pending: Pending, payload: ?json.Value, err: ?protocol.ResponseError) void {
        const gpa = self.gpa;
        defer freePending(gpa, pending);
        switch (pending) {
            .pane_write_check => |op| self.paneWriteChecked(op, payload, err),
            .pane_write_owner => |op| self.paneWriteOwned(op, payload, err),
            .pane_screen => |op| self.paneScreenFormatted(op, payload, err),
            .pane_screen_tail => |op| self.paneScreenRead(op, payload, err),
            .pane_write => |w| {
                if (err) |e| return self.notePaneWrite(w.tag, .unconfirmed, e.message);
                const written = model.get(payload orelse .null, "bytes_written") orelse .null;
                if (written == .integer and written.integer == @as(i64, @intCast(w.bytes)))
                    self.notePaneWrite(w.tag, .sent, "")
                else
                    self.notePaneWrite(w.tag, .unconfirmed, "the server did not confirm the whole answer");
            },
            .dock_tab_created => {
                if (err) |e| {
                    self.clearDockTab();
                    return self.noteResponseError(e);
                }
                self.moveIntoDockTab(payload orelse .null) catch {
                    self.finishDockTab(false);
                    self.noteError("internal", "could not move the pane to the new tab");
                };
            },
            .dock_tab_moved => {
                if (err == null) if (self.dock_tab) |dock| self.noteMoveRevision(dock.serial, payload);
                self.finishDockTab(err == null);
                if (err) |e| self.noteResponseError(e);
            },
            .dock_tab_closed => |serial| if (err) |e| self.noteCloseError(e) else self.noteMoveRevision(serial, payload),
            .pane_moved => |m| {
                if (err) |e| return self.noteResponseError(e);
                self.noteMoveRevision(m.serial, payload);
                if (m.select_gen == self.select_gen) self.focusMoved(m.session, m.block, m.serial);
            },
            .pane_focused => |serial| if (err) |e| self.noteResponseError(e) else self.noteMoveRevision(serial, payload),
            .composer => {
                const op = self.composer orelse return;
                const result = payload orelse json.Value.null;
                op.reply = if (err != null) .refused else .invalid;
                if (err == null) {
                    if (op.expected_bytes) |n| {
                        const written = model.get(result, "bytes_written") orelse .null;
                        if (written == .integer and written.integer == @as(i64, @intCast(n))) op.reply = .confirmed;
                    } else if (model.str(model.get(result, "ticket"))) |t| {
                        if (t.len > 0 and t.len <= op.ticket.len and (model.int(model.get(result, "expires_in_ms")) orelse 0) > 0) {
                            @memcpy(op.ticket[0..t.len], t);
                            op.ticket_len = t.len;
                            op.reply = .confirmed;
                        }
                    }
                }
                self.lock.signal();
            },
            .ask => |a| {
                if (err) |e| {
                    a.code = gpa.dupe(u8, e.code) catch null;
                    a.message = gpa.dupe(u8, e.message) catch null;
                } else {
                    a.payload = json.Stringify.valueAlloc(gpa, payload orelse .null, .{}) catch null;
                }
                a.done = true;
                self.lock.signal();
            },
            .download => {
                const op = self.download orelse return;
                if (op.id != request_id) return;
                op.answer = downloadAnswer(op.gpa, payload, err) catch return;
                self.lock.signal();
            },
            .bootstrap => {
                if (err) |e| {
                    // A failed bootstrap may have taken effect in part: start over on
                    // a new connection.
                    self.noteResponseError(e);
                    self.drop_link = true;
                    self.drop_reason = "session.bootstrap failed";
                    return;
                }
                self.applyBootstrap(payload orelse .null);
            },
            .pane_baseline => |serial| {
                const w = self.watchBySerial(serial) orelse return;
                w.baseline_pending = false;
                const records = model.get(payload orelse .null, "records") orelse .null;
                if (err == null and records == .array) {
                    if (pane_events.baseline(gpa, w.target, records, w.reports.items)) |r| {
                        if (w.reports.items.len == 0) {
                            self.notes.append(gpa, .{ .pane_report = r }) catch gpa.free(r);
                        } else {
                            // Check the location before delivering any live part of this snapshot.
                            for (w.reports.items) |live| gpa.free(live);
                            w.reports.clearRetainingCapacity();
                            w.reports.appendAssumeCapacity(r);
                        }
                    } else |_| {}
                }
                self.flushPaneReports(w);
            },
            .pane_location => |serial| {
                const w = self.watchBySerial(serial) orelse return;
                w.location_pending = false;
                var arena: std.heap.ArenaAllocator = .init(gpa);
                defer arena.deinit();
                const view = model.parseView(arena.allocator(), payload orelse .null) catch return self.finishPaneReports(w, false);
                if (err == null and std.mem.eql(u8, view.session, w.target.session) and view.revision < w.revision)
                    return self.flushPaneReports(w);
                self.finishPaneReports(w, err == null and pane_events.contains(&view, w.target));
            },
            .list => {
                if (err) |e| return self.noteResponseError(e);
                self.setSessions(model.get(payload orelse .null, "sessions"));
                self.pickSession();
                // A view asked for on this connection is not asked for again.
                self.syncFollows();
            },
            .view => |gen| {
                self.view_inflight = false;
                if (gen != self.select_gen) return;
                if (err) |e| {
                    // A destroyed session is reported by its own event.
                    if (!std.mem.eql(u8, e.code, "session_not_found")) self.noteResponseError(e);
                    return;
                }
                self.applyView(payload, null, .update);
                if (self.view_again) self.requestView();
            },
            .window_moved => |session| {
                if (err) |e| return self.noteResponseError(e);
                const revision = model.int(model.get(payload orelse .null, "revision")) orelse return;
                self.sessionRevision(session, revision);
            },
            .stash_floated => |session| {
                if (err) |e| return self.noteResponseError(e);
                const layer = model.str(model.get(payload orelse .null, "layer_id")) orelse
                    return self.noteError("internal", "the pane floated but could not be minimized");
                self.sendFor(session, protocol.methods.setLayerState, protocol.setLayerState(gpa, session, layer, "minimized", null), .command);
            },
            .follow_view => |session| {
                const follow = self.follows.get(session) orelse return;
                follow.inflight = false;
                // A failure is nothing the user did; a destroyed session is reported by
                // its own event.
                if (err == null) self.applyFollowView(follow, payload orelse .null);
                // Read again only for a change the answer does not hold.
                const behind = if (follow.view) |v| follow.want_revision > v.revision else false;
                const again = follow.again;
                follow.again = false;
                if (err == null and (again or behind)) self.requestFollowView(follow);
            },
            .attach => |a| {
                if (a.gen != self.select_gen) {
                    // Superseded while in flight: do not stay attached to it.
                    if (err == null and !self.isSelected(a.session)) self.detach(a.session);
                    return;
                }
                if (err) |e| {
                    if (std.mem.eql(u8, e.code, "session_not_found")) {
                        // It went while this client did not see it: nothing to report.
                        // The list answer picks, as for a saved session that is gone.
                        self.dropFollow(a.session);
                        self.removeSession(a.session);
                        self.setSelected(null);
                        self.requestList();
                        return;
                    }
                    // Refused: the user is told, and no other session is attached in
                    // its place.
                    self.noteResponseError(e);
                    self.auto_pick = false;
                    self.setSelected(null);
                    return;
                }
                self.applyView(model.get(payload orelse .null, "view"), null, .attach);
            },
            .create_session => |attach_on_success| {
                if (err) |e| return self.noteResponseError(e);
                const id = model.str(model.get(payload orelse .null, "session_id")) orelse
                    return self.noteError("bad_response", "session.create named no session");
                // Its own session exists for this core whatever its label.
                if (!self.made.contains(id)) if (gpa.dupe(u8, id)) |copy| {
                    self.made.put(gpa, copy, {}) catch gpa.free(copy);
                } else |_| {};
                // The server does not attach the caller; switch to it like a session tap.
                if (attach_on_success) {
                    if (self.selected) |old| self.detach(old);
                    self.auto_pick = true;
                    self.attach(id);
                }
                // A fenced core let the `session_created` of this session pass unread.
                if (self.fence_prefix.len > 0) self.requestList();
            },
            .whoami => {
                // An older server without the method leaves the account unknown.
                if (err != null) return;
                self.setText(&self.account, model.str(model.get(payload orelse .null, "account")) orelse "");
            },
            .hosts_list => {
                if (err) |e| {
                    // A server older than the host list leaves it empty.
                    if (std.mem.eql(u8, e.code, "method_not_found") or std.mem.eql(u8, e.code, "not_found")) return;
                    return self.noteResponseError(e);
                }
                self.applyHosts(payload orelse .null);
            },
            .input_mode => |serial| {
                const entry = self.entryBySerial(serial) orelse return;
                const check = if (entry.preedit_ask) |*v| v else return;
                if (check.mode_id != request_id) return;
                check.mode_allows = err == null and PreeditAsk.allowsMode(payload orelse .null);
                self.finishPreeditCheck(entry);
            },
            .preedit_process => |serial| {
                const entry = self.entryBySerial(serial) orelse return;
                const check = if (entry.preedit_ask) |*v| v else return;
                if (check.process_id != request_id) return;
                check.program_allows = err == null and PreeditAsk.allowsProgram(payload orelse .null);
                self.finishPreeditCheck(entry);
            },
            .process => |serial| {
                const entry = self.entryBySerial(serial) orelse return;
                if (err != null or request_id < entry.last_process_response) return;
                entry.last_process_response = request_id;
                var buf: [std.fs.max_path_bytes + 1]u8 = undefined;
                const info = processInfo(&buf, payload orelse .null);
                entry.agent = info.agent;
                self.setText(&entry.program, info.program);
                self.setText(&entry.cwd, info.cwd);
                self.syncBlockInfo(entry);
            },
            .program_status => |serial| {
                const entry = self.entryBySerial(serial) orelse return;
                if (err != null or request_id < entry.last_status_response) return;
                entry.last_status_response = request_id;
                const worst = worstActivity(payload orelse .null);
                const details = tab_meta.Details.read(payload orelse .null, worst.activity, worst.since);
                const changed = entry.tab_report.replace(self.gpa, details) catch false;
                if (!changed and worst.activity == entry.activity and worst.since == entry.since) return;
                entry.activity = worst.activity;
                entry.since = worst.since;
                self.syncBlockInfo(entry);
            },
            .ticket => |serial| {
                const entry = self.entryBySerial(serial) orelse return;
                if (entry.ticket_state != .waiting) return;
                const ticket = model.str(model.get(payload orelse .null, "ticket"));
                if (ticket != null and ticket.?.len <= entry.ticket.len) {
                    @memcpy(entry.ticket[0..ticket.?.len], ticket.?);
                    entry.ticket_len = ticket.?.len;
                    entry.ticket_state = .ready;
                } else {
                    // `not_found` is a block that closed while the request was on its way.
                    if (err) |e| if (!std.mem.eql(u8, e.code, "not_found")) self.noteResponseError(e);
                    entry.ticket_state = .failed;
                }
                self.lock.signal();
            },
            .write => |serial| {
                if (err) |e| self.noteResponseError(e);
                const entry = self.entryBySerial(serial) orelse return;
                entry.write_inflight = false;
                self.flushInput(entry);
            },
            .take => |serial| {
                const entry = self.entryBySerial(serial) orelse return;
                entry.size.take_inflight = false;
                // A refusal (say, the block closed on the way) is nothing the user did,
                // and nothing sends the take again by itself.
                if (err != null) return self.sizeStep(entry, .refused);
                // A take that succeeded made this client the owner. The answer may list no
                // blocks; one that names another client says the block was taken again.
                entry.size.owned = true;
                for (model.items(model.get(payload orelse .null, "blocks"))) |b| {
                    if (!std.mem.eql(u8, model.str(model.get(b, "block_id")) orelse "", entry.id)) continue;
                    if (model.str(model.get(b, "owner"))) |owner| entry.size.owned = self.isMe(owner);
                }
                // A pane shown again while this take was in flight still wants its own.
                self.sizeStep(entry, .answered);
            },
            .resize => |serial| {
                if (err == null) return;
                // The server does not hold this size: the next fit asks again.
                const entry = self.entryBySerial(serial) orelse return;
                entry.size.asked = null;
            },
            .lead_process => |serial| {
                const lead = self.leadBySerial(serial) orelse return;
                lead.process.inflight = false;
                if (lead.process.again) self.requestLeadProcess(lead);
                if (err != null) return;
                var buf: [std.fs.max_path_bytes + 1]u8 = undefined;
                const info = processInfo(&buf, payload orelse .null);
                if (!std.mem.eql(u8, lead.agent orelse "", info.agent orelse "")) self.dirty = true;
                lead.agent = info.agent;
                self.setText(&lead.program, info.program);
                self.setText(&lead.cwd, info.cwd);
            },
            .lead_status => |serial| {
                const lead = self.leadBySerial(serial) orelse return;
                lead.status.inflight = false;
                if (lead.status.again) self.requestLeadStatus(lead);
                if (err != null) return;
                const worst = worstActivity(payload orelse .null);
                const details = tab_meta.Details.read(payload orelse .null, worst.activity, worst.since);
                const changed = lead.tab_report.replace(self.gpa, details) catch false;
                if (!changed and worst.activity == lead.activity and worst.since == lead.since) return;
                lead.activity = worst.activity;
                lead.since = worst.since;
                self.dirty = true;
            },
            .entry_title => |q| {
                const entry = self.entryBySerial(q.serial) orelse return;
                if (err != null or q.revision != entry.title_revision) return;
                if (model.str(model.get(payload orelse .null, "title"))) |title| self.setEntryTitle(entry, title);
            },
            .lead_title => |q| {
                const lead = self.leadBySerial(q.serial) orelse return;
                lead.title_refresh.inflight = false;
                if (lead.title_refresh.again) self.requestLeadTitle(lead);
                if (err != null or q.revision != lead.title_revision) return;
                if (model.str(model.get(payload orelse .null, "title"))) |title| _ = self.setTitle(&lead.title, title);
            },
            .close => if (err) |e| self.noteCloseError(e),
            .command => if (err) |e| self.noteResponseError(e),
            .silent => {},
        }
    }

    /// The error of a close or destroy request.
    fn noteCloseError(self: *Core, e: protocol.ResponseError) void {
        // Already closed, by this client or another: what the user asked for.
        if (std.mem.eql(u8, e.code, "not_found") or std.mem.eql(u8, e.code, "session_not_found")) return;
        self.noteResponseError(e);
    }

    fn applyBootstrap(self: *Core, payload: json.Value) void {
        self.setSessions(model.get(model.get(payload, "attach_all") orelse .null, "sessions"));
        self.ready = true;
        self.ever_connected = true;
        self.status = .connected;
        self.dirty = true;
        // With a fence the bootstrap attached nothing, so that no session is named before
        // its label is known. A selection the list does not hold is none of this core's.
        const fenced = self.fence_prefix.len > 0;
        if (fenced) if (self.selected) |s| if (!self.exists(s)) self.setSelected(null);

        _ = self.sendRequest(protocol.methods.whoami, &.{}, .whoami);
        self.requestHosts();
        if (self.pane_watches) |watches| for (watches.items) |*w| self.requestPaneBaseline(w);

        // `selected_session` when the server picked, `attached_sessions` when we named one.
        // With a fence neither was asked for: one that is reported all the same is let go
        // before anything is made for it, unless it is the selection of this core.
        var chosen: ?json.Value = null;
        if (model.get(payload, "selected_session")) |s| if (s == .object) {
            if (!self.letGo(s)) chosen = s;
        };
        for (model.items(model.get(payload, "attached_sessions"))) |s| {
            if (self.letGo(s)) continue;
            if (chosen == null) chosen = s;
        }
        const view = if (chosen) |c| model.get(c, "view") else null;
        if (model.str(model.get(view orelse .null, "session_id"))) |session| {
            if (self.selected == null and !self.auto_pick) {
                // Detached by the user: do not stay attached to what the server chose.
                self.detach(session);
            } else {
                if (!self.isSelected(session)) self.setSelected(session);
                self.applyView(view, model.get(payload, "data_tickets"), .bootstrap);
            }
        } else if (self.selected != null) {
            if (fenced) {
                // The list holds it: attach it now. Panes that outlived the last
                // connection stay, and their streams wait for the answer.
                self.resume_pending = self.entries.count() > 0;
                self.attachSelected();
            } else {
                // The session we asked for is gone.
                self.setSelected(null);
            }
        }
        self.pickSession();
        // Every other session's view is read once per connection; events keep it current.
        self.syncFollows();

        if (self.refresh_pending) {
            self.refresh_pending = false;
            self.requestList();
            self.requestView();
        }
    }

    /// With no session selected, attach to the first one, unless the user detached.
    fn pickSession(self: *Core) void {
        if (self.selected != null or !self.ready or !self.auto_pick or self.sessions.items.len == 0) return;
        self.attach(self.sessions.items[0].id);
    }

    fn handleEvent(self: *Core, event_session: ?[]const u8, kind_name: []const u8, payload: json.Value) void {
        // Sent to every connection, attached or not. Before the bootstrap answer there is
        // nothing to do: the bootstrap asks for the list.
        if (std.mem.eql(u8, kind_name, "hosts_changed")) {
            const hash = model.str(model.get(payload, "hash")) orelse "";
            if (!std.mem.eql(u8, hash, self.hosts_hash)) self.requestHosts();
            return;
        }
        // Scope `all` also brings the client events and kinds a newer server adds: none of
        // them is a reason to ask the server anything.
        const Kind = enum {
            session_created,
            session_destroyed,
            session_moved,
            session_label_changed,
            window_label_changed,
            session_view_changed,
            block_owner_changed,
            block_event,
            event_dropped,
        };
        const kind = std.meta.stringToEnum(Kind, kind_name) orelse return;
        const session = event_session orelse model.str(model.get(payload, "session_id"));
        // A session that does not exist for this core: nothing it does is a reason to
        // ask, except a label that may bring it in.
        if (self.ready and self.fence_prefix.len > 0) switch (kind) {
            .session_created => {
                const born = model.get(payload, "session") orelse .null;
                if (model.str(model.get(born, "session_id"))) |id| {
                    if (!self.admits(id, model.str(model.get(born, "label")) orelse "")) return;
                }
            },
            .session_label_changed => if (session) |s| {
                if (!self.admits(s, model.str(model.get(payload, "label")) orelse "")) {
                    // Renamed out of the prefix, or never in it.
                    self.unlist(s);
                    return;
                }
            },
            else => if (session) |s| if (!self.exists(s)) return,
        };
        if (!self.ready) {
            // The bootstrap result may or may not include this change.
            self.refresh_pending = true;
            return;
        }
        const mine = if (session) |s| self.isSelected(s) else false;
        switch (kind) {
            .session_created, .session_moved => self.requestList(),
            .session_destroyed => {
                const gone = session orelse return self.requestList();
                // Shown here at once; the list answer confirms it.
                const next = if (mine and self.auto_pick) neighbour(self.sessions.items, gone) else null;
                self.dropFollow(gone);
                self.removeSession(gone);
                // `next` is the id of another list entry, which the removal left alone.
                if (mine) if (next) |n| self.attach(n) else self.setSelected(null);
                self.requestList();
            },
            // Only the selected session has panes; no other session reaches the size rules.
            .block_owner_changed => if (mine) {
                const entry = self.entries.get(model.str(model.get(payload, "block_id")) orelse return) orelse return;
                entry.size.owned = self.isMe(model.str(model.get(payload, "owner")));
            },
            .block_event => {
                if (session) |s| self.receivePaneEvent(s, payload);
                if (mine) self.handleBlockEvent(payload) else self.followedBlockEvent(payload);
            },
            .session_label_changed => {
                self.requestList();
                if (mine) self.requestView();
            },
            .window_label_changed => if (mine) self.requestView(),
            .session_view_changed => {
                const revision = model.int(model.get(payload, "revision")) orelse return;
                if (self.pane_watches) |watches| for (watches.items) |*w| {
                    if (std.mem.eql(u8, w.target.session, session orelse return)) w.revision = @max(w.revision, revision);
                };
                self.sessionRevision(session orelse return, revision);
            },
            .event_dropped => if (session == null) {
                if (std.mem.eql(u8, model.str(model.get(payload, "event_type")) orelse "", "hosts_changed"))
                    self.requestHosts()
                else
                    self.requestList();
            } else if (mine) {
                self.requestView();
                if (self.stashed.get(model.str(model.get(payload, "block_id")) orelse "")) |lead| self.requestLead(lead);
                if (std.mem.eql(u8, model.str(model.get(payload, "event_type")) orelse "", "block_event") and
                    std.mem.eql(u8, model.str(model.get(payload, "block_event_name")) orelse "", "title_changed"))
                {
                    const entry = self.entries.get(model.str(model.get(payload, "block_id")) orelse return) orelse return;
                    self.requestEntryTitle(entry);
                }
            } else if (self.follows.get(session.?)) |follow| {
                self.requestFollowView(follow);
                const block = model.str(model.get(payload, "block_id")) orelse "";
                if (self.leads.get(block)) |lead| self.requestLead(lead);
                if (self.beside.get(block)) |other| self.requestBeside(other);
            },
        }
    }

    /// `session` is at `revision` on the server: from its `session_view_changed` or from the
    /// answer of a command that changed it. A selected session reads its view when the held
    /// one is older; a followed session does so through `followRevision`.
    fn sessionRevision(self: *Core, session: []const u8, revision: i64) void {
        if (!self.isSelected(session)) {
            if (self.follows.get(session)) |follow| self.followRevision(follow, revision);
            return;
        }
        if (revision > self.want_revision) self.want_revision = revision;
        // Without a view the attach answer is on its way; `applyView` compares
        // it with `want_revision`.
        if (self.view) |v| if (revision > v.revision) self.requestView();
    }

    fn setPaneWatches(self: *Core, value: json.Value) void {
        const next = pane_events.Watches.init(self.gpa, value) catch return;
        for (next.items) |*w| {
            if (self.pane_watches) |old| {
                for (old.items) |*prev| {
                    if (!pane_events.same(w.target, prev.target)) continue;
                    const target = w.target;
                    w.* = prev.*;
                    w.target = target;
                    prev.reports = .empty;
                    break;
                }
            }
            if (w.serial == 0) {
                w.serial = self.next_serial;
                self.next_serial += 1;
                if (self.ready) self.requestPaneBaseline(w);
            }
        }
        if (self.pane_watches) |old| old.deinit(self.gpa);
        self.pane_watches = if (next.items.len != 0) next else null;
        if (next.items.len == 0) next.deinit(self.gpa);
        self.dirty = true;
    }

    fn watchBySerial(self: *Core, serial: u64) ?*pane_events.Watch {
        const watches = self.pane_watches orelse return null;
        for (watches.items) |*w| if (w.serial == serial) return w;
        return null;
    }

    fn requestPaneBaseline(self: *Core, w: *pane_events.Watch) void {
        if (!self.exists(w.target.session)) return;
        const payload = protocol.blockCall(self.gpa, w.target.session, w.target.block) catch return;
        w.baseline_pending = self.sendRequest(protocol.methods.terminalProgramStatus, payload, .{ .pane_baseline = w.serial });
    }

    fn receivePaneEvent(self: *Core, session: []const u8, event: json.Value) void {
        const watches = self.pane_watches orelse return;
        const block = model.str(model.get(event, "block_id")) orelse return;
        for (watches.items) |*w| {
            if (!std.mem.eql(u8, session, w.target.session) or !std.mem.eql(u8, block, w.target.block)) continue;
            const r = (pane_events.report(self.gpa, w.target, event) catch null) orelse continue;
            w.reports.append(self.gpa, r) catch {
                self.gpa.free(r);
                continue;
            };
            self.flushPaneReports(w);
        }
    }

    fn flushPaneReports(self: *Core, w: *pane_events.Watch) void {
        if (w.reports.items.len == 0 or w.baseline_pending or w.location_pending) return;
        const s = w.target.session;
        if (!self.exists(s)) return self.finishPaneReports(w, false);
        // A reconnect can retain an old view until the new attach or follow request answers.
        const current = if (self.isSelected(s)) !self.resume_pending and self.view != null and !self.view_inflight else if (self.follows.get(s)) |f| f.asked and !f.inflight else false;
        if (current) if (self.viewOf(s)) |v| {
            if (v.revision >= w.revision) return self.finishPaneReports(w, pane_events.contains(v, w.target));
        };
        // A parked host needs only the location, never an attach or a terminal stream.
        const payload = protocol.sessionView(self.gpa, s) catch return self.finishPaneReports(w, false);
        w.location_pending = self.sendRequest(protocol.methods.sessionView, payload, .{ .pane_location = w.serial });
        if (!w.location_pending) self.finishPaneReports(w, false);
    }

    fn finishPaneReports(self: *Core, w: *pane_events.Watch, present: bool) void {
        for (w.reports.items) |r| {
            if (present) self.notes.append(self.gpa, .{ .pane_report = r }) catch self.gpa.free(r) else self.gpa.free(r);
        }
        w.reports.clearRetainingCapacity();
    }

    /// A terminal reported a new foreground process or program status. Both events carry
    /// only part of what the methods return, so each one asks for the whole answer.
    fn handleBlockEvent(self: *Core, payload: json.Value) void {
        const block = model.str(model.get(payload, "block_id")) orelse return;
        const entry = self.entries.get(block) orelse {
            // A minimized pane: named in the state, with no pane to refresh.
            if (self.stashed.get(block)) |lead| self.leadEvent(lead, payload);
            return;
        };
        const name = model.str(model.get(payload, "name")) orelse return;
        if (std.mem.eql(u8, name, "process_changed")) {
            self.requestProcess(entry);
            if (entry.pane.hidePreedit()) self.checkPreedit(entry);
        } else if (std.mem.eql(u8, name, "program_status_changed") or std.mem.eql(u8, name, "program_status_removed")) {
            self.requestProgramStatus(entry);
        } else if (std.mem.eql(u8, name, "title_changed")) {
            const title = model.str(model.get(model.get(payload, "payload") orelse .null, "title")) orelse return;
            self.setEntryTitle(entry, title);
        }
    }

    fn requestBlockInfo(self: *Core, entry: *Entry) void {
        self.requestProcess(entry);
        self.requestProgramStatus(entry);
        if (self.resume_pending) return;
        entry.has_control_title = false;
        self.requestEntryTitle(entry);
    }

    fn requestEntryTitle(self: *Core, entry: *Entry) void {
        if (self.resume_pending) return;
        const session = self.selected orelse return;
        const payload = protocol.blockCall(self.gpa, session, entry.id) catch return;
        // A newer query or title event supersedes every earlier query for this entry.
        entry.title_revision +%= 1;
        const id = self.next_id;
        if (self.sendRequest(protocol.methods.terminalTitle, payload, .{ .entry_title = .{ .serial = entry.serial, .revision = entry.title_revision } })) {
            entry.last_title_request = id;
        }
    }

    fn requestProcess(self: *Core, entry: *Entry) void {
        if (self.resume_pending) return;
        const session = self.selected orelse return;
        const payload = protocol.blockCall(self.gpa, session, entry.id) catch return;
        const id = self.next_id;
        if (self.sendRequest(protocol.methods.terminalProcess, payload, .{ .process = entry.serial })) {
            entry.last_process_request = id;
        }
    }

    fn requestProgramStatus(self: *Core, entry: *Entry) void {
        if (self.resume_pending) return;
        const session = self.selected orelse return;
        const payload = protocol.blockCall(self.gpa, session, entry.id) catch return;
        const id = self.next_id;
        if (self.sendRequest(protocol.methods.terminalProgramStatus, payload, .{ .program_status = entry.serial })) {
            entry.last_status_request = id;
        }
    }

    /// Replace `slot` with a copy of `text`; keeps the old value when memory runs out.
    fn setText(self: *Core, slot: *[]u8, text: []const u8) void {
        if (std.mem.eql(u8, slot.*, text)) return;
        const copy = self.gpa.dupe(u8, text) catch return;
        self.gpa.free(slot.*);
        slot.* = copy;
        self.dirty = true;
    }

    /// Publish what `entry` knows about its block. Entries with nothing to say have no
    /// record, so the UI falls back to the block's label.
    fn syncBlockInfo(self: *Core, entry: *Entry) void {
        if (entry.removed) return;
        self.blocks.put(self.gpa, entry.id, .{
            .title = entry.title,
            .program = entry.program,
            .agent = entry.agent,
            .tab_report = entry.tab_report,
            .cwd = entry.cwd,
            .activity = entry.activity,
            .since = entry.since,
        }) catch {
            _ = self.blocks.remove(entry.id);
        };
        self.dirty = true;
    }

    // ------------------------------------------------------------------- model

    fn isSelected(self: *const Core, session: []const u8) bool {
        return if (self.selected) |s| std.mem.eql(u8, s, session) else false;
    }

    /// Replace the session list with a `sessions` array (`[{session_id, label}]`).
    fn setSessions(self: *Core, value: ?json.Value) void {
        const gpa = self.gpa;
        const list = model.items(value);
        // The silence probe asks for the list every 30 s: no change, no new state.
        // With a fence the list holds only the sessions that exist for this core. The
        // selected one stays in it until it is let go below, for its place in the order.
        var count: usize = 0;
        var same = true;
        var selected_out = false;
        // Before the bootstrap answer is applied nothing is attached: a selection that is
        // not this core's is simply not listed.
        const keep_place = self.ready;
        for (list) |item| {
            const id = model.str(model.get(item, "session_id")) orelse continue;
            const label = model.str(model.get(item, "label")) orelse "";
            if (!self.admits(id, label)) {
                if (!keep_place or !self.isSelected(id)) continue;
                selected_out = true;
            }
            same = same and count < self.sessions.items.len and
                std.mem.eql(u8, self.sessions.items[count].id, id) and
                std.mem.eql(u8, self.sessions.items[count].label, label);
            count += 1;
        }
        if (same and count == self.sessions.items.len and !selected_out) return;

        self.freeSessions();
        self.dirty = true;
        for (list) |item| {
            const listed_id = model.str(model.get(item, "session_id")) orelse continue;
            const listed_label = model.str(model.get(item, "label")) orelse "";
            if (!self.admits(listed_id, listed_label) and !(selected_out and self.isSelected(listed_id))) continue;
            const id = gpa.dupe(u8, listed_id) catch break;
            const label = gpa.dupe(u8, listed_label) catch {
                gpa.free(id);
                break;
            };
            self.sessions.append(gpa, .{ .id = id, .label = label }) catch {
                gpa.free(id);
                gpa.free(label);
                break;
            };
        }
        if (selected_out) self.expelSelected();
    }

    fn freeSessions(self: *Core) void {
        const gpa = self.gpa;
        for (self.sessions.items) |s| {
            gpa.free(s.id);
            gpa.free(s.label);
        }
        self.sessions.clearRetainingCapacity();
    }

    /// Change the selected session: its view and panes go away.
    fn setSelected(self: *Core, session: ?[]const u8) void {
        self.invalidateComposerLocked();
        const gpa = self.gpa;
        const copy: ?[]u8 = if (session) |s| gpa.dupe(u8, s) catch null else null;
        const old = self.selected;
        self.selected = copy;
        self.select_gen += 1;
        if (old) |o| {
            // Before its panes go: the tabs keep what the panes knew.
            if (copy != null and !std.mem.eql(u8, o, copy.?)) self.handOver(o);
            gpa.free(o);
        }
        self.resume_pending = false;
        self.clearView();
        self.syncFollows();
        self.dirty = true;
    }

    fn clearView(self: *Core) void {
        self.view = null;
        if (self.view_arena) |arena| arena.deinit();
        self.view_arena = null;
        self.want_revision = -1;
        self.view_inflight = false;
        self.view_again = false;
        // The last move was in this view: no Undo for another session.
        self.forgetMove();
        self.reconcile(null, false);
    }

    /// Drop sessions, selection, view and panes (another server, disconnect, destroy).
    fn clearModel(self: *Core) void {
        if (self.selected) |old| self.gpa.free(old);
        self.selected = null;
        self.select_gen += 1;
        self.auto_pick = true;
        self.clearView();
        self.clearFollows();
        self.freeSessions();
        self.ever_connected = false;
        self.clearHosts();
        self.dirty = true;
    }

    /// Replace the host list with a `hosts.list` result. The same hash is the same list.
    fn applyHosts(self: *Core, payload: json.Value) void {
        const hash = model.str(model.get(payload, "hash")) orelse "";
        if (hash.len > 0 and std.mem.eql(u8, hash, self.hosts_hash)) return;
        var arena: std.heap.ArenaAllocator = .init(self.gpa);
        const a = arena.allocator();
        const hosts = model.parseHosts(a, payload) catch return arena.deinit();
        const hash_copy = a.dupe(u8, hash) catch return arena.deinit();
        self.clearHosts();
        self.hosts_arena = arena;
        self.hosts = hosts;
        self.hosts_hash = hash_copy;
        self.dirty = true;
    }

    fn clearHosts(self: *Core) void {
        if (self.hosts_arena) |arena| arena.deinit();
        self.hosts_arena = null;
        self.hosts = &.{};
        self.hosts_hash = "";
        self.dirty = true;
    }

    /// Select `session` and ask the server to attach to it. Not connected: the next
    /// bootstrap attaches it.
    fn attach(self: *Core, session: []const u8) void {
        if (self.ready and !self.exists(session)) return;
        const gpa = self.gpa;
        // `session` may point into `self.selected`, which setSelected frees.
        const owned = gpa.dupe(u8, session) catch return;
        defer gpa.free(owned);
        self.setSelected(owned);
        self.attachSelected();
    }

    /// Ask the server to attach the selected session; its view and panes stay.
    fn attachSelected(self: *Core) void {
        const gpa = self.gpa;
        if (!self.ready) return;
        const owned = gpa.dupe(u8, self.selected orelse return) catch return;
        const payload = protocol.sessionView(gpa, owned) catch return gpa.free(owned);
        if (!self.sendRequest("session.attach", payload, .{ .attach = .{ .gen = self.select_gen, .session = owned } }))
            gpa.free(owned);
    }

    fn detach(self: *Core, session: []const u8) void {
        // Its tunnel blocks pass to another client: a forward takes its block back when
        // it next needs a stream.
        for (self.forwards.items) |f| {
            if (std.mem.eql(u8, f.session, session)) f.claimed = false;
        }
        const payload = protocol.sessionView(self.gpa, session) catch return;
        _ = self.sendRequest("session.detach", payload, .silent);
    }

    fn requestList(self: *Core) void {
        _ = self.sendRequest("session.list", &.{}, .list);
    }

    fn requestHosts(self: *Core) void {
        _ = self.sendRequest(protocol.methods.hostsList, &.{}, .hosts_list);
    }

    fn requestView(self: *Core) void {
        const session = self.selected orelse return;
        if (!self.ready) return;
        if (self.view_inflight or self.resume_pending) {
            self.view_again = true;
            return;
        }
        const payload = protocol.sessionView(self.gpa, session) catch return;
        if (self.sendRequest(protocol.methods.sessionView, payload, .{ .view = self.select_gen })) {
            self.view_inflight = true;
            self.view_again = false;
        }
    }

    /// Bootstrap and attach answers supply the authoritative view of an attachment.
    /// A view update older than the held view is dropped.
    fn applyView(self: *Core, value: ?json.Value, tickets: ?json.Value, source: enum { bootstrap, attach, update }) void {
        var arena: std.heap.ArenaAllocator = .init(self.gpa);
        const parsed = model.parseView(arena.allocator(), value orelse .null) catch return arena.deinit();
        if (!self.isSelected(parsed.session)) return arena.deinit();
        if (source == .update) if (self.view) |held| {
            if (parsed.revision < held.revision) return arena.deinit();
        };
        const reattached = source == .attach and self.resume_pending;
        if (reattached) self.resume_pending = false;
        if (!std.mem.eql(u8, focusedBlock(self.view) orelse "", focusedBlock(parsed) orelse "")) self.invalidateComposerLocked();
        self.view = parsed;
        if (self.view_arena) |old| old.deinit();
        self.view_arena = arena;
        self.dirty = true;
        self.reconcile(tickets, source == .bootstrap or reattached);
        // Its panes know its blocks from here on.
        self.dropFollow(parsed.session);
        // Use the confirmed owners and surviving entries before taking or writing.
        var entries = self.entries.valueIterator();
        while (entries.next()) |entry| {
            const buffered = reattached and entry.*.input.items.len > 0;
            self.sizeStep(entry.*, if (buffered) .used else .online);
            if (buffered) self.flushInput(entry.*);
        }
        if (self.want_revision > parsed.revision or (reattached and self.view_again)) self.requestView();
    }

    // ------------------------------------------------------- the other sessions
    //
    // The core is attached to the selected session only. It reads the view of every other
    // session once per connection and again when a `session_view_changed` of that session
    // names a newer revision, and it keeps the lead block of each of their tabs current
    // from that block's events (event scope `all`). Nothing here makes a pane, opens a
    // data stream, or reaches the size rules. A core without a selected session follows
    // none.

    /// The view the state shows for `session`: the selected session's own, else the
    /// followed one. A session selected a moment ago keeps its followed view until the
    /// attach answer brings its own.
    fn viewOf(self: *const Core, session: []const u8) ?*const model.View {
        if (self.isSelected(session)) if (self.view) |*v| return v;
        const follow = self.follows.get(session) orelse return null;
        return if (follow.view) |*v| v else null;
    }

    fn listed(self: *const Core, session: []const u8) bool {
        for (self.sessions.items) |s| if (std.mem.eql(u8, s.id, session)) return true;
        return false;
    }

    fn removeSession(self: *Core, session: []const u8) void {
        for (self.sessions.items, 0..) |s, i| {
            if (!std.mem.eql(u8, s.id, session)) continue;
            _ = self.sessions.orderedRemove(i);
            self.gpa.free(s.id);
            self.gpa.free(s.label);
            self.dirty = true;
            return;
        }
    }

    // ---------------------------------------------------------------- the fence
    //
    // A debug build runs against servers whose other sessions are in use, and new ones
    // appear while it runs. The app names a label prefix once (intent `fence`); from then
    // on a session exists for this core only when its label starts with the prefix or the
    // core created it. `sessions` holds no other session, so the state, the pick, the
    // neighbour rule and the follows cannot reach one, and whatever still names one (a
    // user intent, an event, a bootstrap answer) is dropped where it comes in. A release
    // build sends no fence.

    /// A session with this label is one of this core's.
    fn admits(self: *const Core, session: []const u8, label: []const u8) bool {
        return self.fence_prefix.len == 0 or std.mem.startsWith(u8, label, self.fence_prefix) or self.made.contains(session);
    }

    /// `session` may be named in a request. With a fence the label decides, and the list
    /// is where the labels are: a session it does not hold does not exist.
    fn exists(self: *const Core, session: []const u8) bool {
        return self.fence_prefix.len == 0 or self.listed(session) or self.made.contains(session);
    }

    /// What a new connection's `session.bootstrap` attaches (see `bootstrapAttach`). With
    /// a fence always none: no label is known yet, so the core attaches the selected
    /// session, or picks one, when the list has arrived. Called with `lock` held.
    pub fn bootstrapWanted(self: *const Core) protocol.Attach {
        return if (self.fence_prefix.len > 0) .none else bootstrapAttach(self.selected, self.auto_pick);
    }

    /// A session a bootstrap answer reports attached although a fenced core asked for
    /// none: detached at once, before a view or a pane is made for it, unless it is this
    /// core's selection. True when it was let go.
    fn letGo(self: *Core, entry: json.Value) bool {
        if (self.fence_prefix.len == 0) return false;
        const id = model.str(model.get(model.get(entry, "view") orelse .null, "session_id")) orelse
            model.str(model.get(model.get(entry, "session") orelse .null, "session_id")) orelse return false;
        if (self.exists(id) and (self.selected == null or self.isSelected(id))) return false;
        self.detach(id);
        return true;
    }

    /// The selected session stopped existing for this core (renamed out of the prefix,
    /// another prefix): detach it and pick as after its destruction.
    fn expelSelected(self: *Core) void {
        const gone = self.selected orelse return;
        const next = if (self.auto_pick) neighbour(self.sessions.items, gone) else null;
        self.detach(gone);
        self.dropFollow(gone);
        // `next` is the id of another list entry, which the removal leaves alone.
        self.removeSession(gone);
        if (next) |n| self.attach(n) else self.setSelected(null);
    }

    /// `session` stopped existing for this core.
    fn unlist(self: *Core, session: []const u8) void {
        if (self.isSelected(session)) return self.expelSelected();
        self.dropFollow(session);
        self.removeSession(session);
    }

    /// Set the fence prefix; "" lifts the fence. The same prefix changes nothing. The
    /// sessions that no longer exist leave the list and are no longer followed; the
    /// selected one is detached and the core picks as after its destruction. The list is
    /// read again, for the sessions that exist now.
    fn setFence(self: *Core, prefix: []const u8) void {
        const gpa = self.gpa;
        if (std.mem.eql(u8, prefix, self.fence_prefix)) return;
        const copy = gpa.dupe(u8, prefix) catch return;
        gpa.free(self.fence_prefix);
        self.fence_prefix = copy;

        var selected_out = false;
        var i: usize = 0;
        while (i < self.sessions.items.len) {
            const s = self.sessions.items[i];
            if (self.admits(s.id, s.label)) {
                i += 1;
            } else if (self.isSelected(s.id)) {
                selected_out = true;
                i += 1;
            } else {
                self.dropFollow(s.id);
                _ = self.sessions.orderedRemove(i);
                gpa.free(s.id);
                gpa.free(s.label);
                self.dirty = true;
            }
        }
        if (selected_out) self.expelSelected();
        self.syncFollows();
        if (self.ready) self.requestList();
    }

    /// Make `follows` match the session list: one per session other than the selected
    /// one, none without a selected session. Asks for each view not asked for on this
    /// connection.
    fn syncFollows(self: *Core) void {
        // Removing invalidates the iterator: start over after each removal.
        sweep: while (true) {
            var it = self.follows.valueIterator();
            while (it.next()) |f| {
                if (self.selected != null and self.listed(f.*.session)) continue;
                self.dropFollow(f.*.session);
                continue :sweep;
            }
            break;
        }
        if (self.selected == null) return;
        for (self.sessions.items) |s| {
            if (self.isSelected(s.id)) continue;
            const follow = self.follows.get(s.id) orelse self.createFollow(s.id) orelse continue;
            if (!follow.asked) self.requestFollowView(follow);
        }
    }

    fn createFollow(self: *Core, session: []const u8) ?*Follow {
        const gpa = self.gpa;
        const follow = gpa.create(Follow) catch return null;
        const id = gpa.dupe(u8, session) catch {
            gpa.destroy(follow);
            return null;
        };
        follow.* = .{ .session = id };
        self.follows.put(gpa, id, follow) catch {
            gpa.free(id);
            gpa.destroy(follow);
            return null;
        };
        return follow;
    }

    /// Stop following `session`: its view and the lead blocks of its tabs go.
    fn dropFollow(self: *Core, session: []const u8) void {
        const follow = self.follows.get(session) orelse return;
        follow.view = null;
        self.syncLeads(follow);
        _ = self.follows.remove(follow.session);
        if (follow.arena) |arena| arena.deinit();
        self.gpa.free(follow.session);
        self.gpa.destroy(follow);
        self.dirty = true;
    }

    fn clearFollows(self: *Core) void {
        while (true) {
            var it = self.follows.valueIterator();
            const follow = it.next() orelse break;
            self.dropFollow(follow.*.session);
        }
    }

    /// The selected session `old` is another session's turn: follow it from the view and
    /// the block texts its panes hold, so that its tabs do not blink. Called while its
    /// panes are still there.
    fn handOver(self: *Core, old: []const u8) void {
        // A follow of the selected session exists only until its attach answers
        // (`applyView` ends it), so with one there is no view of the panes to hand over.
        if (!self.listed(old) or self.follows.contains(old)) return;
        const follow = self.createFollow(old) orelse return;
        follow.view = self.view;
        follow.arena = self.view_arena;
        self.view = null;
        self.view_arena = null;
        follow.want_revision = self.want_revision;
        // A view held while the link is down is read again by the next connection.
        follow.asked = self.ready and follow.view != null;
        self.syncLeads(follow);
        if (follow.view) |v| if (follow.want_revision > v.revision) self.requestFollowView(follow);
    }

    fn requestFollowView(self: *Core, follow: *Follow) void {
        if (!self.ready) return;
        if (follow.inflight) {
            follow.again = true;
            return;
        }
        const gpa = self.gpa;
        const session = gpa.dupe(u8, follow.session) catch return;
        const payload = protocol.sessionView(gpa, session) catch return gpa.free(session);
        if (!self.sendRequest(protocol.methods.sessionView, payload, .{ .follow_view = session })) return gpa.free(session);
        follow.inflight = true;
        follow.again = false;
        follow.asked = true;
    }

    /// A `session_view_changed` of a followed session: at most one view request. While
    /// one is in flight its answer is compared with the revision instead.
    fn followRevision(self: *Core, follow: *Follow, revision: i64) void {
        if (revision > follow.want_revision) follow.want_revision = revision;
        if (follow.inflight) return;
        if (follow.view) |v| if (revision <= v.revision) return;
        self.requestFollowView(follow);
    }

    /// Take a `session.view` result of a followed session; an older revision is dropped.
    fn applyFollowView(self: *Core, follow: *Follow, value: json.Value) void {
        var arena: std.heap.ArenaAllocator = .init(self.gpa);
        const parsed = model.parseView(arena.allocator(), value) catch return arena.deinit();
        if (!std.mem.eql(u8, parsed.session, follow.session)) return arena.deinit();
        if (follow.view) |held| {
            if (parsed.revision < held.revision) return arena.deinit();
        }
        if (follow.arena) |old| old.deinit();
        follow.arena = arena;
        follow.view = parsed;
        self.dirty = true;
        self.syncLeads(follow);
    }

    /// Make `leads` match the tabs of `follow`'s view: one per tab whose lead block is a
    /// terminal. A new lead asks the server what its terminal runs. Make `beside` match the
    /// other terminal blocks the tabs draw: a new one asks only for its process (#226).
    fn syncLeads(self: *Core, follow: *Follow) void {
        for ([_]*std.StringHashMapUnmanaged(*Lead){ &self.leads, &self.beside }) |map| {
            var it = map.valueIterator();
            while (it.next()) |l| {
                if (std.mem.eql(u8, l.*.session, follow.session)) l.*.seen = false;
            }
        }
        if (follow.view) |*view| for (view.windows) |*w| {
            const face = model.leadBlock(w);
            for (w.blocks) |*block| {
                if (!block.terminal) continue;
                if (face != null and block == face.?) {
                    const lead = self.leads.get(block.id) orelse self.createLead(&self.leads, follow.session, block.id) orelse continue;
                    lead.seen = true;
                    if (!lead.asked) self.requestLead(lead);
                } else {
                    const other = self.beside.get(block.id) orelse self.createLead(&self.beside, follow.session, block.id) orelse continue;
                    other.seen = true;
                    if (!other.asked) self.requestBeside(other);
                }
            }
        };
        for ([_]*std.StringHashMapUnmanaged(*Lead){ &self.leads, &self.beside }) |map| {
            sweep: while (true) {
                var rest = map.valueIterator();
                while (rest.next()) |l| {
                    if (l.*.seen) continue;
                    self.destroyLead(map, l.*);
                    continue :sweep;
                }
                break;
            }
        }
    }

    /// A lead of `block` of `session` in `map` (`leads`, `stashed` or `beside`).
    fn createLead(self: *Core, map: *std.StringHashMapUnmanaged(*Lead), session: []const u8, block: []const u8) ?*Lead {
        const gpa = self.gpa;
        const lead = gpa.create(Lead) catch return null;
        const id = gpa.dupe(u8, block) catch {
            gpa.destroy(lead);
            return null;
        };
        const owner = gpa.dupe(u8, session) catch {
            gpa.free(id);
            gpa.destroy(lead);
            return null;
        };
        lead.* = .{ .id = id, .session = owner, .serial = self.next_serial };
        map.put(gpa, id, lead) catch {
            gpa.free(owner);
            gpa.free(id);
            gpa.destroy(lead);
            return null;
        };
        self.next_serial += 1;
        // Only its process is asked about a block beside a followed tab's lead (#226).
        const full = map != &self.beside;
        // The pane of a session shown until now knows its block already.
        if (self.entries.get(block)) |entry| {
            self.setText(&lead.program, entry.program);
            self.setText(&lead.cwd, entry.cwd);
            lead.agent = entry.agent;
            // A block beside a lead keeps no report or title: nothing would keep them current.
            if (full) {
                if (entry.title) |title| _ = self.setTitle(&lead.title, title);
                _ = lead.tab_report.replace(gpa, entry.tab_report) catch false;
                lead.activity = entry.activity;
                lead.since = entry.since;
            }
            lead.asked = self.ready and entry.program.len > 0;
            // Outstanding pane requests still address the old serial after handover.
            if (lead.asked) {
                if (full and self.pending.contains(entry.last_status_request)) self.requestLeadStatus(lead);
                if (self.pending.contains(entry.last_process_request)) self.requestLeadProcess(lead);
                if (self.pending.get(entry.last_title_request)) |request| {
                    if (full and request == .entry_title and request.entry_title.revision == entry.title_revision) self.requestLeadTitle(lead);
                }
            }
        } else if (if (map == &self.leads) self.beside.get(block) else if (!full) self.leads.get(block) else null) |was| {
            // A pane of a followed tab that becomes its lead, or a lead that steps aside: it
            // keeps what it runs. A lead still asks for its report and title.
            self.setText(&lead.program, was.program);
            self.setText(&lead.cwd, was.cwd);
            lead.agent = was.agent;
            if (!full) lead.asked = was.asked and !was.process.inflight;
        }
        self.dirty = true;
        return lead;
    }

    fn destroyLead(self: *Core, map: *std.StringHashMapUnmanaged(*Lead), lead: *Lead) void {
        const gpa = self.gpa;
        _ = map.remove(lead.id);
        gpa.free(lead.session);
        lead.tab_report.deinit(gpa);
        if (lead.title) |title| gpa.free(title);
        gpa.free(lead.program);
        gpa.free(lead.cwd);
        gpa.free(lead.id);
        gpa.destroy(lead);
        self.dirty = true;
    }

    fn leadBySerial(self: *Core, serial: u64) ?*Lead {
        for ([_]*const std.StringHashMapUnmanaged(*Lead){ &self.leads, &self.stashed, &self.beside }) |map| {
            var it = map.valueIterator();
            while (it.next()) |lead| if (lead.*.serial == serial) return lead.*;
        }
        return null;
    }

    /// Ask what the lead's terminal runs, reports and is titled.
    fn requestLead(self: *Core, lead: *Lead) void {
        if (!self.ready) return;
        self.requestLeadProcess(lead);
        self.requestLeadStatus(lead);
        self.requestLeadTitle(lead);
        lead.asked = true;
    }

    /// Ask what a block beside a followed tab's lead runs (#226).
    fn requestBeside(self: *Core, other: *Lead) void {
        if (!self.ready) return;
        self.requestLeadProcess(other);
        other.asked = true;
    }

    fn requestLeadTitle(self: *Core, lead: *Lead) void {
        if (lead.title_refresh.inflight) {
            lead.title_refresh.again = true;
            return;
        }
        lead.title_refresh.again = false;
        const payload = protocol.blockCall(self.gpa, lead.session, lead.id) catch return;
        lead.title_refresh.inflight = self.sendRequest(protocol.methods.terminalTitle, payload, .{ .lead_title = .{ .serial = lead.serial, .revision = lead.title_revision } });
    }

    fn requestLeadProcess(self: *Core, lead: *Lead) void {
        if (lead.process.inflight) {
            lead.process.again = true;
            return;
        }
        lead.process.again = false;
        const payload = protocol.blockCall(self.gpa, lead.session, lead.id) catch return;
        lead.process.inflight = self.sendRequest(protocol.methods.terminalProcess, payload, .{ .lead_process = lead.serial });
    }

    fn requestLeadStatus(self: *Core, lead: *Lead) void {
        if (lead.status.inflight) {
            lead.status.again = true;
            return;
        }
        lead.status.again = false;
        const payload = protocol.blockCall(self.gpa, lead.session, lead.id) catch return;
        lead.status.inflight = self.sendRequest(protocol.methods.terminalProgramStatus, payload, .{ .lead_status = lead.serial });
    }

    /// A block of a session that is not selected sent an event: its lead's events are
    /// followed, a pane beside it asks only when its process changes (#226).
    fn followedBlockEvent(self: *Core, payload: json.Value) void {
        const block = model.str(model.get(payload, "block_id")) orelse return;
        if (self.leads.get(block)) |lead| return self.leadEvent(lead, payload);
        const other = self.beside.get(block) orelse return;
        if (std.mem.eql(u8, model.str(model.get(payload, "name")) orelse "", "process_changed")) self.requestLeadProcess(other);
    }

    /// A followed block sent an event: a lead block of a tab of a session that is not
    /// selected, or a minimized pane of the selected one. A block beside a lead asks only
    /// when its process changes (`followedBlockEvent`); any other block costs no request.
    fn leadEvent(self: *Core, lead: *Lead, payload: json.Value) void {
        const name = model.str(model.get(payload, "name")) orelse return;
        if (std.mem.eql(u8, name, "process_changed")) {
            self.requestLeadProcess(lead);
        } else if (std.mem.eql(u8, name, "program_status_changed") or std.mem.eql(u8, name, "program_status_removed")) {
            self.requestLeadStatus(lead);
        } else if (std.mem.eql(u8, name, "title_changed")) {
            const title = model.str(model.get(model.get(payload, "payload") orelse .null, "title")) orelse return;
            lead.title_revision +%= 1;
            // The one event that carries all of its answer.
            _ = self.setTitle(&lead.title, title);
        }
    }

    // ------------------------------------------------------------------- panes

    /// Make `entries` match the terminal blocks of the view: new blocks get a pane and
    /// a data stream, blocks that left every window lose theirs.
    fn reconcile(self: *Core, tickets: ?json.Value, reattached: bool) void {
        var it = self.entries.valueIterator();
        while (it.next()) |entry| entry.*.seen = false;

        if (self.view) |view| for (view.windows) |w| for (w.blocks) |b| {
            if (!b.terminal) continue;
            if (self.entries.get(b.id)) |entry| {
                if (entry.seen) continue;
                entry.seen = true;
                // Its stream ended (say, a server restart): open a new one.
                if (entry.stream) |stream| {
                    if (reattached) stream.restart();
                } else {
                    self.startStream(entry, null);
                }
                // Block events sent while the link was down are gone.
                if (reattached) {
                    self.requestBlockInfo(entry);
                    if (entry.pane.hidePreedit()) self.checkPreedit(entry);
                }
            } else {
                self.createEntry(b.id, ticketFor(tickets, b.id));
            }
            // The view names each block's owner; a block this client made is its own.
            if (self.entries.get(b.id)) |entry| entry.size.owned = self.isMe(b.owner);
        };
        // Before the panes go: a pane minimized now starts from what it knew.
        self.syncStashed(reattached);

        // Removing invalidates the iterator: start over after each removal.
        sweep: while (true) {
            var rest = self.entries.valueIterator();
            while (rest.next()) |entry| {
                if (entry.*.seen) continue;
                self.removeEntry(entry.*);
                continue :sweep;
            }
            break;
        }
    }

    /// Make `stashed` match the terminal blocks of the view's minimized layers that no tab
    /// draws. A new one asks what its terminal runs, reports and is titled (three requests),
    /// unless the pane it was until now knew already (#233). On a new connection
    /// (`reattached`) every one asks: a pane minimized while the link was down knew only
    /// what it ran before.
    fn syncStashed(self: *Core, reattached: bool) void {
        var it = self.stashed.valueIterator();
        while (it.next()) |l| l.*.seen = false;
        if (self.view) |view| for (view.windows) |w| for (w.hidden) |h| {
            if (!h.terminal) continue;
            if (self.entries.get(h.id)) |entry| if (entry.seen) continue;
            const lead = self.stashed.get(h.id) orelse self.createLead(&self.stashed, view.session, h.id) orelse continue;
            lead.seen = true;
            if (reattached) lead.asked = false;
            if (!lead.asked) self.requestLead(lead);
        };
        sweep: while (true) {
            var rest = self.stashed.valueIterator();
            while (rest.next()) |l| {
                if (l.*.seen) continue;
                self.destroyLead(&self.stashed, l.*);
                continue :sweep;
            }
            break;
        }
    }

    fn ticketFor(tickets: ?json.Value, block: []const u8) ?[]const u8 {
        for (model.items(tickets)) |t| {
            const id = model.str(model.get(t, "block_id")) orelse continue;
            if (std.mem.eql(u8, id, block)) return model.str(model.get(t, "ticket"));
        }
        return null;
    }

    fn createEntry(self: *Core, block: []const u8, ticket: ?[]const u8) void {
        const gpa = self.gpa;
        const p = pane.Pane.create(gpa, self.host.glyphs) catch return;
        p.setThreadTag(pane.Trace.blockTag(block));
        p.setFontSizePx(self.font_sp * self.px_per_sp);
        p.setFont(self.line_height);
        p.setPaddingPx(self.pad_px);
        const entry = gpa.create(Entry) catch return p.destroy();
        const id = gpa.dupe(u8, block) catch {
            gpa.destroy(entry);
            return p.destroy();
        };
        entry.* = .{ .core = self, .id = id, .serial = self.next_serial, .pane = p };
        self.entries.put(gpa, id, entry) catch return self.destroyEntry(entry);
        // A tab of a session followed until now, or a pane shown again: start from what
        // its row or its capsule showed.
        if (self.stashed.get(block) orelse self.leads.get(block) orelse self.beside.get(block)) |lead| {
            if (lead.title) |title| _ = self.setTitle(&entry.title, title);
            self.setText(&entry.program, lead.program);
            self.setText(&entry.cwd, lead.cwd);
            entry.agent = lead.agent;
            _ = entry.tab_report.replace(gpa, lead.tab_report) catch false;
            entry.activity = lead.activity;
            entry.since = lead.since;
            self.syncBlockInfo(entry);
        }
        self.requestBlockInfo(entry);
        self.next_serial += 1;
        self.startStream(entry, ticket);
    }

    fn startStream(self: *Core, entry: *Entry, ticket: ?[]const u8) void {
        entry.stream = ds.DataStream.start(self.gpa, self.io, self.server, self.net, entry.pane, ticket, .{
            .ctx = entry,
            .ticket = streamTicket,
            .title = streamTitle,
            .exited = streamExited,
            .may_dial = streamMayDial,
        }) catch return;
        self.live_streams += 1;
    }

    /// The block is gone: stop its stream. The pane goes with the stream's thread.
    fn removeEntry(self: *Core, entry: *Entry) void {
        _ = self.entries.remove(entry.id);
        _ = self.blocks.remove(entry.id);
        entry.removed = true;
        // The UI drops the block's surface now; stop drawing to it.
        entry.pane.detachSurface();
        if (entry.stream) |s| {
            s.stop();
            // Its thread may be waiting here for a ticket.
            self.lock.signal();
        } else {
            self.destroyEntry(entry);
        }
    }

    fn destroyEntry(self: *Core, entry: *Entry) void {
        const gpa = self.gpa;
        entry.pane.destroy();
        entry.input.deinit(gpa);
        entry.tab_report.deinit(gpa);
        if (entry.title) |title| gpa.free(title);
        gpa.free(entry.program);
        gpa.free(entry.cwd);
        gpa.free(entry.id);
        gpa.destroy(entry);
    }

    fn entryBySerial(self: *Core, serial: u64) ?*Entry {
        var it = self.entries.valueIterator();
        while (it.next()) |entry| if (entry.*.serial == serial) return entry.*;
        return null;
    }

    /// Bytes typed into a terminal. Dropped while disconnected and kept on the pane
    /// while its fenced session is reattaching.
    fn queueInput(self: *Core, entry: *Entry, bytes: []const u8) void {
        if (!self.ready or self.selected == null) return;
        // Typing is using the terminal: it follows this device from here on.
        self.sizeStep(entry, .used);
        entry.input.appendSlice(self.gpa, bytes) catch return;
        self.flushInput(entry);
    }

    fn flushInput(self: *Core, entry: *Entry) void {
        if (!self.ready or self.resume_pending) return;
        if (self.composer) |op| if (std.mem.eql(u8, op.block, entry.id)) return;
        if (entry.write_inflight or entry.input.items.len == 0) return;
        const session = self.selected orelse return;
        const n = writeChunkLen(entry.input.items, max_write_bytes);
        const payload = protocol.terminalWrite(self.gpa, session, entry.id, entry.input.items[0..n]) catch return;
        if (!self.sendRequest(protocol.methods.terminalWrite, payload, .{ .write = entry.serial })) return;
        entry.write_inflight = true;
        entry.input.replaceRangeAssumeCapacity(0, n, &.{});
    }

    // ----------------------------------------------------------- terminal size

    /// The control connection ended. Called with `lock` held; also a test seam for a
    /// dropped link.
    pub fn linkEnded(self: *Core) void {
        self.invalidateComposerLocked();
        // A request that another thread waits for ends: no answer comes on a new link.
        self.lock.signal();
        // A new link attaches the selected session only, and takes no tunnel block.
        for (self.forwards.items) |f| f.claimed = false;
        self.ws = null;
        self.link_up = false;
        self.ready = false;
        self.drop_link = false;
        self.clearLink();
    }

    /// Keep this client's id. Another id is another client to the server (the reconnect
    /// token was not accepted): it owns nothing yet.
    fn setClient(self: *Core, id: []const u8) void {
        if (std.mem.eql(u8, self.client_id, id)) return;
        const copy = self.gpa.dupe(u8, id) catch return;
        self.gpa.free(self.client_id);
        self.client_id = copy;
        var it = self.entries.valueIterator();
        while (it.next()) |entry| entry.*.size.owned = false;
    }

    fn isMe(self: *const Core, owner: ?[]const u8) bool {
        const id = owner orelse return false;
        return self.client_id.len > 0 and std.mem.eql(u8, id, self.client_id);
    }

    /// The grid that fits the surface the app gave the pane; null while that surface
    /// has no area (before the first one, or a size with a zero axis).
    fn wantedSize(entry: *const Entry) ?size_rules.Size {
        if (entry.surface_w == 0 or entry.surface_h == 0) return null;
        const fit = entry.pane.fitGrid(entry.surface_w, entry.surface_h);
        return .{ .cols = fit.cols, .rows = fit.rows, .cell_w = fit.cell_w, .cell_h = fit.cell_h };
    }

    /// Report `event` for the entry's pane to the size rules and send what they decide.
    /// The take goes first, but only takes run in order on the server: a size that
    /// arrives before its take applies when this client becomes the owner. Called with
    /// `lock` held.
    fn sizeStep(self: *Core, entry: *Entry, event: size_rules.Event) void {
        // Taking a block needs the attached session.
        const session: ?[]const u8 = if (self.ready and !self.resume_pending) self.selected else null;
        const actions = entry.size.step(event, wantedSize(entry), session != null);
        const to = session orelse return;
        const gpa = self.gpa;
        if (actions.take) {
            const sent = if (protocol.ownBlock(gpa, to, entry.id)) |payload|
                self.sendRequest(protocol.methods.ownBlock, payload, .{ .take = entry.serial })
            else |_|
                false;
            if (!sent) entry.size.take_inflight = false;
        }
        if (actions.resize) |size| {
            const sent = if (protocol.terminalResize(gpa, to, entry.id, size.cols, size.rows, size.cell_w, size.cell_h)) |payload|
                self.sendRequest(protocol.methods.terminalResize, payload, .{ .resize = entry.serial })
            else |_|
                false;
            if (!sent) entry.size.asked = null;
        }
    }

    // ------------------------------------------- data stream callbacks (its thread)

    /// Get a `data.connect` ticket for the entry's block; waits for the answer.
    fn streamTicket(ctx: *anyopaque, buf: *[ds.max_ticket_len]u8) ds.Ticket {
        const entry: *Entry = @ptrCast(@alignCast(ctx));
        const self = entry.core;
        self.lock.lock();
        defer self.lock.unlock();
        if (self.quit or entry.removed) return .gone;
        // Tickets need the attached session.
        if (self.resume_pending) return .retry;
        const session = self.selected orelse return .retry;
        const payload = protocol.dataConnect(self.gpa, session, entry.id) catch return .retry;
        if (!self.sendRequest(protocol.methods.dataConnect, payload, .{ .ticket = entry.serial })) return .retry;

        entry.ticket_state = .waiting;
        defer entry.ticket_state = .none;
        const deadline = ds.nowMs(self.io) + ticket_wait_ms;
        while (entry.ticket_state == .waiting) {
            if (self.quit or entry.removed) return .gone;
            const now = ds.nowMs(self.io);
            if (now >= deadline) return .retry;
            ds.timedWait(&self.lock, @intCast(@min(deadline - now, 500)));
        }
        if (entry.ticket_state != .ready) return .retry;
        @memcpy(buf[0..entry.ticket_len], entry.ticket[0..entry.ticket_len]);
        return .{ .ok = buf[0..entry.ticket_len] };
    }

    /// False while the control connection is stopped (`stopped`): a stream dialing an
    /// ssh locator again, say to RESUME, would start a new login.
    fn streamMayDial(ctx: *anyopaque) bool {
        const entry: *Entry = @ptrCast(@alignCast(ctx));
        const self = entry.core;
        self.lock.lock();
        defer self.lock.unlock();
        return self.stopped != self.epoch;
    }

    fn streamTitle(ctx: *anyopaque, title: []const u8) void {
        const entry: *Entry = @ptrCast(@alignCast(ctx));
        const self = entry.core;
        self.lock.lock();
        defer self.release();
        // A snapshot replayed on the data stream may be older than a control event.
        if (entry.removed or entry.has_control_title) return;
        self.updateEntryTitle(entry, title);
    }

    fn setEntryTitle(self: *Core, entry: *Entry, title: []const u8) void {
        entry.has_control_title = true;
        entry.title_revision +%= 1;
        self.updateEntryTitle(entry, title);
    }

    fn updateEntryTitle(self: *Core, entry: *Entry, title: []const u8) void {
        if (self.setTitle(&entry.title, title)) self.syncBlockInfo(entry);
    }

    fn setTitle(self: *Core, slot: *?[]u8, title: []const u8) bool {
        if (slot.*) |old| if (std.mem.eql(u8, old, title)) return false;
        const copy = self.gpa.dupe(u8, title) catch return false;
        if (slot.*) |old| self.gpa.free(old);
        slot.* = copy;
        self.dirty = true;
        return true;
    }

    /// Last call of a stream thread. Nothing of the core is touched after the unlock:
    /// `destroy` frees the core once `live_streams` is zero.
    fn streamExited(ctx: *anyopaque) void {
        const entry: *Entry = @ptrCast(@alignCast(ctx));
        const self = entry.core;
        self.lock.lock();
        if (entry.stream) |s| s.destroy();
        entry.stream = null;
        if (entry.removed) self.destroyEntry(entry);
        self.live_streams -= 1;
        self.lock.signal();
        self.lock.unlock();
    }
};
