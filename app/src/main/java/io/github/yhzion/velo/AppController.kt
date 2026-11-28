package io.github.yhzion.velo

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.res.Resources
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.yhzion.velo.core.SessionInfo
import io.github.yhzion.velo.core.Remote
import io.github.yhzion.velo.core.hostKey
import io.github.yhzion.velo.core.isSshLocator
import io.github.yhzion.velo.ui.CapsuleCorner
import io.github.yhzion.velo.ui.PaneHighlight
import io.github.yhzion.velo.ui.SshKept
import io.github.yhzion.velo.ui.TailnetLogin
import io.github.yhzion.velo.ui.forgetTrustedHostKey
import io.github.yhzion.velo.ui.readEndpointsFile
import io.github.yhzion.velo.ui.readSshKept
import io.github.yhzion.velo.ui.rememberedFor
import io.github.yhzion.velo.ui.displayName
import io.github.yhzion.velo.ui.hexOf
import io.github.yhzion.velo.ui.readKeyOrder
import io.github.yhzion.velo.ui.OverviewLayout
import io.github.yhzion.velo.ui.theme.UiSize
import kotlinx.serialization.json.Json
import io.github.yhzion.velo.ui.TapKey
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Client-local appearance and keyboard settings, global to every host, persisted in private SharedPreferences. */
class Settings(
    private val prefs: SharedPreferences,
    restoreFont: (String?) -> TerminalFont = { id -> TerminalFont.bundledFor(id) ?: TerminalFont.JETBRAINS },
) {

    var keepScreenOn by mutableStateOf(prefs.getBoolean("keepScreenOn", false))
        private set
    var fontSize by mutableStateOf(prefs.getFloat("fontSize", 13f))
        private set
    var font by mutableStateOf(restoreFont(prefs.getString("font", null)))
        private set
    var paneStyle by mutableStateOf(prefs.getString("paneStyle", "comfortable")!!)
        private set
    /** How large the app's own controls and text are; the terminal's text has [fontSize]. */
    var uiSize by mutableStateOf(UiSize.parse(prefs.getString("uiSize", null)))
        private set
    /** How the tab overview lays its cards out (Appearance → Interface → Overview); phone only, not sent with `app.setting`. */
    var overviewLayout by mutableStateOf(OverviewLayout.parse(prefs.getString("overviewLayout", null)))
        private set
    /** The edge of the pane that takes the keys (Appearance → Active pane), with the custom colour kept. */
    var paneHighlight by mutableStateOf(PaneHighlight.read(prefs.getString("paneHighlight", null), prefs.getString("paneHighlightCustom", null)))
        private set
    /** The corner the user chose for minimized-pane capsules; null until they choose, so a changed default reaches them. */
    var capsuleCorner by mutableStateOf(CapsuleCorner.parse(prefs.getString("capsuleCorner", null)))
        private set
    var keyBar by mutableStateOf(prefs.getString("keyBar", null)?.let {
        readKeyOrder(it, prefs.getBoolean("keyBarOrdered", false))
    } ?: DEFAULT_KEY_BAR.split(','))
        private set
    var activitySounds by mutableStateOf(prefs.getBoolean("activitySounds", true))
        private set
    var activityNotices by mutableStateOf(prefs.getBoolean("activityNotices", true))
        private set
    var activityVolume by mutableFloatStateOf(prefs.getFloat("activityVolume", 1f))
        private set
    /** Stay connected while the app is off screen, so notifications arrive (Settings → Notifications → Background). */
    var stayConnected by mutableStateOf(prefs.getBoolean("stayConnected", true))
        private set
    /** The first-connection prompt about background running was answered or dismissed. */
    var backgroundAsked by mutableStateOf(prefs.getBoolean("backgroundAsked", false))
        private set
    fun saveStayConnected(on: Boolean) { stayConnected = on; prefs.edit().putBoolean("stayConnected", on).apply() }
    fun saveBackgroundAsked() { backgroundAsked = true; prefs.edit().putBoolean("backgroundAsked", true).apply() }

    fun saveActivitySounds(on: Boolean) { activitySounds = on; prefs.edit().putBoolean("activitySounds", on).apply() }
    fun saveActivityNotices(on: Boolean) { activityNotices = on; prefs.edit().putBoolean("activityNotices", on).apply() }
    fun saveActivityVolume(value: Float) {
        activityVolume = value.coerceIn(0f, 2f)
        prefs.edit().putFloat("activityVolume", activityVolume).apply()
    }

    private var mutedStatuses by mutableStateOf(prefs.getStringSet("mutedActivityStatuses", setOf(PaneAlertKind.Working.name)).orEmpty().toSet())
    private var hiddenStatuses by mutableStateOf(prefs.getStringSet("hiddenActivityStatuses", setOf(PaneAlertKind.Working.name)).orEmpty().toSet())
    internal fun plays(kind: PaneAlertKind) = kind.name !in mutedStatuses
    internal fun posts(kind: PaneAlertKind) = kind.name !in hiddenStatuses
    internal fun savePlays(kind: PaneAlertKind, on: Boolean) {
        mutedStatuses = if (on) mutedStatuses - kind.name else mutedStatuses + kind.name
        prefs.edit().putStringSet("mutedActivityStatuses", mutedStatuses).apply()
    }
    internal fun savePosts(kind: PaneAlertKind, on: Boolean) {
        hiddenStatuses = if (on) hiddenStatuses - kind.name else hiddenStatuses + kind.name
        prefs.edit().putStringSet("hiddenActivityStatuses", hiddenStatuses).apply()
    }

    private var activityPresets by mutableStateOf(PaneAlertKind.entries.associateWith { kind ->
        ActivitySound.entries.find { it.name == prefs.getString("activitySound.${kind.name}", null) } ?: kind.defaultSound
    })
    internal fun soundFor(kind: PaneAlertKind) = activityPresets.getValue(kind)
    internal fun saveSound(kind: PaneAlertKind, sound: ActivitySound) {
        activityPresets = activityPresets + (kind to sound)
        prefs.edit().putString("activitySound.${kind.name}", sound.name).apply()
    }

    var haptics by mutableStateOf(prefs.getBoolean("haptics", true))
        private set
    var keepKeyboard by mutableStateOf(prefs.getBoolean("keepKeyboard", false))
        private set
    /** Tap gestures on a terminal (Settings → Keyboard → Tap gestures): off until turned on. */
    var tapGestures by mutableStateOf(prefs.getBoolean("tapGestures", false))
        private set
    /** The key a double tap sends while [tapGestures] is on. */
    var doubleTapKey by mutableStateOf(TapKey.parse(prefs.getString("doubleTapKey", null), TapKey.Tab))
        private set
    /** The key a triple tap sends while [tapGestures] is on; [TapKey.None] lets a double tap go at once. */
    var tripleTapKey by mutableStateOf(TapKey.parse(prefs.getString("tripleTapKey", null), TapKey.Enter))
        private set
    /** Run the app's own Tailscale node and connect through it. */
    var tailnet by mutableStateOf(prefs.getBoolean("tailnet", false))
        private set
    /** The node's name on the tailnet. */
    var tailnetName by mutableStateOf(prefs.getString("tailnetName", DEFAULT_TAILNET_NAME)!!)
        private set
    /** The node has run with its login: at a start the connections wait for it ([HostConnection]). */
    var tailnetRan = prefs.getBoolean("tailnetRan", false)
        private set
    /**
     * The open sidebar sits beside the panes where it would cover them but there is room for both
     * (the unfolded screen upright, WsOpenDocked); false keeps it over them. A narrower window ignores it.
     */
    var sidebarDocked by mutableStateOf(prefs.getBoolean("sidebarDocked", false))
        private set

    fun saveKeepScreenOn(value: Boolean) {
        keepScreenOn = value
        prefs.edit().putBoolean("keepScreenOn", value).apply()
    }

    fun saveFontSize(v: Float) { fontSize = v; prefs.edit().putFloat("fontSize", v).apply() }
    fun saveFont(v: TerminalFont) { font = v; prefs.edit().putString("font", v.id).apply() }
    fun savePaneStyle(v: String) { paneStyle = v; prefs.edit().putString("paneStyle", v).apply() }
    fun saveUiSize(v: UiSize) { uiSize = v; prefs.edit().putString("uiSize", v.saved).apply() }
    fun saveOverviewLayout(v: OverviewLayout) { overviewLayout = v; prefs.edit().putString("overviewLayout", v.saved).apply() }
    fun savePaneHighlight(v: PaneHighlight) {
        paneHighlight = v
        val edit = prefs.edit().putString("paneHighlight", v.saved)
        v.custom?.let { edit.putString("paneHighlightCustom", hexOf(it)) }
        edit.apply()
    }
    fun saveSidebarDocked(v: Boolean) { sidebarDocked = v; prefs.edit().putBoolean("sidebarDocked", v).apply() }
    fun saveCapsuleCorner(v: CapsuleCorner) { capsuleCorner = v; prefs.edit().putString("capsuleCorner", v.saved).apply() }
    fun saveKeyBar(v: List<String>) { keyBar = v; prefs.edit().putString("keyBar", v.joinToString(",")).putBoolean("keyBarOrdered", true).apply() }
    fun saveHaptics(v: Boolean) { haptics = v; prefs.edit().putBoolean("haptics", v).apply() }
    fun saveKeepKeyboard(v: Boolean) { keepKeyboard = v; prefs.edit().putBoolean("keepKeyboard", v).apply() }
    fun saveTapGestures(v: Boolean) { tapGestures = v; prefs.edit().putBoolean("tapGestures", v).apply() }
    fun saveDoubleTapKey(v: TapKey) { doubleTapKey = v; prefs.edit().putString("doubleTapKey", v.saved).apply() }
    fun saveTripleTapKey(v: TapKey) { tripleTapKey = v; prefs.edit().putString("tripleTapKey", v.saved).apply() }
    fun saveTailnet(v: Boolean) { tailnet = v; prefs.edit().putBoolean("tailnet", v).apply() }
    fun saveTailnetName(v: String) { tailnetName = v; prefs.edit().putString("tailnetName", v).apply() }
    fun saveTailnetRan(v: Boolean) { if (v != tailnetRan) { tailnetRan = v; prefs.edit().putBoolean("tailnetRan", v).apply() } }

    companion object {
        val ALL_KEYS = listOf("/", "Esc", "Tab", "Enter", "Ctrl", "Alt", "Shift", "Left", "Down", "Up", "Right", "Home", "End", "PgUp", "PgDn", "~", "|", "-", "Space", "Backspace")
        const val DEFAULT_KEY_BAR = "/,Ctrl,Esc,Tab,Left,Down,Up,Right,Backspace"
        const val DEFAULT_TAILNET_NAME = "velo"
    }
}

