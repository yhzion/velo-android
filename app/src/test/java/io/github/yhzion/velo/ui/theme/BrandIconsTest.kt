package io.github.yhzion.velo.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The tab tiles' logos: Simple Icons 16.38.1, one path each on a 24 by 24 grid, and their notice. */
class BrandIconsTest {
    /** The slugs of Simple Icons that the redesign's tiles draw. */
    private val slugs = listOf("claude", "googlegemini", "neovim", "docker", "zsh", "htop", "python", "git")

    // Unit tests run in the module's directory.
    private fun drawable(name: String) = File("src/main/res/drawable/$name.xml")

    @Test fun everyLogoIsOnePathOnATwentyFourGrid() {
        for (slug in slugs) {
            val file = drawable("ic_brand_$slug")
            assertTrue("$file", file.isFile)
            val xml = file.readText()
            assertTrue(slug, xml.contains("android:viewportWidth=\"24\"") && xml.contains("android:viewportHeight=\"24\""))
            assertEquals(slug, 1, Regex("<path\\b").findAll(xml).count())
            assertTrue(slug, Regex("android:pathData=\"[Mm][^\"]{20,}\"").containsMatchIn(xml))
        }
    }

    @Test fun onlyTheLogosTheTilesDrawAreShipped() {
        val shipped = File("src/main/res/drawable").listFiles()!!.map { it.nameWithoutExtension }.filter { it.startsWith("ic_brand_") }
        assertEquals(slugs.map { "ic_brand_$it" }.toSet(), shipped.toSet())
    }

    @Test fun theNoticeNamesSimpleIconsAndTheMarks() {
        val notice = File("../NOTICE").readText()
        assertTrue(notice.contains("Simple Icons 16.38.1"))
        assertTrue(notice.contains("CC0"))
        assertTrue(notice.contains("trademarks"))
    }
}
