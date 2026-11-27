package io.github.yhzion.velo.fidelity

import io.github.yhzion.velo.SshKeyTool
import io.github.yhzion.velo.ui.RemoteDraft
import androidx.compose.ui.platform.LocalConfiguration
import io.github.yhzion.velo.PaneAlertKind
import org.robolectric.Shadows
import android.os.PowerManager
import android.app.Application
import android.Manifest
import io.github.yhzion.velo.ui.TapKey
import io.github.yhzion.velo.ui.SettingsOpen
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
import io.github.yhzion.velo.LicenseComponent
import io.github.yhzion.velo.LicenseGroup
import io.github.yhzion.velo.OpenSourceLicenses
import io.github.yhzion.velo.PaneNotifications
import io.github.yhzion.velo.Settings
import io.github.yhzion.velo.SshSecrets
import io.github.yhzion.velo.TailnetState
import io.github.yhzion.velo.TerminalFont
import io.github.yhzion.velo.openSshPem
import io.github.yhzion.velo.core.ForwardInfo
import io.github.yhzion.velo.core.Remote
import io.github.yhzion.velo.readNotice
import io.github.yhzion.velo.ui.OverviewLayout
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
import io.github.yhzion.velo.updates.ChangeKind
import io.github.yhzion.velo.updates.ChangeSection
import io.github.yhzion.velo.updates.UpdateChannel
import io.github.yhzion.velo.updates.UpdateSettings
import kotlinx.coroutines.Dispatchers
import org.robolectric.RuntimeEnvironment
import kotlin.coroutines.CoroutineContext

/**
 * The settings without an [io.github.yhzion.velo.AppController]: real preferences, fonts,
 * notifications and SSH keys (with the test sealer and key tool), and the rest as given. What a
 * page reads runs in place ([io]), so the first frame captured has it.
 */
internal class FakeSettingsHost(val context: Context) : SettingsHost {
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
    /** The key tool: the test one, whose phone key is the SetSsh block's. */
    val keyTool = BoardKeys()
    override val sshSecrets = SshSecrets(context.getSharedPreferences("fidelity-ssh", Context.MODE_PRIVATE), FakeSealer(), keyTool)
    var releases: List<AppRelease> = emptyList()
    /** What else happens while the page checks. */
    var onCheck: () -> Unit = {}
    override fun newerReleases(channel: UpdateChannel, installed: Long) = releases.also { onCheck() }
    override val installsReleases = true
    override var licenses: List<LicenseComponent> = OpenSourceLicenses.all
    /** Texts in place of the APK's notices, by their asset path. */
    var notices: Map<String, String> = emptyMap()
    private val assets = context.assets
    override fun readNotice(asset: String) = notices[asset] ?: readNotice(asset, assets::open)
}

/** The boards' list of licenses (SetLicenses block). */
private val SampleLicenses = listOf(
    LicenseComponent("velo", "Velo", "2.10.0", "MIT", LicenseGroup.Velo, null, emptyList()),
    LicenseComponent("notice", "NOTICE", "Full notice for this build", "Text", LicenseGroup.Velo, null, emptyList()),
    LicenseComponent("ghostty", "Ghostty (libghostty-vt)", "1.0.1", "MIT", LicenseGroup.TerminalCore, null, emptyList()),
    LicenseComponent("simdutf", "simdutf", "9.0.0", "Apache-2.0 / MIT", LicenseGroup.TerminalCore, null, emptyList()),
    LicenseComponent("highway", "Google Highway", "1.2.0", "Apache-2.0", LicenseGroup.TerminalCore, null, emptyList()),
    LicenseComponent("uucode", "uucode and Unicode data", "0.2.0", "MIT, Unicode", LicenseGroup.TerminalCore, null, emptyList()),
    LicenseComponent("tailscale", "Tailscale", "tailscale.com 1.104.1", "BSD-3-Clause", LicenseGroup.Networking, null, emptyList()),
    LicenseComponent("libtailscale", "libtailscale", "dacde75", "BSD-3-Clause", LicenseGroup.Networking, null, emptyList()),
    LicenseComponent("go-modules", "Go modules", "34 modules", "Various", LicenseGroup.Networking, null, emptyList()),
    LicenseComponent("androidx", "AndroidX and Jetpack Compose", "Release runtime", "Apache-2.0", LicenseGroup.Android, null, emptyList()),
    LicenseComponent("kotlin", "Kotlin and kotlinx", "2.4.21", "Apache-2.0", LicenseGroup.Android, null, emptyList()),
    LicenseComponent("zxing", "ZXing Core", "3.5.4", "Apache-2.0", LicenseGroup.Android, null, emptyList()),
    LicenseComponent("jetbrains-mono", "JetBrains Mono", "2.304", "OFL-1.1", LicenseGroup.Fonts, null, emptyList()),
    LicenseComponent("fira-code", "Fira Code", "6.2", "OFL-1.1", LicenseGroup.Fonts, null, emptyList()),
    LicenseComponent("d2coding", "D2Coding Ligature", "1.4.0", "OFL-1.1", LicenseGroup.Fonts, null, emptyList()),
    LicenseComponent("noto-emoji", "Noto Emoji", "2.051", "OFL-1.1", LicenseGroup.Fonts, null, emptyList()),
)

