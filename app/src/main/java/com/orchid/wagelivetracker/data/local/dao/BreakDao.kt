package com.orchid.wagelivetracker.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.orchid.wagelivetracker.data.local.entity.BreakEntity

@Dao
interface BreakDao {
    @Insert suspend fun insert(rest: BreakEntity): Long
    @Update suspend fun update(rest: BreakEntity): Int
    @Query("SELECT * FROM breaks WHERE id = :id") suspend fun getById(id: Long): BreakEntity?
    @Query("SELECT * FROM breaks WHERE shiftId = :shiftId") suspend fun getForShift(shiftId: Long): List<BreakEntity>
    @Query("DELETE FROM breaks WHERE id = :id") suspend fun delete(id: Long): Int
    @Query("DELETE FROM breaks WHERE shiftId = :shiftId") suspend fun deleteForShift(shiftId: Long): Int
}
