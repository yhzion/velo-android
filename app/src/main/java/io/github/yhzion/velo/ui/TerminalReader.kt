package io.github.yhzion.velo.ui

import android.graphics.Typeface
import android.widget.TextView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import io.github.yhzion.velo.HostConnection
import io.github.yhzion.velo.ui.theme.DialogSurface
import io.github.yhzion.velo.ui.theme.ProvideHaptics
import io.github.yhzion.velo.ui.theme.VeloTheme

/** A selectable snapshot of one pane. Replacing the text resets Android's reading cursor. */
@Composable
internal fun TerminalReader(conn: HostConnection, block: String, label: String, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose) {
        val context = LocalContext.current
        val snapshot = remember(conn, block) { TerminalReaderSnapshot { conn.viewportText(block) } }
        val body = remember(context, snapshot) {
            TextView(context).apply {
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
                text = snapshot.text
            }
        }
        // This window has haptics of its own: the setting gates them here too (#309).
        ProvideHaptics {
            DialogSurface(
                title = label,
                confirmText = "Done", onConfirm = onClose,
                cancelText = "Refresh", onCancel = { if (snapshot.refresh()) body.text = snapshot.text },
            ) {
                val color = VeloTheme.surface.text.toArgb()
                AndroidView(factory = { body }, update = { it.setTextColor(color) }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
