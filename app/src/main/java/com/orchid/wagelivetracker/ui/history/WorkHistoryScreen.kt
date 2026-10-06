package com.orchid.wagelivetracker.ui.history

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.orchid.wagelivetracker.data.repository.HistoryBreak
import com.orchid.wagelivetracker.ui.profile.formatWage
import com.orchid.wagelivetracker.ui.shift.formatDuration
import com.orchid.wagelivetracker.ui.shift.formatEstimatedPay
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class HistoryActions(
    val back: () -> Unit, val month: (Long) -> Unit, val detail: (Long) -> Unit,
    val add: () -> Unit, val edit: () -> Unit, val delete: () -> Unit,
    val cancelDelete: () -> Unit, val confirmDelete: () -> Unit, val retry: () -> Unit,
    val wage: (String) -> Unit, val start: (LocalDateTime) -> Unit, val end: (LocalDateTime) -> Unit,
    val addBreak: () -> Unit, val changeBreak: (Int, HistoryBreak) -> Unit, val removeBreak: (Int) -> Unit,
    val save: () -> Unit,
) {
    constructor(vm: WorkHistoryViewModel) : this(vm::back, vm::changeMonth, vm::detail, vm::add, vm::edit, vm::requestDelete,
        vm::cancelDelete, vm::confirmDelete, vm::retry, vm::changeWage, vm::changeStart, vm::changeEnd,
        vm::addBreak, vm::changeBreak, vm::removeBreak, vm::save)
}

@Composable
fun WorkHistoryScreen(state: WorkHistoryState, actions: HistoryActions) {
    BackHandler(enabled = state != WorkHistoryState.Closed) { actions.back() }
    val busy = (state as? WorkHistoryState.Editing)?.editor?.saving == true || (state as? WorkHistoryState.Detail)?.deleting == true
    val routeKey = when (state) {
        is WorkHistoryState.MonthLoaded -> "month:${state.summary.month}"
        is WorkHistoryState.Detail -> "detail:${state.entry.record.stored.shift.id}"
        is WorkHistoryState.Editing -> "editor:${state.editor.original?.stored?.shift?.id}"
        is WorkHistoryState.Loading -> "loading"
        is WorkHistoryState.Error -> "error"
        WorkHistoryState.Closed -> "closed"
    }
    // Retain scroll during field edits; a new route starts with its heading/summary visible.
    val scrollState = remember(routeKey) { ScrollState(0) }
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(scrollState).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            TextButton(onClick = actions.back, enabled = !busy, modifier = Modifier.testTag("historyBack")) {
                Text(if (state is WorkHistoryState.MonthLoaded || state is WorkHistoryState.Error || state is WorkHistoryState.Loading) "홈으로" else "뒤로")
            }
            when (state) {
                WorkHistoryState.Closed -> Unit
                is WorkHistoryState.Loading -> { CircularProgressIndicator(); Text("기록을 불러오는 중이에요") }
                is WorkHistoryState.Error -> {
                    Text(state.message, color = MaterialTheme.colorScheme.error)
                    Button(onClick = actions.retry) { Text("다시 시도") }
                }
                is WorkHistoryState.MonthLoaded -> MonthContent(state, actions)
                is WorkHistoryState.Detail -> DetailContent(state, actions)
                is WorkHistoryState.Editing -> EditorContent(state.editor, actions)
            }
        }
    }
    if (state is WorkHistoryState.Detail && state.confirmingDelete) {
        AlertDialog(onDismissRequest = actions.cancelDelete, title = { Text("근무 기록을 삭제할까요?") },
            text = { Text("이 근무와 휴게 기록이 함께 삭제돼요. 삭제한 기록은 되돌릴 수 없어요.") },
            confirmButton = { TextButton(onClick = actions.confirmDelete, modifier = Modifier.testTag("confirmHistoryDelete")) { Text("삭제") } },
            dismissButton = { TextButton(onClick = actions.cancelDelete) { Text("취소") } })
    }
}

