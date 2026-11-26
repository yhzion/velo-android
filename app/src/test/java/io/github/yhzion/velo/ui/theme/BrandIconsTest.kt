package io.github.yhzion.velo.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The tab tiles' logos and Tailscale's: Simple Icons 16.38.1, one path each on a 24 by 24 grid, and their notice. */
class BrandIconsTest {
    /** The slugs of Simple Icons that the redesign's tiles draw, and Tailscale, which the login card and Settings draw. */
    private val slugs = listOf("claude", "googlegemini", "neovim", "docker", "zsh", "htop", "python", "git", "tailscale")

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

    @Test fun onlyTheLogosVeloDrawsAreShipped() {
        val shipped = File("src/main/res/drawable").listFiles()!!.map { it.nameWithoutExtension }.filter { it.startsWith("ic_brand_") }
        assertEquals(slugs.map { "ic_brand_$it" }.toSet(), shipped.toSet())
    }

    @Test fun theNoticeNamesSimpleIconsAndTheMarks() {
        val notice = File("../NOTICE").readText()
        assertTrue(notice.contains("Simple Icons 16.38.1"))
        assertTrue(notice.contains("CC0"))
        assertTrue(notice.contains("trademarks"))
    }

    @Test fun theNoticeSaysWhereTheTailscaleLogoIsShown() {
        val section = notice().substringAfter("Program logos").substringBefore("\n\n\n")
        for (said in listOf("Tailscale", "login card", "Settings")) assertTrue(said, said in section)
    }

    @Test fun ompAndPiMarksAreFilledVectorsOnATwentyFourGrid() {
        for (name in listOf("ic_agent_omp", "ic_mark_pi")) {
            val file = drawable(name)
            assertTrue("$file", file.isFile)
            val xml = file.readText()
            assertTrue(name, xml.contains("android:viewportWidth=\"24\"") && xml.contains("android:viewportHeight=\"24\""))
            assertTrue(name, xml.contains("android:fillColor=\"#FFFFFFFF\""))
            assertFalse(name, xml.contains("android:strokeColor"))
            assertTrue(name, Regex("android:pathData=\"[Mm][^\"]{20,}\"").containsMatchIn(xml))
        }
    }

    @Test fun theNoticeNamesOmpAndPiDrawings() {
        val section = notice().substringAfter("Program logos").substringBefore("\n\n\n")
        for (said in listOf("omp", "pi", "Velo's own")) assertTrue(said, said in section)
    }

    private fun notice() = File("../NOTICE").readText()
}
