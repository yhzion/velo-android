//! Rex session model: the parsed `session.view` and the UI state JSON published to Kotlin.
//! Pure data, no I/O.

const std = @import("std");
const json = std.json;
const Allocator = std.mem.Allocator;

pub const Id = []const u8; // "session:<uuid>", "window:<uuid>", "block:<uuid>", "layer:<uuid>"

pub const Layout = union(enum) {
    block: Id,
    split: struct { column: bool, ratio: f32, first: *Layout, second: *Layout }, // column = vertical
};
pub const Floating = struct { block: Id, layer: Id, x: f32, y: f32, w: f32, h: f32 };
pub const PaneAddress = struct { session: Id, window: Id, block: Id };
/// A block of a minimized layer. It carries what a drawn `Block` does, so the app can name it.
pub const HiddenBlock = struct {
    id: Id,
    layer: Id,
    terminal: bool = false,
    label: []const u8 = "",
    title: []const u8 = "",
    owner: ?Id = null,

    fn asBlock(self: HiddenBlock) Block {
        return .{ .id = self.id, .label = self.label, .terminal = self.terminal, .title = self.title, .owner = self.owner };
    }
};
pub const Block = struct { id: Id, label: []const u8, terminal: bool, title: []const u8 = "", owner: ?Id = null };
/// `blocks`: those of the layers that are drawn. `minimized`: at least one block sits in a
/// minimized layer, which is not drawn and not in `blocks`; it keeps running.
pub const Window = struct {
    id: Id,
    label: []const u8,
    icon: ?[]const u8 = null,
    focused: ?Id,
    zoomed: ?Id,
    layout: ?*Layout,
    floating: []Floating,
    blocks: []Block,
    minimized: bool = false,
    hidden: []const HiddenBlock = &.{},
};
pub const View = struct { session: Id, label: []const u8, revision: i64, active_window: ?Id, windows: []Window };
/// A session of the server. `view` is what the core knows of its windows (null until the
/// first answer); the state's `tabs` are made from it.
pub const Session = struct { id: Id, label: []const u8, view: ?*const View = null };

pub const terminal_creator = "com.superlogical.terminal";

/// Layout trees deeper than this are rejected instead of recursing without bound.
const max_layout_depth = 64;

pub const ParseError = error{ OutOfMemory, InvalidView };

// --- std.json.Value accessors shared by the Model files -------------------------------

/// Member `name` of a JSON object; null when `v` is not an object or has no such member.
pub fn get(v: json.Value, name: []const u8) ?json.Value {
    return switch (v) {
        .object => |o| o.get(name),
        else => null,
    };
}

pub fn str(v: ?json.Value) ?[]const u8 {
    return switch (v orelse return null) {
        .string => |s| s,
        else => null,
    };
}

pub fn num(v: ?json.Value) ?f64 {
    return switch (v orelse return null) {
        .integer => |i| @floatFromInt(i),
        .float => |f| f,
        .number_string => |s| std.fmt.parseFloat(f64, s) catch null,
        else => null,
    };
}

pub fn int(v: ?json.Value) ?i64 {
    return switch (v orelse return null) {
        .integer => |i| i,
        .float => |f| if (@abs(f) < 9.0e18 and @trunc(f) == f) @intFromFloat(f) else null,
        else => null,
    };
}

pub fn boolean(v: ?json.Value) ?bool {
    return switch (v orelse return null) {
        .bool => |b| b,
        else => null,
    };
}

/// Elements of a JSON array; empty for null, missing or non-array values.
pub fn items(v: ?json.Value) []const json.Value {
    return switch (v orelse return &.{}) {
        .array => |a| a.items,
        else => &.{},
    };
}

/// Write `s` as a JSON string. Invalid UTF-8 becomes U+FFFD so the output is always a
/// string (std.json would emit an array of numbers instead).
pub fn writeString(jw: *json.Stringify, s: []const u8) std.Io.Writer.Error!void {
    if (std.unicode.utf8ValidateSlice(s)) return jw.write(s);
    try jw.beginWriteRaw();
    try jw.writer.writeByte('"');
    var i: usize = 0;
    while (i < s.len) {
        const n: usize = std.unicode.utf8ByteSequenceLength(s[i]) catch 0;
        if (n == 0 or i + n > s.len or !std.unicode.utf8ValidateSlice(s[i .. i + n])) {
            try jw.writer.writeAll(&std.unicode.replacement_character_utf8);
            i += 1;
            continue;
        }
        try json.Stringify.encodeJsonStringChars(s[i .. i + n], .{}, jw.writer);
        i += n;
    }
    try jw.writer.writeByte('"');
    jw.endWriteRaw();
}

