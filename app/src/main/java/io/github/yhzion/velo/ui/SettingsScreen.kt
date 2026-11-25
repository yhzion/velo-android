package io.github.yhzion.velo.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.yhzion.velo.ActivitySound
import io.github.yhzion.velo.PaneAlertKind
import io.github.yhzion.velo.NotificationAccess
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import io.github.yhzion.velo.R
import io.github.yhzion.velo.AppController
import io.github.yhzion.velo.Settings
import io.github.yhzion.velo.Tailnet
import io.github.yhzion.velo.TailnetState
import io.github.yhzion.velo.core.isSshLocator
import io.github.yhzion.velo.findLicense
import io.github.yhzion.velo.licenseParent
import io.github.yhzion.velo.ui.theme.AttentionBadge
import io.github.yhzion.velo.ui.theme.ButtonKind
import io.github.yhzion.velo.ui.theme.CloseButton
import io.github.yhzion.velo.ui.theme.GlassIconButton
import io.github.yhzion.velo.ui.theme.GroupCard
import io.github.yhzion.velo.ui.theme.GroupDivider
import io.github.yhzion.velo.ui.theme.GroupHeader
import io.github.yhzion.velo.ui.theme.KeyChoice
import io.github.yhzion.velo.ui.theme.ListRow
import io.github.yhzion.velo.ui.theme.MenuItem
import io.github.yhzion.velo.ui.theme.OverlayHost
import io.github.yhzion.velo.ui.theme.VeloSurface
import io.github.yhzion.velo.ui.theme.SettingsPanelSurface
import io.github.yhzion.velo.ui.theme.VeloButton
import io.github.yhzion.velo.ui.theme.VeloDialog
import io.github.yhzion.velo.ui.theme.VeloMenu
import io.github.yhzion.velo.ui.theme.VeloSegmented
import io.github.yhzion.velo.ui.theme.VeloSlider
import io.github.yhzion.velo.ui.theme.VeloSwitch
import io.github.yhzion.velo.ui.theme.VeloTextField
import io.github.yhzion.velo.ui.theme.VeloTheme
import io.github.yhzion.velo.ui.theme.RowIconButton
import io.github.yhzion.velo.ui.theme.SettingRow
import io.github.yhzion.velo.ui.theme.UiSize
import kotlin.math.roundToInt

// Settings remains a modal over the originating screen. Navigation borrows the workspace
// sidebar; the detail area uses pane chrome, with glass reserved for interactive controls.

/**
 * This Device's own screen: the Tailscale node's controls. The sidebar's This Device row opens
 * it; the settings sheet shows the same controls inside itself.
 */
@Composable
fun TailscaleScreen(app: AppController, onBack: () -> Unit) = TailscaleScreen(remember(app) { AppSettingsHost(app) }, onBack)

@Composable
internal fun TailscaleScreen(host: SettingsHost, onBack: () -> Unit) {
    val sizes = VeloTheme.sizes
    // Clear of the soft keyboard and the system bars: the node name and Log out stay reachable.
    Column(Modifier.fillMaxSize().background(VeloTheme.surface.surface).windowInsetsPadding(WindowInsets.screenSafeDrawing)
        .verticalScroll(rememberScrollState()).padding(sizes.rowPadding)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlassIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack)
            Text("This Device", Modifier.padding(start = sizes.rowGap).semantics { heading() }, style = VeloTheme.type.screenTitle)
        }
        DeviceSettings(host)
    }
}

private const val TAILSCALE_MACHINES_URL = "https://console.tailscale.com/admin/machines"

/** Settings categories with their colored icons. */
private enum class SettingsCategory(val title: String, @DrawableRes val icon: Int) {
    Appearance("Appearance", R.drawable.ic_settings_appearance),
    Keyboard("Keyboard", R.drawable.ic_settings_keyboard),
    Notifications("Notifications", R.drawable.ic_settings_notifications),
    Remotes("Remotes", R.drawable.ic_settings_remotes),
    About("About", R.drawable.ic_settings_about),
}

private enum class SettingsLevel(val title: String) {
    Device("This Device"), Font("Terminal Font"), Updates("Updates"), SshKey(SSH_KEY_ROW),
    Licenses("Open source licenses"), License("License"),
}

