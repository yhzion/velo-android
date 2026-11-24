package io.github.yhzion.velo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.yhzion.velo.R
import io.github.yhzion.velo.ui.theme.GlassIconButton
import io.github.yhzion.velo.ui.theme.MenuItem
import io.github.yhzion.velo.ui.theme.VeloPopover
import io.github.yhzion.velo.ui.theme.VeloTheme
import io.github.yhzion.velo.ui.theme.Status
import io.github.yhzion.velo.ui.theme.rounded
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

/**
 * The font scale the rail's clock draws with: the system's [fontScale], but no more than [limit],
 * the scale at which the widest clock text still fills the rail's inner width. The rail is a fixed
 * width, so a larger clock would lose a digit at its edge.
 */
internal fun clockFontScale(fontScale: Float, limit: Float): Float = min(fontScale, limit)

/** Between the system's status bar and the clock under it. derived: the mockup the owner chose (v4). */
internal val StatusBarClearance: Dp = 6.dp

/**
 * How far the rail's content starts below the rail's top edge. The system shows its privacy
 * chips (microphone, camera, location) and background work over the status bar even while the
 * bar is hidden, so the clock goes below [statusBar] (the bar's height, hidden or not) with
 * [clearance] to spare. [railTop] is where the rail starts in the window; [least] is the rail's
 * own padding, kept when the status bar is already clear.
 */
internal fun railTopPadding(statusBar: Float, railTop: Float, clearance: Float, least: Float): Float =
    maxOf(least, statusBar + clearance - railTop)

/**
 * The rail beside the panes: the clock, the device's battery and network ([BatteryGauge]), the
 * active remote's connection mark ([mark], a status that opens a popover naming [remote] and the
 * [failure] that keeps it down, with "Copy command" for its one-line [fix]), the focused pane's
 * menu (off while [menuEnabled] is false: no pane is focused), the keyboard, the free [strip] (empty, or the window
 * wheel of #304, [RailScrubber]), then the keep-screen-on toggle ([keepScreenOn], the setting of the same name), the tab
 * overview and the sidebar.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RightRail(
    mark: ConnectionMark, remote: String, failure: String?, keyboard: Boolean, onReconnect: () -> Unit, menuEnabled: Boolean,
    fix: String? = null, onCopyCommand: () -> Unit = {},
    overviewOpen: Boolean, sidebarOpen: Boolean, keepScreenOn: Boolean, onKeepScreenOn: () -> Unit,
    keyboardAvailable: Boolean = true, onMenu: () -> Unit, onKeyboard: () -> Unit, onSidebar: () -> Unit, onOverview: () -> Unit, menu: @Composable () -> Unit,
    strip: @Composable ColumnScope.() -> Unit = { Spacer(Modifier.weight(1f)) },
) {
    // The network first: both are registered off the main thread one after the other (#336), and a battery shown
    // before the network would read as "no network" until its registration ends.
    val network = rememberDeviceNetwork()
    val battery = rememberBatteryState()
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) { while (true) { now = Date(); delay(15_000) } }
    val sizes = VeloTheme.sizes
    val locale = Locale.getDefault()
    val clock = remember(locale) { SimpleDateFormat("H:mm", locale) }
    // "00:00" is the widest text the clock can show, so one measurement at scale 1 bounds every time of day.
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val clockStyle = VeloTheme.type.clock
    val clockWidth = remember(measurer, clockStyle, density.density) {
        measurer.measure("00:00", clockStyle, maxLines = 1, softWrap = false, density = Density(density.density, 1f)).size.width
    }
    val clockLimit = with(density) { (sizes.rail - sizes.gapCompact * 2).toPx() } / clockWidth
    val clockDensity = Density(density.density, clockFontScale(density.fontScale, clockLimit))
    val statusBar = WindowInsets.statusBarsIgnoringVisibility.getTop(density).toFloat()
    var railTop by remember { mutableStateOf(0f) }
    val top = with(density) {
        railTopPadding(statusBar, railTop, StatusBarClearance.toPx(), sizes.gap.toPx()).toDp()
    }
    BoxWithConstraints(Modifier.width(sizes.rail).fillMaxHeight().onGloballyPositioned { railTop = it.positionInWindow().y }
        .clip(rounded(VeloTheme.radii.bar)).background(VeloTheme.surface.surface)) {
        // With the IME up the rail can be shorter than its controls. Keep the bottom actions reachable.
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(top = top, bottom = sizes.gap),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(sizes.rowGap)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CompositionLocalProvider(LocalDensity provides clockDensity) {
                    Text(clock.format(now), color = VeloTheme.surface.text, style = clockStyle, maxLines = 1)
                }
                if (battery != null) {
                    Spacer(Modifier.height(sizes.gapCompact * 2))
                    BatteryGauge(battery, network)
                }
                ConnectionStatus(mark, remote, failure, onReconnect, fix, onCopyCommand)
            }
            Box {
                GlassIconButton(Icons.Default.MoreHoriz, "Pane menu", onMenu, enabled = menuEnabled)
                menu()
            }
            if (keyboardAvailable) GlassIconButton(if (keyboard) Icons.Default.KeyboardHide else Icons.Default.Keyboard, "Keyboard", onKeyboard, selected = keyboard)
            // What is left between the top and the bottom buttons: it takes the room, however little.
            strip()
            GlassIconButton(if (keepScreenOn) CupFull else CupEmpty, "Keep screen on", onKeepScreenOn, selected = keepScreenOn)
            GlassIconButton(Icons.Default.GridView, "Overview", onOverview, selected = overviewOpen)
            // The same glyph as the sidebar's own Toggle Sidebar button: both buttons do one thing, so they look alike.
            GlassIconButton(ImageVector.vectorResource(R.drawable.ic_toolbar_sidebar), "Sidebar", onSidebar, selected = sidebarOpen)
        }
    }
}

/**
 * The connection of the active remote, as a status under the clock: a small mark in the
 * state's colour, with no button around it. A tap says which remote it is and how it is
 * doing, with the [failure] that keeps it down when one is known, and offers "Reconnect" when
 * its phase does, and "Copy command" when the failure has a one-line [fix] (convenience C).
 */
