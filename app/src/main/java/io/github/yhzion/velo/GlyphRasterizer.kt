package io.github.yhzion.velo

import android.content.SharedPreferences
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.graphics.fonts.SystemFonts
import android.icu.lang.UCharacter
import android.icu.lang.UProperty
import android.graphics.text.TextRunShaper
import android.os.Build
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import java.util.Locale

/**
 * Renders one grapheme into a cell-sized bitmap for the core's glyph atlas.
 * Returns A8 (w*h bytes) for text glyphs, RGBA8888 premultiplied (w*h*4 bytes) for color emoji,
 * or null when no face covers the grapheme. The core distinguishes formats by length.
 *
 * Font size and baseline depend only on (cellW, cellH) and come from the terminal font's
 * regular face, so Latin, Hangul, symbols and emoji share one size and one baseline. A glyph
 * from a fallback face is centered in its cell and scaled down when it is wider than the cell.
 */
class GlyphRasterizer(private val assets: AssetManager) {
    /** Which styles a face set draws with its own designs; the paint emboldens or slants the rest. */
    private data class Designs(val bold: Boolean, val italic: Boolean)

    /**
     * The faces of one font by Typeface style bits (NORMAL, BOLD, ITALIC, BOLD_ITALIC), each made when first asked
     * for: the first frame needs only the regular face, for the line height, and a bundled face reads its file (#336).
     */
    private class Faces(private val make: (style: Int) -> Typeface) {
        private val made = arrayOfNulls<Typeface>(4)

        @Synchronized
        operator fun get(style: Int): Typeface = made[style] ?: make(style).also { made[style] = it }
    }

    /**
     * Each compressed font asset is read only once, by whichever thread asks first. Other callers wait for
     * that read ([prefetch], #336).
     */
    private val files = ConcurrentHashMap<String, Lazy<Font>>()

    private fun file(path: String): Font = files.computeIfAbsent(path) { lazy { Font.Builder(assets, path).build() } }.value

    /** The files of the fonts the rasterizer left: a [prefetch] still under way does not read them, or keep them. */
    private val left = ConcurrentHashMap.newKeySet<String>()

    /** Stock JetBrains Mono supplies basic Powerline symbols when the selected font lacks them. */
    private val symbolFallback: Font get() = file(SYMBOL_FALLBACK)

    // Keep Hangul coverage stable across vendors. Select these faces only for Hangul,
    // since their Latin and symbol glyphs overlap with the user's preferred code font.
    // Loaded on first use: the font list's own rasterizer may never draw Hangul.
    private val korean: Faces = bundledFaces(TerminalFont.D2CODING)
    private val koreanDesigns = designs(TerminalFont.D2CODING)

    private var mono: Faces = faces(TerminalFont.JETBRAINS)
    private var monoDesigns = designs(TerminalFont.JETBRAINS)
    // Drawn only for an emoji, on the render thread: made then, from the file [prefetch] read.
    private val emoji: Typeface by lazy {
        Typeface.CustomFallbackBuilder(FontFamily.Builder(file(EMOJI)).build()).setSystemFallback("sans-serif").build()
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        fontFeatureSettings = "'calt' 1, 'liga' 1"
        // Han and Hangul share code points across CJK faces; pick the Korean one.
        textLocale = Locale.KOREAN
    }

    private var metricsKey = -1L
    private var textSize = 0f
    private var baseline = 0f

    var font: TerminalFont = TerminalFont.JETBRAINS
        @Synchronized set(value) {
            if (field == value) return
            val old = field
            field = value
            mono = faces(value)
            monoDesigns = designs(value)
            metricsKey = -1L
            // Keep the shared Powerline, emoji and Hangul fallbacks when switching fonts.
            val gone = old.assets.toSet() - value.assets.toSet() - KEPT
            left += gone
            left -= value.assets.toSet()
            files.keys.removeAll(gone)
        }

    val regularFace: Typeface get() = mono[Typeface.NORMAL]

    /** The regular face [font] would draw with, without making it the terminal's font: a font list shows each font in itself. */
    fun regularFaceOf(font: TerminalFont): Typeface = when {
        font == this.font -> regularFace
        // Read anew and not kept: a font only looked at does not stay in memory.
        font.assets.isNotEmpty() -> bundledFace(font.assets[0], files[font.assets[0]]?.value ?: Font.Builder(assets, font.assets[0]).build())
        else -> faces(font)[Typeface.NORMAL]
    }

    /** The cell height the core should use, in em of its font size: see [cellLineHeight]. */
    val lineHeight: Float
        @Synchronized get() = cellLineHeight(mono[Typeface.NORMAL])

    /**
     * Reads ahead the selected regular face (needed for line height), then the Powerline and emoji fallbacks
     * and the other styles. A face waits for any read in progress. Failed reads are left to fail at use time.
     */
    fun prefetch(fontAssets: List<String>) {
        for (path in (fontAssets.take(1) + SYMBOL_FALLBACK + EMOJI + fontAssets.drop(1)).distinct()) {
            if (path in left) continue
            runCatching { file(path) }
            // The font was left while its file was read.
            if (path in left) files.remove(path)
        }
    }

