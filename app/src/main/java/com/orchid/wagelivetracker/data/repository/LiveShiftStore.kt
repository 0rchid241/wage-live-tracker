package com.orchid.wagelivetracker.data.repository

import java.time.LocalDateTime

/** Only persistence operations needed by the live shift presentation. */
interface LiveShiftStore {
    suspend fun getCurrentProfile(): WorkProfile?
    suspend fun getInProgressShift(): StoredShift?
    suspend fun startShift(profileId: Long, startedAt: LocalDateTime): ShiftRecord
    suspend fun startBreak(shiftId: Long, startedAt: LocalDateTime): StoredShift
    suspend fun endBreak(shiftId: Long, endedAt: LocalDateTime): StoredShift
    suspend fun finishShift(shiftId: Long, endedAt: LocalDateTime): StoredShift
}
