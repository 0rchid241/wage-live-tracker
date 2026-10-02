package com.orchid.wagelivetracker.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.orchid.wagelivetracker.data.repository.WorkProfile
import com.orchid.wagelivetracker.data.repository.WorkProfileStore
import com.orchid.wagelivetracker.domain.wage.WorkCondition
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface WorkProfileSetupState {
    data object Loading : WorkProfileSetupState
    data class Form(
        val original: WorkProfile? = null,
        val hourlyWage: String = "",
        val nickname: String = "",
        val hasAtLeastFiveEmployees: Boolean? = null,
        val wageError: String? = null,
        val employeeError: String? = null,
        val nicknameError: String? = null,
        val saveError: String? = null,
        val isSaving: Boolean = false,
    ) : WorkProfileSetupState
    data class Ready(val profile: WorkProfile) : WorkProfileSetupState
    data class LoadError(val message: String) : WorkProfileSetupState
}

class WorkProfileSetupViewModel(
    private val repository: WorkProfileStore,
    private val clock: Clock = Clock.systemDefaultZone(),
) : ViewModel() {
    private val mutableState = MutableStateFlow<WorkProfileSetupState>(WorkProfileSetupState.Loading)
    val state = mutableState.asStateFlow()

    init { load() }

    fun retryLoad() {
        if (mutableState.value is WorkProfileSetupState.LoadError) load()
    }

    private fun load() {
        mutableState.value = WorkProfileSetupState.Loading
        viewModelScope.launch {
            try {
                val profile = repository.getCurrentProfile()
                mutableState.value = if (profile == null) WorkProfileSetupState.Form() else WorkProfileSetupState.Ready(profile)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableState.value = WorkProfileSetupState.LoadError("근무 정보를 불러오지 못했어요. 다시 시도해 주세요.")
            }
        }
    }

    fun editProfile() {
        val current = (mutableState.value as? WorkProfileSetupState.Ready)?.profile ?: return
        mutableState.value = WorkProfileSetupState.Form(
            original = current,
            hourlyWage = current.condition.hourlyWage.stripTrailingZeros().toPlainString(),
            nickname = current.nickname,
            hasAtLeastFiveEmployees = current.condition.hasAtLeastFiveEmployees,
        )
    }

    fun cancelEdit() {
        val form = mutableState.value as? WorkProfileSetupState.Form ?: return
        if (!form.isSaving) form.original?.let { mutableState.value = WorkProfileSetupState.Ready(it) }
    }

    fun changeWage(value: String) = updateForm { it.copy(hourlyWage = value, wageError = null, saveError = null) }
    fun changeNickname(value: String) = updateForm { it.copy(nickname = value, nicknameError = null, saveError = null) }
    fun chooseEmployeeCount(value: Boolean) = updateForm { it.copy(hasAtLeastFiveEmployees = value, employeeError = null, saveError = null) }

    private fun updateForm(change: (WorkProfileSetupState.Form) -> WorkProfileSetupState.Form) {
        val form = mutableState.value as? WorkProfileSetupState.Form ?: return
        if (!form.isSaving) mutableState.value = change(form)
    }

    fun save() {
        val form = mutableState.value as? WorkProfileSetupState.Form ?: return
        if (form.isSaving) return
        val rawWage = form.hourlyWage.trim()
        // Bound length before parsing; pasted exponents/huge strings cannot allocate huge decimals.
        val wage = if (rawWage.length <= MAX_WAGE_INPUT_LENGTH && DECIMAL_INPUT.matches(rawWage)) rawWage.toBigDecimalOrNull() else null
        val wageError = when {
            rawWage.isEmpty() -> "시급을 입력해 주세요."
            wage == null -> "시급은 숫자로 입력해 주세요."
            wage.signum() <= 0 -> "시급은 0원보다 커야 해요."
            wage > MAX_HOURLY_WAGE -> "시급은 1,000,000원 이하로 입력해 주세요."
            else -> null
        }
        val employeeError = if (form.hasAtLeastFiveEmployees == null) "사업장 규모를 선택해 주세요." else null
        val nickname = form.nickname.trim()
        val nicknameError = if (nickname.length > MAX_NICKNAME_LENGTH) "별명은 80자 이하로 입력해 주세요." else null
        if (wageError != null || employeeError != null || nicknameError != null) {
            mutableState.value = form.copy(wageError = wageError, employeeError = employeeError, nicknameError = nicknameError, saveError = null)
            return
        }
        // Set Saving synchronously so rapid repeated clicks cannot enqueue another write.
        mutableState.value = form.copy(isSaving = true, wageError = null, employeeError = null, nicknameError = null, saveError = null)
        viewModelScope.launch {
            try {
                val original = form.original
                val now = LocalDateTime.now(clock)
                val parsedWage = checkNotNull(wage)
                // Display strips trailing zeroes; unchanged wage retains its stored value AND scale.
                val storedWage = original?.condition?.hourlyWage?.takeIf { it.compareTo(parsedWage) == 0 } ?: parsedWage
                val condition = original?.condition?.copy(
                    hourlyWage = storedWage, hasAtLeastFiveEmployees = checkNotNull(form.hasAtLeastFiveEmployees),
                ) ?: WorkCondition(storedWage, checkNotNull(form.hasAtLeastFiveEmployees))
                val profile = WorkProfile(
                    id = original?.id ?: 0, nickname = nickname, condition = condition,
                    createdAt = original?.createdAt ?: now,
                    // Honor storage's monotonic update policy if the device clock moves backwards.
                    updatedAt = original?.let { maxOf(now, it.updatedAt) } ?: now,
                )
                mutableState.value = WorkProfileSetupState.Ready(repository.saveProfile(profile))
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableState.value = form.copy(saveError = "저장하지 못했어요. 입력 내용을 확인하고 다시 시도해 주세요.")
            }
        }
    }

    class Factory(private val repository: WorkProfileStore) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(WorkProfileSetupViewModel::class.java))
            @Suppress("UNCHECKED_CAST")
            return WorkProfileSetupViewModel(repository) as T
        }
    }

    private companion object {
        val MAX_HOURLY_WAGE = BigDecimal("1000000")
        val DECIMAL_INPUT = Regex("-?[0-9]+(?:\\.[0-9]+)?")
        const val MAX_WAGE_INPUT_LENGTH = 64
        const val MAX_NICKNAME_LENGTH = 80
    }
}
