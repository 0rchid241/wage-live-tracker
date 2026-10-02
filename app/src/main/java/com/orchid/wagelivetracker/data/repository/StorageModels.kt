package com.orchid.wagelivetracker.data.repository

import com.orchid.wagelivetracker.domain.wage.BreakPeriod
import com.orchid.wagelivetracker.domain.wage.WorkCondition
import com.orchid.wagelivetracker.domain.wage.WorkPeriod
import java.time.LocalDateTime

data class WorkProfile(
    val id: Long = 0,
    val nickname: String,
    val condition: WorkCondition,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime = createdAt,
) {
    init {
        require(id >= 0)
        require(updatedAt >= createdAt) { "Updated time must not precede creation" }
    }
}

enum class ShiftStatus(val storedValue: String) {
    IN_PROGRESS("IN_PROGRESS"),
    COMPLETED("COMPLETED"),
}

data class ShiftRecord(
    val id: Long,
    val workProfileId: Long,
    val startedAt: LocalDateTime,
    val endedAt: LocalDateTime?,
    val status: ShiftStatus,
    val conditionSnapshot: WorkCondition,
)

data class BreakRecord(
    val id: Long = 0,
    val shiftId: Long,
    val startedAt: LocalDateTime,
    val endedAt: LocalDateTime? = null,
) {
    init {
        require(id >= 0 && shiftId > 0)
        require(endedAt == null || endedAt > startedAt) { "Break end must follow start" }
    }
}

data class StoredShift(val shift: ShiftRecord, val breaks: List<BreakRecord>) {
    /** Completed records only. Open breaks must be closed before historical calculation. */
    fun toWageInput(): StoredWageInput {
        check(shift.status == ShiftStatus.COMPLETED) { "Shift must be completed" }
        val end = checkNotNull(shift.endedAt) { "Completed shift must have an end" }
        return StoredWageInput(
            WorkPeriod(shift.startedAt, end),
            shift.conditionSnapshot,
            breaks.map {
                require(it.shiftId == shift.id) { "Break belongs to another shift" }
                BreakPeriod(it.startedAt, checkNotNull(it.endedAt) { "Break is still open" })
            },
        )
    }
}

data class StoredWageInput(
    val workPeriod: WorkPeriod,
    val condition: WorkCondition,
    val breaks: List<BreakPeriod>,
)
