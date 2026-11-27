package io.github.yhzion.velo.ui

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.yhzion.velo.ActivitySound
import io.github.yhzion.velo.NotificationAccess
import io.github.yhzion.velo.PaneAlertKind
import io.github.yhzion.velo.ui.theme.Capsule
import io.github.yhzion.velo.ui.theme.GroupCard
import io.github.yhzion.velo.ui.theme.GroupDivider
import io.github.yhzion.velo.ui.theme.GroupHeader
import io.github.yhzion.velo.ui.theme.MenuButton
import io.github.yhzion.velo.ui.theme.SelectMenu
import io.github.yhzion.velo.ui.theme.SwitchRow
import io.github.yhzion.velo.ui.theme.VeloButton
import io.github.yhzion.velo.ui.theme.VeloSlider
import io.github.yhzion.velo.ui.theme.VeloSwitch
import io.github.yhzion.velo.ui.theme.VeloTheme
import io.github.yhzion.velo.ui.theme.groupBleed
import io.github.yhzion.velo.ui.theme.groupInset
import io.github.yhzion.velo.ui.theme.saidBy
import io.github.yhzion.velo.ui.theme.textAt
import kotlin.math.roundToInt

/** A kind of alert in test tags, as the boards name it: its label in lower case with dashes ("needs-response" drops "your"). */
internal fun alertTagId(kind: PaneAlertKind): String = when (kind) {
    PaneAlertKind.Blocked -> "needs-response"
    PaneAlertKind.Done -> "work-completed"
    PaneAlertKind.Error -> "work-failed"
    PaneAlertKind.Working -> "work-started"
}

/** The activity sound volume as the page says it: 0 % to 200 %, 100 % the sound as recorded. */
fun volumeValue(volume: Float): String = "${(volume * 100).roundToInt()}%"

/**
 * Notifications (canvas SetNotifications block): whether program activity shows notifications and
 * plays sounds, at what volume; staying connected in the background and the battery it needs; and
 * for each kind of alert its notification and its sound. Turning notifications on where Android
 * holds them back offers to allow them, or opens Android's settings.
 */
@Composable
internal fun NotificationSettings(host: SettingsHost) {
    val settings = host.settings
    val context = LocalContext.current
    val system = host.notifications
    val space = VeloTheme.space
    var blocked by remember { mutableStateOf(emptyList<PaneAlertKind>()) }
    fun refresh() {
        system.createChannels()
        blocked = PaneAlertKind.entries.filter { system.access(it) != NotificationAccess.Allowed }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }
    LifecycleResumeEffect(Unit) { refresh(); onPauseOrDispose { } }
    Column(Modifier.fillMaxWidth().testTag("notifications-content").padding(bottom = space.s24)) {
        GroupHeader("Program activity", Modifier.testTag("notifications-activity-header"), first = true)
        GroupCard(Modifier.testTag("notifications-activity-group")) {
            SwitchRow("Show background notifications", settings.activityNotices, settings::saveActivityNotices, tag = "notifications-background")
            if (settings.activityNotices && blocked.isNotEmpty()) {
                val kind = blocked.first()
                Column(Modifier.padding(end = space.s4, bottom = space.s12), verticalArrangement = Arrangement.spacedBy(space.s8)) {
                    if (Build.VERSION.SDK_INT >= 33 && system.access(kind) == NotificationAccess.RequestPermission) {
                        VeloButton("Allow notifications", { permission.launch(Manifest.permission.POST_NOTIFICATIONS) }, Modifier.fillMaxWidth(), row = true)
                    }
                    VeloButton("Open Android settings", { context.startActivity(system.settingsIntent(kind)) }, Modifier.fillMaxWidth(), row = true)
                }
            }
            GroupDivider()
            SwitchRow("Play activity sounds", settings.activitySounds, settings::saveActivitySounds, tag = "notifications-sounds")
            if (settings.activitySounds) {
                GroupDivider()
                VolumeSetting(settings.activityVolume, settings::saveActivityVolume)
            }
        }
        GroupHeader("Background", Modifier.testTag("notifications-connection-header"))
        GroupCard(Modifier.testTag("notifications-connection-group")) { BackgroundSettings(host) }
        GroupHeader("Alerts", Modifier.testTag("notifications-alerts-header"))
        Column(Modifier.fillMaxWidth().testTag("notifications-alerts"), verticalArrangement = Arrangement.spacedBy(VeloTheme.medium(10.dp))) {
            for (kind in PaneAlertKind.entries) AlertSettings(host, kind)
        }
    }
}

