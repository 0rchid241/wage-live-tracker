package com.orchid.wagelivetracker.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.orchid.wagelivetracker.data.local.entity.ShiftEntity
import com.orchid.wagelivetracker.data.local.entity.ShiftWithBreaks
import kotlinx.coroutines.flow.Flow

@Dao
interface ShiftDao {
    @Transaction
    @Query("SELECT * FROM shifts WHERE status = 'IN_PROGRESS'")
    fun observeInProgressWithBreaks(): Flow<List<ShiftWithBreaks>>
    @Insert suspend fun insert(shift: ShiftEntity): Long
    @Update suspend fun update(shift: ShiftEntity): Int
    @Query("SELECT * FROM shifts WHERE id = :id") suspend fun getById(id: Long): ShiftEntity?
    @Query("SELECT * FROM shifts WHERE status = 'IN_PROGRESS'") suspend fun getInProgress(): List<ShiftEntity>
    @Transaction
    @Query("SELECT * FROM shifts WHERE id = :id") suspend fun getWithBreaks(id: Long): ShiftWithBreaks?
    @Transaction
    @Query("SELECT * FROM shifts WHERE status = 'COMPLETED'")
    suspend fun getCompletedWithBreaks(): List<ShiftWithBreaks>
    @Query("DELETE FROM shifts WHERE id = :id") suspend fun delete(id: Long): Int
}
