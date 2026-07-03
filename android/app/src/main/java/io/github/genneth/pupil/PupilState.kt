package io.github.genneth.pupil

/** Tiny UI-facing snapshot; the activity polls it (no binder ceremony for v1). */
object PupilState {
    @Volatile var running = false
    @Volatile var lastLux: Float? = null
    @Volatile var packetId = 0
    @Volatile var sensorRung = "not started"
}
