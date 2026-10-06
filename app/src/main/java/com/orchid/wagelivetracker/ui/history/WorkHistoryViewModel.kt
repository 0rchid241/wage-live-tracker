package com.orchid.wagelivetracker.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.orchid.wagelivetracker.data.repository.*
import com.orchid.wagelivetracker.domain.wage.WorkCondition
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime
import java.time.YearMonth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class HistoryEditor(
    val original: HistoryRecord?,
    val startedAt: LocalDateTime,
    val endedAt: LocalDateTime,
    val wage: String,
    val breaks: List<HistoryBreak>,
    val condition: WorkCondition,
    val workplaceName: String,
    val saving: Boolean = false,
    val error: String? = null,
) {
    fun draft(): CompletedShiftDraft {
        val raw = wage.trim()
        require(raw.length <= 64 && Regex("[0-9]+(?:\\.[0-9]+)?").matches(raw)) { "시급은 숫자로 입력해 주세요." }
        val parsed = BigDecimal(raw)
        require(parsed.signum() > 0 && parsed <= BigDecimal("1000000")) { "시급은 0원 초과, 1,000,000원 이하로 입력해 주세요." }
        val storedWage = original?.stored?.shift?.conditionSnapshot?.hourlyWage?.takeIf { it.compareTo(parsed) == 0 } ?: parsed
        require(endedAt > startedAt) { "퇴근은 출근보다 늦어야 해요. 날짜와 시간을 확인해 주세요." }
        val sorted = breaks.sortedBy { it.startedAt }
        sorted.forEachIndexed { index, rest ->
            require(rest.endedAt != null && rest.endedAt > rest.startedAt) { "휴게 종료는 시작보다 늦어야 해요." }
            require(rest.startedAt >= startedAt && rest.endedAt <= endedAt) { "휴게는 출근과 퇴근 사이에 있어야 해요." }
            require(index == 0 || sorted[index - 1].endedAt!! <= rest.startedAt) { "휴게시간이 서로 겹쳐요." }
        }
        return CompletedShiftDraft(startedAt, endedAt, storedWage, breaks)
    }

    fun preview(calculation: HistoryCalculation = HistoryCalculation()): HistoryEntry? = try {
        val input = draft()
        val id = original?.stored?.shift?.id ?: 1L
        val shift = ShiftRecord(id, original?.stored?.shift?.workProfileId ?: 1L, input.startedAt, input.endedAt,
            ShiftStatus.COMPLETED, condition.copy(hourlyWage = input.hourlyWage))
        calculation.entry(HistoryRecord(StoredShift(shift, input.breaks.map { BreakRecord(shiftId = id, startedAt = it.startedAt, endedAt = it.endedAt) }), workplaceName))
    } catch (_: IllegalArgumentException) { null }
}

sealed interface WorkHistoryState {
    data object Closed : WorkHistoryState
    data class Loading(val month: YearMonth) : WorkHistoryState
    data class MonthLoaded(val summary: HistoryMonth, val error: String? = null) : WorkHistoryState
    data class Detail(val month: YearMonth, val entry: HistoryEntry, val confirmingDelete: Boolean = false,
        val deleting: Boolean = false, val error: String? = null) : WorkHistoryState
    data class Editing(val month: YearMonth, val editor: HistoryEditor) : WorkHistoryState
    data class Error(val month: YearMonth, val message: String) : WorkHistoryState
}

