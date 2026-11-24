package io.github.yhzion.velo.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The events are the ones [TerminalInputView] reports to its [KeyboardRequest], in the order Android made them. */
class TerminalKeyboardTest {
    /**
     * The first start of the app with "Keep keyboard open" (#299): Android drops a request that
     * it gets while it still binds the keyboard to the new window, and starts the input once more
     * when the binding is made.
     */
    @Test fun aRequestAndroidDroppedIsMadeAgainWhenTheInputStartsAnew() {
        val request = KeyboardRequest()
        request.asked(keyboardUp = false)
        assertTrue(request.inputStarted(keyboardUp = false, takesInput = true))
        // Android may start the input more than once before the keyboard is up.
        request.asked(keyboardUp = false)
        assertTrue(request.inputStarted(keyboardUp = false, takesInput = true))
    }

    @Test fun aStartOfInputAloneNeverOpensTheKeyboard() {
        assertFalse(KeyboardRequest().inputStarted(keyboardUp = false, takesInput = true))
    }

    /** The keyboard came up: the request is answered. The user closes it, and a later start of input leaves it closed. */
    @Test fun theKeyboardTheUserClosedStaysClosed() {
        val request = KeyboardRequest()
        request.asked(keyboardUp = false)
        request.keyboardShown()
        assertFalse(request.inputStarted(keyboardUp = false, takesInput = true))
    }

    /** A tap on the terminal with the keyboard up asks again: nothing is left to answer, so nothing stays open. */
    @Test fun aRequestWithTheKeyboardUpLeavesNothingOpen() {
        val request = KeyboardRequest()
        request.asked(keyboardUp = true)
        // The user closes the keyboard; Android starts the input anew later.
        assertFalse(request.inputStarted(keyboardUp = false, takesInput = true))
    }

    /** The start of input can come before the window's insets say that the keyboard is up. */
    @Test fun aStartOfInputWithTheKeyboardUpAnswersTheRequest() {
        val request = KeyboardRequest()
        request.asked(keyboardUp = false)
        assertFalse(request.inputStarted(keyboardUp = true, takesInput = true))
        assertFalse(request.inputStarted(keyboardUp = false, takesInput = true))
    }

    /** The keyboard is sent away, the focus is lost, the app pauses or an overlay takes the keys: the request is over. */
    @Test fun anEndedRequestIsNotMadeAgain() {
        val request = KeyboardRequest()
        request.asked(keyboardUp = false)
        request.ended()
        assertFalse(request.inputStarted(keyboardUp = false, takesInput = true))
    }

    /** No keyboard comes up over something that has taken the keys from the terminal. */
    @Test fun aRequestIsNotMadeAgainWhileTheTerminalTakesNoInput() {
        val request = KeyboardRequest()
        request.asked(keyboardUp = false)
        assertFalse(request.inputStarted(keyboardUp = false, takesInput = false))
    }
}
