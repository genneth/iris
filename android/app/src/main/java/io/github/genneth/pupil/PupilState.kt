package io.github.genneth.pupil

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class PupilUiState(
    val running: Boolean = false,
    val lux: Float? = null,
    val packetId: Int = 0,
    val sensorRung: String = "not started",
)

/** Process-level bridge from the service to the UI. Service writes, UI collects. */
object PupilState {
    private val _state = MutableStateFlow(PupilUiState())
    val state: StateFlow<PupilUiState> = _state.asStateFlow()
    fun update(transform: (PupilUiState) -> PupilUiState) = _state.update(transform)
}
