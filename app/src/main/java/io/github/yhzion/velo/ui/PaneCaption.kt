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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.yhzion.velo.ui.theme.TabTile
import io.github.yhzion.velo.ui.theme.VeloSurface
import io.github.yhzion.velo.ui.theme.VeloTheme
import io.github.yhzion.velo.ui.theme.naturalLines
import io.github.yhzion.velo.ui.theme.tileBox

/** The caption's tile, the board's 18. */
internal const val CaptionTile = 18f

/**
 * The caption under the panes (WsBar*, WsClosed*, WsOpen* boards, pane-caption): the shown tab's caption
 * ([TabCaption]), titled with which machine the keys go to and the tab's name. Its text is the board's caption type
 * at the chosen UI size, whatever the focused pane's text size (#46). It lies on the backdrop between the panes and
 * the key bar, 40 high as the board draws it. In the [compact] pane style it is as high as its line, or as the
 * tile's box when it draws a tile.
 */
@Composable
internal fun PaneCaption(
    row: TabRow?, connected: Boolean, readState: ActivityReadState, host: String, modifier: Modifier = Modifier,
    compact: Boolean = false,
) = VeloSurface(VeloTheme.colors.backdrop) {
    val activity = row?.let { readState.activity(it, connected) }
    val tile = if (row != null && activity != null) tabTile(row, activity) else null
    // Laid out by its face, the tile's layers step above the face: the compact row keeps the tile's box high,
    // as before, so they stay in the compact gap above it and off the pane, but for a fraction of a pixel.
    val least = when {
        !compact -> VeloTheme.medium(40.dp)
        tile != null -> VeloTheme.medium(tileBox(CaptionTile).dp)
        else -> 0.dp
    }
    TabCaption(
        tile, activity, listOf(host, row?.name.orEmpty()).filter(String::isNotEmpty).joinToString(" · "),
        VeloTheme.colors.textOnRaised, VeloTheme.type.caption.naturalLines(), tag = "pane-caption",
        modifier.testTag("pane-caption").fillMaxWidth().heightIn(min = least)
            .padding(horizontal = VeloTheme.medium(8.dp)),
        // The board's name for the caption's tile, which the fidelity tool pairs with the boards' elements.
        tileTag = "caption-tab-tile",
    )
}

/**
 * A tab's caption (pane-caption board): its [tile] laid out by its face, then [title], then what its program does
 * now ([rowMark]), as [activity] ([ActivityReadState.activity]) shows them. The caption under the panes and the
 * names under the overview's cards draw it. The tile's dot and pane count are ringed in the surface it lies on.
 *
 * The tile, the gap after it and the marks are the board's beside its caption type, grown with [style]'s size, so
 * a larger name has larger ones. With [fill] the title takes what the row leaves and the marks sit at the row's
 * end; without it they follow the title. [tag] names its parts for tests: `-title`, `-working` and `-attention`
 * after it; the tile is tagged [tileTag].
 */
@Composable
internal fun TabCaption(
    tile: TabTileLook?, activity: TabActivity?, title: String, color: Color, style: TextStyle, tag: String,
    modifier: Modifier = Modifier, fill: Boolean = true, tileTag: String = "$tag-tile",
) {
    // One board unit beside this text: the caption type's, or more for a larger name.
    val scale = style.fontSize.value / VeloTheme.type.caption.fontSize.value
    val unit = VeloTheme.medium(1.dp) * scale
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(unit * 8),
    ) {
        if (tile != null) {
            TabTile(tile.brand, panes = tile.panes, size = unit * CaptionTile, dot = tile.dot,
                ring = VeloTheme.surface.surface, tag = tileTag, byFace = true,
                contentDescription = tabTileDescription(tile, activity?.icon),
                stateDescription = activity?.badge?.let { "Unread, ${it.description}" })
        }
        Text(title, Modifier.weight(1f, fill).testTag("$tag-title"), color = color, style = style, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
        when (rowMark(activity?.indicator)) {
            RowMark.Working -> WorkingDots(unit * 4, unit * 3, Modifier.testTag("$tag-working"),
                description = activity?.indicator?.description ?: TabIndicator.Working.description)
            RowMark.Waiting -> Box(
                Modifier.testTag("$tag-attention").size(unit * 8).background(VeloTheme.surface.warning, CircleShape)
                    .semantics { contentDescription = activity?.indicator?.description.orEmpty() },
            )
            RowMark.Offline -> TabStatusMark(TabIndicator.Offline, VeloTheme.sizes.statusIcon * scale)
            null -> Unit
        }
    }
}
