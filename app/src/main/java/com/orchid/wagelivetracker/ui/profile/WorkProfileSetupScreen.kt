package com.orchid.wagelivetracker.ui.profile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

@Composable
fun WorkProfileSetupScreen(
    state: WorkProfileSetupState,
    onWageChange: (String) -> Unit,
    onNicknameChange: (String) -> Unit,
    onEmployeeCountChange: (Boolean) -> Unit,
    onSave: () -> Unit,
    onEdit: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
) {
    val form = state as? WorkProfileSetupState.Form
    BackHandler(enabled = form != null && (form.original != null || form.isSaving)) {
        if (form?.isSaving != true) onCancel()
    }
    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).imePadding()
                .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("얼마벌었지", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            when (state) {
                WorkProfileSetupState.Loading -> {
                    CircularProgressIndicator()
                    Text("근무 정보를 불러오는 중이에요")
                }
                is WorkProfileSetupState.LoadError -> {
                    Text(state.message, color = MaterialTheme.colorScheme.error)
                    Button(onClick = onRetry) { Text("다시 시도") }
                }
                is WorkProfileSetupState.Ready -> {
                    Text("설정이 완료됐어요", style = MaterialTheme.typography.headlineMedium)
                    Text(state.profile.nickname.ifEmpty { "내 근무지" }, style = MaterialTheme.typography.titleMedium)
                    Text("현재 시급")
                    Text("₩${formatWage(state.profile.condition.hourlyWage)}", modifier = Modifier.testTag("currentWage"), style = MaterialTheme.typography.headlineLarge)
                    Text(if (state.profile.condition.hasAtLeastFiveEmployees) "상시근로자 5인 이상" else "상시근로자 5인 미만")
                    Button(onClick = onEdit, modifier = Modifier.fillMaxWidth()) { Text("설정 수정") }
                }
                is WorkProfileSetupState.Form -> ProfileForm(state, onWageChange, onNicknameChange, onEmployeeCountChange, onSave, onCancel)
            }
        }
    }
}

@Composable
private fun ProfileForm(
    form: WorkProfileSetupState.Form,
    onWageChange: (String) -> Unit,
    onNicknameChange: (String) -> Unit,
    onEmployeeCountChange: (Boolean) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    val focus = LocalFocusManager.current
    Text(if (form.original == null) "근무 정보를 알려주세요" else "근무 정보 수정", style = MaterialTheme.typography.headlineMedium)
    OutlinedTextField(
        value = form.hourlyWage, onValueChange = onWageChange,
        modifier = Modifier.fillMaxWidth().testTag("hourlyWage"),
        label = { Text("현재 시급") }, suffix = { Text("원") },
        singleLine = true, enabled = !form.isSaving, isError = form.wageError != null,
        supportingText = form.wageError?.let { message -> { Text(message) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
    )
    OutlinedTextField(
        value = form.nickname, onValueChange = onNicknameChange,
        modifier = Modifier.fillMaxWidth().testTag("nickname"),
        label = { Text("근무지 별명 (선택)") }, placeholder = { Text("예: PC방, 편의점, 카페") },
        singleLine = true, enabled = !form.isSaving, isError = form.nicknameError != null,
        supportingText = form.nicknameError?.let { message -> { Text(message) } },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("상시근로자 5인 이상 사업장인가요?", style = MaterialTheme.typography.titleMedium)
        Column(Modifier.selectableGroup()) {
            listOf(true to "5인 이상", false to "5인 미만").forEach { (value, label) ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .selectable(selected = form.hasAtLeastFiveEmployees == value, enabled = !form.isSaving,
                            role = Role.RadioButton, onClick = { onEmployeeCountChange(value) }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = form.hasAtLeastFiveEmployees == value, onClick = null, enabled = !form.isSaving)
                    Text(label, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
        Text("야간·연장 등 일부 가산수당 계산에 사용돼요.", style = MaterialTheme.typography.bodySmall)
        form.employeeError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    form.saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Button(
        onClick = { focus.clearFocus(); onSave() }, enabled = !form.isSaving,
        modifier = Modifier.fillMaxWidth().testTag("saveProfile"),
    ) {
        if (form.isSaving) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("저장 중…")
        } else Text(if (form.original == null) "저장하고 시작하기" else "변경사항 저장")
    }
    if (form.original != null) {
        TextButton(onClick = onCancel, enabled = !form.isSaving, modifier = Modifier.fillMaxWidth()) { Text("취소") }
    }
}

/** String/BigDecimal formatting only; never converts through Double or Float. */
internal fun formatWage(value: BigDecimal): String {
    val normalized = value.stripTrailingZeros()
    return DecimalFormat("#,##0", DecimalFormatSymbols(Locale.KOREA)).apply {
        maximumFractionDigits = normalized.scale().coerceAtLeast(0)
    }.format(normalized)
}
