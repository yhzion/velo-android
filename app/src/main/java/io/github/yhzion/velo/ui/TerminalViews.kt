package io.github.yhzion.velo.ui

import android.os.SystemClock
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.text.InputType
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeProvider
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.findViewTreeLifecycleOwner
import io.github.yhzion.velo.HostConnection

/**
 * Render surface for one pane. One finger pans or taps; a long press opens Android's text
 * tools in place. Let go at a flick, the pan keeps the finger's speed and coasts (#384): a
 * press, the gesture not this pane's, the pane becoming a preview or the view leaving stops it.
 * Its selection and adjustable grips belong to this view, never the pane menu.
 * Bound for its whole life to the core it was made for ([conn]): its blocks belong to that
 * core, so its surface teardown goes there even after another host is shown.
 * A [floating] pane's surface is stacked above the docked panes' surfaces. Surfaces of one window
 * otherwise stack in the order they were made, and a split or a zoom makes docked views after
 * the floating one, which would cover it. The order is set before the view is attached, so it
 * is a constructor choice and a pane that changes between docked and floating gets a new view.
 * The core decides the terminal's size and whose it is; this view only reports: a surface,
 * shown or a preview ([preview]), a size that settled ([HostConnection.fit]) and a finger put
 * down ([HostConnection.touched]).
 * The view goes when the app's own sidebar or screen covers the workspace, and the pane does
 * not: the view says so as it goes, and the pane's next view shows it as it was ([PaneCover]).
 * Gestures of more fingers (the pinch, the tab overview) belong to the pane area: it takes
 * them before this view, which then gets ACTION_CANCEL ([TabStage]).
 */
