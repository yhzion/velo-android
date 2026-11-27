package io.github.yhzion.velo.ui.theme

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

// The picture behind an overlay. The Fold8 gives apps no window blur
// (ro.surface_flinger.supports_background_blur=0), and a Compose layer cannot blur a SurfaceView
// under it: the terminals are separate surfaces behind the window. So the app copies their
// pixels itself (PixelCopy) into a small bitmap, which the overlay draws blurred (Glass.kt).

/**
 * What the overlays' blur is made from. The screens provide it once ([LocalBackdropSource]);
 * nothing here knows what the views show.
 */
@Stable
class BackdropSource(
    /** The surface views that can be behind an overlay, the one drawn on top last. Asked at every refresh. */
    val views: () -> List<SurfaceView>,
    /**
     * A window whose own pixels go under the views': the window that holds them, so that what
     * is drawn beside the views is in the picture too. Null where the overlay is drawn in that
     * same window: it would copy itself.
     */
    val window: Window? = null,
)

/** The source of the overlays below this point; null draws them without a blur. */
val LocalBackdropSource = staticCompositionLocalOf<BackdropSource?> { null }

/** Whether this device can blur a picture the app holds (RenderEffect, Android 12). Below it overlays are opaque. */
val canBlur: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/** Screen pixels per bitmap pixel. The blur is several times wider than this, so no pixel of the bitmap shows. */
internal const val BackdropScale = 8

/** Milliseconds between two copies while an overlay is open; docs/design-tokens.md says why this rate. */
internal const val BackdropRefreshMillis = 100L

/** How long the last copy took, request to drawn bitmap, in milliseconds: for measuring the rate's cost. */
@Volatile
internal var lastBackdropCopyMillis = 0f
    private set

/** How long the overlay opened last waited for its first picture, in milliseconds. */
@Volatile
internal var lastBackdropWaitMillis = 0f
    private set

/** One view's share of a backdrop: [source], in the pixels of the view's buffer, is copied to [dest] in the bitmap. */
data class BackdropPart(val source: IntRect, val dest: IntRect)

/** The size of the bitmap that holds [target] at one pixel per [scale] screen pixels. */
fun backdropSize(target: IntRect, scale: Int): IntSize =
    IntSize(maxOf(1, (target.width + scale - 1) / scale), maxOf(1, (target.height + scale - 1) / scale))

/**
 * The part of a view that is behind [target], and where it goes in the bitmap of [target]
 * ([backdropSize]). [view] and [target] are in screen pixels; the view draws into a buffer of
 * [bufferWidth] x [bufferHeight] pixels, which need not be its size on screen. Null when the
 * view is not behind the target.
 */
fun backdropPart(target: IntRect, view: IntRect, bufferWidth: Int, bufferHeight: Int, scale: Int): BackdropPart? {
    val over = target.intersect(view)
    if (over.width <= 0 || over.height <= 0 || bufferWidth <= 0 || bufferHeight <= 0) return null
    fun toBuffer(screen: Int, origin: Int, size: Int, buffer: Int) = ((screen - origin).toLong() * buffer / size).toInt()
    val left = toBuffer(over.left, view.left, view.width, bufferWidth)
    val top = toBuffer(over.top, view.top, view.height, bufferHeight)
    val source = IntRect(
        left, top,
        maxOf(left + 1, toBuffer(over.right, view.left, view.width, bufferWidth)),
        maxOf(top + 1, toBuffer(over.bottom, view.top, view.height, bufferHeight)),
    )
    val size = backdropSize(target, scale)
    val right = minOf(size.width, (over.right - target.left + scale - 1) / scale)
    val bottom = minOf(size.height, (over.bottom - target.top + scale - 1) / scale)
    val dest = IntRect(minOf((over.left - target.left) / scale, right - 1), minOf((over.top - target.top) / scale, bottom - 1), right, bottom)
    return BackdropPart(source, dest)
}

/**
 * Where a menu of size [menu] opens for an [anchor] (both in the pixels of a window of size
 * [window]): under the anchor from its left edge; above it when it does not fit below; always
 * [margin] inside the window when it fits there at all.
 */
fun menuPosition(anchor: IntRect, menu: IntSize, window: IntSize, margin: Int): IntOffset {
    val x = anchor.left.coerceIn(margin, maxOf(margin, window.width - margin - menu.width))
    val below = anchor.bottom + margin
    val above = anchor.top - margin - menu.height
    val y = when {
        below + menu.height <= window.height - margin -> below
        above >= margin -> above
        else -> maxOf(margin, window.height - margin - menu.height)
    }
    return IntOffset(x, y)
}

/** The side of its anchor a popover that leaves the anchor in view opens on ([above] or below it), and the [room] there. */
data class ClearPlace(val above: Boolean, val room: Int)

/**
 * Where a popover opens that must not cover its [anchor] (in the pixels of a window of size [window]): above it,
 * unless there is more room below. The popover is then no higher than the [ClearPlace.room] of that side, [margin]
 * from the anchor and from the window's edge, and scrolls what does not fit (#182). Null when neither side has the
 * [least] height the popover needs to be of use: it is then placed as a menu is ([menuPosition]), over the anchor.
 */
fun clearPlace(anchor: IntRect, window: IntSize, margin: Int, least: Int = 0): ClearPlace? {
    val above = anchor.top - 2 * margin
    val below = window.height - anchor.bottom - 2 * margin
    val place = if (above >= below) ClearPlace(true, maxOf(0, above)) else ClearPlace(false, maxOf(0, below))
    return place.takeIf { it.room >= least && it.room > 0 }
}

