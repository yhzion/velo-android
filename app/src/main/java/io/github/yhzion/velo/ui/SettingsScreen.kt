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
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.yhzion.velo.LicenseComponent
import io.github.yhzion.velo.ui.theme.Capsule
import io.github.yhzion.velo.ui.theme.CategoryIcon
import io.github.yhzion.velo.ui.theme.CategoryTints
import io.github.yhzion.velo.ui.theme.MinKey
import io.github.yhzion.velo.ui.theme.rounded
import kotlin.math.roundToInt

// Settings is a modal over the originating screen (OverlayHost). On a phone and the folded
// screen it fills the window: the categories first, each opening a page with Back in its header.
// On the unfolded screen it is a sheet: the categories beside the open page, a page under a
// category with a back row above its title (the redesign's settings boards).

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

/** The settings categories: the line icon on the tile of its tint, and what the category holds (canvas SetNav block). */
internal enum class SettingsCategory(val title: String, val id: String, @DrawableRes val icon: Int, val summary: String) {
    Appearance("Appearance", "appearance", R.drawable.ic_category_appearance, "Size, highlight color, font"),
    Keyboard("Keyboard", "keyboard", R.drawable.ic_category_keyboard, "Key bar, typing, tap gestures"),
    Notifications("Notifications", "notifications", R.drawable.ic_category_notifications, "Alerts, sounds, background"),
    Remotes("Remotes", "remotes", R.drawable.ic_category_remotes, "Tailscale, SSH key, saved"),
    // About's line is the installed version and the release found ([aboutSummary]).
    About("About", "about", R.drawable.ic_category_about, ""),
    ;

    val tint: Color get() = when (this) {
        Appearance -> CategoryTints.appearance
        Keyboard -> CategoryTints.keyboard
        Notifications -> CategoryTints.notifications
        Remotes -> CategoryTints.remotes
        About -> CategoryTints.about
    }
}

/** The pages under a category, by their title. */
internal enum class SettingsLevel(val title: String) {
    Device("Tailscale"), Font("Terminal Font"), Updates("Updates"), SshKey(SSH_KEY_ROW),
    Licenses("Open source licenses"), License("License"),
}

/** About's line in the categories: the installed version, and the newer release found, if any. */
internal fun aboutSummary(version: String, available: String?): String =
    "Version $version" + (available?.let { " · $it available" } ?: "")

/**
 * Where the settings open: a [category], a [level] under it, the [license] a license page shows.
 * Without a category, the folded screen shows the categories and the unfolded one Appearance.
 */
internal data class SettingsRoute(
    val category: SettingsCategory? = null,
    val level: SettingsLevel? = null,
    val license: String? = null,
)

/**
 * The settings, as a modal over the screen ([SettingsPanel]). Back goes up one level; the close
 * button, a tap beside the sheet on the unfolded screen and Back at the top close it ([onClose]).
 */
@Composable
fun SettingsSheet(app: AppController, onClose: () -> Unit) = SettingsSheet(remember(app) { AppSettingsHost(app) }, onClose)

@Composable
internal fun SettingsSheet(host: SettingsHost, onClose: () -> Unit) {
    OverlayHost(onDismissRequest = onClose) { SettingsPanel(host, onClose) }
}

/**
 * The settings themselves, opened at [start]. On the unfolded screen the categories are beside
 * the one that is open; on the folded screen and a phone the categories come first and a category
 * takes their place. Terminal Font, Tailscale, SSH key, Updates and the licenses open as pages
 * under their category.
 */
