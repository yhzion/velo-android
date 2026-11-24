//! Splits the post-snapshot part of a Rex terminal data stream into program bytes and
//! Rex messages. A Rex message is the APC `ESC _ rex1;<nonce>;<json> ESC \` carrying the
//! channel nonce of the data connection's WELCOME; everything else is program output.

const std = @import("std");
const json = std.json;
const Allocator = std.mem.Allocator;
const model = @import("model.zig");

pub const PaletteEntry = struct { u8, [3]u8 }; // (index, rgb)

pub const RexMessage = union(enum) {
    resize: struct { cols: u16, rows: u16, cell_w_px: u32, cell_h_px: u32 },
    /// `palette` is valid only during the `message` callback.
    theme: struct { foreground: ?[3]u8, background: ?[3]u8, cursor: ?[3]u8, palette: []const PaletteEntry },
    child_exited: i32,
};

pub const Sink = struct {
    ctx: *anyopaque,
    /// Bytes of program output (Rex APCs with the channel nonce removed).
    bytes: *const fn (ctx: *anyopaque, data: []const u8) void,
    message: *const fn (ctx: *anyopaque, msg: RexMessage) void,
};

const esc = 0x1b;
const intro = "\x1b_rex1;";
/// intro + 20 decimal digits of a u64 + ';'
const max_prefix_len = intro.len + 20 + 1;
/// A message body longer than this is passed through as program bytes.
const max_body_len = 256 * 1024;

