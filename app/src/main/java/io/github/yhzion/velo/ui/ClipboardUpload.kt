package io.github.yhzion.velo.ui

import android.content.ContentResolver
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.yhzion.velo.HostConnection
import java.io.IOException
import java.time.LocalDateTime

private const val BUSY = "Another send is still in progress."
private const val TARGET_GONE = "The pane or connection changed. Paste again."
private const val NO_MEMORY = "Not enough memory to read the file."
/** How long a paste waits for the core to focus its pane. */
internal const val FOCUS_WAIT_MS = 2_000L
private const val FOCUS_POLL_MS = 50L
/** How long the read may take; a result that comes later does nothing. */
internal const val READ_LIMIT_MS = 60_000L

/** [f] wrapped so that only its first call runs it; later calls do nothing. Main thread. */
internal fun once(f: () -> Unit): () -> Unit {
    var ran = false
    return {
        if (!ran) {
            ran = true
            f()
        }
    }
}

internal enum class FocusWait { Take, Wait, GiveUp }

/** What a paste does when it has waited [waitedMs] for its pane: a pane shown focused is taken, even at the bound. */
internal fun focusWait(focused: Boolean, waitedMs: Long): FocusWait = when {
    focused -> FocusWait.Take
    waitedMs >= FOCUS_WAIT_MS -> FocusWait.GiveUp
    else -> FocusWait.Wait
}

/**
 * Sends [file] to [block] as Compose's attachment, with no text: the server types its path into
 * the pane, without Enter (#109). Main thread. True when the paste started; false when it was
 * refused at once (another send, or no connection), and the error notice says why.
 *
 * The core takes a composer target only in its focused pane, and a pane menu may belong to
 * another pane: the paste then asks the core to focus it and waits for a state that shows it
 * focused ([FOCUS_WAIT_MS]). The target is taken before the read, so a pane or connection that
 * changes while the file is read refuses the send instead of taking it elsewhere. The read is
 * bounded too ([READ_LIMIT_MS]). [HostConnection.uploading] is set from the first tap until the
 * send ends, so a second Paste is refused throughout. Failures go to the error notice.
 *
 * [onReadDone] runs once on main when the file is no longer read: after the read, at its limit,
 * or when the paste ends before it. A keyboard's read grant is released there (#160).
 */
internal fun pasteClipboardFile(
    context: Context, conn: HostConnection, block: String, file: ClipboardFile, onReadDone: () -> Unit = {},
): Boolean {
    // Every path below calls it; once makes a missed or second call harmless to the grant.
    val readDone = once(onReadDone)
    if (conn.uploading != null || conn.composerRunning) {
        conn.error = BUSY
        readDone()
        return false
    }
    val session = conn.state.session
    if (session == null || !conn.connected) {
        conn.error = TARGET_GONE
        readDone()
        return false
    }
    // Each step checks that this paste still owns the toast: a timeout or destroy() takes it away.
    val paste = Any()
    conn.uploadOwner = paste
    conn.uploading = "Uploading file…"
    val resolver = context.applicationContext.contentResolver
    val main = Handler(Looper.getMainLooper())
    val asked = SystemClock.uptimeMillis()
    if (!conn.state.showsComposerPane(session, block)) conn.op("focus", "block" to block)
    fun step() {
        if (conn.uploadOwner !== paste) return readDone()
        when (focusWait(conn.state.showsComposerPane(session, block), SystemClock.uptimeMillis() - asked)) {
            FocusWait.Take -> readAndSend(conn, paste, session, block, file, resolver, main, readDone)
            FocusWait.Wait -> main.postDelayed({ step() }, FOCUS_POLL_MS)
            FocusWait.GiveUp -> {
                readDone()
                conn.endUpload(paste, TARGET_GONE)
            }
        }
    }
    step()
    return true
}

private fun readAndSend(
    conn: HostConnection, paste: Any, session: String, block: String, file: ClipboardFile,
    resolver: ContentResolver, main: Handler, readDone: () -> Unit,
) {
    val epoch = conn.composerTarget(session, block)
    if (epoch == 0L) {
        readDone()
        return conn.endUpload(paste, TARGET_GONE)
    }
    readClipboardFileOnWorker(resolver, file, main) { read ->
        readDone()
        if (conn.uploadOwner !== paste) return@readClipboardFileOnWorker
        read.fold(
            onSuccess = { attachment ->
                conn.uploading = "Uploading ${attachment.name}…"
                conn.sendComposer(session, block, "", attachment.name, attachment.bytes, epoch) { f ->
                    // Nothing was written: the same case as a target gone before the read, so the same text.
                    conn.endUpload(paste, f?.let { if (it.targetGone && it.wroteNothing) TARGET_GONE else it.message })
                }
            },
            onFailure = { e -> conn.endUpload(paste, e.message ?: UNREADABLE) },
        )
    }
}

/**
 * Reads [file] on a worker thread and gives [deliver] the result on [main], exactly once: the
 * read, or [UNREADABLE] when it takes longer than [READ_LIMIT_MS] (a later result then does
 * nothing), or [NO_MEMORY] when no worker can start. Every failure is an [IOException] whose
 * message the user can read.
 */
internal fun readClipboardFileOnWorker(
    resolver: ContentResolver, file: ClipboardFile, main: Handler, deliver: (Result<ComposerAttachment>) -> Unit,
) {
    // Set and read on main only.
    var delivered = false
    fun once(result: Result<ComposerAttachment>) {
        if (delivered) return
        delivered = true
        deliver(result)
    }
    // A stream that never ends would hold the toast, and refuse every later paste, for good.
    val timeout = Runnable { once(Result.failure(IOException(UNREADABLE))) }
    main.postDelayed(timeout, READ_LIMIT_MS)
    val worker = Thread({
        // Only the read runs here; the result goes back to main, which owns the connection.
        val read: Result<ComposerAttachment> = try {
            Result.success(resolver.loadClipboardFile(file, LocalDateTime.now()))
        } catch (e: IOException) {
            Result.failure(e)
        } catch (_: OutOfMemoryError) {
            Result.failure(IOException(NO_MEMORY))
        } catch (_: Exception) {
            Result.failure(IOException(UNREADABLE))
        }
        main.post {
            main.removeCallbacks(timeout)
            once(read)
        }
    }, "clipboard-file")
    try {
        worker.start()
    } catch (_: OutOfMemoryError) {
        main.removeCallbacks(timeout)
        once(Result.failure(IOException(NO_MEMORY)))
    }
}

/** Hides the toast and shows [error], unless [paste] no longer owns the toast: then it does nothing. */
private fun HostConnection.endUpload(paste: Any, error: String?) {
    if (uploadOwner !== paste) return
    uploadOwner = null
    uploading = null
    if (error != null) this.error = error
}
