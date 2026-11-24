package io.github.yhzion.velo.ui

import io.github.yhzion.velo.core.checkLocator
import io.github.yhzion.velo.core.locatorSentence

private val incompleteScheme = listOf("https://", "http://", "ssh://")

/** What the address field says while it is empty, on both remote registration screens. */
internal const val ADDRESS_PLACEHOLDER = "Type the address, ssh:// login, or socket path"

/**
 * A draft used by both remote registration screens: a typed address follows the core's locator
 * rules ([checkLocator]), an `ssh://user@host` login among them. Scans must pass validation
 * before replacing the text, and only an `https://` URL is taken from a QR code.
 */
internal data class RemoteAddressInput(val text: String = "", private val rejectedQr: Boolean = false) {
    private val check = checkLocator(text)
    val acceptedUrl = if (rejectedQr) null else check.url
    val problem = when {
        rejectedQr -> "Scan a QR code that holds an HTTPS address"
        incompleteScheme.any { text.trim().equals(it, ignoreCase = true) } -> null
        else -> locatorSentence(check)
    }

    fun scan(payloads: List<String>): RemoteAddressInput {
        val accepted = payloads.firstNotNullOfOrNull { acceptQr(it) } ?: return copy(rejectedQr = true)
        return RemoteAddressInput(accepted)
    }
}

private fun acceptQr(payload: String): String? =
    if (payload.trim().startsWith("https://", ignoreCase = true)) checkLocator(payload).url else null
