package io.github.yhzion.velo

import io.github.yhzion.velo.ui.CapsuleCorner
import io.github.yhzion.velo.ui.DefaultCapsuleCorner
import io.github.yhzion.velo.ui.shownCorner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsCapsuleCornerTest {
    @Test fun aSettingNeverChosenIsNoChoiceAndShowsTheDefault() {
        val prefs = FakePrefs()
        val settings = Settings(prefs)
        assertNull(settings.capsuleCorner)
        assertEquals(DefaultCapsuleCorner, shownCorner(settings.capsuleCorner))
    }

    @Test fun creatingSettingsNeverWritesTheDefault() {
        val prefs = FakePrefs()
        Settings(prefs)
        assertFalse(prefs.contains("capsuleCorner"))
    }

    @Test fun aChangedDefaultReachesAUserWhoNeverChose() {
        assertEquals(CapsuleCorner.TopLeft, shownCorner(null, CapsuleCorner.TopLeft))
    }

    @Test fun aChoiceOverridesTheDefault() {
        assertEquals(CapsuleCorner.TopRight, shownCorner(CapsuleCorner.TopRight, CapsuleCorner.TopLeft))
    }

    @Test fun aChoiceHoldsNowAndAtTheNextLaunch() {
        val prefs = FakePrefs()
        val settings = Settings(prefs)
        settings.saveCapsuleCorner(CapsuleCorner.TopRight)
        assertEquals("top-right", prefs.getString("capsuleCorner", null))
        assertEquals(CapsuleCorner.TopRight, settings.capsuleCorner)
        assertEquals(CapsuleCorner.TopRight, Settings(prefs).capsuleCorner)
    }

    @Test fun aCornerTheAppDoesNotKnowIsNoChoice() {
        val prefs = FakePrefs()
        prefs.edit().putString("capsuleCorner", "middle").apply()
        val settings = Settings(prefs)
        assertNull(settings.capsuleCorner)
        assertEquals(DefaultCapsuleCorner, shownCorner(settings.capsuleCorner))
    }

    @Test fun parseReadsEachSavedValue() {
        assertEquals(CapsuleCorner.TopLeft, CapsuleCorner.parse("top-left"))
        assertEquals(CapsuleCorner.TopRight, CapsuleCorner.parse("top-right"))
        assertEquals(CapsuleCorner.BottomLeft, CapsuleCorner.parse("bottom-left"))
        assertEquals(CapsuleCorner.BottomRight, CapsuleCorner.parse("bottom-right"))
        assertNull(CapsuleCorner.parse(null))
        assertNull(CapsuleCorner.parse("middle"))
    }
}
