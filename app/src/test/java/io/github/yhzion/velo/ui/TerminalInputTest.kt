package io.github.yhzion.velo.ui

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalInputTest {
    @Test fun deletesOnlyTheRequestedSidesInOrder() {
        fun keys(before: Int, after: Int): List<Int> = buildList {
            sendSurroundingDeletes(before, after) { add(it) }
        }
        val backspace = KeyEvent.KEYCODE_DEL
        val delete = KeyEvent.KEYCODE_FORWARD_DEL
        assertEquals(listOf(backspace), keys(1, 0))
        assertEquals(listOf(delete), keys(0, 1))
        assertEquals(listOf(backspace, backspace, delete), keys(2, 1))
        assertEquals(emptyList<Int>(), keys(0, 0))
    }
}
