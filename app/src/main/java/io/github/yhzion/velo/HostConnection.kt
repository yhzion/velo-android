package io.github.yhzion.velo

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.unit.IntSize
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.yhzion.velo.core.CoreListener
import io.github.yhzion.velo.core.VeloCore
import io.github.yhzion.velo.core.StateAccumulator
import io.github.yhzion.velo.core.UiState
import io.github.yhzion.velo.ui.ForwardAnswer
import io.github.yhzion.velo.ui.LoopbackLink
import io.github.yhzion.velo.ui.PaneCover
import io.github.yhzion.velo.ui.RemoteFileAnswer
import io.github.yhzion.velo.ui.TerminalLink
import io.github.yhzion.velo.ui.panePaddingDp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * What every core of the app shares: certificates, display scale and the glyph rasterizer. The
 * display scale is the current one: the activity handles a `density` change itself, so the app
 * sets it again ([AppController.displayChanged]) and pushes it to every core ([pushSettings]).
 */
class CoreEnv(
    val caDirs: Array<String>,
    /** Pixels per sp: the core converts setting{fontSize} (sp) to px with this factor. */
    var pxPerSp: Float,
    /** Pixels per dp, for the pane padding. */
    var density: Float,
    val rasterizer: GlyphRasterizer,
    val settings: Settings,
    val store: HostStore,
    /** Debug checks limit the core's session selection before it can open a data stream. */
    val fencePrefix: String? = null,
    /**
     * Debug builds log each pane focus and tab selection the app hands the core, so the
     * emulator checks can count what was asked while the remote was down.
     */
    val logSelections: Boolean = false,
    /**
     * Opens when [caDirs] hold what they should: the core reads them when it connects (#253).
     * What waits for it may run on the export's thread.
     */
    val cas: TrustedCas.Ready = TrustedCas.Ready.opened,
)

/**
 * One native core connected to one remote ([url]), mirroring its published state into Compose.
 * Keeps the remote's reconnect token and last session in [CoreEnv.store]. The core retries by
 * itself, so a remote that was off connects when it comes back. All methods are main-thread only.
 *
 * With [waitForNode] the first dial waits until the app's node, which is starting, runs: a
 * dial before that goes out on the system network, where a tailnet name may not resolve, and
 * the row would read "Connection failed" for the node's first seconds. An ssh login waits the
 * same way for the SSH pool's setup ([firstDialWaits]). The wait ends with [redial] (the node
 * runs), [dialIfWaiting] (it will not run, or the pool is ready) or after [NODE_WAIT_MS].
 *
 * A failure retrying cannot fix ([mayReconnectAutomatically]: the user declined an SSH question,
 * the host key changed, …) stops the core until the user's [reconnect]; [redial] leaves it stopped.
 * One more [reconnect] comes without a tap: a remote stopped on `key_refused` is reconnected once
 * when the key list the pool offers changes ([AppController.reconnectKeyRefused]).
 * [onStatusChanged] hears each new status or failure; [onReconnect] hears each [reconnect].
 */
