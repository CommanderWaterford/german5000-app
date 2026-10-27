package com.vocab5000.core.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Injected clock: persisted times are UTC epoch millis; calendar values use [zone]. */
interface AppClock {
    fun nowMillis(): Long
    fun zone(): ZoneId
    fun today(): LocalDate = Instant.ofEpochMilli(nowMillis()).atZone(zone()).toLocalDate()
}

object SystemAppClock : AppClock {
    override fun nowMillis(): Long = System.currentTimeMillis()
    override fun zone(): ZoneId = ZoneId.systemDefault()
}

class FixedClock(var millis: Long, var zoneId: ZoneId = ZoneId.of("UTC")) : AppClock {
    override fun nowMillis(): Long = millis
    override fun zone(): ZoneId = zoneId
    fun advanceMinutes(minutes: Long) { millis += minutes * 60_000 }
    fun advanceDays(days: Long) { millis += days * 86_400_000 }
}