@Composable
internal fun SettingsPanel(host: SettingsHost, onClose: () -> Unit, start: SettingsRoute = SettingsRoute()) {
    val sizes = VeloTheme.sizes
    var category by rememberSaveable { mutableStateOf(start.category) }
    var level by rememberSaveable { mutableStateOf(start.level) }
    // The open license page: a component's id, or a Go module's inside the Go modules page.
    var license by rememberSaveable { mutableStateOf(start.license) }
    val licensed = license?.takeIf { level == SettingsLevel.License }?.let(::findLicense)
    val scrollStates = rememberSaveableStateHolder()

    val window = LocalConfiguration.current
    val expanded = !isCompact(window.screenWidthDp.toFloat(), window.screenHeightDp.toFloat())
    val shown = category ?: SettingsCategory.Appearance
    val home = !expanded && category == null && level == null
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
        else -> shown.title
    }
    // On the unfolded screen a page under a category names the page Back returns to.
    val above = when {
        level == SettingsLevel.License -> licensed?.let { licenseParent(it.id)?.name } ?: SettingsLevel.Licenses.title
        level != null -> shown.title
        else -> null
    }
    val context = LocalContext.current
    val version = remember(context) { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
    val status = rememberUpdateStatus()
    val installed = rememberInstalledCode()
    val nav = NavState(
        selected = shown.takeIf { expanded }, about = aboutSummary(version, status.available(installed)),
        dot = status.unseen(installed), onSelect = { category = it; level = null },
    )
    BackHandler(enabled = back != null) { back?.invoke() }

    @Composable
    fun Page(top: @Composable () -> Unit, modifier: Modifier, scrolls: (@Composable (Modifier, @Composable () -> Unit) -> Unit)) {
        // Each page keeps its own place; each license page keeps its own place in its text.
        scrollStates.SaveableStateProvider(licensed?.let { "License:${it.id}" } ?: level?.name ?: shown.name) {
            SettingsPageContent(host, shown, level, licensed, top, modifier, scrolls,
                openLevel = { category = shown; level = it }, openLicense = { license = it; level = SettingsLevel.License })
        }
    }

    Box(Modifier.fillMaxSize().testTag("screen-settings")) {
        if (expanded) SettingsPanelSurface(
            Modifier.align(Alignment.Center).padding(VeloTheme.space.s8).widthIn(max = sizes.sheetWidth).fillMaxSize().testTag("settings-sheet"),
        ) {
            Column(Modifier.fillMaxSize()) {
                SheetHeader(onClose)
                Row(Modifier.fillMaxWidth().weight(1f)) {
                    scrollStates.SaveableStateProvider("categories") {
                        Box(
                            Modifier.testTag("settings-nav-column").width(sizes.sheetList + sizes.edge).fillMaxHeight()
                                .background(VeloTheme.colors.sidebar.surface).drawEndLine(VeloTheme.surface.divider),
                        ) { CategoryList(nav, chevrons = false, Modifier.width(sizes.sheetList).verticalScroll(rememberScrollState())) }
                    }
                    val top: @Composable () -> Unit = { PageTitle(title, above, back) }
                    Page(top, Modifier.weight(1f).fillMaxHeight().testTag("settings-detail").padding(horizontal = VeloTheme.space.s24)) { modifier, content ->
                        Column(modifier.verticalScroll(rememberScrollState())) { content() }
                    }
                }
            }
        } else SettingsPanelSurface(Modifier.fillMaxSize(), fullScreen = true) {
            Column(Modifier.fillMaxSize()) {
                if (home) HomeHeader(onClose) else PageHeader(title, back ?: {}, onClose)
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    if (home) scrollStates.SaveableStateProvider("categories") {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            Column(Modifier.testTag("settings-detail").padding(start = VeloTheme.space.s16, end = VeloTheme.space.s16, top = VeloTheme.space.s8)) {
                                CategoryList(nav, chevrons = true, Modifier.fillMaxWidth())
                            }
                        }
                    } else Page({}, Modifier.testTag("settings-detail").padding(start = VeloTheme.space.s16, end = VeloTheme.space.s16, top = VeloTheme.space.s4)) { modifier, content ->
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { Column(modifier) { content() } }
                    }
                }
            }
        }
    }
}

/** A line along the end of what this draws: the border between the categories and the page. */
private fun Modifier.drawEndLine(color: Color) = drawBehind {
    val x = if (layoutDirection == LayoutDirection.Ltr) size.width - density / 2 else density / 2
    drawLine(color, Offset(x, 0f), Offset(x, size.height), density)
}

/** A line along the bottom of what this draws: the border under a header. */
private fun Modifier.drawBottomLine(color: Color) = drawBehind {
    drawLine(color, Offset(0f, size.height - density / 2), Offset(size.width, size.height - density / 2), density)
}

/**
 * The page of [category] or of the [level] under it. [top] goes first (the page's title on the
 * unfolded screen); [scrolls] lays out a page that does not scroll itself. The font list and a
 * license's text scroll themselves, so they take [modifier] and [top] themselves.
 */