class HostConnection(
    val url: String,
    private val env: CoreEnv,
    initiallyParked: Boolean,
    waitForNode: Boolean = false,
    private val receivePaneReport: (HostConnection, String) -> Unit = { _, _ -> },
    private val updateActivityTargets: (HostConnection, UiState) -> Unit = { _, _ -> },
    private val onConnectivityChanged: () -> Unit,
    private val onStatusChanged: (HostConnection) -> Unit = {},
    private val onReconnect: (HostConnection) -> Unit = {},
    /** The number of forwards changed: the background service may be needed now, or no more. */
    private val onForwardsChanged: () -> Unit = {},
) : CoreListener, RemoteLink {
    private val main = Handler(Looper.getMainLooper())
    private var waiting = waitForNode
    /**
     * A dial waits for the CA export ([CoreEnv.cas]); later dials until then join it. Declared
     * before `init`, which dials: a later initializer would clear it after the first dial.
     */
    @Volatile private var dialQueued = false
    /**
     * The one call into the core off the main thread: a dial that waited for the CA export
     * connects on the export's thread, so it need not wait for the main thread, which is busy
     * drawing the first frame then. This lock keeps that call apart from [destroyCore].
     */
    private val coreLock = Any()
    /** True while the first dial waits (for the node, or the SSH pool, to start): nothing dials yet. */
    val waitingToDial: Boolean get() = waiting

    var state by mutableStateOf(UiState(status = if (waitForNode) "connecting" else "idle"))
        private set
    override val connected: Boolean get() = state.status == "connected"
    private val errorNote = ErrorNote()
    /** The failure notice's text ([io.github.yhzion.velo.ui.ErrorNotice]); null hides it. */
    var error: String?
        get() = errorNote.text
        set(value) { errorNote.text = value }
    /** Changes when the notice starts again although its text stays ([ErrorNote.renew]). */
    val errorReports: Long get() = errorNote.reports
    /** Ends the notice shown as [text] at [reports], unless another came since ([ErrorNotice]'s timer). */
    fun expireError(text: String, reports: Long) = errorNote.expire(text, reports)

    /**
     * Why the remote is not connected: the last failure `onError` reported while it was down
     * ([keptFailure]), unless it only says that a command was refused ([connectionFailure]).
     * [error] shows it for a few seconds; this stays until the remote is connected again, so the
     * rail's popover and the empty pane area can still say it.
     */
    var failure by mutableStateOf<ConnectionFailure?>(null)
        private set
    /** [failure]'s text. */
    val lastFailure: String? get() = failure?.text
    /** The one-line command that fixes [failure], for "Copy command"; null when it has none. */
    val failureFix: String? get() = failure?.fix?.ifEmpty { null }

    /**
     * The failure the core stopped on ([mayReconnectAutomatically] false): only [reconnect] dials
     * again (the user's, or the one a change of the key list makes for a `key_refused` remote),
     * or a connection's success ends it ([stoppedAfter]). It lives as long as this
     * connection: a new process (a cold start, or reopening the app after backing out of it)
     * dials every remote again. Opening the app counts as the user's Reconnect, as the desktop
     * app asks again after a restart, so a changed host key is shown again (ruling P9).
     */
    var stoppedOn: String? = null
        private set

    /** True while its session is detached to save the panes (see [rowsToPark]). */
    var parked = initiallyParked
        private set

    @Volatile private var destroyed = false
    // Owned on main. destroy() invalidates immediately but never frees a worker's handle.
    internal var composerRunning = false
        private set
    // Owned on main, as composerRunning: a download thread holds the core until it ends.
    private var downloadRunning = false
    // Owned on main, as composerRunning: threads that open, close or sweep forwards hold the core.
    private var forwardWorkers = 0
    /** The tunnel blocks this client left behind were looked for on this connection's first link. */
    private var swept = false
    /** Detach disabled the core's automatic selection; wait for a session if the list is empty. */
    private var waitingForSessions = false
    /** A session selected to restore it; the last session is not saved until the core shows it. */
    private var restoring: String? = null
    private val scales = PaneScales(mutableStateMapOf()) { block, scale, live -> VeloCore.paneScale(core, block, scale, live) }

    /**
     * Which of this remote's panes the app's own sidebar or screen covers. The workspace says
     * when a cover begins and ends, and the pane views ask it as they go and come.
     */
    val cover = PaneCover()

    // start() may deliver synchronously. Install the callback-owned baseline first.
    private val nativeState = StateAccumulator(::postStateRequest)

    private val core: Long = VeloCore.start(this, env.caDirs, env.pxPerSp)

    init {
        env.fencePrefix?.takeIf { it.isNotEmpty() }?.let { op("fence", "prefix" to it) }
        pushSettings()
        // Bootstrap can start streams before main learns that the control link is up.
        if (parked) {
            op("detach")
        } else {
            env.store.lastSession(url)?.let { VeloCore.selectSession(core, it) }
        }
        if (waiting) main.postDelayed({ dialIfWaiting() }, NODE_WAIT_MS) else dial()
    }

    private fun dial() {
        if (destroyed) return
        waiting = false
        // The core reads its CA directories when it opens a connection, and they are still being
        // written for a moment after start (#253). Every connection of the core follows this one.
        if (dialQueued) return
        dialQueued = true
        val asked = SystemClock.elapsedRealtime()
        // Runs at once when the export has ended; otherwise on the export's thread ([coreLock]).
        env.cas.whenReady {
            dialQueued = false
            val waited = SystemClock.elapsedRealtime() - asked
            if (waited > 0) Log.i(CAS_LOG, "a dial waited $waited ms for the CA export")
            synchronized(coreLock) {
                if (!destroyed) VeloCore.connect(core, url, env.store.token(url))
            }
        }
    }


    /**
     * Reconnect: connect again at once, also after a failure the core stopped on. The user's
     * ([byUser]): the next failure the core reports answers the tap and renews the notice (#277).
     * The one the app makes itself, for a `key_refused` remote when the key list changes, is not
     * ([byUser] false, #292): its failure is shown as the core's own retries are.
     */
    fun reconnect(byUser: Boolean = true) {
        if (destroyed) return
        stoppedOn = stoppedAfter(stoppedOn, DialEvent.UserReconnect)
        if (byUser) errorNote.userDialed()
        onReconnect(this)
        dial()
    }

    /**
     * Drop the control connection and connect again at once (another network came up), unless
     * the core stopped on a failure retrying cannot fix: that waits for the user's [reconnect]
     * (or, for `key_refused`, for a change of the key list).
     */
    fun redial() {
        if (mayReconnectAutomatically(stoppedOn)) dial()
    }

    /** Dial now if the first dial still waits for the node. */
    fun dialIfWaiting() {
        if (waiting) dial()
    }

    fun selectSession(id: String) {
        if (destroyed) return
        if (parked) {
            // An active but offline row may still be waiting for a stream slot.
            env.store.saveLastSession(url, id)
            return
        }
        restoring = null
        waitingForSessions = false
        VeloCore.selectSession(core, id)
    }

    /** Detaches the session so its panes stop streaming; the control connection stays. */
    override fun park() {
        if (parked || destroyed) return
        parked = true
        waitingForSessions = false
        restoring = null
        op("detach")
    }

    /** Re-attaches a parked row's last session, else the first one, as the core would pick. */
    override fun unpark() {
        if (!parked || destroyed) return
        parked = false
        waitingForSessions = true
        resumeSessionIfAvailable()
    }

    private fun resumeSessionIfAvailable() {
        if (state.sessions.isEmpty()) return
        restoring = env.store.lastSession(url)
        op("resumeSession", "session" to restoring)
        waitingForSessions = false
    }

    private fun restoreSession(id: String) {
        restoring = id
        VeloCore.selectSession(core, id)
    }

    fun op(op: String, vararg args: Pair<String, Any?>) {
        if (destroyed) return
        val json = buildJsonObject {
            put("op", op)
            for ((k, v) in args) when (v) {
                null -> {}
                is String -> put(k, v)
                is Number -> put(k, JsonPrimitive(v))
                is Boolean -> put(k, v)
                is kotlinx.serialization.json.JsonElement -> put(k, v)
                else -> put(k, v.toString())
            }
        }
        if (env.logSelections && (op == "focus" || op == "selectWindow")) Log.d(SELECTION_LOG, "op=$op connected=$connected")
        VeloCore.command(core, json.toString())
    }

    fun setting(key: String, value: Any) = op("setting", "key" to key, "value" to value)

    /** Apply the global terminal metrics to this core. */
    fun pushSettings() {
        pushDisplay()
        setting("fontSize", env.settings.fontSize)
        pushLineHeight()
    }

    /**
     * The display's scale ([CoreEnv.pxPerSp]) and the margin around each terminal grid in px, so
     * text clears the pane's rounded corners. One setting: the core fits every pane once for both.
     */
    fun pushDisplay() = setting("display", buildJsonObject {
        put("pxPerSp", env.pxPerSp)
        put("panePaddingPx", panePaddingDp(env.settings.paneStyle) * env.density)
    })

    /** Has the core redraw with the rasterizer's current font. */
    fun pushLineHeight() = setting("fontLineHeight", env.rasterizer.lineHeight)

    /** Capture when opening the composer, not when a picker returns. Zero is not a target. */
    fun composerTarget(session: String, block: String, requirePaste: Boolean = false): Long =
        if (destroyed || parked) 0L else VeloCore.composerTarget(core, session, block, requirePaste)

    override fun invalidateComposer() {
        if (!destroyed) VeloCore.invalidateComposer(core)
    }

    /** Main-thread API. Keep data immutable until done, which runs once on main; null
     * means confirmed. A failure can be partial ([ComposerFailure.wroteNothing] is false then):
     * never automatically retry it. */
    fun sendComposer(session: String, block: String, text: String, name: String?, data: ByteArray?, done: (ComposerFailure?) -> Unit) =
        sendComposer(session, block, text, name, data, composerTarget(session, block), done)

    fun sendComposer(session: String, block: String, text: String, name: String?, data: ByteArray?, epoch: Long, done: (ComposerFailure?) -> Unit): Boolean {
        fun reject(failure: ComposerFailure): Boolean {
            done(failure)
            return false
        }
        // These checks run before the core is called: nothing of the draft has been written.
        if (epoch == 0L || destroyed || parked) return reject(ComposerFailure(TARGET_CHANGED, wroteNothing = true, targetGone = true))
        if (composerRunning) return reject(ComposerFailure("Another send is still in progress.", wroteNothing = true))
        if (data != null && data.size > 8 * 1024 * 1024) return reject(ComposerFailure("Choose a file of 8 MiB or less.", wroteNothing = true))
        if (text.length > 65523 || text.toByteArray().size > 65523) return reject(ComposerFailure("Shorten the draft to 65,523 UTF-8 bytes or less.", wroteNothing = true))
        composerRunning = true
        val worker = Runnable {
            val code = runCatching { VeloCore.sendComposer(core, epoch, session, block, text, name, data) }.getOrNull()
            main.post {
                composerRunning = false
                if (destroyed && idle()) destroyCore()
                val result = composerFailure(code)
                // The core went with the connection, so no later send reaches this pane. What the
                // send wrote before that is still what its own code says.
                done(if (destroyed) ComposerFailure(TARGET_CHANGED, wroteNothing = result?.wroteNothing == true, targetGone = true) else result)
            }
        }
        try {
            Thread(worker, "composer-send").start()
        } catch (_: OutOfMemoryError) {
            composerRunning = false
            return reject(ComposerFailure("Not enough memory to start the send.", wroteNothing = true))
        }
        return true
    }

    // Pane views keep the connection they were made with, so their teardown may arrive after
    // the row went away: a destroyed core takes nothing.
    fun key(block: String, keyCode: Int, meta: Int, unicode: Int, action: Int) {
        if (!destroyed) VeloCore.key(core, block, keyCode, meta, unicode, action)
    }
    private val shownPaneSizes = mutableStateMapOf<String, IntSize>()
    internal fun shownPaneSize(block: String) = shownPaneSizes[block]

    private fun measureShownPane(block: String, surface: Surface?, w: Int, h: Int) {
        if (surface != null && w > 0 && h > 0) shownPaneSizes[block] = IntSize(w, h)
        else shownPaneSizes.remove(block)
    }

    fun surface(block: String, s: Surface?, w: Int, h: Int) {
        if (destroyed) return
        measureShownPane(block, s, w, h)
        VeloCore.paneSurface(core, block, s, w, h)
    }
    /**
     * [surface] for a pane that is drawn but not shown (the tab overview): nothing is taken and
     * no size is asked for. With the surface the pane already draws to, it and [surface] switch
     * between the two in place.
     */
    fun preview(block: String, s: Surface?, w: Int, h: Int) {
        shownPaneSizes.remove(block)
        if (!destroyed) VeloCore.panePreview(core, block, s, w, h)
    }
    /**
     * [surface] for a pane that is back where it was shown before: the overview closed on the
     * tab it was opened from, or its surface is back after [suspend] (the app is on the screen
     * again, or what covered the workspace is gone). No terminal is taken, and a size is asked
     * for only when the pane's fit changed. A pane that was not shown before is shown as
     * [surface] shows it.
     */
    fun resume(block: String, s: Surface?, w: Int, h: Int) {
        if (destroyed) return
        measureShownPane(block, s, w, h)
        VeloCore.paneResume(core, block, s, w, h)
    }
    /**
     * The pane's surface went away and the pane did not: the app left the screen, or its own
     * sidebar or screen covers the workspace ([cover]). [resume] brings it back.
     */
    fun suspend(block: String) {
        shownPaneSizes.remove(block)
        if (destroyed) return
        VeloCore.paneSuspend(core, block)
        // A pinch still under way when the pane goes off screen is over: its end may never come.
        // Settled after the pane has let its surface go, so no glyph is drawn again for a surface
        // that is going away (the core ends the pinch there too).
        scales.settle(block)
    }
    /** A pane's text scale (pinch, palette): 1 until it is changed, and gone with its block. */
    fun scaleOf(block: String): Float = scales.of(block)
    fun terminalFontPx(block: String?): Float =
        (env.settings.fontSize * env.pxPerSp).coerceAtLeast(4f) * (block?.let(::scaleOf) ?: 1f)

    /**
     * [live]: a pinch is still changing the scale, and [settleScale] follows at its end. Until
     * then the pane stretches the glyphs it has instead of drawing them again at every size.
     */
    fun scale(block: String, f: Float, live: Boolean = false) {
        if (destroyed) return
        scales.set(block, f.coerceIn(MIN_SCALE, MAX_SCALE), live)
    }
    /** The pinch that changed the scale of [block] has ended: the pane draws its text at that size. */
    fun settleScale(block: String) {
        if (!destroyed) scales.settle(block)
    }
    /** The gestures were cut off with fingers down: no pinch ends by itself, so all of them end here. */
    fun settleScales() {
        if (!destroyed) scales.settleAll()
    }
    /**
     * A menu's or the palette's text size command for the pane: [change] of its scale, then the
     * terminal is fitted to the new cell size, as after a pinch.
     */
    fun textSize(block: String, change: (Float) -> Float) {
        scale(block, change(scaleOf(block)))
        fit(block)
    }
    fun pan(block: String, dxPx: Float, dyPx: Float, xPx: Float, yPx: Float) {
        if (!destroyed) VeloCore.panePan(core, block, dxPx, dyPx, xPx, yPx)
    }
    fun touch(block: String, action: Int, x: Float, y: Float) { if (!destroyed) VeloCore.paneTouch(core, block, action, x, y) }
    fun copy(block: String): String = if (destroyed) "" else VeloCore.paneCopy(core, block, false)
    fun find(block: String, needle: String, direction: Int = 0): Long =
        if (destroyed) -1 else VeloCore.paneFind(core, block, needle, direction)
    fun viewportText(block: String): String = if (destroyed) "" else VeloCore.paneCopy(core, block, true)
    /** Select the word at pane-local pixel (x, y), or clear on a blank cell. */
    fun selectWord(block: String, x: Float, y: Float) {
        if (!destroyed) VeloCore.paneSelectWord(core, block, x, y)
    }
    fun moveSelectionHandle(block: String, start: Boolean, x: Float, y: Float) {
        if (!destroyed) VeloCore.paneMoveSelectionHandle(core, block, start, x, y)
    }
    /** Visible handle anchors in pane-local pixels: start left/bottom, end right/bottom. */
    fun selectionBounds(block: String): IntArray? = if (destroyed) null else VeloCore.paneSelectionBounds(core, block)
    /** Atomic selection-only copy: an absent range never falls back to screen contents. */
    fun selectedText(block: String): String = if (destroyed) "" else VeloCore.paneSelectedText(core, block)
    /** True when the pane has a selection: [copy] then copies it, else the whole screen. */
    fun hasSelection(block: String): Boolean = !destroyed && VeloCore.paneHasSelection(core, block)
    /** Ends the pane's selection: [copy] copies the whole screen again. */
    fun clearSelection(block: String) { if (!destroyed) VeloCore.paneClearSelection(core, block) }
    /** The keyboard's composition, drawn at the pane's cursor until committed ("" clears it). Nothing is sent. */
    fun preedit(block: String, text: String) { if (!destroyed) VeloCore.panePreedit(core, block, text) }
    /** True when the pane's terminal has bracketed paste on: its program takes a paste as one text and runs nothing by itself. */
    fun bracketedPaste(block: String): Boolean = !destroyed && VeloCore.paneBracketedPaste(core, block)
    /** A tap at ([x], [y]) that the pane's program takes as a left click at that cell; false, with nothing sent, when it does not take the mouse. */
    fun click(block: String, x: Float, y: Float): Boolean = !destroyed && VeloCore.paneClick(core, block, x, y)
    /** The link under the surface point ([x], [y]) of the pane: a hyperlink a program set, or a URL or path in its text. */
    internal fun linkAt(block: String, x: Float, y: Float): TerminalLink? =
        if (destroyed) null else TerminalLink.parse(VeloCore.paneLink(core, block, x, y))
    /** Underlines the link under ([x], [y]) until [unmarkLink]; false when there is none. */
    fun markLink(block: String, x: Float, y: Float): Boolean = !destroyed && VeloCore.paneMarkLink(core, block, x, y)
    fun unmarkLink(block: String) { if (!destroyed) VeloCore.paneUnmarkLink(core, block) }

    /**
     * Starts the one download this remote runs at a time: its cancel generation, which the
     * download's calls pass on, or null when another runs or the remote is gone. The core stays
     * until [endDownload], so the download's thread may call it; a [cancelDownload] or [destroy]
     * from now on ends it, whichever step it is at.
     */
    internal fun startDownload(): Long? {
        if (destroyed || downloadRunning) return null
        downloadRunning = true
        return VeloCore.downloadGeneration(core)
    }

    /** The download's thread no longer calls the core. Main thread. */
    internal fun endDownload() {
        downloadRunning = false
        if (destroyed && idle()) destroyCore()
    }

    /** Blocking, on the download's thread: the terminal of [block] opens [path] for download. */
    internal fun downloadBegin(block: String, path: String, ifNoneMatch: String?, generation: Long): RemoteFileAnswer =
        RemoteFileAnswer.parse(VeloCore.paneDownloadBegin(core, block, path, ifNoneMatch, generation))

    /** Blocking, on the download's thread: the file of [ticket] into the open file [fd]; see [VeloCore.downloadFetch]. */
    internal fun downloadFetch(ticket: String, fd: Int, announced: Long?, generation: Long): Long =
        VeloCore.downloadFetch(core, ticket, fd, announced ?: -1L, generation)

    /** Blocking, on the download's thread: ends the stream of [ticket], whose file the app does not need. */
    internal fun downloadRelease(ticket: String, generation: Long): Boolean = VeloCore.downloadRelease(core, ticket, generation)

    /** No thread but main calls the core: it may be destroyed. */
    private fun idle() = !composerRunning && !downloadRunning && forwardWorkers == 0

    /**
     * Runs [work] on a thread of its own that may call the core, then [done] on main with its
     * result, or with [failed] when it threw: the count of workers must go down either way.
     */
    private fun <T> forwardWork(name: String, failed: T, work: () -> T, done: (T) -> Unit) {
        forwardWorkers++
        Thread({
            val result = try {
                work()
            } catch (e: Throwable) {
                Log.w(FORWARD_LOG, "$name failed", e)
                failed
            }
            main.post {
                forwardWorkers--
                if (destroyed && idle()) destroyCore()
                done(result)
            }
        }, name).start()
    }

    /**
     * Forwards the port of [link] to the phone for a tap on it in the terminal [block], and calls
     * [done] on main with where the phone reaches it. A forward of the same address is used again.
     */
    internal fun openForward(block: String, link: LoopbackLink, done: (ForwardAnswer) -> Unit) {
        if (destroyed) return done(ForwardAnswer(code = "NotConnected"))
        forwardWork("forward-open", ForwardAnswer(code = "internal"), { ForwardAnswer.parse(VeloCore.forwardOpen(core, block, link.remoteHost, link.port)) }) { answer ->
            Log.i(FORWARD_LOG, "forward of ${link.label}: ${answer.status} ${answer.port} ${answer.code}")
            done(answer)
        }
    }

    /** Ends the forward of the tunnel block [block]: the phone stops listening, and the block closes. */
    fun closeForward(block: String) {
        if (destroyed) return
        forwardWork("forward-close", Unit, { VeloCore.forwardClose(core, block) }) {}
    }

    /** Ends the running download, if any. Main thread. */
    fun cancelDownload() {
        if (!destroyed && downloadRunning) VeloCore.downloadCancel(core)
    }
    /**
     * Paste [text] into the pane. The core frames it for a terminal with bracketed paste. For one
     * without, it writes a text with a line break or a control character only when [confirmed],
     * and reports `paste_needs_confirm` otherwise: the terminal may have left bracketed paste
     * since [bracketedPaste] was asked.
     */
    fun paste(block: String, text: String, confirmed: Boolean) = op("paste", "block" to block, "text" to text, "confirmed" to confirmed)
    /** The pane's size or text size has settled: the core fits the terminal to it. */
    fun fit(block: String) = op("fit", "block" to block)
    /** A finger went down on the pane: this device is the one in use. */
    fun touched(block: String) = op("touch", "block" to block)

    private val paneWrites = mutableMapOf<String, (PaneWriteResult, String) -> Unit>()
    private var paneWriteSerial = 0L

    /**
     * Write [text] to the terminal [block] of [session] for an answer from a notification: the
     * core reads the pane's program status first and writes only while the server still
     * reports [record] blocked with [kind] and, when given, [updatedAt] (`Core.paneWrite`).
     * Any session works, selected or not, and a parked row too. Nothing waits for a
     * connection: [done] runs once on main with the result and the core's reason. Returns
     * the write's tag for [forgetPaneWrite].
     */
    internal fun paneWrite(session: String, block: String, text: String, record: String, kind: String?, updatedAt: String?,
                  done: (PaneWriteResult, String) -> Unit): String {
        val tag = "w${++paneWriteSerial}"
        if (destroyed) {
            main.post { done(PaneWriteResult.NotSent, "the connection was closed") }
            return tag
        }
        paneWrites[tag] = done
        op("paneWrite", "tag" to tag, "session" to session, "block" to block, "text" to text, "expect" to buildJsonObject {
            put("id", record)
            kind?.let { put("kind", it) }
            updatedAt?.let { put("updatedAt", it) }
        })
        return tag
    }

    /** The caller stopped waiting for the write [tag]; its result, should it come, is dropped. */
    internal fun forgetPaneWrite(tag: String) {
        paneWrites.remove(tag)
    }

    /**
     * Stops the core for good; nothing of the remote is drawn any more. The panes give their
     * surfaces back here, before the views go; the core itself is torn down on its own thread,
     * because that joins a control thread that may sit in a dial to a machine that is off.
     */
    override fun destroy() {
        if (destroyed) return
        for (block in state.blocks.keys) VeloCore.paneSurface(core, block, null, 0, 0)
        invalidateComposer()
        destroyed = true
        val forwarded = state.forwards.isNotEmpty()
        state = UiState()
        // The core ends the forwards as it goes.
        if (forwarded) onForwardsChanged()
        error = null
        failure = null
        // The core reports nothing more: a write it was checking or sending may have gone out.
        val writes = paneWrites.values.toList()
        paneWrites.clear()
        for (done in writes) main.post { done(PaneWriteResult.Unconfirmed, "the connection was closed") }
        // A download that waits for an answer or streams a file ends now ([endDownload]).
        if (downloadRunning) VeloCore.downloadCancel(core)
        if (idle()) destroyCore()
    }

    private fun destroyCore() = Thread({ synchronized(coreLock) { VeloCore.destroy(core) } }, "core-destroy").start()

    /**
     * How many times the focused pane, tab or session changed: each state the core publishes is
     * counted, so a focus that left a pane and came back counts twice ([io.github.yhzion.velo.ui.TapGestureInput]).
     */
    internal var focusMoves = 0L
        private set

    private fun apply(update: UiState) {
        // Waiting for the node is connecting, whatever the core says before its first dial.
        val next = if (waiting) update.copy(status = "connecting") else update
        scales.retain(next.blocks.keys)
        cover.keep(next.blocks.keys)
        val wasConnected = connected
        val wasParked = parked
        val wasStatus = state.status
        val previousForwards = state.forwards.size
        if (next.focused != state.focused || next.window != state.window || next.session != state.session) focusMoves++
        state = next
        if (connected) updateActivityTargets(this, next)
        failure = keptFailure(failure, next.status)
        if (connected) stoppedOn = stoppedAfter(stoppedOn, DialEvent.Connected)
        if (next.forwards.size != previousForwards) onForwardsChanged()
        if (connected != wasConnected) {
            if (connected) {
                restoring = null
                // A Reconnect that went through: a later failure is no answer to it.
                errorNote.connected()
                // Once per connection: the forwards of an app that was stopped left their blocks.
                if (!swept) {
                    swept = true
                    forwardWork("forward-sweep", false, { VeloCore.forwardSweep(core) }) { looked -> if (!looked) swept = false }
                }
            }
            // Publish connectivity before deciding whether this bootstrap should attach anything.
            onConnectivityChanged()
        }
        if (next.status != wasStatus) onStatusChanged(this)
        if (parked) return
        if (connected && waitingForSessions) {
            resumeSessionIfAvailable()
        } else if (connected && !wasConnected && !wasParked) {
            sessionToRestore(env.store.lastSession(url), next.sessions.map { it.id }, next.session)?.let(::restoreSession)
        }
        val session = next.session
        if (restoring != null && (session == restoring || (connected && next.sessions.none { it.id == restoring }))) {
            restoring = null
        }
        if (session != null && restoring == null && session != env.store.lastSession(url)) {
            env.store.saveLastSession(url, session)
        }
    }

    private fun postStateRequest() {
        // Never re-enter JNI under the native host delivery lock.
        main.post { if (!destroyed) VeloCore.requestState(core) }
    }

    override fun onState(gen: Long, full: Boolean, json: String) {
        val decoded = nativeState.accept(gen, full, json) ?: return
        main.post { if (!destroyed) apply(decoded) }
    }

    internal val activityReadState = io.github.yhzion.velo.ui.ActivityReadState(env.store.activityReadReceipts(url)) {
        env.store.saveActivityReadReceipts(url, it)
    }
    internal var visibleActivityPanes: Set<PaneAddress> = emptySet()
    private var watchedPanes = emptySet<PaneAddress>()

    internal fun watchPanes(targets: Set<PaneAddress>) {
        if (destroyed || targets == watchedPanes) return
        activityReadState.retainTargets(targets.toSet())
        watchedPanes = targets
        VeloCore.command(core, buildJsonObject {
            put("op", "paneWatches")
            put("panes", Json.encodeToJsonElement(targets))
        }.toString())
    }

    override fun onPaneReport(json: String) {
        main.post { if (!destroyed) receivePaneReport(this, json) }
    }

    override fun onPaneWrite(tag: String, result: String, message: String) {
        main.post { paneWrites.remove(tag)?.invoke(PaneWriteResult.fromWire(result), message) }
    }

    override fun onReconnectToken(token: String) {
        main.post { if (!destroyed) env.store.saveToken(url, token) }
    }

    override fun onError(code: String, message: String, fix: String) {
        main.post {
            if (destroyed) return@post
            val text = errorText(code, message)
            errorNote.report(text)
            // The core reports a failed connect before the state that says so (`Core.release`
            // hands over the errors first), so the status here is still the attempt's own.
            failure = keptFailure(failure, state.status, connectionFailure(code, message, fix))
            stoppedOn = stoppedAfter(stoppedOn, DialEvent.Failed(code))
            onStatusChanged(this)
        }
    }

    override fun rasterizeGlyph(utf8: ByteArray, bold: Boolean, italic: Boolean, cellW: Int, cellH: Int, columns: Int): ByteArray? =
        env.rasterizer.rasterize(utf8, bold, italic, cellW, cellH, columns)

    companion object {
        private const val TARGET_CHANGED = "The original pane or connection changed. Check it before sending again."
        /** The logcat tag of [CoreEnv.logSelections]. */
        const val SELECTION_LOG = "velo.selection"
        /** The logcat tag of port forwards. */
        const val FORWARD_LOG = "velo.forward"
        /** A dial that waited for the CA export (#253): how long. */
        const val CAS_LOG = "velo.cas"

        /**
         * The failure that a code of [VeloCore.sendComposer] stands for; null for "OK". Only the
         * codes that `Core.sendComposer` and `protocol.composerInput` return before the first
         * request goes to the writer count as nothing written. Every other code, and no code at
         * all, can come after a write: TargetChanged, for one, also ends the wait for a write's
         * confirmation, and Canceled stops an upload that follows the text. TargetChangedBeforeWrite
         * is the same change found before anything was written.
         */
        internal fun composerFailure(code: String?): ComposerFailure? = when (code) {
            "OK" -> null
            // The core's target generation only grows, so this target cannot be sent to again.
            "TargetChangedBeforeWrite" -> ComposerFailure(TARGET_CHANGED, wroteNothing = true, targetGone = true)
            "TargetChanged", "Canceled" -> ComposerFailure(TARGET_CHANGED, wroteNothing = false, targetGone = true)
            "InputBusy" -> ComposerFailure("The terminal is still receiving other input.", wroteNothing = true)
            // Also when the terminal left bracketed paste before the text's write: the core then drops that write unsent.
            "PasteModeRequired" -> ComposerFailure("The terminal must have bracketed paste enabled for line breaks or tabs.", wroteNothing = true)
            "ControlCharacter" -> ComposerFailure("Remove control characters before sending.", wroteNothing = true)
            "TextTooLarge" -> ComposerFailure("Shorten the draft to 65,523 UTF-8 bytes or less.", wroteNothing = true)
            "FileTooLarge" -> ComposerFailure("Choose a file of 8 MiB or less.", wroteNothing = true)
            "BadFileName" -> ComposerFailure("Choose a file with a valid file name.", wroteNothing = true)
            "EmptyDraft" -> ComposerFailure("Add text or select a file before sending.", wroteNothing = true)
            "ServerRefused" -> ComposerFailure("The remote refused the request. Check the original pane before sending again.", wroteNothing = false)
            "Timeout" -> ComposerFailure("Confirmation timed out. Check the original pane before sending again.", wroteNothing = false)
            else -> ComposerFailure("Send not confirmed. Check the original pane before sending again.", wroteNothing = false)
        }
        /** The smallest and largest text scale of a pane. */
        const val MIN_SCALE = 0.5f
        const val MAX_SCALE = 3f
        /** One step of Text Size Larger and Smaller. */
        const val TEXT_SIZE_STEP = 1.15f
        /** The longest a first dial waits for the node; a node that starts takes 2 to 5 s to run. */
        const val NODE_WAIT_MS = 10_000L
    }
}

