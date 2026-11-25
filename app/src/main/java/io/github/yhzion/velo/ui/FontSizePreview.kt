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
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import io.github.yhzion.velo.cellLineHeight
import io.github.yhzion.velo.ui.theme.VeloTheme
import io.github.yhzion.velo.ui.theme.rounded
import kotlinx.coroutines.withContext
import kotlin.math.ceil

/** The colour of the branch in the sample: a prompt's own (canvas SetAppearance block). */
private val BranchColour = Color(0xFFC792EA)

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
internal fun FontSizePreview(host: SettingsHost) {
    val settings = host.settings
    val font = settings.font
    val rasterizer = host.rasterizer
    val face by produceState<Typeface?>(null, font.id) {
        value = withContext(host.io) { runCatching { synchronized(rasterizer) { rasterizer.regularFaceOf(font) } }.getOrNull() }
    }
    val metrics = remember(face) { face?.let(::faceMetrics) }
    val density = LocalDensity.current
    val backdrop = VeloTheme.colors.backdrop
    val sizes = VeloTheme.sizes
    val pad = panePaddingDp(settings.paneStyle)
    val shown = fontSizeValue(settings.fontSize)
    // The focused pane's text size in px, its pinch scale with it, else the setting's; in the
    // terminal's own linear sp ([AppController] pxPerSp), not Compose's, which grows large text less.
    val pane = host.focusedPaneText()
    val fontPx = pane?.fontPx ?: (settings.fontSize * density.density * density.fontScale).coerceAtLeast(4f)
    // The line height the core uses: the face's, or the rasterizer's while the face loads or failed to.
    val lineHeight = metrics?.lineHeight ?: remember(font.id) { rasterizer.lineHeight }
    val cellW = ceil(fontPx * 0.6f)
    val cellH = ceil(fontPx * lineHeight.coerceIn(1f, 2f))
    val space = VeloTheme.space
    val shape = rounded(VeloTheme.medium(10.dp))
    // At least three of the board's lines tall (1.55 of the size), so that the box keeps its place
    // while the lines in it keep the terminal's own height.
    val least = with(density) { (settings.fontSize.sp.toDp() * 1.55f * 3) } + space.s12 * 2
    Column(Modifier.fillMaxWidth().padding(top = space.s4)) {
        Box(
            Modifier.fillMaxWidth().testTag("appearance-font-sample").heightIn(min = least).clip(shape).background(backdrop.surface)
                .border(sizes.edge, VeloTheme.surface.divider, shape).padding(horizontal = VeloTheme.medium(14.dp), vertical = space.s12)
                .clearAndSetSemantics { contentDescription = "Preview of ${font.label} at $shown" },
        ) {
            if (face != null && metrics != null) {
                val accent = VeloTheme.colors.chrome.accent
                val sample = buildAnnotatedString {
                    withStyle(SpanStyle(color = accent)) { append("~/src/web") }
                    withStyle(SpanStyle(color = BranchColour)) { append(" main") }
                    withStyle(SpanStyle(color = backdrop.secondary)) { append("$ ") }
                    append("ls\n")
                    withStyle(SpanStyle(color = accent)) { append("app  docs  native") }
                    append("  README.md\n0O 1lI {}[] -> != 한글")
                }
                Text(
                    sample, maxLines = 3, softWrap = false, overflow = TextOverflow.Clip, color = backdrop.text,
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
        val paneSize = pane?.paneSize
        if (paneSize != null) {
            val grid = gridFit(paneSize.width, paneSize.height, fontPx, lineHeight, pad * density.density)
            Text(
                "About ${grid.cols} columns × ${grid.rows} rows in the current pane",
                Modifier.padding(top = sizes.menuPadding), style = VeloTheme.type.caption, color = VeloTheme.surface.secondary,
            )
        }
    }
}
