package io.github.genneth.pupil

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

enum class PupilLayout { FOLDED, UNFOLDED }

@Composable
fun PupilScreen(
    ui: PupilUiState,
    settings: PupilSettings,
    layout: PupilLayout,
    sensorReport: String,
    batteryExempt: Boolean,
    onToggle: () -> Unit,
    onBattery: () -> Unit,
    onInterval: (AdvertInterval) -> Unit,
    onTxPower: (TxPower) -> Unit,
    onDeadband: (Deadband) -> Unit,
    onHeartbeat: (Heartbeat) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest) {
        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            if (layout == PupilLayout.FOLDED) {
                Column(
                    Modifier
                        .align(Alignment.TopCenter)
                        .widthIn(max = 560.dp)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Wordmark()
                    HeroCard(ui, onToggle, spacious = false, modifier = Modifier.fillMaxWidth())
                    SettingsCard(ui, settings, onInterval, onTxPower, onDeadband, onHeartbeat)
                    DeviceCard(batteryExempt, sensorReport, onBattery)
                }
            } else {
                Row(
                    Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    Column(
                        Modifier.weight(0.9f).fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        Wordmark()
                        HeroCard(
                            ui,
                            onToggle,
                            spacious = true,
                            modifier = Modifier.fillMaxWidth().weight(1f),
                        )
                    }
                    Column(
                        Modifier.weight(1.1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        SettingsCard(ui, settings, onInterval, onTxPower, onDeadband, onHeartbeat)
                        DeviceCard(batteryExempt, sensorReport, onBattery)
                    }
                }
            }
        }
    }
}

@Composable
private fun Wordmark() {
    Column {
        Text(
            "Pupil",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "Ambient light, broadcast gently",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HeroCard(
    ui: PupilUiState,
    onToggle: () -> Unit,
    spacious: Boolean,
    modifier: Modifier = Modifier,
) {
    val active = ui.status.isActive
    Card(
        modifier = modifier.testTag("hero"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(
            Modifier.then(if (spacious) Modifier.fillMaxSize() else Modifier.fillMaxWidth()).padding(24.dp),
        ) {
            StatusLine(ui.status)
            if (spacious) {
                Box(
                    Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    LuxReadout(ui)
                }
            } else {
                Spacer(Modifier.size(18.dp))
                LuxReadout(ui)
                Spacer(Modifier.size(24.dp))
            }
            Button(
                onClick = onToggle,
                enabled = ui.status !is BroadcastStatus.Starting,
                modifier = Modifier.fillMaxWidth().testTag("broadcast-toggle"),
            ) {
                Text(if (active) "Stop broadcasting" else "Start broadcasting")
            }
        }
    }
}

@Composable
private fun LuxReadout(ui: PupilUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                ui.lux?.let { "%.1f".format(it) } ?: "—",
                style = MaterialTheme.typography.displayLarge.copy(
                    fontFeatureSettings = "tnum",
                    fontWeight = FontWeight.Medium,
                ),
                modifier = Modifier.testTag("lux"),
            )
            Text(
                " lx",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
                modifier = Modifier.padding(bottom = 9.dp),
            )
        }
        if (ui.lux != null) {
            Text(
                "BTHome packet #${ui.packetId}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
            )
        }
    }
}

@Composable
private fun StatusLine(status: BroadcastStatus) {
    val (title, detail, color) = when (status) {
        BroadcastStatus.Stopped -> Triple(
            "Ready",
            "Start when the phone is near your laptop.",
            MaterialTheme.colorScheme.outline,
        )
        BroadcastStatus.Starting -> Triple(
            "Starting Bluetooth…",
            "Finding the light sensor and opening the beacon.",
            MaterialTheme.colorScheme.tertiary,
        )
        is BroadcastStatus.Broadcasting -> Triple(
            "Broadcasting nearby",
            status.sensor,
            MaterialTheme.colorScheme.primary,
        )
        is BroadcastStatus.Failed -> Triple(
            "Needs attention",
            status.message,
            MaterialTheme.colorScheme.error,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Surface(
            Modifier.padding(top = 5.dp).size(10.dp),
            shape = CircleShape,
            color = color,
            content = {},
        )
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f),
                modifier = Modifier.testTag("broadcast-status"),
            )
        }
    }
}

@Composable
private fun SettingsCard(
    ui: PupilUiState,
    settings: PupilSettings,
    onInterval: (AdvertInterval) -> Unit,
    onTxPower: (TxPower) -> Unit,
    onDeadband: (Deadband) -> Unit,
    onHeartbeat: (Heartbeat) -> Unit,
) {
    val enabled = !ui.status.isActive
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column {
                Text("Broadcast", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    "Tune proximity, responsiveness and liveness.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!enabled) {
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().testTag("settings-lock"),
                ) {
                    Text(
                        "Stop broadcasting to change these values. They are applied together on the next start.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
            SettingRow(
                title = "Advertising interval",
                supporting = "How often the radio repeats the current packet",
                current = settings.interval.label,
                options = AdvertInterval.entries.map { it to it.label },
                enabled = enabled,
                testTag = "setting-interval",
                onPick = onInterval,
            )
            SettingRow(
                title = "TX power",
                supporting = "Low keeps the phone relevant to this room",
                current = settings.txPower.label,
                options = TxPower.entries.map { it to it.label },
                enabled = enabled,
                testTag = "setting-tx-power",
                onPick = onTxPower,
            )
            SettingRow(
                title = "Deadband",
                supporting = "Ignore small sensor fluctuations",
                current = settings.deadband.label,
                options = Deadband.entries.map { it to it.label },
                enabled = enabled,
                testTag = "setting-deadband",
                onPick = onDeadband,
            )
            SettingRow(
                title = "Heartbeat",
                supporting = settings.heartbeat.receiverGuidance ?: "Lets iris distinguish steady light from silence",
                current = settings.heartbeat.label,
                options = Heartbeat.entries.map { it to it.label },
                enabled = enabled,
                testTag = "setting-heartbeat",
                onPick = onHeartbeat,
            )
        }
    }
}

@Composable
private fun DeviceCard(batteryExempt: Boolean, sensorReport: String, onBattery: () -> Unit) {
    var detailsOpen by remember { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Screen-off reliability", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (batteryExempt) "Battery protection is configured" else "Battery exemption still needed",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (batteryExempt) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }
                if (batteryExempt) {
                    FilledTonalButton(onClick = {}, enabled = false) { Text("Protected ✓") }
                } else {
                    OutlinedButton(onClick = onBattery, modifier = Modifier.testTag("battery-setup")) {
                        Text("Set up")
                    }
                }
            }
            TextButton(onClick = { detailsOpen = !detailsOpen }, modifier = Modifier.testTag("device-details")) {
                Text(if (detailsOpen) "Hide device details" else "Show device details")
            }
            if (detailsOpen) {
                Text(
                    sensorReport,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("sensor-report"),
                )
            }
        }
    }
}

@Composable
private fun <T> SettingRow(
    title: String,
    supporting: String,
    current: String,
    options: List<Pair<T, String>>,
    enabled: Boolean,
    testTag: String,
    onPick: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                supporting,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            OutlinedButton(
                onClick = { open = true },
                enabled = enabled,
                modifier = Modifier.widthIn(min = 112.dp).testTag(testTag),
            ) {
                Text(current)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (value, text) ->
                    DropdownMenuItem(
                        text = { Text(text) },
                        onClick = {
                            onPick(value)
                            open = false
                        },
                    )
                }
            }
        }
    }
}
