package io.github.genneth.pupil

import androidx.compose.runtime.Composable
import com.android.tools.screenshot.PreviewTest
import io.github.genneth.haines.HainesFolded
import io.github.genneth.haines.HainesUnfolded
import io.github.genneth.haines.HainesUnfoldedDark

private const val SENSOR_REPORT = """wakeup ALS: none
default ALS: OPLUS Fusion Light Sensor Next Gen
  vendor=OPLUS maxRange=65535.0 lx
  fifoMax=0 isWakeUp=false"""

@PreviewTest
@HainesFolded
@Composable
fun FoldedReadyPreview() {
    PreviewScreen(PupilLayout.FOLDED, PupilUiState())
}

@PreviewTest
@HainesFolded
@Composable
fun FoldedBroadcastingPreview() {
    PreviewScreen(
        PupilLayout.FOLDED,
        PupilUiState(
            status = BroadcastStatus.Broadcasting("Non-wakeup ALS + wakelock · OPLUS Fusion Light Sensor"),
            lux = 143.5f,
            packetId = 42,
        ),
    )
}

@PreviewTest
@HainesUnfolded
@Composable
fun UnfoldedBroadcastingPreview() {
    PreviewScreen(
        PupilLayout.UNFOLDED,
        PupilUiState(
            status = BroadcastStatus.Broadcasting("Non-wakeup ALS + wakelock · OPLUS Fusion Light Sensor"),
            lux = 143.5f,
            packetId = 42,
        ),
    )
}

@PreviewTest
@HainesUnfoldedDark
@Composable
fun UnfoldedFailureDarkPreview() {
    PupilTheme(darkTheme = true, dynamicColor = false) {
        PupilScreen(
            ui = PupilUiState(status = BroadcastStatus.Failed("Bluetooth is off or BLE advertising is unavailable.")),
            settings = PupilSettings(),
            layout = PupilLayout.UNFOLDED,
            sensorReport = SENSOR_REPORT,
            batteryExempt = false,
            onToggle = {},
            onBattery = {},
            onInterval = {},
            onTxPower = {},
            onDeadband = {},
            onHeartbeat = {},
        )
    }
}

@Composable
private fun PreviewScreen(layout: PupilLayout, state: PupilUiState) {
    PupilTheme(darkTheme = false, dynamicColor = false) {
        PupilScreen(
            ui = state,
            settings = PupilSettings(),
            layout = layout,
            sensorReport = SENSOR_REPORT,
            batteryExempt = true,
            onToggle = {},
            onBattery = {},
            onInterval = {},
            onTxPower = {},
            onDeadband = {},
            onHeartbeat = {},
        )
    }
}
