package io.github.yhzion.velo.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UiSizeTest {
    /** Every Dp token of [s] by its getter's name: a Dp is a float on the JVM. */
    private fun dps(s: Any): Map<String, Float> = s.javaClass.methods
        .filter { it.parameterCount == 0 && it.name.startsWith("get") && it.returnType == Float::class.javaPrimitiveType }
        .associate { it.name.substringBefore('-') to it.invoke(s) as Float }

    /** Every text style of [t] by its getter's name. */
    private fun styles(t: VeloType): Map<String, TextStyle> = t.javaClass.methods
        .filter { it.parameterCount == 0 && it.name.startsWith("get") && it.returnType == TextStyle::class.java }
        .associate { it.name to it.invoke(t) as TextStyle }

    /** The tokens that are the same at every size: a line, a focus edge, a blur and a shadow. */
    private val unscaled = setOf("getEdge", "getBlur", "getOverlayShadow", "getPaneFocus")

    @Test fun aSavedChoiceComesBackAsItWasSaved() {
        for (size in UiSize.entries) assertEquals(size, UiSize.parse(size.saved))
    }

    @Test fun nothingOrAnythingElseSavedIsMedium() {
        for (saved in listOf(null, "", "huge", "Large")) assertEquals(saved, UiSize.Medium, UiSize.parse(saved))
    }

    @Test fun largeIsTheSizeReadFromTheDuo() {
        assertEquals(dps(VeloSizes()), dps(veloSizes(UiSize.Large)))
        assertEquals(dps(VeloRadii()), dps(veloRadii(UiSize.Large)))
        val base = styles(VeloType())
        for ((name, style) in styles(veloType(UiSize.Large))) {
            assertEquals(name, base.getValue(name).fontSize, style.fontSize)
            assertEquals(name, base.getValue(name).lineHeight, style.lineHeight)
        }
    }

    @Test fun everyOtherTokenIsSmallerBelowLargeUnlessItIsAtItsFloor() {
        val base = dps(VeloSizes())
        assertTrue(base.size > 40)
        for (size in listOf(UiSize.Medium, UiSize.Small)) {
            for ((name, value) in dps(veloSizes(size))) {
                val atFloor = value == MinTouch.value || value == MinKey.value
                if (name in unscaled) assertEquals("$size $name", base.getValue(name), value)
                else assertTrue("$size $name: $value of ${base.getValue(name)}", value < base.getValue(name) || atFloor)
            }
            for ((name, value) in dps(veloRadii(size))) assertTrue("$size $name", value < dps(VeloRadii()).getValue(name))
        }
    }

    @Test fun whatTakesATapStaysAtLeastTheLeastTouchTarget() {
        for (size in UiSize.entries) {
            val s = veloSizes(size)
            for (target in listOf(s.control, s.capsule, s.capsuleCell, s.row, s.sectionHeader, s.field, s.menuItem)) {
                assertTrue("$size $target", target >= MinTouch)
            }
            // A key's cap may be narrower (KeyBar board: 32); its cell takes the bar's room ([keyCapWidths]) and its height the taps.
            assertTrue("$size keyHeight", s.keyHeight >= MinKey)
        }
    }

    @Test fun theRailHoldsAControlAndTheBarHoldsItsKeys() {
        for (size in UiSize.entries) {
            val s = veloSizes(size)
            // The Rail board: a control with 6 on each side at Medium.
            assertTrue("$size rail", s.rail.value >= s.control.value + mediumAt(size, 6.dp).value * 2 - 0.01f)
            assertTrue("$size keyBar", s.keyBar > s.keyHeight)
        }
    }

    /**
     * The boards set no line height on a line of text, so the browser gives it Roboto's own:
     * its ascent (1900 of 2048 units) and descent (500), each rounded to a whole dp. A label of
     * the app is as tall, so it lines up: 19 at 16, 15 at 13, 25 at 22.
     */
    @Test fun aLineOfInterfaceTextIsAsTallAsTheBrowsersLineOfRoboto() {
        val t = veloType(UiSize.Medium)
        val lines = mapOf(t.body to 19f, t.caption to 15f, t.pageTitle to 25f, t.screenTitle to 30f, t.title to 21f, t.value to 18f, t.eyebrow to 14f, t.overlayTitle to 24f)
        for ((style, line) in lines) assertEquals("${style.fontSize}", line, style.lineHeight.value, 0.01f)
        for (style in listOf(t.bodyStrong, t.header, t.control)) {
            val size = style.fontSize.value
            assertEquals("$size", Math.round(size * 1900f / 2048f) + Math.round(size * 500f / 2048f).toFloat(), style.lineHeight.value, 0.01f)
        }
    }

    @Test fun textGetsSmallerStepByStepAndNeverBelowTheLeastSize() {
        val large = styles(veloType(UiSize.Large))
        val medium = styles(veloType(UiSize.Medium))
        val small = styles(veloType(UiSize.Small))
        for ((name, style) in large) {
            val l = style.fontSize.value
            val m = medium.getValue(name).fontSize.value
            val sm = small.getValue(name).fontSize.value
            assertTrue("$name $sm $m $l", sm <= m && m < l)
            assertTrue("$name $sm", sm >= minOf(l, MinTextSp))
            // The line keeps its proportion to the size.
            assertEquals(name, style.lineHeight.value / l, small.getValue(name).lineHeight.value / sm, 0.001f)
        }
    }
}
