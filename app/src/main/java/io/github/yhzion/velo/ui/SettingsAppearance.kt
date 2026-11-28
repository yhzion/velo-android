package io.github.yhzion.velo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.yhzion.velo.ui.theme.GroupCard
import io.github.yhzion.velo.ui.theme.GroupDivider
import io.github.yhzion.velo.ui.theme.GroupHeader
import io.github.yhzion.velo.ui.theme.NavigationRow
import io.github.yhzion.velo.ui.theme.SettingRow
import io.github.yhzion.velo.ui.theme.SwitchRow
import io.github.yhzion.velo.ui.theme.UiSize
import io.github.yhzion.velo.ui.theme.VeloSegmented
import io.github.yhzion.velo.ui.theme.VeloSlider
import io.github.yhzion.velo.ui.theme.VeloTheme
import io.github.yhzion.velo.ui.theme.groupBleed
import io.github.yhzion.velo.ui.theme.groupInset
import io.github.yhzion.velo.ui.theme.saidBy
import kotlin.math.roundToInt

private val PaneStyles = listOf("comfortable", "compact")

/**
 * Appearance (canvas SetAppearance block): the interface's size, pane style, overview, where
 * minimized windows sit and keeping the screen on; the active pane's highlight; the terminal's
 * font, which opens Terminal Font ([openFont]), and its size with a sample.
 */
@Composable
internal fun AppearanceSettings(host: SettingsHost, openFont: () -> Unit) {
    val s = host.settings
    Column(Modifier.fillMaxWidth().testTag("appearance-content").padding(bottom = VeloTheme.space.s24)) {
        GroupHeader("Interface", Modifier.testTag("appearance-interface-header"), first = true)
        GroupCard(Modifier.testTag("appearance-interface-group")) {
            SettingRow("Size", tag = "appearance-size") {
                VeloSegmented(UiSize.entries, s.uiSize, { it.name }, Modifier.testTag("appearance-size-options"),
                    optionTag = { "appearance-size-${it.name.lowercase()}" }, onPick = s::saveUiSize)
            }
            GroupDivider()
            SettingRow("Pane style", tag = "appearance-pane-style") {
                VeloSegmented(PaneStyles, s.paneStyle, { it.replaceFirstChar(Char::uppercaseChar) }, Modifier.testTag("appearance-pane-style-options"),
                    optionTag = { "appearance-pane-style-$it" }) {
                    s.savePaneStyle(it)
                    host.pushDisplay()
                }
            }
            GroupDivider()
            OverviewLayoutSetting(s)
            GroupDivider()
            CapsuleCornerRow(shownCorner(s.capsuleCorner), s::saveCapsuleCorner)
            GroupDivider()
            SwitchRow("Keep screen on while using Velo", s.keepScreenOn, s::saveKeepScreenOn, tag = "appearance-keep-screen-on")
        }
        PaneHighlightSettings(s)
        GroupHeader("Terminal", Modifier.testTag("appearance-terminal-header"))
        GroupCard(Modifier.testTag("appearance-terminal-group")) {
            NavigationRow("Font", openFont, value = s.font.label, tag = "appearance-font")
            GroupDivider()
            FontSizeSetting(host)
        }
    }
}

/**
 * The terminal's font size: its value beside its name, a slider under them that has the row's width, and the sample.
 * The slider says the name and the value, so a screen reader stops on it alone and hears them once (#200).
 */
@Composable
private fun FontSizeSetting(host: SettingsHost) {
    val s = host.settings
    val space = VeloTheme.space
    val value = fontSizeValue(s.fontSize)
    Row(
        Modifier.groupBleed().testTag("appearance-font-size-row").fillMaxWidth().groupInset().padding(end = space.s4, top = VeloTheme.medium(14.dp)),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("Font size", Modifier.testTag("appearance-font-size-label").saidBy(FontSizeName), style = VeloTheme.type.body, color = VeloTheme.surface.text)
        Text(value, Modifier.testTag("appearance-font-size-value").saidBy(value), style = VeloTheme.type.value, color = VeloTheme.surface.secondary)
    }
    // The slider and the sample keep 16 from both sides of the group, as the label does.
    Column(Modifier.fillMaxWidth().padding(end = space.s4, bottom = space.s16)) {
        VeloSlider(
            s.fontSize, { s.saveFontSize(it.roundToInt().toFloat()) },
            Modifier.fillMaxWidth().testTag("appearance-font-size-slider").semantics {
                contentDescription = FontSizeName
                stateDescription = value
            },
            valueRange = 8f..24f, steps = 15,
            onValueChangeFinished = { host.pushSetting("fontSize", s.fontSize) },
        )
        FontSizePreview(host)
    }
}

/** What the font size slider is called, by a screen reader as on the row. */
private const val FontSizeName = "Font size"

/** The font size as a whole number: the text beside the slider and the slider's own state say it alike. */
fun fontSizeValue(size: Float): String = size.roundToInt().toString()
