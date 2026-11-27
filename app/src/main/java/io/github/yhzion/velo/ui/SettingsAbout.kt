package io.github.yhzion.velo.ui

import io.github.yhzion.velo.ui.theme.textAt
import io.github.yhzion.velo.ui.theme.lineBox
import io.github.yhzion.velo.ui.theme.lineBoxOrTaller
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.foundation.text.selection.SelectionContainer
import io.github.yhzion.velo.R
import io.github.yhzion.velo.ui.theme.Capsule
import io.github.yhzion.velo.ui.theme.GroupCard
import io.github.yhzion.velo.ui.theme.GroupDivider
import io.github.yhzion.velo.ui.theme.NavigationRow
import io.github.yhzion.velo.ui.theme.RaisedCard
import io.github.yhzion.velo.ui.theme.VeloTheme
import io.github.yhzion.velo.ui.theme.paragraph

/** Where Velo's source is. */
private const val SOURCE_URL = "https://github.com/yhzion/velo-android"

/** What About's licenses row says the list holds. */
private const val LICENSES_SUMMARY = "Fonts, core, network"

/**
 * About (canvas SetAbout block): the app's card with its mark, name and version; Updates, which
 * says "X.Y.Z available" with the dot until the Updates page has shown it ([UpdateStatus]); the
 * licenses; the source; and that Velo is not the Rex authors'.
 */
@Composable
internal fun AboutSettings(openUpdates: () -> Unit, openLicenses: () -> Unit) {
    val context = LocalContext.current
    val info = remember(context) { context.packageManager.getPackageInfo(context.packageName, 0) }
    val name = remember(context) { context.applicationInfo.loadLabel(context.packageManager).toString() }
    val status = rememberUpdateStatus()
    val installed = rememberInstalledCode()
    val available = status.available(installed)
    val dot = status.unseen(installed)
    val c = VeloTheme.surface
    val type = VeloTheme.type
    val space = VeloTheme.space
    val uri = LocalUriHandler.current
    var unopened by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().testTag("about-content").padding(top = space.s8, bottom = space.s24),
        verticalArrangement = Arrangement.spacedBy(space.s12),
    ) {
        RaisedCard(Modifier.testTag("about-app-card"), padding = VeloTheme.medium(20.dp) + VeloTheme.sizes.edge) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(space.s16)) {
                Image(painterResource(R.drawable.ic_about_mark), "Velo mark",
                    Modifier.testTag("about-app-mark").size(VeloTheme.medium(56.dp), VeloTheme.medium(40.dp)))
                Column(Modifier.width(IntrinsicSize.Max), verticalArrangement = Arrangement.spacedBy(VeloTheme.medium(3.dp))) {
                    Text(name, Modifier.fillMaxWidth().testTag("about-app-name").lineBox(type.pageTitle), style = type.pageTitle, color = c.text)
                    Text("Version ${info.versionName} (${info.longVersionCode})", Modifier.fillMaxWidth().testTag("about-app-version").lineBoxOrTaller(textAt(14f)),
                        style = textAt(14f), color = c.secondary)
                    Text("An unofficial Android client for Rex. MIT licensed.", Modifier.fillMaxWidth().testTag("about-app-description"),
                        style = textAt(14f).paragraph(1.4.em), color = c.secondary)
                }
            }
        }
        GroupCard(Modifier.testTag("about-links-group")) {
            // The release found is said by its text: no state says it again (#167).
            NavigationRow("Updates", openUpdates, tag = "about-updates", spacing = VeloTheme.medium(10.dp)) {
                if (available != null) Text("$available available", Modifier.testTag("about-updates-available"),
                    style = textAt(14f, FontWeight.SemiBold), color = c.accent, maxLines = 1)
                if (dot) Box(Modifier.testTag("about-updates-dot").size(VeloTheme.sizes.dot).clip(Capsule).background(c.accent)
                    .semantics { contentDescription = "Not seen yet" })
            }
            GroupDivider()
            NavigationRow("Open source licenses", openLicenses, tag = "about-licenses", spacing = VeloTheme.medium(10.dp)) {
                Text(LICENSES_SUMMARY, Modifier.testTag("about-licenses-summary"), style = textAt(14f), color = c.secondary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            GroupDivider()
            NavigationRow(
                "Source code on GitHub", {
                    try { uri.openUri(SOURCE_URL) } catch (_: IllegalArgumentException) { unopened = true }
                },
                tag = "about-source", spacing = VeloTheme.medium(10.dp), icon = Icons.AutoMirrored.Filled.OpenInNew,
            )
        }
        // No browser took the page: its address, to open elsewhere.
        if (unopened) SelectionContainer { Text("Open in a browser: $SOURCE_URL", style = type.caption, color = c.secondary) }
        Text(
            "Velo is not affiliated with or endorsed by the Rex authors.",
            Modifier.fillMaxWidth().testTag("about-disclaimer").padding(horizontal = VeloTheme.medium(6.dp)),
            style = type.caption.paragraph(1.5.em), color = c.secondary,
        )
    }
}
