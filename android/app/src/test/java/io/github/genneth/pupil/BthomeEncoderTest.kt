package io.github.genneth.pupil

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

class BthomeEncoderTest {

    // Gradle runs unit tests with the module dir (android/app) as CWD.
    private val golden = JSONObject(File("../../contract/bthome-golden.json").readText())

    @Test
    fun goldenVectors() {
        val cases = golden.getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val got = BthomeEncoder
                .encode(c.getInt("packet_id"), c.getDouble("input_lux").toFloat())
                .joinToString("") { "%02x".format(it) }
            assertEquals(c.getString("description"), c.getString("service_data_hex"), got)
        }
    }

    @Test
    fun rejectsOutOfRangePacketId() {
        assertThrows(IllegalArgumentException::class.java) { BthomeEncoder.encode(256, 1f) }
        assertThrows(IllegalArgumentException::class.java) { BthomeEncoder.encode(-1, 1f) }
    }

    @Test
    fun negativeLuxClampsToZero() {
        assertEquals(
            "4000050500 0000".replace(" ", ""),
            BthomeEncoder.encode(5, -3f).joinToString("") { "%02x".format(it) },
        )
    }
}
