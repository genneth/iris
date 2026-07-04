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
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class MainActivity : AppCompatActivity() {

    private val ui = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            render()
            ui.postDelayed(this, 1000)
        }
    }

    // Denied at least once this session: render a graceful disabled state instead of
    // nudging toward system settings (current guidance says never deep-link there).
    private var permissionsRefused = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            permissionsRefused = false
            startForegroundService(Intent(this, PupilService::class.java))
        } else {
            permissionsRefused = true
        }
        render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        // Edge-to-edge (enforced from targetSdk 35): pad the root by the system-bar
        // and cutout insets on top of its own 24dp padding, and consume them.
        val root = findViewById<LinearLayout>(R.id.root)
        val basePad = root.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.updatePadding(
                left = basePad + insets.left, top = basePad + insets.top,
                right = basePad + insets.right, bottom = basePad + insets.bottom,
            )
            WindowInsetsCompat.CONSUMED
        }

        findViewById<Button>(R.id.toggleButton).setOnClickListener {
            if (PupilState.running) {
                stopService(Intent(this, PupilService::class.java))
            } else {
                ensurePermissionsThenStart()
            }
        }
        findViewById<Button>(R.id.batteryButton).setOnClickListener { requestBatteryExemption() }
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

    private val wantedPermissions = arrayOf(
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    private fun ensurePermissionsThenStart() {
        val missing = wantedPermissions.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        when {
            missing.isEmpty() ->
                startForegroundService(Intent(this, PupilService::class.java))
            missing.any { shouldShowRequestPermissionRationale(it) } -> {
                // Rationale step (with a real decline option) before re-asking.
                MaterialAlertDialogBuilder(this)
                    .setTitle("Why these permissions?")
                    .setMessage(
                        "Pupil broadcasts the light sensor as Bluetooth adverts — Android calls " +
                            "that “Nearby devices”. The notification shows the live reading " +
                            "while broadcasting runs."
                    )
                    .setPositiveButton("Continue") { _, _ ->
                        permissionLauncher.launch(missing.toTypedArray())
                    }
                    .setNegativeButton("No thanks", null)
                    .show()
            }
            else -> permissionLauncher.launch(missing.toTypedArray())
        }
    }

    /** Explain-first, and only when not already exempt (spec §4a: Doze ignores wakelocks). */
    private fun requestBatteryExemption() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            render()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Allow unrestricted battery use?")
            .setMessage(
                "With the phone idle, Doze ignores wakelocks and screen-off broadcasting " +
                    "freezes. The exemption keeps the light sensor streaming while the screen " +
                    "is off. Costs roughly 1% battery per hour while broadcasting."
            )
            .setPositiveButton("Request") { _, _ ->
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(Uri.parse("package:$packageName"))
                )
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun render() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val exempt = if (pm.isIgnoringBatteryOptimizations(packageName)) "exempt" else "NOT exempt"
        val granted = wantedPermissions.all {
            checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
        findViewById<TextView>(R.id.statusText).text = when {
            PupilState.running -> "broadcasting · ${PupilState.sensorRung} · battery: $exempt"
            permissionsRefused && !granted ->
                "nearby-devices / notification permission refused — Pupil cannot broadcast"
            else -> "stopped · battery: $exempt"
        }
        findViewById<TextView>(R.id.luxText).text =
            PupilState.lastLux?.let { "%.1f lx  ·  #%d".format(it, PupilState.packetId) } ?: "—"
        findViewById<Button>(R.id.toggleButton).text =
            if (PupilState.running) "Stop broadcasting" else "Start broadcasting"
        findViewById<Button>(R.id.batteryButton).text =
            if (pm.isIgnoringBatteryOptimizations(packageName)) "Battery: exempt ✓"
            else "Battery exemption…"
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
