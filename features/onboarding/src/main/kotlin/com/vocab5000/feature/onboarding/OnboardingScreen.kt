package com.vocab5000.feature.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vocab5000.core.designsystem.OptionCard
import com.vocab5000.core.designsystem.PrimaryAction
import com.vocab5000.core.designsystem.PromptNote
import com.vocab5000.core.designsystem.SecondaryAction
import com.vocab5000.core.designsystem.Wordmark
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

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Wordmark()
        when (step) {
            OnboardingStep.WELCOME, OnboardingStep.DONE -> {
                Text("Hallo! 👋", style = MaterialTheme.typography.headlineLarge)
                Text("Learn useful German words with examples and spaced review.", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Works offline. No account needed.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                PrimaryAction("Get started", onClick = { go(OnboardingStep.START_CHOICE) })
            }
            OnboardingStep.START_CHOICE -> {
                Text("Where would you like to start?", style = MaterialTheme.typography.headlineLarge)
                Text(
                    "Both paths use the same word list, in order of usefulness.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OptionCard(
                        title = "Start from the beginning",
                        detail = "The most useful words first, in rank order",
                        selected = false,
                        onClick = {
                            scope.launch {
                                preferences.setStartChoice(StartChoice.BEGINNING)
                                preferences.setOnboardingStep(OnboardingStep.GOAL)
                            }
                        },
                    )
                    OptionCard(
                        title = "Check what I know",
                        detail = "About 30 questions, no timer · suggests where to begin",
                        selected = false,
                        onClick = {
                            scope.launch {
                                preferences.setStartChoice(StartChoice.ASSESSMENT)
                                preferences.setOnboardingStep(OnboardingStep.GOAL)
                            }
                        },
                    )
                }
                PromptNote("The check is optional — you can take it later from Study.")
            }
            OnboardingStep.GOAL -> {
                Text("How many new words a day?", style = MaterialTheme.typography.headlineLarge)
                Text(
                    "Reviews of earlier words come on top and can be done in short sessions.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    UserPreferences.GOAL_PRESETS.forEach { goal ->
                        OptionCard(
                            title = if (goal == UserPreferences.DEFAULT_DAILY_GOAL) "$goal words (recommended)" else "$goal words",
                            detail = "${goal * 7} a week · ${goal * 30} a month",
                            selected = false,
                            onClick = {
                                scope.launch {
                                    preferences.setDailyGoal(goal)
                                    preferences.setOnboardingStep(OnboardingStep.REMINDERS)
                                }
                            },
                        )
                    }
                }
                PromptNote("You can choose any number from 0 to 30 later in Settings.")
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
                Text("Your progress stays on this device", style = MaterialTheme.typography.headlineLarge)
                Text("Progress is stored on this device. Export a backup to keep a copy — you'll find it in Settings.", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "German5000 has no account and sends no learning data anywhere.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
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
    Text("Daily reminder?", style = MaterialTheme.typography.headlineLarge)
    Text(
        "One gentle reminder a day, only when words are ready. Off by default.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Time", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text("%02d:00".format(hour), style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryAction("Earlier", onClick = { hour = (hour + 23) % 24 }, modifier = Modifier.weight(1f))
                SecondaryAction("Later", onClick = { hour = (hour + 1) % 24 }, modifier = Modifier.weight(1f))
            }
        }
    }
    Spacer(Modifier.height(8.dp))
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
