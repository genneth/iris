package io.github.genneth.pupil

/**
 * BTHome v2 service-data payload builder (unencrypted, non-trigger).
 *
 * Emits ONLY the payload — the Android stack adds the AD header and the
 * 0xFCD2 UUID via AdvertiseData.addServiceData(). Object order (packet id
 * 0x00 before illuminance 0x05) is mandated by the spec. Golden vectors:
 * contract/bthome-golden.json (shared with the Python receiver tests).
 */
object BthomeEncoder {
    private const val DEVICE_INFO: Byte = 0x40 // v2, unencrypted, non-trigger

    fun encode(packetId: Int, lux: Float): ByteArray {
        require(packetId in 0..255) { "packetId must fit a uint8, got $packetId" }
        val centiLux = Math.round(lux.toDouble() * 100.0).coerceIn(0L, 0xFFFFFFL)
        return byteArrayOf(
            DEVICE_INFO,
            0x00, packetId.toByte(),
            0x05,
            (centiLux and 0xFF).toByte(),
            ((centiLux shr 8) and 0xFF).toByte(),
            ((centiLux shr 16) and 0xFF).toByte(),
        )
    }
}
