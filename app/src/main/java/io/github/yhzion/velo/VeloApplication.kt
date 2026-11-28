package io.github.yhzion.velo

import android.app.Application
import android.app.UiModeManager
import android.os.Build
import androidx.compose.ui.AndroidComposeUiFlags
import androidx.compose.ui.ExperimentalComposeUiApi
import java.util.Collections
import java.util.IdentityHashMap

/**
 * One [T] for the whole process, made on first use and handed to every owner (an activity
 * instance). It is destroyed when an owner leaves for good and no other owner holds it; the
 * next owner then gets a new one. An owner that leaves to be made again keeps it alive, so
 * the next instance finds it as it was. Main thread only.
 */
class PerProcess<T : Any>(private val create: () -> T, private val destroy: (T) -> Unit) {
    private var value: T? = null
    private val owners: MutableSet<Any> = Collections.newSetFromMap(IdentityHashMap())

    /** The shared value, for [owner] to use until it calls [release]. */
    fun acquire(owner: Any): T {
        owners += owner
        return value ?: create().also { value = it }
    }

    /**
     * [owner] is gone. [forGood] is whether it will not come back (the activity is finishing),
     * as opposed to being recreated. A finishing activity may report this after its successor
     * has already acquired the value: then the successor keeps it.
     */
    fun release(owner: Any, forGood: Boolean) {
        if (!owners.remove(owner)) return
        if (forGood && owners.isEmpty()) {
            value?.let(destroy)
            value = null
        }
    }
}

/**
 * The process. It owns the one [AppController], and with it the one connection per saved
 * remote: however often the activity is started or recreated, the servers see one client.
 */
class VeloApplication : Application() {
    val controller = PerProcess(create = { AppController(this) }, destroy = AppController::destroy)

    override fun onCreate() {
        super.onCreate()
        // Compose UI 1.13.0, after each scroll with the screen not in touch mode (a keyboard in use), sends an
        // accessibility-focused event for the node with input focus. TalkBack takes it as focus the app moved, so it
        // leaves the item it was reading for that node, which may be far up the list (#199). Keep TalkBack's place.
        // The flag is meant to let a screen reader follow keyboard focus to an item a scroll brings into view, but on
        // 1.13.0 Tab to a control below the screen is silent with it on or off (#220), so turning it off loses nothing.
        // When a Compose update makes ScrollFocusEventTest's test of Compose's default fail, decide this line again.
        @OptIn(ExperimentalComposeUiApi::class)
        AndroidComposeUiFlags.isScrollAccessibilityFocusEventEnabled = false
        // Replace any per-app light override from an older install, never the device-wide mode.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(UiModeManager::class.java)?.setApplicationNightMode(UiModeManager.MODE_NIGHT_YES)
        }
    }
}
