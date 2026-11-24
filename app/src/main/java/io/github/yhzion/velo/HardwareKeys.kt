package io.github.yhzion.velo

/** Where [MainActivity.dispatchKeyEvent] sends a key of the activity's window ([hardwareKeyRoute]). */
internal enum class HardwareKeyRoute {
    /** To the terminal: the pane that takes the input of the workspace on screen. */
    Forward,
    /** Nowhere: it is taken and dropped. */
    Swallow,
    /** On the window's normal path to the focused view and the system. */
    PassOn,
}

/**
 * Where [MainActivity.dispatchKeyEvent] sends a key of the activity's window. Only a key of a real
 * keyboard goes to the terminal ([fromHardware]), and only when the terminal has bytes for it: a
 * system key ([systemKey], `KeyEvent.isSystem`) reaches the system. A text field of the window
 * with the focus ([otherEditorFocused]) keeps what is typed into it.
 *
 * The terminal takes the key while the workspace on screen accepts input and its terminal input
 * has the focus ([terminalOwnsFocus], `TerminalInputView.acceptsFocusedInput`): not on Settings,
 * the Tailscale screen, pairing or the start screen, not while the sidebar covers the panes, and
 * not under a dialog or a menu. Otherwise the key takes the window's path. While panes are on
 * screen but their connection is down ([panesPaused]) the key is swallowed instead. On the
 * window's path Tab and the arrows would move the focus off the terminal input and Enter would
 * press the control that got it; the panes then need no tap when the connection is back.
 */
internal fun hardwareKeyRoute(
    fromHardware: Boolean, systemKey: Boolean, terminalOwnsFocus: Boolean, panesPaused: Boolean, otherEditorFocused: Boolean,
): HardwareKeyRoute = when {
    !fromHardware || systemKey || otherEditorFocused -> HardwareKeyRoute.PassOn
    panesPaused -> HardwareKeyRoute.Swallow
    terminalOwnsFocus -> HardwareKeyRoute.Forward
    else -> HardwareKeyRoute.PassOn
}
