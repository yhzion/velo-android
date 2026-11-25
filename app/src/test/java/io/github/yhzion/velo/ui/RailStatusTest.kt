package io.github.yhzion.velo.ui

import android.os.BatteryManager
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The rail's status rules: where the clock starts, the battery gauge and the device's network. */
class RailStatusTest {

    @Test
    fun theClockGoesBelowTheStatusBarWithRoomToSpare() {
        // The rail starts 10 px down; the status bar is 110 px tall.
        assertEquals(116f, railTopPadding(statusBar = 110f, railTop = 10f, clearance = 16f, least = 25f), 0f)
    }

    @Test
    fun withoutAStatusBarTheRailKeepsItsOwnPadding() {
        assertEquals(25f, railTopPadding(statusBar = 0f, railTop = 10f, clearance = 16f, least = 25f), 0f)
        // A rail that already starts below the bar keeps its padding too.
        assertEquals(25f, railTopPadding(statusBar = 110f, railTop = 200f, clearance = 16f, least = 25f), 0f)
    }

    @Test
    fun theRingRunsUnbrokenOverTheTopUnlessTheBatteryCharges() {
        assertEquals(listOf(GaugeArc(GaugeStart, GaugeSpan)), gaugeTrack(charging = false))
        val charging = gaugeTrack(charging = true)
        assertEquals(2, charging.size)
        assertEquals(GaugeSpan - GaugeChargeGap, charging.sumOf { it.sweep.toDouble() }.toFloat(), 0.001f)
        // The opening is centred on twelve o'clock (270 in Compose's degrees).
        val gapStart = charging[0].start + charging[0].sweep
        assertEquals(270f, gapStart + GaugeChargeGap / 2, 0.001f)
    }

    @Test
    fun theFillIsTheShareOfTheDrawnRing() {
        assertEquals(listOf<GaugeArc>(), gaugeFill(0, charging = false))
        assertEquals(listOf(GaugeArc(GaugeStart, GaugeSpan / 2)), gaugeFill(50, charging = false))
        assertEquals(gaugeTrack(true), gaugeFill(100, charging = true))
        // Charging, three quarters fill the first arc and half of the second.
        val half = (GaugeSpan - GaugeChargeGap) / 2
        val fill = gaugeFill(75, charging = true)
        assertEquals(2, fill.size)
        assertEquals(half, fill[0].sweep, 0.001f)
        assertEquals(half / 2, fill[1].sweep, 0.001f)
        assertEquals(gaugeFill(100, false), gaugeFill(140, false))
    }

    @Test
    fun saverWinsOverChargingAndChargingOverALowBattery() {
        assertEquals(BatteryTone.PowerSave, batteryTone(BatteryState(10, charging = true, powerSave = true)))
        assertEquals(BatteryTone.Charging, batteryTone(BatteryState(10, charging = true, powerSave = false)))
        assertEquals(BatteryTone.Low, batteryTone(BatteryState(LowBatteryPercent, charging = false, powerSave = false)))
        assertEquals(BatteryTone.Normal, batteryTone(BatteryState(LowBatteryPercent + 1, charging = false, powerSave = false)))
    }

    @Test
    fun aFullBatteryOnTheChargerStillShowsTheBolt() {
        assertEquals(BatteryState(100, charging = true, powerSave = false),
            batteryOf(100, 100, BatteryManager.BATTERY_STATUS_FULL, BatteryManager.BATTERY_PLUGGED_AC, false))
        assertEquals(BatteryState(100, charging = false, powerSave = false),
            batteryOf(100, 100, BatteryManager.BATTERY_STATUS_FULL, 0, false))
        assertEquals(BatteryState(64, charging = false, powerSave = true),
            batteryOf(16, 25, BatteryManager.BATTERY_STATUS_DISCHARGING, 0, true))
        assertNull(batteryOf(-1, 100, BatteryManager.BATTERY_STATUS_UNKNOWN, 0, false))
        // A "charging" status with nothing plugged in is stale: no bolt.
        assertEquals(BatteryState(64, charging = false, powerSave = false),
            batteryOf(64, 100, BatteryManager.BATTERY_STATUS_CHARGING, 0, false))
    }

    @Test
    fun wifiUnderAVpnIsStillWifi() {
        assertEquals(DeviceNetwork.Wifi, networkOf(connected = true, wifi = true, cellular = false, ethernet = false, generation = "LTE"))
        assertEquals(DeviceNetwork.Cellular("5G"), networkOf(true, wifi = false, cellular = true, ethernet = false, generation = "5G"))
        assertEquals(DeviceNetwork.Ethernet, networkOf(true, wifi = false, cellular = false, ethernet = true, generation = null))
        assertEquals(DeviceNetwork.None, networkOf(false, wifi = true, cellular = false, ethernet = false, generation = null))
    }

    @Test
    fun theGenerationIsWhatTheStatusBarShows() {
        val none = TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE
        // LTE anchored to 5G shows 5G, as the status bar does.
        assertEquals("5G", cellularGeneration(TelephonyManager.NETWORK_TYPE_LTE, TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA))
        assertEquals("5G", cellularGeneration(TelephonyManager.NETWORK_TYPE_NR, none))
        assertEquals("LTE", cellularGeneration(TelephonyManager.NETWORK_TYPE_LTE, none))
        assertEquals("LTE", cellularGeneration(TelephonyManager.NETWORK_TYPE_LTE, TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA))
        assertEquals("3G", cellularGeneration(TelephonyManager.NETWORK_TYPE_HSPAP, none))
        assertEquals("3G", cellularGeneration(TelephonyManager.NETWORK_TYPE_UMTS, none))
        assertEquals("2G", cellularGeneration(TelephonyManager.NETWORK_TYPE_EDGE, none))
        assertEquals("2G", cellularGeneration(TelephonyManager.NETWORK_TYPE_GPRS, none))
        assertNull(cellularGeneration(TelephonyManager.NETWORK_TYPE_UNKNOWN, none))
    }

    @Test
    fun theGaugeSaysItsStateInWords() {
        assertEquals("Battery 72 percent, charging, 5G",
            gaugeDescription(BatteryState(72, charging = true, powerSave = false), DeviceNetwork.Cellular("5G")))
        assertEquals("Battery 45 percent, battery saver on, Wi-Fi",
            gaugeDescription(BatteryState(45, charging = false, powerSave = true), DeviceNetwork.Wifi))
        assertEquals("Battery 9 percent, mobile network",
            gaugeDescription(BatteryState(9, charging = false, powerSave = false), DeviceNetwork.Cellular(null)))
    }

    @Test fun theRailCountsTheShownTabAmongTheSessionsTabs() {
        // Rail board: "2 / 4".
        val page = railPage(listOf("a", "b", "c", "d"), "b")
        org.junit.Assert.assertEquals("2 / 4", page?.text)
        org.junit.Assert.assertEquals("Tab 2 of 4", page?.description)
        org.junit.Assert.assertEquals(RailPage(4, 4), railPage(listOf("a", "b", "c", "d"), "d"))
        // A single tab, or none shown, has nothing to count.
        org.junit.Assert.assertNull(railPage(listOf("a"), "a"))
        org.junit.Assert.assertNull(railPage(listOf("a", "b"), null))
        org.junit.Assert.assertNull(railPage(listOf("a", "b"), "gone"))
    }
}
