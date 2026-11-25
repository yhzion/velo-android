package io.github.yhzion.velo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OpenSourceLicensesTest {
    /** Unit tests run in the app module; the notices live at the repository root. */
    private val root = File("..")

    private fun everyComponent(): List<LicenseComponent> =
        OpenSourceLicenses.all.flatMap { listOf(it) + it.parts }

    private fun everyText(): List<LicenseText> = everyComponent().flatMap { it.texts }

    @Test fun everyListedTextExistsInTheSourceTree() {
        val texts = everyText()
        assertTrue(texts.isNotEmpty())
        for (text in texts) {
            assertTrue("${text.asset} is not a packaged notice", text.asset.startsWith("notices/"))
            val source = File(root, noticeSource(text.asset))
            assertTrue("${text.asset} has no source at $source", source.isFile)
        }
    }

    @Test fun assetPathsMapToTheirSources() {
        assertEquals("LICENSE", noticeSource("notices/LICENSE"))
        assertEquals("NOTICE", noticeSource("notices/NOTICE"))
        assertEquals("licenses/native/ghostty-MIT.txt", noticeSource("notices/third-party/native/ghostty-MIT.txt"))
    }

    @Test fun everyBundledNoticeIsListedOrACopyOfAListedOne() {
        val listed = everyText().map { File(root, noticeSource(it.asset)).canonicalFile }.toSet()
        val listedBytes = listed.map { it.readBytes().toList() }.toSet()
        val tree = File(root, "licenses")
        val missing = tree.walkTopDown().filter { it.isFile }
            .filter { it.canonicalFile !in listed && it.readBytes().toList() !in listedBytes }
            .map { it.relativeTo(tree).path }.sorted().toList()
        assertEquals(emptyList<String>(), missing)
        assertTrue(File(root, "LICENSE").canonicalFile in listed)
        assertTrue(File(root, "NOTICE").canonicalFile in listed)
    }

    @Test fun groupsComeInTheirOrderAndKeepTheManifestOrder() {
        val groups = licenseGroups(OpenSourceLicenses.all)
        assertEquals(LicenseGroup.entries.toList(), groups.map { it.first })
        for ((group, components) in groups) {
            assertTrue("$group is empty", components.isNotEmpty())
            assertEquals(OpenSourceLicenses.all.filter { it.group == group }, components)
        }
        assertEquals("velo", groups.first().second.first().id)
    }

    @Test fun theRequiredComponentsAreListed() {
        val ids = everyComponent().map { it.id }.toSet()
        val required = listOf(
            "velo", "notice", "ghostty", "simdutf", "highway", "wuffs", "uucode", "zig",
            "tailscale", "libtailscale", "go", "go-modules", "androidx", "kotlin-stdlib", "kotlinx-coroutines",
            "kotlinx-serialization", "camerax", "zxing", "dagger", "jakarta-inject", "checker-qual",
            "jetbrains-mono", "fira-code", "cascadia-code", "d2coding", "noto-emoji", "powerline",
        )
        assertEquals(emptyList<String>(), required.filter { it !in ids })
    }

    @Test fun idsAreUniqueAndFoundWithTheirParent() {
        val ids = everyComponent().map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        val crypto = findLicense("go:golang.org/x/crypto")
        assertNotNull(crypto)
        assertEquals("go-modules", licenseParent(crypto!!.id)?.id)
        assertNull(licenseParent("ghostty"))
        assertNull(findLicense("nothing"))
    }

    @Test fun goModulesListEveryModuleWithItsOwnTexts() {
        val modules = findLicense("go-modules")!!.parts
        assertTrue(modules.size >= 20)
        assertEquals(modules.map { it.name }.sorted(), modules.map { it.name })
        for (module in modules) {
            assertTrue(module.texts.isNotEmpty())
            assertTrue(module.texts.all { it.asset.startsWith("notices/third-party/go/${module.name}/") })
        }
        assertTrue(findLicense("go-modules")!!.texts.isEmpty())
    }

    @Test fun theSubtitleJoinsVersionAndLicense() {
        assertEquals("1.0.1 · MIT", licenseSubtitle(findLicense("ghostty")!!))
        assertEquals("MIT", licenseSubtitle(findLicense("velo")!!))
    }

    @Test fun aNoticeIsReadAsUtf8ThroughTheOpener() {
        val asset = "notices/third-party/native/ghostty-MIT.txt"
        val text = readNotice(asset) { File(root, noticeSource(it)).inputStream() }
        assertTrue(text.contains("Mitchell Hashimoto"))
        assertEquals("한글 ✓", readNotice("x") { "한글 ✓".byteInputStream() })
    }

    @Test fun longTextsSplitAtLineEndsAndJoinBack() {
        val text = (1..500).joinToString("\n") { "line $it of the notice" }
        val blocks = licenseBlocks(text, maxChars = 1000)
        assertTrue(blocks.size > 1)
        assertTrue(blocks.all { it.length <= 1000 })
        assertEquals(text, blocks.joinToString("\n"))
        assertEquals(listOf("short"), licenseBlocks("short", maxChars = 1000))
        assertEquals(listOf(""), licenseBlocks("", maxChars = 1000))
    }

    @Test fun aLineLongerThanABlockStaysWhole() {
        val long = "x".repeat(2500)
        assertEquals(listOf("a", long, "b"), licenseBlocks("a\n$long\nb", maxChars = 1000))
    }
}
