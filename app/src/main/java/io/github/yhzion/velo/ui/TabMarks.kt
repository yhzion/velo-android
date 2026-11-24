package io.github.yhzion.velo.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.em
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import io.github.yhzion.velo.R
import io.github.yhzion.velo.ui.theme.VeloTheme
import io.github.yhzion.velo.ui.theme.RowTile
import kotlin.math.round

/** Draws a program mark without relying on upstream logos. */
@Composable
internal fun TabProgramMark(hint: String?, tile: Boolean, color: Color = VeloTheme.surface.text, badge: TabIndicator? = null, status: TabIndicator? = null) {
    val sizes = VeloTheme.sizes
    val agent = TabAgent.from(hint)
    val description = agent?.label ?: hint?.takeIf { it.isNotBlank() } ?: "Terminal"
    val modifier = Modifier.clearAndSetSemantics {
        contentDescription = description
        if (badge != null) stateDescription = "Unread, ${badge.description}"
        else if (status != null) stateDescription = status.description
    }
    Box(if (tile) modifier.width(VeloTheme.sizes.tile) else modifier, contentAlignment = Alignment.Center) {
        Box(contentAlignment = Alignment.Center) {
            if (tile && agent == null) {
                RowTile(iconGlyph(hint))
            } else if (agent == TabAgent.Claude || agent == TabAgent.Codex || agent == TabAgent.Omp) {
                val height = if (tile) sizes.markTile * 1.1f else sizes.markIcon
                // Each program uses its own tile palette.
                val fill = when (agent) {
                    TabAgent.Claude -> Brush.verticalGradient(listOf(Color(0xFFD59773), Color(0xFFC27649), Color(0xFFBB7146)))
                    TabAgent.Codex -> Brush.verticalGradient(listOf(Color.White, Color(0xFFE3E3E3)))
                    else -> Brush.linearGradient(listOf(Color(0xFFEC5FCD), Color(0xFF696AFF)))
                }
                val glyph = when (agent) {
                    TabAgent.Claude -> R.drawable.ic_agent_claude
                    TabAgent.Codex -> R.drawable.ic_agent_codex
                    else -> R.drawable.ic_agent_omp
                }
                Box(Modifier.size(width = height * 1.25f, height = height).background(fill, RoundedCornerShape(25)),
                    contentAlignment = Alignment.Center) {
                    Icon(painterResource(glyph), null, Modifier.size(height * .62f),
                        tint = if (agent == TabAgent.Codex) Color.Black else Color.White)
                }
            } else if (agent == TabAgent.Pi) {
                Icon(painterResource(R.drawable.ic_agent_pi), null, Modifier.size(if (tile) sizes.markTile else sizes.markIcon),
                    tint = Color.Unspecified)
            } else {
                Text(iconGlyph(hint), color = color,
                    style = if (tile) VeloTheme.type.markGlyphTile else VeloTheme.type.markGlyph, maxLines = 1)
            }
            if (badge != null) {
                val colors = VeloTheme.surface
                val badgeSize = if (tile) sizes.mark else sizes.mark * .75f
                val tint = if (badge == TabIndicator.Error) colors.danger else colors.accent
                Box(Modifier.align(Alignment.TopEnd)
                    .offset(x = badgeSize / 4, y = -badgeSize / 8).size(badgeSize)
                    .background(tint, CircleShape).border(badgeSize / 8, colors.surface, CircleShape))
            }
        }
    }
}

@Composable
internal fun TabStatusMark(mark: TabIndicator, markHeight: Dp = VeloTheme.sizes.statusIcon) {
    val colors = VeloTheme.surface
    if (mark.animates) {
        // Read time only during drawing. The three dots share one stable accessible label.
        val phase by rememberInfiniteTransition(label = "Working dots").animateFloat(
            initialValue = 0f, targetValue = 3f,
            animationSpec = infiniteRepeatable(tween(3 * DotIntervalMs, easing = LinearEasing)), label = "Dot phase",
        )
        Canvas(Modifier.size(markHeight * 1.5f, markHeight).clearAndSetSemantics { contentDescription = mark.description }) {
            val spacing = round(size.width / 3f)
            val radius = size.height / 8f
            repeat(3) { index ->
                drawCircle(
                    color = colors.accent.copy(alpha = workingDotAlpha(phase, index)),
                    radius = radius,
                    center = Offset(center.x + (index - 1) * spacing, center.y),
                )
            }
        }
        return
    }
    if (mark == TabIndicator.Offline) {
        Icon(Icons.Default.CloudOff, mark.description, Modifier.size(markHeight), tint = colors.secondary)
    }
}

@Composable
internal fun TabCaption(row: TabRow?, connected: Boolean, readState: ActivityReadState, color: Color, host: String = "", modifier: Modifier = Modifier, terminalFontPx: Float? = null) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(VeloTheme.sizes.gapCompact), verticalAlignment = Alignment.CenterVertically) {
        val activity = row?.let { readState.activity(it, connected) }
        if (activity != null) TabProgramMark(activity.icon, tile = false, color = color, badge = activity.badge, status = activity.indicator)
        val caption = VeloTheme.type.caption
        val density = LocalDensity.current
        val style = if (terminalFontPx == null) caption else caption.copy(
            fontSize = with(density) { terminalFontPx.toSp() }, lineHeight = 1.2.em,
        )
        Text(listOf(host, row?.name.orEmpty()).filter(String::isNotEmpty).joinToString(" · "),
            modifier = Modifier.weight(1f, fill = false), color = color, style = style,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        activity?.indicator?.let { TabStatusMark(it, terminalFontPx?.let { px -> with(density) { px.toDp() } } ?: VeloTheme.sizes.statusIcon) }
    }
}
