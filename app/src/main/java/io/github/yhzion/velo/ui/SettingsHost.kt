package io.github.yhzion.velo.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.IntSize
import io.github.yhzion.velo.AppController
import io.github.yhzion.velo.BackgroundConnection
import io.github.yhzion.velo.GlyphRasterizer
import io.github.yhzion.velo.PaneNotifications
import io.github.yhzion.velo.Settings
import io.github.yhzion.velo.SshSecrets
import io.github.yhzion.velo.Tailnet
import io.github.yhzion.velo.TailnetState
import io.github.yhzion.velo.TerminalFont
import io.github.yhzion.velo.core.ForwardInfo
import io.github.yhzion.velo.core.Remote
import io.github.yhzion.velo.updates.AppRelease
import io.github.yhzion.velo.updates.ReleaseFeed
import io.github.yhzion.velo.updates.UpdateChannel
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext

/** The focused pane's text: its size in px, and the pane's size in px when it has been drawn. */
internal class PaneText(val fontPx: Float, val paneSize: IntSize?)

/**
 * What the settings read and change beyond their own pages: the preferences, the remotes, the
 * Tailscale node, the SSH keys, the terminal's fonts, Android's notifications and battery, and
 * the releases on GitHub. [AppSettingsHost] is the app's ([AppController]). A test draws the
 * pages with a fake, since the app's native core does not load on the JVM.
 */
internal interface SettingsHost {
    val settings: Settings
    /**
     * Where a page reads files, fonts, the Keystore and the network: off the main thread in the
     * app. A test runs it in place, so that the first frame it captures has what was read.
     */
    val io: CoroutineContext

    // Appearance
    val rasterizer: GlyphRasterizer
    /** The fonts the terminal can use: the bundled ones, the imported ones, the device's. Reads files: call it on [io]. */
    fun fontChoices(): List<TerminalFont>
    /** Keeps a copy of the font file at [uri] and returns its faces. Reads the file: call it on [io]. */
    fun importFont(uri: Uri): List<TerminalFont>
    /** The pane style changed the padding: every remote fits its panes to it. */
    fun pushDisplay()
    /** The terminal's font changed: the rasterizer takes it, and every remote redraws with its line height. */
    fun pushFont()
    /** A terminal metric changed ([key], [value]): every remote takes it. */
    fun pushSetting(key: String, value: Any)
    /** The focused pane's text, for the font size preview; null without a focused pane. */
    fun focusedPaneText(): PaneText?

    // Notifications
    val notifications: PaneNotifications
    val background: BackgroundConnection

    // Remotes
    val saved: List<Remote>
    /** The ports of [remote] forwarded to the phone. */
    fun forwards(remote: Remote): List<ForwardInfo>
    fun stopForward(remote: Remote, block: String)
    /** Saves a remote; returns the label of the saved remote that already has [url], else null. */
    fun addRemote(label: String, url: String): String?
    fun renameRemote(id: String, label: String)
    fun removeRemote(id: String)
    /** The panel of what the phone keeps for the ssh remote [remote]. */
    @Composable fun SshDetails(remote: Remote, onDismiss: () -> Unit)

    // This Device
    val tailnetState: TailnetState
    /** Android withholds the network from the app, so the node cannot reach the tailnet. */
    val tailnetPaused: Boolean
    val tailnetLogin: TailnetLogin
    fun setTailnet(on: Boolean)
    fun setTailnetName(name: String)
    fun logoutTailnet()

    // SSH key
    val sshSecrets: SshSecrets

    // Updates
    /** The releases on [channel] newer than [installed], newest first. Reads the network: call it on [io]. */
    fun newerReleases(channel: UpdateChannel, installed: Long): List<AppRelease>
    /** Release APKs are built for ARM64 only. */
    val installsReleases: Boolean
}

/** The settings of the app's own [AppController]. */
internal class AppSettingsHost(private val app: AppController) : SettingsHost {
    override val settings get() = app.settings
    override val io: CoroutineContext = Dispatchers.IO
    override val rasterizer get() = app.rasterizer
    override fun fontChoices() = app.fonts.available()
    override fun importFont(uri: Uri) = app.fonts.importFile(uri)
    override fun pushDisplay() = app.pushDisplay()
    override fun pushFont() = app.pushFont()
    override fun pushSetting(key: String, value: Any) = app.setting(key, value)
    override fun focusedPaneText(): PaneText? {
        val conn = app.active ?: return null
        val block = conn.state.focused ?: return null
        return PaneText(conn.terminalFontPx(block), conn.shownPaneSize(block))
    }
    override val notifications get() = app.notifications
    override val background get() = app.background
    override val saved get() = app.saved
    override fun forwards(remote: Remote) = app.connectionOf(remote)?.state?.forwards.orEmpty()
    override fun stopForward(remote: Remote, block: String) { app.connectionOf(remote)?.closeForward(block) }
    override fun addRemote(label: String, url: String) = app.addRemote(label, url)
    override fun renameRemote(id: String, label: String) = app.renameRemote(id, label)
    override fun removeRemote(id: String) = app.removeRemote(id)
    @Composable override fun SshDetails(remote: Remote, onDismiss: () -> Unit) = SshDetailsPanel(app, remote, onDismiss)
    override val tailnetState get() = Tailnet.state
    override val tailnetPaused get() = Tailnet.paused
    override val tailnetLogin get() = app.tailnetLogin
    override fun setTailnet(on: Boolean) = app.setTailnet(on)
    override fun setTailnetName(name: String) = app.setTailnetName(name)
    override fun logoutTailnet() = app.logoutTailnet()
    override val sshSecrets get() = app.sshSecrets
    override fun newerReleases(channel: UpdateChannel, installed: Long) = ReleaseFeed.newer(channel, installed)
    override val installsReleases get() = "arm64-v8a" in android.os.Build.SUPPORTED_ABIS
}
