package com.vocab5000.feature.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vocab5000.core.designsystem.InfoCard
import com.vocab5000.core.designsystem.LoadingState
import com.vocab5000.core.designsystem.PrimaryAction
import com.vocab5000.core.designsystem.ScreenTitle
import com.vocab5000.core.designsystem.SecondaryAction
import com.vocab5000.core.designsystem.StatTile
import com.vocab5000.core.model.SessionMode
import com.vocab5000.core.settings.UserPreferences

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TodayScreen(
    viewModel: TodayViewModel,
    clockChanged: Boolean,
    pendingAssessment: Boolean,
    onStart: (SessionMode?) -> Unit,
    onAssessment: () -> Unit,
    onOpenPremium: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmNewWithBacklog by remember { mutableStateOf(false) }
    var editGoal by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenTitle("Today")
        if (!state.loaded) {
            LoadingState()
            return@Column
        }
        if (clockChanged) {
            InfoCard { Text("Your device clock changed recently, so the number of due reviews may have shifted.") }
        }
        if (pendingAssessment) {
            InfoCard {
                Text("You chose to check what you already know.")
                SecondaryAction("Take the short assessment", onClick = onAssessment)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("reviews due", state.dueCount.toString())
            StatTile("new words left today", state.newAllowance.toString(), detail = "goal ${state.dailyGoal}")
            StatTile("day streak", state.streak.toString())
        }
        Text(
            "Today: ${state.introducedToday} new words · ${state.gradedToday} reviews" + if (state.goalMet) " · daily goal met" else "",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (state.hasActiveSession) {
            PrimaryAction("Continue session", onClick = { onStart(null) })
        } else if (state.dueCount > 0 || state.newAllowance > 0) {
            PrimaryAction("Start session", onClick = { onStart(SessionMode.MIXED) })
        } else {
            InfoCard { Text("You're all caught up. Come back later for reviews, or practise words you already know.") }
        }
        if (state.dueCount > 0) SecondaryAction("Reviews only", onClick = { onStart(SessionMode.REVIEW_ONLY) })
        if (state.newAllowance > 0) {
            SecondaryAction("Learn a few new words", onClick = {
                if (state.backlogWarning) confirmNewWithBacklog = true else onStart(SessionMode.LEARN_NEW)
            })
        }
        if (state.freeLimitReached) {
            InfoCard {
                Text("You've reached the end of the free words. Your progress, reviews, practice and backups stay free.")
                SecondaryAction("About Premium", onClick = onOpenPremium)
            }
        }
        if (state.backlogWarning) {
            InfoCard {
                Text("You have a large review backlog. Short batches help — nothing has been reset.")
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) {}) {
                    Text("Pause new words", Modifier.weight(1f))
                    Switch(checked = state.newWordsPaused, onCheckedChange = viewModel::setPaused)
                }
            }
        } else if (state.newWordsPaused) {
            InfoCard {
                Text("New words are paused.")
                SecondaryAction("Resume new words", onClick = { viewModel.setPaused(false) })
            }
        }
        TextButton(onClick = { editGoal = true }) { Text("Change daily goal (${state.dailyGoal} new words)") }
        Text("Reviews are extra and can be done in short sessions.", style = MaterialTheme.typography.bodySmall)
    }

    if (confirmNewWithBacklog) {
        AlertDialog(
            onDismissRequest = { confirmNewWithBacklog = false },
            title = { Text("Add new words?") },
            text = { Text("You have ${state.dueCount} reviews due. New words will add to that backlog. You can still learn a few if you like.") },
            confirmButton = { TextButton(onClick = { confirmNewWithBacklog = false; onStart(SessionMode.LEARN_NEW) }) { Text("Learn new words") } },
            dismissButton = { TextButton(onClick = { confirmNewWithBacklog = false; onStart(SessionMode.REVIEW_ONLY) }) { Text("Review first") } },
        )
    }
    if (editGoal) {
        AlertDialog(
            onDismissRequest = { editGoal = false },
            title = { Text("New words per day") },
            text = {
                Column {
                    UserPreferences.GOAL_PRESETS.forEach { goal ->
                        TextButton(onClick = { viewModel.setGoal(goal); editGoal = false }) {
                            Text(if (goal == state.dailyGoal) "✓ $goal" else "$goal")
                        }
                    }
                    Text("More options in Settings.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { editGoal = false }) { Text("Close") } },
        )
    }
}
