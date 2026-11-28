package io.github.yhzion.velo.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import io.github.yhzion.velo.ui.theme.VeloPalettes
import kotlin.math.roundToInt

// The splash of a cold start (redesign canvas, page "1 스플래시": boards Main and SplashFrames, note nSp).
// A pane's outline is traced from its top-left corner, a seam drops through it, it splits into two
// panes, the left one takes the highlight with a cursor in it, and the wordmark rises under it.
// Nothing loops, glows or bounces: it is seen on every start. The timeline is [splashFrame], a pure
// function of the time since the app opened; [SplashScreen] draws a frame and [Splash] plays it.

/** The timeline's length: the wordmark has risen. */
const val SplashDurationMs = 1400L

/** The fade to the screen under the splash once the timeline ends. derived: a short hand-off, no motion of its own. */
const val SplashFadeMs = 160L

/** How long the last frame stays when animations are off: long enough to be seen, with no motion. derived. */
const val SplashReducedHoldMs = 600L

// The board's CSS easings: cubic-bezier(.4,0,.2,1) and cubic-bezier(.2,0,0,1).
private val Standard: Easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
private val Decelerate: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/** The share of a step that starts at [start] ms and lasts [length] ms reached at [t], through [easing]. */
private fun phase(t: Long, start: Long, length: Long, easing: Easing = LinearEasing): Float =
    easing.transform(((t - start).toFloat() / length).coerceIn(0f, 1f))

/**
 * One frame of the splash. Every part runs from 0 to 1 once, except [outline], which fades from 1 to 0.
 */
@Immutable
data class SplashFrame(
    /** The share of the outline traced, clockwise from its top-left corner (0–480 ms). */
    val trace: Float,
    /** The share of the seam drawn, from the top down (380–560 ms). */
    val seam: Float,
    /** The opacity of the outline and the seam: they give way to the panes (560–640 ms). */
    val outline: Float,
    /** The opacity of the two panes (560–640 ms). */
    val panes: Float,
    /** How far the panes have moved apart (560–760 ms). */
    val split: Float,
    /** How far the left pane has taken the highlight and the right one dimmed (760–1060 ms). */
    val focus: Float,
    /** The opacity of the prompt and the cursor in the left pane (940–1100 ms). */
    val cursor: Float,
    /** The wordmark's opacity and rise (1000–1400 ms). */
    val word: Float,
) {
    /** How far each pane still is from its place, toward the middle, in units of the 136 wide mark. */
    val paneShift: Float get() = (1f - split) * PaneShiftUnits

    /** How far the wordmark still is below its place, in dp. */
    val wordDrop: Float get() = (1f - word) * WordDropDp

    companion object {
        /** The last frame: the app's mark and the wordmark. Animations off show it alone. */
        val End = SplashFrame(trace = 1f, seam = 1f, outline = 0f, panes = 1f, split = 1f, focus = 1f, cursor = 1f, word = 1f)
    }
}

private const val PaneShiftUnits = 4f
private const val WordDropDp = 6f

/** The frame [t] ms after the app opened (note nSp; the board's keyframes). From [SplashDurationMs] on it is [SplashFrame.End]. */
fun splashFrame(t: Long): SplashFrame = SplashFrame(
    trace = phase(t, 0, 480, Standard),
    seam = phase(t, 380, 180, Standard),
    outline = 1f - phase(t, 560, 80),
    panes = phase(t, 560, 80),
    split = phase(t, 560, 200, Decelerate),
    focus = phase(t, 760, 300, Standard),
    cursor = phase(t, 940, 160),
    word = phase(t, 1000, 400, Decelerate),
)

/** The splash's lengths in dp for a window [widthDp] wide: the bar phone's, the folded Fold8's or the unfolded one's (board Main). */
@Immutable
data class SplashSize(val mark: Float, val markHeight: Float, val word: Float, val gap: Float, val bottom: Float)

/** The width from which the folded Fold8's sizes apply: half way from the bar phone (393) to it (475). */
private const val SplashClosedFromDp = 434f

/** The width from which the unfolded Fold8's sizes apply: half way from the folded (475) to it (821). */
private const val SplashOpenFromDp = 648f

fun splashSize(widthDp: Float): SplashSize = when {
    widthDp >= SplashOpenFromDp -> SplashSize(mark = 176f, markHeight = 124f, word = 38f, gap = 30f, bottom = 72f)
    widthDp >= SplashClosedFromDp -> SplashSize(mark = 144f, markHeight = 102f, word = 32f, gap = 24f, bottom = 50f)
    else -> SplashSize(mark = 136f, markHeight = 96f, word = 30f, gap = 24f, bottom = 60f)
}

/** Whether the splash shows its last frame alone: animations are off (developer options, or Remove animations in Accessibility). */
fun splashReduced(animatorScale: Float): Boolean = animatorScale == 0f

