package com.orchid.wagelivetracker

import android.app.Application
import com.orchid.wagelivetracker.data.local.database.WageDatabase
import com.orchid.wagelivetracker.data.repository.WorkRepository
import java.time.Clock

/** Application-scoped composition root. Activities never recreate the database. */
class WageApplication : Application() {
    // Composition-root clock can be replaced by Android tests before creating an Activity.
    var shiftClock: Clock = Clock.systemDefaultZone()
    private val database by lazy { WageDatabase.open(this) }
    val repository by lazy { WorkRepository(database) }
}
