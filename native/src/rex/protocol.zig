//! Rex control/data protocol (API revision 18): message builders and parsers. No I/O.
//! Argument names follow the Method Reference of the server's /llms.txt.

const std = @import("std");
const json = std.json;
const Allocator = std.mem.Allocator;
const model = @import("model.zig");

pub const Id = model.Id;

pub const api_revision = 18;
pub const control_protocol = "rex.control.v1";
pub const terminal_subprotocol = "rex.data.terminal.v1";
pub const terminal_creator = model.terminal_creator;
pub const terminal_data_ref = "terminal";
pub const shell_flavor = "com.superlogical.terminal.shell";

/// The server rejects a hello of 512 bytes or more.
pub const max_hello_bytes = 511;
const max_version_bytes = 64;

/// Method names, one per payload builder below (same identifier as the builder).
pub const methods = struct {
    pub const bootstrap = "session.bootstrap";
    pub const sessionView = "session.view";
    pub const createSession = "session.create";
    pub const dataConnect = "data.connect";
    pub const resizePane = "session.resize_pane";
    pub const newSplit = "session.new_split";
    pub const floatBlock = "session.float_block";
    pub const setLayerState = "session.set_layer_state";
    pub const swapBlocks = "session.swap_blocks";
    pub const moveBlock = "session.move_block";
    pub const zoomBlock = "session.zoom_block";
    pub const closeBlock = "block.close";
    pub const focusBlock = "session.focus_block";
    pub const newWindow = "session.new_window";
    pub const moveWindow = "session.move_window";
    pub const focusWindow = "session.focus_window";
    pub const closeWindow = "session.close_window";
    pub const destroySession = "session.destroy";
    pub const setSessionLabel = "session.set_label";
    pub const setWindowLabel = "session.set_window_label";
    pub const blockEvent = "block.event";
    pub const ownBlock = "session.own_block";
    pub const terminalWrite = "com.superlogical.terminal.write";
    pub const uploadBegin = "com.superlogical.terminal.upload_begin";
    pub const downloadBegin = "com.superlogical.terminal.download_begin";
    pub const attach = "session.attach";
    pub const newBlock = "session.new_block";
    pub const listBlocks = "session.list_blocks";
    pub const tunnelInfo = "com.superlogical.tunnel.info";
    /// Takes `blockCall`.
    pub const terminalTitle = "com.superlogical.terminal.title";
    pub const terminalProcess = "com.superlogical.terminal.process";
    pub const terminalProgramStatus = "com.superlogical.terminal.program_status";
    /// Takes `terminalFormat`.
    pub const terminalFormat = "com.superlogical.terminal.format";
    /// Takes `terminalBlobRead`.
    pub const terminalBlobRead = "com.superlogical.terminal.blob_read";
    /// Takes `blockCall`. Whether the terminal echoes typed input; not_found on Windows.
    pub const terminalInputMode = "com.superlogical.terminal.input_mode";
    pub const terminalResize = "com.superlogical.terminal.resize";
    /// Takes `blockCall`.
    pub const terminalSize = "com.superlogical.terminal.size";
    /// Takes no payload.
    pub const whoami = "server.whoami";
    /// Takes no payload.
    pub const hostsList = "hosts.list";
    pub const hostsPut = "hosts.put";
    pub const hostsRemove = "hosts.remove";
};

// --- control messages -----------------------------------------------------------------

/// The hello sent right after the WebSocket opens. Always under 512 bytes: `version` is
/// cut to 64 bytes, and a reconnect token that still does not fit is an error.
pub fn hello(gpa: Allocator, version: []const u8, reconnect_token: ?[]const u8) error{ OutOfMemory, HelloTooLarge }![]u8 {
    var b: Builder = .init(gpa);
    defer b.deinit();
    b.begin();
    b.field("type", "hello");
    b.fieldValue("api_revision", api_revision);
    if (reconnect_token) |t| if (t.len > 0) b.field("reconnect_token", t);
    b.key("client");
    b.begin();
    b.field("kind", "app");
    b.field("platform", "other");
    b.field("name", "Velo");
    b.field("version", utf8Prefix(version, max_version_bytes));
    b.end();
    b.end();
    const out = try b.finish();
    if (out.len > max_hello_bytes) {
        gpa.free(out);
        return error.HelloTooLarge;
    }
    return out;
}

/// A request. `payload_json` is a JSON object text; empty leaves `payload` out.
pub fn request(gpa: Allocator, id: u64, method: []const u8, payload_json: []const u8) Allocator.Error![]u8 {
    var b: Builder = .init(gpa);
    defer b.deinit();
    var id_buf: [20]u8 = undefined;
    b.begin();
    b.field("type", "request");
    b.field("id", std.fmt.bufPrint(&id_buf, "{d}", .{id}) catch unreachable);
    b.field("method", method);
    if (payload_json.len > 0) {
        b.key("payload");
        b.raw(payload_json);
    }
    b.end();
    return b.finish();
}

pub const Incoming = union(enum) {
    welcome: struct { client_id: []const u8, reconnect_token: []const u8, api_revision: i64 },
    response: struct { id: u64, seq: i64, payload: ?json.Value, err: ?ResponseError },
    event: struct { seq: i64, session: ?Id, kind: []const u8, payload: json.Value }, // session_event is unwrapped: kind = inner type
    other,
};
pub const ResponseError = struct { code: []const u8, message: []const u8, status: i64 };

