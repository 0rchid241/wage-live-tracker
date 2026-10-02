package com.orchid.wagelivetracker.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.math.BigDecimal
import java.time.LocalDateTime

@Entity(tableName = "work_profiles")
data class WorkProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val nickname: String,
    val hourlyWage: BigDecimal,
    val hasAtLeastFiveEmployees: Boolean,
    val nightPremiumRate: BigDecimal,
    val overtimePremiumRate: BigDecimal,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
    val isCurrent: Boolean,
)
