package io.github.genneth.pupil

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateGovernorPropertyTest {
    @Test
    fun generatedReadingsObeyDeadbandAndRateBounds() {
        val random = Random(0x1A_15)
        repeat(1_000) {
            val baseline = random.nextFloat() * 50_000f
            val sentAt = random.nextLong(0L, 1_000_000L)
            val threshold = maxOf(1f, baseline * 0.05f)
            val governor = UpdateGovernor(500, 0.05f, 1f)
            governor.recordSent(baseline, sentAt)

            assertFalse(governor.significantChange(baseline + threshold * 0.99f))
            assertTrue(governor.significantChange(baseline + threshold * 1.01f))

            val elapsed = random.nextLong(0L, 1_000L)
            assertEquals(maxOf(0L, 500L - elapsed), governor.gapRemainingMs(sentAt + elapsed))
        }
    }
}
