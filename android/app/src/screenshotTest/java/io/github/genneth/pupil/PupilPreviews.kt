package io.github.genneth.pupil

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest

private const val SENSOR_REPORT = """wakeup ALS: none
default ALS: OPLUS Fusion Light Sensor Next Gen
  vendor=OPLUS maxRange=65535.0 lx
  fifoMax=0 isWakeUp=false"""

@PreviewTest
@Preview(name = "Find N6 folded ready", widthDp = 413, heightDp = 948, showBackground = true)
@Composable
fun FoldedReadyPreview() {
    PreviewScreen(PupilLayout.FOLDED, PupilUiState())
}

@PreviewTest
@Preview(name = "Find N6 folded broadcasting", widthDp = 413, heightDp = 948, showBackground = true)
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
@Preview(name = "Find N6 unfolded broadcasting", widthDp = 813, heightDp = 898, showBackground = true)
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
@Preview(
    name = "Find N6 unfolded failure dark",
    widthDp = 813,
    heightDp = 898,
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
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
