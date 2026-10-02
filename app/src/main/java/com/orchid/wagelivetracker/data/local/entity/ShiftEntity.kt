package com.orchid.wagelivetracker.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.orchid.wagelivetracker.data.repository.ShiftStatus
import java.math.BigDecimal
import java.time.LocalDateTime

@Entity(
    tableName = "shifts",
    foreignKeys = [ForeignKey(
        entity = WorkProfileEntity::class, parentColumns = ["id"], childColumns = ["workProfileId"],
        onDelete = ForeignKey.RESTRICT,
    )],
    indices = [Index("workProfileId"), Index("status")],
)
data class ShiftEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workProfileId: Long,
    val startedAt: LocalDateTime,
    val endedAt: LocalDateTime?,
    val status: ShiftStatus,
    val hourlyWageSnapshot: BigDecimal,
    val hasAtLeastFiveEmployeesSnapshot: Boolean,
    val nightPremiumRateSnapshot: BigDecimal,
    val overtimePremiumRateSnapshot: BigDecimal,
)
