package io.github.yhzion.velo.ui.theme

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
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
import java.io.File
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A working tab's progress as a pie on its tile (#250): a ring and a wedge that fills clockwise from twelve o'clock, on
 * the plain glass face in place of the logo. The capture is also written to `app/build/reports/tile-pie.png`, to hold
 * beside Rex for Mac (report 242, evidence 02).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w640dp-h894dp-420dpi")
class TabTilePieDrawTest {
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(HostActivityRule()).around(compose)

    private val shares = listOf(0, 30, 70, 100)

    @Test fun theWedgeFillsClockwiseFromTwelveInPlaceOfTheLogo() {
        compose.setContent {
            VeloTheme {
                Row(Modifier.testTag("sheet").background(VeloTheme.surface.surface).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    for (p in shares) TabTile(TileBrand.Claude, size = VeloTheme.medium(44.dp), tag = "p$p", progress = p)
                    TabTile(TileBrand.Claude, size = VeloTheme.medium(44.dp), tag = "logo")
                }
            }
        }
        compose.waitForIdle()
        val sheet = compose.onNodeWithTag("sheet").captureToImage()
        File("build/reports").mkdirs()
        File("build/reports/tile-pie.png").outputStream().use { sheet.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }

        compose.onNodeWithTag("logo-logo", useUnmergedTree = true).assertIsDisplayed()
        for (p in shares) {
            compose.onNodeWithTag("p$p-logo", useUnmergedTree = true).assertDoesNotExist()
            val pie = compose.onNodeWithTag("p$p-pie", useUnmergedTree = true).captureToImage()
            // Half way out along the wedge: at one and a half o'clock (45°) and at half past seven (225°).
            assertEquals("$p% at 45°", p > 12, green(pie, 45.0))
            assertEquals("$p% at 225°", p > 62, green(pie, 225.0))
            // The ring is there from 0: at twelve, on its line.
            assertTrue("$p% ring", green(pie, 0.0, out = 0.97))
        }
    }

    /** Whether the pie is green at [degrees] clockwise from twelve, [out] of the way from its centre to its edge (the wedge reaches 0.6). */
    private fun green(pie: ImageBitmap, degrees: Double, out: Double = 0.3): Boolean {
        val map = pie.toPixelMap()
        val r = pie.width / 2.0 * out
        val a = Math.toRadians(degrees)
        val x = (pie.width / 2.0 + r * sin(a)).roundToInt().coerceIn(0, pie.width - 1)
        val y = (pie.height / 2.0 - r * cos(a)).roundToInt().coerceIn(0, pie.height - 1)
        val c = map[x, y]
        return c.green - c.red > 0.25f && c.green - c.blue > 0.25f
    }
}
