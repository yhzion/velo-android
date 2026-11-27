package io.github.yhzion.velo.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.yhzion.velo.ui.spokenKeyName

/**
 * The redesign's Controls, Overlays and tab tile samples, under the test tags the boards give
 * them (`sample-…`, `confirm-dialog`, `popup-menu`, `select-menu`, `toast`, `brand-tile-…`), so
 * that a screenshot and a UI dump of the gallery can be held against the boards.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RedesignSample() {
    val space = VeloTheme.space
    VeloSurface(VeloTheme.colors.sheet) {
        Column(
            Modifier.fillMaxWidth().background(VeloTheme.surface.surface).padding(space.s24),
            verticalArrangement = Arrangement.spacedBy(space.s24),
        ) {
            Heading("Redesign: rows, buttons and controls")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(space.s24), verticalArrangement = Arrangement.spacedBy(space.s24)) {
                Column(Modifier.width(ColumnWidth), verticalArrangement = Arrangement.spacedBy(space.s16)) { Rows(); TabRows(); Keys() }
                Column(Modifier.width(ColumnWidth), verticalArrangement = Arrangement.spacedBy(space.s16)) { Buttons(); Selection(); Fields() }
                Column(Modifier.width(ColumnWidth), verticalArrangement = Arrangement.spacedBy(space.s16)) { Marks(); Tiles(); Card() }
            }
            Heading("Redesign: menus and dialogs")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(space.s24), verticalArrangement = Arrangement.spacedBy(space.s24)) {
                AddRemote()
                Column(Modifier.width(ColumnWidth), verticalArrangement = Arrangement.spacedBy(space.s24)) { Confirm(); Toast() }
                Column(verticalArrangement = Arrangement.spacedBy(space.s24)) { Menus() }
            }
            Heading("Redesign: tab tiles")
            AllTiles()
        }
    }
}

private val ColumnWidth = 360.dp

@Composable
private fun Heading(text: String) = Text(text, style = VeloTheme.type.pageTitle, color = VeloTheme.surface.text)

@Composable
private fun Label(text: String) = Text(text, style = VeloTheme.type.caption, color = VeloTheme.surface.secondary)

@Composable
private fun Rows() {
    var haptics by remember { mutableStateOf(true) }
    var updates by remember { mutableStateOf(false) }
    Label("Settings group: rows 60 high, dividers from 16")
    GroupCard(Modifier.testTag("sample-row-group")) {
        NavigationRow("Font", {}, Modifier.testTag("sample-row-navigation"), value = "JetBrains Mono")
        GroupDivider()
        SwitchRow("Haptic feedback", haptics, { haptics = it }, Modifier.testTag("sample-row-switch"))
        GroupDivider()
        SwitchRow("Check for updates automatically", updates, { updates = it }, Modifier.testTag("sample-row-two-line"),
            subtitle = "When Velo opens, at most every 12 hours")
        GroupDivider()
        ActionRow("Add Remote…", Icons.Default.Add, {}, Modifier.testTag("sample-row-action"))
    }
}

@Composable
private fun TabRows() {
    Label("Sidebar tab rows")
    VeloSurface(VeloTheme.colors.sidebar) {
        val c = VeloTheme.surface
        Column(Modifier.testTag("sample-tab-rows").clip(rounded(VeloTheme.radii.card)).background(c.surface).padding(vertical = VeloTheme.space.s8)) {
            ListRow("Refactor the sync queue", {}, Modifier.testTag("sample-tab-row"), minHeight = VeloTheme.sizes.tabRow,
                leading = { TabTile(TileBrand.Claude, tag = "sample-tab-row-tile") })
            ListRow("Port the parser tests", {}, Modifier.testTag("sample-tab-row-selected"), selected = true, strong = true, minHeight = VeloTheme.sizes.tabRow,
                leading = { TabTile(TileBrand.Codex, panes = 2, ring = c.selected, tag = "sample-tab-row-selected-tile") },
                trailing = { RowIconButton(Icons.Default.Close, "Close tab", {}) })
            ListRow("Review the release notes", {}, Modifier.testTag("sample-tab-row-attention"), minHeight = VeloTheme.sizes.tabRow,
                leading = { TabTile(TileBrand.Claude, panes = 3, tag = "sample-tab-row-attention-tile") },
                trailing = { StatusMark(Status.Warning, "Waiting for input") })
        }
    }
}

@Composable
private fun Keys() {
    var tab by remember { mutableStateOf(true) }
    var home by remember { mutableStateOf(false) }
    Label("Key bar keys and key chips")
    VeloSurface(VeloTheme.colors.chrome) {
        KeyStrip(Modifier.testTag("sample-key-strip")) {
            KeyCap("Esc", {}, Modifier.testTag("sample-key"), contentDescription = spokenKeyName("Esc"))
            KeyCap("Ctrl", {}, Modifier.testTag("sample-key-latched"), armed = true, contentDescription = spokenKeyName("Ctrl"))
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(VeloTheme.space.s8)) {
        KeyChoice("Tab", tab, { tab = !tab }, Modifier.testTag("sample-key-chip-on"))
        KeyChoice("Home", home, { home = !home }, Modifier.testTag("sample-key-chip-off"))
    }
}

@Composable
private fun Buttons() {
    Label("Buttons: 48 high, one primary per screen")
    VeloButton("Download update", {}, Modifier.fillMaxWidth().testTag("sample-button-primary"), ButtonKind.Primary,
        icon = rememberVectorPainter(Icons.Default.Download))
    VeloButton("Search again", {}, Modifier.fillMaxWidth().testTag("sample-button-secondary"))
    VeloButton("Log out", {}, Modifier.fillMaxWidth().testTag("sample-button-destructive"), ButtonKind.Destructive)
    VeloButton("Connect", {}, Modifier.fillMaxWidth().testTag("sample-button-disabled"), ButtonKind.Primary, enabled = false)
    Row(horizontalArrangement = Arrangement.spacedBy(VeloTheme.space.s12), verticalAlignment = Alignment.CenterVertically) {
        VeloButton("Check for updates", {}, Modifier.testTag("sample-button-text"), ButtonKind.Text)
        VeloButton("Connect", {}, Modifier.testTag("sample-button-small"), ButtonKind.Primary, small = true)
        GlassIconButton(Icons.Default.Close, "Close", {}, Modifier.testTag("sample-icon-button"))
    }
}

@Composable
private fun Selection() {
    var size by remember { mutableStateOf(UiSize.Medium) }
    var channel by remember { mutableStateOf("Stable") }
    var menu by remember { mutableStateOf(false) }
    var level by remember { mutableStateOf(0.4f) }
    Label("Switches: on, off, cannot be used")
    Row(horizontalArrangement = Arrangement.spacedBy(VeloTheme.space.s16)) {
        VeloSwitch(true, Modifier.testTag("sample-switch-on"))
        VeloSwitch(false, Modifier.testTag("sample-switch-off"))
        VeloSwitch(true, Modifier.testTag("sample-switch-disabled"), enabled = false)
    }
    Label("Segmented: the chosen cell is the lighter glass")
    VeloSegmented(UiSize.entries, size, { it.name }, Modifier.testTag("sample-segmented"),
        optionTag = { if (it == size) "sample-segmented-selected" else "sample-segmented-${it.saved}" }) { size = it }
    Label("Menu button and slider")
    Row(horizontalArrangement = Arrangement.spacedBy(VeloTheme.space.s16), verticalAlignment = Alignment.CenterVertically) {
        Box {
            MenuButton(channel, { menu = true }, Modifier.testTag("sample-menu-button"), contentDescription = "Channel, $channel")
            SelectMenu(menu, { menu = false }, listOf("Stable", "Beta"), channel, { it }, { channel = it }, Modifier.testTag("sample-select-menu"))
        }
        VeloSlider(level, { level = it }, Modifier.weight(1f).testTag("sample-slider"))
    }
}

@Composable
private fun Fields() {
    var search by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("ssh://dev@studio-mac") }
    var wrong by remember { mutableStateOf("studio mac") }
    Label("Fields: as it is, typing, wrong")
    VeloTextField(search, { search = it }, Modifier.testTag("sample-text-field"), placeholder = "Search fonts")
    VeloTextField(address, { address = it }, Modifier.testTag("sample-text-field-focused"), mono = true)
    VeloTextField(wrong, { wrong = it }, Modifier.testTag("sample-text-field-error"), mono = true,
        error = "An address can't contain spaces.", tagPrefix = "sample-text-field-error")
}

@Composable
private fun Marks() {
    val c = VeloTheme.surface
    Label("Marks")
    Row(horizontalArrangement = Arrangement.spacedBy(VeloTheme.space.s16), verticalAlignment = Alignment.CenterVertically) {
        AttentionBadge(c.accent, Modifier.size(VeloTheme.sizes.dot).testTag("sample-update-dot"))
        StatusMark(Status.Ok, "Connected", Modifier.testTag("sample-status-dot"))
        StateChip("Connected", Status.Ok, Modifier.testTag("sample-state-chip"))
        TagBadge("Apache-2.0", Modifier.testTag("sample-license-badge"))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(VeloTheme.space.s16), verticalAlignment = Alignment.CenterVertically) {
        Eyebrow("NEW VERSION", Modifier.testTag("sample-eyebrow"))
        Text("2.10.1 available", Modifier.testTag("sample-available-label"), style = VeloTheme.type.value, fontWeight = FontWeight.SemiBold, color = c.accent)
    }
    WarningNote("Enter sends whatever is typed in the terminal right away.", Modifier.testTag("sample-warning"))
    Label("Category icons")
    Row(Modifier.testTag("sample-category-icons"), horizontalArrangement = Arrangement.spacedBy(VeloTheme.space.s12)) {
        CategoryIcon(rememberVectorPainter(Icons.Outlined.WbSunny), CategoryTints.appearance, Modifier.testTag("sample-category-appearance"))
        CategoryIcon(rememberVectorPainter(Icons.Outlined.Keyboard), CategoryTints.keyboard, Modifier.testTag("sample-category-keyboard"))
        CategoryIcon(rememberVectorPainter(Icons.Outlined.Notifications), CategoryTints.notifications, Modifier.testTag("sample-category-notifications"))
        CategoryIcon(rememberVectorPainter(Icons.Outlined.Computer), CategoryTints.remotes, Modifier.testTag("sample-category-remotes"))
        CategoryIcon(rememberVectorPainter(Icons.Outlined.Info), CategoryTints.about, Modifier.testTag("sample-category-about"))
    }
}

@Composable
private fun Tiles() {
    val size = VeloTheme.medium(36.dp)
    Label("Tab tiles: kinds, then 2, 3, 4 and 6 panes and an unread dot")
    Row(horizontalArrangement = Arrangement.spacedBy(VeloTheme.medium(18.dp)), verticalAlignment = Alignment.Bottom) {
        TabTile(TileBrand.Claude, size = size, tag = "sample-tile-claude")
        TabTile(TileBrand.Codex, size = size, tag = "sample-tile-codex")
        TabTile(TileBrand.Neovim, size = size, tag = "sample-tile-tool")
        TabTile(TileBrand.Azure, size = size, tag = "sample-tile-letter")
        TabTile(TileBrand.Terminal, size = size, tag = "sample-tile-default")
    }
    Row(horizontalArrangement = Arrangement.spacedBy(VeloTheme.medium(18.dp)), verticalAlignment = Alignment.Bottom) {
        TabTile(TileBrand.Claude, panes = 2, size = size, tag = "sample-tile-stack-2")
        TabTile(TileBrand.Claude, panes = 3, size = size, tag = "sample-tile-stack-3")
        TabTile(TileBrand.Claude, panes = 4, size = size, tag = "sample-tile-stack-4")
        TabTile(TileBrand.Claude, panes = 6, size = size, tag = "sample-tile-stack-6")
        TabTile(TileBrand.Gemini, size = size, dot = TileDot.Unread, tag = "sample-tile-badge")
    }
}

@Composable
private fun Card() {
    Label("Raised card")
    RaisedCard(Modifier.testTag("sample-raised-card")) {
        Column(verticalArrangement = Arrangement.spacedBy(VeloTheme.space.s4)) {
            Eyebrow("NEW VERSION")
            Text("Velo 2.10.1", style = VeloTheme.type.pageTitle.copy(fontSize = VeloTheme.type.pageTitle.fontSize * (24f / 22f)), color = VeloTheme.surface.text)
            Text("You have 2.10.0 · Stable", style = VeloTheme.type.caption, color = VeloTheme.surface.secondary)
        }
        Text("Velo finds the computers on your tailnet that run Rex.", style = VeloTheme.type.body, color = VeloTheme.colors.textOnRaised)
        VeloButton("Download update", {}, Modifier.fillMaxWidth(), ButtonKind.Primary)
    }
}

@Composable
private fun AddRemote() {
    var label by remember { mutableStateOf("Studio Mac") }
    var address by remember { mutableStateOf("ssh://dev@studio-mac.example.ts.net") }
    DialogSurface("Add Remote", "Add", {}, {}, Modifier.width(VeloTheme.medium(386.dp)).testTag("add-remote-dialog"), tagPrefix = "add-remote", keyFocus = { false }) {
        VeloTextField(label, { label = it }, label = "Label", tagPrefix = "add-remote-label")
        VeloTextField(
            address, { address = it }, label = "Address", mono = true, help = "Type the address, ssh:// login, or socket path", tagPrefix = "add-remote-address",
            trailing = {
                Box(
                    Modifier.size(VeloTheme.medium(40.dp)).clip(Capsule).background(VeloTheme.surface.glassSelected).testTag("add-remote-scan-qr"),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Default.QrCodeScanner, "Scan QR code", Modifier.size(VeloTheme.medium(18.dp)), tint = VeloTheme.surface.onGlass) }
            },
        )
        GroupCard(Modifier.testTag("add-remote-examples")) {
            Column(Modifier.padding(vertical = VeloTheme.space.s12), verticalArrangement = Arrangement.spacedBy(VeloTheme.medium(6.dp))) {
                Eyebrow("EXAMPLES", color = VeloTheme.surface.secondary)
                for (example in listOf("https://rex.example.ts.net", "ssh://user@rex.example.ts.net")) {
                    Text(example, style = VeloTheme.type.caption, fontFamily = FontFamily.Monospace, color = VeloTheme.surface.text)
                }
            }
        }
    }
}

@Composable
private fun Confirm() {
    DialogSurface(
        "Remove studio-mac?", "Remove", {}, {}, Modifier.testTag("confirm-dialog"), destructive = true, keyFocus = { false },
        text = "Its tabs close on this phone. Nothing changes on the computer.", tagPrefix = "confirm-dialog",
    )
}

@Composable
private fun Toast() {
    ToastSurface("Install command copied", icon = Icons.Default.Check, actionLabel = "Share", onAction = {})
}

@Composable
private fun Menus() {
    MenuSurface(Modifier.width(VeloTheme.medium(240.dp)).testTag("popup-menu")) {
        MenuItem("Rename…", {}, Modifier.testTag("popup-menu-item-rename"), icon = Icons.Default.Edit)
        MenuItem("SSH details", {}, Modifier.testTag("popup-menu-item-pressed"), icon = Icons.Default.Terminal)
        MenuDivider()
        MenuItem("Remove Remote…", {}, Modifier.testTag("popup-menu-item-destructive"), icon = Icons.Default.Delete, destructive = true)
    }
    MenuSurface(Modifier.width(VeloTheme.medium(240.dp)).testTag("select-menu")) {
        SelectMenuItem("Bottom Left", true, {}, Modifier.testTag("select-menu-option-bottom-left"))
        SelectMenuItem("Bottom Right", false, {}, Modifier.testTag("select-menu-option-bottom-right"))
        SelectMenuItem("Hidden", false, {}, Modifier.testTag("select-menu-option-hidden"))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AllTiles() {
    VeloSurface(VeloTheme.colors.sidebar) {
        Column(
            Modifier.fillMaxWidth().clip(rounded(VeloTheme.radii.card)).background(VeloTheme.surface.surface).padding(VeloTheme.space.s24),
            verticalArrangement = Arrangement.spacedBy(VeloTheme.space.s16),
        ) {
            for (brand in listOf(TileBrand.Claude, TileBrand.Codex, TileBrand.Gemini, TileBrand.Neovim, TileBrand.Terminal)) {
                Row(horizontalArrangement = Arrangement.spacedBy(VeloTheme.space.s24), verticalAlignment = Alignment.CenterVertically) {
                    for (panes in listOf(1, 2, 3, 4, 6)) {
                        TabTile(brand, panes = panes, size = VeloTheme.medium(44.dp), tag = "tab-tile-${brand.name.lowercase()}-$panes")
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(VeloTheme.space.s16), verticalArrangement = Arrangement.spacedBy(VeloTheme.space.s16)) {
                for (brand in TileBrand.entries) {
                    Row(Modifier.width(VeloTheme.medium(150.dp)), horizontalArrangement = Arrangement.spacedBy(VeloTheme.space.s12), verticalAlignment = Alignment.CenterVertically) {
                        TabTile(brand, size = VeloTheme.medium(36.dp), tag = "brand-tile-${brand.name.lowercase()}", contentDescription = null)
                        Text(brand.label, style = VeloTheme.type.value, color = VeloTheme.surface.text)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(VeloTheme.space.s24), verticalAlignment = Alignment.CenterVertically) {
                TabTile(TileBrand.Gemini, dot = TileDot.Unread, tag = "state-tile-unread")
                TabTile(TileBrand.Claude, panes = 3, dot = TileDot.Waiting, tag = "state-tile-attention")
                TabTile(TileBrand.Codex, panes = 2, dot = TileDot.Failed, tag = "state-tile-failed")
                TabTile(TileBrand.Claude, panes = 12, dot = TileDot.Unread, tag = "state-tile-many")
            }
        }
    }
}