/** Where a popover of size [menu] opens on the side [place] gives it: from the anchor's left edge, as [menuPosition]. */
fun clearPosition(anchor: IntRect, menu: IntSize, window: IntSize, margin: Int, place: ClearPlace): IntOffset {
    val x = anchor.left.coerceIn(margin, maxOf(margin, window.width - margin - menu.width))
    return IntOffset(x, if (place.above) anchor.top - margin - menu.height else anchor.bottom + margin)
}

private fun View.onScreen(): IntRect {
    val at = IntArray(2)
    getLocationOnScreen(at)
    return IntRect(at[0], at[1], at[0] + width, at[1] + height)
}

private fun IntRect.toRect() = Rect(left, top, right, bottom)

/** Copies what is behind a rectangle of the screen into small bitmaps. Main thread only. */
private class BackdropCopier(private val fill: Int) {
    private val handler = Handler(Looper.getMainLooper())
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** One bitmap per thing copied (the window, then each view), kept from copy to copy while its size stays. */
    private val slots = ArrayList<Bitmap?>()
    private var front: Bitmap? = null
    private var back: Bitmap? = null

    private class Copied(val part: BackdropPart, val bitmap: Bitmap)

    /** A copy of what [source] shows behind [target], or null when nothing could be copied. */
    suspend fun copy(source: BackdropSource, target: IntRect): Bitmap? {
        val began = System.nanoTime()
        val window = source.window?.let { window ->
            val decor = window.decorView
            backdropPart(target, decor.onScreen(), decor.width, decor.height, BackdropScale)?.let { part ->
                fetch(0, part) { bitmap, done -> PixelCopy.request(window, part.source.toRect(), bitmap, done, handler) }
            }
        }
        val views = source.views().mapIndexedNotNull { i, view ->
            if (!view.isAttachedToWindow || !view.holder.surface.isValid) return@mapIndexedNotNull null
            val frame = view.holder.surfaceFrame
            backdropPart(target, view.onScreen(), frame.width(), frame.height(), BackdropScale)?.let { part ->
                fetch(i + 1, part) { bitmap, done -> PixelCopy.request(view, part.source.toRect(), bitmap, done, handler) }
            }
        }
        if (window == null && views.isEmpty()) return null

        val size = backdropSize(target, BackdropScale)
        // Two bitmaps take turns, so the one on screen is never written to.
        val out = back?.takeIf { it.width == size.width && it.height == size.height }
            ?: Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(fill)
        // A surface view is behind its window, which is clear where the view shows: the views go
        // under the window's picture. A view the window's picture is opaque over is one the
        // window's copy did not see through (or one that is drawn on top): it goes over.
        val (under, over) = views.partition { window != null && window.seesThrough(it.part.dest) }
        for (v in under) canvas.drawBitmap(v.bitmap, null, v.part.dest.toRect(), paint)
        if (window != null) canvas.drawBitmap(window.bitmap, null, window.part.dest.toRect(), paint)
        for (v in over) canvas.drawBitmap(v.bitmap, null, v.part.dest.toRect(), paint)
        back = front
        front = out
        lastBackdropCopyMillis = (System.nanoTime() - began) / 1e6f
        return out
    }

    /** Whether this copy of a window is clear somewhere inside [dest] (a rectangle of the backdrop bitmap). */
    private fun Copied.seesThrough(dest: IntRect): Boolean {
        for (fx in 1..3) for (fy in 1..3) {
            val x = dest.left + dest.width * fx / 4 - part.dest.left
            val y = dest.top + dest.height * fy / 4 - part.dest.top
            if (x in 0 until bitmap.width && y in 0 until bitmap.height && (bitmap.getPixel(x, y) ushr 24) < 255) return true
        }
        return false
    }

    private suspend fun fetch(slot: Int, part: BackdropPart, request: (Bitmap, PixelCopy.OnPixelCopyFinishedListener) -> Unit): Copied? {
        while (slots.size <= slot) slots.add(null)
        val bitmap = slots[slot]?.takeIf { it.width == part.dest.width && it.height == part.dest.height }
            ?: Bitmap.createBitmap(part.dest.width, part.dest.height, Bitmap.Config.ARGB_8888).also { slots[slot] = it }
        val ok = suspendCancellableCoroutine { next ->
            try {
                request(bitmap) { result -> if (next.isActive) next.resume(result == PixelCopy.SUCCESS) }
            } catch (e: IllegalArgumentException) {
                // The surface went away between the check and the request.
                next.resume(false)
            }
        }
        return if (ok) Copied(part, bitmap) else null
    }
}

/**
 * The picture behind [target] (a rectangle of the screen that may move or be unknown at first),
 * copied again every [BackdropRefreshMillis] for as long as the caller is on screen. Null until
 * the first copy arrives, and where nothing can be copied.
 */
@Composable
internal fun rememberBackdrop(source: BackdropSource, fill: Int, target: () -> IntRect?): State<ImageBitmap?> {
    val image = remember(source) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(source, fill) {
        val copier = BackdropCopier(fill)
        val began = System.nanoTime()
        while (isActive) {
            val at = target()
            if (at != null && at.width > 0 && at.height > 0) {
                val first = image.value == null
                // A new wrapper each time: the two bitmaps alternate, and the state must see a change.
                image.value = copier.copy(source, at)?.asImageBitmap()
                if (first && image.value != null) lastBackdropWaitMillis = (System.nanoTime() - began) / 1e6f
                delay(BackdropRefreshMillis)
            } else {
                // Not laid out yet: look again at the next frame, so the first picture is not a refresh late.
                withFrameNanos { }
            }
        }
    }
    return image
}
