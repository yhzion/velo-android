package io.github.yhzion.velo.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalReaderSnapshotTest {
    @Test fun keepsTheOpenedTextUntilRefresh() {
        var viewport = "first second"
        val snapshot = TerminalReaderSnapshot { viewport }

        viewport = "first second third"
        assertEquals("first second", snapshot.text)

        assertTrue(snapshot.refresh())
        assertEquals("first second third", snapshot.text)
    }

    @Test fun refreshingUnchangedTextDoesNotRequestAReplacement() {
        var reads = 0
        val snapshot = TerminalReaderSnapshot {
            reads++
            "first second"
        }

        assertFalse(snapshot.refresh())
        assertEquals(2, reads)
        assertEquals("first second", snapshot.text)
    }
}
