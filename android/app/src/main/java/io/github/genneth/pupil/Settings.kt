package io.github.genneth.pupil

import android.bluetooth.le.AdvertisingSetParameters
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class AdvertInterval(val millis: Int, val label: String) {
    MS_100(100, "100 ms"),
    MS_250(250, "250 ms"),
    MS_400(400, "400 ms"),
    S_1(1_000, "1 second");

    companion object {
        fun fromMillis(value: Int): AdvertInterval = entries.firstOrNull { it.millis == value } ?: MS_400
    }
}

enum class TxPower(val key: String, val advertiseLevel: Int, val label: String) {
    ULTRA_LOW("ultra_low", AdvertisingSetParameters.TX_POWER_ULTRA_LOW, "Ultra low (−21 dBm)"),
    LOW("low", AdvertisingSetParameters.TX_POWER_LOW, "Low (−15 dBm)"),
    MEDIUM("medium", AdvertisingSetParameters.TX_POWER_MEDIUM, "Medium (−7 dBm)"),
    HIGH("high", AdvertisingSetParameters.TX_POWER_HIGH, "High (+1 dBm)");

    companion object {
        fun fromKey(key: String): TxPower = entries.firstOrNull { it.key == key } ?: LOW
    }
}

enum class Deadband(val percent: Int, val label: String) {
    PCT_1(1, "1 %"),
    PCT_5(5, "5 %"),
    PCT_10(10, "10 %"),
    PCT_20(20, "20 %");

    companion object {
        fun fromPercent(value: Int): Deadband = entries.firstOrNull { it.percent == value } ?: PCT_5
    }
}

enum class Heartbeat(val seconds: Int, val label: String, val receiverGuidance: String?) {
    S_5(5, "5 seconds", null),
    S_10(10, "10 seconds", null),
    S_30(30, "30 seconds", "Receiver stale-after must be at least 75 s"),
    S_60(60, "60 seconds", "Receiver stale-after must be at least 150 s");

    companion object {
        fun fromSeconds(value: Int): Heartbeat = entries.firstOrNull { it.seconds == value } ?: S_10
    }
}

data class PupilSettings(
    val interval: AdvertInterval = AdvertInterval.MS_400,
    val txPower: TxPower = TxPower.LOW,
    val deadband: Deadband = Deadband.PCT_5,
    val heartbeat: Heartbeat = Heartbeat.S_10,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "pupil_settings")

class SettingsRepository(context: Context) {
    private val store = context.applicationContext.dataStore

    private object Keys {
        val interval = intPreferencesKey("interval_ms")
        val txPower = stringPreferencesKey("tx_power")
        val deadband = intPreferencesKey("deadband_pct")
        val heartbeat = intPreferencesKey("heartbeat_s")
    }

    val settings: Flow<PupilSettings> = store.data.map { p ->
        PupilSettings(
            interval = AdvertInterval.fromMillis(p[Keys.interval] ?: AdvertInterval.MS_400.millis),
            txPower = TxPower.fromKey(p[Keys.txPower] ?: TxPower.LOW.key),
            deadband = Deadband.fromPercent(p[Keys.deadband] ?: Deadband.PCT_5.percent),
            heartbeat = Heartbeat.fromSeconds(p[Keys.heartbeat] ?: Heartbeat.S_10.seconds),
        )
    }

    suspend fun setInterval(v: AdvertInterval) = store.edit { it[Keys.interval] = v.millis }
    suspend fun setTxPower(v: TxPower) = store.edit { it[Keys.txPower] = v.key }
    suspend fun setDeadband(v: Deadband) = store.edit { it[Keys.deadband] = v.percent }
    suspend fun setHeartbeat(v: Heartbeat) = store.edit { it[Keys.heartbeat] = v.seconds }
}