@SuppressLint("ViewConstructor")
class PaneSurfaceView(
    context: Context,
    private val conn: HostConnection,
    val floating: Boolean,
) : SurfaceView(context), SurfaceHolder.Callback {
    var block: String = ""
        set(value) {
            if (field == value) return
            // A pending fit never outlives the block it was scheduled for.
            removeCallbacks(reportFit)
            dropFinger()
            if (field.isNotEmpty() && attached) gone(field)
            field = value
            attach()
        }
    /**
     * The pane is drawn but not shown (a tab of the overview): nothing is taken or sized for
     * it, and it gets no gesture. The surface stays the same when this changes.
     */
    var preview: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (value) {
                removeCallbacks(reportFit)
                dropFinger()
            }
            attach(back = !value && returns)
        }
    /**
     * When this pane stops being a preview it is back where it was shown before (the overview
     * closed on the tab it was opened from): that takes no terminal. Set before [preview].
     */
    var returns: Boolean = false
    /** The Haptic feedback setting, as the workspace's own controls follow it: off, a long press gives none. */
    var haptics: Boolean = true
    var onTap: () -> Unit = {}
    /** Where a run of tap gestures is counted ([TapGestureInput]); null for a view that makes none. */
    var taps: TapGestureInput<Pair<HostConnection, String>>? = null
    var inputTarget: Boolean = false
        set(value) {
            // Other panes share the tap counter: losing focus must not cancel the new pane's run.
            if (field && !value && selection.active) dropFinger()
            field = value
        }
    var onRead: () -> Unit = {}

    override fun onInitializeAccessibilityNodeInfo(info: android.view.accessibility.AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        if (preview || block.isEmpty()) return
        info.isClickable = true
        info.isLongClickable = true
        info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(
            android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK, "Read terminal text"))
        info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(
            io.github.yhzion.velo.R.id.focus_terminal_pane, "Make input target"))
        info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(
            android.view.accessibility.AccessibilityNodeInfo.ACTION_LONG_CLICK, "Show text tools"))
    }

    override fun performAccessibilityAction(action: Int, arguments: android.os.Bundle?): Boolean {
        if (preview || block.isEmpty()) return false
        when (action) {
            android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK -> onRead()
            io.github.yhzion.velo.R.id.focus_terminal_pane -> onTap()
            android.view.accessibility.AccessibilityNodeInfo.ACTION_LONG_CLICK -> {
                if (!attached) return false
                dropFinger()
                return selection.open(width / 2f, height / 2f)
            }
            else -> return super.performAccessibilityAction(action, arguments)
        }
        return true
    }

    var onCopySelection: () -> Unit = {}
    /** Opens a link that a tap or the text tools' Open link chose ([linkTap]); the workspace sets it. */
    /** A link tapped or chosen from the menu; the second argument runs when the link names no file. */
    internal var onLink: (LinkTap, () -> Unit) -> Unit = { _, _ -> }
    var onPasteSelection: () -> Unit = {}
    var onTextFocus: () -> Unit = {}
    /** A finger went down on this pane. */
    var onFinger: () -> Unit = {}
    private var attached = false
    /** The surface went away with the app (screen off, another app in front), not with the pane. */
    private var away = false
    private val selection = PaneSelection(this, conn)
    private enum class FingerMode { Normal, Word, Grip, Ignored }
    private var fingerMode = FingerMode.Ignored
    private var surfaceW = 0
    private var surfaceH = 0
    private val reportFit = Runnable { if (attached && !preview && block.isNotEmpty()) conn.fit(block) }
    private var fingerX = 0f
    private var fingerY = 0f
    private val finger = FirstFinger()
    private val fingerAlone = Runnable { useFinger() }
    /** What a tap does with the link the finger now down went down on; null off a link. */
    private var pressedLink: LinkTap? = null
    /** The pane underlines a link ([HostConnection.markLink]) until [unmarkLink] runs. */
    private var linkMarked = false
    /** Keeps the second tap of a double tap on a link from opening it again. */
    private val linkTaps = LinkTapGate(ViewConfiguration.getDoubleTapTimeout().toLong())
    private val unmarkLink = Runnable {
        if (linkMarked && block.isNotEmpty()) conn.unmarkLink(block)
        linkMarked = false
    }

    init {
        holder.addCallback(this)
        // Draw only the grips in the window, above the terminal's separate surface.
        setWillNotDraw(false)
        setZOrderOnTop(false)
        // After setZOrderOnTop: that call sets the same layer and would undo this one.
        setZOrderMediaOverlay(floating)
    }

    /**
     * The one place that tells the core what this pane's surface is: a preview, shown, or
     * ([back]) shown again as it was before its preview, before the app left the screen or
     * before the app covered the workspace.
     */
    private fun attach(back: Boolean = false) {
        if (!attached || block.isEmpty()) return
        // The surface's own size: a tab that is scaled for the overview keeps full-size surfaces.
        when {
            preview -> conn.preview(block, holder.surface, surfaceW, surfaceH)
            back -> conn.resume(block, holder.surface, surfaceW, surfaceH)
            else -> conn.surface(block, holder.surface, surfaceW, surfaceH)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        selection.draw(canvas)
    }

    override fun onDetachedFromWindow() {
        dropFinger()
        super.onDetachedFromWindow()
    }

    override fun surfaceCreated(h: SurfaceHolder) {}
    override fun surfaceChanged(h: SurfaceHolder, format: Int, w: Int, hgt: Int) {
        // The core sizes a terminal when its pane is first shown. A later size (keyboard,
        // rotation, fold, split) only moves the surface, until it has stayed for a moment.
        // A preview asks for nothing: shown again, the core fits it by itself.
        val resized = attached && (w != surfaceW || hgt != surfaceH)
        if (resized) dropFinger()
        // A surface that comes, not one that changed its size.
        val arrived = !attached
        attached = true
        surfaceW = w
        surfaceH = hgt
        // The view is new when the pane is back from under a cover: the cover knows of the pane.
        val covered = arrived && block.isNotEmpty() && conn.cover.returns(block)
        // So is a view made for a tab the overview comes back to (the sidebar covered the panes
        // while the overview closed): it was never a preview, and [returns] says so instead. The
        // core takes nothing only for a pane that was shown before and has not been hidden since.
        val back = away || covered || (arrived && !preview && returns)
        away = false
        attach(back)
        if (resized && !preview && block.isNotEmpty()) {
            removeCallbacks(reportFit)
            postDelayed(reportFit, FIT_DELAY_MS)
        }
    }
    override fun surfaceDestroyed(h: SurfaceHolder) {
        attached = false
        removeCallbacks(reportFit)
        dropFinger()
        if (block.isEmpty()) return
        // Android takes every surface when the app leaves the screen. That is no change to
        // the pane: back on the screen it is shown as it was, and takes no terminal.
        away = !appOnScreen()
        if (away) conn.suspend(block) else gone(block)
    }

    /**
     * The surface of the pane [of] went away while the app is on the screen: this view goes.
     * Where the app's own sidebar or screen covers the workspace that closes no pane. One that
     * was shown is suspended, as when the app leaves the screen, and its next view shows it
     * again without a take ([PaneCover]). So is a preview under the cover: the pane it drew
     * was shown before the overview opened, and the overview closed on its tab shows it as it
     * was ([returns]).
     */
    private fun gone(of: String) {
        stopFling()
        val covered = if (preview) conn.cover.covering else conn.cover.leaves(of)
        if (covered) conn.suspend(of) else conn.surface(of, null, 0, 0)
    }

    /** False from the moment the activity stops or its window is hidden, so before the surface goes. */
    private fun appOnScreen(): Boolean = windowVisibility == View.VISIBLE &&
        findViewTreeLifecycleOwner()?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) != false

    /** The finger is this pane's (it stayed or lifted alone): the terminal follows this device. */
    private fun useFinger() {
        removeCallbacks(fingerAlone)
        val (dx, dy) = finger.alone() ?: return
        conn.touched(block)
        if (dx != 0f || dy != 0f) conn.pan(block, dx, dy, fingerX, fingerY)
    }

    /** The fling a flick left, coasting on this view; null while none runs. */
    private var fling: ScrollFling? = null
    /** The frame the coast last stood at, in uptime ms. */
    private var flingAt = 0L

    /** One frame of a let go fling: the pane pans by its step at the place of the last move, and the next frame follows while it moves. */
    private val coast: Runnable = Runnable {
        val f = fling ?: return@Runnable
        val now = SystemClock.uptimeMillis()
        val dt = (now - flingAt) / 1000f
        flingAt = now
        val (dx, dy) = f.step(dt)
        if (dx != 0f || dy != 0f) conn.pan(block, dx, dy, fingerX, fingerY)
        if (f.moving) postOnAnimation(coast) else stopFling()
    }

    /** The pane coasts on [f]: a frame at a time, from the next one. */
    private fun startFling(f: ScrollFling) {
        fling = f
        flingAt = SystemClock.uptimeMillis()
        postOnAnimation(coast)
    }

    /** The fling stops: a press, the gesture not this pane's, the pane a preview, the view gone. */
    private fun stopFling() {
        fling = null
        removeCallbacks(coast)
    }

    /** The gesture is not this pane's (more fingers, the overview): nothing of it is left. */
    private fun dropFinger() {
        stopFling()
        removeCallbacks(fingerAlone)
        releaseLink()
        finger.joined()
        taps?.cancel()
        fingerMode = FingerMode.Ignored
        cancelDetector()
        selection.close()
    }

    /** A finger went down at ([x], [y]): a link there that a tap opens is underlined while it stays. */
    private fun pressLink(x: Float, y: Float) {
        removeCallbacks(unmarkLink)
        unmarkLink.run()
        val tap = conn.linkAt(block, x, y)?.let(::linkTap)
        pressedLink = tap
        if (tap != null) linkMarked = conn.markLink(block, x, y)
    }

    /** The finger is no longer on its link. After a tap the line stays a moment so the link reads as the one opened. */
    private fun releaseLink(afterTap: Boolean = false) {
        pressedLink = null
        removeCallbacks(unmarkLink)
        if (afterTap && linkMarked) postDelayed(unmarkLink, LINK_MARK_MS) else unmarkLink.run()
    }

    /** Where the finger went down, for a tap the detector does not check ([tapped]). */
    private var downX = 0f
    private var downY = 0f
    /** The finger now down moved further than a tap: it is no tap, whatever the detector says. */
    private var movedOff = false

    /** The finger at ([x], [y]) is further from where it went down than a tap goes. */
    private fun offTap(x: Float, y: Float): Boolean {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        val dx = x - downX
        val dy = y - downY
        return dx * dx + dy * dy > slop * slop
    }

    /**
     * A tap outside the grips dismisses the tools without opening the keyboard. Other taps
     * participate in the normal tap run. The detector doesn't check slop on double-tap up.
     */
    private fun tapped(e: MotionEvent) {
        if (selection.active) {
            taps?.cancel()
            selection.close()
            return
        }
        // Moved off at any point, its up included: a drag, also one the detector keeps as a double tap's.
        if (movedOff || offTap(e.x, e.y)) return taps?.cancel() ?: Unit
        // A tap on a link opens it, in a program that takes the mouse too: the program would open it
        // on the remote, where nobody sees it. The pane takes no focus and no keyboard for it.
        pressedLink?.let { link ->
            taps?.cancel()
            releaseLink(afterTap = true)
            // A path may name no file on the remote: the server says so, and the tap is then the
            // pane's after all, a moment late.
            val x = e.x
            val y = e.y
            val at = e.eventTime
            val tapped = block
            val wasPreview = preview
            if (linkTaps.take(e.eventTime)) onLink(link) {
                // Only while the tap still means what it did: on this pane, as shown, not long after.
                if (isAttachedToWindow && block == tapped && preview == wasPreview && SystemClock.uptimeMillis() - at < LATE_TAP_MS)
                    paneTap(x, y, at)
            }
            return
        }
        paneTap(e.x, e.y, e.eventTime)
    }

    private fun paneTap(x: Float, y: Float, at: Long) {
        // A program that takes the mouse (opencode, htop, Claude Code) gets the tap as a click at
        // its cell: its buttons and rows work by touch. The pane takes the focus and shows the
        // keyboard first, as every tap does, so the program hears of the focus before the click,
        // as on a desktop. Tap gestures count in such a pane too (#307): the click is part of the
        // single, which waits for a second tap and is not made when the taps become a key.
        val tapped = block
        val single: () -> Unit = {
            onTap()
            conn.click(tapped, x, y)
        }
        if (taps?.tap(conn to block, at, single) != true) single()
    }

    private val gestureListener = object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            // A fling the pane let go: the press catches it before anything else.
            stopFling()
            return true
        }
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            tapped(e)
            return true
        }
        // The detector takes the second tap of two quick ones as a double tap and gives it no
        // onSingleTapUp: its moves and its up come here.
        override fun onDoubleTapEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_UP) tapped(e)
            return true
        }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (fingerMode != FingerMode.Normal || e2.pointerCount > 1) return false
            selection.close()
            taps?.cancel()
            // onScroll reports previous − current; the core wants how far the finger moved.
            finger.drag(-dx, -dy)?.let { (x, y) -> conn.pan(block, x, y, e2.x, e2.y) }
            return true
        }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            // A flick on a panning pane, one finger's: the terminal keeps the finger's speed and coasts on it.
            if (fingerMode != FingerMode.Normal || e2.pointerCount > 1) return false
            startFling(ScrollFling(vx, vy, resources.displayMetrics.density))
            return true
        }
        override fun onLongPress(e: MotionEvent) {
            if (fingerMode != FingerMode.Normal) return
            taps?.cancel()
            releaseLink()
            useFinger()
            fingerMode = FingerMode.Word
            if (haptics) performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            selection.open(e.x, e.y)
        }
    }
    /** Its touch slop is in px at the density it was made with: a new display size makes it again. */
    private var gestures = GestureDetector(context, gestureListener)
    private var gestureDensity = resources.configuration.densityDpi

    private fun cancelDetector() {
        val now = android.os.SystemClock.uptimeMillis()
        MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0).also { gestures.onTouchEvent(it) }.recycle()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val density = newConfig.densityDpi
        if (density == gestureDensity) return
        gestureDensity = density
        // A gesture under way ends with the old detector: its long press must not fire later.
        dropFinger()
        gestures = GestureDetector(context, gestureListener)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (preview || block.isEmpty() || !attached) return false
        if (event.actionMasked == MotionEvent.ACTION_CANCEL || event.pointerCount > 1) {
            dropFinger()
            return true
        }
        if (fingerMode == FingerMode.Ignored && event.actionMasked != MotionEvent.ACTION_DOWN) return true
        fingerX = event.x
        fingerY = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                onFinger()
                downX = event.x
                downY = event.y
                movedOff = false
                if (selection.grab(event.x, event.y)) {
                    taps?.cancel()
                    fingerMode = FingerMode.Grip
                    return true
                }
                fingerMode = FingerMode.Normal
                // A tap while the text tools are open only closes them.
                if (selection.active) releaseLink() else pressLink(event.x, event.y)
                if (event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER) {
                    taps?.down(conn to block, event.eventTime, event.x, event.y)
                } else taps?.cancel()
                // It may be the first of several fingers: the terminal hears of it once it is alone.
                finger.down()
                postDelayed(fingerAlone, ViewConfiguration.getTapTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> if (!movedOff && offTap(event.x, event.y)) {
                // A drag, also one the detector keeps as a double tap's: the run ends unsent.
                movedOff = true
                taps?.cancel()
                releaseLink()
            }
            MotionEvent.ACTION_UP -> useFinger()
        }
        if (fingerMode == FingerMode.Word || fingerMode == FingerMode.Grip) {
            if (event.actionMasked == MotionEvent.ACTION_MOVE || event.actionMasked == MotionEvent.ACTION_UP) {
                if (fingerMode == FingerMode.Grip) selection.move(event.x, event.y)
                else if (movedOff || offTap(event.x, event.y)) selection.extend(event.x, event.y)
            }
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                selection.release()
                fingerMode = FingerMode.Ignored
                cancelDetector()
            }
            return true
        }
        gestures.onTouchEvent(event)
        // An up that was no tap (the detector saw a long hold or a fling) leaves no line behind.
        if (event.actionMasked == MotionEvent.ACTION_UP && pressedLink != null) releaseLink()
        return true
    }

    private companion object {
        /** How long a surface keeps its size before the terminal is fitted to it. */
        const val FIT_DELAY_MS = 120L
        /** How long a tapped link stays underlined: long enough to see which one opened. */
        const val LINK_MARK_MS = 400L
        /** How late the server's "no file there" may come for a tap on a path to still be the pane's. */
        const val LATE_TAP_MS = 2000L
    }
}