// --- session.view ---------------------------------------------------------------------

/// Parse a `session.view` result (also the `view` member of bootstrap/attach results).
/// All memory from `arena`. Rex layout: `layers[]` (first kind "tiled" fills the window;
/// "floating" layers have `bounds` [x,y,w,h]; skip state "minimized"); layout node is
/// `{rect, block_id}` or `{rect, split:{direction:"horizontal"|"vertical", ratio, before, after}}`.
/// A floating layer with a split layout yields one Floating per leaf using the leaf `rect`.
/// `terminal` = creator_name == "com.superlogical.terminal".
pub fn parseView(arena: Allocator, value: json.Value) ParseError!View {
    if (value != .object) return error.InvalidView;
    const session = str(get(value, "session_id")) orelse return error.InvalidView;

    var windows: std.ArrayList(Window) = .empty;
    for (items(get(value, "windows"))) |w| {
        const id = str(get(w, "window_id")) orelse continue;
        try windows.append(arena, try parseWindow(arena, id, w));
    }

    return .{
        .session = try arena.dupe(u8, session),
        .label = try arena.dupe(u8, str(get(value, "label")) orelse ""),
        .revision = int(get(value, "revision")) orelse 0,
        .active_window = try dupeOpt(arena, str(get(value, "active_window_id"))),
        .windows = try windows.toOwnedSlice(arena),
    };
}

fn parseWindow(arena: Allocator, id: []const u8, w: json.Value) ParseError!Window {
    var layout: ?*Layout = null;
    var have_tiled = false;
    var floating: std.ArrayList(Floating) = .empty;
    var blocks: std.ArrayList(Block) = .empty;
    var hidden: std.ArrayList(HiddenBlock) = .empty;

    for (items(get(w, "layers"))) |layer| {
        if (layer != .object) continue;
        // A minimized layer is not drawn; its blocks keep running on the server.
        if (std.mem.eql(u8, str(get(layer, "state")) orelse "", "minimized")) {
            for (items(get(layer, "blocks"))) |b| {
                const block_id = str(get(b, "block_id")) orelse continue;
                try hidden.append(arena, .{
                    .id = try arena.dupe(u8, block_id),
                    .terminal = std.mem.eql(u8, str(get(b, "creator_name")) orelse "", terminal_creator),
                    .layer = try arena.dupe(u8, str(get(layer, "layer_id")) orelse ""),
                    .label = try arena.dupe(u8, str(get(b, "label")) orelse ""),
                    .title = try arena.dupe(u8, str(get(b, "title")) orelse ""),
                    .owner = try dupeOpt(arena, str(get(b, "owner"))),
                });
            }
            continue;
        }

        const node = get(layer, "layout") orelse json.Value.null;
        const tiled = std.mem.eql(u8, str(get(layer, "kind")) orelse "", "tiled");
        if (tiled and !have_tiled) {
            have_tiled = true;
            layout = try parseLayout(arena, node, 0);
        } else {
            // Unknown kinds are drawn as floating.
            const layer_id = try arena.dupe(u8, str(get(layer, "layer_id")) orelse "");
            try collectFloating(arena, &floating, layer_id, node, rect(get(layer, "bounds")), 0);
        }

        for (items(get(layer, "blocks"))) |b| {
            const block_id = str(get(b, "block_id")) orelse continue;
            try blocks.append(arena, .{
                .id = try arena.dupe(u8, block_id),
                .label = try arena.dupe(u8, str(get(b, "label")) orelse ""),
                .title = try arena.dupe(u8, str(get(b, "title")) orelse ""),
                .terminal = std.mem.eql(u8, str(get(b, "creator_name")) orelse "", terminal_creator),
                .owner = try dupeOpt(arena, str(get(b, "owner"))),
            });
        }
    }

    const icon = std.mem.trim(u8, str(get(w, "icon")) orelse "", " \t\r\n");
    return .{
        .id = try arena.dupe(u8, id),
        .label = try arena.dupe(u8, str(get(w, "label")) orelse ""),
        .icon = if (icon.len == 0) null else try arena.dupe(u8, icon),
        .focused = try dupeOpt(arena, str(get(w, "focused_block_id"))),
        .zoomed = try dupeOpt(arena, str(get(w, "zoomed_block_id"))),
        .layout = layout,
        .floating = try floating.toOwnedSlice(arena),
        .blocks = try blocks.toOwnedSlice(arena),
        .minimized = hidden.items.len > 0,
        .hidden = try hidden.toOwnedSlice(arena),
    };
}

