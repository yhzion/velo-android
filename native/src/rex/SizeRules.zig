//! The terminal size rules for one terminal block: when this client takes the block and
//! which size it asks for. See docs/native-core.md, "Terminal size". Pure decisions, no
//! I/O and no clock: the core reports what happened to the pane and sends what
//! `State.step` returns.
//!
//! A Rex terminal takes its size from the client that owns its block, and the server
//! keeps the size each client asked for last. So this client takes a block when its pane
//! is shown or used, and asks for the size that fits the pane. It never gives a block up:
//! a client that takes it gets its own size back. Three things are neither: looking at the
//! tab overview and coming back to the same tab, the app leaving the screen (the screen
//! went off, another app came to the front) and coming back, and the app's own sidebar or
//! screen covering the panes and giving them back. A pane that was shown before is then
//! shown again without a take (`resumed`).

/// A terminal size request: the grid, and the size of one cell in px.
pub const Size = struct {
    cols: u16,
    rows: u16,
    cell_w: u16,
    cell_h: u16,

    pub fn eql(a: Size, b: Size) bool {
        return a.cols == b.cols and a.rows == b.rows and a.cell_w == b.cell_w and a.cell_h == b.cell_h;
    }
};

pub const Event = enum {
    /// The pane was given a surface. The first one with an area shows the pane; one with
    /// a zero axis (`wanted` is null) leaves it not shown. One for a pane that is shown
    /// (the view changed size) sends nothing by itself, and the app reports the settled
    /// size with `fit`.
    surface,
    /// The pane's surface went away.
    hidden,
    /// The pane's surface is a preview from now on (the tab overview): it draws the pane,
    /// but the pane is not shown and using it claims nothing, until `surface`, `resumed`
    /// or `hidden`.
    preview,
    /// The pane's surface went away and the pane did not: the app left the screen (the
    /// screen went off, another app came to the front), or its own sidebar or screen covers
    /// the panes. The pane is not shown, as after `hidden`, but what it was is kept for
    /// `resumed`.
    suspended,
    /// The app says the pane is back where it was shown before: the overview closed on the
    /// tab it was opened from, the app came back to the screen, or what covered the panes
    /// is gone. For a pane that was shown when its preview began or it was `suspended`, and
    /// has not been `hidden` since, that is no use of the terminal: the pane is shown as it
    /// was, with no take, and the size is asked only if it is not the one asked last. For
    /// every other pane it is `surface`.
    resumed,
    /// The size that fits the pane may have changed: the app's `fit` intent (a settled
    /// surface size, a finished pinch) or a font size, line height or padding setting.
    fit,
    /// The user touched the pane, or sent it key or text input.
    used,
    /// Requests can go out again (a new control connection attached the session).
    online,
    /// The take that was in flight succeeded; `owned` and `take_inflight` are set.
    answered,
    /// The take that was in flight was refused; `take_inflight` is cleared.
    refused,
};

/// What to send for the block, in this order.
pub const Actions = struct {
    /// `session.own_block` in mode `take`.
    take: bool = false,
    /// `com.superlogical.terminal.resize` with this size.
    resize: ?Size = null,
};

pub const State = struct {
    /// The pane has a surface with an area, and that surface is not a preview.
    shown: bool = false,
    /// The pane's surface is a preview: never shown, and a use takes nothing.
    preview: bool = false,
    /// The pane's surface went away and the pane did not (`suspended`): the app left the
    /// screen, or its own sidebar or screen covers the panes. The surface has not come back.
    away: bool = false,
    /// The pane was shown when its surface became a preview or went away with the pane
    /// kept (`suspended`), and has not been shown or `hidden` since: what `resumed` goes
    /// back to, once.
    was_shown: bool = false,
    /// A take was still owed then: it is owed again after `resumed`.
    was_take_pending: bool = false,
    /// The pane became shown and its take is not sent yet: there was no connection, or
    /// an earlier take is still in flight.
    take_pending: bool = false,
    /// The size of a shown pane must be compared with `asked` once it can be sent and
    /// there is an area to fit.
    ask_pending: bool = false,
    /// The size asked for last on this connection: what the server holds for this client.
    asked: ?Size = null,
    /// The server's last word: this client owns the block.
    owned: bool = false,
    /// A take is on its way: no second one until its answer.
    take_inflight: bool = false,

    /// Apply `event`. `wanted`: the size that fits the pane now, null when its surface
    /// has no area. `can_send`: requests for the block can go out now (connected and
    /// attached to its session). The state already counts what is returned as sent.
    pub fn step(s: *State, event: Event, wanted: ?Size, can_send: bool) Actions {
        var out: Actions = .{};
        switch (event) {
            .surface, .resumed => {
                const back = event == .resumed and (s.preview or s.away) and s.was_shown;
                const take_owed = s.was_take_pending;
                s.preview = false;
                s.away = false;
                s.was_shown = false;
                s.was_take_pending = false;
                if (!s.shown and wanted != null) {
                    s.shown = true;
                    s.take_pending = if (back) take_owed else true;
                    s.ask_pending = true;
                }
            },
            .hidden, .preview, .suspended => {
                if (event != .hidden and s.shown) {
                    s.was_shown = true;
                    s.was_take_pending = s.take_pending;
                } else if (event == .hidden or !(s.preview or s.away)) {
                    s.was_shown = false;
                    s.was_take_pending = false;
                }
                s.preview = event == .preview;
                s.away = event == .suspended;
                s.shown = false;
                s.take_pending = false;
                s.ask_pending = false;
            },
            .fit => if (s.shown) {
                s.ask_pending = true;
            },
            // Input typed without a connection is dropped, and so is its take. A preview
            // is only looked at: input for it goes to the terminal and claims nothing.
            .used => if (!s.preview and can_send and s.take()) {
                out.take = true;
                // The server holds no size from this connection to apply to the take.
                if (s.shown and s.asked == null) s.ask_pending = true;
            },
            // A refused take is not sent again by itself: the next use or show tries.
            .refused => s.take_pending = false,
            // A reconnect alone claims nothing: the server gives a client its blocks back.
            .online, .answered => {},
        }
        if (!can_send) return out;
        if (s.take_pending) {
            if (s.owned) {
                s.take_pending = false;
            } else if (s.take()) {
                s.take_pending = false;
                out.take = true;
            }
            // Else one is in flight: its answer decides (`answered`).
        }
        // A surface without an area has nothing to ask for; the ask waits for one.
        if (s.ask_pending) if (wanted) |size| {
            s.ask_pending = false;
            out.resize = s.ask(size);
        };
        return out;
    }

    fn take(s: *State) bool {
        if (s.owned or s.take_inflight) return false;
        s.take_inflight = true;
        return true;
    }

    fn ask(s: *State, size: Size) ?Size {
        if (s.asked) |last| if (last.eql(size)) return null;
        s.asked = size;
        return size;
    }

    /// The control connection ended: its requests got no answer, and the next `surface`
    /// or `fit` asks for the size again.
    pub fn linkLost(s: *State) void {
        s.asked = null;
        s.take_inflight = false;
    }
};
