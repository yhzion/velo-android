package io.github.yhzion.velo.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toSize
import io.github.yhzion.velo.ui.theme.Feel
import io.github.yhzion.velo.ui.theme.VeloTheme
import io.github.yhzion.velo.ui.theme.feel
import io.github.yhzion.velo.ui.theme.glass

/** The height of a minimized pane's capsule: Android's least touch target, the height of a menu item. */
val MinimizedCapsuleHeight: Dp @Composable get() = VeloTheme.sizes.menuItem

/** How much larger the row of capsules is drawn while a long press holds it. */
private const val LiftedRowScale = 1.05f

/**
 * The row of capsules while a long press holds it ([liftRow]). [row] is where the row was placed: its parent is the
 * box whose corners it stands in. [lifted] is true from the long press until the finger lets go, and [offset] is how
 * far the finger has taken the row from its corner, in px.
 */
private class RowLift {
    var row: LayoutCoordinates? = null
    var lifted by mutableStateOf(false)
    var offset by mutableStateOf(Offset.Zero)
}

/**
 * The minimized floating windows of the shown tab, one glass capsule each, side by side from the left
 * and scrolling sideways when they do not fit. A capsule shows a terminal mark and [Capsule.text] on
 * one line; a tap calls [onShow]. They take no taps while [enabled] is false (no connection to send it on).
 * The row stands at [corner] of the box it is placed in, [insets] from its edges. A long press on a capsule
 * lifts the row, with a haptic when [haptics] is on; the row follows the finger, and let go, it goes to the
 * corner whose place is nearest, chosen with [onMoveTo] ([dropCorner]). Let go nearest its own place, it stays.
 * A screen reader, which cannot drag, moves it to each of the other corners with [onMoveTo] by a custom
 * action on every capsule.
 */
