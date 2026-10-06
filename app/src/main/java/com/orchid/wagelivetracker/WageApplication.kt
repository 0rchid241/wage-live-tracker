package com.orchid.wagelivetracker

import android.app.Application
import com.orchid.wagelivetracker.data.local.database.WageDatabase
import com.orchid.wagelivetracker.data.repository.WorkRepository

/** Application-scoped composition root. Activities never recreate the database. */
class WageApplication : Application() {
    private val database by lazy { WageDatabase.open(this) }
    val repository by lazy { WorkRepository(database) }
}
