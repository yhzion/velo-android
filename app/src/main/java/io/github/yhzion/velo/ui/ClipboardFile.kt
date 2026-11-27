package io.github.yhzion.velo.ui

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.FileNotFoundException
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** A file that another app put on the clipboard: the primary clip's first item (#107). */
internal class ClipboardFile(val uri: Uri, val mime: String?)

internal const val UNREADABLE = "Could not read the clipboard file."
private const val REFUSED = "Velo may not read this clipboard file."
private const val GONE = "The clipboard file is no longer available."
private val GENERATED_NAME = DateTimeFormatter.ofPattern("'clipboard-'yyyyMMdd-HHmmss")

/**
 * A clip item is a file when it has a `content:` URI and no text. A browser's copied link or
 * rich text carries both, and its text wins: it pastes as text.
 */
internal fun clipItemIsFile(text: CharSequence?, uriScheme: String?): Boolean =
    uriScheme == ContentResolver.SCHEME_CONTENT && text.isNullOrEmpty()

/** The provider's name when the composer accepts it; otherwise `clipboard-yyyyMMdd-HHmmss` and the MIME type's extension. */
internal fun clipboardFileName(displayName: String?, extension: String?, now: LocalDateTime): String {
    if (usableAttachmentName(displayName)) return displayName!!
    val base = GENERATED_NAME.format(now)
    return if (extension.isNullOrEmpty()) base else "$base.$extension"
}

/**
 * The primary clip's first item when it is a file, or null for a text clip or an empty clipboard.
 * Main thread, with no call to the source app's provider: the MIME type is the one the clip's
 * description holds (`ClipData.newUri` put the provider's there), and [loadClipboardFile] asks the
 * provider on the worker when it holds none.
 */
internal fun clipboardFile(context: Context): ClipboardFile? {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
    val clip = cm.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    val item = clip.getItemAt(0)
    val uri = item.uri ?: return null
    if (!clipItemIsFile(item.text, uri.scheme)) return null
    val mime = clip.description?.takeIf { it.mimeTypeCount > 0 }?.getMimeType(0)?.takeIf(::namesAType)
    return ClipboardFile(uri, mime)
}

/** text/uri-list is what `ClipData.newUri` writes when the provider gave no type; a wildcard names none either. */
private fun namesAType(mime: String): Boolean = mime != ClipDescription.MIMETYPE_TEXT_URILIST && '*' !in mime

/**
 * Reads [file] (8 MiB at most) on the calling thread, a worker: the provider may take long to
 * answer. A provider without a name, size or type is not an error. Every failure except
 * [OutOfMemoryError] is an [IOException] whose message the user can read: a provider's own
 * message or exception becomes [UNREADABLE].
 */
internal fun ContentResolver.loadClipboardFile(file: ClipboardFile, now: LocalDateTime): ComposerAttachment {
    try {
        val columns = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        var displayName: String? = null
        var size: Long? = null
        query(file.uri, columns, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameColumn >= 0 && !cursor.isNull(nameColumn)) displayName = cursor.getString(nameColumn)
                val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) size = cursor.getLong(sizeColumn)
            }
        }
        val mime = file.mime ?: getType(file.uri)
        val extension = mime?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
        val name = clipboardFileName(displayName, extension, now)
        val bytes = openInputStream(file.uri)?.use { readComposerBytes(it, size) } ?: throw IOException(UNREADABLE)
        return ComposerAttachment(name, bytes)
    } catch (_: SecurityException) {
        throw IOException(REFUSED)
    } catch (_: FileNotFoundException) {
        // The source app cleared its copy, or the URI names a folder.
        throw IOException(GONE)
    } catch (e: IOException) {
        throw if (e.message == FILE_TOO_LARGE || e.message == PROVIDER_STALLED) e else IOException(UNREADABLE)
    } catch (_: RuntimeException) {
        throw IOException(UNREADABLE)
    }
}
