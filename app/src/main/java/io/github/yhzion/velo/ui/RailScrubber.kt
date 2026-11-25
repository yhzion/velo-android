package io.github.yhzion.velo.ui

import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.yhzion.velo.ui.theme.VeloTheme
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

/** A window the rail's wheel turns past: its tab's [id], the [name] the sidebar shows, and its program's [icon]. */
internal data class WheelWindow(val id: String, val name: String, val icon: String?)

/** What a screen reader calls the strip. */
internal const val WheelName = "Windows"

/** How long the strip's scroll samples are kept, in ms: a long drag collects no more, and the fling's speed is read from the last of them (#384). */
internal const val ScrollSampleKeepMs = 130L

// The rail's drum of titles. derived: the mockup the owner chose (v2).
/** One title's height on the drum. */
private val TitleHeight = 50.dp
/** The eye's distance from the drum's front title: titles turned away are drawn smaller. */
private val TitleEye = 240.dp
/** The lens behind the front title. */
private val LensHeight = 58.dp
private val LensInset = 6.dp
private val LensCorner = 18.dp
/** The marks at the strip's sides, level with the lens. */
private val NotchWidth = 3.dp
private val NotchHeight = 14.dp
/** The ring that fills while a press becomes a long press. */
private val ChargeRadius = 22.dp
private val ChargeStroke = 3.dp
/** Room under the drum for the count of windows. */
private val CountRoom = 26.dp
/** The ridges at the strip's sides: four to a title, the title's own one longer. */
private const val RidgesPerTitle = 4
private val RidgeInset = 4.dp
private val RidgeLong = 6.dp
private val RidgeShort = 4.dp

/**
 * The rail's free strip, between the keyboard and the keep-screen-on buttons (#304). A plain drag on it scrolls the
 * pane [pane] names, as a finger on the pane would ([onScroll], in px); let go at a flick, that pane keeps the
 * finger's speed and coasts (#384): a press on the strip catches it before anything else, it starts only while
 * [pressed] says no finger is down anywhere in the workspace, any new touch in the workspace ([touchEpoch]) ends
 * it, and a pane that has gone ends it ([onScroll] says so when it takes no scroll).
 * A long press turns it into a wheel of the session's
 * [windows]: [onOpen] opens the overview as the wheel's drum and gives its tabs in the drum's order, which the wheel
 * counts by (null when the overview could not open), the strip shows the window titles on a drum of its own, each window
 * turned past ticks ([wheelTickFeedback]), and [onTurn] follows the wheel's position and the window in front. Let go,
 * the window in front is [onPick]ed. A screen reader moves the wheel as a slider, and its window is [onSelect]ed at once.
 *
 * [scrolls]: a pane can be scrolled. [turns]: the wheel may open. Both are read when a finger goes down.
 */
