package io.github.yhzion.velo.ui

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import io.github.yhzion.velo.ui.theme.monoAt
import androidx.compose.runtime.mutableIntStateOf
import kotlin.math.ceil
import androidx.compose.ui.platform.LocalDensity
import io.github.yhzion.velo.ui.theme.textAt
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import io.github.yhzion.velo.LicenseComponent
import io.github.yhzion.velo.LicenseGroup
import io.github.yhzion.velo.LicenseText
import io.github.yhzion.velo.licenseBlocks
import io.github.yhzion.velo.licenseGroups
import io.github.yhzion.velo.noticeSection
import io.github.yhzion.velo.reflowLicense
import io.github.yhzion.velo.ui.theme.GroupCard
import io.github.yhzion.velo.ui.theme.GroupHeader
import io.github.yhzion.velo.ui.theme.TagBadge
import io.github.yhzion.velo.ui.theme.VeloTheme
import io.github.yhzion.velo.ui.theme.groupBleed
import io.github.yhzion.velo.ui.theme.paragraph
import io.github.yhzion.velo.ui.theme.rounded
import kotlinx.coroutines.withContext
import java.io.IOException

/** A group's id in the test tags: its title in lower case, words joined by dashes ("terminal-core"). */
internal fun licenseGroupId(group: LicenseGroup): String = group.title.lowercase().replace(' ', '-')

/** Where a component's code ends up in the app, said on its page; null for Velo's own. */
internal fun licenseUsage(c: LicenseComponent): String? = when (c.group) {
    LicenseGroup.Velo -> null
    LicenseGroup.TerminalCore -> "Linked into libvelocore.so"
    LicenseGroup.Networking -> "Linked into libtailscale.so"
    LicenseGroup.Android -> "Part of the app"
    LicenseGroup.Fonts -> "Bundled font"
    LicenseGroup.Artwork -> "Drawn on the tab tiles"
}

/**
 * About > Open source licenses (canvas SetLicenses block): what Velo's own license is, then
 * [components] by group, each opening its page ([open] takes its id).
 */
@Composable
internal fun OpenSourceLicenseList(components: List<LicenseComponent>, open: (String) -> Unit) {
    val space = VeloTheme.space
    Column(
        Modifier.fillMaxWidth().testTag("licenses-content").padding(top = space.s8, bottom = space.s24),
        verticalArrangement = Arrangement.spacedBy(VeloTheme.medium(6.dp)),
    ) {
        Text(
            "Velo's own code is MIT licensed. It is built on the projects below, each under its own license. Tap one to read it.",
            Modifier.fillMaxWidth().padding(horizontal = space.s4).testTag("licenses-intro").padding(bottom = space.s4),
            style = VeloTheme.type.caption.paragraph(1.5.em), color = VeloTheme.surface.secondary,
        )
        for ((group, members) in licenseGroups(components)) {
            val id = licenseGroupId(group)
            // A group's heading as a settings group's, closer to its rows (12 above, 4 below).
            Text(group.title, Modifier.fillMaxWidth().testTag("license-group-$id-header").padding(start = space.s4, end = space.s4, top = space.s12, bottom = space.s4)
                .semantics { heading() }, style = VeloTheme.type.caption, fontWeight = FontWeight.Medium, color = VeloTheme.surface.secondary)
            LicenseRows(members, open, Modifier.testTag("license-group-$id"))
        }
    }
}

