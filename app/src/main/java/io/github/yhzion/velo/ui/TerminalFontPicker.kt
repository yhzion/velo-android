package io.github.yhzion.velo.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.yhzion.velo.GlyphRasterizer
import io.github.yhzion.velo.TerminalFont
import io.github.yhzion.velo.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where a terminal font comes from, as the font list groups them. */
enum class FontGroup(val title: String, val id: String) {
    BuiltIn("Built in", "built-in"), Device("On this device", "device"), Imported("Imported", "imported"),
}

/** The group of [font]: an imported font's id starts with `import:`, a device font's with `device:`. */
fun fontGroup(font: TerminalFont): FontGroup = when {
    font.id.startsWith("import:") -> FontGroup.Imported
    font.id.startsWith("device:") -> FontGroup.Device
    else -> FontGroup.BuiltIn
}

/** [fonts] by group, in [FontGroup] order, each in the order given; a group without any is left out. */
fun fontGroups(fonts: List<TerminalFont>): List<Pair<FontGroup, List<TerminalFont>>> =
    FontGroup.entries.mapNotNull { group -> fonts.filter { fontGroup(it) == group }.takeIf { it.isNotEmpty() }?.let { group to it } }

/** A font's name in test tags: lower case, words joined by dashes ("jetbrains-mono"). */
fun fontTagId(label: String): String = label.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

/** What a font's row says under its name: that it draws its own Hangul, that it was imported. */
fun fontNote(font: TerminalFont): String? =
    listOfNotNull("Korean".takeIf { font.hangul }, "imported".takeIf { fontGroup(font) == FontGroup.Imported })
        .joinToString(", ").replaceFirstChar(Char::uppercaseChar).ifEmpty { null }

/** The most rows in one item of the list: a group of device fonts can be long, and each row loads its face. */
private const val ROWS_PER_ITEM = 20

/**
 * The Terminal Font page of the settings (canvas SetFont block): search, the monospaced/all
 * filter, a sample of the chosen font, importing one, and the fonts by group. A tapped font is the
 * terminal's font at once, as every other setting of the sheet is; the sheet's Back returns to
 * Appearance. [top] goes over the page (its title beside the categories).
 */
