package com.orchid.wagelivetracker.notification

import com.orchid.wagelivetracker.data.repository.ShiftNotificationAction
import com.orchid.wagelivetracker.data.repository.StoredShift
import com.orchid.wagelivetracker.data.repository.ShiftStatus
import com.orchid.wagelivetracker.data.repository.sessionRevision
import com.orchid.wagelivetracker.ui.shift.LiveWageProjection
import com.orchid.wagelivetracker.ui.shift.formatEstimatedPay
import java.time.Duration
import java.time.LocalDateTime

data class ShiftNotificationModel(
    val shiftId: Long,
    val revision: String,
    val title: String,
    val text: String,
    val elapsed: Duration,
    val actions: List<ShiftNotificationAction>,
    val actionToken: String = revision,
) {
    companion object {
        const val UPDATE_INTERVAL_MILLIS = 20_000L

        fun from(stored: StoredShift, now: LocalDateTime, nickname: String): ShiftNotificationModel {
            require(stored.shift.status == ShiftStatus.IN_PROGRESS)
            val estimate = LiveWageProjection().calculate(stored, now)
            val paused = stored.breaks.any { it.endedAt == null }
            return ShiftNotificationModel(
                stored.shift.id, stored.sessionRevision(),
                "${if (paused) "휴게 중" else "근무 중"} · ${nickname.ifEmpty { "내 근무지" }}",
                "현재 예상 급여 ₩${formatEstimatedPay(estimate.breakdown.totalEstimatedPay)}",
                estimate.elapsed,
                listOf(if (paused) ShiftNotificationAction.RESUME else ShiftNotificationAction.BREAK, ShiftNotificationAction.FINISH),
            )
        }
    }
}

/** A failed optional foreground start can never undo a successfully saved shift. */
fun tryStartShiftNotification(start: () -> Unit): Boolean = try {
    start()
    true
} catch (_: RuntimeException) { false }
