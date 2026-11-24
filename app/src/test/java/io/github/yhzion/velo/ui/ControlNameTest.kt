package io.github.yhzion.velo.ui

import io.github.yhzion.velo.ui.theme.fieldDescription
import io.github.yhzion.velo.ui.theme.labelNamesField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlNameTest {

    @Test
    fun aFieldIsNamedByItsLabelElseByItsPlaceholder() {
        assertEquals("Node name", fieldDescription(label = "Node name", placeholder = ""))
        // The label wins: the placeholder is only an example of the content.
        assertEquals("Remote URL", fieldDescription(label = "Remote URL", placeholder = "https://"))
        assertEquals("Filter", fieldDescription(label = null, placeholder = "Filter"))
    }

    @Test
    fun aFieldWithNeitherLabelNorPlaceholderGetsNoName() {
        assertNull(fieldDescription(label = null, placeholder = ""))
        assertNull(fieldDescription(label = "", placeholder = ""))
    }

    @Test
    fun anEmptyLabelNamesNothingAndTheFieldFallsBackToItsPlaceholder() {
        // The same rule decides whether the drawn placeholder leaves the tree: it must not be read twice.
        assertFalse(labelNamesField(null))
        assertFalse(labelNamesField(""))
        assertTrue(labelNamesField("URL"))
        assertEquals("Filter", fieldDescription(label = "", placeholder = "Filter"))
    }

    @Test
    fun theFontSizeIsAWholeNumberAsTheLabelBesideTheSliderShowsIt() {
        assertEquals("14", fontSizeValue(14f))
        assertEquals("8", fontSizeValue(8f))
        assertEquals("24", fontSizeValue(24f))
    }
}
