package io.github.genneth.pupil

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {

    private val vm: PupilViewModel by viewModels()

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results -> if (results.values.all { it }) vm.start() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PupilTheme {
                Surface {
                    val ui by vm.ui.collectAsStateWithLifecycle()
                    Column(Modifier.safeDrawingPadding().padding(24.dp)) {
                        Text("Pupil", style = MaterialTheme.typography.titleLarge)
                        Text(ui.lux?.let { "%.1f lx · #%d".format(it, ui.packetId) } ?: "—",
                            style = MaterialTheme.typography.displayMedium)
                        Text(if (ui.running) "broadcasting · ${ui.sensorRung}" else "stopped",
                            style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = { if (ui.running) vm.stop() else ensurePermsThenStart() }) {
                            Text(if (ui.running) "Stop broadcasting" else "Start broadcasting")
                        }
                    }
                }
            }
        }
    }

    private fun ensurePermsThenStart() {
        val wanted = arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.POST_NOTIFICATIONS)
        val missing = wanted.filter {
            checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) vm.start() else permLauncher.launch(missing.toTypedArray())
    }
}
