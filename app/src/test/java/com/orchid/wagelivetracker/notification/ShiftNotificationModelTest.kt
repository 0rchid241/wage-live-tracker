package com.orchid.wagelivetracker.notification

import com.orchid.wagelivetracker.data.repository.*
import com.orchid.wagelivetracker.domain.wage.WorkCondition
import java.math.BigDecimal
import java.time.LocalDateTime
import org.junit.Assert.*
import org.junit.Test

class ShiftNotificationModelTest {
    private val start = LocalDateTime.parse("2026-10-06T21:59:50")
    private val stored = StoredShift(ShiftRecord(1, 1, start, null, ShiftStatus.IN_PROGRESS, WorkCondition(BigDecimal("3600"), true)), emptyList())
    private fun model(at: LocalDateTime = start, session: StoredShift = stored, name: String = "카페") = ShiftNotificationModel.from(session, at, name)
    private fun paused() = stored.copy(breaks = listOf(BreakRecord(1, 1, start.plusSeconds(5))))

    @Test fun workingModelHasNicknameAndZeroPay() { assertEquals("근무 중 · 카페", model().title); assertEquals("현재 예상 급여 ₩0", model().text) }
    @Test fun blankNicknameUsesFallback() { assertEquals("근무 중 · 내 근무지", model(name = "").title) }
    @Test fun workingActionsArePauseAndFinish() { assertEquals(listOf(ShiftNotificationAction.BREAK, ShiftNotificationAction.FINISH), model().actions) }
    @Test fun pausedActionsAreResumeAndFinish() { assertEquals(listOf(ShiftNotificationAction.RESUME, ShiftNotificationAction.FINISH), model(session = paused()).actions) }
    @Test fun pauseStopsMoneyWhileElapsedContinues() {
        assertEquals("휴게 중 · 카페", model(start.plusHours(1), paused()).title)
        assertEquals(model(start.plusSeconds(10), paused()).text, model(start.plusHours(1), paused()).text)
        assertEquals(3600L, model(start.plusHours(1), paused()).elapsed.seconds)
    }
    @Test fun nightBoundaryUsesExistingProjection() { assertEquals("현재 예상 급여 ₩25", model(start.plusSeconds(20)).text) }
    @Test fun underFiveHasNoNightPremium() {
        val small = stored.copy(shift = stored.shift.copy(conditionSnapshot = WorkCondition(BigDecimal("3600"), false)))
        assertEquals("현재 예상 급여 ₩20", model(start.plusSeconds(20), small).text)
    }
    @Test fun resumedPayUsesSnapshotAndBreaks() {
        val resumed = paused().copy(breaks = listOf(paused().breaks.single().copy(endedAt = start.plusSeconds(15))))
        assertEquals("현재 예상 급여 ₩12", model(start.plusSeconds(20), resumed).text)
    }
    @Test fun fractionalWageTruncatesOnlyDisplay() {
        val fractional = stored.copy(shift = stored.shift.copy(conditionSnapshot = WorkCondition(BigDecimal("12000.25"), false)))
        assertEquals("현재 예상 급여 ₩3", model(start.plusSeconds(1), fractional).text)
        assertEquals(BigDecimal("12000.25"), fractional.shift.conditionSnapshot.hourlyWage)
    }
    @Test fun ticksChangePayWithoutChangingSessionOrActionToken() {
        val revision = stored.sessionRevision()
        repeat(100) { model(start.plusSeconds(it.toLong())) }
        assertEquals(revision, stored.sessionRevision()); assertTrue(stored.breaks.isEmpty())
    }
    @Test fun updateIntervalBalancesBatteryAndEstimate() { assertTrue(ShiftNotificationModel.UPDATE_INTERVAL_MILLIS in 15_000L..30_000L) }
    @Test fun completedCannotBecomeOngoingNotification() {
        assertThrows(IllegalArgumentException::class.java) { model(session = stored.copy(shift = stored.shift.copy(status = ShiftStatus.COMPLETED, endedAt = start.plusHours(1)))) }
    }
    @Test fun wrongShiftAndStaleTokensAreRejected() {
        assertFalse(stored.acceptsNotificationAction(2, stored.sessionRevision(), ShiftNotificationAction.FINISH))
        assertFalse(paused().acceptsNotificationAction(1, stored.sessionRevision(), ShiftNotificationAction.FINISH))
    }
    @Test fun actionsValidateWorkingAndPausedState() {
        assertTrue(stored.acceptsNotificationAction(1, stored.sessionRevision(), ShiftNotificationAction.BREAK))
        assertFalse(stored.acceptsNotificationAction(1, stored.sessionRevision(), ShiftNotificationAction.RESUME))
        assertFalse(paused().acceptsNotificationAction(1, paused().sessionRevision(), ShiftNotificationAction.BREAK))
        assertTrue(paused().acceptsNotificationAction(1, paused().sessionRevision(), ShiftNotificationAction.RESUME))
    }
    @Test fun failedOptionalStartLeavesSavedShiftUntouched() {
        assertFalse(tryStartShiftNotification { throw SecurityException("denied") })
        assertEquals(ShiftStatus.IN_PROGRESS, stored.shift.status)
        assertTrue(tryStartShiftNotification {})
    }
}