/** The activity sounds' volume: its value beside its name, and a slider as wide as the row's words under them. */
@Composable
private fun VolumeSetting(volume: Float, onChange: (Float) -> Unit) {
    val space = VeloTheme.space
    val value = volumeValue(volume)
    Row(
        Modifier.groupBleed().testTag("notifications-volume-row").fillMaxWidth().groupInset().padding(end = space.s4, top = VeloTheme.medium(14.dp)),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("Sound volume", Modifier.testTag("notifications-volume-label"), style = VeloTheme.type.body, color = VeloTheme.surface.text)
        Text(value, Modifier.testTag("notifications-volume-value"), style = VeloTheme.type.value, color = VeloTheme.surface.secondary)
    }
    VeloSlider(
        volume, onChange,
        Modifier.fillMaxWidth().padding(end = space.s4, bottom = space.s4).testTag("notifications-volume-slider").semantics {
            contentDescription = "Activity sound volume"
            stateDescription = value
        },
        valueRange = 0f..2f,
    )
}

/**
 * One kind of alert: its name with its colour, whether it posts a notification in the background,
 * and whether it plays a sound, which one (a menu; a pick plays it).
 */
@Composable
private fun AlertSettings(host: SettingsHost, kind: PaneAlertKind) {
    val settings = host.settings
    val c = VeloTheme.surface
    val id = alertTagId(kind)
    val words = textAt(15f)
    val ink = VeloTheme.colors.textOnRaised
    GroupCard(Modifier.testTag("alert-$id")) {
        Row(
            // The heading's node takes the row's bounds: set before the row's inset.
            Modifier.groupBleed().testTag("alert-$id-title").fillMaxWidth().heightIn(min = VeloTheme.medium(48.dp))
                .semantics(mergeDescendants = true) { heading() }.groupInset(),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(VeloTheme.medium(10.dp)),
        ) {
            Box(Modifier.size(VeloTheme.space.s8).clip(Capsule).background(alertColour(kind)))
            Text(kind.label, style = VeloTheme.type.body, fontWeight = FontWeight.Medium, color = c.text)
        }
        GroupDivider()
        AlertRow("$id-notification-row", settings.posts(kind), { settings.savePosts(kind, it) }, "Background notification, ${kind.label}") { description ->
            Text("Background notification", Modifier.weight(1f).saidBy(description), style = words, color = ink)
            VeloSwitch(settings.posts(kind), Modifier.testTag("alert-$id-notification-switch"))
        }
        GroupDivider()
        AlertRow("$id-sound-row", settings.plays(kind), { settings.savePlays(kind, it) }, "Activity sound, ${kind.label}") { description ->
            Text("Activity sound", Modifier.weight(1f).saidBy(description), style = words, color = ink)
            if (settings.plays(kind)) {
                var choosing by remember { mutableStateOf(false) }
                val sound = settings.soundFor(kind)
                Box {
                    MenuButton(sound.name, { choosing = true }, contentDescription = "Sound, ${sound.name}", tag = "alert-$id-sound-menu", small = true)
                    SelectMenu(choosing, { choosing = false }, ActivitySound.entries, sound, { it.name }, onPick = {
                        settings.saveSound(kind, it)
                        if (settings.activitySounds) host.notifications.play(kind, it, settings.activityVolume)
                    })
                }
            }
            VeloSwitch(settings.plays(kind), Modifier.testTag("alert-$id-sound-switch"))
        }
    }
}

/**
 * A row of an alert, 56 high, its words and controls 10 apart: a tap anywhere but on a menu in it
 * turns its switch; a screen reader says [description], which names the kind. [content] is given
 * the description, to hide the row's words under it ([saidBy]).
 */
@Composable
private fun AlertRow(
    tag: String, checked: Boolean, onChange: (Boolean) -> Unit, description: String,
    content: @Composable androidx.compose.foundation.layout.RowScope.(description: String) -> Unit,
) = Row(
    Modifier.groupBleed().testTag("alert-$tag").fillMaxWidth().heightIn(min = VeloTheme.medium(56.dp))
        .toggleable(checked, role = Role.Switch, onValueChange = onChange).semantics { contentDescription = description }
        .groupInset().padding(vertical = VeloTheme.space.s4).padding(end = VeloTheme.space.s4),
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(VeloTheme.medium(10.dp)),
) { content(description) }

/** The colour that marks a kind of alert, as Rex for Mac's badges do. */
@Composable
private fun alertColour(kind: PaneAlertKind): Color {
    val c = VeloTheme.surface
    return when (kind) {
        PaneAlertKind.Blocked -> c.warning
        PaneAlertKind.Done -> c.ok
        PaneAlertKind.Error -> c.danger
        PaneAlertKind.Working -> c.accent
    }
}
