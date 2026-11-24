package io.github.yhzion.velo.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import io.github.yhzion.velo.ui.theme.VeloTheme
import io.github.yhzion.velo.ui.theme.rounded

/**
 * What a pane's handle does. [onTap] opens the menu of the pane that the block names. [onLongPress]
 * starts moving that pane: its second argument is where the finger is, in root coordinates, so a
 * caller that draws in other coordinates converts it into its own. [onMoveToTab] is the same move
 * without a drag (pick mode): the handle's action for a screen reader, which cannot long-press and drag.
 */
internal class PaneHandles(
    val onTap: (String) -> Unit,
    val onLongPress: (block: String, root: Offset) -> Unit,
    val onMoveToTab: ((String) -> Unit)? = null,
)

// The divider between two splits has a touch band that reaches 16dp into each pane. The handle's
// touch target starts below it, so that no touch is shared with dragging a divider.
private val HandleTop = 16.dp
private val HandleTarget = DpSize(48.dp, 32.dp)
private val HandlePill = DpSize(32.dp, 4.dp)
/** How far down a pane's top edge the handle's touch target reaches; what a row of capsules at a top corner stays below. */
internal val PaneHandleStrip = HandleTop + HandleTarget.height
private const val HandleAlpha = 0.45f

/**
 * The handle near the top of a pane: a tap opens the pane's menu and a long press starts moving it.
 * The pill is small to stay out of the terminal's way, and the touch target around it is larger.
 * It is the last child of the pane, so that it lies above the terminal view and takes its touches.
 * [label] is the pane's name; [haptics] is the Haptic feedback setting. [onMoveToTab], when given, is
 * the handle's `Move Pane to Tab…` action for a screen reader.
 */
@Composable
internal fun BoxScope.PaneHandle(label: String, haptics: Boolean, onTap: () -> Unit, onLongPress: (Offset) -> Unit, onMoveToTab: (() -> Unit)? = null) {
    val view = LocalView.current
    val tap by rememberUpdatedState(onTap)
    val longPress by rememberUpdatedState(onLongPress)
    val moveToTab by rememberUpdatedState(onMoveToTab)
    val feel by rememberUpdatedState(haptics)
    var placed by remember { mutableStateOf<LayoutCoordinates?>(null) }
    Box(
        Modifier.align(Alignment.TopCenter).offset(y = HandleTop).size(HandleTarget)
            .onGloballyPositioned { placed = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { tap() },
                    onLongPress = { at ->
                        if (feel) view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        // The press is in the target's own coordinates; the caller wants the root's.
                        longPress(placed?.takeIf { it.isAttached }?.localToRoot(at) ?: at)
                    },
                )
            }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = "Move $label"
                // The name is the move handle's, but a double tap opens the pane's menu: the action says so.
                onClick(label = "Pane menu") { tap(); true }
                if (onMoveToTab != null) customActions = listOf(CustomAccessibilityAction("Move Pane to Tab…") { moveToTab?.let { it(); true } ?: false })
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(HandlePill).background(VeloTheme.colors.backdrop.text.copy(alpha = HandleAlpha), rounded(HandlePill.height / 2)))
    }
}