@Composable
private fun ColumnScope.MonthContent(state: WorkHistoryState.MonthLoaded, actions: HistoryActions) {
    val summary = state.summary
    Text("근무 기록", style = MaterialTheme.typography.headlineMedium)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { actions.month(-1) }, modifier = Modifier.testTag("previousMonth")) { Text("이전 달") }
        Text("${summary.month.year}년 ${summary.month.monthValue}월", Modifier.testTag("selectedMonth"), style = MaterialTheme.typography.titleLarge)
        TextButton(onClick = { actions.month(1) }, modifier = Modifier.testTag("nextMonth")) { Text("다음 달") }
    }
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("월 총 예상 급여")
            Text("₩${formatEstimatedPay(summary.totalPay)}", Modifier.testTag("monthPay"), style = MaterialTheme.typography.headlineLarge)
            Text("월 총 유급시간 ${formatDuration(summary.paidDuration)}", Modifier.testTag("monthPaid"))
            Text("월 총 휴게시간 ${formatDuration(summary.breakDuration)}", Modifier.testTag("monthBreak"))
        }
    }
    Text("출근한 날짜의 월에 집계해요. 진행 중 근무는 제외돼요.", style = MaterialTheme.typography.bodySmall)
    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Button(onClick = actions.add, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("addHistory")) { Text("근무 직접 추가") }
    if (summary.entries.isEmpty()) Text("이 달에는 완료한 근무가 없어요.")
    summary.entries.forEach { entry ->
        val shift = entry.record.stored.shift
        OutlinedCard(onClick = { actions.detail(shift.id) }, modifier = Modifier.fillMaxWidth().testTag("historyShift${shift.id}")) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${shift.startedAt.format(dateFormat)} · ${entry.record.workplaceName}", style = MaterialTheme.typography.titleMedium)
                Text("${shift.startedAt.format(dateTimeFormat)} → ${shift.endedAt!!.format(dateTimeFormat)}")
                Text("유급시간 ${formatDuration(entry.breakdown.paidWorkDuration)}")
                Text("₩${formatEstimatedPay(entry.breakdown.totalEstimatedPay)}", style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}

@Composable
private fun ColumnScope.DetailContent(state: WorkHistoryState.Detail, actions: HistoryActions) {
    val entry = state.entry
    val shift = entry.record.stored.shift
    Text("근무 상세", style = MaterialTheme.typography.headlineMedium)
    Text(entry.record.workplaceName)
    Text("출근 ${shift.startedAt.format(dateTimeFormat)}")
    Text("퇴근 ${shift.endedAt!!.format(dateTimeFormat)}")
    Text("총 경과시간 ${formatDuration(entry.elapsed)}")
    Text("휴게시간 ${formatDuration(entry.breakDuration)}")
    Text("유급시간 ${formatDuration(entry.breakdown.paidWorkDuration)}")
    Text("적용 시급 ₩${formatWage(shift.conditionSnapshot.hourlyWage)}")
    Text(if (shift.conditionSnapshot.hasAtLeastFiveEmployees) "상시근로자 5인 이상" else "상시근로자 5인 미만")
    entry.record.stored.breaks.forEachIndexed { index, rest -> Text("휴게 ${index + 1}: ${rest.startedAt.format(dateTimeFormat)} → ${rest.endedAt!!.format(dateTimeFormat)}") }
    BreakdownContent(entry)
    Text("입력한 근무조건으로 계산한 예상 금액이에요. 실제 지급액과 차이가 있을 수 있어요.", style = MaterialTheme.typography.bodySmall)
    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Button(onClick = actions.edit, enabled = !state.deleting, modifier = Modifier.fillMaxWidth().testTag("editHistory")) { Text("기록 수정") }
    OutlinedButton(onClick = actions.delete, enabled = !state.deleting, modifier = Modifier.fillMaxWidth().testTag("deleteHistory")) { Text(if (state.deleting) "삭제 중…" else "삭제") }
}

@Composable
private fun BreakdownContent(entry: HistoryEntry) {
    OutlinedCard {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("기본급 ₩${formatEstimatedPay(entry.breakdown.basePay)}")
            Text("야간가산 ₩${formatEstimatedPay(entry.breakdown.nightPremium)}")
            Text("연장가산 (현재 미적용) ₩${formatEstimatedPay(entry.breakdown.overtimePremium)}")
            Text("총 예상 급여 ₩${formatEstimatedPay(entry.breakdown.totalEstimatedPay)}", Modifier.testTag("historyTotalPay"), style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun ColumnScope.EditorContent(editor: HistoryEditor, actions: HistoryActions) {
    val focus = LocalFocusManager.current
    Text(if (editor.original == null) "근무 직접 추가" else "근무 기록 수정", style = MaterialTheme.typography.headlineMedium)
    Text(editor.workplaceName)
    Text(if (editor.original == null) "현재 근무조건을 복사해 완료 기록으로 저장해요." else "이 근무의 적용 시급만 바뀌어요. 현재 근무조건은 유지돼요.", style = MaterialTheme.typography.bodySmall)
    DateTimeField("출근", "editStart", editor.startedAt, !editor.saving, actions.start)
    DateTimeField("퇴근", "editEnd", editor.endedAt, !editor.saving, actions.end)
    OutlinedTextField(value = editor.wage, onValueChange = actions.wage, label = { Text("적용 시급") }, suffix = { Text("원") },
        singleLine = true, enabled = !editor.saving, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth().testTag("historyWage"))
    Text("휴게 목록", style = MaterialTheme.typography.titleMedium)
    if (editor.breaks.isEmpty()) Text("휴게 없음")
    editor.breaks.forEachIndexed { index, rest ->
        OutlinedCard {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("휴게 ${index + 1}")
                DateTimeField("휴게 시작", "break${index}Start", rest.startedAt, !editor.saving) { actions.changeBreak(index, rest.copy(startedAt = it)) }
                DateTimeField("휴게 종료", "break${index}End", rest.endedAt ?: rest.startedAt, !editor.saving) { actions.changeBreak(index, rest.copy(endedAt = it)) }
                TextButton(onClick = { actions.removeBreak(index) }, enabled = !editor.saving, modifier = Modifier.testTag("removeBreak$index")) { Text("휴게 삭제") }
            }
        }
    }
    OutlinedButton(onClick = actions.addBreak, enabled = !editor.saving, modifier = Modifier.fillMaxWidth().testTag("addHistoryBreak")) { Text("휴게 추가") }
    editor.preview()?.let { BreakdownContent(it) }
    editor.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("historySaveError")) }
    Button(onClick = { focus.clearFocus(); actions.save() }, enabled = !editor.saving,
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("saveHistory")) { Text(if (editor.saving) "저장 중…" else "기록 저장") }
    TextButton(onClick = actions.back, enabled = !editor.saving, modifier = Modifier.fillMaxWidth()) { Text("취소") }
}

/** Picker state is UI-only. The editor receives typed LocalDateTime values and is independently testable. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateTimeField(label: String, tag: String, value: LocalDateTime, enabled: Boolean, onChange: (LocalDateTime) -> Unit) {
    var dateOpen by remember { mutableStateOf(false) }
    var timeOpen by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { dateOpen = true }, enabled = enabled, modifier = Modifier.weight(1f).testTag("${tag}Date")) { Text(value.format(dateFormat)) }
            OutlinedButton(onClick = { timeOpen = true }, enabled = enabled, modifier = Modifier.testTag("${tag}Time")) { Text(value.format(timeFormat)) }
        }
    }
    if (dateOpen) {
        val picker = rememberDatePickerState(initialSelectedDateMillis = value.toLocalDate().atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
        DatePickerDialog(onDismissRequest = { dateOpen = false },
            confirmButton = { TextButton(onClick = {
                picker.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atOffset(ZoneOffset.UTC).toLocalDate().atTime(value.toLocalTime())) }
                dateOpen = false
            }, enabled = picker.selectedDateMillis != null, modifier = Modifier.testTag("pickerConfirm")) { Text("확인") } },
            dismissButton = { TextButton(onClick = { dateOpen = false }) { Text("취소") } }) {
            DatePicker(state = picker, showModeToggle = false)
        }
    }
    if (timeOpen) {
        val picker = rememberTimePickerState(initialHour = value.hour, initialMinute = value.minute, is24Hour = true)
        AlertDialog(onDismissRequest = { timeOpen = false }, title = { Text("$label 시간") }, text = { TimePicker(state = picker) },
            confirmButton = { TextButton(onClick = {
                onChange(value.toLocalDate().atTime(picker.hour, picker.minute)); timeOpen = false
            }, modifier = Modifier.testTag("pickerConfirm")) { Text("확인") } },
            dismissButton = { TextButton(onClick = { timeOpen = false }) { Text("취소") } })
    }
}

private val dateFormat = DateTimeFormatter.ofPattern("yyyy.MM.dd")
private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
private val dateTimeFormat = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm")
