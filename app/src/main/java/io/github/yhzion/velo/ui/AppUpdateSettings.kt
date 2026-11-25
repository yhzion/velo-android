package io.github.yhzion.velo.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import io.github.yhzion.velo.ui.theme.*
import io.github.yhzion.velo.updates.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

/** What the update checks remember ([UpdateStatus]), kept current while it is on screen. */
@Composable
internal fun rememberUpdateStatus(): UpdateStatus {
    val ctx = LocalContext.current
    var status by remember(ctx) { mutableStateOf(UpdateSettings.status(ctx)) }
    DisposableEffect(ctx) {
        status = UpdateSettings.status(ctx)
        val stop = UpdateSettings.observe(ctx) { status = UpdateSettings.status(ctx) }
        onDispose { stop() }
    }
    return status
}

/** The installed build's version code, for comparing with [rememberUpdateStatus]. */
@Composable
internal fun rememberInstalledCode(): Long {
    val ctx = LocalContext.current
    return remember(ctx) { installedVersionCode(ctx) }
}

/** Whether Settings shows the dot: a release newer than the installed build that the Updates page has not shown. */
@Composable
internal fun updateDot(): Boolean = rememberUpdateStatus().unseen(rememberInstalledCode())

@Composable
internal fun AppUpdateSettings(host: SettingsHost) {
    val ctx = LocalContext.current
    var channel by remember { mutableStateOf(UpdateSettings.channel(ctx)) }
    var automatic by remember { mutableStateOf(UpdateSettings.automatic(ctx)) }
    var enabled by remember { mutableStateOf(UpdateSettings.enabled(ctx)) }
    var notice by remember { mutableStateOf("") }
    fun toggle(on: Boolean) {
        try {
            UpdateSettings.enable(ctx, on)
            enabled = UpdateSettings.enabled(ctx)
            notice = ""
        } catch (_: Exception) { notice = "Couldn’t change the background check schedule." }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) toggle(true) else notice = "Notifications are blocked. You can still check manually."
    }
    val installed = remember(ctx) { ctx.packageManager.getPackageInfo(ctx.packageName, 0) }
    // The previous channel's coroutine scope and its install button disappear together.
    key(channel) { ChannelRelease(host, channel, installed) {
        GroupHeader("Installed version")
        GroupCard { Text("${installed.versionName} (${installed.longVersionCode})") }
        GroupHeader("Update channel")
        GroupCard {
            val colors = VeloTheme.surface
            val s = VeloTheme.sizes
            SettingRow("Channel") {
                Row(Modifier.fillMaxWidth().padding(top = s.cellInset)
                    .clip(RoundedCornerShape(14.dp)).background(colors.divider)
                    .padding(3.dp).selectableGroup()) {
                    for (next in UpdateChannel.entries) {
                        val selected = next == channel
                        Box(Modifier.weight(1f).clip(RoundedCornerShape(11.dp))
                            .background(if (selected) colors.accent else Color.Transparent)
                            .selectable(selected, role = Role.RadioButton) {
                                if (!selected) { UpdateSettings.select(ctx, next); channel = next }
                            }.heightIn(min = s.control), contentAlignment = Alignment.Center) {
                            Text(next.name, color = if (selected) colors.onAccent else colors.text)
                        }
                    }
                }
            }
            Text(when (channel) {
                UpdateChannel.Stable -> "Official releases only."
                UpdateChannel.Preview -> "Official releases and preview builds."
                UpdateChannel.Edge -> "All releases, including experimental builds."
            }, Modifier.padding(top = s.cellInset, start = s.overlayPadding, end = s.overlayPadding, bottom = s.menuPadding),
                style = VeloTheme.type.body, color = colors.secondary)
            GroupDivider()
            Toggle("Check for updates automatically", automatic) { on ->
                UpdateSettings.setAutomatic(ctx, on)
                automatic = UpdateSettings.automatic(ctx)
            }
            GroupDivider()
            Toggle("Notify me about updates", enabled) { on ->
                if (on && Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                else toggle(on)
            }
            if (notice.isNotEmpty()) Text(notice, Modifier.padding(start = s.overlayPadding, top = s.cellInset, bottom = s.menuPadding))
        }
    } }
}

/**
 * The selected channel's release: at the top, when one is newer than [installed], its version,
 * what changed since the installed build ([whatsNew]) and the download and install buttons; then
 * the [settings]; then the check itself. Opening the page checks GitHub Releases, and a release it
 * shows counts as seen ([UpdateSettings.seen]).
 */