/**
 * The app-level owner: the global [Settings], the shared [GlyphRasterizer], the [HostStore],
 * the app's Tailscale node (This Device), the SSH pool's secrets and questions, and one
 * [HostConnection] per remote saved on the phone. One remote is active and drawn by the
 * workspace. Main thread only.
 */
class AppController(context: Context) {
    private val main = Handler(Looper.getMainLooper())
    // The CA export comes first, on a thread of its own, so it runs beside the rest of start-up;
    // the cores connect once it ends (#253). See [TrustedCas.coreDirs]. What waits for it runs on
    // that thread: the main thread is busy with the first frame by then (HostConnection.dial).
    private val caDir = File(context.noBackupFilesDir, "user-cas")
    private val caLink = File(context.noBackupFilesDir, "system-cas")
    private val cas = TrustedCas.start(caDir, caLink, { it.run() }, last = TrustedCas.LastExport(File(context.noBackupFilesDir, "user-cas.source")) {
        TrustedCas.storeSources(context.applicationInfo.sourceDir)
    })
    // The terminal's glyph rasterizer, made as early, with its font files read ahead on a thread of their own: the
    // first connection, made below, asks for the line height at once, and reading the fonts here took a third of the
    // main thread's time before the first frame (#336). Its font is set once the settings are read.
    internal val rasterizer = GlyphRasterizer(context.assets).also { r ->
        prefetchFonts(r) { savedFontAssets(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)) }
    }
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val fonts = TerminalFonts(context.applicationContext)
    val settings = Settings(prefs, fonts::restore)
    val store = HostStore(prefs).also { it.migrate() }
    internal val paneAlerts = PaneAlertStore(prefs)
    internal val notifications = PaneNotifications(context.applicationContext)
    internal val background = BackgroundConnection(context.applicationContext)
    /** Activities on screen; when it falls to 0 the background service takes over ([updateBackground]). */
    internal var resumedActivities = 0
        set(value) {
            val left = field > 0 && value == 0
            field = value
            updateBackground()
            // A question asked on screen still waits: off screen the notification says so.
            if (left) for (q in sshQuestions.waiting()) postSshNotice(q.target)
        }

    /** Keep the process, and the connections, alive while the app is off screen ([BackgroundConnection]). */
    internal fun updateBackground() =
        background.update(settings.stayConnected, resumedActivities > 0, saved.size, remotes.all.sumOf { it.state.forwards.size })
    init {
        rasterizer.font = settings.font
    }
    private val env = CoreEnv(
        // System and user CAs come through AndroidCAStore ([TrustedCas]): it leaves out the system CAs
        // the user turned off, and the app may not read cacerts-added itself. The directory keeps the
        // name it had when it held the user CAs only.
        caDirs = TrustedCas.coreDirs(caDir, caLink),
        cas = cas,
        pxPerSp = pxPerSp(context.resources),
        density = context.resources.displayMetrics.density,
        rasterizer = rasterizer,
        settings = settings,
        store = store,
        fencePrefix = if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
            prefs.getString("debugFencePrefix", null) else null,
        logSelections = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
    )
    private val tailnetDir = File(context.filesDir, "tailscale")
    private val sshDir = File(context.filesDir, "ssh")
    /** The SSH values' own prefs file, which no backup or device transfer takes; an earlier build's values move there once. */
    private val sshPrefs = context.getSharedPreferences(SshSecrets.PREFS, Context.MODE_PRIVATE).also { moveSshPrefs(prefs, it) }

    /**
     * The phone key, imported keys, saved passwords and trusted host keys. A change of the keys
     * (import, remove, replace) goes to the pool at once, on the thread that made it, under
     * [sshSecrets]' lock ([sshKeys]).
     */
    val sshSecrets: SshSecrets = SshSecrets(sshPrefs, KeystoreSealer(), keysChanged = { if (sshInitDone) sshKeys.giveChanged() })

    /**
     * Gives the pool the keys ([SshKeyGiver]), on threads of their own (Keystore work): keys the
     * Keystore could not open are given again on its backoff, and a change of the keys connects
     * again the ssh remotes stopped on `key_refused` ([reconnectKeyRefused]). [destroy] closes it.
     */
    private val sshKeys: SshKeyGiver = SshKeyGiver(sshSecrets, { SshNative.setIdentities(it) },
        main = object : MainQueue {
            override fun post(r: Runnable) { main.post(r) }
            override fun postDelayed(r: Runnable, ms: Long) { main.postDelayed(r, ms) }
            override fun remove(r: Runnable) = main.removeCallbacks(r)
        },
        background = { Thread(it, "ssh-keys").start() },
        clock = SystemClock::elapsedRealtime,
        changed = { reconnectKeyRefused() },
        log = { Log.w(SSH_LOG, it) },
    )
    private val sshNotices = SshQuestionNotices(context.applicationContext)

    /** The SSH pool's questions: answered from [sshSecrets], or [SshQuestions.pending] for the user. */
    val sshQuestions = SshQuestions(NativeSshPool, sshSecrets,
        notices = object : SshQuestions.Notices {
            override fun ask(question: SshQuestion) { if (resumedActivities == 0) postSshNotice(question.target) }
            override fun clear(target: String) = sshNotices.cancel(target)
        },
        reconnect = { target -> sshConnection(target)?.reconnect() },
        log = { Log.i(SSH_LOG, it) },
    )

    /** [SshNative.init] succeeded: a change of the keys goes to the pool. Set on the setup thread. */
    @Volatile private var sshInitDone = false
    /**
     * The pool's setup ([startSsh]) is done, with the keys given to it that could be opened. Main
     * thread. Compose state: the SSH details panel reads again what the pool remembers once it
     * runs ([sshPoolReady]).
     */
    private var sshReady by mutableStateOf(false)
    /** Whether the SSH pool's setup is done: from then on [sshKept] asks the pool, and a server socket can be forgotten. */
    val sshPoolReady: Boolean get() = sshReady
    /** [startSsh]'s thread runs. Main thread. */
    private var sshStarting = false
    /** When each ssh remote entered `reconnecting` ([reconnectingSince]), on [SystemClock.elapsedRealtime]. Main thread. */
    private val sshReconnectingSince = HashMap<String, Long>()

    /**
     * The login the user asked for, and whether the browser has its page: one for the process, so the start screen and
     * the settings share it, and leaving a screen or the Activity being made again does not forget a login under way.
     */
    val tailnetLogin = TailnetLogin(startNode = { setTailnet(true) })

    init {
        Tailnet.watchNetwork(context.applicationContext)
        // Before the connections are made: one made while the node starts waits for it.
        if (settings.tailnet && Tailnet.state == TailnetState.Off) startTailnet()
    }

    // A node that never ran (no login yet) is not waited for: the system network is the only way.
    private val remotes = RemoteConnections(store) { url, initiallyParked, onConnectivityChanged ->
        val remote = store.remotes().first { it.url == url }
        var labels = emptyMap<PaneAddress, String>()
        var catalog: List<SessionInfo>? = null
        // An ssh login's first dial waits for the pool, whose setup runs on its own thread;
        // its first status may be the one it starts with, so the poll is not left to a change.
        if (isSshLocator(url)) {
            startSsh()
            main.post { pollSshQuestions() }
        }
        HostConnection(
            url, env,
            initiallyParked = initiallyParked,
            waitForNode = firstDialWaits(url, nodeStarting(), sshReady),
            onConnectivityChanged = onConnectivityChanged,
            onStatusChanged = ::sshStatusChanged,
            onReconnect = { conn ->
                if (isSshLocator(conn.url)) {
                    sshQuestions.retry(conn.url)
                    pollSshQuestions()
                }
            },
            onForwardsChanged = ::updateBackground,
            receivePaneReport = { conn, text ->
                val report = runCatching { Json.decodeFromString<PaneReport>(text) }.getOrNull()
                if (report != null) {
                    val alerts = paneAlerts.receive(remote.id, report, labels[report.target])
                    conn.activityReadState.update(report.target, paneAlerts.records(remote.id, report.target))
                    deliver(alerts.map { it.copy(location = labels[it.subscription.target] ?: it.subscription.label) }, conn)
                }
            },
            updateActivityTargets = update@{ conn, state ->
                if (catalog == state.sessions) return@update
                catalog = state.sessions
                val unknown = state.sessions.filter { it.tabs == null }.map { it.id }.toSet()
                labels = buildMap {
                    putAll(labels.filterKeys { it.session in unknown })
                    for (session in state.sessions) for (tab in session.tabs.orEmpty()) {
                        val label = listOf(remote.label, session.label, tab.displayName(session.id)).filter { it.isNotBlank() }.joinToString(" · ")
                        for (block in tab.terminalBlocks) put(PaneAddress(session.id, tab.id, block), label)
                    }
                }
                val targets = labels.keys + paneAlerts.forRemote(remote.id).map { it.target }
                paneAlerts.retainTargets(remote.id, targets)
                conn.activityReadState.retainTargets(targets)
                conn.watchPanes(targets)
            },
        ).also { it.watchPanes(paneAlerts.forRemote(remote.id).map { sub -> sub.target }.toSet()) }
    }

    private fun deliver(alerts: List<PaneAlert>, conn: HostConnection) {
        val visible = conn.visibleActivityPanes
        val foreground = resumedActivities > 0
        for (alert in alerts) {
            val sub = alert.subscription
            val enabled = paneAlerts.find(sub.remote, sub.target)?.enabled(alert.kind) ?: settings.posts(alert.kind)
            if (!foreground && settings.activityNotices && enabled) {
                // A notification that can be answered shows what the pane's screen ends with (#32).
                notifications.post(alert, screen = { lines -> conn.paneScreen(sub.target.session, sub.target.block, lines) }) {
                    paneAlerts.rememberRoute(alert.subscription, notifications.activeTokens())
                }
            }
        }
        if (!settings.activitySounds) return
        val sound = alerts.filter { (!foreground || it.subscription.target !in visible) && settings.plays(it.kind) &&
            (foreground || notifications.access(it.kind) == NotificationAccess.Allowed)
        }.minByOrNull { it.kind.priority } ?: return
        notifications.play(sound.kind, settings.soundFor(sound.kind), settings.activityVolume)
    }

    /** The remotes saved on the phone, in the order saved. */
    val saved: List<Remote> get() = remotes.saved
    /** The sidebar's remote rows: [saved], with the active remote the user removed ([removed]) where it was. */
    val rows: List<Remote> get() = remotes.rows
    /** The active remote after the user removed it; its row stays until they leave it. */
    val removed: Remote? get() = remotes.removed
    /** The connection the workspace draws; null when no remote is saved. */
    val active: HostConnection? get() = remotes.active
    val activeRemote: Remote? get() = remotes.activeRemote

    private val onTailnetState: (TailnetState, TailnetState) -> Unit = { was, now ->
        if (now is TailnetState.Running) settings.saveTailnetRan(true)
        if (now is TailnetState.NeedsLogin || now == TailnetState.LoggedOut) settings.saveTailnetRan(false)
        val redial = redialOn(was, now)
        if (redial != Redial.None) Log.i("velo.tailnet", "redial $redial")
        when (redial) {
            Redial.All -> reconnectAll()
            Redial.Waiting -> remotes.all.forEach { it.dialIfWaiting() }
            Redial.None -> {}
        }
    }

    init {
        Tailnet.addStateListener(onTailnetState)
    }

    /** Run the app's own Tailscale node (or stop it); the choice persists. Retries a node that failed. */
    fun setTailnet(on: Boolean) {
        settings.saveTailnet(on)
        if (on) {
            // A logged-out node starts again too: only a new start gives it a login page.
            if (Tailnet.state == TailnetState.Off || Tailnet.state == TailnetState.LoggedOut || Tailnet.state is TailnetState.Error) startTailnet()
        } else if (Tailnet.state != TailnetState.Off) {
            Tailnet.stop()
            reconnectAll()
        }
    }

    /** Rename the node; a running node restarts under the new name. */
    fun setTailnetName(name: String) {
        val clean = name.trim()
        if (clean.isEmpty() || clean == settings.tailnetName) return
        settings.saveTailnetName(clean)
        if (settings.tailnet) {
            startTailnet()
            reconnectAll()
        }
    }

    /** Log the node out of the tailnet; it restarts and asks for a new login (Log in returns). */
    fun logoutTailnet() {
        Tailnet.logout(tailnetDir, settings.tailnetName)
        reconnectAll()
    }

    private fun startTailnet() = Tailnet.start(tailnetDir, settings.tailnetName)

    /** The connection of the row [remote]: a saved remote's, or the removed active one's. */
    fun connectionOf(remote: Remote): HostConnection? = remotes.connectionOf(remote)

    /** The connection of the saved remote [id], for an answer from a notification ([PaneReplyService]); null when it is not saved. */
    internal fun savedConnection(id: String): HostConnection? = saved.firstOrNull { it.id == id }?.let(::connectionOf)

    /** Make the saved remote at [url] the one the workspace draws. */
    fun activate(url: String) = remotes.activate(url)

    /**
     * Save a remote on the phone and connect to it: a server found on the tailnet, a typed
     * URL or a scanned QR code. Returns the label of the saved remote that already has [url]
     * (nothing changes), else null. No server is told.
     */
    fun addRemote(label: String, url: String): String? = remotes.add(label, url)

    /** Rename the saved remote [id] on this phone; a blank [label] changes nothing. No server is told. */
    fun renameRemote(id: String, label: String) = remotes.rename(id, label)

    /**
     * Forget the remote [id]: it leaves the phone with its reconnect token and last session,
     * and its connection is destroyed. No server is told.
     */
    fun removeRemote(id: String) {
        val url = saved.firstOrNull { it.id == id }?.url
        remotes.remove(id)
        for (token in paneAlerts.postedTokens(id)) for (kind in PaneAlertKind.entries) notifications.cancel(token, kind)
        paneAlerts.removeRemote(id)
        if (url != null && isSshLocator(url)) {
            // After the connection: its core no longer dials. This frees a dial waiting on a
            // question. On a thread of its own: closing the connection and writing
            // host-endpoints.json (fsync) is disk and network work.
            if (sshInitDone) Thread({ SshNative.forget(url) }, "ssh-forget").start()
            sshQuestions.removed(url)
            sshReconnectingSince.remove(url)
        }
    }

    /**
     * What the phone remembers for the login of the ssh remote [url] ([io.github.yhzion.velo.ui.SshDetailsPanel]): the
     * host key trusted for its `host:port`, the pool's server socket and refusal, and whether a
     * password is saved. Null for a remote that is not an ssh login. Nothing is opened (no
     * Keystore work), but the pool is asked, or before it starts its file is read
     * ([rememberedFor]): call it off the main thread.
     */
    internal fun sshKept(url: String): SshKept? {
        val runs = sshInitDone
        return readSshKept(url, sshSecrets, poolRuns = runs) {
            rememberedFor(it, runs, pool = { login -> SshNative.remembered(login) }, file = { readEndpointsFile(sshDir) }, now = System.currentTimeMillis() / 1000)
        }
    }

    /**
     * Forget the host key trusted for the ssh remote [url]'s `host:port` (every login to it): the
     * next login shows the key and waits for the user's Trust, as for a host the phone never met.
     * The passwords saved for the logins to it go too (#29), so that login asks for the password.
     * A connection already logged in carries on. Prefs only: any thread.
     */
    fun forgetHostKey(url: String) = forgetTrustedHostKey(sshSecrets, url)

    /**
     * Forget the server socket the pool remembers for the ssh remote [url], and a refusal of its
     * forward: the next login probes again. The open connection stays. The pool writes
     * `host-endpoints.json` (fsync): call it off the main thread. 0, or the pool's error
     * ([SshNative.forgetSocket]); 0 when there is no pool yet, which forgets nothing (the SSH
     * details panel offers no Forget then: [SshKept.poolRuns]).
     */
    fun forgetServerSocket(url: String): Int {
        if (!isSshLocator(url) || !sshInitDone) return 0
        return SshNative.forgetSocket(url).also { if (it != 0) Log.w(SSH_LOG, "forget socket: $it") }
    }

    /** Forget the password saved for the ssh remote [url]: the next login that needs one asks for it. Main thread ([SshQuestions]). */
    fun forgetPassword(url: String) {
        if (isSshLocator(url)) sshQuestions.forgetPassword(url)
    }

    /** The notification of an SSH question was tapped: show the remote whose login waits ([SshQuestions.pending] holds the question). */
    internal fun openSshQuestion(target: String) {
        saved.firstOrNull { hostKey(it.url) == hostKey(target) }?.let { activate(it.url) }
    }

    /** The app's node is starting and has run before: a first dial waits for it ([HostConnection]). */
    private fun nodeStarting() = Tailnet.state == TailnetState.Starting && settings.tailnetRan

    /**
     * Make the SSH pool, which keeps its server sockets in `files/ssh`, and give it the keys (the
     * phone key made on first use), on a thread of its own: the native library and the Keystore
     * stay off the main thread. It runs once, when the first ssh connection is made; a process
     * without ssh remotes never loads it. Meanwhile the ssh connections wait ([firstDialWaits]);
     * then they dial ([dialsWhenSshReady]). One that dials first anyway reads `ssh_not_ready`,
     * which the core retries. After an init that failed, the native side remembers it, and a dial
     * reads `ssh_unavailable` instead, which stops the core until Reconnect. A setup that throws
     * counts as failed too ([setUpSshPool]), and the app stays up.
     */
    private fun startSsh() {
        if (sshReady || sshStarting) return
        sshStarting = true
        Thread({
            val ok = setUpSsh()
            main.post {
                sshStarting = false
                sshReady = ok
                // A failed setup does not hold the dials: the core says why it cannot connect.
                for (remote in saved) if (dialsWhenSshReady(remote.url, nodeStarting() && ok)) connectionOf(remote)?.dialIfWaiting()
                pollSshQuestions()
            }
        }, "ssh-setup").start()
    }

    /** [startSsh]'s thread: false when the pool could not be made. */
    private fun setUpSsh(): Boolean = setUpSshPool(
        init = {
            sshDir.mkdirs()
            SshNative.init(sshDir.path)
        },
        giveKeys = {
            sshInitDone = true
            sshKeys.give()
        },
        log = { Log.w(SSH_LOG, it) },
    )

    /**
     * The keys the pool offers changed ([SshKeyGiver]): each ssh remote stopped on `key_refused`
     * connects again, once ([reconnectsAfterKeysChange]); the key its host wants may be there now.
     * A remote whose Reconnect waits for the keys ([reconnect]) dials then instead. This is the
     * one way a stopped remote is redialled without a tap, and its failure is shown as the core's
     * own retries are, not as the answer to a tap ([HostConnection.reconnect], #292). Main thread.
     */
    private fun reconnectKeyRefused() {
        val conns = saved.mapNotNull(::connectionOf)
        val urls = reconnectsAfterKeysChange(conns.map { SshDialState(it.url, it.state.status, it.stoppedOn) }, sshKeys.waiting).toSet()
        for (conn in conns) if (conn.url in urls) conn.reconnect(byUser = false)
    }

    /**
     * The user's Reconnect of [conn] (the rail, the empty pane area, the palette). An ssh login
     * while a key the Keystore could not open is left out first gives the pool the keys again,
     * then dials ([SshKeyGiver.reconnect]): a key that opens now goes with this login. Main thread.
     */
    fun reconnect(conn: HostConnection) {
        if (isSshLocator(conn.url)) sshKeys.reconnect(conn.url) { conn.reconnect() } else conn.reconnect()
    }

    private fun sshConnection(target: String): HostConnection? =
        saved.firstOrNull { hostKey(it.url) == hostKey(target) }?.let(::connectionOf)

    private fun postSshNotice(target: String) {
        val label = saved.firstOrNull { hostKey(it.url) == hostKey(target) }?.label ?: return
        sshNotices.post(target, label)
    }

    private fun sshStatusChanged(conn: HostConnection) {
        if (!isSshLocator(conn.url)) return
        // A try the backoff planned while the phone slept (the handler's clock stops then).
        sshKeys.tryIfDue()
        val since = reconnectingSince(sshReconnectingSince[conn.url], conn.state.status, SystemClock.elapsedRealtime())
        if (since == null) sshReconnectingSince.remove(conn.url) else sshReconnectingSince[conn.url] = since
        if (conn.connected) sshQuestions.connected(conn.url)
        pollSshQuestions()
    }

    /** The delay the next read of the pool's questions was scheduled with; null when none is. */
    private var sshPollDelay: Long? = null
    private val sshPoll = object : Runnable {
        override fun run() {
            // One read after the last reason to poll ends: a question the pool dropped leaves the screen.
            sshQuestions.poll()
            val next = sshPollMs()
            sshPollDelay = next
            if (next != null) main.postDelayed(this, next)
        }
    }

    private fun sshPollMs(): Long? = if (!sshReady) null else questionPollMs(
        saved.mapNotNull { remote ->
            connectionOf(remote)?.let { SshDialState(remote.url, it.state.status, it.stoppedOn, it.waitingToDial, sshReconnectingSince[remote.url]) }
        },
        sshQuestions.asking,
        SystemClock.elapsedRealtime(),
    )

    /**
     * Read the pool's questions as often as [questionPollMs] says: often while an ssh remote
     * dials or a question waits, seldom while they only retry, not at all otherwise. A reason to
     * read sooner than the read already scheduled reads now.
     */
    private fun pollSshQuestions() {
        val wanted = sshPollMs() ?: return
        val scheduled = sshPollDelay
        if (scheduled != null && scheduled <= wanted) return
        main.removeCallbacks(sshPoll)
        sshPollDelay = wanted
        main.post(sshPoll)
    }

    internal fun inheritPaneAlerts(remote: Remote, target: PaneAddress, label: String, inherit: Boolean) {
        val conn = connectionOf(remote) ?: return
        if (remote !in saved || !target.isPresent(conn.state)) return
        if (inherit) {
            paneAlerts.find(remote.id, target)?.let { sub ->
                for (kind in PaneAlertKind.entries) if (!settings.posts(kind)) notifications.cancel(sub.token, kind)
            }
            paneAlerts.inherit(remote.id, target)
        } else paneAlerts.customize(remote.id, target, label, settings::posts)
    }

    internal fun setPaneAlert(remote: Remote, target: PaneAddress, label: String, kind: PaneAlertKind, on: Boolean) {
        val conn = connectionOf(remote) ?: return
        if (remote !in saved || !target.isPresent(conn.state)) return
        val old = paneAlerts.find(remote.id, target)
        paneAlerts.set(remote.id, target, label, kind, on)
        if (!on && old != null) notifications.cancel(old.token, kind)
    }

    /**
     * The panes [visible] of [remote] are on screen, so what their notifications said is seen:
     * they leave the shade, and the launcher's badge counts only what is still unseen. Only
     * notifications Android still shows are cancelled, so a pane on screen costs nothing.
     */
    internal fun clearSeenNotifications(remote: Remote, visible: Set<PaneAddress>) {
        val posted = notifications.activeTokens()
        if (posted.isEmpty()) return
        for (target in visible) for (token in paneAlerts.tokensFor(remote.id, target))
            if (token in posted) for (kind in PaneAlertKind.entries) notifications.cancel(token, kind)
    }

    // Terminal metrics are global. The interface appearance never goes to a core.
    fun setting(key: String, value: Any) = remotes.all.forEach { it.setting(key, value) }

    /** Switches the rasterizer to the chosen font, then has every core redraw with its line height. */
    fun pushFont() {
        val font = settings.font
        rasterizer.font = font
        // Its other styles and the emoji are read ahead too: the first bold or italic cell would read them on the
        // render thread, which holds the rasterizer meanwhile.
        prefetchFonts(rasterizer) { font.assets }
        remotes.all.forEach { it.pushLineHeight() }
    }

    /** The pane style changed the padding: every core fits its panes to it ([HostConnection.pushDisplay]). */
    fun pushDisplay() = remotes.all.forEach { it.pushDisplay() }

    /**
     * The activity's configuration changed. The manifest has the activity handle a `density`
     * change (Display size) itself, so nothing is made again: a new display size or font scale
     * reaches the terminals here. Each core draws its text and pane margins at the new scale.
     */
    fun displayChanged(resources: Resources) {
        val scale = pxPerSp(resources)
        val density = resources.displayMetrics.density
        if (scale == env.pxPerSp && density == env.density) return
        env.pxPerSp = scale
        env.density = density
        pushDisplay()
    }

    private fun pxPerSp(resources: Resources) = resources.displayMetrics.density * resources.configuration.fontScale

    fun destroy() {
        background.update(enabled = false, foreground = false, remotes = 0)
        Tailnet.removeStateListener(onTailnetState)
        main.removeCallbacks(sshPoll)
        // No key try of this controller runs later: the next one's pool gets its own keys.
        sshKeys.close()
        sshPollDelay = null
        remotes.destroy()
        notifications.release()
    }

    /** Another network: every remote dials again, except one stopped until the user's Reconnect ([HostConnection.redial]). */
    private fun reconnectAll() = remotes.all.forEach { it.redial() }

    private companion object {
        /** The app's preferences file: [Settings], [HostStore] and the saved remotes. */
        const val PREFS = "velo"
        const val SSH_LOG = "velo.ssh"
    }
}

