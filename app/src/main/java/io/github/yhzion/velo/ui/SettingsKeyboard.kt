package io.github.yhzion.velo.ui

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import io.github.yhzion.velo.ui.theme.monoAt
import io.github.yhzion.velo.ui.theme.IconKeys
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.yhzion.velo.Settings
import io.github.yhzion.velo.ui.theme.ButtonKind
import io.github.yhzion.velo.ui.theme.Capsule
import io.github.yhzion.velo.ui.theme.GroupCard
import io.github.yhzion.velo.ui.theme.GroupDivider
import io.github.yhzion.velo.ui.theme.GroupHeader
import io.github.yhzion.velo.ui.theme.KeyChoice
import io.github.yhzion.velo.ui.theme.MenuButton
import io.github.yhzion.velo.ui.theme.SelectMenu
import io.github.yhzion.velo.ui.theme.SettingRow
import io.github.yhzion.velo.ui.theme.SwitchRow
import io.github.yhzion.velo.ui.theme.VeloButton
import io.github.yhzion.velo.ui.theme.VeloTheme
import io.github.yhzion.velo.ui.theme.WarningNote
import io.github.yhzion.velo.ui.theme.groupBleed
import io.github.yhzion.velo.ui.theme.hairline
import io.github.yhzion.velo.ui.theme.lineBox
import io.github.yhzion.velo.ui.theme.saidBy

/** A key's name in test tags, as the boards name them: `/` is slash, PgUp page-up, Backspace backspace. */
fun keyTagId(key: String): String = when (key) {
    "/" -> "slash"
    "~" -> "tilde"
    "|" -> "pipe"
    "-" -> "minus"
    "PgUp" -> "page-up"
    "PgDn" -> "page-down"
    else -> key.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
}

/** What a tap gesture sending Enter does, said under the tap gestures. */
internal const val EnterWarning = "Enter sends whatever is typed in the terminal right away."

/**
 * Keyboard (canvas SetKeyboard block): the key bar as it is, its order and its keys; typing;
 * the tap gestures. With two or more keys, Edit order turns the keys into a list whose arrows
 * move one key a step; Done ends it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun KeyboardSettings(s: Settings) {
    val space = VeloTheme.space
    val c = VeloTheme.surface
    val opensEditing = LocalSettingsOpen.current.editingOrder
    var editingOrder by rememberSaveable { mutableStateOf(opensEditing) }
    val ordering = editingOrder && s.keyBar.size >= 2
    Column(Modifier.fillMaxWidth().testTag("keyboard-content").padding(bottom = space.s24)) {
        GroupHeader("Key bar", Modifier.testTag("keyboard-key-bar-header"), first = true)
        GroupCard(Modifier.testTag("keyboard-key-bar-group")) {
            // The bar as the terminal shows it, on the panes' black; it takes no key here.
            Box(
                Modifier.groupBleed().testTag("keyboard-key-bar-preview").fillMaxWidth().background(VeloTheme.colors.backdrop.surface)
                    .drawBehind { drawLine(c.divider, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1f) }
                    .padding(space.s12).padding(bottom = hairline()),
            ) {
                // Rounded as the panes' cards are in the chosen pane style (#218).
                val radius = if (s.paneStyle == "compact") VeloTheme.radii.paneCompact else VeloTheme.radii.pane
                KeyBar(s.keyBar, emptySet(), Modifier, enabled = true, radius = radius) { _, _ -> }
            }
            if (s.keyBar.size >= 2) {
                SettingRow("Order", tag = "keyboard-order", labelFills = false) {
                    if (ordering) VeloButton("Done", { editingOrder = false }, kind = ButtonKind.Primary, row = true, tag = "keyboard-order-done-button")
                    else VeloButton("Edit order", { editingOrder = true }, row = true, tag = "keyboard-edit-order-button")
                }
                GroupDivider()
            }
            if (ordering) OrderList(s) else KeyChips(s)
        }
        GroupHeader("Typing", Modifier.testTag("keyboard-typing-header"))
        GroupCard(Modifier.testTag("keyboard-typing-group")) {
            SwitchRow("Keep keyboard open", s.keepKeyboard, s::saveKeepKeyboard, tag = "keyboard-keep-open")
            GroupDivider()
            SwitchRow("Haptic feedback", s.haptics, s::saveHaptics, tag = "keyboard-haptics")
        }
        TapGestureSettings(s)
    }
}

/** The keys that can be on the bar, by group; a chosen one is lit. A saved key that is no longer offered stays removable. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeyChips(s: Settings) {
    val space = VeloTheme.space
    val offered = Settings.ALL_KEYS + s.keyBar.filter { it !in Settings.ALL_KEYS }.distinct()
    Column(
        Modifier.groupBleed().testTag("keyboard-key-chips").fillMaxWidth().padding(top = space.s4, bottom = space.s16, start = space.s16, end = space.s16),
        verticalArrangement = Arrangement.spacedBy(VeloTheme.medium(6.dp)),
    ) {
        for (group in KeyGroup.entries) {
            val keys = offered.filter { keyGroup(it) == group }
            if (keys.isEmpty()) continue
            Text(group.caption, Modifier.fillMaxWidth().testTag("keyboard-chip-group-${group.name.lowercase()}").padding(top = VeloTheme.medium(10.dp))
                .lineBox(VeloTheme.type.caption).semantics { heading() }, style = VeloTheme.type.caption, color = VeloTheme.surface.secondary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(space.s8), verticalArrangement = Arrangement.spacedBy(space.s8)) {
                for (choice in keys) {
                    KeyChoice(choice, choice in s.keyBar, { s.saveKeyBar(toggleKey(s.keyBar, choice)) }, Modifier.testTag("keyboard-chip-${keyTagId(choice)}"))
                }
            }
        }
    }
}

/** The bar's keys in order, each with its place and arrows that move it a step earlier or later; each move is saved at once. */
@Composable
private fun OrderList(s: Settings) {
    val space = VeloTheme.space
    val c = VeloTheme.surface
    val line = hairline()
    Column(Modifier.groupBleed().testTag("keyboard-order-list").fillMaxWidth().padding(top = space.s4, bottom = space.s8, start = space.s8, end = space.s8)) {
        s.keyBar.forEachIndexed { index, name ->
            key(name) {
                val id = keyTagId(name)
                Row(
                    Modifier.fillMaxWidth().testTag("keyboard-order-row-$id").heightIn(min = VeloTheme.medium(52.dp) + if (index > 0) line else 0.dp)
                        .then(if (index > 0) Modifier.drawBehind { drawLine(c.divider, Offset(0f, line.toPx() / 2), Offset(size.width, line.toPx() / 2), line.toPx()) } else Modifier)
                        .padding(top = if (index > 0) line else 0.dp).padding(start = space.s8, end = space.s4),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(space.s12),
                ) {
                    Text("${index + 1}", Modifier.width(space.s20).testTag("keyboard-order-position-$id"), style = VeloTheme.type.caption, color = c.secondary)
                    // As tall as the boards' line of 15 in JetBrains Mono, an arrow's icon or a key's name.
                    Box(Modifier.weight(1f).height(VeloTheme.medium(20.dp)).testTag("keyboard-order-key-$id").semantics(mergeDescendants = true) { contentDescription = spokenKeyName(name) },
                        contentAlignment = Alignment.CenterStart) {
                        if (name in IconKeys) KeyLegend(name, c.text)
                        else Text(keyName(name), Modifier.saidBy(spokenKeyName(name)), style = monoAt(15f, FontWeight.Medium), color = c.text, maxLines = 1, softWrap = false)
                    }
                    OrderButton(Icons.Default.ArrowUpward, "Move ${spokenKeyName(name)} earlier", index > 0, "keyboard-order-earlier-$id") {
                        s.saveKeyBar(moveKey(s.keyBar, name, -1))
                    }
                    OrderButton(Icons.Default.ArrowDownward, "Move ${spokenKeyName(name)} later", index < s.keyBar.lastIndex, "keyboard-order-later-$id") {
                        s.saveKeyBar(moveKey(s.keyBar, name, 1))
                    }
                }
            }
        }
    }
}

