package com.orchid.wagelivetracker.data.repository

import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.YearMonth

data class HistoryBreak(val startedAt: LocalDateTime, val endedAt: LocalDateTime?)

/** Entire replacement input; validation belongs to the repository, including open breaks. */
data class CompletedShiftDraft(
    val startedAt: LocalDateTime,
    val endedAt: LocalDateTime,
    val hourlyWage: BigDecimal,
    val breaks: List<HistoryBreak> = emptyList(),
)

data class HistoryRecord(val stored: StoredShift, val workplaceName: String)

interface WorkHistoryStore {
    suspend fun getCurrentProfile(): WorkProfile?
    suspend fun getCompletedShifts(month: YearMonth): List<HistoryRecord>
    suspend fun getCompletedShift(id: Long): HistoryRecord?
    suspend fun updateCompletedShift(id: Long, draft: CompletedShiftDraft): HistoryRecord
    suspend fun addCompletedShift(draft: CompletedShiftDraft): HistoryRecord
    suspend fun deleteCompletedShift(id: Long)
}