@Composable
private fun SettingsPageContent(
    host: SettingsHost, category: SettingsCategory, level: SettingsLevel?, licensed: LicenseComponent?,
    top: @Composable () -> Unit, modifier: Modifier, scrolls: @Composable (Modifier, @Composable () -> Unit) -> Unit,
    openLevel: (SettingsLevel) -> Unit, openLicense: (String) -> Unit,
) {
    if (level == SettingsLevel.Font) TerminalFontSettings(host, modifier, top)
    else if (licensed != null) LicenseDetail(licensed, open = openLicense, modifier = modifier, top = top, io = host.io)
    else scrolls(modifier) {
        top()
        if (level == SettingsLevel.Device) DeviceSettings(host)
        else if (level == SettingsLevel.Updates) AppUpdateSettings(host)
        else if (level == SettingsLevel.SshKey) SshKeySettings(host.sshSecrets)
        // A license that is no longer listed falls back to the list.
        else if (level == SettingsLevel.Licenses || level == SettingsLevel.License) OpenSourceLicenseList(open = openLicense)
        else when (category) {
            SettingsCategory.Appearance -> AppearanceSettings(host, openFont = { openLevel(SettingsLevel.Font) })
            SettingsCategory.Keyboard -> KeyboardSettings(host.settings)
            SettingsCategory.Notifications -> ActivitySettings(host)
            SettingsCategory.Remotes -> RemoteSettings(host, openDevice = { openLevel(SettingsLevel.Device) },
                openSshKey = { openLevel(SettingsLevel.SshKey) })
            SettingsCategory.About -> AboutSettings(openUpdates = { openLevel(SettingsLevel.Updates) },
                openLicenses = { openLevel(SettingsLevel.Licenses) })
        }
    }
}

