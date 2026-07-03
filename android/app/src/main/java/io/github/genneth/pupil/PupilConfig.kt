package io.github.genneth.pupil

import android.bluetooth.le.AdvertisingSetParameters
import android.content.Context
import androidx.preference.PreferenceManager

/**
 * Spec §6 knobs, SharedPreferences-backed; most knobs apply on next service start, but
 * [heartbeatMs] is re-read every tick and so live-applies while the service is running.
 */
class PupilConfig(context: Context) {
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

    /** AdvertisingSetParameters.setInterval units are 0.625 ms: 400 ms -> 640. */
    val intervalUnits: Int
        get() = (prefs.getString("interval_ms", "400")!!.toInt() * 1000) / 625

    val txPowerLevel: Int
        get() = when (prefs.getString("tx_power", "low")) {
            "ultra_low" -> AdvertisingSetParameters.TX_POWER_ULTRA_LOW
            "medium" -> AdvertisingSetParameters.TX_POWER_MEDIUM
            "high" -> AdvertisingSetParameters.TX_POWER_HIGH
            else -> AdvertisingSetParameters.TX_POWER_LOW
        }

    val heartbeatMs: Long
        get() = prefs.getString("heartbeat_s", "10")!!.toLong() * 1000

    val deadbandFraction: Float
        get() = prefs.getString("deadband_pct", "5")!!.toFloat() / 100f

    val minGapMs: Long = 500
    val deadbandAbsLux: Float = 1f
}
