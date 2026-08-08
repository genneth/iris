package io.github.genneth.pupil

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsRepositoryTest {
    @Test
    fun everyStoredChoiceRoundTrips() {
        AdvertInterval.entries.forEach { assertEquals(it, AdvertInterval.fromMillis(it.millis)) }
        TxPower.entries.forEach { assertEquals(it, TxPower.fromKey(it.key)) }
        Deadband.entries.forEach { assertEquals(it, Deadband.fromPercent(it.percent)) }
        Heartbeat.entries.forEach { assertEquals(it, Heartbeat.fromSeconds(it.seconds)) }
    }

    @Test
    fun unknownStoredChoicesFallBackToShippingDefaults() {
        assertEquals(AdvertInterval.MS_400, AdvertInterval.fromMillis(-1))
        assertEquals(TxPower.LOW, TxPower.fromKey("nonsense"))
        assertEquals(TxPower.LOW, TxPower.fromKey(""))
        assertEquals(Deadband.PCT_5, Deadband.fromPercent(99))
        assertEquals(Heartbeat.S_10, Heartbeat.fromSeconds(0))
    }

    @Test
    fun defaultsMatchSpec() {
        val d = PupilSettings()
        assertEquals(AdvertInterval.MS_400, d.interval)
        assertEquals(TxPower.LOW, d.txPower)
        assertEquals(Deadband.PCT_5, d.deadband)
        assertEquals(Heartbeat.S_10, d.heartbeat)
    }
}
