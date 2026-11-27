package io.github.yhzion.velo.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.yhzion.velo.ui.theme.MenuButton
import io.github.yhzion.velo.ui.theme.SelectMenu
import io.github.yhzion.velo.ui.theme.SettingRow

/**
 * What picking [choice] in the setting's menu saves: null for the corner already [shown], so that a user who never
 * chose and picks the default keeps following the default, as the palette, TalkBack and the drag do.
 */
fun pickedCorner(choice: CapsuleCorner, shown: CapsuleCorner): CapsuleCorner? = choice.takeIf { it != shown }

/**
 * The setting of the corner of the pane area where minimized windows sit: its name and the [shown] corner, which a
 * menu changes. Only another corner than the shown one reaches [onPick] ([pickedCorner]).
 */
@Composable
internal fun CapsuleCornerRow(shown: CapsuleCorner, onPick: (CapsuleCorner) -> Unit) {
    var choosing by remember { mutableStateOf(false) }
    SettingRow("Minimized windows", tag = "appearance-minimized", saidByControl = true) {
        Box {
            MenuButton(shown.label, { choosing = true }, contentDescription = "Minimized windows: ${shown.label}", tag = "appearance-minimized-menu")
            SelectMenu(choosing, { choosing = false }, CapsuleCorner.entries, shown, { it.label }, onPick = { choice ->
                pickedCorner(choice, shown)?.let(onPick)
            })
        }
    }
}
