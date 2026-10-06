package com.orchid.wagelivetracker.ui.shift

import com.orchid.wagelivetracker.data.repository.ShiftStatus
import com.orchid.wagelivetracker.data.repository.StoredShift
import com.orchid.wagelivetracker.domain.wage.BreakPeriod
import com.orchid.wagelivetracker.domain.wage.WageBreakdown
import com.orchid.wagelivetracker.domain.wage.WageCalculator
import com.orchid.wagelivetracker.domain.wage.WorkPeriod
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

data class LiveWageEstimate(
    val at: LocalDateTime,
    val elapsed: Duration,
    val breakDuration: Duration,
    val breakdown: WageBreakdown,
    val payPerSecond: BigDecimal,
    val nightPremiumActive: Boolean,
)

/** Adapts stored intervals to the existing engine. No independently implemented wage formula. */
class LiveWageProjection(private val calculator: WageCalculator = WageCalculator()) {
    fun calculate(stored: StoredShift, now: LocalDateTime): LiveWageEstimate {
        val shift = stored.shift
        val open = stored.breaks.filter { it.endedAt == null }
        check(open.size <= 1) { "Multiple open breaks" }
        check(shift.status != ShiftStatus.COMPLETED || (shift.endedAt != null && open.isEmpty()))
        val latestEvent = stored.breaks.fold(shift.startedAt) { latest, rest -> maxOf(latest, rest.endedAt ?: rest.startedAt) }
        // A backwards local clock cannot produce negative durations or reverse recorded events.
        val end = shift.endedAt ?: maxOf(now, latestEvent)
        val breaks = stored.breaks.mapNotNull { rest ->
            require(rest.shiftId == shift.id)
            val restEnd = rest.endedAt ?: end
            require(restEnd >= rest.startedAt)
            if (restEnd == rest.startedAt) null else BreakPeriod(rest.startedAt, restEnd)
        }
        val breakdown = if (end == shift.startedAt) {
            WageBreakdown(Duration.ZERO, BigDecimal.ZERO, Duration.ZERO, BigDecimal.ZERO, Duration.ZERO, BigDecimal.ZERO)
        } else calculator.calculate(WorkPeriod(shift.startedAt, end), shift.conditionSnapshot, breaks)
        val isWorking = shift.status == ShiftStatus.IN_PROGRESS && open.isEmpty()
        // Probe a one-second interval through the same engine. At whole-second 22/06 boundaries
        // this also determines eligibility without duplicating the domain's night-time rule.
        val secondStart = end.truncatedTo(ChronoUnit.SECONDS)
        val nextSecond = if (isWorking) calculator.calculate(WorkPeriod(secondStart, secondStart.plusSeconds(1)), shift.conditionSnapshot) else null
        return LiveWageEstimate(
            at = end,
            elapsed = Duration.between(shift.startedAt, end),
            breakDuration = breaks.fold(Duration.ZERO) { total, rest -> total.plus(Duration.between(rest.start, rest.end)) },
            breakdown = breakdown,
            payPerSecond = nextSecond?.totalEstimatedPay ?: BigDecimal.ZERO,
            nightPremiumActive = nextSecond?.nightPremium?.signum() == 1,
        )
    }
}