fn parseLayout(arena: Allocator, node: json.Value, depth: usize) ParseError!?*Layout {
    if (node != .object) return null;
    if (depth > max_layout_depth) return error.InvalidView;

    if (str(get(node, "block_id"))) |block_id| {
        const leaf = try arena.create(Layout);
        leaf.* = .{ .block = try arena.dupe(u8, block_id) };
        return leaf;
    }

    const split = get(node, "split") orelse return null;
    if (split != .object) return null;
    const first = try parseLayout(arena, get(split, "before") orelse json.Value.null, depth + 1);
    const second = try parseLayout(arena, get(split, "after") orelse json.Value.null, depth + 1);
    // A split with an empty side collapses to the other side.
    const a = first orelse return second;
    const b = second orelse return a;

    const ratio = num(get(split, "ratio")) orelse 0.5;
    const out = try arena.create(Layout);
    out.* = .{ .split = .{
        // An unknown direction is drawn as horizontal.
        .column = std.mem.eql(u8, str(get(split, "direction")) orelse "", "vertical"),
        .ratio = if (std.math.isFinite(ratio)) @floatCast(std.math.clamp(ratio, 0.0, 1.0)) else 0.5,
        .first = a,
        .second = b,
    } };
    return out;
}

/// One Floating per leaf. The root leaf takes the layer `bounds`; leaves under a split
/// take their own `rect`.
fn collectFloating(
    arena: Allocator,
    out: *std.ArrayList(Floating),
    layer_id: Id,
    node: json.Value,
    bounds: ?[4]f32,
    depth: usize,
) ParseError!void {
    if (node != .object) return;
    if (depth > max_layout_depth) return error.InvalidView;

    if (str(get(node, "block_id"))) |block_id| {
        const r = (if (depth == 0) bounds orelse rect(get(node, "rect")) else rect(get(node, "rect")) orelse bounds) orelse return;
        try out.append(arena, .{
            .block = try arena.dupe(u8, block_id),
            .layer = layer_id,
            .x = r[0],
            .y = r[1],
            .w = r[2],
            .h = r[3],
        });
        return;
    }

    const split = get(node, "split") orelse return;
    if (split != .object) return;
    try collectFloating(arena, out, layer_id, get(split, "before") orelse json.Value.null, bounds, depth + 1);
    try collectFloating(arena, out, layer_id, get(split, "after") orelse json.Value.null, bounds, depth + 1);
}

fn rect(v: ?json.Value) ?[4]f32 {
    const a = items(v);
    if (a.len != 4) return null;
    var out: [4]f32 = undefined;
    for (a, 0..) |e, i| {
        const f = num(e) orelse return null;
        if (!std.math.isFinite(f)) return null;
        out[i] = @floatCast(f);
    }
    return out;
}

fn dupeOpt(arena: Allocator, s: ?[]const u8) Allocator.Error!?[]const u8 {
    return if (s) |x| try arena.dupe(u8, x) else null;
}

// --- hosts.list -----------------------------------------------------------------------

/// One entry of the server's host list. Fields other than these are not kept: the app
/// never sends an entry back.
pub const HostEntry = struct { id: []const u8, label: []const u8, endpoints: []const []const u8, icon: []const u8 };

/// Parse a `hosts.list` result (`{hosts:[{id,label,endpoints,icon,…}], hash}`). Entries
/// without a `label`, or with an empty one, are skipped; a missing `id` or `icon` is "",
/// and endpoints that are not strings are left out. All memory from `arena`.
pub fn parseHosts(arena: Allocator, value: json.Value) ParseError![]HostEntry {
    if (value != .object) return error.InvalidView;
    var hosts: std.ArrayList(HostEntry) = .empty;
    for (items(get(value, "hosts"))) |h| {
        const label = str(get(h, "label")) orelse continue;
        if (label.len == 0) continue;
        var endpoints: std.ArrayList([]const u8) = .empty;
        for (items(get(h, "endpoints"))) |e| {
            const endpoint = str(e) orelse continue;
            try endpoints.append(arena, try arena.dupe(u8, endpoint));
        }
        try hosts.append(arena, .{
            .id = try arena.dupe(u8, str(get(h, "id")) orelse ""),
            .label = try arena.dupe(u8, label),
            .endpoints = try endpoints.toOwnedSlice(arena),
            .icon = try arena.dupe(u8, str(get(h, "icon")) orelse ""),
        });
    }
    return hosts.toOwnedSlice(arena);
}

