package com.orchid.wagelivetracker.domain.wage

import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WageCalculatorTest {
    private val calculator = WageCalculator()
    private val condition = WorkCondition(BigDecimal("10000"), true)

    private fun time(value: String) = LocalDateTime.parse(value)
    private fun work(start: String, end: String) = WorkPeriod(time(start), time(end))
    private fun rest(start: String, end: String) = BreakPeriod(time(start), time(end))
    private fun calculate(
        start: String,
        end: String,
        breaks: List<BreakPeriod> = emptyList(),
        terms: WorkCondition = condition,
    ) = calculator.calculate(work(start, end), terms, breaks)

    private fun assertMoney(expected: String, actual: BigDecimal) {
        assertEquals("Expected $expected, actual $actual", 0, BigDecimal(expected).compareTo(actual))
    }

    private fun assertBreakdown(result: WageBreakdown, paidMinutes: Long, nightMinutes: Long, base: String, night: String) {
        assertEquals(Duration.ofMinutes(paidMinutes), result.paidWorkDuration)
        assertEquals(Duration.ofMinutes(nightMinutes), result.nightWorkDuration)
        assertMoney(base, result.basePay)
        assertMoney(night, result.nightPremium)
        assertMoney((BigDecimal(base) + BigDecimal(night)).toPlainString(), result.totalEstimatedPay)
        assertEquals(Duration.ZERO, result.overtimeDuration)
        assertMoney("0", result.overtimePremium)
    }

    @Test fun `ordinary daytime work includes all paid time in base pay`() {
        assertBreakdown(calculate("2026-10-02T09:00", "2026-10-02T17:00"), 480, 0, "80000", "0")
    }

    @Test fun `exactly one hour uses hourly wage`() {
        assertBreakdown(calculate("2026-10-02T12:00", "2026-10-02T13:00"), 60, 0, "10000", "0")
    }

    @Test fun `second precision night work keeps sub won amounts`() {
        val result = calculate("2026-10-02T22:00:30", "2026-10-02T22:01:15")
        assertEquals(Duration.ofSeconds(45), result.paidWorkDuration)
        assertEquals(Duration.ofSeconds(45), result.nightWorkDuration)
        assertMoney("125", result.basePay)
        assertMoney("62.5", result.nightPremium)
        assertMoney("187.5", result.totalEstimatedPay)
    }

    @Test fun `nanosecond precision is preserved`() {
        val result = calculate("2026-10-02T12:00:00", "2026-10-02T12:00:00.000000001", terms = condition.copy(hourlyWage = BigDecimal("3600")))
        assertEquals(Duration.ofNanos(1), result.paidWorkDuration)
        assertMoney("0.000000001", result.basePay)
    }

    @Test fun `repeating fractional pay uses 34 significant digits without whole won truncation`() {
        val result = calculate("2026-10-02T12:00:00", "2026-10-02T12:00:01")
        assertMoney("2.777777777777777777777777777777778", result.basePay)
    }

    @Test fun `fractional hourly wage and rate multiply before final division`() {
        val result = calculate("2026-10-02T22:00:00", "2026-10-02T22:00:45", terms = condition.copy(hourlyWage = BigDecimal("10000.125"), nightPremiumRate = BigDecimal("0.375")))
        assertMoney("125.0015625", result.basePay)
        assertMoney("46.8755859375", result.nightPremium)
    }

    @Test fun `dated work crosses midnight`() {
        assertBreakdown(calculate("2026-10-02T18:00", "2026-10-03T02:00"), 480, 240, "80000", "20000")
    }

    @Test fun `dated overnight work includes morning daytime`() {
        assertBreakdown(calculate("2026-10-02T22:00", "2026-10-03T08:00"), 600, 480, "100000", "40000")
    }

    @Test fun `ending just before 22 has no night duration`() {
        val result = calculate("2026-10-02T21:00", "2026-10-02T21:59:59")
        assertEquals(Duration.ZERO, result.nightWorkDuration)
        assertMoney("0", result.nightPremium)
    }

    @Test fun `ending exactly at 22 excludes night start`() {
        assertBreakdown(calculate("2026-10-02T21:00", "2026-10-02T22:00"), 60, 0, "10000", "0")
    }

    @Test fun `starting exactly at 22 counts night time`() {
        assertBreakdown(calculate("2026-10-02T22:00", "2026-10-02T23:00"), 60, 60, "10000", "5000")
    }

    @Test fun `crossing 22 counts only time after boundary`() {
        assertBreakdown(calculate("2026-10-02T21:30", "2026-10-02T22:30"), 60, 30, "10000", "2500")
    }

    @Test fun `ending exactly at 06 counts preceding night time`() {
        assertBreakdown(calculate("2026-10-03T05:00", "2026-10-03T06:00"), 60, 60, "10000", "5000")
    }

    @Test fun `starting exactly at 06 is daytime`() {
        assertBreakdown(calculate("2026-10-03T06:00", "2026-10-03T07:00"), 60, 0, "10000", "0")
    }

    @Test fun `crossing 06 counts only time before boundary`() {
        assertBreakdown(calculate("2026-10-03T05:30", "2026-10-03T06:30"), 60, 30, "10000", "2500")
    }

    @Test fun `entire 22 to 06 interval is night work`() {
        assertBreakdown(calculate("2026-10-02T22:00", "2026-10-03T06:00"), 480, 480, "80000", "40000")
    }

    @Test fun `day and night mix never duplicates base pay`() {
        assertBreakdown(calculate("2026-10-02T21:00", "2026-10-03T07:00"), 600, 480, "100000", "40000")
    }

    @Test fun `multiple calendar days include each nightly window`() {
        assertBreakdown(calculate("2026-10-02T21:00", "2026-10-04T07:00"), 2040, 960, "340000", "80000")
    }

    @Test fun `midnight endpoints include eight night hours per full day`() {
        assertBreakdown(calculate("2026-10-02T00:00", "2026-10-04T00:00"), 2880, 960, "480000", "80000")
    }

    @Test fun `multi day boundary combinations match explicit nightly intersections`() {
        val firstDay = time("2026-10-02T00:00").toLocalDate()
        val boundaryTimes = listOf("00:00:00", "05:59:59.999999999", "06:00:00", "12:00:00", "21:59:59.999999999", "22:00:00", "23:59:59.999999999")
        val endpoints = (0L..2L).flatMap { day ->
            boundaryTimes.map { firstDay.plusDays(day).atTime(java.time.LocalTime.parse(it)) }
        }
        for (start in endpoints) {
            for (end in endpoints.filter { it > start }) {
                val expected = (-1L..2L).fold(Duration.ZERO) { total, day ->
                    val nightStart = firstDay.plusDays(day).atTime(22, 0)
                    val nightEnd = firstDay.plusDays(day + 1).atTime(6, 0)
                    val left = maxOf(start, nightStart)
                    val right = minOf(end, nightEnd)
                    total.plus(if (left < right) Duration.between(left, right) else Duration.ZERO)
                }
                assertEquals("$start to $end", expected, calculator.calculate(WorkPeriod(start, end), condition).nightWorkDuration)
            }
        }
    }

    @Test fun `very long work is accepted with no arbitrary maximum`() {
        val result = calculate("2026-01-01T00:00", "2126-01-01T00:00")
        val days = java.time.temporal.ChronoUnit.DAYS.between(time("2026-01-01T00:00"), time("2126-01-01T00:00"))
        assertEquals(Duration.ofDays(days), result.paidWorkDuration)
        assertEquals(Duration.ofHours(days * 8), result.nightWorkDuration)
        assertMoney(BigDecimal(days).multiply(BigDecimal("240000")).toPlainString(), result.basePay)
    }

    @Test fun `daytime break reduces only paid duration`() {
        assertBreakdown(calculate("2026-10-02T09:00", "2026-10-02T17:00", listOf(rest("2026-10-02T12:00", "2026-10-02T13:00"))), 420, 0, "70000", "0")
    }

    @Test fun `overnight break reduces paid and night duration`() {
        assertBreakdown(calculate("2026-10-02T21:00", "2026-10-03T07:00", listOf(rest("2026-10-02T23:00", "2026-10-03T00:30"))), 510, 390, "85000", "32500")
    }

    @Test fun `break crossing 22 removes only its night overlap`() {
        assertBreakdown(calculate("2026-10-02T21:00", "2026-10-02T23:00", listOf(rest("2026-10-02T21:45", "2026-10-02T22:15"))), 90, 45, "15000", "3750")
    }

    @Test fun `break crossing 06 removes only its night overlap`() {
        assertBreakdown(calculate("2026-10-03T05:00", "2026-10-03T07:00", listOf(rest("2026-10-03T05:45", "2026-10-03T06:15"))), 90, 45, "15000", "3750")
    }

    @Test fun `multiple unsorted breaks are excluded once each`() {
        val breaks = listOf(rest("2026-10-03T05:30", "2026-10-03T06:30"), rest("2026-10-02T23:00", "2026-10-03T00:00"))
        assertBreakdown(calculate("2026-10-02T21:00", "2026-10-03T07:00", breaks), 480, 390, "80000", "32500")
    }

    @Test fun `explicit empty breaks matches default`() {
        assertEquals(calculate("2026-10-02T22:00", "2026-10-03T06:00"), calculate("2026-10-02T22:00", "2026-10-03T06:00", emptyList()))
    }

    @Test fun `break covering all work produces zero pay`() {
        assertBreakdown(calculate("2026-10-02T22:00", "2026-10-03T06:00", listOf(rest("2026-10-02T22:00", "2026-10-03T06:00"))), 0, 0, "0", "0")
    }

    @Test fun `touching breaks and breaks at shift endpoints are accepted`() {
        assertBreakdown(calculate("2026-10-02T22:00", "2026-10-03T02:00", listOf(rest("2026-10-02T22:00", "2026-10-02T23:00"), rest("2026-10-02T23:00", "2026-10-03T00:00"), rest("2026-10-03T01:00", "2026-10-03T02:00"))), 60, 60, "10000", "5000")
    }

    @Test fun `subsecond break is deducted from night and paid time`() {
        val result = calculate("2026-10-02T22:00:00", "2026-10-02T22:00:01", listOf(rest("2026-10-02T22:00:00.25", "2026-10-02T22:00:00.75")), condition.copy(hourlyWage = BigDecimal("3600")))
        assertEquals(Duration.ofMillis(500), result.paidWorkDuration)
        assertEquals(Duration.ofMillis(500), result.nightWorkDuration)
        assertMoney("0.5", result.basePay)
        assertMoney("0.25", result.nightPremium)
    }

    @Test fun `break extending outside work is rejected`() {
        for (invalid in listOf(rest("2026-10-02T08:00", "2026-10-02T10:00"), rest("2026-10-02T16:00", "2026-10-02T18:00"), rest("2026-10-03T09:00", "2026-10-03T10:00"))) {
            assertThrows(IllegalArgumentException::class.java) { calculate("2026-10-02T09:00", "2026-10-02T17:00", listOf(invalid)) }
        }
    }

    @Test fun `overlapping duplicate and nested breaks are explicitly rejected`() {
        val first = rest("2026-10-02T12:00", "2026-10-02T13:00")
        for (second in listOf(first, rest("2026-10-02T12:30", "2026-10-02T13:30"), rest("2026-10-02T12:15", "2026-10-02T12:45"))) {
            assertThrows(IllegalArgumentException::class.java) { calculate("2026-10-02T09:00", "2026-10-02T17:00", listOf(first, second)) }
        }
    }

    @Test fun `five employee condition enables night premium`() {
        assertBreakdown(calculate("2026-10-02T22:00", "2026-10-02T23:00"), 60, 60, "10000", "5000")
    }

    @Test fun `below five employees keeps night duration but disables premium`() {
        assertBreakdown(calculate("2026-10-02T22:00", "2026-10-02T23:00", terms = condition.copy(hasAtLeastFiveEmployees = false)), 60, 60, "10000", "0")
    }

    @Test fun `configured night rate replaces default`() {
        assertBreakdown(calculate("2026-10-02T22:00", "2026-10-02T23:00", terms = condition.copy(nightPremiumRate = BigDecimal("0.25"))), 60, 60, "10000", "2500")
    }

    @Test fun `reversed actual dates are rejected without adding a day`() {
        assertThrows(IllegalArgumentException::class.java) { work("2026-10-03T22:00", "2026-10-02T08:00") }
    }

    @Test fun `equal work endpoints are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { work("2026-10-02T22:00", "2026-10-02T22:00") }
    }

    @Test fun `equal and reversed break endpoints are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { rest("2026-10-02T12:00", "2026-10-02T12:00") }
        assertThrows(IllegalArgumentException::class.java) { rest("2026-10-02T13:00", "2026-10-02T12:00") }
    }

    @Test fun `negative wage or premium rates are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { condition.copy(hourlyWage = BigDecimal("-1")) }
        assertThrows(IllegalArgumentException::class.java) { condition.copy(nightPremiumRate = BigDecimal("-0.1")) }
        assertThrows(IllegalArgumentException::class.java) { condition.copy(overtimePremiumRate = BigDecimal("-0.1")) }
    }

    @Test fun `zero wage is accepted`() {
        assertBreakdown(calculate("2026-10-02T22:00", "2026-10-02T23:00", terms = condition.copy(hourlyWage = BigDecimal.ZERO)), 60, 60, "0", "0")
    }

    @Test fun `default overtime remains unapplied even on long shifts`() {
        val result = calculate("2026-10-02T09:00", "2026-10-03T09:00", terms = condition.copy(overtimePremiumRate = BigDecimal("0.5")))
        assertEquals(Duration.ZERO, result.overtimeDuration)
        assertMoney("0", result.overtimePremium)
    }

    @Test fun `future overtime rule receives paid intervals and adds only its premium`() {
        val terms = condition.copy(overtimePremiumRate = BigDecimal("0.5"))
        val custom = WageCalculator(OvertimeRule { periods, suppliedCondition ->
            assertEquals(terms, suppliedCondition)
            assertEquals(listOf(work("2026-10-02T21:00", "2026-10-02T23:00"), work("2026-10-03T00:00", "2026-10-03T02:00")), periods)
            Duration.ofHours(1)
        })
        val result = custom.calculate(work("2026-10-02T21:00", "2026-10-03T02:00"), terms, listOf(rest("2026-10-02T23:00", "2026-10-03T00:00")))
        assertMoney("40000", result.basePay)
        assertMoney("15000", result.nightPremium)
        assertEquals(Duration.ofHours(1), result.overtimeDuration)
        assertMoney("5000", result.overtimePremium)
        assertMoney("60000", result.totalEstimatedPay)
    }

    @Test fun `invalid duration from future overtime rule is rejected`() {
        for (invalid in listOf(Duration.ofSeconds(-1), Duration.ofHours(2))) {
            assertThrows(IllegalArgumentException::class.java) {
                WageCalculator(OvertimeRule { _, _ -> invalid }).calculate(work("2026-10-02T09:00", "2026-10-02T10:00"), condition)
            }
        }
    }
}
