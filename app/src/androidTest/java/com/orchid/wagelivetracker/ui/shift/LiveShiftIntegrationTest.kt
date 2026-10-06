package com.orchid.wagelivetracker.ui.shift

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.orchid.wagelivetracker.MainActivity
import com.orchid.wagelivetracker.WageApplication
import com.orchid.wagelivetracker.data.repository.WorkProfile
import com.orchid.wagelivetracker.domain.wage.WorkCondition
import java.math.BigDecimal
import java.time.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class LiveShiftIntegrationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var app: WageApplication
    private lateinit var oldClock: Clock
    private lateinit var clock: MutableClock
    private var scenario: ActivityScenario<MainActivity>? = null
    private var shiftId: Long? = null
    private var profileId: Long? = null

    private class MutableClock(var now: LocalDateTime) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(instant(), zone)
        override fun instant(): Instant = now.toInstant(ZoneOffset.UTC)
    }

    @Before fun setUp() {
        app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as WageApplication
        oldClock = app.shiftClock
        clock = MutableClock(LocalDateTime.parse("2026-10-06T12:00:00"))
        app.shiftClock = clock
        runBlocking {
            // Only test-owned app records are used in this emulator test suite.
            app.repository.getInProgressShift()?.let { app.repository.deleteShift(it.shift.id) }
            profileId = app.repository.saveProfile(WorkProfile(nickname = "카페", condition = WorkCondition(BigDecimal("3600"), true), createdAt = clock.now)).id
        }
        launch()
    }

    @After fun tearDown() {
        scenario?.close()
        runBlocking {
            shiftId?.let { app.repository.deleteShift(it) }
            profileId?.let { app.repository.deleteProfile(it) }
        }
        app.shiftClock = oldClock
    }

    private fun launch() { scenario = ActivityScenario.launch(MainActivity::class.java) }
    private fun waitTag(tag: String) = compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun start() {
        waitTag("startShift")
        compose.onNodeWithTag("startShift").performScrollTo().performClick()
        waitTag("estimatedPay")
        shiftId = runBlocking { app.repository.getInProgressShift()!!.shift.id }
    }
    private fun advance(seconds: Long) {
        scenario!!.onActivity {
            clock.now = clock.now.plusSeconds(seconds)
            ViewModelProvider(it)[LiveShiftViewModel::class.java].refreshTime()
        }
        compose.waitForIdle()
    }
    private fun pause() {
        compose.onNodeWithTag("toggleBreak").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("휴게 중").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun scenarioAHomeStartFinishSummaryAndHome() {
        start()
        compose.onNodeWithTag("estimatedPay").assertTextEquals("₩0")
        advance(10)
        compose.onNodeWithTag("estimatedPay").assertTextEquals("₩10")
        compose.onNodeWithTag("finishShift").performScrollTo().performClick()
        waitTag("confirmSummary")
        compose.onNodeWithText("오늘도 수고했어요.").assertExists()
        compose.onNodeWithTag("estimatedPay").assertTextEquals("₩10")
        assertNull(runBlocking { app.repository.getInProgressShift() })
        compose.onNodeWithTag("confirmSummary").performScrollTo().performClick()
        waitTag("startShift")
        compose.onNodeWithTag("currentWage").assertTextEquals("₩3,600")
    }

    @Test fun scenarioBPauseRecreateRestoreResumeAndFinish() {
        start(); advance(10); pause(); advance(600)
        compose.onNodeWithTag("estimatedPay").assertTextEquals("₩10")
        scenario!!.recreate()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("휴게 중").fetchSemanticsNodes().isNotEmpty() }
        scenario!!.close(); launch() // New ViewModels must recover the open break from Room.
        compose.waitUntil(10_000) { compose.onAllNodesWithText("휴게 중").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("estimatedPay").assertTextEquals("₩10")
        compose.onNodeWithTag("toggleBreak").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("근무 중").fetchSemanticsNodes().isNotEmpty() }
        advance(5)
        compose.onNodeWithTag("estimatedPay").assertTextEquals("₩15")
        compose.onNodeWithTag("finishShift").performScrollTo().performClick()
        waitTag("confirmSummary")
        val saved = runBlocking { app.repository.getShift(shiftId!!)!! }
        assertEquals(clock.now, saved.shift.endedAt)
        assertTrue(saved.breaks.all { it.endedAt != null })
    }

    @Test fun scenarioCWorkingRelaunchRecalculatesFromSnapshot() {
        start()
        scenario!!.close()
        clock.now = clock.now.plusMinutes(10)
        runBlocking {
            val profile = app.repository.getCurrentProfile()!!
            app.repository.saveProfile(profile.copy(condition = profile.condition.copy(hourlyWage = BigDecimal("7200")), updatedAt = profile.updatedAt.plusHours(1)))
        }
        launch(); waitTag("estimatedPay")
        compose.onNodeWithText("근무 중").assertExists()
        compose.onNodeWithTag("estimatedPay").assertTextEquals("₩600")
        compose.onNodeWithTag("startShift").assertDoesNotExist()
    }

    @Test fun finishingWhilePausedProducesCorrectSummaryAndPersistedClosedBreak() {
        start(); advance(10); pause(); advance(30)
        compose.onNodeWithTag("finishShift").performScrollTo().performClick()
        waitTag("confirmSummary")
        compose.onNodeWithTag("estimatedPay").assertTextEquals("₩10")
        compose.onNodeWithText("총 휴게시간 00:00:30").assertExists()
        val stored = runBlocking { app.repository.getShift(shiftId!!)!! }
        assertEquals(stored.shift.endedAt, stored.breaks.single().endedAt)
    }
}
