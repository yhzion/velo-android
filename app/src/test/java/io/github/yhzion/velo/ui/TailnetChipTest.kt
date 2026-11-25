package io.github.yhzion.velo.ui

import io.github.yhzion.velo.TailnetState
import io.github.yhzion.velo.ui.theme.Status
import org.junit.Assert.assertEquals
import org.junit.Test

class TailnetChipTest {
    @Test fun theChipSaysTheNodesStateInAWordOrTwo() {
        assertEquals(TailnetChip("Connected", Status.Ok), tailnetChip(TailnetState.Running("velo-phone", emptyList()), paused = false))
        assertEquals(TailnetChip("Off", Status.Off), tailnetChip(TailnetState.Off, paused = false))
        assertEquals(TailnetChip("Starting", Status.Active), tailnetChip(TailnetState.Starting, paused = false))
        assertEquals(TailnetChip("Needs login", Status.Warning), tailnetChip(TailnetState.NeedsLogin("https://login"), paused = false))
        assertEquals(TailnetChip("Logged out", Status.Off), tailnetChip(TailnetState.LoggedOut, paused = false))
        assertEquals(TailnetChip("Error", Status.Danger), tailnetChip(TailnetState.Error("no network"), paused = false))
    }

    @Test fun aNodeHeldBackInTheBackgroundIsPausedWhateverItsState() {
        assertEquals(TailnetChip("Paused", Status.Warning), tailnetChip(TailnetState.Running("velo-phone", emptyList()), paused = true))
    }

    @Test fun theSwitchSaysWhatOffDoesUnlessThereIsSomethingToExplain() {
        assertEquals(NodeOffExplanation, nodeCaption(TailnetState.Running("velo-phone", emptyList()), paused = false))
        assertEquals(NodeOffExplanation, nodeCaption(TailnetState.Off, paused = false))
        assertEquals("Error: no network", nodeCaption(TailnetState.Error("no network"), paused = false))
        assertEquals("Paused while Velo is in the background", nodeCaption(TailnetState.Running("velo-phone", emptyList()), paused = true))
    }
}
