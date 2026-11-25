package io.github.yhzion.velo.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import io.github.yhzion.velo.LicenseComponent
import io.github.yhzion.velo.LicenseText
import io.github.yhzion.velo.OpenSourceLicenses
import io.github.yhzion.velo.licenseBlocks
import io.github.yhzion.velo.reflowLicense
import io.github.yhzion.velo.licenseGroups
import io.github.yhzion.velo.licenseSubtitle
import io.github.yhzion.velo.readNotice
import io.github.yhzion.velo.ui.theme.GroupCard
import io.github.yhzion.velo.ui.theme.GroupDivider
import io.github.yhzion.velo.ui.theme.GroupHeader
import io.github.yhzion.velo.ui.theme.ListRow
import io.github.yhzion.velo.ui.theme.VeloTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** About > Open source licenses: the components by group, each opening its page ([open] takes its id). */
@Composable
internal fun OpenSourceLicenseList(open: (String) -> Unit) {
    Text(
        "Velo's own code is MIT licensed. It is built on the projects below, each under its own license.",
        Modifier.padding(top = VeloTheme.sizes.menuPadding), style = VeloTheme.type.caption, color = VeloTheme.surface.secondary,
    )
    for ((group, components) in licenseGroups(OpenSourceLicenses.all)) {
        GroupHeader(group.title)
        LicenseRows(components, open)
    }
}

@Composable
private fun LicenseRows(components: List<LicenseComponent>, open: (String) -> Unit) {
    GroupCard {
        components.forEachIndexed { index, c ->
            if (index > 0) GroupDivider()
            ListRow(c.name, onClick = { open(c.id) }, subtitle = licenseSubtitle(c).ifEmpty { null })
        }
    }
}

/** A notice as the page shows it: its blocks, or null when the APK could not give it. */
private class LoadedNotice(val text: LicenseText, val blocks: List<String>?)

/**
 * One component's page: its license and version, its project home, then the texts bundled in
 * the APK, in a monospaced font and selectable. A collection lists its [LicenseComponent.parts]
 * instead, each opening its own page through [open]. The page scrolls itself, a block at a time.
 */
@Composable
internal fun LicenseDetail(component: LicenseComponent, open: (String) -> Unit, modifier: Modifier) {
    val sizes = VeloTheme.sizes
    val c = VeloTheme.surface
    val context = LocalContext.current
    val notices by produceState<List<LoadedNotice>?>(null, component.id) {
        value = withContext(Dispatchers.IO) {
            component.texts.map { text ->
                val blocks = try { licenseBlocks(reflowLicense(readNotice(text.asset, context.assets::open))) } catch (_: IOException) { null }
                LoadedNotice(text, blocks)
            }
        }
    }
    val mono = VeloTheme.type.caption.copy(fontFamily = FontFamily.Monospace)
    SelectionContainer(modifier) {
        LazyColumn {
            item("about") {
                DisableSelection {
                    Column(Modifier.padding(top = sizes.menuPadding)) {
                        val subtitle = licenseSubtitle(component)
                        if (subtitle.isNotEmpty()) Text(subtitle, style = VeloTheme.type.caption, color = c.secondary)
                        component.url?.let { ProjectHome(it) }
                        if (component.parts.isNotEmpty()) {
                            GroupHeader(component.name)
                            LicenseRows(component.parts, open)
                        }
                    }
                }
            }
            val loaded = notices
            if (component.texts.isNotEmpty() && loaded == null) item("loading") {
                Text("Loading…", Modifier.padding(vertical = sizes.menuPadding), color = c.secondary)
            }
            for ((index, notice) in loaded.orEmpty().withIndex()) {
                if (component.texts.size > 1) item("title:$index") { DisableSelection { GroupHeader(notice.text.title) } }
                else item("gap:$index") { Spacer(Modifier.height(sizes.menuPadding)) }
                val blocks = notice.blocks
                if (blocks == null) item("error:$index") { Text("Couldn’t read ${notice.text.asset} from the app.", color = c.secondary) }
                else items(blocks.size, key = { "text:$index:$it" }) { Text(blocks[it], style = mono, color = c.text) }
            }
            item("end") { Spacer(Modifier.height(sizes.overlayPadding)) }
        }
    }
}

/** The row that opens the project's page in the browser; the address stays readable when no browser takes it. */
@Composable
private fun ProjectHome(url: String) {
    val uri = LocalUriHandler.current
    var unopened by remember(url) { mutableStateOf(false) }
    Spacer(Modifier.height(VeloTheme.sizes.menuPadding))
    GroupCard {
        ListRow("Project home", onClick = {
            try {
                uri.openUri(url)
            } catch (_: IllegalArgumentException) {
                unopened = true
            }
        }, subtitle = url)
    }
    if (unopened) SelectionContainer {
        Text("Open in a browser: $url", Modifier.padding(top = VeloTheme.sizes.cellInset), color = VeloTheme.surface.secondary)
    }
}
