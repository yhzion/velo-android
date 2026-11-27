package io.github.yhzion.velo.ui

import android.os.Build
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A card of the overview whose panes are drawn as stills while the row moves: each pane's surface is copied into a
 * picture, which the app's window draws, and the surface's own layer is hidden until the row rests ([PaneSurfaceView.hold]).
 * SurfaceFlinger moves every layer on each frame of a motion, and with more than two cards' layers on the screen it
 * composes the frames on the GPU (2026-11-27, the LG Velvet in landscape: 290 of 490 frames of four swipes each way). With
 * stills it composes the window alone.
 * [moving] reads the overview's motion ([OverviewState.rowStill]); [scale] is the card's scale now, which sizes the picture;
 * [onScreen] whether the card is in the pane area now: its panes are copied first. A card out of it is copied after them,
 * for the motion may bring it in, and a live layer moved off the screen still costs SurfaceFlinger each frame.
 */
@Stable
internal class PaneStill(val moving: () -> Boolean, val scale: () -> Float, val onScreen: () -> Boolean) {
    companion object {
        /** A surface can stay while its layer is hidden only from API 34 on; before it, the cards move live. */
        val canHold: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
    }
}

/**
 * One pane at a time copies its surface or shows its layer again, one in each frame. A copy holds the render thread for 5 to
 * 15 ms on the LG Velvet, and a layer shown again for about as long: all the cards' at once took two or three frames each
 * time the row started or stopped.
 */
private val stillTurns = Mutex()

/** The card a pane is drawn in, when that card's panes may be stills; null outside the overview. */
internal val LocalPaneStill = staticCompositionLocalOf<PaneStill?> { null }

/** How long the row's scroll stays unchanged before the row is at rest and the panes are live again, in ms. */
internal const val ROW_QUIET_MS = 120L

/**
 * Whether the row is moving, from the times its scroll changed: from the first change until [quietMs] pass with none.
 * Plain, on the main thread; [OverviewState] says it to composition as it starts and ends.
 */
internal class RowMotionClock(private val quietMs: Long = ROW_QUIET_MS) {
    private var last = 0L
    var moving = false
        private set

    /** The scroll changed at [now], in ms: true when that starts a motion. */
    fun changed(now: Long): Boolean {
        last = now
        if (moving) return false
        moving = true
        return true
    }

    /** How long from [now] until the motion has been quiet for [quietMs], in ms. 0 ends the motion. */
    fun quietIn(now: Long): Long {
        val left = last + quietMs - now
        if (left <= 0) moving = false
        return left.coerceAtLeast(0)
    }
}

/**
 * The last still of a pane and the pane's swaps when it was copied ([PaneStillOver]). Plain, on the main thread.
 */
internal class LastStill<T : Any> {
    private var image: T? = null
    private var swaps = -1L
    private var size = 0 to 0

    /** The kept still, when the pane has taken no frame since it was copied ([swaps] the same) and it is of [size]. */
    fun unchanged(swaps: Long, size: Pair<Int, Int>): T? =
        image?.takeIf { swaps >= 0 && swaps == this.swaps && size == this.size }

    /** [image] was copied when the pane had taken [swaps] frames, at [size]. */
    fun keep(image: T, swaps: Long, size: Pair<Int, Int>) {
        this.image = image
        this.swaps = swaps
        this.size = size
    }

    fun forget() {
        image = null
        swaps = -1
    }
}

/**
 * The still of one pane of [card] ([PaneStill]): while [preview] and the row moves, a copy of [view] drawn over it, and its
 * layer hidden once the copy is there. A copy that fails (no frame yet, the surface went) leaves the pane live. At rest the
 * layer shows again, with the core's last frame; the copy stays over it for two frames more, until that layer is on the
 * screen. The motion is followed in an effect, not in composition: the row starting or stopping composes nothing.
 * The last copy is kept while the pane is a preview, with the pane's swaps when it was made ([PaneSurfaceView.swaps]): a
 * pane that has taken no frame since, at the same size, is drawn from it again and not copied.
 */
@Composable
internal fun PaneStillOver(view: PaneSurfaceView?, preview: Boolean, card: PaneStill) {
    var still by remember(view) { mutableStateOf<ImageBitmap?>(null) }
    val last = remember(view) { LastStill<ImageBitmap>() }
    val previewNow by rememberUpdatedState(preview)
    LaunchedEffect(view, card) {
        val v = view ?: return@LaunchedEffect
        // Where the layer cannot be hidden a copy over it would only cost: the pane moves live.
        if (!PaneStill.canHold) return@LaunchedEffect
        snapshotFlow { previewNow && card.moving() }.collectLatest { holding ->
            if (holding) {
                if (v.held) return@collectLatest
                // A frame later than the cards on the screen, which are then before it in the turns.
                if (!card.onScreen()) withFrameNanos {}
                val scale = card.scale().coerceIn(0.1f, 1f)
                val image = stillTurns.withLock {
                    withFrameNanos {}
                    // Read before the copy: a frame that comes meanwhile makes the next motion copy again.
                    val swaps = v.swaps()
                    val size = v.stillSize(scale)
                    last.unchanged(swaps, size) ?: v.still(scale)?.asImageBitmap()?.also { last.keep(it, swaps, size) }
                } ?: return@collectLatest
                still = image
                v.hold(true)
            } else {
                // No longer a preview: the pane is shown or goes, and the copy is of no more use.
                if (!previewNow) last.forget()
                if (v.held) stillTurns.withLock {
                    withFrameNanos {}
                    v.hold(false)
                }
                if (still == null) return@collectLatest
                withFrameNanos {}
                withFrameNanos {}
                still = null
            }
        }
    }
    // Read while drawing: a still that comes or goes redraws the pane, and composes nothing.
    Spacer(Modifier.fillMaxSize().drawBehind {
        val s = still ?: return@drawBehind
        drawImage(s, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()))
    })
}
