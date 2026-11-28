package io.github.yhzion.velo.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.yhzion.velo.ui.theme.TabTile
import io.github.yhzion.velo.ui.theme.VeloMenu
import io.github.yhzion.velo.ui.theme.VeloTheme
import io.github.yhzion.velo.ui.theme.naturalLines
import io.github.yhzion.velo.ui.theme.rounded
import io.github.yhzion.velo.ui.theme.tabRowFill
import io.github.yhzion.velo.ui.theme.MenuItem as VeloMenuItem
import kotlinx.coroutines.delay

/** A length the Sidebar board gives, at the current size ([VeloTheme.medium]). */
private val Int.bd: Dp @Composable @ReadOnlyComposable get() = VeloTheme.medium(this.dp)

/** How often a row's age is worked out again: often enough for "now" to become "1m" on time. */
private const val AgeTickMs = 15_000L

/**
 * A tab's row in Activity (#248), as Rex for Mac 2185 draws it (described, not copied). Its first line is its tile,
 * its name and, while its program works, the working dots at the end; its second its session's name and how long ago
 * it came into its group ([shortAge]). Under them, by group: a working tab's [capsule] of its program's words, a
 * waiting or failed tab's well ([ReasonWell]), and nothing for a finished one. A tap shows the tab, a long press
 * offers Rename and Close; the shown tab has the pill behind it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ActivityRow(
    entry: ActivityEntry, motion: Float, modifier: Modifier = Modifier,
    onSelect: () -> Unit, onRename: () -> Unit, onClose: () -> Unit,
    capsule: @Composable (String) -> Unit = {},
) {
    val c = VeloTheme.surface
    val type = VeloTheme.type
    val row = entry.row
    val activity = entry.drawn
    val id = entry.key
    var menu by remember { mutableStateOf(false) }
    val age by produceState(entry.enteredAt?.let { shortAge(it, System.currentTimeMillis()) }, entry.enteredAt) {
        val at = entry.enteredAt ?: run { value = null; return@produceState }
        while (true) {
            value = shortAge(at, System.currentTimeMillis())
            delay(AgeTickMs)
        }
    }
    val tile = tabTile(row, activity)
    // The group's title says the state, so a screen reader hears only what the tile adds: unread, and of what.
    val said = tabRowTileSpeech(tile, activity.icon ?: row.icon, row.name, activity.badge, null)
    val gap = 12.bd
    Box(modifier) {
        Column(Modifier.testTag("activity-row-$id").padding(horizontal = 8.bd).fillMaxWidth().clip(rounded(14.bd))
            .background(if (row.shown) c.selected else Color.Transparent)
            .combinedClickable(onLongClick = { menu = true }, onClick = onSelect)
            .semantics { if (row.shown) selected = true }
            .padding(start = 12.bd, end = 10.bd, top = 7.bd, bottom = 7.bd)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(gap)) {
                BouncingMark(bouncesIcon(activity.badge), motion) {
                    TabTile(tile.brand, panes = tile.panes, behind = tile.behind, dot = tile.dot, ring = tabRowFill(row.shown),
                        tag = "tab-tile-$id", contentDescription = said.description, stateDescription = said.state)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.bd)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(row.name, Modifier.weight(1f).testTag("activity-title-$id"), style = type.body.naturalLines(), color = c.text,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (rowMark(activity.indicator) == RowMark.Working) {
                            WorkingDots(5.bd, 4.bd, Modifier.padding(start = 8.bd).testTag("tab-working-$id"), description = null)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(entry.sessionLabel, Modifier.weight(1f).testTag("activity-session-$id"), style = type.caption.naturalLines(),
                            color = c.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        age?.let {
                            Text(it, Modifier.padding(start = 8.bd).testTag("activity-age-$id"), style = type.caption.naturalLines(),
                                color = c.secondary, maxLines = 1)
                        }
                    }
                }
            }
            val message = entry.message
            if (message != null) Box(Modifier.padding(start = VeloTheme.sizes.tabTile + gap, top = 6.bd)) {
                when (entry.priority) {
                    ActivityPriority.Working -> capsule(message)
                    ActivityPriority.Blocked, ActivityPriority.Failed ->
                        ReasonWell(wellReason(activity.indicator), message, Modifier.testTag("activity-well-$id"))
                    ActivityPriority.Done -> Unit
                }
            }
        }
        VeloMenu(menu, onDismissRequest = { menu = false }) {
            // Its place in its session is not what this list shows, so the moves are left to the session's list.
            for (item in tabMenu(true, forward = false, backward = false)) {
                val close = item.action == TabAction.Close
                if (item.action != TabAction.Rename && !close) continue
                VeloMenuItem(item.label, {
                    menu = false
                    if (close) onClose() else onRename()
                }, icon = if (close) Icons.Default.Close else Icons.Default.Edit, enabled = item.enabled, destructive = close)
            }
        }
    }
}

/**
 * The well under a waiting or failed tab's row (#248): what it waits for ([reason], in the accent; a failure has
 * none) over its program's [message] in at most two lines, which end with an ellipsis and do not run.
 */
@Composable
internal fun ReasonWell(reason: String?, message: String, modifier: Modifier = Modifier) {
    val c = VeloTheme.surface
    val type = VeloTheme.type
    Column(modifier.fillMaxWidth().clip(rounded(10.bd)).background(c.text.copy(alpha = .07f))
        .padding(horizontal = 12.bd, vertical = 8.bd), verticalArrangement = Arrangement.spacedBy(2.bd)) {
        if (reason != null) Text(reason, style = type.value.naturalLines(), color = c.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(message, style = type.value.naturalLines(), color = c.secondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