/** A keyboard paste ([keyboardTextIsPaste]) into [block] of [conn] that waits for the user: the dialog's [title] ([pasteQuestion]) and the [text]. */
internal class KeyboardPaste(val conn: HostConnection, val block: String, val title: String, val text: String)

/**
 * Invisible view that owns the soft keyboard. Committed text goes to `sendText`,
 * key events to `VeloCore.key`, both for the focused block of [connection]. A committed text
 * that is a paste follows the same rule as the pane's Paste action ([pasteTextIntoPane]).
 */
class TerminalInputView(context: Context) : View(context) {
    /** The focused block of [connection]; null while no pane of it is on screen, and while its input is paused ([paneInputPaused]). */
    var target: () -> String? = { null }
    /** The connection that owns [target]'s block; set together with it by the workspace drawing that connection. */
    var connection: HostConnection? = null
    /** Whether panes of [connection] are on screen while their input is paused ([io.github.yhzion.velo.ui.panesPaused]): a hardware key then goes nowhere. */
    var panesPaused: () -> Boolean = { false }
    /** Sticky modifier meta state from the key bar (e.g. Ctrl); consumed by the next key. */
    var stickyMeta: Int = 0
    var onStickyConsumed: () -> Unit = {}
    /** Opens the pane picker while the workspace has panes available for keyboard actions. */
    internal var onPaneMenuShortcut: (() -> Unit)? = null
    /** Opens the command palette while the workspace is shown and no other overlay is open. */
    internal var onPaletteShortcut: (() -> Unit)? = null
    /** Ctrl+Alt+Shift+arrow: for a side, the move the workspace offers now, or null when that move is not possible. */
    internal var onMovePaneShortcut: ((PaneDirection) -> (() -> Unit)?)? = null
    /** Asks about a keyboard paste that the terminal would run line by line; null while nothing can ask, and the core then refuses it. */
    internal var onPasteQuestion: ((KeyboardPaste) -> Unit)? = null
    /** A tap gesture sends its key ([taps]): the workspace makes the finger feel it as a key bar key (#309). */
    internal var onTapKey: () -> Unit = {}
    /**
     * The app's run of tap gestures ([TapGestureInput]): it counts on the pane that takes keys
     * ([target], [connection]) and sends its key as the key bar's goes. The workspace sets its keys.
     */
    internal val taps = TapGestureInput<Pair<HostConnection, String>>(
        // The slop is read at each finger: a new display size changes it while the view stays.
        doubleTapTimeoutMs = ViewConfiguration.getDoubleTapTimeout().toLong(),
        slopPx = { ViewConfiguration.get(context).scaledDoubleTapSlop.toFloat() },
        post = { r, ms -> postDelayed(r, ms) },
        unpost = { removeCallbacks(it) },
        now = { android.os.SystemClock.uptimeMillis() },
    ).also { taps ->
        // The pane that takes keys now: none while an overlay, a window over the app, a cover or a
        // lost connection holds them.
        taps.target = { connection?.takeIf { workspaceAcceptsInput && hasWindowFocus() }?.let { c -> target()?.let { c to it } } }
        taps.send = { key ->
            key.keyCode?.let { code ->
                onTapKey()
                if (!finishBarComposition()) sendKey(code, 0, 0)
            }
        }
        taps.paneSelects = { (c, block) -> c.hasSelection(block) }
        // The focus that left the pane and came back ends its run, though the pane takes keys again.
        taps.moves = { (c, _) -> c.focusMoves }
    }

