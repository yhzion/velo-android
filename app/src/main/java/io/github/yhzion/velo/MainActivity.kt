package io.github.yhzion.velo

import android.content.Intent
import android.content.res.Configuration
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.descendants
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import io.github.yhzion.velo.ui.FoldInfo
import io.github.yhzion.velo.ui.MovePaneModifiers
import io.github.yhzion.velo.ui.PairingScreen
import io.github.yhzion.velo.ui.PaneShortcutModifiers
import io.github.yhzion.velo.ui.PaneMenuShortcut
import io.github.yhzion.velo.ui.PaneSurfaceView
import io.github.yhzion.velo.ui.SettingsSheet
import io.github.yhzion.velo.ui.SshQuestionDialog
import io.github.yhzion.velo.ui.StartScreen
import io.github.yhzion.velo.ui.TailscaleScreen
import io.github.yhzion.velo.ui.BackgroundPrompt
import io.github.yhzion.velo.ui.TerminalInputView
import io.github.yhzion.velo.ui.metaStateIs
import io.github.yhzion.velo.ui.movePaneDirection
import io.github.yhzion.velo.ui.Workspace
import io.github.yhzion.velo.ui.opaque
import io.github.yhzion.velo.ui.theme.BackdropSource
import io.github.yhzion.velo.ui.theme.LocalBackdropSource
import io.github.yhzion.velo.ui.theme.ProvideHaptics
import io.github.yhzion.velo.ui.theme.VeloTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /** The process's one controller: every instance of this activity uses it (see [VeloApplication]). */
    private lateinit var app: AppController
    private lateinit var input: TerminalInputView
    private var fold by mutableStateOf(FoldInfo())
    private val paneMenuShortcut = PaneMenuShortcut()
    /** Ctrl+Shift+P, which opens the palette, is followed like the pane picker's F10, by a tracker of its own. */
    private val paletteShortcut = PaneMenuShortcut()
    private val movePaneShortcut = PaneMenuShortcut()
    private val notificationLaunches = PaneNotificationLaunches()
    private var receivedNotification: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = (application as VeloApplication).controller.acquire(this)
        // A system font size change makes the activity again while the cores keep running, and the
        // controller read its first scale from the application: this activity's scale is the one.
        app.displayChanged(resources)
        val chrome = VeloTheme.colors.chrome.surface.toArgb()
        val bars = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        input = TerminalInputView(this)
        receivedNotification = PaneNotifications.token(intent)
        // A restored task may be launched by a new notification. Consume a new target,
        // but do not replay an intent the previous activity already finished handling.
        notificationLaunches.token = if (receivedNotification != null &&
            receivedNotification != savedInstanceState?.getString("receivedPaneNotification")) receivedNotification
        else savedInstanceState?.getString("paneNotification")

        // The area behind edge-to-edge content stays dark, including during IME resize.
        val root = FrameLayout(this).apply { setBackgroundColor(chrome) }
        // Ask at capture time: switching remote, tab or overview replaces the attached pane views.
        // Compose adds a view to its owner when it is made, so a pane docked by a split comes after
        // a floating one; the stable sort puts the floating panes last, as they are on the screen.
        val backdrop = BackdropSource(window = window, views = { panes(root) })
        root.addView(input, FrameLayout.LayoutParams(1, 1))
        val compose = ComposeView(this).apply {
            setContent {
                // The screens below come and go; this reader of the window's insets stays (#314).
                // Keep it here, above every screen: InsetsReaderInstrumentation tests it placed so.
                HoldWindowInsets()
                val keepScreenOn = app.settings.keepScreenOn
                SideEffect { root.keepScreenOn = keepScreenOn }
                CompositionLocalProvider(LocalBackdropSource provides backdrop) {
                    VeloTheme(size = app.settings.uiSize, paneHighlight = app.settings.paneHighlight.override?.let(::opaque)) {
                        // The Haptic feedback setting, for the library's own haptics too (#309).
                        ProvideHaptics(app.settings.haptics) {
                            key(notificationLaunches.generation) {
                                val gate = notificationLaunches.token?.let { paneNotificationGate(app, it) { notificationLaunches.token = null } }
                                Screens(gate)
                            }
                        }
                    }
                }
            }
        }
        root.addView(compose, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        // A recreated activity still holds its first intent: what it asked for was done then.
        if (savedInstanceState == null) {
            sshQuestionIntent(intent)
            debugIntent(intent)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                WindowInfoTracker.getOrCreate(this@MainActivity).windowLayoutInfo(this@MainActivity).collect { info ->
                    val f = info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()
                    fold = FoldInfo(
                        tabletop = f != null && f.state == FoldingFeature.State.HALF_OPENED &&
                            f.orientation == FoldingFeature.Orientation.HORIZONTAL,
                        hingeTopPx = f?.bounds?.top ?: 0,
                    )
                    hideSystemBars()
                }
            }
        }
    }

    /** The pane views as the screen stacks them: a docked one under a floating one. */
    private fun panes(root: ViewGroup) =
        root.descendants.filterIsInstance<PaneSurfaceView>().filter { it.isShown }.sortedBy { it.floating }.toList()

    @Composable
    private fun Screens(gate: PaneNotificationGate?) {
        var settings by rememberSaveable { mutableStateOf(false) }
        var tailscale by rememberSaveable { mutableStateOf(false) }
        var pairing by remember { mutableStateOf(false) }
        BackHandler(settings && !pairing) { settings = false }
        BackHandler(tailscale && !pairing) { tailscale = false }
        BackHandler(pairing) { pairing = false }
        val conn = app.active
        // Each of these screens takes the workspace's place and its pane views go, which closes
        // no pane: back on the workspace they are shown as they were and take no terminal
        // (`PaneCover`). Told while composing, because the views ask as this composition removes them.
        // Settings open over the screen as a sheet: the workspace stays, and so do its pane views.
        if (pairing || tailscale) conn?.cover?.begin()
        val notice = gate != null && (!gate.opened || conn == null)
        when {
            notice -> PaneNotificationNotice(gate!!)
            // A remote added by its QR code is shown at once: the screens above it close.
            pairing -> PairingScreen(
                add = { app.addRemote("", it) },
                onDone = { pairing = false; settings = false },
                onBack = { pairing = false },
            )
            tailscale -> TailscaleScreen(app, onBack = { tailscale = false })
            // No remote saved: log in to the tailnet, pick a server found there, or scan a code.
            conn == null -> StartScreen(app, onAddByQr = { pairing = true }, onSettings = { settings = true })
            else -> Workspace(app, conn, input, fold, gate, notificationLaunches, settingsOpen = settings,
                onSettings = { settings = true }, onTailscale = { tailscale = true })
        }
        if (settings && !notice && !pairing && !tailscale) SettingsSheet(app, onClose = { settings = false })
        // First time a remote is connected: ask whether Velo may stay connected off screen.
        if (!notice && !pairing && !tailscale && conn?.connected == true) BackgroundPrompt(app)
        // An SSH login that waits for the user (a host key, a password): over whatever screen is up.
        SshQuestionDialog(app.sshQuestions)
    }

    /**
     * Full screen on both displays: status and navigation bars stay hidden until swiped in.
     * Called again on every fold change, since the system may bring the bars back then, and
     * whenever the window gets the focus ([onWindowFocusChanged]).
     */
    private fun hideSystemBars() {
        val c = WindowCompat.getInsetsController(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // The layout info alone did not keep the bars hidden: after a first start the navigation
        // bar stayed on screen, with no fold change to hide it again. Each time the window gets
        // the focus back (first start, another app, a system dialog), the bars are hidden again.
        if (hasFocus) hideSystemBars()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // The shortcuts of the pane picker and the palette stay local while the shown workspace
        // offers them ([dispatchShortcut]); without that they are keys like the others. Other keys
        // go where [hardwareKeyRoute] says: to the pane of the workspace whose terminal input has
        // the focus, nowhere while its connection is down, or to the focused view and the system.
        val fromHardware = event.deviceId > 0 && event.device?.isVirtual == false
        if (::input.isInitialized) {
            val activeWorkspace = input.connection === app.active
            // A Compose text field (the sidebar's Filter) has the focus through the ComposeView,
            // which reports itself as a text editor only while such a field is focused.
            val focus = currentFocus
            val route = hardwareKeyRoute(
                fromHardware = fromHardware,
                systemKey = event.isSystem,
                terminalOwnsFocus = activeWorkspace && input.acceptsFocusedInput,
                panesPaused = activeWorkspace && input.panesPaused(),
                otherEditorFocused = focus != null && focus !== input && focus.onCheckIsTextEditor(),
            )
            if (fromHardware && !event.isSystem) {
                if (dispatchShortcut(paneMenuShortcut, event, KeyEvent.KEYCODE_F10, if (activeWorkspace) input.onPaneMenuShortcut else null, PaneShortcutModifiers) ||
                    dispatchShortcut(paletteShortcut, event, KeyEvent.KEYCODE_P, if (activeWorkspace) input.onPaletteShortcut else null, PaneShortcutModifiers) ||
                    dispatchMovePane(event, activeWorkspace)
                ) return true
                // A key that went to a pane when it was pressed is also released there, whatever
                // the route is by then: the input view follows each press it forwarded.
                if (input.dispatchHardwareKey(event, terminalOwnsFocus = route == HardwareKeyRoute.Forward)) return true
            }
            if (route == HardwareKeyRoute.Swallow) return true
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * Ctrl+Alt+Shift+arrow moves the focused pane toward the arrow, while the shown workspace offers that move
     * ([TerminalInputView.onMovePaneShortcut]). With no move possible the arrow is not claimed and goes on to the terminal.
     * The workspace is asked only for a press that may be claimed (its key down with the chord held) and for the key up of
     * a claimed press, which runs the move; any other arrow, and every auto-repeat, costs nothing.
     */
    private fun dispatchMovePane(event: KeyEvent, activeWorkspace: Boolean): Boolean {
        val direction = movePaneDirection(event.keyCode) ?: return false
        val asks = when (event.action) {
            KeyEvent.ACTION_DOWN -> event.repeatCount == 0 && metaStateIs(event.metaState, MovePaneModifiers)
            KeyEvent.ACTION_UP -> movePaneShortcut.holds(event.deviceId, event.downTime)
            else -> false
        }
        val move = if (activeWorkspace && asks) input.onMovePaneShortcut?.invoke(direction) else null
        return dispatchShortcut(movePaneShortcut, event, event.keyCode, move, MovePaneModifiers)
    }

    /**
     * [modifiers] ([PaneShortcutModifiers] or [MovePaneModifiers], with no default so that none is forgotten) plus [keyCode] runs [open], the callback the shown workspace offers for it. [shortcut]
     * claims the key on key down and acts at key up, and a claimed key never reaches the terminal.
     * With no callback the key is not claimed: it takes the usual route ([hardwareKeyRoute]),
     * where it may be forwarded, swallowed or passed on.
     */
    private fun dispatchShortcut(shortcut: PaneMenuShortcut, event: KeyEvent, keyCode: Int, open: (() -> Unit)?, modifiers: Int): Boolean {
        if (event.keyCode != keyCode) return false
        return shortcut.dispatch(
            deviceId = event.deviceId,
            downTime = event.downTime,
            action = event.action,
            repeatCount = event.repeatCount,
            hasShortcutModifiers = metaStateIs(event.metaState, modifiers),
            canceled = event.isCanceled,
            open = open,
        )
    }

    override fun onResume() {
        super.onResume()
        app.resumedActivities++
        input.onActivityResumed()
    }

    // The manifest keeps the activity through a `density` change: the terminals take the new scale here.
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        app.displayChanged(resources)
    }

    override fun onPause() {
        app.resumedActivities--
        input.onActivityPaused()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("paneNotification", notificationLaunches.pending ?: notificationLaunches.token)
        outState.putString("receivedPaneNotification", receivedNotification)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        PaneNotifications.token(intent)?.let {
            receivedNotification = it
            input.disconnectInput()
            notificationLaunches.open(it)
        }
        sshQuestionIntent(intent)
        debugIntent(intent)
    }

    /** "REMOTE needs your answer to connect" was tapped: its remote is shown, with the question if it still waits. */
    private fun sshQuestionIntent(intent: Intent?) {
        intent?.getStringExtra(SshQuestionNotices.EXTRA_TARGET)?.let(app::openSshQuestion)
    }

    /**
     * Debug builds: `am start -n io.github.yhzion.velo/.MainActivity --ez tailnet true|false` switches
     * the app's Tailscale node on or off, `--es tailnetName NAME` renames it, `--ez tailnetLogout
     * true` logs it out; all persist (state, including the login URL, goes to logcat under
     * `velo.tailnet`). The activity is singleTask, so a running one gets the intent here
     * through [onNewIntent]; no flag is needed.
     */
    private fun debugIntent(intent: Intent?) {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0 || intent == null) return
        if (intent.hasExtra("tailnet")) app.setTailnet(intent.getBooleanExtra("tailnet", false))
        intent.getStringExtra("tailnetName")?.let(app::setTailnetName)
        if (intent.getBooleanExtra("tailnetLogout", false)) app.logoutTailnet()
    }

    /**
     * A finishing activity (the user removed the task) takes the controller and its cores with
     * it; one that is only recreated leaves them running for the next instance, whose pane
     * views attach their surfaces again.
     */
    override fun onDestroy() {
        (application as VeloApplication).controller.release(this, forGood = isFinishing)
        super.onDestroy()
    }
}

