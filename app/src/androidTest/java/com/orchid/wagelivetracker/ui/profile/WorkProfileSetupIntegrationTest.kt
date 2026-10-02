package com.orchid.wagelivetracker.ui.profile

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.orchid.wagelivetracker.MainActivity
import com.orchid.wagelivetracker.WageApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class WorkProfileSetupIntegrationTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun firstSetupPersistsOnActivityRestartAndEditingUpdatesSameProfile() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as WageApplication
        runBlocking {
            app.repository.getCurrentProfile()?.let { app.repository.deleteProfile(it.id) }
        }
        var scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("hourlyWage").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("saveProfile").performScrollTo().performClick()
            compose.onNodeWithText("시급을 입력해 주세요.").assertExists()
            compose.onNodeWithTag("hourlyWage").performTextInput("12000")
            compose.onNodeWithTag("nickname").performTextInput("카페")
            compose.onNodeWithText("5인 이상").performScrollTo().performClick()
            compose.onNodeWithTag("saveProfile").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("currentWage").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("currentWage").assertTextEquals("₩12,000")
            val first = runBlocking { app.repository.getCurrentProfile()!! }
            scenario.close()
            scenario = ActivityScenario.launch(MainActivity::class.java)
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("currentWage").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("hourlyWage").assertDoesNotExist()
            compose.onNodeWithText("설정 수정").performClick()
            compose.onNodeWithTag("hourlyWage").assertTextContains("12000")
            compose.onNodeWithTag("nickname").assertTextContains("카페")
            compose.onNodeWithTag("hourlyWage").performTextReplacement("15000")
            compose.onNodeWithText("5인 미만").performScrollTo().performClick()
            compose.onNodeWithTag("saveProfile").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("currentWage").fetchSemanticsNodes().isNotEmpty() }
            scenario.close()
            scenario = ActivityScenario.launch(MainActivity::class.java)
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("currentWage").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("currentWage").assertTextEquals("₩15,000")
            compose.onNodeWithText("상시근로자 5인 미만").assertExists()
            val updated = runBlocking { app.repository.getCurrentProfile()!! }
            assertEquals(first.id, updated.id)
            assertEquals(first.createdAt, updated.createdAt)
            assertTrue(updated.updatedAt >= first.updatedAt)
        } finally { scenario.close() }
    }
}