@Composable
private fun ChannelRelease(host: SettingsHost, channel: UpdateChannel, installed: PackageInfo, settings: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var check by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(true) }
    var releases by remember { mutableStateOf(emptyList<AppRelease>()) }
    var verified by remember { mutableStateOf<File?>(null) }
    var message by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf("") }
    var lastCheck by remember { mutableLongStateOf(UpdateSettings.lastCheck(ctx)) }
    val arm64 = host.installsReleases
    LaunchedEffect(check) {
        releases = emptyList()
        verified = null
        progress = ""
        busy = true
        try {
            if (!arm64) { message = "Release APKs require an ARM64 device."; return@LaunchedEffect }
            message = "Checking GitHub Releases…"
            val startedAt = System.currentTimeMillis()
            val found = withContext(host.io) { host.newerReleases(channel, installed.longVersionCode) }
            UpdateSettings.record(ctx, channel, startedAt, found.firstOrNull())
            found.firstOrNull()?.let { UpdateSettings.seen(ctx, channel, it.versionCode) }
            releases = found
            lastCheck = UpdateSettings.lastCheck(ctx)
            message = found.firstOrNull()?.let { "Available: ${it.tag} (${it.channel.name})" } ?: "No newer release on this channel."
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { message = "Couldn’t check for updates. Try again later." }
        finally { busy = false }
    }
    val s = VeloTheme.sizes
    val colors = VeloTheme.surface
    releases.firstOrNull()?.let { candidate ->
        GroupHeader("New version")
        GroupCard {
            val label = remember(ctx) { ctx.applicationInfo.loadLabel(ctx.packageManager).toString() }
            Text("$label ${versionLabel(candidate.tag)}", Modifier.padding(top = s.menuPadding), style = VeloTheme.type.overlayTitle)
            Text("You have ${installed.versionName}", style = VeloTheme.type.body, color = colors.secondary)
            WhatsNew(whatsNew(releases))
            if (progress.isNotEmpty()) Text(progress, Modifier.padding(top = s.menuPadding), style = VeloTheme.type.body, color = colors.text)
            val file = verified
            if (file == null) VeloButton("Download update", {
                busy = true
                progress = "Downloading and verifying ${candidate.tag}…"
                scope.launch {
                    try { verified = UpdateInstaller.download(ctx, candidate); progress = "Verified ${candidate.tag}. Ready to install." }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { progress = "Couldn’t download or verify this APK. Check your connection and use a build signed by the same publisher." }
                    finally { busy = false }
                }
            }, Modifier.fillMaxWidth().padding(top = s.menuPadding), enabled = !busy)
            else VeloButton("Install update", {
                busy = true
                scope.launch {
                    try {
                        if (ctx.packageManager.canRequestPackageInstalls()) {
                            withContext(Dispatchers.IO) { UpdateInstaller.launch(ctx, file, candidate) }
                        } else {
                            progress = "Allow installs from this app, then return and tap Install update."
                            ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")))
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { verified = null; progress = "Couldn’t open the installer. Check installation permissions and download the update again." }
                    finally { busy = false }
                }
            }, Modifier.fillMaxWidth().padding(top = s.menuPadding), enabled = !busy)
            VeloButton("Full release notes on GitHub", {
                try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(candidate.notesUrl))) }
                catch (_: android.content.ActivityNotFoundException) { progress = "No browser available." }
            }, Modifier.fillMaxWidth().padding(top = s.gap, bottom = s.menuPadding))
        }
    }
    settings()
    GroupHeader("GitHub Releases")
    GroupCard {
        Text(message, Modifier.padding(vertical = s.menuPadding), style = VeloTheme.type.body, color = colors.text)
        if (lastCheck > 0) Text("Last checked: ${DateFormat.getDateTimeInstance().format(Date(lastCheck))}",
            Modifier.padding(bottom = s.menuPadding), style = VeloTheme.type.body, color = colors.secondary)
        VeloButton("Check for updates", { busy = true; check++ }, Modifier.fillMaxWidth(), enabled = !busy && arm64)
    }
}

/** "What's new": each release's sections and items, under its version when several are newer. Nothing without notes. */
@Composable
private fun WhatsNew(changes: List<ReleaseChanges>) {
    if (changes.isEmpty()) return
    val s = VeloTheme.sizes
    val colors = VeloTheme.surface
    val type = VeloTheme.type
    GroupDivider(Modifier.padding(top = s.menuPadding))
    Text("What’s new", Modifier.padding(top = s.menuPadding).semantics { heading() }, style = type.bodyStrong, color = colors.text)
    for (release in changes) {
        release.heading?.let {
            Text(it, Modifier.padding(top = s.menuPadding).semantics { heading() }, style = type.header, color = colors.text)
        }
        for (section in release.sections) {
            Text(section.kind.title, Modifier.padding(top = s.cellInset * 2), style = type.caption, color = colors.secondary)
            for (item in section.items) {
                Row(Modifier.padding(top = s.cellInset), horizontalArrangement = Arrangement.spacedBy(s.rowGap / 2)) {
                    Text("•", style = type.body, color = colors.accent, modifier = Modifier.clearAndSetSemantics { })
                    Text(item, style = type.body, color = colors.text)
                }
            }
        }
    }
}
