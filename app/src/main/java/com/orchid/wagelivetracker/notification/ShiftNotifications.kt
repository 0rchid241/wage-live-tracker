package com.orchid.wagelivetracker.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.orchid.wagelivetracker.MainActivity
import com.orchid.wagelivetracker.R
import com.orchid.wagelivetracker.data.repository.ShiftNotificationAction

object ShiftNotifications {
    const val CHANNEL_ID = "active_shift"
    const val NOTIFICATION_ID = 6001
    const val EXTRA_SHIFT_ID = "shift_id"
    const val EXTRA_REVISION = "revision"
    const val EXTRA_ACTION = "session_action"
    const val EXTRA_ACTION_TOKEN = "action_token"

    fun createChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "근무 중 알림", NotificationManager.IMPORTANCE_LOW).apply {
                description = "직접 시작한 근무의 상태, 예상 급여, 휴게 및 퇴근 버튼"
                setSound(null, null)
                enableVibration(false)
            },
        )
    }

    fun canDisplay(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE

    fun build(context: Context, model: ShiftNotificationModel): Notification {
        val open = PendingIntent.getActivity(context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shift_notification)
            .setContentTitle(model.title).setContentText(model.text)
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setWhen(System.currentTimeMillis() - model.elapsed.toMillis())
            .setUsesChronometer(true).setShowWhen(true)
            .apply {
                model.actions.forEach { action ->
                    val title = when (action) {
                        ShiftNotificationAction.BREAK -> "휴게 시작"
                        ShiftNotificationAction.RESUME -> "휴게 종료"
                        ShiftNotificationAction.FINISH -> "퇴근"
                    }
                    addAction(0, title, actionIntent(context, model, action))
                }
            }.build()
    }

    fun actionIntent(context: Context, model: ShiftNotificationModel, action: ShiftNotificationAction): PendingIntent {
        val intent = Intent(context, ShiftForegroundService::class.java)
            .setAction("com.orchid.wagelivetracker.SHIFT_ACTION")
            // Identity includes the issued token so an old PendingIntent cannot acquire fresh extras.
            .setData(Uri.parse("wagelive://shift/${model.shiftId}/${model.actionToken}/${action.name}"))
            .putExtra(EXTRA_SHIFT_ID, model.shiftId).putExtra(EXTRA_REVISION, model.revision)
            .putExtra(EXTRA_ACTION, action.name)
            .putExtra(EXTRA_ACTION_TOKEN, model.actionToken)
        return PendingIntent.getForegroundService(context, action.ordinal, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}
