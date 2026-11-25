package io.github.yhzion.velo.ui.theme

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.yhzion.velo.fidelity.HostActivityRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A title held to the browser's line: one line keeps that height, a wrapped one takes its lines'. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w393dp-h894dp-420dpi")
class LineBoxTest {
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(HostActivityRule()).around(compose)

    private val style = TextStyle(fontSize = 22.sp, lineHeight = 25.sp)

    @Test fun oneLineIsTheBrowsersLineAndAWrappedTitleIsAsTallAsItsLines() {
        compose.setContent {
            Column {
                Text("Velo", Modifier.width(300.dp).testTag("short").lineBoxOrTaller(style), style = style)
                Text("golang.org/x/crypto and golang.org/x/net", Modifier.width(120.dp).testTag("long").lineBoxOrTaller(style), style = style)
                Text("after", Modifier.testTag("after"))
            }
        }
        val short = compose.onNodeWithTag("short").getUnclippedBoundsInRoot()
        assertEquals(25f, (short.bottom - short.top).value, 0.5f)
        val long = compose.onNodeWithTag("long").getUnclippedBoundsInRoot()
        assertTrue("a wrapped title is ${long.bottom - long.top} tall", (long.bottom - long.top).value >= 50f)
        val after = compose.onNodeWithTag("after").getUnclippedBoundsInRoot()
        assertEquals(long.bottom.value, after.top.value, 0.5f)
    }
}
