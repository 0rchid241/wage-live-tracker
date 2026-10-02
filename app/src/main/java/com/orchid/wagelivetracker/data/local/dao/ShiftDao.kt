package com.orchid.wagelivetracker.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.orchid.wagelivetracker.data.local.entity.ShiftEntity
import com.orchid.wagelivetracker.data.local.entity.ShiftWithBreaks

@Dao
interface ShiftDao {
    @Insert suspend fun insert(shift: ShiftEntity): Long
    @Update suspend fun update(shift: ShiftEntity): Int
    @Query("SELECT * FROM shifts WHERE id = :id") suspend fun getById(id: Long): ShiftEntity?
    @Query("SELECT * FROM shifts WHERE status = 'IN_PROGRESS'") suspend fun getInProgress(): List<ShiftEntity>
    @Transaction
    @Query("SELECT * FROM shifts WHERE id = :id") suspend fun getWithBreaks(id: Long): ShiftWithBreaks?
    @Query("DELETE FROM shifts WHERE id = :id") suspend fun delete(id: Long): Int
}
