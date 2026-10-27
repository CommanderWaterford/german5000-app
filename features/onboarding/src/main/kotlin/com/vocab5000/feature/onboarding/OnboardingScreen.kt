package com.vocab5000.feature.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vocab5000.core.designsystem.PrimaryAction
import com.vocab5000.core.designsystem.ScreenTitle
import com.vocab5000.core.designsystem.SecondaryAction
import com.vocab5000.core.settings.OnboardingStep
import com.vocab5000.core.settings.PreferencesRepository
import com.vocab5000.core.settings.StartChoice
import com.vocab5000.core.settings.UserPreferences
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalTime

/**
 * First launch (spec §3.1). Every step is persisted; nothing here needs network,
 * login, billing or a permission. Notification permission is requested only after opt-in.
 */
@Composable
fun OnboardingScreen(
    step: OnboardingStep,
    preferences: PreferencesRepository,
    onRemindersChanged: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    fun go(next: OnboardingStep) = scope.launch { preferences.setOnboardingStep(next) }
    val previous = mapOf(
        OnboardingStep.START_CHOICE to OnboardingStep.WELCOME,
        OnboardingStep.GOAL to OnboardingStep.START_CHOICE,
        OnboardingStep.REMINDERS to OnboardingStep.GOAL,
        OnboardingStep.STORAGE_NOTE to OnboardingStep.REMINDERS,
    )
    BackHandler(enabled = step in previous) { go(previous.getValue(step)) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        when (step) {
            OnboardingStep.WELCOME, OnboardingStep.DONE -> {
                ScreenTitle("German5000")
                Text("Learn useful German words with examples and spaced review.", style = MaterialTheme.typography.titleLarge)
                Text("Works offline. No account needed.", style = MaterialTheme.typography.bodyLarge)
                PrimaryAction("Get started", onClick = { go(OnboardingStep.START_CHOICE) })
            }
            OnboardingStep.START_CHOICE -> {
                ScreenTitle("Where would you like to start?")
                PrimaryAction("Start from the beginning", onClick = {
                    scope.launch {
                        preferences.setStartChoice(StartChoice.BEGINNING)
                        preferences.setOnboardingStep(OnboardingStep.GOAL)
                    }
                })
                SecondaryAction("Check what I know (about 30 questions)", onClick = {
                    scope.launch {
                        preferences.setStartChoice(StartChoice.ASSESSMENT)
                        preferences.setOnboardingStep(OnboardingStep.GOAL)
                    }
                })
                Text("The check is optional — you can take it later from Study.", style = MaterialTheme.typography.bodySmall)
            }
            OnboardingStep.GOAL -> {
                ScreenTitle("How many new words a day?")
                Text("Reviews of earlier words come on top and can be done in short sessions.")
                UserPreferences.GOAL_PRESETS.forEach { goal ->
                    SecondaryAction(if (goal == UserPreferences.DEFAULT_DAILY_GOAL) "$goal words (recommended)" else "$goal words", onClick = {
                        scope.launch {
                            preferences.setDailyGoal(goal)
                            preferences.setOnboardingStep(OnboardingStep.REMINDERS)
                        }
                    })
                }
                Text("You can choose any number from 0 to 30 later in Settings.", style = MaterialTheme.typography.bodySmall)
            }
            OnboardingStep.REMINDERS -> ReminderStep(
                onEnable = { time ->
                    scope.launch {
                        preferences.setReminder(true, time, DayOfWeek.entries.toSet())
                        onRemindersChanged()
                        preferences.setOnboardingStep(OnboardingStep.STORAGE_NOTE)
                    }
                },
                onSkip = { go(OnboardingStep.STORAGE_NOTE) },
            )
            OnboardingStep.STORAGE_NOTE -> {
                ScreenTitle("Your progress stays on this device")
                Text("Progress is stored on this device. Export a backup to keep a copy — you'll find it in Settings.")
                Text("German5000 has no account and sends no learning data anywhere.", style = MaterialTheme.typography.bodyMedium)
                PrimaryAction("Go to Today", onClick = { go(OnboardingStep.DONE) })
            }
        }
    }
}

@Composable
private fun ReminderStep(onEnable: (LocalTime) -> Unit, onSkip: () -> Unit) {
    var hour by remember { mutableIntStateOf(19) }
    var asked by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // Denial is respected: reminders stay off, learning continues, and we don't nag.
        if (granted) onEnable(LocalTime.of(hour, 0)) else onSkip()
    }
    ScreenTitle("Daily reminder?")
    Text("One gentle reminder a day, only when words are ready. Off by default.")
    Text("Time: %02d:00".format(hour), style = MaterialTheme.typography.titleMedium)
    SecondaryAction("Earlier", onClick = { hour = (hour + 23) % 24 })
    SecondaryAction("Later", onClick = { hour = (hour + 1) % 24 })
    PrimaryAction("Remind me", enabled = !asked, onClick = {
        asked = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            onEnable(LocalTime.of(hour, 0))
        }
    })
    SecondaryAction("Not now", onClick = onSkip)
}
