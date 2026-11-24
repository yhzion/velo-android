package io.github.yhzion.velo.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TailnetQuestionTest {

    @Test
    fun logOutWarnsThatEveryRemoteDisconnectsAndTheBrowserComesBack() {
        val q = tailnetQuestion(TailnetAction.LogOut, "")
        assertEquals("Log out of Tailscale?", q.title)
        assertEquals("Every remote disconnects. Logging in again opens the browser.", q.text)
        assertEquals("Log out", q.confirm)
        assertTrue(q.destructive)
    }

    @Test
    fun renameNamesTheNewNodeAndWarnsThatTheNodeRestarts() {
        val q = tailnetQuestion(TailnetAction.Rename, "velo")
        assertEquals("Rename this node to velo?", q.title)
        assertEquals("The node restarts and every remote reconnects.", q.text)
        assertEquals("Rename", q.confirm)
        assertFalse(q.destructive)
    }

    @Test
    fun aBlankOrUnchangedNameAsksNothing() {
        assertTrue(renameAsksFirst("velo", "rex"))
        assertFalse(renameAsksFirst("", "rex"))
        assertFalse(renameAsksFirst("  ", "rex"))
        assertFalse(renameAsksFirst("rex", "rex"))
    }
}
