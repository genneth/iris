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

enum class TxPower(val key: String, val advertiseLevel: Int) {
    ULTRA_LOW("ultra_low", AdvertisingSetParameters.TX_POWER_ULTRA_LOW),
    LOW("low", AdvertisingSetParameters.TX_POWER_LOW),
    MEDIUM("medium", AdvertisingSetParameters.TX_POWER_MEDIUM),
    HIGH("high", AdvertisingSetParameters.TX_POWER_HIGH);

    companion object {
        fun fromKey(key: String): TxPower = entries.firstOrNull { it.key == key } ?: LOW
    }
}

data class PupilSettings(
    val intervalMs: Int = 400,
    val txPower: TxPower = TxPower.LOW,
    val deadbandPct: Int = 5,
    val heartbeatS: Int = 10,
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
            intervalMs = p[Keys.interval] ?: 400,
            txPower = TxPower.fromKey(p[Keys.txPower] ?: TxPower.LOW.key),
            deadbandPct = p[Keys.deadband] ?: 5,
            heartbeatS = p[Keys.heartbeat] ?: 10,
        )
    }

    suspend fun setIntervalMs(v: Int) = store.edit { it[Keys.interval] = v }
    suspend fun setTxPower(v: TxPower) = store.edit { it[Keys.txPower] = v.key }
    suspend fun setDeadbandPct(v: Int) = store.edit { it[Keys.deadband] = v }
    suspend fun setHeartbeatS(v: Int) = store.edit { it[Keys.heartbeat] = v }
}
