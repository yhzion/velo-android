package io.github.yhzion.velo.ui.theme

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.yhzion.velo.R
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

// The tab tile of the redesign (Tile and TabTiles boards): a program's brand tile, one layer
// more behind it for each pane of a split tab up to four, the pane count at the front tile's
// lower right from five panes, and a state dot at its upper right. The rules are pure functions
// of the tile's size in the board's units (dp at Medium); TabTile scales them to the Size setting.

/** The program a tab runs, as its tile draws it. */
enum class TileBrand(
    /** What a screen reader calls the program. */
    val label: String,
    /** The logo on the face: a Simple Icons path (NOTICE), or Codex's C. */
    @DrawableRes val logo: Int? = null,
    /** The text on the face when there is no logo. */
    val letter: String? = null,
    /** The logo's or the letter's colour; null is the surface's text. */
    val ink: Color? = null,
    /** An agent's tile is filled with its own colour; a tool's is dark glass. */
    val filled: Boolean = false,
    /** The letter is set in the monospace face. */
    val mono: Boolean = false,
    /** How much of the face the logo takes. */
    val glyphShare: Float = GlyphShare,
) {
    Claude("Claude Code", logo = R.drawable.ic_brand_claude, ink = Color.White, filled = true),
    // The C drawn with a pen, not a Simple Icons logo: it fills less of its box than a logo does.
    Codex("Codex", logo = R.drawable.ic_agent_codex, ink = Color.Black, filled = true, glyphShare = 0.54f),
    // Not on the Tile board: the agents the app marked before the redesign keep their own tile.
    Omp("omp", logo = R.drawable.ic_agent_omp, ink = Color.White, filled = true, glyphShare = 0.62f),
    Pi("pi", letter = "π", ink = Color.White, filled = true),
    Gemini("Gemini", logo = R.drawable.ic_brand_googlegemini, ink = Color(0xFF8E75B2)),
    Neovim("Neovim", logo = R.drawable.ic_brand_neovim, ink = Color(0xFF57A143)),
    Docker("Docker", logo = R.drawable.ic_brand_docker, ink = Color(0xFF2496ED)),
    Zsh("zsh", logo = R.drawable.ic_brand_zsh, ink = Color(0xFFF15A24)),
    Htop("htop", logo = R.drawable.ic_brand_htop, ink = Color(0xFF009020)),
    Python("Python", logo = R.drawable.ic_brand_python, ink = Color(0xFF3776AB)),
    Git("Git", logo = R.drawable.ic_brand_git, ink = Color(0xFFF03C2E)),
    // Not in Simple Icons: letter tiles (owner decision 5).
    Azure("Azure", letter = "A"),
    Heroku("Heroku", letter = "H"),
    /** Any other program. */
    Terminal("Terminal", letter = ">_", mono = true),
}

private const val GlyphShare = 0.6f

/** The brand of the tile of a tab that runs [program] (the server's program name or icon hint). */
fun tileBrand(program: String?): TileBrand = when (program?.trim()?.removePrefix("-")?.lowercase()) {
    "claude", "claude-code" -> TileBrand.Claude
    "codex", "codex-cli", "openai-codex" -> TileBrand.Codex
    "omp", "oh-my-pi" -> TileBrand.Omp
    "pi", "pi-coding-agent" -> TileBrand.Pi
    "gemini", "gemini-cli" -> TileBrand.Gemini
    "nvim", "neovim" -> TileBrand.Neovim
    "docker", "docker-compose" -> TileBrand.Docker
    "zsh" -> TileBrand.Zsh
    "htop" -> TileBrand.Htop
    "python", "python3", "ipython" -> TileBrand.Python
    "git" -> TileBrand.Git
    "az", "azure" -> TileBrand.Azure
    "heroku" -> TileBrand.Heroku
    else -> TileBrand.Terminal
}

/** The most tiles a split tab draws; from one pane more, the pane count shows. */
const val MaxTileLayers = 4

/** How many tiles a tab of [panes] panes draws: one per pane, at least one and at most [MaxTileLayers]. */
fun tileLayers(panes: Int): Int = panes.coerceIn(1, MaxTileLayers)

/** Whether the pane count shows: when there are more panes than layers. */
fun tilePaneCountShown(panes: Int): Boolean = panes > MaxTileLayers

/** How far each layer behind steps up and right, for a face [size] across. */
fun tileStep(size: Float): Float = max(2.5f, size * 0.115f)

/** The square the tile takes: the face and room for three steps above and to its right, so a row's text never moves. */
fun tileBox(size: Float): Float = size + ceil(tileStep(size) * 3)

/** A layer behind the face. [number] is the board's name for it: 2 is just behind the face, 4 the farthest. */
data class TileLayer(val number: Int, val dx: Float, val dy: Float, val scale: Float, val alpha: Float, val brightness: Float)

