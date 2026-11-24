package io.github.yhzion.velo

import org.junit.Assert.assertEquals
import org.junit.Test

class PaneScalesTest {
    private val sent = mutableListOf<Triple<String, Float, Boolean>>()
    private val scales = PaneScales(mutableMapOf()) { block, scale, live -> sent += Triple(block, scale, live) }

    @Test
    fun `a pinch sends its steps live and settles once at its end`() {
        scales.set("b", 1.2f, live = true)
        scales.set("b", 1.5f, live = true)
        scales.settle("b")
        scales.settle("b")
        assertEquals(listOf(Triple("b", 1.2f, true), Triple("b", 1.5f, true), Triple("b", 1.5f, false)), sent)
        assertEquals(1.5f, scales.of("b"))
    }

    @Test
    fun `a text size command is settled at once and asks no settle after it`() {
        scales.set("b", 2f, live = false)
        scales.settle("b")
        assertEquals(listOf(Triple("b", 2f, false)), sent)
    }

    @Test
    fun `a settle with no pinch before it sends nothing`() {
        scales.settle("b")
        assertEquals(emptyList<Triple<String, Float, Boolean>>(), sent)
        assertEquals(1f, scales.of("b"))
    }

    @Test
    fun `a settled size ends the pinch of that pane only`() {
        scales.set("a", 1.5f, live = true)
        scales.set("b", 1.5f, live = true)
        scales.set("a", 2f, live = false)
        scales.settle("a")
        scales.settle("b")
        assertEquals(Triple("b", 1.5f, false), sent.last())
        assertEquals(4, sent.size)
    }

    @Test
    fun `gestures that are cut off settle every pinch once`() {
        scales.set("a", 1.5f, live = true)
        scales.set("b", 2f, live = true)
        scales.set("c", 3f, live = false)
        scales.settleAll()
        scales.settleAll()
        assertEquals(setOf(Triple("a", 1.5f, false), Triple("b", 2f, false)), sent.drop(3).toSet())
        assertEquals(5, sent.size)
    }

    @Test
    fun `a closed pane forgets its scale and its pinch`() {
        scales.set("a", 1.5f, live = true)
        scales.set("b", 2f, live = true)
        scales.retain(setOf("b"))
        scales.settle("a")
        assertEquals(1f, scales.of("a"))
        assertEquals(2, sent.size)
        scales.settle("b")
        assertEquals(Triple("b", 2f, false), sent.last())
    }
}
