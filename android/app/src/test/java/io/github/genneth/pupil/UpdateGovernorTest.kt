package io.github.genneth.pupil

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateGovernorTest {
    private fun governor() = UpdateGovernor(minGapMs = 500, deadbandFraction = 0.05f, deadbandAbsLux = 1f)

    @Test
    fun firstReadingIsAlwaysSignificant() {
        assertTrue(governor().significantChange(0f))
    }

    @Test
    fun deadbandAbsoluteFloorAtLowLux() {
        val g = governor()
        g.recordSent(10f, 0)
        assertFalse(g.significantChange(10.4f)) // < max(1 lx, 0.5 lx)
        assertTrue(g.significantChange(11.1f))  // > 1 lx
    }

    @Test
    fun deadbandRelativeAtHighLux() {
        val g = governor()
        g.recordSent(1000f, 0)
        assertFalse(g.significantChange(1040f)) // < 5 % (50 lx)
        assertTrue(g.significantChange(1060f))
    }

    @Test
    fun rateGap() {
        val g = governor()
        g.recordSent(10f, 1000)
        assertEquals(400, g.gapRemainingMs(1100))
        assertEquals(0, g.gapRemainingMs(1500))
        assertEquals(0, g.gapRemainingMs(9999))
    }
}
