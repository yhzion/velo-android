package io.github.yhzion.velo.fidelity

import android.app.Application
import android.view.View
import android.view.accessibility.AccessibilityNodeProvider
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The sweep the speech scans use (#276) reaches the nodes off the screen that a scan of the window alone misses: the
 * end of a column that scrolls, of a row inside it that scrolls sideways and comes into view only as the column scrolls,
 * and of a lazy list, whose last items are not even composed until it scrolls. A slider on the way keeps its value.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ScrollSweepTest {
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(HostActivityRule()).around(compose)

    @Before fun phone() = RuntimeEnvironment.setQualifiers("w393dp-h894dp-420dpi")

    private fun Modifier.says(words: String) = semantics { contentDescription = words }

    @Test fun theSweepHearsWhatIsOffTheScreen() {
        var value by mutableFloatStateOf(0.5f)
        lateinit var view: View
        compose.setContent {
            view = LocalView.current
            Column(Modifier.fillMaxWidth().height(400.dp).verticalScroll(rememberScrollState())) {
                Slider(value, { value = it }, Modifier.fillMaxWidth())
                repeat(8) { Box(Modifier.fillMaxWidth().height(100.dp).says("Row $it")) }
                // Off the screen until the column scrolls, and then its end is off the screen until it scrolls too.
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    repeat(8) { Box(Modifier.width(150.dp).height(60.dp).says("Card $it")) }
                }
                LazyColumn(Modifier.fillMaxWidth().height(200.dp)) {
                    items(30) { Box(Modifier.fillMaxWidth().height(50.dp).says("Item $it")) }
                }
            }
        }
        val atRest = compose.runOnIdle { allWords(view) }.toSet()
        val swept = mutableSetOf<String>()
        ScrollSweep(compose, view).sweep { swept += allWords(view) }
        for (end in listOf("Row 7", "Card 7", "Item 29")) {
            assertFalse("$end is off the screen as the scene is drawn", end in atRest)
            assertTrue("The sweep hears $end, among: $swept", end in swept)
        }
        assertEquals("The slider keeps its value", 0.5f, value)
    }

    /** The words of every node in [view], focusable or not. */
    private fun allWords(view: View): List<String> {
        val provider = view.accessibilityNodeProvider
        val words = mutableListOf<String>()
        fun walk(id: Int) {
            val info = provider.createAccessibilityNodeInfo(id) ?: return
            words += Speech.words(info)
            for (c in 0 until info.childCount) Speech.childVirtualId(info, c)?.let(::walk)
        }
        walk(AccessibilityNodeProvider.HOST_VIEW_ID)
        return words
    }
}
