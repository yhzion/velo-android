package io.github.yhzion.velo

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri

/**
 * Keeps the process, and with it every connection to a remote, running while no activity is
 * shown. Without it Android freezes the cached process within minutes, the connections drop,
 * and no pane notification can arrive. It runs only while [BackgroundConnection.wanted] holds.
 */
class BackgroundConnectionService : Service() {
    /** The last start this service took; [stop] ends the service only if no later one is on its way. */
    private var lastStart = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "Connected to your remotes"
        try {
            // Android 14 and later need the type; before it, the manifest's type is enough.
            if (Build.VERSION.SDK_INT >= 34) startForeground(ID, BackgroundConnection.note(this, text), ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
            else startForeground(ID, BackgroundConnection.note(this, text))
        } catch (e: RuntimeException) {
            // Android refuses a start it does not allow from the background; the app then runs as before.
            Log.w("velo.background", "foreground service refused", e)
            // Not stopSelf(): a later start may still be on its way, and stopping under it crashes the app.
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        running = this
        lastStart = startId
        // The app may be back on screen already: a new intent or an activity result pauses and
        // resumes the activity in one step, before this start arrives. Android crashes an app
        // whose service stops before it calls startForeground, so the service stops itself here.
        if (!wanted) stop()
        return START_NOT_STICKY
    }

    private fun stop() {
        if (stopSelfResult(lastStart) && running === this) running = null
    }

    override fun onDestroy() {
        if (running === this) running = null
        super.onDestroy()
    }

    companion object {
        internal const val ID = 0x5245
        internal const val EXTRA_TEXT = "text"

        /** Whether the app wants the service now ([BackgroundConnection.update]). Main thread only. */
        internal var wanted = false

        /** The service once it has gone foreground, until it stops. */
        private var running: BackgroundConnectionService? = null

        /**
         * Stop the service without stopService: a start still on its way stops it when it
         * arrives ([onStartCommand]), after it has gone foreground.
         */
        internal fun stopWhenForeground() {
            wanted = false
            running?.stop()
        }
    }
}

/** Whether, and how, the app stays connected while it is not on screen. Main thread only. */
internal class BackgroundConnection(private val context: Context) {
    private val power = requireNotNull(context.getSystemService(PowerManager::class.java))
    private var running = false
    private var shownText: String? = null

    /** Android lets the app keep its network during Doze: the battery optimization is off for it. */
    fun unrestricted() = power.isIgnoringBatteryOptimizations(context.packageName)

    /**
     * The system dialog that turns the battery optimization off for this app. Where a device has
     * no such dialog, starting it fails and the caller opens [unrestrictedSettings] instead.
     */
    @SuppressLint("BatteryLife") // Velo keeps live connections to remotes: an accepted use of the request.
    fun requestUnrestricted() = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri())

    /** The list of apps under battery optimization. */
    fun unrestrictedSettings() = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    /**
     * Start or stop the service: it runs while [enabled] or the phone forwards ports ([forwards]),
     * no activity is on screen ([foreground] false) and [remotes] are connected. Starting happens
     * as the app leaves the screen, while Android still lets it start a foreground service.
     */
    fun update(enabled: Boolean, foreground: Boolean, remotes: Int, forwards: Int = 0) {
        val wanted = wanted(enabled, foreground, remotes, forwards)
        BackgroundConnectionService.wanted = wanted
        if (!wanted) {
            // Even when this controller thinks nothing runs: a start it lost track of (a later
            // start that Android refused) may still be on its way. Stopping twice is harmless.
            BackgroundConnectionService.stopWhenForeground()
            running = false
            shownText = null
            return
        }
        val text = statusText(remotes, forwards)
        if (running && text == shownText) return
        try {
            context.startForegroundService(Intent(context, BackgroundConnectionService::class.java).putExtra(BackgroundConnectionService.EXTRA_TEXT, text))
            running = true
            shownText = text
        } catch (e: RuntimeException) {
            Log.w("velo.background", "could not start the foreground service", e)
        }
    }

    companion object {
        internal const val CHANNEL = "background_connection"

        /** A forward needs the process while the browser is in front: its connections are relayed here. */
        internal fun wanted(enabled: Boolean, foreground: Boolean, remotes: Int, forwards: Int = 0) =
            (enabled || forwards > 0) && !foreground && remotes > 0

        internal fun statusText(remotes: Int, forwards: Int = 0) = when {
            forwards == 1 -> "Forwarding 1 port to this phone"
            forwards > 1 -> "Forwarding $forwards ports to this phone"
            remotes == 1 -> "Connected to 1 remote · notifications stay on"
            else -> "Connected to $remotes remotes · notifications stay on"
        }

        /**
         * The channel of the service's line. Low importance: the line stays in the shade without
         * a sound or a heads-up. It is not news, so it never counts on the launcher icon's badge.
         */
        internal fun createChannel(context: Context) {
            val manager = requireNotNull(context.getSystemService(NotificationManager::class.java))
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Background connection", NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) })
        }

        internal fun note(context: Context, text: String): Notification {
            createChannel(context)
            val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), PendingIntent.FLAG_IMMUTABLE)
            return NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_status_done)
                .setContentTitle("Velo is running in the background").setContentText(text)
                .setOngoing(true).setSilent(true).setShowWhen(false).setContentIntent(open)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE).build()
        }
    }
}