/** A group of license rows: each the name over its version, the license as a badge, and a chevron. */
@Composable
private fun LicenseRows(components: List<LicenseComponent>, open: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = VeloTheme.surface
    val divider = c.divider
    val edge = VeloTheme.sizes.edge
    GroupCard(modifier) {
        components.forEachIndexed { index, component ->
            val id = component.id
            Row(
                Modifier.groupBleed().testTag("license-row-$id").fillMaxWidth().heightIn(min = VeloTheme.medium(56.dp))
                    // The line between two rows is the lower row's top edge, as the board draws it.
                    .then(if (index > 0) Modifier.drawBehind { drawLine(divider, Offset(0f, edge.toPx() / 2), Offset(size.width, edge.toPx() / 2), edge.toPx()) } else Modifier)
                    .clickable(role = Role.Button) { open(id) }
                    .padding(start = VeloTheme.space.s16, end = VeloTheme.medium(10.dp), top = if (index > 0) edge else 0.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(VeloTheme.medium(10.dp)),
            ) {
                Column(Modifier.weight(1f).padding(vertical = VeloTheme.medium(6.dp)), verticalArrangement = Arrangement.spacedBy(VeloTheme.medium(1.dp))) {
                    Text(component.name, Modifier.fillMaxWidth().testTag("license-name-$id"), style = VeloTheme.type.value, color = c.text)
                    val version = component.version.ifEmpty { null }
                    if (version != null) Text(version, Modifier.fillMaxWidth().testTag("license-version-$id"), style = textAt(12.5f),
                        color = c.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                // At most half the row: a long license ("BSD-3-Clause, MIT, Apache-2.0 and others") ends in "…"
                // and leaves the name its room. The license page shows it whole.
                if (component.license.isNotEmpty()) TagBadge(component.license, Modifier.atMostHalfWidth().testTag("license-badge-$id"))
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(VeloTheme.medium(14.dp)), tint = c.disabled)
            }
        }
    }
}

/** At most half the width this is offered, so what comes beside it in a row keeps the other half. */
private fun Modifier.atMostHalfWidth() = layout { measurable, constraints ->
    val half = if (constraints.hasBoundedWidth) constraints.maxWidth / 2 else constraints.maxWidth
    val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = half))
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

/** A notice as the page shows it: its blocks, or null when the APK could not give it. */
private class LoadedNotice(val text: LicenseText, val blocks: List<String>?)

/**
 * One component's page (canvas SetLicense block): its license, version and where its code is
 * as badges; its project home; then the texts bundled in the APK, each in a frame, monospaced
 * and selectable, read through [read]. A collection lists its [LicenseComponent.parts] instead,
 * each opening its own page through [open]. The page scrolls itself, a block at a time.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LicenseDetail(
    component: LicenseComponent, open: (String) -> Unit, modifier: Modifier, host: SettingsHost,
    /** What goes over the page (its title beside the categories). */
    top: @Composable () -> Unit = {},
) {
    val space = VeloTheme.space
    val c = VeloTheme.surface
    val notices by produceState<List<LoadedNotice>?>(null, component.id) {
        value = withContext(host.io) {
            component.texts.map { text ->
                val blocks = try {
                    val notice = host.readNotice(text.asset)
                    licenseBlocks(reflowLicense(text.section?.let { noticeSection(notice, it) } ?: notice))
                } catch (_: IOException) { null }
                LoadedNotice(text, blocks)
            }
        }
    }
    val density = LocalDensity.current
    val mono = monoAt(12f).paragraph(1.6.em)
    // The board's line of the text, 1.6 of its size: Android sets each line on whole pixels instead.
    val line = with(density) { (mono.fontSize * 1.6f).toPx() }
    // The inset on whole pixels, rounded up: rounded down, the text would be a fraction wider than
    // the board's and fit a character more on each line.
    val inset = with(density) { ceil((VeloTheme.medium(14.dp) + VeloTheme.sizes.edge).toPx()).toDp() }
    val frame = Frame(c.divider, VeloTheme.radii.group, VeloTheme.sizes.edge, inset)
    @Composable
    fun About() = DisableSelection {
        Column(verticalArrangement = Arrangement.spacedBy(space.s12)) {
            FlowRow(Modifier.fillMaxWidth().testTag("license-detail-badges"), horizontalArrangement = Arrangement.spacedBy(space.s8),
                verticalArrangement = Arrangement.spacedBy(space.s8)) {
                if (component.license.isNotEmpty()) TagBadge(component.license, Modifier.testTag("license-detail-license"))
                if (component.version.isNotEmpty()) TagBadge(component.version, Modifier.testTag("license-detail-version"))
                licenseUsage(component)?.let { TagBadge(it, Modifier.testTag("license-detail-usage")) }
            }
            component.url?.let { ProjectHome(it) }
            if (component.parts.isNotEmpty()) {
                GroupHeader(component.name, first = true)
                LicenseRows(component.parts, open)
            }
        }
    }

    @Composable
    fun Title(notice: LoadedNotice) = DisableSelection { GroupHeader(notice.text.title) }

    @Composable
    fun Block(text: String, tagged: Boolean, first: Boolean, last: Boolean) {
        // A block is as tall as its lines at the board's line, so that what follows it stays in its place.
        var lines by remember(text) { mutableIntStateOf(0) }
        Text(
            text,
            Modifier.fillMaxWidth().then(if (tagged) Modifier.testTag("license-detail-text") else Modifier)
                .drawBehind { frame.draw(this, first, last) }
                .padding(start = frame.inset, end = frame.inset, top = if (first) frame.inset else 0.dp, bottom = if (last) frame.inset else 0.dp)
                .heightIn(min = with(density) { ceil(lines * line).toDp() }),
            style = mono, color = VeloTheme.colors.textOnRaised, onTextLayout = { lines = it.lineCount },
        )
    }

    @Composable
    fun Unread(notice: LoadedNotice) = Text("Couldn’t read ${notice.text.asset} from the app.", color = c.secondary)

    @Composable
    fun Note() = DisableSelection {
        Text("Read from the notices bundled in the app. No network needed.",
            Modifier.fillMaxWidth().padding(horizontal = space.s4).testTag("license-detail-offline-note"),
            style = VeloTheme.type.caption.paragraph(1.5.em), color = c.secondary)
    }

    val loaded = notices
    // Short texts, one block each, go in the page's one piece; a long one (the NDK's) a block at a time.
    val whole = loaded != null && loaded.all { (it.blocks?.size ?: 0) <= 1 }
    SelectionContainer(modifier) {
        LazyColumn {
            item("top") { DisableSelection { top() } }
            if (whole) item("whole") {
                Column(
                    Modifier.fillMaxWidth().testTag("license-detail-content").padding(top = space.s8, bottom = space.s24),
                    verticalArrangement = Arrangement.spacedBy(space.s12),
                ) {
                    About()
                    for ((index, notice) in loaded.orEmpty().withIndex()) {
                        if (component.texts.size > 1) Title(notice)
                        val blocks = notice.blocks
                        if (blocks == null) Unread(notice) else Block(blocks.firstOrNull().orEmpty(), index == 0, first = true, last = true)
                    }
                    Note()
                }
            } else {
                item("about") { Column(Modifier.testTag("license-detail-content").padding(top = space.s8)) { About() } }
                if (component.texts.isNotEmpty() && loaded == null) item("loading") {
                    Text("Loading…", Modifier.padding(vertical = space.s12), color = c.secondary)
                }
                for ((index, notice) in loaded.orEmpty().withIndex()) {
                    if (component.texts.size > 1) item("title:$index") { Title(notice) }
                    else item("gap:$index") { Spacer(Modifier.height(space.s12)) }
                    val blocks = notice.blocks
                    if (blocks == null) item("error:$index") { Unread(notice) }
                    // One frame around a text, drawn a block at a time: its top on the first block, its bottom on the last.
                    else items(blocks.size, key = { "text:$index:$it" }) { block ->
                        Block(blocks[block], index == 0 && block == 0, first = block == 0, last = block == blocks.lastIndex)
                    }
                }
                item("end") { Column(Modifier.padding(top = space.s12, bottom = space.s24)) { Note() } }
            }
        }
    }
}

