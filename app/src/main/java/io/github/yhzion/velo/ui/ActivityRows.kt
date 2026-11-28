package io.github.yhzion.velo.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import io.github.yhzion.velo.ui.theme.Capsule
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.yhzion.velo.ui.theme.TabTile
import io.github.yhzion.velo.ui.theme.VeloMenu
import io.github.yhzion.velo.ui.theme.VeloTheme
import io.github.yhzion.velo.ui.theme.naturalLines
import io.github.yhzion.velo.ui.theme.rounded
import io.github.yhzion.velo.ui.theme.tabRowFill
import io.github.yhzion.velo.ui.theme.MenuItem as VeloMenuItem
import kotlinx.coroutines.delay

/** A length the Sidebar board gives, at the current size ([VeloTheme.medium]). */
private val Int.bd: Dp @Composable @ReadOnlyComposable get() = VeloTheme.medium(this.dp)

/** How long until the age of a time [atMs] next changes, at [nowMs]: its next whole minute ([shortAge]). */
internal fun nextAgeChangeMs(atMs: Long, nowMs: Long): Long = 60_000 - Math.floorMod(nowMs - atMs, 60_000L)

/**
 * A tab's row in Activity (#248), as Rex for Mac 2185 draws it (described, not copied). Its first line is its tile,
 * its name and, while its program works, the working dots at the end; its second its session's name and how long ago
 * it came into its group ([shortAge]). Under them, by group: a working tab's capsule of its program's words ([MessageCapsule]), a
 * waiting or failed tab's well ([ReasonWell]), and nothing for a finished one. A tap shows the tab, a long press
 * offers Rename and Close; the shown tab has the pill behind it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ActivityRow(
    entry: ActivityEntry, motion: Float, modifier: Modifier = Modifier,
    onSelect: () -> Unit, onRename: () -> Unit, onClose: () -> Unit,
) {
    val c = VeloTheme.surface
    val type = VeloTheme.type
    val row = entry.row
    val activity = entry.drawn
    val id = entry.key
    var menu by remember { mutableStateOf(false) }
    val age by produceState(entry.enteredAt?.let { shortAge(it, System.currentTimeMillis()) }, entry.enteredAt) {
        val at = entry.enteredAt ?: run { value = null; return@produceState }
        while (true) {
            val now = System.currentTimeMillis()
            value = shortAge(at, now)
            delay(nextAgeChangeMs(at, now))
        }
    }
    val tile = tabTile(row, activity)
    val message = entry.message
    val reason = wellReason(activity.indicator)
    val third = message?.let { activityThirdLine(entry.priority, reason, it) }
    // The group's title says the state, so a screen reader hears only what the tile adds: unread, and of what. Where
    // the well says what the tab waits for, "Unread" alone (#279: each part once).
    val wellSays = activity.indicator.takeIf { reason != null && third?.contains(reason) == true }
    val said = tabRowTileSpeech(tile, activity.icon ?: row.icon, row.name, activity.badge, wellSays)
    val gap = 12.bd
    // The group's title says it works; the percent of a pie is said with the session (#251).
    val sessionSaid = progressSaid(tile, entry.sessionLabel, PercentAt.First)
    // The row says all of it, its parts hidden, so that its words stay with it half under the Filter field (#279).
    val description = activityRowDescription(listOfNotNull(said.description, said.state), row.name.takeIf { said.nameSaid },
        sessionSaid ?: entry.sessionLabel, age, third.orEmpty())
    Box(modifier) {
        Column(Modifier.testTag("activity-row-$id").padding(horizontal = 8.bd).fillMaxWidth().clip(rounded(14.bd))
            .background(if (row.shown) c.selected else Color.Transparent)
            .combinedClickable(onLongClick = { menu = true }, onClick = onSelect)
            .semantics { contentDescription = description; if (row.shown) selected = true }
            .padding(start = 12.bd, end = 10.bd, top = 7.bd, bottom = 7.bd)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(gap)) {
                BouncingMark(bouncesIcon(activity.badge), motion) {
                    TabTile(tile.brand, panes = tile.panes, behind = tile.behind, dot = tile.dot, ring = tabRowFill(row.shown),
                        tag = "tab-tile-$id", contentDescription = null, progress = tile.progress)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.bd)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // The row says the name, unless it only says again what the tile says (#150).
                        Text(row.name, Modifier.weight(1f).testTag("activity-title-$id").semantics { hideFromAccessibility() },
                            style = type.body.naturalLines(), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        // A tile with a progress pie already shows the work (#250): no dots then.
                        if (rowMark(activity.indicator, tile) == RowMark.Working) {
                            WorkingDots(5.bd, 4.bd, Modifier.padding(start = 8.bd).testTag("tab-working-$id"), description = null)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // The line keeps the words the row says for it, a pie's percent first, for what reads them.
                        Text(entry.sessionLabel, Modifier.weight(1f).testTag("activity-session-$id")
                            .clearAndSetSemantics { text = AnnotatedString(sessionSaid ?: entry.sessionLabel); hideFromAccessibility() },
                            style = type.caption.naturalLines(), color = c.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        age?.let {
                            Text(it, Modifier.padding(start = 8.bd).testTag("activity-age-$id").semantics { hideFromAccessibility() },
                                style = type.caption.naturalLines(), color = c.secondary, maxLines = 1)
                        }
                    }
                }
            }
            if (message != null) Box(Modifier.padding(start = VeloTheme.sizes.tabTile + gap, top = 6.bd)) {
                when (entry.priority) {
                    ActivityPriority.Working -> MessageCapsule(message, motion, Modifier.testTag("activity-capsule-$id"))
                    ActivityPriority.Blocked, ActivityPriority.Failed ->
                        ReasonWell(wellReason(activity.indicator), message, Modifier.testTag("activity-well-$id"))
                    ActivityPriority.Done -> Unit
                }
            }
        }
        VeloMenu(menu, onDismissRequest = { menu = false }) {
            // Its place in its session is not what this list shows, so the moves are left to the session's list.
            for (item in tabMenu(true, forward = false, backward = false)) {
                val close = item.action == TabAction.Close
                if (item.action != TabAction.Rename && !close) continue
                VeloMenuItem(item.label, {
                    menu = false
                    if (close) onClose() else onRename()
                }, icon = if (close) Icons.Default.Close else Icons.Default.Edit, enabled = item.enabled, destructive = close)
            }
        }
    }
}

/**
 * What an Activity row says (#279), in the order it shows them: its [tile]'s words, its [name] (null where the tile
 * says it already, #150), its [session] line (a pie's percent first, #251), its [age] and its [third] line's words.
 * Compose leaves out a node that a later one covers whole, so a row half under the sidebar's Filter field lost all
 * but what showed above the field while these were said by its parts.
 */