/** The timeline's time after [elapsedMs] of real time at Android's animator duration scale. */
fun splashTime(elapsedMs: Long, animatorScale: Float): Long =
    if (animatorScale > 0f) (elapsedMs / animatorScale).toLong() else SplashDurationMs

/**
 * Whether an activity shows the splash: only on a cold start. Not when the process showed it already (the activity made
 * again, the task brought back), not when the activity is restored, which brings back a screen the user had, and not
 * for a [deepLink]: a notification tapped for a pane or an SSH question goes straight to it.
 */
fun showSplash(shownInProcess: Boolean, restored: Boolean, deepLink: Boolean): Boolean = !shownInProcess && !restored && !deepLink

/** Whether the splash is over at [elapsedMs] on its timeline (real time when [reduced]). */
fun splashDone(elapsedMs: Long, reduced: Boolean): Boolean =
    if (reduced) elapsedMs >= SplashReducedHoldMs else elapsedMs >= SplashDurationMs + SplashFadeMs

/** The splash's opacity at [elapsedMs]: whole through the timeline, then a fade; whole until it goes when [reduced]. */
fun splashAlpha(elapsedMs: Long, reduced: Boolean): Float =
    if (reduced) 1f else 1f - phase(elapsedMs, SplashDurationMs, SplashFadeMs)

/** The stroke of a pane, grey until the focus step and then [target] at [focus]: mixed in sRGB, as the board's CSS does. */
fun splashPaneColor(target: Color, focus: Float): Color {
    val from = SplashGrey
    if (focus <= 0f) return from
    if (focus >= 1f) return target
    fun mix(a: Float, b: Float) = a + (b - a) * focus
    return Color(mix(from.red, target.red), mix(from.green, target.green), mix(from.blue, target.blue))
}

/** The strokes before the focus step, and the prompt (board Main: #8C8F94, the sidebar's secondary). */
internal val SplashGrey: Color get() = VeloPalettes.dark.sidebar.secondary

/** The left pane and the cursor (#7FA6F5, the focused pane's highlight). */
internal val SplashActive: Color get() = VeloPalettes.dark.chrome.accent

/** The right pane (#3A3D44, the sidebar's selected glass). */
internal val SplashOther: Color get() = VeloPalettes.dark.sidebar.glassSelected

/** The mark's drawing, in the units of its 136 × 96 view box (board Main). */
private const val MarkUnitsW = 136f
private const val MarkUnitsH = 96f
private const val MarkStroke = 2f
private const val TracePath = "M15 1H121A14 14 0 0 1 135 15V81A14 14 0 0 1 121 95H15A14 14 0 0 1 1 81V15A14 14 0 0 1 15 1Z"
private const val SeamPath = "M68 1V95"
private const val ActivePanePath = "M13 1H51A12 12 0 0 1 63 13V83A12 12 0 0 1 51 95H13A12 12 0 0 1 1 83V13A12 12 0 0 1 13 1Z"
private const val OtherPanePath = "M85 1H123A12 12 0 0 1 135 13V83A12 12 0 0 1 123 95H85A12 12 0 0 1 73 83V13A12 12 0 0 1 85 1Z"
private const val PromptPath = "M11 14l4 4-4 4"

/** A part of the mark: its box in view box units (the board's element box, without the stroke). */
private class MarkPart(val tag: String, val x: Float, val y: Float, val w: Float, val h: Float)

private val Trace = MarkPart("splash-trace", 1f, 1f, 134f, 94f)
private val Seam = MarkPart("splash-seam", 68f, 1f, 0f, 94f)
private val ActivePane = MarkPart("splash-pane-active", 1f, 1f, 62f, 94f)
private val OtherPane = MarkPart("splash-pane-other", 73f, 1f, 62f, 94f)
private val Prompt = MarkPart("splash-prompt", 11f, 14f, 4f, 8f)
private val Cursor = MarkPart("splash-cursor", 19f, 12f, 7f, 12f)

private fun parse(d: String): Path = PathParser().parsePathString(d).toPath()

/**
 * The splash at [frame], filling the window on the sidebar's colour. The mark's parts and the wordmark carry the board's
 * names as test tags; TalkBack reads "Velo" once.
 */
