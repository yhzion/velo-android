package io.github.yhzion.velo.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.yhzion.velo.ui.theme.VeloTheme
import io.github.yhzion.velo.ui.theme.BrowserLines
import io.github.yhzion.velo.ui.theme.browserLine
import io.github.yhzion.velo.ui.theme.onPixels
import androidx.compose.ui.platform.LocalDensity

/**
 * A style of [size] sp at Medium that the type scale does not name (the boards' 14, 12.5),
 * scaled with the Size setting and on the browser's line of Roboto, as the type scale's styles are.
 */
@Composable
internal fun textAt(size: Float, weight: FontWeight = FontWeight.Normal): TextStyle {
    val scale = VeloTheme.medium(1.dp).value
    return TextStyle(fontSize = (size * scale).sp, lineHeight = (browserLine(size) * scale).sp, fontWeight = weight, lineHeightStyle = BrowserLines)
        .onPixels(LocalDensity.current)
}
