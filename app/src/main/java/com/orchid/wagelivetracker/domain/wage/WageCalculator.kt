package com.orchid.wagelivetracker.domain.wage

import java.math.BigDecimal
import java.math.MathContext
import java.time.Duration

data class WageBreakdown(
    val paidWorkDuration: Duration,
    val basePay: BigDecimal,
    val nightWorkDuration: Duration,
    val nightPremium: BigDecimal,
    val overtimeDuration: Duration,
    val overtimePremium: BigDecimal,
) {
    val totalEstimatedPay: BigDecimal get() = basePay + nightPremium + overtimePremium
}

/** Future policies choose eligible time from paid intervals; base pay is already included. */
fun interface OvertimeRule {
    fun eligibleDuration(paidPeriods: List<WorkPeriod>, condition: WorkCondition): Duration
}

class WageCalculator(
    private val overtimeRule: OvertimeRule = OvertimeRule { _, _ -> Duration.ZERO },
) {
    fun calculate(
        workPeriod: WorkPeriod,
        condition: WorkCondition,
        breaks: List<BreakPeriod> = emptyList(),
    ): WageBreakdown {
        val paidPeriods = workPeriod.excludeBreaks(breaks)
        val paidDuration = paidPeriods.fold(Duration.ZERO) { total, period -> total.plus(period.duration) }
        val nightDuration = paidPeriods.fold(Duration.ZERO) { total, period ->
            total.plus(NightWorkRule.duration(period))
        }
        val overtimeDuration = overtimeRule.eligibleDuration(paidPeriods.toList(), condition)
        require(!overtimeDuration.isNegative && overtimeDuration <= paidDuration) {
            "Overtime duration must be between zero and paid work duration"
        }
        return WageBreakdown(
            paidWorkDuration = paidDuration,
            basePay = pay(paidDuration, condition.hourlyWage),
            nightWorkDuration = nightDuration,
            nightPremium = if (condition.hasAtLeastFiveEmployees) {
                pay(nightDuration, condition.hourlyWage, condition.nightPremiumRate)
            } else BigDecimal.ZERO,
            overtimeDuration = overtimeDuration,
            overtimePremium = pay(overtimeDuration, condition.hourlyWage, condition.overtimePremiumRate),
        )
    }

    private fun pay(duration: Duration, hourlyWage: BigDecimal, rate: BigDecimal = BigDecimal.ONE): BigDecimal {
        val seconds = BigDecimal.valueOf(duration.seconds).add(BigDecimal.valueOf(duration.nano.toLong(), 9))
        // Multiply exactly before dividing. Only repeating decimals need rounding (34 significant digits).
        // Preserve sub-won amounts and nanoseconds; display rounding belongs to the UI.
        val numerator = hourlyWage.multiply(rate).multiply(seconds)
        return try {
            numerator.divide(SECONDS_PER_HOUR)
        } catch (_: ArithmeticException) {
            numerator.divide(SECONDS_PER_HOUR, MathContext.DECIMAL128)
        }
    }

    private companion object {
        val SECONDS_PER_HOUR: BigDecimal = BigDecimal("3600")
    }
}
