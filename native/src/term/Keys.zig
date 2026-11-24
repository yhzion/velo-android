//! Android KeyEvent → libghostty-vt key event mapping.
const c = @import("c");

/// android.view.KeyEvent meta state bits.
pub const META_SHIFT_ON: i32 = 0x1;
pub const META_ALT_ON: i32 = 0x2;
pub const META_CTRL_ON: i32 = 0x1000;
pub const META_META_ON: i32 = 0x10000;
pub const META_CAPS_LOCK_ON: i32 = 0x100000;
pub const META_NUM_LOCK_ON: i32 = 0x200000;

/// android.view.KeyEvent key codes used here.
pub const KEYCODE_0: i32 = 7;
pub const KEYCODE_9: i32 = 16;
pub const KEYCODE_DPAD_UP: i32 = 19;
pub const KEYCODE_DPAD_DOWN: i32 = 20;
pub const KEYCODE_DPAD_LEFT: i32 = 21;
pub const KEYCODE_DPAD_RIGHT: i32 = 22;
pub const KEYCODE_A: i32 = 29;
pub const KEYCODE_Z: i32 = 54;
pub const KEYCODE_COMMA: i32 = 55;
pub const KEYCODE_PERIOD: i32 = 56;
pub const KEYCODE_TAB: i32 = 61;
pub const KEYCODE_SPACE: i32 = 62;
pub const KEYCODE_ENTER: i32 = 66;
pub const KEYCODE_DEL: i32 = 67;
pub const KEYCODE_GRAVE: i32 = 68;
pub const KEYCODE_MINUS: i32 = 69;
pub const KEYCODE_EQUALS: i32 = 70;
pub const KEYCODE_LEFT_BRACKET: i32 = 71;
pub const KEYCODE_RIGHT_BRACKET: i32 = 72;
pub const KEYCODE_BACKSLASH: i32 = 73;
pub const KEYCODE_SEMICOLON: i32 = 74;
pub const KEYCODE_APOSTROPHE: i32 = 75;
pub const KEYCODE_SLASH: i32 = 76;
pub const KEYCODE_PAGE_UP: i32 = 92;
pub const KEYCODE_PAGE_DOWN: i32 = 93;
pub const KEYCODE_ESCAPE: i32 = 111;
pub const KEYCODE_FORWARD_DEL: i32 = 112;
pub const KEYCODE_MOVE_HOME: i32 = 122;
pub const KEYCODE_MOVE_END: i32 = 123;
pub const KEYCODE_INSERT: i32 = 124;
pub const KEYCODE_F1: i32 = 131;
pub const KEYCODE_F12: i32 = 142;
pub const KEYCODE_NUMPAD_ENTER: i32 = 160;

pub const Mapped = struct {
    key: c.GhosttyKey,
    /// Unshifted US-layout code point for printable keys, 0 otherwise.
    unshifted: u32 = 0,
};

