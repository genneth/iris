package io.github.genneth.pupil

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PupilStateTest {
    @Test
    fun onlyStartingAndBroadcastingAreActive() {
        assertFalse(BroadcastStatus.Stopped.isActive)
        assertTrue(BroadcastStatus.Starting.isActive)
        assertTrue(BroadcastStatus.Broadcasting("wakeup ALS").isActive)
        assertFalse(BroadcastStatus.Failed("Bluetooth is off").isActive)
    }
}
