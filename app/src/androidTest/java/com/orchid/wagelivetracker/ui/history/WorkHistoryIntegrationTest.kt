package com.orchid.wagelivetracker.ui.history

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.orchid.wagelivetracker.MainActivity
import com.orchid.wagelivetracker.WageApplication
import com.orchid.wagelivetracker.data.repository.*
import com.orchid.wagelivetracker.domain.wage.WorkCondition
import java.math.BigDecimal
import java.time.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class WorkHistoryIntegrationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var app: WageApplication
    private lateinit var oldClock: Clock
    private var scenario: ActivityScenario<MainActivity>? = null
    private var profileId = 0L
    private var firstId = 0L
    private var secondId = 0L
    private val start = LocalDateTime.parse("2030-04-10T09:00:00")
    private val month = YearMonth.of(2030, 4)

    @Before fun setUp() {
        app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as WageApplication
        oldClock = app.shiftClock
        app.shiftClock = Clock.fixed(start.toInstant(ZoneOffset.UTC), ZoneOffset.UTC)
        runBlocking {
            profileId = app.repository.saveProfile(WorkProfile(nickname = "기록 테스트 카페", condition = WorkCondition(BigDecimal("10000"), true), createdAt = start)).id
            firstId = app.repository.addCompletedShift(CompletedShiftDraft(start, start.plusHours(2), BigDecimal("10000"),
                listOf(HistoryBreak(start, start.plusMinutes(30))))).stored.shift.id
            secondId = app.repository.addCompletedShift(CompletedShiftDraft(start.plusDays(1), start.plusDays(1).plusHours(1), BigDecimal("10000"))).stored.shift.id
        }
        launch()
    }

    @After fun tearDown() {
        scenario?.close()
        runBlocking {
            app.repository.getInProgressShift()?.takeIf { it.shift.workProfileId == profileId }?.let { app.repository.deleteShift(it.shift.id) }
            app.repository.getCompletedShifts(month).filter { it.stored.shift.workProfileId == profileId }.forEach { app.repository.deleteCompletedShift(it.stored.shift.id) }
            if (profileId != 0L) app.repository.deleteProfile(profileId)
        }
        app.shiftClock = oldClock
    }
    private fun launch() { scenario = ActivityScenario.launch(MainActivity::class.java) }
    private fun waitTag(tag: String) = compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun click(tag: String) = compose.onNodeWithTag(tag).performScrollTo().performClick()
    private fun history() { waitTag("openHistory"); click("openHistory"); waitTag("monthPay") }
    private fun firstDetail() { history(); click("historyShift$firstId"); waitTag("editHistory") }

    @Test fun scenarioAMonthListAndTotalsThenNavigateAndReturnHome() {
        history()
        compose.onNodeWithTag("selectedMonth").assertTextEquals("2030년 4월")
        compose.onNodeWithTag("monthPay").assertTextEquals("₩25,000")
        compose.onNodeWithTag("monthPaid").assertTextEquals("월 총 유급시간 02:30:00")
        compose.onNodeWithTag("monthBreak").assertTextEquals("월 총 휴게시간 00:30:00")
        compose.onNodeWithTag("historyShift$firstId").assertExists()
        compose.onNodeWithTag("historyShift$secondId").assertExists()
        click("previousMonth"); waitTag("monthPay")
        compose.onNodeWithText("이 달에는 완료한 근무가 없어요.").assertExists()
        click("nextMonth"); waitTag("monthPay")
        compose.onNodeWithTag("monthPay").assertTextEquals("₩25,000")
        click("historyBack"); waitTag("startShift")
    }

    @Test fun scenarioBEditTimesWageAndBreaksRefreshesDetailAndMonthWithoutChangingProfile() {
        firstDetail(); click("editHistory"); waitTag("historyWage")
        // Exercise real structured Picker dialogs; typed time edits use the independent editor API.
        click("editStartDate"); compose.onNodeWithTag("pickerConfirm").performClick()
        click("editEndTime"); compose.onNodeWithTag("pickerConfirm").performClick()
        scenario!!.onActivity {
            val vm = ViewModelProvider(it)[WorkHistoryViewModel::class.java]
            vm.changeStart(start.plusMinutes(30)); vm.changeEnd(start.plusHours(3).plusMinutes(30))
        }
        compose.onNodeWithTag("historyWage").performTextReplacement("12000")
        click("removeBreak0"); click("addHistoryBreak")
        click("saveHistory"); waitTag("editHistory")
        compose.onNodeWithTag("historyBack").assertIsDisplayed()
        compose.onNodeWithTag("historyTotalPay").assertTextEquals("총 예상 급여 ₩30,000")
        val saved = runBlocking { app.repository.getCompletedShift(firstId)!! }
        assertEquals(start.plusMinutes(30), saved.stored.shift.startedAt)
        assertEquals(1, saved.stored.breaks.size)
        assertEquals(0, BigDecimal("10000").compareTo(runBlocking { app.repository.getCurrentProfile()!!.condition.hourlyWage }))
        click("historyBack"); waitTag("monthPay")
        compose.onNodeWithTag("monthPay").assertIsDisplayed()
        compose.onNodeWithTag("monthPay").assertTextEquals("₩40,000")
    }

    @Test fun scenarioCManualEntryAppearsImmediatelyAndSurvivesNewActivityAndDatabaseRead() {
        history(); click("addHistory"); waitTag("historyWage")
        compose.onNodeWithTag("historyWage").performTextReplacement("6000")
        click("addHistoryBreak"); click("saveHistory"); waitTag("monthPay")
        compose.onNodeWithTag("monthPay").assertIsDisplayed()
        compose.onNodeWithTag("monthPay").assertTextEquals("₩76,000")
        val manual = runBlocking { app.repository.getCompletedShifts(month).single { it.stored.shift.workProfileId == profileId && it.stored.shift.conditionSnapshot.hourlyWage.compareTo(BigDecimal("6000")) == 0 } }
        assertEquals(ShiftStatus.COMPLETED, manual.stored.shift.status)
        scenario!!.close(); launch(); history()
        compose.onNodeWithTag("monthPay").assertTextEquals("₩76,000")
        compose.onNodeWithTag("historyShift${manual.stored.shift.id}").assertExists()
        assertEquals(manual, runBlocking { app.repository.getCompletedShift(manual.stored.shift.id) })
    }

    @Test fun scenarioDDeleteConfirmationCancelAndDeleteUpdateTotals() {
        firstDetail(); click("deleteHistory")
        compose.onNodeWithText("근무 기록을 삭제할까요?").assertExists()
        compose.onNodeWithText("취소").performClick()
        assertNotNull(runBlocking { app.repository.getCompletedShift(firstId) })
        click("deleteHistory"); compose.onNodeWithTag("confirmHistoryDelete").performClick(); waitTag("monthPay")
        compose.onNodeWithTag("monthPay").assertTextEquals("₩10,000")
        compose.onNodeWithTag("historyShift$firstId").assertDoesNotExist()
        assertNull(runBlocking { app.repository.getCompletedShift(firstId) })
    }

    @Test fun activeShiftScreenDoesNotExposeHistoryEditing() {
        waitTag("startShift"); click("startShift"); waitTag("estimatedPay")
        compose.onNodeWithTag("openHistory").assertDoesNotExist()
        compose.onNodeWithTag("addHistory").assertDoesNotExist()
        scenario!!.recreate(); waitTag("estimatedPay")
        compose.onNodeWithText("근무 중").assertExists()
        compose.onNodeWithTag("openHistory").assertDoesNotExist()
    }
}
