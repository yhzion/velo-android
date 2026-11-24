package io.github.yhzion.velo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class TerminalFontsTest {
    private val assets = File("src/main/assets")
    private val provenance = File(assets, "fonts/PROVENANCE.txt").readText()

    @Test fun savedFontIdsStillRestore() {
        // Settings saved before more fonts were bundled keep their font.
        for (id in listOf("jetbrains", "system", "d2coding")) {
            val prefs = FakePrefs()
            prefs.edit().putString("font", id).apply()
            assertEquals(id, Settings(prefs).font.id)
        }
    }

    @Test fun savedJetBrainsSelectionRestoresStockJetBrainsMono() {
        val prefs = FakePrefs()
        prefs.edit().putString("font", "jetbrains").apply()
        val font = Settings(prefs).font
        val faces = font.assets
        assertEquals("JetBrains Mono", font.label)
        assertEquals(faces, savedFontAssets(prefs))
        assertEquals(
            listOf("Regular", "Bold", "Italic", "BoldItalic").map { "fonts/JetBrainsMono-$it.ttf" },
            faces,
        )
        val upstreamHashes = listOf(
            "a0bf60ef0f83c5ed4d7a75d45838548b1f6873372dfac88f71804491898d138f",
            "5590990c82e097397517f275f430af4546e1c45cff408bde4255dad142479dcb",
            "9d0a1f7a708e6af183f1193b7e81d40da294f5c67682c085d8401c60aac8ded4",
            "4039d5ce0ed225bf9c8b2c8c6436290ae2f356b7e90d70fa666227238324aa3b",
        )
        for ((face, hash) in faces.zip(upstreamHashes)) {
            assertEquals(face, hash, sha256(File(assets, face)))
        }
    }

    @Test fun retiredJetendardSelectionKeepsBundledHangul() {
        val prefs = FakePrefs()
        prefs.edit().putString("font", "jetendard").apply()
        assertEquals("d2coding", Settings(prefs).font.id)
        assertEquals(TerminalFont.D2CODING.assets, savedFontAssets(prefs))
    }

    @Test fun bundledFontsHaveDistinctIdsAndLabels() {
        val fonts = TerminalFont.bundled
        assertEquals(fonts.size, fonts.map { it.id }.toSet().size)
        assertEquals(fonts.size, fonts.map { it.label }.toSet().size)
        assertEquals(TerminalFont.JETBRAINS, fonts.first())
    }

    @Test fun bundledFacesHaveSupportedStyleCounts() {
        // The rasterizer selects Regular, Bold, Italic and BoldItalic by position.
        for (font in TerminalFont.bundled.filter { it.assets.isNotEmpty() }) {
            assertTrue(font.id, font.assets.size in setOf(1, 2, 4))
        }
    }

    @Test fun everyShippedFaceMatchesItsRecordedHash() {
        val records = Regex("(?m)^  ([a-f0-9]{64})  ([^/\\s]+\\.ttf)$").findAll(provenance)
            .groupBy({ it.groupValues[2] }, { it.groupValues[1] })
        val files = File(assets, "fonts").listFiles().orEmpty().filter { it.extension == "ttf" }
        assertEquals(files.map { it.name }.toSet(), records.keys)
        for (file in files) {
            assertEquals(file.name, listOf(sha256(file)), records[file.name])
        }
    }

    @Test fun everyShippedFontFileIsBundled() {
        val used = TerminalFont.bundled.flatMap { it.assets }.toSet() + "fonts/Noto-COLRv1.ttf"
        val shipped = File(assets, "fonts").listFiles().orEmpty().filter { it.extension == "ttf" }.map { "fonts/${it.name}" }.toSet()
        assertEquals(shipped, used)
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }
}