/** The frame of a license text: black inside a line of [line], [radius] at its corners, [inset] to the text. */
private class Frame(val line: Color, val radius: Dp, val edge: Dp, val inset: Dp) {
    /** The part of the frame behind one block: its sides, with the top when [first] and the bottom when [last]. */
    fun draw(scope: DrawScope, first: Boolean, last: Boolean) = scope.clipRect {
        val r = radius.toPx()
        val w = edge.toPx()
        val top = if (first) 0f else -r
        val bottom = if (last) size.height else size.height + r
        // The rounded box from the frame's top to its bottom, clipped to this block by the list.
        val box = Size(size.width, bottom - top)
        drawRoundRect(Color.Black, Offset(0f, top), box, CornerRadius(r))
        val path = Path().apply {
            addRoundRect(androidx.compose.ui.geometry.RoundRect(w / 2, top + w / 2, size.width - w / 2, bottom - w / 2, CornerRadius(r - w / 2)))
        }
        drawPath(path, line, style = Stroke(w))
    }
}

/** The link to the project's page in the browser; the address stays readable when no browser takes it. */
@Composable
private fun ProjectHome(url: String) {
    val uri = LocalUriHandler.current
    val c = VeloTheme.surface
    var unopened by remember(url) { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().testTag("license-detail-project-link").heightIn(min = VeloTheme.medium(44.dp))
            .clickable(role = Role.Button) {
                try { uri.openUri(url) } catch (_: IllegalArgumentException) { unopened = true }
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(VeloTheme.medium(6.dp)),
    ) {
        Text("Project home", style = VeloTheme.type.value, color = c.accent)
        Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(VeloTheme.medium(14.dp)), tint = c.accent)
    }
    if (unopened) SelectionContainer {
        Text("Open in a browser: $url", style = VeloTheme.type.caption, color = c.secondary)
    }
}