    /** [family] first, then stock JetBrains Mono, then the system's fonts. */
    private fun withFallbacks(family: FontFamily, includeSymbolFallback: Boolean = true): Typeface =
        Typeface.CustomFallbackBuilder(family)
            .apply { if (includeSymbolFallback) addCustomFallback(FontFamily.Builder(symbolFallback).build()) }
            .setSystemFallback("sans-serif").build()

    private fun bundledFace(asset: String, font: Font = file(asset)): Typeface =
        if (asset == SYMBOL_FALLBACK) withFallbacks(FontFamily.Builder(font).build(), includeSymbolFallback = false)
        else withFallbacks(FontFamily.Builder(font).build())

    /**
     * The faces of a bundled [font] by style. A missing italic reuses the upright face of the same weight,
     * and each asset is loaded once however many styles share it.
     */
    private fun bundledFaces(font: TerminalFont): Faces {
        // Only [Faces.get] touches it, under the lock of its Faces.
        val loaded = HashMap<String, Typeface>()
        return Faces { style ->
            val asset = font.assets[when (font.assets.size) {
                4 -> style
                2 -> style and Typeface.BOLD
                else -> 0
            }]
            loaded.getOrPut(asset) { bundledFace(asset) }
        }
    }

    /** A bundled font has the styles it ships; an installed or imported one is completed by Typeface.create. */
    private fun designs(font: TerminalFont) = when {
        font.assets.isNotEmpty() -> Designs(bold = font.assets.size >= 2, italic = font.assets.size >= 4)
        font.faces.isNotEmpty() -> Designs(bold = true, italic = true)
        else -> Designs(bold = false, italic = false)
    }

    private fun faces(font: TerminalFont): Faces = when {
        font == TerminalFont.D2CODING -> korean
        font.assets.isNotEmpty() -> bundledFaces(font)
        font == TerminalFont.SYSTEM -> {
            val base by lazy {
                // The font file itself: vendors replace what the "monospace" family name resolves to.
                val file = SystemFonts.getAvailableFonts().firstOrNull { it.file?.name == "DroidSansMono.ttf" }
                if (file != null) withFallbacks(FontFamily.Builder(file).build()) else Typeface.MONOSPACE
            }
            Faces { base }
        }
        else -> {
            val base by lazy {
                val members = font.faces.distinctBy { it.style }
                val builder = FontFamily.Builder(members.first())
                members.drop(1).forEach(builder::addFont)
                withFallbacks(builder.build())
            }
            Faces { style -> Typeface.create(base, style) }
        }
    }

    private companion object {
        val SYMBOL_FALLBACK = TerminalFont.JETBRAINS.assets[0]
        const val EMOJI = "fonts/Noto-COLRv1.ttf"
        /** The shared Powerline, emoji and Hangul fallback files. */
        val KEPT = setOf(SYMBOL_FALLBACK, EMOJI) + TerminalFont.D2CODING.assets
    }

    /** One size per cell: the 'M' advance equals cellW; ascent+descent is centered in cellH. */
    private fun cellMetrics(cellW: Int, cellH: Int) {
        val key = (cellW.toLong() shl 32) or cellH.toLong()
        if (key == metricsKey) return
        paint.typeface = mono[Typeface.NORMAL]
        paint.textSkewX = 0f
        paint.isFakeBoldText = false
        paint.textScaleX = 1f
        paint.textSize = 100f
        textSize = 100f * cellW / paint.measureText("M")
        paint.textSize = textSize
        val fm = paint.fontMetrics
        baseline = (cellH - (fm.descent - fm.ascent)) / 2f - fm.ascent
        metricsKey = key
    }

