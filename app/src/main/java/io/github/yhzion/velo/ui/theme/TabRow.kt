package io.github.yhzion.velo.ui.theme

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

/**
 * A text style at the line height a browser gives its font ("normal": the font's own ascent and
 * descent), as the Sidebar, Rail and Pane boards set their rows' text. The type scale's line
 * heights are for paragraphs; a row's line takes no more room than its letters.
 */
fun TextStyle.naturalLines(): TextStyle = copy(lineHeight = TextUnit.Unspecified)

/** The fill behind a tab's row: the pill of the shown tab, else the surface. A row's tile rings its dot in it. */
@Composable @ReadOnlyComposable
fun tabRowFill(selected: Boolean): Color = if (selected) VeloTheme.surface.selected else VeloTheme.surface.surface

/**
 * A tab's row in the sidebar (Controls board, sample-tab-row; Sidebar board, tab-row): the tab's
 * [tile], its [title] over its [subtitle], and [trailing] marks, 12 apart. The shown tab ([selected])
 * has the pill behind it. A tap is [onClick], a long press [onLongClick].
 *
 * [id] names the parts for tests as the board does: `tab-row-<id>`, `tab-title-<id>`,
 * `tab-status-<id>`. [titleContent] draws in the title's place (a waiting program's words that take
 * turns with the name); a screen reader still reads [title], unless [titleSaid] is false (a title
 * that only says again what the tile says, #150). The row keeps the outside room the
 * list gives it (the board's 8 from the sidebar's edge) in [modifier].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TabListRow(
    title: String, onClick: () -> Unit, modifier: Modifier = Modifier, subtitle: String? = null, selected: Boolean = false,
    onLongClick: (() -> Unit)? = null, onLongClickLabel: String? = null, id: String? = null,
    titleContent: (@Composable () -> Unit)? = null, titleSaid: Boolean = true,
    tile: @Composable () -> Unit,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val c = VeloTheme.surface
    val type = VeloTheme.type
    fun Modifier.part(name: String) = if (id != null) testTag("$name-$id") else this
    Row(
        modifier.part("tab-row").fillMaxWidth().heightIn(min = VeloTheme.sizes.tabRow).clip(Capsule)
            .background(if (selected) c.selected else Color.Transparent)
            .combinedClickable(onLongClickLabel = onLongClickLabel, onLongClick = onLongClick, onClick = onClick)
            .semantics { if (selected) this.selected = true }
            .padding(start = VeloTheme.space.s12, end = VeloTheme.medium(6.dp)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(VeloTheme.space.s12),
    ) {
        tile()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(VeloTheme.medium(2.dp))) {
            if (titleContent != null) Box(Modifier.part("tab-title").fillMaxWidth().clearAndSetSemantics { if (titleSaid) text = AnnotatedString(title) }) { titleContent() }
            else Text(title, Modifier.part("tab-title").fillMaxWidth().then(if (titleSaid) Modifier else Modifier.semantics { hideFromAccessibility() }), style = type.body.naturalLines(), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, Modifier.part("tab-status").fillMaxWidth(), style = type.caption.naturalLines(), color = c.secondary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) trailing()
    }
}
