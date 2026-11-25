package io.github.yhzion.velo

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Paint
import android.os.Bundle
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * The glyph rasterizer reads its font files when they are first needed, and [GlyphRasterizer.prefetch] may read them
 * on another thread meanwhile (#336). What it draws must stay what one made and used on a single thread draws.
 *
 * - Made, it has read no file; its line height reads the regular face's file alone.
 * - Every glyph below, in every style, with the files read ahead on a thread of their own while three threads draw,
 *   comes out byte for byte as from a rasterizer that read everything on one thread, and so does the line height.
 * - After a change of font, and back, it draws as a rasterizer made for that font, and keeps no file of the fonts it
 *   left, also when the font changes while the files are read ahead.
 *
 * The reference is this same code on one thread: that it draws as before #336 rests on the same builders and fallback
 * chains, and on screens of the same terminal compared before and after.
 *
 * Run on an emulator: `adb shell am instrument -w io.github.yhzion.velo.test/io.github.yhzion.velo.GlyphRasterizerInstrumentation`
 */
class GlyphRasterizerInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = runCatching {
            readsNothingUntilAsked()
            cellsKeepTheProportionsOfTheirFont()
            drawsAsOneThreadWould(TerminalFont.JETBRAINS)
            drawsAsOneThreadWould(TerminalFont.CASCADIA_CODE)
            followsAChangeOfFont()
            leavesNoFileOfAFontLeftDuringAPrefetch()
        }
        finish(if (result.isSuccess) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("stream", result.exceptionOrNull()?.stackTraceToString() ?: "GlyphRasterizer regression passed\n")
        })
    }

    /** A grapheme as the core asks for it: its bytes, its style and the cells it spans. */
    private data class Glyph(val text: String, val bold: Boolean, val italic: Boolean, val columns: Int)

    private val glyphs = buildList {
        for (bold in listOf(false, true)) for (italic in listOf(false, true)) {
            for (text in listOf("M", "g", "0", "@", "─", "▒", "\uF120", "\uE0B0")) add(Glyph(text, bold, italic, 1))
            add(Glyph("한", bold, italic, 2))
            add(Glyph("=>", bold, italic, 2))
        }
        add(Glyph("😀", false, false, 2))
        add(Glyph("👍🏽", false, false, 2))
    }

    private fun draw(r: GlyphRasterizer, g: Glyph): ByteArray? = r.rasterize(g.text.toByteArray(), g.bold, g.italic, 18, 38, g.columns)

    @Suppress("UNCHECKED_CAST")
    private fun readFiles(r: GlyphRasterizer): Set<String> {
        val field = GlyphRasterizer::class.java.getDeclaredField("files").apply { isAccessible = true }
        return (field.get(r) as Map<String, Lazy<*>>).filterValues { it.isInitialized() }.keys
    }

    private fun readsNothingUntilAsked() {
        val r = GlyphRasterizer(targetContext.assets)
        check(readFiles(r).isEmpty()) { "Made, the rasterizer read ${readFiles(r)}" }
        r.lineHeight
        check(readFiles(r) == setOf(TerminalFont.JETBRAINS.assets[0])) { "The line height read ${readFiles(r)}" }
    }

    private fun reference(font: TerminalFont): Pair<Float, List<ByteArray?>> {
        val r = GlyphRasterizer(targetContext.assets).also { it.font = font }
        return r.lineHeight to glyphs.map { draw(r, it) }
    }

    private fun same(a: ByteArray?, b: ByteArray?) = if (a == null || b == null) a == b else a.contentEquals(b)

    private fun drawsAsOneThreadWould(font: TerminalFont) {
        val (height, expected) = reference(font)
        check(expected.count { it != null } >= glyphs.size - 2) { "${font.id}: the reference drew too little: $expected" }
        val r = GlyphRasterizer(targetContext.assets).also { it.font = font }
        val go = CountDownLatch(1)
        val drawn = ConcurrentHashMap<Int, ByteArray>()
        val failures = ConcurrentHashMap<String, Throwable>()
        val threads = listOf(thread(name = "prefetch") { go.await(); r.prefetch(font.assets) }) + (0 until 3).map { n ->
            thread(name = "draw-$n") {
                go.await()
                try {
                    // Each thread in its own order, so that styles are first asked for from different threads.
                    for (i in glyphs.indices.shuffled(kotlin.random.Random(n))) {
                        val out = draw(r, glyphs[i]) ?: ByteArray(0)
                        val before = drawn.putIfAbsent(i, out)
                        check(before == null || before.contentEquals(out)) { "${glyphs[i]} drew differently on two threads" }
                    }
                } catch (e: Throwable) {
                    failures["draw-$n"] = e
                }
            }
        }
        go.countDown()
        threads.forEach { it.join(TimeUnit.SECONDS.toMillis(60)) }
        failures.values.firstOrNull()?.let { throw it }
        for ((i, g) in glyphs.withIndex()) {
            val got = drawn.getValue(i).takeIf { it.isNotEmpty() }
            check(same(got, expected[i])) { "${font.id}: $g differs from the one-thread rasterizer" }
        }
        check(r.lineHeight == height) { "${font.id}: line height ${r.lineHeight}, one-thread ${height}" }
    }

    /** The files a rasterizer of [font] may keep: its own, plus the Powerline, emoji and Hangul fallbacks. */
    private fun keeps(font: TerminalFont) =
        font.assets.toSet() + TerminalFont.JETBRAINS.assets[0] + "fonts/Noto-COLRv1.ttf" + TerminalFont.D2CODING.assets

    private fun followsAChangeOfFont() {
        val r = GlyphRasterizer(targetContext.assets)
        for (font in listOf(TerminalFont.FIRA_CODE, TerminalFont.D2CODING, TerminalFont.JETBRAINS, TerminalFont.CASCADIA_CODE)) {
            r.font = font
            val (height, expected) = reference(font)
            for ((i, g) in glyphs.withIndex()) check(same(draw(r, g), expected[i])) { "after a change to ${font.id}: $g differs" }
            check(r.lineHeight == height) { "after a change to ${font.id}: line height differs" }
            val extra = readFiles(r) - keeps(font)
            check(extra.isEmpty()) { "after a change to ${font.id} the rasterizer keeps $extra" }
        }
    }

    private fun leavesNoFileOfAFontLeftDuringAPrefetch() {
        val r = GlyphRasterizer(targetContext.assets)
        val reading = thread(name = "prefetch") { r.prefetch(TerminalFont.JETBRAINS.assets) }
        r.font = TerminalFont.FIRA_CODE
        reading.join(TimeUnit.SECONDS.toMillis(60))
        val extra = readFiles(r) - keeps(TerminalFont.FIRA_CODE)
        check(extra.isEmpty()) { "a font left during a prefetch stays read: $extra" }
    }

    /**
     * The core makes a cell 0.6 of the font size wide and [GlyphRasterizer.lineHeight] of it high, and the rasterizer
     * fills the width with the face's 'M'. A cell must then be as many widths high as the face's line is 'M' advances,
     * or a face narrower than 0.6 em is drawn larger than its row and squeezed into it: D2Coding's 'M' is 0.5 em.
     */
    private fun cellsKeepTheProportionsOfTheirFont() {
        val r = GlyphRasterizer(targetContext.assets)
        for (font in listOf(TerminalFont.JETBRAINS, TerminalFont.FIRA_CODE, TerminalFont.CASCADIA_CODE, TerminalFont.D2CODING)) {
            r.font = font
            r.rasterize("한".toByteArray(), false, false, 18, 38, 2) // Leaves a stretched paint behind.
            val paint = Paint().apply { typeface = r.regularFaceOf(font); textSize = 100f }
            val fm = paint.fontMetrics
            val face = (fm.descent - fm.ascent + fm.leading) / paint.measureText("M")
            val cell = r.lineHeight / 0.6f
            check(abs(cell - face) < 0.001f) { "${font.id}: a cell is $cell widths high, the face's line $face 'M' advances" }
        }
    }
}
