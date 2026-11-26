package io.github.yhzion.velo.ui.theme

import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.HorizontalSplit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.outlined.LaptopMac
import androidx.compose.material.icons.filled.KeyboardCommandKey
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.VerticalSplit
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.viewinterop.AndroidView
import io.github.yhzion.velo.R
import io.github.yhzion.velo.ui.KeyBar
import io.github.yhzion.velo.ui.StickyKeys
import io.github.yhzion.velo.ui.toggleSticky

private val Commands = listOf("New Tab", "New Session", "Split Right", "Split Down", "Rename Tab…", "Close Pane", "Toggle Sidebar", "Settings")

/**
 * The whole gallery: a workspace built from the components, then every overlay surface and
 * control laid out flat, then the redesign's samples ([RedesignSample]). Test tags are resource
 * ids here, so a UI dump names the samples as the boards do.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun Gallery(
    pattern: SurfaceView,
    open: GalleryOverlay?, onOpen: (GalleryOverlay?) -> Unit, scrollDp: Int,
) {
    val colors = VeloTheme.colors
    val s = VeloTheme.sizes
    val start = with(LocalDensity.current) { (scrollDp * density).toInt() }
    VeloSurface(colors.backdrop) {
        BoxWithConstraints(Modifier.fillMaxSize().background(colors.backdrop.surface).semantics { testTagsAsResourceId = true }) {
            val page = maxHeight
            Column(Modifier.verticalScroll(rememberScrollState(start))) {
                Row(Modifier.height(page).padding(end = s.gap).padding(vertical = s.gap), horizontalArrangement = Arrangement.spacedBy(s.gap)) {
                    VeloSurface(colors.sidebar) { SidebarSample(Modifier.width(s.sidebar).fillMaxHeight()) }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(s.gap)) {
                        VeloSurface(colors.chrome) { PaneSample(pattern, Modifier.weight(1f), open, onOpen) }
                        KeySample()
                    }
                    VeloSurface(colors.chrome) { RailSample(open, onOpen) }
                }
                VeloSurface(colors.chrome) { FlatSample() }
                RedesignSample()
            }
        }
    }
    OpenOverlay(open, onOpen)
}

@Composable
private fun SidebarSample(modifier: Modifier) {
    val s = VeloTheme.sizes
    var query by remember { mutableStateOf("") }
    var activity by remember { mutableStateOf(false) }
    Box(modifier.background(VeloTheme.surface.surface)) {
        Column(Modifier.padding(horizontal = s.rowInset)) {
            Row(Modifier.padding(vertical = s.gap), verticalAlignment = Alignment.CenterVertically) {
                GlassIconButton(ImageVector.vectorResource(R.drawable.ic_toolbar_sidebar), "Toggle Sidebar", {})
                Spacer(Modifier.weight(1f))
                GlassCapsule {
                    CapsuleIcon(painterResource(if (activity) R.drawable.ic_toolbar_eye_fill else R.drawable.ic_toolbar_eye),
                        "Toggle Activity", { activity = !activity }, selected = activity, badge = true)
                    CapsuleIcon(painterResource(R.drawable.ic_toolbar_command), "Command Palette", {})
                    CapsuleIcon(painterResource(R.drawable.ic_toolbar_gear), "Settings", {})
                }
            }
            ListRow("velo", {}, subtitle = "192.0.2.7 · Running", strong = true,
                leading = { Icon(Icons.Outlined.Smartphone, "This Device", Modifier.size(s.icon), tint = VeloTheme.surface.text) }, trailing = { StatusMark(Status.Ok, "Node Running") })
            ListRow("workstation", {}, strong = true, leading = { Icon(Icons.Outlined.LaptopMac, "Remote", Modifier.size(s.icon), tint = VeloTheme.surface.text) },
                trailing = { StatusMark(Status.Ok, "Connected"); RowIconButton(Icons.Default.MoreHoriz, "Remote menu", {}) })
            SectionHeader("ios-crash-report", trailing = {
                RowIconButton(Icons.Default.AddCircleOutline, "New Tab", {})
                RowIconButton(Icons.Default.ExpandMore, "Collapse", {})
            })
            ListRow("Activity mode tab bar", {}, selected = true, leading = { TabTile(TileBrand.Terminal) }, trailing = { StatusMark(Status.Active, "Working") })
            ListRow("zsh ~/src/velo-android", {}, leading = { TabTile(TileBrand.Zsh) })
            SectionHeader("docs", trailing = { RowIconButton(Icons.Default.AddCircleOutline, "New Tab", {}, enabled = false) })
            ListRow("nvim notes.md", {}, dimmed = true, leading = { TabTile(TileBrand.Neovim) }, trailing = { StatusMark(Status.Warning, "Waiting") })
            ListRow("laptop", {}, subtitle = "offline", dimmed = true, strong = true, leading = { Icon(Icons.Outlined.LaptopMac, "Remote", Modifier.size(s.icon), tint = VeloTheme.surface.secondary) },
                trailing = { StatusMark(Status.Danger, "Not connected") })
            ListRow("New Session", {}, enabled = false, leading = { Icon(Icons.Default.Add, null, Modifier.size(s.icon), tint = VeloTheme.surface.disabled) })
        }
        SearchField(query, { query = it }, Modifier.align(Alignment.BottomCenter).padding(horizontal = s.rowInset + s.gap).padding(bottom = s.rowInset))
    }
}

@Composable
private fun PaneSample(pattern: SurfaceView, modifier: Modifier, open: GalleryOverlay?, onOpen: (GalleryOverlay?) -> Unit) {
    val s = VeloTheme.sizes
    var tab by remember { mutableStateOf(0) }
    Column(modifier.clip(rounded(VeloTheme.radii.pane)).background(VeloTheme.surface.surface)) {
        Row(Modifier.padding(s.overlayPadding), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(s.rowGap)) {
            GlassIconButton(Icons.Default.ArrowBackIosNew, "Back", {})
            TabTile(TileBrand.Terminal)
            Column(Modifier.weight(1f)) {
                Text("Activity mode tab bar", style = VeloTheme.type.title, color = VeloTheme.surface.text, maxLines = 1)
                Text("ios-crash-report", style = VeloTheme.type.caption, color = VeloTheme.surface.secondary, maxLines = 1)
            }
            GlassCapsule {
                for (i in 0 until 3) CapsuleIcon(Icons.Default.Terminal, "Tab ${i + 1}", { tab = i }, selected = tab == i)
            }
            GlassIconButton(Icons.Default.Add, "New Tab", {})
            Box {
                GlassIconButton(Icons.Default.MoreHoriz, "Open menu", { onOpen(GalleryOverlay.Menu) })
                VeloMenu(open == GalleryOverlay.Menu, { onOpen(null) }) { MenuItems { onOpen(null) } }
            }
        }
        AndroidView({ pattern }, Modifier.weight(1f).fillMaxWidth())
    }
}

@Composable
private fun ColumnScope.MenuItems(done: () -> Unit) {
    MenuItem("Split Right", done, icon = Icons.Default.VerticalSplit)
    MenuItem("Split Down", done, icon = Icons.Default.HorizontalSplit)
    MenuItem("Float", done, icon = Icons.Default.PictureInPicture)
    MenuItem("Rename Tab…", done, icon = Icons.Default.Edit)
    MenuDivider()
    MenuItem("New Tab", done, icon = Icons.Default.Add, enabled = false)
    MenuItem("Close Pane", done, icon = Icons.Default.Close, destructive = true)
}

@Composable
private fun KeySample() {
    var armed by remember { mutableStateOf(setOf("Ctrl")) }
    KeyBar(listOf("/", "Ctrl", "Alt", "Esc", "Tab", "Left", "Down", "Up", "Right", "Backspace"), armed, Modifier.fillMaxWidth()) { key, _ ->
        armed = if (key in StickyKeys) toggleSticky(armed, key) else emptySet()
    }
}

@Composable
private fun RailSample(open: GalleryOverlay?, onOpen: (GalleryOverlay?) -> Unit) {
    val s = VeloTheme.sizes
    Column(
        Modifier.width(s.rail).fillMaxHeight().clip(rounded(VeloTheme.radii.bar)).background(VeloTheme.surface.surface).padding(vertical = s.overlayPadding),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(s.rowGap),
    ) {
        Text("16:12", style = VeloTheme.type.clock, color = VeloTheme.surface.text)
        StatusMark(Status.Ok, "Remote connected", icon = Icons.Default.Dns)
        Box {
            GlassIconButton(Icons.Default.Info, "Open popover", { onOpen(GalleryOverlay.Popover) })
            VeloPopover(open == GalleryOverlay.Popover, { onOpen(null) }) {
                Text("workstation", style = VeloTheme.type.overlayTitle, color = VeloTheme.surface.text)
                Text("Connected · 161 × 54", style = VeloTheme.type.body, color = VeloTheme.surface.secondary)
                Spacer(Modifier.height(s.overlayPadding))
                VeloButton("Done", { onOpen(null) }, Modifier.fillMaxWidth(), ButtonKind.Primary)
            }
        }
        GlassIconButton(Icons.Default.KeyboardCommandKey, "Open palette", { onOpen(GalleryOverlay.Palette) })
        GlassIconButton(Icons.Default.Edit, "Open dialog", { onOpen(GalleryOverlay.Dialog) })
        Spacer(Modifier.weight(1f))
        GlassIconButton(Icons.Default.GridView, "Overview", {}, enabled = false)
    }
}

/** The overlay the intent or a button asked for, in its own window over the pattern. */
@Composable
private fun OpenOverlay(open: GalleryOverlay?, onOpen: (GalleryOverlay?) -> Unit) {
    val close = { onOpen(null) }
    var name by remember { mutableStateOf("Activity mode tab bar") }
    var query by remember { mutableStateOf("") }
    when (open) {
        GalleryOverlay.Dialog -> VeloDialog("Rename Tab", close, confirmText = "Rename", onConfirm = close, text = "The name shows in the sidebar and in the tab overview.") {
            VeloTextField(name, { name = it }, label = "Name", placeholder = "Tab")
        }
        GalleryOverlay.Palette -> VeloPalette(query, { query = it }, onGo = close, onDismissRequest = close) { paletteRows(query, close) }
        else -> {}
    }
}