/// Parse one control message. Everything returned lives in `arena` (nothing points into
/// `text`). Unknown message types are `.other`.
pub fn parse(arena: Allocator, text: []const u8) !Incoming {
    const root = try json.parseFromSliceLeaky(json.Value, arena, text, .{ .allocate = .alloc_always });
    if (root != .object) return error.InvalidMessage;
    const kind = model.str(model.get(root, "type")) orelse return .other;

    if (std.mem.eql(u8, kind, "welcome")) {
        const protocol = model.str(model.get(root, "protocol")) orelse return error.UnsupportedProtocol;
        if (!std.mem.eql(u8, protocol, control_protocol)) return error.UnsupportedProtocol;
        const client = model.get(root, "client") orelse json.Value.null;
        return .{ .welcome = .{
            .client_id = model.str(model.get(client, "id")) orelse "",
            .reconnect_token = model.str(model.get(client, "reconnect_token")) orelse "",
            .api_revision = model.int(model.get(root, "api_revision")) orelse 0,
        } };
    }

    if (std.mem.eql(u8, kind, "response")) {
        const id_text = model.str(model.get(root, "id")) orelse return .other;
        // This client only sends decimal ids.
        const id = std.fmt.parseUnsigned(u64, id_text, 10) catch return .other;
        const err: ?ResponseError = if (model.get(root, "error")) |e| switch (e) {
            .object => .{
                .code = model.str(model.get(e, "code")) orelse "internal",
                .message = model.str(model.get(e, "message")) orelse "",
                .status = model.int(model.get(e, "status")) orelse 0,
            },
            else => null,
        } else null;
        return .{ .response = .{
            .id = id,
            .seq = model.int(model.get(root, "seq")) orelse 0,
            .payload = if (err == null) model.get(root, "payload") else null,
            .err = err,
        } };
    }

    if (std.mem.eql(u8, kind, "event")) {
        const seq = model.int(model.get(root, "seq")) orelse 0;
        var ev = model.get(root, "payload") orelse return .other;
        var name = model.str(model.get(ev, "type")) orelse return .other;
        var session: ?Id = null;
        if (std.mem.eql(u8, name, "session_event")) {
            const wrapper = model.get(ev, "payload") orelse return .other;
            session = model.str(model.get(wrapper, "session_id"));
            ev = model.get(wrapper, "event") orelse return .other;
            name = model.str(model.get(ev, "type")) orelse return .other;
        }
        return .{ .event = .{
            .seq = seq,
            .session = session,
            .kind = name,
            .payload = model.get(ev, "payload") orelse json.Value.null,
        } };
    }

    return .other;
}

// --- payload builders (JSON object text; caller frees) --------------------------------

/// Which sessions `session.bootstrap` attaches.
pub const Attach = union(enum) {
    /// Leave `attach_session_ids` out: the server picks one (`selected_session`).
    pick,
    /// `attach_session_ids: []`: attach none.
    none,
    /// Exactly this session (`attached_sessions`).
    session: Id,
};

/// `session.bootstrap`: every session's events, block events included (scope `all`: the
/// core follows the tabs of the sessions it is not attached to), and terminal data
/// tickets, attaching what `attach` says.
pub fn bootstrap(gpa: Allocator, attach: Attach) Allocator.Error![]u8 {
    var b: Builder = .init(gpa);
    defer b.deinit();
    b.begin();
    b.field("event_scope", "all");
    switch (attach) {
        .pick => {},
        .none => {
            b.key("attach_session_ids");
            b.beginArray();
            b.endArray();
        },
        .session => |s| {
            b.key("attach_session_ids");
            b.beginArray();
            b.string(s);
            b.endArray();
        },
    }
    b.key("data_tickets");
    b.beginArray();
    b.begin();
    b.field("creator_name", terminal_creator);
    b.field("data_ref_id", terminal_data_ref);
    b.field("subprotocol", terminal_subprotocol);
    b.end();
    b.endArray();
    b.end();
    return b.finish();
}

/// `session.create` with one window holding a shell, so the session is usable at once.
/// Without `label` the server names the session (`quiet-meadow` and the like).
pub fn createSession(gpa: Allocator, label: ?[]const u8) Allocator.Error![]u8 {
    var b: Builder = .init(gpa);
    defer b.deinit();
    b.begin();
    if (label) |l| if (l.len > 0) b.field("label", l);
    b.key("initial_windows");
    b.beginArray();
    b.begin();
    b.key("layout");
    shellLayout(&b);
    b.end();
    b.endArray();
    b.end();
    return b.finish();
}

pub fn sessionView(gpa: Allocator, session: Id) Allocator.Error![]u8 {
    var b: Builder = .init(gpa);
    defer b.deinit();
    b.begin();
    b.field("session_id", session);
    b.end();
    return b.finish();
}

pub fn dataConnect(gpa: Allocator, session: Id, block: Id) Allocator.Error![]u8 {
    var b: Builder = .init(gpa);
    defer b.deinit();
    b.begin();
    b.field("session_id", session);
    b.field("block_id", block);
    b.field("data_ref_id", terminal_data_ref);
    b.field("subprotocol", terminal_subprotocol);
    b.end();
    return b.finish();
}

/// `data.connect` for the data source `data_ref` of `block`, such as a `conn:` stream of a
/// tunnel block.
pub fn streamConnect(gpa: Allocator, session: Id, block: Id, data_ref: []const u8, subprotocol: []const u8) Allocator.Error![]u8 {
    var b: Builder = .init(gpa);
    defer b.deinit();
    b.begin();
    b.field("session_id", session);
    b.field("block_id", block);
    b.field("data_ref_id", data_ref);
    b.field("subprotocol", subprotocol);
    b.end();
    return b.finish();
}

/// The flavor of tunnel block that connects each stream its owner opens to one host and port.
pub const tunnel_dial = "com.superlogical.tunnel.dial";

/// `session.new_block` of a dial tunnel to `host`:`port` of the remote, which the phone
/// forwards from `127.0.0.1:client_port` (`client_address` is only shown to people).
pub fn newTunnel(gpa: Allocator, session: Id, host: []const u8, port: u16, client_port: u16) Allocator.Error![]u8 {
    var address: [32]u8 = undefined;
    var b: Builder = .init(gpa);
    defer b.deinit();
    b.begin();
    b.field("session_id", session);
    b.field("flavor", tunnel_dial);
    b.key("options");
    b.begin();
    b.field("host", host);
    b.fieldValue("port", port);
    b.field("client_address", std.fmt.bufPrint(&address, "127.0.0.1:{d}", .{client_port}) catch unreachable);
    b.end();
    b.end();
    return b.finish();
}

pub const SplitDirection = enum { left, right, above, below };

/// The server's split `direction` for a side of the anchor: left and right split it
/// horizontally, above and below vertically.
fn splitAxis(direction: SplitDirection) []const u8 {
    return if (direction == .above or direction == .below) "vertical" else "horizontal";
}

/// The server's `side` for a side of the anchor: left and above are the before part.
fn splitSide(direction: SplitDirection) []const u8 {
    return if (direction == .above or direction == .left) "before" else "after";
}

