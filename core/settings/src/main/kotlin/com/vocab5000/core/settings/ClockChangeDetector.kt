package com.vocab5000.core.settings

import android.os.SystemClock
import kotlin.math.abs

/**
 * Detects large wall-clock jumps within one boot so the UI can explain shifted due
 * counts (spec §8.5). This is informational; it does not defend against manipulation.
 */
class ClockChangeDetector(private val preferences: PreferencesRepository) {
    suspend fun check(nowWall: Long = System.currentTimeMillis(), nowElapsed: Long = SystemClock.elapsedRealtime()): Boolean {
        val previous = preferences.swapClockSample(nowWall, nowElapsed) ?: return false
        val (wall, elapsed) = previous
        if (nowElapsed < elapsed) return false // rebooted; elapsed time restarted
        val drift = (nowWall - wall) - (nowElapsed - elapsed)
        return abs(drift) > THRESHOLD_MS
    }

    companion object {
        const val THRESHOLD_MS = 60 * 60 * 1000L
    }
}
