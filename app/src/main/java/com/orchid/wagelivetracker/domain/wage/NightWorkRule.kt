package com.orchid.wagelivetracker.domain.wage

import java.time.Duration
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/** Product rule: 22:00 inclusive to 06:00 exclusive, on each local calendar day. */
internal object NightWorkRule {
    private val start = LocalTime.of(22, 0)
    private val end = LocalTime.of(6, 0)
    private val fullDayNightDuration = Duration.ofHours(8)

    fun duration(period: WorkPeriod): Duration {
        val firstDate = period.start.toLocalDate()
        val lastDate = period.end.toLocalDate()
        if (firstDate == lastDate) {
            return withinDay(period.start.toLocalTime(), period.end.toLocalTime())
        }
        // No arbitrary shift limit and no day-by-day loop, even for very long dated inputs.
        val middleDays = ChronoUnit.DAYS.between(firstDate, lastDate) - 1
        return fullDayNightDuration.multipliedBy(middleDays)
            .plus(fullDayNightDuration.minus(withinDay(LocalTime.MIDNIGHT, period.start.toLocalTime())))
            .plus(withinDay(LocalTime.MIDNIGHT, period.end.toLocalTime()))
    }

    private fun withinDay(from: LocalTime, to: LocalTime): Duration =
        overlap(from, to, LocalTime.MIDNIGHT, end)
            .plus(if (to > start) Duration.between(maxOf(from, start), to) else Duration.ZERO)

    private fun overlap(from: LocalTime, to: LocalTime, windowStart: LocalTime, windowEnd: LocalTime): Duration {
        val left = maxOf(from, windowStart)
        val right = minOf(to, windowEnd)
        return if (left < right) Duration.between(left, right) else Duration.ZERO
    }
}