/** The boards' license text of Ghostty, cut short as the SetLicense block cuts it. */
private val SampleGhosttyLicense = """MIT License

Copyright (c) 2024 Mitchell Hashimoto, Ghostty contributors

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND …"""

/** The boards' sample release, newer than any installed build. */
private val SampleRelease = AppRelease(
    tag = "v2.10.1", versionCode = Long.MAX_VALUE / 2, channel = UpdateChannel.Stable,
    apkUrl = "https://example.invalid/velo.apk", sha256 = "", notesUrl = "https://github.com/yhzion/velo-android/releases",
    changes = listOf(
        ChangeSection(ChangeKind.Added, listOf(
            "Tabs show the program’s own logo.",
            "A tab with split panes stacks its icon, one layer per pane.",
            "Settings use two columns on an unfolded screen.",
        )),
        ChangeSection(ChangeKind.Changed, listOf("A calmer start animation, shown once per launch.")),
        ChangeSection(ChangeKind.Fixed, listOf("The key bar no longer jumps when the keyboard opens.")),
    ),
)

/** The boards' sample data: the remotes, the node, the switches as the boards draw them, and a release found but not yet seen. */
private fun FakeSettingsHost.boardSample(context: Context) {
    saved = listOf(
        // ssh, so that its menu holds SSH details as the open board's does: the app offers them on ssh remotes only.
        Remote("studio-mac", "studio-mac", "ssh://me@studio-mac.example.ts.net"),
        Remote("build-server", "build-server", "ssh://dev@build-server.example.ts.net"),
    )
    tailnetState = TailnetState.Running("velo-phone", listOf("100.101.12.7", "fd7a:115c:a1e0::4501:c07"))
    settings.saveTailnet(true)
    settings.saveTailnetName("velo-phone")
    settings.saveTapGestures(true)
    settings.saveDoubleTapKey(TapKey.Tab)
    settings.saveTripleTapKey(TapKey.Enter)
    settings.saveKeyBar(listOf("/", "Ctrl", "Esc", "Tab", "Left", "Down", "Up", "Right", "Backspace"))
    settings.saveOverviewLayout(OverviewLayout.Grid)
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
    "settings-font" to { SettingsScene(SettingsRoute(SettingsCategory.Appearance, SettingsLevel.Font)) { fonts = SampleFonts } },
    "settings-keyboard" to { SettingsScene(SettingsRoute(SettingsCategory.Keyboard)) },
    "settings-key-order" to { SettingsScene(SettingsRoute(SettingsCategory.Keyboard, open = SettingsOpen(editingOrder = true))) },
    "settings-notif" to { SettingsScene(SettingsRoute(SettingsCategory.Notifications)) { boardNotifications() } },
    "settings-remotes" to {
        // Only the unfolded board draws studio-mac's menu open.
        val wide = LocalConfiguration.current.screenWidthDp >= 600
        SettingsScene(SettingsRoute(SettingsCategory.Remotes, open = SettingsOpen(remoteMenu = if (wide) "studio-mac" else null))) { sshSecrets.publicLine() }
    },
    "settings-add-remote" to {
        val draft = RemoteDraft("Studio Mac", "ssh://dev@studio-mac.example.ts.net")
        SettingsScene(SettingsRoute(SettingsCategory.Remotes, open = SettingsOpen(addingRemote = draft))) { sshSecrets.publicLine() }
    },
    "settings-tailscale" to { SettingsScene(SettingsRoute(SettingsCategory.Remotes, SettingsLevel.Device)) },
    "settings-ssh" to {
        SettingsScene(SettingsRoute(SettingsCategory.Remotes, SettingsLevel.SshKey)) {
            sshSecrets.publicLine()
            sshSecrets.importKey(BoardImportedKey, null)
        }
    },
    "settings-about" to { SettingsScene(SettingsRoute(SettingsCategory.About)) },
    // The board keeps About's dot on the Updates page, which marks the release it shows as seen: a later
    // check found a newer build than the page shows, so the dot stays.
    "settings-updates" to {
        SettingsScene(SettingsRoute(SettingsCategory.About, SettingsLevel.Updates)) {
            onCheck = {
                // After the page's check started: the page's own record is older, so this one stays.
                Thread.sleep(2)
                val context = RuntimeEnvironment.getApplication()
                UpdateSettings.record(context, UpdateSettings.channel(context), System.currentTimeMillis(),
                    SampleRelease.copy(versionCode = SampleRelease.versionCode + 1))
            }
        }
    },
    "settings-licenses" to { SettingsScene(SettingsRoute(SettingsCategory.About, SettingsLevel.Licenses)) { licenses = SampleLicenses } },
    "settings-license" to {
        SettingsScene(SettingsRoute(SettingsCategory.About, SettingsLevel.License, "ghostty")) {
            notices = mapOf("notices/third-party/native/ghostty-MIT.txt" to SampleGhosttyLicense)
        }
    },
)

