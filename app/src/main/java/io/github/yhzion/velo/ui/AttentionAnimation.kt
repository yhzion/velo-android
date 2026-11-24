package io.github.yhzion.velo.ui

import android.content.ContentResolver
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// Rex for Mac build 2077's two tab-row animations; research/mac-attention-animations.md has the
// addresses. Times are milliseconds of motion and lengths are dp (Rex for Mac's pt).

/** Marks whose unread report makes a tab's icon bounce: Rex for Mac bounces blocked and error tabs, not done or working ones. */
private val BouncingMarks = setOf(TabIndicator.Waiting, TabIndicator.Permission, TabIndicator.Question, TabIndicator.Auth, TabIndicator.Error)

/** Waiting marks: only a waiting report's message runs in the title's place. */
private val WaitingMarks = setOf(TabIndicator.Waiting, TabIndicator.Permission, TabIndicator.Question, TabIndicator.Auth)

/**
 * Whether a tab's icon bounces: its row draws the unread dot ([TabActivity.badge]) of a report
 * that waits for the user or failed. Seeing the tab takes the dot away and so stops it at once,
 * and so does a report that no longer waits or fails.
 */
internal fun bouncesIcon(badge: TabIndicator?): Boolean = badge in BouncingMarks

/** One bounce. */
internal const val BounceMs = 700L

/** The rest after a bounce before the next one. */
internal const val BounceRestMs = 3000L

/** How far through its bounce the icon is, [elapsedMs] of motion after it started: 1 while it rests. */
internal fun bounceProgress(elapsedMs: Long): Float {
    val t = elapsedMs.coerceAtLeast(0) % (BounceMs + BounceRestMs)
    return if (t < BounceMs) t / BounceMs.toFloat() else 1f
}

// Rex for Mac's tab icons: spring 0.12, damping 0.72, jump 0.5, scale 0.05.
private const val BounceHeight = (0.12f * 0.35f + 0.82f) * 0.5f
private const val Rebound = 0.12f * 0.2f + (1f - 0.72f) * 0.32f + 0.08f
private const val Squash = 0.05f

/** How high the icon is at [progress] of its bounce, as a fraction of its height: one jump and two smaller rebounds. */
internal fun bounceLift(progress: Float): Float {
    val p = progress.coerceIn(0f, 1f)
    if (p >= 1f) return 0f
    return when {
        p < 0.6f -> BounceHeight * sin(PI * p / 0.6f).toFloat()
        p < 0.84f -> BounceHeight * Rebound * sin(PI * (p - 0.6f) / 0.24f).toFloat()
        else -> BounceHeight * Rebound * Rebound * sin(PI * (p - 0.84f) / 0.16f).toFloat()
    }
}

/**
 * How much the icon is flattened at [progress] when it lands: it is `1 + 0.55 ×` this wider and
 * `1 −` this lower. Most at the first landing, a third as much at the second.
 */
internal fun bounceSquash(progress: Float): Float {
    val p = progress.coerceIn(0f, 1f)
    if (p >= 1f) return 0f
    val first = (p - 0.6f) / 0.045f
    val second = (p - 0.84f) / 0.035f
    return Squash * (exp(-first * first) + 0.35f * exp(-second * second))
}

/**
 * Whether a tab's title place runs its program's [message] (OSC 7501 `msg`): only while the
 * row draws the unread dot of a waiting report ([TabActivity.badge]) and the report has words.
 */
internal fun showsTicker(badge: TabIndicator?, message: String?): Boolean = badge in WaitingMarks && !message.isNullOrBlank()

/** How long the name shows between two showings of the message. */
internal const val TickerTitleMs = 4000L

/** The shortest showing of the message. */
internal const val TickerMessageMinMs = 5000L

/** The crossfade between the name and the message. */
internal const val TickerFadeMs = 300

/** The message runs this far outside the title on each side, its ends fading over this width. */
internal const val TickerInset = 8f

/** Between the end of the message and its next copy. */
internal const val TickerGap = 28f

/** How fast the message runs, in dp a second. */
internal const val TickerSpeed = 34f

/** How long the message stands at its start before each run. */
internal const val TickerPauseMs = 1500L

/** Whether a message [textWidth] wide runs in a title [titleWidth] wide: when it does not fit. */
internal fun tickerScrolls(textWidth: Float, titleWidth: Float): Boolean = textWidth > titleWidth

/** One run of the message: its pause, then its width and the gap at [TickerSpeed]. */
internal fun tickerRunMs(textWidth: Float): Long = TickerPauseMs + ((textWidth + TickerGap) / TickerSpeed * 1000).toLong()

/** How long the message shows each time: [TickerMessageMinMs], or longer for a message that runs. */
internal fun tickerMessageMs(textWidth: Float, titleWidth: Float): Long {
    val loop = if (tickerScrolls(textWidth, titleWidth)) ((textWidth + TickerGap) / TickerSpeed * 1000).toLong() + 3000 else 0
    return max(TickerMessageMinMs, loop)
}

/** Where the running message is, [elapsedMs] of motion after it showed: 0, or how far left it has gone. */
internal fun tickerOffset(elapsedMs: Long, textWidth: Float): Float {
    val t = elapsedMs.coerceAtLeast(0) % tickerRunMs(textWidth)
    return if (t < TickerPauseMs) 0f else -(t - TickerPauseMs) / 1000f * TickerSpeed
}

/** Whether animations move at all: the system's animator duration scale, 0 for developer options' "off" and for Remove animations. */
internal fun animates(animatorScale: Float): Boolean = animatorScale > 0f