// --- UI state -------------------------------------------------------------------------

pub const Status = enum { idle, connecting, connected, reconnecting, @"error" };

/// What the program in a terminal last reported (OSC 7501), worst record first.
pub const Activity = enum {
    idle,
    done,
    working,
    @"error",
    blocked,

    /// Unknown states count as idle, as the server asks.
    pub fn parse(state: ?[]const u8) Activity {
        return std.meta.stringToEnum(Activity, state orelse return .idle) orelse .idle;
    }
};

/// The server's RFC 3339 report time as Unix milliseconds.
pub fn parseRfc3339Ms(text: []const u8) ?i64 {
    if (text.len < 20 or text[4] != '-' or text[7] != '-' or text[13] != ':' or text[16] != ':') return null;
    switch (text[10]) {
        'T', 't', ' ' => {},
        else => return null,
    }
    const year = decimal(text[0..4]) orelse return null;
    const month = decimal(text[5..7]) orelse return null;
    const day = decimal(text[8..10]) orelse return null;
    const hour = decimal(text[11..13]) orelse return null;
    const minute = decimal(text[14..16]) orelse return null;
    const second = decimal(text[17..19]) orelse return null;
    if (month < 1 or month > 12 or hour > 23 or minute > 59 or second > 60) return null;

    const leap = @mod(year, 4) == 0 and (@mod(year, 100) != 0 or @mod(year, 400) == 0);
    const month_days = [_]i64{ 31, if (leap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31 };
    const month_index: usize = @intCast(month - 1);
    if (day < 1 or day > month_days[month_index]) return null;

    var suffix: usize = 19;
    var millis: i64 = 0;
    if (text[suffix] == '.') {
        suffix += 1;
        const start = suffix;
        var place: i64 = 100;
        while (suffix < text.len and std.ascii.isDigit(text[suffix])) : (suffix += 1) {
            millis += (text[suffix] - '0') * place;
            place = @divTrunc(place, 10);
        }
        if (suffix == start) return null;
    }

    const zone = text[suffix..];
    var offset_minutes: i64 = 0;
    if (zone.len == 6 and (zone[0] == '+' or zone[0] == '-') and zone[3] == ':') {
        const hh = decimal(zone[1..3]) orelse return null;
        const mm = decimal(zone[4..6]) orelse return null;
        if (hh > 23 or mm > 59) return null;
        offset_minutes = (hh * 60 + mm) * (if (zone[0] == '-') @as(i64, -1) else 1);
    } else if (zone.len != 1 or (zone[0] != 'Z' and zone[0] != 'z')) {
        return null;
    }

    // Count Gregorian days before this date and subtract the count before 1970.
    const y = year - 1;
    const days_before_epoch = 719162;
    var days = 365 * y + @divFloor(y, 4) - @divFloor(y, 100) + @divFloor(y, 400) - days_before_epoch + day - 1;
    for (month_days[0..month_index]) |n| days += n;

    // Unix time represents a leap second at the following minute.
    const seconds = days * 86400 + (hour * 60 + minute - offset_minutes) * 60 + second;
    if (second == 60) {
        if (@mod(seconds, 86400) != 0) return null;
        const utc_day = @divFloor(seconds, 86400) - days + day;
        if (utc_day != 1 and utc_day != month_days[month_index] + 1) return null;
    }
    return seconds * 1000 + millis;
}

fn decimal(digits: []const u8) ?i64 {
    var value: i64 = 0;
    for (digits) |digit| {
        if (!std.ascii.isDigit(digit)) return null;
        value = 10 * value + digit - '0';
    }
    return value;
}

/// What the core learned about one terminal block beyond the view. All text is borrowed.
pub const BlockInfo = struct {
    /// Null until a terminal title arrives. An empty title clears the view's title.
    title: ?[]const u8 = null,
    /// File name of the foreground program, such as `zsh`; "" when unknown.
    program: []const u8 = "",
    agent: ?[]const u8 = null,
    tab_report: @import("TabMetadata.zig").Details = .{},
    /// Its working directory, with the home directory written as `~`; "" when unknown.
    cwd: []const u8 = "",
    activity: Activity = .idle,
    /// Arrival time of the status report, as Unix milliseconds.
    since: ?i64 = null,
};

/// A port forward of the core (#226): `host`:`port` of the remote, reached on the phone at
/// `127.0.0.1:local_port` through the tunnel block `block`.
pub const ForwardInfo = struct {
    block: []const u8,
    host: []const u8,
    port: u16,
    local_port: u16,
    /// Connections it relays now.
    connections: u32,
};

pub const UiState = struct {
    status: Status,
    server: []const u8,
    url: []const u8,
    /// Operating system account the server runs as; "" until the server says.
    account: []const u8 = "",
    pane_watches: []const PaneAddress = &.{},
    /// The server's host list (`hosts.list`) and its hash; "" before the first answer.
    hosts: []const HostEntry = &.{},
    hosts_hash: []const u8 = "",
    sessions: []const Session,
    session: ?Id,
    view: ?*const View,
    /// By block id. A block without an entry shows its label.
    blocks: *const std.StringHashMapUnmanaged(BlockInfo),
    /// By block id: the lead blocks of the tabs of sessions other than the selected one.
    leads: ?*const std.StringHashMapUnmanaged(BlockInfo) = null,
    forwards: []const ForwardInfo = &.{},
};

/// The block a tab is named after: its focused block, else its first.
pub fn leadBlock(w: *const Window) ?*const Block {
    if (w.focused) |id| {
        for (w.blocks) |*b| if (std.mem.eql(u8, b.id, id)) return b;
    }
    return if (w.blocks.len > 0) &w.blocks[0] else null;
}

/// The JSON Kotlin consumes (app/src/main/java/io/github/yhzion/velo/core/UiState.kt):
/// {status, server, url, account, hosts:[{id,label,endpoints:[…],icon}], hostsHash,
///  forwards:[{block,host,port,localPort,connections}], sessions:[{id,label,tabs:[{id,label,icon?,panes,active,minimized,block,title,program,cwd,
///  activity,since?,progress?,attention}]}], session, windows:[{id,label,icon?,
///  layout,zoomed,minimized, floating:[{block,x,y,w,h}]}], window, focused,
///  blocks:{id:{icon?,title,program,cwd,activity,since?,progress?,attention}}}
/// `tabs` are the session's windows in order. It is left out while the windows are not
/// known (`Session.view` null); `[]` is a session without windows. `panes` counts the
/// tab's blocks (not those of minimized layers), `active` marks the session's active
/// window, `minimized` is true when the tab has a block in a minimized layer (a program
/// that runs unseen; the same value is in the tab's entry of `windows`), `block` is the
/// tab's lead block (`leadBlock`; left out for a tab without blocks) and the fields
/// after it describe that block as an entry of `blocks` does.
/// Both lists use the reported app, window hint, recognized executable, or program basename.
/// layout node: {"block":id} or {"dir":"row"|"column","ratio":f,"first":node,"second":node}
/// ("row" = side by side = Rex "horizontal"). `window` = active window, `focused` = its
/// focused block. `attention` is true while a program waits for the user
/// or reports an error. Caller frees.
pub fn stateJson(gpa: Allocator, state: UiState) Allocator.Error![]u8 {
    return (try stateSnapshot(gpa, state)).text;
}

/// Owns one compact JSON object. Treat its bytes as immutable until deinit.
pub const StateSnapshot = struct {
    text: []u8,
    // Exclusive ends of scalars, sessions, windows, blocks, before the comma or `}`.
    ends: [4]usize,

    pub fn deinit(self: StateSnapshot, gpa: Allocator) void {
        gpa.free(self.text);
    }

    fn group(self: StateSnapshot, i: usize) []const u8 {
        const start = if (i == 0) 1 else self.ends[i - 1] + 1;
        return self.text[start..self.ends[i]];
    }

    /// Null if unchanged. Otherwise borrows self.text (all groups) or scratch.items
    /// (some groups); consume the result before freeing or reusing its backing store.
    pub fn delta(self: StateSnapshot, previous: StateSnapshot, gpa: Allocator, scratch: *std.ArrayList(u8)) Allocator.Error!?[]const u8 {
        var changed: [4][]const u8 = undefined;
        var count: usize = 0;
        var size: usize = 1; // closing brace, plus a leading brace or comma per group
        for (0..changed.len) |i| {
            const fields = self.group(i);
            if (std.mem.eql(u8, fields, previous.group(i))) continue;
            changed[count] = fields;
            count += 1;
            size += 1 + fields.len;
        }
        if (count == 0) return null;
        if (count == changed.len) return self.text;

        scratch.clearRetainingCapacity();
        try scratch.ensureTotalCapacity(gpa, size);
        for (changed[0..count], 0..) |fields, i| {
            scratch.appendAssumeCapacity(if (i == 0) '{' else ',');
            scratch.appendSliceAssumeCapacity(fields);
        }
        scratch.appendAssumeCapacity('}');
        return scratch.items;
    }
};

pub fn stateSnapshot(gpa: Allocator, state: UiState) Allocator.Error!StateSnapshot {
    var aw: std.Io.Writer.Allocating = .init(gpa);
    defer aw.deinit();
    var jw: json.Stringify = .{ .writer = &aw.writer };
    // The allocating writer fails only when it cannot grow.
    const ends = writeState(&jw, state) catch return error.OutOfMemory;
    return .{ .text = try aw.toOwnedSlice(), .ends = ends };
}

fn writeState(jw: *json.Stringify, state: UiState) std.Io.Writer.Error![4]usize {
    const windows: []const Window = if (state.view) |v| v.windows else &.{};
    const active = activeWindow(state.view);

    try jw.beginObject();
    try jw.objectField("status");
    try jw.write(@tagName(state.status));
    try jw.objectField("server");
    try writeString(jw, state.server);
    try jw.objectField("url");
    try writeString(jw, state.url);
    try jw.objectField("account");
    try writeString(jw, state.account);

    try jw.objectField("hosts");
    try jw.beginArray();
    for (state.hosts) |h| {
        try jw.beginObject();
        try jw.objectField("id");
        try writeString(jw, h.id);
        try jw.objectField("label");
        try writeString(jw, h.label);
        try jw.objectField("endpoints");
        try jw.beginArray();
        for (h.endpoints) |e| try writeString(jw, e);
        try jw.endArray();
        try jw.objectField("icon");
        try writeString(jw, h.icon);
        try jw.endObject();
    }
    try jw.endArray();
    try jw.objectField("hostsHash");
    try writeString(jw, state.hosts_hash);
    try jw.objectField("session");
    try writeOptString(jw, state.session);
    try jw.objectField("window");
    try writeOptString(jw, if (active) |w| w.id else null);
    try jw.objectField("focused");
    try writeOptString(jw, if (active) |w| w.focused else null);
    try jw.objectField("forwards");
    try jw.beginArray();
    for (state.forwards) |f| {
        try jw.beginObject();
        try jw.objectField("block");
        try writeString(jw, f.block);
        try jw.objectField("host");
        try writeString(jw, f.host);
        try jw.objectField("port");
        try jw.write(f.port);
        try jw.objectField("localPort");
        try jw.write(f.local_port);
        try jw.objectField("connections");
        try jw.write(f.connections);
        try jw.endObject();
    }
    try jw.endArray();
    const scalars_end = jw.writer.end;

    try jw.objectField("sessions");
    try jw.beginArray();
    for (state.sessions) |s| {
        try jw.beginObject();
        try jw.objectField("id");
        try writeString(jw, s.id);
        try jw.objectField("label");
        try writeString(jw, s.label);
        // Left out when unknown, as `block` is: `[]` says the session has no tabs.
        if (s.view) |v| {
            try jw.objectField("tabs");
            try jw.beginArray();
            const shown = activeWindow(v);
            for (v.windows) |*w| {
                const lead = leadBlock(w);
                const info = if (lead) |b| blockInfo(state, b.id) else BlockInfo{};
                try jw.beginObject();
                try jw.objectField("id");
                try writeString(jw, w.id);
                try jw.objectField("label");
                try writeString(jw, w.label);
                try writeTabIcon(jw, w.icon, info);
                try jw.objectField("panes");
                try jw.write(w.blocks.len);
                try jw.objectField("terminalBlocks");
                try jw.beginArray();
                for (w.blocks) |b| if (b.terminal) try writeString(jw, b.id);
                for (w.hidden) |b| if (b.terminal) try writeString(jw, b.id);
                try jw.endArray();
                try jw.objectField("active");
                try jw.write(w == shown);
                try jw.objectField("minimized");
                try jw.write(w.minimized);
                if (lead) |b| {
                    try jw.objectField("block");
                    try writeString(jw, b.id);
                }
                try writeBlockInfo(jw, lead, info);
                try jw.endObject();
            }
            try jw.endArray();
        }
        try jw.endObject();
    }
    try jw.endArray();
    const sessions_end = jw.writer.end;

    try jw.objectField("windows");
    try jw.beginArray();
    for (windows) |*w| {
        const info = if (leadBlock(w)) |b| blockInfo(state, b.id) else BlockInfo{};
        try jw.beginObject();
        try jw.objectField("id");
        try writeString(jw, w.id);
        try jw.objectField("label");
        try writeString(jw, w.label);
        try writeTabIcon(jw, w.icon, info);
        try jw.objectField("layout");
        if (w.layout) |l| try writeLayout(jw, l) else try jw.write(null);
        try jw.objectField("zoomed");
        try writeOptString(jw, w.zoomed);
        try jw.objectField("minimized");
        try jw.write(w.minimized);
        if (w.hidden.len > 0) {
            // Every pane of a minimized layer, in layer stack order, then the same by layer.
            try jw.objectField("minimizedBlocks");
            try jw.beginArray();
            for (w.hidden) |b| try writeString(jw, b.id);
            try jw.endArray();
            try jw.objectField("minimizedLayers");
            try jw.beginArray();
            for (w.hidden, 0..) |b, i| {
                const starts = i == 0 or !std.mem.eql(u8, w.hidden[i - 1].layer, b.layer);
                if (starts) {
                    if (i > 0) try jw.endArray();
                    try jw.beginArray();
                }
                try writeString(jw, b.id);
            }
            try jw.endArray();
            try jw.endArray();
        }
        try jw.objectField("floating");
        try jw.beginArray();
        for (w.floating) |f| {
            try jw.beginObject();
            try jw.objectField("block");
            try writeString(jw, f.block);
            inline for (.{ "x", "y", "w", "h" }) |name| {
                try jw.objectField(name);
                try writeF32(jw, @field(f, name));
            }
            try jw.endObject();
        }
        try jw.endArray();
        try jw.endObject();
    }
    try jw.endArray();
    const windows_end = jw.writer.end;

    try jw.objectField("blocks");
    try jw.beginObject();
    for (windows, 0..) |w, wi| {
        for (w.blocks, 0..) |b, bi| {
            if (seenBefore(windows, wi, bi, b.id)) continue;
            try jw.objectField(b.id);
            try jw.beginObject();
            const info = state.blocks.get(b.id) orelse BlockInfo{};
            // Each pane's program as a tab's `icon` names its lead's, so the app can draw every pane's brand (#217).
            try writeTabIcon(jw, null, info);
            try writeBlockInfo(jw, &b, info);
            try jw.endObject();
        }
    }
    // The panes of minimized layers are named too, so the app can label them.
    for (windows, 0..) |w, wi| {
        for (w.hidden, 0..) |h, hi| {
            if (drawnAnywhere(windows, h.id) or hiddenBefore(windows, wi, hi, h.id)) continue;
            const b = h.asBlock();
            try jw.objectField(b.id);
            try jw.beginObject();
            const info = state.blocks.get(b.id) orelse BlockInfo{};
            // Each pane's program as a tab's `icon` names its lead's, so the app can draw every pane's brand (#217).
            try writeTabIcon(jw, null, info);
            try writeBlockInfo(jw, &b, info);
            try jw.endObject();
        }
    }
    try jw.endObject();
    const blocks_end = jw.writer.end;
    try jw.endObject();
    return .{ scalars_end, sessions_end, windows_end, blocks_end };
}

/// What the core learned about block `id`: from the selected session's panes, else from
/// the lead blocks it follows in the other sessions.
fn blockInfo(state: UiState, id: Id) BlockInfo {
    if (state.blocks.get(id)) |info| return info;
    if (state.leads) |leads| if (leads.get(id)) |info| return info;
    return .{};
}

fn writeTabIcon(jw: *json.Stringify, icon: ?[]const u8, info: BlockInfo) std.Io.Writer.Error!void {
    const report = info.tab_report;
    // A child can report activity for a conversation owned by another program.
    const hint = if (report.app_from_child)
        @import("TabMetadata.zig").agent(icon orelse "") orelse info.agent orelse report.app
    else if (report.app.len > 0)
        report.app
    else
        icon orelse info.agent orelse std.fs.path.basenamePosix(info.program);
    if (hint.len == 0) return;
    try jw.objectField("icon");
    try writeString(jw, hint);
}

/// Write a block's display fields into an open object.
/// Without a reported title the block's label stands in.
fn writeBlockInfo(jw: *json.Stringify, block: ?*const Block, info: BlockInfo) std.Io.Writer.Error!void {
    if (info.tab_report.title.len > 0) {
        try jw.objectField("statusTitle");
        try writeString(jw, info.tab_report.title);
    }
    if (info.tab_report.summary.len > 0) {
        try jw.objectField("statusSummary");
        try writeString(jw, info.tab_report.summary);
    }
    if (info.tab_report.kind) |kind| {
        try jw.objectField("kind");
        try jw.write(@tagName(kind));
    }
    if (info.tab_report.reported_idle) {
        try jw.objectField("reportedIdle");
        try jw.write(true);
    }
    if (info.tab_report.progress) |progress| {
        try jw.objectField("progress");
        try jw.write(progress);
    }
    const title = info.title orelse if (block) |b| b.title else "";
    const label = if (block) |b| b.label else "";
    if (title.len == 0 and label.len > 0) {
        try jw.objectField("titleIsLabel");
        try jw.write(true);
    }
    try jw.objectField("title");
    try writeString(jw, if (title.len > 0) title else label);
    try jw.objectField("program");
    try writeString(jw, info.program);
    try jw.objectField("cwd");
    try writeString(jw, info.cwd);
    try jw.objectField("activity");
    try jw.write(@tagName(info.activity));
    if (info.activity != .idle) {
        if (info.since) |ms| {
            try jw.objectField("since");
            try jw.write(ms);
        }
    }
    try jw.objectField("attention");
    try jw.write(info.activity == .blocked or info.activity == .@"error");
}

/// The view's active window, or its first window when the view names none.
fn activeWindow(view: ?*const View) ?*const Window {
    const v = view orelse return null;
    if (v.active_window) |id| {
        for (v.windows) |*w| if (std.mem.eql(u8, w.id, id)) return w;
    }
    return if (v.windows.len > 0) &v.windows[0] else null;
}

/// `path` with a leading `home` written as `~`, the way a shell prompt shows it. The
/// result is `path` itself or lives in `buf`.
pub fn tildePath(buf: []u8, path: []const u8, home: []const u8) []const u8 {
    if (home.len < 2 or !std.mem.startsWith(u8, path, home)) return path;
    const rest = path[home.len..];
    if (rest.len > 0 and rest[0] != '/') return path;
    if (rest.len + 1 > buf.len) return path;
    buf[0] = '~';
    @memcpy(buf[1 .. 1 + rest.len], rest);
    return buf[0 .. 1 + rest.len];
}

/// True when block `id` already appeared earlier in the view, so the `blocks` object
/// never repeats a key.
fn seenBefore(windows: []const Window, wi: usize, bi: usize, id: Id) bool {
    for (windows[0 .. wi + 1], 0..) |w, i| {
        const upto = if (i == wi) bi else w.blocks.len;
        for (w.blocks[0..upto]) |b| if (std.mem.eql(u8, b.id, id)) return true;
    }
    return false;
}

/// True when block `id` is in the `blocks` of any window.
fn drawnAnywhere(windows: []const Window, id: Id) bool {
    for (windows) |w| for (w.blocks) |b| if (std.mem.eql(u8, b.id, id)) return true;
    return false;
}

/// True when block `id` is a minimized block listed earlier in the view.
fn hiddenBefore(windows: []const Window, wi: usize, hi: usize, id: Id) bool {
    for (windows[0 .. wi + 1], 0..) |w, i| {
        const upto = if (i == wi) hi else w.hidden.len;
        for (w.hidden[0..upto]) |b| if (std.mem.eql(u8, b.id, id)) return true;
    }
    return false;
}

fn writeLayout(jw: *json.Stringify, layout: *const Layout) std.Io.Writer.Error!void {
    try jw.beginObject();
    switch (layout.*) {
        .block => |id| {
            try jw.objectField("block");
            try writeString(jw, id);
        },
        .split => |s| {
            try jw.objectField("dir");
            try jw.write(if (s.column) "column" else "row");
            try jw.objectField("ratio");
            try writeF32(jw, s.ratio);
            try jw.objectField("first");
            try writeLayout(jw, s.first);
            try jw.objectField("second");
            try writeLayout(jw, s.second);
        },
    }
    try jw.endObject();
}

fn writeOptString(jw: *json.Stringify, s: ?[]const u8) std.Io.Writer.Error!void {
    if (s) |x| try writeString(jw, x) else try jw.write(null);
}

/// Shortest decimal that round-trips the f32 (0.2, not 0.20000000298023224).
fn writeF32(jw: *json.Stringify, v: f32) std.Io.Writer.Error!void {
    try jw.print("{d}", .{if (std.math.isFinite(v)) v else 0});
}
