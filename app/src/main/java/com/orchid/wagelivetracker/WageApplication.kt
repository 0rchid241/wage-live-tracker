package com.orchid.wagelivetracker

import android.app.Application
import com.orchid.wagelivetracker.data.local.database.WageDatabase
import com.orchid.wagelivetracker.data.repository.WorkRepository
import java.time.Clock
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.orchid.wagelivetracker.notification.ShiftForegroundService

/** Application-scoped composition root. Activities never recreate the database. */
class WageApplication : Application() {
    // Composition-root clock can be replaced by Android tests before creating an Activity.
    var shiftClock: Clock = Clock.systemDefaultZone()
    // Optional platform boundary, replaceable to verify start failures independently from Room saves.
    var shiftNotificationStarter: (Context) -> Unit = { context ->
        ContextCompat.startForegroundService(context, Intent(context, ShiftForegroundService::class.java))
        Unit
    }
    private val database by lazy { WageDatabase.open(this) }
    val repository by lazy { WorkRepository(database) }
}