/**
 * Why a composer send was not confirmed. [wroteNothing]: the send stopped before its first
 * write, so none of the draft reached the pane and the same draft can be sent again. Otherwise
 * part of it may be there, and a second send could type that part twice. [targetGone]: the pane
 * the draft was opened for can no longer be sent to.
 */
class ComposerFailure(val message: String, val wroteNothing: Boolean, val targetGone: Boolean = false)

/**
 * A connection failure as `onError` reported it: [code], the [text] the app shows
 * ([errorText]), and [fix], a one-line command that fixes it ("" when none; the SSH pool's).
 */
data class ConnectionFailure(val code: String, val text: String, val fix: String = "")

/**
 * The connection failure kept once the status is [status] ([HostConnection.failure]): the one
 * [reported] by `onError` while the remote is not connected, else the one [kept] before. A
 * connected status ends it. An error reported while connected is a command's, not the
 * connection's: the notice alone shows it.
 */
internal fun <T> keptFailure(kept: T?, status: String, reported: T? = null): T? =
    if (status == "connected") null else reported ?: kept

/**
 * What an `onError` report of [code], [message] and [fix] gives [keptFailure] as the reason the
 * remote is down, or null for `not_connected`. That code says that the core refused a command
 * while the connection was down, not why it is down, so the reason kept before stays.
 */