@Composable
internal fun RailScrubber(
    windows: List<WheelWindow>, shown: String?, scrolls: Boolean, turns: Boolean, haptics: Boolean,
    pane: () -> String?, touchEpoch: () -> Int, pressed: () -> Boolean,
    onScroll: (block: String?, dy: Float) -> Boolean, onOpen: () -> List<String>?, onTurn: (position: Float, front: Int) -> Unit, onPick: (id: String) -> Unit,
    onSelect: (id: String) -> Unit, modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val density = LocalDensity.current
    val colors = VeloTheme.surface
    val scope = rememberCoroutineScope()
    val windowsNow by rememberUpdatedState(windows)
    val shownNow by rememberUpdatedState(shown)
    val scrollsNow by rememberUpdatedState(scrolls)
    val turnsNow by rememberUpdatedState(turns)
    val hapticsNow by rememberUpdatedState(haptics)
    val scrollNow by rememberUpdatedState(onScroll)
    val paneNow by rememberUpdatedState(pane)
    val epochNow by rememberUpdatedState(touchEpoch)
    val pressedNow by rememberUpdatedState(pressed)
    val openNow by rememberUpdatedState(onOpen)
    val turnNow by rememberUpdatedState(onTurn)
    val pickNow by rememberUpdatedState(onPick)

    /** The wheel while a finger holds it and until it rests; null otherwise. */
    var wheel by remember { mutableStateOf<WindowWheel?>(null) }
    /** The pane's scroll fling a flick on the strip left; null while none runs. */
    var fling by remember { mutableStateOf<ScrollFling?>(null) }
    /** The pane the fling scrolls, captured when it began: a change of focus never redirects it. */
    var flingBlock by remember { mutableStateOf<String?>(null) }
    /** The touch count when the fling began, read where it began: any finger that lands after it ends the fling. */
    var flingEpoch by remember { mutableIntStateOf(0) }
    /** The windows the wheel was opened on: the list may change under the finger, the wheel does not. */
    var turned by remember { mutableStateOf<List<WheelWindow>>(emptyList()) }
    var position by remember { mutableFloatStateOf(0f) }
    /** The window in front, the one a let go wheel picks ([WindowWheel.index]): what the strip shows as in front. */
    var front by remember { mutableIntStateOf(0) }
    /** How far the titles are out of the strip; the lens springs past 1 as it opens. */
    val open = remember { Animatable(0f) }
    /** The drum's middle in the strip, px: where the long press was. NaN: the strip's middle. */
    var centre by remember { mutableFloatStateOf(Float.NaN) }
    /** The angle of the ridges at the strip's sides, degrees: they roll with a scroll and turn with the wheel. */
    var ridges by remember { mutableFloatStateOf(0f) }
    var scrolling by remember { mutableStateOf(false) }
    var pressY by remember { mutableFloatStateOf(0f) }
    var pressAt by remember { mutableLongStateOf(0L) }
    var pressing by remember { mutableStateOf(false) }
    var longPress by remember { mutableLongStateOf(400L) }
    var charge by remember { mutableFloatStateOf(0f) }
    /** The light of the last tick, 1 when it is felt. */
    val flash = remember { Animatable(0f) }
    var flashEnd by remember { mutableStateOf(false) }
    /** Ticks waiting to be felt, each its haptic constant. */
    val ticks = remember { Channel<Int>(Channel.UNLIMITED) }
    var stripHeight by remember { mutableIntStateOf(0) }

    val titleHeight = with(density) { TitleHeight.toPx() }
    // A title's drum: one title's height between two titles WheelTitleAngle apart.
    val radius = titleHeight / (2f * kotlin.math.tan(Math.toRadians(WheelTitleAngle / 2.0)).toFloat())
    val eye = with(density) { TitleEye.toPx() }

    // Ticks are felt one after another, no closer than WheelTickGapMs: a frame that turned the wheel past two windows
    // gives two ticks, not one buzz.
    LaunchedEffect(ticks) {
        for (feedback in ticks) {
            if (hapticsNow) view.performHapticFeedback(feedback)
            flashEnd = false
            launch { flash.snapTo(1f); flash.animateTo(0f, tween(170)) }
            delay(WheelTickGapMs)
        }
    }
    // The ring fills while a press may become a long press.
    LaunchedEffect(pressing) {
        if (!pressing) {
            charge = 0f
            return@LaunchedEffect
        }
        while (true) {
            withFrameMillis { }
            charge = pressCharge(SystemClock.uptimeMillis() - pressAt, longPress)
        }
    }
    // The wheel turns toward the finger frame by frame, no faster than WheelMaxSpeed; each window it passes ticks.
    LaunchedEffect(wheel) {
        val w = wheel ?: return@LaunchedEffect
        val start = w.position
        val base = ridges
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val passed = w.advance((now - last) / 1e9f)
            last = now
            repeat(passed) { ticks.trySend(wheelTickFeedback(Build.VERSION.SDK_INT)) }
            position = w.position
            front = w.index
            ridges = base + (w.position - start) * WheelTitleAngle
            turnNow(w.position, w.index)
            if (w.resting) break
        }
    }

    /** The pane [block]'s terminal moves by [dy], the knurl turning with it; false when the pane took no scroll. */
    fun scrollStep(block: String?, dy: Float): Boolean {
        if (!scrollNow(block, dy)) return false
        ridges -= Math.toDegrees((dy / (radius * 1.05f)).toDouble()).toFloat()
        return true
    }

    // A fling the strip let go: the pane it began for keeps the finger's speed frame by frame, the knurl turning
    // with it as for a drag, until the fling stops. A new touch anywhere in the workspace ends it before another
    // step, and so does a pane that has gone; a press on the strip catches it before anything else.
    LaunchedEffect(fling) {
        val f = fling ?: return@LaunchedEffect
        val block = flingBlock
        val epoch = flingEpoch
        var last = withFrameNanos { it }
        while (f.moving) {
            val now = withFrameNanos { it }
            val dt = (now - last) / 1e9f
            last = now
            if (epochNow() != epoch) break
            val dy = f.step(dt).second
            if (dy != 0f && !scrollStep(block, dy)) break
        }
        if (fling === f) fling = null
    }

    val count = turned.size
    val shownAt = windows.indexOfFirst { it.id == shown }
    val canTurnAloud = turns && windows.size >= 2 && shownAt >= 0

    Box(
        modifier.fillMaxWidth().clipToBounds().onSizeChanged { stripHeight = it.height }
            // A slider of the session's windows for a screen reader; without windows to turn to, nothing to read.
            .then(if (canTurnAloud) Modifier.semantics {
                contentDescription = WheelName
                progressBarRangeInfo = ProgressBarRangeInfo((shownAt + 1).toFloat(), 1f..windows.size.toFloat(), steps = (windows.size - 2).coerceAtLeast(0))
                stateDescription = wheelValueText(windows[shownAt].name, shownAt, windows.size)
                setProgress { value ->
                    val at = wheelStepTo(shownAt, value, windows.size)
                    if (at != shownAt) onSelect(windows[at].id)
                    true
                }
            } else Modifier.clearAndSetSemantics {})
            // Its sizes are the density's: another density (a fold, a display size) starts the gestures again.
            .pointerInput(density.density) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // A fling the strip let go: the press catches it before anything else.
                    if (fling != null) fling = null
                    val canScroll = scrollsNow
                    val list = windowsNow
                    val canTurn = turnsNow && list.size >= 2 && wheel == null
                    if (!canScroll && !canTurn) return@awaitEachGesture
                    val slop = viewConfiguration.touchSlop
                    longPress = viewConfiguration.longPressTimeoutMillis
                    pressY = down.position.y
                    pressAt = down.uptimeMillis
                    pressing = canTurn
                    var moved: PointerInputChange? = null
                    val decided = withTimeoutOrNull(if (canTurn) longPress else Long.MAX_VALUE) {
                        var finger = StripFinger.Holding
                        while (finger == StripFinger.Holding) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                            finger = if (change == null) StripFinger.Gone else stripFinger(change.position.x - down.position.x,
                                change.position.y - down.position.y, slop, change.pressed, event.changes.count { it.pressed })
                            if (finger == StripFinger.Scrolls) moved = change
                        }
                        finger
                    }
                    pressing = false
                    val first = moved
                    if (decided == StripFinger.Scrolls && canScroll && first != null) {
                        // A plain drag: the pane's terminal moves with the finger from where the slop ended.
                        // Only the travel down or up past the slop: a finger that went sideways first does not jump back.
                        val travel = first.position.y - down.position.y
                        first.consume()
                        scrolling = true
                        // The pane the drag and its coast are for: a change of focus under them never redirects either.
                        val block = paneNow()
                        // The places the finger scrolled to, in px as it moved it: its speed as it lifts is read from them.
                        val sSamples = ArrayList<ScrollSample>()
                        var place = 0f
                        if (abs(travel) > slop) {
                            val d = travel - sign(travel) * slop
                            scrollStep(block, d)
                            place += d
                        }
                        sSamples.add(ScrollSample(first.uptimeMillis, place))
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            val cancelled = change.isConsumed
                            change.consume()
                            if (!change.pressed) {
                                // Lifted: the pane keeps the finger's speed and coasts, only while no finger is
                                // pressed anywhere in the workspace ([pressedNow]) — the root sees every pointer,
                                // the strip only its own, so one that took the touch over in the panes would
                                // otherwise be invisible here. A finger that tapped and lifted before this one
                                // allows the coast; a cancelled pointer (a palm, a system gesture) flings nothing.
                                val v = if (cancelled || pressedNow()) 0f else flingVelocity(sSamples, change.uptimeMillis)
                                val f = ScrollFling(0f, v, density.density)
                                if (f.moving) {
                                    flingBlock = block
                                    flingEpoch = epochNow()
                                    fling = f
                                }
                                break
                            }
                            val dy = change.position.y - change.previousPosition.y
                            if (dy != 0f) {
                                scrollStep(block, dy)
                                place += dy
                            }
                            sSamples.add(ScrollSample(change.uptimeMillis, place))
                            while (sSamples.isNotEmpty() && change.uptimeMillis - sSamples.first().at > ScrollSampleKeepMs) sSamples.removeAt(0)
                        }
                        scrolling = false
                        return@awaitEachGesture
                    }
                    if (decided != null || !canTurn) return@awaitEachGesture
                    // A long press: the wheel, opened on the window shown now, counting the windows in the drum's order.
                    val order = openNow() ?: return@awaitEachGesture
                    val named = list.associateBy { it.id }
                    val drum = order.map { id -> named[id] ?: WheelWindow(id, "", null) }
                    if (drum.isEmpty()) return@awaitEachGesture
                    if (hapticsNow) view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    val start = drum.indexOfFirst { it.id == shownNow }.coerceAtLeast(0)
                    val w = WindowWheel(drum.size, start)
                    turned = drum
                    position = start.toFloat()
                    front = start
                    val room = radius + titleHeight
                    val bottom = size.height - with(density) { CountRoom.toPx() }
                    centre = if (bottom - room < room) bottom / 2 else pressY.coerceIn(room, bottom - room)
                    wheel = w
                    scope.launch { open.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = 520f)) }
                    val detent = with(density) { WheelDetentDp.dp.toPx() }
                    var speed = 0f
                    var lastAt = down.uptimeMillis
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        change.consume()
                        if (!change.pressed) break
                        val dy = change.position.y - change.previousPosition.y
                        val dt = (change.uptimeMillis - lastAt).coerceAtLeast(1L)
                        lastAt = change.uptimeMillis
                        speed = speed * 0.7f + 0.3f * abs(dy) / density.density / dt
                        // Up is the next window: the titles move with the finger.
                        if (w.drag(-dy / detent * wheelGain(speed)) == WheelTick.End) {
                            wheelEndFeedback(Build.VERSION.SDK_INT)?.let { if (hapticsNow) view.performHapticFeedback(it) }
                            flashEnd = true
                            scope.launch { flash.snapTo(1f); flash.animateTo(0f, tween(260)) }
                        }
                    }
                    w.release()
                    val picked = drum[w.index].id
                    if (picked != shownNow && hapticsNow) view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    pickNow(picked)
                    scope.launch {
                        open.animateTo(0f, tween(240))
                        if (wheel === w) {
                            wheel = null
                            centre = Float.NaN
                        }
                    }
                }
            },
    ) {
        Canvas(Modifier.matchParentSize()) {
            val bottom = size.height - CountRoom.toPx()
            val middle = if (centre.isNaN()) bottom / 2 else centre
            val shown = open.value
            drawRidges(ridges, middle, radius * 1.05f, eye, bottom, glow = 0.22f + 0.32f * shown.coerceIn(0f, 1f) + if (scrolling) 0.22f else 0f,
                ink = colors.text)
            if (shown > 0.001f) {
                // The lens behind the front title springs out of the press.
                val k = shown
                val w = (size.width - 2 * LensInset.toPx()) * (0.5f + 0.5f * k)
                val h = LensHeight.toPx() * (0.35f + 0.65f * k)
                val a = (k * 1.2f).coerceIn(0f, 1f)
                val topLeft = Offset((size.width - w) / 2, middle - h / 2)
                drawRoundRect(colors.glassSelected.copy(alpha = a), topLeft, Size(w, h), CornerRadius(LensCorner.toPx()))
                drawRoundRect(colors.glassEdge.copy(alpha = a), topLeft, Size(w, h), CornerRadius(LensCorner.toPx()), style = Stroke(0.65.dp.toPx()))
                val light = if (flashEnd) colors.danger else colors.accent
                if (flash.value > 0f) drawRoundRect(light.copy(alpha = 0.9f * flash.value * a), topLeft, Size(w, h), CornerRadius(LensCorner.toPx()),
                    style = Stroke(1.5.dp.toPx()))
                val notch = (if (flash.value > 0f) light else colors.disabled).copy(alpha = 0.9f * a)
                val nh = NotchHeight.toPx() * (1f + 0.7f * flash.value)
                drawRoundRect(notch, Offset(0f, middle - nh / 2), Size(NotchWidth.toPx(), nh), CornerRadius(NotchWidth.toPx() / 2))
                drawRoundRect(notch, Offset(size.width - NotchWidth.toPx(), middle - nh / 2), Size(NotchWidth.toPx(), nh), CornerRadius(NotchWidth.toPx() / 2))
            }
            if (charge > 0f) {
                // The ring that fills while the press becomes a long press.
                val at = Offset(size.width / 2, pressY.coerceIn(ChargeRadius.toPx(), size.height - ChargeRadius.toPx()))
                val r = ChargeRadius.toPx()
                val a = (charge * 4f).coerceIn(0f, 1f)
                drawCircle(colors.text.copy(alpha = 0.14f * a), r, at, style = Stroke(ChargeStroke.toPx()))
                drawArc(colors.accent.copy(alpha = a), -90f, 360f * charge, false, Offset(at.x - r, at.y - r), Size(2 * r, 2 * r),
                    style = Stroke(ChargeStroke.toPx(), cap = StrokeCap.Round))
            }
        }
        // The titles on the strip's own drum, turned in the draw phase: only a new front recomposes them.
        if (wheel != null) for (i in (front - 3)..(front + 3)) {
            if (i !in 0 until count) continue
            val window = turned[i]
            key(window.id) {
                Column(
                    // The slider says the window in front; the titles are only seen.
                    Modifier.align(Alignment.TopCenter).fillMaxWidth().height(TitleHeight).padding(horizontal = 4.dp).clearAndSetSemantics {}.graphicsLayer {
                        val o = open.value.coerceIn(0f, 1f)
                        val angle = (i - position) * WheelTitleAngle * (0.3f + 0.7f * o)
                        val rad = Math.toRadians(angle.toDouble())
                        val c = cos(rad).toFloat()
                        val depth = eye / (eye - radius * (c - 1f))
                        val middle = if (centre.isNaN()) (stripHeight - CountRoom.toPx()) / 2 else centre
                        // The title is laid out at the strip's top: its middle goes to the drum's.
                        translationY = middle - size.height / 2 + radius * sin(rad).toFloat() * depth
                        rotationX = -angle
                        scaleX = depth
                        scaleY = depth
                        val near = (1f - abs(i - position)).coerceAtLeast(0f)
                        alpha = if (abs(angle) >= 88f) 0f else c.pow(1.3f) * (0.5f + 0.5f * near) * o
                    },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
                ) {
                    val inFront = i == front
                    TabProgramMark(window.icon, tile = false, color = if (inFront) colors.text else colors.secondary)
                    Text(window.name, color = if (inFront) colors.text else colors.secondary, fontSize = 10.5.sp, lineHeight = 12.5.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

/**
 * The ridges at the strip's sides, on a drum of [radius] turned [angle] degrees about [middle], seen from [eye]: they
 * roll with the finger as the knurled edge of a thumb wheel does. Above [bottom] only; [glow] is their light.
 */
private fun DrawScope.drawRidges(angle: Float, middle: Float, radius: Float, eye: Float, bottom: Float, glow: Float, ink: androidx.compose.ui.graphics.Color) {
    val step = WheelTitleAngle / RidgesPerTitle
    val first = kotlin.math.floor((angle - 90f) / step).toInt()
    val last = kotlin.math.ceil((angle + 90f) / step).toInt()
    val inset = RidgeInset.toPx()
    for (j in first..last) {
        val phi = angle - j * step
        if (abs(phi) >= 86f) continue
        val rad = Math.toRadians(phi.toDouble())
        val c = cos(rad).toFloat()
        val depth = eye / (eye - radius * (c - 1f))
        val y = middle - radius * sin(rad).toFloat() * depth
        if (y < 2f || y > bottom - 2f) continue
        val major = Math.floorMod(j, RidgesPerTitle) == 0
        val a = (glow * c.pow(1.4f) * if (major) 1f else 0.5f).coerceIn(0f, 1f)
        val len = (if (major) RidgeLong else RidgeShort).toPx() * (0.6f + 0.4f * c)
        val width = maxOf(0.6f, (if (major) 1.5f else 1f) * c).dp.toPx()
        val color = ink.copy(alpha = a)
        drawLine(color, Offset(inset, y), Offset(inset + len, y), width)
        drawLine(color, Offset(size.width - inset - len, y), Offset(size.width - inset, y), width)
    }
}