/**
 * The settings, as a workspace panel over the screen. On the unfolded screen the categories are
 * beside the one that is open; on the folded screen the categories come first and a category
 * takes their place. This Device, Terminal Font and SSH key open inside the sheet. Back goes up one level;
 * the close button, a tap beside the sheet and Back at the top close it ([onClose]).
 */
@Composable
fun SettingsSheet(app: AppController, onClose: () -> Unit) = SettingsSheet(remember(app) { AppSettingsHost(app) }, onClose)

@Composable
internal fun SettingsSheet(host: SettingsHost, onClose: () -> Unit) {
    val sizes = VeloTheme.sizes
    var category by rememberSaveable { mutableStateOf<SettingsCategory?>(null) }
    var level by rememberSaveable { mutableStateOf<SettingsLevel?>(null) }
    // The open license page: a component's id, or a Go module's inside the Go modules page.
    var license by rememberSaveable { mutableStateOf<String?>(null) }
    val licensed = license?.takeIf { level == SettingsLevel.License }?.let(::findLicense)
    val scrollStates = rememberSaveableStateHolder()
    val context = LocalContext.current
    val about = remember(context) {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${context.applicationInfo.loadLabel(context.packageManager)} ${info.versionName} (${info.longVersionCode})"
    }

    val window = LocalConfiguration.current
    val expanded = !isCompact(window.screenWidthDp.toFloat(), window.screenHeightDp.toFloat())
    val shown = category ?: SettingsCategory.Appearance
    val listShown = expanded || (category == null && level == null)
    val detailShown = expanded || category != null || level != null
    val back: (() -> Unit)? = when {
        // A license page goes back to the page that listed it: the Go modules, or all licenses.
        level == SettingsLevel.License -> ({
            val parent = license?.let(::licenseParent)
            if (parent != null) license = parent.id else level = SettingsLevel.Licenses
        })
        level != null -> ({ level = null })
        !expanded && category != null -> ({ category = null })
        else -> null
    }
    val title = when {
        licensed != null -> licensed.name
        level != null -> level!!.title
        !expanded && category != null -> shown.title
        else -> "Settings"
    }

    OverlayHost(onDismissRequest = onClose) {
        BackHandler(enabled = back != null) { back?.invoke() }
        SettingsPanelSurface(
            Modifier.align(Alignment.Center).padding(sizes.overlayMargin)
                .then(if (expanded) Modifier.widthIn(max = sizes.sheetWidth) else Modifier)
                .fillMaxSize(),
        ) {
            Column(Modifier.fillMaxSize()) {
                SheetHeader(title, back, onClose)
                GroupDivider()
                Row(Modifier.fillMaxWidth().weight(1f)) {
                    if (listShown) {
                        VeloSurface(VeloTheme.colors.sidebar) {
                            scrollStates.SaveableStateProvider("categories") {
                                CategoryList(
                                    selected = shown.takeIf { expanded }, chevrons = !expanded,
                                    onSelect = { category = it; level = null },
                                    modifier = if (expanded) Modifier.width(sizes.sheetList) else Modifier.weight(1f),
                                )
                            }
                        }
                    }
                    if (expanded) Spacer(Modifier.fillMaxHeight().width(sizes.edge).background(VeloTheme.surface.divider))
                    if (detailShown) {
                        // Each license page keeps its own place in its text.
                        scrollStates.SaveableStateProvider(licensed?.let { "License:${it.id}" } ?: level?.name ?: shown.name) {
                            val detail = Modifier.weight(1f).fillMaxHeight().padding(horizontal = sizes.overlayPadding)
                            // The font list and a license's text scroll themselves, so their levels are not inside the sheet's scroll.
                            if (level == SettingsLevel.Font) TerminalFontSettings(host, detail.padding(top = sizes.menuPadding))
                            else if (licensed != null) LicenseDetail(licensed, open = { license = it }, modifier = detail)
                            else Column(
                                detail.verticalScroll(rememberScrollState()).padding(bottom = sizes.overlayPadding),
                            ) {
                                if (level == SettingsLevel.Device) DeviceSettings(host)
                                else if (level == SettingsLevel.Updates) AppUpdateSettings()
                                else if (level == SettingsLevel.SshKey) SshKeySettings(host.sshSecrets)
                                // A license that is no longer listed falls back to the list.
                                else if (level == SettingsLevel.Licenses || level == SettingsLevel.License) {
                                    OpenSourceLicenseList(open = { license = it; level = SettingsLevel.License })
                                } else when (shown) {
                                    SettingsCategory.Appearance -> AppearanceSettings(host, openFont = { category = shown; level = SettingsLevel.Font })
                                    SettingsCategory.Keyboard -> KeyboardSettings(host.settings)
                                    SettingsCategory.Notifications -> ActivitySettings(host)
                                    SettingsCategory.Remotes -> RemoteSettings(host, openDevice = { level = SettingsLevel.Device },
                                        openSshKey = { category = shown; level = SettingsLevel.SshKey })
                                    SettingsCategory.About -> GroupCard(Modifier.padding(top = sizes.menuPadding)) {
                                        Box(Modifier.fillMaxWidth().heightIn(min = sizes.row), contentAlignment = Alignment.CenterStart) {
                                            Text(about)
                                        }
                                        GroupDivider()
                                        UpdatesRow(onClick = { category = shown; level = SettingsLevel.Updates })
                                        GroupDivider()
                                        ListRow("Open source licenses", onClick = { category = shown; level = SettingsLevel.Licenses })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * About's Updates row. While a newer release is found it says "X.Y.Z available" until that build is
 * installed, with the dot until the Updates page has shown it ([UpdateStatus]).
 */
@Composable
private fun UpdatesRow(onClick: () -> Unit) {
    val status = rememberUpdateStatus()
    val installed = rememberInstalledCode()
    val available = status.available(installed)
    val dot = status.unseen(installed)
    val c = VeloTheme.surface
    ListRow(
        "Updates", onClick = onClick,
        modifier = Modifier.semantics { if (available != null) stateDescription = "$available available" },
        trailing = if (available == null) null else {
            {
                Text("$available available", style = VeloTheme.type.body, color = c.accent, maxLines = 1)
                if (dot) AttentionBadge(c.accent, Modifier.size(VeloTheme.sizes.mark))
            }
        },
    )
}

/** The top of the sheet: Back where there is a level above, the title, and the close button (AK). */
@Composable
private fun SheetHeader(title: String, back: (() -> Unit)?, onClose: () -> Unit) {
    val sizes = VeloTheme.sizes
    Row(
        Modifier.fillMaxWidth().padding(horizontal = sizes.overlayPadding, vertical = sizes.menuPadding),
        horizontalArrangement = Arrangement.spacedBy(sizes.rowGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (back != null) GlassIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", back)
        Text(
            title,
            Modifier.weight(1f).semantics { heading() },
            style = VeloTheme.type.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        CloseButton(onClose, contentDescription = "Close Settings")
    }
}

/** The categories, as rows on the sheet. The one [selected] has the pill; [chevrons] says a row opens a level. */
@Composable
private fun CategoryList(
    selected: SettingsCategory?,
    chevrons: Boolean,
    onSelect: (SettingsCategory) -> Unit,
    modifier: Modifier,
) {
    val c = VeloTheme.surface
    val sizes = VeloTheme.sizes
    Column(
        modifier.fillMaxHeight().background(c.surface).verticalScroll(rememberScrollState()).padding(sizes.menuPadding),
        verticalArrangement = Arrangement.spacedBy(sizes.cellInset),
    ) {
        for (category in SettingsCategory.entries) {
            ListRow(
                category.title, onClick = { onSelect(category) }, selected = category == selected, strong = true,
                leading = { Image(painterResource(category.icon), null, Modifier.size(sizes.icon)) },
                trailing = if (chevrons) {
                    { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(sizes.iconSmall), tint = c.secondary) }
                } else null,
            )
        }
    }
}

private val PaneStyles = listOf("comfortable", "compact")

@Composable
private fun ActivitySettings(host: SettingsHost) {
    val settings = host.settings
    val context = LocalContext.current
    val system = host.notifications
    var blocked by remember { mutableStateOf(emptyList<PaneAlertKind>()) }
    fun refresh() {
        system.createChannels()
        blocked = PaneAlertKind.entries.filter { system.access(it) != NotificationAccess.Allowed }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }
    LifecycleResumeEffect(Unit) { refresh(); onPauseOrDispose { } }
    GroupHeader("Program activity")
    GroupCard {
        Toggle("Show background notifications", settings.activityNotices, settings::saveActivityNotices)
        if (settings.activityNotices && blocked.isNotEmpty()) {
            val kind = blocked.first()
            if (Build.VERSION.SDK_INT >= 33 && system.access(kind) == NotificationAccess.RequestPermission) {
                VeloButton("Allow notifications", { permission.launch(Manifest.permission.POST_NOTIFICATIONS) })
            }
            VeloButton("Open Android settings", { context.startActivity(system.settingsIntent(kind)) })
        }
        GroupDivider()
        Toggle("Play activity sounds", settings.activitySounds, settings::saveActivitySounds)
        if (settings.activitySounds) {
            Text("Sound volume · ${(settings.activityVolume * 100).roundToInt()}%")
            VeloSlider(settings.activityVolume, settings::saveActivityVolume, valueRange = 0f..2f, modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = "Activity sound volume"
            })
        }
    }
    GroupHeader("Background")
    GroupCard {
        BackgroundSettings(host)
    }
    for (kind in PaneAlertKind.entries) {
        // Rex for Mac marks each kind with its own coloured badge in Notifications.
        Row(
            Modifier.padding(horizontal = VeloTheme.sizes.overlayPadding, vertical = VeloTheme.sizes.menuPadding).semantics(mergeDescendants = true) { heading() },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(VeloTheme.sizes.rowGap),
        ) {
            Image(painterResource(alertIcon(kind)), null, Modifier.size(VeloTheme.sizes.iconSmall))
            Text(kind.label, style = VeloTheme.type.header, color = VeloTheme.surface.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        GroupCard {
            Toggle("Background notification", settings.posts(kind)) { settings.savePosts(kind, it) }
            GroupDivider()
            Toggle("Activity sound", settings.plays(kind)) { settings.savePlays(kind, it) }
            if (settings.plays(kind)) {
                var chooseSound by remember { mutableStateOf(false) }
                Box {
                    VeloButton("Sound · ${settings.soundFor(kind).name}", { chooseSound = true })
                    VeloMenu(chooseSound, onDismissRequest = { chooseSound = false }) {
                        for (sound in ActivitySound.entries) {
                            MenuItem(sound.name, {
                                chooseSound = false
                                settings.saveSound(kind, sound)
                                if (settings.activitySounds) host.notifications.play(kind, sound, settings.activityVolume)
                            })
                        }
                    }
                }
            }
        }
    }
}

/** The badge shown beside each kind of pane notification. */
@DrawableRes
private fun alertIcon(kind: PaneAlertKind): Int = when (kind) {
    PaneAlertKind.Blocked -> R.drawable.ic_alert_blocked
    PaneAlertKind.Done -> R.drawable.ic_alert_done
    PaneAlertKind.Error -> R.drawable.ic_alert_error
    PaneAlertKind.Working -> R.drawable.ic_alert_working
}

@Composable
private fun AppearanceSettings(host: SettingsHost, openFont: () -> Unit) {
    val s = host.settings
    GroupHeader("Interface")
    GroupCard {
        SettingRow("Size") { VeloSegmented(UiSize.entries, s.uiSize, { it.name }, onPick = s::saveUiSize) }
        GroupDivider()
        SettingRow("Pane style") {
            VeloSegmented(PaneStyles, s.paneStyle, { it.replaceFirstChar(Char::uppercaseChar) }) {
                s.savePaneStyle(it)
                host.pushDisplay()
            }
        }
        GroupDivider()
        OverviewLayoutSetting(s)
        GroupDivider()
        CapsuleCornerRow(shownCorner(s.capsuleCorner), s::saveCapsuleCorner)
        GroupDivider()
        Toggle("Keep screen on while using Velo", s.keepScreenOn, s::saveKeepScreenOn)
    }
    PaneHighlightSettings(host.settings)
    GroupHeader("Terminal")
    GroupCard {
        TerminalFontSetting(host.settings, onOpen = openFont)
        GroupDivider()
        FontSizeSetting(host)
    }
}

/** The terminal's font size: its value beside its name, and a slider under them that has the row's width. */
@Composable
private fun FontSizeSetting(host: SettingsHost) {
    val s = host.settings
    val sizes = VeloTheme.sizes
    val value = fontSizeValue(s.fontSize)
    Column(Modifier.fillMaxWidth().padding(vertical = sizes.cellInset)) {
        Row(Modifier.fillMaxWidth().heightIn(min = sizes.control), verticalAlignment = Alignment.CenterVertically) {
            Text("Font size", Modifier.weight(1f))
            Text(value, color = VeloTheme.surface.secondary)
        }
        VeloSlider(
            s.fontSize, { s.saveFontSize(it.roundToInt().toFloat()) },
            Modifier.fillMaxWidth().semantics {
                contentDescription = "Font size"
                stateDescription = value
            },
            valueRange = 8f..24f, steps = 15,
            onValueChangeFinished = { host.pushSetting("fontSize", s.fontSize) },
        )
        FontSizePreview(host)
    }
}

/** The font size as a whole number: the text beside the slider and the slider's own state say it alike. */
fun fontSizeValue(size: Float): String = size.roundToInt().toString()

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeyboardSettings(s: Settings) {
    val sizes = VeloTheme.sizes
    var editingOrder by rememberSaveable { mutableStateOf(false) }
    val ordering = editingOrder && s.keyBar.size >= 2
    GroupHeader("Key bar")
    GroupCard {
        if (s.keyBar.size >= 2) {
            SettingRow("Order") { VeloButton(if (ordering) "Done" else "Edit order", { editingOrder = !editingOrder }) }
            GroupDivider()
        }
        if (ordering) {
            s.keyBar.forEachIndexed { index, name ->
                key(name) {
                    if (index > 0) GroupDivider()
                    Row(Modifier.fillMaxWidth().heightIn(min = sizes.row), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f).semantics { contentDescription = name }) {
                            KeyLegend(name, VeloTheme.surface.text)
                        }
                        if (index > 0) RowIconButton(Icons.Default.KeyboardArrowUp, "Move ${keyName(name)} earlier",
                            { s.saveKeyBar(moveKey(s.keyBar, name, -1)) })
                        else Spacer(Modifier.width(sizes.control))
                        if (index < s.keyBar.lastIndex) RowIconButton(Icons.Default.KeyboardArrowDown, "Move ${keyName(name)} later",
                            { s.saveKeyBar(moveKey(s.keyBar, name, 1)) })
                        else Spacer(Modifier.width(sizes.control))
                    }
                }
            }
        } else {
            // A saved key that is no longer offered must still be removable.
            val offered = Settings.ALL_KEYS + s.keyBar.filter { it !in Settings.ALL_KEYS }.distinct()
            Column(Modifier.padding(vertical = sizes.menuPadding), verticalArrangement = Arrangement.spacedBy(sizes.menuPadding)) {
                for (group in KeyGroup.entries) {
                    val keys = offered.filter { keyGroup(it) == group }
                    if (keys.isEmpty()) continue
                    Text(group.caption, Modifier.semantics { heading() }, style = VeloTheme.type.caption, color = VeloTheme.surface.secondary)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(sizes.menuPadding),
                        verticalArrangement = Arrangement.spacedBy(sizes.menuPadding),
                    ) {
                        for (choice in keys) {
                            KeyChoice(choice, choice in s.keyBar, { s.saveKeyBar(toggleKey(s.keyBar, choice)) })
                        }
                    }
                }
            }
        }
    }
    GroupHeader("Typing")
    GroupCard {
        Toggle("Keep keyboard open", s.keepKeyboard, s::saveKeepKeyboard)
        GroupDivider()
        Toggle("Haptic feedback", s.haptics, s::saveHaptics)
    }
    TapGestureSettings(s)
}

/**
 * Tap gestures on a terminal: off until turned on. Each of the double and the triple tap sends a
 * key the user picks, kept on this phone apart from the key bar.
 */
@Composable
private fun TapGestureSettings(s: Settings) {
    GroupHeader("Tap gestures")
    GroupCard {
        Toggle("Use tap gestures", s.tapGestures, s::saveTapGestures)
        if (s.tapGestures) {
            GroupDivider()
            TapKeyRow("Double tap", s.doubleTapKey, s::saveDoubleTapKey)
            GroupDivider()
            TapKeyRow("Triple tap", s.tripleTapKey, s::saveTripleTapKey)
        }
    }
}

/** The key one tap gesture sends: its name and the chosen key, which a menu changes. */
@Composable
private fun TapKeyRow(label: String, key: TapKey, onPick: (TapKey) -> Unit) {
    var choosing by remember { mutableStateOf(false) }
    SettingRow(label) {
        Box {
            VeloButton(key.label, { choosing = true }, modifier = Modifier.semantics { contentDescription = "$label: ${key.label}" })
            VeloMenu(choosing, onDismissRequest = { choosing = false }) {
                for (choice in TapKey.entries) {
                    MenuItem(choice.label, { choosing = false; onPick(choice) })
                }
            }
        }
    }
}

@Composable
private fun RemoteSettings(host: SettingsHost, openDevice: () -> Unit, openSshKey: () -> Unit) {
    var adding by rememberSaveable { mutableStateOf(false) }
    var removingId by rememberSaveable { mutableStateOf<String?>(null) }
    var renamingId by rememberSaveable { mutableStateOf<String?>(null) }
    var detailsId by rememberSaveable { mutableStateOf<String?>(null) }
    val sizes = VeloTheme.sizes
    val c = VeloTheme.surface

    GroupHeader("This Device")
    GroupCard {
        Row(
            Modifier.fillMaxWidth().heightIn(min = sizes.row).clickable(role = Role.Button, onClick = openDevice),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(sizes.rowGap),
        ) {
            // Distinguish a running node from a stopped one.
            Icon(painterResource(if (host.tailnetState is TailnetState.Running) R.drawable.ic_tailscale_on else R.drawable.ic_tailscale_off),
                null, Modifier.size(sizes.icon), tint = c.text)
            Text("Tailscale", Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(sizes.iconSmall), tint = c.secondary)
        }
    }
    // The key ssh:// logins offer first: one for the phone, whatever remote it logs in to.
    GroupHeader(SSH_GROUP)
    GroupCard {
        Row(
            Modifier.fillMaxWidth().heightIn(min = sizes.row).clickable(role = Role.Button, onClick = openSshKey),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(sizes.rowGap),
        ) {
            Icon(Icons.Default.Key, null, Modifier.size(sizes.icon), tint = c.text)
            Text(SSH_KEY_ROW, Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(sizes.iconSmall), tint = c.secondary)
        }
    }
    GroupHeader("Saved")
    if (host.saved.isNotEmpty()) GroupCard {
        host.saved.forEachIndexed { index, remote ->
            key(remote.id) {
                if (index > 0) GroupDivider()
                var menu by remember { mutableStateOf(false) }
                val name = remote.label.ifEmpty { "Remote" }
                Row(Modifier.fillMaxWidth().heightIn(min = sizes.row), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(vertical = sizes.menuPadding)) {
                        Text(name, style = VeloTheme.type.bodyStrong)
                        Text(remote.url, style = VeloTheme.type.caption, color = c.secondary)
                    }
                    Box {
                        RowIconButton(Icons.Default.MoreHoriz, "Actions for $name", { menu = true })
                        VeloMenu(menu, { menu = false }) {
                            // An ssh remote shows its SSH details, as in the sidebar's menu.
                            for (item in savedRemoteMenu(isSshLocator(remote.url))) MenuItem(item.label, onClick = {
                                menu = false
                                when (item.action) {
                                    RemoteAction.Rename -> renamingId = remote.id
                                    RemoteAction.SshDetails -> detailsId = remote.id
                                    RemoteAction.Remove -> removingId = remote.id
                                    RemoteAction.NewSession -> Unit
                                }
                            }, icon = remoteActionIcon(item.action), enabled = item.enabled, destructive = item.action == RemoteAction.Remove)
                        }
                    }
                }
                // The remote's ports forwarded to the phone (#226): they stay until stopped here.
                for (forward in host.forwards(remote)) {
                    key(forward.block) {
                        GroupDivider()
                        Row(Modifier.fillMaxWidth().heightIn(min = sizes.row).padding(start = sizes.rowGap), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).padding(vertical = sizes.menuPadding)) {
                                Text(forward.label)
                                Text(forwardDetail(forward), style = VeloTheme.type.caption, color = c.secondary)
                            }
                            RowIconButton(Icons.Default.Close, "Stop forwarding ${forward.label}", { host.stopForward(remote, forward.block) })
                        }
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(sizes.menuPadding))
    VeloButton(AddRemoteCommand, { adding = true }, kind = ButtonKind.Primary)
    if (adding) AddRemoteDialog(add = host::addRemote, onDismiss = { adding = false })
    host.saved.firstOrNull { it.id == removingId }?.let { remote ->
        RemoveRemoteDialog(remote, remove = { host.removeRemote(remote.id) }, onDismiss = { removingId = null })
    }
    host.saved.firstOrNull { it.id == renamingId }?.let { remote ->
        RenameDialog(
            "Rename Remote", remote.label, allowEmpty = false,
            onRename = { host.renameRemote(remote.id, it) }, onDismiss = { renamingId = null },
        )
    }
    // A remote removed meanwhile takes its panel with it.
    host.saved.firstOrNull { it.id == detailsId }?.let { remote ->
        host.SshDetails(remote, onDismiss = { detailsId = null })
    }
}

/** This Device: the Tailscale node's controls, then where to remove devices from the network. */
@Composable
private fun DeviceSettings(host: SettingsHost) {
    val sizes = VeloTheme.sizes
    GroupHeader("Tailscale")
    GroupCard {
        Column(Modifier.padding(bottom = sizes.overlayPadding)) { TailscaleSection(host) }
    }
    GroupHeader("Other devices")
    GroupCard {
        Column(Modifier.padding(vertical = sizes.overlayPadding), verticalArrangement = Arrangement.spacedBy(sizes.menuPadding)) {
            val uri = LocalUriHandler.current
            var browserUnavailable by remember { mutableStateOf(false) }
            VeloButton("Open Tailscale admin console", {
                try {
                    uri.openUri(TAILSCALE_MACHINES_URL)
                } catch (_: IllegalArgumentException) {
                    browserUnavailable = true
                }
            })
            if (browserUnavailable) {
                SelectionContainer { Text("Open in a browser: $TAILSCALE_MACHINES_URL") }
            }
        }
    }
}

/** A switch with its label: the whole row takes the tap, so the switch is found by what it says. */
@Composable internal fun Toggle(t: String, v: Boolean, on: (Boolean) -> Unit) = Toggle(t, v, true, on)

@Composable internal fun Toggle(t: String, v: Boolean, enabled: Boolean, on: (Boolean) -> Unit) {
    val sizes = VeloTheme.sizes
    Row(
        Modifier.fillMaxWidth().heightIn(min = sizes.row)
            .toggleable(v, enabled = enabled, role = Role.Switch, onValueChange = on)
            .padding(vertical = sizes.cellInset),
        horizontalArrangement = Arrangement.spacedBy(sizes.rowGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(t, modifier = Modifier.weight(1f), color = if (enabled) VeloTheme.surface.text else VeloTheme.surface.disabled)
        VeloSwitch(v, Modifier.alpha(if (enabled) 1f else .38f))
    }
}

/** The line under the Tailscale switch: what the node is doing, and why when it cannot. */
fun tailnetStatusLine(state: TailnetState, paused: Boolean): String = when {
    state == TailnetState.Off -> "Off: Velo connects through the phone's network"
    paused -> "Paused while Velo is in the background"
    state == TailnetState.Starting -> "Starting…"
    state is TailnetState.NeedsLogin -> "Needs login"
    state == TailnetState.LoggedOut -> "Logged out of Tailscale."
    state is TailnetState.Running -> "Connected as ${state.name}"
    state is TailnetState.Error -> "Error: ${state.message}"
    else -> ""
}

/** The two things on the Tailscale node that cut every remote's connection at once, so they ask first. */
enum class TailnetAction { LogOut, Rename }

/** What the dialog of a [TailnetAction] says: its title, the line that names the cost, and the confirming button. */
data class TailnetQuestion(val title: String, val text: String, val confirm: String, val destructive: Boolean)

/** The question asked before [action]; [name] is the new node name a rename asks about, and unused by a log out. */
fun tailnetQuestion(action: TailnetAction, name: String): TailnetQuestion = when (action) {
    TailnetAction.LogOut -> TailnetQuestion(
        "Log out of Tailscale?", "Every remote disconnects. Logging in again opens the browser.", "Log out", destructive = true)
    TailnetAction.Rename -> TailnetQuestion(
        "Rename this node to $name?", "The node restarts and every remote reconnects.", "Rename", destructive = false)
}

/** Whether renaming the node to [name] is worth a question: a blank name or the current one changes nothing. */
fun renameAsksFirst(name: String, current: String): Boolean = name.isNotBlank() && name != current

/** The app's own Tailscale node: on/off, status, login, its name and addresses, log out. */
@Composable private fun TailscaleSection(host: SettingsHost) {
    val s = host.settings
    val state = host.tailnetState
    val uri = LocalUriHandler.current
    val login = rememberTailnetLogin(host.tailnetLogin)
    Toggle("Connect through Velo's own Tailscale node", s.tailnet, host::setTailnet)
    Text(tailnetStatusLine(state, host.tailnetPaused), color = VeloTheme.surface.secondary)
    if (state is TailnetState.NeedsLogin) {
        Spacer(Modifier.height(VeloTheme.sizes.menuPadding))
        // Through the app's login, so the start screen shows the wait for this page too.
        VeloButton("Log in", { login.open(uri, state.url) }, kind = ButtonKind.Primary)
    }
    if (state == TailnetState.LoggedOut) {
        Spacer(Modifier.height(VeloTheme.sizes.menuPadding))
        VeloButton("Log in again", login::start, kind = ButtonKind.Primary)
    }
    // No browser took the login page: its address, to open elsewhere.
    login.unopened?.let {
        Spacer(Modifier.height(VeloTheme.sizes.menuPadding))
        SelectionContainer { Text("Open in a browser: $it", color = VeloTheme.surface.secondary) }
    }
    if (state is TailnetState.Running) {
        for (ip in state.ips) Text(ip, color = VeloTheme.surface.secondary, style = VeloTheme.type.caption)
    }
    Spacer(Modifier.height(VeloTheme.sizes.menuPadding))
    var name by rememberSaveable(s.tailnetName) { mutableStateOf(s.tailnetName) }
    // The name is kept with the question: the field may change while the dialog is open, and the answer is for what was asked.
    var asking by remember { mutableStateOf<Pair<TailnetAction, String>?>(null) }
    val askRename = { if (renameAsksFirst(name, s.tailnetName)) asking = TailnetAction.Rename to name }
    VeloTextField(
        name, { name = it.filter { c -> c.isLetterOrDigit() || c == '-' } },
        label = "Node name",
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { askRename() }),
        trailing = {
            if (renameAsksFirst(name, s.tailnetName)) VeloButton("Rename", { askRename() })
        },
    )
    if (state is TailnetState.Running) {
        Spacer(Modifier.height(VeloTheme.sizes.menuPadding))
        VeloButton("Log out", { asking = TailnetAction.LogOut to name })
    }
    asking?.let { (action, asked) ->
        val q = tailnetQuestion(action, asked)
        VeloDialog(q.title, onDismissRequest = { asking = null }, text = q.text, confirmText = q.confirm, destructive = q.destructive,
            onConfirm = {
                asking = null
                when (action) {
                    TailnetAction.LogOut -> host.logoutTailnet()
                    TailnetAction.Rename -> host.setTailnetName(asked)
                }
            })
    }
}
