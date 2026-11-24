package io.github.yhzion.velo.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import io.github.yhzion.velo.SystemCalls

// The device's own state for the rail's battery gauge: the battery, and the network the device
// is on (not the link to the remote, which the connection mark shows). The rules are plain
// functions so tests can check them without a device.

/** The battery as the gauge shows it: [percent] of a full charge, and the two states that change its look. */
@Immutable
internal data class BatteryState(val percent: Int, val charging: Boolean, val powerSave: Boolean)

/** The colour the gauge's arc takes. */
internal enum class BatteryTone { Normal, Low, Charging, PowerSave }

/** At this share or below the arc turns red, as the iPhone's battery does. */
internal const val LowBatteryPercent = 20

/** Battery saver wins over charging, and charging over a low battery: the iPhone's order. */
internal fun batteryTone(b: BatteryState): BatteryTone = when {
    b.powerSave -> BatteryTone.PowerSave
    b.charging -> BatteryTone.Charging
    b.percent <= LowBatteryPercent -> BatteryTone.Low
    else -> BatteryTone.Normal
}

/**
 * The battery that ACTION_BATTERY_CHANGED reports. [status] is BatteryManager's. It charges only
 * while a power source is [plugged] in: some devices keep reporting "charging" after the cable
 * comes out. A full battery on the charger still counts, so the bolt stays while it is plugged in.
 */
internal fun batteryOf(level: Int, scale: Int, status: Int, plugged: Int, powerSave: Boolean): BatteryState? {
    if (level < 0 || scale <= 0) return null
    val percent = (level * 100f / scale).toInt().coerceIn(0, 100)
    val charging = plugged != 0 && (status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
    return BatteryState(percent, charging, powerSave)
}

/** The network the device is on, as the gauge names it under its ring. */
@Immutable
internal sealed interface DeviceNetwork {
    data object Wifi : DeviceNetwork
    data object Ethernet : DeviceNetwork
    /** A mobile network; [generation] is "5G", "LTE", "3G" or "2G", null when the device does not say. */
    data class Cellular(val generation: String?) : DeviceNetwork
    data object None : DeviceNetwork
}

/**
 * The device's network from the transports of its default network. A VPN (Tailscale) carries
 * the transports of the network under it, so a tailnet over Wi-Fi still reads as Wi-Fi.
 */
internal fun networkOf(connected: Boolean, wifi: Boolean, cellular: Boolean, ethernet: Boolean, generation: String?): DeviceNetwork = when {
    !connected -> DeviceNetwork.None
    wifi -> DeviceNetwork.Wifi
    ethernet -> DeviceNetwork.Ethernet
    cellular -> DeviceNetwork.Cellular(generation)
    else -> DeviceNetwork.None
}

/**
 * The generation the status bar shows for a mobile network: the [override] of TelephonyDisplayInfo
 * first (LTE with a 5G anchor shows "5G"), then the radio's [networkType]. Null when neither says.
 */
@Suppress("DEPRECATION")
internal fun cellularGeneration(networkType: Int, override: Int): String? = when (override) {
    TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA,
    TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED,
    TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA_MMWAVE -> "5G"
    TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA,
    TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_ADVANCED_PRO -> "LTE"
    else -> when (networkType) {
        TelephonyManager.NETWORK_TYPE_NR -> "5G"
        TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
        TelephonyManager.NETWORK_TYPE_UMTS, TelephonyManager.NETWORK_TYPE_HSDPA, TelephonyManager.NETWORK_TYPE_HSUPA,
        TelephonyManager.NETWORK_TYPE_HSPA, TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_TD_SCDMA,
        TelephonyManager.NETWORK_TYPE_EVDO_0,
        TelephonyManager.NETWORK_TYPE_EVDO_A,
        TelephonyManager.NETWORK_TYPE_EVDO_B,
        TelephonyManager.NETWORK_TYPE_EHRPD -> "3G"
        TelephonyManager.NETWORK_TYPE_GPRS, TelephonyManager.NETWORK_TYPE_EDGE, TelephonyManager.NETWORK_TYPE_GSM,
        TelephonyManager.NETWORK_TYPE_CDMA,
        TelephonyManager.NETWORK_TYPE_1xRTT,
        TelephonyManager.NETWORK_TYPE_IDEN -> "2G"
        else -> null
    }
}

private const val TAG = "DeviceStatus"

/** The battery, kept current while the caller is shown; null until Android first reports it. */
@Composable
internal fun rememberBatteryState(): BatteryState? {
    val context = LocalContext.current
    var state by remember { mutableStateOf<BatteryState?>(null) }
    DisposableEffect(context) {
        // The application's: a registration that ends after the activity has gone is no leak of it.
        val app = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        val power = app.getSystemService(PowerManager::class.java)
        var last: Intent? = null
        // Main thread only: a report that comes after the gauge has gone changes nothing.
        var shown = true
        fun read(battery: Intent?, powerSave: Boolean = power?.isPowerSaveMode == true) {
            if (!shown) return
            val i = battery ?: last ?: return
            last = i
            state = batteryOf(
                level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
                scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1),
                status = i.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN),
                plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0),
                powerSave = powerSave,
            )
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                read(if (intent.action == Intent.ACTION_BATTERY_CHANGED) intent else null)
            }
        }
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED).apply { addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED) }
        // Off the main thread, as each is a call to system_server ([SystemCalls], #336); the receiver still runs on the
        // main thread. ACTION_BATTERY_CHANGED is sticky: registering returns the current battery at once.
        SystemCalls.submit {
            val battery = ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            val powerSave = power?.isPowerSaveMode == true
            main.post { read(battery, powerSave) }
        }
        onDispose {
            shown = false
            SystemCalls.submit { app.unregisterReceiver(receiver) }
        }
    }
    return state
}

