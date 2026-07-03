package io.github.genneth.pupil

import kotlin.math.abs
import kotlin.math.max

/** Pure deadband + rate-limit policy for advert payload updates (spec §5). */
class UpdateGovernor(
    private val minGapMs: Long,
    private val deadbandFraction: Float,
    private val deadbandAbsLux: Float,
) {
    private var lastSentLux = Float.NEGATIVE_INFINITY
    private var lastSentAtMs = Long.MIN_VALUE / 2

    fun significantChange(lux: Float): Boolean =
        abs(lux - lastSentLux) >= max(deadbandAbsLux, abs(lastSentLux) * deadbandFraction)

    fun gapRemainingMs(nowMs: Long): Long = max(0L, lastSentAtMs + minGapMs - nowMs)

    fun recordSent(lux: Float, nowMs: Long) {
        lastSentLux = lux
        lastSentAtMs = nowMs
    }
}
