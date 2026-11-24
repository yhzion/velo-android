package io.github.yhzion.velo

/**
 * The text scale of each pane (pinch, palette), 1 until it is changed, and the panes whose
 * scale a pinch is still changing. [send] hands a scale to the core: `live` while more steps
 * follow, so that the core keeps the glyphs it has and stretches them, and not live once the
 * scale has settled, when the core draws them again at that size, once (#334).
 */
class PaneScales(
    private val scales: MutableMap<String, Float>,
    private val send: (block: String, scale: Float, live: Boolean) -> Unit,
) {
    private val live = mutableSetOf<String>()

    fun of(block: String): Float = scales[block] ?: 1f

    fun set(block: String, scale: Float, live: Boolean) {
        scales[block] = scale
        if (live) this.live += block else this.live -= block
        send(block, scale, live)
    }

    /** The scale of [block] stops changing: if a pinch was changing it, it is sent once more, settled. */
    fun settle(block: String) {
        if (live.remove(block)) send(block, of(block), false)
    }

    /** Every pinch is over: the gestures that made them were cut off and none ends by itself. */
    fun settleAll() {
        for (block in live.toList()) settle(block)
    }

    /** Only [blocks] are left: a pane that went takes its scale and its pinch with it. */
    fun retain(blocks: Set<String>) {
        scales.keys.retainAll(blocks)
        live.retainAll(blocks)
    }
}