    /**
     * The workspace takes keys now (no overlay, cover or lost connection): when it stops, a run of
     * taps ends unsent, and a request for the keyboard is over.
     */
    var workspaceAcceptsInput = false
        set(value) {
            if (!value) {
                taps.cancel()
                endKeyboardRequest()
            }
            field = value
        }
    val acceptsFocusedInput: Boolean
        get() = workspaceAcceptsInput && hasFocus() && hasWindowFocus()

    private data class HardwarePress(
        val connection: HostConnection,
        val block: String,
        val stickyMeta: Int,
    ) {
        fun send(event: KeyEvent) {
            val meta = event.metaState or stickyMeta
            connection.key(block, event.keyCode, meta, event.getUnicodeChar(meta), event.action)
        }
    }
    private val hardwarePresses = mutableMapOf<Pair<Int, Int>, HardwarePress>()

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    override fun onCheckIsTextEditor() = true

    // The editors of the keyboard and of an accessibility input method. They write to the bound
    // pane ([editorConnection], [editorBlock]) as it is at each call, so a connection Android
    // keeps follows the pane. Replaced only when the keyboard's input restarts ([restartEditor]).
    private var editors: TerminalEditorState? = null
    // The editors while a window over the app has the focus: what they get waits for the focus.
    private var interruptedEditors: TerminalEditorState? = null
    private var activityResumed = false
    private var editorConnection: HostConnection? = null
    private var editorBlock: String? = null
    // Set when a keyboard paste is asked about: what the keyboard sends after it, before the
    // workspace shows the question and takes the keyboard, must not reach the terminal before it.
    private var pasteAsking = false
    private val keyboardRequest = KeyboardRequest()

