package io.github.genneth.pupil

import java.util.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class BthomeEncoderPropertyTest {
    @Test
    fun generatedPayloadsPreservePacketAndRoundedIlluminance() {
        val random = Random(0xB7_40_4E)
        repeat(2_000) {
            val packetId = random.nextInt(256)
            val lux = random.nextDouble() * 167_772.15
            val expectedCentiLux = Math.round(lux * 100.0).coerceIn(0L, 0xFF_FFFFL)

            val payload = BthomeEncoder.encode(packetId, lux.toFloat())

            assertEquals(7, payload.size)
            assertArrayEquals(byteArrayOf(0x40, 0x00, packetId.toByte(), 0x05), payload.copyOf(4))
            val decoded =
                (payload[4].toInt() and 0xFF) or
                    ((payload[5].toInt() and 0xFF) shl 8) or
                    ((payload[6].toInt() and 0xFF) shl 16)
            // The encoder accepts Float because SensorEvent does; compute the oracle from that
            // same boundary value rather than from the higher-precision generator input.
            val expectedFromFloat = Math.round(lux.toFloat().toDouble() * 100.0)
                .coerceIn(0L, 0xFF_FFFFL)
            assertEquals(expectedCentiLux, expectedFromFloat.coerceIn(0L, 0xFF_FFFFL), 1L)
            assertEquals(expectedFromFloat.toInt(), decoded)
        }
    }

    private fun assertEquals(expected: Long, actual: Long, tolerance: Long) {
        org.junit.Assert.assertTrue("expected $expected ±$tolerance, got $actual", kotlin.math.abs(expected - actual) <= tolerance)
    }
}
