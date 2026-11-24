package io.github.yhzion.velo.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

// The rail's "keep screen on" toggle: a teacup, after the cup of the Mac's Caffeine and
// Amphetamine menu bar icons. The outline is the same in both states and carries no steam;
// only the coffee inside comes and goes.

/** The cup while the screen may turn off: empty. */
internal val CupEmpty: ImageVector by lazy { cup(full = false) }

/** The cup while the screen stays on: full. */
internal val CupFull: ImageVector by lazy { cup(full = true) }

private fun cup(full: Boolean): ImageVector = ImageVector.Builder(
    name = if (full) "CupFull" else "CupEmpty", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
).apply {
    val ink = SolidColor(Color.Black)
    // The rim, seen from a little above.
    path(stroke = ink, strokeLineWidth = 1.7f) {
        moveTo(3f, 9f)
        arcTo(8f, 3.1f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 19f, y1 = 9f)
        arcTo(8f, 3.1f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 3f, y1 = 9f)
        close()
    }
    // The bowl under it.
    path(stroke = ink, strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round) {
        moveTo(3f, 9.2f)
        curveTo(3.2f, 15f, 6.6f, 19f, 11f, 19f)
        curveTo(15.4f, 19f, 18.8f, 15f, 19f, 9.2f)
    }
    // The handle.
    path(stroke = ink, strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round) {
        moveTo(18.6f, 10.4f)
        curveTo(21.1f, 9.5f, 22.5f, 11f, 21.8f, 12.7f)
        curveTo(21.2f, 14.1f, 19.5f, 14.7f, 17.4f, 14.6f)
    }
    if (full) path(fill = ink) {
        moveTo(5f, 9.25f)
        arcTo(6f, 1.95f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 17f, y1 = 9.25f)
        arcTo(6f, 1.95f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 5f, y1 = 9.25f)
        close()
    }
}.build()
