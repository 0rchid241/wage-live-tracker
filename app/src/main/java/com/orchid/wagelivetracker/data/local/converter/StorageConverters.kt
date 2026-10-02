package com.orchid.wagelivetracker.data.local.converter

import androidx.room.TypeConverter
import com.orchid.wagelivetracker.data.repository.ShiftStatus
import java.math.BigDecimal
import java.time.LocalDateTime

class StorageConverters {
    @TypeConverter fun encodeTime(value: LocalDateTime?): String? = value?.toString()
    @TypeConverter fun decodeTime(value: String?): LocalDateTime? = value?.let(LocalDateTime::parse)
    // toString preserves the unscaled value AND scale, including scientific notation.
    @TypeConverter fun encodeDecimal(value: BigDecimal?): String? = value?.toString()
    @TypeConverter fun decodeDecimal(value: String?): BigDecimal? = value?.let(::BigDecimal)
    @TypeConverter fun encodeStatus(value: ShiftStatus): String = value.storedValue
    @TypeConverter fun decodeStatus(value: String): ShiftStatus =
        ShiftStatus.entries.single { it.storedValue == value }
}
