package io.github.yhzion.velo.ui

/**
 * Which panes of one connection are off the screen because this app covers its workspace, and
 * which of them come back. The sidebar where it takes the panes' place, Settings, the Tailscale
 * screen and pairing remove the pane views, and that is no change to the panes. Looking is not
 * using: a pane that was shown when its view went away under the cover, and is drawn again when
 * the cover ends, is shown as it was, and its terminal is not taken (`HostConnection.suspend`
 * and `resume`). A pane whose view goes away without a cover (a tab switch, a zoom, another
 * remote, the pane's own end), a pane that closed while it was covered and another tab drawn
 * after the cover are shown by the usual rules. A return is used once.
 * The views are gone while the cover lasts, so what was covered is kept here, one for each
 * connection. Main thread only.
 */
class PaneCover {
    /** The workspace is covered now: the pane views that go away are covered, not closed. */
    var covering = false
        private set
    /** The panes that were shown when their views went away under the cover, and have not come back. */
    private val covered = HashSet<String>()

    /** The workspace is covered from now on: a pane view that goes away is covered, not closed. */
    fun begin() {
        covering = true
    }

    /**
     * The workspace draws [panes], the blocks of the tab it shows (none without a tab): the
     * cover has ended, and only they come back.
     */
    fun draws(panes: Set<String>) {
        covering = false
        covered.retainAll(panes)
    }

    /**
     * The view of the shown pane [block] goes away. True when the workspace is covered: the
     * pane is not closed, and comes back when its tab is drawn again.
     */
    fun leaves(block: String): Boolean {
        if (covering) covered += block
        return covering
    }

    /** A new view shows the pane [block]. True, once, when the pane is back from under the cover. */
    fun returns(block: String): Boolean = !covering && covered.remove(block)

    /** The connection has [blocks] now: a pane that closed while it was covered is not kept. */
    fun keep(blocks: Set<String>) {
        covered.retainAll(blocks)
    }

    /** Another remote is drawn in this one's place: its panes were left, not covered. */
    fun forget() {
        covering = false
        covered.clear()
    }
}
