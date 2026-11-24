package io.github.yhzion.velo.ui

import android.view.KeyEvent

/** A key a tap gesture can send (Settings → Keyboard → Tap gestures), or none. */
enum class TapKey(val label: String, val keyCode: Int?) {
    None("None", null),
    Tab("Tab", KeyEvent.KEYCODE_TAB),
    Enter("Enter", KeyEvent.KEYCODE_ENTER),
    Esc("Esc", KeyEvent.KEYCODE_ESCAPE),
    Left("Left", KeyEvent.KEYCODE_DPAD_LEFT),
    Right("Right", KeyEvent.KEYCODE_DPAD_RIGHT),
    Up("Up", KeyEvent.KEYCODE_DPAD_UP),
    Down("Down", KeyEvent.KEYCODE_DPAD_DOWN);

    /** The saved name, which stays when the label changes. */
    val saved: String get() = name

    companion object {
        fun parse(saved: String?, default: TapKey): TapKey = entries.find { it.name == saved } ?: default
    }
}

/**
 * One finger's tap sequence, shared by the panes. A sequence belongs to the pane where its
 * first finger went down. If that pane was not the input target, every tap in the sequence
 * remains the pane's own, even if its first tap moved the input there.
 *
 * On the target, the pane action (focus, show keyboard) waits for a second finger. A double
 * key waits for a third finger only if a triple key is configured. Each wait runs from the
 * last up to the next down, using Android's timeout and slop. A new down takes the pending
 * action out of the queue: its up decides the gesture, or [cancel] discards it.
 */
class TapGestureInput<P : Any>(
    private val doubleTapTimeoutMs: Long,
    private val slopPx: () -> Float,
    private val post: (Runnable, Long) -> Unit,
    private val unpost: (Runnable) -> Unit,
    private val now: () -> Long,
) {
    /** The keys of a double and a triple tap; null while tap gestures are off or touch is explored. */
    var keys: () -> Pair<TapKey, TapKey>? = { null }
    /** The pane that takes keys now, or null. */
    var target: () -> P? = { null }
    /** Sends a key to the pane that takes keys, as the key bar's keys go. */
    var send: (TapKey) -> Unit = {}
    /**
     * A selection in the pane has priority over tap gestures. A program that takes mouse reports
     * does not: its pane makes keys as any other, and a single there is also a click (#307).
     */
    var paneSelects: (P) -> Boolean = { false }
    /** Counts each change of the pane's input target that the app saw ([HostConnection.focusMoves]). */
    var moves: (P) -> Long = { 0 }

    private var pane: P? = null
    private var count = 0
    private var firstX = 0f
    private var firstY = 0f
    private var deadline = 0L
    private var armed = false
    // A spare tap immediately after a completed key may start another key, but isn't a single.
    private var followsKey = false
    private var single: (() -> Unit)? = null
    private var waitingKey = TapKey.None
    /** [moves] when the run began: the input that left the pane, even to come back, ends it. */
    private var since = 0L
    /** A finger went [down] and its tap has not come yet: only that tap counts in the run. */
    private var fingerDown = false
    private val expire = Runnable {
        val p = pane ?: return@Runnable
        val action = single
        val key = waitingKey
        val eligible = keys() != null && takes(p)
        cancel()
        if (eligible) {
            if (action != null) action() else if (key != TapKey.None) send(key)
        }
    }

    /** Delivery is allowed only while the same pane has continuously owned the input. */
    private fun takes(p: P) = target() == p && !paneSelects(p) && moves(p) == since

    /** A down either takes over the pending decision or starts a new sequence. */
    fun down(p: P, atMs: Long, x: Float, y: Float) {
        unpost(expire)
        fingerDown = true
        single = null
        waitingKey = TapKey.None
        val dx = x - firstX
        val dy = y - firstY
        val slop = slopPx()
        if (p == pane && (count > 0 || followsKey) && atMs <= deadline && dx * dx + dy * dy <= slop * slop) return
        followsKey = false
        pane = p
        count = 0
        firstX = x
        firstY = y
        since = moves(p)
        armed = keys() != null && takes(p)
    }

    /**
     * True if this controller owns the tap, including calling [onSingle] if it stays a single. A
     * tap without a down of its own (a path that named no file, made the pane's after the server's
     * answer) is not owned and leaves the run as it is: the caller makes its single at once.
     */
    fun tap(p: P, atMs: Long, onSingle: () -> Unit): Boolean {
        if (!fingerDown) return false
        fingerDown = false
        val configured = keys()
        if (p != pane || configured == null) {
            cancel()
            return false
        }
        count++
        deadline = atMs + doubleTapTimeoutMs
        if (!armed) return false
        if (!takes(p)) {
            cancel()
            return false
        }
        val (double, triple) = configured
        when {
            count == 1 -> if (!followsKey) single = onSingle
            count == 2 && triple != TapKey.None -> waitingKey = double
            else -> {
                val key = if (count == 2) double else triple
                // A triple with only a double configured must not turn its last tap into an IME request.
                count = 0
                followsKey = true
                if (key != TapKey.None) send(key)
                return true
            }
        }
        post(expire, (deadline - now()).coerceAtLeast(0))
        return true
    }

    /** The run ends with nothing sent. */
    fun cancel() {
        unpost(expire)
        single = null
        waitingKey = TapKey.None
        count = 0
        followsKey = false
        fingerDown = false
        pane = null
    }
}
