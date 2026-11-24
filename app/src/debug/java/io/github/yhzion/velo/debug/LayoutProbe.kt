package io.github.yhzion.velo.debug

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import java.lang.ref.WeakReference

/**
 * Debug builds only: the layout of the screen in front, as the app has it, for the window-size e2e
 * (`dev/emulator/e2e_windows.py`). `adb shell am broadcast -a io.github.yhzion.velo.debug.PROBE -p io.github.yhzion.velo -e id N` logs,
 * under the tag `VeloLayoutProbe`, each line starting with N:
 * - `window W H DPI`: the window the activity in front lays out for (its configuration's screenWidthDp/HeightDp, what
 *   the start screen's layout reads) and its density;
 * - `control L T R B l t r b NAME`: per control that takes a tap, its whole bounds in the window, then the part of it
 *   inside the window and every clip above it (all zero when none is). The accessibility tree reports only the second,
 *   so a control cut at a side reads there as ending at that side;
 * - `end`.
 * It measures the screen as it is, scrolled where it is, and changes nothing.
 */
object LayoutProbe {
    const val TAG = "VeloLayoutProbe"
    const val ACTION = "io.github.yhzion.velo.debug.PROBE"

    /** The activity in front, while it is resumed. */
    @Volatile var front: WeakReference<Activity>? = null

    fun report(activity: Activity, id: String) {
        val config = activity.resources.configuration
        Log.i(TAG, "$id window ${config.screenWidthDp} ${config.screenHeightDp} ${activity.resources.displayMetrics.densityDpi}")
        val root = findRoot(activity.window.decorView)
        if (root != null) walk(root.semanticsOwner.unmergedRootSemanticsNode, id)
        Log.i(TAG, "$id end")
    }

    private fun walk(node: SemanticsNode, id: String) {
        val c = node.config
        if (c.getOrNull(SemanticsActions.OnClick) != null) {
            val name = c.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()
                ?: c.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text
                ?: node.children.firstNotNullOfOrNull { it.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text }
            val p = node.positionInWindow
            val whole = listOf(p.x, p.y, p.x + node.size.width, p.y + node.size.height).map { it.toInt() }
            val shown = node.boundsInWindow.let { listOf(it.left, it.top, it.right, it.bottom).map { v -> v.toInt() } }
            Log.i(TAG, "$id control ${whole.joinToString(" ")} ${shown.joinToString(" ")} $name")
        }
        node.children.forEach { walk(it, id) }
    }

    private fun findRoot(view: View): ViewRootForTest? =
        view as? ViewRootForTest ?: (view as? ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { findRoot(g.getChildAt(it)) } }
}

/** Measures the activity in front on its own thread, after the frame being drawn. */
class LayoutProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: "0"
        val activity = LayoutProbe.front?.get()
        if (activity == null) {
            Log.i(LayoutProbe.TAG, "$id end no-activity")
            return
        }
        activity.window.decorView.post { LayoutProbe.report(activity, id) }
    }
}

/** Starts with the app (a provider is made before any activity) and follows which activity is in front. */
class LayoutProbeInstaller : ContentProvider() {
    override fun onCreate(): Boolean {
        (context?.applicationContext as? Application)?.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) { LayoutProbe.front = WeakReference(activity) }
            override fun onActivityPaused(activity: Activity) { if (LayoutProbe.front?.get() === activity) LayoutProbe.front = null }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?) = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
