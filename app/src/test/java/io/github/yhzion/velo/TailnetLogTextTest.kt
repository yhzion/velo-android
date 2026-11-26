package io.github.yhzion.velo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TailnetLogTextTest {
    private val url = "https://login.example/abc123"

    @Test fun aReleaseBuildLeavesTheLoginUrlOutOfTheLog() {
        val text = tailnetLogText(TailnetState.NeedsLogin(url), debuggable = false)
        assertFalse(text, text.contains(url))
        assertFalse(text, text.contains("login.example"))
        assertTrue(text, text.contains("NeedsLogin"))
    }

    @Test fun aDebuggableBuildKeepsTheLoginUrlSoAdbCanReadIt() {
        assertTrue(tailnetLogText(TailnetState.NeedsLogin(url), debuggable = true).contains(url))
    }

    @Test fun everyOtherStateIsNamedWhetherOrNotTheBuildIsDebuggable() {
        val others = mapOf(
            TailnetState.Off to "Off",
            TailnetState.Starting to "Starting",
            TailnetState.LoggedOut to "LoggedOut",
            TailnetState.Running("velo", listOf("192.0.2.9")) to "Running",
            TailnetState.Error("the node is stopped") to "Error",
        )
        for ((state, kind) in others) for (debuggable in listOf(false, true))
            assertTrue("$state: ${tailnetLogText(state, debuggable)}", tailnetLogText(state, debuggable).contains(kind))
    }
}
