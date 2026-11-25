package io.github.yhzion.velo.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.github.yhzion.velo.Settings
import io.github.yhzion.velo.ui.theme.VeloSegmented
import io.github.yhzion.velo.ui.theme.SettingRow
import io.github.yhzion.velo.ui.theme.VeloTheme
import androidx.compose.ui.unit.dp

/** How the tab overview lays its cards out (Appearance → Interface → Overview). */
enum class OverviewLayout(
    /** The value kept in the settings. */
    val saved: String,
    /** The label on the control. */
    val label: String,
) {
    Row("row", "Row"), Grid("grid", "Grid");

    companion object {
        /** The layout a saved value stands for; a missing or unknown one is [Row]. */
        fun parse(value: String?): OverviewLayout = entries.firstOrNull { it.saved == value } ?: Row
    }
}

/** The Overview row of the Interface card: the strip of cards (Row) or a vertical grid (Grid). */
@Composable
internal fun OverviewLayoutSetting(s: Settings) {
    SettingRow("Overview", tag = "appearance-overview") {
        VeloSegmented(OverviewLayout.entries, s.overviewLayout, { it.label }, Modifier.testTag("appearance-overview-options"),
            optionTag = { "appearance-overview-${it.saved}-option" }, cellPadding = VeloTheme.medium(14.dp), onPick = s::saveOverviewLayout)
    }
}
