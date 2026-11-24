package io.github.yhzion.velo.ui

import android.view.KeyEvent
import io.github.yhzion.velo.core.WindowInfo

/** Ctrl+Shift: the chord of the local shortcuts that open the pane picker (F10) and the palette (P). */
internal const val PaneShortcutModifiers = KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON

/** Ctrl+Alt+Shift: the chord that moves the focused pane with an arrow key. */
internal const val MovePaneModifiers = KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON or KeyEvent.META_SHIFT_ON

private const val ShiftBits = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON or KeyEvent.META_SHIFT_RIGHT_ON
private const val AltBits = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON or KeyEvent.META_ALT_RIGHT_ON
private const val CtrlBits = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON or KeyEvent.META_CTRL_RIGHT_ON
private const val MetaBits = KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON or KeyEvent.META_META_RIGHT_ON

/**
 * True when [metaState] holds exactly the modifiers of [modifiers] (the generic `META_*_ON` flags): Shift, Alt, Ctrl,
 * Meta, Sym and Fn each held or not, as [modifiers] says. A left or right bit counts as its generic modifier. Lock
 * keys (Caps, Num, Scroll) do not count. This is [KeyEvent.hasModifiers] (`metaStateHasModifiers`) on a plain number,
 * so a JVM test can check it.
 */
internal fun metaStateIs(metaState: Int, modifiers: Int): Boolean {
    fun held(bits: Int) = metaState and bits != 0
    fun wanted(flag: Int) = modifiers and flag != 0
    return held(ShiftBits) == wanted(KeyEvent.META_SHIFT_ON) && held(AltBits) == wanted(KeyEvent.META_ALT_ON) &&
        held(CtrlBits) == wanted(KeyEvent.META_CTRL_ON) && held(MetaBits) == wanted(KeyEvent.META_META_ON) &&
        held(KeyEvent.META_SYM_ON) == wanted(KeyEvent.META_SYM_ON) &&
        held(KeyEvent.META_FUNCTION_ON) == wanted(KeyEvent.META_FUNCTION_ON)
}

/** The side an arrow key moves the pane to, or null for a key that is not an arrow. */
internal fun movePaneDirection(keyCode: Int): PaneDirection? = when (keyCode) {
    KeyEvent.KEYCODE_DPAD_LEFT -> PaneDirection.Left
    KeyEvent.KEYCODE_DPAD_RIGHT -> PaneDirection.Right
    KeyEvent.KEYCODE_DPAD_UP -> PaneDirection.Up
    KeyEvent.KEYCODE_DPAD_DOWN -> PaneDirection.Down
    else -> null
}

/**
 * Whether the app takes the move chord for [focused] toward [direction]. When this is false the chord is not
 * claimed, so the arrow key goes on to the terminal like any other key. The app takes it only while the workspace
 * takes keys ([accepting]), no overlay is open, the remote is [connected], and the pane has a neighbour there.
 * A zoomed tab and a floating pane have none ([paneToward]). While the previous move is [busy] (it has not shown
 * on screen yet, so the layout is the old one) the chord is taken whatever the old layout says, and does nothing.
 */
internal fun movePaneChord(
    accepting: Boolean,
    overlayOpen: Boolean,
    connected: Boolean,
    window: WindowInfo?,
    focused: String?,
    direction: PaneDirection,
    rtl: Boolean,
    busy: Boolean,
): Boolean =
    accepting && !overlayOpen && connected && (busy || (window != null && focused != null && paneToward(window, focused, direction, rtl) != null))

/** Tracks the local F10 shortcut while the workspace offers it. */
internal class PaneMenuShortcut {
    private var held: Pair<Int, Long>? = null

    /** Whether the press of ([deviceId], [downTime]) was claimed and its key up is still to come. */
    fun holds(deviceId: Int, downTime: Long): Boolean = held == deviceId to downTime

    fun dispatch(
        deviceId: Int,
        downTime: Long,
        action: Int,
        repeatCount: Int,
        hasShortcutModifiers: Boolean,
        canceled: Boolean,
        open: (() -> Unit)?,
    ): Boolean {
        val press = deviceId to downTime
        if (held == press) {
            if (action == KeyEvent.ACTION_UP) {
                held = null
                // Open after release so the triggering key-up never reaches the dialog.
                if (!canceled) open?.invoke()
            }
            return true
        }
        if (action != KeyEvent.ACTION_DOWN || repeatCount != 0 || !hasShortcutModifiers || open == null) return false
        held = press
        return true
    }
}

/** A physical modifier leaves the key bar armed for the key it modifies. */
internal inline fun withHardwareStickyMeta(stickyMeta: Int, isModifier: Boolean, consume: () -> Unit, send: (Int) -> Unit) {
    val meta = if (isModifier) 0 else stickyMeta
    send(meta)
    if (meta != 0) consume()
}
