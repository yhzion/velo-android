package io.github.yhzion.velo.core

import android.view.Surface

/** JNI bridge to libvelocore.so (Zig). sendComposer is worker-only. */
object VeloCore {
    init {
        System.loadLibrary("velocore")
    }

    @JvmStatic external fun start(listener: CoreListener, caDirs: Array<String>, fontScaleDp: Float): Long
    @JvmStatic external fun connect(core: Long, url: String, reconnectToken: String?)
    @JvmStatic external fun disconnect(core: Long)
    @JvmStatic external fun requestState(core: Long)
    @JvmStatic external fun destroy(core: Long)
    @JvmStatic external fun selectSession(core: Long, sessionId: String)
    @JvmStatic external fun command(core: Long, json: String)
    @JvmStatic external fun composerTarget(core: Long, session: String, block: String, requirePaste: Boolean): Long
    @JvmStatic external fun invalidateComposer(core: Long)
    @JvmStatic external fun sendComposer(core: Long, epoch: Long, session: String, block: String, text: String, name: String?, data: ByteArray?): String?
    @JvmStatic external fun key(core: Long, block: String, keyCode: Int, metaState: Int, unicode: Int, action: Int)
    @JvmStatic external fun paneSurface(core: Long, block: String, surface: Surface?, widthPx: Int, heightPx: Int)
    @JvmStatic external fun panePreview(core: Long, block: String, surface: Surface?, widthPx: Int, heightPx: Int)
    @JvmStatic external fun paneResume(core: Long, block: String, surface: Surface?, widthPx: Int, heightPx: Int)
    @JvmStatic external fun paneSuspend(core: Long, block: String)
    /** [live]: a pinch is still changing [scale]; a scale that is not live follows when it settles. */
    @JvmStatic external fun paneScale(core: Long, block: String, scale: Float, live: Boolean)
    @JvmStatic external fun panePan(core: Long, block: String, dxPx: Float, dyPx: Float, xPx: Float, yPx: Float)
    @JvmStatic external fun paneTouch(core: Long, block: String, action: Int, x: Float, y: Float)
    @JvmStatic external fun paneFind(core: Long, block: String, needle: String, direction: Int): Long
    @JvmStatic external fun paneCopy(core: Long, block: String, viewport: Boolean): String
    @JvmStatic external fun paneBracketedPaste(core: Long, block: String): Boolean
    @JvmStatic external fun paneClick(core: Long, block: String, x: Float, y: Float): Boolean
    @JvmStatic external fun downloadGeneration(core: Long): Long
    @JvmStatic external fun paneDownloadBegin(core: Long, block: String, path: String, ifNoneMatch: String?, generation: Long): String?
    /**
     * [announced]: the size the server gave in the begin answer, -1 when it gave none. Returns the file's size, or -1
     * when the download was stopped, -2 when it failed or ended early, -3 when the file is larger than announced or
     * than Velo opens.
     */
    @JvmStatic external fun downloadFetch(core: Long, ticket: String, fd: Int, announced: Long, generation: Long): Long
    @JvmStatic external fun downloadRelease(core: Long, ticket: String, generation: Long): Boolean
    @JvmStatic external fun downloadCancel(core: Long)
    /** Blocking: forwards [host]:[port] of the remote to the phone (`Core.forwardOpen`); answers JSON. */
    @JvmStatic external fun forwardOpen(core: Long, block: String, host: String, port: Int): String?
    /** Blocking: ends the forward of the tunnel block [block], its connections and the block. */
    @JvmStatic external fun forwardClose(core: Long, block: String)
    /** Blocking: closes the tunnel blocks this client left behind; false when it could not look. */
    @JvmStatic external fun forwardSweep(core: Long): Boolean
    @JvmStatic external fun paneLink(core: Long, block: String, x: Float, y: Float): String?
    @JvmStatic external fun paneMarkLink(core: Long, block: String, x: Float, y: Float): Boolean
    @JvmStatic external fun paneUnmarkLink(core: Long, block: String)
    @JvmStatic external fun paneSelectWord(core: Long, block: String, x: Float, y: Float)
    @JvmStatic external fun paneMoveSelectionHandle(core: Long, block: String, start: Boolean, x: Float, y: Float)
    @JvmStatic external fun paneSelectionBounds(core: Long, block: String): IntArray?
    @JvmStatic external fun paneSelectedText(core: Long, block: String): String
    @JvmStatic external fun paneHasSelection(core: Long, block: String): Boolean
    @JvmStatic external fun paneSwaps(core: Long, block: String): Long
    @JvmStatic external fun paneClearSelection(core: Long, block: String)
    @JvmStatic external fun panePreedit(core: Long, block: String, text: String)
}

/** Callbacks from the core, invoked on a core thread. */
interface CoreListener {
    fun onState(gen: Long, full: Boolean, json: String)
    fun onPaneReport(json: String)
    fun onPaneWrite(tag: String, result: String, message: String)
    /** The last lines of a pane's text for the `paneScreen` intent [tag]; "" when they could not be read. */
    fun onPaneScreen(tag: String, text: String)
    fun onReconnectToken(token: String)
    /** [fix]: a one-line command that fixes the failure, for "Copy command" (from the SSH pool); "" when none. */
    fun onError(code: String, message: String, fix: String)
    fun rasterizeGlyph(utf8: ByteArray, bold: Boolean, italic: Boolean, cellW: Int, cellH: Int, columns: Int): ByteArray?
}
