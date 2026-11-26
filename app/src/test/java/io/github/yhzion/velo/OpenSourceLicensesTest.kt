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

    @Test fun aGivenCatalogIsWhereALicenseAndItsParentAreFound() {
        val part = LicenseComponent("own:part", "Part", "1", "MIT", LicenseGroup.Networking, null, emptyList())
        val own = LicenseComponent("own", "Own", "", "", LicenseGroup.Networking, null, emptyList(), parts = listOf(part))
        val notice = LicenseComponent("notice", "NOTICE", "", "Text", LicenseGroup.Velo, null, emptyList())
        val catalog = listOf(own, notice)
        assertEquals(notice, findLicense("notice", catalog))
        assertEquals(part, findLicense("own:part", catalog))
        assertEquals(own, licenseParent("own:part", catalog))
        assertNull(findLicense("ghostty", catalog))
        assertNull(licenseParent("go:golang.org/x/crypto", catalog))
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

    @Test fun hardWrappedProseFlowsAsOneLinePerParagraph() {
        val mit = """
            MIT License

            Copyright (c) 2024 Mitchell Hashimoto, Ghostty contributors

            Permission is hereby granted, free of charge, to any person obtaining a copy
            of this software and associated documentation files (the "Software"), to deal
            in the Software without restriction.
        """.trimIndent()
        assertEquals(
            """
            MIT License

            Copyright (c) 2024 Mitchell Hashimoto, Ghostty contributors

            Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction.
            """.trimIndent(),
            reflowLicense(mit),
        )
    }

    @Test fun listsIndentsAndShortLinesKeepTheirBreaks() {
        val apache = listOf(
            "   1. Definitions.",
            "",
            "      \"License\" shall mean the terms and conditions for use, reproduction,",
            "      and distribution as defined by Sections 1 through 9 of this document.",
            "",
            "   The conditions are the following, each of them required by this License:",
            "   - you must give any other recipients of the Work a copy of this License;",
            "   - you must cause any modified files to carry prominent notices stating it.",
            "Name: Velo",
            "Version: 1.0",
        ).joinToString("\n")
        assertEquals(
            listOf(
                "   1. Definitions.",
                "",
                "      \"License\" shall mean the terms and conditions for use, reproduction, and distribution as defined by Sections 1 through 9 of this document.",
                "",
                "   The conditions are the following, each of them required by this License:",
                "   - you must give any other recipients of the Work a copy of this License;",
                "   - you must cause any modified files to carry prominent notices stating it.",
                "Name: Velo",
                "Version: 1.0",
            ).joinToString("\n"),
            reflowLicense(apache),
        )
        // A numbered item whose text hangs under it, as in the Apache License.
        assertEquals(
            "   2. Grant of Copyright License. Subject to the terms and conditions of this License, each Contributor hereby grants to You a license.\n" +
                "   3. Grant of Patent License.",
            reflowLicense(
                "   2. Grant of Copyright License. Subject to the terms and conditions of\n" +
                    "      this License, each Contributor hereby grants to You\n" +
                    "      a license.\n" +
                    "   3. Grant of Patent License.",
            ),
        )
        assertEquals("", reflowLicense(""))
        assertEquals("a\r\nb".replace("\r", ""), reflowLicense("a\r\nb"))
    }

    @Test fun aLineLongerThanABlockStaysWhole() {
        val long = "x".repeat(2500)
        assertEquals(listOf("a", long, "b"), licenseBlocks("a\n$long\nb", maxChars = 1000))
    }

    @Test fun aSectionRunsFromItsHeadingToTheNextHeading() {
        val text = "Intro\n\nFirst part\n----------\none\n\nSecond part (here)\n------------------\ntwo\nmore\n\nThird\n-----\nthree\n"
        assertEquals("Second part (here)\n------------------\ntwo\nmore", noticeSection(text, "Second part"))
        assertEquals("Third\n-----\nthree", noticeSection(text, "Third"))
        assertNull(noticeSection(text, "Fourth"))
    }

    @Test fun theProgramLogosEntryShowsTheLogoSectionOfTheNotice() {
        val logos = findLicense("simple-icons")
        assertNotNull(logos)
        assertEquals("Program and Tailscale logos (Simple Icons)", logos!!.name)
        val text = logos.texts.single()
        assertEquals("notices/NOTICE", text.asset)
        val section = noticeSection(File(root, "NOTICE").readText(), text.section!!)
        assertNotNull("NOTICE has no section ${text.section}", section)
        for (said in listOf("Simple Icons 16.38.1", "CC0 1.0", "CC BY 3.0", "CC BY-SA 3.0", "Tailscale")) assertTrue(said, said in section!!)
        assertTrue("Bundled fonts" !in section!!)
    }
}
