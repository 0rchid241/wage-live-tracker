package com.orchid.wagelivetracker.domain.wage

import java.time.Duration
import java.time.LocalDateTime

/** Half-open local interval [start, end). The caller supplies dates and the local clock basis. */
data class WorkPeriod(val start: LocalDateTime, val end: LocalDateTime) {
    init {
        require(end > start) { "Work end must be after start" }
    }

    val duration: Duration get() = Duration.between(start, end)
}

data class BreakPeriod(val start: LocalDateTime, val end: LocalDateTime) {
    init {
        require(end > start) { "Break end must be after start" }
    }
}

/** Rejects invalid breaks instead of silently clipping or deducting overlapping time twice. */
internal fun WorkPeriod.excludeBreaks(breaks: List<BreakPeriod>): List<WorkPeriod> {
    val sorted = breaks.sortedBy { it.start }
    var cursor = start
    val paid = mutableListOf<WorkPeriod>()
    for (rest in sorted) {
        require(rest.start >= start && rest.end <= end) { "Break must be inside work period" }
        require(rest.start >= cursor) { "Break periods must not overlap" }
        if (cursor < rest.start) paid += WorkPeriod(cursor, rest.start)
        cursor = rest.end
    }
    if (cursor < end) paid += WorkPeriod(cursor, end)
    return paid
}
