package io.github.genneth.pupil

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : AppCompatActivity() {

    private val ui = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            render()
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // targetSdk 35 enforces edge-to-edge: the window draws behind the system
        // bars, so pad the root view by the system-bar insets (on top of its own
        // 24dp padding) or content lands under the status bar.
        val root = findViewById<LinearLayout>(R.id.root)
        val basePad = root.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(
                basePad + bars.left, basePad + bars.top,
                basePad + bars.right, basePad + bars.bottom,
            )
            insets
        }

        findViewById<Button>(R.id.toggleButton).setOnClickListener {
            if (PupilState.running) {
                stopService(Intent(this, PupilService::class.java))
            } else {
                ensurePermissionsThenStart()
            }
        }
        findViewById<Button>(R.id.batteryButton).setOnClickListener {
            // Load-bearing, not just ColorOS appeasement: Doze ignores wakelocks
            // without this exemption (spec §4a).
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        }
        findViewById<Button>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<TextView>(R.id.sensorReport).text = sensorReport()
    }

    override fun onResume() {
        super.onResume()
        ui.post(refresh)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(refresh)
    }

    private fun ensurePermissionsThenStart() {
        val wanted = arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.POST_NOTIFICATIONS)
        val missing = wanted.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startForegroundService(Intent(this, PupilService::class.java))
        } else {
            requestPermissions(missing.toTypedArray(), 1)
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<String>, granted: IntArray) {
        super.onRequestPermissionsResult(code, perms, granted)
        if (granted.isNotEmpty() && granted.all { it == PackageManager.PERMISSION_GRANTED }) {
            startForegroundService(Intent(this, PupilService::class.java))
        } else {
            android.widget.Toast.makeText(
                this,
                "Bluetooth-advertise or notification permission denied — enable in Settings → Apps → Pupil",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun render() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val exempt = if (pm.isIgnoringBatteryOptimizations(packageName)) "exempt" else "NOT exempt"
        findViewById<TextView>(R.id.statusText).text =
            if (PupilState.running) "broadcasting · ${PupilState.sensorRung} · battery: $exempt"
            else "stopped · battery: $exempt"
        findViewById<TextView>(R.id.luxText).text =
            PupilState.lastLux?.let { "%.1f lx  ·  #%d".format(it, PupilState.packetId) } ?: "—"
        findViewById<Button>(R.id.toggleButton).text =
            if (PupilState.running) "Stop broadcasting" else "Start broadcasting"
    }

    /** Spec §4: per-device sensor facts nobody has published for the Find N6. */
    private fun sensorReport(): String {
        val sm = getSystemService(SENSOR_SERVICE) as SensorManager
        val wakeup = sm.getDefaultSensor(Sensor.TYPE_LIGHT, true)
        val default = sm.getDefaultSensor(Sensor.TYPE_LIGHT)
        return buildString {
            appendLine("wakeup ALS: ${wakeup?.name ?: "none"}")
            appendLine("default ALS: ${default?.name ?: "none"}")
            // Prefer the non-wakeup default sensor for the detail lines, but fall back to the
            // wakeup variant so a wakeup-only device (no non-wakeup default) still records
            // fifoMax etc. for the acceptance step.
            (default ?: wakeup)?.let {
                appendLine("  vendor=${it.vendor} maxRange=${it.maximumRange} lx")
                appendLine("  fifoMax=${it.fifoMaxEventCount} isWakeUp=${it.isWakeUpSensor}")
            }
        }.trimEnd()
    }
}
