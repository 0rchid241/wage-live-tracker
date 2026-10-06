package com.orchid.wagelivetracker.ui.shift

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.orchid.wagelivetracker.ui.profile.formatWage
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration

@Composable
fun LiveShiftScreen(
    state: LiveShiftState,
    onStart: () -> Unit,
    onStartBreak: () -> Unit,
    onEndBreak: () -> Unit,
    onFinish: () -> Unit,
    onConfirm: () -> Unit,
    onRetry: () -> Unit,
    onEdit: () -> Unit,
) {
    BackHandler(enabled = state is LiveShiftState.Summary) { onConfirm() }
    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("얼마벌었지", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            when (state) {
                LiveShiftState.Loading -> { CircularProgressIndicator(); Text("근무 기록을 불러오는 중이에요") }
                LiveShiftState.SetupRequired -> Unit // Setup is rendered by the application route.
                is LiveShiftState.Error -> {
                    Text(state.message, color = MaterialTheme.colorScheme.error)
                    Button(onClick = onRetry) { Text("다시 시도") }
                }
                is LiveShiftState.Idle -> {
                    Text("일하는 순간, 돈이 보인다", style = MaterialTheme.typography.headlineMedium)
                    Text(state.profile.nickname.ifEmpty { "내 근무지" }, style = MaterialTheme.typography.titleLarge)
                    Text("현재 시급")
                    Text("₩${formatWage(state.profile.condition.hourlyWage)}", Modifier.testTag("currentWage"), style = MaterialTheme.typography.headlineLarge)
                    Text(if (state.profile.condition.hasAtLeastFiveEmployees) "상시근로자 5인 이상" else "상시근로자 5인 미만")
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Button(onClick = onStart, enabled = !state.isStarting, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("startShift")) {
                        Text(if (state.isStarting) "출근 저장 중…" else "출근")
                    }
                    TextButton(onClick = onEdit, enabled = !state.isStarting, modifier = Modifier.fillMaxWidth()) { Text("설정 수정") }
                }
                is LiveShiftState.Active -> {
                    Text("현재 예상 급여", style = MaterialTheme.typography.titleMedium)
                    PayAmount(state.estimate.breakdown.totalEstimatedPay)
                    Card(colors = CardDefaults.cardColors(containerColor = if (state.onBreak) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(if (state.onBreak) "휴게 중" else "근무 중", Modifier.testTag("shiftStatus"), style = MaterialTheme.typography.titleLarge)
                            Text(if (state.onBreak) "급여 증가가 잠시 멈췄어요" else "+${state.estimate.payPerSecond.setScale(2, RoundingMode.HALF_UP).toPlainString()}원/초", Modifier.testTag("paySpeed"))
                            if (state.estimate.nightPremiumActive) Text("야간가산 적용 중", color = MaterialTheme.colorScheme.primary)
                            Text("경과시간 ${formatDuration(state.estimate.elapsed)}", Modifier.testTag("elapsed"))
                            Text("유급시간 ${formatDuration(state.estimate.breakdown.paidWorkDuration)}", Modifier.testTag("paidDuration"))
                        }
                    }
                    Breakdown(state.estimate)
                    state.error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = onRetry, enabled = state.operation == null) { Text("저장된 기록 다시 불러오기") }
                    }
                    if (state.operation != null) Text(if (state.operation == ShiftOperation.COMPLETING) "퇴근 정산 중…" else "저장 중…")
                    Button(
                        onClick = if (state.onBreak) onEndBreak else onStartBreak,
                        enabled = state.operation == null,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("toggleBreak"),
                    ) { Text(if (state.onBreak) "휴게 종료" else "휴게 시작") }
                    OutlinedButton(onClick = onFinish, enabled = state.operation == null, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("finishShift")) { Text("퇴근") }
                }
                is LiveShiftState.Summary -> {
                    Text("오늘도 수고했어요.", style = MaterialTheme.typography.headlineMedium)
                    Text("총 예상 급여")
                    PayAmount(state.estimate.breakdown.totalEstimatedPay)
                    Text("총 근무 경과시간 ${formatDuration(state.estimate.elapsed)}")
                    Text("총 휴게시간 ${formatDuration(state.estimate.breakDuration)}")
                    Text("유급시간 ${formatDuration(state.estimate.breakdown.paidWorkDuration)}")
                    Breakdown(state.estimate)
                    Text("입력한 근무조건으로 계산한 예상 금액이에요. 실제 지급액과 차이가 있을 수 있어요.", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("confirmSummary")) { Text("확인") }
                }
            }
        }
    }
}

@Composable
private fun PayAmount(amount: BigDecimal) {
    Text("₩${formatEstimatedPay(amount)}", Modifier.testTag("estimatedPay"),
        fontSize = 44.sp, lineHeight = 52.sp, fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun Breakdown(estimate: LiveWageEstimate) {
    OutlinedCard {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("급여 내역", style = MaterialTheme.typography.titleMedium)
            PayRow("기본급", estimate.breakdown.basePay)
            PayRow("야간가산", estimate.breakdown.nightPremium)
            PayRow("연장가산 (현재 미적용)", estimate.breakdown.overtimePremium)
        }
    }
}

@Composable
private fun PayRow(label: String, amount: BigDecimal) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text("₩${formatEstimatedPay(amount)}", style = MaterialTheme.typography.bodyMedium)
    }
}

/** Display only: truncate sub-won values so the counter never claims an unearned whole won. */
internal fun formatEstimatedPay(value: BigDecimal): String = formatWage(value.setScale(0, RoundingMode.DOWN))

internal fun formatDuration(value: Duration): String {
    val seconds = value.seconds
    return "%02d:%02d:%02d".format(seconds / 3600, seconds % 3600 / 60, seconds % 60)
}
