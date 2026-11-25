package io.github.yhzion.velo.ui

import io.github.yhzion.velo.TerminalFont
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FontGroupsTest {
    @Test fun bundledDeviceAndImportedFontsGoInTheirOwnGroups() {
        assertEquals(FontGroup.BuiltIn, fontGroup(TerminalFont.JETBRAINS))
        assertEquals(FontGroup.BuiltIn, fontGroup(TerminalFont.SYSTEM))
        assertEquals(FontGroup.Device, fontGroup(TerminalFont("device:Droid Sans Mono", "Droid Sans Mono", true)))
        assertEquals(FontGroup.Imported, fontGroup(TerminalFont("import:ab12:Iosevka Term", "Iosevka Term", true)))
    }

    @Test fun theGroupsListTheirFontsInTheirOrderAndLeaveEmptyGroupsOut() {
        val device = TerminalFont("device:Noto Sans Mono", "Noto Sans Mono", true)
        val groups = fontGroups(listOf(device, TerminalFont.JETBRAINS, TerminalFont.FIRA_CODE))
        assertEquals(listOf(FontGroup.BuiltIn, FontGroup.Device), groups.map { it.first })
        assertEquals(listOf(TerminalFont.JETBRAINS, TerminalFont.FIRA_CODE), groups.first().second)
    }

    @Test fun aFontsTagIsItsNameInLowerCaseWithDashes() {
        assertEquals("jetbrains-mono", fontTagId("JetBrains Mono"))
        assertEquals("d2coding-ligature", fontTagId("D2Coding Ligature"))
        assertEquals("sarasa-term-k-velo", fontTagId("Sarasa Term K Velo"))
    }

    @Test fun aFontThatDrawsItsOwnHangulSaysSo() {
        assertEquals("Korean", fontNote(TerminalFont.D2CODING))
        assertNull(fontNote(TerminalFont.JETBRAINS))
    }

    @Test fun anImportedFontSaysItWasImported() {
        assertEquals("Imported", fontNote(TerminalFont("import:ab12:Iosevka Term", "Iosevka Term", true)))
        assertEquals("Korean, imported", fontNote(TerminalFont("import:cd34:Sarasa", "Sarasa", true, hangul = true)))
        assertNull(fontNote(TerminalFont("device:Noto Sans Mono", "Noto Sans Mono", true)))
    }
}
