package io.github.yhzion.velo

import io.github.yhzion.velo.ui.OverviewLayout
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsOverviewLayoutTest {
    @Test fun theRowIsTheDefault() = assertEquals(OverviewLayout.Row, Settings(FakePrefs()).overviewLayout)

    @Test fun theChoiceIsKeptAcrossRestarts() {
        val prefs = FakePrefs()
        Settings(prefs).saveOverviewLayout(OverviewLayout.Grid)
        assertEquals("grid", prefs.getString("overviewLayout", null))
        assertEquals(OverviewLayout.Grid, Settings(prefs).overviewLayout)
    }

    @Test fun anUnknownSavedValueFallsBackToTheRow() {
        val prefs = FakePrefs()
        prefs.edit().putString("overviewLayout", "stack").apply()
        assertEquals(OverviewLayout.Row, Settings(prefs).overviewLayout)
    }
}