/** The device's network, kept current while the caller is shown. */
@Composable
internal fun rememberDeviceNetwork(): DeviceNetwork {
    val context = LocalContext.current
    var caps by remember { mutableStateOf<NetworkCapabilities?>(null) }
    var display by remember { mutableStateOf<TelephonyDisplayInfo?>(null) }
    DisposableEffect(context) {
        // The application's: a registration that ends after the activity has gone is no leak of it.
        val app = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        // Main thread only: a callback that comes after the gauge has gone changes nothing.
        var shown = true
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { if (shown) caps = capabilities }
            override fun onLost(network: Network) { if (shown) caps = null }
        }
        // Android 12 and later give the display info without READ_PHONE_STATE; on 11 the
        // generation stays unknown and the gauge shows plain signal bars for a mobile network.
        val telephony = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            object : TelephonyCallback(), TelephonyCallback.DisplayInfoListener {
                override fun onDisplayInfoChanged(info: TelephonyDisplayInfo) { if (shown) display = info }
            }
        } else null
        // Off the main thread, as each is a call to system_server ([SystemCalls], #336); the callbacks still come to
        // the main thread.
        SystemCalls.submit { app.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(callback, main) }
        if (telephony != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) SystemCalls.submit {
            try {
                app.getSystemService(TelephonyManager::class.java)?.registerTelephonyCallback(app.mainExecutor, telephony)
            } catch (e: SecurityException) {
                Log.w(TAG, "display info: ${e.message}")
            }
        }
        onDispose {
            shown = false
            SystemCalls.submit { app.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback) }
            if (telephony != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) SystemCalls.submit {
                app.getSystemService(TelephonyManager::class.java)?.unregisterTelephonyCallback(telephony)
            }
        }
    }
    val c = caps
    val generation = display?.let { cellularGeneration(it.networkType, it.overrideNetworkType) }
    return networkOf(
        connected = c != null,
        wifi = c?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true,
        cellular = c?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true,
        ethernet = c?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true,
        generation = generation,
    )
}
