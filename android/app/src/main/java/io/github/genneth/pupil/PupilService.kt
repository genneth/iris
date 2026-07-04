package io.github.genneth.pupil

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Foreground service (type connectedDevice — no FGS timeout on Android 14+):
 * ALS -> deadband/rate governor -> BTHome payload -> setAdvertisingData in
 * place. Event-driven with a heartbeat; no polling loop (spec §5).
 *
 * All advertise calls are guarded: BLUETOOTH_ADVERTISE is checked in
 * onStartCommand, and the activity only starts us after granting it.
 */
class PupilService : Service(), SensorEventListener {

    companion object {
        private const val TAG = "PupilService"
        private const val CHANNEL_ID = "pupil"
        private const val NOTIFICATION_ID = 1
        private val BTHOME_UUID: ParcelUuid =
            ParcelUuid.fromString("0000fcd2-0000-1000-8000-00805f9b34fb")

        const val EXTRA_INTERVAL_MS = "interval_ms"
        const val EXTRA_TX_LEVEL = "tx_level"
        const val EXTRA_DEADBAND_PCT = "deadband_pct"
        const val EXTRA_HEARTBEAT_S = "heartbeat_s"
        private const val MIN_GAP_MS = 500L
        private const val DEADBAND_ABS_LUX = 1f

        fun startIntent(context: Context, s: PupilSettings): Intent =
            Intent(context, PupilService::class.java)
                .putExtra(EXTRA_INTERVAL_MS, s.intervalMs)
                .putExtra(EXTRA_TX_LEVEL, s.txPower.advertiseLevel)
                .putExtra(EXTRA_DEADBAND_PCT, s.deadbandPct)
                .putExtra(EXTRA_HEARTBEAT_S, s.heartbeatS)
    }

    private var intervalUnits = 640          // 400 ms in 0.625 ms units
    private var txPowerLevel = AdvertisingSetParameters.TX_POWER_LOW
    private var heartbeatMs = 10_000L
    private lateinit var governor: UpdateGovernor
    private val handler = Handler(Looper.getMainLooper())

    private var sensorManager: SensorManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var advertisingSet: AdvertisingSet? = null

    private var latestLux = 0f
    private var packetId = 0
    private var inFlight = false        // a setAdvertisingData awaiting its callback
    private var sendQueued = false      // a send will happen as soon as it legally can

    private val heartbeat = object : Runnable {
        override fun run() {
            sendNow() // bumps packet id even when lux is unchanged: liveness signal
            handler.postDelayed(this, heartbeatMs)
        }
    }

