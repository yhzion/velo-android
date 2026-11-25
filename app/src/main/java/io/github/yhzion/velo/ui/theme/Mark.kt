package io.github.yhzion.velo.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser

// The app's mark (StartKit board, start-mark; the splash ends on the same picture): two panes side by side, the left one
// focused with a cursor in it. Drawn from the board's 136 × 96 view box.

private const val UnitsW = 136f
private const val UnitsH = 96f
private const val LeftPane = "M13 1H51A12 12 0 0 1 63 13V83A12 12 0 0 1 51 95H13A12 12 0 0 1 1 83V13A12 12 0 0 1 13 1Z"
private const val RightPane = "M85 1H123A12 12 0 0 1 135 13V83A12 12 0 0 1 123 95H85A12 12 0 0 1 73 83V13A12 12 0 0 1 85 1Z"

/**
 * The app's mark, scaled to fit [modifier]'s size and centred, as SVG's "meet" does. Decorative: the wordmark beside it
 * names the app.
 */
@Composable
fun AppMark(modifier: Modifier = Modifier) {
    val focus = VeloTheme.colors.chrome.accent
    val other = VeloTheme.colors.sidebar.glassSelected
    val panes = remember { PathParser().parsePathString(LeftPane).toPath() to PathParser().parsePathString(RightPane).toPath() }
    Canvas(modifier) {
        val k = minOf(size.width / UnitsW, size.height / UnitsH)
        withTransform({
            translate((size.width - UnitsW * k) / 2, (size.height - UnitsH * k) / 2)
            scale(k, k, Offset.Zero)
        }) {
            // The board's strokes are 5 units, on panes drawn at 97 % from (2, 2) so the stroke stays inside the box.
            val stroke = Stroke(width = 5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            withTransform({ translate(2f, 2f); scale(0.97f, 0.97f, Offset.Zero) }) {
                drawPath(panes.first, focus, style = stroke)
                drawPath(panes.second, other, style = stroke)
            }
            drawRoundRect(focus, Offset(18f, 16f), Size(10f, 17f), CornerRadius(2f))
        }
    }
}
