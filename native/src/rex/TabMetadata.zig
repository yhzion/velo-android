//! Display metadata from the terminal's process and program_status methods.
const std = @import("std");
const model = @import("model.zig");

pub fn agent(value: []const u8) ?[]const u8 {
    const base = std.fs.path.basenameWindows(value);
    const name = if (std.ascii.endsWithIgnoreCase(base, ".exe")) base[0 .. base.len - 4] else base;
    const aliases = .{
        .{ "claude", "claude" },      .{ "claude-code", "claude" },
        .{ "codex", "codex" },        .{ "codex-cli", "codex" },
        .{ "openai-codex", "codex" }, .{ "pi", "pi" },
        .{ "pi-coding-agent", "pi" }, .{ "omp", "omp" },
        .{ "oh-my-pi", "omp" },       .{ "gemini", "gemini" },
        .{ "gemini-cli", "gemini" },  .{ "opencode", "opencode" },
    };
    inline for (aliases) |pair| if (std.ascii.eqlIgnoreCase(name, pair[0])) return pair[1];
    return null;
}

pub fn processAgent(proc: std.json.Value) ?[]const u8 {
    // An agent run by an interpreter such as node shows up only in script.
    for ([_][]const u8{ "invoked_path", "argv0", "name", "path", "script" }) |field| {
        const path = model.str(model.get(proc, field)) orelse continue;
        if (agent(path)) |id| return id;
        // Native Claude installs resolve the executable symlink to a version number.
        if (std.mem.find(u8, path, "/.local/share/claude/versions/") != null) return "claude";
        // A package's entry script can be named cli.js even when its interpreter is node.
        const packages = .{
            .{ "/node_modules/@anthropic-ai/claude-code/", "claude" },
            .{ "/node_modules/@openai/codex/", "codex" },
            .{ "/node_modules/@mariozechner/pi-coding-agent/", "pi" },
            .{ "/node_modules/@earendil-works/pi-coding-agent/", "pi" },
            .{ "/node_modules/@oh-my-pi/pi-coding-agent/", "omp" },
            .{ "/node_modules/@google/gemini-cli/", "gemini" },
        };
        inline for (packages) |pair| if (std.mem.find(u8, path, pair[0]) != null) return pair[1];
    }
    return null;
}

const Kind = enum { permission, question, auth };

pub const Details = struct {
    app: []const u8 = "",
    app_from_child: bool = false,
    title: []const u8 = "",
    summary: []const u8 = "",
    kind: ?Kind = null,
    reported_idle: bool = false,

    /// The root names the conversation. A child can supply its own identity and
    /// reason for attention. The server resolves inherited identities in effective_app.
    pub fn read(result: std.json.Value, activity: model.Activity, since: ?i64) Details {
        var root: std.json.Value = .null;
        var selected: std.json.Value = .null;
        for (model.items(model.get(result, "records"))) |r| {
            const state = std.meta.stringToEnum(model.Activity, string(r, "state")) orelse continue;
            if (string(r, "id").len == 0) root = r;
            if (state != activity or selected != .null) continue;
            if (since) |ms| {
                if (model.parseRfc3339Ms(string(r, "updated_at")) != ms) continue;
            }
            selected = r;
        }
        const root_app = first(string(root, "app"), string(root, "effective_app"));
        const app = first(root_app, first(string(selected, "app"), string(selected, "effective_app")));
        return .{
            .app = agent(app) orelse app,
            .app_from_child = root_app.len == 0 and app.len > 0 and string(selected, "id").len > 0,
            .title = first(string(root, "title"), string(selected, "title")),
            .summary = first(string(selected, "msg"), string(root, "msg")),
            .kind = if (activity == .blocked) std.meta.stringToEnum(Kind, string(selected, "kind")) else null,
            .reported_idle = activity == .idle and selected != .null,
        };
    }

    /// Entry and Lead own their strings independently of the response and each other.
    pub fn replace(self: *Details, gpa: std.mem.Allocator, next: Details) !bool {
        if (self.kind == next.kind and self.reported_idle == next.reported_idle and self.app_from_child == next.app_from_child and
            std.mem.eql(u8, self.app, next.app) and std.mem.eql(u8, self.title, next.title) and
            std.mem.eql(u8, self.summary, next.summary)) return false;
        const app = try gpa.dupe(u8, next.app);
        errdefer gpa.free(app);
        const title = try gpa.dupe(u8, next.title);
        errdefer gpa.free(title);
        const summary = try gpa.dupe(u8, next.summary);
        self.deinit(gpa);
        self.* = next;
        self.app = app;
        self.title = title;
        self.summary = summary;
        return true;
    }

    pub fn deinit(self: Details, gpa: std.mem.Allocator) void {
        gpa.free(self.app);
        gpa.free(self.title);
        gpa.free(self.summary);
    }
};