    /**
     * Asks Android for the keyboard. The request stays open until the keyboard is up, and is
     * made again when Android starts the input anew before that ([KeyboardRequest]).
     */
    internal fun requestKeyboard() {
        // showSoftInput may start the input itself before it asks: that start is not a later one.
        keyboardRequest.ended()
        context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)?.showSoftInput(this, 0)
        keyboardRequest.asked(keyboardUp())
    }

    /** The keyboard is sent away, or the terminal is no longer the editor that takes the keys: an open request ends. */
    internal fun endKeyboardRequest() = keyboardRequest.ended()

    private fun keyboardUp() = rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == true

    override fun onApplyWindowInsets(insets: android.view.WindowInsets): android.view.WindowInsets {
        if (insets.isVisible(android.view.WindowInsets.Type.ime())) keyboardRequest.keyboardShown()
        return super.onApplyWindowInsets(insets)
    }

    private val accessibleEditor = TerminalAccessibilityEditor(this,
        active = { activityResumed && acceptsFocusedInput && editorConnection === connection && editorBlock != null && editorBlock == target() },
        insert = ::insertAccessibleText,
    )

    override fun getAccessibilityNodeProvider(): AccessibilityNodeProvider = accessibleEditor

    override fun onInitializeAccessibilityEvent(event: AccessibilityEvent) {
        super.onInitializeAccessibilityEvent(event)
        accessibleEditor.focusEvent(event)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (!gainFocus) {
            forgetImeContext()
            cancelImeInterruption()
            endKeyboardRequest()
        }
        accessibleEditor.updateVisibility()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        // A window over the workspace (a menu, a dialog, the system's) takes the keys: a run of taps ends,
        // and so does a request for the keyboard.
        if (!hasWindowFocus) {
            taps.cancel()
            endKeyboardRequest()
        }
        if (hasWindowFocus) {
            interruptedEditors?.flushDeferred()
            interruptedEditors?.preeditHidden = false
            interruptedEditors = null
            editors?.preeditHidden = false
        } else {
            // The pane behind the window does not show what the keyboard composes there.
            editors?.preeditHidden = true
            if (hasFocus() && workspaceAcceptsInput && activityResumed) {
                forgetImeContext()
                interruptedEditors = editors
            }
        }
        accessibleEditor.updateVisibility()
    }

    /** The editors take input again. Android starts the keyboard's input itself when the window gets the focus back. */
    internal fun onActivityResumed() {
        activityResumed = true
        accessibleEditor.updateVisibility()
    }

    /**
     * The editors stay open and refuse input until the resume, so the keyboard never holds a closed
     * one. What they compose and what waits behind another window is discarded, not sent.
     */
    internal fun onActivityPaused() {
        activityResumed = false
        taps.cancel()
        endKeyboardRequest()
        cancelImeInterruption()
        editors?.discardDeferred()
        accessibleEditor.invalidateTarget()
    }

    private fun cancelImeInterruption() {
        interruptedEditors?.discardDeferred()
        interruptedEditors = null
    }

    internal fun forgetImeContext() {
        editors?.forgetContext()
    }

    private fun insertAccessibleText(text: String): Boolean {
        val destination = connection ?: return false
        val block = target() ?: return false
        val session = destination.state.session ?: return false
        if (destination.composerRunning || text.any { Character.isISOControl(it) && it !in "\n\t" }) return false
        if (text.none { it in "\n\t" }) {
            retireComposition()
            if (text.isNotEmpty()) destination.op("sendText", "block" to block, "text" to text)
            return true
        }
        val epoch = destination.composerTarget(session, block, requirePaste = true)
        if (epoch == 0L) return false

        // The worker rechecks paste mode before writing. Retire preedit only for an
        // admitted send, before the IME can submit its old candidate a second time.
        val accepted = destination.sendComposer(session, block, text, null, null, epoch) { error ->
            if (error != null) destination.error = error.message
        }
        if (accepted) retireComposition()
        return accepted
    }

    internal fun disconnectInput() {
        workspaceAcceptsInput = false
        connection = null
        target = { null }
        panesPaused = { false }
        onPaneMenuShortcut = null
        onPaletteShortcut = null
        onMovePaneShortcut = null
        onPasteQuestion = null
        onTapKey = {}
        refreshEditorTarget()
    }

    /**
     * Binds the editors to the pane that takes the keys now, or to none. The editors stay open
     * and follow it: Android's connections to them are kept, and write to nothing while no pane
     * is bound. A composition was for the pane before: it is retired ([retireComposition]).
     */
    internal fun refreshEditorTarget() {
        val block = if (workspaceAcceptsInput) target() else null
        if (editorConnection === connection && editorBlock == block) return
        // The workspace has taken the keys, or the pane that asked is left: the question holds the paste now.
        pasteAsking = false
        // Before the binding changes: the pane before still shows the composition that is dropped here.
        retireComposition()
        editorConnection = connection
        editorBlock = block
        accessibleEditor.invalidateTarget()
    }

    /**
     * The bound pane changes, or gets text from elsewhere. A composition the editors hold
     * ([holdsTerminalComposition]) is dropped, not sent, and the keyboard's input restarts so
     * that the keyboard forgets it. Without one the editors and Android's connections to them
     * stay: only the character the keyboard reads before its cursor is no longer there, and the
     * keyboard is told so.
     */
    private fun retireComposition() {
        if (editors?.holdsComposition() == true) restartEditor() else forgetImeContext()
    }

    /**
     * Closes the editors and restarts the keyboard's input: the keyboard forgets the composition
     * it holds, and its next key starts afresh. Called only to retire such a composition: one the
     * editors hold when the pane changes or gets text from elsewhere ([retireComposition]), one
     * that a key of the app has just sent ([finishBarComposition]), or one that an armed modifier
     * took as its key before it reached the editors ([sendArmedKey]).
     */
    private fun restartEditor() {
        cancelImeInterruption()
        editors?.close()
        editors = null
        if (hasFocus()) context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)?.restartInput(this)
    }

    /**
     * Sends what the keyboard is composing before a key of the bar or a hardware key, and
     * restarts the keyboard's input: the keyboard still holds that composition. True while a
     * paste waits for its question ([pasteTextIntoPane]), whether this composition or a commit
     * just before it asked: the key then waits with it and the caller does not send it.
     */
    fun finishBarComposition(): Boolean {
        if (pasteAsking) return true
        if (editors?.composing?.finishBeforeExternalKey() != true) return false
        restartEditor()
        return pasteAsking
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_ACTION_SEND
        // Android starts the input anew and has dropped a request it had not answered (#299). Asked
        // again once this start is made: a request from inside it would be dropped with the first.
        if (keyboardRequest.open) post {
            if (keyboardRequest.inputStarted(keyboardUp(), acceptsFocusedInput)) requestKeyboard()
        }
        // A new connection joins the live editors, which follow the bound pane ([refreshEditorTarget]).
        val state = editors ?: run {
            cancelImeInterruption()
            TerminalEditorState({ start, end ->
                // Also while no pane is bound: the editors stay through that time, and the keyboard
                // has to learn that the character it typed is no longer before its cursor.
                if (hasFocus() && hasWindowFocus() && activityResumed) {
                    context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                        ?.updateSelection(this, start, end, -1, -1)
                }
            }, preeditShown = { text -> boundPane { host, block -> host.preedit(block, text) } }).also {
                it.preeditHidden = !hasWindowFocus()
                editors = it
            }
        }
        val preedit = state.preedit
        outAttrs.initialSelStart = android.text.Selection.getSelectionStart(preedit).coerceAtLeast(0)
        outAttrs.initialSelEnd = android.text.Selection.getSelectionEnd(preedit).coerceAtLeast(0)
        // Read at each call: the editor writes to the pane bound then, and to nothing while none is.
        fun bound() = !pasteAsking && editors === state && workspaceAcceptsInput && hasFocus() &&
            editorConnection != null && editorConnection === connection && editorBlock != null && editorBlock == target()
        return TerminalEditor(this,
            accepts = { bound() && hasWindowFocus() && activityResumed },
            interrupted = { state === interruptedEditors && bound() && activityResumed },
            state = state,
            commit = ::emitText,
            finish = { text -> boundPane { host, block -> host.op("sendText", "block" to block, "text" to text) } },
            key = { code -> sendKey(code, 0, if (code == KeyEvent.KEYCODE_ENTER) 13 else 0) },
            forward = { event -> boundPane { host, block -> forward(event, host, block) } },
            armedKey = ::sendArmedKey,
            plainInput = { stickyMeta == 0 },
            paste = { text -> boundPane { host, block -> pasteTextIntoPane(host, block, text) } },
        )
    }

    /** Runs [action] for the pane the editors are bound to ([refreshEditorTarget]): nothing while none is bound. */
    private inline fun boundPane(action: (HostConnection, String) -> Unit) {
        val host = editorConnection ?: return
        val block = editorBlock ?: return
        action(host, block)
    }

    /**
     * Shared by keyboard and clipboard paste: the core frames text for bracketed paste; unsafe
     * unbracketed text must be confirmed. Without a confirmation handler the core refuses it.
     */
    internal fun pasteTextIntoPane(destination: HostConnection, block: String, text: String) {
        forgetImeContext()
        val title = pasteQuestion(text, destination.bracketedPaste(block))
        val ask = onPasteQuestion
        if (title == null || ask == null) return destination.paste(block, text, confirmed = false)
        pasteAsking = true
        ask(KeyboardPaste(destination, block, title, text))
    }

    /**
     * The question about a keyboard paste was answered or ended: the pane takes the keyboard's
     * input again. Nothing restarts here: a composition that a key of the app ended as a paste
     * was retired with that key ([finishBarComposition]).
     */
    internal fun pasteAnswered() {
        pasteAsking = false
    }

    fun sendText(text: String) {
        forgetImeContext()
        emitText(text)
    }

    private fun emitText(text: String) {
        val block = target() ?: return
        val a = connection ?: return
        if (stickyMeta != 0 && text.codePointCount(0, text.length) == 1) {
            val cp = text.codePointAt(0)
            val code = KeyEvent.keyCodeFromString("KEYCODE_" + text.uppercase()).takeIf { it != KeyEvent.KEYCODE_UNKNOWN } ?: 0
            val shifted = if (stickyMeta and KeyEvent.META_SHIFT_ON != 0) {
                val map = android.view.KeyCharacterMap.load(android.view.KeyCharacterMap.VIRTUAL_KEYBOARD)
                val key = map.getEvents(text.toCharArray())?.firstOrNull {
                    it.action == KeyEvent.ACTION_DOWN && !KeyEvent.isModifierKey(it.keyCode)
                }
                key?.let { map.get(it.keyCode, it.metaState or KeyEvent.META_SHIFT_ON) }
                    ?.takeIf { it > 0 } ?: Character.toUpperCase(cp)
            } else cp
            sendKey(code, stickyMeta, shifted)
            return
        }
        a.op("sendText", "block" to block, "text" to text)
    }

    fun sendKey(keyCode: Int, meta: Int, unicode: Int) {
        forgetImeContext()
        val block = target() ?: return
        val a = connection ?: return
        val m = meta or stickyMeta
        a.key(block, keyCode, m, unicode, KeyEvent.ACTION_DOWN)
        a.key(block, keyCode, m, unicode, KeyEvent.ACTION_UP)
        consumeStickyMeta()
    }

    /**
     * With Ctrl or Alt of the key bar armed, [text] the keyboard composes — one jamo of the
     * two-set Korean layout or one Latin letter — is sent as that modifier with the Latin key
     * of its place ([armedLatinKey]): Ctrl with the Korean C key is Ctrl+C, and so is the c a predicting
     * English keyboard composes. True when it was sent. The keyboard still holds the text as
     * its composition, so the editor is restarted and the next key starts afresh.
     */
    private fun sendArmedKey(text: String): Boolean {
        val key = armedLatinKey(text, stickyMeta) ?: return false
        // The armed modifier takes the key of the jamo or the letter, as it takes a direct commit.
        sendText(key.toString())
        restartEditor()
        return true
    }

    internal fun dispatchHardwareKey(event: KeyEvent, terminalOwnsFocus: Boolean): Boolean {
        val key = event.deviceId to event.keyCode
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            hardwarePresses.remove(key)
            if (!terminalOwnsFocus) return false
            val destination = connection ?: return false
            val block = target() ?: return false
            forgetImeContext()
            // The key comes after the syllable the keyboard is still composing ([keyFlushesComposition]).
            if (keyFlushesComposition(modifier = KeyEvent.isModifierKey(event.keyCode)) && finishBarComposition()) return true
            withHardwareStickyMeta(stickyMeta, KeyEvent.isModifierKey(event.keyCode), ::consumeStickyMeta) { meta ->
                val press = HardwarePress(destination, block, meta)
                hardwarePresses[key] = press
                press.send(event)
            }
            return true
        }

        // In a key chord, downTime can refer to a different key.
        val press = hardwarePresses[key] ?: return false
        when (event.action) {
            KeyEvent.ACTION_UP -> {
                hardwarePresses.remove(key)
                // Release the original pane even after a workspace switch.
                press.send(event)
            }
            KeyEvent.ACTION_DOWN -> {
                if (terminalOwnsFocus && press.connection === connection && press.block == target()) {
                    press.send(event)
                }
            }
        }
        return true
    }

    private fun forward(event: KeyEvent, destination: HostConnection, block: String) {
        val meta = event.metaState or stickyMeta
        destination.key(block, event.keyCode, meta, event.getUnicodeChar(meta), event.action)
        if (event.action == KeyEvent.ACTION_UP) consumeStickyMeta()
    }

    private fun consumeStickyMeta() {
        if (stickyMeta == 0) return
        stickyMeta = 0
        onStickyConsumed()
    }
}

/** Android gives the lengths on each side of the cursor separately. */
internal inline fun sendSurroundingDeletes(before: Int, after: Int, send: (Int) -> Unit) {
    repeat(before.coerceAtLeast(0)) { send(KeyEvent.KEYCODE_DEL) }
    repeat(after.coerceAtLeast(0)) { send(KeyEvent.KEYCODE_FORWARD_DEL) }
}