/** The board's fonts: the bundled ones, two of the device's and one imported (canvas SetFont block). */
private val SampleFonts = listOf(
    TerminalFont.JETBRAINS, TerminalFont.FIRA_CODE, TerminalFont.CASCADIA_CODE,
    TerminalFont.D2CODING.copy(label = "D2Coding Ligature"),
    TerminalFont("sarasa-term-k-velo", "Sarasa Term K Velo", true, hangul = true),
    TerminalFont("device:Droid Sans Mono", "Droid Sans Mono", true),
    TerminalFont("device:Noto Sans Mono", "Noto Sans Mono", true),
    TerminalFont("import:ab12:Iosevka Term", "Iosevka Term", true),
)

/** The SetNotifications block's state: notifications allowed, the battery unrestricted, Work started quiet. */
private fun FakeSettingsHost.boardNotifications() {
    Shadows.shadowOf(context.applicationContext as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    Shadows.shadowOf(context.getSystemService(PowerManager::class.java)).setIgnoringBatteryOptimizations(context.packageName, true)
    with(settings) {
        saveActivityNotices(true)
        saveActivitySounds(true)
        saveActivityVolume(1f)
        saveStayConnected(true)
        for (kind in PaneAlertKind.entries) {
            val on = kind != PaneAlertKind.Working
            savePosts(kind, on)
            savePlays(kind, on)
            saveSound(kind, kind.defaultSound)
        }
    }
}

/** The test key tool, but the phone key it makes is about as long as the one the SetSsh block shows. */
internal class BoardKeys(val fake: FakeKeys = FakeKeys()) : SshKeyTool by fake {
    override fun generate(comment: String): Pair<String, String> = fake.generate(comment).first to BoardPhoneKey
}

// The board's public key line, so the fingerprints the app shows are the board's too.
private const val BoardPhoneKey = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIK7v1Qm3xR9tY2b velo-phone"

/** The SetSsh block's imported key: an rsa key without a comment. Its digest is the scene's own (tools/fidelity/known-text.json). */
private val BoardImportedKey = openSshPem("ssh-rsa", ByteArray(32) { (it * 7).toByte() }).first
