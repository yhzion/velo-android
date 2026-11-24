package io.github.yhzion.velo

import android.os.Build
import android.os.Process
import android.util.Log
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import kotlin.concurrent.thread

/**
 * The CAs Android trusts, as PEM files the native core can read ([CoreEnv.caDirs]): the system
 * CAs and the ones the user installed in Settings, read through the `AndroidCAStore` key store.
 * The core cannot read them where Android keeps them: a phone may refuse the app the user CA
 * directory (`/data/misc/user/0/cacerts-added`), and Android writes the certificates there as DER.
 * The store also leaves out a system CA the user turned off in Settings, and on Android 14 and
 * later it reads the updatable system CAs (`/apex/com.android.conscrypt/cacerts`) rather than
 * `/system/etc/security/cacerts` (#237). [export] rewrites one app-private directory on a start
 * that finds the files the store reads changed, so a CA the user removed or turned off stops being
 * trusted too. Reading the store takes 100-200 ms, so it runs on a thread of its own ([start],
 * #253), and only when those files changed ([LastExport], #338).
 */
object TrustedCas {
    private const val TAG = "TrustedCas"
    private const val USER_PREFIX = "user:"
    private const val SYSTEM_PREFIX = "system:"
    /** Where the system CAs are when the store cannot be read. */
    private const val SYSTEM_DIR = "/system/etc/security/cacerts"
    /** Where the store reads the system CAs from on Android 14 and later: the updatable conscrypt module. */
    private const val APEX_DIR = "/apex/com.android.conscrypt/cacerts"
    /** The conscrypt module's manifest, with its version: the store's code comes with the module. */
    private const val APEX_MANIFEST = "/apex/com.android.conscrypt/apex_manifest.pb"
    /** What [fileName] replaces in an alias. */
    private val UNSAFE = Regex("[^A-Za-z0-9._-]")
    /** The uids of one Android user (`UserHandle.PER_USER_RANGE`). */
    private const val PER_USER_RANGE = 100_000
    /** What [fileName] puts before the name of a system CA. */
    private const val SYSTEM_FILE_PREFIX = "system-"
    /** How long the app waits for the store before the system directory stands in ([start]). */
    const val DEADLINE_MS = 5_000L

    /**
     * The directories the core trusts: [link], which [export] points at the system directory only
     * when the store cannot stand in for it, and [dir], where [export] puts every CA of the store.
     * The link comes first, as the system directory did: the core keeps the first certificate of
     * a subject. Both paths are known before the export runs, so the core starts at once. It
     * reads them each time it opens a connection, and the app opens none before the export ends
     * ([Ready]): the core never reads a directory being written.
     */
    fun coreDirs(dir: File, link: File): Array<String> = arrayOf(link.path, dir.path)

    /**
     * Run [export] on a thread of its own; what it returns opens when the export ends, failed
     * or not, on [post]'s thread. [note] gets the result for the log.
     *
     * A store that does not answer in [deadlineMs] must not keep the app from connecting: the
     * system directory then stands in, as for a store that cannot be read ([standIn]), and the
     * way opens. When the store answers after all, its CAs replace the stand-in for the next
     * connection ([Exported.late]). That write adds the store's files to an empty [dir] first and
     * removes [link] last, so a connection opened meanwhile trusts no CA outside the stand-in and
     * the store together.
     */
    fun start(
        dir: File,
        link: File,
        post: (Runnable) -> Unit,
        system: File = File(SYSTEM_DIR),
        note: (Exported) -> Unit = ::log,
        deadlineMs: Long = DEADLINE_MS,
        last: LastExport? = null,
        read: () -> Map<String, ByteArray> = ::installed,
    ): Ready {
        val ready = Ready(post)
        val gate = Gate()
        val exporting = thread(name = TAG) {
            try {
                note(export(dir, link, system, gate, last, read))
            } finally {
                ready.open()
            }
        }
        thread(name = "$TAG deadline", isDaemon = true) {
            exporting.join(deadlineMs)
            val stoodIn = synchronized(gate) {
                if (gate.wrote) null else standIn(dir, link, system, deadlineMs, last).also { gate.stoodIn = true }
            }
            if (stoodIn != null) {
                try {
                    note(stoodIn)
                } finally {
                    ready.open()
                }
            }
        }
        return ready
    }

