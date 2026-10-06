package com.orchid.wagelivetracker.ui.shift

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.orchid.wagelivetracker.data.repository.LiveShiftStore
import com.orchid.wagelivetracker.data.repository.StoredShift
import com.orchid.wagelivetracker.data.repository.WorkProfile
import com.orchid.wagelivetracker.data.repository.ShiftSessionStore
import com.orchid.wagelivetracker.data.repository.ShiftStatus
import java.time.Clock
import java.time.LocalDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

enum class ShiftOperation { STARTING_BREAK, ENDING_BREAK, COMPLETING }

sealed interface LiveShiftState {
    data object Loading : LiveShiftState
    data object SetupRequired : LiveShiftState
    data class Idle(val profile: WorkProfile, val isStarting: Boolean = false, val error: String? = null) : LiveShiftState
    data class Active(
        val stored: StoredShift,
        val estimate: LiveWageEstimate,
        val operation: ShiftOperation? = null,
        val error: String? = null,
    ) : LiveShiftState {
        val onBreak: Boolean get() = stored.breaks.any { it.endedAt == null }
    }
    data class Summary(val stored: StoredShift, val estimate: LiveWageEstimate) : LiveShiftState
    data class Error(val message: String) : LiveShiftState
}

class LiveShiftViewModel(
    private val repository: LiveShiftStore,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val projection: LiveWageProjection = LiveWageProjection(),
) : ViewModel() {
    private val mutableState = MutableStateFlow<LiveShiftState>(LiveShiftState.Loading)
    val state = mutableState.asStateFlow()
    private var ticker: Job? = null

    init {
        reload()
        (repository as? ShiftSessionStore)?.let { sessions ->
            viewModelScope.launch {
                sessions.observeInProgressShift().catch {
                    (state.value as? LiveShiftState.Active)?.let { current ->
                        mutableState.value = current.copy(error = "변경된 기록을 불러오지 못했어요. 다시 불러와 주세요.")
                    }
                }.collect { stored ->
                    try {
                        val current = state.value
                        // Our own saves publish their result; observation synchronizes external notification actions.
                        if (current is LiveShiftState.Active && current.operation == null) {
                            if (stored != null && stored != current.stored) mutableState.value = active(stored)
                            else if (stored == null) {
                                val saved = sessions.getShift(current.stored.shift.id)
                                if (saved?.shift?.status == ShiftStatus.COMPLETED) {
                                    mutableState.value = LiveShiftState.Summary(saved, projection.calculate(saved, checkNotNull(saved.shift.endedAt)))
                                } else reload()
                            }
                        } else if (current is LiveShiftState.Idle && !current.isStarting && stored != null) mutableState.value = active(stored)
                    } catch (error: CancellationException) { throw error
                    } catch (_: Exception) {
                        (state.value as? LiveShiftState.Active)?.let { current ->
                            mutableState.value = current.copy(error = "변경된 기록을 불러오지 못했어요. 다시 불러와 주세요.")
                        }
                    }
                }
            }
        }
    }

    fun reload() {
        if ((state.value as? LiveShiftState.Active)?.operation != null || (state.value as? LiveShiftState.Idle)?.isStarting == true) return
        mutableState.value = LiveShiftState.Loading
        viewModelScope.launch {
            try {
                // Active shift wins even when the current profile changed or is missing.
                val stored = repository.getInProgressShift()
                mutableState.value = if (stored != null) active(stored) else {
                    repository.getCurrentProfile()?.let { LiveShiftState.Idle(it) } ?: LiveShiftState.SetupRequired
                }
            } catch (error: CancellationException) { throw error
            } catch (_: Exception) { mutableState.value = LiveShiftState.Error("근무 기록을 불러오지 못했어요. 다시 시도해 주세요.") }
        }
    }

    fun openSetup() {
        val idle = state.value as? LiveShiftState.Idle ?: return
        if (!idle.isStarting) mutableState.value = LiveShiftState.SetupRequired
    }

    fun startShift() {
        val idle = state.value as? LiveShiftState.Idle ?: return
        if (idle.isStarting) return
        mutableState.value = idle.copy(isStarting = true, error = null)
        viewModelScope.launch {
            try {
                val shift = repository.startShift(idle.profile.id, LocalDateTime.now(clock))
                mutableState.value = active(StoredShift(shift, emptyList()))
            } catch (error: CancellationException) { throw error
            } catch (_: Exception) { mutableState.value = idle.copy(error = "출근을 저장하지 못했어요. 다시 시도해 주세요.") }
        }
    }

    fun startBreak() = changeShift(ShiftOperation.STARTING_BREAK) { current, now -> repository.startBreak(current.stored.shift.id, now) }
    fun endBreak() = changeShift(ShiftOperation.ENDING_BREAK) { current, now -> repository.endBreak(current.stored.shift.id, now) }
    fun finishShift() = changeShift(ShiftOperation.COMPLETING) { current, now -> repository.finishShift(current.stored.shift.id, now) }

    private fun changeShift(operation: ShiftOperation, save: suspend (LiveShiftState.Active, LocalDateTime) -> StoredShift) {
        val current = state.value as? LiveShiftState.Active ?: return
        if (current.operation != null) return
        if (operation == ShiftOperation.STARTING_BREAK && current.onBreak) return
        if (operation == ShiftOperation.ENDING_BREAK && !current.onBreak) return
        mutableState.value = current.copy(operation = operation, error = null)
        viewModelScope.launch {
            try {
                val stored = save(current, LocalDateTime.now(clock))
                mutableState.value = if (operation == ShiftOperation.COMPLETING) {
                    LiveShiftState.Summary(stored, projection.calculate(stored, checkNotNull(stored.shift.endedAt)))
                } else active(stored)
            } catch (error: CancellationException) { throw error
            } catch (_: Exception) {
                val message = "변경을 저장하지 못했어요. 시각을 확인하고 다시 시도해 주세요."
                // A notification action may have committed while this screen's operation failed.
                // Recover that Room result rather than restoring an outdated pre-operation copy.
                val sessions = repository as? ShiftSessionStore
                mutableState.value = try {
                    val latest = sessions?.getShift(current.stored.shift.id)
                    when {
                        latest?.shift?.status == ShiftStatus.COMPLETED -> LiveShiftState.Summary(latest, projection.calculate(latest, checkNotNull(latest.shift.endedAt)))
                        latest != null -> active(latest).copy(error = message)
                        else -> current.copy(error = message)
                    }
                } catch (error: CancellationException) { throw error
                } catch (_: Exception) { current.copy(error = message) }
            }
        }
    }

    fun dismissSummary() { if (state.value is LiveShiftState.Summary) reload() }

    /** No database read/write on a tick. Tests drive the injected clock and call this directly. */
    fun refreshTime() {
        val current = state.value as? LiveShiftState.Active ?: return
        if (current.operation != null) return
        try {
            val now = maxOf(LocalDateTime.now(clock), current.estimate.at)
            mutableState.value = current.copy(estimate = projection.calculate(current.stored, now))
        } catch (_: Exception) {
            mutableState.value = current.copy(error = "급여를 계산하지 못했어요. 근무 기록을 다시 확인해 주세요.")
        }
    }

    /** The Activity lifecycle controls the ticker; background updates need no service. */
    fun setForeground(visible: Boolean) {
        ticker?.cancel()
        ticker = null
        if (visible) {
            ticker = viewModelScope.launch {
                while (true) { refreshTime(); delay(1_000) }
            }
        }
    }

    private fun active(stored: StoredShift) = LiveShiftState.Active(stored, projection.calculate(stored, LocalDateTime.now(clock)))

    class Factory(private val repository: LiveShiftStore, private val clock: Clock) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(LiveShiftViewModel::class.java))
            @Suppress("UNCHECKED_CAST")
            return LiveShiftViewModel(repository, clock) as T
        }
    }
}
