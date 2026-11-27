package io.github.yhzion.velo.fidelity

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Draws every scene in [FidelityScenes] at each board's size and records, for tools/fidelity,
 * a screenshot (<screen>@<device>.png) and the boxes of all nodes with a testTag
 * (<screen>@<device>.json, in dp). Runs only with -Pvelo.fidelity.out=<dir> (app/build.gradle.kts).
 *
 * Density 2.625 (420dpi) is the LG Velvet's and close to the Fold8's. SDK 34: Robolectric's
 * newer SDKs need Java 21 and the build runs tests on Java 17; Compose draws the same.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, shadows = [ShadowVeloCore::class], instrumentedPackages = [CorePackage])
class FidelityCaptureTest(private val capture: Capture) {
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(HostActivityRule()).around(compose)

    @Test fun capture() {
        val out = File(requireNotNull(System.getProperty("velo.fidelity.out")))
        out.mkdirs()
        RuntimeEnvironment.setQualifiers("w${capture.w}dp-h${capture.h}dp-420dpi")
        compose.setContent { FidelityScenes.getValue(capture.screen)() }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(File(out, "${capture.fileName}.png").path)

        val density = compose.density.density
        val nodes = compose.taggedNodes()
        val root = compose.onRoot().fetchSemanticsNode()
        val json = buildJsonObject {
            put("screen", capture.screen)
            put("device", capture.device)
            capture.board?.let { put("board", it) }
            put("w", capture.w)
            put("h", capture.h)
            put("density", density)
            put("rootPx", "${root.size.width}x${root.size.height}")
            putJsonArray("nodes") {
                for (n in nodes) addJsonObject {
                    put("tag", n.tag)
                    put("x", n.x); put("y", n.y); put("w", n.w); put("h", n.h)
                    n.text?.let { put("text", it) }
                    n.label?.let { put("label", it) }
                }
            }
        }
        File(out, "${capture.fileName}.json").writeText(json.toString())
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun parameters(): List<Array<Any>> {
            val plan = System.getProperty("velo.fidelity.plan")?.let { File(it).readText() }
            val only = System.getProperty("velo.fidelity.screens")?.split(',')?.filter { it.isNotBlank() }?.toSet()
            return captures(plan, FidelityScenes.keys, only).map { arrayOf(it) }
        }
    }
}

/** Registers ComponentActivity, the compose rule's host, which the app's manifest does not list. */
class HostActivityRule : ExternalResource() {
    override fun before() {
        val app = RuntimeEnvironment.getApplication()
        val name = ComponentName(app, ComponentActivity::class.java)
        val pm = shadowOf(app.packageManager)
        pm.addActivityIfNotPresent(name)
        pm.addIntentFilterForActivity(name, IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) })
    }
}