    /**
     * Who claimed the directories, the export (also when it leaves them as they are) or the deadline ([start]);
     * read and set under its lock.
     */
    class Gate {
        var wrote = false
        var stoodIn = false
    }

    /**
     * Opens once, when the export ends. Until then [whenReady] keeps its actions; [open] runs
     * them on [post]'s thread (the main thread in the app), and later ones run at once.
     */
    class Ready(private val post: (Runnable) -> Unit) {
        private var ended = false
        private val waiting = ArrayList<() -> Unit>()

        fun whenReady(action: () -> Unit) {
            val now = synchronized(this) {
                if (!ended) waiting += action
                ended
            }
            if (now) action()
        }

        fun open() = post(Runnable {
            val actions = synchronized(this) {
                ended = true
                waiting.toList().also { waiting.clear() }
            }
            actions.forEach { it() }
        })

        companion object {
            /** Open from the start: for directories nothing writes. */
            val opened = Ready { it.run() }.apply { open() }
        }
    }

    /** What [export] did: how many CAs it read, what the core then trusts, and how long each part took. */
    class Exported(
        val count: Int?,
        val dirs: List<String>,
        val storeMs: Long,
        val pemMs: Long,
        val filesMs: Long,
        val problems: List<Pair<String, Exception?>>,
        /** Written after the deadline had the system directory stand in. */
        val late: Boolean = false,
        /** Left as the last export wrote it: the files the store reads had not changed ([LastExport]). */
        val unchanged: Boolean = false,
        /** How long reading those files took, before and after the store. */
        val checkMs: Long = 0,
        /** Files beside the CAs that could not be read, and so counted as such ([Sources.unread]). */
        val unread: List<String> = emptyList(),
    )

    /** SHA-256 of texts and byte arrays, each closed so that no two sequences of them run together. */
    private class Digest {
        private val sha = MessageDigest.getInstance("SHA-256")

        fun text(text: String) {
            sha.update(text.toByteArray())
            sha.update(0)
        }

        fun bytes(bytes: ByteArray) {
            text(bytes.size.toString())
            sha.update(bytes)
        }

        fun hex(): String = sha.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * What the store's CAs rest on, read without the store (#338): a digest of [Sources.read]'s files and context,
     * the names [export] gives the CAs those files hold ([fileNames]), and the side files that could not be read
     * ([unread]).
     */
    class Sources(val digest: String, val fileNames: Set<String>, val unread: List<String> = emptyList()) {
        companion object {
            /**
             * Read the files a store reads its CAs from: the system CAs in any of [system], the user's CAs in
             * [added], and the system CAs the user turned off in [removed], with [files] and [context] beside them
             * (what the store's answer also rests on). A directory or side file that does not exist counts as such;
             * a side file that cannot be read counts as such too. A CA directory that cannot be listed, or a CA file
             * that cannot be read, throws, and the store is read instead.
             *
             * The digest covers the bytes, not the names, lengths and times alone: a user CA replaced by another
             * with the same subject keeps its file name, and may keep its length and time; the system images give
             * every file the same fixed time. Reading about 300 files of 3 KB costs tens of milliseconds, against
             * the store's hundreds: the store parses each certificate twice (`aliases` checks each one).
             */
            fun read(system: List<File>, added: File, removed: File, files: List<File>, context: List<String>): Sources {
                val digest = Digest()
                val names = HashSet<String>()
                val unread = ArrayList<String>()
                fun put(dir: File, alias: String?) {
                    digest.text(dir.path)
                    if (!dir.exists()) return digest.text("none")
                    val listed = dir.listFiles() ?: throw IOException("cannot list $dir")
                    for (file in listed.sortedBy { it.name }) {
                        digest.text(file.name)
                        if (!file.isFile) continue
                        digest.bytes(file.readBytes())
                        if (alias != null) names += fileName(alias + file.name)
                    }
                }
                context.forEach(digest::text)
                for (file in files) {
                    digest.text(file.path)
                    val bytes = try {
                        file.readBytes()
                    } catch (e: IOException) {
                        null
                    }
                    when {
                        bytes != null -> digest.bytes(bytes)
                        !file.exists() -> digest.text("none")
                        else -> {
                            digest.text("unread")
                            unread += file.path
                        }
                    }
                }
                system.forEach { put(it, SYSTEM_PREFIX) }
                put(added, USER_PREFIX)
                put(removed, null)
                return Sources(digest.hex(), names, unread)
            }
        }
    }

