package io.github.yhzion.velo

import android.content.Context
import android.graphics.fonts.Font
import android.graphics.fonts.SystemFonts
import android.net.Uri
import java.io.File
import java.nio.ByteOrder
import java.security.DigestInputStream
import java.security.MessageDigest

/**
 * A terminal font. A bundled font names its asset files in [assets]: Regular, then Bold, then Italic and BoldItalic
 * when it has them. A font with [hangul] draws its own Hangul; the others take Hangul from [D2CODING].
 */
data class TerminalFont(
    val id: String,
    val label: String,
    val fixedPitch: Boolean,
    val faces: List<Font> = emptyList(),
    val assets: List<String> = emptyList(),
    val hangul: Boolean = false,
) {
    companion object {
        private fun files(name: String, vararg styles: String) = styles.map { "fonts/$name-$it.ttf" }

        val JETBRAINS = TerminalFont("jetbrains", "JetBrains Mono", true,
            assets = files("JetBrainsMono", "Regular", "Bold", "Italic", "BoldItalic"))
        val FIRA_CODE = TerminalFont("fira-code", "Fira Code", true, assets = files("FiraCode", "Regular", "Bold"))
        val CASCADIA_CODE = TerminalFont("cascadia-code", "Cascadia Code", true,
            assets = files("CascadiaCode", "Regular", "Bold", "Italic", "BoldItalic"))
        val D2CODING = TerminalFont("d2coding", "D2Coding", true, assets = files("D2CodingLigature", "Regular", "Bold"), hangul = true)
        val SYSTEM = TerminalFont("system", "System monospace", true)
        val bundled = listOf(JETBRAINS, FIRA_CODE, CASCADIA_CODE, D2CODING, SYSTEM)

        internal fun bundledFor(id: String?): TerminalFont? =
            if (id == "jetendard") D2CODING else bundled.firstOrNull { it.id == id }
    }
}

/** What [describe] reads of a face: its family name, whether it is fixed-pitch, and whether it draws in color. */
private data class FaceInfo(val name: String, val fixedPitch: Boolean, val color: Boolean)

/** Tables that hold color glyphs (CBDT, COLR, sbix, SVG): an emoji font, whose letters are not drawn in its own style. */
private val ColorTables = setOf(0x43424454, 0x434f4c52, 0x73626978, 0x53564720)

/** Read the OpenType family name, fixed-pitch flag and color tables of this face, including faces in collections. */
private fun describe(font: Font): FaceInfo {
    val data = font.buffer.duplicate().order(ByteOrder.BIG_ENDIAN)
    fun u16(pos: Int) = data.getShort(pos).toInt() and 0xffff
    val face = if (data.getInt(0) == 0x74746366) data.getInt(12 + 4 * font.ttcIndex) else 0
    val signature = data.getInt(face)
    require(signature == 0x00010000 || signature == 0x4f54544f || signature == 0x74727565)

    var fixed = false
    var color = false
    var label: String? = null
    var best = -1
    for (i in 0 until u16(face + 4)) {
        val table = face + 12 + i * 16
        val base = data.getInt(table + 8)
        val tag = data.getInt(table)
        if (tag in ColorTables) color = true
        when (tag) {
            0x706f7374 -> fixed = data.getInt(base + 12) != 0 // post.isFixedPitch
            0x6e616d65 -> { // name: prefer typographic family, then legacy family.
                for (j in 0 until u16(base + 2)) {
                    val rec = base + 6 + j * 12
                    val platform = u16(rec)
                    val id = u16(rec + 6)
                    if ((platform != 0 && platform != 3) || (id != 1 && id != 16)) continue
                    val score = (if (id == 16) 2 else 0) + (if (u16(rec + 4) == 0x0409) 1 else 0)
                    if (score <= best) continue
                    val offset = base + u16(base + 4) + u16(rec + 10)
                    val length = u16(rec + 8)
                    require(offset >= 0 && length <= data.limit() - offset)
                    val bytes = ByteArray(length)
                    data.duplicate().apply { position(offset) }.get(bytes)
                    val name = bytes.toString(Charsets.UTF_16BE).trim()
                    if (name.isNotBlank()) { label = name; best = score }
                }
            }
        }
    }
    return FaceInfo(label ?: font.file?.nameWithoutExtension ?: error("Unnamed font"), fixed, color)
}

class TerminalFonts(private val context: Context) {
    private val directory = File(context.filesDir, "terminal-fonts")
    private val device by lazy {
        SystemFonts.getAvailableFonts().mapNotNull { font ->
            runCatching { describe(font) to font }.getOrNull()
        }.filterNot { it.first.color }.groupBy { it.first.name }.map { (name, group) ->
            TerminalFont("device:$name", name, group.any { it.first.fixedPitch }, group.map { it.second })
        }.sortedBy { it.label.lowercase() }
    }

    private fun imported(file: File, digest: String): List<TerminalFont> {
        val first = Font.Builder(file).build()
        val data = first.buffer.duplicate().order(ByteOrder.BIG_ENDIAN)
        val count = if (data.getInt(0) == 0x74746366) data.getInt(8) else 1
        require(count in 1..((data.limit() - 12) / 4))
        val faces = (0 until count).map { index ->
            val font = if (index == 0) first else Font.Builder(file).setTtcIndex(index).build()
            describe(font) to font
        }
        require(faces.any { !it.first.color }) { "Emoji fonts cannot be the terminal font" }
        return faces.filterNot { it.first.color }.groupBy { it.first.name }.map { (name, group) ->
            TerminalFont("import:$digest:$name", name, group.any { it.first.fixedPitch }, group.map { it.second })
        }
    }

    fun available() = TerminalFont.bundled + directory.listFiles().orEmpty()
        .filter { it.extension == "font" }.flatMap { runCatching { imported(it, it.nameWithoutExtension) }.getOrDefault(emptyList()) } + device

    fun restore(id: String?): TerminalFont {
        TerminalFont.bundledFor(id)?.let { return it }
        if (id?.startsWith("device:") == true) return device.firstOrNull { it.id == id } ?: TerminalFont.JETBRAINS
        if (id?.startsWith("import:") == true) {
            val digest = id.removePrefix("import:").substringBefore(':')
            if (digest.matches(Regex("[a-f0-9]{64}"))) {
                return runCatching { imported(File(directory, "$digest.font"), digest).first { it.id == id } }
                    .getOrDefault(TerminalFont.JETBRAINS)
            }
        }
        return TerminalFont.JETBRAINS
    }

    /** The private copy keeps a selection usable after the document provider goes offline. */
    fun importFile(uri: Uri): List<TerminalFont> {
        directory.mkdirs()
        val temp = File.createTempFile("incoming-", ".tmp", directory)
        try {
            val sha = MessageDigest.getInstance("SHA-256")
            requireNotNull(context.contentResolver.openInputStream(uri)).use { source ->
                temp.outputStream().use { target -> DigestInputStream(source, sha).copyTo(target) }
            }
            val digest = sha.digest().joinToString("") { "%02x".format(it) }
            val dest = File(directory, "$digest.font")
            if (!dest.exists()) {
                imported(temp, digest) // Validate every face before publishing it.
                check(temp.renameTo(dest))
            }
            return imported(dest, digest)
        } finally { temp.delete() }
    }
}
