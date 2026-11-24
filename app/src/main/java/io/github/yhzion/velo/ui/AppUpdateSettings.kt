package io.github.yhzion.velo.ui

import android.Manifest
import android.content.Intent
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

@Composable
internal fun AppUpdateSettings() {
    val ctx = LocalContext.current
    var channel by remember { mutableStateOf(UpdateSettings.channel(ctx)) }
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
        Toggle("Notify me about updates", enabled) { on ->
            if (on && Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            else toggle(on)
        }
        if (notice.isNotEmpty()) Text(notice, Modifier.padding(start = s.overlayPadding, top = s.cellInset, bottom = s.menuPadding))
    }
    // The previous channel's coroutine scope and its install button disappear together.
    key(channel) { ChannelRelease(channel, installed.longVersionCode) }
}

@Composable
private fun ChannelRelease(channel: UpdateChannel, installed: Long) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var check by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(true) }
    var release by remember { mutableStateOf<AppRelease?>(null) }
    var verified by remember { mutableStateOf<File?>(null) }
    var message by remember { mutableStateOf("") }
    var lastCheck by remember { mutableLongStateOf(UpdateSettings.lastCheck(ctx)) }
    val arm64 = "arm64-v8a" in Build.SUPPORTED_ABIS
    LaunchedEffect(check) {
        release = null
        verified = null
        busy = true
        try {
            if (!arm64) { message = "Release APKs require an ARM64 device."; return@LaunchedEffect }
            message = "Checking GitHub Releases…"
            release = withContext(Dispatchers.IO) { ReleaseFeed.latest(channel, installed) }
            UpdateSettings.checked(ctx, channel)
            lastCheck = UpdateSettings.lastCheck(ctx)
            message = release?.let { "Available: ${it.tag} (${it.channel.name})" } ?: "No newer release on this channel."
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { message = "Couldn’t check for updates. Try again later." }
        finally { busy = false }
    }
    GroupHeader("GitHub Releases")
    GroupCard {
        val s = VeloTheme.sizes
        Text(message, Modifier.padding(vertical = s.menuPadding), style = VeloTheme.type.body, color = VeloTheme.surface.text)
        if (lastCheck > 0) Text("Last checked: ${DateFormat.getDateTimeInstance().format(Date(lastCheck))}",
            Modifier.padding(bottom = s.menuPadding), style = VeloTheme.type.body, color = VeloTheme.surface.secondary)
        VeloButton("Check for updates", { busy = true; check++ }, Modifier.fillMaxWidth(), enabled = !busy && arm64)
        release?.let { candidate ->
            VeloButton("View release notes", {
                try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(candidate.notesUrl))) }
                catch (_: android.content.ActivityNotFoundException) { message = "No browser available." }
            }, Modifier.fillMaxWidth().padding(top = s.gap))
            val file = verified
            if (file == null) VeloButton("Download update", {
                busy = true
                message = "Downloading and verifying ${candidate.tag}…"
                scope.launch {
                    try { verified = UpdateInstaller.download(ctx, candidate); message = "Verified ${candidate.tag}. Ready to install." }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { message = "Couldn’t download or verify this APK. Check your connection and use a build signed by the same publisher." }
                    finally { busy = false }
                }
            }, Modifier.fillMaxWidth().padding(top = s.gap), enabled = !busy)
            else VeloButton("Install update", {
                busy = true
                scope.launch {
                    try {
                        if (ctx.packageManager.canRequestPackageInstalls()) {
                            withContext(Dispatchers.IO) { UpdateInstaller.launch(ctx, file, candidate) }
                        } else {
                            message = "Allow installs from this app, then return and tap Install update."
                            ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")))
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { verified = null; message = "Couldn’t open the installer. Check installation permissions and download the update again." }
                    finally { busy = false }
                }
            }, Modifier.fillMaxWidth().padding(top = s.gap), enabled = !busy)
        }
    }
}