pub fn mapKey(code: i32) Mapped {
    if (code >= KEYCODE_A and code <= KEYCODE_Z) {
        const i: u32 = @intCast(code - KEYCODE_A);
        return .{ .key = @intCast(c.GHOSTTY_KEY_A + @as(c_int, @intCast(i))), .unshifted = 'a' + i };
    }
    if (code >= KEYCODE_0 and code <= KEYCODE_9) {
        const i: u32 = @intCast(code - KEYCODE_0);
        return .{ .key = @intCast(c.GHOSTTY_KEY_DIGIT_0 + @as(c_int, @intCast(i))), .unshifted = '0' + i };
    }
    if (code >= KEYCODE_F1 and code <= KEYCODE_F12)
        return .{ .key = @intCast(c.GHOSTTY_KEY_F1 + (code - KEYCODE_F1)) };
    const k: c_int, const u: u32 = switch (code) {
        KEYCODE_DPAD_UP => .{ c.GHOSTTY_KEY_ARROW_UP, 0 },
        KEYCODE_DPAD_DOWN => .{ c.GHOSTTY_KEY_ARROW_DOWN, 0 },
        KEYCODE_DPAD_LEFT => .{ c.GHOSTTY_KEY_ARROW_LEFT, 0 },
        KEYCODE_DPAD_RIGHT => .{ c.GHOSTTY_KEY_ARROW_RIGHT, 0 },
        KEYCODE_COMMA => .{ c.GHOSTTY_KEY_COMMA, ',' },
        KEYCODE_PERIOD => .{ c.GHOSTTY_KEY_PERIOD, '.' },
        KEYCODE_TAB => .{ c.GHOSTTY_KEY_TAB, 0 },
        KEYCODE_SPACE => .{ c.GHOSTTY_KEY_SPACE, ' ' },
        KEYCODE_ENTER, KEYCODE_NUMPAD_ENTER => .{ c.GHOSTTY_KEY_ENTER, 0 },
        KEYCODE_DEL => .{ c.GHOSTTY_KEY_BACKSPACE, 0 },
        KEYCODE_GRAVE => .{ c.GHOSTTY_KEY_BACKQUOTE, '`' },
        KEYCODE_MINUS => .{ c.GHOSTTY_KEY_MINUS, '-' },
        KEYCODE_EQUALS => .{ c.GHOSTTY_KEY_EQUAL, '=' },
        KEYCODE_LEFT_BRACKET => .{ c.GHOSTTY_KEY_BRACKET_LEFT, '[' },
        KEYCODE_RIGHT_BRACKET => .{ c.GHOSTTY_KEY_BRACKET_RIGHT, ']' },
        KEYCODE_BACKSLASH => .{ c.GHOSTTY_KEY_BACKSLASH, '\\' },
        KEYCODE_SEMICOLON => .{ c.GHOSTTY_KEY_SEMICOLON, ';' },
        KEYCODE_APOSTROPHE => .{ c.GHOSTTY_KEY_QUOTE, '\'' },
        KEYCODE_SLASH => .{ c.GHOSTTY_KEY_SLASH, '/' },
        KEYCODE_PAGE_UP => .{ c.GHOSTTY_KEY_PAGE_UP, 0 },
        KEYCODE_PAGE_DOWN => .{ c.GHOSTTY_KEY_PAGE_DOWN, 0 },
        KEYCODE_ESCAPE => .{ c.GHOSTTY_KEY_ESCAPE, 0 },
        KEYCODE_FORWARD_DEL => .{ c.GHOSTTY_KEY_DELETE, 0 },
        KEYCODE_MOVE_HOME => .{ c.GHOSTTY_KEY_HOME, 0 },
        KEYCODE_MOVE_END => .{ c.GHOSTTY_KEY_END, 0 },
        KEYCODE_INSERT => .{ c.GHOSTTY_KEY_INSERT, 0 },
        else => .{ c.GHOSTTY_KEY_UNIDENTIFIED, 0 },
    };
    return .{ .key = @intCast(k), .unshifted = u };
}

pub fn mapMods(meta: i32) c.GhosttyMods {
    var m: c_int = 0;
    if (meta & META_SHIFT_ON != 0) m |= c.GHOSTTY_MODS_SHIFT;
    if (meta & META_CTRL_ON != 0) m |= c.GHOSTTY_MODS_CTRL;
    if (meta & META_ALT_ON != 0) m |= c.GHOSTTY_MODS_ALT;
    if (meta & META_META_ON != 0) m |= c.GHOSTTY_MODS_SUPER;
    if (meta & META_CAPS_LOCK_ON != 0) m |= c.GHOSTTY_MODS_CAPS_LOCK;
    if (meta & META_NUM_LOCK_ON != 0) m |= c.GHOSTTY_MODS_NUM_LOCK;
    return @intCast(m);
}

pub fn mapAction(action: u8) c.GhosttyKeyAction {
    return @intCast(switch (action) {
        1 => c.GHOSTTY_KEY_ACTION_RELEASE,
        2 => c.GHOSTTY_KEY_ACTION_REPEAT,
        else => c.GHOSTTY_KEY_ACTION_PRESS,
    });
}
