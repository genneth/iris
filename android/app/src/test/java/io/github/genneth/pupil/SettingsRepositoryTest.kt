package io.github.genneth.pupil

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsRepositoryTest {
    @Test
    fun txPowerRoundTrips() {
        for (p in TxPower.entries) {
            assertEquals(p, TxPower.fromKey(p.key))
        }
    }

    @Test
    fun txPowerUnknownKeyDefaultsToLow() {
        assertEquals(TxPower.LOW, TxPower.fromKey("nonsense"))
        assertEquals(TxPower.LOW, TxPower.fromKey(""))
    }

    @Test
    fun defaultsMatchSpec() {
        val d = PupilSettings()
        assertEquals(400, d.intervalMs)
        assertEquals(TxPower.LOW, d.txPower)
        assertEquals(5, d.deadbandPct)
        assertEquals(10, d.heartbeatS)
    }
}
