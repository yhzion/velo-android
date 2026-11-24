package io.github.yhzion.velo.updates

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.security.MessageDigest
import java.util.zip.ZipFile
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val MAX_APK_BYTES = 256L * 1024 * 1024
private const val APK_MIME = "application/vnd.android.package-archive"
private val sha256Hex = Regex("[0-9a-f]{64}")

object UpdateInstaller {
    // Serializing writers also lets the next download safely remove a partial left by process death.
    private val writer = Mutex()

    suspend fun download(context: Context, release: AppRelease): File = withContext(Dispatchers.IO) {
        writer.withLock {
            withTimeoutOrNull(5 * 60_000L) {
                requireArm64Device()
                val target = updateTarget(context.cacheDir, release)
                val dir = requireNotNull(target.parentFile)
                check(dir.mkdir() || dir.isDirectory) { "Cannot create the update cache." }
                stageUpdate(target) { partial ->
                    fetchApk(release, partial) { ensureActive() }
                    ensureActive()
                    verifyArchive(context, partial, release)
                    ensureActive()
                }
                target
            } ?: throw IOException("The update download timed out.")
        }
    }

    /** Blocking validation: call off the UI thread after the user chooses to install. */
    @Suppress("DEPRECATION")
    fun launch(context: Context, file: File, release: AppRelease) {
        requireArm64Device()
        requireUpdatePath(context.cacheDir, file, release)
        file.inputStream().use { verifyStream(it, null, file.length(), MAX_APK_BYTES, release.sha256) {} }
        verifyArchive(context, file, release)

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(uri, APK_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pm = context.packageManager
        val handler = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY or PackageManager.MATCH_SYSTEM_ONLY)
            .firstOrNull { it.activityInfo.exported && pm.checkPermission("android.permission.INSTALL_PACKAGES",
                it.activityInfo.packageName) == PackageManager.PERMISSION_GRANTED }?.activityInfo
            ?: error("No system package installer is available.")
        intent.component = ComponentName(handler.packageName, handler.name)
        context.startActivity(intent)
    }
}

private fun requireArm64Device() {
    require("arm64-v8a" in Build.SUPPORTED_ABIS) { "This update requires an arm64 device." }
}

internal fun updateTarget(cache: File, release: AppRelease): File {
    require(release.apkUrl == ReleaseFeed.apkUrl(release.tag) && sha256Hex.matches(release.sha256)) {
        "Invalid update metadata."
    }
    val dir = File(cache.canonicalFile, "updates")
    require(dir == dir.canonicalFile) { "Invalid update cache directory." }
    // A later download cannot change the contents behind a previously granted installer URI.
    return File(dir, "${release.sha256}.apk")
}

internal fun requireUpdatePath(cache: File, file: File, release: AppRelease) {
    require(file == updateTarget(cache, release) && file == file.canonicalFile && file.isFile) {
        "The selected file is not the cached update."
    }
}

/** Must run under the single writer lock. PackageManager expects even the staging file to end in .apk. */
internal fun stageUpdate(target: File, writeAndVerify: (File) -> Unit) {
    val partial = File(requireNotNull(target.parentFile), "download.partial.apk")
    Files.deleteIfExists(partial.toPath())
    check(partial.createNewFile()) { "Cannot stage the update." }
    try {
        writeAndVerify(partial)
        Files.move(partial.toPath(), target.toPath(), ATOMIC_MOVE)
    } finally {
        Files.deleteIfExists(partial.toPath())
    }
}

internal fun assetRedirect(from: URI, location: String): URI {
    val next = from.resolve(location)
    require(next.scheme == "https" && next.rawUserInfo == null && (next.port == -1 || next.port == 443) &&
        next.rawFragment == null && (next.host == "release-assets.githubusercontent.com" ||
        next.host == "objects.githubusercontent.com")) { "Untrusted update redirect." }
    return next
}

private fun fetchApk(release: AppRelease, partial: File, checkActive: () -> Unit) {
    // updateTarget already checked the initial URL against the exact release and repository.
    var uri = URI(release.apkUrl)
    for (hop in 0..5) {
        checkActive()
        val connection = uri.toURL().openConnection() as HttpsURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Accept-Encoding", "identity")
            when (connection.responseCode) {
                301, 302, 303, 307, 308 -> uri = assetRedirect(uri, requireNotNull(connection.getHeaderField("Location")) {
                    "The update redirect has no destination."
                })
                200 -> {
                    val length = connection.contentLengthLong
                    connection.inputStream.use { input -> partial.outputStream().use { output ->
                        verifyStream(input, output, length, MAX_APK_BYTES, release.sha256, checkActive)
                    } }
                    return
                }
                else -> throw IOException("The update server did not return the APK.")
            }
        } finally {
            connection.disconnect()
        }
    }
    throw IOException("Too many update redirects.")
}

internal fun verifyStream(input: InputStream, output: OutputStream?, length: Long, limit: Long,
                         sha256: String, checkActive: () -> Unit) {
    require(length == -1L || length in 1..limit) { "Invalid update size." }
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        checkActive()
        val read = input.read(buffer)
        if (read == -1) break
        total += read
        require(total <= limit && (length < 0 || total <= length)) { "Update exceeds its size limit." }
        digest.update(buffer, 0, read)
        output?.write(buffer, 0, read)
    }
    checkActive()
    require(total > 0 && (length < 0 || total == length)) { "Incomplete update." }
    require(digest.digest().toHexString() == sha256) { "Update checksum does not match." }
}

internal data class UpdateIdentity(val name: String, val version: String?, val code: Long, val signers: Set<String>)

private fun PackageInfo.updateIdentity() = UpdateIdentity(packageName, versionName, longVersionCode,
    signingInfo?.apkContentsSigners?.mapTo(mutableSetOf()) { it.toCharsString() }.orEmpty())

@Suppress("DEPRECATION") // Int flags overloads support API 30–32 as well as newer Android versions.
private fun verifyArchive(context: Context, file: File, release: AppRelease) {
    val pm = context.packageManager
    val flags = PackageManager.GET_SIGNING_CERTIFICATES
    val archive = requireNotNull(pm.getPackageArchiveInfo(file.path, flags)) { "Invalid update APK." }
    val installed = pm.getPackageInfo(context.packageName, flags)
    requireUpdateIdentity(installed.updateIdentity(), archive.updateIdentity(), release)
    requireArm64Payload(file)
}

internal fun requireUpdateIdentity(installed: UpdateIdentity, apk: UpdateIdentity, release: AppRelease) {
    require(apk.name == installed.name) { "Update package does not match this app." }
    require(installed.signers.isNotEmpty() && apk.signers == installed.signers) { "Update signing certificates do not match." }
    require(apk.version == release.tag.removePrefix("v") && apk.code == release.versionCode && apk.code > installed.code) {
        "The APK is not the requested newer version."
    }
}

internal fun requireArm64Payload(file: File) {
    ZipFile(file).use { zip ->
        for (name in listOf("libvelocore.so", "libtailscale.so")) {
            val entry = zip.getEntry("lib/arm64-v8a/$name")
            require(entry != null && !entry.isDirectory && entry.size > 0) { "The update lacks required arm64 libraries." }
        }
    }
}
