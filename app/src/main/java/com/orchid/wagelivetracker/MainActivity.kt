package com.orchid.wagelivetracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.orchid.wagelivetracker.ui.profile.WorkProfileSetupScreen
import com.orchid.wagelivetracker.ui.profile.WorkProfileSetupViewModel
import com.orchid.wagelivetracker.ui.theme.WageLiveTrackerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repository = (application as WageApplication).repository
        val viewModel = ViewModelProvider(this, WorkProfileSetupViewModel.Factory(repository))[WorkProfileSetupViewModel::class.java]
        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            WageLiveTrackerTheme {
                WorkProfileSetupScreen(
                    state = state,
                    onWageChange = viewModel::changeWage,
                    onNicknameChange = viewModel::changeNickname,
                    onEmployeeCountChange = viewModel::chooseEmployeeCount,
                    onSave = viewModel::save,
                    onEdit = viewModel::editProfile,
                    onCancel = viewModel::cancelEdit,
                    onRetry = viewModel::retryLoad,
                )
            }
        }
    }
}