/**
 * The layers behind the face of a tab of [panes] panes, the farthest first. Each is the face
 * moved [TileLayer.dx] right and [TileLayer.dy] down, made smaller towards its top right corner,
 * and dimmed.
 */
fun tileBackLayers(panes: Int, size: Float): List<TileLayer> {
    val step = tileStep(size)
    return (tileLayers(panes) - 1 downTo 1).map { i ->
        TileLayer(i + 1, i * step, -i * step, 1 - i * 0.12f, 1 - i * 0.2f, 0.6f - i * 0.08f)
    }
}

/** The face's corner radius. */
fun tileCorner(size: Float): Float = (size * 0.27f).roundToInt().toFloat()

/** A logo's size on the face. */
fun tileGlyph(size: Float): Float = (size * GlyphShare).roundToInt().toFloat()

/** A letter's size on the face. */
fun tileLetter(size: Float, mono: Boolean): Float = (size * if (mono) 0.4f else 0.56f).roundToInt().toFloat()

/** The state dot's square in the tile's box: on the face's upper right corner. */
fun tileDotRect(size: Float, dot: Float = 9f): Rect {
    val extra = tileBox(size) - size
    val left = size - dot * 2 / 3
    val top = extra - dot / 3
    return Rect(left, top, left + dot, top + dot)
}

/** The pane count chip's height. */
private const val ChipHeight = 16f

/** How far the count reaches past the face's right edge and the box's bottom. */
private const val ChipOut = 4f

/** The pane count's rectangle in the tile's box, for a count of [digits] digits: on the face's lower right corner. */
fun tileCountRect(size: Float, digits: Int): Rect {
    val box = tileBox(size)
    // Roboto Bold's digits at 10 are about 6 wide, inside 4 on each side.
    val width = max(ChipHeight, digits * 6f + 8f)
    val right = size + ChipOut
    val bottom = box + ChipOut
    return Rect(right - width, bottom - ChipHeight, right, bottom)
}

/** What a screen reader says for the tile: the program, and the panes when there are several. */
fun tileLabel(brand: TileBrand, panes: Int): String = brand.label + if (panes > 1) ", $panes panes" else ""

/** The state a tile's dot shows at its upper right. */
enum class TileDot { Unread, Waiting, Failed }

private val ClaudeFill = Brush.verticalGradient(0f to Color(0xFFD59773), 0.6f to Color(0xFFC27649), 1f to Color(0xFFBB7146))
private val CodexFill = Brush.verticalGradient(listOf(Color.White, Color(0xFFE3E3E3)))
/** omp's and pi's fills: the marks the app drew for them before the redesign. */
private val OmpFill = Brush.linearGradient(listOf(Color(0xFFEC5FCD), Color(0xFF696AFF)))
private val PiFill = SolidColor(Color(0xFF30556B))

/** A filled face's top highlight and a layer's rim (Tile board). */
private val FaceHighlight = Color.White.copy(alpha = 0.25f)
private val LayerRim = Color.White.copy(alpha = 0.18f)

/**
 * The tile of a tab that runs [brand] in [panes] panes. [size] is the face; the tile takes
 * [tileBox] of it, the face at its lower left. [dot] puts a state dot at the face's upper right;
 * from five panes the pane count sits at its lower right. Both are ringed in [ring], the colour
 * of the row behind the tile.
 *
 * [tag] is the tile's test tag, `tab-tile-<tab id>`; its parts get the canvas's names under it
 * (`-face`, `-logo`, `-letter`, `-layer-N`, `-badge`, `-pane-count`). [contentDescription] is
 * what a screen reader says; null leaves the tile out, for a row that says it itself.
 */
