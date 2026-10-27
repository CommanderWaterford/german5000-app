package com.vocab5000.core.learning

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** Computes the next local reminder occurrence (spec §11). */
object ReminderCalculator {

    /**
     * Next occurrence strictly after [now] on an enabled day, skipping [lastNotifiedDate].
     * A nonexistent DST time resolves to the first valid instant after the gap; an
     * ambiguous time resolves to its first occurrence. Missed times are never caught up.
     */
    fun nextOccurrence(
        now: Instant,
        zone: ZoneId,
        time: LocalTime,
        days: Set<DayOfWeek>,
        lastNotifiedDate: LocalDate?,
    ): Instant? {
        if (days.isEmpty()) return null
        val today = now.atZone(zone).toLocalDate()
        for (offset in 0L..7L) {
            val date = today.plusDays(offset)
            if (date.dayOfWeek !in days) continue
            if (lastNotifiedDate != null && !date.isAfter(lastNotifiedDate)) continue
            val instant = resolve(date, time, zone)
            if (instant.isAfter(now)) return instant
        }
        return null
    }

    fun resolve(date: LocalDate, time: LocalTime, zone: ZoneId): Instant {
        val local = LocalDateTime.of(date, time)
        val rules = zone.rules
        val offsets = rules.getValidOffsets(local)
        return when {
            offsets.isEmpty() -> rules.getTransition(local).instant // gap: first valid time
            else -> local.atOffset(offsets.first()).toInstant() // overlap: earlier offset
        }
    }
}