    @Synchronized
    fun rasterize(utf8: ByteArray, bold: Boolean, italic: Boolean, cellW: Int, cellH: Int, columns: Int): ByteArray? {
        val text = String(utf8, Charsets.UTF_8)
        if (text.isEmpty() || cellW <= 0 || cellH <= 0 || columns !in 1..3) return null
        cellMetrics(cellW, cellH)
        val w = cellW * columns
        val style = (if (bold) Typeface.BOLD else 0) or (if (italic) Typeface.ITALIC else 0)
        val cps = text.codePoints().toArray()
        val color = 0xFE0E !in cps && (0xFE0F in cps || 0x20E3 in cps ||
            cps.any { UCharacter.hasBinaryProperty(it, UProperty.EMOJI_PRESENTATION) })
        // Ghostty supplies each grapheme separately, except for adjacent ASCII operator cells.
        val operatorRun = columns > 1 && text.length == columns && text.all { it.code in 33..126 }
        paint.textScaleX = 1f
        val hangul = cps.any { Character.UnicodeScript.of(it) == Character.UnicodeScript.HANGUL }
        // A font without Hangul of its own takes it from D2Coding; an installed or imported font keeps its own.
        val koreanFace = hangul && !font.hangul && font.faces.isEmpty()
        paint.typeface = when {
            color -> emoji
            koreanFace -> korean[style]
            else -> mono[style]
        }
        val designs = when {
            color -> Designs(bold = false, italic = false)
            koreanFace -> koreanDesigns
            else -> monoDesigns
        }
        paint.textSize = textSize
        paint.isFakeBoldText = bold && !designs.bold
        paint.textSkewX = if (italic && !designs.italic) -0.2f else 0f
        if (operatorRun) {
            // hasGlyph(String) requires one glyph. JetBrains Mono's calt substitutions
            // can produce two ligature components occupying the original two cells.
            if (text.any { !paint.hasGlyph(it.toString()) }) return null
        } else if (!paint.hasGlyph(text)) return null

        var adv = paint.measureText(text)
        if (adv > w) {
            paint.textSize = textSize * w / adv
            adv = paint.measureText(text)
        }
        // A fallback face (Hangul, emoji) has its own vertical metrics; keep its ink inside the cell.
        var y = baseline
        if (color || hangul || !primaryCovers(text)) {
            val fm = paint.fontMetrics
            if (fm.descent - fm.ascent > cellH) paint.textSize *= cellH / (fm.descent - fm.ascent)
            adv = paint.measureText(text)
            val m = paint.fontMetrics
            y = (cellH - (m.descent - m.ascent)) / 2f - m.ascent
        }
        // D2Coding's Korean syllables advance one em. The grid reserves
        // two Latin advances (1.2 em in JetBrains Mono). Fit the Korean advance
        // horizontally while retaining its baseline, height and side bearings.
        if (columns == 2 && hangul && adv > 0f) {
            paint.textScaleX = w / adv
            adv = paint.measureText(text)
        }
        val x = (w - adv) / 2f

        val bmp = Bitmap.createBitmap(w, cellH, if (color) Bitmap.Config.ARGB_8888 else Bitmap.Config.ALPHA_8)
        Canvas(bmp).drawText(text, x, y, paint)
        val out = ByteBuffer.allocate(bmp.byteCount)
        bmp.copyPixelsToBuffer(out)
        val rowLen = w * (if (color) 4 else 1)
        val stride = bmp.rowBytes
        val bmpH = bmp.height
        bmp.recycle()
        val src = out.array()
        if (stride == rowLen) return src
        val packed = ByteArray(rowLen * bmpH)
        for (row in 0 until bmpH) System.arraycopy(src, row * stride, packed, row * rowLen, rowLen)
        return packed
    }

    /** Whether the terminal font itself, not a fallback, draws [text] with the current paint. */
    private fun primaryCovers(text: String): Boolean {
        // API 30 can shape runs with Canvas.drawText, but cannot inspect which
        // family supplied each glyph. Fit the selected face to the cell on that API.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val glyphs = TextRunShaper.shapeTextRun(text, 0, text.length, 0, text.length, 0f, 0f, false, paint)
        if (glyphs.glyphCount() == 0) return false
        // Asset fonts have no file; every system fallback has one.
        return (0 until glyphs.glyphCount()).all { glyphs.getFont(it).file?.name.let { n -> n == null || n == "DroidSansMono.ttf" } }
    }
}

/** The width of every cell in em of the core's font size, whatever the font (Pane.cellSizeLocked). */
internal const val CELL_ADVANCE = 0.6f

/**
 * [face]'s line height in em of the core's font size. The rasterizer draws a face so that its 'M' fills a cell
 * [CELL_ADVANCE] wide, so a face whose 'M' is narrower, such as D2Coding's 0.5 em, is drawn larger than the font size
 * and needs a taller cell; with the face's own line height it would be squeezed into its row.
 */
internal fun cellLineHeight(face: Typeface): Float {
    // A paint of its own: the rasterizer's keeps the last glyph's horizontal scale, which changes the advance.
    val paint = Paint().apply { typeface = face; textSize = 100f }
    val fm = paint.fontMetrics
    return (fm.descent - fm.ascent + fm.leading) / paint.measureText("M") * CELL_ADVANCE
}

/** Starts [GlyphRasterizer.prefetch] on a thread of its own, for the files [fontAssets] gives there (#336). */
internal fun prefetchFonts(rasterizer: GlyphRasterizer, fontAssets: () -> List<String>) {
    thread(name = "velo-fonts", isDaemon = true) { rasterizer.prefetch(fontAssets()) }
}

/**
 * The files of the terminal font saved in [prefs] ([Settings.font]): JetBrains Mono's when none is saved, and none for
 * a font that is not bundled (a device's or an imported one), whose faces are no assets.
 */
internal fun savedFontAssets(prefs: SharedPreferences): List<String> {
    val id = prefs.getString("font", null) ?: return TerminalFont.JETBRAINS.assets
    return TerminalFont.bundledFor(id)?.assets.orEmpty()
}
