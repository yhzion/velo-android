package io.github.yhzion.velo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.yhzion.velo.ui.theme.TabTile
import io.github.yhzion.velo.ui.theme.VeloSurface
import io.github.yhzion.velo.ui.theme.VeloTheme
import io.github.yhzion.velo.ui.theme.naturalLines

/**
 * The caption under the panes (WsBar*, WsClosed*, WsOpen* boards, pane-caption): the shown tab's
 * tile, which machine the keys go to and the tab's name, and what its program does now ([rowMark]).
 * Its text takes the focused pane's text size ([terminalFontPx]) when one is given, so a pinch shows
 * in it too (#335); the board's 13 otherwise. It lies on the backdrop between the panes and the key bar,
 * 40 high as the board draws it, or only as high as its line in the [compact] pane style.
 */
@Composable
internal fun PaneCaption(
    row: TabRow?, connected: Boolean, readState: ActivityReadState, host: String, modifier: Modifier = Modifier, terminalFontPx: Float? = null,
    compact: Boolean = false,
) = VeloSurface(VeloTheme.colors.backdrop) {
    val activity = row?.let { readState.activity(it, connected) }
    val density = LocalDensity.current
    val caption = VeloTheme.type.caption.naturalLines()
    val style = if (terminalFontPx == null) caption else caption.copy(fontSize = with(density) { terminalFontPx.toSp() })
    Row(
        modifier.testTag("pane-caption").fillMaxWidth().heightIn(min = if (compact) 0.dp else VeloTheme.medium(40.dp))
            .padding(horizontal = VeloTheme.medium(8.dp)),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(VeloTheme.medium(8.dp)),
    ) {
        if (row != null && activity != null) {
            val tile = tabTile(row, activity)
            TabTile(tile.brand, panes = tile.panes, size = VeloTheme.medium(18.dp), dot = tile.dot, ring = VeloTheme.surface.surface, tag = "caption-tab-tile",
                contentDescription = tabTileDescription(tile, activity.icon ?: row.icon),
                stateDescription = activity.badge?.let { "Unread, ${it.description}" })
        }
        Text(listOf(host, row?.name.orEmpty()).filter(String::isNotEmpty).joinToString(" · "), Modifier.weight(1f).testTag("pane-caption-title"),
            color = VeloTheme.colors.textOnRaised, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
        when (rowMark(activity?.indicator)) {
            RowMark.Working -> WorkingDots(VeloTheme.medium(4.dp), VeloTheme.medium(3.dp), Modifier.testTag("pane-caption-working"),
                description = activity?.indicator?.description ?: TabIndicator.Working.description)
            RowMark.Waiting -> Box(Modifier.testTag("pane-caption-attention").size(VeloTheme.medium(8.dp)).background(VeloTheme.surface.warning, CircleShape)
                .semantics { contentDescription = activity?.indicator?.description.orEmpty() })
            RowMark.Offline -> TabStatusMark(TabIndicator.Offline)
            null -> Unit
        }
    }
}
