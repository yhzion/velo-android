package io.github.yhzion.velo.fidelity

import org.robolectric.RuntimeEnvironment

/**
 * A window size the speech scans draw every scene at (#175), read from FidelityPlan's [Devices]: the
 * bar phone's ("bar", 393 x 894) and the Fold8 unfolded ("open", 821 x 1088). Unfolded, the window is
 * 600 dp or more on both sides, so the wide layouts show (the settings' side list, studio-mac's open
 * menu in settings-remotes), and it is 800 dp or more wide, so the workspace scenes draw the "Open"
 * boards' data (the dev session open with four tabs, the Open panes). Density 420dpi, as FidelityPlan's.
 */
enum class ScanWidth(device: String) {
    Phone("bar"),
    Unfolded("open"),
    ;

    val w: Int = Devices.getValue(device).first
    val h: Int = Devices.getValue(device).second

    /** Draws the next content at this size. */
    fun apply() = RuntimeEnvironment.setQualifiers("w${w}dp-h${h}dp-420dpi")

    override fun toString() = "${name.lowercase()} ${w}x${h}dp"

    companion object {
        /** The widths as the parameters of a ParameterizedRobolectricTestRunner, named by [toString]. */
        @JvmStatic fun parameters(): List<Array<Any>> = entries.map { arrayOf(it) }
    }
}
