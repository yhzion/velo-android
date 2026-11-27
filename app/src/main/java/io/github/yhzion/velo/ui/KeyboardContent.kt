package io.github.yhzion.velo.ui

import android.content.ClipDescription
import android.content.ContentResolver
import android.net.Uri
import android.view.inputmethod.EditorInfo

/**
 * The image types a keyboard may insert into a pane (#160), through `commitContent`: an image
 * from Gboard's clipboard strip, a GIF or a sticker. A keyboard offers only what the editor declares.
 */
private val KEYBOARD_CONTENT_TYPES = arrayOf(
    // Any image: keyboards that compare with ClipDescription.compareMimeTypes, as Gboard does, take the wildcard.
    "image/*",
    // The rest for keyboards that look for an exact type in the list instead of matching the wildcard:
    // an image copied in a browser or a screenshot,
    "image/png",
    // a GIF from the keyboard's GIF search,
    "image/gif",
    // a photo,
    "image/jpeg",
    // and a sticker, which keyboards often send as WebP.
    "image/webp",
)

/** Declares the image types a keyboard may insert ([KEYBOARD_CONTENT_TYPES]). */
internal fun declareKeyboardContent(outAttrs: EditorInfo) {
    outAttrs.contentMimeTypes = KEYBOARD_CONTENT_TYPES.copyOf()
}

/**
 * A keyboard's content is pasted only from a `content:` URI, and only while a pane is bound: anything
 * else sends nothing. (Android already enforces the scheme; this is a guard.)
 */
internal fun keyboardContentAccepted(uriScheme: String?, bound: Boolean): Boolean =
    bound && uriScheme == ContentResolver.SCHEME_CONTENT

/** The keyboard's content as a clipboard file (#109): the description's first type, when it names one. */
internal fun keyboardContentFile(uri: Uri, description: ClipDescription): ClipboardFile =
    ClipboardFile(uri, description.takeIf { it.mimeTypeCount > 0 }?.getMimeType(0)?.takeIf(::namesAType))
