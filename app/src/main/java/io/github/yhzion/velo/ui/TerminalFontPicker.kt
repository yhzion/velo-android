package io.github.yhzion.velo.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import io.github.yhzion.velo.Settings
import io.github.yhzion.velo.GlyphRasterizer
import io.github.yhzion.velo.TerminalFont
import io.github.yhzion.velo.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The Font row of Appearance: the current font, and a chevron because it opens the Terminal Font level of the sheet. */
@Composable
internal fun TerminalFontSetting(settings: Settings, onOpen: () -> Unit) {
    val sizes = VeloTheme.sizes
    val c = VeloTheme.surface
    Row(
        Modifier.fillMaxWidth().heightIn(min = sizes.row).clickable(role = Role.Button, onClick = onOpen),
        horizontalArrangement = Arrangement.spacedBy(sizes.rowGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Font", color = c.text)
        Text(settings.font.label, Modifier.weight(1f), color = c.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(sizes.iconSmall), tint = c.secondary)
    }
}

/**
 * The Terminal Font level of the settings sheet: search, the monospaced/all filter, a preview,
 * the fonts, and importing one. A tapped font is the terminal's font at once, as every other
 * setting of the sheet is; the sheet's Back returns to Appearance.
 */
@Composable
internal fun TerminalFontSettings(host: SettingsHost, modifier: Modifier = Modifier) {
    val settings = host.settings
    val sizes = VeloTheme.sizes
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
    // One scroll owner: with the IME up there may not be height left over for a nested list.
    // Keep import above the results so it is reachable even with hundreds of installed fonts.
    LazyColumn(modifier.windowInsetsPadding(WindowInsets.screenIme), contentPadding = PaddingValues(bottom = sizes.overlayPadding)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(sizes.menuPadding)) {
                VeloTextField(query, { query = it }, placeholder = "Search fonts")
                VeloSegmented(listOf(false, true), all, { if (it) "All fonts" else "Monospaced" }, onPick = { all = it })
                GroupCard {
                    Column(Modifier.padding(vertical = sizes.menuPadding)) {
                        Text(selected.label, style = VeloTheme.type.bodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Aa 0O 1Il  ->  {code}  📱 😀", fontFamily = preview, style = VeloTheme.type.body)
                    }
                }
                VeloButton("Import font…", { importer.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth(), enabled = !loading)
                error?.let { Text(it, color = VeloTheme.surface.danger) }
            }
        }
        if (matches.isEmpty()) item { Text(if (loading) "Loading fonts…" else "No matching fonts") }
        items(matches, key = { it.id }) { font ->
            val face by produceState(faces[font.id], font.id) { value = runCatching { faceOf(font) }.getOrNull() }
            FontRow(font, face, checked = selected.id == font.id, enabled = !loading) { use(font) }
        }
    }
}

/** Each font's name is drawn in its own face. */
@Composable
private fun FontRow(font: TerminalFont, face: FontFamily?, checked: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val c = VeloTheme.surface
    ListRow(font.label, onClick, selected = checked, enabled = enabled, titleFont = face,
        trailing = { if (checked) Icon(Icons.Default.Check, null, tint = c.accent) })
}
