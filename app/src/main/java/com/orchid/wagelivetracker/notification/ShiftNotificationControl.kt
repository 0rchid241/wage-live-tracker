package com.orchid.wagelivetracker.notification

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.orchid.wagelivetracker.ui.shift.LiveShiftState
import com.orchid.wagelivetracker.WageApplication

data class NotificationControl(val message: String?, val enable: () -> Unit)

/** Starts only while the Activity is resumed and a saved active session is visible. */
@Composable
fun rememberShiftNotificationControl(state: LiveShiftState): NotificationControl {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var refresh by remember { mutableIntStateOf(0) }
    var failed by remember { mutableStateOf(false) }
    var explain by remember { mutableStateOf(false) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) { resumed = true; refresh++ }
            if (event == Lifecycle.Event.ON_PAUSE) resumed = false
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val activeId = (state as? LiveShiftState.Active)?.stored?.shift?.id
    LaunchedEffect(resumed, activeId, refresh) {
        if (resumed && activeId != null) {
            failed = !tryStartShiftNotification {
                (context.applicationContext as WageApplication).shiftNotificationStarter(context)
            }
        }
    }
    val displayed = remember(resumed, refresh, activeId) { ShiftNotifications.canDisplay(context) }
    if (explain) AlertDialog(
        onDismissRequest = { explain = false },
        title = { Text("근무 중 알림") },
        text = {
            Column {
                Text("앱을 닫거나 화면을 꺼도 알림에서 예상 급여를 확인하고 휴게·퇴근을 처리할 수 있어요. 알림을 허용하지 않아도 근무 기록과 복구는 정상 동작해요.")
                TextButton(onClick = {
                    explain = false
                    context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                }) { Text("시스템 설정 열기") }
            }
        },
        confirmButton = { TextButton(onClick = {
            explain = false
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) request.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            }
        }) { Text("알림 설정") } },
        dismissButton = { TextButton(onClick = { explain = false }) { Text("나중에") } },
    )
    return NotificationControl(
        if (activeId == null) null else if (failed) "근무 알림을 시작하지 못했어요. 근무 기록은 저장되어 있어요."
        else if (!displayed) "알림이 표시되지 않을 수 있어요. 근무 기록은 계속 저장돼요." else null,
        { explain = true },
    )
}