/** Motion time for [realMs] of the clock: the system's animator duration scale stretches it. */
internal fun motionMs(realMs: Long, animatorScale: Float): Long = if (animates(animatorScale)) (realMs / animatorScale).toLong() else 0

private fun animatorScale(resolver: ContentResolver): Float =
    Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)

/** The system's animator duration scale, kept up to date while it changes. */
@Composable
internal fun rememberAnimatorScale(): Float {
    val resolver = LocalContext.current.contentResolver
    var scale by remember(resolver) { mutableFloatStateOf(animatorScale(resolver)) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { scale = animatorScale(resolver) }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return scale
}

/**
 * A tab's icon that bounces while [bouncing]: at once, then after every rest, until it stops,
 * when it is back in its place that frame. It does not move when animations are off.
 */
@Composable
internal fun BouncingMark(bouncing: Boolean, animatorScale: Float, content: @Composable () -> Unit) {
    val progress = remember { mutableFloatStateOf(1f) }
    val moving = bouncing && animates(animatorScale)
    LaunchedEffect(moving, animatorScale) {
        progress.floatValue = 1f
        if (!moving) return@LaunchedEffect
        val start = withFrameMillis { it }
        while (true) withFrameMillis { now -> progress.floatValue = bounceProgress(motionMs(now - start, animatorScale)) }
    }
    Box(Modifier.graphicsLayer {
        val p = progress.floatValue
        val squash = bounceSquash(p)
        translationY = -bounceLift(p) * size.height
        scaleX = 1f + 0.55f * squash
        scaleY = 1f - squash
    }) { content() }
}

/**
 * The title place of a tab whose waiting program reports [message]: the message first, then
 * [title] for [TickerTitleMs], in turn, with a crossfade. A message wider than the title runs
 * left in a loop of two copies. Without animations it stands and the two still take turns.
 */
@Composable
internal fun TitleTicker(title: String, message: String, style: TextStyle, color: Color, animatorScale: Float) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val textWidth = with(density) { measurer.measure(message, style, maxLines = 1, softWrap = false).size.width.toDp().value }
    var titleWidth by remember { mutableFloatStateOf(0f) }
    var showsMessage by remember(message) { mutableStateOf(true) }
    val fade = remember { mutableFloatStateOf(1f) }
    val hold = tickerMessageMs(textWidth, titleWidth)
    LaunchedEffect(message, hold) {
        while (true) {
            delay(if (showsMessage) hold else TickerTitleMs)
            showsMessage = !showsMessage
        }
    }
    LaunchedEffect(showsMessage, animatorScale) {
        val from = fade.floatValue
        val to = if (showsMessage) 1f else 0f
        val length = TickerFadeMs * animatorScale
        if (!animates(animatorScale) || from == to) { fade.floatValue = to; return@LaunchedEffect }
        val start = withFrameMillis { it }
        while (fade.floatValue != to) withFrameMillis { now ->
            val f = min(1f, (now - start) / length)
            fade.floatValue = from + (to - from) * f
        }
    }
    val offset = remember { mutableFloatStateOf(0f) }
    val runs = showsMessage && animates(animatorScale) && tickerScrolls(textWidth, titleWidth)
    LaunchedEffect(runs, textWidth, animatorScale) {
        offset.floatValue = 0f
        if (!runs) return@LaunchedEffect
        val start = withFrameMillis { it }
        while (true) withFrameMillis { now -> offset.floatValue = tickerOffset(motionMs(now - start, animatorScale), textWidth) }
    }
    val inset = TickerInset.dp
    // The place reaches [TickerInset] past the title on each side, as Rex for Mac's ticker does;
    // the name inside it stands where the row's title would.
    Box(Modifier.layout { measurable, constraints ->
        val extra = (inset * 2).roundToPx()
        val placeable = measurable.measure(constraints.copy(
            minWidth = constraints.minWidth + extra, maxWidth = constraints.maxWidth + extra))
        titleWidth = (placeable.width - extra).toDp().value
        layout(placeable.width - extra, placeable.height) { placeable.place(-extra / 2, 0) }
    }) {
        Text(title, Modifier.layout { measurable, constraints ->
            val pad = inset.roundToPx()
            val placeable = measurable.measure(constraints.copy(
                minWidth = (constraints.minWidth - pad * 2).coerceAtLeast(0), maxWidth = (constraints.maxWidth - pad * 2).coerceAtLeast(0)))
            layout(placeable.width + pad * 2, placeable.height) { placeable.place(pad, 0) }
        }.graphicsLayer { alpha = 1f - fade.floatValue }, color = color, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(Modifier.matchParentSize().clipToBounds()
            .graphicsLayer { alpha = fade.floatValue; compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val edge = min(inset.toPx() / size.width, 0.5f)
                drawRect(Brush.horizontalGradient(0f to Color.Transparent, edge to Color.Black, 1f - edge to Color.Black,
                    1f to Color.Transparent), blendMode = BlendMode.DstIn)
            }) {
            val copy = Modifier.wrapContentWidth(Alignment.Start, unbounded = true)
            Text(message, copy.graphicsLayer { translationX = (inset + offset.floatValue.dp).toPx() },
                color = color, style = style, maxLines = 1, softWrap = false)
            if (tickerScrolls(textWidth, titleWidth)) {
                Text(message, copy.graphicsLayer { translationX = (inset + (textWidth + TickerGap + offset.floatValue).dp).toPx() },
                    color = color, style = style, maxLines = 1, softWrap = false)
            }
        }
    }
}