internal fun connectionFailure(code: String, message: String, fix: String): ConnectionFailure? =
    if (code == "not_connected") null else ConnectionFailure(code, errorText(code, message), fix)

/**
 * A connection's failure notice ([io.github.yhzion.velo.ui.ErrorNotice]), shown for a while after each
 * new text. The core's own retries report the same text again and keep the notice as it is. The
 * user's Reconnect [renew]s it: when it fails as the last retry did, its failure is shown anew
 * instead of going at the old deadline (#277).
 */
internal class ErrorNote {
    var text by mutableStateOf<String?>(null)
    /**
     * Changes when the notice starts again: a renewal. The notice's timer and the notice itself
     * ([io.github.yhzion.velo.ui.ErrorNotice], #280) are keyed by it.
     */
    var reports by mutableLongStateOf(0L)
        private set

    fun renew(value: String) {
        text = value
        reports++
    }

    /** The next failure reported answers the user's Reconnect ([report]). */
    private var answersUser = false

    /** The user tapped Reconnect: the failure that answers it renews the notice. */
    fun userDialed() {
        answersUser = true
    }

    /**
     * A failure the core reported: the notice says it. Only the one that answers the user's
     * Reconnect [renew]s it; a retry of the core's own, or a reconnect the app made itself (a
     * change of the key list, #292), leaves a notice that says the same as it is.
     */
    fun report(value: String) {
        if (answersUser) renew(value) else text = value
        answersUser = false
    }