@Composable
fun MinimizedPanes(
    capsules: List<Capsule>, enabled: Boolean, onShow: (Capsule) -> Unit,
    corner: CapsuleCorner, onMoveTo: (CapsuleCorner) -> Unit, haptics: Boolean, insets: CapsuleInsets,
    modifier: Modifier = Modifier,
) {
    val sizes = VeloTheme.sizes
    val colors = VeloTheme.surface
    val view = LocalView.current
    val shown by rememberUpdatedState(corner)
    val moveTo by rememberUpdatedState(onMoveTo)
    val feel by rememberUpdatedState(haptics)
    val edges by rememberUpdatedState(insets)
    val lift = remember { RowLift() }
    Row(
        // Its padding is what the drop's places ([capsulePlace]) are made of.
        modifier.padding(insets.padding(corner)).onGloballyPositioned { lift.row = it }
            .graphicsLayer {
                translationX = lift.offset.x
                translationY = lift.offset.y
                val scale = if (lift.lifted) LiftedRowScale else 1f
                scaleX = scale
                scaleY = scale
            }
            .horizontalScroll(rememberScrollState()).semantics { contentDescription = "Minimized panes"; isTraversalGroup = true },
        horizontalArrangement = Arrangement.spacedBy(sizes.gapCompact),
    ) {
        for (capsule in capsules) {
            var placed by remember { mutableStateOf<LayoutCoordinates?>(null) }
            Row(
                Modifier.height(MinimizedCapsuleHeight).widthIn(max = sizes.menuMinWidth).glass()
                    .alpha(if (enabled) 1f else 0.5f)
                    .onGloballyPositioned { placed = it }
                    .pointerInput(Unit) {
                        liftRow(lift, { placed },
                            onLift = { if (feel) view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }) { delta, row, box ->
                            val side = edges.side.toPx()
                            val top = edges.top.toPx()
                            dropCorner(shown, delta, viewConfiguration.touchSlop) {
                                capsulePlace(it, row.toSize(), box.toSize(), side, top)
                            }?.let {
                                // Let go at another corner, the move is felt as done, as a dropped tab card is (#309).
                                if (feel) view.feel(Feel.Confirm)
                                moveTo(it)
                            }
                        }
                    }
                    // No capsule takes a tap while the row is lifted: not the finger that holds it, nor another.
                    .clickable(enabled = enabled && !lift.lifted, role = Role.Button, onClick = { onShow(capsule) })
                    .semantics {
                        contentDescription = "Show ${capsule.text}"
                        customActions = CapsuleCorner.entries.filter { it != corner }.map { c ->
                            CustomAccessibilityAction(moveCornerLabel(c)) { onMoveTo(c); true }
                        }
                    }
                    .padding(horizontal = sizes.rowPadding),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(sizes.gapCompact),
            ) {
                Icon(Icons.Default.Terminal, null, Modifier.size(sizes.iconSmall), tint = colors.onGlass)
                Text(capsule.text, color = colors.onGlass, style = VeloTheme.type.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * One finger on a capsule, whose place is [capsule]. Held still for the system's long press, it lifts the whole row
 * ([onLift]); the row follows it. When it lets go, [onDrop] gets how far it took the row, the row's size and the
 * size of the box the row stands in, all in px.
 *
 * The capsule's click and the row's sideways scroll keep their touches. Until the long press has passed, this only
 * looks: it sees each change on the initial pass, before either of them, and consumes nothing. A finger that lifts
 * first is their tap, and one that moves past the touch slop first is their scroll ([capsuleFinger]). A change the
 * scroll consumed, seen again on the final pass, ends the wait too, and so does a down it took to stop a fling.
 * Once the row is lifted, every change of the gesture is consumed on the initial pass: the click and the scroll see
 * only consumed changes, and a consumed change cancels both. A cancel from the system puts the row back, with no
 * drop ([liftedFinger]).
 */
private suspend fun PointerInputScope.liftRow(
    lift: RowLift, capsule: () -> LayoutCoordinates?, onLift: () -> Unit, onDrop: (delta: Offset, row: IntSize, box: IntSize) -> Unit,
) {
    val slop = viewConfiguration.touchSlop
    val longPress = viewConfiguration.longPressTimeoutMillis
    awaitEachGesture {
        // An unconsumed down, like the click's: a touch that stops a thrown row is the scroll's.
        val down = awaitFirstDown(pass = PointerEventPass.Initial)
        var at = down.position
        val decided = withTimeoutOrNull(longPress) {
            var finger = CapsuleFinger.Holding
            while (finger == CapsuleFinger.Holding) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id }
                finger = if (change == null) CapsuleFinger.Taken else {
                    at = change.position
                    capsuleFinger(at.x - down.position.x, at.y - down.position.y, slop, change.pressed,
                        event.changes.count { it.pressed }, change.isConsumed)
                }
                // The same change after the click and the scroll saw it: a scroll that began keeps the finger.
                if (finger == CapsuleFinger.Holding &&
                    awaitPointerEvent(PointerEventPass.Final).changes.any { it.id == down.id && it.isConsumed }
                ) finger = CapsuleFinger.Taken
            }
            finger
        }
        // A tap, a scroll, or a touch something else took: it stays theirs.
        if (decided != null) return@awaitEachGesture
        val here = capsule()?.takeIf { it.isAttached } ?: return@awaitEachGesture
        val row = lift.row?.takeIf { it.isAttached } ?: return@awaitEachGesture
        val box = row.parentLayoutCoordinates ?: return@awaitEachGesture
        // Another finger, on another capsule, holds the row already.
        if (lift.lifted) return@awaitEachGesture
        val from = box.localPositionOf(here, at)
        lift.lifted = true
        onLift()
        try {
            var end = LiftedFinger.Follow
            var finger = from
            while (end == LiftedFinger.Follow) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id }
                end = if (change == null || !here.isAttached || !row.isAttached || !box.isAttached) LiftedFinger.PutBack
                    else liftedFinger(change.pressed, change.isConsumed)
                // Before the click and the scroll see it: neither acts on the finger that holds the row.
                event.changes.forEach { it.consume() }
                if (change != null && end != LiftedFinger.PutBack) finger = box.localPositionOf(here, change.position)
                if (end == LiftedFinger.Follow) lift.offset = finger - from
            }
            if (end == LiftedFinger.Drop) onDrop(finger - from, row.size, box.size)
        } finally {
            // Dropped, put back, or the capsule gone: the row stands at its corner again.
            lift.lifted = false
            lift.offset = Offset.Zero
        }
    }
}