internal fun activityRowDescription(tile: List<String>, name: String?, session: String, age: String?, third: List<String>): String =
    (tile + listOfNotNull(name, session, age) + third).joinToString(", ")

/** The words of the line under a row of [priority]: a working tab's [message], a waiting or failed tab's [reason] and message, a finished one's none. */
internal fun activityThirdLine(priority: ActivityPriority, reason: String?, message: String): List<String> = when (priority) {
    ActivityPriority.Working -> listOf(message)
    ActivityPriority.Blocked, ActivityPriority.Failed -> listOfNotNull(reason, message)
    ActivityPriority.Done -> emptyList()
}

/**
 * The well under a waiting or failed tab's row (#248): what it waits for ([reason], in the accent; a failure has
 * none) over its program's [message] in at most two lines, which end with an ellipsis and do not run. The row says
 * them ([activityRowDescription]), so the well is hidden from a screen reader.
 */
@Composable
internal fun ReasonWell(reason: String?, message: String, modifier: Modifier = Modifier) {
    val c = VeloTheme.surface
    val type = VeloTheme.type
    Column(modifier.clearAndSetSemantics { hideFromAccessibility() }.fillMaxWidth().clip(rounded(10.bd)).background(c.text.copy(alpha = .07f))
        .padding(horizontal = 12.bd, vertical = 8.bd), verticalArrangement = Arrangement.spacedBy(2.bd)) {
        if (reason != null) Text(reason, style = type.value.naturalLines(), color = c.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(message, style = type.value.naturalLines(), color = c.secondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** The capsule's words: how long they rest at their start before each lap, and how fast they then run (#249, measured on Rex for Mac 2185). */
internal const val CapsuleRestMs = 1500
internal const val CapsuleSpeedDp = 32

/** Between the end of the words and their next copy as they run, and the width over which the capsule's ends fade. */
internal const val CapsuleGapDp = 28
internal const val CapsuleFadeDp = 8

/**
 * New words: the old leave upward (about 0.1 s), a short blank, and the new come up from below, a little past their
 * place and back (about 0.13 s); some 0.31 s in all, as measured on Rex for Mac 2185 (#249).
 */
internal const val CapsuleOutMs = 100
internal const val CapsuleInDelayMs = 170
internal const val CapsuleInMs = 140

/** A rise that passes its place by a few percent and comes back. */
private val CapsuleRise = CubicBezierEasing(0.3f, 1.2f, 0.6f, 1f)

/**
 * A working tab's capsule (#249): its program's [text] in a grey pill as wide as the words, at most the row. New words
 * push the old up and come in from below while the pill takes their width. Words wider than the row rest for
 * [CapsuleRestMs] from the change, then run left at [CapsuleSpeedDp] dp a second, a copy following after a gap, and
 * rest again at their start after each lap; the pill's ends fade while they run. Nothing moves while the system's
 * animations are off ([motion]): long words end in an ellipsis then. The row says the words once
 * ([activityRowDescription]); the capsule keeps them as its description for what reads them, hidden from a screen reader.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MessageCapsule(text: String, motion: Float, modifier: Modifier = Modifier) {
    val c = VeloTheme.surface
    val style = VeloTheme.type.caption.naturalLines()
    val moves = animates(motion)
    val pad = 10.bd
    Box(modifier.clip(Capsule).background(c.text.copy(alpha = .08f)).heightIn(min = 24.bd)
        .clearAndSetSemantics { contentDescription = text; hideFromAccessibility() }, contentAlignment = Alignment.CenterStart) {
        AnimatedContent(text, transitionSpec = {
            val sizing = SizeTransform(clip = true) { _, _ -> if (moves) spring(stiffness = Spring.StiffnessMediumLow) else snap() }
            if (!moves) EnterTransition.None togetherWith ExitTransition.None using sizing
            else slideInVertically(tween(CapsuleInMs, CapsuleInDelayMs, CapsuleRise)) { it } togetherWith
                slideOutVertically(tween(CapsuleOutMs, easing = FastOutLinearInEasing)) { -it } using sizing
        }, contentAlignment = Alignment.CenterStart, label = "Capsule words") { words ->
            if (!moves) {
                Text(words, Modifier.padding(horizontal = pad), style = style, color = c.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                return@AnimatedContent
            }
            // The words keep the pill's padding as they run, so at rest their ends are clear of the fades.
            var room by remember { mutableIntStateOf(0) }
            var needs by remember { mutableIntStateOf(0) }
            val runs = room in 1..<needs
            Box(Modifier.onSizeChanged { room = it.width }
                .then(if (runs) Modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
                    drawContent()
                    val edge = (CapsuleFadeDp.dp.toPx() / size.width).coerceAtMost(.5f)
                    drawRect(Brush.horizontalGradient(0f to Color.Transparent, edge to Color.Black, 1f - edge to Color.Black,
                        1f to Color.Transparent), blendMode = BlendMode.DstIn)
                } else Modifier)
                .basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = CapsuleRestMs, repeatDelayMillis = CapsuleRestMs,
                    spacing = MarqueeSpacing(CapsuleGapDp.dp - pad * 2), velocity = CapsuleSpeedDp.dp)) {
                Text(words, Modifier.onSizeChanged { needs = it.width }.padding(horizontal = pad), style = style, color = c.secondary,
                    maxLines = 1, softWrap = false)
            }
        }
    }
}
