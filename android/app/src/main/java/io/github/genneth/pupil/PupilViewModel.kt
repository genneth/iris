package io.github.genneth.pupil

import android.app.Application
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PupilViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = SettingsRepository(app)

    val ui: StateFlow<PupilUiState> = PupilState.state
    val settings: StateFlow<PupilSettings> =
        repo.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PupilSettings())

    /** Hardware facts for the ambient-light sensor(s), captured once at startup (Find N6 acceptance aid). */
    val sensorReport: String = run {
        val sm = getApplication<Application>().getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val wakeup = sm.getDefaultSensor(Sensor.TYPE_LIGHT, true)
        val default = sm.getDefaultSensor(Sensor.TYPE_LIGHT)
        buildString {
            appendLine("wakeup ALS: ${wakeup?.name ?: "none"}")
            appendLine("default ALS: ${default?.name ?: "none"}")
            (default ?: wakeup)?.let {
                appendLine("  vendor=${it.vendor} maxRange=${it.maximumRange} lx")
                append("  fifoMax=${it.fifoMaxEventCount} isWakeUp=${it.isWakeUpSensor}")
            }
        }
    }

    fun start() {
        val app = getApplication<Application>()
        app.startForegroundService(PupilService.startIntent(app, settings.value))
    }

    fun stop() {
        val app = getApplication<Application>()
        app.stopService(android.content.Intent(app, PupilService::class.java))
    }

    fun setIntervalMs(v: Int) = viewModelScope.launch { repo.setIntervalMs(v) }
    fun setTxPower(v: TxPower) = viewModelScope.launch { repo.setTxPower(v) }
    fun setDeadbandPct(v: Int) = viewModelScope.launch { repo.setDeadbandPct(v) }
    fun setHeartbeatS(v: Int) = viewModelScope.launch { repo.setHeartbeatS(v) }
}