/// `session.new_split`: insert and focus one shell on the chosen side of the anchor.
pub fn newSplit(gpa: Allocator, session: Id, anchor_block: Id, direction: SplitDirection) Allocator.Error![]u8 {
    var b: Builder = .init(gpa);
    defer b.deinit();
    b.begin();
    b.field("session_id", session);
    b.field("anchor_id", anchor_block);
    b.field("direction", splitAxis(direction));
    b.field("side", splitSide(direction));
    b.key("layout");
    shellLayout(&b);
    b.fieldValue("focus", true);
    b.end();
    return b.finish();
}

pub const SplitResize = struct { before: Id, after: Id, direction: Id, ratio: f64 };

/// The program status record a `paneWrite` answers: the write goes out only while the
/// server still reports record `id` blocked with this `kind` and, when `updated_at` is
/// set, the same report time. Without it the time is not compared: the rest of an answer
/// whose first write already passed the full check.
pub const PaneExpect = struct { id: []const u8, kind: ?[]const u8, updated_at: ?[]const u8 };

/// Intent `paneWrite`: an answer to a pane's prompt, from a notification. `tag` names the
/// result the core reports back (`Core.paneWrite`).
pub const PaneWrite = struct { tag: []const u8, session: Id, block: Id, text: []const u8, expect: PaneExpect };

/// Intent `paneScreen`: the last lines of a pane's text, for the notification of its prompt.
/// `tag` names the answer the core reports back (`Core.paneScreen`).
pub const PaneScreen = struct { tag: []const u8, session: Id, block: Id };

pub fn resizePane(gpa: Allocator, session: Id, resize: SplitResize) Allocator.Error![]u8 {
    var b: Builder = .init(gpa);
    defer b.deinit();
    b.begin();
    b.field("session_id", session);
    b.field("before_block_id", resize.before);
    b.field("after_block_id", resize.after);
    b.field("direction", resize.direction);
    b.fieldValue("ratio", resize.ratio);
    b.end();
    return b.finish();
}

pub fn floatBlock(gpa: Allocator, session: Id, block: Id) Allocator.Error![]u8 {
    var b: Builder = .init(gpa);
    defer b.deinit();
    b.begin();
    b.field("session_id", session);
    b.field("block_id", block);
    b.key("bounds");
    b.raw("[0.15,0.15,0.7,0.7]");
    b.end();
    return b.finish();
}

/// `session.set_layer_state`: `state` is "normal" or "minimized". `focus` is left out when null.
pub fn setLayerState(gpa: Allocator, session: Id, layer: Id, state: []const u8, focus: ?bool) Allocator.Error![]u8 {
    var b: Builder = .init(gpa);
    defer b.deinit();
    b.begin();
    b.field("session_id", session);
    b.field("layer_id", layer);
    b.field("state", state);
    if (focus) |f| b.fieldValue("focus", f);
    b.end();
    return b.finish();
}

pub fn swapBlocks(gpa: Allocator, session: Id, first: Id, second: Id) Allocator.Error![]u8 {
    return json.Stringify.valueAlloc(gpa, .{ .session_id = session, .block_id = first, .other_block_id = second }, .{});
}

/// `session.move_block`: dock `block` to the right of `anchor`.
pub fn moveBlock(gpa: Allocator, session: Id, block: Id, anchor: Id) Allocator.Error![]u8 {
    return moveTo(gpa, session, block, anchor, .right, null);
}

/// `session.move_block`: move `block` to the `direction` side of the tiled block `anchor`,
/// placed as `newSplit` places a new pane. `ratio` is the share of the before part of the
/// new split, which is the moved block for left and above; without it the server splits
/// evenly.
pub fn moveTo(gpa: Allocator, session: Id, block: Id, anchor: Id, direction: SplitDirection, ratio: ?f64) Allocator.Error![]u8 {
    var b: Builder = .init(gpa);
    defer b.deinit();
    b.begin();
    b.field("session_id", session);
    b.field("block_id", block);
    b.field("anchor_block_id", anchor);
    b.field("direction", splitAxis(direction));
    b.field("side", splitSide(direction));
    if (ratio) |r| b.fieldValue("ratio", r);
    b.end();
    return b.finish();
}

pub fn zoomBlock(gpa: Allocator, session: Id, block: Id, zoom: bool) Allocator.Error![]u8 {
    var b: Builder = .init(gpa);
    defer b.deinit();
    b.begin();
    b.field("session_id", session);
    b.field("block_id", block);
    b.fieldValue("zoom", zoom);
    b.end();
    return b.finish();
}

/// `block.close`; never closes the last block of a window.
pub fn closeBlock(gpa: Allocator, session: Id, block: Id) Allocator.Error![]u8 {
    var b: Builder = .init(gpa);
    defer b.deinit();
    b.begin();
    b.field("session_id", session);
    b.field("block_id", block);
    b.fieldValue("keep_last", true);
    b.end();
    return b.finish();
}

pub fn focusBlock(gpa: Allocator, session: Id, block: Id) Allocator.Error![]u8 {
    var b: Builder = .init(gpa);
    defer b.deinit();
    b.begin();
    b.field("session_id", session);
    b.field("block_id", block);
    b.end();
    return b.finish();
}

/// `session.new_window` holding one shell.
pub fn newWindow(gpa: Allocator, session: Id) Allocator.Error![]u8 {
    var b: Builder = .init(gpa);
    defer b.deinit();
    b.begin();
    b.field("session_id", session);
    b.key("layout");
    shellLayout(&b);
    b.fieldValue("focus", true);
    b.end();
    return b.finish();
}

/// An unfocused tab with one temporary tiled anchor. The program exits on its own
/// so a lost connection cannot leave the anchor running indefinitely.
pub fn newDockTab(gpa: Allocator, session: Id) Allocator.Error![]u8 {
    var b: Builder = .init(gpa);
    defer b.deinit();
    b.begin();
    b.field("session_id", session);
    b.fieldValue("focus", false);
    b.key("layout");
    b.raw(
        \\{"block":{"flavor":"com.superlogical.terminal.shell","label":"Temporary dock anchor",
        \\"options":{"shell":"none","command":["sleep","60"],"exit":{"on_completion":true}}}}
    );
    b.end();
    return b.finish();
}

