package io.github.yhzion.velo.ui

internal class TerminalReaderSnapshot(private val readViewport: () -> String) {
    var text: String = readViewport()
        private set

    /** Return true only if the view needs new text and can reset its reading cursor. */
    fun refresh(): Boolean {
        val latest = readViewport()
        if (latest == text) return false
        text = latest
        return true
    }
}
