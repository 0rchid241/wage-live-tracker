package com.orchid.wagelivetracker.ui.profile

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.orchid.wagelivetracker.data.repository.WorkProfile
import com.orchid.wagelivetracker.domain.wage.WorkCondition
import com.orchid.wagelivetracker.ui.theme.WageLiveTrackerTheme
import java.math.BigDecimal
import java.time.LocalDateTime
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class WorkProfileSetupScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun show(
        state: WorkProfileSetupState,
        onWage: (String) -> Unit = {},
        onNickname: (String) -> Unit = {},
        onEmployees: (Boolean) -> Unit = {},
        onSave: () -> Unit = {},
        onEdit: () -> Unit = {},
        onRetry: () -> Unit = {},
    ) {
        compose.setContent {
            WageLiveTrackerTheme {
                WorkProfileSetupScreen(state, onWage, onNickname, onEmployees, onSave, onEdit, {}, onRetry)
            }
        }
    }

    @Test fun mandatoryAndOptionalFieldsAreVisibleWithoutDefaultEmployeeSelection() {
        show(WorkProfileSetupState.Form())
        compose.onNodeWithTag("hourlyWage").assertIsDisplayed()
        compose.onNodeWithTag("nickname").assertIsDisplayed()
        compose.onNodeWithText("5인 이상").assertIsNotSelected()
        compose.onNodeWithText("5인 미만").assertIsNotSelected()
    }

    @Test fun inputAndEmployeeChoiceReachCallbacks() {
        val state = mutableStateOf(WorkProfileSetupState.Form())
        compose.setContent {
            WageLiveTrackerTheme {
                WorkProfileSetupScreen(state.value,
                    { state.value = state.value.copy(hourlyWage = it) },
                    { state.value = state.value.copy(nickname = it) },
                    { state.value = state.value.copy(hasAtLeastFiveEmployees = it) },
                    {}, {}, {}, {},
                )
            }
        }
        compose.onNodeWithTag("hourlyWage").performTextInput("12000")
        compose.onNodeWithTag("nickname").performTextInput("카페")
        compose.onNodeWithText("5인 미만").performClick()
        compose.runOnIdle {
            assertEquals("12000", state.value.hourlyWage)
            assertEquals("카페", state.value.nickname)
            assertEquals(false, state.value.hasAtLeastFiveEmployees)
        }
    }

    @Test fun invalidWageAndMissingEmployeeErrorsAreRendered() {
        show(WorkProfileSetupState.Form(wageError = "시급은 0원보다 커야 해요.", employeeError = "사업장 규모를 선택해 주세요."))
        compose.onNodeWithText("시급은 0원보다 커야 해요.").assertIsDisplayed()
        compose.onNodeWithText("사업장 규모를 선택해 주세요.").assertIsDisplayed()
    }

    @Test fun validInputCanInvokeSave() {
        var calls = 0
        show(WorkProfileSetupState.Form(hourlyWage = "12000", hasAtLeastFiveEmployees = true), onSave = { calls++ })
        compose.onNodeWithTag("saveProfile").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, calls) }
    }

    @Test fun existingValuesArePrefilledAndSelected() {
        val profile = WorkProfile(1, "PC방", WorkCondition(BigDecimal("12000"), true), LocalDateTime.parse("2026-10-02T12:00"))
        show(WorkProfileSetupState.Form(profile, "12000", "PC방", true))
        compose.onNodeWithTag("hourlyWage").assertTextContains("12000")
        compose.onNodeWithTag("nickname").assertTextContains("PC방")
        compose.onNodeWithText("5인 이상").assertIsSelected()
        compose.onNodeWithText("변경사항 저장").assertExists()
    }

    @Test fun savingDisablesSubmissionAndInput() {
        show(WorkProfileSetupState.Form(hourlyWage = "12000", hasAtLeastFiveEmployees = true, isSaving = true))
        compose.onNodeWithTag("hourlyWage").assertIsNotEnabled()
        compose.onNodeWithTag("nickname").assertIsNotEnabled()
        compose.onNodeWithTag("saveProfile").assertIsNotEnabled()
        compose.onNodeWithText("저장 중…").assertExists()
    }

    @Test fun readyShowsFormattedWageAndEditCallback() {
        var edits = 0
        val profile = WorkProfile(1, "", WorkCondition(BigDecimal("12000.000"), false), LocalDateTime.parse("2026-10-02T12:00"))
        show(WorkProfileSetupState.Ready(profile), onEdit = { edits++ })
        compose.onNodeWithText("설정이 완료됐어요").assertIsDisplayed()
        compose.onNodeWithTag("currentWage").assertTextEquals("₩12,000")
        compose.onNodeWithText("내 근무지").assertIsDisplayed()
        compose.onNodeWithText("설정 수정").performClick()
        compose.runOnIdle { assertEquals(1, edits) }
    }

    @Test fun loadErrorOffersRetryAndSaveErrorKeepsForm() {
        var retries = 0
        show(WorkProfileSetupState.LoadError("불러오기 실패"), onRetry = { retries++ })
        compose.onNodeWithText("불러오기 실패").assertIsDisplayed()
        compose.onNodeWithText("다시 시도").performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test fun selectionChangesInStateAreReflectedInRadioButtons() {
        val state = mutableStateOf<WorkProfileSetupState>(WorkProfileSetupState.Form())
        compose.setContent {
            WageLiveTrackerTheme {
                WorkProfileSetupScreen(state.value, {}, {}, { selected ->
                    state.value = (state.value as WorkProfileSetupState.Form).copy(hasAtLeastFiveEmployees = selected)
                }, {}, {}, {}, {})
            }
        }
        compose.onNodeWithText("5인 이상").performClick().assertIsSelected()
        compose.onNodeWithText("5인 미만").performClick().assertIsSelected()
        compose.onNodeWithText("5인 이상").assertIsNotSelected()
    }
}