/// `session.move_window`: put tab `window` before tab `before`; null puts it at the end.
pub fn moveWindow(gpa: Allocator, session: Id, window: Id, before: ?Id) Allocator.Error![]u8 {
    var b: Builder = .init(gpa);
    defer b.deinit();
    b.begin();
    b.field("session_id", session);
    b.field("window_id", window);
    if (before) |w| b.field("before_window_id", w);
    b.end();
    return b.finish();
}

/// `block.close` which can close the tab if the named block is its last block.
pub fn closeBlockAllowLast(gpa: Allocator, session: Id, block: Id) Allocator.Error![]u8 {
    return json.Stringify.valueAlloc(gpa, .{ .session_id = session, .block_id = block, .keep_last = false }, .{});
}

pub fn focusWindow(gpa: Allocator, session: Id, window: Id) Allocator.Error![]u8 {
    var b: Builder = .init(gpa);
    defer b.deinit();
    b.begin();
    b.field("session_id", session);
    b.field("window_id", window);
    b.end();
    return b.finish();
}

/// `session.close_window`: the window and every block in it.
pub fn closeWindow(gpa: Allocator, session: Id, window: Id) Allocator.Error![]u8 {
    return focusWindow(gpa, session, window);
}

/// `session.destroy`: the session and every block in it.
pub fn destroySession(gpa: Allocator, session: Id) Allocator.Error![]u8 {
    return sessionView(gpa, session);
}

/// `session.set_label`. An empty label restores the name the server chose for the session.
pub fn setSessionLabel(gpa: Allocator, session: Id, label: []const u8) Allocator.Error![]u8 {
    var b: Builder = .init(gpa);
    defer b.deinit();
    b.begin();
    b.field("session_id", session);
    b.field("label", label);
    b.end();
    return b.finish();
}

/// `session.set_window_label`. An empty label restores the default made from the window's
/// ID (`Window 89abcdef`).
pub fn setWindowLabel(gpa: Allocator, session: Id, window: Id, label: []const u8) Allocator.Error![]u8 {
    var b: Builder = .init(gpa);
    defer b.deinit();
    b.begin();
    b.field("session_id", session);
    b.field("window_id", window);
    b.field("label", label);
    b.end();
    return b.finish();
}

pub const max_composer_text = 64 * 1024 - 13; // room for both paste delimiters and a separator

fn validateLiteral(text: []const u8, paste: bool) !void {
    const view = std.unicode.Utf8View.init(text) catch return error.ControlCharacter;
    var it = view.iterator();
    while (it.nextCodepoint()) |cp| {
        if (cp == '\n' or cp == '\t') {
            if (!paste) return error.PasteModeRequired;
        } else if (cp < 0x20 or (cp >= 0x7f and cp <= 0x9f)) return error.ControlCharacter;
    }
}

/// One terminal write, with no Enter. The server itself inserts a completed upload's path.
pub fn composerInput(gpa: Allocator, text: []const u8, with_file: bool, paste: bool) ![]u8 {
    if (text.len > max_composer_text) return error.TextTooLarge;
    try validateLiteral(text, paste);
    return std.mem.concat(gpa, u8, &.{ if (paste) "\x1b[200~" else "", text, if (with_file) " " else "", if (paste) "\x1b[201~" else "" });
}

const paste_start = "\x1b[200~";
const paste_end = "\x1b[201~";

/// True when `text`, typed into a terminal, can do more than be inserted: a line break
/// runs the line in a shell, and any other control character is a key or starts a terminal
/// sequence. A tab is left out: it completes or indents and ends no line.
fn pasteActs(text: []const u8) bool {
    for (text, 0..) |byte, i| {
        if (byte == '\t') continue;
        if (byte < 0x20 or byte == 0x7f) return true;
        // U+0080 to U+009F, the C1 control characters, in UTF-8.
        if (byte == 0xc2 and i + 1 < text.len and text[i + 1] >= 0x80 and text[i + 1] <= 0x9f) return true;
    }
    return false;
}

/// The bytes a paste of `text` writes to a terminal. Caller frees.
/// A terminal with bracketed paste (`bracketed`) gets the text between the two paste marks,
/// so its program takes it as one paste and runs nothing by itself. The end mark is taken
/// out of the text first, so that the text cannot end the paste and type what follows.
/// Without bracketed paste the text arrives as typed keys: a text that acts (`pasteActs`)
/// is `error.NeedsConfirm` unless the user `confirmed` it.
pub fn pasteInput(gpa: Allocator, text: []const u8, bracketed: bool, confirmed: bool) error{ OutOfMemory, NeedsConfirm }![]u8 {
    if (!bracketed) {
        if (!confirmed and pasteActs(text)) return error.NeedsConfirm;
        return gpa.dupe(u8, text);
    }
    var out: std.ArrayList(u8) = try .initCapacity(gpa, paste_start.len + text.len + paste_end.len);
    errdefer out.deinit(gpa);
    out.appendSliceAssumeCapacity(paste_start);
    for (text) |byte| {
        out.appendAssumeCapacity(byte);
        // Looked for after every byte: the bytes around an end mark that was taken out
        // must not join into another one.
        if (std.mem.endsWith(u8, out.items[paste_start.len..], paste_end)) out.items.len -= paste_end.len;
    }
    out.appendSliceAssumeCapacity(paste_end);
    return out.toOwnedSlice(gpa);
}

pub fn validateUploadName(name: []const u8) !void {
    if (name.len == 0 or name.len > 255 or std.mem.findAny(u8, name, "/\\") != null or
        std.mem.eql(u8, name, ".") or std.mem.eql(u8, name, "..")) return error.BadFileName;
    validateLiteral(name, false) catch return error.BadFileName;
}

pub fn uploadBegin(gpa: Allocator, session: Id, block: Id, random: [16]u8, name: []const u8, size: usize) Allocator.Error![]u8 {
    var uuid = random;
    uuid[6] = (uuid[6] & 0x0f) | 0x40;
    uuid[8] = (uuid[8] & 0x3f) | 0x80;
    var ref: [43]u8 = undefined;
    const id = std.fmt.bufPrint(&ref, "upload:{x}-{x}-{x}-{x}-{x}", .{ uuid[0..4], uuid[4..6], uuid[6..8], uuid[8..10], uuid[10..16] }) catch unreachable;
    var b: Builder = .init(gpa);
    defer b.deinit();
    b.begin();
    b.field("session_id", session);
    b.field("block_id", block);
    b.key("args");
    b.begin();
    b.field("ref", id);
    b.field("name", name);
    b.fieldValue("size", size);
    b.end();
    b.end();
    return b.finish();
}

