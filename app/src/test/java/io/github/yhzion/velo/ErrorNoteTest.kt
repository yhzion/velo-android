package io.github.yhzion.velo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The failure notice of a connection (#277). */
class ErrorNoteTest {
    private val refused = "connect_failed: could not reach the server (ConnectionRefused)"

    @Test
    fun theUsersReconnectRenewsANoticeEqualToTheOneShown() {
        // The core's own retry already shows this text when the user's Reconnect fails with it:
        // the notice must start again, or the Reconnect's failure is gone at the old deadline.
        val note = ErrorNote()
        note.text = refused
        val shown = note.reports
        note.renew(refused)
        assertEquals(refused, note.text)
        assertNotEquals(shown, note.reports)
    }

    @Test
    fun theCoresOwnRetriesDoNotRenewIt() {
        // Retries every 15 s keep their notice as before: 4 s each, not one that never ends.
        val note = ErrorNote()
        note.text = refused
        val shown = note.reports
        note.text = refused
        assertEquals(shown, note.reports)
    }

    @Test
    fun onlyTheFailureThatAnswersTheUsersReconnectRenewsIt() {
        // #292: the reconnect a change of the key list makes for a key_refused remote is the app's
        // own. Its failure is shown as the core's retries are, not as the answer to a tap.
        val note = ErrorNote()
        note.report(refused)
        val shown = note.reports
        note.report(refused)
        assertEquals(refused, note.text)
        assertEquals(shown, note.reports)

        note.userDialed()
        note.report(refused)
        val renewed = note.reports
        assertNotEquals(shown, renewed)
        // The one failure after the tap, not those after it.
        note.report(refused)
        assertEquals(renewed, note.reports)

        // A Reconnect that went through: the notice goes, and a later failure is no answer to it.
        note.userDialed()
        note.connected()
        assertNull(note.text)
        note.report(refused)
        assertEquals(refused, note.text)
        assertEquals(renewed, note.reports)
    }

    @Test
    fun aNoticeExpiresOnlyIfNothingCameSince() {
        val note = ErrorNote()
        note.text = refused
        val seen = note.reports
        note.expire(refused, seen)
        assertNull(note.text)

        // The old timer ends just as a Reconnect's failure comes in: that report stays.
        note.text = refused
        val old = note.reports
        note.renew(refused)
        note.expire(refused, old)
        assertEquals(refused, note.text)

        // Another text came in: the old timer does not clear it either.
        note.text = "bad_url: expected a server address"
        note.expire(refused, note.reports)
        assertEquals("bad_url: expected a server address", note.text)
    }
}
