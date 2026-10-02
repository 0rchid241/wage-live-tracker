package com.orchid.wagelivetracker.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.orchid.wagelivetracker.data.local.entity.WorkProfileEntity

@Dao
interface WorkProfileDao {
    @Insert suspend fun insert(profile: WorkProfileEntity): Long
    @Update suspend fun update(profile: WorkProfileEntity): Int
    @Query("SELECT * FROM work_profiles WHERE id = :id") suspend fun getById(id: Long): WorkProfileEntity?
    @Query("SELECT * FROM work_profiles WHERE isCurrent = 1") suspend fun getCurrent(): List<WorkProfileEntity>
    @Query("UPDATE work_profiles SET isCurrent = 0 WHERE isCurrent = 1") suspend fun clearCurrent()
    @Query("DELETE FROM work_profiles WHERE id = :id") suspend fun delete(id: Long): Int
}