@Composable
private fun ConnectionStatus(mark: ConnectionMark, remote: String, failure: String?, onReconnect: () -> Unit, fix: String?, onCopyCommand: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        // The touch target is wider than the mark; nothing is drawn around it.
        Box(Modifier.size(VeloTheme.sizes.control).clickable(role = Role.Button, onClickLabel = "Connection") { open = true }, contentAlignment = Alignment.Center) {
            ConnectionMarkIcon(mark)
        }
        VeloPopover(open, onDismissRequest = { open = false }, modifier = Modifier.widthIn(min = VeloTheme.sizes.menuMinWidth)) {
            Text(remote, color = VeloTheme.surface.text, style = VeloTheme.type.bodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val tint = when (mark.level) {
                ConnectionLevel.Up -> VeloTheme.surface.ok
                ConnectionLevel.Busy -> VeloTheme.surface.warning
                ConnectionLevel.Down -> VeloTheme.surface.danger
            }
            Text(mark.word, color = tint, style = VeloTheme.type.caption)
            failure?.let { Text(it, color = VeloTheme.surface.secondary, style = VeloTheme.type.caption) }
            if (mark.canReconnect) MenuItem("Reconnect", { open = false; onReconnect() }, icon = Icons.Default.Refresh)
            if (fix != null) MenuItem(COPY_COMMAND, { open = false; onCopyCommand() }, icon = Icons.Default.ContentCopy)
        }
    }
}

/** The colour of a connection [level]: the rail's mark, the capsule's and the sidebar's dot share it. */
internal fun levelStatus(level: ConnectionLevel): Status = when (level) {
    ConnectionLevel.Up -> Status.Ok
    ConnectionLevel.Busy -> Status.Warning
    ConnectionLevel.Down -> Status.Danger
}

/** How much of its ink the connection glyph keeps while it connects: dimmed, the same hue. derived: the owner asked for one colour. */
internal const val ConnectingAlpha = 0.45f

/**
 * The glyph of a connection [mark], in one colour ([ink], the text of the surface it is on):
 * the shape says the level, not a colour. The rail and the capsule over paused panes both draw
 * it, so the two cannot show different marks for one connection.
 */
@Composable
internal fun ConnectionMarkIcon(mark: ConnectionMark, ink: Color = VeloTheme.surface.text) {
    // This is the link to the remote, not the device's network (the battery gauge shows that):
    // the remote's own icon, the one the sidebar and Settings give a remote. Connecting dims it;
    // down crosses the screen out.
    val icon = when (mark.level) {
        ConnectionLevel.Up, ConnectionLevel.Busy -> Icons.Default.Computer
        ConnectionLevel.Down -> Icons.Default.DesktopAccessDisabled
    }
    val tint = if (mark.level == ConnectionLevel.Busy) ink.copy(alpha = ConnectingAlpha) else ink
    Icon(icon, "Connection: ${mark.word}", Modifier.size(VeloTheme.sizes.iconSmall), tint = tint)
}
