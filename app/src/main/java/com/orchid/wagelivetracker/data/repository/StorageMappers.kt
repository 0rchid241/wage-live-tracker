package com.orchid.wagelivetracker.data.repository

import com.orchid.wagelivetracker.data.local.entity.BreakEntity
import com.orchid.wagelivetracker.data.local.entity.ShiftEntity
import com.orchid.wagelivetracker.data.local.entity.ShiftWithBreaks
import com.orchid.wagelivetracker.data.local.entity.WorkProfileEntity
import com.orchid.wagelivetracker.domain.wage.WorkCondition

internal fun WorkProfileEntity.toModel() = WorkProfile(
    id, nickname, WorkCondition(hourlyWage, hasAtLeastFiveEmployees, nightPremiumRate, overtimePremiumRate), createdAt, updatedAt,
)

internal fun WorkProfile.toEntity(isCurrent: Boolean) = WorkProfileEntity(
    id, nickname, condition.hourlyWage, condition.hasAtLeastFiveEmployees, condition.nightPremiumRate,
    condition.overtimePremiumRate, createdAt, updatedAt, isCurrent,
)

internal fun ShiftEntity.toModel() = ShiftRecord(
    id, workProfileId, startedAt, endedAt, status,
    WorkCondition(hourlyWageSnapshot, hasAtLeastFiveEmployeesSnapshot, nightPremiumRateSnapshot, overtimePremiumRateSnapshot),
)

internal fun BreakEntity.toModel() = BreakRecord(id, shiftId, startedAt, endedAt)
internal fun BreakRecord.toEntity() = BreakEntity(id, shiftId, startedAt, endedAt)
internal fun ShiftWithBreaks.toModel() = StoredShift(shift.toModel(), breaks.map { it.toModel() }.sortedBy { it.startedAt })