    /** The remote connected: the notice goes, and a later failure is no answer to a Reconnect that went through. */
    fun connected() {
        answersUser = false
        text = null
    }

    /** The notice shown as [seenText] at [seenReports] is over: clear it unless something came since. */
    fun expire(seenText: String, seenReports: Long) {
        if (text == seenText && reports == seenReports) text = null
    }
}

/**
 * The SSH pool's failure codes (`native/tailscale/rexssh/errors.go`) and the core's own for ssh
 * (`no_user`, `ssh_unavailable`, `ssh_not_ready`): their message is a whole sentence for the user.
 * A `login_refused` sentence (also reported as `closed_before_login` while the core tries again)
 * can have a second line, the host's own words under "HOST says:", cleaned to one line by the pool.
 */
private val sshSentenceCodes = setOf(
    "malformed", "no_user", "unreachable", "not_ssh", "closed_before_login", "password_refused",
    "key_refused", "host_key_changed", "declined", "not_installed", "ssh_unavailable", "ssh_not_ready",
    "no_common_algorithm", "login_refused",
)

/** What the app shows for an `onError` report: an SSH sentence as it is, else `code: message`. */
internal fun errorText(code: String, message: String): String = if (code in sshSentenceCodes) message else "$code: $message"

/**
 * The failures retrying cannot fix (`permanent_failures` in `native/src/rex/Core.zig`): the core
 * stops on them until the user reconnects (a `key_refused` remote also when the key list changes,
 * once), since trying again would ask the user again, pile up refused logins or probe for nothing.
 */