@Composable
internal fun TerminalFontSettings(host: SettingsHost, modifier: Modifier = Modifier, top: @Composable () -> Unit = {}) {
    val settings = host.settings
    val sizes = VeloTheme.sizes
    val space = VeloTheme.space
    val c = VeloTheme.surface
    val scope = rememberCoroutineScope()
    val assets = LocalContext.current.assets
    var all by rememberSaveable { mutableStateOf(!settings.font.fixedPitch) }
    var query by rememberSaveable { mutableStateOf("") }
    var choices by remember { mutableStateOf(TerminalFont.bundled) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(host) {
        choices = withContext(host.io) { host.fontChoices() }
        loading = false
    }
    fun use(font: TerminalFont) {
        if (font.id == settings.font.id) return
        settings.saveFont(font)
        host.pushFont()
    }
    val selected = settings.font
    val renderer = remember(assets) { lazy { GlyphRasterizer(assets) } }
    // Each font's face, made once when its row first shows: a row draws its own name in it.
    val faces = remember(renderer) { mutableStateMapOf<String, FontFamily>() }
    suspend fun faceOf(font: TerminalFont): FontFamily = faces[font.id] ?: withContext(host.io) {
        val rasterizer = renderer.value
        FontFamily(synchronized(rasterizer) { rasterizer.regularFaceOf(font) })
    }.also { faces[font.id] = it }
    val preview by produceState<FontFamily?>(null, selected.id) { value = faceOf(selected) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            loading = true
            error = null
            try {
                val (imported, catalog) = withContext(host.io) {
                    host.importFont(uri) to host.fontChoices()
                }
                choices = catalog
                all = !imported.first().fixedPitch
                query = ""
                use(imported.first())
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = "Could not read this font. Choose a TTF, OTF, or TTC file." }
            finally { loading = false }
        }
    }
    val matches = remember(choices, all, query) {
        choices.filter { (all || it.fixedPitch) && it.label.contains(query.trim(), ignoreCase = true) }
    }
    val groups = remember(matches) { fontGroups(matches) }
    val frame = GroupFrame(c.divider, VeloTheme.radii.group, hairline(), VeloTheme.space.s4)
    // One scroll owner: with the IME up there may not be height left over for a nested list.
    // Keep import above the results so it is reachable even with hundreds of installed fonts.
    // The title stays over the list; the list is as tall as its fonts, at most the rest of the page.
    Column(modifier.windowInsetsPadding(WindowInsets.screenIme)) {
    top()
    LazyColumn(Modifier.weight(1f, fill = false).testTag("font-picker-content"), contentPadding = PaddingValues(bottom = space.s24)) {
        item {
            Column(Modifier.fillMaxWidth().padding(top = space.s8), verticalArrangement = Arrangement.spacedBy(space.s12)) {
                Box(Modifier.testTag("font-search")) {
                    VeloTextField(query, { query = it }, placeholder = "Search fonts", icon = rememberVectorPainter(Icons.Default.Search),
                        tagPrefix = "font-search")
                }
                VeloSegmented(listOf(false, true), all, { if (it) "All fonts" else "Monospaced" }, Modifier.fillMaxWidth().testTag("font-filter"),
                    optionTag = { if (it) "font-filter-all" else "font-filter-monospaced" }, stretch = true, onPick = { all = it })
                FontSample(selected, preview, fontSizeValue(settings.fontSize))
                VeloButton("Import font…", { importer.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth(), enabled = !loading,
                    icon = rememberVectorPainter(Icons.Default.Upload), tag = "font-import-button")
                error?.let { Text(it, color = c.dangerText) }
            }
        }
        if (matches.isEmpty()) item {
            Text(if (loading) "Loading fonts…" else "No matching fonts", Modifier.padding(top = space.s12), color = c.secondary)
        }
        for ((group, fonts) in groups) {
            item(key = "header:${group.id}") {
                Text(group.title, Modifier.fillMaxWidth().padding(top = space.s12).testTag("font-group-${group.id}-header")
                    .padding(start = space.s4, end = space.s4, top = space.s8).lineBox(VeloTheme.type.caption).semantics { heading() },
                    style = VeloTheme.type.caption, fontWeight = FontWeight.Medium, color = c.secondary)
            }
            // A group is one framed box; a long one is drawn a few rows at a time, its top on the first, its bottom on the last.
            val chunks = fonts.chunked(ROWS_PER_ITEM)
            chunks.forEachIndexed { index, chunk ->
                item(key = "${group.id}:$index") {
                    val first = index == 0
                    val last = index == chunks.lastIndex
                    Column(
                        Modifier.fillMaxWidth().padding(top = if (first) space.s12 else 0.dp)
                            .then(if (chunks.size == 1) Modifier.testTag("font-group-${group.id}") else Modifier)
                            .drawBehind { frame.draw(this, first, last) }
                            .padding(start = frame.inset, end = frame.inset, top = if (first) frame.inset else 0.dp, bottom = if (last) frame.inset else 0.dp)
                            .selectableGroup(),
                    ) {
                        for (font in chunk) {
                            val face by produceState(faces[font.id], font.id) { value = runCatching { faceOf(font) }.getOrNull() }
                            FontRow(font, face, checked = selected.id == font.id, enabled = !loading) { use(font) }
                        }
                    }
                }
            }
        }
    }
    }
}

/** The chosen font's sample, on the terminal's black: a line in the font, and what it is at which size. */
@Composable
private fun FontSample(font: TerminalFont, face: FontFamily?, size: String) {
    val c = VeloTheme.surface
    val shape = rounded(VeloTheme.radii.group)
    Column(
        Modifier.fillMaxWidth().testTag("font-sample").clip(shape).background(VeloTheme.colors.backdrop.surface)
            .border(VeloTheme.sizes.edge, c.divider, shape).padding(VeloTheme.space.s16),
        verticalArrangement = Arrangement.spacedBy(VeloTheme.medium(6.dp)),
    ) {
        Text("Aa 0O 1Il  ->  {code}", Modifier.fillMaxWidth().testTag("font-sample-text"), style = textAt(17f).copy(fontFamily = face),
            color = c.text, maxLines = 1, overflow = TextOverflow.Clip, softWrap = false)
        Text("${font.label} · $size px in the terminal", Modifier.fillMaxWidth().testTag("font-sample-caption"), style = VeloTheme.type.caption, color = c.secondary)
    }
}

/** A font's row: its name drawn in its own face, what it is under it, and a check on the chosen one. */
@Composable
private fun FontRow(font: TerminalFont, face: FontFamily?, checked: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val c = VeloTheme.surface
    val id = fontTagId(font.label)
    val note = fontNote(font)
    Row(
        Modifier.fillMaxWidth().testTag("font-row-$id").heightIn(min = VeloTheme.medium(52.dp)).clip(rounded(VeloTheme.medium(10.dp)))
            .background(if (checked) c.selected else Color.Transparent)
            .selectable(checked, enabled = enabled, role = Role.RadioButton, onClick = onClick).padding(horizontal = VeloTheme.space.s12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(VeloTheme.space.s12),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(VeloTheme.medium(1.dp))) {
            Text(font.label, Modifier.fillMaxWidth().testTag("font-name-$id"), style = VeloTheme.type.body.copy(fontFamily = face),
                color = if (enabled) c.text else c.disabled, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (note != null) Text(note, Modifier.fillMaxWidth().testTag("font-note-$id"), style = textAt(12f), color = c.secondary)
        }
        if (checked) Icon(Icons.Default.Check, null, Modifier.testTag("font-check-$id").size(VeloTheme.space.s20), tint = c.accent)
    }
}

/**
 * The frame of a group drawn a part at a time: a line of [line] with [radius] at its corners,
 * [padding] inside it. A part draws its sides, the top when it is the first and the bottom when it is the last.
 */
internal class GroupFrame(val line: Color, val radius: Dp, val edge: Dp, val padding: Dp) {
    val inset: Dp get() = padding + edge

    fun draw(scope: androidx.compose.ui.graphics.drawscope.DrawScope, first: Boolean, last: Boolean) = scope.clipRect {
        val r = radius.toPx()
        val w = edge.toPx()
        val top = if (first) 0f else -r
        val bottom = if (last) size.height else size.height + r
        drawPath(Path().apply { addRoundRect(RoundRect(w / 2, top + w / 2, size.width - w / 2, bottom - w / 2, CornerRadius(r - w / 2))) }, line, style = Stroke(w))
    }
}
