package io.github.yhzion.velo.ui

import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowManager.LayoutParams
import android.view.inputmethod.InputMethodManager

/** Focus the terminal editor again even if the system dismissed its last keyboard request. */
internal fun TerminalInputView.showTerminalKeyboard() {
    if (requestFocus()) requestKeyboard()
}

internal fun TerminalInputView.hideTerminalKeyboard() {
    endKeyboardRequest()
    context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(windowToken, 0)
}

/**
 * A request of the terminal for the keyboard, from when it is made until Android has answered it.
 *
 * Android drops a request that it gets while it still binds the keyboard to a new window, as on
 * the first start of the app with "Keep keyboard open" and a fast connection (#299): when the
 * binding is made it starts the keyboard's input once more, and the keyboard stays down. So a
 * request stays open until the keyboard is up, and while it is open it is made again each time
 * Android starts the input anew ([inputStarted]).
 */
internal class KeyboardRequest {
    /** Asked, and not answered or ended since. */
    var open = false
        private set

    /**
     * The terminal asks for the keyboard. With the keyboard up already ([keyboardUp]) nothing is
     * left to answer: a request left open then would bring back the keyboard the user closes next.
     */
    fun asked(keyboardUp: Boolean) {
        open = !keyboardUp
    }

    /** The keyboard is up: the request is answered. */
    fun keyboardShown() {
        open = false
    }

    /** The keyboard is sent away, or the terminal is no longer the editor that takes the keys: the request is over. */
    fun ended() {
        open = false
    }

    /**
     * Android starts the keyboard's input on the terminal anew. True when the request is to be
     * made again: it is still open, the keyboard is not up ([keyboardUp]), and the terminal takes
     * input ([takesInput]: no keyboard comes up over something that has taken the keys).
     */
    fun inputStarted(keyboardUp: Boolean, takesInput: Boolean): Boolean {
        if (keyboardUp) open = false
        return open && takesInput
    }
}

/** Keep Android from reopening the IME as the palette hands focus back to the terminal. */
internal class TerminalImeRestoreGuard(private val window: Window) : ViewTreeObserver.OnWindowFocusChangeListener {
    private var previousState: Int? = null
    private var regainedFocus = false

    fun suppressRestore() {
        if (previousState != null) return
        val mode = window.attributes.softInputMode
        previousState = mode and LayoutParams.SOFT_INPUT_MASK_STATE
        regainedFocus = window.decorView.hasWindowFocus()
        window.decorView.viewTreeObserver.addOnWindowFocusChangeListener(this)
        window.setSoftInputMode((mode and LayoutParams.SOFT_INPUT_MASK_STATE.inv()) or LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        if (hasFocus) regainedFocus = true
        else if (regainedFocus) release()
    }

    fun release() {
        val state = previousState ?: return
        previousState = null
        val observer = window.decorView.viewTreeObserver
        if (observer.isAlive) observer.removeOnWindowFocusChangeListener(this)
        val mode = window.attributes.softInputMode
        if ((mode and LayoutParams.SOFT_INPUT_MASK_STATE) == LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN) {
            window.setSoftInputMode((mode and LayoutParams.SOFT_INPUT_MASK_STATE.inv()) or state)
        }
    }
}