private val stoppingFailures = setOf(
    "declined", "host_key_changed", "password_refused", "key_refused", "not_installed", "malformed", "no_user", "ssh_unavailable",
    "no_common_algorithm", "login_refused",
)

/**
 * Whether a remote stopped on [code] ([HostConnection.stoppedOn]) may be connected again
 * without the user (a tailnet change, [AppController]'s redial): not when the core stopped on
 * a failure retrying cannot fix. A new process dials it again all the same: opening the app
 * counts as the user's Reconnect (ruling P9).
 */
fun mayReconnectAutomatically(code: String?): Boolean = code !in stoppingFailures

/** What happens to a remote's connection, as far as its stop is concerned ([stoppedAfter]). */
sealed interface DialEvent {
    /** `onError` reported [code]: a connection failure, or a command refused while down. */
    data class Failed(val code: String) : DialEvent
    /** The remote connected. */
    data object Connected : DialEvent
    /** The user tapped Reconnect. */
    data object UserReconnect : DialEvent
}

/**
 * The failure a remote is stopped on ([HostConnection.stoppedOn]) after [event]: a failure
 * retrying cannot fix stops it; any other report (a transient failure, a command refused while
 * down) leaves the stop as it was; a connection or the user's Reconnect ends it.
 */
internal fun stoppedAfter(stoppedOn: String?, event: DialEvent): String? = when (event) {
    is DialEvent.Failed -> if (mayReconnectAutomatically(event.code)) stoppedOn else event.code
    DialEvent.Connected, DialEvent.UserReconnect -> null
}
