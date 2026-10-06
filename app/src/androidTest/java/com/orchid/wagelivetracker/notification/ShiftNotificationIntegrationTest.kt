package com.orchid.wagelivetracker.notification

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.orchid.wagelivetracker.MainActivity
import com.orchid.wagelivetracker.WageApplication
import com.orchid.wagelivetracker.data.repository.*
import com.orchid.wagelivetracker.domain.wage.WorkCondition
import java.math.BigDecimal
import java.time.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

class ShiftNotificationIntegrationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var app: WageApplication
    private lateinit var oldClock: Clock
    private lateinit var oldStarter: (Context) -> Unit
    private lateinit var clock: MutableClock
    private var scenario: ActivityScenario<MainActivity>? = null
    private var shiftId: Long? = null
    private var profileId: Long? = null
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val manager get() = app.getSystemService(NotificationManager::class.java)
    private class MutableClock(@Volatile var now: LocalDateTime) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(instant(), zone)
        override fun instant(): Instant = now.toInstant(ZoneOffset.UTC)
    }
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes().decodeToString() }
    @Before fun setUp() {
        app = instrumentation.targetContext.applicationContext as WageApplication
        oldClock = app.shiftClock
        oldStarter = app.shiftNotificationStarter
        clock = MutableClock(LocalDateTime.parse("2026-10-06T12:00:00")); app.shiftClock = clock
        shell("pm grant ${app.packageName} android.permission.POST_NOTIFICATIONS")
        shell("cmd appops set ${app.packageName} POST_NOTIFICATION allow")
        runBlocking {
            app.repository.getInProgressShift()?.let { app.repository.deleteShift(it.shift.id) }
            profileId = app.repository.saveProfile(WorkProfile(nickname = "알림 카페", condition = WorkCondition(BigDecimal("3600"), true), createdAt = clock.now)).id
        }
    }
    @After fun tearDown() {
        shell("input keyevent 224")
        shell("cmd appops set ${app.packageName} POST_NOTIFICATION allow")
        scenario?.close()
        app.stopService(Intent(app, ShiftForegroundService::class.java))
        runBlocking { shiftId?.let { app.repository.deleteShift(it) }; profileId?.let { app.repository.deleteProfile(it) } }
        app.shiftClock = oldClock
        app.shiftNotificationStarter = oldStarter
    }
    private fun active() = runBlocking { app.repository.getInProgressShift()!! }
    private fun notification(): Notification? = manager.activeNotifications.firstOrNull { it.id == ShiftNotifications.NOTIFICATION_ID }?.notification
    private fun waitFor(predicate: () -> Boolean) = compose.waitUntil(10_000, predicate)
    private fun waitTag(tag: String) = waitFor { compose.onAllNodesWithTag(tag).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
    private fun launch() { scenario = ActivityScenario.launch(MainActivity::class.java) }
    private fun start() {
        launch(); waitTag("startShift"); compose.onNodeWithTag("startShift").performScrollTo().performClick()
        waitTag("estimatedPay"); shiftId = active().shift.id
        waitFor { notification()?.actions?.size == 2 }
    }
    private fun advance(seconds: Long) { scenario!!.onActivity { clock.now = clock.now.plusSeconds(seconds) }; compose.waitForIdle() }
    private fun send(title: String) { notification()!!.actions.first { it.title.toString() == title }.actionIntent.send() }
    private fun waitPaused(paused: Boolean) = waitFor { runBlocking { app.repository.getInProgressShift()?.breaks?.any { it.endedAt == null } } == paused }

    @Test fun savedStartCreatesLowImportanceSilentForegroundNotification() {
        start()
        assertEquals(NotificationManager.IMPORTANCE_LOW, manager.getNotificationChannel(ShiftNotifications.CHANNEL_ID).importance)
        assertNull(manager.getNotificationChannel(ShiftNotifications.CHANNEL_ID).sound)
        assertTrue(notification()!!.flags and Notification.FLAG_FOREGROUND_SERVICE != 0)
        assertTrue(notification()!!.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertEquals("근무 중 · 알림 카페", notification()!!.extras.getString(Notification.EXTRA_TITLE))
    }
    @Test fun notificationPauseAndResumeSynchronizeAlreadyVisibleActivity() {
        start(); advance(10); send("휴게 시작"); waitPaused(true)
        waitFor { compose.onAllNodesWithText("휴게 중").fetchSemanticsNodes().isNotEmpty() }
        advance(30); compose.onNodeWithTag("estimatedPay").assertTextEquals("₩10")
        waitFor { notification()?.actions?.any { it.title.toString() == "휴게 종료" } == true }
        send("휴게 종료"); waitPaused(false)
        waitFor { compose.onAllNodesWithText("근무 중").fetchSemanticsNodes().isNotEmpty() }
        advance(5)
        waitFor { compose.onAllNodesWithText("₩15").fetchSemanticsNodes().isNotEmpty() }
    }
    @Test fun finishingPausedViaNotificationClosesBreakStopsAndCreatesHistory() {
        start(); advance(10); send("휴게 시작"); waitPaused(true); advance(30)
        waitFor { notification()?.actions?.any { it.title.toString() == "휴게 종료" } == true }
        send("퇴근")
        waitFor { runBlocking { app.repository.getInProgressShift() } == null && notification() == null }
        waitTag("confirmSummary")
        val saved = runBlocking { app.repository.getCompletedShift(shiftId!!)!! }
        assertEquals(clock.now, saved.stored.shift.endedAt)
        assertEquals(clock.now, saved.stored.breaks.single().endedAt)
        compose.onNodeWithTag("estimatedPay").assertTextEquals("₩10")
        assertFalse(shell("dumpsys activity services ${app.packageName}").contains("ServiceRecord{"))
    }
    @Test fun duplicateAndStalePauseActionsCannotCreateExtraBreaks() {
        start(); advance(10)
        val old = notification()!!.actions.first().actionIntent
        repeat(5) { old.send() }; waitPaused(true)
        waitFor { notification()?.actions?.first()?.title.toString() == "휴게 종료" }
        advance(5); send("휴게 종료"); waitPaused(false)
        old.send(); compose.waitForIdle()
        // Wait for all service commands to drain; the old working token must stay rejected.
        waitFor { notification()?.actions?.first()?.title.toString() == "휴게 시작" }
        assertEquals(1, active().breaks.size); assertNotNull(active().breaks.single().endedAt)
    }
    @Test fun zeroDurationPauseResumeAlsoInvalidatesPreviousControls() {
        start(); advance(10)
        val old = notification()!!.actions.first().actionIntent
        old.send(); waitPaused(true)
        waitFor { notification()?.actions?.first()?.title.toString() == "휴게 종료" }
        send("휴게 종료"); waitPaused(false)
        waitFor { notification()?.actions?.first()?.title.toString() == "휴게 시작" }
        old.send()
        // A fresh working action remains usable after the stale action has been ignored.
        advance(5); send("휴게 시작"); waitPaused(true)
        assertEquals(clock.now, active().breaks.single().startedAt)
    }
    @Test fun repeatedFinishCannotFinishANewShift() {
        start(); advance(10); val old = notification()!!.actions.last().actionIntent
        repeat(3) { old.send() }
        waitFor { runBlocking { app.repository.getInProgressShift() } == null && notification() == null }
        waitTag("confirmSummary")
        val first = shiftId!!
        compose.onNodeWithTag("confirmSummary").performScrollTo().performClick(); waitTag("startShift")
        compose.onNodeWithTag("startShift").performScrollTo().performClick(); waitTag("estimatedPay")
        shiftId = active().shift.id; advance(5); old.send()
        waitFor { notification()?.actions?.size == 2 }
        assertEquals(shiftId, active().shift.id)
        runBlocking { app.repository.deleteShift(first) }
    }
    @Test fun noActiveSessionImmediatelyStopsService() {
        launch(); waitTag("startShift")
        scenario!!.onActivity { it.startForegroundService(Intent(it, ShiftForegroundService::class.java)) }
        waitFor { !shell("dumpsys activity services ${app.packageName}").contains("ServiceRecord{") }
        assertNull(notification()); assertNull(runBlocking { app.repository.getInProgressShift() })
    }
    @Test fun stoppedServiceAndClosedActivityRecoverOpenBreakFromRoom() {
        start(); advance(10); send("휴게 시작"); waitPaused(true)
        scenario!!.close(); scenario = null
        app.stopService(Intent(app, ShiftForegroundService::class.java))
        waitFor { notification() == null }
        clock.now = clock.now.plusHours(1)
        launch(); waitTag("estimatedPay"); waitFor { notification()?.actions?.size == 2 }
        compose.onNodeWithText("휴게 중").assertExists()
        compose.onNodeWithTag("estimatedPay").assertTextEquals("₩10")
        assertEquals("휴게 중 · 알림 카페", notification()!!.extras.getString(Notification.EXTRA_TITLE))
    }
    @Test fun notificationBodyReopensTheSavedSession() {
        start(); advance(60)
        scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        notification()!!.contentIntent.send()
        waitTag("estimatedPay")
        waitFor { compose.onAllNodesWithText("₩60").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(shiftId, active().shift.id)
    }
    @Test fun screenOffAndBackgroundKeepSessionUntilUserFinishes() {
        start(); val original = active()
        shell("input keyevent 3"); shell("input keyevent 223")
        clock.now = clock.now.plusMinutes(5)
        assertEquals(original, active()); assertNotNull(notification())
        shell("input keyevent 224"); shell("wm dismiss-keyguard")
        notification()!!.contentIntent.send(); waitTag("estimatedPay")
        waitFor { compose.onAllNodesWithText("₩300").fetchSemanticsNodes().isNotEmpty() }
        advance(1); send("퇴근")
        waitFor { runBlocking { app.repository.getInProgressShift() } == null && notification() == null }
    }
    @Test fun foregroundStartFailureDoesNotBlockStartPauseResumeFinishOrHistory() {
        app.shiftNotificationStarter = { throw SecurityException("Simulated platform start rejection") }
        launch(); waitTag("startShift"); compose.onNodeWithTag("startShift").performScrollTo().performClick()
        waitTag("estimatedPay"); shiftId = active().shift.id; advance(10)
        compose.onNodeWithTag("notificationHint").assertTextEquals("근무 알림을 시작하지 못했어요. 근무 기록은 저장되어 있어요.")
        compose.onNodeWithTag("toggleBreak").performScrollTo().performClick(); waitPaused(true); advance(30)
        compose.onNodeWithTag("toggleBreak").performScrollTo().performClick(); waitPaused(false); advance(5)
        compose.onNodeWithTag("finishShift").performScrollTo().performClick(); waitTag("confirmSummary")
        compose.onNodeWithTag("estimatedPay").assertTextEquals("₩15")
        assertNotNull(runBlocking { app.repository.getCompletedShift(shiftId!!) })
    }
    @Test fun invalidActionTimeRollsBackAndKeepsCurrentNotification() {
        start(); advance(10); send("휴게 시작"); waitPaused(true)
        waitFor { notification()?.actions?.first()?.title.toString() == "휴게 종료" }
        val before = active(); clock.now = clock.now.minusSeconds(5)
        send("퇴근")
        waitFor { notification()?.actions?.first()?.title.toString() == "휴게 종료" }
        assertEquals(before, active()); assertNull(active().shift.endedAt)
    }
    @Test fun recreatedServiceRecalculatesUsingShiftSnapshotAfterProfileChange() {
        start(); scenario!!.close(); scenario = null
        app.stopService(Intent(app, ShiftForegroundService::class.java)); waitFor { notification() == null }
        runBlocking {
            val profile = app.repository.getCurrentProfile()!!
            app.repository.saveProfile(profile.copy(condition = profile.condition.copy(hourlyWage = BigDecimal("7200")), updatedAt = clock.now.plusHours(1)))
        }
        clock.now = clock.now.plusMinutes(1)
        launch(); waitTag("estimatedPay"); waitFor { notification()?.actions?.size == 2 }
        assertEquals("현재 예상 급여 ₩60", notification()!!.extras.getString(Notification.EXTRA_TEXT))
    }
}
