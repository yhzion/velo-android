package io.github.yhzion.velo.ui

import io.github.yhzion.velo.core.SessionInfo
import io.github.yhzion.velo.core.TabInfo
import io.github.yhzion.velo.core.UiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStripTest {
    private val work = SessionInfo("session:w", "work", listOf(
        TabInfo("window:a", "editor"), TabInfo("window:b", "build", active = true), TabInfo("window:c", "logs"),
    ))
    private val play = SessionInfo("session:p", "play", listOf(
        TabInfo("window:x", "claude", activity = "blocked", kind = "permission", active = true),
    ))
    private val state = UiState(status = "connected", sessions = listOf(work, play), session = work.id, window = "window:c")

    @Test fun everySessionHasAChipInTheServersOrder() {
        val chips = sessionChips(state) { false }
        assertEquals(listOf("session:w", "session:p"), chips.map { it.id })
        assertEquals(listOf("work", "play"), chips.map { it.label })
        assertEquals(listOf(3, 1), chips.map { it.tabs })
    }

    @Test fun theSessionShownIsTheCurrentChip() {
        assertEquals(listOf(true, false), sessionChips(state) { false }.map { it.current })
    }

    @Test fun theCurrentSessionMarksTheTabItShowsAndAnotherTheTabItWouldShow() {
        val chips = sessionChips(state) { false }
        // The selected window of the panes, though the session list still marks another one.
        assertEquals(2, chips[0].shownTab)
        assertEquals(0, chips[1].shownTab)
    }

    @Test fun aSessionWithATabThatWantsTheUserHasTheDot() {
        val chips = sessionChips(state) { row -> row.activity == "blocked" }
        assertEquals(listOf(false, true), chips.map { it.attention })
    }

    @Test fun aSessionWhoseTabsAreNotKnownHasNoCount() {
        val chips = sessionChips(state.copy(sessions = listOf(SessionInfo("session:q", ""))), attention = { true })
        val chip = chips.single()
        assertEquals(null, chip.tabs)
        assertEquals("Session", chip.label)
        assertEquals(-1, chip.shownTab)
        assertFalse(chip.attention)
    }

    @Test fun aChipDrawsOneCellPerTabAndAtMostSix() {
        assertEquals(listOf(false, true, false), chipCells(tabs = 3, shown = 1))
        assertEquals(6, chipCells(tabs = 9, shown = 0).size)
        // A shown tab past the last cell lights the last one.
        assertEquals(listOf(false, false, false, false, false, true), chipCells(tabs = 9, shown = 7))
        assertEquals(emptyList<Boolean>(), chipCells(tabs = 0, shown = -1))
        assertEquals(listOf(false, false), chipCells(tabs = 2, shown = -1))
    }

    @Test fun aScreenReaderHearsTheSessionAndItsTabs() {
        assertEquals("Session work, 3 tabs", sessionChipDescription("work", 3))
        assertEquals("Session play, 1 tab", sessionChipDescription("play", 1))
        assertEquals("Session new, no tabs", sessionChipDescription("new", 0))
        assertEquals("Session lost", sessionChipDescription("lost", null))
    }

    // #236: the sessions of the other saved remotes.

    private fun chip(id: String, current: Boolean = false) = SessionChip(id, id, 1, 0, current, attention = false)
    private val here = StripRemote("r:here", "mac", active = true, connected = true, chips = listOf(chip("s:1", current = true), chip("s:2")))
    private val there = StripRemote("r:there", "studio", active = false, connected = true, chips = listOf(chip("s:9", current = true)))
    private val down = StripRemote("r:down", "nas", active = false, connected = false, chips = listOf(chip("s:5")))
    private val empty = StripRemote("r:empty", "pi", active = false, connected = true, chips = emptyList())

    @Test fun theStripShowsTheRemotesInTheOrderSaved() {
        assertEquals(listOf("r:there", "r:here"), stripRemotes(listOf(there, here)).map { it.id })
    }

    @Test fun anotherRemoteIsShownOnlyWhileConnectedAndWithASession() {
        assertEquals(listOf("r:here", "r:there"), stripRemotes(listOf(here, down, there, empty)).map { it.id })
    }

    @Test fun theActiveRemoteIsShownOfflineAndWithoutSessions() {
        val alone = here.copy(connected = false, chips = emptyList())
        assertEquals(listOf("r:here"), stripRemotes(listOf(alone, there)).map { it.id }.take(1))
    }

    @Test fun onlyTheActiveRemotesChipIsCurrent() {
        val shown = stripRemotes(listOf(here, there))
        assertEquals(listOf(true, false), shown[0].chips.map { it.current })
        assertEquals(listOf(false), shown[1].chips.map { it.current })
    }

    // #246: the chip tapped is the current one before the server has switched.

    @Test fun theChosenSessionIsCurrentBeforeTheServerSwitches() {
        val shown = stripRemotes(listOf(here, there), chosen = "s:2")
        assertEquals(listOf(false, true), shown[0].chips.map { it.current })
        assertEquals(listOf(false), shown[1].chips.map { it.current })
    }

    @Test fun aChosenSessionThatIsNotOnTheActiveRemoteChangesNothing() {
        // Gone meanwhile, or another remote's: the server's selection stays the current chip.
        val shown = stripRemotes(listOf(here, there), chosen = "s:9")
        assertEquals(listOf(true, false), shown[0].chips.map { it.current })
        assertEquals(listOf(false), shown[1].chips.map { it.current })
    }

    @Test fun oneRemoteDrawsItsChipsWithoutItsNameAndNewSessionLast() {
        val items = stripItems(stripRemotes(listOf(here)))
        assertEquals(listOf("chip s:1", "chip s:2", "new"), items.map(::describe))
    }

    @Test fun severalRemotesEachStartWithTheirNameAndNewSessionFollowsTheActiveOne() {
        val items = stripItems(stripRemotes(listOf(there, here)))
        assertEquals(listOf("name studio", "chip s:9", "name mac", "chip s:1", "chip s:2", "new"), items.map(::describe))
    }

    private fun describe(item: StripItem) = when (item) {
        is StripItem.Name -> "name ${item.remote.name}"
        is StripItem.Chip -> "chip ${item.chip.id}"
        StripItem.NewSession -> "new"
    }

    @Test fun theMiddleOfAnItemAddsTheItemsAndGapsBeforeIt() {
        val widths = listOf(72f, 136f, 136f, 64f)
        assertEquals(36f, stripMiddle(widths, gap = 12f, index = 0), 0f)
        assertEquals(72f + 12f + 68f, stripMiddle(widths, gap = 12f, index = 1), 0f)
        assertEquals(72f + 136f + 136f + 3 * 12f + 32f, stripMiddle(widths, gap = 12f, index = 3), 0f)
    }

    @Test fun aScreenReaderHearsTheRemoteOfAnotherRemotesSession() {
        assertEquals("Session work on studio, 3 tabs", sessionChipDescription("work", 3, remote = "studio"))
        assertEquals("Session lost on studio", sessionChipDescription("lost", null, remote = "studio"))
    }
}
