package com.orchid.wagelivetracker.data.repository

import java.security.MessageDigest
import java.time.LocalDateTime
import kotlinx.coroutines.flow.Flow

enum class ShiftNotificationAction { BREAK, RESUME, FINISH }

/** Room-backed session boundary shared by the visible screen and optional notification. */
interface ShiftSessionStore : LiveShiftStore {
    fun observeInProgressShift(): Flow<StoredShift?>
    suspend fun getShift(id: Long): StoredShift?
    suspend fun getProfile(id: Long): WorkProfile?
    suspend fun applyNotificationAction(id: Long, revision: String, action: ShiftNotificationAction, at: LocalDateTime): StoredShift?
}

/** PendingIntents retain the state they were issued for, even after another action commits. */
fun StoredShift.sessionRevision(): String {
    val value = shift.toString() + breaks.sortedBy { it.id }.joinToString()
    return MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

fun StoredShift.acceptsNotificationAction(id: Long, revision: String, action: ShiftNotificationAction): Boolean {
    if (shift.id != id || shift.status != ShiftStatus.IN_PROGRESS || shift.endedAt != null || sessionRevision() != revision) return false
    val paused = breaks.any { it.endedAt == null }
    return when (action) {
        ShiftNotificationAction.BREAK -> !paused
        ShiftNotificationAction.RESUME -> paused
        ShiftNotificationAction.FINISH -> true
    }
}
