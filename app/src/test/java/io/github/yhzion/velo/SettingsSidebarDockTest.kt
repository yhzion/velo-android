package io.github.yhzion.velo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The docked sidebar of the unfolded screen (WsOpenDocked) is a choice the app keeps. */
class SettingsSidebarDockTest {
    @Test fun theSidebarCoversThePanesUntilTheUserDocksIt() {
        val prefs = FakePrefs()
        assertFalse(Settings(prefs).sidebarDocked)
        // Reading the default writes nothing: a changed default reaches a user who never chose.
        assertFalse(prefs.contains("sidebarDocked"))
    }

    @Test fun aDockingHoldsNowAndAtTheNextLaunch() {
        val prefs = FakePrefs()
        val settings = Settings(prefs)
        settings.saveSidebarDocked(true)
        assertTrue(settings.sidebarDocked)
        assertTrue(prefs.getBoolean("sidebarDocked", false))
        assertTrue(Settings(prefs).sidebarDocked)
    }

    @Test fun undockingIsKeptToo() {
        val prefs = FakePrefs()
        Settings(prefs).saveSidebarDocked(true)
        val settings = Settings(prefs)
        settings.saveSidebarDocked(false)
        assertFalse(settings.sidebarDocked)
        assertFalse(Settings(prefs).sidebarDocked)
        assertTrue(prefs.contains("sidebarDocked"))
    }
}