pub const StreamFilter = struct {
    gpa: Allocator,
    /// `ESC _ rex1;<nonce>;` for this channel.
    prefix_buf: [max_prefix_len]u8,
    prefix_len: usize,
    state: State = .ground,
    /// Bytes of the prefix seen so far (state `.prefix`).
    matched: usize = 0,
    /// JSON of the message being read (states `.body`, `.body_esc`).
    body: std.ArrayList(u8) = .empty,
    palette: [256]PaletteEntry = undefined,

    const State = enum {
        /// Program output.
        ground,
        /// Saw the first `matched` bytes of the prefix; they are held back.
        prefix,
        /// Inside a message with the channel nonce.
        body,
        /// Inside a message, after an ESC that may start the terminator.
        body_esc,
    };

    pub fn init(gpa: Allocator, nonce: u64) StreamFilter {
        var self: StreamFilter = .{ .gpa = gpa, .prefix_buf = undefined, .prefix_len = 0 };
        const text = std.fmt.bufPrint(&self.prefix_buf, intro ++ "{d};", .{nonce}) catch unreachable;
        self.prefix_len = text.len;
        return self;
    }

    pub fn deinit(self: *StreamFilter) void {
        self.body.deinit(self.gpa);
        self.* = undefined;
    }

    /// Feed post-snapshot stream bytes in any chunking. `ESC _ rex1;<nonce>;<json> ESC \`
    /// with a matching decimal nonce → `message`; any other bytes (including APCs with
    /// another nonce and sequences split across chunks) → `bytes`, in order.
    /// A message whose `type` is unknown is dropped. Bytes are never lost: a message that
    /// cannot be buffered or is cut short by another escape sequence goes to `bytes`.
    pub fn feed(self: *StreamFilter, data: []const u8, sink: Sink) void {
        const prefix = self.prefix_buf[0..self.prefix_len];
        var i: usize = 0;
        while (i < data.len) switch (self.state) {
            .ground => {
                const at = std.mem.findScalarPos(u8, data, i, esc) orelse {
                    sink.bytes(sink.ctx, data[i..]);
                    return;
                };
                if (at > i) sink.bytes(sink.ctx, data[i..at]);
                self.state = .prefix;
                self.matched = 1;
                i = at + 1;
            },
            .prefix => {
                if (data[i] == prefix[self.matched]) {
                    self.matched += 1;
                    i += 1;
                    if (self.matched == prefix.len) {
                        self.body.clearRetainingCapacity();
                        self.state = .body;
                    }
                } else {
                    // Not a message of this channel: the held bytes are program output.
                    // data[i] is looked at again; it may start another sequence.
                    sink.bytes(sink.ctx, prefix[0..self.matched]);
                    self.state = .ground;
                }
            },
            .body => {
                const at = std.mem.findScalarPos(u8, data, i, esc);
                const chunk = data[i .. at orelse data.len];
                if (self.body.items.len + chunk.len > max_body_len or !self.hold(chunk)) {
                    // Give up on this message; the rest of it flows through as bytes.
                    self.release(sink);
                    self.state = .ground;
                    continue;
                }
                i += chunk.len;
                if (at != null) {
                    i += 1;
                    self.state = .body_esc;
                }
            },
            .body_esc => {
                if (data[i] == '\\') {
                    i += 1;
                    self.state = .ground;
                    self.dispatch(sink);
                } else {
                    // Another escape sequence cut the APC short. Its ESC is held as a
                    // possible start of the next message.
                    self.release(sink);
                    self.state = .prefix;
                    self.matched = 1;
                }
            },
        };
    }

    fn hold(self: *StreamFilter, chunk: []const u8) bool {
        self.body.appendSlice(self.gpa, chunk) catch return false;
        return true;
    }

    /// Pass the held prefix and body through as program bytes.
    fn release(self: *StreamFilter, sink: Sink) void {
        sink.bytes(sink.ctx, self.prefix_buf[0..self.prefix_len]);
        if (self.body.items.len > 0) sink.bytes(sink.ctx, self.body.items);
        self.body.clearRetainingCapacity();
    }

    fn dispatch(self: *StreamFilter, sink: Sink) void {
        var arena: std.heap.ArenaAllocator = .init(self.gpa);
        defer arena.deinit();
        const msg = self.decode(arena.allocator()) orelse return;
        sink.message(sink.ctx, msg);
    }

    /// The held body as a RexMessage; null for an unknown or malformed message.
    fn decode(self: *StreamFilter, arena: Allocator) ?RexMessage {
        const root = json.parseFromSliceLeaky(json.Value, arena, self.body.items, .{}) catch return null;
        const kind = model.str(model.get(root, "type")) orelse return null;

        if (std.mem.eql(u8, kind, "resize")) {
            const cols = clampInt(u16, model.int(model.get(root, "cols")));
            const rows = clampInt(u16, model.int(model.get(root, "rows")));
            if (cols == 0 or rows == 0) return null;
            return .{ .resize = .{
                .cols = cols,
                .rows = rows,
                .cell_w_px = clampInt(u32, model.int(model.get(root, "cell_width_px"))),
                .cell_h_px = clampInt(u32, model.int(model.get(root, "cell_height_px"))),
            } };
        }

        if (std.mem.eql(u8, kind, "theme")) {
            // The last entry for an index wins; entries come out in index order.
            var colors: [256]?[3]u8 = @splat(null);
            for (model.items(model.get(root, "palette"))) |entry| {
                const text = model.str(entry) orelse continue;
                const eq = std.mem.findScalar(u8, text, '=') orelse continue;
                const index = std.fmt.parseUnsigned(u8, text[0..eq], 10) catch continue;
                colors[index] = parseColor(text[eq + 1 ..]) orelse continue;
            }
            var n: usize = 0;
            for (colors, 0..) |color, index| if (color) |rgb| {
                self.palette[n] = .{ @intCast(index), rgb };
                n += 1;
            };
            return .{ .theme = .{
                .foreground = parseColor(model.str(model.get(root, "foreground")) orelse ""),
                .background = parseColor(model.str(model.get(root, "background")) orelse ""),
                .cursor = parseColor(model.str(model.get(root, "cursor")) orelse ""),
                .palette = self.palette[0..n],
            } };
        }

        if (std.mem.eql(u8, kind, "child_exited")) {
            const code = model.int(model.get(root, "exit_code")) orelse 0;
            return .{ .child_exited = std.math.cast(i32, code) orelse -1 };
        }

        return null;
    }
};

fn clampInt(comptime T: type, v: ?i64) T {
    const x = v orelse return 0;
    return @intCast(std.math.clamp(x, 0, std.math.maxInt(T)));
}

/// `RRGGBB` or `#RRGGBB`; null for anything else (an empty color is the built-in one).
fn parseColor(text: []const u8) ?[3]u8 {
    const hex = if (text.len > 0 and text[0] == '#') text[1..] else text;
    if (hex.len != 6) return null;
    var rgb: [3]u8 = undefined;
    for (&rgb, 0..) |*channel, i| {
        const hi = std.fmt.charToDigit(hex[i * 2], 16) catch return null;
        const lo = std.fmt.charToDigit(hex[i * 2 + 1], 16) catch return null;
        channel.* = hi << 4 | lo;
    }
    return rgb;
}