@Composable
fun SplashScreen(frame: SplashFrame, modifier: Modifier = Modifier) {
    val colors = VeloPalettes.dark.sidebar
    BoxWithConstraints(modifier.fillMaxSize().background(colors.surface).testTag("screen-splash")) {
        val size = splashSize(maxWidth.value)
        Column(Modifier.fillMaxSize().padding(bottom = size.bottom.dp), verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally) {
            SplashMark(frame, size)
            Spacer(Modifier.height(size.gap.dp))
            val density = LocalDensity.current
            // The wordmark is part of the picture: it keeps its size whatever the font scale.
            val fontSize = with(density) { size.word.dp.toSp() }
            // Moved by its offset, not a layer, so that its box is where it is drawn, as the board's transform does.
            Text("Velo", Modifier.offset { IntOffset(0, frame.wordDrop.dp.roundToPx()) }.testTag("splash-wordmark")
                .clearAndSetSemantics {}.graphicsLayer { alpha = frame.word }, color = colors.text, style = TextStyle(fontSize = fontSize, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.01).em))
        }
    }
}

/** The mark at [frame]: each part is drawn in a box of its own, at its place in the view box scaled to [size]. */
@Composable
private fun SplashMark(frame: SplashFrame, size: SplashSize) {
    val paths = remember {
        mapOf(Trace to parse(TracePath), Seam to parse(SeamPath), ActivePane to parse(ActivePanePath),
            OtherPane to parse(OtherPanePath), Prompt to parse(PromptPath))
    }
    // As SVG's "xMidYMid meet": the view box scaled to fit, centred.
    val unit = minOf(size.mark / MarkUnitsW, size.markHeight / MarkUnitsH)
    val left = (size.mark - MarkUnitsW * unit) / 2
    val top = (size.markHeight - MarkUnitsH * unit) / 2
    // One picture, a stop in its place: its parts' tags make them nodes, and without merging them Compose would leave
    // the mark out of a screen reader's order (#235).
    Box(Modifier.size(size.mark.dp, size.markHeight.dp).testTag("splash-mark").semantics(mergeDescendants = true) {
        contentDescription = "Velo"
        role = Role.Image
    }) {
        @Composable
        fun part(p: MarkPart, shift: Float = 0f, draw: DrawScope.() -> Unit) {
            val density = LocalDensity.current.density
            Canvas(Modifier
                .offset { IntOffset(((left + (p.x + shift) * unit) * density).roundToInt(), ((top + p.y * unit) * density).roundToInt()) }
                .size((p.w * unit).dp, (p.h * unit).dp)
                .testTag(p.tag)) {
                val k = unit * density
                withTransform({ translate(-p.x * k, -p.y * k); scale(k, k, Offset.Zero) }) { draw() }
            }
        }
        val stroke = Stroke(width = MarkStroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
        part(Trace) { drawPath(traced(paths.getValue(Trace), frame.trace), SplashGrey, alpha = frame.outline, style = stroke) }
        part(Seam) { drawPath(traced(paths.getValue(Seam), frame.seam), SplashGrey, alpha = frame.outline, style = stroke) }
        part(ActivePane, shift = frame.paneShift) {
            drawPath(paths.getValue(ActivePane), splashPaneColor(SplashActive, frame.focus), alpha = frame.panes, style = stroke)
        }
        part(OtherPane, shift = -frame.paneShift) {
            drawPath(paths.getValue(OtherPane), splashPaneColor(SplashOther, frame.focus), alpha = frame.panes, style = stroke)
        }
        part(Prompt) { drawPath(paths.getValue(Prompt), SplashGrey, alpha = frame.cursor, style = stroke) }
        part(Cursor) {
            drawRoundRect(SplashActive, Offset(Cursor.x, Cursor.y), Size(Cursor.w, Cursor.h), CornerRadius(1.5f), alpha = frame.cursor)
        }
    }
}

/** The first [share] of [path]'s length, from where it starts. */
private fun traced(path: Path, share: Float): Path {
    if (share >= 1f) return path
    val out = Path()
    if (share <= 0f) return out
    val measure = PathMeasure()
    measure.setPath(path, false)
    measure.getSegment(0f, measure.length * share, out, true)
    return out
}

/**
 * Plays the splash over the screens of a cold start and calls [onDone] when it is over. Time runs from the first frame,
 * stretched by Android's [animatorScale]; with animations off ([splashReduced]) the last frame shows for
 * [SplashReducedHoldMs] and goes, with no motion. It takes every touch while it shows.
 */
@Composable
fun Splash(animatorScale: Float, onDone: () -> Unit) {
    val reduced = splashReduced(animatorScale)
    var time by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        val start = withFrameMillis { it }
        while (!splashDone(time, reduced)) {
            val elapsed = withFrameMillis { it } - start
            time = if (reduced) elapsed else splashTime(elapsed, animatorScale)
        }
        onDone()
    }
    SplashScreen(if (reduced) SplashFrame.End else splashFrame(time), Modifier
        .graphicsLayer { alpha = splashAlpha(time, reduced) }
        .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } })
}

/** Whether this process showed the splash already: an activity made again later does not show it ([showSplash]). */
internal object SplashSeen {
    var inProcess = false
}
