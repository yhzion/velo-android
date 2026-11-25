package io.github.yhzion.velo.ui

import io.github.yhzion.velo.ui.theme.textAt
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import io.github.yhzion.velo.ui.theme.*
import io.github.yhzion.velo.updates.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

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

/**
 * When the last successful check was, in [zone]: "Last checked: today, 9:41 PM", "yesterday, …",
 * or its date. Null when nothing was checked yet ([at] 0).
 */
internal fun lastCheckedText(at: Long, now: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String? {
    if (at <= 0L) return null
    val then = Instant.ofEpochMilli(at).atZone(zone)
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val day = when (then.toLocalDate()) {
        today -> "today"
        today.minusDays(1) -> "yesterday"
        else -> DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(then)
    }
    // Newer locale data puts a narrow no-break space before AM and PM.
    val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(then).replace(' ', ' ')
    return "Last checked: $day, $time"
}

/**
 * Settings → About → Updates (canvas SetUpdates block): a newer release first, in its card
 * ([ChannelRelease]); then this phone's version; then how Velo checks: automatically, with a
 * notification, on which channel; then when it last checked, and Check for updates.
 */
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
    val c = VeloTheme.surface
    // The previous channel's coroutine scope and its install button disappear together.
    key(channel) { ChannelRelease(host, channel, installed) {
        GroupHeader("This phone", Modifier.testTag("updates-this-phone-header"))
        GroupCard(Modifier.testTag("updates-this-phone-group")) {
            SettingRow("Installed", tag = "updates-installed", labelFills = false) {
                // A value alone keeps the 16 from the border a switch keeps, not a control's 12.
                Text("${installed.versionName} (${installed.longVersionCode})", Modifier.padding(end = VeloTheme.space.s4).testTag("updates-installed-value"),
                    style = VeloTheme.type.value, color = c.secondary)
            }
        }
        GroupHeader("Checking", Modifier.testTag("updates-checking-header"))
        GroupCard(Modifier.testTag("updates-checking-group")) {
            SwitchRow("Check for updates automatically", automatic, { on ->
                UpdateSettings.setAutomatic(ctx, on)
                automatic = UpdateSettings.automatic(ctx)
            }, subtitle = "When Velo opens, at most every 12 hours", tag = "updates-auto-check")
            GroupDivider()
            SwitchRow("Notify me about updates", enabled, { on ->
                if (on && Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                else toggle(on)
            }, subtitle = "An Android notification, even when Velo is closed", tag = "updates-notify")
            GroupDivider()
            var choosing by remember { mutableStateOf(false) }
            SettingRow("Channel", tag = "updates-channel", labelFills = false) {
                Box {
                    MenuButton(channel.name, { choosing = true }, contentDescription = "Channel, ${channel.name}", tag = "updates-channel-menu")
                    SelectMenu(choosing, { choosing = false }, UpdateChannel.entries, channel, { it.name }, onPick = { next ->
                        if (next != channel) { UpdateSettings.select(ctx, next); channel = next }
                    })
                }
            }
            if (notice.isNotEmpty()) Text(notice, Modifier.padding(bottom = VeloTheme.space.s12), style = VeloTheme.type.caption, color = c.secondary)
        }
    } }
}

/**
 * The selected channel's release: when one is newer than [installed], a card with its version,
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
    // Why the last check found nothing to say: no ARM64, no network; null after a check that worked.
    var problem by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf("") }
    var lastCheck by remember { mutableLongStateOf(UpdateSettings.lastCheck(ctx)) }
    val arm64 = host.installsReleases
    LaunchedEffect(check) {
        releases = emptyList()
        verified = null
        progress = ""
        busy = true
        problem = null
        try {
            if (!arm64) { problem = "Release APKs require an ARM64 device."; return@LaunchedEffect }
            val startedAt = System.currentTimeMillis()
            val found = withContext(host.io) { host.newerReleases(channel, installed.longVersionCode) }
            UpdateSettings.record(ctx, channel, startedAt, found.firstOrNull())
            found.firstOrNull()?.let { UpdateSettings.seen(ctx, channel, it.versionCode) }
            releases = found
            lastCheck = UpdateSettings.lastCheck(ctx)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { problem = "Couldn’t check for updates. Try again later." }
        finally { busy = false }
    }
    val s = VeloTheme.sizes
    val space = VeloTheme.space
    val colors = VeloTheme.surface
    Column(Modifier.fillMaxWidth().testTag("updates-content").padding(top = space.s8, bottom = space.s24)) {
        releases.firstOrNull()?.let { candidate ->
            RaisedCard(Modifier.testTag("new-version-card"), padding = VeloTheme.medium(18.dp) + s.edge) {
                val label = remember(ctx) { ctx.applicationInfo.loadLabel(ctx.packageManager).toString() }
                Column(verticalArrangement = Arrangement.spacedBy(space.s4)) {
                    Eyebrow("NEW VERSION", Modifier.fillMaxWidth().testTag("new-version-eyebrow"))
                    Text("$label ${versionLabel(candidate.tag)}", Modifier.fillMaxWidth().testTag("new-version-title").lineBox(textAt(24f)).semantics { heading() },
                        style = textAt(24f, FontWeight.SemiBold), color = colors.text)
                    Text("You have ${installed.versionName} · ${candidate.channel.name}", Modifier.fillMaxWidth().testTag("new-version-meta").lineBox(textAt(14f)),
                        style = textAt(14f), color = colors.secondary)
                }
                Box(Modifier.fillMaxWidth().height(hairline()).background(colors.divider))
                WhatsNew(whatsNew(releases))
                if (progress.isNotEmpty()) Text(progress, style = VeloTheme.type.body, color = colors.text)
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
                }, Modifier.fillMaxWidth(), kind = ButtonKind.Primary, enabled = !busy, icon = rememberVectorPainter(Icons.Default.Download),
                    tag = "download-update-button")
                else VeloButton("Install update", {
                    busy = true
                    scope.launch {
                        try {
                            if (ctx.packageManager.canRequestPackageInstalls()) {
                                withContext(host.io) { UpdateInstaller.launch(ctx, file, candidate) }
                            } else {
                                progress = "Allow installs from this app, then return and tap Install update."
                                ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")))
                            }
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { verified = null; progress = "Couldn’t open the installer. Check installation permissions and download the update again." }
                        finally { busy = false }
                    }
                }, Modifier.fillMaxWidth(), kind = ButtonKind.Primary, enabled = !busy, tag = "download-update-button")
                VeloButton("Full release notes on GitHub", {
                    try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(candidate.notesUrl))) }
                    catch (_: android.content.ActivityNotFoundException) { progress = "No browser available." }
                }, Modifier.fillMaxWidth(), kind = ButtonKind.Text, icon = rememberVectorPainter(Icons.AutoMirrored.Filled.OpenInNew), iconAtEnd = true,
                    tag = "release-notes-link")
            }
        }
        settings()
        val status = when {
            busy -> "Checking GitHub Releases…"
            problem != null -> problem!!
            else -> listOfNotNull(if (releases.isEmpty()) "No newer release" else null, lastCheckedText(lastCheck, System.currentTimeMillis()))
                .joinToString(" · ")
        }
        Row(
            Modifier.fillMaxWidth().testTag("updates-check-status").padding(start = space.s4, end = space.s4, top = space.s8),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(status, Modifier.weight(1f, fill = false).padding(end = space.s12).testTag("updates-last-checked"), style = VeloTheme.type.caption, color = colors.secondary)
            VeloButton("Check for updates", { busy = true; check++ }, kind = ButtonKind.Text, enabled = !busy && arm64, tag = "updates-check-now-button")
        }
    }
}

/**
 * "What's new": each release's sections and items, under its version when several are newer,
 * then a note that every version since the installed one is here. Nothing without notes.
 */
@Composable
private fun WhatsNew(changes: List<ReleaseChanges>) {
    if (changes.isEmpty()) return
    val colors = VeloTheme.surface
    val type = VeloTheme.type
    val space = VeloTheme.space
    Text("What’s new", Modifier.fillMaxWidth().testTag("whats-new-title").semantics { heading() }, style = type.body, fontWeight = FontWeight.SemiBold, color = colors.text)
    var n = 0
    for (release in changes) {
        release.heading?.let {
            Text(it, Modifier.semantics { heading() }, style = type.header, color = colors.text)
        }
        for (section in release.sections) {
            Column(Modifier.fillMaxWidth().testTag("whats-new-section-${section.kind.title.lowercase()}"), verticalArrangement = Arrangement.spacedBy(VeloTheme.medium(6.dp))) {
                Eyebrow(section.kind.title.uppercase(), Modifier.fillMaxWidth().testTag("whats-new-section-${section.kind.title.lowercase()}-title"), color = colors.secondary)
                for (item in section.items) {
                    n++
                    Row(Modifier.fillMaxWidth().testTag("whats-new-item-$n"), horizontalArrangement = Arrangement.spacedBy(VeloTheme.medium(10.dp))) {
                        Box(Modifier.padding(top = VeloTheme.medium(9.dp)).size(VeloTheme.medium(5.dp)).clip(Capsule).background(colors.accent).clearAndSetSemantics { })
                        Text(item, style = type.value.paragraph(1.45.em), color = colors.text)
                    }
                }
            }
        }
    }
    Text("Skipped a few versions? Each one since yours is listed here, newest first.", Modifier.fillMaxWidth().testTag("whats-new-skipped-note"),
        style = type.caption.paragraph(1.5.em), color = colors.secondary)
}
