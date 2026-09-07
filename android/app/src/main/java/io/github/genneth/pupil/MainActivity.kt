package io.github.genneth.pupil

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {

    private val vm: PupilViewModel by viewModels()

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            vm.start()
        } else {
            vm.reportFailure(
                "Bluetooth advertising and notifications must be allowed before Pupil can broadcast."
            )
        }
    }

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PupilTheme {
                Surface(modifier = androidx.compose.ui.Modifier.fillMaxSize()) {
                    val ui by vm.ui.collectAsStateWithLifecycle()
                    val settings by vm.settings.collectAsStateWithLifecycle()
                    val widthClass = calculateWindowSizeClass(this).widthSizeClass
                    // The Find N6 cover is Compact (413dp), while its inner display lands in
                    // Medium (813dp) depending on system-bar posture. Treating every
                    // non-Compact window as unfolded keeps the hinge transition stable.
                    val layout = if (widthClass == WindowWidthSizeClass.Compact) {
                        PupilLayout.FOLDED
                    } else {
                        PupilLayout.UNFOLDED
                    }
                    var showBatteryDialog by remember { mutableStateOf(false) }
                    var showPermissionDialog by remember { mutableStateOf(false) }

                    val lifecycleOwner = LocalLifecycleOwner.current
                    var batteryExempt by remember { mutableStateOf(isBatteryExempt()) }
                    DisposableEffect(lifecycleOwner) {
                        val obs = LifecycleEventObserver { _, event ->
                            if (event == Lifecycle.Event.ON_RESUME) batteryExempt = isBatteryExempt()
                        }
                        lifecycleOwner.lifecycle.addObserver(obs)
                        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
                    }

                    PupilScreen(
                        ui = ui,
                        settings = settings,
                        layout = layout,
                        sensorReport = vm.sensorReport,
                        batteryExempt = batteryExempt,
                        onToggle = {
                            if (ui.status.isActive) {
                                vm.stop()
                            } else if (missingPermissions().isEmpty()) {
                                vm.start()
                            } else {
                                showPermissionDialog = true
                            }
                        },
                        onBattery = { if (!batteryExempt) showBatteryDialog = true },
                        onInterval = vm::setInterval,
                        onTxPower = vm::setTxPower,
                        onDeadband = vm::setDeadband,
                        onHeartbeat = vm::setHeartbeat,
                    )

                    if (showPermissionDialog) {
                        AlertDialog(
                            onDismissRequest = { showPermissionDialog = false },
                            title = { Text("Let Pupil broadcast nearby?") },
                            text = {
                                Text(
                                    "Pupil needs Bluetooth advertising to send lux and notification " +
                                        "permission to keep its screen-off service visible. It never " +
                                        "pairs, scans, or learns nearby device identities."
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    showPermissionDialog = false
                                    permLauncher.launch(missingPermissions().toTypedArray())
                                }) { Text("Continue") }
                            },
                            dismissButton = {
                                TextButton(onClick = { showPermissionDialog = false }) { Text("Not now") }
                            },
                        )
                    }

                    if (showBatteryDialog) {
                        AlertDialog(
                            onDismissRequest = { showBatteryDialog = false },
                            title = { Text("Allow unrestricted battery use?") },
                            text = {
                                Text(
                                    "With the phone idle, Doze ignores wakelocks and screen-off " +
                                        "broadcasting freezes. The exemption keeps the light sensor " +
                                        "streaming while the screen is off (~1%/h while broadcasting)."
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    showBatteryDialog = false
                                    startActivity(
                                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                            .setData(Uri.parse("package:$packageName"))
                                    )
                                }) { Text("Request") }
                            },
                            dismissButton = {
                                TextButton(onClick = { showBatteryDialog = false }) { Text("Not now") }
                            },
                        )
                    }
                }
            }
        }
    }

    private fun isBatteryExempt(): Boolean =
        (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)

    private fun missingPermissions(): List<String> = missingStartPermissions(this)
}