/**
 * Keeps Compose following the window's insets for as long as the composition lives (#314).
 *
 * Compose follows a window's insets, and the insets animations over it, only while something in
 * the composition reads `WindowInsets`. When the last reader leaves and the next one arrives, it
 * starts afresh (foundation-layout 1.13, `WindowInsetsHolder.incrementAccessors`): it forgets the
 * animations that run and the insets it set aside for their end. Each screen here reads the insets
 * for itself, and a change of screen replaces them all at once. If that happens while the bars'
 * hide animation and the input method's show animation run, before the first of them ends, Compose
 * ends with the bars' "no bar" while Android keeps the navigation bar up for the input method's
 * window, and the rail stays under the taskbar (#291). Called above the screens, this reader never
 * leaves: Compose keeps both animations and takes the window's insets when the last one ends.
 *
 * It reads no value, so nothing recomposes for it and nothing moves.
 *
 * Not covered, on Android 11 alone: when an animation Compose was told to prepare is cancelled
 * before it starts, the listener's `run()` sets its mask of running animations to 0 and forgets one
 * of another type that still runs. Covering that would need #291's helper again (the insets asked
 * for after each animation's end), which #314 removed.
 */
@Composable
internal fun HoldWindowInsets() {
    // The getter registers this composition as a reader for as long as it stays; the value is not needed.
    WindowInsets.safeDrawing
}
