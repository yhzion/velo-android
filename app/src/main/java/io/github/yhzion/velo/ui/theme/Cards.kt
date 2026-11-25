package io.github.yhzion.velo.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

// What stands out of a page: the raised card, a warning note and a settings category's icon tile
// (the redesign's Tokens and Controls boards). Each keeps the colours of the surface around it.

/**
 * A card that stands out of the sheet or the start screen: the raised fill, a border of the
 * divider and the card radius (Tokens board "raised"; the start screen's step, About's app card,
 * the new version). Its content keeps the colours of the surface around it; a paragraph in it is
 * [VeloColors.textOnRaised]. [padding] and [spacing] are the board's 18 and 14 unless given.
 */
@Composable
fun RaisedCard(modifier: Modifier = Modifier, padding: Dp? = null, spacing: Dp? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape = rounded(VeloTheme.radii.card)
    Column(
        modifier.fillMaxWidth().clip(shape).background(VeloTheme.colors.raised).border(VeloTheme.sizes.edge, VeloTheme.surface.divider, shape)
            .padding(padding ?: VeloTheme.medium(18.dp)),
        verticalArrangement = Arrangement.spacedBy(spacing ?: VeloTheme.medium(14.dp)),
        content = content,
    )
}

/**
 * A warning about what a choice does, on a tint of the warning colour with its triangle
 * (Controls board, sample-warning: Enter sends whatever is typed). It takes no tap.
 */
@Composable
fun WarningNote(text: String, modifier: Modifier = Modifier) {
    val c = VeloTheme.surface
    val size = VeloTheme.medium(14.dp).value.sp
    Row(
        modifier.fillMaxWidth().clip(rounded(VeloTheme.medium(10.dp))).background(c.warning.copy(alpha = 0.10f))
            .padding(horizontal = VeloTheme.space.s12, vertical = VeloTheme.medium(10.dp)),
        horizontalArrangement = Arrangement.spacedBy(VeloTheme.medium(10.dp)),
    ) {
        Icon(Icons.Outlined.WarningAmber, null, Modifier.size(VeloTheme.medium(18.dp)), tint = c.warning)
        Text(text, style = VeloTheme.type.body.copy(fontSize = size, lineHeight = 1.45.em), color = c.text)
    }
}

/** A settings category's icon: a white line [icon] on a tile of the category's [tint] ([CategoryTints]; Controls board, 32 with a radius of 9). */
@Composable
fun CategoryIcon(icon: Painter, tint: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(VeloTheme.medium(32.dp)).clip(rounded(VeloTheme.medium(9.dp))).background(tint), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(VeloTheme.medium(18.dp)), tint = Color.White)
    }
}
