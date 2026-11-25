package io.github.yhzion.velo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.em
import io.github.yhzion.velo.ui.theme.VeloTheme

// The rail's battery gauge, after the status ring of the iPhone Duo (docs/design-tokens.md, M01):
// a ring open at the bottom that fills clockwise from seven o'clock, the percentage inside, the
// device's network in the opening. While the battery charges, the top of the ring opens too and
// holds a bolt; otherwise the ring runs unbroken over the top.

/** One stretch of the ring in Compose's arc degrees: 0 at three o'clock, clockwise. */
internal data class GaugeArc(val start: Float, val sweep: Float)

/** Seven o'clock, where the ring starts. */
internal const val GaugeStart = 140f
/** The ring's length: the opening at the bottom takes the other 100 degrees. */
internal const val GaugeSpan = 260f
/** The opening at the top that holds the bolt while the battery charges. */
internal const val GaugeChargeGap = 64f

/** The ring as drawn: one arc, or two around the top opening while [charging]. */
internal fun gaugeTrack(charging: Boolean): List<GaugeArc> {
    if (!charging) return listOf(GaugeArc(GaugeStart, GaugeSpan))
    val half = (GaugeSpan - GaugeChargeGap) / 2
    return listOf(GaugeArc(GaugeStart, half), GaugeArc(GaugeStart + half + GaugeChargeGap, half))
}

/** The filled part of [gaugeTrack]: [percent] of the ring's drawn length, from its start. */
internal fun gaugeFill(percent: Int, charging: Boolean): List<GaugeArc> {
    val track = gaugeTrack(charging)
    var left = track.sumOf { it.sweep.toDouble() }.toFloat() * percent.coerceIn(0, 100) / 100f
    val out = mutableListOf<GaugeArc>()
    for (arc in track) {
        if (left <= 0f) break
        val sweep = minOf(arc.sweep, left)
        out += GaugeArc(arc.start, sweep)
        left -= sweep
    }
    return out
}

/** What a screen reader hears for the gauge. */
internal fun gaugeDescription(battery: BatteryState, network: DeviceNetwork): String {
    val parts = mutableListOf("Battery ${battery.percent} percent")
    if (battery.charging) parts += "charging"
    if (battery.powerSave) parts += "battery saver on"
    parts += when (network) {
        DeviceNetwork.Wifi -> "Wi-Fi"
        DeviceNetwork.Ethernet -> "Ethernet"
        is DeviceNetwork.Cellular -> network.generation ?: "mobile network"
        DeviceNetwork.None -> "no network"
    }
    return parts.joinToString(", ")
}

/** Battery saver's yellow: the iOS system yellow of each appearance; no image shows it. */
private val PowerSaveOnDark = Color(0xFFFFD60A)
private val PowerSaveOnLight = Color(0xFFFFCC00)

/** The share of the text colour the empty part of the ring takes. derived: the mockup the owner chose (v4). */
private const val TrackAlpha = 0.28f

@Composable
internal fun BatteryGauge(battery: BatteryState, network: DeviceNetwork, modifier: Modifier = Modifier, faceModifier: Modifier = Modifier) {
    val c = VeloTheme.surface
    val d = VeloTheme.sizes.gauge
    val stroke = VeloTheme.sizes.gaugeStroke
    val fill = when (batteryTone(battery)) {
        BatteryTone.Normal -> c.text
        BatteryTone.Low -> c.danger
        BatteryTone.Charging -> c.ok
        BatteryTone.PowerSave -> if (c.surface.luminance() < 0.5f) PowerSaveOnDark else PowerSaveOnLight
    }
    val track = c.text.copy(alpha = TrackAlpha)
    // The gauge takes exactly the square of a round button: the network mark sits inside the
    // opening at the bottom, and the bolt inside the opening at the top.
    val mark = d * 0.30f
    val description = gaugeDescription(battery, network)
    Box(modifier.size(d).clearAndSetSemantics { contentDescription = description }) {
        Box(faceModifier.size(d), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(d)) {
                val w = stroke.toPx()
                val topLeft = Offset(w / 2, w / 2)
                val arcSize = Size(size.width - w, size.height - w)
                val line = Stroke(w, cap = StrokeCap.Round)
                for (a in gaugeTrack(battery.charging)) drawArc(track, a.start, a.sweep, false, topLeft, arcSize, style = line)
                for (a in gaugeFill(battery.percent, battery.charging)) drawArc(fill, a.start, a.sweep, false, topLeft, arcSize, style = line)
            }
            // The gauge is a drawing of a fixed size: its text follows the ring, not the font scale.
            GaugeText("${battery.percent}", d * if (battery.percent >= 100) 0.31f else 0.39f, c.text)
            if (battery.charging) {
                val bolt = d * 0.32f
                Icon(Icons.Default.Bolt, null, Modifier.align(Alignment.TopCenter).size(bolt), tint = c.ok)
            }
        }
        Box(Modifier.align(Alignment.BottomCenter).size(mark), contentAlignment = Alignment.Center) {
            NetworkMark(network, mark, c.text)
        }
    }
}

@Composable
private fun NetworkMark(network: DeviceNetwork, box: Dp, color: Color) {
    val icon = when (network) {
        DeviceNetwork.Wifi -> Icons.Default.Wifi
        DeviceNetwork.Ethernet -> Icons.Default.SettingsEthernet
        DeviceNetwork.None -> Icons.Default.WifiOff
        is DeviceNetwork.Cellular -> if (network.generation == null) Icons.Default.SignalCellularAlt else null
    }
    if (icon != null) Icon(icon, null, Modifier.size(box), tint = color)
    else {
        val label = (network as DeviceNetwork.Cellular).generation.orEmpty()
        GaugeText(label, box * if (label.length > 2) 0.62f else 0.78f, color)
    }
}

@Composable
private fun GaugeText(text: String, size: Dp, color: Color) {
    val sp = with(LocalDensity.current) { size.toSp() }
    Text(text, color = color, maxLines = 1, softWrap = false,
        style = TextStyle(fontSize = sp, lineHeight = sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.02).em))
}
