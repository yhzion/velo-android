package io.github.yhzion.velo.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import io.github.yhzion.velo.ui.theme.VeloTheme

private fun keyVector(name: String, path: String): ImageVector = ImageVector.Builder(
    name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
).addPath(
    pathData = PathParser().parsePathString(path).toNodes(), stroke = SolidColor(Color.Black),
    strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
).build()

// Physical cursor directions do not mirror in RTL layouts. An arrow head on a bar or a bend has arms of 6, a plain arrow's of 7 (#218).
private val keyIcons = mapOf(
    "Tab" to keyVector("Tab", "M3 12 H17 M11 6 L17 12 L11 18 M21 5 V19"),
    "Enter" to keyVector("Enter", "M20 4 V10 A4 4 0 0 1 16 14 H4 M10 8 L4 14 L10 20"),
    "Shift" to keyVector("Shift", "M12 3.5 L20.5 12 H16 V20 H8 V12 H3.5 Z"),
    // The key itself comes with #213.
    "Space" to keyVector("Space", "M4 10 V16 H20 V10"),
    "/" to keyVector("Slash", "M16 4 L8 20"),
    "~" to keyVector("Tilde", "M3 14 Q7 7 12 12 T21 10"),
    "|" to keyVector("Pipe", "M12 4 V20"),
    "-" to keyVector("Minus", "M5 12 H19"),
    "Left" to keyVector("Left", "M20 12 H4 M11 5 L4 12 L11 19"),
    "Right" to keyVector("Right", "M4 12 H20 M13 5 L20 12 L13 19"),
    "Up" to keyVector("Up", "M12 20 V4 M5 11 L12 4 L19 11"),
    "Down" to keyVector("Down", "M12 4 V20 M5 13 L12 20 L19 13"),
    "Backspace" to keyVector("Backspace", "M9 5 H21 V19 H9 L2 12 Z M11 9 L17 15 M17 9 L11 15"),
)

/** The icon [key] is drawn with, or null for a key named in a word. */
internal fun keyIcon(key: String): ImageVector? = keyIcons[key]

/** Visual legend only: the enclosing control supplies the key's accessible name. */
@Composable
internal fun KeyLegend(key: String, color: Color, modifier: Modifier = Modifier) {
    val icon = keyIcon(key)
    if (icon != null) {
        Icon(icon, contentDescription = null, modifier = modifier.size(VeloTheme.sizes.keyIcon), tint = color)
    } else {
        Text(keyName(key), modifier.clearAndSetSemantics {}, style = VeloTheme.type.key, color = color, maxLines = 1, softWrap = false)
    }
}
