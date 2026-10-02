package com.orchid.wagelivetracker.data.local.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.orchid.wagelivetracker.data.local.converter.StorageConverters
import com.orchid.wagelivetracker.data.local.dao.BreakDao
import com.orchid.wagelivetracker.data.local.dao.ShiftDao
import com.orchid.wagelivetracker.data.local.dao.WorkProfileDao
import com.orchid.wagelivetracker.data.local.entity.BreakEntity
import com.orchid.wagelivetracker.data.local.entity.ShiftEntity
import com.orchid.wagelivetracker.data.local.entity.WorkProfileEntity

@Database(entities = [WorkProfileEntity::class, ShiftEntity::class, BreakEntity::class], version = 1, exportSchema = true)
@TypeConverters(StorageConverters::class)
abstract class WageDatabase : RoomDatabase() {
    abstract fun workProfileDao(): WorkProfileDao
    abstract fun shiftDao(): ShiftDao
    abstract fun breakDao(): BreakDao

    companion object {
        /** Own one database instance at application scope; never enable destructive migration. */
        fun open(context: Context): WageDatabase = Room.databaseBuilder(
            context.applicationContext, WageDatabase::class.java, "wage-live-tracker.db",
        ).build()
    }
}
