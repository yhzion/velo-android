package io.github.yhzion.velo.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import io.github.yhzion.velo.ui.theme.VeloTheme

/** The offline mark drawn at the end of a disconnected tab's row or caption. */
@Composable
internal fun OfflineMark(markHeight: Dp = VeloTheme.sizes.statusIcon) {
    Icon(Icons.Default.CloudOff, TabIndicator.Offline.description, Modifier.size(markHeight), tint = VeloTheme.surface.secondary)
}

/**
 * The working mark as the Sidebar and Pane boards draw it: three dots [dot] across, [gap] apart, in
 * the accent, lit one after another. A screen reader hears [description] once for all three.
 */
@Composable
internal fun WorkingDots(dot: Dp, gap: Dp, modifier: Modifier = Modifier, description: String = TabIndicator.Working.description) {
    val accent = VeloTheme.surface.accent
    val phase by rememberInfiniteTransition(label = "Working dots").animateFloat(
        initialValue = 0f, targetValue = 3f,
        animationSpec = infiniteRepeatable(tween(3 * DotIntervalMs, easing = LinearEasing)), label = "Dot phase",
    )
    Canvas(modifier.size(dot * 3 + gap * 2, dot).clearAndSetSemantics { contentDescription = description }) {
        val radius = size.height / 2
        val step = (size.width - size.height) / 2
        repeat(3) { index -> drawCircle(accent.copy(alpha = workingDotAlpha(phase, index)), radius, Offset(radius + index * step, center.y)) }
    }
}
