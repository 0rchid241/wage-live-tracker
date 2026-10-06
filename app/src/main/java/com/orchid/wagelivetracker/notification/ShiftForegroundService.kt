package com.orchid.wagelivetracker.notification

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.app.NotificationCompat
import com.orchid.wagelivetracker.R
import com.orchid.wagelivetracker.WageApplication
import com.orchid.wagelivetracker.data.repository.ShiftNotificationAction
import java.time.LocalDateTime
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Optional presentation of the Room session. No timers, amounts or service flags are persisted. */
class ShiftForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()
    private var foreground = false
    private var observation: Job? = null
    private var ticker: Job? = null
    // Ephemeral notification identities/last display only, never the source of session data.
    private var postedModel: ShiftNotificationModel? = null
    private var issuedRevision: String? = null
    private var actionToken: String? = null
    private val app get() = application as WageApplication

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android also enforces promotion when a stale command discovers no active shift.
        // This transient notification makes asynchronous Room lookup/failure safe within that contract.
        if (!foreground) {
            ShiftNotifications.createChannel(this)
            val checking = NotificationCompat.Builder(this, ShiftNotifications.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_shift_notification).setContentTitle("근무 기록 확인 중")
                .setOngoing(true).setSilent(true).setOnlyAlertOnce(true).build()
            ServiceCompat.startForeground(this, ShiftNotifications.NOTIFICATION_ID, checking,
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
            foreground = true
        }
        scope.launch {
            mutex.withLock {
                try {
                    // Room decides whether this service should continue; startup never creates a shift.
                    if (!publishLatest()) return@withLock
                    val actionName = intent?.getStringExtra(ShiftNotifications.EXTRA_ACTION)
                    val action = ShiftNotificationAction.entries.firstOrNull { it.name == actionName }
                    if (action != null && intent != null && intent.getStringExtra(ShiftNotifications.EXTRA_ACTION_TOKEN) == actionToken) {
                        try {
                            val saved = app.repository.applyNotificationAction(
                                intent.getLongExtra(ShiftNotifications.EXTRA_SHIFT_ID, -1),
                                intent.getStringExtra(ShiftNotifications.EXTRA_REVISION).orEmpty(), action,
                                LocalDateTime.now(app.shiftClock),
                            )
                            // Even a zero-duration pause/resume that deletes its Break invalidates old controls.
                            if (saved != null) issuedRevision = null
                        } catch (error: CancellationException) { throw error
                        } catch (_: Exception) {
                            // Transaction failed: keep the persisted session and its current controls.
                        }
                        if (!publishLatest()) return@withLock
                    }
                    startUpdates()
                } catch (_: TimeoutCancellationException) { stopSession()
                } catch (error: CancellationException) { throw error
                } catch (_: Exception) { stopSession() }
            }
        }
        // Re-entry from a visible Activity can retry. Correct recovery never requires service restart.
        return START_NOT_STICKY
    }

    private suspend fun publishLatest(force: Boolean = false): Boolean {
        val stored = withTimeout(3_000) { app.repository.getInProgressShift() }
        if (stored == null) { stopSession(); return false }
        val nickname = withTimeout(1_000) { app.repository.getProfile(stored.shift.workProfileId)?.nickname.orEmpty() }
        val computed = ShiftNotificationModel.from(stored, LocalDateTime.now(app.shiftClock), nickname)
        if (issuedRevision != computed.revision) {
            issuedRevision = computed.revision
            actionToken = UUID.randomUUID().toString()
        }
        val model = computed.copy(actionToken = checkNotNull(actionToken))
        val previous = postedModel
        // Chronometer advances itself; repeated stale intents/Room emissions must not flood Android.
        if (!force && previous != null && previous.copy(elapsed = model.elapsed) == model) return true
        ShiftNotifications.createChannel(this)
        val notification = ShiftNotifications.build(this, model)
        if (!foreground) {
            ServiceCompat.startForeground(this, ShiftNotifications.NOTIFICATION_ID, notification,
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
            foreground = true
        } else getSystemService(NotificationManager::class.java).notify(ShiftNotifications.NOTIFICATION_ID, notification)
        postedModel = model
        return true
    }

    private fun startUpdates() {
        if (observation == null) observation = scope.launch {
            try {
                app.repository.observeInProgressShift().collect {
                    mutex.withLock { publishLatest() }
                }
            } catch (_: TimeoutCancellationException) { stopSession()
            } catch (error: CancellationException) { throw error
            } catch (_: Exception) { stopSession() }
        }
        if (ticker == null) ticker = scope.launch {
            try {
                while (isActive) {
                    delay(ShiftNotificationModel.UPDATE_INTERVAL_MILLIS)
                    mutex.withLock { publishLatest(force = true) }
                }
            } catch (_: TimeoutCancellationException) { stopSession()
            } catch (error: CancellationException) { throw error
            } catch (_: Exception) { stopSession() }
        }
    }

    private fun stopSession() {
        observation?.cancel(); ticker?.cancel()
        observation = null; ticker = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        foreground = false
        postedModel = null; issuedRevision = null; actionToken = null
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
