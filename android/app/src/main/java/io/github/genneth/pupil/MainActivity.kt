package io.github.genneth.pupil

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {

    private val vm: PupilViewModel by viewModels()

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results -> if (results.values.all { it }) vm.start() }

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PupilTheme {
                Surface {
                    val ui by vm.ui.collectAsStateWithLifecycle()
                    val settings by vm.settings.collectAsStateWithLifecycle()
                    val widthClass = calculateWindowSizeClass(this).widthSizeClass
                    val singleColumn = widthClass != WindowWidthSizeClass.Expanded
                    var showBatteryDialog by remember { mutableStateOf(false) }

                    PupilScreen(
                        ui = ui,
                        settings = settings,
                        singleColumn = singleColumn,
                        onToggle = { if (ui.running) vm.stop() else ensurePermsThenStart() },
                        onBattery = {
                            val pm = getSystemService(POWER_SERVICE) as PowerManager
                            if (!pm.isIgnoringBatteryOptimizations(packageName)) showBatteryDialog = true
                        },
                        onInterval = vm::setIntervalMs,
                        onTxPower = vm::setTxPower,
                        onDeadband = vm::setDeadbandPct,
                        onHeartbeat = vm::setHeartbeatS,
                    )

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

    private fun ensurePermsThenStart() {
        val wanted = arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.POST_NOTIFICATIONS)
        val missing = wanted.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) vm.start() else permLauncher.launch(missing.toTypedArray())
    }
}