/// `download_begin`: open `path` on the terminal's machine for one `rex.data.bytes.v1` stream.
/// `if_none_match` is the version of a copy already held: the server then sends nothing if it
/// is current.
pub fn downloadBegin(gpa: Allocator, session: Id, block: Id, random: [16]u8, path: []const u8, if_none_match: ?[]const u8) Allocator.Error![]u8 {
    var uuid = random;
    uuid[6] = (uuid[6] & 0x0f) | 0x40;
    uuid[8] = (uuid[8] & 0x3f) | 0x80;
    var ref: [45]u8 = undefined;
    const id = std.fmt.bufPrint(&ref, "download:{x}-{x}-{x}-{x}-{x}", .{ uuid[0..4], uuid[4..6], uuid[6..8], uuid[8..10], uuid[10..16] }) catch unreachable;
    var b: Builder = .init(gpa);
    defer b.deinit();
    b.begin();
    b.field("session_id", session);
    b.field("block_id", block);
    b.key("args");
    b.begin();
    b.field("ref", id);
    b.field("path", path);
    if (if_none_match) |version| b.field("if_none_match", version);
    b.end();
    b.end();
    return b.finish();
}

/// One wheel notch at zero-based terminal coordinates. Rex encodes the event for the PTY.
pub fn wheel(gpa: Allocator, session: Id, block: Id, x: u16, y: u16, delta_y: i8) Allocator.Error![]u8 {
    return json.Stringify.valueAlloc(gpa, .{
        .session_id = session,
        .block_id = block,
        .event = .{ .type = "wheel", .payload = .{ .x = x, .y = y, .delta_y = delta_y } },
    }, .{});
}

/// `block.event` mouse: a button `action` ("press" or "release") at a cell. The server encodes it
/// for the mouse mode the terminal's program turned on.
pub fn mouse(gpa: Allocator, session: Id, block: Id, action: []const u8, x: u16, y: u16) Allocator.Error![]u8 {
    return json.Stringify.valueAlloc(gpa, .{
        .session_id = session,
        .block_id = block,
        .event = .{ .type = "mouse", .payload = .{ .action = action, .button = "left", .x = x, .y = y } },
    }, .{});
}

/// `com.superlogical.terminal.write`: block methods take their own arguments under `args`.
pub fn terminalWrite(gpa: Allocator, session: Id, block: Id, data: []const u8) Allocator.Error![]u8 {
    var b: Builder = .init(gpa);
    defer b.deinit();
    b.begin();
    b.field("session_id", session);
    b.field("block_id", block);
    b.key("args");
    b.begin();
    b.field("data", data);
    b.end();
    b.end();
    return b.finish();
}

/// `session.own_block` in mode `take`: the caller owns `block` at once, so the terminal
/// takes the caller's size.
pub fn ownBlock(gpa: Allocator, session: Id, block: Id) Allocator.Error![]u8 {
    var b: Builder = .init(gpa);
    defer b.deinit();
    b.begin();
    b.field("session_id", session);
    b.key("block_ids");
    b.beginArray();
    b.string(block);
    b.endArray();
    b.field("mode", "take");
    b.end();
    return b.finish();
}

/// `com.superlogical.terminal.resize`: the size this client wants for the terminal, with
/// the size of one of its cells in px. The server takes 1 to 65535 for each.
pub fn terminalResize(gpa: Allocator, session: Id, block: Id, cols: u16, rows: u16, cell_w_px: u16, cell_h_px: u16) Allocator.Error![]u8 {
    var b: Builder = .init(gpa);
    defer b.deinit();
    b.begin();
    b.field("session_id", session);
    b.field("block_id", block);
    b.key("args");
    b.begin();
    b.fieldValue("columns", @max(cols, 1));
    b.fieldValue("rows", @max(rows, 1));
    b.fieldValue("cell_width_px", @max(cell_w_px, 1));
    b.fieldValue("cell_height_px", @max(cell_h_px, 1));
    b.end();
    b.end();
    return b.finish();
}

/// A block method that takes no arguments of its own, such as
/// `com.superlogical.terminal.process`.
pub fn blockCall(gpa: Allocator, session: Id, block: Id) Allocator.Error![]u8 {
    var b: Builder = .init(gpa);
    defer b.deinit();
    b.begin();
    b.field("session_id", session);
    b.field("block_id", block);
    b.end();
    return b.finish();
}

/// `com.superlogical.terminal.format` of the terminal's text (screen and scrollback), spaces
/// at the ends of lines removed. With more than `max_bytes` of it the answer holds its start
/// and names the rest (`id`, `total`) for `terminalBlobRead`.
pub fn terminalFormat(gpa: Allocator, session: Id, block: Id, max_bytes: u32) Allocator.Error![]u8 {
    var b: Builder = .init(gpa);
    defer b.deinit();
    b.begin();
    b.field("session_id", session);
    b.field("block_id", block);
    b.key("args");
    b.begin();
    b.fieldValue("trim", true);
    b.fieldValue("max_bytes", max_bytes);
    b.end();
    b.end();
    return b.finish();
}

/// `com.superlogical.terminal.blob_read`: up to `max_bytes` of the result `id` (of a
/// `terminalFormat`), from byte `offset` of it.
pub fn terminalBlobRead(gpa: Allocator, session: Id, block: Id, id: i64, offset: i64, max_bytes: u32) Allocator.Error![]u8 {
    var b: Builder = .init(gpa);
    defer b.deinit();
    b.begin();
    b.field("session_id", session);
    b.field("block_id", block);
    b.key("args");
    b.begin();
    b.fieldValue("id", id);
    b.fieldValue("offset", offset);
    b.fieldValue("max_bytes", max_bytes);
    b.end();
    b.end();
    return b.finish();
}

