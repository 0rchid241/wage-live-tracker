package com.orchid.wagelivetracker.data.local.entity

import androidx.room.Embedded
import androidx.room.Relation

data class ShiftWithBreaks(
    @Embedded val shift: ShiftEntity,
    @Relation(parentColumn = "id", entityColumn = "shiftId") val breaks: List<BreakEntity>,
)