class WorkHistoryViewModel(
    private val repository: WorkHistoryStore,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val calculation: HistoryCalculation = HistoryCalculation(),
) : ViewModel() {
    private val mutableState = MutableStateFlow<WorkHistoryState>(WorkHistoryState.Closed)
    val state = mutableState.asStateFlow()
    private var readJob: Job? = null

    fun open(month: YearMonth = YearMonth.now(clock)) {
        if (state.value != WorkHistoryState.Closed) return
        loadMonth(month)
    }

    fun changeMonth(delta: Long) {
        val current = state.value as? WorkHistoryState.MonthLoaded ?: return
        loadMonth(current.summary.month.plusMonths(delta))
    }

    fun retry() { (state.value as? WorkHistoryState.Error)?.let { loadMonth(it.month) } }

    private fun loadMonth(month: YearMonth) {
        readJob?.cancel()
        mutableState.value = WorkHistoryState.Loading(month)
        readJob = viewModelScope.launch {
            try { mutableState.value = WorkHistoryState.MonthLoaded(calculation.month(month, repository.getCompletedShifts(month)))
            } catch (error: CancellationException) { throw error
            } catch (_: Exception) { mutableState.value = WorkHistoryState.Error(month, "기록을 불러오지 못했어요. 다시 시도해 주세요.") }
        }
    }

    fun detail(id: Long) {
        val current = state.value as? WorkHistoryState.MonthLoaded ?: return
        readJob?.cancel()
        mutableState.value = WorkHistoryState.Loading(current.summary.month)
        readJob = viewModelScope.launch {
            try {
                val record = checkNotNull(repository.getCompletedShift(id))
                mutableState.value = WorkHistoryState.Detail(current.summary.month, calculation.entry(record))
            } catch (error: CancellationException) { throw error
            } catch (_: Exception) { mutableState.value = current.copy(error = "상세 기록을 불러오지 못했어요. 다시 시도해 주세요.") }
        }
    }

    fun edit() {
        val current = state.value as? WorkHistoryState.Detail ?: return
        if (current.deleting) return
        val record = current.entry.record
        val shift = record.stored.shift
        mutableState.value = WorkHistoryState.Editing(current.month, HistoryEditor(record, shift.startedAt, checkNotNull(shift.endedAt),
            shift.conditionSnapshot.hourlyWage.stripTrailingZeros().toPlainString(), record.stored.breaks.map { HistoryBreak(it.startedAt, it.endedAt) },
            shift.conditionSnapshot, record.workplaceName))
    }

    fun add() {
        val current = state.value as? WorkHistoryState.MonthLoaded ?: return
        mutableState.value = WorkHistoryState.Loading(current.summary.month)
        readJob = viewModelScope.launch {
            try {
                val profile = repository.getCurrentProfile()
                if (profile == null) mutableState.value = current.copy(error = "근무조건 설정이 필요해요. 홈에서 먼저 설정해 주세요.")
                else {
                    val today = LocalDateTime.now(clock).toLocalDate()
                    val date = if (YearMonth.from(today) == current.summary.month) today else current.summary.month.atDay(1)
                    mutableState.value = WorkHistoryState.Editing(current.summary.month, HistoryEditor(null, date.atTime(9, 0), date.atTime(18, 0),
                        profile.condition.hourlyWage.stripTrailingZeros().toPlainString(), emptyList(), profile.condition, profile.nickname.ifEmpty { "내 근무지" }))
                }
            } catch (error: CancellationException) { throw error
            } catch (_: Exception) { mutableState.value = current.copy(error = "근무조건을 불러오지 못했어요. 다시 시도해 주세요.") }
        }
    }

    private fun changeEditor(change: (HistoryEditor) -> HistoryEditor) {
        val current = state.value as? WorkHistoryState.Editing ?: return
        if (!current.editor.saving) mutableState.value = current.copy(editor = change(current.editor).copy(error = null))
    }
    fun changeWage(value: String) = changeEditor { it.copy(wage = value) }
    fun changeStart(value: LocalDateTime) = changeEditor { it.copy(startedAt = value) }
    fun changeEnd(value: LocalDateTime) = changeEditor { it.copy(endedAt = value) }
    fun addBreak() = changeEditor {
        val middle = if (it.endedAt > it.startedAt) it.startedAt.plusSeconds(Duration.between(it.startedAt, it.endedAt).seconds / 2) else it.startedAt
        val end = if (it.endedAt > middle) minOf(middle.plusMinutes(30), it.endedAt) else middle.plusMinutes(30)
        it.copy(breaks = it.breaks + HistoryBreak(middle, end))
    }
    fun changeBreak(index: Int, value: HistoryBreak) = changeEditor { it.copy(breaks = it.breaks.mapIndexed { i, rest -> if (i == index) value else rest }) }
    fun removeBreak(index: Int) = changeEditor { it.copy(breaks = it.breaks.filterIndexed { i, _ -> i != index }) }

    fun save() {
        val current = state.value as? WorkHistoryState.Editing ?: return
        if (current.editor.saving) return
        val draft = try { current.editor.draft() } catch (error: IllegalArgumentException) {
            mutableState.value = current.copy(editor = current.editor.copy(error = error.message)); return
        }
        mutableState.value = current.copy(editor = current.editor.copy(saving = true, error = null))
        viewModelScope.launch {
            try {
                val original = current.editor.original
                val saved = if (original == null) repository.addCompletedShift(draft) else repository.updateCompletedShift(original.stored.shift.id, draft)
                val month = YearMonth.from(saved.stored.shift.startedAt)
                if (original == null) loadMonth(month) else mutableState.value = WorkHistoryState.Detail(month, calculation.entry(saved))
            } catch (error: CancellationException) { throw error
            } catch (_: Exception) { mutableState.value = current.copy(editor = current.editor.copy(error = "저장하지 못했어요. 입력을 유지했으니 다시 시도해 주세요.")) }
        }
    }

    fun requestDelete() {
        val current = state.value as? WorkHistoryState.Detail ?: return
        if (!current.deleting) mutableState.value = current.copy(confirmingDelete = true, error = null)
    }
    fun cancelDelete() {
        val current = state.value as? WorkHistoryState.Detail ?: return
        if (!current.deleting) mutableState.value = current.copy(confirmingDelete = false)
    }
    fun confirmDelete() {
        val current = state.value as? WorkHistoryState.Detail ?: return
        if (!current.confirmingDelete || current.deleting) return
        mutableState.value = current.copy(deleting = true, confirmingDelete = false)
        viewModelScope.launch {
            try { repository.deleteCompletedShift(current.entry.record.stored.shift.id); loadMonth(current.month)
            } catch (error: CancellationException) { throw error
            } catch (_: Exception) { mutableState.value = current.copy(confirmingDelete = false, deleting = false, error = "삭제하지 못했어요. 다시 시도해 주세요.") }
        }
    }

    fun back() {
        when (val current = state.value) {
            is WorkHistoryState.Editing -> if (!current.editor.saving) {
                val original = current.editor.original
                if (original == null) loadMonth(current.month) else mutableState.value = WorkHistoryState.Detail(current.month, calculation.entry(original))
            }
            is WorkHistoryState.Detail -> if (!current.deleting) {
                if (current.confirmingDelete) cancelDelete() else loadMonth(current.month)
            }
            is WorkHistoryState.MonthLoaded, is WorkHistoryState.Error, is WorkHistoryState.Loading -> {
                readJob?.cancel(); mutableState.value = WorkHistoryState.Closed
            }
            WorkHistoryState.Closed -> Unit
        }
    }

    class Factory(private val repository: WorkHistoryStore, private val clock: Clock) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(WorkHistoryViewModel::class.java))
            @Suppress("UNCHECKED_CAST") return WorkHistoryViewModel(repository, clock) as T
        }
    }
}