/// `hosts.put` of a new entry with one endpoint. The app never rewrites an existing entry,
/// so it never has to carry fields it does not know.
pub fn hostsPut(gpa: Allocator, id: []const u8, label: []const u8, url: []const u8) Allocator.Error![]u8 {
    var b: Builder = .init(gpa);
    defer b.deinit();
    b.begin();
    b.key("host");
    b.begin();
    b.field("id", id);
    b.field("label", label);
    b.key("endpoints");
    b.beginArray();
    b.string(url);
    b.endArray();
    b.field("added_from", "Velo");
    b.end();
    b.end();
    return b.finish();
}

pub fn hostsRemove(gpa: Allocator, id: []const u8) Allocator.Error![]u8 {
    var b: Builder = .init(gpa);
    defer b.deinit();
    b.begin();
    b.field("id", id);
    b.end();
    return b.finish();
}

fn shellLayout(b: *Builder) void {
    b.begin();
    b.key("block");
    b.begin();
    b.field("flavor", shell_flavor);
    b.end();
    b.end();
}

/// JSON object writer over an allocating buffer. Writes can only fail by running out of
/// memory; the first failure turns every later call into a no-op and `finish` reports it.
const Builder = struct {
    aw: std.Io.Writer.Allocating,
    jw: json.Stringify = undefined,
    bound: bool = false,
    failed: bool = false,

    fn init(gpa: Allocator) Builder {
        return .{ .aw = .init(gpa) };
    }

    fn deinit(b: *Builder) void {
        b.aw.deinit();
    }

    /// The Stringify, or null after a failure. It points at `aw`, so it is bound on first
    /// use, when the Builder has its final address.
    fn out(b: *Builder) ?*json.Stringify {
        if (b.failed) return null;
        if (!b.bound) {
            b.jw = .{ .writer = &b.aw.writer };
            b.bound = true;
        }
        return &b.jw;
    }

    fn begin(b: *Builder) void {
        const jw = b.out() orelse return;
        jw.beginObject() catch b.fail();
    }
    fn end(b: *Builder) void {
        const jw = b.out() orelse return;
        jw.endObject() catch b.fail();
    }
    fn beginArray(b: *Builder) void {
        const jw = b.out() orelse return;
        jw.beginArray() catch b.fail();
    }
    fn endArray(b: *Builder) void {
        const jw = b.out() orelse return;
        jw.endArray() catch b.fail();
    }
    fn key(b: *Builder, name: []const u8) void {
        const jw = b.out() orelse return;
        jw.objectField(name) catch b.fail();
    }
    fn string(b: *Builder, s: []const u8) void {
        const jw = b.out() orelse return;
        model.writeString(jw, s) catch b.fail();
    }
    fn field(b: *Builder, name: []const u8, s: []const u8) void {
        b.key(name);
        b.string(s);
    }
    fn fieldValue(b: *Builder, name: []const u8, v: anytype) void {
        b.key(name);
        const jw = b.out() orelse return;
        jw.write(v) catch b.fail();
    }
    /// A complete JSON value, written as is.
    fn raw(b: *Builder, text: []const u8) void {
        const jw = b.out() orelse return;
        jw.beginWriteRaw() catch return b.fail();
        jw.writer.writeAll(text) catch return b.fail();
        jw.endWriteRaw();
    }
    fn fail(b: *Builder) void {
        b.failed = true;
    }
    fn finish(b: *Builder) Allocator.Error![]u8 {
        if (b.failed) return error.OutOfMemory;
        return b.aw.toOwnedSlice();
    }
};

/// Longest prefix of `s` of at most `max` bytes that does not cut a UTF-8 sequence.
fn utf8Prefix(s: []const u8, max: usize) []const u8 {
    if (s.len <= max) return s;
    var n = max;
    while (n > 0 and (s[n] & 0xC0) == 0x80) n -= 1;
    return s[0..n];
}

// --- data connection handshake --------------------------------------------------------

pub const data_hello: u8 = 0x01;
pub const data_resume: u8 = 0x02;
pub const data_welcome: u8 = 0x81;
pub const data_reject: u8 = 0x82;

/// HELLO: 0x01 then the 16 ticket bytes (`ticket_b64url` is 22 base64url characters).
pub fn dataHello(ticket_b64url: []const u8, out: *[17]u8) error{InvalidTicket}!void {
    const dec = std.base64.url_safe_no_pad.Decoder;
    const n = dec.calcSizeForSlice(ticket_b64url) catch return error.InvalidTicket;
    if (n != 16) return error.InvalidTicket;
    out[0] = data_hello;
    dec.decode(out[1..], ticket_b64url) catch return error.InvalidTicket;
}

/// RESUME: 0x02, the latest reconnect token, then the stream bytes received so far (u64 LE).
pub fn dataResume(token: [16]u8, consumed: u64, out: *[25]u8) void {
    out[0] = data_resume;
    out[1..17].* = token;
    std.mem.writeInt(u64, out[17..25], consumed, .little);
}

pub const DataWelcome = struct { token: [16]u8, resume_ms: u32, nonce: u64 };
pub const DataHandshake = union(enum) { welcome: DataWelcome, reject: u8, invalid };

/// The server's answer to HELLO/RESUME: WELCOME is exactly 32 bytes (0x81, token, 3 zero
/// bytes, resume window u32 LE, nonzero channel nonce u64 LE); REJECT is 0x82 + reason.
pub fn parseDataHandshake(bytes: []const u8) DataHandshake {
    if (bytes.len == 0) return .invalid;
    switch (bytes[0]) {
        data_welcome => {
            if (bytes.len != 32) return .invalid;
            const nonce = std.mem.readInt(u64, bytes[24..32], .little);
            if (nonce == 0) return .invalid;
            return .{ .welcome = .{
                .token = bytes[1..17].*,
                .resume_ms = std.mem.readInt(u32, bytes[20..24], .little),
                .nonce = nonce,
            } };
        },
        data_reject => return if (bytes.len >= 2) .{ .reject = bytes[1] } else .invalid,
        else => return .invalid,
    }
}

// --- user intents ---------------------------------------------------------------------