    /**
     * Where [export] keeps what the store's CAs rested on when it last wrote them ([file], beside the export, with a
     * digest of what it wrote), and how to read that now ([sources]). An export that finds both as they were leaves
     * the directories as they are, and does not read the store (#338).
     */
    class LastExport(val file: File, val sources: () -> Sources)

    /** [LastExport.sources] for this app: the files `AndroidCAStore` reads, for this Android user. [app] is the APK's path. */
    fun storeSources(app: String): Sources {
        // ActivityThread points the store at Environment.getUserConfigDirectory(userId) for the user's CAs.
        val user = File("/data/misc/user/${Process.myUid() / PER_USER_RANGE}")
        return Sources.read(
            // Both: the store reads the APEX's when it has CAs, but a property can send it to the system image's.
            system = listOf(File(APEX_DIR), File(SYSTEM_DIR)),
            added = File(user, "cacerts-added"),
            removed = File(user, "cacerts-removed"),
            files = listOf(File(APEX_MANIFEST)),
            // The OS build, the store's choice of directory, and this app (its export format), each once changed.
            context = listOf(Build.FINGERPRINT, System.getProperty("system.certs.enabled").orEmpty(), app),
        )
    }

    /**
     * Make [dir] hold every CA of the store and [link] point at [system] when the store cannot
     * stand in for it ([chooseDirs]): the core then trusts what [chooseDirs] says, through the
     * two fixed paths of [coreDirs].
     *
     * With [last], it first reads the files the store reads: when they, and what it left in [dir], are as it last
     * recorded, it leaves the directories as they are and does not read the store (#338). It records them only when
     * they were the same after the store was read, and every CA the store gave is one of them.
     */
    fun export(
        dir: File,
        link: File,
        system: File?,
        gate: Gate = Gate(),
        last: LastExport? = null,
        read: () -> Map<String, ByteArray>,
    ): Exported {
        val problems = ArrayList<Pair<String, Exception?>>()
        val started = System.nanoTime()
        val sources = last?.let {
            try {
                it.sources()
            } catch (e: Exception) {
                problems += "CA files unreadable: the store is read" to e
                null
            }
        }
        // What the last export left, read back with the link (only a record of what was written is compared to it).
        val left = if (last != null && sources != null) exportDigest(dir, link) else null
        val checked = System.nanoTime()
        if (last != null && sources != null) {
            val kept = synchronized(gate) {
                // Not over a stand-in: the deadline wrote the directories after the last export.
                (!gate.stoodIn && left != null && recorded(last.file) == listOf(sources.digest, left.second))
                    .also { if (it) gate.wrote = true }
            }
            if (kept) {
                return Exported(left!!.first, listOfNotNull(linked(link), dir.path), 0, 0, 0, problems, unchanged = true,
                    checkMs = ms(started, checked), unread = sources.unread)
            }
        }
        val certs = try {
            read()
        } catch (e: Exception) {
            problems += "trusted CAs unreadable" to e
            null
        }
        val readAt = System.nanoTime()
        // The files again: what changed while the store was read must not be recorded as the files before it.
        val after = if (last != null && sources != null && certs != null) runCatching { last.sources().digest } else null
        val rechecked = System.nanoTime()
        val pems = certs.orEmpty().mapValues { pem(it.value) }
        val encodedAt = System.nanoTime()
        val (dirs, late) = synchronized(gate) {
            gate.wrote = true
            last?.file?.delete()
            val dirs = trust(dir, link, system, certs?.keys, pems, problems)
            if (last != null && sources != null && certs != null && problems.isEmpty()) {
                val outside = certs.keys - sources.fileNames
                when {
                    // A CA the files checked do not hold could change unseen: such a store is read on every start.
                    outside.isNotEmpty() ->
                        problems += "${outside.size} CAs outside the files checked (${outside.take(3)}): the store is read on every start" to null
                    after?.isFailure == true ->
                        problems += "CA files unreadable after the store was read: it is read again next start" to after.exceptionOrNull() as? Exception
                    after?.getOrNull() != sources.digest ->
                        problems += "the CA files changed while the store was read: it is read again next start" to null
                    else -> record(last.file, listOf(sources.digest, exportDigest(pems.mapValues { it.value.toByteArray() }, linked(link))), problems)
                }
            }
            dirs to gate.stoodIn
        }
        val done = System.nanoTime()
        return Exported(certs?.size, dirs, ms(checked, readAt), ms(rechecked, encodedAt), ms(encodedAt, done), problems, late,
            checkMs = ms(started, checked) + ms(readAt, rechecked), unread = sources?.unread.orEmpty())
    }

