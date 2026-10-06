package com.orchid.wagelivetracker.ui.history

import com.orchid.wagelivetracker.data.repository.HistoryRecord
import com.orchid.wagelivetracker.data.repository.ShiftStatus
import com.orchid.wagelivetracker.domain.wage.WageBreakdown
import com.orchid.wagelivetracker.domain.wage.WageCalculator
import java.math.BigDecimal
import java.time.Duration
import java.time.YearMonth

data class HistoryEntry(val record: HistoryRecord, val breakdown: WageBreakdown, val elapsed: Duration, val breakDuration: Duration)
data class HistoryMonth(
    val month: YearMonth,
    val entries: List<HistoryEntry>,
    val paidDuration: Duration,
    val breakDuration: Duration,
    val totalPay: BigDecimal,
)

/** Aggregation only. Every wage result is produced by the existing domain engine and Snapshot. */
class HistoryCalculation(private val calculator: WageCalculator = WageCalculator()) {
    fun entry(record: HistoryRecord): HistoryEntry {
        val input = record.stored.toWageInput()
        val result = calculator.calculate(input.workPeriod, input.condition, input.breaks)
        return HistoryEntry(record, result, input.workPeriod.duration,
            input.breaks.fold(Duration.ZERO) { sum, rest -> sum.plus(Duration.between(rest.start, rest.end)) })
    }

    fun month(month: YearMonth, records: List<HistoryRecord>): HistoryMonth {
        val entries = records.filter { it.stored.shift.status == ShiftStatus.COMPLETED && YearMonth.from(it.stored.shift.startedAt) == month }
            .sortedWith(compareByDescending<HistoryRecord> { it.stored.shift.startedAt }.thenByDescending { it.stored.shift.id }).map(::entry)
        return HistoryMonth(month, entries,
            entries.fold(Duration.ZERO) { sum, item -> sum.plus(item.breakdown.paidWorkDuration) },
            entries.fold(Duration.ZERO) { sum, item -> sum.plus(item.breakDuration) },
            entries.fold(BigDecimal.ZERO) { sum, item -> sum.add(item.breakdown.totalEstimatedPay) })
    }
}
