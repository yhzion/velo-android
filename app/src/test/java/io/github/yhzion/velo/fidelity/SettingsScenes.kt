package io.github.yhzion.velo.fidelity

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import io.github.yhzion.velo.BackgroundConnection
import io.github.yhzion.velo.FakeKeys
import io.github.yhzion.velo.FakeSealer
import io.github.yhzion.velo.GlyphRasterizer
import io.github.yhzion.velo.PaneNotifications
import io.github.yhzion.velo.Settings
import io.github.yhzion.velo.SshSecrets
import io.github.yhzion.velo.TailnetState
import io.github.yhzion.velo.TerminalFont
import io.github.yhzion.velo.core.ForwardInfo
import io.github.yhzion.velo.core.Remote
import io.github.yhzion.velo.ui.PaneText
import io.github.yhzion.velo.ui.SettingsCategory
import io.github.yhzion.velo.ui.SettingsHost
import io.github.yhzion.velo.ui.SettingsLevel
import io.github.yhzion.velo.ui.SettingsPanel
import io.github.yhzion.velo.ui.SettingsRoute
import io.github.yhzion.velo.ui.TailnetLogin
import io.github.yhzion.velo.ui.theme.OverlayLayer
import io.github.yhzion.velo.ui.theme.OverlayLayerBox
import io.github.yhzion.velo.ui.theme.UiSize
import io.github.yhzion.velo.ui.theme.VeloTheme
import io.github.yhzion.velo.updates.AppRelease
import io.github.yhzion.velo.updates.UpdateChannel
import io.github.yhzion.velo.updates.UpdateSettings
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext

/**
 * The settings without an [io.github.yhzion.velo.AppController]: real preferences, fonts,
 * notifications and SSH keys (with the test sealer and key tool), and the rest as given. What a
 * page reads runs in place ([io]), so the first frame captured has it.
 */
internal class FakeSettingsHost(context: Context) : SettingsHost {
    override val settings = Settings(context.getSharedPreferences("fidelity-settings", Context.MODE_PRIVATE))
    override val io: CoroutineContext = Dispatchers.Unconfined
    override val rasterizer = GlyphRasterizer(context.assets)
    var fonts: List<TerminalFont> = TerminalFont.bundled
    override fun fontChoices() = fonts
    override fun importFont(uri: Uri) = emptyList<TerminalFont>()
    override fun pushDisplay() {}
    override fun pushFont() {}
    override fun pushSetting(key: String, value: Any) {}
    override fun focusedPaneText(): PaneText? = null
    override val notifications = PaneNotifications(context)
    override val background = BackgroundConnection(context)
    override var saved: List<Remote> = emptyList()
    override fun forwards(remote: Remote) = emptyList<ForwardInfo>()
    override fun stopForward(remote: Remote, block: String) {}
    override fun addRemote(label: String, url: String): String? = null
    override fun renameRemote(id: String, label: String) {}
    override fun removeRemote(id: String) {}
    @Composable override fun SshDetails(remote: Remote, onDismiss: () -> Unit) {}
    override var tailnetState: TailnetState = TailnetState.Off
    override val tailnetPaused = false
    override val tailnetLogin = TailnetLogin(startNode = {})
    override fun setTailnet(on: Boolean) {}
    override fun setTailnetName(name: String) {}
    override fun logoutTailnet() {}
    override val sshSecrets = SshSecrets(context.getSharedPreferences("fidelity-ssh", Context.MODE_PRIVATE), FakeSealer(), FakeKeys())
    var releases: List<AppRelease> = emptyList()
    override fun newerReleases(channel: UpdateChannel, installed: Long) = releases
    override val installsReleases = true
}

/** The boards' sample release, newer than any installed build. */
private val SampleRelease = AppRelease(
    tag = "v2.10.1", versionCode = Long.MAX_VALUE / 2, channel = UpdateChannel.Stable,
    apkUrl = "https://example.invalid/velo.apk", sha256 = "", notesUrl = "https://github.com/yhzion/velo-android/releases",
)

/** The boards' sample data: the remotes, the node, the switches as the boards draw them, and a release found but not yet seen. */
private fun FakeSettingsHost.boardSample(context: Context) {
    saved = listOf(
        Remote("studio-mac", "studio-mac", "https://studio-mac.example.ts.net"),
        Remote("build-server", "build-server", "ssh://dev@build-server.example.ts.net"),
    )
    tailnetState = TailnetState.Running("velo-phone", listOf("100.101.12.7", "fd7a:115c:a1e0::4501:c07"))
    settings.saveTailnet(true)
    settings.saveTailnetName("velo-phone")
    settings.saveTapGestures(true)
    releases = listOf(SampleRelease)
    UpdateSettings.record(context, UpdateSettings.channel(context), System.currentTimeMillis(), SampleRelease)
}

/** The settings at [route] as the app draws them, over the black the boards put around the sheet. */
@Composable
private fun SettingsScene(route: SettingsRoute, prepare: FakeSettingsHost.() -> Unit = {}) {
    val context = LocalContext.current
    val host = remember { FakeSettingsHost(context).apply { boardSample(context); prepare() } }
    val layer = remember { OverlayLayer() }
    VeloTheme(UiSize.Medium) {
        OverlayLayerBox(layer, Modifier.fillMaxSize().background(Color.Black)) {
            SettingsPanel(host, onClose = {}, start = route)
        }
    }
}

/** Every settings screen of the canvas (page "4 설정"), by its screen id. */
internal val SettingsScenes: Map<String, @Composable () -> Unit> = linkedMapOf(
    "settings-home" to { SettingsScene(SettingsRoute()) },
    "settings-appearance" to { SettingsScene(SettingsRoute(SettingsCategory.Appearance)) },
    "settings-font" to { SettingsScene(SettingsRoute(SettingsCategory.Appearance, SettingsLevel.Font)) },
    "settings-keyboard" to { SettingsScene(SettingsRoute(SettingsCategory.Keyboard)) },
    "settings-key-order" to { SettingsScene(SettingsRoute(SettingsCategory.Keyboard)) },
    "settings-notif" to { SettingsScene(SettingsRoute(SettingsCategory.Notifications)) },
    "settings-remotes" to { SettingsScene(SettingsRoute(SettingsCategory.Remotes)) },
    "settings-add-remote" to { SettingsScene(SettingsRoute(SettingsCategory.Remotes)) },
    "settings-tailscale" to { SettingsScene(SettingsRoute(SettingsCategory.Remotes, SettingsLevel.Device)) },
    "settings-ssh" to { SettingsScene(SettingsRoute(SettingsCategory.Remotes, SettingsLevel.SshKey)) },
    "settings-about" to { SettingsScene(SettingsRoute(SettingsCategory.About)) },
    "settings-updates" to { SettingsScene(SettingsRoute(SettingsCategory.About, SettingsLevel.Updates)) },
    "settings-licenses" to { SettingsScene(SettingsRoute(SettingsCategory.About, SettingsLevel.Licenses)) },
    "settings-license" to { SettingsScene(SettingsRoute(SettingsCategory.About, SettingsLevel.License, "ghostty")) },
)
