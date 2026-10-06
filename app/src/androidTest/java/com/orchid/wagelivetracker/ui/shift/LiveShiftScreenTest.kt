package com.orchid.wagelivetracker.ui.shift

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.orchid.wagelivetracker.data.repository.*
import com.orchid.wagelivetracker.domain.wage.WorkCondition
import com.orchid.wagelivetracker.ui.theme.WageLiveTrackerTheme
import java.math.BigDecimal
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LiveShiftScreenTest {
    @get:Rule val compose = createComposeRule()
    private val start = LocalDateTime.parse("2026-10-06T22:00:00")
    private val shift = ShiftRecord(1, 1, start, null, ShiftStatus.IN_PROGRESS, WorkCondition(BigDecimal("12000"), true))
    private fun show(state: LiveShiftState, startBreak: () -> Unit = {}, endBreak: () -> Unit = {}, finish: () -> Unit = {}) {
        compose.setContent { WageLiveTrackerTheme { LiveShiftScreen(state, {}, startBreak, endBreak, finish, {}, {}, {}) } }
    }

    @Test fun workingShowsPaySpeedNightBreakdownAndActions() {
        val stored = StoredShift(shift, emptyList())
        show(LiveShiftState.Active(stored, LiveWageProjection().calculate(stored, start.plusHours(1))))
        compose.onNodeWithTag("estimatedPay").assertTextEquals("₩18,000")
        compose.onNodeWithText("근무 중").assertExists()
        compose.onNodeWithText("야간가산 적용 중").assertExists()
        compose.onNodeWithTag("paySpeed").assertTextEquals("+5.00원/초")
        compose.onNodeWithText("기본급").assertExists()
        compose.onNodeWithText("야간가산").assertExists()
        compose.onNodeWithText("휴게 시작").assertExists()
        compose.onNodeWithText("퇴근").assertExists()
    }

    @Test fun onBreakShowsStoppedCounterAndResumeCallback() {
        val stored = StoredShift(shift, listOf(BreakRecord(1, 1, start.plusMinutes(10))))
        var resumes = 0
        show(LiveShiftState.Active(stored, LiveWageProjection().calculate(stored, start.plusMinutes(20))), endBreak = { resumes++ })
        compose.onNodeWithText("휴게 중").assertExists()
        compose.onNodeWithText("급여 증가가 잠시 멈췄어요").assertExists()
        compose.onNodeWithText("야간가산 적용 중").assertDoesNotExist()
        compose.onNodeWithTag("toggleBreak").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, resumes) }
    }

    @Test fun completingDisablesActionsAndShowsProgress() {
        val stored = StoredShift(shift, emptyList())
        show(LiveShiftState.Active(stored, LiveWageProjection().calculate(stored, start.plusSeconds(10)), operation = ShiftOperation.COMPLETING))
        compose.onNodeWithTag("toggleBreak").assertIsNotEnabled()
        compose.onNodeWithTag("finishShift").assertIsNotEnabled()
        compose.onNodeWithText("퇴근 정산 중…").assertExists()
    }

    @Test fun belowFiveEmployeesDoesNotClaimNightPremium() {
        val stored = StoredShift(shift.copy(conditionSnapshot = shift.conditionSnapshot.copy(hasAtLeastFiveEmployees = false)), emptyList())
        show(LiveShiftState.Active(stored, LiveWageProjection().calculate(stored, start.plusSeconds(10))))
        compose.onNodeWithText("야간가산 적용 중").assertDoesNotExist()
        compose.onNodeWithTag("paySpeed").assertTextEquals("+3.33원/초")
    }

    @Test fun summaryShowsAllDurationsAndEstimatedPayNotice() {
        val stored = StoredShift(shift.copy(endedAt = start.plusHours(1), status = ShiftStatus.COMPLETED), emptyList())
        show(LiveShiftState.Summary(stored, LiveWageProjection().calculate(stored, start.plusHours(1))))
        compose.onNodeWithText("총 근무 경과시간 01:00:00").assertExists()
        compose.onNodeWithText("총 휴게시간 00:00:00").assertExists()
        compose.onNodeWithText("유급시간 01:00:00").assertExists()
        compose.onNodeWithText("입력한 근무조건으로 계산한 예상 금액이에요. 실제 지급액과 차이가 있을 수 있어요.").assertExists()
        compose.onNodeWithTag("confirmSummary").assertExists()
    }
}
