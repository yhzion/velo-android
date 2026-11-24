package io.github.yhzion.velo.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import io.github.yhzion.velo.HostConnection
import io.github.yhzion.velo.ui.theme.VeloTextField
import io.github.yhzion.velo.ui.theme.VeloTheme
import io.github.yhzion.velo.ui.theme.RowIconButton
import kotlinx.coroutines.delay

@Composable
internal fun PaneSearchBar(conn: HostConnection, block: String, label: String, onClose: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var result by remember(query) { mutableLongStateOf(0) }
    val pending = result >= 0 && (result and (1L shl 62)) != 0L
    val count = ((result ushr 32) and 0x3fffffff).toInt()
    val position = result.toInt()
    fun move(step: Int) {
        if (query.isNotEmpty() && result >= 0 && count > 0) result = conn.find(block, query, step)
    }
    LaunchedEffect(conn, block, query) {
        do {
            result = conn.find(block, query)
            if (query.isEmpty()) break
            delay(if ((result and (1L shl 62)) != 0L && result >= 0) 16 else 200)
        } while (true)
    }
    DisposableEffect(conn, block) { onDispose { conn.find(block, "") } }
    val focus = remember { FocusRequester() }
    val ime = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focus.requestFocus(); ime?.show() }
    Column(Modifier.fillMaxWidth().onPreviewKeyEvent {
        if (it.key != Key.Escape) return@onPreviewKeyEvent false
        if (it.type == KeyEventType.KeyUp) onClose()
        true
    }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            VeloTextField(query, { query = it }, Modifier.weight(1f).focusRequester(focus).onPreviewKeyEvent {
                if (it.key != Key.Enter && it.key != Key.NumPadEnter) return@onPreviewKeyEvent false
                if (it.type == KeyEventType.KeyUp) move(if (it.isShiftPressed) -1 else 1)
                true
            }, placeholder = "Find in $label",
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { move(1) }))
            RowIconButton(Icons.Default.KeyboardArrowUp, "Previous match", { move(-1) }, enabled = result >= 0 && count > 1)
            RowIconButton(Icons.Default.KeyboardArrowDown, "Next match", { move(1) }, enabled = result >= 0 && count > 1)
            RowIconButton(Icons.Default.Close, "Close search", onClose)
        }
        if (query.isNotEmpty()) {
            val status = when {
                result < 0 -> "Search unavailable"
                pending -> "Searching…"
                count == 0 -> "No matches"
                else -> "$position of $count"
            }
            Text(status, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = VeloTheme.type.caption, color = VeloTheme.colors.backdrop.secondary)
        }
    }
}
