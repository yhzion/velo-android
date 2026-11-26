package io.github.yhzion.velo.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.findViewTreeOnBackPressedDispatcherOwner
import io.github.yhzion.velo.HostConnection
import io.github.yhzion.velo.R
import kotlin.math.roundToInt

/** A floating text toolbar and grips for one SurfaceView. The range lives in the terminal;
 * Android keeps only a snapshot of its pane-local pixel endpoints, never a copy of its text. */
internal class PaneSelection(private val view: PaneSurfaceView, private val conn: HostConnection) {
    private val clipboard = view.context.getSystemService(ClipboardManager::class.java)
    private val handles = SelectionHandles()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        val attrs = view.context.obtainStyledAttributes(intArrayOf(android.R.attr.colorAccent))
        try { color = attrs.getColor(0, Color.CYAN) } finally { attrs.recycle() }
    }
    private var owner: String? = null
    private var mode: ActionMode? = null
    private var bounds: IntArray? = null
    /** The link under the long press: the tools offer to open it ([linkTap]) and to copy its target. */
    private var link: TerminalLink? = null
    private var pointX = 0
    private var pointY = 0
    private var grabbed: SelectionEnd? = null
    val active get() = owner != null

    private val back = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() = close()
    }
    private fun canPaste() = clipboard?.hasPrimaryClip() == true
    private val clipChanged = ClipboardManager.OnPrimaryClipChangedListener {
        if (bounds == null && !canPaste() && link == null) close() else mode?.invalidate()
    }

    fun open(x: Float, y: Float): Boolean {
        close()
        val block = view.block
        if (block.isEmpty() || view.preview || !view.isAttachedToWindow) return false
        view.onTextFocus()
        if (view.block != block || view.preview || !view.isAttachedToWindow) return false
        conn.selectWord(block, x, y)
        owner = block
        pointX = x.roundToInt()
        pointY = y.roundToInt()
        place(conn.selectionBounds(block))
        link = conn.linkAt(block, x, y)
        if (bounds == null && !canPaste() && link == null) {
            close()
            return false
        }
        mode = view.startActionMode(actions, ActionMode.TYPE_FLOATING)
        if (mode == null) {
            close()
            return false
        }
        clipboard?.addPrimaryClipChangedListener(clipChanged)
        view.findViewTreeOnBackPressedDispatcherOwner()?.onBackPressedDispatcher?.addCallback(back)
        if (bounds != null) view.postDelayed(checkRange, CHECK_RANGE_MS)
        return true
    }

    // Terminal output can scroll independently of View drawing. Check only while grips exist.
    private val checkRange = object : Runnable {
        override fun run() {
            if (refreshRange()) view.postDelayed(this, CHECK_RANGE_MS)
        }
    }
    private fun refreshRange(): Boolean {
        val block = owner ?: return false
        val fresh = conn.selectionBounds(block)
        if (fresh == null) {
            close()
            return false
        }
        if (!fresh.contentEquals(bounds)) {
            place(fresh)
            mode?.invalidateContentRect()
        }
        return true
    }
    private fun place(range: IntArray?) {
        bounds = range
        if (range != null) handles.place(range, view.width, view.height, view.resources.displayMetrics.density)
        view.invalidate()
    }

    fun grab(x: Float, y: Float): Boolean {
        if (bounds == null || !refreshRange()) return false
        val end = handles.hit(x, y) ?: return false
        grabbed = end
        handles.grab(end, x, y)
        return true
    }
    fun move(x: Float, y: Float) {
        val end = grabbed ?: return
        moveCell(end, handles.cellX(x), handles.cellY(y))
    }
    /** A drag from the original press adjusts the leading edge if it starts before the word. */
    fun extend(x: Float, y: Float) {
        val b = bounds ?: return
        if (grabbed == null) {
            val above = y < b[1] - conn.terminalFontPx(view.block)
            grabbed = if (above || (y < b[1] && x < b[0])) SelectionEnd.Start else SelectionEnd.End
        }
        moveCell(grabbed ?: return, x, y)
    }
    private fun moveCell(end: SelectionEnd, x: Float, y: Float) {
        val block = owner ?: return
        conn.moveSelectionHandle(block, end == SelectionEnd.Start,
            x.coerceIn(0f, (view.width - 1).coerceAtLeast(0).toFloat()),
            y.coerceIn(0f, (view.height - 1).coerceAtLeast(0).toFloat()))
        refreshRange()
    }
    fun release() { grabbed = null }

    fun draw(canvas: Canvas) {
        if (bounds == null) return
        for (end in SelectionEnd.entries) {
            val x = handles.knobX(end)
            val y = handles.knobY(end)
            paint.strokeWidth = handles.radius / 2
            canvas.drawLine(handles.edgeX(end), handles.edgeY(end), x, y, paint)
            canvas.drawCircle(x, y, handles.radius, paint)
        }
    }

    fun close() {
        val block = owner ?: return
        owner = null
        link = null
        val old = mode
        mode = null
        release()
        view.removeCallbacks(checkRange)
        clipboard?.removePrimaryClipChangedListener(clipChanged)
        back.remove()
        place(null)
        conn.clearSelection(block)
        old?.finish()
    }

    private val actions = object : ActionMode.Callback2() {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            val target = link
            val open = target?.let(::linkTap)
            if (open != null) menu.add(Menu.NONE, R.id.open_terminal_link, 0, if (open.file != null) "Open file" else "Open link")
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            if (bounds != null) menu.add(Menu.NONE, android.R.id.copy, 0, android.R.string.copy)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            menu.add(Menu.NONE, android.R.id.paste, 1, android.R.string.paste).apply {
                isVisible = canPaste()
                setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            }
            if (target != null) menu.add(Menu.NONE, R.id.copy_terminal_link, 2, if (target.kind == "file") "Copy path" else "Copy link")
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            return menu.hasVisibleItems()
        }
        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            val paste = menu.findItem(android.R.id.paste) ?: return false
            val available = canPaste()
            if (paste.isVisible == available) return false
            paste.isVisible = available
            return true
        }
        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            if (mode !== this@PaneSelection.mode || owner != view.block) return false
            when (item.itemId) {
                android.R.id.copy -> if (bounds != null) view.onCopySelection()
                android.R.id.paste -> if (canPaste()) view.onPasteSelection()
                R.id.open_terminal_link -> link?.let(::linkTap)?.let { tap ->
                    // Chosen from the menu, a path that is no file there gets a word, not a tap.
                    view.onLink(tap) { tap.file?.let { if (conn.error == null) conn.error = "The remote has no file at ${it.target}." } }
                }
                R.id.copy_terminal_link -> link?.let { clipboard?.setPrimaryClip(ClipData.newPlainText("Velo", it.target)) }
                else -> return false
            }
            close()
            return true
        }
        override fun onDestroyActionMode(mode: ActionMode) {
            if (mode !== this@PaneSelection.mode) return
            this@PaneSelection.mode = null
            close()
        }
        override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
            val b = bounds
            if (b == null) outRect.set(pointX, pointY, pointX + 1, pointY + 1)
            else {
                // For wrapped text the start can be to the right of the end.
                val top = b[1] - conn.terminalFontPx(this@PaneSelection.view.block).roundToInt()
                val bottom = (maxOf(handles.knobY(SelectionEnd.Start), handles.knobY(SelectionEnd.End)) + handles.radius).roundToInt()
                outRect.set(minOf(b[0], b[2]), top.coerceAtLeast(0), maxOf(b[0], b[2]), bottom)
            }
        }
    }

    private companion object { const val CHECK_RANGE_MS = 200L }
}