/**
 * [AppController]'s pool setup, on its own thread: [init] makes the pool (0, else an errno),
 * then [giveKeys] gives it the keys. False when the pool could not be made: [init] failed or
 * threw anything (not only a library that cannot load), which is logged, never let out of the
 * thread (that would end the app). Keys that cannot be given leave the pool made: logins then
 * ask for a password. Logs only class names and return codes.
 */
internal fun setUpSshPool(init: () -> Int, giveKeys: () -> Unit, log: (String) -> Unit): Boolean {
    val rc = try {
        init()
    } catch (e: Exception) {
        log("init: ${e.javaClass.simpleName}")
        return false
    } catch (e: LinkageError) {
        log("init: ${e.javaClass.simpleName}")
        return false
    }
    if (rc != 0) {
        log("init: $rc")
        return false
    }
    try {
        giveKeys()
    } catch (e: Exception) {
        log("keys: ${e.javaClass.simpleName}")
    } catch (e: LinkageError) {
        log("keys: ${e.javaClass.simpleName}")
    }
    return true
}

/** How long the keys wait after a giving left a key out ([SshKeyGiver]), by the givings in a row that did. */
private val KEY_RETRY_MS = longArrayOf(30_000, 120_000, 300_000, 600_000)

/**
 * How long after the [tries]th giving in a row that left a key out ([SshSecrets.keysMissing])
 * the pool gets the keys again: 30 s, 2 min, 5 min, then every 10 min.
 */