fn string(record: std.json.Value, key: []const u8) []const u8 {
    return model.str(model.get(record, key)) orelse "";
}

fn first(value: []const u8, fallback: []const u8) []const u8 {
    return if (value.len > 0) value else fallback;
}

test "agent identity uses a launcher name or installed package, never a version or conversation text" {
    var arena: std.heap.ArenaAllocator = .init(std.testing.allocator);
    defer arena.deinit();
    for ([_]struct { []const u8, ?[]const u8 }{
        .{ "{\"name\":\"2.1.329\",\"invoked_path\":\"/usr/bin/claude\"}", "claude" },
        .{ "{\"name\":\"2.1.329\",\"path\":\"/home/u/.local/share/claude/versions/2.1.329\"}", "claude" },
        .{ "{\"name\":\"node\",\"argv0\":\"/bin/codex\"}", "codex" },
        .{ "{\"name\":\"bun\",\"path\":\"/bin/omp\"}", "omp" },
        .{ "{\"name\":\"node\",\"argv0\":\"/lib/node_modules/@mariozechner/pi-coding-agent/dist/cli.js\"}", "pi" },
        .{ "{\"name\":\"pi\",\"path\":\"/bin/node\"}", "pi" },
        // Gemini CLI runs under node, so only the script names it: a bin link or the package's entry.
        .{ "{\"name\":\"node\",\"path\":\"/bin/node\",\"argv0\":\"node\",\"script\":\"/usr/local/bin/gemini\"}", "gemini" },
        .{ "{\"name\":\"node\",\"argv0\":\"node\",\"script\":\"/lib/node_modules/@google/gemini-cli/bundle/gemini.js\"}", "gemini" },
        .{ "{\"name\":\"gemini-cli\",\"path\":\"/bin/node\"}", "gemini" },
        .{ "{\"name\":\"2.1.329\",\"path\":\"/bin/2.1.329\"}", null },
        .{ "{\"name\":\"node\",\"cwd\":\"/work/codex\",\"path\":\"/bin/node\"}", null },
        .{ "{\"name\":\"node\",\"argv0\":\"node\",\"script\":\"/work/gemini/index.js\"}", null },
        .{ "{\"name\":\"python3\",\"script\":\"/home/u/bin/pi.py\"}", null },
        // OpenCode is its own executable, which its npm package installs as bin/opencode.exe on every platform.
        .{ "{\"name\":\"opencode.exe\",\"argv0\":\"opencode\",\"invoked_path\":\"/home/u/lib/node_modules/.bin/opencode\",\"path\":\"/home/u/lib/node_modules/opencode-ai/bin/opencode.exe\"}", "opencode" },
        .{ "{\"name\":\"opencode.exe\",\"argv0\":\"oc\",\"path\":\"/home/u/lib/node_modules/opencode-ai/bin/opencode.exe\"}", "opencode" },
        .{ "{\"name\":\"node\",\"argv0\":\"node\",\"cwd\":\"/work/opencode\",\"script\":\"/work/opencode/index.js\"}", null },
    }) |case| {
        const proc = try std.json.parseFromSliceLeaky(std.json.Value, arena.allocator(), case[0], .{});
        try std.testing.expectEqualStrings(case[1] orelse "", processAgent(proc) orelse "");
    }
}

test "the conversation title and the reason for attention can come from different records" {
    var arena: std.heap.ArenaAllocator = .init(std.testing.allocator);
    defer arena.deinit();
    const response = try std.json.parseFromSliceLeaky(std.json.Value, arena.allocator(),
        \\{"records":[{"id":"worker","state":"blocked","kind":"question","effective_app":"pi","title":"Worker"},
        \\{"state":"idle","app":"pi","title":"Release review"}]}
    , .{});
    const details = Details.read(response, .blocked, null);
    try std.testing.expectEqualStrings("pi", details.app);
    try std.testing.expectEqualStrings("Release review", details.title);
    try std.testing.expectEqual(Kind.question, details.kind.?);
    try std.testing.expect(!details.reported_idle);
    try std.testing.expect(Details.read(response, .idle, null).reported_idle);
}
