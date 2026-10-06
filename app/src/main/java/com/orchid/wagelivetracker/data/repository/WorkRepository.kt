package com.orchid.wagelivetracker.data.repository

import androidx.room.withTransaction
import com.orchid.wagelivetracker.data.local.database.WageDatabase
import com.orchid.wagelivetracker.data.local.entity.ShiftEntity
import java.time.LocalDateTime

/** All application writes go through this boundary. DAOs are persistence implementation details. */
class WorkRepository(private val database: WageDatabase) : WorkProfileStore, LiveShiftStore {
    private val profiles = database.workProfileDao()
    private val shifts = database.shiftDao()
    private val rests = database.breakDao()

    override suspend fun saveProfile(profile: WorkProfile, makeCurrent: Boolean): WorkProfile = database.withTransaction {
        val previous = if (profile.id == 0L) null else checkNotNull(profiles.getById(profile.id)) { "Profile not found" }
        require(previous == null || previous.createdAt == profile.createdAt) { "Creation time cannot change" }
        require(previous == null || profile.updatedAt >= previous.updatedAt) { "Update time cannot go backwards" }
        if (makeCurrent) profiles.clearCurrent()
        val entity = profile.toEntity(makeCurrent || previous?.isCurrent == true)
        if (previous == null) profile.copy(id = profiles.insert(entity)) else {
            check(profiles.update(entity) == 1)
            profile
        }
    }

    suspend fun getProfile(id: Long): WorkProfile? = profiles.getById(id)?.toModel()
    override suspend fun getCurrentProfile(): WorkProfile? {
        val current = profiles.getCurrent()
        check(current.size <= 1) { "Multiple current profiles found" }
        return current.singleOrNull()?.toModel()
    }

    override suspend fun startShift(profileId: Long, startedAt: LocalDateTime): ShiftRecord = database.withTransaction {
        check(shifts.getInProgress().isEmpty()) { "A shift is already in progress" }
        val profile = checkNotNull(profiles.getById(profileId)) { "Profile not found" }
        val entity = ShiftEntity(
            workProfileId = profile.id, startedAt = startedAt, endedAt = null, status = ShiftStatus.IN_PROGRESS,
            hourlyWageSnapshot = profile.hourlyWage, hasAtLeastFiveEmployeesSnapshot = profile.hasAtLeastFiveEmployees,
            nightPremiumRateSnapshot = profile.nightPremiumRate, overtimePremiumRateSnapshot = profile.overtimePremiumRate,
        )
        entity.copy(id = shifts.insert(entity)).toModel()
    }

    override suspend fun getInProgressShift(): StoredShift? = database.withTransaction {
        val active = shifts.getInProgress()
        check(active.size <= 1) { "Multiple in-progress shifts found" }
        active.singleOrNull()?.let { checkNotNull(shifts.getWithBreaks(it.id)).toModel() }
    }

    suspend fun getShift(id: Long): StoredShift? = shifts.getWithBreaks(id)?.toModel()

    suspend fun getOpenBreak(shiftId: Long): BreakRecord? = database.withTransaction {
        val open = rests.getForShift(shiftId).filter { it.endedAt == null }
        check(open.size <= 1) { "Multiple open breaks found" }
        open.singleOrNull()?.toModel()
    }

    override suspend fun startBreak(shiftId: Long, startedAt: LocalDateTime): StoredShift = database.withTransaction {
        val shift = requireActiveShift(shiftId)
        val breaks = rests.getForShift(shiftId).map { it.toModel() }
        check(breaks.none { it.endedAt == null }) { "A break is already open" }
        val rest = BreakRecord(shiftId = shiftId, startedAt = startedAt)
        validateIntervals(shift, breaks + rest)
        rests.insert(rest.toEntity())
        checkNotNull(shifts.getWithBreaks(shiftId)).toModel()
    }

    override suspend fun endBreak(shiftId: Long, endedAt: LocalDateTime): StoredShift = database.withTransaction {
        requireActiveShift(shiftId)
        closeOpenBreak(shiftId, endedAt, required = true)
        checkNotNull(shifts.getWithBreaks(shiftId)).toModel()
    }

    /** Closing an open break AND completing the shift commit or roll back together. */
    override suspend fun finishShift(shiftId: Long, endedAt: LocalDateTime): StoredShift = database.withTransaction {
        val previous = requireActiveShift(shiftId)
        closeOpenBreak(shiftId, endedAt, required = false)
        val completed = previous.copy(endedAt = endedAt, status = ShiftStatus.COMPLETED)
        validateIntervals(completed, rests.getForShift(shiftId).map { it.toModel() })
        check(shifts.update(completed) == 1)
        checkNotNull(shifts.getWithBreaks(shiftId)).toModel()
    }