internal fun keyRetryDelayMs(tries: Int): Long = KEY_RETRY_MS[(tries - 1).coerceIn(0, KEY_RETRY_MS.lastIndex)]

/**
 * The ssh remotes to connect again when the keys the pool offers changed: those stopped on
 * `key_refused`, whose host may take a key the pool has now, except those [waiting] for the keys
 * before the user's Reconnect dials them ([SshKeyGiver.reconnect]). [SshKeyGiver] asks once per
 * change, so a remote whose host refuses the keys again stays stopped until the next change or
 * the user's Reconnect.
 */
internal fun reconnectsAfterKeysChange(remotes: List<SshDialState>, waiting: Set<String> = emptySet()): List<String> =
    remotes.filter { isSshLocator(it.url) && it.stoppedOn == "key_refused" && it.url !in waiting }.map { it.url }

/** The main thread's queue as [SshKeyGiver] uses it ([Handler] in the app; the JVM tests run their own). */
internal interface MainQueue {
    fun post(r: Runnable)
    fun postDelayed(r: Runnable, ms: Long)
    fun remove(r: Runnable)
}

/**
 * Gives the SSH pool the keys to offer, in order ([SshSecrets.identityPems] to [setIdentities],
 * [SshNative.setIdentities] in the app): Keystore work, so never on the main thread. Under the
 * secrets' lock: a key imported meanwhile reaches the pool after this list, not before.
 *
 * Keys the Keystore could not open now ([SshSecrets.keysMissing]) are given again after
 * [keyRetryDelayMs], not on every dial: the try is planned on [main] and due on [clock]
 * (`elapsedRealtime`, which runs on in deep sleep, when the handler's clock stops), so a dial
 * after the due time tries at once ([tryIfDue]). The user's Reconnect gives the keys first
 * ([reconnect]). A change of the keys gives them too, without counting as a try of the backoff
 * ([giveChanged]). [changed] runs on [main] when the pool took keys other than those it had: an
 * import, a remove, a replace, or a key that opened at last (the first keys are a change from
 * none). A giving the pool refused (the keys stay as they were) is no change.
 *
 * [close] (the controller is destroyed) stops it for good: no try, change or dial of it runs
 * later, and no list it read reaches the pool, which is one per process and may already belong
 * to the next controller. Nothing of a key stays here but a digest of the list; [log] gets codes
 * and class names only.
 */
