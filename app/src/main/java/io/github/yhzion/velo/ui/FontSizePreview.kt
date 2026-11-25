package io.github.yhzion.velo.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.yhzion.velo.AppController
import io.github.yhzion.velo.cellLineHeight
import io.github.yhzion.velo.ui.theme.VeloTheme
import io.github.yhzion.velo.ui.theme.rounded
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ceil

/** The margin around a terminal grid in dp, which keeps text off the pane's rounded corners (HostConnection.pushDisplay). */
fun panePaddingDp(paneStyle: String): Float = if (paneStyle == "compact") 6f else 10f

/** What the terminal takes from a face: the advance of 'M' in em, and the cell height in em of the font size. */
private class FaceMetrics(val advance: Float, val lineHeight: Float)

private fun faceMetrics(face: Typeface): FaceMetrics {
    val paint = Paint().apply { typeface = face; textSize = 100f }
    // The width the rasterizer fits a glyph to, and its line height (GlyphRasterizer.lineHeight).
    return FaceMetrics(paint.measureText("M") / 100f, cellLineHeight(face))
}

/**
 * Under the Font size slider: a few terminal lines in the terminal's font at the chosen size, as
 * the terminal draws them (each cell 0.6 of the size wide and as high as the font's line at the
 * size its 'M' fills that width, on the pane's black with its padding), and how many columns and
 * rows the focused pane holds at it.
 * Both follow the focused pane's pinch scale, so they show what that pane will show.
 */
@Composable
internal fun FontSizePreview(app: AppController) {
    val settings = app.settings
    val font = settings.font
    val rasterizer = app.rasterizer
    val face by produceState<Typeface?>(null, font.id) {
        value = withContext(Dispatchers.Default) { runCatching { synchronized(rasterizer) { rasterizer.regularFaceOf(font) } }.getOrNull() }
    }
    val metrics = remember(face) { face?.let(::faceMetrics) }
    val density = LocalDensity.current
    val backdrop = VeloTheme.colors.backdrop
    val sizes = VeloTheme.sizes
    val pad = panePaddingDp(settings.paneStyle)
    val shown = fontSizeValue(settings.fontSize)
    // The focused pane's text size in px, its pinch scale with it, else the setting's; in the
    // terminal's own linear sp ([AppController] pxPerSp), not Compose's, which grows large text less.
    val conn = app.active
    val block = conn?.state?.focused
    val fontPx = if (conn != null && block != null) conn.terminalFontPx(block)
        else (settings.fontSize * density.density * density.fontScale).coerceAtLeast(4f)
    // The line height the core uses: the face's, or the rasterizer's while the face loads or failed to.
    val lineHeight = metrics?.lineHeight ?: remember(font.id) { rasterizer.lineHeight }
    val cellW = ceil(fontPx * 0.6f)
    val cellH = ceil(fontPx * lineHeight.coerceIn(1f, 2f))
    Column(Modifier.fillMaxWidth().padding(top = sizes.menuPadding)) {
        Box(
            Modifier.fillMaxWidth().clip(rounded(VeloTheme.radii.paneCompact)).background(backdrop.surface)
                .border(sizes.edge, VeloTheme.surface.divider, rounded(VeloTheme.radii.paneCompact)).padding(pad.dp)
                .clearAndSetSemantics { contentDescription = "Preview of ${font.label} at $shown" },
        ) {
            if (face != null && metrics != null) {
                val sample = buildAnnotatedString {
                    withStyle(SpanStyle(color = backdrop.accent)) { append("~/velo-android") }
                    withStyle(SpanStyle(color = backdrop.secondary)) { append(" main") }
                    append("\n")
                    withStyle(SpanStyle(color = backdrop.accent)) { append("$") }
                    append(" ls -la\n")
                    withStyle(SpanStyle(color = backdrop.accent)) { append("app  docs  native") }
                    append("  README.md\n0O 1lI {}[] -> != 한글")
                }
                Text(
                    sample, maxLines = 4, softWrap = false, overflow = TextOverflow.Clip, color = backdrop.text,
                    style = TextStyle(
                        fontFamily = remember(face) { FontFamily(face!!) },
                        // As the rasterizer does: the face's 'M' fills a cell, and a line is a cell high.
                        // px.toSp() undoes Compose's own scaling, so the px come out as the terminal's.
                        fontSize = with(density) { (cellW / metrics.advance.coerceAtLeast(0.1f)).toSp() },
                        lineHeight = with(density) { cellH.toSp() },
                    ),
                )
            }
        }
        val paneSize = if (conn != null && block != null) conn.shownPaneSize(block) else null
        if (paneSize != null) {
            val grid = gridFit(paneSize.width, paneSize.height, fontPx, lineHeight, pad * density.density)
            Text(
                "About ${grid.cols} columns × ${grid.rows} rows in the current pane",
                Modifier.padding(top = sizes.menuPadding), style = VeloTheme.type.caption, color = VeloTheme.surface.secondary,
            )
        }
    }
}
