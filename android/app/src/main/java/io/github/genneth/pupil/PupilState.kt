package io.github.genneth.pupil

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

sealed interface BroadcastStatus {
    data object Stopped : BroadcastStatus
    data object Starting : BroadcastStatus
    data class Broadcasting(val sensor: String) : BroadcastStatus
    data class Failed(val message: String) : BroadcastStatus
}

val BroadcastStatus.isActive: Boolean
    get() = this is BroadcastStatus.Starting || this is BroadcastStatus.Broadcasting

data class PupilUiState(
    val status: BroadcastStatus = BroadcastStatus.Stopped,
    val lux: Float? = null,
    val packetId: Int = 0,
)

/** Process-level bridge from the service to the UI. Service writes, UI collects. */
object PupilState {
    private val _state = MutableStateFlow(PupilUiState())
    val state: StateFlow<PupilUiState> = _state.asStateFlow()
    fun update(transform: (PupilUiState) -> PupilUiState) = _state.update(transform)
}