    /** What [record] wrote, line by line: the sources' digest and the export's. Null when there is none or it cannot be read. */
    private fun recorded(file: File): List<String>? = try {
        file.readLines()
    } catch (e: IOException) {
        null
    }

    private fun record(file: File, lines: List<String>, problems: MutableList<Pair<String, Exception?>>) {
        try {
            file.writeText(lines.joinToString("\n"))
        } catch (e: IOException) {
            problems += "the export's sources not recorded at $file" to e
        }
    }

    /** Where [link] points, or null when there is no link ([point]). */
    private fun linked(link: File): String? =
        link.toPath().takeIf { Files.isSymbolicLink(it) }?.let { Files.readSymbolicLink(it).toString() }

    /** A digest of [files] (name → content) as [write] leaves them in a directory, and of where the link points. */
    private fun exportDigest(files: Map<String, ByteArray>, linked: String?): String {
        val digest = Digest()
        digest.text(linked ?: "none")
        for ((name, bytes) in files.toSortedMap()) {
            digest.text(name)
            digest.bytes(bytes)
        }
        return digest.hex()
    }

    /**
     * How many files [dir] holds and their [exportDigest] with [link]: what an export left, read back, so that a file
     * lost or cut short since (a crash right after a write) is written again, as when every start rewrote it.
     * Null when it cannot be read.
     */
    private fun exportDigest(dir: File, link: File): Pair<Int, String>? = try {
        val files = (dir.listFiles() ?: return null).associate { it.name to it.readBytes() }
        files.size to exportDigest(files, linked(link))
    } catch (e: IOException) {
        null
    }

    /** What the deadline writes: the system directory alone, as for a store that cannot be read. */
    private fun standIn(dir: File, link: File, system: File?, deadlineMs: Long, last: LastExport?): Exported {
        val problems = arrayListOf<Pair<String, Exception?>>(
            "the store did not answer in $deadlineMs ms: the system directory stands in" to null,
        )
        last?.file?.delete()
        return Exported(null, trust(dir, link, system, null, emptyMap(), problems), deadlineMs, 0, 0, problems)
    }

    /**
     * Make [dir] hold [pems] and [link] point at [system] when [chooseDirs] wants the system
     * directory ([stored]: the store's file names, null when it could not be read).
     */
    private fun trust(
        dir: File,
        link: File,
        system: File?,
        stored: Collection<String>?,
        pems: Map<String, String>,
        problems: MutableList<Pair<String, Exception?>>,
    ): List<String> {
        val exported = try {
            write(dir, pems)
            dir.path
        } catch (e: Exception) {
            problems += "trusted CAs not written to $dir" to e
            // As before, the system directory alone then: not a directory written halfway.
            val left = dir.listFiles().orEmpty().filterNot { it.deleteRecursively() }
            if (left.isNotEmpty()) problems += "${left.size} files left in $dir" to null
            null
        }
        val systemDir = system?.takeIf { it.isDirectory }?.path
        val dirs = chooseDirs(stored, exported, systemDir)
        try {
            point(link, systemDir?.takeIf { it in dirs })
        } catch (e: Exception) {
            problems += "system CA link not made at $link" to e
        }
        return dirs
    }

    private fun ms(from: Long, to: Long) = (to - from) / 1_000_000