private fun LazyListScope.paletteRows(query: String, run: () -> Unit) {
    val matches = Commands.filter { query.isBlank() || it.contains(query.trim(), true) }
    items(matches.size) { i ->
        val disabled = matches[i] == "Close Pane"
        PaletteRow(matches[i], run, enabled = !disabled, note = if (disabled) "The last pane of a tab stays." else null)
    }
    if (matches.isEmpty()) item { PaletteRow("No matching command", {}, enabled = false) }
}

/** Every overlay surface without its window, and the controls that are not in the workspace above. */
@Composable
private fun FlatSample() {
    val s = VeloTheme.sizes
    var query by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var on by remember { mutableStateOf(true) }
    var size by remember { mutableStateOf(13f) }
    Column(Modifier.fillMaxWidth().background(VeloTheme.surface.surface).padding(s.overlayPadding * 2), verticalArrangement = Arrangement.spacedBy(s.overlayPadding * 2)) {
        Title("Menu, popover, palette")
        Row(horizontalArrangement = Arrangement.spacedBy(s.overlayPadding * 2), verticalAlignment = Alignment.Top) {
            MenuSurface { MenuItems {} }
            PopoverSurface {
                Text("workstation", style = VeloTheme.type.overlayTitle, color = VeloTheme.surface.text)
                Text("Connected · 161 × 54", style = VeloTheme.type.body, color = VeloTheme.surface.secondary)
            }
            PaletteSurface(query, { query = it }, onGo = {}) { paletteRows(query) {} }
        }
        Title("Dialogs")
        Row(horizontalArrangement = Arrangement.spacedBy(s.overlayPadding * 2), verticalAlignment = Alignment.Top) {
            DialogSurface("Add Remote", "Add", {}, {}, confirmEnabled = name.isNotBlank()) {
                VeloTextField(name, { name = it }, label = "Address", placeholder = "https://", error = "Already added as workstation")
            }
            DialogSurface("Remove workstation?", "Remove", {}, {}, destructive = true,
                text = "It is removed from this phone only. Its sessions keep running on the computer.")
        }
        Title("Buttons, fields, marks")
        Row(horizontalArrangement = Arrangement.spacedBy(s.menuPadding), verticalAlignment = Alignment.CenterVertically) {
            VeloButton("Log in", {}, kind = ButtonKind.Primary)
            VeloButton("Add by QR Code", {})
            VeloButton("Remove", {}, kind = ButtonKind.Destructive)
            VeloButton("Try Again", {}, enabled = false)
            GlassIconButton(Icons.Default.Delete, "Remove", {})
            GlassIconButton(Icons.Default.Delete, "Remove", {}, selected = true)
            GlassIconButton(Icons.Default.Delete, "Remove", {}, enabled = false)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(s.overlayPadding), verticalAlignment = Alignment.CenterVertically) {
            SearchField(query, { query = it }, Modifier.width(s.sidebar - s.rowInset * 2))
            for ((status, word) in listOf(Status.Ok to "Connected", Status.Warning to "Connecting", Status.Danger to "Not connected", Status.Active to "Working", Status.Off to "Off")) {
                StatusMark(status, word)
                Text(word, style = VeloTheme.type.caption, color = VeloTheme.surface.secondary)
            }
        }
        Title("Settings controls")
        Row(horizontalArrangement = Arrangement.spacedBy(s.overlayPadding * 2), verticalAlignment = Alignment.Top) {
            // On the overlay surface, as in the settings sheet.
            VeloSurface(VeloTheme.colors.overlay) {
                Column(Modifier.width(s.dialogWidth).background(VeloTheme.surface.surface, rounded(VeloTheme.radii.overlay)).padding(s.overlayPadding)) {
                    GroupHeader("Interface")
                    GroupCard {
                        SettingRow("Font") { VeloSegmented(listOf(true, false), on, { if (it) "JetBrains Mono" else "System" }) { on = it } }
                        GroupDivider()
                        Row(Modifier.fillMaxWidth().heightIn(min = s.row), verticalAlignment = Alignment.CenterVertically) {
                            Text("Keep screen on", Modifier.weight(1f))
                            VeloSwitch(on)
                            Spacer(Modifier.width(s.menuPadding))
                            VeloSwitch(!on)
                        }
                        GroupDivider()
                        VeloSlider(size, { size = it }, Modifier.fillMaxWidth(), valueRange = 8f..24f, steps = 15)
                    }
                    GroupHeader("Key bar")
                    GroupCard {
                        Row(Modifier.padding(vertical = s.menuPadding), horizontalArrangement = Arrangement.spacedBy(s.menuPadding)) {
                            KeyChoice("esc", on, { on = !on })
                            KeyChoice("tab", !on, { on = !on })
                            CloseButton({})
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(s.overlayPadding))
    }
}

@Composable
private fun Title(text: String) = Text(text, style = VeloTheme.type.header, color = VeloTheme.surface.secondary)
