package io.github.yhzion.velo.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.github.yhzion.velo.HostConnection
import java.io.IOException
import java.time.LocalDateTime

private const val BUSY = "Another send is still in progress."
private const val TARGET_GONE = "The pane or connection changed. Paste again."
private const val NO_MEMORY = "Not enough memory to read the file."
private const val UNREADABLE = "Could not read the clipboard file."

/**
 * Sends [file] to [block] as Compose's attachment, with no text: the server types its path into
 * the pane, without Enter (#109). Main thread. The target is taken before the read, so a pane or
 * connection that changes while the file is read refuses the send instead of taking it elsewhere.
 * [HostConnection.uploading] covers the read too, when [HostConnection.composerRunning] is not
 * yet set, so a second Paste is refused from the first tap on. Failures go to the error notice.
 */
internal fun pasteClipboardFile(context: Context, conn: HostConnection, block: String, file: ClipboardFile) {
    if (conn.uploading != null || conn.composerRunning) {
        conn.error = BUSY
        return
    }
    val session = conn.state.session
    val epoch = session?.let { conn.composerTarget(it, block) } ?: 0L
    if (session == null || epoch == 0L) {
        conn.error = TARGET_GONE
        return
    }
    conn.uploading = "Uploading file…"
    val resolver = context.applicationContext.contentResolver
    val main = Handler(Looper.getMainLooper())
    val worker = Thread({
        // Only the read runs here; the result goes back to main, which owns the connection.
        val read: Result<ComposerAttachment> = try {
            Result.success(resolver.loadClipboardFile(file, LocalDateTime.now()))
        } catch (e: IOException) {
            Result.failure(e)
        } catch (_: OutOfMemoryError) {
            Result.failure(IOException(NO_MEMORY))
        } catch (_: Exception) {
            // A provider may throw what it likes; its own message is not one for the user.
            Result.failure(IOException(UNREADABLE))
        }
        main.post {
            read.fold(
                onSuccess = { attachment ->
                    conn.uploading = "Uploading ${attachment.name}…"
                    conn.sendComposer(session, block, "", attachment.name, attachment.bytes, epoch) { f ->
                        conn.uploading = null
                        if (f != null) conn.error = f.message
                    }
                },
                onFailure = { e ->
                    conn.uploading = null
                    conn.error = e.message ?: UNREADABLE
                },
            )
        }
    }, "clipboard-file")
    try {
        worker.start()
    } catch (_: OutOfMemoryError) {
        conn.uploading = null
        conn.error = NO_MEMORY
    }
}
