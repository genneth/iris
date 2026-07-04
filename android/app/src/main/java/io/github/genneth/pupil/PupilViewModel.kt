package io.github.genneth.pupil

import android.app.Application
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
