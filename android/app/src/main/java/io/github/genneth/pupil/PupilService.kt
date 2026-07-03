package io.github.genneth.pupil

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.BluetoothLeAdvertiser
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
    }

    private lateinit var config: PupilConfig
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
            handler.postDelayed(this, config.heartbeatMs)
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
            advertisingSet = null
            if (PupilState.running) handler.postDelayed({ startAdvertising() }, 1000)
        }

        override fun onAdvertisingDataSet(set: AdvertisingSet?, status: Int) {
            inFlight = false
            if (sendQueued) {
                sendQueued = false
                maybeSend()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (checkSelfPermission(android.Manifest.permission.BLUETOOTH_ADVERTISE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "BLUETOOTH_ADVERTISE not granted; refusing to start")
            stopSelf()
            return START_NOT_STICKY
        }
        config = PupilConfig(this)
        governor = UpdateGovernor(config.minGapMs, config.deadbandFraction, config.deadbandAbsLux)
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification("starting…"))
        PupilState.running = true
        acquireSensor()
        startAdvertising()
        handler.postDelayed(heartbeat, config.heartbeatMs)
        return START_STICKY
    }

    /** Spec §4a: wakeup ALS (rung 1) if the hardware has one, else wakelock (rung 2). */
    private fun acquireSensor() {
        val sm = getSystemService(SENSOR_SERVICE) as SensorManager
        sensorManager = sm
        val wakeup: Sensor? = sm.getDefaultSensor(Sensor.TYPE_LIGHT, true)
        val sensor = wakeup ?: sm.getDefaultSensor(Sensor.TYPE_LIGHT)
        if (sensor == null) {
            PupilState.sensorRung = "no light sensor at all?!"
            stopSelf()
            return
        }
        if (wakeup != null) {
            PupilState.sensorRung = "rung 1: wakeup ALS (${sensor.name}), no wakelock"
        } else {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pupil:sensor")
                .apply { acquire() }
            PupilState.sensorRung = "rung 2: non-wakeup ALS (${sensor.name}) + partial wakelock"
        }
        sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
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
            .setInterval(config.intervalUnits)
            .setTxPowerLevel(config.txPowerLevel)
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
        PupilState.packetId = packetId
        return AdvertiseData.Builder()
            .setIncludeDeviceName(false) // would leak the phone's BT name, and costs bytes
            .setIncludeTxPowerLevel(false)
            .addServiceData(BTHOME_UUID, BthomeEncoder.encode(packetId, latestLux))
            .build()
    }

    override fun onSensorChanged(event: SensorEvent) {
        latestLux = event.values[0]
        PupilState.lastLux = latestLux
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
        )
    }

    private fun buildNotification(text: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_pupil)
            .setContentTitle("Pupil")
            .setContentText(text)
            .setOngoing(true)
            .build()

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        PupilState.running = false
        PupilState.sensorRung = "not started"
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