    private fun log(e: Exported) {
        for ((what, error) in e.problems) Log.w(TAG, what, error)
        val unread = if (e.unread.isEmpty()) "" else "; not read: ${e.unread}"
        if (e.unchanged) {
            Log.i(TAG, "${e.count} CAs as exported before: their files checked in ${e.checkMs} ms off the main thread; trusting ${e.dirs}$unread")
            return
        }
        Log.i(TAG, "${e.count ?: "no"} CAs exported in ${e.storeMs + e.pemMs + e.filesMs} ms off the main thread " +
            "(check ${e.checkMs} ms, store ${e.storeMs} ms, PEM ${e.pemMs} ms, files ${e.filesMs} ms)${if (e.late) " after the deadline" else ""}; " +
            "trusting ${e.dirs}$unread")
    }

    /** Make [link] a symbolic link to [target], or no file at all when [target] is null. */
    fun point(link: File, target: String?) {
        val path = link.toPath()
        val isLink = Files.isSymbolicLink(path)
        if (isLink && target != null && Files.readSymbolicLink(path).toString() == target) return
        if (isLink) Files.delete(path) else if (link.exists() && !link.deleteRecursively()) throw IOException("cannot remove $link")
        if (target != null) Files.createSymbolicLink(path, File(target).toPath())
    }

    /**
     * [exported] alone when the store was read and written there ([stored]: the file names
     * written) and holds system CAs; otherwise the [system] directory, and [exported] beside it.
     */
    fun chooseDirs(stored: Collection<String>?, exported: String?, system: String?): List<String> =
        if (exported != null && stored.orEmpty().any { it.startsWith(SYSTEM_FILE_PREFIX) }) {
            listOf(exported)
        } else {
            listOfNotNull(system, exported)
        }

    /** The CAs of `AndroidCAStore`, system (`system:…`) and user (`user:…`), as DER, keyed by [fileName]. */
    private fun installed(): Map<String, ByteArray> {
        val started = System.nanoTime()
        val store = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
        val loaded = System.nanoTime()
        val aliases = store.aliases().toList().filter { it.startsWith(USER_PREFIX) || it.startsWith(SYSTEM_PREFIX) }
        val listed = System.nanoTime()
        val certs = aliases.mapNotNull { alias -> store.getCertificate(alias)?.let { fileName(alias) to it.encoded } }.toMap()
        Log.i(TAG, "store loaded in ${ms(started, loaded)} ms, ${aliases.size} aliases listed in ${ms(loaded, listed)} ms, " +
            "certificates read in ${ms(listed, System.nanoTime())} ms")
        return certs
    }

    /**
     * `user:cf4d12bf.0` → `cf4d12bf.0.pem`, `system:cf4d12bf.0` → `system-cf4d12bf.0.pem`: a user CA
     * may have a system CA's hash. Anything but letters, digits, `.`, `-` and `_` becomes `_`.
     */
    fun fileName(alias: String): String {
        val system = alias.startsWith(SYSTEM_PREFIX)
        val name = alias.removePrefix(USER_PREFIX).removePrefix(SYSTEM_PREFIX).replace(UNSAFE, "_")
        return if (system) "$SYSTEM_FILE_PREFIX$name.pem" else name.ifEmpty { "_" } + ".pem"
    }

    /** A DER certificate as PEM text: base64 in 64-character lines between the markers. */
    fun pem(der: ByteArray): String =
        "-----BEGIN CERTIFICATE-----\n" +
            Base64.getMimeEncoder(64, byteArrayOf('\n'.code.toByte())).encodeToString(der) +
            "\n-----END CERTIFICATE-----\n"

    /** Make [dir] hold exactly [certs] (file name → DER) as PEM files; every other file goes. */
    fun sync(dir: File, certs: Map<String, ByteArray>) = write(dir, certs.mapValues { pem(it.value) })

    /** Make [dir] hold exactly [wanted] (file name → text); every other file goes. */
    private fun write(dir: File, wanted: Map<String, String>) {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create $dir")
        for (file in dir.listFiles().orEmpty()) {
            if (file.name !in wanted && !file.deleteRecursively()) throw IOException("cannot remove $file")
        }
        for ((name, text) in wanted) {
            val file = File(dir, name)
            if (!file.isFile || file.readText() != text) file.writeText(text)
        }
    }
}
