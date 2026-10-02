package com.orchid.wagelivetracker.domain.wage

import java.math.BigDecimal

/** Rates are additional premiums, not multipliers including base pay. Amounts are in KRW. */
data class WorkCondition(
    val hourlyWage: BigDecimal,
    val hasAtLeastFiveEmployees: Boolean,
    val nightPremiumRate: BigDecimal = DEFAULT_NIGHT_PREMIUM_RATE,
    // No overtime policy is defined yet; callers must supply both a rule and its rate.
    val overtimePremiumRate: BigDecimal = BigDecimal.ZERO,
) {
    init {
        require(hourlyWage.signum() >= 0) { "Hourly wage must not be negative" }
        require(nightPremiumRate.signum() >= 0) { "Night premium rate must not be negative" }
        require(overtimePremiumRate.signum() >= 0) { "Overtime premium rate must not be negative" }
    }

    companion object {
        val DEFAULT_NIGHT_PREMIUM_RATE: BigDecimal = BigDecimal("0.5")
    }
}
