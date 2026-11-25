package io.github.yhzion.velo.fidelity

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** The three device shapes in dp: bar phone (LG Velvet), Fold8 closed, Fold8 open (portrait). */
val Devices = linkedMapOf("bar" to (393 to 894), "closed" to (475 to 751), "open" to (821 to 1088))

/** One app screenshot to take: [screen] drawn at [w] x [h] dp for [device], to compare with [board]. */
data class Capture(val screen: String, val device: String, val board: String?, val w: Int, val h: Int) {
    val fileName get() = "$screen@$device"
}

/**
 * What to capture. [plan] is the JSON tools/fidelity/fidelity.py writes: one entry per canvas board,
 * at the board's size (a scrolling page is drawn at its full length). Without a plan, every
 * registered screen is taken at the three device sizes. Screens without a scene are left out;
 * [only], when given, keeps just those screens.
 */
fun captures(plan: String?, registered: Set<String>, only: Set<String>? = null): List<Capture> {
    val all = if (plan == null) {
        registered.flatMap { s -> Devices.map { (d, size) -> Capture(s, d, null, size.first, size.second) } }
    } else {
        Json.parseToJsonElement(plan).jsonObject.getValue("captures").jsonArray.map {
            val o = it.jsonObject
            Capture(
                o.getValue("screen").jsonPrimitive.content, o.getValue("device").jsonPrimitive.content,
                o["board"]?.jsonPrimitive?.contentOrNull, o.getValue("w").jsonPrimitive.int, o.getValue("h").jsonPrimitive.int,
            )
        }
    }
    return all.filter { it.screen in registered && (only == null || it.screen in only) }
}

/** A node with a testTag, its box measured in px at [density] and reported in dp. */
data class TaggedNode(
    val tag: String, val xPx: Float, val yPx: Float, val wPx: Float, val hPx: Float, val density: Float,
    val text: String?, val label: String? = null,
) {
    val x get() = xPx / density
    val y get() = yPx / density
    val w get() = wPx / density
    val h get() = hPx / density
}

private val HasTestTag = SemanticsMatcher("has a testTag") { SemanticsProperties.TestTag in it.config }

/**
 * Every node with a testTag, in tree order (the order tools/fidelity pairs repeated names in),
 * unmerged so a tag inside a clickable row still counts. Boxes are unclipped: position in the root
 * and the node's own size, so content below the fold keeps its full box, as on the board.
 */
fun SemanticsNodeInteractionsProvider.taggedNodes(): List<TaggedNode> =
    onAllNodes(HasTestTag, useUnmergedTree = true).fetchSemanticsNodes().map { n ->
        val config = n.config
        TaggedNode(
            tag = config[SemanticsProperties.TestTag],
            xPx = n.positionInRoot.x, yPx = n.positionInRoot.y,
            wPx = n.size.width.toFloat(), hPx = n.size.height.toFloat(), density = n.layoutInfo.density.density,
            text = (config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty() +
                listOfNotNull(config.getOrNull(SemanticsProperties.EditableText)?.text)).joinToString(" ").ifEmpty { null },
            label = config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" "),
        )
    }