/// User intent from Kotlin (`VeloCore.command` JSON).
pub const Intent = union(enum) {
    pane_watches: json.Value,
    /// Show the minimized pane `block`; `focus` raises its layer (false: no pane takes focus).
    restore_pane: struct { session: Id, window: Id, block: Id, focus: bool },
    /// Stash `block` of the selected session: minimize its floating layer, or float it first.
    minimize_pane: Id,
    /// Minimize every floating layer of tab `window` of the selected session, or show every
    /// minimized one.
    minimize_floating: struct { window: Id, minimize: bool },
    resize_pane: SplitResize,
    split: struct { block: Id, direction: SplitDirection },
    float: Id,
    dock: Id,
    /// Move `block` to the `direction` side of the tiled block `anchor`, in any tab of the
    /// selected session. `direction` is the server's structure, not the screen's.
    move_pane: struct { block: Id, anchor: Id, direction: SplitDirection },
    /// Swap two blocks placed in tabs of the selected session.
    swap_panes: struct { block: Id, other: Id },
    /// Move `block` out to a new tab at the end (also op `restoreInNewTab`).
    move_to_new_tab: Id,
    undo_move: UndoMove,
    zoom: Id,
    unzoom: Id,
    close: Id,
    close_last_pane: model.PaneAddress,
    focus: Id,
    new_session: struct { label: ?[]const u8 },
    /// A new tab in `session`; null is the selected session.
    new_window: struct { session: ?Id },
    /// Show tab `window` of `session`; null is the selected session.
    select_window: struct { session: ?Id, window: Id },
    close_window: struct { session: Id, window: Id },
    /// Put tab `window` of `session` before tab `before`; null puts it at the end.
    move_window: struct { session: Id, window: Id, before: ?Id },
    close_session: Id,
    rename_session: struct { session: Id, label: []const u8 },
    rename_window: struct { session: Id, window: Id, label: []const u8 },
    send_text: struct { block: Id, text: []const u8 },
    /// Write `text` to the terminal `block` of `session`, whichever session is selected or
    /// none (parked), once the server says its program status still holds `expect`.
    pane_write: PaneWrite,
    /// Read the last lines of the text of the terminal `block` of `session`, whichever
    /// session is selected or none (parked).
    pane_screen: PaneScreen,
    /// The clipboard's text for a terminal; `confirmed` when the user was asked and said yes.
    paste: struct { block: Id, text: []const u8, confirmed: bool },
    /// The pinch on this pane ended: its terminal fits the pane again.
    fit: Id,
    /// A finger went down on this pane.
    touch: Id,
    setting: struct { key: []const u8, value: json.Value },
    hosts_put: struct { id: []const u8, label: []const u8, url: []const u8 },
    hosts_remove: []const u8,
    /// Detach the selected session; the control connection stays.
    detach,
    /// Resume a parked session, preferring this id when it is still listed.
    resume_session: ?[]const u8,
    /// The label prefix of the sessions that exist for this core; "" is no fence.
    fence: []const u8,
    unknown,
};

/// Intent `undoMove`: the exact inverse of the last pane move, which the app worked out from
/// the layout before that move.
pub const UndoMove = union(enum) {
    /// Swap the same two blocks again.
    swap: struct { block: Id, other: Id },
    /// Move `block` back beside its old sibling `anchor`, with the old split's ratio.
    beside: struct { block: Id, anchor: Id, direction: SplitDirection, ratio: f64 },
    /// Move `block` out to a new tab again, because the move closed its old tab. `label`
    /// names the tab (null keeps the server's name); the tab goes before tab `before`
    /// (null is the end).
    retab: struct { block: Id, label: ?[]const u8, before: ?Id },
};

