package io.github.yhzion.velo.ui.theme

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay

/** Which overlay the gallery has open. */
enum class GalleryOverlay { Menu, Dialog, Popover, Palette }

/**
 * Debug builds only. Every component of `ui/theme` around a pane that is a real [SurfaceView]
 * with a moving pattern; `-e open menu|dialog|popover|palette` opens that overlay over the pattern,
 * and `-e scroll DP` starts that far down the page. It starts no connection.
 */
class GalleryActivity : ComponentActivity() {
    private var open by mutableStateOf<GalleryOverlay?>(null)
    private var scroll by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        val bars = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        super.onCreate(savedInstanceState)
        read(intent)
        // As the app itself: the system bars are hidden and come back with a swipe.
        WindowCompat.getInsetsController(window, window.decorView).run {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        val pattern = PatternView(this)
        val source = BackdropSource(views = { listOf(pattern) }, window = window)
        setContent {
            VeloTheme {
                CompositionLocalProvider(LocalBackdropSource provides source) {
                    Gallery(pattern, open, { open = it }, scroll)
                }
            }
            // What the refresh of an open overlay's backdrop costs, for the report.
            LaunchedEffect(open) {
                while (open != null) {
                    delay(1000)
                    Log.i("VeloGallery", "backdrop: first picture after %.0f ms, a copy takes %.1f ms, every %d ms".format(lastBackdropWaitMillis, lastBackdropCopyMillis, BackdropRefreshMillis))
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        read(intent)
    }

    private fun read(intent: Intent) {
        open = GalleryOverlay.entries.firstOrNull { it.name.equals(intent.getStringExtra("open"), ignoreCase = true) }
        scroll = intent.getStringExtra("scroll")?.toIntOrNull() ?: 0
    }
}

/**
 * A stand-in for a pane: a surface of its own behind the window, as a terminal's is, with
 * lines of text that stand still, coloured bars that move (across the lower part, and up a
 * strip at the right, where the menu and the popover open) and the time, so that a capture
 * shows whether an overlay's blur is of this picture and follows it.
 */
class PatternView(context: Context) : SurfaceView(context), SurfaceHolder.Callback, Runnable {
    @Volatile private var running = false
    private var thread: Thread? = null
    private val density = resources.displayMetrics.density
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE; textSize = 15 * density }
    private val fill = Paint()
    private val bars = intArrayOf(0xFFE5484D.toInt(), 0xFFF5A623.toInt(), 0xFFF2E94E.toInt(), 0xFF30D158.toInt(), 0xFF2EC4B6.toInt(), 0xFF0A84FF.toInt(), 0xFFBF5AF2.toInt(), 0xFFFFFFFF.toInt())
    private val lines = listOf(
        0xFF8BE9FD.toInt() to "velo@gallery ~/src/velo-android (theme)",
        0xFFF8F8F2.toInt() to "$ git log --oneline -3",
        0xFFF1FA8C.toInt() to "c15a33e feat(theme): components",
        0xFFF1FA8C.toInt() to "e2543e8 feat(theme): in-window glass, and overlays that blur",
        0xFFF1FA8C.toInt() to "8f32d27 feat(theme): VeloTheme provides the tokens",
        0xFFF8F8F2.toInt() to "$ ls app/src/main/java/io/github/yhzion/velo/ui/theme",
        0xFF50FA7B.toInt() to "Backdrop.kt  Components.kt  Fields.kt  Glass.kt  Overlays.kt  Theme.kt  Tokens.kt",
        0xFFFF79C6.toInt() to "The quick brown fox jumps over the lazy dog 0123456789",
    )

    init { holder.addCallback(this) }

    override fun surfaceCreated(holder: SurfaceHolder) {
        running = true
        thread = Thread(this, "gallery-pattern").also { it.start() }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        running = false
        thread?.join()
        thread = null
    }

    override fun run() {
        val began = SystemClock.uptimeMillis()
        while (running) {
            val canvas = try { holder.lockHardwareCanvas() } catch (e: IllegalStateException) { null } ?: break
            try { paint(canvas, (SystemClock.uptimeMillis() - began) / 1000f) } finally { holder.unlockCanvasAndPost(canvas) }
            Thread.sleep(16)
        }
    }

    private fun paint(canvas: Canvas, seconds: Float) {
        val w = canvas.width.toFloat()
        val h = canvas.height.toFloat()
        canvas.drawColor(0xFF10131A.toInt())
        val pad = 14 * density
        val line = 24 * density
        // Bars that travel to the right, one bar's width in 0.8 s, across the lower part.
        val bar = 96 * density
        val shift = (seconds / 0.8f * bar) % (bar * bars.size)
        var x = -bar * bars.size + shift
        var i = 0
        while (x < w) {
            fill.color = bars[i % bars.size]
            canvas.drawRect(x, h * 0.55f, x + bar, h, fill)
            x += bar
            i++
        }
        // A strip of the same colours that travels upward at the right, where the menu and the popover open.
        val strip = bar * 2.5f
        var y = -shift
        i = 0
        while (y < h * 0.55f) {
            fill.color = bars[i % bars.size]
            canvas.drawRect(w - strip, maxOf(y, 0f), w, minOf(y + bar, h * 0.55f), fill)
            y += bar
            i++
        }
        var baseline = pad + line
        for (round in 0 until 4) for ((color, s) in lines) {
            text.color = color
            canvas.drawText(s, pad, baseline, text)
            baseline += line
            if (baseline > h * 0.55f - pad) break
        }
        text.color = 0xFF10131A.toInt()
        canvas.drawText("t = %.1f s".format(seconds), pad, h - pad, text)
    }
}
