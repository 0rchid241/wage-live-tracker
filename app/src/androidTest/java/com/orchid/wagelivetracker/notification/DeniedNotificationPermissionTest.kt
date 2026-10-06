package com.orchid.wagelivetracker.notification

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.filters.SdkSuppress
import com.orchid.wagelivetracker.MainActivity
import com.orchid.wagelivetracker.WageApplication
import com.orchid.wagelivetracker.data.repository.WorkProfile
import com.orchid.wagelivetracker.domain.wage.WorkCondition
import java.math.BigDecimal
import java.time.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

/** Run on API 35 with POST_NOTIFICATIONS revoked before instrumentation starts.
 * Revoking inside the instrumented app kills that process and invalidates the test.
 */
@SdkSuppress(minSdkVersion = 33)
class DeniedNotificationPermissionTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun deniedPermissionPreservesAllCoreOperationsAndRelaunchRecovery() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as WageApplication
        Assume.assumeTrue("Gradle may auto-grant install permissions. Run adb instrumentation after POST_NOTIFICATIONS revoke to verify denial.",
            app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_DENIED)
        assertEquals(PackageManager.PERMISSION_DENIED, app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
        assertFalse(ShiftNotifications.canDisplay(app))
        val oldClock = app.shiftClock
        val start = LocalDateTime.parse("2026-10-06T12:00:00")
        var scenario: ActivityScenario<MainActivity>? = null
        var id: Long? = null
        val profile = runBlocking {
            app.repository.getInProgressShift()?.let { app.repository.deleteShift(it.shift.id) }
            app.repository.saveProfile(WorkProfile(nickname = "권한 거부 카페", condition = WorkCondition(BigDecimal("3600"), true), createdAt = start))
        }
        fun time(seconds: Long) { app.shiftClock = Clock.fixed(start.plusSeconds(seconds).toInstant(ZoneOffset.UTC), ZoneOffset.UTC) }
        fun waitTag(tag: String) = compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
        try {
            time(0); scenario = ActivityScenario.launch(MainActivity::class.java); waitTag("startShift")
            compose.onNodeWithTag("startShift").performScrollTo().performClick(); waitTag("estimatedPay")
            id = runBlocking { app.repository.getInProgressShift()!!.shift.id }
            waitTag("notificationHint")
            time(10) // Service uses composition-root clock; re-create ViewModel to adopt the new clock.
            scenario.close(); scenario = ActivityScenario.launch(MainActivity::class.java); waitTag("estimatedPay")
            compose.onNodeWithTag("estimatedPay").assertTextEquals("₩10")
            compose.onNodeWithTag("toggleBreak").performScrollTo().performClick()
            compose.waitUntil(10_000) { runBlocking { app.repository.getInProgressShift()!!.breaks.any { it.endedAt == null } } }
            time(40); scenario.close(); scenario = ActivityScenario.launch(MainActivity::class.java); waitTag("estimatedPay")
            compose.onNodeWithText("휴게 중").assertExists(); compose.onNodeWithTag("estimatedPay").assertTextEquals("₩10")
            compose.onNodeWithTag("toggleBreak").performScrollTo().performClick()
            compose.waitUntil(10_000) { runBlocking { app.repository.getInProgressShift()!!.breaks.none { it.endedAt == null } } }
            time(45); scenario.close(); scenario = ActivityScenario.launch(MainActivity::class.java); waitTag("estimatedPay")
            compose.onNodeWithTag("finishShift").performScrollTo().performClick(); waitTag("confirmSummary")
            compose.onNodeWithTag("estimatedPay").assertTextEquals("₩15")
            scenario.close(); scenario = null
            val saved = runBlocking { app.repository.getCompletedShift(id!!)!! }
            assertEquals(start.plusSeconds(45), saved.stored.shift.endedAt)
            assertEquals(1, saved.stored.breaks.size)
            assertNull(runBlocking { app.repository.getInProgressShift() })
        } finally {
            scenario?.close(); app.stopService(Intent(app, ShiftForegroundService::class.java))
            runBlocking { id?.let { app.repository.deleteShift(it) }; app.repository.deleteProfile(profile.id) }
            app.shiftClock = oldClock
        }
    }
}