    private val setCallback = object : AdvertisingSetCallback() {
        override fun onAdvertisingSetStarted(set: AdvertisingSet?, txPower: Int, status: Int) {
            if (status == ADVERTISE_SUCCESS && set != null) {
                advertisingSet = set
                sendNow()
            } else {
                Log.e(TAG, "advertising set failed to start: status=$status")
                stopSelf()
            }
        }

        override fun onAdvertisingSetStopped(set: AdvertisingSet?) {
            // Bluetooth toggled off/on invalidates the set — recreate it.
            // Pending-op callbacks for a torn-down set may never arrive, so unwedge here too.
            inFlight = false
            sendQueued = false
            advertisingSet = null
            if (PupilState.state.value.running) handler.postDelayed({ startAdvertising() }, 1000)
        }

        override fun onAdvertisingDataSet(set: AdvertisingSet?, status: Int) {
            // A late callback from a torn-down set must not clear the new set's in-flight state.
            if (set !== advertisingSet) return
            inFlight = false
            if (sendQueued) {
                sendQueued = false
                maybeSend()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A re-delivered/double start (e.g. the system redelivering the start intent, or a
        // fast double-tap before PupilState.running is observed) must not re-acquire the
        // sensor/wakelock or re-create the advertising set: doing so previously leaked a
        // wakelock and killed the existing set with ADVERTISE_FAILED_ALREADY_STARTED.
        if (PupilState.state.value.running) return START_STICKY
        if (checkSelfPermission(android.Manifest.permission.BLUETOOTH_ADVERTISE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            // Belt-and-braces: the activity already gates startup on this permission being
            // granted, so this path should be unreachable in practice. Deliberate tension —
            // we return before startForeground() rather than calling it, because starting a
            // connectedDevice-type FGS without BLUETOOTH_ADVERTISE throws SecurityException
            // on API 34+.
            Log.e(TAG, "BLUETOOTH_ADVERTISE not granted; refusing to start")
            stopSelf()
            return START_NOT_STICKY
        }
        val intervalMs = intent?.getIntExtra(EXTRA_INTERVAL_MS, 400) ?: 400
        intervalUnits = (intervalMs * 1000) / 625
        txPowerLevel = intent?.getIntExtra(EXTRA_TX_LEVEL, AdvertisingSetParameters.TX_POWER_LOW)
            ?: AdvertisingSetParameters.TX_POWER_LOW
        heartbeatMs = ((intent?.getIntExtra(EXTRA_HEARTBEAT_S, 10) ?: 10) * 1000).toLong()
        val deadbandFraction = (intent?.getIntExtra(EXTRA_DEADBAND_PCT, 5) ?: 5) / 100f
        governor = UpdateGovernor(MIN_GAP_MS, deadbandFraction, DEADBAND_ABS_LUX)
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification("starting…"))
        PupilState.update { it.copy(running = true) }
        if (!acquireSensor()) return START_NOT_STICKY
        startAdvertising()
        handler.postDelayed(heartbeat, heartbeatMs)
        return START_STICKY
    }

    /** Spec §4a: wakeup ALS (rung 1) if the hardware has one, else wakelock (rung 2). */
    private fun acquireSensor(): Boolean {
        val sm = getSystemService(SENSOR_SERVICE) as SensorManager
        sensorManager = sm
        val wakeup: Sensor? = sm.getDefaultSensor(Sensor.TYPE_LIGHT, true)
        val sensor = wakeup ?: sm.getDefaultSensor(Sensor.TYPE_LIGHT)
        if (sensor == null) {
            Log.e(TAG, "no TYPE_LIGHT sensor on this device")
            PupilState.update { it.copy(sensorRung = "no light sensor at all?!") }
            stopSelf()
            return false
        }
        if (wakeup != null) {
            PupilState.update { it.copy(sensorRung = "rung 1: wakeup ALS (${sensor.name}), no wakelock") }
        } else {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pupil:sensor")
                .apply { acquire() }
            PupilState.update {
                it.copy(sensorRung = "rung 2: non-wakeup ALS (${sensor.name}) + partial wakelock")
            }
        }
        sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        return true
    }

    @SuppressLint("MissingPermission") // checked in onStartCommand
    private fun startAdvertising() {
        val bm = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        val adv = bm.adapter?.bluetoothLeAdvertiser
        if (adv == null) {
            Log.e(TAG, "no BLE advertiser (Bluetooth off?)")
            stopSelf()
            return
        }
        advertiser = adv
        val params = AdvertisingSetParameters.Builder()
            .setLegacyMode(true)
            .setConnectable(false)
            .setScannable(false)
            .setInterval(intervalUnits)
            .setTxPowerLevel(txPowerLevel)
            .build()
        try {
            adv.startAdvertisingSet(params, buildAdvertiseData(), null, null, null, setCallback)
        } catch (e: SecurityException) {
            Log.e(TAG, "advertise permission lost", e)
            stopSelf()
        }
    }

    private fun buildAdvertiseData(): AdvertiseData {
        packetId = (packetId + 1) and 0xFF
        PupilState.update { it.copy(packetId = packetId) }
        return AdvertiseData.Builder()
            .setIncludeDeviceName(false) // would leak the phone's BT name, and costs bytes
            .setIncludeTxPowerLevel(false)
            .addServiceData(BTHOME_UUID, BthomeEncoder.encode(packetId, latestLux))
            .build()
    }

    override fun onSensorChanged(event: SensorEvent) {
        latestLux = event.values[0]
        PupilState.update { it.copy(lux = latestLux) }
        if (governor.significantChange(latestLux)) maybeSend()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /** Coalescing send: at most one queued/in-flight update at any time. */
    private fun maybeSend() {
        if (inFlight) {
            sendQueued = true
            return
        }
        val gap = governor.gapRemainingMs(SystemClock.elapsedRealtime())
        if (gap > 0) {
            if (!sendQueued) {
                sendQueued = true
                handler.postDelayed({ sendQueued = false; maybeSend() }, gap)
            }
            return
        }
        sendNow()
    }

    @SuppressLint("MissingPermission")
    private fun sendNow() {
        val set = advertisingSet ?: return
        if (inFlight) {
            sendQueued = true
            return
        }
        try {
            set.setAdvertisingData(buildAdvertiseData())
        } catch (e: SecurityException) {
            Log.e(TAG, "advertise permission lost", e)
            stopSelf()
            return
        }
        inFlight = true
        governor.recordSent(latestLux, SystemClock.elapsedRealtime())
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification("broadcasting %.1f lx · #%d".format(latestLux, packetId)))
    }

    private fun createChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Pupil", NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
        )
    }

    private fun buildNotification(text: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_pupil)
            .setContentTitle("Pupil")
            .setContentText(text)
            .setOngoing(true)
            // A steady stream of ~1/s live-value updates must never re-alert or buzz.
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .build()

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        PupilState.update { it.copy(running = false, sensorRung = "not started") }
        handler.removeCallbacksAndMessages(null)
        sensorManager?.unregisterListener(this)
        wakeLock?.release()
        wakeLock = null
        advertisingSet = null
        try {
            advertiser?.stopAdvertisingSet(setCallback)
        } catch (e: SecurityException) {
            Log.w(TAG, "could not stop advertising set", e)
        }
        super.onDestroy()
    }
}