/** The title of the open page beside the categories; a page under a category has a row back to [above] over it. */
@Composable
private fun PageTitle(title: String, above: String?, back: (() -> Unit)?) {
    val c = VeloTheme.surface
    if (above != null && back != null) {
        Row(
            Modifier.padding(top = VeloTheme.space.s8).offset(x = -VeloTheme.medium(10.dp)).testTag("settings-back")
                .heightIn(min = VeloTheme.medium(44.dp).coerceAtLeast(MinKey)).clip(Capsule)
                .clickable(role = Role.Button, onClickLabel = "Back", onClick = back)
                .padding(start = VeloTheme.medium(2.dp), end = VeloTheme.medium(10.dp)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(VeloTheme.medium(2.dp)),
        ) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null, Modifier.size(VeloTheme.space.s20), tint = c.accent)
            Text(above, style = VeloTheme.type.value, color = c.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    Text(
        title,
        Modifier.fillMaxWidth().testTag("settings-page-title")
            .padding(top = if (above != null) 0.dp else VeloTheme.medium(18.dp), bottom = VeloTheme.medium(if (above != null) 6.dp else 2.dp))
            .semantics { heading() },
        style = VeloTheme.type.pageTitle, color = c.text,
    )
}

/** The top of the unfolded sheet: "Settings" and the close button. */
@Composable
private fun SheetHeader(onClose: () -> Unit) {
    val c = VeloTheme.surface
    Row(
        Modifier.testTag("settings-header").fillMaxWidth().height(VeloTheme.medium(64.dp) + VeloTheme.sizes.edge).drawBottomLine(c.divider)
            .padding(start = VeloTheme.space.s20, end = VeloTheme.medium(10.dp), bottom = VeloTheme.sizes.edge),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Settings", Modifier.weight(1f).testTag("settings-sheet-title").semantics { heading() },
            style = VeloTheme.type.title, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        CloseButton(onClose, Modifier.testTag("settings-close"), contentDescription = "Close Settings", small = true)
    }
}

/** The top of the categories on the folded screen: the screen's title and the close button. */
@Composable
private fun HomeHeader(onClose: () -> Unit) {
    Row(
        Modifier.testTag("settings-header").fillMaxWidth().height(VeloTheme.medium(72.dp))
            .padding(start = VeloTheme.space.s20, end = VeloTheme.medium(10.dp), top = VeloTheme.space.s8, bottom = VeloTheme.space.s8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(VeloTheme.space.s8),
    ) {
        Text("Settings", Modifier.weight(1f).testTag("settings-page-title").semantics { heading() },
            style = VeloTheme.type.screenTitle, color = VeloTheme.surface.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        CloseButton(onClose, Modifier.testTag("settings-close"), contentDescription = "Close Settings")
    }
}

/** The top of a page on the folded screen: Back, the page's title and the close button. */
@Composable
private fun PageHeader(title: String, back: () -> Unit, onClose: () -> Unit) {
    val c = VeloTheme.surface
    Row(
        Modifier.testTag("settings-header").fillMaxWidth().height(VeloTheme.medium(64.dp)).drawBottomLine(c.divider)
            .padding(start = VeloTheme.space.s4, end = VeloTheme.medium(10.dp), top = VeloTheme.space.s8, bottom = VeloTheme.space.s8 + VeloTheme.sizes.edge),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(VeloTheme.space.s4),
    ) {
        Box(
            Modifier.testTag("settings-back").size(VeloTheme.sizes.control).clip(Capsule).clickable(role = Role.Button, onClick = back),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Back", Modifier.size(VeloTheme.medium(22.dp)), tint = c.text) }
        Text(title, Modifier.weight(1f).testTag("settings-page-title").semantics { heading() },
            style = VeloTheme.type.title, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        CloseButton(onClose, Modifier.testTag("settings-close"), contentDescription = "Close Settings", small = true)
    }
}

/** What the categories show: the [selected] one (beside its page), About's line and its update [dot]. */
private class NavState(val selected: SettingsCategory?, val about: String, val dot: Boolean, val onSelect: (SettingsCategory) -> Unit)

/**
 * The categories. On the folded screen ([chevrons]) each is a bordered card with what it holds
 * under its name and a chevron; beside the page on the unfolded screen, a row whose pill marks
 * the open one. About has the update dot while a release has not been seen (canvas SetNav block).
 */
@Composable
private fun CategoryList(nav: NavState, chevrons: Boolean, modifier: Modifier) {
    val c = VeloTheme.surface
    val sizes = VeloTheme.sizes
    val type = VeloTheme.type
    Column(
        modifier.testTag("settings-nav").semantics { contentDescription = "Settings categories" }
            .then(if (chevrons) Modifier else Modifier.padding(VeloTheme.space.s8)),
        verticalArrangement = Arrangement.spacedBy(if (chevrons) VeloTheme.space.s8 else VeloTheme.space.s4),
    ) {
        for (category in SettingsCategory.entries) {
            val id = category.id
            val selected = category == nav.selected
            val shape = rounded(if (chevrons) VeloTheme.radii.card else VeloTheme.radii.nav)
            Row(
                Modifier.testTag("settings-nav-$id").fillMaxWidth()
                    .heightIn(min = if (chevrons) sizes.row + sizes.edge * 2 else sizes.row).clip(shape)
                    .then(if (chevrons) Modifier.border(sizes.edge, c.divider, shape) else Modifier.background(if (selected) c.selected else Color.Transparent))
                    .selectable(selected, role = Role.Tab) { nav.onSelect(category) }
                    .padding(horizontal = VeloTheme.space.s12 + if (chevrons) sizes.edge else 0.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(VeloTheme.medium(14.dp)),
            ) {
                CategoryIcon(painterResource(category.icon), category.tint, Modifier.testTag("settings-nav-$id-icon"))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(VeloTheme.medium(1.dp))) {
                    Text(category.title, Modifier.fillMaxWidth().testTag("settings-nav-$id-label"), style = type.body,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (chevrons) Text(if (category == SettingsCategory.About) nav.about else category.summary,
                        Modifier.fillMaxWidth().testTag("settings-nav-$id-summary"), style = type.caption, color = c.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (category == SettingsCategory.About && nav.dot) Box(
                    Modifier.testTag("settings-nav-about-update-dot").size(sizes.dot).clip(Capsule).background(c.accent)
                        .semantics { contentDescription = "Update available" },
                )
                if (chevrons) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(VeloTheme.space.s16), tint = c.secondary)
            }
        }
    }
}

/** About: the app's version, Updates and the licenses. */
@Composable
private fun AboutSettings(openUpdates: () -> Unit, openLicenses: () -> Unit) {
    val context = LocalContext.current
    val about = remember(context) {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${context.applicationInfo.loadLabel(context.packageManager)} ${info.versionName} (${info.longVersionCode})"
    }
    GroupCard(Modifier.padding(top = VeloTheme.sizes.menuPadding)) {
        Box(Modifier.fillMaxWidth().heightIn(min = VeloTheme.sizes.row), contentAlignment = Alignment.CenterStart) {
            Text(about)
        }
        GroupDivider()
        UpdatesRow(onClick = openUpdates)
        GroupDivider()
        ListRow("Open source licenses", onClick = openLicenses)
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