/** A round glass button that moves a key; at the end it cannot move past, it stays, dimmed. */
@Composable
private fun OrderButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, tag: String, onClick: () -> Unit) {
    val c = VeloTheme.surface
    Box(
        Modifier.testTag(tag).size(VeloTheme.medium(44.dp)).clip(Capsule).background(c.glass).clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, Modifier.size(VeloTheme.medium(18.dp)), tint = if (enabled) c.text else c.disabled) }
}

/**
 * Tap gestures on a terminal: off until turned on. Each of the double and the triple tap sends a
 * key the user picks, kept on this phone apart from the key bar. Enter says what it does.
 */
@Composable
private fun TapGestureSettings(s: Settings) {
    GroupHeader("Tap gestures", Modifier.testTag("keyboard-tap-gestures-header"))
    GroupCard(Modifier.testTag("keyboard-tap-gestures-group")) {
        SwitchRow("Use tap gestures", s.tapGestures, s::saveTapGestures, tag = "keyboard-tap-gestures")
        if (s.tapGestures) {
            GroupDivider()
            TapKeyRow("Double tap", "keyboard-double-tap", s.doubleTapKey, s::saveDoubleTapKey)
            GroupDivider()
            TapKeyRow("Triple tap", "keyboard-triple-tap", s.tripleTapKey, s::saveTripleTapKey)
            if (s.doubleTapKey == TapKey.Enter || s.tripleTapKey == TapKey.Enter) {
                WarningNote(EnterWarning, Modifier.groupBleed().padding(start = VeloTheme.space.s12, end = VeloTheme.space.s12, bottom = VeloTheme.space.s12)
                    .testTag("keyboard-enter-warning"))
            }
        }
    }
}

/** The key one tap gesture sends: its name and the chosen key, which a menu changes. */
@Composable
private fun TapKeyRow(label: String, tag: String, key: TapKey, onPick: (TapKey) -> Unit) {
    var choosing by remember { mutableStateOf(false) }
    SettingRow(label, tag = tag, labelFills = false, saidByControl = true) {
        Box {
            MenuButton(key.label, { choosing = true }, contentDescription = "$label: ${key.spoken}", tag = "$tag-menu")
            SelectMenu(choosing, { choosing = false }, TapKey.entries, key, { it.label }, onPick = onPick, spoken = { it.spoken })
        }
    }
}