@Composable
fun TabTile(
    brand: TileBrand, modifier: Modifier = Modifier, panes: Int = 1, size: Dp = VeloTheme.sizes.tabTile,
    dot: TileDot? = null, ring: Color = VeloTheme.surface.surface, tag: String? = null,
    contentDescription: String? = tileLabel(brand, panes),
) {
    val colors = VeloTheme.colors.sheet
    // One board unit at this size, and the face in board units: the rules are in those.
    val unit = VeloTheme.medium(1.dp).value
    val s = size.value / unit
    fun u(v: Float): Dp = (v * unit).dp
    fun Modifier.tagged(part: String) = if (tag != null) testTag(if (part.isEmpty()) tag else "$tag-$part") else this
    val fill: Brush = when (brand) {
        TileBrand.Claude -> ClaudeFill
        TileBrand.Codex -> CodexFill
        TileBrand.Omp -> OmpFill
        TileBrand.Pi -> PiFill
        else -> SolidColor(colors.glass)
    }
    val corner = u(tileCorner(s))
    val shape = RoundedCornerShape(corner)
    Box(
        modifier.size(u(tileBox(s))).tagged("")
            .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription; role = Role.Image } else Modifier),
    ) {
        for (layer in tileBackLayers(panes, s)) {
            Box(
                Modifier.align(Alignment.BottomStart).size(size).tagged("layer-${layer.number}")
                    .graphicsLayer {
                        transformOrigin = TransformOrigin(1f, 0f)
                        scaleX = layer.scale
                        scaleY = layer.scale
                        translationX = u(layer.dx).toPx()
                        translationY = u(layer.dy).toPx()
                        alpha = layer.alpha
                    }
                    .drawBehind { drawLayer(fill, corner.toPx(), layer.brightness) },
            )
        }
        Box(
            Modifier.align(Alignment.BottomStart).size(size).tagged("face")
                .drawBehind { drawFace(fill, corner.toPx(), brand.filled, colors.glassEdge) },
            contentAlignment = Alignment.Center,
        ) {
            val ink = brand.ink ?: colors.text
            if (brand.logo != null) {
                Icon(painterResource(brand.logo), null, Modifier.size(u((s * brand.glyphShare).roundToInt().toFloat())).tagged("logo"), tint = ink)
            } else if (brand.letter != null) {
                Text(
                    brand.letter, Modifier.tagged("letter"), color = ink, maxLines = 1,
                    fontSize = (tileLetter(s, brand.mono) * unit).sp, lineHeight = (tileLetter(s, brand.mono) * unit).sp,
                    fontWeight = FontWeight.Bold, fontFamily = if (brand.mono) FontFamily.Monospace else null,
                )
            }
        }
        if (dot != null) {
            val r = tileDotRect(s)
            val tint = when (dot) {
                TileDot.Unread -> colors.accent
                TileDot.Waiting -> colors.warning
                TileDot.Failed -> colors.danger
            }
            Box(Modifier.offset(u(r.left), u(r.top)).size(u(r.width)).tagged("badge").drawBehind { drawRingedDot(tint, ring, 2.dp.toPx() * unit) })
        }
        if (tilePaneCountShown(panes)) {
            // The chip's right edge is ChipOut past the face, its bottom ChipOut below the box.
            Box(
                Modifier.align(Alignment.BottomEnd).offset(u(-(tileBox(s) - s - ChipOut)), u(ChipOut))
                    .heightIn(min = u(ChipHeight)).widthIn(min = u(ChipHeight)).tagged("pane-count")
                    .drawBehind {
                        // `this.size`: the chip's, not the face's size above.
                        val chip = this.size
                        val ringWidth = 2.dp.toPx() * unit
                        val radius = CornerRadius(chip.height / 2 + ringWidth)
                        drawRoundRect(ring, Offset(-ringWidth, -ringWidth), Size(chip.width + ringWidth * 2, chip.height + ringWidth * 2), radius)
                        drawRoundRect(colors.glassSelected, cornerRadius = CornerRadius(chip.height / 2))
                    }
                    .padding(horizontal = u(4f)),
                contentAlignment = Alignment.Center,
            ) {
                Text("$panes", color = colors.text, fontSize = (10 * unit).sp, lineHeight = (ChipHeight * unit).sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
    }
}

/** A layer behind the face: the fill, dimmed to [brightness], and a faint rim. */
private fun DrawScope.drawLayer(fill: Brush, corner: Float, brightness: Float) {
    val radius = CornerRadius(corner)
    drawRoundRect(fill, cornerRadius = radius)
    drawRoundRect(Color.Black.copy(alpha = 1 - brightness), cornerRadius = radius)
    val line = 1.dp.toPx()
    drawRoundRect(LayerRim, Offset(line / 2, line / 2), Size(size.width - line, size.height - line), CornerRadius(corner - line / 2), Stroke(line))
}

/** The face: the fill, and a filled tile's light top edge or a glass tile's rim. */
private fun DrawScope.drawFace(fill: Brush, corner: Float, filled: Boolean, rim: Color) {
    val radius = CornerRadius(corner)
    drawRoundRect(fill, cornerRadius = radius)
    val line = 1.dp.toPx()
    if (filled) {
        // An inset shadow one line down: a light line along the top, inside the round corners.
        val outline = Path().apply { addRoundRect(RoundRect(0f, 0f, size.width, size.height, radius)) }
        clipPath(outline) {
            val inner = Path().apply { addRoundRect(RoundRect(0f, line, size.width, size.height + line, radius)) }
            val band = Path().apply { op(outline, inner, PathOperation.Difference) }
            drawPath(band, FaceHighlight)
        }
    } else {
        drawRoundRect(rim, Offset(line / 2, line / 2), Size(size.width - line, size.height - line), CornerRadius(corner - line / 2), Stroke(line))
    }
}

/** A dot of [tint] in a ring of [ring] [ringWidth] wide, drawn outside the dot's square. */
private fun DrawScope.drawRingedDot(tint: Color, ring: Color, ringWidth: Float) {
    val r = size.minDimension / 2
    drawCircle(ring, r + ringWidth)
    drawCircle(tint, r)
}
