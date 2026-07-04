package io.github.genneth.pupil

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** True (single column) for compact/medium width, false (two-pane) for expanded. */
@Composable
fun PupilScreen(
    ui: PupilUiState,
    settings: PupilSettings,
    singleColumn: Boolean,
    sensorReport: String,
    batteryExempt: Boolean,
    onToggle: () -> Unit,
    onBattery: () -> Unit,
    onInterval: (Int) -> Unit,
    onTxPower: (TxPower) -> Unit,
    onDeadband: (Int) -> Unit,
    onHeartbeat: (Int) -> Unit,
) {
    val readout: @Composable () -> Unit = { Readout(ui, onToggle) }
    val controls: @Composable () -> Unit = {
        Controls(
            settings, onBattery, onInterval, onTxPower, onDeadband, onHeartbeat,
            ui.sensorRung, sensorReport, batteryExempt,
        )
    }
    if (singleColumn) {
        Column(
            Modifier.safeDrawingPadding().padding(24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) { readout(); controls() }
    } else {
        Row(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp)) {
            Column(Modifier.width(320.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { readout() }
            Column(
                Modifier.padding(start = 24.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) { controls() }
        }
    }
}

@Composable
private fun Readout(ui: PupilUiState, onToggle: () -> Unit) {
    Text("Pupil", style = MaterialTheme.typography.titleLarge)
    Text(
        ui.lux?.let { "%.1f lx · #%d".format(it, ui.packetId) } ?: "—",
        style = MaterialTheme.typography.displayMedium.copy(
            fontFeatureSettings = "tnum"
        ),
    )
    Text(
        if (ui.running) "broadcasting · ${ui.sensorRung}" else "stopped",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(onClick = onToggle, modifier = Modifier.fillMaxWidth()) {
        Text(if (ui.running) "Stop broadcasting" else "Start broadcasting")
    }
}

@Composable
private fun Controls(
    settings: PupilSettings,
    onBattery: () -> Unit,
    onInterval: (Int) -> Unit,
    onTxPower: (TxPower) -> Unit,
    onDeadband: (Int) -> Unit,
    onHeartbeat: (Int) -> Unit,
    sensorRung: String,
    sensorReport: String,
    batteryExempt: Boolean,
) {
    Setting("Advertising interval", "${settings.intervalMs} ms",
        listOf(100 to "100 ms", 250 to "250 ms", 400 to "400 ms", 1000 to "1 s"), onInterval)
    Setting("TX power", settings.txPower.label(),
        TxPower.entries.map { it to it.label() }, onTxPower)
    Setting("Deadband", "${settings.deadbandPct} %",
        listOf(1 to "1 %", 5 to "5 %", 10 to "10 %", 20 to "20 %"), onDeadband)
    Setting("Heartbeat", "${settings.heartbeatS} s",
        listOf(5 to "5 s", 10 to "10 s", 30 to "30 s (needs receiver --stale-after ≥75)",
            60 to "60 s (needs receiver --stale-after ≥150)"), onHeartbeat)
    FilledTonalButton(onClick = onBattery, modifier = Modifier.fillMaxWidth()) {
        Text(if (batteryExempt) "Battery: exempt ✓" else "Battery exemption…")
    }
    Text(sensorReport, style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(sensorReportLabel(sensorRung), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun TxPower.label(): String = when (this) {
    TxPower.ULTRA_LOW -> "Ultra low (−21 dBm)"
    TxPower.LOW -> "Low (−15 dBm)"
    TxPower.MEDIUM -> "Medium (−7 dBm)"
    TxPower.HIGH -> "High (+1 dBm)"
}

private fun sensorReportLabel(rung: String): String = "sensor: $rung"

@Composable
private fun <T> Setting(label: String, current: String, options: List<Pair<T, String>>, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    FilledTonalButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
        Text("$label: $current")
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        options.forEach { (value, text) ->
            DropdownMenuItem(text = { Text(text) }, onClick = { onPick(value); open = false })
        }
    }
}