    private suspend fun requireActiveShift(id: Long): ShiftEntity {
        val shift = checkNotNull(shifts.getById(id)) { "Shift not found" }
        check(shift.status == ShiftStatus.IN_PROGRESS && shift.endedAt == null) { "Shift is not in progress" }
        return shift
    }

    private suspend fun closeOpenBreak(shiftId: Long, endedAt: LocalDateTime, required: Boolean) {
        val open = rests.getForShift(shiftId).filter { it.endedAt == null }
        check(open.size <= 1) { "Multiple open breaks found" }
        val rest = open.singleOrNull()
        check(!required || rest != null) { "No break is open" }
        if (rest != null) {
            require(endedAt >= rest.startedAt) { "Break end precedes start" }
            // An immediate resume has no unpaid duration. Do not create an invalid zero-length interval.
            if (endedAt == rest.startedAt) check(rests.delete(rest.id) == 1)
            else check(rests.update(rest.copy(endedAt = endedAt)) == 1)
        }
    }

    suspend fun completeShift(id: Long, endedAt: LocalDateTime): StoredShift = database.withTransaction {
        val previous = checkNotNull(shifts.getById(id)) { "Shift not found" }
        check(previous.status == ShiftStatus.IN_PROGRESS) { "Shift is already completed" }
        val updated = previous.copy(endedAt = endedAt, status = ShiftStatus.COMPLETED)
        validateIntervals(updated, rests.getForShift(id).map { it.toModel() })
        check(shifts.update(updated) == 1)
        checkNotNull(shifts.getWithBreaks(id)).toModel()
    }

    /** Edit dates only; the stored calculation snapshot and profile association are immutable here. */
    suspend fun updateShiftTimes(id: Long, startedAt: LocalDateTime, endedAt: LocalDateTime?): StoredShift = database.withTransaction {
        val previous = checkNotNull(shifts.getById(id)) { "Shift not found" }
        val updated = previous.copy(startedAt = startedAt, endedAt = endedAt)
        validateIntervals(updated, rests.getForShift(id).map { it.toModel() })
        check(shifts.update(updated) == 1)
        checkNotNull(shifts.getWithBreaks(id)).toModel()
    }

    suspend fun saveBreak(rest: BreakRecord): BreakRecord = database.withTransaction {
        val shift = checkNotNull(shifts.getById(rest.shiftId)) { "Shift not found" }
        if (rest.id != 0L) {
            val previous = checkNotNull(rests.getById(rest.id)) { "Break not found" }
            require(previous.shiftId == rest.shiftId) { "Break cannot move to another shift" }
        }
        validateIntervals(shift, rests.getForShift(rest.shiftId).filter { it.id != rest.id }.map { it.toModel() } + rest)
        if (rest.id == 0L) rest.copy(id = rests.insert(rest.toEntity())) else {
            check(rests.update(rest.toEntity()) == 1)
            rest
        }
    }

    suspend fun deleteShift(id: Long): Boolean = shifts.delete(id) == 1
    suspend fun deleteBreak(id: Long): Boolean = rests.delete(id) == 1
    // FK RESTRICT preserves historical shifts when a referenced profile is deleted.
    suspend fun deleteProfile(id: Long): Boolean = profiles.delete(id) == 1

    private fun validateIntervals(shift: ShiftEntity, breaks: List<BreakRecord>) {
        require((shift.status == ShiftStatus.COMPLETED) == (shift.endedAt != null)) { "Shift status and end disagree" }
        require(shift.endedAt == null || shift.endedAt > shift.startedAt) { "Shift end must follow start" }
        val sorted = breaks.sortedBy { it.startedAt }
        sorted.forEachIndexed { index, rest ->
            require(rest.startedAt >= shift.startedAt) { "Break precedes shift" }
            require(shift.endedAt == null || (rest.endedAt != null && rest.endedAt <= shift.endedAt)) { "Completed shift must contain closed breaks" }
            if (index > 0) {
                val previousEnd = sorted[index - 1].endedAt
                require(previousEnd != null && previousEnd <= rest.startedAt) { "Breaks overlap or multiple breaks are open" }
            }
        }
    }
}
