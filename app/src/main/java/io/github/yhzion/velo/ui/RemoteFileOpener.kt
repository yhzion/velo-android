package io.github.yhzion.velo.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import io.github.yhzion.velo.HostConnection
import java.io.File

/** A download that runs, for the dialog that shows it once it takes longer than [SHOW_AFTER_MS]. */
internal class FileDownload(val conn: HostConnection, val name: String, val size: Long?, internal val partial: File) {
    var received by mutableLongStateOf(0L)
    /** The dialog is up: the download took a while, and the person did not hide it. */
    var shown by mutableStateOf(false)
}

/** A file whose opening the person confirms first ([asksBeforeOpening]). */
internal class FileQuestion(val conn: HostConnection, val file: File, val type: String)

/**
 * Opens a file of the remote in the app this phone uses for its type, as desktop Rex does: the
 * terminal sends a copy (`download_begin`), the app keeps it in its cache with the version the
 * server gave, and a later tap on the same path opens that copy when the file has not changed.
 * One per process ([of]), so a download outlives the screen that started it. All methods are
 * main-thread only; one download runs per remote.
 */
internal class RemoteFileOpener private constructor(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val root = File(context.cacheDir, ROOT)

    /** The activity shown now, which starts the viewer; the workspace sets it while it is up. */
    var activity: Activity? = null

    var download by mutableStateOf<FileDownload?>(null)
        private set
    var question by mutableStateOf<FileQuestion?>(null)

    /**
     * Opens the file at [target] (a path or a `file:` URL) in the terminal of [block]. [notAFile]
     * runs when the remote has no file there, such as for a word that only looks like a path.
     */
    fun open(conn: HostConnection, block: String, target: String, notAFile: () -> Unit) {
        val path = remoteFilePath(target, remoteNames(conn.url)) ?: return notAFile()
        val generation = conn.startDownload() ?: run {
            // The tap stays the pane's: the word may be no file at all.
            notAFile()
            conn.error = "Another file is still downloading."
            return
        }
        val remote = conn.url
        Log.i(TAG, "asked for a file")
        val worker = Thread({
            var outcome: Outcome = Outcome.Failed("the copy could not be saved")
            try {
                outcome = fetch(conn, remote, block, path, generation)
            } catch (e: Throwable) {
                Log.w(TAG, "the download failed", e)
            } finally {
                // However the thread ends, the remote may start another download and free its core.
                main.post {
                    conn.endDownload()
                    finish(conn, outcome, notAFile)
                }
            }
        }, "remote-file")
        try {
            worker.start()
        } catch (_: OutOfMemoryError) {
            conn.endDownload()
            notAFile()
            conn.error = "Not enough memory to download the file."
        }
    }

    /** Ends the download that [download] shows. */
    fun stop() {
        download?.conn?.cancelDownload()
    }

    /** Hides the download's dialog; the file still opens once it is here. */
    fun hide() {
        download?.shown = false
    }

    /** Opens [file], whose opening the person confirmed. */
    fun answer(q: FileQuestion) {
        question = null
        view(q.conn, q.file, q.type)
    }

    private sealed interface Outcome {
        data class Open(val file: File) : Outcome
        data object NoFile : Outcome
        data object Canceled : Outcome
        data class Failed(val why: String) : Outcome
    }

    /** On the download's thread. */
    private fun fetch(conn: HostConnection, remote: String, block: String, path: String, generation: Long): Outcome {
        val known = if (rememberable(path)) synchronized(cacheLock) { cached(remote, path) } else null
        val answer = conn.downloadBegin(block, path, known?.version, generation)
        when {
            answer.isNoFile -> return Outcome.NoFile
            answer.status == "not_modified" -> {
                Log.i(TAG, "the copy is current")
                return known?.let { Outcome.Open(it.file) } ?: Outcome.Failed("the app's copy is gone. Tap the path again")
            }
            answer.status == "error" && answer.code == "Canceled" -> return Outcome.Canceled
            answer.status != "ok" || answer.ticket == null -> return Outcome.Failed(downloadFailure(answer))
        }
        val absolute = answer.path ?: path
        val version = answer.version
        if (version != null) synchronized(cacheLock) { cached(remote, absolute) }?.takeIf { it.version == version }?.let {
            // The copy here is current. An unused ticket would keep the file open on the server.
            val released = conn.downloadRelease(answer.ticket, generation)
            Log.i(TAG, "opened the copy kept for this version" + if (released) "" else "; its stream was not ended")
            return Outcome.Open(it.file)
        }
        val name = safeFileName(answer.name)
        root.mkdirs()
        // One download runs at a time per remote, but several remotes may run at once.
        val partial = File(root, "partial-${System.nanoTime()}-${Thread.currentThread().id}")
        val shown = FileDownload(conn, name, answer.size, partial)
        main.post { follow(shown) }
        val size = try {
            ParcelFileDescriptor.open(partial, ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE)
                .use { conn.downloadFetch(answer.ticket, it.fd, answer.size, generation) }
        } catch (e: Exception) {
            partial.delete()
            throw e
        }
        if (size < 0) {
            partial.delete()
            Log.i(TAG, when (size) {
                FETCH_STOPPED -> "the download was stopped"
                FETCH_TOO_LARGE -> "the file is larger than announced, or than the limit"
                else -> "the download ended before the file did"
            })
            return if (size == FETCH_STOPPED) Outcome.Canceled else Outcome.Failed(fetchFailure(size))
        }
        Log.i(TAG, "downloaded $size bytes")
        return synchronized(cacheLock) {
            // A copy without a version is not kept for later: the file was changing, or the server can't tell.
            val dir = if (version != null) File(root, cacheKey(remote, absolute)) else File(root, "once-${System.nanoTime()}")
            dir.deleteRecursively()
            val file = File(File(dir, FILE), name)
            file.parentFile?.mkdirs()
            if (!partial.renameTo(file)) {
                partial.delete()
                return@synchronized Outcome.Failed("the copy could not be saved")
            }
            if (version != null) File(dir, VERSION).writeText(version)
            prune(keep = dir)
            Outcome.Open(file)
        }
    }

    private class Cached(val version: String, val file: File)

    /** The copy kept for [path] of [remote], if any. Under [cacheLock]. */
    private fun cached(remote: String, path: String): Cached? {
        val dir = File(root, cacheKey(remote, path))
        val version = runCatching { File(dir, VERSION).readText() }.getOrNull() ?: return null
        val file = File(dir, FILE).listFiles()?.singleOrNull()?.takeIf { it.isFile } ?: return null
        dir.setLastModified(System.currentTimeMillis())
        return Cached(version, file)
    }

    /** Drops the copies used longest ago until the cache holds [CACHE_BYTES] or less. Under [cacheLock]. */
    private fun prune(keep: File) {
        // What a download left when the app died during it.
        val stale = System.currentTimeMillis() - STALE_MS
        root.listFiles { f -> f.isFile && f.name.startsWith("partial-") && f.lastModified() < stale }?.forEach { it.delete() }
        val dirs = root.listFiles { f -> f.isDirectory && f != keep }?.sortedBy { it.lastModified() } ?: return
        var total = dirs.sumOf { size(it) } + size(keep)
        for (dir in dirs) {
            if (total <= CACHE_BYTES) break
            total -= size(dir)
            dir.deleteRecursively()
        }
    }

    private fun size(dir: File): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** Shows [d] after [SHOW_AFTER_MS] and counts its bytes while it runs. */
    private fun follow(d: FileDownload) {
        // Posted before the download's end, so this runs while it is still the latest.
        download = d
        val tick = object : Runnable {
            var waited = 0L
            override fun run() {
                if (download !== d) return
                d.received = d.partial.length()
                waited += TICK_MS
                if (waited == SHOW_AFTER_MS) d.shown = true
                main.postDelayed(this, TICK_MS)
            }
        }
        main.postDelayed(tick, TICK_MS)
    }

    private fun finish(conn: HostConnection, outcome: Outcome, notAFile: () -> Unit) {
        // Another remote's download may be the one shown now.
        if (download?.conn === conn) download = null
        when (outcome) {
            is Outcome.Open -> {
                val type = typeOf(outcome.file)
                if (asksBeforeOpening(outcome.file.name)) question = FileQuestion(conn, outcome.file, type)
                else view(conn, outcome.file, type)
            }
            Outcome.NoFile -> notAFile()
            Outcome.Canceled -> Unit
            is Outcome.Failed -> {
                // No file opened, so the tap was the pane's after all; the notice says why.
                notAFile()
                conn.error = "Could not open the file: ${outcome.why}."
            }
        }
    }

    private fun typeOf(file: File): String {
        val ext = file.name.substringAfterLast('.', "").lowercase()
        return fileType(file.name, MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)) {
            val head = ByteArray(HEAD_BYTES)
            val n = runCatching { file.inputStream().use { it.read(head) } }.getOrDefault(-1)
            if (n > 0) head.copyOf(n) else ByteArray(0)
        }
    }

    /** Opens [file] in the app for [type]; with none, the share sheet offers the apps that take it. */
    private fun view(conn: HostConnection, file: File, type: String) {
        // The application's context starts the viewer in a task of its own: no screen of Velo is up.
        val context = activity ?: context
        val uri = try {
            FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "the copy is outside the shared folder", e)
            conn.error = "Could not open the file."
            return
        }
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (context !is android.app.Activity) view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(view)
            Log.i(TAG, "opened a $type file")
            return
        } catch (_: ActivityNotFoundException) {
            Log.i(TAG, "no app views $type files; sharing instead")
        } catch (e: SecurityException) {
            Log.w(TAG, "the $type file was refused", e)
            conn.error = "This phone did not let Velo open the file."
            return
        }
        val send = Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // The chooser passes the grant on only for a URI in the clip.
        send.clipData = ClipData.newRawUri(file.name, uri)
        val chooser = Intent.createChooser(send, file.name)
        if (context !is android.app.Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(chooser)
        } catch (_: ActivityNotFoundException) {
            conn.error = "No app on this phone opens ${file.name}."
        }
    }

    companion object {
        private const val TAG = "velo.file"
        /** Under the cache folder, and in `update_paths.xml` for the FileProvider. */
        const val ROOT = "remote-files"
        private const val FILE = "file"
        private const val VERSION = "version"
        private const val CACHE_BYTES = 256L * 1024 * 1024
        private const val HEAD_BYTES = 512
        private const val TICK_MS = 250L
        private const val STALE_MS = 60L * 60 * 1000
        const val SHOW_AFTER_MS = 1000L
        private val cacheLock = Any()
        private var instance: RemoteFileOpener? = null

        /** The process's opener. Main thread. */
        fun of(context: Context): RemoteFileOpener =
            instance ?: RemoteFileOpener(context.applicationContext).also { instance = it }
    }
}