/// ops: restorePane{session,window,block,focus?}, minimizePane{block}, minimizeFloating{window,minimize},
/// split{block,dir:"left"|"right"|"above"|"below"}, float, dock, zoom, unzoom, close, focus, fit,
/// touch (each {block}), movePane{block,anchor,dir:"left"|"right"|"above"|"below"},
/// swapPanes{block,other}, moveToNewTab{block} (alias restoreInNewTab{block}),
/// undoMove{kind:"swap",block,other} | undoMove{kind:"beside",block,anchor,dir,ratio} |
/// undoMove{kind:"retab",block,label?,before?}, newWindow{session?}, newSession{label?},
/// selectWindow{session?,window}, closeWindow{session,window}, moveWindow{session,window,before?},
/// closeSession{session},
/// closeLastPane{session,window,block},
/// renameSession{session,label}, renameWindow{session,window,label},
/// sendText{block,text}, paste{block,text,confirmed?}, setting{key,value},
/// paneWrite{tag,session,block,text,expect:{id,kind?,updatedAt?}}, paneScreen{tag,session,block},
/// hostsPut{id,label,url}, hostsRemove{id}, detach, resumeSession{session?}, fence{prefix?}.
/// An unknown op, or one missing a field, is `.unknown`, and so is an undoMove beside with a
/// ratio outside (0, 1); a paste without `confirmed` is not confirmed. Everything returned
/// lives in `arena`.
pub fn parseIntent(arena: Allocator, text: []const u8) !Intent {
    const root = try json.parseFromSliceLeaky(json.Value, arena, text, .{ .allocate = .alloc_always });
    const op = model.str(model.get(root, "op")) orelse return .unknown;
    const block = model.str(model.get(root, "block"));
    const session = model.str(model.get(root, "session"));
    const window = model.str(model.get(root, "window"));

    const Op = enum { resizePane, paneWatches, restorePane, minimizePane, minimizeFloating, split, float, dock, movePane, swapPanes, moveToNewTab, restoreInNewTab, undoMove, zoom, unzoom, close, closeLastPane, focus, fit, touch, newWindow, newSession, selectWindow, closeWindow, moveWindow, closeSession, renameSession, renameWindow, sendText, paneWrite, paneScreen, paste, setting, hostsPut, hostsRemove, detach, resumeSession, fence };
    switch (std.meta.stringToEnum(Op, op) orelse return .unknown) {
        .resizePane => {
            const direction = model.str(model.get(root, "direction")) orelse return .unknown;
            if (!std.mem.eql(u8, direction, "horizontal") and !std.mem.eql(u8, direction, "vertical")) return .unknown;
            const ratio = model.num(model.get(root, "ratio")) orelse return .unknown;
            if (!(ratio > 0 and ratio < 1)) return .unknown;
            return .{ .resize_pane = .{
                .before = model.str(model.get(root, "before")) orelse return .unknown,
                .after = model.str(model.get(root, "after")) orelse return .unknown,
                .direction = direction,
                .ratio = ratio,
            } };
        },
        .restorePane => return .{ .restore_pane = .{
            .session = session orelse return .unknown,
            .window = window orelse return .unknown,
            .block = block orelse return .unknown,
            .focus = model.boolean(model.get(root, "focus")) orelse false,
        } },
        .minimizePane => return .{ .minimize_pane = block orelse return .unknown },
        .minimizeFloating => return .{ .minimize_floating = .{
            .window = window orelse return .unknown,
            .minimize = model.boolean(model.get(root, "minimize")) orelse return .unknown,
        } },
        .paneWatches => return .{ .pane_watches = model.get(root, "panes") orelse return .unknown },
        .split => {
            const dir = model.str(model.get(root, "dir")) orelse "right";
            const direction: SplitDirection = std.meta.stringToEnum(SplitDirection, dir) orelse return .unknown;
            return .{ .split = .{ .block = block orelse return .unknown, .direction = direction } };
        },
        .float => return .{ .float = block orelse return .unknown },
        .dock => return .{ .dock = block orelse return .unknown },
        .movePane => return .{ .move_pane = .{
            .block = block orelse return .unknown,
            .anchor = model.str(model.get(root, "anchor")) orelse return .unknown,
            .direction = std.meta.stringToEnum(SplitDirection, model.str(model.get(root, "dir")) orelse "") orelse return .unknown,
        } },
        .swapPanes => return .{ .swap_panes = .{
            .block = block orelse return .unknown,
            .other = model.str(model.get(root, "other")) orelse return .unknown,
        } },
        .moveToNewTab, .restoreInNewTab => return .{ .move_to_new_tab = block orelse return .unknown },
        .undoMove => {
            const Kind = enum { swap, beside, retab };
            const kind = std.meta.stringToEnum(Kind, model.str(model.get(root, "kind")) orelse "") orelse return .unknown;
            const moved = block orelse return .unknown;
            switch (kind) {
                .swap => return .{ .undo_move = .{ .swap = .{
                    .block = moved,
                    .other = model.str(model.get(root, "other")) orelse return .unknown,
                } } },
                .beside => {
                    const ratio = model.num(model.get(root, "ratio")) orelse return .unknown;
                    if (!(ratio > 0 and ratio < 1)) return .unknown;
                    return .{ .undo_move = .{ .beside = .{
                        .block = moved,
                        .anchor = model.str(model.get(root, "anchor")) orelse return .unknown,
                        .direction = std.meta.stringToEnum(SplitDirection, model.str(model.get(root, "dir")) orelse "") orelse return .unknown,
                        .ratio = ratio,
                    } } };
                },
                .retab => return .{ .undo_move = .{ .retab = .{
                    .block = moved,
                    .label = model.str(model.get(root, "label")),
                    .before = model.str(model.get(root, "before")),
                } } },
            }
        },
        .zoom => return .{ .zoom = block orelse return .unknown },
        .unzoom => return .{ .unzoom = block orelse return .unknown },
        .close => return .{ .close = block orelse return .unknown },
        .closeLastPane => return .{ .close_last_pane = .{
            .session = session orelse return .unknown,
            .window = window orelse return .unknown,
            .block = block orelse return .unknown,
        } },
        .focus => return .{ .focus = block orelse return .unknown },
        .fit => return .{ .fit = block orelse return .unknown },
        .touch => return .{ .touch = block orelse return .unknown },
        .newSession => return .{ .new_session = .{ .label = model.str(model.get(root, "label")) } },
        .newWindow => return .{ .new_window = .{ .session = session } },
        .selectWindow => return .{ .select_window = .{ .session = session, .window = window orelse return .unknown } },
        .closeWindow => return .{ .close_window = .{
            .session = session orelse return .unknown,
            .window = window orelse return .unknown,
        } },
        .moveWindow => return .{ .move_window = .{
            .session = session orelse return .unknown,
            .window = window orelse return .unknown,
            .before = model.str(model.get(root, "before")),
        } },
        .closeSession => return .{ .close_session = session orelse return .unknown },
        .renameSession => return .{ .rename_session = .{
            .session = session orelse return .unknown,
            .label = model.str(model.get(root, "label")) orelse return .unknown,
        } },
        .renameWindow => return .{ .rename_window = .{
            .session = session orelse return .unknown,
            .window = window orelse return .unknown,
            .label = model.str(model.get(root, "label")) orelse return .unknown,
        } },
        .sendText => return .{ .send_text = .{
            .block = block orelse return .unknown,
            .text = model.str(model.get(root, "text")) orelse return .unknown,
        } },
        .paneWrite => {
            const expect = model.get(root, "expect") orelse return .unknown;
            return .{ .pane_write = .{
                .tag = model.str(model.get(root, "tag")) orelse return .unknown,
                .session = session orelse return .unknown,
                .block = block orelse return .unknown,
                .text = model.str(model.get(root, "text")) orelse return .unknown,
                .expect = .{
                    .id = model.str(model.get(expect, "id")) orelse return .unknown,
                    .kind = model.str(model.get(expect, "kind")),
                    .updated_at = model.str(model.get(expect, "updatedAt")),
                },
            } };
        },
        .paneScreen => return .{ .pane_screen = .{
            .tag = model.str(model.get(root, "tag")) orelse return .unknown,
            .session = session orelse return .unknown,
            .block = block orelse return .unknown,
        } },
        .paste => return .{ .paste = .{
            .block = block orelse return .unknown,
            .text = model.str(model.get(root, "text")) orelse return .unknown,
            .confirmed = model.boolean(model.get(root, "confirmed")) orelse false,
        } },
        .setting => return .{ .setting = .{
            .key = model.str(model.get(root, "key")) orelse return .unknown,
            .value = model.get(root, "value") orelse json.Value.null,
        } },
        .hostsPut => return .{ .hosts_put = .{
            .id = model.str(model.get(root, "id")) orelse return .unknown,
            .label = model.str(model.get(root, "label")) orelse return .unknown,
            .url = model.str(model.get(root, "url")) orelse return .unknown,
        } },
        .hostsRemove => return .{ .hosts_remove = model.str(model.get(root, "id")) orelse return .unknown },
        .detach => return .detach,
        .resumeSession => return .{ .resume_session = session },
        .fence => {
            const prefix = model.get(root, "prefix") orelse return .{ .fence = "" };
            return .{ .fence = model.str(prefix) orelse return .unknown };
        },
    }
}