internal class SshKeyGiver(
    private val secrets: SshSecrets,
    private val setIdentities: (Array<String>) -> Int,
    private val main: MainQueue,
    private val background: (Runnable) -> Unit,
    private val clock: () -> Long,
    private val changed: () -> Unit,
    private val log: (String) -> Unit,
) {
    /** [close] was called: nothing of this giver runs any more. */
    @Volatile var closed = false
        private set
    /** The pool got keys from this giver once ([give] ran after the pool's init). */
    @Volatile private var started = false
    /** When the planned try is due, on [clock]; null when none is. */
    @Volatile private var dueAt: Long? = null
    /** The givings in a row that left a key out. Under the secrets' lock. */
    private var tries = 0
    /** The digest of the keys the pool has ([digestOf]). Under the secrets' lock. */
    private var given = digestOf(emptyList())
    /** The planned try, posted on [main]. */
    private val retry = Runnable { tryIfDue() }

    /** The remotes whose user's Reconnect waits for the keys ([reconnect]). Main thread. */
    val waiting: Set<String> get() = waitingFor
    private val waitingFor = HashSet<String>()

    /** The pool lacks a key the Keystore could not open when it last got the keys. */
    val keysMissing: Boolean get() = started && !closed && secrets.keysMissing

    /**
     * The keys changed (an import, a remove, a replace): the pool gets them now. That is no try of
     * the backoff (#292: three imports while a key was missing put the next try 10 min away). While
     * a key is still missing the planned try stays where it is; a change that finds one missing for
     * the first time plans the first try.
     */
    fun giveChanged() = give(aTry = false)

    /** The pool gets the keys, as a try of the backoff: the pool's setup, the planned try, the user's Reconnect. */
    fun give() = give(aTry = true)

    private fun give(aTry: Boolean) {
        if (closed) return
        synchronized(secrets) {
            if (closed) return
            started = true
            val taken = try {
                val pems = secrets.identityPems()
                // The pool is the process's: a giver closed meanwhile does not set it.
                val rc = synchronized(POOL) { if (closed) return else setIdentities(pems.toTypedArray()) }
                identitiesLog(rc)?.let(log)
                // A key left out (1000+N) leaves the others set; any other failure, the keys as they were.
                if (rc == 0 || rc > SSH_KEY_LEFT_OUT) digestOf(pems) else null
            } catch (e: Exception) {
                // Keystore or key trouble: logins go without the keys and ask for a password.
                log("keys: ${e.javaClass.simpleName}")
                null
            } catch (e: LinkageError) {
                log("keys: ${e.javaClass.simpleName}")
                null
            }
            // What the backoff plans now ([replan] false: the planned try stays as it is).
            val replan: Boolean
            val delay: Long?
            if (!secrets.keysMissing) {
                tries = 0
                delay = null
                replan = true
            } else if (aTry || tries == 0 || dueAt == null) {
                // A try counts; so does the first giving that finds a key missing. A change of the
                // keys while the planned try is already on its way (no due time) plans one again.
                if (aTry || tries == 0) tries++
                delay = keyRetryDelayMs(tries)
                replan = true
            } else {
                delay = null
                replan = false
            }
            if (replan) dueAt = delay?.let { clock() + it }
            val change = taken != null && !taken.contentEquals(given)
            if (taken != null) given = taken
            // Posted under the lock, so in the order of the givings.
            main.post {
                if (closed) return@post
                if (replan) {
                    main.remove(retry)
                    if (delay != null) main.postDelayed(retry, delay)
                }
                if (change) changed()
            }
        }
    }

    /**
     * The planned try, if it is due on [clock]: on [background]. Main thread: the planned try
     * itself, and every status change of an ssh remote (a dial after a deep sleep, when the
     * planned try did not run in time). A try already made moved the due time on.
     */
    fun tryIfDue() {
        val due = dueAt ?: return
        if (closed || clock() < due) return
        dueAt = null
        background(Runnable { give() })
    }

    /**
     * The user's Reconnect of the ssh remote [url] ([dial]): while a key is missing, the pool
     * gets the keys first, on [background], and [dial] runs on [main] after them (a dial first
     * would log in without the key). The key change does not dial it as well ([waiting]).
     * Otherwise [dial] runs at once. However the giving ends, the remote stops waiting and its
     * dial runs: a login without the keys asks for a password. Main thread.
     */
    fun reconnect(url: String, dial: () -> Unit) {
        // A tap while the keys are on their way: that giving and dial serve it (one try counted, one
        // dial). Asked first: another giving may have opened the missing key meanwhile, and the
        // tap would dial at once, with the dial that waits still to come (#292).
        if (url in waitingFor) return
        if (!keysMissing) return dial()
        waitingFor += url
        background(Runnable {
            try {
                give()
            } finally {
                // Also when the giving threw (only an Error leaves it): a remote left in the
                // waiting set would ignore every later Reconnect (#292).
                main.post {
                    waitingFor -= url
                    if (!closed) dial()
                }
            }
        })
    }

    /** Stop for good ([AppController.destroy]); main thread. */
    fun close() {
        closed = true
        dueAt = null
        main.remove(retry)
    }

    private companion object {
        /** One pool per process: two givers (one closing) never set it at once. */
        val POOL = Any()
    }
}

/** A SHA-256 of the keys [pems] in their order: it tells one list from another and keeps nothing of a key. */
private fun digestOf(pems: List<String>): ByteArray {
    val digest = MessageDigest.getInstance("SHA-256")
    for (pem in pems) {
        val bytes = pem.toByteArray(Charsets.UTF_8)
        digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
        digest.update(bytes)
        bytes.fill(0)
    }
    return digest.digest()
}
