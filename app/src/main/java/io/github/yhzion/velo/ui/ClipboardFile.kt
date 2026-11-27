package io.github.yhzion.velo.ui

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

private const val UNREADABLE = "Could not read the clipboard file."
private const val REFUSED = "Velo may not read this clipboard file."
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

/** The primary clip's first item when it is a file, or null for a text clip or an empty clipboard. */
internal fun clipboardFile(context: Context): ClipboardFile? {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
    val clip = cm.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    val item = clip.getItemAt(0)
    val uri = item.uri ?: return null
    if (!clipItemIsFile(item.text, uri.scheme)) return null
    val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        ?: clip.description?.takeIf { it.mimeTypeCount > 0 }?.getMimeType(0)
    return ClipboardFile(uri, mime)
}

/**
 * Reads [file] (8 MiB at most) on the calling thread. A provider without a name or size is not
 * an error. Every failure is an [IOException] whose message the user can read.
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
        val extension = file.mime?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
        val name = clipboardFileName(displayName, extension, now)
        val bytes = openInputStream(file.uri)?.use { readComposerBytes(it, size) } ?: throw IOException(UNREADABLE)
        return ComposerAttachment(name, bytes)
    } catch (_: SecurityException) {
        throw IOException(REFUSED)
    } catch (_: FileNotFoundException) {
        throw IOException(REFUSED)
    }
}
