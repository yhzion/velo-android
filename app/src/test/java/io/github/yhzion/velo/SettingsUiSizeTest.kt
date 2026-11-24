package io.github.yhzion.velo

import io.github.yhzion.velo.ui.theme.UiSize
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsUiSizeTest {
    @Test fun aSettingNeverSavedIsMedium() {
        assertEquals(UiSize.Medium, Settings(FakePrefs()).uiSize)
    }

    @Test fun aChoiceHoldsNowAndAtTheNextLaunch() {
        val prefs = FakePrefs()
        val settings = Settings(prefs)
        for (choice in listOf(UiSize.Small, UiSize.Large, UiSize.Medium)) {
            settings.saveUiSize(choice)
            assertEquals(choice, settings.uiSize)
            assertEquals(choice, Settings(prefs).uiSize)
        }
    }

    @Test fun aSizeTheAppDoesNotKnowIsMedium() {
        val prefs = FakePrefs()
        prefs.edit().putString("uiSize", "enormous").apply()
        assertEquals(UiSize.Medium, Settings(prefs).uiSize)
    }
}
