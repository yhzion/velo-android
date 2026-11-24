package io.github.yhzion.velo.ui

import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import io.github.yhzion.velo.ui.theme.PaletteRow
import io.github.yhzion.velo.ui.theme.VeloPalette
import io.github.yhzion.velo.ui.theme.VeloTheme

/** One entry of the command palette. `enabled` false keeps it listed but greyed out; a `note` says why, under the title. */
data class PaletteCommand(val title: String, val enabled: Boolean = true, val note: String? = null, val run: () -> Unit)

/** Type to filter, tap or press Enter to run the first match. */
@Composable
fun CommandPalette(commands: List<PaletteCommand>, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val matches = commands.filter { query.isBlank() || it.title.contains(query.trim(), true) }
    fun run(c: PaletteCommand) { if (c.enabled) { onDismiss(); c.run() } }

    VeloPalette(query, { query = it }, onGo = { matches.firstOrNull { it.enabled }?.let(::run) }, onDismissRequest = onDismiss) {
        // Titles repeat (two tabs named "zsh", two hosts with one label): no title keys.
        items(matches) { c -> PaletteRow(c.title, onClick = { run(c) }, enabled = c.enabled, note = c.note) }
        if (matches.isEmpty()) item {
            Text("No matching command", Modifier.padding(VeloTheme.sizes.menuPadding), style = VeloTheme.type.body,
                color = VeloTheme.surface.secondary)
        }
    }
}
