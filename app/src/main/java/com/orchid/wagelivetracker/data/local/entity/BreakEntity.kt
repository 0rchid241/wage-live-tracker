package com.orchid.wagelivetracker.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDateTime

@Entity(
    tableName = "breaks",
    foreignKeys = [ForeignKey(
        entity = ShiftEntity::class, parentColumns = ["id"], childColumns = ["shiftId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("shiftId")],
)
data class BreakEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val shiftId: Long,
    val startedAt: LocalDateTime,
    val endedAt: LocalDateTime?,
)
