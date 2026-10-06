package com.orchid.wagelivetracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.orchid.wagelivetracker.ui.profile.WorkProfileSetupScreen
import com.orchid.wagelivetracker.ui.profile.WorkProfileSetupViewModel
import com.orchid.wagelivetracker.ui.profile.WorkProfileSetupState
import com.orchid.wagelivetracker.ui.shift.LiveShiftViewModel
import com.orchid.wagelivetracker.ui.shift.LiveShiftState
import com.orchid.wagelivetracker.ui.shift.LiveShiftScreen
import com.orchid.wagelivetracker.ui.theme.WageLiveTrackerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repository = (application as WageApplication).repository
        val viewModel = ViewModelProvider(this, WorkProfileSetupViewModel.Factory(repository))[WorkProfileSetupViewModel::class.java]
        val live = ViewModelProvider(this, LiveShiftViewModel.Factory(repository, (application as WageApplication).shiftClock))[LiveShiftViewModel::class.java]
        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            val liveState by live.state.collectAsStateWithLifecycle()
            DisposableEffect(live) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_START) live.setForeground(true)
                    if (event == Lifecycle.Event.ON_STOP) live.setForeground(false)
                }
                lifecycle.addObserver(observer)
                live.setForeground(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
                onDispose { lifecycle.removeObserver(observer); live.setForeground(false) }
            }
            LaunchedEffect(state, liveState is LiveShiftState.SetupRequired) {
                if (state is WorkProfileSetupState.Ready && liveState is LiveShiftState.SetupRequired) live.reload()
            }
            WageLiveTrackerTheme {
                if (liveState is LiveShiftState.SetupRequired) WorkProfileSetupScreen(
                    state = state,
                    onWageChange = viewModel::changeWage,
                    onNicknameChange = viewModel::changeNickname,
                    onEmployeeCountChange = viewModel::chooseEmployeeCount,
                    onSave = viewModel::save,
                    onEdit = viewModel::editProfile,
                    onCancel = viewModel::cancelEdit,
                    onRetry = viewModel::retryLoad,
                )
                else LiveShiftScreen(
                    state = liveState, onStart = live::startShift, onStartBreak = live::startBreak,
                    onEndBreak = live::endBreak, onFinish = live::finishShift,
                    onConfirm = live::dismissSummary, onRetry = live::reload,
                    onEdit = { viewModel.editProfile(); live.openSetup() },
                )
            }
        }
    }
}
