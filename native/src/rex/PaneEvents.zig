const std = @import("std");
const model = @import("model.zig");
const json = std.json;

const Target = model.PaneAddress;

pub const Watch = struct {
    target: Target,
    serial: u64,
    baseline_pending: bool = false,
    location_pending: bool = false,
    revision: i64 = -1,
    // Live records wait in wire order for the baseline or the location check.
    reports: std.ArrayList([]u8) = .empty,

    pub fn reset(self: *Watch, gpa: std.mem.Allocator) void {
        for (self.reports.items) |r| gpa.free(r);
        self.reports.deinit(gpa);
        self.* = .{ .target = self.target, .serial = self.serial };
    }
};

pub const Watches = struct {
    targets: json.Parsed([]const Target),
    items: []Watch,

    pub fn init(gpa: std.mem.Allocator, value: json.Value) !Watches {
        const targets = try json.parseFromValue([]const Target, gpa, value, .{});
        errdefer targets.deinit();
        const items = try targets.arena.allocator().alloc(Watch, targets.value.len);
        for (items, targets.value) |*w, t| w.* = .{ .target = t, .serial = 0 };
        return .{ .targets = targets, .items = items };
    }

    pub fn deinit(self: Watches, gpa: std.mem.Allocator) void {
        for (self.items) |*w| w.reset(gpa);
        self.targets.deinit();
    }
};

pub fn same(a: Target, b: Target) bool {
    return std.mem.eql(u8, a.session, b.session) and std.mem.eql(u8, a.window, b.window) and std.mem.eql(u8, a.block, b.block);
}

fn agent(record: json.Value) ?[]const u8 {
    const app = model.str(model.get(record, "app")) orelse "";
    return if (app.len > 0) app else model.str(model.get(record, "effective_app"));
}

pub fn report(gpa: std.mem.Allocator, target: Target, event: json.Value) !?[]u8 {
    if (!std.mem.eql(u8, model.str(model.get(event, "creator_name")) orelse "", model.terminal_creator)) return null;
    const name = model.str(model.get(event, "name")) orelse return null;
    const data = model.get(event, "payload") orelse return null;
    if (std.mem.eql(u8, name, "program_status_changed")) {
        const record = model.get(data, "record") orelse return null;
        const state = model.str(model.get(record, "state")) orelse return null;
        return try json.Stringify.valueAlloc(gpa, .{
            .target = target,
            .id = model.str(model.get(data, "id")) orelse model.str(model.get(record, "id")) orelse "",
            .state = state,
            .kind = model.str(model.get(record, "kind")),
            .agent = agent(record),
            .updatedAt = model.str(model.get(record, "updated_at")),
            // What the work is and how it goes, for the notification's text.
            .title = model.str(model.get(record, "title")),
            .msg = model.str(model.get(record, "msg")),
        }, .{});
    }
    if (std.mem.eql(u8, name, "program_status_removed")) {
        const ids = model.get(data, "ids") orelse return null;
        if (ids != .array) return null;
        return try json.Stringify.valueAlloc(gpa, .{ .target = target, .removed = ids }, .{});
    }
    return null;
}

/// The initial snapshot also supplies the per-pane status badge.
pub fn baseline(gpa: std.mem.Allocator, target: Target, records: json.Value, pending: []const []u8) ![]u8 {
    const Record = struct { id: []const u8, state: []const u8, updatedAt: ?[]const u8, kind: ?[]const u8, agent: ?[]const u8 };
    var seen: std.ArrayList(Record) = .empty;
    defer seen.deinit(gpa);
    for (model.items(records)) |record| {
        try seen.append(gpa, .{
            .id = model.str(model.get(record, "id")) orelse "",
            .state = model.str(model.get(record, "state")) orelse continue,
            .kind = model.str(model.get(record, "kind")),
            .agent = agent(record),
            .updatedAt = model.str(model.get(record, "updated_at")),
        });
    }
    var arena: std.heap.ArenaAllocator = .init(gpa);
    defer arena.deinit();
    const live = try arena.allocator().alloc(json.Value, pending.len);
    for (pending, live) |report_json, *item|
        item.* = try json.parseFromSliceLeaky(json.Value, arena.allocator(), report_json, .{});
    return json.Stringify.valueAlloc(gpa, .{ .target = target, .baseline = seen.items, .pending = live }, .{});
}

pub fn contains(view: *const model.View, target: Target) bool {
    if (!std.mem.eql(u8, view.session, target.session)) return false;
    for (view.windows) |w| {
        if (!std.mem.eql(u8, w.id, target.window)) continue;
        for (w.blocks) |b| if (std.mem.eql(u8, b.id, target.block)) return true;
        for (w.hidden) |b| if (std.mem.eql(u8, b.id, target.block)) return true;
    }
    return false;
}
